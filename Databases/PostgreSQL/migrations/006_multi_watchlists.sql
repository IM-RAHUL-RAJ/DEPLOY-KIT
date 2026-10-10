-- ============================================================================
-- Migration 006: named, multiple watchlists per account.
--
-- Sprint 6 stored one watchlist per client account (`account_id ... UNIQUE`).
-- Sprint 10 lets a customer keep any number of named lists and move instruments
-- between them. A fresh install does not need this file: schema.sql has the new
-- shape. Safe to run more than once.
--
--   psql -U postgres -d trading_system_db -f migrations/006_multi_watchlists.sql
-- ============================================================================

BEGIN;

-- Every existing watchlist becomes the customer's first list, named as before.
ALTER TABLE trading.watchlist ADD COLUMN IF NOT EXISTS name VARCHAR(40)
    NOT NULL DEFAULT 'My Watchlist';

-- The inline UNIQUE on account_id (PostgreSQL names it watchlist_account_id_key)
-- forced one list per account; it is what this migration removes.
ALTER TABLE trading.watchlist DROP CONSTRAINT IF EXISTS watchlist_account_id_key;

-- A name is unique within an account; the same name may exist under another.
CREATE UNIQUE INDEX IF NOT EXISTS uq_watchlist_account_name
    ON trading.watchlist (account_id, name);

COMMIT;