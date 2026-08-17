import assert from "node:assert/strict";
import test from "node:test";
import { validateAcceptance, validateDefinition } from "./check-host-profile.mjs";

const implementation = { manifest: "manifest", jail_config: "jail", firewall_config: "pf", resource_config: "rctl", service_script: "rc", install_script: "install", backup_script: "backup", restore_script: "restore", os_dispatch: "dispatch" };
const base = { schema_version: 1, profile_id: "freebsd-production", host_os: "freebsd", supported_versions: ["15.1-RELEASE"], architectures: ["amd64"], artifact_format: "pkg", service_manager: "rc.d", isolation: "jail-vnet", network_policy: "pf-default-deny", filesystem: "zfs", storage_encryption: "geli", resource_control: "rctl", forbidden_runtime_dependencies: ["docker"], required_service_boundaries: ["edge", "app", "database", "identity", "object-queue", "telemetry"], implementation, status: "BLOCKED", status_reason: "external acceptance pending" };
const macImplementation = { manifest: "manifest", firewall_config: "pf", launchd_services: ["db", "idp", "objq", "app", "edge", "otel"], install_script: "install", preflight_script: "preflight", backup_script: "backup", restore_script: "restore", update_script: "update", monitor_script: "monitor", os_dispatch: "dispatch" };
const mac = { ...base, profile_id: "macos-production", host_os: "macos", artifact_format: "signed-application-bundle", service_manager: "launchd", isolation: "native-service-identities", network_policy: "application-firewall-and-pf-default-deny", filesystem: "apfs", storage_encryption: "filevault", resource_control: "launchd-limits", forbidden_runtime_dependencies: ["docker-desktop", "interactive-user-session", "local-integration-volume"], implementation: macImplementation };
const linuxImplementation = { manifest: "manifest", firewall_config: "nft", apparmor_config: "apparmor", sysusers_config: "sysusers", tmpfiles_config: "tmpfiles", journald_config: "journald", hardening_config: "sysctl", update_config: "apt", systemd_units: ["target", "db", "idp", "objq", "app", "edge", "otel"], bootstrap_script: "bootstrap", install_script: "install", preflight_script: "preflight", backup_script: "backup", restore_script: "restore", update_script: "update", rollback_script: "rollback", monitor_script: "monitor", os_dispatch: "dispatch" };
const linux = { ...base, profile_id: "linux-production", host_os: "linux", artifact_format: "signed-native-package", service_manager: "systemd", isolation: "systemd-sandbox", network_policy: "nftables-default-deny", filesystem: "ext4", storage_encryption: "luks2", resource_control: "cgroup-v2", forbidden_runtime_dependencies: ["mutable-latest-tag", "unconfined-root-container", "interactive-user-session"], implementation: linuxImplementation };

test("accepts a FreeBSD Jail definition without Docker", () => assert.deepEqual(validateDefinition(base), []));
test("rejects Docker as a FreeBSD runtime premise", () => assert.ok(validateDefinition({ ...base, forbidden_runtime_dependencies: ["linux-guest"] }).some((error) => error.includes("Docker"))));
test("accepts a macOS native launchd definition without Docker Desktop", () => assert.deepEqual(validateDefinition(mac), []));
test("requires macOS lifecycle implementation", () => assert.ok(validateDefinition({ ...mac, implementation: { ...macImplementation, backup_script: "" } }).some((error) => error.includes("backup_script"))));
test("accepts a Linux native systemd definition", () => assert.deepEqual(validateDefinition(linux), []));
test("requires Linux rollback implementation", () => assert.ok(validateDefinition({ ...linux, implementation: { ...linuxImplementation, rollback_script: "" } }).some((error) => error.includes("rollback_script"))));
test("requires every external acceptance gate", () => {
  const evidence = { schema_version: 1, profile_id: base.profile_id, host_os: base.host_os, source_commit: "a".repeat(40), failure_domains: ["a", "b"], gates: {}, approvals: [{ actor: "one" }, { actor: "two" }] };
  assert.ok(validateAcceptance(base, evidence).some((error) => error.includes("gates.install")));
});
