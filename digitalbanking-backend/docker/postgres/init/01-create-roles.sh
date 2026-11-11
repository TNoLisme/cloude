#!/usr/bin/env bash
set -euo pipefail

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=banking_app_password="$BANKING_APP_PASSWORD" \
  --set=banking_migration_password="$BANKING_MIGRATION_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE banking_app LOGIN PASSWORD %L', :'banking_app_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'banking_app')
\gexec

SELECT format('CREATE ROLE banking_migrator LOGIN PASSWORD %L', :'banking_migration_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'banking_migrator')
\gexec

GRANT CONNECT ON DATABASE digital_banking TO banking_app, banking_migrator;
GRANT CREATE ON DATABASE digital_banking TO banking_migrator;
CREATE SCHEMA IF NOT EXISTS banking AUTHORIZATION banking_migrator;
ALTER SCHEMA banking OWNER TO banking_migrator;
GRANT USAGE ON SCHEMA banking TO banking_app;
ALTER DEFAULT PRIVILEGES FOR ROLE banking_migrator IN SCHEMA banking
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO banking_app;
ALTER DEFAULT PRIVILEGES FOR ROLE banking_migrator IN SCHEMA banking
  GRANT USAGE, SELECT ON SEQUENCES TO banking_app;
SQL