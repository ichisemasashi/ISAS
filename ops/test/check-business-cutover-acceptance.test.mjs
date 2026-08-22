import assert from "node:assert/strict";
import test from "node:test";

import { validateBusinessCutoverAcceptance, validateUserAcceptance } from "../check-business-cutover-acceptance.mjs";

test("accepts only measured real-user thresholds", () => {
  const sessions = Array.from({ length: 6 }, (_, index) => ({ real_participant: true, evidence_uri: `artifact://ut/${index}` }));
  const evidence = {
    schema_version: 1, status: "PASS", evidence_class: "real_participants",
    participant_groups: { workers: 2, senior_workers: 2, technical_interns: 2 },
    metrics: { task_success_percent: 90, journal_median_seconds: 30, pesticide_median_seconds: 60, sus: 75, offline_understanding_percent: 100 },
    sessions,
  };
  assert.deepEqual(validateUserAcceptance(evidence), []);
  evidence.participant_groups.technical_interns = 1;
  assert.ok(validateUserAcceptance(evidence).some((error) => error.includes("technical_interns")));
});

test("blocks a cutover when evidence is not bound to the release commit and selected host", () => {
  const source = "1".repeat(40);
  const documents = Object.fromEntries(["migration", "user_acceptance", "device", "quality", "operations", "staging_bake"].map((name) => [name, { source_commit: "2".repeat(40), deployment_id: "wrong", host_os: "linux" }]));
  const errors = validateBusinessCutoverAcceptance({
    index: { schema_version: 1, status: "PASS", source_commit: source, deployment_id: "prod-a", host_os: "freebsd", environment: "isolated-staging", documents: {} },
    release: { release: { source_commit: source }, deployment: { deployment_id: "prod-a", host_os: "freebsd" } },
    documents,
    documentBytes: {},
  });
  assert.ok(errors.some((error) => error.includes("migration.source_commit")));
  assert.ok(errors.some((error) => error.includes("migration.host_os")));
  assert.ok(errors.some((error) => error.includes("documents.migration.digest")));
});
