#!/bin/sh
set -eu
LOCAL_OPS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -r "$LOCAL_OPS_DIR/install.env" ]; then . "$LOCAL_OPS_DIR/install.env"; else ISAS_REPO_ROOT=$(CDPATH= cd -- "$LOCAL_OPS_DIR/../.." && pwd); fi
ISAS_NATIVE_DATA_ROOT=${ISAS_NATIVE_DATA_ROOT:-$HOME/Library/Application Support/ISAS/local-integration}
export ISAS_NATIVE_DATA_ROOT
ISAS_LOCAL_ENV="$ISAS_NATIVE_DATA_ROOT/secrets/runtime.env"
ISAS_NATIVE_ROOT="$ISAS_NATIVE_DATA_ROOT/runtime"
ISAS_NATIVE_STATE="$ISAS_NATIVE_DATA_ROOT/state"
ISAS_NATIVE_LOG="$ISAS_NATIVE_DATA_ROOT/log"
ISAS_NATIVE_LAUNCHD="$ISAS_NATIVE_DATA_ROOT/launchd"
ISAS_NATIVE_PG_SOCKET="/tmp/isas-local-pg-$(id -u)"
ISAS_LAUNCH_DOMAIN="gui/$(id -u)"
ISAS_LOCAL_LABEL_PREFIX="com.isas.local"
ISAS_HOMEBREW_PREFIX=${ISAS_HOMEBREW_PREFIX:-$(brew --prefix)}

local_label() { printf '%s.%s\n' "$ISAS_LOCAL_LABEL_PREFIX" "$1"; }

local_service_running() {
  launchctl print "$ISAS_LAUNCH_DOMAIN/$(local_label "$1")" >/dev/null 2>&1
}

local_service_pid() {
  launchctl print "$ISAS_LAUNCH_DOMAIN/$(local_label "$1")" 2>/dev/null | awk '/pid = / { print $3; exit }'
}
