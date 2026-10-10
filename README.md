# Akatsuki Trading Platform

*Trade from the shadows, profit in the spotlight.*

A full-stack equities trading platform. Traders register, get approved, and place
market and limit orders. They watch orders fill on a live blotter, follow
instruments on watchlists with candlestick charts, get indicator-based trade
advice, arm automated price targets, and receive notifications in-app, by email
or both. Administrators approve customers, fund and suspend accounts, and
oversee every order on the platform.

| Document | What it covers |
|---|---|
| **README.md** (this file) | The whole application: features, architecture, configuration, running, testing |
| [RUNNING.md](RUNNING.md) | Step-by-step start-up, terminal by terminal |
| [PROJECT_GUIDE.md](PROJECT_GUIDE.md) | Deep dive: request flows, every file, the Angular patterns used |
| [Frontend/README.md](Frontend/README.md) | UI client generation, end-to-end journeys, bundle scan |
| [CHATBOT_IMPLEMENTATION.md](CHATBOT_IMPLEMENTATION.md) | The trading assistant |
| [decision-log/](decision-log/) | Why things are the way they are |

---

## Contents

1. [Features](#1-features)
2. [Architecture](#2-architecture)
3. [Configuration](#3-configuration)
4. [Running it](#4-running-it)
5. [How the main flows work](#5-how-the-main-flows-work)
6. [API surface](#6-api-surface)
7. [Database](#7-database)
8. [Testing, results and coverage](#8-testing-results-and-coverage)
9. [Repository layout](#9-repository-layout)
10. [Troubleshooting](#10-troubleshooting)

---

## 1. Features

### For traders

| Page | What it does |
|---|---|
| **Sign in / Register** | Email-verified registration (six-digit code). Accounts start inactive until an administrator approves them. **Forgot password** emails a code and sets a new password in two steps. |
| **Dashboard** | Total equity, cash, positions value and order flow, with values that resize to fit their card. An allocation donut, the top holdings, recent orders and a compact watchlist carousel. |
| **Order ticket** | Market or limit, buy or sell. A live summary shows the price, FX rate, total, and cash and holding before and after. Market orders are priced by the server; limit orders wait for the market poller. |
| **Blotter** | Every order with its type (Market / Limit). A limit price is shown only for limit orders, plus the executed price. Status colours: **filled green**, rejected red, working amber, cancelled grey. A rejected row explains the executor's reason. |
| **Portfolio** | Holdings valued at the latest quote, converted to the account currency. Cost basis, market value, unrealised P&L and percentage, and realised P&L. |
| **Watchlist** | Several named lists shown as a card carousel. Each instrument opens an inline chart with timeline ranges. Confirmations clear themselves after a few seconds. |
| **Price alerts** | Notify when an instrument crosses a price. |
| **Trade advice** | Buy / sell / hold from five standard indicators, with every formula shown. |
| **Automated trading** | **Price targets**: instrument, buy or sell, quantity and a target price in your display currency. When the market poller's quote reaches the target, one market order is placed and the target is marked done. Double clicks are safe because creation is idempotent. |
| **Notifications** | Inbox of everything sent. In-app toasts for in-app deliveries. |
| **Settings** | Delivery by default and per category (account, order, price): **In-app, Email or Both**. Display currency, **theme saved to your account**, order-ticket defaults and chart defaults. |
| **Assistant** | A chat assistant for platform questions (bottom-right). |

The display currency is used everywhere: dashboard, portfolio, order ticket and
automated trading. Each account keeps a **ledger currency** fixed at
registration from the country (INR for India, USD for the US, and so on).
Orders settle in the ledger currency; the display currency is only how amounts
are shown.

The top bar's clock shows the time in the customer's country. The navigation
rail **collapses** to icons, and the choice is remembered on the device.

### For administrators

Overview statistics, the approval queue, users (approve, reject, suspend,
reinstate), accounts (fund with a recorded reason, change status), instruments
and every order on the platform. Sign-out asks for confirmation.

---

## 2. Architecture

```
                 ┌──────────────────────────────┐
  Browser ─────► │  Trading UI  (Angular 21)    │  :4200
                 └──────┬─────────────────┬─────┘
       sign in, refresh │                 │ orders, portfolio, quotes, watchlists,
       password reset   │                 │ advice, strategies, preferences, admin
                 ┌──────▼──────┐   ┌──────▼────────────────────┐
                 │ Auth service│   │ Trade REST API            │
                 │ NestJS :3000│   │ order-service  Spring :8081│◄── strategy sweeper (15 s)
                 └──────┬──────┘   └──────┬───────────▲────────┘
                        │                 │ "orders"  │ "trade-events", "market-data"
                        │          ┌──────▼───────────┴────────┐
                        │          │ Kafka                     │
                        │          └──────┬───────────▲────────┘
                        │          ┌──────▼───────────┴────────┐       Fauxnance
                        │          │ Trade Executor  Spring    │ ────► market data
                        │          │ :8082  + market poller    │       (batches of 25)
                        │          └──────┬────────────────────┘
                 ┌──────▼─────────────────▼──────┐    ┌──────────────────────┐
                 │ PostgreSQL  trading_system_db │──► │ ETL → DuckDB (star)  │
                 │ schemas: auth, trading        │    └──────────────────────┘
                 └───────────────────────────────┘
```

| Component | Tech | Folder | Port | Responsibility |
|---|---|---|---|---|
| Trading UI | Angular 21, signals, standalone components | `Frontend/` | 4200 | Every trader and admin screen |
| Auth service | NestJS 10 | `Services/auth-service/` | 3000 | Registration + OTP, sign-in, JWT, refresh cookie, password reset |
| Trade REST API | Spring Boot 3, MyBatis | `Services/order-service/` | 8081 | Orders, accounts, portfolio, quotes, watchlists, alerts, advice, strategies, preferences, notifications, admin |
| Trade Executor | Spring Boot 3 | `Services/executor-service/` | 8082 | Fills market orders, polls the market for limit orders, end of day |
| PostgreSQL | 16+ | `Databases/PostgreSQL/` | 5432 | System of record |
| Kafka | Apache Kafka (KRaft) | `Infrastructure/Kafka/` | 9092 / 29092 | Orders, trade events, market data |
| ETL | Python | `ETL Layer/` | — | PostgreSQL → DuckDB star schema for analytics |

**Execution is asynchronous.** `POST /api/v1/orders` answers with the order at
`NEW`. The executor decides later whether it fills or is rejected, so the UI
never treats the POST answer as the outcome.

**The market-data key never reaches the browser.** Every quote and candle goes
through the Trade REST API.

---

## 3. Configuration

### Shared hosts and ports: `Config/application.yml`

The platform's hosts and ports are written down once, in
[`Config/application.yml`](Config/application.yml):

```yaml
frontend:  { url: http://localhost, port: 4200 }
services:
  auth:     { url: http://localhost, port: 3000 }
  order:    { url: http://localhost, port: 8081 }
  executor: { url: http://localhost, port: 8082 }
database:  { host: localhost, port: 5432, name: trading_system_db, user: postgres }
kafka:     { host: kafka, port: 29092 }
# plus the external API URLs (market-data, llm, email) and platform parameters
```

| Service | How it reads the file |
|---|---|
| order-service, executor-service | `spring.config.import` in their `application.yml`: every host, port, URL and topic comes from `.env` or this file |
| auth-service | `src/config/shared-config.ts`: port, database, JWT and CORS settings |
| Frontend | `scripts/sync-config.mjs` generates `src/environments/*.ts` from `.env`/Config before every start, build and test |

**Precedence, highest first:** real environment variables, then `Application/.env`,
then `Config/application.yml`. No service has built-in hosts or ports. Point
`SHARED_CONFIG` at another file (for example `application.staging.yml`) to switch
environments without editing any service.

`kafka.host: kafka` / `29092` is the broker as the containers see it on the
compose network. A service started on the host machine uses the advertised
listener instead, which `.env` sets with `KAFKA_BOOTSTRAP_SERVERS` (for example
`10.8.68.169:9092`).

### Secrets: `Application/.env`

Copy `.env.example` to `.env`. It is git-ignored; never commit it. Both files list the
same variables; `node scripts/check-deploy.mjs` verifies that and the whole deployment
setup. See [DEPLOYMENT.md](DEPLOYMENT.md) for the full deployment guide.

| Variable | Needed by | Notes |
|---|---|---|
| `DB_PASSWORD` | all services | No default anywhere |
| `JWT_SECRET` | auth, order-service | ≥ 32 bytes, identical in both |
| `KAFKA_BOOTSTRAP_SERVERS` | order, executor | e.g. `10.8.68.169:9092` |
| `FAUXNANCE_API_KEY` | executor, order-service | Market data; without it market orders answer `MKT-503` |
| `SMTP_HOST`, `SMTP_USER`, `SMTP_PASS`, `SMTP_FROM` | auth, order-service | Leave `SMTP_HOST` empty locally: codes are logged instead of emailed |
| `GROQ_API_KEY` | order-service | The assistant |

### Tunables (order-service `application.yml`)

| Property | Default | Meaning |
|---|---|---|
| `strategy.sweep.interval-ms` | 15000 | How often active price targets are checked against the latest stored quote |
| `strategy.sweep.enabled` | true | Turn the sweeper off (tests do) |
| `fx.rates.*` | reference rates | Currency conversion, the same in the UI |

The executor's poller (`POLL_INTERVAL_SECONDS`, Config `market-data.poll-interval-seconds`: 60) fetches quotes in
batches of 25 symbols per Fauxnance call.

---

## 4. Running it

### Everything on Docker

```bash
cp .env.example .env              # fill in DB_PASSWORD, JWT_SECRET, FAUXNANCE_API_KEY
docker compose up --build
```

Open http://localhost:4200 and sign in as `demo` / `Capstone@2026` (trader) or
`admin` / `Admin@2026`.

### On the host, one terminal each

```bash
# Database, first time
psql -U postgres -d trading_system_db -f Databases/PostgreSQL/schema.sql
psql -U postgres -d trading_system_db -f Databases/PostgreSQL/seed_data.sql

# Kafka topics, once per broker
KAFKA_BOOTSTRAP_SERVERS=10.8.68.169:9092 Infrastructure/Kafka/scripts/create-topics.sh

cd Services/auth-service     && npm ci && npm run start:dev   # :3000
cd Services/order-service    && mvn spring-boot:run           # :8081
cd Services/executor-service && mvn spring-boot:run           # :8082
cd Frontend                  && npm ci && npm start           # :4200
```

### Upgrading an existing database

Run the migrations you have not applied yet, in order, from
`Databases/PostgreSQL/migrations/`. Each one is safe to run twice. The latest:

| Migration | Adds |
|---|---|
| `009_market_data_cache.sql` | Stored quotes and candles from the poller |
| `010_automated_strategies.sql` | Automated trading (price targets) and their executions |
| `011_preferences_v2.sql` | In-app / email / both delivery (SMS retired → in-app), three notification categories, theme in the customer preference |

```bash
psql -U postgres -d trading_system_db -f Databases/PostgreSQL/migrations/011_preferences_v2.sql
```

See [RUNNING.md](RUNNING.md) for health checks and the full walk-through.

---

## 5. How the main flows work

### Orders

```
Order ticket ──POST /api/v1/orders──► order-service
   validates: idempotency key unused, account ACTIVE and the token's own, instrument tradeable
   MARKET: priced from a fresh quote, converted to the account currency
   saved as NEW ──► Kafka "orders"
                         │
                  executor-service
   MARKET  → filled at once at the ask (buy) / bid (sell), or rejected
             (INSUFFICIENT_FUNDS / INSUFFICIENT_HOLDINGS)
   LIMIT   → left NEW; the market poller re-prices it each cycle and fills it
             once ask ≤ limit (buy) or bid ≥ limit (sell)
   End of day (20:00 UTC, Mon–Fri) rejects what is still NEW with MARKET_CLOSED
                         │
                  "trade-events" ──► notifications, blotter
```

Quotes are converted to the account's ledger currency **before** the fill rule
compares them. Executed prices and average costs are therefore always in the
ledger currency.

### Automated trading (price targets)

1. The trader picks an instrument, buy or sell, a quantity and a target price
   (typed in the display currency, stored in the instrument's currency).
2. `POST /api/v1/strategies/{accountId}` with `strategyType: PRICE_TARGET`. The
   order type is always MARKET. Posting the same target twice returns the one
   already armed instead of creating a second.
3. The target is checked straight away, then every 15 s by the
   **StrategyQuoteSweeper** against the latest quote the poller stored. Kafka
   `market-data` events trigger the same check.
4. The first time the price reaches the target (buy ≤ target, sell ≥ target), one
   market order is placed with the idempotency key `strategy-<id>-<symbol@quoteTime>`,
   and the target becomes `COMPLETED`.

### Portfolio valuation

| Figure | Formula |
|---|---|
| Last price | Latest quote converted from the instrument's currency to the account currency |
| Cost basis | Σ average cost × quantity (average cost is already in the account currency) |
| Market value | Σ last price × quantity |
| Unrealised P&L | market value − cost basis, and ÷ cost basis for the percentage |
| Total equity | cash + market value |

Trade advice uses the same converted prices, so its "unrealised" figures agree
with the portfolio page.

### Notifications and preferences

* **Delivery channels:** `PUSH` (in-app), `EMAIL`, `BOTH`, or `NONE`. Only email
  and both need an address.
* **Categories:** `ACCOUNT_RELATED`, `ORDER_RELATED` and `PRICE_RELATED`. Every
  notification type belongs to one (`ORDER_*` → order, `PRICE_*` → price,
  otherwise account). A category set to `INHERIT` follows the account-wide
  default.
* **Theme:** saved with the customer preference
  (`PUT /api/v1/preferences/{id}/theme`) and applied at sign-in. The browser
  stores no theme of its own.

### Sessions

The auth service issues a 15-minute access token (JWT, in the response body) and
a refresh token in an `HttpOnly` cookie. The UI's interceptor adds the bearer
token. On a 401 it refreshes once, sharing the refresh across concurrent calls,
then retries. If the refresh is refused, the session ends and the user returns
to the sign-in page.

---

## 6. API surface

Auth service (`:3000`):

| Method | Path | Purpose |
|---|---|---|
| POST | `/auth/register`, `/auth/register/verify`, `/auth/register/resend` | Registration with an emailed code |
| POST | `/auth/login`, `/auth/refresh`, `/auth/logout` | Session |
| POST | `/auth/password-reset/request`, `/auth/password-reset/reset` | Forgot password |
| GET / PATCH | `/auth/me` | Profile |
| POST | `/auth/me/password` | Change password while signed in |

Trade REST API (`:8081`, all under `/api/v1` except `/health`):

| Area | Endpoints |
|---|---|
| Orders | `POST /orders`, `DELETE /orders/{id}`, `GET /accounts/{id}/orders` |
| Accounts | `GET /accounts/{id}`, `/balance`, `/positions` |
| Portfolio | `GET /portfolio/{accountId}`, `/positions`, `/pnl` |
| Market data | `GET /instruments/{symbol}/quote`, `/candles` |
| Watchlists | `GET/POST /watchlists`, `PATCH/DELETE /watchlists/{id}`, instruments add / move / remove |
| Price alerts | `GET/POST /watchlist/alerts`, `DELETE /watchlist/alerts/{id}` |
| Advice | `GET /advice/{accountId}`, `/advice/{accountId}/{symbol}`, `/advice/methodology` |
| Automated trading | `GET/POST /strategies/{accountId}`, `PATCH/DELETE /strategies/{accountId}/{id}`, `/preview`, `/executions` |
| Preferences | `GET/PUT /preferences/{accountId}`, `PUT /preferences/{accountId}/theme`, `GET/PUT /preferences/notifications/{accountId}` |
| Notifications | `GET /notifications/{accountId}`, `POST /notifications/{accountId}/{id}/read` |
| Assistant | `POST /chatbot/chat` |
| Admin | `GET /admin/stats`, `/users`, `/users/pending`, `/accounts`, `/orders`, `/instruments`; `POST` approve / reject / suspend / reinstate, balance, status |

The OpenAPI contracts are in `Contracts/API Schemas/`. Errors use one envelope,
`{errorCode, message}`, with codes such as `AUTH-401`, `ACC-403`, `ORD-409`,
`VAL-422` and `MKT-503`.

---

## 7. Database

PostgreSQL `trading_system_db` has two schemas:

* `auth`: users, refresh tokens, registration and password-reset challenges.
* `trading`: accounts, instruments, orders, holdings, cash adjustments, watchlists
  and their instruments, price alerts, notifications, customer and notification
  preferences, the market-quote and candle cache, and trading strategies with
  their executions.

`schema.sql` builds a fresh database and never drops anything. `seed_data.sql`
adds the demo trader, the admin and three applicants waiting for approval.
`reset.sql` starts over deliberately.

---

## 8. Testing, results and coverage

| Suite | Framework | Tests | Command |
|---|---|---|---|
| order-service | JUnit 5, Mockito, H2 (PostgreSQL mode) | 457 | `mvn test` |
| executor-service | JUnit 5 | 29 | `mvn test` |
| auth-service | Jest | 67 | `npx jest --coverage` |
| Frontend | Jasmine / Karma | 450 | `npx ng test --watch=false --code-coverage` |

Related tests are grouped: JUnit `@Nested` classes and nested Jasmine `describe`
blocks (for example *Automated trading › price target*,
*Login › forgot password*).

### One command, one page

```bash
./scripts/run-all-tests.sh            # every suite, then the report
./scripts/run-all-tests.sh frontend   # just some: order, executor, auth, frontend
node scripts/test-report.mjs          # rebuild the page from the last runs
```

This writes **`reports/test-report.html`**. It shows the totals, each suite's
pass/fail/skip counts and timings, every test grouped by suite (failures open
with their message), and coverage bars with a link to each detailed coverage
report:

| Suite | Detailed coverage report |
|---|---|
| order-service | `Services/order-service/target/site/jacoco/index.html` (JaCoCo) |
| executor-service | `Services/executor-service/target/site/jacoco/index.html` (JaCoCo) |
| auth-service | `Services/auth-service/coverage/lcov-report/index.html` (Istanbul) |
| Frontend | `Frontend/coverage/trading-app-ui/index.html` (Istanbul) |

The Frontend build **fails** below its coverage gates: 90% statements, 80%
branches, 85% functions, 90% lines (`karma.conf.js`). At the last run it stood at
95.2 / 81.3 / 93.4 / 95.5.

End-to-end journeys (Playwright) live in `Frontend/e2e/`. See
[Frontend/README.md](Frontend/README.md).

### The six extensions, one package each

In the order-service (`Services/order-service/src/main/java/com/trading/tradeapi/`),
every extension is self-contained, with its own `controller`, `dto`, `entity`,
`mapper` and `service` sub-packages:

| Extension | Package |
|---|---|
| Preferences | `preferences` |
| Watchlist and price alerts | `watchlist` (alerts in `watchlist.alerts`) |
| Portfolio | `portfolio` |
| Automated trading | `strategy` |
| Trade advice | `advice` |
| In-app notifications | `notifications` |

The core trading code (orders, accounts, instruments) stays in the top-level
`controller`, `service`, `dto`, `entity` and `mapper` packages. Market data shared by
the extensions lives in `marketdata`, the assistant in `chatbot`, and the admin API in
`admin`. The tests follow the same packages.

---

## 9. Repository layout

```
Application/
├── README.md                  this file
├── DEPLOYMENT.md              clone → configure → build → deploy
├── Config/application.yml     shared hosts, ports, URLs and parameters
├── .env.example               secrets template (.env is git-ignored)
├── docker-compose.yml         the whole platform on Docker
├── scripts/                   check-deploy.mjs, run-all-tests.sh, test-report.mjs
├── reports/                   generated test-report.html (git-ignored)
├── Contracts/                 API Schemas/ and Analytics Schemas/
├── Databases/PostgreSQL/      schema.sql, seed_data.sql, reset.sql, migrations/
├── Infrastructure/Kafka/      broker compose file and create-topics.sh
├── Services/
│   ├── auth-service/          NestJS
│   ├── order-service/         Spring Boot: the Trade REST API
│   └── executor-service/      Spring Boot: fills, market poller, end of day
├── ETL Layer/                 PostgreSQL → DuckDB
├── Frontend/                  Angular 21 trading UI
└── decision-log/              architecture decisions
```

---

## 10. Troubleshooting

| Symptom | Likely cause |
|---|---|
| Orders stay `NEW` forever | The executor is not consuming. Check its log for a Kafka error, and that the broker advertises a reachable host. A limit order stays `NEW` until the price reaches it; that is expected. |
| A price target never trades | The order-service must be running (the sweeper lives there), the poller must have stored a quote for the symbol, and the target must actually be reachable. A buy fires at or below the target, a sell at or above. |
| `maxPosition must not be null` when arming a price target | An order-service built before price targets were simplified is still running. Rebuild and restart it. |
| Saving preferences fails with a category error | Migration `011_preferences_v2.sql` has not been applied. |
| Market orders refused with `MKT-503` | `FAUXNANCE_API_KEY` is missing from the order-service environment. |
| Sign-in refused with `AUTH-403` right after registering | The account is waiting for an administrator's approval. |
| No registration or reset email | `SMTP_HOST` is empty, so the code is written to the auth-service log. |
