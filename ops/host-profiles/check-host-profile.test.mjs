import assert from "node:assert/strict";
import test from "node:test";
import { validateAcceptance, validateDefinition } from "./check-host-profile.mjs";

const base = { schema_version: 1, profile_id: "freebsd-production", host_os: "freebsd", supported_versions: ["15.1-RELEASE"], architectures: ["amd64"], artifact_format: "pkg", service_manager: "rc.d", isolation: "jail-vnet", network_policy: "pf-default-deny", filesystem: "zfs", storage_encryption: "geli", resource_control: "rctl", forbidden_runtime_dependencies: ["docker"], required_service_boundaries: ["edge", "app", "database", "identity", "object-queue", "telemetry"], status: "BLOCKED", status_reason: "external acceptance pending" };

test("accepts a FreeBSD Jail definition without Docker", () => assert.deepEqual(validateDefinition(base), []));
test("rejects Docker as a FreeBSD runtime premise", () => assert.ok(validateDefinition({ ...base, forbidden_runtime_dependencies: ["linux-guest"] }).some((error) => error.includes("Docker"))));
test("requires every external acceptance gate", () => {
  const evidence = { schema_version: 1, profile_id: base.profile_id, host_os: base.host_os, source_commit: "a".repeat(40), failure_domains: ["a", "b"], gates: {}, approvals: [{ actor: "one" }, { actor: "two" }] };
  assert.ok(validateAcceptance(base, evidence).some((error) => error.includes("gates.install")));
});
