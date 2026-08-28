#!/bin/sh
set -eu
OPS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$OPS_DIR/common.sh"
set -a
. "$ISAS_LOCAL_ENV"
set +a

PSQL="$ISAS_HOMEBREW_PREFIX/opt/postgresql@16/bin/psql"
postgis_version=$(PGPASSWORD="$POSTGRES_PASSWORD" "$PSQL" -X -Atq -h 127.0.0.1 -p 55433 -U postgres -d isas -c "SELECT extversion FROM pg_extension WHERE extname='postgis'")
PGPASSWORD="$POSTGRES_PASSWORD" "$PSQL" -X -v ON_ERROR_STOP=1 -v EXPECTED_POSTGIS_VERSION="$postgis_version" -h 127.0.0.1 -p 55433 -U postgres -d isas -f "$ISAS_REPO_ROOT/apps/bff/migrations/verify/production_auth_context_security.sql" >/dev/null
PGPASSWORD="$POSTGRES_PASSWORD" "$PSQL" -X -Atq -v ON_ERROR_STOP=1 -h 127.0.0.1 -p 55433 -U postgres -d isas -c "SELECT CASE WHEN pg_get_userbyid(n.nspowner)='local_support_owner' AND NOT has_schema_privilege('app_user','local_support','USAGE') AND NOT has_schema_privilege('auth_role','local_support','USAGE') THEN 'ok' ELSE 'invalid' END FROM pg_namespace n WHERE n.nspname='local_support'" | grep -qx ok
for item in "6430:p0_user:$ISAS_DB_P0_PASSWORD" "6431:auth_role:$ISAS_DB_AUTH_P1_PASSWORD" "6432:app_user:$ISAS_DB_P1_PASSWORD" "6433:p2_user:$ISAS_DB_P2_PASSWORD" "6434:ops_user:$ISAS_DB_OPS_PASSWORD"; do
  port=${item%%:*}; rest=${item#*:}; role=${rest%%:*}; password=${rest#*:}
  actual=$(PGPASSWORD="$password" "$PSQL" -X -Atq -h 127.0.0.1 -p "$port" -U "$role" -d isas -c 'SELECT current_user')
  [ "$actual" = "$role" ] || { echo "PgBouncer port $port returned unexpected role" >&2; exit 1; }
done
count=0
for service in pgbouncer-p0 pgbouncer-auth-p1 pgbouncer-p1 pgbouncer-p2 pgbouncer-ops; do local_service_running "$service" && count=$((count + 1)); done
[ "$count" = 5 ] || { echo "five independent PgBouncer native processes are required" >&2; exit 1; }
printf '%s\n' 'database migrations, role ownership, and five PgBouncer instances: PASS'
