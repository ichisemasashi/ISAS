#!/usr/bin/env node

import { readdir, readFile, stat } from "node:fs/promises";
import { relative, resolve, sep } from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";

const PHASES = ["R0", "R1", "R2", "R3", "R4", "R5"];
const STATUSES = new Set(["active-transitional", "removed"]);
const ARTIFACT = /^(?:Dockerfile(?:\..+)?|docker-compose.*\.ya?ml|compose.*\.ya?ml|\.dockerignore)$/i;
const COMMAND = /\bdocker\s+(?:compose|build|run|version|login|push)|docker\/(?:setup-buildx|build-push|login)-action@/i;
const SCANNED = /\.(?:sh|mjs|js|cjs|yml|yaml)$/i;
// These files contain command patterns only to reject them. They are guards,
// not runtime or build dependencies.
const POLICY_GUARDS = new Set([
  "ops/docker-retirement/check-docker-retirement.mjs",
  "ops/host-profiles/check-host-profile.mjs",
]);

function text(value) { return typeof value === "string" && value.trim() !== ""; }
function covered(path, roots) { return roots.some((root) => path === root || path.startsWith(`${root}/`)); }

export function validateInventory(value) {
  const errors = [];
  if (value?.schema_version !== 1 || value?.decision !== "ADR-0024" || value?.target !== "zero-active-docker-dependencies") errors.push("inventory identity is invalid");
  if (!Array.isArray(value?.phases) || value.phases.map((item) => item.id).join(",") !== PHASES.join(",")) errors.push("retirement phases must be ordered R0 through R5");
  if (value?.phases?.[0]?.status !== "completed" || value?.phases?.slice(1).some((item) => item.status !== "pending")) errors.push("only R0 may be completed in the initial inventory");
  const ids = new Set();
  for (const item of value?.dependencies || []) {
    if (!text(item.id) || ids.has(item.id)) errors.push(`dependency id is missing or duplicated: ${item.id}`); else ids.add(item.id);
    if (!STATUSES.has(item.status)) errors.push(`${item.id} status is invalid`);
    if (!PHASES.includes(item.retirement_phase) || item.retirement_phase === "R0") errors.push(`${item.id} retirement_phase is invalid`);
    for (const key of ["category", "owner", "replacement"]) if (!text(item[key])) errors.push(`${item.id} ${key} is required`);
    for (const key of ["paths", "completion_checks"]) if (!Array.isArray(item[key]) || item[key].length === 0 || item[key].some((entry) => !text(entry))) errors.push(`${item.id} ${key} must be a non-empty string array`);
  }
  return errors;
}

async function walk(root, directory = root, output = []) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if ([".git", "node_modules", "dist", "coverage", ".local"].includes(entry.name) || entry.name.startsWith("._")) continue;
    const absolute = resolve(directory, entry.name);
    if (entry.isDirectory()) await walk(root, absolute, output);
    else output.push(relative(root, absolute).split(sep).join("/"));
  }
  return output;
}

export async function findUnregisteredDependencies(root, inventory) {
  const registered = inventory.dependencies.filter((item) => item.status === "active-transitional").flatMap((item) => item.paths);
  const errors = [];
  for (const path of await walk(root)) {
    if (ARTIFACT.test(path.split("/").at(-1)) && !covered(path, registered)) errors.push(`unregistered Docker artifact: ${path}`);
    if (SCANNED.test(path) && !POLICY_GUARDS.has(path) && !covered(path, registered)) {
      const body = await readFile(resolve(root, path), "utf8");
      if (COMMAND.test(body)) errors.push(`unregistered Docker command: ${path}`);
    }
  }
  for (const item of inventory.dependencies.filter((entry) => entry.status === "active-transitional")) {
    for (const path of item.paths) {
      try { await stat(resolve(root, path)); } catch { errors.push(`registered active dependency is missing: ${item.id}=${path}`); }
    }
  }
  return errors;
}

export async function checkRepository(root = ".") {
  const inventory = JSON.parse(await readFile(resolve(root, "ops/docker-retirement/docker-retirement-inventory.json"), "utf8"));
  return [...validateInventory(inventory), ...await findUnregisteredDependencies(resolve(root), inventory)];
}

async function main() {
  const errors = await checkRepository();
  if (errors.length) {
    console.error(`Docker retirement: FAIL (${errors.length})`);
    errors.forEach((error) => console.error(`- ${error}`));
    return 1;
  }
  console.log("Docker retirement R0: PASS (inventory complete; unregistered dependencies rejected)");
  return 0;
}

if (import.meta.url === pathToFileURL(resolve(process.argv[1])).href) process.exitCode = await main();
