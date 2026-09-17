#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x /Volumes/ONE/homebrew/opt/openjdk@21/bin/java ]]; then
    export JAVA_HOME="/Volumes/ONE/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  elif command -v /usr/libexec/java_home >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
  fi
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
clojure -M:coverage
npx --no-install shadow-cljs compile app
npx --no-install shadow-cljs compile test
node target/node-tests.js
./scripts/e2e.sh
