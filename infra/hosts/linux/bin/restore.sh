#!/bin/sh
set -eu

[ "$(uname -s)" = "Linux" ] || exit 64
[ "$(id -u)" -eq 0 ] || exit 77
: "${ISAS_RECOVERY_SET:?recovery set directory is required}"
: "${ISAS_RESTORE_WAL_DIR:?empty WAL restore directory is required}"
(cd "$ISAS_RECOVERY_SET" && sha256sum --check SHA256SUMS)
for item in postgresql wal identity.export object-queue.export wal-inventory object-inventory audit-anchor key-reference release-path recovery-set-id; do
  [ -e "$ISAS_RECOVERY_SET/$item" ] || { echo "missing recovery item: $item" >&2; exit 66; }
done
systemctl stop isas.target
[ -z "$(ls -A /var/lib/isas/database 2>/dev/null)" ] || { echo "database restore target must be empty" >&2; exit 65; }
cp -a "$ISAS_RECOVERY_SET/postgresql/." /var/lib/isas/database/
install -d -m 0700 "$ISAS_RESTORE_WAL_DIR"
cp -a "$ISAS_RECOVERY_SET/wal/." "$ISAS_RESTORE_WAL_DIR/"
/opt/isas/current/identity/bin/import --input "$ISAS_RECOVERY_SET/identity.export"
/opt/isas/current/object-queue/bin/import --input "$ISAS_RECOVERY_SET/object-queue.export"
chown -R isas-db:isas-db /var/lib/isas/database
echo "Verify audit anchor and object inventory, recover PostgreSQL through the requested WAL target, then start isas.target."
