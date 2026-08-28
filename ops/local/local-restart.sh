#!/bin/sh
set -eu
OPS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
"$OPS_DIR/native-launchctl.sh" restart
"$OPS_DIR/local-status.sh"
