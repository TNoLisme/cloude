\set ON_ERROR_STOP on
\getenv banking_app_password BANKING_APP_PASSWORD
\getenv banking_migration_password BANKING_MIGRATION_PASSWORD
SELECT current_database() AS db_name \gset

SELECT format('CREATE ROLE banking_app LOGIN PASSWORD %L', :'banking_app_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'banking_app')
\gexec

SELECT format('CREATE ROLE banking_migrator LOGIN PASSWORD %L', :'banking_migration_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'banking_migrator')
\gexec

GRANT CONNECT ON DATABASE :"db_name" TO banking_app, banking_migrator;
GRANT CREATE ON DATABASE :"db_name" TO banking_migrator;
CREATE SCHEMA IF NOT EXISTS banking AUTHORIZATION banking_migrator;
ALTER SCHEMA banking OWNER TO banking_migrator;
GRANT USAGE ON SCHEMA banking TO banking_app;
ALTER ROLE banking_app SET search_path TO banking;
ALTER ROLE banking_migrator SET search_path TO banking;
ALTER DEFAULT PRIVILEGES FOR ROLE banking_migrator IN SCHEMA banking
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO banking_app;
ALTER DEFAULT PRIVILEGES FOR ROLE banking_migrator IN SCHEMA banking
  GRANT USAGE, SELECT ON SEQUENCES TO banking_app;
