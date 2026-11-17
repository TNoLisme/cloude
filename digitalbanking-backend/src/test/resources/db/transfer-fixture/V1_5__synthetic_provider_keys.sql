-- Test-only stand-ins. These are not provider migrations and do not prove their contracts.
CREATE TABLE banking.customer (customer_id BIGINT PRIMARY KEY);
CREATE TABLE banking.app_user (user_id BIGINT PRIMARY KEY);
CREATE TABLE banking.account (account_id BIGINT PRIMARY KEY);
CREATE TABLE banking.auth_challenge (challenge_id BIGINT PRIMARY KEY);
INSERT INTO banking.customer (customer_id) VALUES (1);
INSERT INTO banking.app_user (user_id) VALUES (2);
INSERT INTO banking.account (account_id) VALUES (3), (4), (5);
INSERT INTO banking.auth_challenge (challenge_id) VALUES (6), (7);
