-- ============================================================================
-- Migration 008: password reset challenges.
--
-- A password reset is the same two-step dance as registration: an emailed
-- code first, a destructive write second. The pending reset lives here, keyed
-- to the user it belongs to, so a code cannot reset a different account and a
-- replayed code cannot change the password twice.
--
--   psql -U postgres -d trading_system_db -f migrations/008_password_reset.sql
--
-- Safe to run more than once, and harmless on a fresh install already carrying
-- the table from schema.sql.
-- ============================================================================

BEGIN;

CREATE TABLE IF NOT EXISTS auth.password_reset_challenge (
    challenge_id BIGSERIAL PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES auth.users (user_id) ON DELETE CASCADE,
    email        VARCHAR(255) NOT NULL,
    otp_hash     TEXT NOT NULL,
    attempts     INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_on  TIMESTAMPTZ,
    created_on   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_password_reset_challenge_user
    ON auth.password_reset_challenge (user_id);
CREATE INDEX IF NOT EXISTS idx_password_reset_challenge_expiry
    ON auth.password_reset_challenge (expires_at);

COMMIT;