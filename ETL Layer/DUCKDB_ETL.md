# DuckDB Analytics ETL — Sprint 7

## Overview

This module implements an incremental star schema loader for analytical queries, decoupling OLTP operations from OLAP analysis. Run it with `python main.py` (refresh loop) or `python main.py --once`; the code is `main.py` and `analytics_pipeline.py`.

Each pass loads the orders created since the watermark (the latest `received_at` already loaded, taken from the data, not the clock) **and** re-reads every order the warehouse still holds as `NEW`, so a fill, rejection or cancellation that happens after an order was first loaded reaches `fact_trades`. Facts are upserted on `source_order_id`; dimensions are looked up so referential integrity holds.

## Architecture

### Star Schema (contracts/analytics-schema.sql)

**Fact Table**: `fact_trades` (one row per order, regardless of status)
- Grain: One order
- Includes rejected and cancelled orders (necessary for fill rate calculation)
- Contains: `trade_key`, `account_key`, `instrument_key`, `date_key`, `side`, `quantity`, `price`, `status`, `executed_price`, `trade_value`, `source_order_id`, `created_at`, `loaded_at`

**Dimension Tables**:
1. **dim_account** (Type 2 SCD)
   - Slowly changing dimension: account status changes create new rows
   - Fields: `account_key`, `account_id`, `holder_name`, `status`, `effective_date`, `end_date`, `is_current`, `source_id`, `loaded_at`
   - One row per account per status version
   - Exactly one row per account_id where `is_current = true`

