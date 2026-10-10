# Trade REST API (order-service)

Spring Boot service exposing the order, account, instrument and admin routes in
`contracts/trade-api.yaml`. It authenticates with the Auth service's JWT, prices
MARKET orders from live Fauxnance quotes, and publishes `ORDER_PLACED` to Kafka.

## Prerequisites

- Java 21+
- Maven 3.9+
- PostgreSQL (`trading_system_db`, schemas `auth` + `trading`)
- Auth service running on `3000` if you want tokens to verify
- Fauxnance API key for MARKET orders
- Kafka broker reachable at `KAFKA_BOOTSTRAP_SERVERS`

## Build and test

```bash
mvn clean verify
```

`src/test/java/com/trading/tradeapi/` holds controller slices, domain/service unit
tests, the admin approval state machine, currency conversion, quote pricing, and
the exception handler.

## Run

```bash
mvn spring-boot:run
```

Serves `http://localhost:8081`. Health: `GET http://localhost:8081/health`.

`application.yml` optionally imports `.env` from the service directory or its
parents, so `mvn spring-boot:run` picks up the team file regardless of the
working directory. Real environment variables always win, and the imports are
`optional:` so containers that pass env vars directly still start.

## Structure

```
src/main/java/com/trading/
├── domain/                  ← shared domain (entities, validation)
└── tradeapi/
    ├── controller/          ← Order, Account, Instrument, Health HTTP layer
    ├── admin/               ← AdminController + AdminService lifecycle
    ├── service/             ← order placement, quotes, FX, holdings
    ├── mapper/              ← MyBatis interfaces
    ├── dto/                 ← request/response records
    ├── entity/              ← DB row POJOs
    ├── security/            ← JwtAuthenticationFilter, JwtService
    └── exception/           ← GlobalExceptionHandler + typed exceptions
src/main/resources/
├── application.yml
└── mapper/                  ← MyBatis XML mappers (all #{} parameterised)
└── Dockerfile               ← multi-stage build
```

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8081` | HTTP port |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5432` / `trading_system_db` | PostgreSQL, `currentSchema=trading` |
| `DB_USER` / `DB_PASSWORD` | `postgres` / **required** | PostgreSQL credentials |
| `JWT_SECRET` | **required**, 32+ bytes | HS256 key; must equal the Auth service's. The service refuses to start without it |
| `JWT_ISSUER` | `auth-service` | Issuer the Trade API requires |
| `KAFKA_BOOTSTRAP_SERVERS` | `10.8.68.169:9092` | Broker for `orders` and `trade-events` (cancellations) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200,http://127.0.0.1:4200` | Browser origins allowed to call the API |
| `FAUXNANCE_API_KEY` | *(empty)* | Quote access. **Empty means MARKET orders fail with `MKT-503`** rather than falling back to a stored price |
| `FAUXNANCE_BASE_URL` | Fauxnance prod endpoint | Override for a mock quote server |
| `FX_RATES_<CCY>` | see `application.yml` | Units of the currency per 1 USD |

## Routes

