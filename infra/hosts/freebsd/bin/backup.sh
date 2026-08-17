#!/bin/sh
set -eu
[ "$(uname -s)" = "FreeBSD" ] || exit 64
: "${ISAS_RECOVERY_DIR:?off-host encrypted recovery directory is required}"
: "${ISAS_PG_JAIL:=isas_db}"
: "${ISAS_WAL_ARCHIVE:?WAL archive inventory is required}"
: "${ISAS_OBJECT_INVENTORY:?object inventory is required}"
: "${ISAS_AUDIT_ANCHOR:?audit anchor is required}"
: "${ISAS_KEY_REFERENCE:?non-secret key reference is required}"
[ "${ISAS_RECOVERY_ENCRYPTION_VERIFIED:-NO}" = "YES" ] || { echo "recovery destination encryption is not verified" >&2; exit 65; }
stamp=$(date -u +%Y%m%dT%H%M%SZ)
setdir="$ISAS_RECOVERY_DIR/$stamp"
mkdir -m 0700 "$setdir"
jexec "$ISAS_PG_JAIL" pg_basebackup -D "/var/backups/isas/$stamp" -X stream -c fast
zfs snapshot -r "zroot/jails/isas@$stamp"
zfs send -R "zroot/jails/isas@$stamp" > "$setdir/jails.zfs"
install -m 0600 "$ISAS_WAL_ARCHIVE" "$setdir/wal-inventory"
install -m 0600 "$ISAS_OBJECT_INVENTORY" "$setdir/object-inventory"
install -m 0600 "$ISAS_AUDIT_ANCHOR" "$setdir/audit-anchor"
install -m 0600 "$ISAS_KEY_REFERENCE" "$setdir/key-reference"
sha256 "$setdir/jails.zfs" "$setdir/wal-inventory" "$setdir/object-inventory" "$setdir/audit-anchor" "$setdir/key-reference" > "$setdir/SHA256"
echo "$stamp" > "$setdir/recovery-set-id"
echo "recovery set complete: $setdir"
