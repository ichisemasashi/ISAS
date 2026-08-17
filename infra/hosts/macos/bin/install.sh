#!/bin/sh
set -eu

[ "$(uname -s)" = "Darwin" ] || { echo "macOS host required" >&2; exit 64; }
[ "$(id -u)" -eq 0 ] || { echo "root required" >&2; exit 77; }
: "${ISAS_ARTIFACT_DIR:?signed application artifacts are required}"
: "${ISAS_PF_INTERFACE:?external interface inventory is required}"
case "$ISAS_PF_INTERFACE" in *[!A-Za-z0-9_.:-]*|'') echo "invalid external interface" >&2; exit 64 ;; esac
base=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
"$base/bin/preflight.sh"

create_service_user() {
  account=$1
  uid=$2
  existing_user=$(/usr/bin/dscl . -search /Users UniqueID "$uid" 2>/dev/null | /usr/bin/awk 'NR == 1 { print $1 }')
  existing_group=$(/usr/bin/dscl . -search /Groups PrimaryGroupID "$uid" 2>/dev/null | /usr/bin/awk 'NR == 1 { print $1 }')
  [ -z "$existing_user" ] || [ "$existing_user" = "$account" ] || { echo "UID $uid is already assigned to $existing_user" >&2; exit 65; }
  [ -z "$existing_group" ] || [ "$existing_group" = "$account" ] || { echo "GID $uid is already assigned to $existing_group" >&2; exit 65; }
  if ! /usr/bin/dscl . -read "/Groups/$account" >/dev/null 2>&1; then
    /usr/bin/dscl . -create "/Groups/$account"
    /usr/bin/dscl . -create "/Groups/$account" PrimaryGroupID "$uid"
  fi
  if ! /usr/bin/dscl . -read "/Users/$account" >/dev/null 2>&1; then
    /usr/bin/dscl . -create "/Users/$account"
    /usr/bin/dscl . -create "/Users/$account" UserShell /usr/bin/false
    /usr/bin/dscl . -create "/Users/$account" UniqueID "$uid"
    /usr/bin/dscl . -create "/Users/$account" PrimaryGroupID "$uid"
    /usr/bin/dscl . -create "/Users/$account" NFSHomeDirectory /var/empty
  fi
  /usr/bin/dscl . -read "/Groups/$account" PrimaryGroupID | /usr/bin/grep -q " $uid$"
  /usr/bin/dscl . -read "/Users/$account" UniqueID | /usr/bin/grep -q " $uid$"
  /usr/bin/dscl . -read "/Users/$account" PrimaryGroupID | /usr/bin/grep -q " $uid$"
  /usr/bin/dscl . -read "/Users/$account" UserShell | /usr/bin/grep -q " /usr/bin/false$"
}
create_service_user _isas_db 390
create_service_user _isas_idp 391
create_service_user _isas_objq 392
create_service_user _isas_app 393
create_service_user _isas_edge 394
create_service_user _isas_otel 395

root="/Library/Application Support/ISAS/Production"
logs="/Library/Logs/ISAS/Production"
/bin/mkdir -p "$root/current" "$root/config" "$root/data" "$root/secrets" "$root/run" "$logs"
/usr/sbin/chown root:wheel "$root" "$root/current"
/bin/chmod 0755 "$root" "$root/current"
/bin/chmod 0750 "$root/config"
/bin/chmod 0711 "$root/data" "$root/run"
/bin/chmod 0700 "$root/secrets"
for spec in database:_isas_db identity:_isas_idp object-queue:_isas_objq app:_isas_app edge:_isas_edge telemetry:_isas_otel; do
  service=${spec%%:*}
  account=${spec#*:}
  /bin/mkdir -p "$root/data/$service" "$root/run/$service"
  /usr/sbin/chown "$account:$account" "$root/data/$service" "$root/run/$service"
  /bin/chmod 0700 "$root/data/$service" "$root/run/$service"
done
for service in database identity object-queue app edge telemetry; do
  artifact="$ISAS_ARTIFACT_DIR/$service.pkg"
  [ -f "$artifact" ] || { echo "missing signed artifact: $artifact" >&2; exit 66; }
  /usr/sbin/pkgutil --check-signature "$artifact" | /usr/bin/grep -Eq "Status: signed|signed by a certificate trusted by Mac OS X"
  /usr/sbin/installer -pkg "$artifact" -target /
done

/usr/bin/install -m 0555 "$base/bin/backup.sh" /usr/local/libexec/isas-production-backup
/usr/bin/install -m 0555 "$base/bin/restore.sh" /usr/local/libexec/isas-production-restore
/usr/bin/install -m 0555 "$base/bin/rolling-update.sh" /usr/local/libexec/isas-production-rolling-update
/usr/bin/install -m 0555 "$base/bin/monitor.sh" /usr/local/libexec/isas-production-monitor
/usr/bin/sed "s/replace-at-deploy/$ISAS_PF_INTERFACE/g" "$base/config/pf.isas.conf" > /etc/pf.anchors/com.isas.production
/bin/chmod 0600 /etc/pf.anchors/com.isas.production
/sbin/pfctl -n -a com.isas.production -f /etc/pf.anchors/com.isas.production
/sbin/pfctl -a com.isas.production -f /etc/pf.anchors/com.isas.production
/sbin/pfctl -E >/dev/null 2>&1 || true
for label in firewall database identity object-queue app edge telemetry monitor; do
  plist="$base/launchd/com.isas.$label.plist"
  destination="/Library/LaunchDaemons/$(basename "$plist")"
  /usr/bin/install -m 0644 "$plist" "$destination"
  /usr/sbin/chown root:wheel "$destination"
  /bin/launchctl bootout system "$destination" >/dev/null 2>&1 || true
  /bin/launchctl bootstrap system "$destination"
done
echo "macOS Production native services installed; verify every readiness endpoint before admitting traffic"
