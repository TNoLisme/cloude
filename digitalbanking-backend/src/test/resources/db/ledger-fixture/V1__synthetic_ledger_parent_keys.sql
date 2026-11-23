-- Minimal FK targets from database design §4.3.1 and §4.4.1. Test-only.
CREATE TABLE banking.account (account_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY);
CREATE TABLE banking.transfer (transfer_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY);

INSERT INTO banking.account OVERRIDING SYSTEM VALUE VALUES (1001), (1002);
