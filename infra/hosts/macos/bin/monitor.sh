#!/bin/sh
set -eu

/usr/bin/curl --fail --silent --show-error --max-time 2 https://127.0.0.1/health/live >/dev/null
seconds=$(/usr/bin/curl --fail --silent --show-error --max-time 2 -o /dev/null -w '%{time_total}' https://127.0.0.1/health/ready)
/usr/bin/awk -v value="$seconds" 'BEGIN { exit !(value <= 0.500) }' || { echo "P0 latency SLO breach: ${seconds}s > 0.500s" >&2; exit 1; }
echo "isas_health=1 p0_latency_seconds=$seconds p0_availability_target=99.9"
