import assert from "node:assert/strict";
import test from "node:test";

import { validateReleaseManifest } from "../check-release-readiness.mjs";

function readyManifest() {
  const gate = () => ({
    status: "pass", evidence: "artifact://evidence/123",
    source_commit: "1".repeat(40), collected_at: "2026-08-15T00:30:00Z",
  });
  return {
    schema_version: 1,
    release: {
      version: "1.0.0",
      source_commit: "1".repeat(40),
      created_at: "2026-08-15T00:00:00Z",
      status: "READY",
    },
    deployment: {
      deployment_id: "jp-production",
      jurisdiction: "JP",
      shard_manifest_version: "42",
      shard_manifest_digest: `sha256:${"a".repeat(64)}`,
    },
    artifacts: [{
      name: "bff",
      digest: `sha256:${"b".repeat(64)}`,
      signature_verified: true,
      provenance_verified: true,
      sbom: "artifact://sbom/bff.spdx.json",
    }],
    gates: {
      unit_contract: gate(), postgres_rls: gate(), e2e_pwa: gate(), accessibility: gate(),
      security: gate(), supply_chain: gate(), performance_slo: gate(),
      device_encryption: gate(), staging_acceptance: gate(), data_migration: gate(),
      user_acceptance: gate(), operational_acceptance: gate(),
    },
    quality: {
      no_data_count: 0, unresolved_high: 0, unresolved_medium: 0,
      error_budget_remaining_percent: 80, active_sev1: 0, active_sev2: 0,
    },
    dr: {
      status: "pass", tested_at: "2026-08-01T00:00:00Z", rpo_minutes: 5, rto_minutes: 90,
      recovery_set_id: "rs-jp-20260801", evidence: "artifact://dr/20260801",
    },
    approvals: [
      { actor: "release-commander", role: "release_manager", approved_at: "2026-08-15T01:00:00Z", evidence: "artifact://approval/rc" },
      { actor: "independent-verifier", role: "independent_verifier", approved_at: "2026-08-15T01:01:00Z", evidence: "artifact://approval/verifier" },
    ],
  };
}

test("accepts a complete release manifest", () => {
  assert.deepEqual(validateReleaseManifest(readyManifest(), new Date("2026-08-15T02:00:00Z")), []);
});

test("rejects gate evidence collected for a different commit", () => {
  const manifest = readyManifest();
  manifest.gates.security.source_commit = "2".repeat(40);
  const errors = validateReleaseManifest(manifest, new Date("2026-08-15T02:00:00Z"));
  assert.ok(errors.some((error) => error.includes("security.source_commit")));
});

test("blocks a release with missing gates and stale DR", () => {
  const manifest = readyManifest();
  manifest.gates.postgres_rls.status = "blocked";
  manifest.quality.no_data_count = 1;
  manifest.quality.error_budget_remaining_percent = 24.9;
  manifest.dr.tested_at = "2026-01-01T00:00:00Z";
  const errors = validateReleaseManifest(manifest, new Date("2026-08-15T02:00:00Z"));
  assert.ok(errors.some((error) => error.includes("postgres_rls")));
  assert.ok(errors.some((error) => error.includes("no_data_count")));
  assert.ok(errors.some((error) => error.includes("error_budget")));
  assert.ok(errors.some((error) => error.includes("last 93 days")));
});

test("rejects duplicate approvers and unverifiable artifacts", () => {
  const manifest = readyManifest();
  manifest.artifacts[0].signature_verified = false;
  manifest.approvals[1].actor = manifest.approvals[0].actor;
  const errors = validateReleaseManifest(manifest, new Date("2026-08-15T02:00:00Z"));
  assert.ok(errors.some((error) => error.includes("signature_verified")));
  assert.ok(errors.some((error) => error.includes("distinct actors")));
});
