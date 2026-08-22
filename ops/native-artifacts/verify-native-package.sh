#!/bin/sh
set -eu

[ "$#" -eq 4 ] || { echo "usage: $0 HOST_OS VERSION ARTIFACT PUBLIC_KEY" >&2; exit 64; }
host_os=$1
version=$2
artifact=$3
public_key=$4
[ "${ISAS_EPHEMERAL_NATIVE_RUNNER:-NO}" = "YES" ] || { echo "native install verification requires an approved ephemeral runner" >&2; exit 77; }
[ -r "$artifact" ] && [ -r "$artifact.sig" ] && [ -r "$public_key" ] || { echo "artifact, signature, or public key is missing" >&2; exit 66; }
digest=$(shasum -a 256 "$artifact" | awk '{print $1}')
grep -qx "$digest  $(basename "$artifact")" "$artifact.sha256"
openssl dgst -sha256 -verify "$public_key" -signature "$artifact.sig" "$artifact"
service=$(basename "$artifact" | sed -E 's/^(macos|linux|freebsd)-(arm64|amd64|aarch64|x86_64)-(.+)\.(pkg|deb)$/\3/')
case "$host_os" in
  macos)
    [ "$(uname -s)" = "Darwin" ] || exit 64
    sudo /usr/sbin/installer -pkg "$artifact" -target /
    installed="/Library/Application Support/ISAS/Production/releases/$version/$service/bin/start"
    ;;
  linux)
    [ "$(uname -s)" = "Linux" ] || exit 64
    sudo dpkg -i "$artifact"
    installed="/opt/isas/releases/$version/$service/bin/start"
    ;;
  freebsd)
    [ "$(uname -s)" = "FreeBSD" ] || exit 64
    sudo pkg add -f "$artifact"
    installed="/usr/local/isas/releases/$version/$service/bin/start"
    ;;
  *) exit 64 ;;
esac
[ -x "$installed" ] || { echo "installed start entrypoint is missing: $installed" >&2; exit 66; }
node "$(dirname "$0")/verify-native-package.mjs" "$artifact.record.json" "$digest" true true
echo "native package install verification: PASS $host_os $service $version"
