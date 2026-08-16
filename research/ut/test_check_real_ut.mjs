import test from "node:test";
import assert from "node:assert/strict";
import { validateRealUt } from "./check-real-ut.mjs";

const names = ["対象者構成", "記録完全性", "主要タスク成功率", "日誌時間中央値", "農薬記録時間中央値", "農薬警告見落とし", "オフライン保存・未同期理解", "SUS平均", "未解決Severity 1"];
const result = { status: "PASS", participant_count: 6, cohort_counts: { worker: 2, older_worker: 2, technical_intern: 2 }, gates: names.map((name) => ({ name, passed: true })) };
const evidence = () => ({ schema_version: 1, status: "PASS", evidence_class: "real_participant", round_id: "ut-2026-08",
  environment: "staging_actual_device", source_commit: "a".repeat(40), completed_at: "2026-08-15T00:00:00Z",
  participant_count: 6, cohort_counts: { worker: 2, older_worker: 2, technical_intern: 2 },
  recruitment_evidence: { worker: "artifact://ut/worker", older_worker: "artifact://ut/older", technical_intern: "artifact://ut/intern" },
  consent_register: "artifact://ut/consent", observation_records: "artifact://ut/observations", device_matrix: "artifact://ut/devices", analysis_inputs_digest: "artifact://ut/digests",
  approvals: [
    { role: "ut_owner", actor: "owner", approved_at: "2026-08-15T01:00:00Z", evidence: "artifact://ut/review-1" },
    { role: "independent_verifier", actor: "verifier", approved_at: "2026-08-15T02:00:00Z", evidence: "artifact://ut/review-2" },
  ] });

test("accepts analyzer pass backed by real-participant evidence", () => assert.deepEqual(validateRealUt(result, evidence(), new Date("2026-08-16T00:00:00Z")), []));
test("rejects preflight metadata and missing independent evidence", () => {
  const value = evidence(); value.evidence_class = "preflight"; value.status = "NOT_RUN"; value.approvals = [];
  const errors = validateRealUt(result, value, new Date("2026-08-16T00:00:00Z")).join("\n");
  assert.match(errors, /real_participant/); assert.match(errors, /missing approval roles/);
});
test("rejects evidence counts that do not match anonymous results", () => {
  const value = evidence(); value.cohort_counts.older_worker = 3;
  assert.match(validateRealUt(result, value, new Date("2026-08-16T00:00:00Z")).join("\n"), /older_worker/);
});
