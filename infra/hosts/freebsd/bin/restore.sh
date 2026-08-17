#!/bin/sh
set -eu
[ "$(uname -s)" = "FreeBSD" ] || exit 64
[ "$(id -u)" -eq 0 ] || exit 77
: "${ISAS_RECOVERY_SET:?recovery set directory is required}"
sha256 -c "$ISAS_RECOVERY_SET/SHA256"
for item in wal-inventory object-inventory audit-anchor key-reference recovery-set-id; do [ -s "$ISAS_RECOVERY_SET/$item" ] || { echo "missing recovery metadata: $item" >&2; exit 66; }; done
service isas stop || true
zfs receive -F "zroot/jails/isas" < "$ISAS_RECOVERY_SET/jails.zfs"
echo "Restore PostgreSQL base backup and WAL to the requested recovery target, then verify object inventory and audit anchor before service isas start."
