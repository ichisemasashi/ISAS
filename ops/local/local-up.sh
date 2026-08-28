#!/bin/sh
set -eu
OPS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$OPS_DIR/common.sh"
node "$OPS_DIR/doctor.mjs"
node "$OPS_DIR/bootstrap.mjs"
"$OPS_DIR/native-prepare.sh"
"$OPS_DIR/native-launchctl.sh" up

CA_FILE="$ISAS_NATIVE_DATA_ROOT/tls/rootCA.pem"
ready=false
for attempt in $(jot 120 1); do
  if curl --cacert "$CA_FILE" -fsS https://isas.localhost:8443/health/ready >/dev/null 2>&1; then ready=true; break; fi
  sleep 2
done
[ "$ready" = true ] || { "$OPS_DIR/local-status.sh" >&2; echo "local-integration readiness timeout" >&2; exit 1; }
NODE_EXTRA_CA_CERTS="$CA_FILE" ISAS_LOCAL_ENV="$ISAS_LOCAL_ENV" node "$OPS_DIR/reconcile-keycloak.mjs"
"$OPS_DIR/reconcile-fixtures.sh"
printf '%s\n' 'ISAS local-integration: https://isas.localhost:8443'
