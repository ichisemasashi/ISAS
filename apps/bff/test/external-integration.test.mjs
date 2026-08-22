import assert from "node:assert/strict";
import test from "node:test";
import { createExternalReadApi } from "../src/external-read-api.mjs";
import { createMachineryConnector } from "../src/machinery-connector.mjs";

const TENANT = "0198a6c0-0000-7000-8000-000000000001";
test("read-only API binds service identity, tenant scope, consent and audit", async () => {
  const audits = [];
  const handle = createExternalReadApi({ origin: "https://api.isas.test", clock: () => Date.parse("2026-08-22T00:00:00Z"),
    async resolveServiceIdentity() { return { status: "active", tenantId: TENANT, subject: "partner", clientId: "client-1", expiresAt: "2026-09-01", scopes: ["external:fields:read"], consent: { status: "granted", expiresAt: "2026-09-01" } }; },
    repository: { async listFieldsForExternalService(input) { assert.equal(input.tenantId, TENANT); return { items: [{ id: "field-1" }], nextCursor: null }; } },
    audit: { async record(event) { audits.push(event); } },
  });
  const response = await handle(new Request("https://api.isas.test/api/external/v1/fields")); assert.equal(response.status, 200); assert.equal(audits[0].actorType, "service");
  assert.equal((await handle(new Request("https://api.isas.test/api/external/v1/fields", { method: "POST" }))).status, 405);
});

test("connector commits cursor only with a page, converts units, deduplicates and continues in file mode on outage", async () => {
  let cursor = null; const events = new Set(); const audits = [];
  const store = { async getCursor() { return cursor; }, async commitPage(page) { let inserted = 0; for (const item of page.observations) if (!events.has(item.providerEventId)) { events.add(item.providerEventId); inserted += 1; assert.equal(item.areaM2, 10000); } cursor = page.nextCursor; return { inserted, duplicates: page.observations.length - inserted }; }, async revoke() {}, async audit(event) { audits.push(event); } };
  const responses = [new Response(JSON.stringify({ items: [{ id: "event-1", observedAt: "2026-08-20T00:00:00Z", fieldExternalKey: "f-1", operation: "harvest", area: 1 }], nextCursor: "page-2" }), { status: 200 }), new Response("unavailable", { status: 503 }), new Response("unavailable", { status: 503 }), new Response("unavailable", { status: 503 })];
  const connector = createMachineryConnector({ registration: { status: "active", baseOrigin: "https://provider.test", tenantId: TENANT, connectorId: "connector-1", consent: { status: "granted", expiresAt: "2026-09-01" }, unitConversions: { area: { source: "ha", target: "m2", factor: 10000, offset: 0 } } },
    tokenProvider: { async accessToken() { return "token"; } }, store, fetcher: async () => responses.shift(), delay: async () => {}, clock: () => Date.parse("2026-08-22T00:00:00Z") });
  const result = await connector.pull(); assert.equal(result.status, "DEGRADED"); assert.equal(result.imported, 1); assert.equal(result.cursor, "page-2"); assert.equal(result.fileImportAvailable, true); assert.ok(audits.some(({ action }) => action === "connector.pull.failed"));
});
