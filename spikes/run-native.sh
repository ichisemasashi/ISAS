#!/bin/sh
set -eu

SPIKE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SPIKE_DIR/.." && pwd)
PG16_BIN=${ISAS_PG16_BIN:-/opt/homebrew/opt/postgresql@16/bin}
PORT=${ISAS_NATIVE_PG_PORT:-55434}
for command in postgres initdb pg_ctl createdb dropdb psql pg_isready; do
  [ -x "$PG16_BIN/$command" ] || { echo "PostgreSQL 16 command is missing: $PG16_BIN/$command" >&2; exit 78; }
done
"$PG16_BIN/postgres" --version | grep -Eq 'PostgreSQL\) 16\.' || { echo "PostgreSQL major version must be 16" >&2; exit 78; }
SHARE_DIR=$($PG16_BIN/pg_config --sharedir)
[ -f "$SHARE_DIR/extension/postgis.control" ] || { echo "PostGIS extension for PostgreSQL 16 is missing: $SHARE_DIR/extension/postgis.control" >&2; exit 78; }

RUN_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/isas-native-pg.XXXXXX")
DATA_DIR="$RUN_ROOT/data"
SOCKET_DIR="$RUN_ROOT/socket"
mkdir -m 700 "$SOCKET_DIR"
cleanup() {
  "$PG16_BIN/pg_ctl" -D "$DATA_DIR" -m immediate stop >/dev/null 2>&1 || true
  case "$RUN_ROOT" in "${TMPDIR:-/tmp}"/isas-native-pg.*|/tmp/isas-native-pg.*) rm -rf -- "$RUN_ROOT" ;; *) echo "refusing unsafe cleanup: $RUN_ROOT" >&2 ;; esac
}
trap cleanup EXIT INT TERM

"$PG16_BIN/initdb" -D "$DATA_DIR" --username=postgres --auth-local=trust --auth-host=trust --no-locale --encoding=UTF8 >/dev/null
"$PG16_BIN/pg_ctl" -D "$DATA_DIR" -o "-p $PORT -h 127.0.0.1 -k $SOCKET_DIR" -w start >/dev/null
PSQL="$PG16_BIN/psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -p $PORT -U postgres"

restart_fresh_cluster() {
  "$PG16_BIN/pg_ctl" -D "$DATA_DIR" -m fast -w stop >/dev/null
  rm -rf -- "$DATA_DIR"
  "$PG16_BIN/initdb" -D "$DATA_DIR" --username=postgres --auth-local=trust --auth-host=trust --no-locale --encoding=UTF8 >/dev/null
  "$PG16_BIN/pg_ctl" -D "$DATA_DIR" -o "-p $PORT -h 127.0.0.1 -k $SOCKET_DIR" -w start >/dev/null
}

$PSQL -d postgres <<'SQL' >/dev/null
CREATE ROLE auth_context_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
CREATE ROLE app_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
CREATE ROLE app_user LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS;
CREATE ROLE auth_role LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS;
CREATE ROLE p0_user LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS;
CREATE ROLE p2_user LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS;
CREATE ROLE ops_user LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT NOBYPASSRLS;
GRANT app_user TO p0_user, p2_user;
GRANT auth_context_owner, app_owner TO postgres;
SQL

bootstrap_database() {
  database=$1
  $PSQL -d postgres -c "CREATE DATABASE $database" >/dev/null
  $PSQL -d "$database" -c 'CREATE EXTENSION postgis' >/dev/null
}

apply_migrations() {
  database=$1
  for migration in "$REPO_ROOT"/apps/bff/migrations/[0-9][0-9][0-9][0-9]_*.sql; do $PSQL -d "$database" -f "$migration" >/dev/null; done
}

echo "native PostgreSQL gate: migrations"
bootstrap_database isas
apply_migrations isas
POSTGIS_VERSION=$($PSQL -d isas -Atq -c "SELECT extversion FROM pg_extension WHERE extname='postgis'")
$PSQL -d isas -v EXPECTED_POSTGIS_VERSION="$POSTGIS_VERSION" -f "$REPO_ROOT/apps/bff/migrations/verify/production_auth_context_security.sql" >/dev/null
for verification in "$REPO_ROOT"/apps/bff/migrations/verify/[0-9][0-9][0-9][0-9]_*.sql; do $PSQL -d isas -f "$verification" >/dev/null; done

echo "native PostgreSQL gate: rollback"
bootstrap_database isas_rollback
apply_migrations isas_rollback
for version in 0017 0016 0015 0014 0013; do
  rollback=$(find "$REPO_ROOT/apps/bff/migrations/rollback" -name "${version}_*_rollback.sql" -type f)
  [ -n "$rollback" ] || { echo "rollback is missing for $version" >&2; exit 1; }
  $PSQL -d isas_rollback -f "$rollback" >/dev/null
done

echo "native PostgreSQL gate: S1 S2 S5 S8"
# Migration/rollback databases retain objects owned by the production roles.
# Spikes deliberately recreate those global roles, so isolate them in a fresh
# ephemeral cluster just as the retired container path used an isolated DBMS.
restart_fresh_cluster
$PSQL -d postgres -c 'CREATE DATABASE spike' >/dev/null
for sql in S1_partition_rls_unique.sql S2_spatial_rls.sql S5_audit_chain.sql S8_auth_context.sql; do
  $PSQL -d postgres -c 'DROP DATABASE IF EXISTS spike WITH (FORCE)' >/dev/null
  $PSQL -d postgres -c 'CREATE DATABASE spike' >/dev/null
  $PSQL -d spike -f "$SPIKE_DIR/00_common.sql" >/dev/null
  $PSQL -d spike -f "$SPIKE_DIR/$sql" >/dev/null
done

echo "native PostgreSQL gate: S7"
python3 "$SPIKE_DIR/S7_offline_sync.py" >/dev/null
echo "native PostgreSQL 16 + PostGIS gate: PASS"
