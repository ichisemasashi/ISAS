import assert from "node:assert/strict";
import { test } from "node:test";
import { createPostgresMvpRepository, postgresMvpContract } from "../src/postgres-mvp-repository.mjs";

const T1 = "11111111-1111-7111-8111-111111111111";
const E1 = "0198a6c0-0000-7000-8000-000000000001";

test("PostgreSQL repository reads the task projection behind RLS", async () => {
  const calls = [];
  const client = { async query(sql, values) { calls.push({ sql, values }); return { rows: [{ id: "task-1", time: "08:30", field: "北圃場", crop: "米", work: "水位確認", status: "today" }] }; } };
  const repository = createPostgresMvpRepository();
  const result = await repository.getToday(client);
  assert.equal(result.tasks[0].id, "task-1");
  assert.match(calls[0].sql, /FROM app\.task/);
  assert.doesNotMatch(calls[0].sql, /tenant_id\s*=\s*\$\d/);
});

test("PostGIS repository makes tenant equality and bbox explicit while returning GeoJSON", async () => {
  const calls = [];
  const client = { async query(sql, values) { calls.push({ sql, values }); return { rows: [{ id: "0198a6c0-0000-7000-8000-000000000101", field_group_id: "f1111111-1111-7111-8111-111111111111", name: "北圃場", crop_name: "つや姫", status: "active", gis_area_sqm: "1234.5", version: "1", geometry: { type: "MultiPolygon", coordinates: [] } }] }; } };
  const repository = createPostgresMvpRepository();
  const result = await repository.searchFields(client, { authContext: { tenantId: T1 } }, { bbox: [140.2, 38.1, 140.5, 38.4], query: "北", limit: 200, cursor: null });
  assert.equal(result.type, "FeatureCollection");
  assert.equal(result.features[0].properties.areaSqm, 1234.5);
  assert.match(calls[0].sql, /tenant_id = \$1::uuid/);
  assert.match(calls[0].sql, /geom && ST_MakeEnvelope/);
  assert.deepEqual(calls[0].values.slice(0, 8), [T1, null, "北", true, 140.2, 38.1, 140.5, 38.4]);
});

test("PostgreSQL repository records an authorization change instead of dropping the event", async () => {
  const calls = [];
  const client = {
    async query(sql, values) {
      calls.push({ sql, values });
      if (sql.includes("FROM app.event_receipt")) return { rows: [] };
      if (sql.includes("app.has_capability")) return { rows: [{ allowed: true }] };
      if (sql.includes("INSERT INTO app.sync_rejection")) return { rows: [{ id: "aaaaaaaa-0000-7000-8000-000000000001", bundle_id: "bundle-1", event_uuids: [E1], reason: "authorization_changed", recovery_action: "reauthenticate_or_request_manager_review", created_at: new Date("2026-08-14T00:00:00Z") }] };
      throw new Error(`unexpected query: ${sql}`);
    },
  };
  const repository = createPostgresMvpRepository({ uuid: () => "aaaaaaaa-0000-7000-8000-000000000001" });
  const result = await repository.pushBundle(client, {
    membershipVersion: "current-membership", authorizationSnapshotId: "current-snapshot", actorPseudonym: "actor-1",
    authContext: { tenantId: T1 },
  }, { bundleId: "bundle-1", events: [{ eventUuid: E1, kind: "journal", occurredAt: "2026-08-14T00:00:00Z", payload: {}, membershipVersion: "old-membership", authorizationSnapshotId: "old-snapshot" }] });
  assert.equal(result.status, "rejected");
  assert.equal(result.rejection.reason, "authorization_changed");
  assert.equal(calls.some(({ sql }) => sql.includes("sync_rejection")), true);
});

test("PostgreSQL pull fixes an MVCC upper bound before reading a page", async () => {
  const calls = [];
  const client = { async query(sql, values) { calls.push({ sql, values }); return sql.includes("max(server_seq)") ? { rows: [{ upper: "42" }] } : { rows: [{ server_seq: "41", type: "journal", operation: "upsert", entity_id: null, event_uuid: E1, data: { memo: "ok" } }] }; } };
  const repository = createPostgresMvpRepository();
  const result = await repository.pull(client, { authContext: { scopeFieldGroups: ["f1111111-1111-7111-8111-111111111111"] } }, { scope: "f1111111-1111-7111-8111-111111111111", priority: "normal", cursor: "40" });
  assert.equal(result.snapshotUpper, "42");
  assert.equal(result.nextCursor, "41");
  assert.deepEqual(calls[1].values.slice(0, 2), [40, "42"]);
});

