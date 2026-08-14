import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { createMvpApiHandler } from "../src/api-handler.mjs";
import { createMemoryMvpRepository } from "../src/memory-mvp-repository.mjs";

const ORIGIN = "https://isas.example";

function fixture(capabilities = ["journal:write"]) {
  const trusted = {
    csrfToken: "csrf-1",
    membershipVersion: "membership-1",
    authorizationSnapshotId: "snapshot-1",
    authContext: { tenantId: "tenant-1", scopeFieldGroups: ["field-group-1"], capabilities },
  };
  const memory = createMemoryMvpRepository({ tasks: [{ id: "task-1", tenantId: "tenant-1", time: "08:30", field: "北圃場", crop: "米", work: "水位確認", status: "今日" }] });
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
});
