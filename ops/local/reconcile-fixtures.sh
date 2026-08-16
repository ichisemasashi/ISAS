#!/bin/sh
set -eu
OPS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$OPS_DIR/common.sh"

result=$(local_compose exec -T database psql -X -Atq -v ON_ERROR_STOP=1 -U postgres -d isas <<'SQL'
UPDATE app.task
SET scheduled_at = (date_trunc('day', now() AT TIME ZONE 'Asia/Tokyo') + interval '9 hours') AT TIME ZONE 'Asia/Tokyo',
    status = 'today', deleted_at = NULL
WHERE tenant_id = '20000000-0000-4000-8000-000000000001'
  AND task_id = '41000000-0000-4000-8000-000000000001'
RETURNING 'local-fixture-ready';
SQL
)
[ "$result" = "local-fixture-ready" ] || { printf '%s\n' 'local synthetic task is missing; recreate the local environment' >&2; exit 70; }
printf '%s\n' 'local synthetic fixtures: ready'
