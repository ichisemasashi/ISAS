import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { createMvpApiHandler } from "../src/api-handler.mjs";
import { createMemoryMvpRepository } from "../src/memory-mvp-repository.mjs";

const ORIGIN = "https://isas.example";

function fixture(capabilities = ["journal:write"], options = {}) {
  const trusted = {
    userId: "22222222-2222-7222-8222-222222222222",
    csrfToken: "csrf-1",
    membershipVersion: "membership-1",
    authorizationSnapshotId: "snapshot-1",
    authContext: { tenantId: "tenant-1", scopeFieldGroups: ["field-group-1"], capabilities },
  };
  const memory = createMemoryMvpRepository({
    tasks: [{ id: "task-1", tenantId: "tenant-1", time: "08:30", field: "北圃場", crop: "米", work: "水位確認", status: "今日" }],
    fields: [{ type: "Feature", id: "0198a6c0-0000-7000-8000-000000000101", tenantId: "tenant-1", geometry: { type: "MultiPolygon", coordinates: [[[[140.3, 38.2], [140.31, 38.2], [140.31, 38.21], [140.3, 38.2]]]] }, properties: { name: "北圃場", cropName: "つや姫", areaSqm: 1000 } }],
    ...options,
  });
  const handle = createMvpApiHandler({ origin: ORIGIN, resolveContext: async (request) => request.headers.get("Cookie") ? trusted : null, ...memory });
  const request = (path, init = {}) => new Request(`${ORIGIN}${path}`, { ...init, headers: { Cookie: "session=1", ...init.headers } });
  return { ...memory, handle, request };
}

function event(overrides = {}) {
  return {
    eventUuid: "0198-event-1",
    kind: "journal",
    occurredAt: "2026-08-14T00:00:00Z",
    payload: { memo: "水位正常" },
    membershipVersion: "membership-1",
    authorizationSnapshotId: "snapshot-1",
    ...overrides,
  };
}

function pushRequest(fx, bundles) {
  return fx.request("/api/v1/sync/push", {
    method: "POST",
    headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" },
    body: JSON.stringify({ bundles }),
  });
}

