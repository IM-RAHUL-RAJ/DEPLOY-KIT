-- ============================================================================
-- Migration 007: per notification type delivery preference.
--
-- One row per (account, category) saying where that kind of notification should
-- be delivered. The single global customer_preference.alert_channel stays the
-- fallback: a row with channel 'INHERIT' (or no row at all) means "deliver this
-- category the same way the account asks for everything". A row with channel
-- 'NONE' means "never deliver this category", which beats an inherited channel
-- that would send it.
--
--   psql -U postgres -d trading_system_db -f migrations/007_notification_preference.sql
-- ============================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS trading.notification_preference (
    account_id BIGINT NOT NULL REFERENCES trading.account (account_id) ON DELETE CASCADE,
    category   VARCHAR(16) NOT NULL
               CHECK (category IN ('ORDER_FILLED', 'ORDER_REJECTED', 'ORDER_CANCELLED', 'PRICE_ALERT')),
    -- INHERIT = fall back to customer_preference.alert_channel / alert_contact.
    channel    VARCHAR(16) NOT NULL DEFAULT 'INHERIT'
               CHECK (channel IN ('INHERIT', 'NONE', 'EMAIL', 'SMS', 'PUSH')),
    contact    VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, category),
    -- Same shape rule as customer_preference: a channel that delivers needs a
    -- destination, and one that does not must not carry a stale destination.
    CONSTRAINT ck_notification_preference_contact_shape
        CHECK ((channel IN ('INHERIT', 'NONE') AND contact IS NULL)
            OR (channel NOT IN ('INHERIT', 'NONE') AND contact IS NOT NULL))
);

COMMIT;