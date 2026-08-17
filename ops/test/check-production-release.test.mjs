import { createHash } from "node:crypto";
import assert from "node:assert/strict";
import test from "node:test";

import { validateProductionRelease } from "../check-production-release.mjs";

function evidence() {
  const source = "1".repeat(40);
  const digest = `sha256:${"a".repeat(64)}`;
  const gate = () => ({ status: "pass", evidence: "artifact://gate/pass", source_commit: source, collected_at: "2026-08-15T00:30:00Z" });
  const release = {
    schema_version: 2,
    release: { version: "1.1.0", source_commit: source, created_at: "2026-08-15T00:00:00Z", status: "READY" },
    deployment: {
      deployment_id: "jp-production", host_os: "macos", os_version: "15.6", architecture: "arm64",
      service_manager: "launchd", isolation: "native-services", filesystem: "apfs", storage_encryption: "filevault",
      provider: "self-hosted", region_or_site: "jp-site-a", failure_domains: ["mac-a", "mac-b"],
      jurisdiction: "JP", shard_manifest_version: "42", shard_manifest_digest: `sha256:${"b".repeat(64)}`,
    },
    artifacts: [{ name: "bff", digest, signature_verified: true, provenance_verified: true, sbom: "artifact://sbom/bff" }],
    gates: Object.fromEntries(["unit_contract", "postgres_rls", "e2e_pwa", "accessibility", "security", "supply_chain", "performance_slo", "device_encryption", "staging_acceptance", "data_migration", "user_acceptance", "operational_acceptance"].map((name) => [name, gate()])),
    quality: { no_data_count: 0, unresolved_high: 0, unresolved_medium: 0, error_budget_remaining_percent: 80, active_sev1: 0, active_sev2: 0 },
    dr: { status: "pass", tested_at: "2026-08-01T00:00:00Z", rpo_minutes: 5, rto_minutes: 90, recovery_set_id: "rs-jp-20260801", evidence: "artifact://dr/pass" },
    approvals: [
      { actor: "manager", role: "release_manager", approved_at: "2026-08-15T01:00:00Z", evidence: "artifact://approval/manager" },
      { actor: "verifier", role: "independent_verifier", approved_at: "2026-08-15T01:01:00Z", evidence: "artifact://approval/verifier" },
    ],
  };
  const releaseBytes = Buffer.from(`${JSON.stringify(release)}\n`);
  const build = { version: "1.1.0", source_commit: source, artifact_set_digest: digest, artifacts: [{ name: "bff", digest }] };
  const history = [
    { stage: "prepared", entered_at: "2026-08-14T23:00:00Z" },
    { stage: "5", entered_at: "2026-08-15T00:00:00Z" },
    { stage: "25", entered_at: "2026-08-15T01:00:00Z" },
    { stage: "100", entered_at: "2026-08-15T03:00:00Z" },
    { stage: "finalized", entered_at: "2026-08-16T04:30:00Z" },
  ];
  const observations = [
    { stage: "5", duration_seconds: 1800, eligible_transactions: 1000 },
    { stage: "25", duration_seconds: 7200, eligible_transactions: 1000 },
    { stage: "100", duration_seconds: 1800, eligible_transactions: 1000 },
  ].map((item) => ({ ...item, status: "PASS", started_at: "2026-08-15T00:00:00Z", completed_at: "2026-08-15T02:00:00Z" }));
  const delivery = { stage: "finalized", source_commit: source, artifact_set_digest: digest, history, observations };
  const bake = {
    schema_version: 1, status: "PASS", environment: "production", source_commit: source, artifact_set_digest: digest,
    release_manifest_digest: `sha256:${createHash("sha256").update(releaseBytes).digest("hex")}`,
    started_at: "2026-08-15T04:00:00Z", completed_at: "2026-08-16T04:00:00Z",
    alarm_breaches: 0, no_data_count: 0, active_sev1: 0, active_sev2: 0, unresolved_high: 0, unresolved_medium: 0,
    error_budget_remaining_percent: 75, evidence: "s3://evidence/production-bake.json",
    approvals: [
      { actor: "manager", role: "release_manager", approved_at: "2026-08-16T04:10:00Z", evidence: "artifact://approval/final-manager" },
      { actor: "verifier", role: "independent_verifier", approved_at: "2026-08-16T04:11:00Z", evidence: "artifact://approval/final-verifier" },
    ],
    tag: { name: "v1.1.0", target_commit: source },
  };
  return { release, build, delivery, bake, releaseBytes };
}

test("authorizes a tag only after ordered rollout and a 24-hour clean bake", () => {
  assert.deepEqual(validateProductionRelease({ ...evidence(), now: new Date("2026-08-16T05:00:00Z") }), []);
});

test("permits the same evidence before finalization without authorizing finalized state", () => {
  const value = evidence();
  value.delivery.stage = "100";
  value.delivery.history = value.delivery.history.filter(({ stage }) => stage !== "finalized");
  assert.deepEqual(validateProductionRelease({ ...value, now: new Date("2026-08-16T05:00:00Z"), preFinalize: true }), []);
  assert.ok(validateProductionRelease({ ...value, now: new Date("2026-08-16T05:00:00Z") }).some((error) => error.includes("must be finalized")));
});

test("blocks a skipped stage, short bake, and duplicate final approvers", () => {
  const value = evidence();
  value.delivery.history = value.delivery.history.filter(({ stage }) => stage !== "25");
  value.bake.completed_at = "2026-08-15T05:00:00Z";
  value.bake.approvals[1].actor = value.bake.approvals[0].actor;
  const errors = validateProductionRelease({ ...value, now: new Date("2026-08-16T05:00:00Z") });
  assert.ok(errors.some((error) => error.includes("ordered 25")));
  assert.ok(errors.some((error) => error.includes("at least 24 hours")));
  assert.ok(errors.some((error) => error.includes("two distinct")));
});

test("blocks release manifest substitution", () => {
  const value = evidence();
  value.releaseBytes = Buffer.from("different manifest");
  const errors = validateProductionRelease({ ...value, now: new Date("2026-08-16T05:00:00Z") });
  assert.ok(errors.some((error) => error.includes("release_manifest_digest")));
});

test("blocks non-monotonic delivery timestamps", () => {
  const value = evidence();
  value.delivery.history[2].entered_at = "2026-08-14T22:00:00Z";
  const errors = validateProductionRelease({ ...value, now: new Date("2026-08-16T05:00:00Z") });
  assert.ok(errors.some((error) => error.includes("timestamps must be monotonic")));
});
