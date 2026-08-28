#!/usr/bin/env node
import { chmodSync, mkdirSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "../..");
const dataRoot = process.env.ISAS_NATIVE_DATA_ROOT || resolve(process.env.HOME, "Library/Application Support/ISAS/local-integration");
const launchdRoot = resolve(dataRoot, "launchd");
const logRoot = resolve(dataRoot, "log");
const wrapper = resolve(dataRoot, "runtime/bin/native-service.sh");
const services = ["database", "pgbouncer-p0", "pgbouncer-auth-p1", "pgbouncer-p1", "pgbouncer-p2", "pgbouncer-ops", "keycloak", "telemetry", "bff", "edge"];

function xml(value) { return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;"); }
mkdirSync(launchdRoot, { recursive: true, mode: 0o700 });
mkdirSync(logRoot, { recursive: true, mode: 0o700 });

for (const service of services) {
  const label = `com.isas.local.${service}`;
  const body = `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>${label}</string>
  <key>ProgramArguments</key><array><string>/bin/sh</string><string>${xml(wrapper)}</string><string>${service}</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>5</integer>
  <key>StandardOutPath</key><string>${xml(resolve(logRoot, `${service}.out.log`))}</string>
  <key>StandardErrorPath</key><string>${xml(resolve(logRoot, `${service}.err.log`))}</string>
</dict></plist>
`;
  const path = resolve(launchdRoot, `${label}.plist`);
  writeFileSync(path, body, { mode: 0o600 });
  chmodSync(path, 0o600);
}

process.stdout.write(`native launchd definitions: ${services.length}\n`);
