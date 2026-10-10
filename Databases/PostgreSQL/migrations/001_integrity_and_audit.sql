-- ============================================================================
-- Migration 001: integrity constraints, audit table and indexes.
--
-- Brings a database built from the earlier schema.sql up to the current one
-- without losing data. A fresh install does not need it: schema.sql already
-- has all of this. Safe to run more than once.
--
--   psql -U postgres -d trading_system_db -f migrations/001_integrity_and_audit.sql
--
-- Constraints that existing rows might violate are added NOT VALID: they hold
-- for every new write at once, and `ALTER TABLE ... VALIDATE CONSTRAINT` checks
-- the history once it has been cleaned.
-- ============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- auth.users: roles is the single source of truth; user_type duplicated it.
-- ---------------------------------------------------------------------------
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'auth' AND table_name = 'users' AND column_name = 'user_type') THEN
        UPDATE auth.users SET roles = ARRAY['ADMIN']
         WHERE user_type = 'admin' AND NOT ('ADMIN' = ANY (roles));
        ALTER TABLE auth.users DROP COLUMN user_type;
    END IF;
END $$;

DO $$ BEGIN
    ALTER TABLE auth.users ADD CONSTRAINT ck_users_roles
        CHECK (cardinality(roles) > 0 AND roles <@ ARRAY['CUSTOMER', 'ADMIN']) NOT VALID;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- Usernames and emails are unique whatever their case, as the services compare them.
ALTER TABLE auth.users DROP CONSTRAINT IF EXISTS users_username_key;
ALTER TABLE auth.users DROP CONSTRAINT IF EXISTS users_email_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_username_ci ON auth.users (lower(username));
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_ci ON auth.users (lower(email));

DROP INDEX IF EXISTS auth.idx_registration_challenge_email;
CREATE INDEX IF NOT EXISTS idx_registration_challenge_email ON auth.registration_challenge (lower(email));
CREATE INDEX IF NOT EXISTS idx_refresh_token_expiry ON auth.refresh_token (expires_at);

-- ---------------------------------------------------------------------------
-- trading.account
-- ---------------------------------------------------------------------------
DO $$ BEGIN
    ALTER TABLE trading.account ADD CONSTRAINT ck_account_cash_non_negative
        CHECK (cash_balance >= 0) NOT VALID;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE trading.account ADD CONSTRAINT ck_account_currency
        CHECK (currency ~ '^[A-Z]{3}$') NOT VALID;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- UNIQUE (user_id) already indexes this column.
DROP INDEX IF EXISTS trading.idx_account_user;

CREATE TABLE IF NOT EXISTS trading.balance_adjustment (
    adjustment_id BIGSERIAL PRIMARY KEY,
    account_id    BIGINT NOT NULL REFERENCES trading.account (account_id) ON DELETE CASCADE,
    amount        NUMERIC(19,2) NOT NULL CHECK (amount <> 0),
    reason        VARCHAR(255) NOT NULL,
    adjusted_by   BIGINT REFERENCES auth.users (user_id) ON DELETE SET NULL,
    adjusted_on   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_balance_adjustment_account
    ON trading.balance_adjustment (account_id, adjusted_on DESC);

-- ---------------------------------------------------------------------------
-- trading.instrument: one status vocabulary, and every traded symbol catalogued.
-- ---------------------------------------------------------------------------
UPDATE trading.instrument SET status = 'ACTIVE' WHERE status IS NULL OR status IN ('TRADEABLE', 'TRADABLE');
UPDATE trading.instrument SET ticker = symbol WHERE ticker IS NULL;
UPDATE trading.instrument SET quote_currency = 'USD' WHERE quote_currency IS NULL;
ALTER TABLE trading.instrument ALTER COLUMN status SET DEFAULT 'ACTIVE';
ALTER TABLE trading.instrument ALTER COLUMN status SET NOT NULL;
ALTER TABLE trading.instrument ALTER COLUMN ticker SET NOT NULL;
ALTER TABLE trading.instrument ALTER COLUMN quote_currency SET DEFAULT 'USD';
ALTER TABLE trading.instrument ALTER COLUMN quote_currency SET NOT NULL;
DO $$ BEGIN
    ALTER TABLE trading.instrument ADD CONSTRAINT ck_instrument_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELISTED')) NOT VALID;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- Symbols that were traded but never catalogued (they were discovered at order
-- time before the API registered them) are added so the references below hold.
INSERT INTO trading.instrument (symbol, ticker, asset_class, quote_currency, status)
SELECT DISTINCT upper(t.ticker), upper(t.ticker), 'EQUITY', 'USD', 'ACTIVE'
  FROM (SELECT ticker FROM trading.orders UNION SELECT ticker FROM trading.holding) t
 WHERE t.ticker IS NOT NULL
ON CONFLICT (symbol) DO NOTHING;

-- ---------------------------------------------------------------------------
-- trading.holding
-- ---------------------------------------------------------------------------
ALTER TABLE trading.holding ALTER COLUMN average_price TYPE NUMERIC(19,8);
DO $$ BEGIN
    ALTER TABLE trading.holding ADD CONSTRAINT ck_holding_quantity
        CHECK (quantity >= 0 AND quantity = trunc(quantity)) NOT VALID;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE trading.holding ADD CONSTRAINT fk_holding_instrument
        FOREIGN KEY (ticker) REFERENCES trading.instrument (symbol) NOT VALID;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
-- The unique (account_id, ticker) index already covers lookups by account.
DROP INDEX IF EXISTS trading.idx_holding_account;

-- ---------------------------------------------------------------------------
-- trading.orders
-- ---------------------------------------------------------------------------
ALTER TABLE trading.orders ALTER COLUMN executed_price TYPE NUMERIC(19,8);
ALTER TABLE trading.orders ALTER COLUMN status SET DEFAULT 'NEW';
DO $$ BEGIN
    ALTER TABLE trading.orders ADD CONSTRAINT ck_orders_side CHECK (side IN ('BUY', 'SELL')) NOT VALID;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE trading.orders ADD CONSTRAINT ck_orders_status
        CHECK (status IN ('NEW', 'FILLED', 'REJECTED', 'CANCELLED')) NOT VALID;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE trading.orders ADD CONSTRAINT ck_orders_type CHECK (order_type IN ('LIMIT', 'MARKET')) NOT VALID;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE trading.orders ADD CONSTRAINT ck_orders_quantity
        CHECK (quantity > 0 AND quantity = trunc(quantity)) NOT VALID;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE trading.orders ADD CONSTRAINT ck_orders_price CHECK (price > 0) NOT VALID;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE trading.orders ADD CONSTRAINT ck_orders_ticker CHECK (ticker IS NOT NULL) NOT VALID;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE trading.orders ADD CONSTRAINT fk_orders_instrument
        FOREIGN KEY (ticker) REFERENCES trading.instrument (symbol) NOT VALID;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- The blotter reads one account newest first; the poller reads working orders by symbol.
DROP INDEX IF EXISTS trading.idx_orders_account;
CREATE INDEX IF NOT EXISTS idx_orders_account_received ON trading.orders (account_id, received_at DESC);
CREATE INDEX IF NOT EXISTS idx_orders_working ON trading.orders (ticker) WHERE status = 'NEW';

COMMIT;
