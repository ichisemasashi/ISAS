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
  if (value?.host_os === "macos") {
    if (value.service_manager !== "launchd" || !value.forbidden_runtime_dependencies?.includes("docker-desktop")) errors.push("macOS Production must use launchd and forbid Docker Desktop dependency");
    for (const key of ["manifest", "firewall_config", "install_script", "preflight_script", "backup_script", "restore_script", "update_script", "monitor_script", "os_dispatch"])
      if (!text(value?.implementation?.[key])) errors.push(`macOS implementation.${key} is required`);
    if (!uniqueText(value?.implementation?.launchd_services) || value.implementation.launchd_services.length < BOUNDARIES.length) errors.push("macOS implementation.launchd_services must contain every service boundary");
  }
  if (value?.host_os === "linux") {
    if (value.service_manager !== "systemd" || !value.network_policy?.startsWith("nftables")) errors.push("Linux Production must use systemd and default-deny nftables");
    for (const key of ["manifest", "firewall_config", "apparmor_config", "sysusers_config", "tmpfiles_config", "journald_config", "hardening_config", "update_config", "bootstrap_script", "install_script", "preflight_script", "backup_script", "restore_script", "update_script", "rollback_script", "monitor_script", "os_dispatch"])
      if (!text(value?.implementation?.[key])) errors.push(`Linux implementation.${key} is required`);
    if (!uniqueText(value?.implementation?.systemd_units) || value.implementation.systemd_units.length < BOUNDARIES.length + 1) errors.push("Linux implementation.systemd_units must contain target and every service boundary");
  }
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

