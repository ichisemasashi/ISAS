#!/bin/sh
set -eu
OPS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$OPS_DIR/../.." && pwd)
node "$OPS_DIR/doctor.mjs"
node "$OPS_DIR/bootstrap.mjs"
. "$OPS_DIR/common.sh"
local_compose up -d --build --wait
printf '%s\n' 'ISAS local-integration: https://isas.localhost:8443'
