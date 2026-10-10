-- ============================================================================
-- Migration 010: automated strategy execution.
--
-- Rules a customer configures once and the platform trades for them: a
-- moving-average crossover or a Bollinger-band breakout on one symbol. Orders are
-- placed through POST /api/v1/orders like any other. A fresh install does not
-- need this file: schema.sql has the same tables. Safe to run more than once.
--
--   psql -U postgres -d trading_system_db -f migrations/010_automated_strategies.sql
-- ============================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS trading.trading_strategy (
    strategy_id          BIGSERIAL PRIMARY KEY,
    account_id           BIGINT NOT NULL REFERENCES trading.account (account_id) ON DELETE CASCADE,
    name                 VARCHAR(60) NOT NULL,
    symbol               VARCHAR(20) NOT NULL,
    strategy_type        VARCHAR(20) NOT NULL
                         CHECK (strategy_type IN ('PRICE_TARGET', 'MA_CROSSOVER', 'BOLLINGER')),
    -- Price target: buy or sell once the price reaches the target, at market or as a
    -- limit order at the target.
    side                 VARCHAR(4) CHECK (side IN ('BUY', 'SELL')),
    target_price         NUMERIC(19,8) CHECK (target_price > 0),
    order_type           VARCHAR(6) NOT NULL DEFAULT 'MARKET' CHECK (order_type IN ('MARKET', 'LIMIT')),
    -- Moving-average crossover: the short and long windows, in daily closes.
    short_window         INTEGER,
    long_window          INTEGER,
    -- Bollinger bands: the averaging period and the width in standard deviations.
    band_period          INTEGER,
    band_k               NUMERIC(4,2),
    -- What a trigger trades, and the limits on it. Checked again at the moment of
    -- decision, never only when the strategy is saved.
    quantity             BIGINT NOT NULL CHECK (quantity > 0),
    max_notional         NUMERIC(19,2) NOT NULL CHECK (max_notional > 0),
    max_orders_per_day   INTEGER NOT NULL DEFAULT 5 CHECK (max_orders_per_day > 0),
    -- The most shares of the symbol the account may hold after a strategy BUY.
    max_position         BIGINT NOT NULL CHECK (max_position > 0),
    status               VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
                         CHECK (status IN ('ACTIVE', 'DISABLED', 'STOPPED', 'COMPLETED')),
    stop_reason          VARCHAR(200),
    -- The last evaluated relation (ABOVE/BELOW for a crossover; ABOVE/INSIDE/BELOW
    -- for bands). A trigger is a change of state, so the first evaluation only
    -- records where things stand and never trades.
    last_state           VARCHAR(10),
    last_price           NUMERIC(19,8),
    last_evaluated_at    TIMESTAMPTZ,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A database that ran an earlier draft of this migration lacks max_position.
ALTER TABLE trading.trading_strategy ADD COLUMN IF NOT EXISTS max_position BIGINT;
UPDATE trading.trading_strategy SET max_position = quantity WHERE max_position IS NULL;
ALTER TABLE trading.trading_strategy ALTER COLUMN max_position SET NOT NULL;

-- ... and the price-target rule and the COMPLETED status, added after it.
ALTER TABLE trading.trading_strategy ADD COLUMN IF NOT EXISTS side VARCHAR(4);
ALTER TABLE trading.trading_strategy ADD COLUMN IF NOT EXISTS target_price NUMERIC(19,8);
ALTER TABLE trading.trading_strategy ADD COLUMN IF NOT EXISTS order_type VARCHAR(6) NOT NULL DEFAULT 'MARKET';
ALTER TABLE trading.trading_strategy DROP CONSTRAINT IF EXISTS trading_strategy_strategy_type_check;
ALTER TABLE trading.trading_strategy ADD CONSTRAINT trading_strategy_strategy_type_check
    CHECK (strategy_type IN ('PRICE_TARGET', 'MA_CROSSOVER', 'BOLLINGER'));
ALTER TABLE trading.trading_strategy DROP CONSTRAINT IF EXISTS trading_strategy_status_check;
ALTER TABLE trading.trading_strategy ADD CONSTRAINT trading_strategy_status_check
    CHECK (status IN ('ACTIVE', 'DISABLED', 'STOPPED', 'COMPLETED'));

CREATE INDEX IF NOT EXISTS idx_trading_strategy_active_symbol
    ON trading.trading_strategy (symbol) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_trading_strategy_account
    ON trading.trading_strategy (account_id, created_at DESC);

-- Every decision a strategy took on a quote: an order placed, a trade refused by a
-- limit, or a failure. One row per strategy and market-data event, so a redelivered
-- quote cannot trade twice.
CREATE TABLE IF NOT EXISTS trading.strategy_execution (
    execution_id   BIGSERIAL PRIMARY KEY,
    strategy_id    BIGINT NOT NULL REFERENCES trading.trading_strategy (strategy_id) ON DELETE CASCADE,
    event_id       VARCHAR(64) NOT NULL,
    signal         VARCHAR(4) NOT NULL CHECK (signal IN ('BUY', 'SELL')),
    price          NUMERIC(19,8),
    quantity       BIGINT NOT NULL,
    status         VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'PLACED', 'SKIPPED', 'FAILED')),
    order_id       VARCHAR(40),
    reason         VARCHAR(300),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_strategy_execution_event UNIQUE (strategy_id, event_id)
);

CREATE INDEX IF NOT EXISTS idx_strategy_execution_strategy
    ON trading.strategy_execution (strategy_id, created_at DESC);

COMMIT;