describe("MVP REST and synchronization API", () => {
  test("requires the server-resolved context and returns REST task data", async () => {
    const fx = fixture();
    assert.equal((await fx.handle(new Request(`${ORIGIN}/api/v1/today`))).status, 401);
    const response = await fx.handle(fx.request("/api/v1/today"));
    assert.equal(response.status, 200);
    assert.equal((await response.json()).tasks[0].id, "task-1");
  });

  test("atomically accepts a bundle and deduplicates a retry by tenant and event UUID", async () => {
    const fx = fixture();
    const bundle = { bundleId: "bundle-1", events: [event()] };
    const first = await fx.handle(pushRequest(fx, [bundle])).then((response) => response.json());
    const retry = await fx.handle(pushRequest(fx, [bundle])).then((response) => response.json());
    assert.equal(first.results[0].status, "accepted");
    assert.equal(retry.results[0].status, "duplicate");
    assert.equal(retry.results[0].events[0].eventTs, first.results[0].events[0].eventTs);
    assert.equal(fx.state.changes.length, 1);
  });

  test("retains an authorization rejection and reports it through the queue", async () => {
    const fx = fixture();
    const bundle = { bundleId: "bundle-revoked", events: [event({ membershipVersion: "membership-old" })] };
    const pushed = await fx.handle(pushRequest(fx, [bundle])).then((response) => response.json());
    assert.equal(pushed.results[0].status, "rejected");
    const queues = await fx.handle(fx.request("/api/v1/sync/queues")).then((response) => response.json());
    assert.equal(queues.rejections[0].bundleId, "bundle-revoked");
    assert.equal(queues.rejections[0].recoveryAction, "reauthenticate_or_request_manager_review");
  });

  test("pulls changes with an independent scope cursor and fails closed after scope revocation", async () => {
    const fx = fixture();
    await fx.handle(pushRequest(fx, [{ bundleId: "bundle-1", events: [event({ scope: "field-group-1" })] }]));
    const pull = await fx.handle(fx.request("/api/v1/sync/pull?scope=field-group-1&priority=normal"));
    const body = await pull.json();
    assert.equal(body.changes.length, 1);
    assert.equal(body.nextCursor, "1");
    const revoked = await fx.handle(fx.request("/api/v1/sync/pull?scope=field-group-revoked&priority=priority"));
    assert.equal(revoked.status, 409);
    assert.equal((await revoked.json()).type, "scope_revoked");
  });

  test("requires CSRF proof for push", async () => {
    const fx = fixture();
    const response = await fx.handle(fx.request("/api/v1/sync/push", {
      method: "POST", headers: { Origin: ORIGIN, "Content-Type": "application/json" }, body: JSON.stringify({ bundles: [{ bundleId: "bundle-1", events: [event()] }] }),
    }));
    assert.equal(response.status, 403);
  });

  test("returns assigned fields as GeoJSON and rejects an invalid bounding box", async () => {
    const fx = fixture();
    const response = await fx.handle(fx.request("/api/v1/fields?bbox=140.2,38.1,140.5,38.4&q=%E5%8C%97"));
    const body = await response.json();
    assert.equal(response.status, 200);
    assert.equal(body.type, "FeatureCollection");
    assert.equal(body.features[0].properties.name, "北圃場");
    assert.equal((await fx.handle(fx.request("/api/v1/fields?bbox=140,38,139,39"))).status, 400);
  });

  test("lets a manager issue and optimistically reassign work instructions online", async () => {
    const fx = fixture(["journal:write", "instruction:manage"]);
    const create = await fx.handle(fx.request("/api/v1/work-instructions", {
      method: "POST",
      headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" },
      body: JSON.stringify({ fieldId: "0198a6c0-0000-7000-8000-000000000101", assigneeUserId: "22222222-2222-7222-8222-222222222222", title: "北圃場の水位確認", workType: "水管理", scheduledStart: "2026-08-14T00:00:00Z", scheduledEnd: "2026-08-14T01:00:00Z" }),
    }));
    assert.equal(create.status, 201);
    const instruction = await create.json();
    assert.equal(instruction.assignment.assigneeUserId, "22222222-2222-7222-8222-222222222222");

    const reassign = await fx.handle(fx.request(`/api/v1/work-instructions/${instruction.id}/assignment`, {
      method: "PATCH",
      headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" },
      body: JSON.stringify({ assigneeUserId: "33333333-3333-7333-8333-333333333333", expectedVersion: 1 }),
    }));
    assert.equal(reassign.status, 200);
    assert.equal((await reassign.json()).version, 2);

    const stale = await fx.handle(fx.request(`/api/v1/work-instructions/${instruction.id}/assignment`, {
      method: "PATCH",
      headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" },
      body: JSON.stringify({ assigneeUserId: "44444444-4444-7444-8444-444444444444", expectedVersion: 1 }),
    }));
    assert.equal(stale.status, 409);
    assert.equal((await stale.json()).type, "version_conflict");
  });

  test("rejects work instruction mutation without the manager capability", async () => {
    const fx = fixture(["journal:write"]);
    const response = await fx.handle(fx.request("/api/v1/work-instructions", {
      method: "POST",
      headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" },
      body: JSON.stringify({ fieldId: "0198a6c0-0000-7000-8000-000000000101", assigneeUserId: "22222222-2222-7222-8222-222222222222", title: "作業", workType: "水管理", scheduledStart: "2026-08-14T00:00:00Z", scheduledEnd: "2026-08-14T01:00:00Z" }),
    }));
    assert.equal(response.status, 403);
  });

  test("returns offline journal defaults and stores an idempotent photo upload", async () => {
    const fx = fixture(["journal:write"]);
    const bootstrap = await fx.handle(fx.request("/api/v1/journal-bootstrap"));
    assert.equal(bootstrap.status, 200);
    assert.equal((await bootstrap.json()).punchSuggestion.warning, "missing_start");

    const headers = {
      Origin: ORIGIN, "Content-Type": "image/jpeg", "X-CSRF-Token": "csrf-1",
      "X-Attachment-ID": "0198a6c0-0000-7000-8000-000000000201",
      "X-Journal-ID": "0198a6c0-0000-7000-8000-000000000202",
      "X-File-Name": encodeURIComponent("圃場.jpg"), "X-Captured-At": "2026-08-14T00:00:00Z",
    };
    const first = await fx.handle(fx.request("/api/v1/journal-attachments", { method: "POST", headers, body: new Uint8Array([0xff, 0xd8, 0xff]) }));
    const retry = await fx.handle(fx.request("/api/v1/journal-attachments", { method: "POST", headers, body: new Uint8Array([0xff, 0xd8, 0xff]) }));
    assert.equal(first.status, 201);
    assert.equal(retry.status, 201);
    assert.equal((await first.json()).sha256, (await retry.json()).sha256);
    assert.equal(fx.state.attachments.length, 1);
  });

  test("requires a reason for return and keeps correction history", async () => {
    const journalId = "0198a6c0-0000-7000-8000-000000000301";
    const journal = { id: journalId, tenantId: "tenant-1", workerUserId: "22222222-2222-7222-8222-222222222222", fieldName: "北圃場", body: { memo: "旧値" }, status: "submitted", version: 1 };
    const fx = fixture(["journal:write", "journal:review"], { workJournals: [journal] });
    const invalid = await fx.handle(fx.request(`/api/v1/journals/${journalId}/review`, {
      method: "POST", headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" },
      body: JSON.stringify({ action: "return", expectedVersion: 1 }),
    }));
    assert.equal(invalid.status, 400);
    const returned = await fx.handle(fx.request(`/api/v1/journals/${journalId}/review`, {
      method: "POST", headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" },
      body: JSON.stringify({ action: "return", reason: "終了時刻を確認してください", expectedVersion: 1 }),
    }));
    assert.equal(returned.status, 200);
    assert.equal((await returned.json()).status, "returned");
    const listed = await fx.handle(fx.request("/api/v1/journals")).then((response) => response.json());
    assert.equal(listed.journals[0].returnReason, "終了時刻を確認してください");

    const corrected = await fx.handle(pushRequest(fx, [{ bundleId: "bundle-correction", events: [event({ eventUuid: "0198a6c0-0000-7000-8000-000000000302", payload: { aggregateId: journalId, baseVersion: 2, baseValue: { memo: "旧値" }, correctionReason: "終了時刻を訂正", changes: { memo: "訂正値" } } })] }])).then((response) => response.json());
    assert.equal(corrected.results[0].status, "accepted");
    assert.equal(fx.state.journals[0].status, "corrected");
    assert.equal(fx.state.revisions[0].reason, "終了時刻を訂正");
  });

  test("publishes and reads a freshness-bounded pesticide master for an assigned field", async () => {
    const fx = fixture(["pesticide:write", "pesticide:manage"]);
    const response = await fx.handle(fx.request("/api/v1/pesticide-master/releases", {
      method: "POST", headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" },
      body: JSON.stringify({ version: "jp-2026-08-14", validUntil: "2026-08-21T00:00:00Z", chemicals: [{
        id: "0198a6c0-0000-7000-8000-000000000401", registrationNumber: "農林水産省登録第1号", name: "テスト水和剤",
        activeIngredient: "成分A", applicableCrops: ["つや姫"], dilutionMin: 500, dilutionMax: 1000, maxUses: 3, preharvestDays: 7,
      }] }),
    }));
    assert.equal(response.status, 201);
    const bootstrap = await fx.handle(fx.request("/api/v1/pesticide-bootstrap?fieldId=0198a6c0-0000-7000-8000-000000000101")).then((item) => item.json());
    assert.equal(bootstrap.release.version, "jp-2026-08-14");
    assert.equal(bootstrap.chemicals[0].maxUses, 3);
  });

  test("derives inventory from append-only events and queues a negative balance for adjudication", async () => {
    const chemicalId = "0198a6c0-0000-7000-8000-000000000401";
    const fx = fixture(["inventory:write", "inventory:adjust"], { pesticideRelease: { id: "release-1", version: "v1", validUntil: "2026-08-21T00:00:00Z" }, agrochemicals: [{ id: chemicalId, tenantId: "tenant-1", name: "テスト水和剤", registrationNumber: "1" }] });
    const withdrawal = event({ eventUuid: "0198a6c0-0000-7000-8000-000000000402", kind: "stock", payload: { aggregateId: "0198a6c0-0000-7000-8000-000000000403", chemicalId, eventType: "withdrawal", quantity: 2, reason: "散布用出庫" } });
    assert.equal((await fx.handle(pushRequest(fx, [{ bundleId: "stock-1", events: [withdrawal] }])).then((item) => item.json())).results[0].status, "accepted");
    const inventory = await fx.handle(fx.request("/api/v1/inventory")).then((item) => item.json());
    assert.equal(inventory.balances[0].quantity, -2);
    assert.equal(inventory.alerts[0].negativeQuantity, -2);
    const queues = await fx.handle(fx.request("/api/v1/sync/queues")).then((item) => item.json());
    assert.equal(queues.stockAlerts[0].id, inventory.alerts[0].id);

    const adjustment = event({ eventUuid: "0198a6c0-0000-7000-8000-000000000404", kind: "stock", payload: { aggregateId: "0198a6c0-0000-7000-8000-000000000405", chemicalId, eventType: "adjustment", quantity: 5, reason: "実棚3Lを確認", alertId: inventory.alerts[0].id } });
    await fx.handle(pushRequest(fx, [{ bundleId: "stock-adjust", events: [adjustment] }]));
    const resolved = await fx.handle(fx.request("/api/v1/inventory")).then((item) => item.json());
    assert.equal(resolved.balances[0].quantity, 3);
    assert.equal(resolved.alerts.length, 0);
    assert.equal(fx.state.inventoryEvents.length, 2);
  });
});