test("field merge applies independent changes and queues only overlapping fields", () => {
  const result = postgresMvpContract.mergeFields(
    { memo: "base", hours: 1, field: "north" },
    { memo: "server", hours: 1, field: "north" },
    { memo: "device", hours: 2, field: "north" },
  );
  assert.deepEqual(result.conflicts, ["memo"]);
  assert.deepEqual(result.merged, { memo: "server", hours: 2, field: "north" });
});

test("conflict resolution performs no write without the current capability", async () => {
  const calls = [];
  const client = { async query(sql, values) { calls.push({ sql, values }); return { rows: [{ allowed: false }] }; } };
  const repository = createPostgresMvpRepository();
  await assert.rejects(() => repository.resolveConflict(client, {}, "0198a6c0-0000-7000-8000-000000000003", { choice: "device" }), (error) => error.code === "forbidden");
  assert.equal(calls.length, 1);
  assert.match(calls[0].sql, /has_capability/);
});

test("work reassignment locks the instruction and rejects a stale expected version", async () => {
  const calls = [];
  const client = { async query(sql, values) {
    calls.push({ sql, values });
    if (sql.includes("has_capability")) return { rows: [{ allowed: true }] };
    if (sql.includes("FOR UPDATE")) return { rows: [{ id: E1, field_group_id: "f1111111-1111-7111-8111-111111111111", version: "3" }] };
    throw new Error(`unexpected query: ${sql}`);
  } };
  const repository = createPostgresMvpRepository();
  await assert.rejects(() => repository.reassignWorkInstruction(client, { authContext: { tenantId: T1 } }, E1, { assigneeUserId: "22222222-2222-7222-8222-222222222222", expectedVersion: 2 }), (error) => error.code === "version_conflict" && error.currentVersion === 3);
  assert.match(calls[1].sql, /FOR UPDATE/);
  assert.equal(calls.length, 2);
});

test("punch suggestion fills a journal and warns when a pair is incomplete", () => {
  const complete = postgresMvpContract.derivePunchSuggestion([
    { action: "start", occurred_at: "2026-08-14T00:12:00Z" },
    { action: "finish", occurred_at: "2026-08-14T01:36:00Z" },
  ]);
  assert.deepEqual(complete, { startedAt: "09:12", endedAt: "10:36", warning: null });
  assert.equal(postgresMvpContract.derivePunchSuggestion([{ action: "start", occurred_at: "2026-08-14T00:12:00Z" }]).warning, "missing_finish");
});

test("pesticide safety rechecks crop, annual uses, harvest interval and master freshness", () => {
  const result = postgresMvpContract.pesticideSafety({
    chemical: { current_chemical_id: E1, release_valid_until: "2026-08-13T00:00:00Z", revoked_on: null,
      applicable_crops: ["つや姫"], dilution_min: "500", dilution_max: "1000", max_uses: "3", preharvest_days: "7" },
    cropName: "雪若丸", dilution: 1200, appliedOn: "2026-08-14", plannedHarvestOn: "2026-08-18", usageCount: 3,
    now: new Date("2026-08-14T00:00:00Z"),
  });
  assert.equal(result.status, "warning");
  assert.deepEqual(result.reasons, ["master_expired", "crop_not_applicable", "dilution_out_of_range", "maximum_uses_exceeded", "preharvest_interval_short"]);
});

test("journal return locks the current version and appends an audit revision", async () => {
  const calls = [];
  const client = { async query(sql, values) {
    calls.push({ sql, values });
    if (sql.includes("has_capability")) return { rows: [{ allowed: true }] };
    if (sql.includes("FROM app.work_journal") && sql.includes("FOR UPDATE")) return { rows: [{ id: E1, worker_user_id: "22222222-2222-7222-8222-222222222222", body: { memo: "確認前" }, status: "submitted", version: "4" }] };
    if (sql.includes("INSERT INTO app.journal_revision")) return { rows: [] };
    if (sql.includes("UPDATE app.work_journal")) return { rows: [{ status: "returned", version: "5", updated_at: new Date("2026-08-14T00:00:00Z") }] };
    if (sql.includes("INSERT INTO app.sync_change")) return { rows: [] };
    throw new Error(`unexpected query: ${sql}`);
  } };
  const repository = createPostgresMvpRepository({ uuid: () => "aaaaaaaa-0000-7000-8000-000000000099" });
  const result = await repository.reviewJournal(client, {}, E1, { action: "return", expectedVersion: 4, reason: "終了時刻を確認" });
  assert.equal(result.status, "returned");
  assert.equal(result.version, 5);
  assert.equal(calls.some(({ sql }) => sql.includes("INSERT INTO app.journal_revision")), true);
  assert.equal(calls.at(-1).values[1], "returned");
});
