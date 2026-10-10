-- ============================================================================
-- DESTRUCTIVE: drops every table and all data in the trading and auth schemas.
--
-- Kept out of schema.sql on purpose, so building a database and wiping one are
-- two different commands. Use it only to start a local database over:
--
--   psql -U postgres -d trading_system_db -f reset.sql
--   psql -U postgres -d trading_system_db -f schema.sql
--   psql -U postgres -d trading_system_db -f seed_data.sql
-- ============================================================================

DROP SCHEMA IF EXISTS trading CASCADE;
DROP SCHEMA IF EXISTS auth CASCADE;
