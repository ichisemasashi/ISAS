#!/bin/sh
set -eu
[ "$#" = 1 ] || { echo "usage: $0 up|down|restart|status" >&2; exit 64; }
ACTION=$1
OPS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$OPS_DIR/common.sh"
SERVICES="database pgbouncer-p0 pgbouncer-auth-p1 pgbouncer-p1 pgbouncer-p2 pgbouncer-ops keycloak telemetry bff edge"

load_one() {
  service=$1 label=$(local_label "$1")
  plist="$ISAS_NATIVE_LAUNCHD/$label.plist"
  [ -r "$plist" ] || { echo "launchd definition is missing: $plist" >&2; exit 78; }
  if local_service_running "$service"; then launchctl kickstart -k "$ISAS_LAUNCH_DOMAIN/$label" >/dev/null; else launchctl bootstrap "$ISAS_LAUNCH_DOMAIN" "$plist"; fi
}

unload_one() {
  service=$1 label=$(local_label "$1")
  if local_service_running "$service"; then launchctl bootout "$ISAS_LAUNCH_DOMAIN/$label"; fi
}

case "$ACTION" in
  up)
    for service in $SERVICES; do load_one "$service"; done
    ;;
  down)
    for service in edge bff telemetry keycloak pgbouncer-ops pgbouncer-p2 pgbouncer-p1 pgbouncer-auth-p1 pgbouncer-p0 database; do unload_one "$service"; done
    stopped=false
    for attempt in $(jot 60 1); do
      active=false
      for service in $SERVICES; do if local_service_running "$service"; then active=true; break; fi; done
      if [ "$active" = false ]; then stopped=true; break; fi
      sleep 1
    done
    [ "$stopped" = true ] || { echo "local native service shutdown timeout" >&2; exit 1; }
    ;;
  restart)
    "$0" down
    "$0" up
    ;;
  status)
    for service in $SERVICES; do
      if local_service_running "$service"; then printf '%-22s running pid=%s\n' "$service" "$(local_service_pid "$service")"; else printf '%-22s stopped\n' "$service"; fi
    done
    ;;
  *) echo "usage: $0 up|down|restart|status" >&2; exit 64 ;;
esac
