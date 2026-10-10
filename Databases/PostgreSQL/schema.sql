-- ============================================================================
-- Trading platform schema (fresh install)
-- Database: trading_system_db
-- Two schemas: trading (owned by the Trade REST API) and auth (auth service).
--
-- Run once against an empty database, then seed_data.sql. This file never drops
-- anything: to start over, run reset.sql first, deliberately. An existing
-- database is brought up to date with the numbered files in migrations/.
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS trading;
CREATE SCHEMA IF NOT EXISTS auth;

-- Unqualified table names ("account", "orders") resolve to the trading schema
-- for the role that runs this script, in this database, whatever they are called.
DO $$
BEGIN
    EXECUTE format('ALTER ROLE %I IN DATABASE %I SET search_path TO trading, public',
                   current_user, current_database());
END
$$;

-- ============================================================================
-- Auth schema
-- ============================================================================

-- Users are created by the auth service once the email OTP is verified. user_id
-- is the internal key; uuid is the stable identifier carried in the JWT "sub".
-- roles is the single source of truth for what a user may do: it is what the
-- JWT carries and what every service checks.
--
-- The status column is the sign-in gate. Until an admin approves the user the
-- row is INACTIVE and no session is issued.
CREATE TABLE auth.users (
    user_id            BIGSERIAL PRIMARY KEY,
    uuid               UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    first_name         TEXT NOT NULL,
    middle_name        TEXT,
    last_name          TEXT NOT NULL,
    username           VARCHAR(64) NOT NULL,
    email              VARCHAR(255) NOT NULL,
    password_hash      TEXT NOT NULL,
    phone_country_code VARCHAR(8) NOT NULL,
    phone_number       VARCHAR(32) NOT NULL,
    country_code       VARCHAR(8) NOT NULL,
    country            VARCHAR(64) NOT NULL,
    risk_appetite      VARCHAR(16) NOT NULL
                       CHECK (risk_appetite IN ('CONSERVATIVE', 'BALANCED', 'AGGRESSIVE')),
    roles              TEXT[] NOT NULL DEFAULT ARRAY['CUSTOMER']
                       CHECK (cardinality(roles) > 0 AND roles <@ ARRAY['CUSTOMER', 'ADMIN']),
    status             VARCHAR(20) NOT NULL DEFAULT 'INACTIVE'
                       CHECK (status IN ('INACTIVE', 'ACTIVE', 'SUSPENDED', 'REJECTED')),
    email_verified_on  TIMESTAMPTZ,
    approved_on        TIMESTAMPTZ,
    approved_by        BIGINT REFERENCES auth.users (user_id) ON DELETE SET NULL,
    rejected_on        TIMESTAMPTZ,
    rejection_reason   VARCHAR(255),
    suspended_on       TIMESTAMPTZ,
    suspended_by       BIGINT REFERENCES auth.users (user_id) ON DELETE SET NULL,
    created_on         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Usernames and emails are unique regardless of case: the auth service compares
-- them with lower() at registration and at sign-in, and so does the database.
CREATE UNIQUE INDEX uq_users_username_ci ON auth.users (lower(username));
CREATE UNIQUE INDEX uq_users_email_ci ON auth.users (lower(email));
CREATE INDEX idx_users_status ON auth.users (status);

-- In-flight registrations. A row here is a *candidate* user: everything needed to
-- create auth.users plus the hashed OTP, before any user exists. Verification
-- consumes the row inside the transaction that inserts auth.users, so a replayed
-- code cannot mint a second account.
CREATE TABLE auth.registration_challenge (
    challenge_id       BIGSERIAL PRIMARY KEY,
    email              VARCHAR(255) NOT NULL,
    username           VARCHAR(64) NOT NULL,
    first_name         TEXT NOT NULL,
    middle_name        TEXT,
    last_name          TEXT NOT NULL,
    password_hash      TEXT NOT NULL,
    phone_country_code VARCHAR(8) NOT NULL,
    phone_number       VARCHAR(32) NOT NULL,
    country_code       VARCHAR(8) NOT NULL,
    country            VARCHAR(64) NOT NULL,
    risk_appetite      VARCHAR(16) NOT NULL
                       CHECK (risk_appetite IN ('CONSERVATIVE', 'BALANCED', 'AGGRESSIVE')),
    otp_hash           TEXT NOT NULL,
    attempts           INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    expires_at         TIMESTAMPTZ NOT NULL,
    verified_on        TIMESTAMPTZ,
    consumed_on        TIMESTAMPTZ,
    created_on         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_registration_challenge_email ON auth.registration_challenge (lower(email));
CREATE INDEX idx_registration_challenge_expiry ON auth.registration_challenge (expires_at);

-- Every approval, rejection, suspension and reinstatement, so the admin console
-- can explain a status rather than only show it.
CREATE TABLE auth.status_change_log (
    log_id     BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES auth.users (user_id) ON DELETE CASCADE,
    old_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,
    reason     VARCHAR(255),
    changed_by BIGINT REFERENCES auth.users (user_id) ON DELETE SET NULL,
    changed_on TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_status_change_log_user ON auth.status_change_log (user_id, changed_on DESC);

-- Refresh tokens are stored hashed (SHA-256), so read access to the database is
-- not session takeover. Rotated on every refresh; presenting a rotated token
-- again revokes the whole family.
CREATE TABLE auth.refresh_token (
    refresh_token_id BIGSERIAL PRIMARY KEY,
    user_id          BIGINT NOT NULL REFERENCES auth.users (user_id) ON DELETE CASCADE,
    token_hash       TEXT NOT NULL UNIQUE,
    family_id        UUID NOT NULL DEFAULT gen_random_uuid(),
    expires_at       TIMESTAMPTZ NOT NULL,
    revoked_on       TIMESTAMPTZ,
    created_on       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_token_user ON auth.refresh_token (user_id);
CREATE INDEX idx_refresh_token_family ON auth.refresh_token (family_id);
CREATE INDEX idx_refresh_token_expiry ON auth.refresh_token (expires_at);

-- A pending password reset. The code only ever revokes the session and changes
-- the password it was issued for; consuming the row in the same transaction
-- that updates auth.users means a replayed code cannot reset the password twice.
CREATE TABLE auth.password_reset_challenge (
    challenge_id BIGSERIAL PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES auth.users (user_id) ON DELETE CASCADE,
    email        VARCHAR(255) NOT NULL,
    otp_hash     TEXT NOT NULL,
    attempts     INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_on  TIMESTAMPTZ,
    created_on   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_password_reset_challenge_user ON auth.password_reset_challenge (user_id);
CREATE INDEX idx_password_reset_challenge_expiry ON auth.password_reset_challenge (expires_at);

-- ============================================================================
-- Trading schema
-- ============================================================================

-- One account per user. It opens INACTIVE with zero cash, mirroring the user's
-- status; an admin approves it and funds it separately. Approving, suspending,
-- reinstating or rejecting a user moves the account in the same transaction, so
-- the Trade REST API's ACTIVE check is enough. version increments on every update
-- (optimistic locking by the executor).
CREATE TABLE trading.account (
    account_id   BIGSERIAL PRIMARY KEY,
    user_id      BIGINT NOT NULL UNIQUE REFERENCES auth.users (user_id) ON DELETE CASCADE,
    currency     CHAR(3) NOT NULL DEFAULT 'USD' CHECK (currency ~ '^[A-Z]{3}$'),
    cash_balance NUMERIC(19,2) NOT NULL DEFAULT 0.00 CHECK (cash_balance >= 0),
    status       VARCHAR(20) NOT NULL DEFAULT 'INACTIVE'
                 CHECK (status IN ('INACTIVE', 'ACTIVE', 'SUSPENDED', 'CLOSED', 'REJECTED')),
    version      BIGINT NOT NULL DEFAULT 1,
    closed_at    TIMESTAMPTZ
);

CREATE INDEX idx_account_status ON trading.account (status);

-- Every deposit and withdrawal an administrator makes, with the reason and who
-- made it. The balance itself lives on the account.
CREATE TABLE trading.balance_adjustment (
    adjustment_id BIGSERIAL PRIMARY KEY,
    account_id    BIGINT NOT NULL REFERENCES trading.account (account_id) ON DELETE CASCADE,
    amount        NUMERIC(19,2) NOT NULL CHECK (amount <> 0),
    reason        VARCHAR(255) NOT NULL,
    adjusted_by   BIGINT REFERENCES auth.users (user_id) ON DELETE SET NULL,
    adjusted_on   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_balance_adjustment_account ON trading.balance_adjustment (account_id, adjusted_on DESC);

-- Instruments are keyed by symbol in the market-data scheme (AAPL, INFY.NS).
-- Orders and holdings reference the symbol, so a symbol must be catalogued
-- before it can be traded; the Trade REST API registers one it discovers.
CREATE TABLE trading.instrument (
    instrument_id  BIGSERIAL PRIMARY KEY,
    symbol         VARCHAR(20) NOT NULL UNIQUE,
    ticker         VARCHAR(20) NOT NULL,
    asset_class    VARCHAR(32) NOT NULL DEFAULT 'EQUITY',
    quote_currency CHAR(3) NOT NULL DEFAULT 'USD',
    status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELISTED')),
    delisted_at    TIMESTAMPTZ
);

-- Quantities are whole units; average_price keeps eight places so the weighted
-- average does not drift as fills accumulate.
CREATE TABLE trading.holding (
    holding_id    BIGSERIAL PRIMARY KEY,
    account_id    BIGINT NOT NULL REFERENCES trading.account (account_id) ON DELETE CASCADE,
    ticker        VARCHAR(20) NOT NULL REFERENCES trading.instrument (symbol),
    quantity      NUMERIC(30,8) NOT NULL CHECK (quantity >= 0 AND quantity = trunc(quantity)),
    average_price NUMERIC(19,8) NOT NULL CHECK (average_price >= 0),
    as_of_date    DATE NOT NULL,
    CONSTRAINT uq_holding_account_ticker UNIQUE (account_id, ticker)
);

CREATE TABLE trading.orders (
    order_id         BIGSERIAL PRIMARY KEY,
    account_id       BIGINT NOT NULL REFERENCES trading.account (account_id) ON DELETE CASCADE,
    ticker           VARCHAR(20) NOT NULL REFERENCES trading.instrument (symbol),
    side             VARCHAR(4) NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity         NUMERIC(30,8) NOT NULL CHECK (quantity > 0 AND quantity = trunc(quantity)),
    price            NUMERIC(19,2) NOT NULL CHECK (price > 0),
    order_type       VARCHAR(16) NOT NULL DEFAULT 'LIMIT' CHECK (order_type IN ('LIMIT', 'MARKET')),
    status           VARCHAR(16) NOT NULL DEFAULT 'NEW'
                     CHECK (status IN ('NEW', 'FILLED', 'REJECTED', 'CANCELLED')),
    received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    idempotency_key  VARCHAR(255) NOT NULL UNIQUE,
    executed_price   NUMERIC(19,8),
    executed_on      TIMESTAMPTZ,
    rejection_reason VARCHAR(255),
    CONSTRAINT ck_orders_fill CHECK (status <> 'FILLED' OR (executed_price IS NOT NULL AND executed_on IS NOT NULL))
);

-- The blotter reads one account's orders newest first.
CREATE INDEX idx_orders_account_received ON trading.orders (account_id, received_at DESC);
-- The executor's poller reads working orders by symbol every tick.
CREATE INDEX idx_orders_working ON trading.orders (ticker) WHERE status = 'NEW';

-- ============================================================================
-- Watchlists
-- ============================================================================

-- Any number of named watchlists per client account (administrators have no
-- account, so none). A name is unique within an account.
CREATE TABLE IF NOT EXISTS trading.watchlist (
    watchlist_id BIGSERIAL PRIMARY KEY,
    account_id   BIGINT NOT NULL REFERENCES trading.account (account_id) ON DELETE CASCADE,
    name         VARCHAR(40) NOT NULL DEFAULT 'My Watchlist',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_watchlist_account_name
    ON trading.watchlist (account_id, name);

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
                       CONSTRAINT customer_preference_alert_channel_check
                       CHECK (alert_channel IN ('NONE', 'EMAIL', 'PUSH', 'BOTH')),
    alert_contact      VARCHAR(255),
    display_currency   CHAR(3) CHECK (display_currency IS NULL OR display_currency ~ '^[A-Z]{3}$'),
    theme              VARCHAR(5) NOT NULL DEFAULT 'dark'
                       CONSTRAINT customer_preference_theme_check CHECK (theme IN ('dark', 'light')),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A channel that delivers needs somewhere to deliver to, and a channel that
    -- does not must not carry a stale destination left over from the choice
    -- before it. ONE row, ONE destination: the shape is fixed by the channel.
    -- In-app needs no destination; email and both need an email address.
    CONSTRAINT ck_customer_preference_contact_shape
        CHECK ((alert_channel IN ('NONE', 'PUSH') AND alert_contact IS NULL)
            OR (alert_channel IN ('EMAIL', 'BOTH') AND alert_contact IS NOT NULL))
);

-- ============================================================================
-- Customer notifications
-- ============================================================================

-- Per notification type delivery preference: one row per (account, category)
-- saying where that kind of notification should go. 'INHERIT' (or no row at
-- all) falls back to customer_preference.alert_channel / alert_contact, so a
-- customer who only ever set the single global channel keeps being reached.
-- 'NONE' is an explicit "never deliver this category", which beats an
-- inherited channel that would send it.
CREATE TABLE IF NOT EXISTS trading.notification_preference (
    account_id BIGINT NOT NULL REFERENCES trading.account (account_id) ON DELETE CASCADE,
    category   VARCHAR(16) NOT NULL
               CONSTRAINT notification_preference_category_check
               CHECK (category IN ('ACCOUNT_RELATED', 'ORDER_RELATED', 'PRICE_RELATED')),
    channel    VARCHAR(16) NOT NULL DEFAULT 'INHERIT'
               CONSTRAINT notification_preference_channel_check
               CHECK (channel IN ('INHERIT', 'NONE', 'EMAIL', 'PUSH', 'BOTH')),
    contact    VARCHAR(255),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, category),
    -- Same shape rule as customer_preference: a channel that delivers needs a
    -- destination, and one that does not must not carry a stale destination.
    CONSTRAINT ck_notification_preference_contact_shape
        CHECK ((channel IN ('INHERIT', 'NONE', 'PUSH') AND contact IS NULL)
            OR (channel IN ('EMAIL', 'BOTH') AND contact IS NOT NULL))
);

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
    -- not: an email address is personal data and this
    -- table is durable, while the contact is read fresh at send time anyway.
    channel         VARCHAR(8) NOT NULL DEFAULT 'NONE'
                    CONSTRAINT notification_channel_check
                    CHECK (channel IN ('NONE', 'EMAIL', 'PUSH', 'BOTH')),
    status          VARCHAR(12) NOT NULL DEFAULT 'QUEUED'
                    CHECK (status IN ('QUEUED', 'SENT', 'FAILED', 'SUPPRESSED')),
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_event UNIQUE (event_id)
);

-- The inbox read is newest first for one account.
CREATE INDEX IF NOT EXISTS idx_notification_account_created
    ON trading.notification (account_id, created_at DESC);

-- ============================================================================
-- Watchlists — price alerts
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

-- ============================================================================
-- Market data through the poller (migration 009)
-- ============================================================================


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

-- ============================================================================
-- Automated strategy execution (migration 010)
-- ============================================================================


CREATE TABLE IF NOT EXISTS trading.trading_strategy (
    strategy_id          BIGSERIAL PRIMARY KEY,
    account_id           BIGINT NOT NULL REFERENCES trading.account (account_id) ON DELETE CASCADE,
    name                 VARCHAR(60) NOT NULL,
    symbol               VARCHAR(20) NOT NULL,
    strategy_type        VARCHAR(20) NOT NULL
                         CHECK (strategy_type IN ('PRICE_TARGET', 'MA_CROSSOVER', 'BOLLINGER')),
    -- Price target: buy or sell once the price reaches the target, at market or as a
    -- limit order at the target.
    side                 VARCHAR(4) CHECK (side IN ('BUY', 'SELL')),
    target_price         NUMERIC(19,8) CHECK (target_price > 0),
    order_type           VARCHAR(6) NOT NULL DEFAULT 'MARKET' CHECK (order_type IN ('MARKET', 'LIMIT')),
    -- Moving-average crossover: the short and long windows, in daily closes.
    short_window         INTEGER,
    long_window          INTEGER,
    -- Bollinger bands: the averaging period and the width in standard deviations.
    band_period          INTEGER,
    band_k               NUMERIC(4,2),
    -- What a trigger trades, and the limits on it. Checked again at the moment of
    -- decision, never only when the strategy is saved.
    quantity             BIGINT NOT NULL CHECK (quantity > 0),
    max_notional         NUMERIC(19,2) NOT NULL CHECK (max_notional > 0),
    max_orders_per_day   INTEGER NOT NULL DEFAULT 5 CHECK (max_orders_per_day > 0),
    -- The most shares of the symbol the account may hold after a strategy BUY.
    max_position         BIGINT NOT NULL CHECK (max_position > 0),
    status               VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
                         CHECK (status IN ('ACTIVE', 'DISABLED', 'STOPPED', 'COMPLETED')),
    stop_reason          VARCHAR(200),
    -- The last evaluated relation (ABOVE/BELOW for a crossover; ABOVE/INSIDE/BELOW
    -- for bands). A trigger is a change of state, so the first evaluation only
    -- records where things stand and never trades.
    last_state           VARCHAR(10),
    last_price           NUMERIC(19,8),
    last_evaluated_at    TIMESTAMPTZ,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_trading_strategy_active_symbol
    ON trading.trading_strategy (symbol) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_trading_strategy_account
    ON trading.trading_strategy (account_id, created_at DESC);

-- Every decision a strategy took on a quote: an order placed, a trade refused by a
-- limit, or a failure. One row per strategy and market-data event, so a redelivered
-- quote cannot trade twice.
CREATE TABLE IF NOT EXISTS trading.strategy_execution (
    execution_id   BIGSERIAL PRIMARY KEY,
    strategy_id    BIGINT NOT NULL REFERENCES trading.trading_strategy (strategy_id) ON DELETE CASCADE,
    event_id       VARCHAR(64) NOT NULL,
    signal         VARCHAR(4) NOT NULL CHECK (signal IN ('BUY', 'SELL')),
    price          NUMERIC(19,8),
    quantity       BIGINT NOT NULL,
    status         VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'PLACED', 'SKIPPED', 'FAILED')),
    order_id       VARCHAR(40),
    reason         VARCHAR(300),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_strategy_execution_event UNIQUE (strategy_id, event_id)
);

CREATE INDEX IF NOT EXISTS idx_strategy_execution_strategy
    ON trading.strategy_execution (strategy_id, created_at DESC);
