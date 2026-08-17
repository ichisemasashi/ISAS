#!/bin/sh
set -eu

[ "$(uname -s)" = "Linux" ] || exit 64
[ "$(id -u)" -eq 0 ] || exit 77
: "${ISAS_ROLLBACK_VERSION:?immutable rollback version is required}"
target="/opt/isas/releases/$ISAS_ROLLBACK_VERSION"
[ -d "$target" ] || { echo "rollback release is missing: $target" >&2; exit 66; }
for service in database identity object-queue app edge telemetry; do [ -x "$target/$service/bin/start" ] || { echo "rollback service is incomplete: $service" >&2; exit 66; }; done
systemctl stop isas.target
ln -sfn "$target" /opt/isas/current
apparmor_parser -r /etc/apparmor.d/isas-production
systemctl start isas.target
curl --fail --silent --show-error --max-time 10 https://127.0.0.1/health/ready >/dev/null
echo "rollback complete: $ISAS_ROLLBACK_VERSION"