All `/api/v1/**` routes require `Authorization: Bearer <JWT>`.

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/api/v1/orders` | Places an order. MARKET needs no price; LIMIT requires one |
| `DELETE` | `/api/v1/orders/{id}` | Cancels, only while still open |
| `GET` | `/api/v1/accounts/{id}` | Account detail |
| `GET` | `/api/v1/accounts/{id}/balance` | Cash and buying power |
| `GET` | `/api/v1/accounts/{id}/positions` | Open holdings |
| `GET` | `/api/v1/accounts/{id}/orders` | Order history |
| `POST` | `/api/v1/accounts` | Account creation |
| `DELETE` | `/api/v1/accounts/{id}` | Account closure |
| `GET` | `/api/v1/instruments/{symbol}/quote` | Live bid/ask with a freshness flag |
| `GET` | `/api/v1/admin/stats` | Counts for the admin overview |
| `GET` | `/api/v1/admin/users`, `/users/pending` | Approval queue |
| `POST` | `/api/v1/admin/users/{id}/approve` | `INACTIVE` → `ACTIVE`; the balance stays at zero |
| `POST` | `/api/v1/admin/users/{id}/reject` | `INACTIVE` → `REJECTED` |
| `POST` | `/api/v1/admin/users/{id}/suspend` | `ACTIVE` → `SUSPENDED` |
| `POST` | `/api/v1/admin/users/{id}/reinstate` | `SUSPENDED` → `ACTIVE`, balance preserved |
| `GET` | `/api/v1/admin/accounts`, `/orders`, `/instruments` | Admin listings |
| `POST` | `/api/v1/admin/accounts/{id}/status`, `/balance` | Admin account maintenance |
| `POST` | `/api/v1/admin/instruments`, `/instruments/{id}/status` | Instrument administration |

Admin routes additionally require an `ADMIN` role; a customer token receives
`AUTH-403` "Administrator privileges are required". An admin token may carry no
`accountId`, because an administrator does not necessarily trade.

## Authentication

The token is signed with the same `JWT_SECRET` the Auth service uses, and its
`accountId` claim must match the account being accessed. Mismatch, or an account
that is not `ACTIVE`, returns `ACC-403`. A missing, malformed, expired or
wrongly signed token returns `ACC-401`.

## Pricing

- MARKET orders are priced server-side: **ask** for a BUY, **bid** for a SELL,
  with a mid fallback when only one side is quoted.
- LIMIT orders use the submitted price, never a quote.
- A live quote is re-denominated into the account's ledger currency with
  `amount * rate(target) / rate(source)`.
- A currency with no configured rate is refused with `FX-422` instead of being
  converted at `1.0`, so a missing rate can never decide an order.
- `FAUXNANCE_API_KEY` absent ⇒ `MKT-503`. The service starts fine and only
  fails when something tries to price.

## Lifecycle

Each transition is guarded on the source state, so a repeated or out-of-order
call is rejected rather than silently re-applied:

```
INACTIVE --approve--> ACTIVE
INACTIVE --reject---> REJECTED
ACTIVE          --suspend--> SUSPENDED
SUSPENDED       --reinstate-> ACTIVE
ACTIVE          --close----> CLOSED
```

Neither approval nor reinstatement moves money. A new account opens at zero and
is funded with `POST /api/v1/admin/accounts/{id}/balance`, which refuses to
overdraw and records every adjustment, its reason and the admin who made it in
`trading.balance_adjustment`.

## Error catalogue

| Code | HTTP | Meaning |
| --- | --- | --- |
| `API-400` | 400 | Malformed JSON, wrong type, or unparseable enum value |
| `API-404` | 404 | Unknown route or method |
| `ACC-401` | 401 | Missing, malformed, expired or wrongly signed token |
| `ACC-403` | 403 | Account not `ACTIVE`, token `accountId` mismatch, or missing admin role |
| `ACC-404` | 404 | Account not found |
| `AUTH-403` | 403 | Admin route reached without the `ADMIN` role |
| `INS-404` | 404 | Instrument not found or delisted |
| `ORD-400` | 400 | Insufficient funds (BUY) |
| `ORD-409` | 409 | Insufficient holdings, duplicate key, or not cancellable |
| `ORD-422` | 422 | Limit order without a price, or a market order that cannot be priced |
| `USR-404` | 404 | Admin route: user not found |
| `FX-422` | 422 | No FX rate for the requested currency |
| `MKT-503` | 503 | Fauxnance unreachable or API key absent |
| `VAL-422` | 422 | Field validation failed |
| `SRV-500` | 500 | Unexpected fault; the response carries a generic message and detail stays in the log |

Every error response has exactly this shape:

```json
{ "errorCode": "ORD-409", "message": "Insufficient holdings" }
```

`API-400` and `SRV-500` deliberately do not echo the offending value, so enum
names and framework messages never reach the browser.