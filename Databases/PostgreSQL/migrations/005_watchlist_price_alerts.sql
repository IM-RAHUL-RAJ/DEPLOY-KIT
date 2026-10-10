-- ============================================================================
-- Migration 005: watchlists — price alerts.
--
-- Adds the price alert table to a database built before it. A fresh install
-- does not need it: schema.sql has it. Safe to run more than once.
--
--   psql -U postgres -d trading_system_db -f migrations/005_watchlist_price_alerts.sql
-- ============================================================================

BEGIN;

-- ============================================================================
-- Price alerts
-- ============================================================================

-- One-shot thresholds pinned to a symbol and evaluated against the market-data
-- stream. An alert fires once: the status flips ACTIVE -> TRIGGERED and the
-- evaluator's index no longer sees it, which is what makes a Kafka redelivery
-- unable to re-fire it. CANCELLED is always a customer choice.
CREATE TABLE IF NOT EXISTS trading.price_alert (
    price_alert_id  BIGSERIAL PRIMARY KEY,
    account_id      BIGINT NOT NULL REFERENCES trading.account (account_id) ON DELETE CASCADE,
    symbol          VARCHAR(20) NOT NULL,
    direction       VARCHAR(5) NOT NULL CHECK (direction IN ('ABOVE', 'BELOW')),
    threshold       NUMERIC(19,8) NOT NULL CHECK (threshold > 0),
    status          VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'TRIGGERED', 'CANCELLED')),
    triggered_price NUMERIC(19,8),
    triggered_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The hot path: the market-data consumer asks "which active alerts watch this
-- symbol?" Only active rows live in this index.
CREATE INDEX IF NOT EXISTS idx_price_alert_active_symbol
    ON trading.price_alert (symbol, status)
    WHERE status = 'ACTIVE';

-- The inbox path: one account's alerts, newest first.
CREATE INDEX IF NOT EXISTS idx_price_alert_account_created
    ON trading.price_alert (account_id, created_at DESC);

COMMIT;