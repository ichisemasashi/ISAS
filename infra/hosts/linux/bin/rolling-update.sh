#!/bin/sh
set -eu

[ "$(uname -s)" = "Linux" ] || exit 64
[ "$(id -u)" -eq 0 ] || exit 77
: "${ISAS_NODE_ID:?node identity is required}"
: "${ISAS_PEER_READY_URL:?peer failure-domain readiness URL is required}"
: "${ISAS_DRAIN_URL:=https://127.0.0.1/internal/operations/drain}"
: "${ISAS_DEPLOY_BUNDLE:?versioned Linux deploy bundle is required}"
: "${ISAS_RELEASE_VERSION:?new immutable release version is required}"
[ -x "$ISAS_DEPLOY_BUNDLE/bin/install.sh" ] || { echo "deploy bundle installer is missing" >&2; exit 66; }
curl --fail --silent --show-error --max-time 10 "$ISAS_PEER_READY_URL" >/dev/null
curl --fail --silent --show-error --max-time 10 -X POST "$ISAS_DRAIN_URL" >/dev/null
/usr/local/libexec/isas-production-backup
previous=$(basename "$(readlink -f /opt/isas/current)")
echo "$previous" > /var/lib/isas/rollback-version
systemctl stop isas.target
"$ISAS_DEPLOY_BUNDLE/bin/install.sh"
if ! curl --fail --silent --show-error --max-time 10 https://127.0.0.1/health/ready >/dev/null; then
  ISAS_ROLLBACK_VERSION="$previous" /usr/local/libexec/isas-production-rollback
  exit 1
fi
if [ "${ISAS_APPLY_OS_UPDATES:-NO}" = "YES" ]; then
  apt-get update
  unattended-upgrade --verbose
  if [ -e /var/run/reboot-required ]; then systemctl reboot; fi
fi
echo "rolling update complete for $ISAS_NODE_ID; admit traffic after external SLO checks pass"
