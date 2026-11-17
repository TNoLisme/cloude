-- Synthetic roles and dependency probes for limit/admin tests; never a production audit schema.
CREATE ROLE banking_migrator LOGIN PASSWORD 'test_migrator' CREATEDB;
CREATE ROLE banking_app LOGIN PASSWORD 'test_app';
GRANT CONNECT ON DATABASE test TO banking_app, banking_migrator;
GRANT CREATE ON DATABASE test TO banking_migrator;
CREATE SCHEMA banking AUTHORIZATION banking_migrator;
GRANT USAGE ON SCHEMA banking TO banking_app;
CREATE SCHEMA limit_test AUTHORIZATION banking_migrator;
SET ROLE banking_migrator;
CREATE TABLE limit_test.business_probe (id BIGINT PRIMARY KEY);
CREATE TABLE limit_test.audit_event (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    action TEXT NOT NULL,
    correlation_id TEXT NOT NULL
);
GRANT USAGE ON SCHEMA limit_test TO banking_app;
GRANT SELECT, INSERT ON limit_test.business_probe, limit_test.audit_event TO banking_app;
GRANT USAGE, SELECT ON SEQUENCE limit_test.audit_event_id_seq TO banking_app;
RESET ROLE;
