#!/bin/sh
set -eu

[ "$(uname -s)" = "Darwin" ] || exit 64
[ "$(id -u)" -eq 0 ] || exit 77
: "${ISAS_NODE_ID:?node identity is required}"
: "${ISAS_PEER_READY_URL:?peer failure-domain readiness URL is required}"
: "${ISAS_DRAIN_URL:=https://127.0.0.1/internal/operations/drain}"
: "${ISAS_ARTIFACT_DIR:?signed artifacts are required}"
/usr/bin/curl --fail --silent --show-error --max-time 10 "$ISAS_PEER_READY_URL" >/dev/null
/usr/bin/curl --fail --silent --show-error --max-time 10 -X POST "$ISAS_DRAIN_URL" >/dev/null
/usr/local/libexec/isas-production-backup
for label in edge app telemetry object-queue identity database; do /bin/launchctl bootout system "/Library/LaunchDaemons/com.isas.$label.plist" >/dev/null 2>&1 || true; done
for artifact in "$ISAS_ARTIFACT_DIR"/*.pkg; do
  /usr/sbin/pkgutil --check-signature "$artifact" | /usr/bin/grep -Eq "Status: signed|signed by a certificate trusted by Mac OS X"
  /usr/sbin/installer -pkg "$artifact" -target /
done
if [ "${ISAS_APPLY_OS_UPDATES:-NO}" = "YES" ]; then /usr/sbin/softwareupdate --install --recommended --restart; fi
for label in database identity object-queue app edge telemetry; do /bin/launchctl bootstrap system "/Library/LaunchDaemons/com.isas.$label.plist"; done
/usr/bin/curl --fail --silent --show-error --max-time 10 https://127.0.0.1/health/ready >/dev/null
echo "rolling update complete for $ISAS_NODE_ID; admit traffic after external readiness and SLO checks pass"
