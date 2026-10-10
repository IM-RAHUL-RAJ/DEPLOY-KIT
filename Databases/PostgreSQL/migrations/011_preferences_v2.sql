-- ============================================================================
-- Migration 011: preferences, second version.
--
--   * Delivery is in-app, email, or both. SMS is retired: a stored SMS choice
--     becomes in-app. In-app needs no destination; email and both need an email.
--   * Per-category delivery is set for three categories (account, order, price)
--     instead of four individual notification types. Each existing per-type row
--     is folded into its category, newest answer first.
--   * The colour theme is the customer's preference, stored with the rest.
--
--   psql -U postgres -d trading_system_db -f migrations/011_preferences_v2.sql
--
-- Safe to run more than once. A fresh install from schema.sql needs nothing here.
-- ============================================================================

BEGIN;

-- ---- customer_preference ----------------------------------------------------
ALTER TABLE trading.customer_preference
    ADD COLUMN IF NOT EXISTS theme VARCHAR(5) NOT NULL DEFAULT 'dark';
ALTER TABLE trading.customer_preference DROP CONSTRAINT IF EXISTS customer_preference_theme_check;
ALTER TABLE trading.customer_preference
    ADD CONSTRAINT customer_preference_theme_check CHECK (theme IN ('dark', 'light'));

ALTER TABLE trading.customer_preference DROP CONSTRAINT IF EXISTS customer_preference_alert_channel_check;
ALTER TABLE trading.customer_preference DROP CONSTRAINT IF EXISTS ck_customer_preference_contact_shape;

UPDATE trading.customer_preference SET alert_channel = 'PUSH', alert_contact = NULL WHERE alert_channel = 'SMS';
UPDATE trading.customer_preference SET alert_contact = NULL WHERE alert_channel = 'PUSH';

ALTER TABLE trading.customer_preference
    ADD CONSTRAINT customer_preference_alert_channel_check
        CHECK (alert_channel IN ('NONE', 'EMAIL', 'PUSH', 'BOTH'));
ALTER TABLE trading.customer_preference
    ADD CONSTRAINT ck_customer_preference_contact_shape
        CHECK ((alert_channel IN ('NONE', 'PUSH') AND alert_contact IS NULL)
            OR (alert_channel IN ('EMAIL', 'BOTH') AND alert_contact IS NOT NULL));

-- ---- notification_preference ------------------------------------------------
ALTER TABLE trading.notification_preference DROP CONSTRAINT IF EXISTS notification_preference_category_check;
ALTER TABLE trading.notification_preference DROP CONSTRAINT IF EXISTS notification_preference_channel_check;
ALTER TABLE trading.notification_preference DROP CONSTRAINT IF EXISTS ck_notification_preference_contact_shape;

UPDATE trading.notification_preference SET channel = 'PUSH', contact = NULL WHERE channel = 'SMS';
UPDATE trading.notification_preference SET contact = NULL WHERE channel = 'PUSH';

INSERT INTO trading.notification_preference (account_id, category, channel, contact, updated_at)
SELECT DISTINCT ON (account_id) account_id, 'ORDER_RELATED', channel, contact, updated_at
  FROM trading.notification_preference
 WHERE category IN ('ORDER_FILLED', 'ORDER_REJECTED', 'ORDER_CANCELLED')
 ORDER BY account_id, updated_at DESC
ON CONFLICT (account_id, category) DO NOTHING;

INSERT INTO trading.notification_preference (account_id, category, channel, contact, updated_at)
SELECT account_id, 'PRICE_RELATED', channel, contact, updated_at
  FROM trading.notification_preference
 WHERE category = 'PRICE_ALERT'
ON CONFLICT (account_id, category) DO NOTHING;

DELETE FROM trading.notification_preference
 WHERE category IN ('ORDER_FILLED', 'ORDER_REJECTED', 'ORDER_CANCELLED', 'PRICE_ALERT');

ALTER TABLE trading.notification_preference
    ADD CONSTRAINT notification_preference_category_check
        CHECK (category IN ('ACCOUNT_RELATED', 'ORDER_RELATED', 'PRICE_RELATED'));
ALTER TABLE trading.notification_preference
    ADD CONSTRAINT notification_preference_channel_check
        CHECK (channel IN ('INHERIT', 'NONE', 'EMAIL', 'PUSH', 'BOTH'));
ALTER TABLE trading.notification_preference
    ADD CONSTRAINT ck_notification_preference_contact_shape
        CHECK ((channel IN ('INHERIT', 'NONE', 'PUSH') AND contact IS NULL)
            OR (channel IN ('EMAIL', 'BOTH') AND contact IS NOT NULL));

-- ---- notification -----------------------------------------------------------
ALTER TABLE trading.notification DROP CONSTRAINT IF EXISTS notification_channel_check;
ALTER TABLE trading.notification
    ADD CONSTRAINT notification_channel_check CHECK (channel IN ('NONE', 'EMAIL', 'SMS', 'PUSH', 'BOTH'));

COMMIT;
