import test from "node:test";
import assert from "node:assert/strict";
import { validateRehearsal } from "./check-rehearsal.mjs";

const now = new Date("2026-08-16T00:00:00Z");
const valid = () => ({
  schema_version: 1, status: "PASS", evidence_class: "real_anonymized", round_id: "migration-2026-08",
  measured_at: "2026-08-15T00:00:00Z", source_commit: "a".repeat(40), deployment_id: "staging-42",
  environment: { kind: "staging", base_origin: "https://staging.isas.example", tenant_id: "tenant" },
  imports: ["fields", "journals", "pesticide_history"].map((dataset) => ({ dataset, status: "pass", idempotent_replay: "pass",
    source_sha256: "b".repeat(64), source_rows: 3, validated: { rows: 3, valid: 2, duplicates: 1, errors: 0 },
    committed: 2, duplicates_at_commit: 0 })),
  exports: { fields: 2, journals: 2, "pesticide-records": 0 },
  rls_scope: { status: "pass", restricted_exports: { fields: 1, journals: 1, "pesticide-records": 0 } },
  approvals: [
    { role: "data_owner", actor: "owner", approved_at: "2026-08-15T01:00:00Z", evidence: "artifact://review/1" },
    { role: "independent_verifier", actor: "verifier", approved_at: "2026-08-15T02:00:00Z", evidence: "artifact://review/2" },
  ],
});

test("accepts reconciled real rehearsal", () => assert.deepEqual(validateRehearsal(valid(), now), []));
test("rejects runner PARTIAL without approvals", () => {
  const value = valid(); value.status = "PARTIAL"; value.approvals = [];
  assert.match(validateRehearsal(value, now).join("\n"), /status must be PASS|missing approval roles/);
});
test("rejects synthetic, count drift, and ineffective RLS proof", () => {
  const value = valid(); value.evidence_class = "synthetic"; value.imports[0].committed = 1; value.rls_scope.restricted_exports = { ...value.exports };
  const errors = validateRehearsal(value, now).join("\n");
  assert.match(errors, /real-derived/); assert.match(errors, /committed count/); assert.match(errors, /narrower result/);
});
