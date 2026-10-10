-- ============================================================================
-- Migration 004: customer notifications.
--
-- Adds the notification table to a database built before it. A fresh install
-- does not need it: schema.sql has it. Safe to run more than once.
--
--   psql -U postgres -d trading_system_db -f migrations/004_customer_notifications.sql
-- ============================================================================

BEGIN;

-- ============================================================================
-- Notifications
-- ============================================================================

-- The inbox. One row per lifecycle outcome, keyed by the Kafka event id so that
-- at-least-once redelivery never books the same outcome twice. The outbound
-- attempt is a property of the row, never of the log: the customer's inbox has
-- to show what happened to each message, including the ones that could not go
-- out, because "logging an alert is not delivering one".
CREATE TABLE IF NOT EXISTS trading.notification (
    notification_id BIGSERIAL PRIMARY KEY,
    account_id      BIGINT NOT NULL REFERENCES trading.account (account_id) ON DELETE CASCADE,
    -- Idempotency key: the Kafka eventId that produced this row.
    event_id        VARCHAR(64) NOT NULL,
    category        VARCHAR(16) NOT NULL
                    CHECK (category IN ('ORDER_FILLED', 'ORDER_REJECTED', 'ORDER_CANCELLED', 'PRICE_ALERT')),
    symbol          VARCHAR(20),
    title           VARCHAR(255) NOT NULL,
    body            VARCHAR(2000) NOT NULL,
    -- The resolved channel is stored so the history is honest. The contact is
    -- not: an email address or a telephone number is personal data and this
    -- table is durable, while the contact is read fresh at send time anyway.
    channel         VARCHAR(8) NOT NULL DEFAULT 'NONE'
                    CHECK (channel IN ('NONE', 'EMAIL', 'SMS', 'PUSH')),
    status          VARCHAR(12) NOT NULL DEFAULT 'QUEUED'
                    CHECK (status IN ('QUEUED', 'SENT', 'FAILED', 'SUPPRESSED')),
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_event UNIQUE (event_id)
);

-- The inbox read is newest first for one account.
CREATE INDEX IF NOT EXISTS idx_notification_account_created
    ON trading.notification (account_id, created_at DESC);

COMMIT;