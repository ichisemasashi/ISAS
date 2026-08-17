#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import process from "node:process";
import { pathToFileURL } from "node:url";

const HOSTS = new Set(["freebsd", "macos", "linux"]);
const BOUNDARIES = ["edge", "app", "database", "identity", "object-queue", "telemetry"];
const GATES = ["install", "reboot", "start_stop", "rolling_restart", "upgrade", "rollback", "backup", "pitr_restore", "full_host_restore", "security", "e2e", "slo"];
const EVIDENCE = /^(?:artifact|https|s3|file):\/\/.+/;

function text(value) { return typeof value === "string" && value.trim() !== "" && !/replace-me|example/i.test(value); }
function uniqueText(values) { return Array.isArray(values) && values.length > 0 && values.every(text) && new Set(values).size === values.length; }

export function validateDefinition(value) {
  const errors = [];
  if (value?.schema_version !== 1) errors.push("schema_version must be 1");
  if (!HOSTS.has(value?.host_os)) errors.push("host_os must be freebsd, macos, or linux");
  for (const key of ["profile_id", "artifact_format", "service_manager", "isolation", "network_policy", "filesystem", "storage_encryption", "resource_control", "status_reason"]) {
    if (!text(value?.[key])) errors.push(`${key} is required`);
  }
  for (const key of ["supported_versions", "architectures", "forbidden_runtime_dependencies"]) if (!uniqueText(value?.[key])) errors.push(`${key} must be a non-empty unique string array`);
  if (!Array.isArray(value?.required_service_boundaries) || BOUNDARIES.some((name) => !value.required_service_boundaries.includes(name))) errors.push("required_service_boundaries is incomplete");
  if (value?.status !== "BLOCKED") errors.push("repository host definitions must remain BLOCKED until external acceptance is supplied");
  if (value?.host_os === "freebsd") {
    if (value.service_manager !== "rc.d" || value.isolation !== "jail-vnet" || value.filesystem !== "zfs" || value.resource_control !== "rctl") errors.push("FreeBSD must use rc.d, Jail/VNET, ZFS, and rctl");
    if (!value.forbidden_runtime_dependencies?.includes("docker")) errors.push("FreeBSD must explicitly forbid Docker runtime dependency");
    for (const key of ["manifest", "jail_config", "firewall_config", "resource_config", "service_script", "install_script", "backup_script", "restore_script", "os_dispatch"])
      if (!text(value?.implementation?.[key])) errors.push(`FreeBSD implementation.${key} is required`);
  }
  if (value?.host_os === "macos" && (value.service_manager !== "launchd" || !value.forbidden_runtime_dependencies?.includes("docker-desktop"))) errors.push("macOS Production must use launchd and forbid Docker Desktop dependency");
  if (value?.host_os === "linux" && (value.service_manager !== "systemd" || !value.network_policy?.startsWith("nftables"))) errors.push("Linux Production must use systemd and default-deny nftables");
  return errors;
}

export async function validateFreeBsdImplementation(definition) {
  if (definition?.host_os !== "freebsd") return [];
  const errors = [];
  const files = {};
  for (const [key, file] of Object.entries(definition.implementation || {})) {
    try { files[key] = await readFile(file, "utf8"); }
    catch { errors.push(`FreeBSD implementation file is missing: ${key}=${file}`); }
  }
  if (errors.length) return errors;
  let manifest;
  try { manifest = JSON.parse(files.manifest); } catch { errors.push("FreeBSD manifest must be valid JSON"); return errors; }
  if (manifest.host_os !== "freebsd") errors.push("FreeBSD manifest host_os must be freebsd");
  for (const boundary of BOUNDARIES) if (!manifest.services?.some((item) => item.name === boundary)) errors.push(`FreeBSD manifest service is missing: ${boundary}`);
  const required = [
    ["jail_config", ["vnet.interface", "allow.mount = 0", "isas_db", "isas_idp", "isas_objq", "isas_app", "isas_edge", "isas_otel"]],
    ["firewall_config", ["block in log quick", "rdr pass on $ext_if", "port 443", "<isas_edge>", "<isas_app>", "<isas_database>"]],
    ["resource_config", ["jail:isas_db:memoryuse", "jail:isas_app:memoryuse", "jail:isas_otel:memoryuse"]],
    ["service_script", ["# PROVIDE: isas", "service jail start", "service jail stop"]],
    ["install_script", ["FreeBSD host required", "zfs create", "bsdinstall jail", "openssl dgst", "pkg -c", "jail-net.sh"]],
    ["backup_script", ["pg_basebackup", "zfs send", "sha256"]],
    ["restore_script", ["sha256 -c", "zfs receive"]],
    ["os_dispatch", ["case \"$host_os\"", "FreeBSD)", "infra/hosts/freebsd/bin/install.sh", "Darwin)", "Linux)"]],
  ];
  for (const [key, tokens] of required) for (const token of tokens) if (!files[key].includes(token)) errors.push(`FreeBSD ${key} must contain ${token}`);
  if (/docker|compose/i.test(files.install_script + files.jail_config + files.service_script)) errors.push("FreeBSD runtime implementation must not invoke Docker or Compose");
  return errors;
}

export function validateAcceptance(definition, value) {
  const errors = [];
  if (value?.schema_version !== 1 || value?.profile_id !== definition.profile_id || value?.host_os !== definition.host_os) errors.push("acceptance identity must match the host definition");
  if (!text(value?.source_commit) || !/^[0-9a-f]{40}$/.test(value.source_commit)) errors.push("source_commit must be 40 lowercase hex characters");
  if (!uniqueText(value?.failure_domains) || value.failure_domains.length < 2) errors.push("at least two named failure domains are required");
  for (const gate of GATES) {
    if (value?.gates?.[gate]?.status !== "PASS") errors.push(`gates.${gate}.status must be PASS`);
    if (!EVIDENCE.test(value?.gates?.[gate]?.evidence ?? "")) errors.push(`gates.${gate}.evidence must be an evidence URI`);
  }
  if (!Array.isArray(value?.approvals) || new Set(value.approvals.map((item) => item?.actor).filter(text)).size < 2) errors.push("two distinct approvers are required");
  return errors;
}

async function main(argv = process.argv.slice(2)) {
  if (argv.length < 1 || argv.length > 2) return 2;
  const definition = JSON.parse(await readFile(argv[0], "utf8"));
  const errors = validateDefinition(definition);
  errors.push(...await validateFreeBsdImplementation(definition));
  if (argv[1]) errors.push(...validateAcceptance(definition, JSON.parse(await readFile(argv[1], "utf8"))));
  if (errors.length) { console.error(`host profile: BLOCKED (${errors.length})`); errors.forEach((error) => console.error(`- ${error}`)); return 1; }
  console.log(`host profile definition: PASS ${definition.profile_id}`); return 0;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main();
