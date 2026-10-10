-- Test seed data for H2

-- Insert test users (letting the database auto-generate user_id)
INSERT INTO auth.users (uuid, first_name, middle_name, last_name, username, email, password_hash, roles)
VALUES (RANDOM_UUID(), 'Test', NULL, 'User', 'testuser', 'test@example.com', 'not-a-real-hash', 'CUSTOMER');

INSERT INTO auth.users (uuid, first_name, middle_name, last_name, username, email, password_hash, roles)
VALUES (RANDOM_UUID(), 'Second', 'T', 'User', 'seconduser', 'second@example.com', 'not-a-real-hash', 'CUSTOMER');

-- Insert test accounts (letting the database auto-generate account_id)
INSERT INTO account (user_id, currency, cash_balance, status, version)
VALUES (1, 'USD', 10000.00, 'ACTIVE', 0);

INSERT INTO account (user_id, currency, cash_balance, status, version)
VALUES (2, 'USD', 5000.00, 'ACTIVE', 0);

-- Insert test instruments (letting the database auto-generate instrument_id)
INSERT INTO instrument (symbol, ticker, asset_class, quote_currency, status)
VALUES ('AAPL', 'AAPL', 'EQUITY', 'USD', 'ACTIVE');

INSERT INTO instrument (symbol, ticker, asset_class, quote_currency, status)
VALUES ('GOOGL', 'GOOGL', 'EQUITY', 'USD', 'ACTIVE');

INSERT INTO instrument (symbol, ticker, asset_class, quote_currency, status)
VALUES ('DELISTED', 'DELISTED', 'EQUITY', 'USD', 'DELISTED');

-- Insert test holdings (letting the database auto-generate holding_id)
INSERT INTO holding (account_id, ticker, quantity, average_price, as_of_date)
VALUES (1, 'AAPL', 100.00000000, 150.00000000, CAST(CURRENT_DATE AS DATE));

-- Insert test orders (letting the database auto-generate order_id)
INSERT INTO orders (account_id, instrument_id, ticker, side, quantity, price, status, received_at, idempotency_key, executed_price, executed_on)
VALUES (1, 1, 'AAPL', 'BUY', 10.00000000, 150.00000000, 'FILLED', CURRENT_TIMESTAMP, 'idempotency-key-1', 150.00000000, CURRENT_TIMESTAMP);

INSERT INTO orders (account_id, instrument_id, ticker, side, quantity, price, status, received_at, idempotency_key)
VALUES (1, 1, 'AAPL', 'SELL', 5.00000000, 160.00000000, 'NEW', CURRENT_TIMESTAMP, 'idempotency-key-2');