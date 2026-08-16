#!/bin/sh
set -eu

OPS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$OPS_DIR/../.." && pwd)
. "$OPS_DIR/common.sh"

CA_FILE="$REPO_ROOT/.local/tls/rootCA.pem"
ORIGIN=https://isas.localhost:8443
TMP_ROOT=${TMPDIR:-/tmp}/isas-local-verify-$$
mkdir -m 700 "$TMP_ROOT"
trap 'rm -rf "$TMP_ROOT"' EXIT HUP INT TERM

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
pass() { printf 'PASS: %s\n' "$*"; }

node "$OPS_DIR/doctor.mjs" >/dev/null
local_compose config --quiet
[ -r "$CA_FILE" ] || fail "local CA is missing; run ops/local/local-up.sh"
pass "host prerequisites and Compose configuration"

for service in caddy web bff database pgbouncer-p0 pgbouncer-auth-p1 pgbouncer-p1 pgbouncer-p2 pgbouncer-ops keycloak; do
  local_compose ps "$service" | grep -q '(healthy)' || fail "$service is not healthy"
done
local_compose ps --status running --services | grep -qx 'otel-collector' || fail "otel-collector is not running"
local_compose ps -a migration | grep -q 'Exited (0)' || fail "migration did not exit successfully"
published=$(local_compose ps | grep -- '->' || true)
[ "$(printf '%s\n' "$published" | grep -c .)" = 1 ] || fail "exactly one host port must be published"
printf '%s\n' "$published" | grep -q '127.0.0.1:8443->8443/tcp' || fail "TLS ingress must bind only 127.0.0.1:8443"
pass "service health, migration, and loopback-only ingress"

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
