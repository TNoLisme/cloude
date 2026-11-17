-- Test-only stand-in for the not-yet-implemented Account module.
-- Identity (V2-V4) and Customer (V5) migrations now own app_user, auth_challenge and
-- customer, so this fixture no longer creates those tables; only Account is synthetic.
-- Not a provider migration and does not prove the Account contract.
CREATE TABLE banking.account (account_id BIGINT PRIMARY KEY);
