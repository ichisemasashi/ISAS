#!/usr/bin/env node
import { execFileSync, spawnSync } from "node:child_process";
import { existsSync, readFileSync, statfsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const lockPath = resolve(root, "infra/local/component-lock.json");
const MIN_FREE_BYTES = 20 * 1024 ** 3;

export function parseVersion(value) {
  const match = String(value).match(/(\d+)\.(\d+)\.(\d+)/);
  if (!match) throw new Error(`versionを判定できません: ${value}`);
  return match.slice(1).map(Number);
}

export function versionAtLeast(actual, minimum) {
  const left = parseVersion(actual);
  const right = parseVersion(minimum);
  for (let index = 0; index < 3; index += 1) if (left[index] !== right[index]) return left[index] > right[index];
  return true;
}

export function validateComponentLock(lock) {
  if (lock?.schemaVersion !== 2 || lock?.profile !== "local-integration") throw new Error("component lockのschema/profileが不正です");
  for (const platform of ["darwin/arm64", "darwin/x64"]) if (!lock.platforms?.includes(platform)) throw new Error(`${platform}がcomponent lockにありません`);
  for (const name of ["postgresql", "postgis", "pgbouncer", "caddy", "java", "keycloak", "otelCollector"]) if (!lock.components?.[name]) throw new Error(`${name}がcomponent lockにありません`);
  const keycloak = lock.components.keycloak;
  if (!/^https:\/\//.test(keycloak.url || "") || !/^[0-9a-f]{64}$/.test(keycloak.sha256 || "")) throw new Error("Keycloak artifactがversion固定されていません");
  for (const platform of lock.platforms) {
    for (const name of ["java", "otelCollector"]) {
      const artifact = lock.components[name].artifacts?.[platform];
      if (!/^https:\/\//.test(artifact?.url || "") || !/^[0-9a-f]{64}$/.test(artifact?.sha256 || "")) throw new Error(`${platform} ${name} artifactがversion固定されていません`);
    }
  }
  return true;
}

function command(file, args) { return execFileSync(file, args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], timeout: 10_000 }).trim(); }
function commandOutput(file, args) {
  const result = spawnSync(file, args, { encoding: "utf8", timeout: 30_000 });
  if (result.error || result.status !== 0) throw result.error || new Error(`${file} exited ${result.status}`);
  return `${result.stdout || ""}${result.stderr || ""}`.trim();
}

export function collectChecks({ platform = process.platform, arch = process.arch } = {}) {
  const lock = JSON.parse(readFileSync(lockPath, "utf8"));
  validateComponentLock(lock);
  const brew = command("brew", ["--prefix"]);
  const dataRoot = process.env.ISAS_NATIVE_DATA_ROOT || resolve(process.env.HOME, "Library/Application Support/ISAS/local-integration");
  const nativeRoot = resolve(dataRoot, "components");
  const fileSystem = statfsSync(root);
  const freeBytes = Number(fileSystem.bavail) * Number(fileSystem.bsize);
  const postgres = resolve(brew, "opt/postgresql@16/bin/postgres");
  const pgConfig = resolve(brew, "opt/postgresql@16/bin/pg_config");
  const pgBouncer = resolve(brew, "bin/pgbouncer");
  const caddy = resolve(brew, "opt/caddy/bin/caddy");
  const java = resolve(nativeRoot, "java/Contents/Home/bin/java");
  const postgisControl = existsSync(pgConfig) ? resolve(command(pgConfig, ["--sharedir"]), "extension/postgis.control") : "";
  const keycloakVersion = readFileSync(resolve(nativeRoot, "keycloak/version.txt"), "utf8").trim();
  const telemetry = resolve(nativeRoot, "otelcol-contrib");
  const checks = [
    { id: "host.os", ok: platform === "darwin", actual: platform, expected: "darwin" },
    { id: "host.arch", ok: ["arm64", "x64"].includes(arch), actual: arch, expected: "arm64|x64" },
    { id: "node", ok: versionAtLeast(process.version, "22.0.0"), actual: process.version, expected: ">=22.0.0" },
    { id: "disk.free", ok: freeBytes >= MIN_FREE_BYTES, actual: `${Math.floor(freeBytes / 1024 ** 3)}GiB`, expected: ">=20GiB" },
    { id: "postgresql", ok: existsSync(postgres) && /^postgres \(PostgreSQL\) 16\./.test(command(postgres, ["--version"])), actual: existsSync(postgres) ? command(postgres, ["--version"]) : "missing", expected: "16.x" },
    { id: "postgis", ok: existsSync(postgisControl) && readFileSync(postgisControl, "utf8").includes(`default_version = '${lock.components.postgis.version}'`), actual: lock.components.postgis.version, expected: lock.components.postgis.version },
    { id: "pgbouncer", ok: existsSync(pgBouncer) && commandOutput(pgBouncer, ["--version"]).includes(lock.components.pgbouncer.version), actual: lock.components.pgbouncer.version, expected: lock.components.pgbouncer.version },
    { id: "caddy", ok: existsSync(caddy) && commandOutput(caddy, ["version"]).includes(lock.components.caddy.version), actual: lock.components.caddy.version, expected: lock.components.caddy.version },
    { id: "java", ok: existsSync(java) && commandOutput(java, ["-version"]).includes(lock.components.java.version), actual: existsSync(java) ? commandOutput(java, ["-version"]).split("\n")[0] : "missing", expected: lock.components.java.version },
    { id: "keycloak", ok: keycloakVersion.includes(lock.components.keycloak.version), actual: keycloakVersion, expected: lock.components.keycloak.version },
    { id: "telemetry", ok: existsSync(telemetry) && commandOutput(telemetry, ["--version"]).includes(lock.components.otelCollector.version), actual: lock.components.otelCollector.version, expected: lock.components.otelCollector.version },
    { id: "component.lock", ok: true, actual: `${Object.keys(lock.components).length} native components`, expected: "content-bound artifacts" }
  ];
  return checks;
}

export function main() {
  let checks;
  try { checks = collectChecks(); }
  catch (error) { process.stderr.write(`${JSON.stringify({ level: "error", event: "local_doctor_failed", message: error.message })}\n`); return 1; }
  for (const item of checks) process.stdout.write(`${item.ok ? "PASS" : "FAIL"} ${item.id}: ${item.actual} (expected ${item.expected})\n`);
  return checks.every(({ ok }) => ok) ? 0 : 1;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) process.exitCode = main();
