-- ============================================================================
-- Migration 002: watchlists.
--
-- Adds the watchlist tables to a database built before them. A fresh install
-- does not need it: schema.sql has them. Safe to run more than once.
--
--   psql -U postgres -d trading_system_db -f migrations/002_watchlist.sql
-- ============================================================================

BEGIN;

-- ============================================================================
-- Watchlists
-- ============================================================================

-- One watchlist per client account (administrators have no account, so none).
CREATE TABLE IF NOT EXISTS trading.watchlist (
    watchlist_id BIGSERIAL PRIMARY KEY,
    account_id   BIGINT NOT NULL UNIQUE REFERENCES trading.account (account_id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The instruments on a watchlist. At most 25 per list, enforced by the service
-- (the market-data batch endpoint takes 25 symbols per request).
CREATE TABLE IF NOT EXISTS trading.watchlist_instrument (
    watchlist_instrument_id BIGSERIAL PRIMARY KEY,
    watchlist_id            BIGINT NOT NULL REFERENCES trading.watchlist (watchlist_id) ON DELETE CASCADE,
    instrument_id           BIGINT NOT NULL REFERENCES trading.instrument (instrument_id) ON DELETE CASCADE,
    added_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_watchlist_instrument UNIQUE (watchlist_id, instrument_id)
);

CREATE INDEX IF NOT EXISTS idx_watchlist_instrument_instrument ON trading.watchlist_instrument (instrument_id);

-- Latest quote per watched instrument, written by the executor's poller so the
-- watchlist page reads prices from our database, never the market-data API.
CREATE TABLE IF NOT EXISTS trading.instrument_quote (
    quote_id       BIGSERIAL PRIMARY KEY,
    instrument_id  BIGINT NOT NULL UNIQUE REFERENCES trading.instrument (instrument_id) ON DELETE CASCADE,
    symbol         VARCHAR(20) NOT NULL,
    current_price  NUMERIC(19,8),
    currency       CHAR(3),
    bid            NUMERIC(19,8),
    ask            NUMERIC(19,8),
    change         NUMERIC(19,8),
    change_percent NUMERIC(10,4),
    last_updated   TIMESTAMPTZ NOT NULL DEFAULT now(),
    stale          BOOLEAN NOT NULL DEFAULT false
);

CREATE INDEX IF NOT EXISTS idx_instrument_quote_symbol ON trading.instrument_quote (symbol);

COMMIT;
