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

psql_db -c "CREATE TABLE IF NOT EXISTS public.isas_schema_migration (
  version text PRIMARY KEY,
  checksum_sha256 text NOT NULL CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
  status text NOT NULL CHECK (status IN ('applying','applied')),
  applied_at timestamptz
); REVOKE ALL ON public.isas_schema_migration FROM PUBLIC;"

for migration in /isas/migrations/[0-9][0-9][0-9][0-9]_*.sql; do
  version=$(basename "$migration")
  checksum=$(sha256sum "$migration" | awk '{print $1}')
  recorded=$(psql_db -At -v migration_version="$version" -c "SELECT checksum_sha256 || ':' || status FROM public.isas_schema_migration WHERE version = :'migration_version'")
  if [ "$recorded" = "$checksum:applied" ]; then
    echo "already applied $version"
    continue
  fi
  [ -z "$recorded" ] || { echo "migration $version has checksum drift or an incomplete prior attempt: $recorded" >&2; exit 78; }
  psql_db -v migration_version="$version" -v migration_checksum="$checksum" -c "INSERT INTO public.isas_schema_migration(version,checksum_sha256,status) VALUES (:'migration_version',:'migration_checksum','applying')"
  echo "applying $version"
  psql_db -f "$migration"
  psql_db -v migration_version="$version" -v migration_checksum="$checksum" -c "UPDATE public.isas_schema_migration SET status='applied', applied_at=clock_timestamp() WHERE version=:'migration_version' AND checksum_sha256=:'migration_checksum' AND status='applying'"
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
