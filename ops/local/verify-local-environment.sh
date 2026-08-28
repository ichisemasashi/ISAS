#!/bin/sh
set -eu

[ "$#" -le 1 ] || { printf 'usage: %s [--full]\n' "$0" >&2; exit 64; }
MODE=${1:-foundation}
case "$MODE" in
  foundation|--full) ;;
  *) printf 'usage: %s [--full]\n' "$0" >&2; exit 64 ;;
esac

OPS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$OPS_DIR/../.." && pwd)
. "$OPS_DIR/common.sh"

CA_FILE="$ISAS_NATIVE_DATA_ROOT/tls/rootCA.pem"
ORIGIN=https://isas.localhost:8443
TMP_ROOT=${TMPDIR:-/tmp}/isas-local-verify-$$
mkdir -m 700 "$TMP_ROOT"
trap 'rm -rf "$TMP_ROOT"' EXIT HUP INT TERM

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
pass() { printf 'PASS: %s\n' "$*"; }

node "$OPS_DIR/doctor.mjs" >/dev/null
[ -r "$CA_FILE" ] || fail "local CA is missing; run ops/local/local-up.sh"
pass "host prerequisites and native component lock"

for service in edge bff database pgbouncer-p0 pgbouncer-auth-p1 pgbouncer-p1 pgbouncer-p2 pgbouncer-ops keycloak telemetry; do
  local_service_running "$service" || fail "$service launchd user agent is not running"
  [ -n "$(local_service_pid "$service")" ] || fail "$service launchd user agent has no live process"
done
lsof -nP -iTCP:8443 -sTCP:LISTEN | grep -q '127.0.0.1:8443' || fail "TLS ingress must bind 127.0.0.1:8443"
if lsof -nP -iTCP:8443 -sTCP:LISTEN | grep -q '\*:8443'; then fail "TLS ingress must not bind all interfaces"; fi
curl --silent --show-error --fail http://127.0.0.1:9464/metrics >/dev/null || fail "native telemetry metrics endpoint is unavailable"
pass "launchd service health, telemetry, and loopback-only ingress"

"$OPS_DIR/verify-database.sh" >/dev/null
pass "PostGIS, RLS, owner, audit trigger, and five isolated pools"

request() {
  name=$1 expected=$2 path=$3
  actual=$(curl --cacert "$CA_FILE" --silent --show-error --output "$TMP_ROOT/$name.body" --dump-header "$TMP_ROOT/$name.headers" --write-out '%{http_code}' "$ORIGIN$path")
  [ "$actual" = "$expected" ] || fail "$name returned HTTP $actual, expected $expected"
}

request ready 200 /health/ready
grep -q '"deploymentProfile":"local-integration"' "$TMP_ROOT/ready.body" || fail "readiness does not identify local-integration"
request session 401 /api/bff/session
request login 302 /api/bff/login
grep -qi '^location: https://isas\.localhost:8443/oidc/realms/isas-local/' "$TMP_ROOT/login.headers" || fail "login redirect left the same-origin issuer"
request discovery 200 /oidc/realms/isas-local/.well-known/openid-configuration
grep -q '"issuer":"https://isas.localhost:8443/oidc/realms/isas-local"' "$TMP_ROOT/discovery.body" || fail "OIDC issuer mismatch"
pass "TLS ingress, BFF readiness, unauthenticated boundary, and OIDC discovery"

printf '%s\n' 'local-integration foundation verification: PASS'

if [ "$MODE" = "--full" ]; then
  [ -d "$REPO_ROOT/apps/web/node_modules/@playwright" ] || fail "Playwright dependencies are missing; run npm ci in apps/web"
  "$OPS_DIR/reconcile-fixtures.sh"
  (
    cd "$REPO_ROOT/apps/web"
    npm run test:local-integration
  )
  printf '%s\n' 'local-integration OIDC/MFA, same-origin HTTPS, and business workflow verification: PASS'
fi
