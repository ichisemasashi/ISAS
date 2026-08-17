import assert from "node:assert/strict";
import test from "node:test";

import { assembleReleaseManifest } from "../assemble-release-manifest.mjs";

function readyManifest() {
  const sourceCommit = "1".repeat(40);
  const gate = () => ({ status: "pass", evidence: "artifact://evidence/123", source_commit: sourceCommit, collected_at: "2026-08-15T00:30:00Z" });
  return {
    schema_version: 2,
    release: { version: "1.0.1", source_commit: sourceCommit, created_at: "2026-08-15T00:00:00Z", status: "BLOCKED" },
    deployment: {
      deployment_id: "jp-production", host_os: "linux", os_version: "debian-13", architecture: "amd64",
      service_manager: "systemd", isolation: "oci", filesystem: "ext4", storage_encryption: "luks2",
      provider: "self-hosted", region_or_site: "jp-site-a", failure_domains: ["host-a", "host-b"],
      jurisdiction: "JP", shard_manifest_version: "42", shard_manifest_digest: `sha256:${"a".repeat(64)}`,
    },
    artifacts: [{ name: "bff", digest: `sha256:${"b".repeat(64)}`, signature_verified: true, provenance_verified: true, sbom: "artifact://sbom/bff.spdx.json" }],
    operations: { deployment_id: "jp-production", ledger_digest: `sha256:${"c".repeat(64)}`, ledger_evidence: "artifact://operations/jp-production.json" },
    gates: {
      unit_contract: gate(), postgres_rls: gate(), e2e_pwa: gate(), accessibility: gate(), security: gate(),
      supply_chain: gate(), performance_slo: gate(), device_encryption: gate(), staging_acceptance: gate(),
      data_migration: gate(), user_acceptance: gate(), operational_acceptance: gate(),
    },
    quality: { no_data_count: 0, unresolved_high: 0, unresolved_medium: 0, error_budget_remaining_percent: 80, active_sev1: 0, active_sev2: 0 },
    dr: { status: "pass", tested_at: "2026-08-01T00:00:00Z", rpo_minutes: 5, rto_minutes: 90, recovery_set_id: "rs-jp-20260801", evidence: "artifact://dr/20260801" },
    approvals: [
      { actor: "release-commander", role: "release_manager", approved_at: "2026-08-15T01:00:00Z", evidence: "artifact://approval/rc" },
      { actor: "independent-verifier", role: "independent_verifier", approved_at: "2026-08-15T01:01:00Z", evidence: "artifact://approval/verifier" },
    ],
  };
}

test("assembles READY only from a complete candidate", () => {
  const candidate = readyManifest();
  candidate.release.status = "BLOCKED";
  const result = assembleReleaseManifest(candidate, new Date("2026-08-15T02:00:00Z"));
  assert.deepEqual(result.errors, []);
  assert.equal(result.manifest.release.status, "READY");
});

test("does not emit a manifest when evidence is missing", () => {
  const candidate = readyManifest();
  delete candidate.gates.operational_acceptance.evidence;
  const result = assembleReleaseManifest(candidate, new Date("2026-08-15T02:00:00Z"));
  assert.equal(result.manifest, null);
  assert.ok(result.errors.some((error) => error.includes("operational_acceptance.evidence")));
});
