import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { createMvpApiHandler } from "../src/api-handler.mjs";
import { createMemoryMvpRepository } from "../src/memory-mvp-repository.mjs";

const ORIGIN = "https://isas.example";

function fixture(capabilities = ["journal:write"]) {
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
});
