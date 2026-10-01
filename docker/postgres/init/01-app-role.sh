#!/bin/bash
# Provisions the runtime database role.
#
# Two roles exist on purpose:
#   - the owner role (POSTGRES_USER), used by Flyway to create and alter schema;
#   - the application role (APP_DB_USER), used by the running service.
#
# Row Level Security is bypassed by superusers and by table owners, so the
# application must never connect as either. This role is deliberately
# unprivileged: no schema rights, no BYPASSRLS, and it is granted only DML on
# tables the owner creates.
set -euo pipefail

if [[ -z "${APP_DB_USER:-}" || -z "${APP_DB_PASSWORD:-}" ]]; then
  echo "APP_DB_USER and APP_DB_PASSWORD must be set" >&2
  exit 1
fi

psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=db_name="$POSTGRES_DB" \
  --set=app_user="$APP_DB_USER" \
  --set=app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE :"app_user" WITH LOGIN PASSWORD :'app_password' NOBYPASSRLS;

GRANT CONNECT ON DATABASE :"db_name" TO :"app_user";
GRANT USAGE ON SCHEMA public TO :"app_user";

-- Tables are created later by Flyway, running as the owner. Default privileges
-- make sure the application role receives DML on each of them automatically.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO :"app_user";
SQL
