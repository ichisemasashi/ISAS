#!/bin/sh
set -eu

[ "$(uname -s)" = "Linux" ] || exit 64
: "${ISAS_RECOVERY_DIR:?off-host encrypted recovery directory is required}"
: "${ISAS_WAL_ARCHIVE_DIR:?WAL archive directory is required}"
: "${ISAS_WAL_INVENTORY:?WAL archive inventory is required}"
: "${ISAS_OBJECT_INVENTORY:?object inventory is required}"
: "${ISAS_AUDIT_ANCHOR:?audit anchor is required}"
: "${ISAS_KEY_REFERENCE:?non-secret key reference is required}"
[ "${ISAS_RECOVERY_ENCRYPTION_VERIFIED:-NO}" = "YES" ] || { echo "recovery destination encryption is not verified" >&2; exit 65; }
stamp=$(date -u +%Y%m%dT%H%M%SZ)
setdir="$ISAS_RECOVERY_DIR/$stamp"
install -d -m 0700 "$setdir" "$setdir/wal"
/opt/isas/current/database/bin/pg_basebackup -D "$setdir/postgresql" -X stream -c fast
cp -a "$ISAS_WAL_ARCHIVE_DIR/." "$setdir/wal/"
/opt/isas/current/identity/bin/export --output "$setdir/identity.export"
/opt/isas/current/object-queue/bin/export --output "$setdir/object-queue.export"
install -m 0600 "$ISAS_WAL_INVENTORY" "$setdir/wal-inventory"
install -m 0600 "$ISAS_OBJECT_INVENTORY" "$setdir/object-inventory"
install -m 0600 "$ISAS_AUDIT_ANCHOR" "$setdir/audit-anchor"
install -m 0600 "$ISAS_KEY_REFERENCE" "$setdir/key-reference"
readlink -f /opt/isas/current > "$setdir/release-path"
echo "$stamp" > "$setdir/recovery-set-id"
(cd "$setdir" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS)
echo "recovery set complete: $setdir"
