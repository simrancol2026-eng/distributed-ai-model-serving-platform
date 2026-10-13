#!/usr/bin/env bash
# Creates the model-registry PostgreSQL role and database IF THEY DO NOT EXIST.
#
# Non-destructive by design: it never drops, recreates, truncates or alters an existing
# database or role (an existing role keeps its current password). Tables are NOT created
# here; the backend's Flyway migration (V1__registry.sql) creates them on first start.
#
# Run it as a PostgreSQL superuser, e.g. on Linux:
#   sudo -u postgres env PLATFORM_DB_PASSWORD='choose-one' bash scripts/setup_registry_db.sh
# Settings (environment variables):
#   PLATFORM_DB_NAME      database name   (default: platform)
#   PLATFORM_DB_USER      role name       (default: platform)
#   PLATFORM_DB_PASSWORD  role password   (required; read from the environment, never from argv)
#   PGHOST / PGPORT / PGUSER ...  standard libpq variables for the ADMIN connection
# Requires psql 15 or newer (uses \getenv).
set -euo pipefail

export PLATFORM_DB_NAME="${PLATFORM_DB_NAME:-platform}"
export PLATFORM_DB_USER="${PLATFORM_DB_USER:-platform}"
if [ -z "${PLATFORM_DB_PASSWORD:-}" ]; then
  echo "PLATFORM_DB_PASSWORD is not set." >&2
  exit 2
fi
for name in "$PLATFORM_DB_NAME" "$PLATFORM_DB_USER"; do
  if ! [[ "$name" =~ ^[a-z_][a-z0-9_]{0,62}$ ]]; then
    echo "Invalid name '$name': use lowercase letters, digits and '_'." >&2
    exit 2
  fi
done

# Values are read inside psql with \getenv and quoted with format(%I / %L), so the password
# never appears on a command line and names cannot inject SQL.
psql -v ON_ERROR_STOP=1 -X -q -d postgres <<'SQL'
\getenv db PLATFORM_DB_NAME
\getenv usr PLATFORM_DB_USER
\getenv pw PLATFORM_DB_PASSWORD

SELECT CASE WHEN EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'usr')
            THEN 'role ' || :'usr' || ': already exists, left unchanged'
            ELSE 'role ' || :'usr' || ': creating' END AS step \gset
\echo :step
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'usr', :'pw')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'usr') \gexec

SELECT CASE WHEN EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db')
            THEN 'database ' || :'db' || ': already exists, left unchanged'
            ELSE 'database ' || :'db' || ': creating (owner ' || :'usr' || ')' END AS step \gset
\echo :step
SELECT format('CREATE DATABASE %I OWNER %I', :'db', :'usr')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db') \gexec
SQL

echo "Done. Start the backend with:"
echo "  PLATFORM_DB_URL=jdbc:postgresql://localhost:5432/$PLATFORM_DB_NAME PLATFORM_DB_USER=$PLATFORM_DB_USER PLATFORM_DB_PASSWORD=... ./mvnw spring-boot:run"
