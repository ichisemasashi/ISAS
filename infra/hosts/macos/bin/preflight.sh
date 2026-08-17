#!/bin/sh
set -eu

[ "$(uname -s)" = "Darwin" ] || { echo "macOS host required" >&2; exit 64; }
case "$(uname -m)" in arm64|x86_64) ;; *) echo "unsupported architecture" >&2; exit 64 ;; esac
[ "$(id -u)" -eq 0 ] || { echo "root required" >&2; exit 77; }
: "${ISAS_SUPPORTED_MACOS_MAJORS:?comma-separated supported macOS major versions are required}"
major=$(/usr/bin/sw_vers -productVersion | /usr/bin/cut -d. -f1)
case ",$ISAS_SUPPORTED_MACOS_MAJORS," in *",$major,"*) ;; *) echo "macOS major version is outside the approved support matrix: $major" >&2; exit 65 ;; esac
/usr/bin/fdesetup status | /usr/bin/grep -q "FileVault is On" || { echo "FileVault must be enabled" >&2; exit 65; }
/usr/bin/pmset -g custom | /usr/bin/awk '
  /AC Power:/ { ac=1; next }
  /^[^ ]/ { ac=0 }
  ac && $1 == "sleep" && $2 == "0" { sleep_ok=1 }
  ac && $1 == "disksleep" && $2 == "0" { disk_ok=1 }
  END { exit !(sleep_ok && disk_ok) }
' || { echo "AC sleep and disksleep must both be 0" >&2; exit 65; }
/usr/sbin/systemsetup -getusingnetworktime | /usr/bin/grep -qi "On" || { echo "network time must be enabled" >&2; exit 65; }
[ ! -e "/Library/Application Support/ISAS/Production/infra/local" ] || { echo "local-integration data found under Production root" >&2; exit 65; }
[ ! -S "/var/run/docker.sock" ] || echo "warning: Docker exists but is forbidden as an ISAS Production dependency" >&2
echo "macOS Production preflight: PASS"
