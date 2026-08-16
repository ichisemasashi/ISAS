#!/bin/sh
set -eu
LOCAL_OPS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ISAS_REPO_ROOT=$(CDPATH= cd -- "$LOCAL_OPS_DIR/../.." && pwd)
ISAS_COMPOSE_FILE="$ISAS_REPO_ROOT/compose.local.yml"
ISAS_LOCAL_ENV="$ISAS_REPO_ROOT/.local/secrets/runtime.env"

local_compose() {
  docker compose --project-directory "$ISAS_REPO_ROOT" --env-file "$ISAS_LOCAL_ENV" -f "$ISAS_COMPOSE_FILE" "$@"
}
