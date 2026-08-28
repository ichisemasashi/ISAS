#!/bin/sh
set -eu
OPS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$OPS_DIR/common.sh"
CA_FILE="$ISAS_NATIVE_DATA_ROOT/tls/rootCA.pem"
[ -r "$CA_FILE" ] || { printf '%s\n' 'local CA is missing; run ops/local/local-up.sh first' >&2; exit 69; }
NODE_EXTRA_CA_CERTS="$CA_FILE" ISAS_NATIVE_DATA_ROOT="$ISAS_NATIVE_DATA_ROOT" ISAS_LOCAL_ENV="$ISAS_LOCAL_ENV" ISAS_PSQL="$ISAS_HOMEBREW_PREFIX/opt/postgresql@16/bin/psql" exec node "$OPS_DIR/register-test-user.mjs" "$@"
