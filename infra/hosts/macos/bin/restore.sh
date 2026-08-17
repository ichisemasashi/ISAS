#!/bin/sh
set -eu

[ "$(uname -s)" = "Darwin" ] || exit 64
[ "$(id -u)" -eq 0 ] || exit 77
: "${ISAS_RECOVERY_SET:?recovery set directory is required}"
: "${ISAS_RESTORE_WAL_DIR:?empty WAL restore directory is required}"
(cd "$ISAS_RECOVERY_SET" && /usr/bin/shasum -a 256 -c SHA256)
for item in postgresql wal identity.export object-queue.export wal-inventory object-inventory audit-anchor key-reference recovery-set-id; do
  [ -e "$ISAS_RECOVERY_SET/$item" ] || { echo "missing recovery item: $item" >&2; exit 66; }
done
for label in edge app telemetry object-queue identity database; do /bin/launchctl bootout system "/Library/LaunchDaemons/com.isas.$label.plist" >/dev/null 2>&1 || true; done
[ -z "$(/bin/ls -A "/Library/Application Support/ISAS/Production/data/database" 2>/dev/null)" ] || { echo "database restore target must be empty" >&2; exit 65; }
/usr/bin/ditto "$ISAS_RECOVERY_SET/postgresql" "/Library/Application Support/ISAS/Production/data/database"
/usr/bin/ditto "$ISAS_RECOVERY_SET/wal" "$ISAS_RESTORE_WAL_DIR"
"/Library/Application Support/ISAS/Production/current/identity/bin/import" --input "$ISAS_RECOVERY_SET/identity.export"
"/Library/Application Support/ISAS/Production/current/object-queue/bin/import" --input "$ISAS_RECOVERY_SET/object-queue.export"
/usr/sbin/chown -R _isas_db:_isas_db "/Library/Application Support/ISAS/Production/data/database"
echo "Verify the audit anchor and object inventory, recover PostgreSQL through the requested WAL target, then bootstrap services in manifest start order."
