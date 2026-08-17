#!/bin/sh
set -eu

[ "$(uname -s)" = "Linux" ] || { echo "Linux host required" >&2; exit 64; }
[ "$(id -u)" -eq 0 ] || { echo "root required" >&2; exit 77; }
: "${ISAS_ARTIFACT_DIR:?signed native deb artifacts are required}"
: "${ISAS_ARTIFACT_REGISTRY:?artifact registry identity is required}"
: "${ISAS_RELEASE_VERSION:?immutable release version is required}"
: "${ISAS_SIGNING_PUBLIC_KEY:?artifact signing public key is required}"
: "${ISAS_MANAGEMENT_CIDR:?management CIDR is required}"
case "$ISAS_MANAGEMENT_CIDR" in *[!0-9a-fA-F:./]*|'') echo "invalid management CIDR" >&2; exit 64 ;; esac
base=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
"$base/bin/preflight.sh"
for command in systemctl systemd-analyze systemd-sysusers systemd-tmpfiles systemd-creds nft apparmor_parser openssl dpkg dpkg-deb; do command -v "$command" >/dev/null; done

verify_file() {
  file=$1
  [ -f "$file" ] && [ -f "$file.sig" ] || { echo "missing signed artifact: $file" >&2; exit 66; }
  openssl dgst -sha256 -verify "$ISAS_SIGNING_PUBLIC_KEY" -signature "$file.sig" "$file"
}
for service in database identity object-queue app edge telemetry; do
  package="$ISAS_ARTIFACT_DIR/$service.deb"
  sbom="$package.sbom.spdx.json"
  provenance="$package.provenance.json"
  verify_file "$package"
  verify_file "$sbom"
  verify_file "$provenance"
  [ "$(dpkg-deb -f "$package" Version)" = "$ISAS_RELEASE_VERSION" ] || { echo "package version mismatch: $service" >&2; exit 66; }
  dpkg -i "$package"
  [ -x "/opt/isas/releases/$ISAS_RELEASE_VERSION/$service/bin/start" ] || { echo "package did not install immutable service path: $service" >&2; exit 66; }
done

install -d -m 0755 /etc/isas /etc/systemd/system /etc/systemd/system/systemd-journald@isas.service.d /etc/apparmor.d /etc/nftables.d /etc/sysusers.d /etc/tmpfiles.d /etc/sysctl.d /etc/apt/apt.conf.d /usr/local/libexec
install -m 0644 "$base/config/isas.sysusers" /etc/sysusers.d/isas.conf
install -m 0644 "$base/config/isas.tmpfiles" /etc/tmpfiles.d/isas.conf
install -m 0644 "$base/config/journald.conf" /etc/systemd/system/systemd-journald@isas.service.d/limits.conf
install -m 0644 "$base/config/apparmor.isas" /etc/apparmor.d/isas-production
sed "s#replace-management-cidr#$ISAS_MANAGEMENT_CIDR#g" "$base/config/nftables.isas.conf" > /etc/nftables.d/isas-production.nft
chmod 0600 /etc/nftables.d/isas-production.nft
install -m 0644 "$base/config/sysctl.isas.conf" /etc/sysctl.d/90-isas-production.conf
install -m 0644 "$base/config/apt.isas.conf" /etc/apt/apt.conf.d/90-isas-production
install -m 0644 "$base"/systemd/* /etc/systemd/system/
install -m 0555 "$base/bin/backup.sh" /usr/local/libexec/isas-production-backup
install -m 0555 "$base/bin/restore.sh" /usr/local/libexec/isas-production-restore
install -m 0555 "$base/bin/rolling-update.sh" /usr/local/libexec/isas-production-rolling-update
install -m 0555 "$base/bin/rollback.sh" /usr/local/libexec/isas-production-rollback
install -m 0555 "$base/bin/monitor.sh" /usr/local/libexec/isas-production-monitor
systemd-sysusers /etc/sysusers.d/isas.conf
systemd-tmpfiles --create /etc/tmpfiles.d/isas.conf
for service in database identity object-queue app edge telemetry; do
  credential="/etc/isas/credentials/$service.cred"
  [ -r "$credential" ] || { echo "encrypted credential is missing: $credential" >&2; exit 66; }
  systemd-creds is-encrypted "$credential"
done
apparmor_parser -r /etc/apparmor.d/isas-production
nft --check --file /etc/nftables.d/isas-production.nft
sysctl --system >/dev/null
systemd-analyze verify /etc/systemd/system/isas-*.service /etc/systemd/system/isas-*.timer /etc/systemd/system/isas.target
ln -sfn "/opt/isas/releases/$ISAS_RELEASE_VERSION" /opt/isas/current
systemctl daemon-reload
systemctl enable --now isas-firewall.service isas-monitor.timer isas-certificate-renew.timer isas.target
echo "Linux Production $ISAS_RELEASE_VERSION installed from $ISAS_ARTIFACT_REGISTRY; verify readiness before admitting traffic"
