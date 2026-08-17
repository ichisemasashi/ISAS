#!/bin/sh
set -eu

host_os=${ISAS_HOST_OS:-$(uname -s)}
case "$host_os" in
  FreeBSD) target="$(dirname "$0")/../../infra/hosts/freebsd/bin/install.sh" ;;
  Darwin) target="$(dirname "$0")/../../infra/hosts/macos/bin/install.sh" ;;
  Linux) target="$(dirname "$0")/../../infra/hosts/linux/profile.json" ;;
  *) echo "unsupported host OS: $host_os" >&2; exit 64 ;;
esac
[ "${ISAS_DISPATCH_ONLY:-0}" = 1 ] && { echo "$host_os -> $target"; exit 0; }
case "$host_os" in
  FreeBSD|Darwin) exec "$target" "$@" ;;
  Linux) echo "$host_os Production installer is not implemented; selected definition: $target" >&2; exit 69 ;;
esac
