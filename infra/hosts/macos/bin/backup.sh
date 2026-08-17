#!/bin/sh
set -eu

[ "$(uname -s)" = "Darwin" ] || exit 64
: "${ISAS_RECOVERY_DIR:?off-host encrypted recovery directory is required}"
: "${ISAS_WAL_ARCHIVE_DIR:?WAL archive directory is required}"
: "${ISAS_WAL_INVENTORY:?WAL archive inventory is required}"
: "${ISAS_OBJECT_INVENTORY:?object inventory is required}"
: "${ISAS_AUDIT_ANCHOR:?audit anchor is required}"
: "${ISAS_KEY_REFERENCE:?non-secret key reference is required}"
[ "${ISAS_RECOVERY_ENCRYPTION_VERIFIED:-NO}" = "YES" ] || { echo "recovery destination encryption is not verified" >&2; exit 65; }
stamp=$(/bin/date -u +%Y%m%dT%H%M%SZ)
setdir="$ISAS_RECOVERY_DIR/$stamp"
/bin/mkdir -m 0700 "$setdir"
"/Library/Application Support/ISAS/Production/current/database/bin/pg_basebackup" -D "$setdir/postgresql" -X stream -c fast
/bin/mkdir -m 0700 "$setdir/wal"
/usr/bin/ditto "$ISAS_WAL_ARCHIVE_DIR" "$setdir/wal"
"/Library/Application Support/ISAS/Production/current/identity/bin/export" --output "$setdir/identity.export"
"/Library/Application Support/ISAS/Production/current/object-queue/bin/export" --output "$setdir/object-queue.export"
/usr/bin/install -m 0600 "$ISAS_WAL_INVENTORY" "$setdir/wal-inventory"
/usr/bin/install -m 0600 "$ISAS_OBJECT_INVENTORY" "$setdir/object-inventory"
/usr/bin/install -m 0600 "$ISAS_AUDIT_ANCHOR" "$setdir/audit-anchor"
/usr/bin/install -m 0600 "$ISAS_KEY_REFERENCE" "$setdir/key-reference"
echo "$stamp" > "$setdir/recovery-set-id"
(cd "$setdir" && /usr/bin/find . -type f ! -name SHA256 -exec /usr/bin/shasum -a 256 {} \; > SHA256)
echo "recovery set complete: $setdir"
