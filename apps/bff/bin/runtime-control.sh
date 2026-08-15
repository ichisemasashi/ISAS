#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP_DIR=$(dirname "$SCRIPT_DIR")
RUN_DIR=${ISAS_BFF_RUN_DIR:-"$APP_DIR/runtime"}
PID_FILE="$RUN_DIR/bff.pid"
LOG_FILE=${ISAS_BFF_LOG_FILE:-"$RUN_DIR/bff.log"}
STOP_WAIT_SECONDS=${ISAS_BFF_STOP_WAIT_SECONDS:-20}

case "$STOP_WAIT_SECONDS" in
  *[!0-9]*|"") echo "ISAS_BFF_STOP_WAIT_SECONDS must be an integer" >&2; exit 64 ;;
esac

read_pid() {
  [ -f "$PID_FILE" ] || return 1
  pid=$(sed -n '1p' "$PID_FILE")
  case "$pid" in
    *[!0-9]*|""|0|1) echo "invalid PID file: $PID_FILE" >&2; exit 65 ;;
  esac
}

is_our_process() {
  kill -0 "$pid" 2>/dev/null || return 1
  command_line=$(ps -p "$pid" -o command= 2>/dev/null || true)
  case "$command_line" in
    *"bin/server.mjs start"*) return 0 ;;
    *) echo "PID $pid is not the ISAS BFF; refusing to signal it" >&2; exit 65 ;;
  esac
}

remove_pid_file() {
  node -e 'const fs=require("node:fs");try{fs.unlinkSync(process.argv[1])}catch(e){if(e.code!=="ENOENT")throw e}' "$PID_FILE"
}

start_service() {
  mkdir -p "$RUN_DIR"
  chmod 700 "$RUN_DIR"
  if read_pid; then
    if is_our_process; then
      echo "ISAS BFF is already running (pid=$pid)" >&2
      exit 69
    fi
    remove_pid_file
  fi

  (cd "$APP_DIR" && node bin/server.mjs check-config)
  umask 077
  cd "$APP_DIR"
  nohup node bin/server.mjs start >>"$LOG_FILE" 2>&1 &
  pid=$!
  printf '%s\n' "$pid" >"$PID_FILE"

  count=0
  while [ "$count" -lt 50 ]; do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "ISAS BFF exited during startup; inspect $LOG_FILE" >&2
      remove_pid_file
      exit 70
    fi
    if node -e 'fetch(`http://127.0.0.1:${process.argv[1]}/health/live`).then(r=>process.exit(r.ok?0:1),()=>process.exit(1))' "${ISAS_HTTP_PORT:-3000}"; then
      echo "ISAS BFF started (pid=$pid, log=$LOG_FILE)"
      return
    fi
    sleep 0.2
    count=$((count + 1))
  done
  kill -TERM "$pid" 2>/dev/null || true
  echo "ISAS BFF did not become live; inspect $LOG_FILE" >&2
  exit 70
}

stop_service() {
  if ! read_pid; then
    echo "ISAS BFF is not running"
    return
  fi
  if ! is_our_process; then
    remove_pid_file
    echo "ISAS BFF is not running (removed stale PID file)"
    return
  fi

  kill -TERM "$pid"
  count=0
  max_count=$((STOP_WAIT_SECONDS * 5))
  while kill -0 "$pid" 2>/dev/null && [ "$count" -lt "$max_count" ]; do
    sleep 0.2
    count=$((count + 1))
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "ISAS BFF did not stop within ${STOP_WAIT_SECONDS}s; escalation is required (pid=$pid)" >&2
    exit 71
  fi
  remove_pid_file
  echo "ISAS BFF stopped"
}

status_service() {
  if read_pid && is_our_process; then
    echo "ISAS BFF is running (pid=$pid)"
    exit 0
  fi
  echo "ISAS BFF is not running"
  exit 3
}

case "${1:-}" in
  start) start_service ;;
  stop) stop_service ;;
  restart) stop_service; start_service ;;
  status) status_service ;;
  foreground) cd "$APP_DIR"; exec node bin/server.mjs start ;;
  *) echo "usage: $0 {start|stop|restart|status|foreground}" >&2; exit 64 ;;
esac