2. **dim_instrument** (Type 1)
   - Instrument attributes are overwritten in place (name, asset class don't branch history)
   - Fields: `instrument_key`, `symbol`, `name`, `asset_class`, `currency`, `exchange`, `tradable`, `loaded_at`
   - Unique on `symbol`

3. **dim_date**
   - Pre-populated by ETL for 10-year range (once, before facts)
   - Fields: `date_key` (YYYYMMDD), `full_date`, `day`, `month`, `year`, `quarter`, `day_of_week`, `day_name`, `month_name`, `is_weekday`
   - Enables "trades by quarter" as a simple join and GROUP BY

**Watermark Table**: `etl_watermark`
- Stores `last_loaded_ts` for each table
- Enables incremental loading: new orders are those with `received_at` past the watermark; open (`NEW`) orders are always re-read

---

## Load Order & Properties Assessed

### Property 1: Load Order (Dimensions Before Facts)

**Enforced sequence**:
```
1. dim_date        (full range, pre-populated)
   ↓
2. dim_instrument  (Type 1 merge on symbol)
   ↓
3. dim_account     (Type 2 SCD merge on account_id+effective_date)
   ↓
4. fact_trades     (incremental, resolved via dimension keys)
```

**Why it matters**: A fact row references `account_key`, `instrument_key`, `date_key`. If these don't exist, the insert fails. Load dimensions first.

**Implementation**: [etl/duckdb_loader.py](../etl/duckdb_loader.py) in `load()` method calls:
```python
self.load_dim_date(date_start, date_end)
self.load_dim_instrument()
self.load_dim_account()
self.load_fact_trades()
```

### Property 2: Incremental, Not Full

**Watermark-driven**: 
- Query: `SELECT last_loaded_ts FROM etl_watermark WHERE table_name = 'fact_trades'`
- Load: `WHERE received_at > last_watermark`
- Update: Set watermark to current time after load completes

**Benefits**:
- Second run loads only new orders (created since last run)
- No full table scans on every invocation
- Scales to millions of orders

**Implementation**: [load_fact_trades()](../etl/duckdb_loader.py#L245) method uses watermark table.

### Property 3: Idempotency (Re-run Safety)

**Fact loads use `INSERT OR IGNORE`** on `source_order_id` unique constraint:
- Same order inserted twice? Second insert is ignored.
- Dimensions use **MERGE** logic:
  - `dim_instrument`: Update if symbol exists, insert if new
  - `dim_account`: Close old version (Type 2), insert new version if status changes

**Benefit**: Running today's load twice or three times produces identical warehouse state.

**Implementation**:
```python
# Dimensions: MERGE on natural key
MERGE INTO dim_instrument t USING new_data s 
ON t.symbol = s.symbol
WHEN MATCHED THEN UPDATE ...
WHEN NOT MATCHED THEN INSERT ...

# Facts: Unique constraint + OR IGNORE
INSERT OR IGNORE INTO fact_trades (...)
```

### Property 4: Data Quality (Before Load)

**Checks executed before insert**:
1. `account_key` resolves (dimension lookup)
2. `instrument_key` resolves (dimension lookup)
3. `date_key` resolves (dimension lookup)
4. `quantity > 0` and `price > 0`
5. `side` in ('BUY', 'SELL')
6. `status` in ('NEW', 'FILLED', 'REJECTED', 'CANCELLED')
7. `trade_value` recomputed (not trusted from source)
8. `source_order_id` not yet loaded (unique constraint)

**Failure mode**: Row is skipped with a `WARNING` log. No dead-letter yet (scope for future sprints).

**Implementation**: [load_fact_trades()](../etl/duckdb_loader.py#L285) method validates each row.

---

## Usage

### Command Line

```bash
python -m etl.duckdb_loader \
  --duckdb-path analytics.duckdb \
  --pg-conn "postgresql://postgres:postgres@localhost:5432/trading_db" \
  --date-start 2025-01-01 \
  --date-end 2026-12-31
```

### Programmatic

```python
from etl.duckdb_loader import AnalyticsDuckDBLoader
from datetime import date

loader = AnalyticsDuckDBLoader(
    duckdb_path="analytics.duckdb",
    pg_conn_str="postgresql://postgres:postgres@localhost:5432/trading_db"
)
loader.load(
    date_start=date(2025, 1, 1),
    date_end=date(2026, 12, 31)
)
```

---

## Analytics Queries

The star schema answers five questions from the curriculum with one join per dimension, no subqueries:

### 1. Trade Volume
```sql
SELECT d.date_key, COUNT(*) as order_count, SUM(f.trade_value) as total_value
FROM fact_trades f
JOIN dim_date d ON d.date_key = f.date_key
GROUP BY d.date_key
ORDER BY d.date_key;
```

### 2. Most Active Accounts
```sql
SELECT a.account_id, a.holder_name, COUNT(*) as order_count
FROM fact_trades f
JOIN dim_account a ON a.account_key = f.account_key AND a.is_current = true
GROUP BY a.account_key, a.account_id, a.holder_name
ORDER BY order_count DESC
LIMIT 10;
```

### 3. Fill Rate by Month
```sql
SELECT d.year, d.month, 
       COUNT(*) as orders_placed,
       SUM(CASE WHEN f.status = 'FILLED' THEN 1 ELSE 0 END) as orders_filled,
       SUM(CASE WHEN f.status = 'FILLED' THEN 1 ELSE 0 END) * 100.0 / COUNT(*) as fill_rate_pct
FROM fact_trades f
JOIN dim_date d ON d.date_key = f.date_key
GROUP BY d.year, d.month
ORDER BY d.year, d.month;
```

### 4. Exposure by Instrument
```sql
SELECT i.symbol, i.asset_class, f.side, SUM(f.trade_value) as total_exposure
FROM fact_trades f
JOIN dim_instrument i ON i.instrument_key = f.instrument_key
GROUP BY i.instrument_key, i.symbol, i.asset_class, f.side
ORDER BY total_exposure DESC;
```

### 5. Average Trade Size Over Date Range
```sql
SELECT AVG(f.trade_value) as avg_trade_value
FROM fact_trades f
JOIN dim_date d ON d.date_key = f.date_key
WHERE d.full_date BETWEEN '2026-01-01' AND '2026-09-30';
```

---

## Type 2 SCD (Slowly Changing Dimension) for Accounts

When an account's status changes:

**Before**:
```
account_key | account_id | status  | effective_date | end_date   | is_current
1           | ACC001     | ACTIVE  | 2025-01-01     | 2025-06-30 | false
2           | ACC001     | SUSPENDED | 2025-07-01   | NULL       | true
```

**Trade history remains intact**: A trade placed in June 2025 joins to account_key=1 (ACTIVE), one in July joins to account_key=2 (SUSPENDED). History is queryable.

---

## Testing the Load

### Scenario: Full Load + Incremental Run

```bash
# First run: Load all orders from 2025-01-01
python -m etl.duckdb_loader --date-start 2025-01-01 --date-end 2025-12-31

# Check watermark
sqlite3 analytics.duckdb "SELECT * FROM etl_watermark;"
# Output: fact_trades | 2026-09-18 14:30:00 (approximate)

# Insert new order into PostgreSQL
psql -h localhost -U postgres -d trading_db -c \
  "INSERT INTO orders (account_id, ticker, side, quantity, price, order_type, status, received_at, idempotency_key) \
   VALUES (1, 'AAPL', 'BUY', 100, 150.00, 'MARKET', 'FILLED', now(), 'new-order-1');"

# Run again: Only new orders loaded
python -m etl.duckdb_loader --date-start 2025-01-01 --date-end 2026-12-31

# Verify: One new row in fact_trades
duckdb analytics.duckdb "SELECT COUNT(*) FROM fact_trades WHERE created_at > '2026-09-18 14:30:00';"
```

---

## Reconciliation (Post-Load Verification)

**Scope for future sprints**: A reconciliation job that compares:
- Row count in `fact_trades` (analytics) vs count of orders in PostgreSQL (operational)
- Summed `trade_value` in warehouse vs operational calculation

For now, manual checks suffice:

```sql
-- Analytics DB
SELECT COUNT(*) as fact_count, SUM(trade_value) as fact_value
FROM fact_trades
WHERE DATE(created_at) = '2026-09-18';

-- Operational DB (pseudo-SQL for comparison)
SELECT COUNT(*) as order_count, 
       SUM(CASE WHEN status='FILLED' THEN quantity * executed_price ELSE quantity * price END) as order_value
FROM orders
WHERE DATE(received_at) = '2026-09-18';
```

---

## Implementation Checklist

- [x] Star schema DDL (contracts/analytics-schema.sql)
- [x] Incremental loader (etl/duckdb_loader.py)
- [x] Watermark table and tracking
- [x] Type 2 SCD for accounts
- [x] Type 1 overwrite for instruments
- [x] Date dimension pre-population (10 years)
- [x] Dimension key lookup before fact insert
- [x] Data quality validation (quantity, price, side, status)
- [x] Idempotent loads (MERGE + OR IGNORE)
- [x] Load order enforcement
- [ ] Reconciliation job (future sprint)
- [ ] Dead-letter handling for failed rows (future sprint)
- [ ] Scheduler/orchestration (future sprint)

---

## Dependencies

- `duckdb` (local analytical database)
- `psycopg2` (PostgreSQL connection)
- `pandas` (for dim_date bulk insert)

Install:
```bash
pip install duckdb psycopg2-binary pandas
```

---

## Logs & Debugging

All loads are logged with timestamps and row counts:

```
2026-09-18T14:25:30  INFO     etl.duckdb_loader — Connected to DuckDB: analytics.duckdb
2026-09-18T14:25:30  INFO     etl.duckdb_loader — Connected to PostgreSQL
2026-09-18T14:25:30  INFO     etl.duckdb_loader — Initialized surrogate keys: account_key=1, instrument_key=1, trade_key=1
2026-09-18T14:25:30  INFO     etl.duckdb_loader — Starting incremental load: 2025-01-01 to 2026-12-31
2026-09-18T14:25:30  INFO     etl.duckdb_loader — Loading dim_date from 2025-01-01 to 2026-12-31
2026-09-18T14:25:31  INFO     etl.duckdb_loader — Loaded 731 date rows
2026-09-18T14:25:31  INFO     etl.duckdb_loader — Loading dim_instrument
2026-09-18T14:25:31  INFO     etl.duckdb_loader — Fetched 11 instruments from PostgreSQL
2026-09-18T14:25:31  INFO     etl.duckdb_loader — Loaded 11 instruments
2026-09-18T14:25:31  INFO     etl.duckdb_loader — Loading dim_account (Type 2 SCD)
2026-09-18T14:25:31  INFO     etl.duckdb_loader — Fetched 1 accounts from PostgreSQL
2026-09-18T14:25:31  INFO     etl.duckdb_loader — Loaded 1 accounts
2026-09-18T14:25:31  INFO     etl.duckdb_loader — Loading fact_trades (incremental)
2026-09-18T14:25:31  INFO     etl.duckdb_loader — Last watermark: 2025-01-01 00:00:00
2026-09-18T14:25:32  INFO     etl.duckdb_loader — Fetched 42 new orders since 2025-01-01 00:00:00
2026-09-18T14:25:32  INFO     etl.duckdb_loader — Loaded 42 new fact_trades rows
2026-09-18T14:25:32  INFO     etl.duckdb_loader — Incremental load complete
```

---

## Future Extensions

1. **Reconciliation Job**: Validates row counts and summed values daily
2. **Dead-Letter Queue**: Failed rows sent to `.dlt` table for manual review
3. **Scheduler**: cron or Airflow to trigger load on schedule
4. **History Rebuild**: Reload from checkpoint for auditing/correction
5. **Incremental Fact Updates**: Handle updated orders (status change PENDING → FILLED)
