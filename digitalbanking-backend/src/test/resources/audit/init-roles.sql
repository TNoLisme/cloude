CREATE ROLE banking_migrator LOGIN PASSWORD 'test_migrator' CREATEDB;
CREATE ROLE banking_app LOGIN PASSWORD 'test_app';
CREATE SCHEMA audit_test;
CREATE TABLE audit_test.business_probe (id BIGINT PRIMARY KEY);
GRANT USAGE ON SCHEMA audit_test TO banking_app;
GRANT SELECT, INSERT ON audit_test.business_probe TO banking_app;
GRANT CONNECT ON DATABASE test TO banking_app, banking_migrator;
GRANT CREATE ON DATABASE test TO banking_migrator;
CREATE SCHEMA banking AUTHORIZATION banking_migrator;
GRANT USAGE ON SCHEMA banking TO banking_app;
ALTER DEFAULT PRIVILEGES FOR ROLE banking_migrator IN SCHEMA banking
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO banking_app;
ALTER DEFAULT PRIVILEGES FOR ROLE banking_migrator IN SCHEMA banking
  GRANT USAGE, SELECT ON SEQUENCES TO banking_app;
