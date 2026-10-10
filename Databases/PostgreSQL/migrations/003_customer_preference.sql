-- ============================================================================
-- Migration 003: customer preferences.
--
-- Adds the preference table to a database built before it. A fresh install does
-- not need it: schema.sql has it. Safe to run more than once.
--
--   psql -U postgres -d trading_system_db -f migrations/003_customer_preference.sql
-- ============================================================================

BEGIN;

-- ============================================================================
-- Customer preferences
-- ============================================================================

-- One row per client account, keyed on the account rather than the user because
-- every route addresses an account and the token carries an accountId claim.
-- Administrators have no account, so they have no preference row.
--
-- The row does not exist until the customer saves one. Absence is answered by the
-- service as an unset preference, never as an error, so no default row is seeded
-- here: a seeded row would make "never answered" indistinguishable from "chose
-- NONE" the day somebody reads the table.
CREATE TABLE IF NOT EXISTS trading.customer_preference (
    account_id         BIGINT PRIMARY KEY REFERENCES trading.account (account_id) ON DELETE CASCADE,
    -- Stored even though trading.account.user_id is UNIQUE today, so this is
    -- always the caller's own account. It is what makes "which account opens at
    -- sign-in" a preference rather than a hard-coded guess, and it stays correct
    -- if the one-account-per-user rule is ever lifted.
    default_account_id BIGINT REFERENCES trading.account (account_id) ON DELETE SET NULL,
    alert_channel      VARCHAR(16) NOT NULL DEFAULT 'NONE'
                       CHECK (alert_channel IN ('NONE', 'EMAIL', 'SMS', 'PUSH')),
    alert_contact      VARCHAR(255),
    display_currency   CHAR(3) CHECK (display_currency IS NULL OR display_currency ~ '^[A-Z]{3}$'),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A channel that delivers needs somewhere to deliver to, and a channel that
    -- does not must not carry a stale destination left over from the choice
    -- before it. ONE row, ONE destination: the shape is fixed by the channel.
    CONSTRAINT ck_customer_preference_contact_shape
        CHECK ((alert_channel = 'NONE' AND alert_contact IS NULL)
            OR (alert_channel <> 'NONE' AND alert_contact IS NOT NULL))
);

COMMIT;
