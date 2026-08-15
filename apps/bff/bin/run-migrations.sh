#!/bin/sh
set -eu

: "${DB_HOST:?DB_HOST is required}"
: "${DB_PORT:?DB_PORT is required}"
: "${DB_NAME:?DB_NAME is required}"
: "${DB_USER:?DB_USER is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"
: "${MIGRATION_REQUIRED_FIRST:=0000_auth_context_v1.sql}"
: "${EXPECTED_POSTGIS_VERSION:?EXPECTED_POSTGIS_VERSION is required}"

export PGPASSWORD="$DB_PASSWORD"
export PGSSLMODE=require
psql_db() {
  psql -X -v ON_ERROR_STOP=1 -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" "$@"
}
first=/isas/migrations/0000_auth_context_v1.sql
[ "$(basename "$first")" = "$MIGRATION_REQUIRED_FIRST" ] || { echo "required first migration mismatch" >&2; exit 78; }

for migration in /isas/migrations/[0-9][0-9][0-9][0-9]_*.sql; do
  echo "applying $(basename "$migration")"
  psql_db -f "$migration"
done

actual_postgis=$(psql_db -Atc "SELECT extversion FROM pg_extension WHERE extname='postgis'")
[ "$actual_postgis" = "$EXPECTED_POSTGIS_VERSION" ] || {
  echo "PostGIS version mismatch: expected $EXPECTED_POSTGIS_VERSION, got ${actual_postgis:-missing}" >&2
  exit 78
}

for verification in /isas/migrations/verify/*.sql; do
  echo "verifying $(basename "$verification")"
  psql_db -f "$verification"
done

echo "migration and production invariants PASS"
