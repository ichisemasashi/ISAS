#!/bin/sh
set -eu
if [ "${1:-}" != "--confirm-local-data-loss" ]; then
  printf '%s\n' '拒否: resetはDB、IdP、object、監視volume、local keyを破棄します。' >&2
  printf '%s\n' '実行する場合: ops/local/local-reset.sh --confirm-local-data-loss' >&2
  exit 64
fi
OPS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$OPS_DIR/../.." && pwd)
. "$OPS_DIR/common.sh"
local_compose down --volumes --remove-orphans
find "$REPO_ROOT/.local/secrets" -type f -delete
find "$REPO_ROOT/.local/tls" -type f -delete
find "$REPO_ROOT/.local/objects" -type f -delete
printf '%s\n' 'local-integration data and keys were deleted; this cannot be recovered.'
