# Trade Executor (executor-service)

Consumes `ORDER_PLACED` from Kafka, decides fills against live Fauxnance quotes,
settles them in PostgreSQL, and publishes the outcome back to Kafka.

## Prerequisites

- Java 21+
- Maven 3.9+
- PostgreSQL (`trading_system_db`, schema `trading`)
- Kafka reachable at `KAFKA_BOOTSTRAP_SERVERS`
- **`FAUXNANCE_API_KEY` is mandatory** — the service refuses to start without it,
  because a poller with no key can never price anything

## Build and test

```bash
mvn clean verify
```

`FillRuleEvaluatorTest` covers the fill rules and the stale-quote policy;
`CurrencyConverterTest` covers the FX arithmetic.

## Run

```bash
mvn spring-boot:run
```

Serves `http://localhost:8082`. Like the order service, `application.yml`
optionally imports `.env` from the service directory or its parents so the team
file is found from any working directory; real environment variables still win.

## Structure

```
src/main/java/com/trade/executor/
├── kafka/         ← OrderEventConsumer (orders topic), TradeEventPublisher (trade-events)
├── domain/        ← FillRuleEvaluator, FillDecision, Quote
├── service/       ← ExecutionService settlement, QuoteCache, CurrencyConverter
├── client/        ← FauxnanceClient
├── poller/        ← MarketDataPoller, retries pending orders on every refresh
├── scheduler/     ← EndOfDayTask
├── config/        ← KafkaConfig (error handler + DLT), RestTemplateConfig
└── exception/     ← RetryableOrderException, NonRetryableOrderException
```

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8082` | HTTP port |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5432` / `trading_system_db` | PostgreSQL, `currentSchema=trading` |
| `DB_USER` / `DB_PASSWORD` | `postgres` / **required** | PostgreSQL credentials |
| `KAFKA_BOOTSTRAP_SERVERS` | `10.8.68.169:9092` | Broker (set the team broker in `.env`) |
| `FAUXNANCE_API_KEY` | **required** | Quote access; startup fails without it |
| `FAUXNANCE_BASE_URL` | Fauxnance prod endpoint | Override for a mock quote server |
| `POLL_INTERVAL_SECONDS` | `60` | Quote poll interval |
| `FX_RATES_<CCY>` | see `application.yml` | Units of the currency per 1 USD |

## Topics

| Topic | Direction | Payload |
| --- | --- | --- |
| `orders` | consumed | `ORDER_PLACED` events published by the order service |
| `trade-events` | published | `TRADE_EXECUTED` / `TRADE_REJECTED`, keyed by `accountId` |
| `orders.DLT` | published | Dead letter for messages that exhausted retries |

Consumer group is `trade-executor` with `enable-auto-commit: false`; the offset
is acknowledged only after the outcome has been published, so a crash replays
the event rather than losing it.

## Fill rules

- **MARKET** executes immediately: **ask** for a BUY, **bid** for a SELL, ignoring
  any submitted limit price.
- **LIMIT** executes only when the price condition is met (`ask <= limit` for a
  BUY, `bid >= limit` for a SELL). Otherwise it stays `PENDING` and the poller
  re-evaluates it on the next quote.

### Stale quotes

A stale quote is treated differently by order type, on purpose:

- **LIMIT** returns `PENDING` (`PRICE_STALE`) and waits for a fresh quote. Filling
  a limit order on a price that may have moved would defeat the point of the limit.
- **MARKET** proceeds on the stale quote. The alternative is refusing the order
  outright, and a market order's whole purpose is to take the available price.
- A **missing** quote rejects a MARKET order and holds a LIMIT one, because there
  is nothing to price with.

## Settlement

`ExecutionService` settles atomically:

1. Flip the order to `FILLED` with `executed_price` / `executed_on`, guarded by
   `WHERE status = 'NEW'` so a replayed event cannot fill it twice.
2. Debit or credit cash and update the holding, also version-guarded.
3. Publish `TRADE_EXECUTED`.

A version conflict throws `RetryableOrderException` rather than committing a
partial fill. `EndOfDayTask` runs at 20:00 UTC on weekdays and rejects whatever
is still `NEW`.

## Failure handling

`DefaultErrorHandler` sends a record that can never succeed (malformed JSON, no
order id, an account that does not exist: `NonRetryableOrderException`) straight
to `orders.DLT`. A transient failure (database blip, optimistic-lock clash) is
retried with exponential backoff — 1s, 2s, 4s, 8s, 16s, five attempts — before
it goes there too. Either way a bad record is visible instead of blocking its
partition.

Every resolution path — the consumer, the poller and the end-of-day job — goes
through `OrderResolver`, and each publishes `ORDER_FILLED` or `ORDER_REJECTED`
to `trade-events` only when its guarded update was the one that changed the
order.

## ETL

The executor does not run the analytics load. It lives in `ETL Layer/` and runs
as its own process (`python main.py`); see `RUNNING.md`.
