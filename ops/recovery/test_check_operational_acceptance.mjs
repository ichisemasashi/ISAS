import test from "node:test";
import assert from "node:assert/strict";
import { validateOperationalAcceptance } from "./check-operational-acceptance.mjs";

const verification = Object.fromEntries(["schema", "rls_force_owner", "triggers_security_invoker", "audit_chain", "object_hashes", "queue_cursor", "idempotency", "revocation", "tenant_crossing", "synthetic_transaction"].map((name) => [name, "PASS"]));
const evidence = () => ({
  schema_version: 1, status: "PASS", deployment_id: "isas-jp-stg-01", environment: "staging",
  source_commit: "a".repeat(40), completed_at: "2026-08-15T00:00:00Z",
  real_data_and_ut: { migration: { status: "PASS", evidence: "artifact://migration/pass" }, ut: { status: "PASS", evidence: "artifact://ut/pass" } },
  recovery_set: { status: "PASS", id: "rs-20260815-abc", components: ["database", "session_context", "private_objects", "quarantine_archive", "shard_config", "offline_maps", "queues", "audit", "configuration", "kms"], pitr_lag_seconds: 120,
    backup_jobs: Array.from({ length: 6 }, (_, i) => ({ status: "COMPLETED", recovery_point_arn: `arn:aws:backup:::${i}` })), evidence: "s3://evidence/recovery.json" },
  monthly_restore: { status: "PASS", executed_at: "2026-08-14T00:00:00Z", isolated_account: true, production_network_attached: false, egress_mode: "sink_only", data_loss_seconds: 300, recovery_seconds: 3600, verification, evidence: "artifact://restore/monthly" },
  quarterly_dr: { status: "PASS", executed_at: "2026-08-01T00:00:00Z", unannounced_generation_selection: true, isolated_account: true, production_network_attached: false, egress_mode: "sink_only", data_loss_seconds: 600, recovery_seconds: 7200, verification, evidence: "artifact://restore/dr" },
  operations: Object.fromEntries(["cold_start", "graceful_stop", "rolling_restart", "dependency_failure", "incident_response"].map((name) => [name, { status: "PASS", actual_staging: true, evidence: `artifact://operations/${name}` }])),
  operations_inventory: { placeholder_matches: 0, placeholder_scan: { status: "PASS", evidence: "artifact://operations/scan" },
    contacts: Object.fromEntries(["service_owner", "on_call", "security", "privacy", "legal"].map((name) => [name, { group: `ISAS-JP-${name}`, route_evidence: `artifact://contacts/${name}` }])),
    monitoring: { status: "PASS", evidence: "artifact://operations/monitoring" }, ledger: { status: "PASS", evidence: "artifact://operations/ledger" } },
  approvals: [
    { role: "service_owner", actor: "owner", approved_at: "2026-08-15T01:00:00Z", evidence: "artifact://approval/owner" },
    { role: "restore_verifier", actor: "verifier", approved_at: "2026-08-15T02:00:00Z", evidence: "artifact://approval/restore" },
    { role: "security_oncall", actor: "security", approved_at: "2026-08-15T03:00:00Z", evidence: "artifact://approval/security" },
  ],
});

test("accepts complete real staging operational evidence", () => assert.deepEqual(validateOperationalAcceptance(evidence(), new Date("2026-08-16T00:00:00Z")), []));
test("rejects examples and absent real migration or UT", () => {
  const value = evidence(); value.status = "NOT_RUN"; value.real_data_and_ut.ut = { status: "BLOCKED", evidence: null };
  assert.match(validateOperationalAcceptance(value, new Date("2026-08-16T00:00:00Z")).join("\n"), /status must be PASS|real participant UT/);
});
test("rejects missed RPO RTO, connected restore, and placeholder contacts", () => {
  const value = evidence(); value.monthly_restore.data_loss_seconds = 901; value.quarterly_dr.recovery_seconds = 14401;
  value.monthly_restore.production_network_attached = true; value.operations_inventory.contacts.on_call.group = "未設定";
  const errors = validateOperationalAcceptance(value, new Date("2026-08-16T00:00:00Z")).join("\n");
  assert.match(errors, /RPO/); assert.match(errors, /RTO/); assert.match(errors, /isolated/); assert.match(errors, /on_call/);
});
