-- ============================================================================
-- Seed data (run AFTER schema.sql against an empty trading_system_db)
-- Demo client credentials:  demo  / Capstone@2026
-- Demo admin credentials:   admin / Admin@2026
--
-- The demo client is seeded already approved and funded (user and account both
-- ACTIVE) so the platform is usable straight after a bootstrap. A user who
-- registers through the UI is created INACTIVE at zero cash and stays
-- untradeable until an admin approves them and deposits funds.
-- ============================================================================

-- Demo client. password_hash is bcrypt(cost 12) of "Capstone@2026".
INSERT INTO auth.users (uuid, first_name, middle_name, last_name, username, email, password_hash,
                       phone_country_code, phone_number, country_code, country, risk_appetite,
                       roles, status, email_verified_on, approved_on)
VALUES (gen_random_uuid(), 'Demo', NULL, 'Investor', 'demo', 'demo@example.com',
        '$2b$12$xIe1rbClDbcPm5uHEOsiC.xYgmOvUzFrwc2LlACuZ.7WDtdKOnPq2',
        '+91', '9876543210', 'IN', 'India', 'BALANCED',
        ARRAY['CUSTOMER'], 'ACTIVE', now(), now());

-- Demo administrator. password_hash is bcrypt(cost 12) of "Admin@2026". The admin
-- signs in through the normal login page and is routed to /admin. An administrator
-- does not trade, so has no trading account: the Trade REST API accepts the ADMIN
-- role without one.
INSERT INTO auth.users (uuid, first_name, middle_name, last_name, username, email, password_hash,
                       phone_country_code, phone_number, country_code, country, risk_appetite,
                       roles, status, email_verified_on, approved_on)
VALUES (gen_random_uuid(), 'Ada', NULL, 'Admin', 'admin', 'admin@example.com',
        '$2b$12$O9K97bgmFbDnm/2YyYhXzupTtGTSiACLqD21rfzAHkdVdjQV.YV82',
        '+1', '2025550147', 'US', 'United States', 'AGGRESSIVE',
        ARRAY['ADMIN'], 'ACTIVE', now(), now());

-- Three registered-but-unapproved customers, so the admin console's pending queue
-- is a queue and the "awaiting approval" sign-in path works out of the box. Each
-- is on a different currency, which follows the country chosen at registration.
INSERT INTO auth.users (uuid, first_name, middle_name, last_name, username, email, password_hash,
                       phone_country_code, phone_number, country_code, country, risk_appetite,
                       roles, status, email_verified_on)
VALUES (gen_random_uuid(), 'Pending', NULL, 'Patron', 'pending', 'pending@example.com',
        '$2b$12$xIe1rbClDbcPm5uHEOsiC.xYgmOvUzFrwc2LlACuZ.7WDtdKOnPq2',
        '+44', '7700900123', 'GB', 'United Kingdom', 'CONSERVATIVE',
        ARRAY['CUSTOMER'], 'INACTIVE', now()),
       (gen_random_uuid(), 'Awaiting', NULL, 'Review', 'awaiting', 'awaiting@example.com',
        '$2b$12$xIe1rbClDbcPm5uHEOsiC.xYgmOvUzFrwc2LlACuZ.7WDtdKOnPq2',
        '+91', '9812345678', 'IN', 'India', 'AGGRESSIVE',
        ARRAY['CUSTOMER'], 'INACTIVE', now()),
       (gen_random_uuid(), 'Newcomer', NULL, 'Prospect', 'newcomer', 'newcomer@example.com',
        '$2b$12$xIe1rbClDbcPm5uHEOsiC.xYgmOvUzFrwc2LlACuZ.7WDtdKOnPq2',
        '+65', '81234567', 'SG', 'Singapore', 'BALANCED',
        ARRAY['CUSTOMER'], 'INACTIVE', now());

-- Trading accounts, looked up by username so the seed does not depend on the
-- order rows were numbered in. account_id 1 is the demo client.
INSERT INTO trading.account (user_id, currency, cash_balance, status, version)
SELECT u.user_id, v.currency, v.cash, v.status, 1
  FROM (VALUES ('demo',     'USD', 100000.00, 'ACTIVE'),
               ('pending',  'GBP',      0.00, 'INACTIVE'),
               ('awaiting', 'INR',      0.00, 'INACTIVE'),
               ('newcomer', 'SGD',      0.00, 'INACTIVE')) AS v (username, currency, cash, status)
  JOIN auth.users u ON u.username = v.username
 ORDER BY CASE v.username WHEN 'demo' THEN 0 ELSE 1 END, u.user_id;

-- The demo account's opening funds, recorded like any other deposit.
INSERT INTO trading.balance_adjustment (account_id, amount, reason, adjusted_by)
SELECT a.account_id, 100000.00 + 7500.00, 'Seeded opening balance', admin.user_id
  FROM trading.account a
  JOIN auth.users u ON u.user_id = a.user_id AND u.username = 'demo'
  JOIN auth.users admin ON admin.username = 'admin';

INSERT INTO trading.instrument (symbol, ticker, asset_class, quote_currency, status)
VALUES ('AAPL', 'AAPL', 'EQUITY', 'USD', 'ACTIVE'),
       ('MSFT', 'MSFT', 'EQUITY', 'USD', 'ACTIVE'),
       ('GOOG', 'GOOG', 'EQUITY', 'USD', 'ACTIVE'),
       ('AMZN', 'AMZN', 'EQUITY', 'USD', 'ACTIVE'),
       ('TSLA', 'TSLA', 'EQUITY', 'USD', 'ACTIVE'),
       ('INFY.NS', 'INFY', 'EQUITY', 'INR', 'ACTIVE');

-- The demo account holds 50 AAPL, and the order history explains it: one filled
-- buy of 50 at 150.00. Cash above is the 107,500 deposited less the 7,500 spent.
INSERT INTO trading.orders (account_id, ticker, side, quantity, price, order_type, status,
                            received_at, idempotency_key, executed_price, executed_on)
SELECT a.account_id, 'AAPL', 'BUY', 50, 150.00, 'LIMIT', 'FILLED',
       now() - interval '1 day', 'seed-0001', 150.00, now() - interval '1 day'
  FROM trading.account a JOIN auth.users u ON u.user_id = a.user_id AND u.username = 'demo';

INSERT INTO trading.holding (account_id, ticker, quantity, average_price, as_of_date)
SELECT a.account_id, 'AAPL', 50, 150.00, CURRENT_DATE
  FROM trading.account a JOIN auth.users u ON u.user_id = a.user_id AND u.username = 'demo';

-- Approval history for the two seeded ACTIVE users.
INSERT INTO auth.status_change_log (user_id, old_status, new_status, reason, changed_by)
SELECT u.user_id, 'INACTIVE', 'ACTIVE', v.reason, admin.user_id
  FROM (VALUES ('demo', 'Seeded demo account'),
               ('admin', 'Seeded administrator account')) AS v (username, reason)
  JOIN auth.users u ON u.username = v.username
  JOIN auth.users admin ON admin.username = 'admin';
