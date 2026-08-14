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
