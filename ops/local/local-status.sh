#!/bin/sh
set -eu
OPS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
"$OPS_DIR/native-launchctl.sh" status
. "$OPS_DIR/common.sh"
if [ -r "$ISAS_NATIVE_DATA_ROOT/tls/rootCA.pem" ]; then
  curl --cacert "$ISAS_NATIVE_DATA_ROOT/tls/rootCA.pem" -fsS https://isas.localhost:8443/health/live >/dev/null 2>&1 \
    && echo "edge-health            PASS" || echo "edge-health            unavailable"
fi
