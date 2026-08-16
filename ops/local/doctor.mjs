#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import { readFileSync, statfsSync } from "node:fs";
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
  for (let index = 0; index < 3; index += 1) {
    if (left[index] !== right[index]) return left[index] > right[index];
  }
  return true;
}

export function validateComponentLock(lock) {
  if (lock?.schemaVersion !== 1 || lock?.profile !== "local-integration") throw new Error("component lockのschema/profileが不正です");
  if (!Array.isArray(lock.platforms) || !lock.platforms.includes("linux/arm64") || !lock.platforms.includes("linux/amd64")) throw new Error("component lockにはarm64/amd64の両方が必要です");
  for (const [name, component] of Object.entries(lock.images || {})) {
    if (!/@sha256:[0-9a-f]{64}$/.test(component.image || "")) throw new Error(`${name}のimageがdigest固定されていません`);
    for (const platform of lock.platforms) {
      if (!/^sha256:[0-9a-f]{64}$/.test(component.platformDigests?.[platform] || "")) throw new Error(`${name}の${platform} digestがありません`);
    }
  }
  if (Object.keys(lock.images || {}).length < 8) throw new Error("必須componentが不足しています");
  return true;
}

function command(file, args) {
  return execFileSync(file, args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], timeout: 10_000 }).trim();
}

export function collectChecks({ platform = process.platform, arch = process.arch } = {}) {
  const lock = JSON.parse(readFileSync(lockPath, "utf8"));
  validateComponentLock(lock);
  const composeVersion = command("docker", ["compose", "version", "--short"]);
  const daemon = JSON.parse(command("docker", ["info", "--format", "{{json .}}"]));
  const fileSystem = statfsSync(root);
  const freeBytes = Number(fileSystem.bavail) * Number(fileSystem.bsize);
  return [
    { id: "host.os", ok: platform === "darwin", actual: platform, expected: "darwin" },
    { id: "host.arch", ok: ["arm64", "x64"].includes(arch), actual: arch, expected: "arm64|x64" },
    { id: "node", ok: versionAtLeast(process.version, "22.0.0"), actual: process.version, expected: ">=22.0.0" },
    { id: "compose", ok: versionAtLeast(composeVersion, "2.31.0"), actual: composeVersion, expected: ">=2.31.0" },
    { id: "docker.daemon", ok: daemon.ServerVersion != null, actual: daemon.ServerVersion || "unavailable", expected: "available" },
    { id: "docker.arch", ok: ["arm64", "aarch64", "x86_64", "amd64"].includes(daemon.Architecture), actual: daemon.Architecture || "unknown", expected: "arm64|aarch64|x86_64|amd64" },
    { id: "disk.free", ok: freeBytes >= MIN_FREE_BYTES, actual: `${Math.floor(freeBytes / 1024 ** 3)}GiB`, expected: ">=20GiB" },
    { id: "component.lock", ok: true, actual: `${Object.keys(lock.images).length} images`, expected: "digest + arm64/amd64" }
  ];
}

export function main() {
  let checks;
  try { checks = collectChecks(); }
  catch (error) {
    process.stderr.write(`${JSON.stringify({ level: "error", event: "local_doctor_failed", message: error.message })}\n`);
    return 1;
  }
  for (const item of checks) process.stdout.write(`${item.ok ? "PASS" : "FAIL"} ${item.id}: ${item.actual} (expected ${item.expected})\n`);
  return checks.every(({ ok }) => ok) ? 0 : 1;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) process.exitCode = main();
