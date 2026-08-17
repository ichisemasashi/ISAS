#!/bin/sh
set -eu

[ "$(uname -s)" = "FreeBSD" ] || { echo "FreeBSD host required" >&2; exit 64; }
[ "$(id -u)" -eq 0 ] || { echo "root required" >&2; exit 77; }
: "${ISAS_ZPOOL:=zroot}"
: "${ISAS_ARTIFACT_DIR:?signed application artifacts are required}"
: "${ISAS_SIGNING_PUBLIC_KEY:?artifact signing public key is required}"
: "${ISAS_EXTERNAL_INTERFACE:?external interface is required}"
case "$ISAS_EXTERNAL_INTERFACE" in *[!A-Za-z0-9_.:-]*|'') echo "invalid external interface" >&2; exit 64 ;; esac

for command in zfs jail pfctl rctl pkg freebsd-update; do command -v "$command" >/dev/null; done
pkg audit -F
create_service() {
  service=$1
  quota=$2
  dataset="$ISAS_ZPOOL/jails/isas/$service"
  zfs list "$dataset" >/dev/null 2>&1 || zfs create -o mountpoint="/jails/isas/$service" "$dataset"
  zfs set "quota=$quota" "$dataset"
  zfs list "$dataset/root" >/dev/null 2>&1 || zfs create "$dataset/root"
  zfs list "$dataset/secrets" >/dev/null 2>&1 || zfs create -o mountpoint="/jails/isas/$service/secrets" "$dataset/secrets"
  chmod 0700 "/jails/isas/$service/secrets"
  if [ ! -x "/jails/isas/$service/root/bin/sh" ]; then bsdinstall jail "/jails/isas/$service/root"; fi
  artifact="$ISAS_ARTIFACT_DIR/$service.pkg"
  signature="$artifact.sig"
  [ -f "$artifact" ] && [ -f "$signature" ] || { echo "missing signed artifact for $service" >&2; exit 66; }
  openssl dgst -sha256 -verify "$ISAS_SIGNING_PUBLIC_KEY" -signature "$signature" "$artifact"
  install -m 0600 "$artifact" "/jails/isas/$service/root/tmp/isas-service.pkg"
  pkg -c "/jails/isas/$service/root" add /tmp/isas-service.pkg
  rm -f "/jails/isas/$service/root/tmp/isas-service.pkg"
}
create_service database 500G
create_service identity 40G
create_service object-queue 1T
create_service app 20G
create_service edge 10G
create_service telemetry 200G
install -m 0600 "$(dirname "$0")/../config/jail.conf" /etc/jail.conf.d/isas.conf
sed "s/replace-at-deploy/$ISAS_EXTERNAL_INTERFACE/g" "$(dirname "$0")/../config/pf.isas.conf" > /etc/pf.isas.conf
chmod 0600 /etc/pf.isas.conf
install -m 0600 "$(dirname "$0")/../config/rctl.conf" /etc/rctl.conf.d/isas.conf
install -m 0555 "$(dirname "$0")/../rc.d/isas" /usr/local/etc/rc.d/isas
install -m 0555 "$(dirname "$0")/jail-net.sh" /usr/local/libexec/isas-jail-net
echo "Merge the reviewed rc.conf fragment, include /etc/pf.isas.conf from the site pf.conf, then start service isas."
