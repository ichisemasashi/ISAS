#!/bin/sh
set -eu
OPS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$OPS_DIR/common.sh"
set -a
. "$ISAS_LOCAL_ENV"
set +a
PG16_BIN=${ISAS_PG16_BIN:-$ISAS_HOMEBREW_PREFIX/opt/postgresql@16/bin}
export PGPASSWORD="$POSTGRES_PASSWORD"
PSQL="$PG16_BIN/psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -p 55433 -U postgres -d isas"

$PSQL <<'SQL' >/dev/null
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
  migration_set=$1 directory=$2
  for migration in "$directory"/[0-9][0-9][0-9][0-9]_*.sql; do
    version=$(basename "$migration" .sql)
    checksum=$(shasum -a 256 "$migration" | awk '{print $1}')
    recorded=$($PSQL -Atq -c "SELECT checksum_sha256 FROM public.isas_schema_migration WHERE migration_set='$migration_set' AND version='$version'")
    if [ -n "$recorded" ]; then
      [ "$recorded" = "$checksum" ] || { echo "migration checksum mismatch: $migration_set/$version" >&2; exit 65; }
      continue
    fi
    echo "applying $migration_set/$version"
    $PSQL -f "$migration" >/dev/null
    $PSQL -c "INSERT INTO public.isas_schema_migration(migration_set,version,checksum_sha256) VALUES ('$migration_set','$version','$checksum')" >/dev/null
  done
}

apply_set application "$ISAS_REPO_ROOT/apps/bff/migrations"
apply_set local_support "$ISAS_REPO_ROOT/infra/local/database/migrations"
postgis_version=$($PSQL -Atq -c "SELECT extversion FROM pg_extension WHERE extname='postgis'")
[ -n "$postgis_version" ] || { echo "PostGIS extension is missing" >&2; exit 78; }
$PSQL -v EXPECTED_POSTGIS_VERSION="$postgis_version" -f "$ISAS_REPO_ROOT/apps/bff/migrations/verify/production_auth_context_security.sql" >/dev/null
$PSQL -f "$ISAS_REPO_ROOT/infra/local/database/migrations/verify.sql" >/dev/null
echo "native local migrations: PASS"
