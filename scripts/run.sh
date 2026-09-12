#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x /Volumes/ONE/homebrew/opt/openjdk@21/bin/java ]]; then
    export JAVA_HOME="/Volumes/ONE/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  fi
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
npx --no-install shadow-cljs compile app
exec clojure -M:run "$@"
