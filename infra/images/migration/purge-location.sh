#!/usr/bin/env bash
set -euo pipefail

: "${DB_HOST:?DB_HOST is required}"
: "${DB_PORT:?DB_PORT is required}"
: "${DB_NAME:?DB_NAME is required}"
: "${DB_USER:?DB_USER is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"

export PGHOST="${DB_HOST}" PGPORT="${DB_PORT}" PGDATABASE="${DB_NAME}" PGUSER="${DB_USER}" PGPASSWORD="${DB_PASSWORD}"
total=0
while true; do
  deleted="$(psql -X -Atq -v ON_ERROR_STOP=1 -c "SET ROLE app_owner; SELECT app.purge_expired_location_tracks(10000);")"
  deleted="${deleted##*$'\n'}"
  [[ "${deleted}" =~ ^[0-9]+$ ]] || { echo "Unexpected purge result" >&2; exit 65; }
  total=$((total + deleted))
  (( deleted < 10000 )) && break
done
unset PGPASSWORD DB_PASSWORD
echo "Location retention purge completed: ${total} rows"
