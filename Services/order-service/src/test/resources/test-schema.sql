-- Test schema for H2 (PostgreSQL compatibility mode)

CREATE SCHEMA IF NOT EXISTS auth;

CREATE TABLE IF NOT EXISTS auth.users (
    user_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uuid UUID NOT NULL UNIQUE,
    first_name VARCHAR(100),
    middle_name VARCHAR(100),
    last_name VARCHAR(100),
    username VARCHAR(100) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    roles VARCHAR(500) NOT NULL DEFAULT 'CUSTOMER',
    created_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS account (
    account_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    currency CHAR(3) NOT NULL,
    cash_balance NUMERIC(18,2) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    closed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_account_user FOREIGN KEY (user_id) REFERENCES auth.users(user_id)
);

CREATE TABLE IF NOT EXISTS instrument (
    instrument_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    symbol VARCHAR(50) NOT NULL UNIQUE,
    ticker VARCHAR(255) NOT NULL UNIQUE,
    asset_class VARCHAR(30) NOT NULL,
    quote_currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    delisted_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS orders (
    order_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL,
    instrument_id BIGINT,
    -- trading.orders keys the instrument by ticker; instrument_id is a test-only legacy column,
    -- nullable because the real insert (OrderMapper.insert) never sets it.
    ticker VARCHAR(20),
    side VARCHAR(4) NOT NULL,
    quantity NUMERIC(18,8) NOT NULL,
    price NUMERIC(18,8) NOT NULL,
    order_type VARCHAR(16) NOT NULL DEFAULT 'LIMIT',
    status VARCHAR(20) NOT NULL DEFAULT 'NEW',
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    executed_price NUMERIC(19,8),
    executed_on TIMESTAMP WITH TIME ZONE,
    rejection_reason VARCHAR(255),
    CONSTRAINT fk_order_account FOREIGN KEY (account_id) REFERENCES account(account_id),
    CONSTRAINT fk_order_instrument FOREIGN KEY (instrument_id) REFERENCES instrument(instrument_id),
    CONSTRAINT ck_orders_fill CHECK (status <> 'FILLED' OR (executed_price IS NOT NULL AND executed_on IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS holding (
    holding_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL,
    ticker VARCHAR(255) NOT NULL,
    quantity NUMERIC(18,8) NOT NULL,
    average_price NUMERIC(18,8) NOT NULL,
    as_of_date DATE NOT NULL,
    CONSTRAINT fk_holding_account FOREIGN KEY (account_id) REFERENCES account(account_id),
    UNIQUE (account_id, ticker)
);

-- Customer preferences (Sprint 10 module 1). Mirrors trading.customer_preference
-- in schema.sql; H2 has no ~ operator, so the currency check is a length check.
CREATE TABLE IF NOT EXISTS customer_preference (
    account_id BIGINT PRIMARY KEY,
    default_account_id BIGINT,
    alert_channel VARCHAR(16) NOT NULL DEFAULT 'NONE',
    alert_contact VARCHAR(255),
    display_currency CHAR(3),
    theme VARCHAR(5) DEFAULT 'dark' NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_customer_preference_account FOREIGN KEY (account_id) REFERENCES account(account_id),
    CONSTRAINT fk_customer_preference_default_account FOREIGN KEY (default_account_id) REFERENCES account(account_id),
    CONSTRAINT ck_customer_preference_channel CHECK (alert_channel IN ('NONE','EMAIL','PUSH','BOTH')),
    CONSTRAINT ck_customer_preference_theme CHECK (theme IN ('dark','light')),
    CONSTRAINT ck_customer_preference_currency CHECK (display_currency IS NULL OR LENGTH(display_currency) = 3),
    CONSTRAINT ck_customer_preference_contact_shape
        CHECK ((alert_channel IN ('NONE','PUSH') AND alert_contact IS NULL)
            OR (alert_channel IN ('EMAIL','BOTH') AND alert_contact IS NOT NULL))
);

-- Per-category delivery choice (migration 007). Mirrors trading.notification_preference;
-- INHERIT or an absent row falls back to customer_preference.
CREATE TABLE IF NOT EXISTS notification_preference (
    account_id BIGINT NOT NULL,
    category VARCHAR(16) NOT NULL,
    channel VARCHAR(16) NOT NULL DEFAULT 'INHERIT',
    contact VARCHAR(255),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, category),
    CONSTRAINT fk_notification_preference_account FOREIGN KEY (account_id) REFERENCES account(account_id),
    CONSTRAINT ck_notification_preference_category
        CHECK (category IN ('ACCOUNT_RELATED','ORDER_RELATED','PRICE_RELATED')),
    CONSTRAINT ck_notification_preference_channel CHECK (channel IN ('INHERIT','NONE','EMAIL','PUSH','BOTH')),
    CONSTRAINT ck_notification_preference_contact_shape
        CHECK ((channel IN ('INHERIT','NONE','PUSH') AND contact IS NULL)
            OR (channel IN ('EMAIL','BOTH') AND contact IS NOT NULL))
);

-- Customer notifications (Sprint 10 module 2). Mirrors trading.notification.
-- At-least-once Kafka redelivery of the same eventId must never create a second
-- row, so the event id is unique.
CREATE TABLE IF NOT EXISTS notification (
    notification_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    category VARCHAR(16) NOT NULL,
    symbol VARCHAR(20),
    title VARCHAR(255) NOT NULL,
    body VARCHAR(2000) NOT NULL,
    channel VARCHAR(8) NOT NULL DEFAULT 'NONE',
    status VARCHAR(12) NOT NULL DEFAULT 'QUEUED',
    read_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_notification_event UNIQUE (event_id),
    CONSTRAINT fk_notification_account FOREIGN KEY (account_id) REFERENCES account(account_id),
    CONSTRAINT ck_notification_category CHECK (category IN ('ORDER_FILLED','ORDER_REJECTED','ORDER_CANCELLED','PRICE_ALERT')),
    CONSTRAINT ck_notification_channel CHECK (channel IN ('NONE','EMAIL','PUSH','BOTH')),
    CONSTRAINT ck_notification_status CHECK (status IN ('QUEUED','SENT','FAILED','SUPPRESSED'))
);

CREATE INDEX IF NOT EXISTS idx_notification_account_created ON notification (account_id, created_at DESC);

-- Watchlists: price alerts (Sprint 10 module 3). Mirrors trading.price_alert.
CREATE TABLE IF NOT EXISTS price_alert (
    price_alert_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    direction VARCHAR(5) NOT NULL,
    threshold NUMERIC(19,8) NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    triggered_price NUMERIC(19,8),
    triggered_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_price_alert_account FOREIGN KEY (account_id) REFERENCES account(account_id),
    CONSTRAINT ck_price_alert_direction CHECK (direction IN ('ABOVE','BELOW')),
    CONSTRAINT ck_price_alert_threshold CHECK (threshold > 0),
    CONSTRAINT ck_price_alert_status CHECK (status IN ('ACTIVE','TRIGGERED','CANCELLED'))
);

CREATE INDEX IF NOT EXISTS idx_price_alert_active_symbol ON price_alert (symbol, status);
CREATE INDEX IF NOT EXISTS idx_price_alert_account_created ON price_alert (account_id, created_at DESC);

-- Market data through the poller (migration 009). Mirrors trading.market_*.
CREATE TABLE IF NOT EXISTS market_quote (
    symbol VARCHAR(20) PRIMARY KEY,
    price NUMERIC(19,8), bid NUMERIC(19,8), ask NUMERIC(19,8),
    currency CHAR(3), change NUMERIC(19,8), change_percent NUMERIC(10,4), previous_close NUMERIC(19,8),
    market_state VARCHAR(10), stale BOOLEAN NOT NULL DEFAULT false,
    quote_as_of TIMESTAMP WITH TIME ZONE, fetched_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS market_interest (
    symbol VARCHAR(20) NOT NULL, reason VARCHAR(10) NOT NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL, not_found_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (symbol, reason)
);
CREATE TABLE IF NOT EXISTS market_candles (
    symbol VARCHAR(20) PRIMARY KEY, currency CHAR(3), candles CLOB NOT NULL,
    fetched_on DATE NOT NULL, fetched_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS market_feed_status (
    id SMALLINT DEFAULT 1 PRIMARY KEY, last_success_at TIMESTAMP WITH TIME ZONE,
    last_failure_at TIMESTAMP WITH TIME ZONE, last_error VARCHAR(200),
    daily_quota INTEGER, used_today INTEGER, quota_checked_at TIMESTAMP WITH TIME ZONE
);

-- Automated strategy execution (migration 010). Mirrors trading.trading_strategy / strategy_execution.
CREATE TABLE IF NOT EXISTS trading_strategy (
    strategy_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL, name VARCHAR(60) NOT NULL, symbol VARCHAR(20) NOT NULL,
    strategy_type VARCHAR(20) NOT NULL, side VARCHAR(4), target_price NUMERIC(19,8),
    order_type VARCHAR(6) DEFAULT 'MARKET' NOT NULL, short_window INTEGER, long_window INTEGER,
    band_period INTEGER, band_k NUMERIC(4,2), quantity BIGINT NOT NULL, max_notional NUMERIC(19,2) NOT NULL,
    max_orders_per_day INTEGER DEFAULT 5 NOT NULL, max_position BIGINT NOT NULL,
    status VARCHAR(10) DEFAULT 'ACTIVE' NOT NULL,
    stop_reason VARCHAR(200), last_state VARCHAR(10), last_price NUMERIC(19,8),
    last_evaluated_at TIMESTAMP WITH TIME ZONE, consecutive_failures INTEGER DEFAULT 0 NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_strategy_account FOREIGN KEY (account_id) REFERENCES account(account_id)
);
CREATE TABLE IF NOT EXISTS strategy_execution (
    execution_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    strategy_id BIGINT NOT NULL, event_id VARCHAR(64) NOT NULL, signal VARCHAR(4) NOT NULL,
    price NUMERIC(19,8), quantity BIGINT NOT NULL, status VARCHAR(10) NOT NULL,
    order_id VARCHAR(40), reason VARCHAR(300),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uq_strategy_execution_event UNIQUE (strategy_id, event_id),
    CONSTRAINT fk_execution_strategy FOREIGN KEY (strategy_id) REFERENCES trading_strategy(strategy_id) ON DELETE CASCADE
);
