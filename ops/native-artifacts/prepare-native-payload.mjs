#!/usr/bin/env node

import { cp, mkdir, readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import process from "node:process";

const [contractFile, hostOs, architecture, service, version, output] = process.argv.slice(2);
if (![contractFile, hostOs, architecture, service, version, output].every(Boolean) || !/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(version)) {
  console.error("usage: prepare-native-payload.mjs CONTRACT HOST_OS ARCH SERVICE VERSION OUTPUT");
  process.exit(64);
}
const contract = JSON.parse(await readFile(contractFile, "utf8"));
const target = contract.targets?.find((item) => item.host_os === hostOs && item.architectures?.includes(architecture));
const spec = contract.service_payloads?.[service];
if (!target || !contract.services?.includes(service) || !spec) throw new Error("unsupported native artifact target or service");

const payload = resolve(output);
const prefix = hostOs === "macos"
  ? resolve(payload, "Library/Application Support/ISAS/Production/releases", version, service)
  : hostOs === "linux"
    ? resolve(payload, "opt/isas/releases", version, service)
    : resolve(payload, "usr/local/isas/releases", version, service);
await mkdir(resolve(prefix, "bin"), { recursive: true });
await mkdir(resolve(prefix, "share"), { recursive: true });
for (const source of spec.sources) {
  const absolute = resolve(source);
  await cp(absolute, resolve(prefix, "share", source), { recursive: true, force: false, errorOnExist: true });
}
const launcher = `#!/bin/sh
set -eu
: "\${${spec.executable_env}:?${spec.executable_env} must point to the reviewed native executable}"
case "\${${spec.executable_env}}" in /*) ;; *) echo "native executable must be an absolute path" >&2; exit 64 ;; esac
[ -x "\${${spec.executable_env}}" ] || { echo "native executable is not executable" >&2; exit 66; }
exec "\${${spec.executable_env}}" "$@"
`;
await writeFile(resolve(prefix, "bin/start"), launcher, { mode: 0o555, flag: "wx" });
await writeFile(resolve(prefix, "artifact-metadata.json"), `${JSON.stringify({ schema_version: 1, host_os: hostOs, architecture, service, version, format: target.format, source_paths: spec.sources }, null, 2)}\n`, { mode: 0o444, flag: "wx" });
console.log(prefix);
