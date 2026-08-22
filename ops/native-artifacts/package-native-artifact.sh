#!/bin/sh
set -eu

[ "$#" -eq 7 ] || { echo "usage: $0 CONTRACT HOST_OS ARCH SERVICE VERSION OUTPUT_DIR SIGNING_KEY" >&2; exit 64; }
contract=$1
host_os=$2
arch=$3
service=$4
version=$5
output_dir=$6
signing_key=$7
[ -r "$signing_key" ] || { echo "signing key is not readable" >&2; exit 66; }
repo_root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/isas-native.XXXXXX")
trap 'rm -rf "$work_dir"' EXIT HUP INT TERM
payload="$work_dir/payload"
mkdir -p "$output_dir"
node "$repo_root/ops/native-artifacts/prepare-native-payload.mjs" "$contract" "$host_os" "$arch" "$service" "$version" "$payload" >/dev/null

case "$host_os" in
  macos)
    [ "$(uname -s)" = "Darwin" ] || { echo "macOS package must be built on macOS" >&2; exit 64; }
    artifact="$output_dir/$host_os-$arch-$service.pkg"
    pkgbuild --root "$payload" --identifier "org.isas.$service" --version "$version" --install-location / "$artifact"
    ;;
  linux)
    [ "$(uname -s)" = "Linux" ] || { echo "Linux package must be built on Linux" >&2; exit 64; }
    artifact="$output_dir/$host_os-$arch-$service.deb"
    mkdir -p "$payload/DEBIAN"
    installed_size=$(du -sk "$payload" | awk '{print $1}')
    cat >"$payload/DEBIAN/control" <<EOF
Package: isas-$service
Version: $version
Architecture: $(dpkg --print-architecture)
Maintainer: ISAS Release Maintainers
Installed-Size: $installed_size
Description: ISAS $service native service payload
EOF
    dpkg-deb --root-owner-group --build "$payload" "$artifact"
    ;;
  freebsd)
    [ "$(uname -s)" = "FreeBSD" ] || { echo "FreeBSD package must be built on FreeBSD" >&2; exit 64; }
    artifact="$output_dir/$host_os-$arch-$service.pkg"
    manifest="$work_dir/+MANIFEST"
    cat >"$manifest" <<EOF
name: isas-$service
version: "$version"
origin: local/isas-$service
comment: ISAS $service native service payload
maintainer: release@localhost.invalid
www: https://example.invalid/isas
abi: "FreeBSD:$(freebsd-version -u | cut -d- -f1):$(uname -p)"
prefix: /
flatsize: 0
desc: ISAS $service native service payload
EOF
    pkg create -M "$manifest" -r "$payload" -o "$output_dir"
    generated=$(find "$output_dir" -type f -name 'isas-*.pkg' -newer "$manifest" | head -1)
    [ -n "$generated" ] || { echo "FreeBSD pkg output is missing" >&2; exit 66; }
    mv "$generated" "$artifact"
    ;;
  *) echo "unsupported host OS" >&2; exit 64 ;;
esac

sha256=$(shasum -a 256 "$artifact" | awk '{print $1}')
printf '%s  %s\n' "$sha256" "$(basename "$artifact")" >"$artifact.sha256"
openssl dgst -sha256 -sign "$signing_key" -out "$artifact.sig" "$artifact"
node "$repo_root/ops/native-artifacts/write-attestations.mjs" "$artifact" "$host_os" "$arch" "$service" "$version" "$sha256"
echo "$artifact"
