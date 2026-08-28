#!/bin/sh
set -eu
OPS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$OPS_DIR/common.sh"
LOCK="$ISAS_REPO_ROOT/infra/local/component-lock.json"
COMPONENT_ROOT="$ISAS_NATIVE_DATA_ROOT/components"
CACHE_ROOT=${ISAS_COMPONENT_CACHE_DIR:-$ISAS_NATIVE_DATA_ROOT/downloads}
mkdir -p "$COMPONENT_ROOT" "$CACHE_ROOT"
chmod 700 "$ISAS_NATIVE_DATA_ROOT" "$COMPONENT_ROOT"
if [ "$CACHE_ROOT" = "$ISAS_NATIVE_DATA_ROOT/downloads" ]; then chmod 700 "$CACHE_ROOT"; fi

json() { node -e "const v=require(process.argv[1]); const p=process.argv[2].split('.'); let x=v; for(const k of p)x=x[k]; process.stdout.write(String(x))" "$LOCK" "$1"; }
fetch() {
  url=$1 path=$2 digest=$3
  if [ ! -f "$path" ]; then curl -fL --retry 3 -o "$path.part" "$url"; mv "$path.part" "$path"; fi
  actual=$(shasum -a 256 "$path" | awk '{print $1}')
  [ "$actual" = "$digest" ] || { echo "component checksum mismatch: $path" >&2; exit 65; }
}

keycloak_version=$(json components.keycloak.version)
keycloak_archive="$CACHE_ROOT/keycloak-$keycloak_version.tar.gz"
if [ ! -x "$COMPONENT_ROOT/keycloak/bin/kc.sh" ]; then
  fetch "$(json components.keycloak.url)" "$keycloak_archive" "$(json components.keycloak.sha256)"
  staging=$(mktemp -d "$ISAS_NATIVE_DATA_ROOT/keycloak-install.XXXXXX")
  trap 'rm -rf "$staging"' EXIT INT TERM
  tar -xzf "$keycloak_archive" -C "$staging"
  mv "$staging/keycloak-$keycloak_version" "$COMPONENT_ROOT/keycloak"
  trap - EXIT INT TERM
  rmdir "$staging"
fi

case "$(uname -m)" in arm64) platform=darwin/arm64; java_arch=aarch64; suffix=darwin_arm64 ;; x86_64) platform=darwin/x64; java_arch=x64; suffix=darwin_amd64 ;; *) echo "unsupported Mac architecture" >&2; exit 78 ;; esac
java_version=$(json components.java.version)
java_archive="$CACHE_ROOT/temurin-${java_version}-${java_arch}.tar.gz"
if [ ! -x "$COMPONENT_ROOT/java/Contents/Home/bin/java" ]; then
  fetch "$(json components.java.artifacts.$platform.url)" "$java_archive" "$(json components.java.artifacts.$platform.sha256)"
  staging=$(mktemp -d "$ISAS_NATIVE_DATA_ROOT/java-install.XXXXXX")
  trap 'rm -rf "$staging"' EXIT INT TERM
  tar -xzf "$java_archive" -C "$staging"
  java_source=$(find "$staging" -mindepth 1 -maxdepth 1 -type d | head -n 1)
  [ -n "$java_source" ] || { echo "Temurin archive layout is invalid" >&2; exit 65; }
  mv "$java_source" "$COMPONENT_ROOT/java"
  trap - EXIT INT TERM
  rmdir "$staging"
fi
otel_version=$(json components.otelCollector.version)
otel_archive="$CACHE_ROOT/otelcol-contrib_${otel_version}_${suffix}.tar.gz"
if [ ! -x "$COMPONENT_ROOT/otelcol-contrib" ]; then
  fetch "$(json components.otelCollector.artifacts.$platform.url)" "$otel_archive" "$(json components.otelCollector.artifacts.$platform.sha256)"
  tar -xzf "$otel_archive" -C "$COMPONENT_ROOT" otelcol-contrib
  chmod 700 "$COMPONENT_ROOT/otelcol-contrib"
fi

echo "native local components: Temurin $java_version, Keycloak $keycloak_version, and OpenTelemetry Collector $otel_version ready"
