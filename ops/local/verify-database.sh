#!/bin/sh
set -eu
OPS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$OPS_DIR/common.sh"
set -a
. "$ISAS_LOCAL_ENV"
set +a

postgis_version=$(local_compose exec -T database psql -X -Atq -U postgres -d isas -c "SELECT extversion FROM pg_extension WHERE extname='postgis'")
local_compose exec -T database psql -X -v ON_ERROR_STOP=1 -v EXPECTED_POSTGIS_VERSION="$postgis_version" -U postgres -d isas -f /migrations/verify/production_auth_context_security.sql >/dev/null
local_compose exec -T database psql -X -Atq -v ON_ERROR_STOP=1 -U postgres -d isas -c "SELECT CASE WHEN pg_get_userbyid(n.nspowner)='local_support_owner' AND NOT has_schema_privilege('app_user','local_support','USAGE') AND NOT has_schema_privilege('auth_role','local_support','USAGE') THEN 'ok' ELSE 'invalid' END FROM pg_namespace n WHERE n.nspname='local_support'" | grep -qx ok
for item in "pgbouncer-p0:p0_user:$ISAS_DB_P0_PASSWORD" "pgbouncer-auth-p1:auth_role:$ISAS_DB_AUTH_P1_PASSWORD" "pgbouncer-p1:app_user:$ISAS_DB_P1_PASSWORD" "pgbouncer-p2:p2_user:$ISAS_DB_P2_PASSWORD" "pgbouncer-ops:ops_user:$ISAS_DB_OPS_PASSWORD"; do
  service=${item%%:*}; rest=${item#*:}; role=${rest%%:*}; password=${rest#*:}
  actual=$(local_compose exec -T -e PGPASSWORD="$password" database psql -X -Atq -h "$service" -p 5432 -U "$role" -d isas -c 'SELECT current_user')
  [ "$actual" = "$role" ] || { echo "$service returned unexpected role" >&2; exit 1; }
done
count=$(local_compose ps -q pgbouncer-p0 pgbouncer-auth-p1 pgbouncer-p1 pgbouncer-p2 pgbouncer-ops | sort -u | wc -l | tr -d ' ')
[ "$count" = 5 ] || { echo "five independent PgBouncer containers are required" >&2; exit 1; }
printf '%s\n' 'database migrations, role ownership, and five PgBouncer instances: PASS'
