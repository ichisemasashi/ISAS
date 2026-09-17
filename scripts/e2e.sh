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

mkdir -p target/e2e-run
rm -f target/e2e-run/ready.json
rm -f target/e2e-run/isas.sqlite
rm -rf target/e2e-run/basemaps

npx --no-install shadow-cljs compile app

clojure -M:e2e >target/e2e-run/harness.log 2>&1 &
HARNESS_PID=$!
cleanup() {
  kill "$HARNESS_PID" 2>/dev/null || true
  wait "$HARNESS_PID" 2>/dev/null || true
}
trap cleanup EXIT

for i in $(seq 1 60); do
  if [[ -f target/e2e-run/ready.json ]]; then
    break
  fi
  if ! kill -0 "$HARNESS_PID" 2>/dev/null; then
    echo "e2e harness exited early:" >&2
    cat target/e2e-run/harness.log >&2 || true
    exit 1
  fi
  sleep 1
done
if [[ ! -f target/e2e-run/ready.json ]]; then
  echo "e2e harness did not become ready:" >&2
  cat target/e2e-run/harness.log >&2 || true
  exit 1
fi

# Prefer system Chrome (channel: chrome in playwright.config.js).
# Fall back to Playwright Chromium only when Chrome is unavailable.
find e2e -name '._*' -delete 2>/dev/null || true
if [[ ! -x "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" ]]; then
  npx --no-install playwright install chromium
fi
npx --no-install playwright test
