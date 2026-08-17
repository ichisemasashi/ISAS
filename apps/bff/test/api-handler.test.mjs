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
    authenticationLevel: "mfa",
    authenticatedAt: "2026-08-14T00:00:00.000Z",
    authContext: { tenantId: "tenant-1", scopeFieldGroups: ["field-group-1"], capabilities },
    ...options.trusted,
  };
  const memory = createMemoryMvpRepository({
    tasks: [{ id: "task-1", tenantId: "tenant-1", time: "08:30", field: "北圃場", crop: "米", work: "水位確認", status: "今日" }],
    fields: [{ type: "Feature", id: "0198a6c0-0000-7000-8000-000000000101", tenantId: "tenant-1", geometry: { type: "MultiPolygon", coordinates: [[[[140.3, 38.2], [140.31, 38.2], [140.31, 38.21], [140.3, 38.2]]]] }, properties: { fieldGroupId: "field-group-1", name: "北圃場", cropName: "つや姫", areaSqm: 1000 } }],
    ...options,
  });
  const securityAdministration = options.securityAdministration || {
    async snapshot() { return { users: [], roles: [], changeRequests: [], breakGlassGrants: [], privacyRequests: [] }; },
    async requestChange(_trusted, input) { return { requestId: "security-request-1", status: "pending", input }; },
    async decideChange(_trusted, id) { return { requestId: id, status: "executed" }; },
    async createPrivacyRequest() { return { requestId: "privacy-request-1", status: "submitted" }; },
    async transitionPrivacyRequest(_trusted, id, input) { return { requestId: id, status: input.action }; },
  };
  const handle = createMvpApiHandler({
    origin: ORIGIN,
    resolveContext: async (request) => request.headers.get("Cookie") ? trusted : null,
    clock: () => Date.parse("2026-08-14T00:05:00.000Z"),
    securityAdministration,
    testUserAdministration: options.testUserAdministration,
    logger: options.logger,
    ...memory,
    ...(options.database ? { database: options.database } : {}),
  });
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
  test("classifies priority pull as P0, ordinary pull as P1, and migration work as P2", async () => {
    const poolClasses = [];
    const database = { async transaction(_trusted, operation, options = {}) { poolClasses.push(options.poolClass || "p1"); return operation({}); } };
    const fx = fixture(["migration:manage"], { database });
    assert.equal((await fx.handle(fx.request("/api/v1/sync/pull?scope=field-group-1&priority=priority"))).status, 200);
    assert.equal((await fx.handle(fx.request("/api/v1/sync/pull?scope=field-group-1&priority=normal"))).status, 200);
    assert.equal((await fx.handle(fx.request("/api/v1/migration-jobs"))).status, 200);
    assert.deepEqual(poolClasses, ["p0", "p1", "p2"]);
  });

  test("protects security administration with recent MFA, CSRF, and the dedicated adapter", async () => {
    const fx = fixture(["security:manage"]);
    const snapshot = await fx.handle(fx.request("/api/v1/security-admin"));
    assert.equal(snapshot.status, 200);
    const rejected = await fx.handle(fx.request("/api/v1/security-admin/change-requests", {
      method: "POST", headers: { Origin: ORIGIN, "Content-Type": "application/json" }, body: "{}",
    }));
    assert.equal(rejected.status, 403);
    const created = await fx.handle(fx.request("/api/v1/security-admin/change-requests", {
      method: "POST", headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" },
      body: JSON.stringify({ changeType: "user_revoke", targetUserId: "33333333-3333-7333-8333-333333333333", reason: "退職に伴う利用停止です", ticketRef: "SEC-1", proposedState: {} }),
    }));
    assert.equal(created.status, 201);
    assert.equal((await created.json()).status, "pending");

    const reconciliation = await fx.handle(fx.request("/api/v1/security-admin/attachment-storage/reconcile", {
      method: "POST", headers: { Origin: ORIGIN, "X-CSRF-Token": "csrf-1" },
    }));
    assert.equal(reconciliation.status, 200);
    assert.deepEqual(await reconciliation.json(), { scanned: 0, taggedOrphans: 0, finalized: 0, quarantined: 0 });
  });

  test("exposes local web test-user registration only through its mounted adapter", async () => {
    const calls = [];
    const testUserAdministration = { async provision(trusted, input) { calls.push({ trusted, input }); return { userId: "33333333-3333-7333-8333-333333333333", username: input.username, temporaryPassword: "one-time", status: "ready_for_first_login" }; } };
    const local = fixture(["security:manage"], { testUserAdministration });
    const snapshot = await local.handle(local.request("/api/v1/security-admin"));
    assert.equal((await snapshot.json()).localTestUserRegistration, true);
    const created = await local.handle(local.request("/api/v1/security-admin/local-test-users", {
      method: "POST", headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" },
      body: JSON.stringify({ username: "web-worker", email: "worker@example.test", displayName: "Web作業者", roleKey: "worker" }),
    }));
    assert.equal(created.status, 201);
    assert.equal((await created.json()).temporaryPassword, "one-time");
    assert.equal(calls.length, 1);

    const production = fixture(["security:manage"]);
    assert.equal((await production.handle(production.request("/api/v1/security-admin/local-test-users", {
      method: "POST", headers: { Origin: ORIGIN, "X-CSRF-Token": "csrf-1" }, body: "{}",
    }))).status, 404);
  });

  test("requires a different pesticide master reviewer before publication", async () => {
    const release = { version: "2026-08", validUntil: "2027-08-01T00:00:00Z", chemicals: [{ registrationNumber: "農林1", name: "試験剤", applicableCrops: ["米"], dilutionMin: 1000, dilutionMax: 2000, maxUses: 2, preharvestDays: 7 }] };
    const requester = fixture(["pesticide:manage"]);
    const created = await requester.handle(requester.request("/api/v1/pesticide-master/reviews", {
      method: "POST", headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" },
      body: JSON.stringify({ release, reason: "月次改訂内容の公開申請です", ticketRef: "PEST-1" }),
    })).then((response) => response.json());
    const own = await requester.handle(requester.request(`/api/v1/pesticide-master/reviews/${created.id}/decision`, {
      method: "POST", headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" }, body: JSON.stringify({ decision: "approve", note: "確認済み" }),
    }));
    assert.equal(own.status, 403);
  });
  test("requires the server-resolved context and returns REST task data", async () => {
    const fx = fixture();
    assert.equal((await fx.handle(new Request(`${ORIGIN}/api/v1/today`))).status, 401);
    const response = await fx.handle(fx.request("/api/v1/today"));
    assert.equal(response.status, 200);
    assert.equal((await response.json()).tasks[0].id, "task-1");
  });

  test("collects location only with localized consent, preference, and active non-break punch", async () => {
    const hash = "a".repeat(64);
    const fx = fixture(["punch:write"], { locationPolicies: [{ tenantId: "tenant-1", policyVersion: "location-v1",
      purpose: "work_evidence", locale: "ja", title: "位置情報の利用", body: "作業実績のために利用します。", contentSha256: hash }] });
    const write = (path, body, method = "POST") => fx.handle(fx.request(path, { method,
      headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" }, body: JSON.stringify(body) }));
    assert.equal((await write("/api/v1/location/consents", { eventUuid: crypto.randomUUID(), action: "granted",
      policyVersion: "location-v1", consentTextSha256: hash, locale: "ja" })).status, 201);
    assert.equal((await write("/api/v1/location/preference", { enabled: true, punchLinked: true, retentionDays: 7, locale: "ja" }, "PUT")).status, 200);
    await fx.handle(pushRequest(fx, [{ bundleId: "punch-start", events: [event({ eventUuid: "punch-event-1", kind: "punch",
      occurredAt: "2026-08-14T00:00:00Z", payload: { action: "start" } })] }]));
    const point = await write("/api/v1/location/points", { collectionSessionId: crypto.randomUUID(), points: [{
      eventUuid: crypto.randomUUID(), longitude: 140.305, latitude: 38.205, accuracyM: 8, recordedAt: "2026-08-14T00:01:00Z",
    }] });
    assert.equal(point.status, 202);
    const tracks = await fx.handle(fx.request(`/api/v1/location/tracks?from=2026-08-14T00:00:00Z&to=2026-08-15T00:00:00Z&purpose=${encodeURIComponent("本人による作業実績確認")}`));
    assert.equal(tracks.status, 200);
    assert.equal((await tracks.json()).points.length, 1);
    assert.equal(fx.state.locationAccessAudits.length, 1);
  });

  test("serves tenant analytics from the operational database with missing and freshness metadata", async () => {
    const fx = fixture(["analytics:read", "analytics:write"], { workInstructions: [{ id: "instruction-analytics-1",
      tenantId: "tenant-1", cropPlanId: "crop-plan-1", cropName: "水稲", targetYieldKg: 600,
      fieldGroupId: "field-group-1", scheduledStart: "2026-08-14T00:00:00Z", scheduledEnd: "2026-08-14T01:00:00Z",
      status: "completed" }] });
    const harvested = await fx.handle(fx.request("/api/v1/analytics/harvests", { method: "POST",
      headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" },
      body: JSON.stringify({ eventUuid: crypto.randomUUID(), cropPlanId: "crop-plan-1", fieldId: "field-1",
        fieldGroupId: "field-group-1", harvestedOn: "2026-08-14", quantityKg: 580, grade: "一等" }) }));
    assert.equal(harvested.status, 201);
    const response = await fx.handle(fx.request("/api/v1/analytics/overview"));
    const body = await response.json();
    assert.equal(response.status, 200);
    assert.equal(body.source, "operational_db");
    assert.equal(body.dwhRequired, false);
    assert.equal(body.plans[0].actualYieldKg, 580);
    assert.ok(body.plans[0].missingMetrics.includes("work_actual"));
    assert.equal(body.freshness[0].status, "fresh");
    assert.deepEqual(body.sourceProfile, { manualRecords: 2, machineRecords: 0, manualPercent: 100, machinePercent: 0 });
    assert.deepEqual(body.coverage.find((item) => item.metric === "yield_actual"), {
      metric: "yield_actual", available: true, coveredPlans: 1, totalPlans: 1, percent: 100,
      inputMode: "manual", freshestAt: body.coverage.find((item) => item.metric === "yield_actual").freshestAt,
    });
    assert.equal(body.coverage.find((item) => item.metric === "work_actual").available, false);
  });

  test("requires a recent MFA authentication for exports and adjudication", async () => {
    const fx = fixture(["export:read"], { trusted: { authenticatedAt: "2026-08-13T23:00:00.000Z" } });
    const response = await fx.handle(fx.request("/api/v1/exports/fields.csv"));
    const body = await response.json();
    assert.equal(response.status, 403);
    assert.equal(body.type, "step_up_required");
    assert.match(body.stepUpUrl, /^\/api\/bff\/login\?step_up=1/);
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
    const events = [];
    const fx = fixture(["journal:write"], { logger: { info: (name, fields) => events.push({ name, ...fields }) } });
    const bundle = { bundleId: "bundle-revoked", events: [event({ membershipVersion: "membership-old" })] };
    const pushed = await fx.handle(pushRequest(fx, [bundle])).then((response) => response.json());
    assert.equal(pushed.results[0].status, "rejected");
    const queues = await fx.handle(fx.request("/api/v1/sync/queues")).then((response) => response.json());
    assert.equal(queues.rejections[0].bundleId, "bundle-revoked");
    assert.equal(queues.rejections[0].recoveryAction, "reauthenticate_or_request_manager_review");
    assert.deepEqual(events.at(-1), { name: "sync_push_completed", bundles: 1, rejected: 1, conflicted: 0 });
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

  test("authorizes an offline PMTiles pack only for an assigned field group", async () => {
    const fx = fixture();
    const response = await fx.handle(fx.request("/api/v1/offline-map-pack?fieldGroupId=field-group-1"));
    assert.equal(response.status, 200);
    const manifest = await response.json();
    assert.equal(manifest.fieldGroupId, "field-group-1");
    assert.equal(manifest.minZoom, 8);
    assert.equal(manifest.maxZoom, 16);
    assert.equal(manifest.maxBytes, 250 * 1024 * 1024);
    assert.match(manifest.attribution, /OpenStreetMap/);

    const denied = await fx.handle(fx.request("/api/v1/offline-map-pack?fieldGroupId=other-group"));
    assert.equal(denied.status, 403);
  });

  test("revalidates field-group authorization for every offline PMTiles range", async () => {
    const fx = fixture();
    const response = await fx.handle(fx.request("/api/v1/offline-map-archive?fieldGroupId=field-group-1&tilesetVersion=test-v1", {
      headers: { Range: "bytes=0-2" },
    }));
    assert.equal(response.status, 206);
    assert.equal(response.headers.get("Content-Range"), "bytes 0-2/3");
    assert.deepEqual([...new Uint8Array(await response.arrayBuffer())], [1, 2, 3]);
    assert.equal((await fx.handle(fx.request("/api/v1/offline-map-archive?fieldGroupId=other-group&tilesetVersion=test-v1", { headers: { Range: "bytes=0-2" } }))).status, 403);
    assert.equal((await fx.handle(fx.request("/api/v1/offline-map-archive?fieldGroupId=field-group-1&tilesetVersion=test-v1"))).status, 416);
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
    assert.equal(instruction.fieldName, "北圃場");

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

  test("expands a crop plan template and keeps gantt and mobile progress on the same instructions", async () => {
    const fx = fixture(["planning:manage", "instruction:manage"], {
      planningTemplates: [{ id: "template-1", tenantId: "tenant-1", name: "水稲標準", cropName: "水稲", active: true, version: 3, steps: [
        { stepKey: "plant", title: "田植え", workType: "定植", startOffsetDays: 0, durationMinutes: 120, priority: 1, sortOrder: 1 },
        { stepKey: "check", title: "活着確認", workType: "生育確認", startOffsetDays: 7, durationMinutes: 60, priority: 1, sortOrder: 2, predecessorStepKey: "plant", dependencyType: "finish_start", lagMinutes: 0 },
      ] }],
      planningResources: [{ id: "resource-1", tenantId: "tenant-1", resourceType: "machine", name: "田植機", status: "active" }],
    });
    const templates = await fx.handle(fx.request("/api/v1/planning/templates")).then((response) => response.json());
    assert.equal(templates.templates[0].version, 3);
    const expandedResponse = await fx.handle(fx.request("/api/v1/planning/templates/template-1/expand", {
      method: "POST", headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" },
      body: JSON.stringify({ cropPlanId: "plan-1", fieldId: "0198a6c0-0000-7000-8000-000000000101", fieldGroupId: "field-group-1", fieldName: "北圃場", cropName: "水稲", varietyName: "つや姫", plannedAreaM2: 1000, targetYieldKg: 540, assigneeUserId: "22222222-2222-7222-8222-222222222222", baseDate: "2027-05-01", expectedVersion: 3 }),
    }));
    assert.equal(expandedResponse.status, 201);
    const expanded = await expandedResponse.json();
    assert.equal(expanded.instructions.length, 2);
    assert.equal(expanded.instructions[1].dependencies.length, 1);
    const progress = await fx.handle(fx.request(`/api/v1/work-instructions/${expanded.instructions[0].id}/progress`, {
      method: "PATCH", headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" },
      body: JSON.stringify({ eventUuid: "progress-1", progressPercent: 40, expectedVersion: 1, note: "現場確認", occurredAt: "2027-05-01T01:00:00Z" }),
    })).then((response) => response.json());
    assert.equal(progress.progressPercent, 40);
    const gantt = await fx.handle(fx.request("/api/v1/work-instructions")).then((response) => response.json());
    assert.equal(gantt.instructions[0].progressPercent, 40);
    assert.equal(gantt.instructions[0].varietyName, "つや姫");
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
    assert.equal(fx.state.attachments[0].storageStatus, "ready");
    assert.equal(fx.state.attachments[0].objectKey.startsWith("attachments/tenant-1/"), true);

    const access = await fx.handle(fx.request("/api/v1/journal-attachments/0198a6c0-0000-7000-8000-000000000201/access"));
    assert.equal(access.status, 200);
    const signed = await access.json();
    assert.match(signed.url, /^https:\/\/objects\.example\//);
    assert.equal(signed.contentType, "image/jpeg");
    assert.equal("objectKey" in signed, false);
  });

  test("rejects spoofed image content and oversized JSON before repository processing", async () => {
    const fx = fixture(["journal:write", "instruction:manage"]);
    const invalidImage = await fx.handle(fx.request("/api/v1/journal-attachments", {
      method: "POST",
      headers: {
        Origin: ORIGIN, "Content-Type": "image/jpeg", "X-CSRF-Token": "csrf-1",
        "X-Attachment-ID": "0198a6c0-0000-7000-8000-000000000201",
        "X-Journal-ID": "0198a6c0-0000-7000-8000-000000000202",
        "X-File-Name": "field.jpg", "X-Captured-At": "2026-08-14T00:00:00Z",
      },
      body: new TextEncoder().encode("not an image"),
    }));
    assert.equal(invalidImage.status, 400);

    const oversized = await fx.handle(fx.request("/api/v1/work-instructions", {
      method: "POST",
      headers: { Origin: ORIGIN, "Content-Type": "application/json", "Content-Length": String(256 * 1024 + 1), "X-CSRF-Token": "csrf-1" },
      body: "{}",
    }));
    assert.equal(oversized.status, 413);
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

  test("submits and lists a freshness-bounded pesticide master for independent review", async () => {
    const fx = fixture(["pesticide:write", "pesticide:manage"]);
    const response = await fx.handle(fx.request("/api/v1/pesticide-master/reviews", {
      method: "POST", headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" },
      body: JSON.stringify({ reason: "農薬マスター月次更新の申請です", ticketRef: "PEST-2026-08", release: { version: "jp-2026-08-14", validUntil: "2026-08-21T00:00:00Z", chemicals: [{
        id: "0198a6c0-0000-7000-8000-000000000401", registrationNumber: "農林水産省登録第1号", name: "テスト水和剤",
        activeIngredient: "成分A", applicableCrops: ["つや姫"], dilutionMin: 500, dilutionMax: 1000, maxUses: 3, preharvestDays: 7,
      }] } }),
    }));
    assert.equal(response.status, 201);
    const listed = await fx.handle(fx.request("/api/v1/pesticide-master/reviews")).then((item) => item.json());
    assert.equal(listed.reviews[0].status, "pending");
    assert.equal(listed.reviews[0].proposedRelease.version, "jp-2026-08-14");
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

  test("tracks purchase arrivals, lots, inventory counts, valuation and JGAP CSV", async () => {
    const chemicalId = "0198a6c0-0000-7000-8000-000000000502";
    const fx = fixture(["inventory:write", "inventory:adjust", "export:read"], {
      pesticideRelease: { id: "release-1", version: "v1", validUntil: "2027-08-21T00:00:00Z" },
      agrochemicals: [{ id: chemicalId, tenantId: "tenant-1", name: "試験水和剤", registrationNumber: "農林1" }],
      inventoryPolicies: [{ tenantId: "tenant-1", chemicalId, status: "active", reorderPoint: 5, targetLevel: 20, safetyStock: 3 }],
    });
    const order = await fx.handle(fx.request("/api/v1/inventory/purchase-orders", {
      method: "POST", headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" },
      body: JSON.stringify({ orderNumber: "PO-2027-001", supplierName: "農業資材店", orderedOn: "2027-01-01", expectedOn: "2027-01-10", currency: "JPY", lines: [{ chemicalId, orderedQuantity: 10, unit: "L", unitCost: 1200 }] }),
    })).then((response) => response.json());
    assert.equal(order.status, "ordered");
    const receipt = await fx.handle(fx.request("/api/v1/inventory/receipts", {
      method: "POST", headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" },
      body: JSON.stringify({ purchaseOrderLineId: order.lines[0].id, lotId: "lot-1", eventUuid: "receipt-1", lotNumber: "LOT-A", receivedOn: "2027-01-09", expiresOn: "2028-01-09", quantity: 6, jgapAttributes: { storage: "施錠庫" } }),
    })).then((response) => response.json());
    assert.equal(receipt.purchaseOrderStatus, "partially_received");
    const count = await fx.handle(fx.request("/api/v1/inventory/counts", {
      method: "POST", headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" },
      body: JSON.stringify({ locationName: "農薬庫", countedAt: "2027-01-31T00:00:00Z", lines: [{ chemicalId, lotId: "lot-1", systemQuantity: 6, countedQuantity: 5, unit: "L", reason: "月次棚卸し" }] }),
    })).then((response) => response.json());
    assert.equal(count.lines[0].variance, -1);
    const inventory = await fx.handle(fx.request("/api/v1/inventory")).then((response) => response.json());
    assert.equal(inventory.incoming[0].incomingQuantity, 4);
    assert.equal(inventory.lots[0].inventoryValue, 7200);
    const exported = await fx.handle(fx.request("/api/v1/exports/jgap-inventory.csv"));
    assert.equal(exported.status, 200);
    assert.match(await exported.text(), /LOT-A/);
  });

  test("stages mapped CSV rows, reports duplicates and commits only a validated migration job", async () => {
    const fx = fixture(["migration:manage"]);
    const create = await fx.handle(fx.request("/api/v1/migration-jobs", {
      method: "POST", headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1", "Idempotency-Key": "fields-20260814" },
      body: JSON.stringify({ dataset: "fields", sourceName: "fields.csv",
        csv: "code,name,group,wkt\nF-001,北圃場,f1111111-1111-7111-8111-111111111111,\"POLYGON((140 38,141 38,141 39,140 38))\"\nF-001,重複圃場,f1111111-1111-7111-8111-111111111111,\"POLYGON((140 38,141 38,141 39,140 38))\"\n",
        mapping: { externalKey: "code", name: "name", fieldGroupId: "group", geometryWkt: "wkt" } }),
    }));
    assert.equal(create.status, 201);
    const job = await create.json();
    assert.equal(job.status, "validated");
    assert.equal(job.validCount, 1);
    assert.equal(job.duplicateCount, 1);
    const committed = await fx.handle(fx.request(`/api/v1/migration-jobs/${job.id}/commit`, {
      method: "POST", headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" }, body: JSON.stringify({ expectedVersion: 1 }),
    })).then((response) => response.json());
    assert.equal(committed.status, "committed");
    assert.equal(fx.state.migrationJobs[0].rows[0].status, "committed");
  });

  test("keeps invalid migration rows for correction and refuses commit", async () => {
    const fx = fixture(["migration:manage"]);
    const create = await fx.handle(fx.request("/api/v1/migration-jobs", {
      method: "POST", headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1", "Idempotency-Key": "journals-invalid" },
      body: JSON.stringify({ dataset: "journals", sourceName: "journals.csv", csv: "id,field,worker,work,date,start,end\nJ1,,bad,除草,2026/08/14,10:00,09:00\n",
        mapping: { externalKey: "id", fieldExternalKey: "field", workerUserId: "worker", workType: "work", workedOn: "date", startedAt: "start", endedAt: "end" } }),
    }));
    const job = await create.json();
    assert.equal(job.status, "needs_review");
    assert.equal(job.rows[0].errors.includes("required:fieldExternalKey"), true);
    const commit = await fx.handle(fx.request(`/api/v1/migration-jobs/${job.id}/commit`, {
      method: "POST", headers: { Origin: ORIGIN, "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" }, body: JSON.stringify({ expectedVersion: 1 }),
    }));
    assert.equal(commit.status, 400);
  });

  test("exports an RLS-filtered UTF-8 CSV and neutralizes spreadsheet formulas", async () => {
    const fx = fixture(["export:read"], { fields: [{ type: "Feature", id: "0198a6c0-0000-7000-8000-000000000101", tenantId: "tenant-1", geometry: { type: "MultiPolygon", coordinates: [] }, properties: { name: "=HYPERLINK(\"bad\")", cropName: "つや姫", areaSqm: 1000 } }] });
    const response = await fx.handle(fx.request("/api/v1/exports/fields.csv"));
    assert.equal(response.status, 200);
    assert.match(response.headers.get("Content-Type"), /^text\/csv/);
    assert.match(response.headers.get("Content-Disposition"), /attachment/);
    const csv = await response.text();
    assert.match(csv, /^\uFEFF?圃場コード,圃場名,作物/);
    assert.match(csv, /'=HYPERLINK/);
    assert.equal((await fx.handle(fx.request("/api/v1/exports/journals.csv?from=2026-08-20&to=2026-08-01"))).status, 400);
  });

  test("rejects CSV export without the current export capability", async () => {
    const fx = fixture([]);
    assert.equal((await fx.handle(fx.request("/api/v1/exports/fields.csv"))).status, 403);
  });
});
