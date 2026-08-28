#!/bin/sh
set -eu
OPS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$OPS_DIR/common.sh"
set -a
. "$ISAS_LOCAL_ENV"
set +a
PG16_BIN=${ISAS_PG16_BIN:-$ISAS_HOMEBREW_PREFIX/opt/postgresql@16/bin}
DATA_DIR="$ISAS_NATIVE_STATE/postgres"
SOCKET_DIR="$ISAS_NATIVE_PG_SOCKET"
COMPONENT_ROOT="$ISAS_NATIVE_DATA_ROOT/components"
ARTIFACT_ROOT="$ISAS_NATIVE_DATA_ROOT/artifacts"
mkdir -p "$ISAS_NATIVE_STATE" "$ISAS_NATIVE_LOG" "$ISAS_NATIVE_LAUNCHD" "$COMPONENT_ROOT" "$ARTIFACT_ROOT" "$ISAS_NATIVE_ROOT/bin" "$ISAS_NATIVE_ROOT/home" "$ISAS_NATIVE_ROOT/tmp" "$SOCKET_DIR"
chmod 700 "$ISAS_NATIVE_ROOT" "$ISAS_NATIVE_STATE" "$ISAS_NATIVE_LOG" "$ISAS_NATIVE_LAUNCHD" "$SOCKET_DIR"

for command in postgres initdb pg_ctl createdb psql pg_isready pg_config; do
  [ -x "$PG16_BIN/$command" ] || { echo "PostgreSQL 16 command is missing: $PG16_BIN/$command" >&2; exit 78; }
done
"$PG16_BIN/postgres" --version | grep -Eq 'PostgreSQL\) 16\.' || { echo "PostgreSQL major version must be 16" >&2; exit 78; }
SHARE_DIR=$($PG16_BIN/pg_config --sharedir)
[ -f "$SHARE_DIR/extension/postgis.control" ] || { echo "PostGIS for PostgreSQL 16 is missing" >&2; exit 78; }
[ -x "$ISAS_HOMEBREW_PREFIX/bin/pgbouncer" ] || { echo "PgBouncer is missing" >&2; exit 78; }
[ -x "$ISAS_HOMEBREW_PREFIX/opt/caddy/bin/caddy" ] || { echo "Caddy is missing" >&2; exit 78; }
[ -x "$COMPONENT_ROOT/java/Contents/Home/bin/java" ] || { echo "Temurin JDK 21 is missing" >&2; exit 78; }
[ -x "$COMPONENT_ROOT/keycloak/bin/kc.sh" ] || { echo "Keycloak native distribution is missing" >&2; exit 78; }
[ -x "$COMPONENT_ROOT/otelcol-contrib" ] || { echo "OpenTelemetry Collector native binary is missing" >&2; exit 78; }

if [ ! -f "$DATA_DIR/PG_VERSION" ]; then
  password_file="$ISAS_NATIVE_ROOT/tmp/postgres-password"
  umask 077
  printf '%s\n' "$POSTGRES_PASSWORD" > "$password_file"
  "$PG16_BIN/initdb" -D "$DATA_DIR" --username=postgres --pwfile="$password_file" --auth-local=trust --auth-host=scram-sha-256 --no-locale --encoding=UTF8 >/dev/null
  rm -f "$password_file"
fi

started_here=false
if ! "$PG16_BIN/pg_isready" -h 127.0.0.1 -p 55433 -d postgres >/dev/null 2>&1; then
  "$PG16_BIN/pg_ctl" -D "$DATA_DIR" -o "-p 55433 -h 127.0.0.1 -k $SOCKET_DIR" -w start >/dev/null
  started_here=true
fi
cleanup() { if [ "$started_here" = true ]; then "$PG16_BIN/pg_ctl" -D "$DATA_DIR" -m fast -w stop >/dev/null 2>&1 || true; fi; }
trap cleanup EXIT INT TERM

export PGPASSWORD="$POSTGRES_PASSWORD"
if ! "$PG16_BIN/psql" -X -Atq -h 127.0.0.1 -p 55433 -U postgres -d postgres -c "SELECT 1 FROM pg_database WHERE datname='isas'" | grep -qx 1; then
  "$PG16_BIN/createdb" -h 127.0.0.1 -p 55433 -U postgres isas
  POSTGRES_DB=isas POSTGRES_USER=postgres PGHOST=127.0.0.1 PGPORT=55433 "$ISAS_REPO_ROOT/infra/local/database/init/00-bootstrap.sh"
fi
"$OPS_DIR/native-migrate.sh"

(cd "$ISAS_REPO_ROOT/apps/web" && npm run build >/dev/null)
artifact_staging="$ARTIFACT_ROOT/staging.$$"
mkdir -p "$artifact_staging/apps" "$artifact_staging/infra/local/native"
/usr/bin/ditto "$ISAS_REPO_ROOT/apps/bff" "$artifact_staging/apps/bff"
/usr/bin/ditto "$ISAS_REPO_ROOT/apps/web/dist" "$artifact_staging/apps/web/dist"
/usr/bin/ditto "$ISAS_REPO_ROOT/infra/local/native" "$artifact_staging/infra/local/native"
if [ -d "$ARTIFACT_ROOT/previous" ]; then rm -rf -- "$ARTIFACT_ROOT/previous"; fi
if [ -d "$ARTIFACT_ROOT/current" ]; then mv "$ARTIFACT_ROOT/current" "$ARTIFACT_ROOT/previous"; fi
mv "$artifact_staging" "$ARTIFACT_ROOT/current"
/usr/bin/install -m 700 "$OPS_DIR/common.sh" "$ISAS_NATIVE_ROOT/bin/common.sh"
/usr/bin/install -m 700 "$OPS_DIR/native-service.sh" "$ISAS_NATIVE_ROOT/bin/native-service.sh"
printf "ISAS_REPO_ROOT='%s'\nISAS_NATIVE_DATA_ROOT='%s'\nISAS_HOMEBREW_PREFIX='%s'\n" "$ARTIFACT_ROOT/current" "$ISAS_NATIVE_DATA_ROOT" "$ISAS_HOMEBREW_PREFIX" > "$ISAS_NATIVE_ROOT/bin/install.env"
chmod 600 "$ISAS_NATIVE_ROOT/bin/install.env"
node "$OPS_DIR/generate-native-config.mjs"
node "$OPS_DIR/generate-launchd.mjs"
echo "native local preparation: PASS"
