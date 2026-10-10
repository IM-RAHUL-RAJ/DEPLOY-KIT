-- ============================================================================
-- Migration 009: market data through the poller.
--
-- The market-data poller in the Trade Executor is the one component that asks
-- Fauxnance for quotes and candles. These tables are how everything else reads
-- what it fetched, and asks it to fetch something new. A fresh install does not
-- need this file: schema.sql has the same tables. Safe to run more than once.
--
--   psql -U postgres -d trading_system_db -f migrations/009_market_data_cache.sql
-- ============================================================================

BEGIN;

-- The latest quote per symbol, for every symbol the poller has fetched. The order
-- ticket, the watchlist, market-order pricing and the portfolio all read this
-- row instead of calling Fauxnance. Freshness is judged by quote_as_of, the time
-- Fauxnance observed the price, not by when the poller fetched it.
CREATE TABLE IF NOT EXISTS trading.market_quote (
    symbol          VARCHAR(20) PRIMARY KEY,
    price           NUMERIC(19,8),
    bid             NUMERIC(19,8),
    ask             NUMERIC(19,8),
    currency        CHAR(3),
    change          NUMERIC(19,8),
    change_percent  NUMERIC(10,4),
    previous_close  NUMERIC(19,8),
    market_state    VARCHAR(10),
    stale           BOOLEAN NOT NULL DEFAULT false,
    quote_as_of     TIMESTAMPTZ,
    fetched_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Symbols somebody wants that nothing else keeps the poller interested in. A
-- customer viewing a symbol on the ticket registers VIEWED interest, which the
-- scheduled poll honours until it expires; a chart registers CANDLES interest
-- for the day's history. The poller's fast path picks up a request with no data
-- within a couple of seconds. not_found_at records that Fauxnance has no such
-- symbol, so an unknown one is answered at once and not retried every tick.
CREATE TABLE IF NOT EXISTS trading.market_interest (
    symbol        VARCHAR(20) NOT NULL,
    reason        VARCHAR(10) NOT NULL CHECK (reason IN ('VIEWED', 'CANDLES')),
    requested_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    not_found_at  TIMESTAMPTZ,
    PRIMARY KEY (symbol, reason)
);

CREATE INDEX IF NOT EXISTS idx_market_interest_live
    ON trading.market_interest (reason, expires_at);

-- Daily candles change once a day, so one fetch per symbol per day serves every
-- chart. The candles are kept as the JSON array Fauxnance returned.
CREATE TABLE IF NOT EXISTS trading.market_candles (
    symbol      VARCHAR(20) PRIMARY KEY,
    currency    CHAR(3),
    candles     TEXT NOT NULL,
    fetched_on  DATE NOT NULL,
    fetched_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row describing the market-data feed, written by the poller: when it last
-- reached Fauxnance, when it last failed, and the key's quota as of the poller's
-- hourly check. Health reports read this instead of calling Fauxnance, because
-- every call, /usage included, costs quota.
CREATE TABLE IF NOT EXISTS trading.market_feed_status (
    id                SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    last_success_at   TIMESTAMPTZ,
    last_failure_at   TIMESTAMPTZ,
    last_error        VARCHAR(200),
    daily_quota       INTEGER,
    used_today        INTEGER,
    quota_checked_at  TIMESTAMPTZ
);

INSERT INTO trading.market_feed_status (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

COMMIT;