export async function validateMacOsImplementation(definition) {
  if (definition?.host_os !== "macos") return [];
  const errors = [];
  const files = {};
  const implementation = definition.implementation || {};
  for (const key of ["manifest", "firewall_config", "install_script", "preflight_script", "backup_script", "restore_script", "update_script", "monitor_script", "os_dispatch"]) {
    try { files[key] = await readFile(implementation[key], "utf8"); }
    catch { errors.push(`macOS implementation file is missing: ${key}=${implementation[key]}`); }
  }
  const plists = [];
  for (const file of implementation.launchd_services || []) {
    try { plists.push({ file, body: await readFile(file, "utf8") }); }
    catch { errors.push(`macOS launchd file is missing: ${file}`); }
  }
  if (errors.length) return errors;
  let manifest;
  try { manifest = JSON.parse(files.manifest); } catch { errors.push("macOS manifest must be valid JSON"); return errors; }
  if (manifest.host_os !== "macos" || manifest.profile_id !== "macos-production") errors.push("macOS manifest identity must be macos-production");
  if (!manifest.root?.includes("/ISAS/Production") || /local-integration/i.test(manifest.root)) errors.push("macOS manifest must use a Production-only root");
  if (manifest.slo?.p0_availability !== 0.999 || manifest.slo?.p0_latency_ms !== 500) errors.push("macOS manifest must declare the common P0 SLO");
  for (const boundary of BOUNDARIES) {
    const service = manifest.services?.find((item) => item.name === boundary);
    if (!service) { errors.push(`macOS manifest service is missing: ${boundary}`); continue; }
    if (!service.user?.startsWith("_isas_") || service.user === "root") errors.push(`macOS service must use a non-root identity: ${boundary}`);
    const plist = plists.find((item) => item.body.includes(`<string>${service.label}</string>`));
    if (!plist) { errors.push(`macOS launchd service is missing: ${service.label}`); continue; }
    for (const token of ["<key>UserName</key>", `<string>${service.user}</string>`, "<key>ProgramArguments</key>", "<key>RunAtLoad</key><true/>", "<key>KeepAlive</key>", "<key>HardResourceLimits</key>", "<key>StandardOutPath</key>", "/ISAS/Production/"])
      if (!plist.body.includes(token)) errors.push(`macOS ${service.label} plist must contain ${token}`);
  }
  const required = [
    ["firewall_config", ["ext_if", "block in log quick", "port 443", "port 8444", "127.0.0.1"]],
    ["preflight_script", ["macOS host required", "ISAS_SUPPORTED_MACOS_MAJORS", "FileVault must be enabled", "pmset -g custom", "sleep", "local-integration data"]],
    ["install_script", ["macOS host required", "dscl", "pkgutil --check-signature", "installer -pkg", "/Library/Application Support/ISAS/Production", "launchctl bootstrap", "pfctl"]],
    ["backup_script", ["pg_basebackup", "WAL archive", "object inventory", "audit anchor", "key reference", "shasum -a 256"]],
    ["restore_script", ["shasum -a 256 -c", "launchctl bootout", "identity/bin/import", "object-queue/bin/import", "ISAS_RESTORE_WAL_DIR"]],
    ["update_script", ["peer failure-domain readiness", "/operations/drain", "isas-production-backup", "softwareupdate", "launchctl bootstrap", "/health/ready"]],
    ["monitor_script", ["/health/live", "/health/ready", "0.500", "p0_availability_target=99.9"]],
    ["os_dispatch", ["case \"$host_os\"", "Darwin)", "infra/hosts/macos/bin/install.sh", "FreeBSD)", "Linux)"]],
  ];
  for (const [key, tokens] of required) for (const token of tokens) if (!files[key].includes(token)) errors.push(`macOS ${key} must contain ${token}`);
  const runtime = files.install_script + files.preflight_script + plists.map((item) => item.body).join("");
  if (/docker\s+(?:compose|run|start)|docker\.app|\.docker\//i.test(runtime)) errors.push("macOS Production runtime must not invoke Docker Desktop");
  return errors;
}

export async function validateLinuxImplementation(definition) {
  if (definition?.host_os !== "linux") return [];
  const errors = [];
  const files = {};
  const implementation = definition.implementation || {};
  const keys = ["manifest", "firewall_config", "apparmor_config", "sysusers_config", "tmpfiles_config", "journald_config", "hardening_config", "update_config", "bootstrap_script", "install_script", "preflight_script", "backup_script", "restore_script", "update_script", "rollback_script", "monitor_script", "os_dispatch"];
  for (const key of keys) {
    try { files[key] = await readFile(implementation[key], "utf8"); }
    catch { errors.push(`Linux implementation file is missing: ${key}=${implementation[key]}`); }
  }
  const units = [];
  for (const file of implementation.systemd_units || []) {
    try { units.push({ file, body: await readFile(file, "utf8") }); }
    catch { errors.push(`Linux systemd unit is missing: ${file}`); }
  }
  if (errors.length) return errors;
  let manifest;
  try { manifest = JSON.parse(files.manifest); } catch { errors.push("Linux manifest must be valid JSON"); return errors; }
  if (manifest.host_os !== "linux" || manifest.profile_id !== "linux-production") errors.push("Linux manifest identity must be linux-production");
  for (const [id, version] of [["debian", "13"], ["ubuntu", "24.04"]]) if (!manifest.distributions?.some((item) => item.id === id && item.version_id === version && item.security_profile === "apparmor")) errors.push(`Linux support matrix is missing: ${id} ${version}`);
  if (!manifest.architectures?.includes("x86_64") || !manifest.architectures?.includes("aarch64")) errors.push("Linux manifest must support x86_64 and aarch64");
  if (manifest.minimum_resources?.cpu_cores !== 8 || manifest.minimum_resources?.memory_gib !== 32 || manifest.minimum_resources?.data_disk_gib !== 1024) errors.push("Linux minimum resource contract is incomplete");
  if (manifest.container_runtime !== "none" || manifest.secret_store !== "systemd-creds-encrypted") errors.push("Linux native profile must avoid container runtime dependency and use encrypted systemd credentials");
  if (manifest.slo?.p0_availability !== 0.999 || manifest.slo?.p0_latency_ms !== 500) errors.push("Linux manifest must declare the common P0 SLO");
  for (const boundary of BOUNDARIES) {
    const service = manifest.services?.find((item) => item.name === boundary);
    if (!service) { errors.push(`Linux manifest service is missing: ${boundary}`); continue; }
    if (!service.user?.startsWith("isas-") || service.user === "root") errors.push(`Linux service must use a non-root identity: ${boundary}`);
    const unit = units.find((item) => item.file.endsWith(`/${service.unit}`));
    if (!unit) { errors.push(`Linux systemd service is missing: ${service.unit}`); continue; }
    for (const token of [`User=${service.user}`, "PartOf=isas.target", "RequiresMountsFor=/var/lib/isas", "ConditionPathIsMountPoint=/var/lib/isas", "LoadCredentialEncrypted=", "NoNewPrivileges=yes", "PrivateTmp=yes", "ProtectSystem=strict", "ProtectHome=yes", "CapabilityBoundingSet=", "ReadWritePaths=", "Restart=on-failure", "LogNamespace=isas"])
      if (!unit.body.includes(token)) errors.push(`Linux ${service.unit} must contain ${token}`);
  }
  const required = [
    ["firewall_config", ["policy drop", "ct original proto-dst 443", "redirect to :8444", "dport 22", "dport { 3000"]],
    ["apparmor_config", ["database/bin/start", "identity/bin/start", "object-queue/bin/start", "app/bin/start", "edge/bin/start", "telemetry/bin/start"]],
    ["sysusers_config", ["isas-db", "isas-idp", "isas-objq", "isas-app", "isas-edge", "isas-otel", "/usr/sbin/nologin"]],
    ["tmpfiles_config", ["/var/lib/isas/database", "/run/isas/app", "/etc/isas/credentials"]],
    ["journald_config", ["Storage=persistent", "SystemMaxUse=10G", "MaxRetentionSec=30day", "Seal=yes"]],
    ["hardening_config", ["kernel.kptr_restrict = 2", "kernel.unprivileged_bpf_disabled = 1", "fs.suid_dumpable = 0"]],
    ["update_config", ["Unattended-Upgrade::Automatic-Reboot \"false\""]],
    ["bootstrap_script", ["Linux host required", "debian:13", "ubuntu:24.04", "apt-get install", "ISAS_CONFIRM_LUKS_FORMAT", "cryptsetup luksFormat --type luks2"]],
    ["preflight_script", ["Linux host required", "debian:13", "ubuntu:24.04", "cgroup v2", "32 GiB", "1 TiB", "cryptsetup isLuks --type luks2", "ISAS_LUKS_MAPPER", "data mount is not backed", "aa-enabled", "NTPSynchronized", "ISAS_UPS_MODE", "ISAS_SUPPORT_MATRIX_EVIDENCE"]],
    ["install_script", ["Linux host required", "openssl dgst -sha256 -verify", "sbom.spdx.json", "provenance.json", "dpkg -i", "systemd-creds is-encrypted", "apparmor_parser -r", "nft --check", "systemd-analyze verify", "systemctl enable --now"]],
    ["backup_script", ["pg_basebackup", "WAL archive", "object inventory", "audit anchor", "key reference", "sha256sum"]],
    ["restore_script", ["sha256sum --check", "systemctl stop isas.target", "identity/bin/import", "object-queue/bin/import", "ISAS_RESTORE_WAL_DIR"]],
    ["update_script", ["peer failure-domain readiness", "/operations/drain", "isas-production-backup", "systemctl stop isas.target", "unattended-upgrade", "/health/ready"]],
    ["rollback_script", ["ISAS_ROLLBACK_VERSION", "systemctl stop isas.target", "ln -sfn", "systemctl start isas.target", "/health/ready"]],
    ["monitor_script", ["/health/live", "/health/ready", "0.500", "p0_availability_target=99.9"]],
    ["os_dispatch", ["case \"$host_os\"", "Linux)", "infra/hosts/linux/bin/install.sh", "FreeBSD)", "Darwin)"]],
  ];
  for (const [key, tokens] of required) for (const token of tokens) if (!files[key].includes(token)) errors.push(`Linux ${key} must contain ${token}`);
  const runtime = files.install_script + files.preflight_script + units.map((item) => item.body).join("");
  if (/docker\s+(?:compose|run|start)|docker\.sock|containerd\.sock/i.test(runtime)) errors.push("Linux native Production runtime must not invoke Docker or containerd");
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
  errors.push(...await validateMacOsImplementation(definition));
  errors.push(...await validateLinuxImplementation(definition));
  if (argv[1]) errors.push(...validateAcceptance(definition, JSON.parse(await readFile(argv[1], "utf8"))));
  if (errors.length) { console.error(`host profile: BLOCKED (${errors.length})`); errors.forEach((error) => console.error(`- ${error}`)); return 1; }
  console.log(`host profile definition: PASS ${definition.profile_id}`); return 0;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main();
