#!/bin/sh
set -eu

[ "$#" = 1 ] || { echo "usage: $0 SERVICE" >&2; exit 64; }
SERVICE=$1
OPS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$OPS_DIR/common.sh"
[ -r "$ISAS_LOCAL_ENV" ] || { echo "local runtime environment is missing" >&2; exit 78; }
set -a
. "$ISAS_LOCAL_ENV"
set +a

PG16_BIN=${ISAS_PG16_BIN:-$ISAS_HOMEBREW_PREFIX/opt/postgresql@16/bin}
export PATH="$PG16_BIN:$ISAS_HOMEBREW_PREFIX/bin:/usr/bin:/bin:/usr/sbin:/sbin"
export LC_ALL=C
export HOME="$ISAS_NATIVE_ROOT/home"
export TMPDIR="$ISAS_NATIVE_ROOT/tmp"
mkdir -p "$HOME" "$TMPDIR"

case "$SERVICE" in
  database)
    mkdir -p "$ISAS_NATIVE_PG_SOCKET"
    chmod 700 "$ISAS_NATIVE_PG_SOCKET"
    exec "$PG16_BIN/postgres" -D "$ISAS_NATIVE_STATE/postgres" -p 55433 -h 127.0.0.1 -k "$ISAS_NATIVE_PG_SOCKET"
    ;;
  pgbouncer-p0|pgbouncer-auth-p1|pgbouncer-p1|pgbouncer-p2|pgbouncer-ops)
    exec "$ISAS_HOMEBREW_PREFIX/bin/pgbouncer" "$ISAS_NATIVE_STATE/pgbouncer/${SERVICE#pgbouncer-}.ini"
    ;;
  keycloak)
    export JAVA_HOME=${ISAS_JAVA_HOME:-$ISAS_NATIVE_DATA_ROOT/components/java/Contents/Home}
    export KC_DB=postgres
    export KC_DB_URL="jdbc:postgresql://127.0.0.1:55433/keycloak"
    export KC_DB_USERNAME=keycloak_user
    export KC_DB_PASSWORD="$KEYCLOAK_DB_PASSWORD"
    export KC_BOOTSTRAP_ADMIN_USERNAME="$KEYCLOAK_ADMIN"
    export KC_BOOTSTRAP_ADMIN_PASSWORD="$KEYCLOAK_ADMIN_PASSWORD"
    export KC_HOSTNAME="https://isas.localhost:8443/oidc"
    export KC_HOSTNAME_ADMIN="https://127.0.0.1:8443/oidc"
    export KC_PROXY_HEADERS=xforwarded
    exec "$ISAS_NATIVE_DATA_ROOT/components/keycloak/bin/kc.sh" start-dev --import-realm --http-port=18080 --http-relative-path=/oidc --health-enabled=true
    ;;
  telemetry)
    exec "$ISAS_NATIVE_DATA_ROOT/components/otelcol-contrib" --config="$ISAS_REPO_ROOT/infra/local/native/otel.yml"
    ;;
  bff)
    export NODE_ENV=production
    export ISAS_ENV_PROFILE=local-integration
    export ISAS_DEPLOYMENT_ID=isas-jp-local-01
    export ISAS_JURISDICTION=JP
    export ISAS_PUBLIC_ORIGIN=https://isas.localhost:8443
    export ISAS_RUNTIME_ADAPTER_MODULE=./runtime-adapters/local-integration.mjs
    export ISAS_HTTP_PORT=3000
    export OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318
    export NODE_EXTRA_CA_CERTS="$ISAS_NATIVE_DATA_ROOT/tls/rootCA.pem"
    export ISAS_LOCAL_RUNTIME_ROOT="$ISAS_NATIVE_DATA_ROOT"
    export LOCAL_OBJECT_ROOT="$ISAS_NATIVE_DATA_ROOT/objects"
    export LOCAL_SESSION_KEY_FILE="$ISAS_NATIVE_DATA_ROOT/secrets/session.key"
    export LOCAL_OBJECT_KEY_FILE="$ISAS_NATIVE_DATA_ROOT/secrets/object.key"
    export LOCAL_OFFLINE_RECOVERY_KEY_FILE="$ISAS_NATIVE_DATA_ROOT/secrets/offline-recovery.key"
    export ISAS_DB_P0_HOST=127.0.0.1 ISAS_DB_P0_PORT=6430 ISAS_DB_P0_NAME=isas ISAS_DB_P0_USER=p0_user ISAS_DB_P0_SSLMODE=disable
    export ISAS_DB_AUTH_P1_HOST=127.0.0.1 ISAS_DB_AUTH_P1_PORT=6431 ISAS_DB_AUTH_P1_NAME=isas ISAS_DB_AUTH_P1_USER=auth_role ISAS_DB_AUTH_P1_SSLMODE=disable
    export ISAS_DB_P1_HOST=127.0.0.1 ISAS_DB_P1_PORT=6432 ISAS_DB_P1_NAME=isas ISAS_DB_P1_USER=app_user ISAS_DB_P1_SSLMODE=disable
    export ISAS_DB_P2_HOST=127.0.0.1 ISAS_DB_P2_PORT=6433 ISAS_DB_P2_NAME=isas ISAS_DB_P2_USER=p2_user ISAS_DB_P2_SSLMODE=disable
    export ISAS_DB_OPS_HOST=127.0.0.1 ISAS_DB_OPS_PORT=6434 ISAS_DB_OPS_NAME=isas ISAS_DB_OPS_USER=ops_user ISAS_DB_OPS_SSLMODE=disable
    cd "$ISAS_REPO_ROOT/apps/bff"
    exec node bin/server.mjs start
    ;;
  edge)
    export XDG_DATA_HOME="$ISAS_NATIVE_STATE/caddy-data"
    export XDG_CONFIG_HOME="$ISAS_NATIVE_STATE/caddy-config"
    exec "$ISAS_HOMEBREW_PREFIX/opt/caddy/bin/caddy" run --config "$ISAS_NATIVE_STATE/Caddyfile" --adapter caddyfile
    ;;
  *) echo "unknown local native service: $SERVICE" >&2; exit 64 ;;
esac
