#!/bin/sh
set -eu
: "${DB_HOST:?DB_HOST is required}"
: "${DB_PORT:?DB_PORT is required}"
: "${DB_NAME:?DB_NAME is required}"
: "${DB_USER:?DB_USER is required}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"
export PGPASSWORD="$POSTGRES_PASSWORD"

psql_db() { psql -X -v ON_ERROR_STOP=1 -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" "$@"; }

psql_db <<'SQL'
CREATE TABLE IF NOT EXISTS public.isas_schema_migration (
  migration_set text NOT NULL CHECK (migration_set IN ('application','local_support')),
  version text NOT NULL,
  checksum_sha256 text NOT NULL CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
  applied_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (migration_set, version)
);
REVOKE ALL ON public.isas_schema_migration FROM PUBLIC, app_user, auth_role, p0_user, p2_user, ops_user;
SQL

apply_set() {
  migration_set=$1
  directory=$2
  for migration in "$directory"/[0-9][0-9][0-9][0-9]_*.sql; do
    version=$(basename "$migration" .sql)
    checksum=$(sha256sum "$migration" | awk '{print $1}')
    recorded=$(psql_db -Atq -c "SELECT checksum_sha256 FROM public.isas_schema_migration WHERE migration_set='$migration_set' AND version='$version'")
    if [ -n "$recorded" ]; then
      [ "$recorded" = "$checksum" ] || { echo "migration checksum mismatch: $migration_set/$version" >&2; exit 65; }
      echo "already applied $migration_set/$version"
      continue
    fi
    echo "applying $migration_set/$version"
    psql_db -f "$migration"
    psql_db -c "INSERT INTO public.isas_schema_migration(migration_set,version,checksum_sha256) VALUES ('$migration_set','$version','$checksum')"
  done
}

apply_set application /isas/application-migrations
apply_set local_support /isas/local-migrations

actual_postgis=$(psql_db -Atq -c "SELECT extversion FROM pg_extension WHERE extname='postgis'")
[ -n "$actual_postgis" ] || { echo "PostGIS extension is missing" >&2; exit 78; }
psql_db -v EXPECTED_POSTGIS_VERSION="$actual_postgis" -f /isas/application-migrations/verify/production_auth_context_security.sql
psql_db -f /isas/local-migrations/verify.sql
echo "local application and support migrations: PASS"
