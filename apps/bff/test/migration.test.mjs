import assert from "node:assert/strict";
import { test } from "node:test";
import { mapMigrationRows } from "../src/migration.mjs";

test("field mapping normalizes rows and marks an in-file natural-key duplicate", () => {
  const headers = ["圃場コード", "名称", "班ID", "境界"];
  const rows = [1, 2].map(() => ({ 圃場コード: "F-001", 名称: "北圃場", 班ID: "f1111111-1111-7111-8111-111111111111", 境界: "POLYGON((140 38,141 38,141 39,140 38))" }));
  const result = mapMigrationRows("fields", headers, rows, { externalKey: "圃場コード", name: "名称", fieldGroupId: "班ID", geometryWkt: "境界" });
  assert.equal(result[0].status, "valid");
  assert.equal(result[0].normalized.timezone, "Asia/Tokyo");
  assert.equal(result[1].status, "duplicate");
});

test("journal mapping reports missing and invalid values by source line", () => {
  const headers = ["id", "field", "worker", "work", "date", "start", "end"];
  const result = mapMigrationRows("journals", headers, [{ id: "J1", field: "", worker: "bad", work: "除草", date: "2026/08/14", start: "10:00", end: "09:00" }],
    { externalKey: "id", fieldExternalKey: "field", workerUserId: "worker", workType: "work", workedOn: "date", startedAt: "start", endedAt: "end" });
  assert.equal(result[0].status, "invalid");
  assert.deepEqual(result[0].errors, ["required:fieldExternalKey", "invalid_worker_user_id", "invalid_worked_on", "end_before_start"]);
});

test("pesticide history uses field, crop, registration and season as duplicate key", () => {
  const headers = ["field", "crop", "reg", "count", "last"];
  const result = mapMigrationRows("pesticide_history", headers, [{ field: "F1", crop: "米", reg: "123", count: "2", last: "2026-07-01" }],
    { fieldExternalKey: "field", cropName: "crop", registrationNumber: "reg", usageCount: "count", lastAppliedOn: "last" });
  assert.equal(result[0].status, "valid");
  assert.equal(result[0].normalized.seasonYear, 2026);
  assert.equal(result[0].normalized.usageCount, 2);
});
