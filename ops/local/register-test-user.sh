#!/bin/sh
set -eu
OPS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$OPS_DIR/../.." && pwd)
CA_FILE="$REPO_ROOT/.local/tls/rootCA.pem"
[ -r "$CA_FILE" ] || { printf '%s\n' 'local CA is missing; run ops/local/local-up.sh first' >&2; exit 69; }
NODE_EXTRA_CA_CERTS="$CA_FILE" exec node "$OPS_DIR/register-test-user.mjs" "$@"
