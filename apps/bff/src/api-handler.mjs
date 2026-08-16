import { createHash, timingSafeEqual } from "node:crypto";
import { createCsv, parseCsv } from "./csv.mjs";
import { mapMigrationRows } from "./migration.mjs";

const MAX_PUSH_BYTES = 1024 * 1024;
const MAX_BUNDLES = 100;
const MAX_EVENTS_PER_BUNDLE = 100;
const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;
const MAX_IMPORT_BYTES = 5 * 1024 * 1024;
const MAX_JSON_BYTES = 256 * 1024;
const STEP_UP_MAX_AGE_MS = 10 * 60 * 1000;
const IMAGE_TYPES = new Set(["image/jpeg", "image/png", "image/webp", "image/heic"]);

function json(status, body, correlationId, contentType = "application/json; charset=utf-8") {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Cache-Control": "no-store",
      "Content-Type": contentType,
      "X-Correlation-ID": correlationId,
    },
  });
}

function problem(status, type, title, correlationId, detail, extra = {}) {
  return json(status, { type, title, status, detail, correlationId, ...extra }, correlationId, "application/problem+json; charset=utf-8");
}

function csvResponse({ fileName, headers, rows }, correlationId) {
  return new Response(createCsv(headers, rows), {
    status: 200,
    headers: {
      "Cache-Control": "no-store",
      "Content-Type": "text/csv; charset=utf-8",
      "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(fileName)}`,
      "X-Content-Type-Options": "nosniff",
      "X-Correlation-ID": correlationId,
    },
  });
}

function equalSecret(left, right) {
  const a = Buffer.from(left || "");
  const b = Buffer.from(right || "");
  return a.length === b.length && a.length > 0 && timingSafeEqual(a, b);
}

function validWrite(request, origin, csrfToken) {
  const fetchSite = request.headers.get("Sec-Fetch-Site");
  return request.headers.get("Origin") === origin
    && (!fetchSite || fetchSite === "same-origin")
    && equalSecret(request.headers.get("X-CSRF-Token"), csrfToken);
}

function hasRecentStepUp(trusted, now) {
  const authenticatedAt = Date.parse(trusted.authenticatedAt);
  return ["mfa", "phishing-resistant"].includes(trusted.authenticationLevel)
    && Number.isFinite(authenticatedAt)
    && authenticatedAt <= now + 5000
    && now - authenticatedAt <= STEP_UP_MAX_AGE_MS;
}

function privilegedPath(method, path) {
  return path.startsWith("/api/v1/migration-jobs")
    || path.startsWith("/api/v1/security-admin")
    || path.startsWith("/api/v1/exports/")
    || path.startsWith("/api/v1/pesticide-master/reviews")
    || (method !== "GET" && path.startsWith("/api/v1/inventory/"))
    || (method === "POST" && /^\/api\/v1\/journals\/[^/]+\/review$/.test(path))
    || (method === "PATCH" && /^\/api\/v1\/work-instructions\/[^/]+\/assignment$/.test(path))
    || (method === "POST" && /^\/api\/v1\/sync\/conflicts\/[^/]+\/resolve$/.test(path));
}

async function readPush(request) {
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get("Content-Type") || "")) throw new TypeError("content_type");
  const declared = Number(request.headers.get("Content-Length") || 0);
  if (declared > MAX_PUSH_BYTES) throw new RangeError("push_too_large");
  const text = await request.text();
  if (Buffer.byteLength(text) > MAX_PUSH_BYTES) throw new RangeError("push_too_large");
  const body = JSON.parse(text || "{}");
  if (!body || !Array.isArray(body.bundles) || body.bundles.length < 1 || body.bundles.length > MAX_BUNDLES) throw new TypeError("bundles");
  for (const bundle of body.bundles) {
    if (!bundle || typeof bundle.bundleId !== "string" || !bundle.bundleId || !Array.isArray(bundle.events)
      || bundle.events.length < 1 || bundle.events.length > MAX_EVENTS_PER_BUNDLE) throw new TypeError("bundle");
    for (const event of bundle.events) {
      if (!event || typeof event.eventUuid !== "string" || !event.eventUuid || typeof event.kind !== "string"
        || typeof event.occurredAt !== "string" || !event.payload || typeof event.payload !== "object" || Array.isArray(event.payload)) {
        throw new TypeError("event");
      }
    }
  }
  return body;
}

function correlationId(request) {
  const supplied = request.headers.get("X-Correlation-ID");
  return supplied && /^[A-Za-z0-9._-]{1,128}$/.test(supplied) ? supplied : crypto.randomUUID();
}

function fieldSearch(url) {
  const bboxText = url.searchParams.get("bbox");
  let bbox = null;
  if (bboxText) {
    bbox = bboxText.split(",").map(Number);
    if (bbox.length !== 4 || bbox.some((value) => !Number.isFinite(value))
      || bbox[0] < -180 || bbox[2] > 180 || bbox[1] < -90 || bbox[3] > 90
      || bbox[0] >= bbox[2] || bbox[1] >= bbox[3]) throw new TypeError("invalid bbox");
  }
  const query = (url.searchParams.get("q") || "").trim();
  if (query.length > 100) throw new TypeError("invalid query");
  const limit = Number(url.searchParams.get("limit") || 200);
  if (!Number.isInteger(limit) || limit < 1 || limit > 500) throw new TypeError("invalid limit");
  const cursor = url.searchParams.get("cursor");
  if (cursor && !/^[0-9a-f-]{36}$/i.test(cursor)) throw new TypeError("invalid cursor");
  return { bbox, query, limit, cursor };
}

function exportSearch(url) {
  const from = url.searchParams.get("from");
  const to = url.searchParams.get("to");
  if ((from && !/^\d{4}-\d{2}-\d{2}$/.test(from)) || (to && !/^\d{4}-\d{2}-\d{2}$/.test(to)) || (from && to && from > to)) throw new TypeError("invalid export range");
  return { from, to };
}

async function readJsonObject(request) {
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get("Content-Type") || "")) throw new TypeError("content_type");
  const declared = Number(request.headers.get("Content-Length") || 0);
  if (Number.isFinite(declared) && declared > MAX_JSON_BYTES) throw new RangeError("json_too_large");
  const text = await request.text();
  if (Buffer.byteLength(text) > MAX_JSON_BYTES) throw new RangeError("json_too_large");
  const body = JSON.parse(text || "{}");
  if (!body || typeof body !== "object" || Array.isArray(body)) throw new TypeError("body");
  return body;
}

function hasImageSignature(contentType, bytes) {
  if (contentType === "image/jpeg") return bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
  if (contentType === "image/png") return bytes.length >= 8 && bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]));
  if (contentType === "image/webp") return bytes.length >= 12 && bytes.subarray(0, 4).toString("ascii") === "RIFF" && bytes.subarray(8, 12).toString("ascii") === "WEBP";
  if (contentType === "image/heic") return bytes.length >= 12 && bytes.subarray(4, 8).toString("ascii") === "ftyp" && /^hei[cf]|^mif1$/.test(bytes.subarray(8, 12).toString("ascii"));
  return false;
}

async function readMigrationJob(request) {
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get("Content-Type") || "")) throw new TypeError("content_type");
  const declared = Number(request.headers.get("Content-Length") || 0);
  if (declared > MAX_IMPORT_BYTES) throw new RangeError("import_too_large");
  const text = await request.text();
  if (Buffer.byteLength(text) > MAX_IMPORT_BYTES) throw new RangeError("import_too_large");
  const body = JSON.parse(text || "{}");
  if (!body || typeof body !== "object" || Array.isArray(body) || typeof body.csv !== "string"
    || typeof body.dataset !== "string" || typeof body.sourceName !== "string") throw new TypeError("invalid import job");
  const parsed = parseCsv(body.csv);
  return {
    dataset: body.dataset,
    sourceName: body.sourceName,
    sourceSha256: createHash("sha256").update(body.csv).digest("hex"),
    mapping: body.mapping,
    rows: mapMigrationRows(body.dataset, parsed.headers, parsed.rows, body.mapping),
  };
}

async function readAttachment(request) {
  const contentType = (request.headers.get("Content-Type") || "").toLowerCase();
  const declared = Number(request.headers.get("Content-Length") || 0);
  if (!IMAGE_TYPES.has(contentType)) throw new TypeError("content_type");
  if (declared > MAX_ATTACHMENT_BYTES) throw new RangeError("attachment_too_large");
  const bytes = Buffer.from(await request.arrayBuffer());
  if (!bytes.length || bytes.length > MAX_ATTACHMENT_BYTES) throw new RangeError("attachment_size");
  if (!hasImageSignature(contentType, bytes)) throw new TypeError("image_signature");
  const attachmentId = request.headers.get("X-Attachment-ID") || "";
  const journalId = request.headers.get("X-Journal-ID") || "";
  const fileName = decodeURIComponent(request.headers.get("X-File-Name") || "photo");
  const capturedAt = request.headers.get("X-Captured-At") || "";
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(attachmentId)
    || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(journalId)
    || !fileName || fileName.length > 255 || /[\\/\u0000-\u001f\u007f]/.test(fileName)
    || !Number.isFinite(Date.parse(capturedAt))) throw new TypeError("attachment_metadata");
  return {
    attachmentId,
    journalId,
    fileName,
    capturedAt,
    contentType,
    bytes,
    sha256: createHash("sha256").update(bytes).digest("hex"),
  };
}

export function createMvpApiHandler({ origin, resolveContext, database, repository, securityAdministration, testUserAdministration, attachmentStorage, mapStorage, clock = () => Date.now(), logger = {} }) {
  if (!origin || new URL(origin).origin !== origin) throw new Error("origin must be an exact URL origin");
  if (!attachmentStorage) throw new Error("attachmentStorage is required");
  if (!mapStorage) throw new Error("mapStorage is required");

  return async function handle(request) {
    const requestId = correlationId(request);
    const url = new URL(request.url);
    if (url.origin !== origin || !url.pathname.startsWith("/api/v1/")) return problem(404, "not_found", "Not found", requestId);

    const trusted = await resolveContext(request);
    if (!trusted) return problem(401, "authentication_required", "Authentication required", requestId);
    if (privilegedPath(request.method, url.pathname) && !hasRecentStepUp(trusted, clock())) {
      return problem(403, "step_up_required", "Recent MFA authentication required", requestId, undefined, {
        stepUpUrl: `/api/bff/login?step_up=1&return_to=${encodeURIComponent(`${url.pathname}${url.search}`)}`,
      });
    }

    try {
      const localObject = url.pathname.match(/^\/api\/v1\/local-objects\/([A-Za-z0-9_-]{32,128})$/);
      if (request.method === "GET" && localObject && typeof attachmentStorage.readSigned === "function") {
        const object = await attachmentStorage.readSigned(localObject[1], trusted);
        return new Response(object.bytes, { status: 200, headers: { "Content-Type": object.contentType, "Content-Disposition": "inline", "Cache-Control": "no-store", "X-Correlation-ID": requestId, "X-Content-Type-Options": "nosniff" } });
      }

      if (request.method === "GET" && url.pathname === "/api/v1/security-admin") {
        if (!securityAdministration) return problem(503, "service_unavailable", "Security administration unavailable", requestId);
        return json(200, { ...await securityAdministration.snapshot(trusted), localTestUserRegistration: Boolean(testUserAdministration) }, requestId);
      }

      if (request.method === "POST" && url.pathname === "/api/v1/security-admin/local-test-users") {
        if (!testUserAdministration) return problem(404, "not_found", "Not found", requestId);
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        return json(201, await testUserAdministration.provision(trusted, body), requestId);
      }

      if (request.method === "POST" && url.pathname === "/api/v1/security-admin/change-requests") {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        return json(201, await securityAdministration.requestChange(trusted, body), requestId);
      }

      const securityDecision = url.pathname.match(/^\/api\/v1\/security-admin\/change-requests\/([^/]+)\/decision$/);
      if (request.method === "POST" && securityDecision) {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        if (!['approve', 'reject'].includes(body.decision)) throw new TypeError("decision");
        return json(200, await securityAdministration.decideChange(trusted, decodeURIComponent(securityDecision[1]), body), requestId);
      }

      if (request.method === "POST" && url.pathname === "/api/v1/security-admin/privacy-requests") {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        return json(201, await securityAdministration.createPrivacyRequest(trusted, body), requestId);
      }

      if (request.method === "POST" && url.pathname === "/api/v1/security-admin/attachment-storage/reconcile") {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const records = await database.transaction(trusted, (client, canonical) => repository.listAttachmentStorageRecords(client, canonical ? { ...trusted, authContext: canonical } : trusted), { readOnly: true });
        const reconciliation = await attachmentStorage.reconcile({ tenantId: trusted.authContext.tenantId, records });
        const applied = await database.transaction(trusted, (client, canonical) => repository.applyAttachmentReconciliation(client, canonical ? { ...trusted, authContext: canonical } : trusted, reconciliation));
        logger.info?.("attachment_storage_reconciled", {
          scanned: reconciliation.scanned,
          missing: reconciliation.missingAttachmentIds?.length || 0,
          orphanBacklog: reconciliation.taggedOrphans || 0,
        });
        return json(200, { scanned: reconciliation.scanned, taggedOrphans: reconciliation.taggedOrphans, ...applied }, requestId);
      }

      const privacyTransition = url.pathname.match(/^\/api\/v1\/security-admin\/privacy-requests\/([^/]+)\/transitions$/);
      if (request.method === "POST" && privacyTransition) {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        return json(200, await securityAdministration.transitionPrivacyRequest(trusted, decodeURIComponent(privacyTransition[1]), body), requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/today") {
        const result = await database.transaction(trusted, (client, canonical) => repository.getToday(client, canonical ? { ...trusted, authContext: canonical } : trusted), { readOnly: true });
        return json(200, result, requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/location") {
        const result = await database.transaction(trusted, (client, canonical) => repository.getLocationBootstrap(client, canonical ? { ...trusted, authContext: canonical } : trusted, {
          locale: url.searchParams.get("locale") || "ja",
        }), { readOnly: true });
        return json(200, result, requestId);
      }

      if (request.method === "POST" && url.pathname === "/api/v1/location/consents") {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        return json(201, await database.transaction(trusted, (client, canonical) => repository.recordLocationConsent(client, canonical ? { ...trusted, authContext: canonical } : trusted, body)), requestId);
      }

      if (request.method === "PUT" && url.pathname === "/api/v1/location/preference") {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        return json(200, await database.transaction(trusted, (client, canonical) => repository.saveLocationPreference(client, canonical ? { ...trusted, authContext: canonical } : trusted, body)), requestId);
      }

      if (request.method === "POST" && url.pathname === "/api/v1/location/points") {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        return json(202, await database.transaction(trusted, (client, canonical) => repository.appendLocationPoints(client, canonical ? { ...trusted, authContext: canonical } : trusted, body)), requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/location/tracks") {
        const result = await database.transaction(trusted, (client, canonical) => repository.readLocationTracks(client, canonical ? { ...trusted, authContext: canonical } : trusted, {
          subjectUserId: url.searchParams.get("subjectUserId") || trusted.userId,
          from: url.searchParams.get("from"), to: url.searchParams.get("to"), purpose: url.searchParams.get("purpose"),
        }));
        return json(200, result, requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/work-actuals") {
        const defaultTo = new Date(clock()).toISOString();
        const defaultFrom = new Date(clock() - 30 * 86400000).toISOString();
        const result = await database.transaction(trusted, (client, canonical) => repository.getWorkActuals(client, canonical ? { ...trusted, authContext: canonical } : trusted, {
          from: url.searchParams.get("from") || defaultFrom, to: url.searchParams.get("to") || defaultTo,
        }));
        return json(200, result, requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/analytics/overview") {
        const result = await database.transaction(trusted, (client, canonical) => repository.getTenantAnalytics(client, canonical ? { ...trusted, authContext: canonical } : trusted), { readOnly: true });
        return json(200, result, requestId);
      }

      if (request.method === "POST" && url.pathname === "/api/v1/analytics/harvests") {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        return json(201, await database.transaction(trusted, (client, canonical) => repository.recordHarvestActual(client, canonical ? { ...trusted, authContext: canonical } : trusted, body)), requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/fields") {
        const search = fieldSearch(url);
        const result = await database.transaction(trusted, (client, canonical) => repository.searchFields(client, canonical ? { ...trusted, authContext: canonical } : trusted, search), { readOnly: true });
        return json(200, result, requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/offline-map-pack") {
        const fieldGroupId = url.searchParams.get("fieldGroupId");
        const authorization = await database.transaction(trusted, (client, canonical) => repository.authorizeOfflineMapPack(client, canonical ? { ...trusted, authContext: canonical } : trusted, fieldGroupId), { readOnly: true });
        return json(200, await mapStorage.packManifest(authorization), requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/offline-map-archive") {
        const fieldGroupId = url.searchParams.get("fieldGroupId");
        await database.transaction(trusted, (client, canonical) => repository.authorizeOfflineMapPack(client, canonical ? { ...trusted, authContext: canonical } : trusted, fieldGroupId), { readOnly: true });
        const result = await mapStorage.readRange(request.headers.get("Range"), url.searchParams.get("tilesetVersion"));
        return new Response(result.bytes, { status: 206, headers: {
          "Accept-Ranges": "bytes", "Cache-Control": "private, no-store", "Content-Range": result.contentRange,
          "Content-Type": "application/vnd.pmtiles", ...(result.etag ? { ETag: result.etag } : {}), "X-Correlation-ID": requestId,
        } });
      }

      if (request.method === "GET" && url.pathname === "/api/v1/work-instructions") {
        const result = await database.transaction(trusted, (client, canonical) => repository.listWorkInstructions(client, canonical ? { ...trusted, authContext: canonical } : trusted), { readOnly: true });
        return json(200, result, requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/planning/templates") {
        const result = await database.transaction(trusted, (client, canonical) => repository.listPlanningTemplates(client, canonical ? { ...trusted, authContext: canonical } : trusted), { readOnly: true });
        return json(200, result, requestId);
      }

      const templateExpansion = url.pathname.match(/^\/api\/v1\/planning\/templates\/([^/]+)\/expand$/);
      if (request.method === "POST" && templateExpansion) {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        const result = await database.transaction(trusted, (client, canonical) => repository.expandPlanningTemplate(client, canonical ? { ...trusted, authContext: canonical } : trusted, decodeURIComponent(templateExpansion[1]), body));
        return json(201, result, requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/journal-bootstrap") {
        const instructionId = url.searchParams.get("instructionId");
        const fieldId = url.searchParams.get("fieldId");
        const journalId = url.searchParams.get("journalId");
        const result = await database.transaction(trusted, (client, canonical) => repository.getJournalBootstrap(client, canonical ? { ...trusted, authContext: canonical } : trusted, { instructionId, fieldId, journalId }), { readOnly: true });
        return json(200, result, requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/journals") {
        const result = await database.transaction(trusted, (client, canonical) => repository.listJournals(client, canonical ? { ...trusted, authContext: canonical } : trusted), { readOnly: true });
        return json(200, result, requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/pesticide-bootstrap") {
        const fieldId = url.searchParams.get("fieldId");
        const result = await database.transaction(trusted, (client, canonical) => repository.getPesticideBootstrap(client, canonical ? { ...trusted, authContext: canonical } : trusted, { fieldId }), { readOnly: true, poolClass: "p0" });
        return json(200, result, requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/inventory") {
        const result = await database.transaction(trusted, (client, canonical) => repository.listInventory(client, canonical ? { ...trusted, authContext: canonical } : trusted), { readOnly: true });
        return json(200, result, requestId);
      }

      if (request.method === "POST" && url.pathname === "/api/v1/inventory/purchase-orders") {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        return json(201, await database.transaction(trusted, (client, canonical) => repository.createPurchaseOrder(client, canonical ? { ...trusted, authContext: canonical } : trusted, body)), requestId);
      }

      if (request.method === "POST" && url.pathname === "/api/v1/inventory/receipts") {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        return json(201, await database.transaction(trusted, (client, canonical) => repository.receiveInventoryLot(client, canonical ? { ...trusted, authContext: canonical } : trusted, body)), requestId);
      }

      if (request.method === "POST" && url.pathname === "/api/v1/inventory/counts") {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        return json(201, await database.transaction(trusted, (client, canonical) => repository.createInventoryCount(client, canonical ? { ...trusted, authContext: canonical } : trusted, body)), requestId);
      }

      const countPosting = url.pathname.match(/^\/api\/v1\/inventory\/counts\/([^/]+)\/post$/);
      if (request.method === "POST" && countPosting) {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        return json(200, await database.transaction(trusted, (client, canonical) => repository.postInventoryCount(client, canonical ? { ...trusted, authContext: canonical } : trusted, decodeURIComponent(countPosting[1]), body)), requestId);
      }

      if (request.method === "POST" && url.pathname === "/api/v1/migration-jobs") {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const idempotencyKey = request.headers.get("Idempotency-Key");
        if (!idempotencyKey || !/^[A-Za-z0-9._:-]{1,200}$/.test(idempotencyKey)) return problem(400, "invalid_request", "Invalid idempotency key", requestId);
        const input = await readMigrationJob(request);
        const result = await database.transaction(trusted, (client, canonical) => repository.createMigrationJob(client, canonical ? { ...trusted, authContext: canonical } : trusted, { ...input, idempotencyKey }), { poolClass: "p2" });
        return json(201, result, requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/migration-jobs") {
        const result = await database.transaction(trusted, (client, canonical) => repository.listMigrationJobs(client, canonical ? { ...trusted, authContext: canonical } : trusted), { readOnly: true, poolClass: "p2" });
        return json(200, result, requestId);
      }

      const migrationCommitMatch = url.pathname.match(/^\/api\/v1\/migration-jobs\/([^/]+)\/commit$/);
      if (request.method === "POST" && migrationCommitMatch) {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        const result = await database.transaction(trusted, (client, canonical) => repository.commitMigrationJob(client, canonical ? { ...trusted, authContext: canonical } : trusted, decodeURIComponent(migrationCommitMatch[1]), body), { poolClass: "p2" });
        return json(200, result, requestId);
      }

      const exportMatch = url.pathname.match(/^\/api\/v1\/exports\/(fields|journals|pesticide-records|jgap-inventory)\.csv$/);
      if (request.method === "GET" && exportMatch) {
        const search = exportSearch(url);
        const result = await database.transaction(trusted, (client, canonical) => repository.exportCsv(client, canonical ? { ...trusted, authContext: canonical } : trusted, exportMatch[1], search), { readOnly: true, poolClass: "p2" });
        return csvResponse(result, requestId);
      }

      if (request.method === "POST" && url.pathname === "/api/v1/pesticide-master/reviews") {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        const result = await database.transaction(trusted, (client, canonical) => repository.requestPesticideMasterReview(client, canonical ? { ...trusted, authContext: canonical } : trusted, body));
        return json(201, result, requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/pesticide-master/reviews") {
        const result = await database.transaction(trusted, (client, canonical) => repository.listPesticideMasterReviews(client, canonical ? { ...trusted, authContext: canonical } : trusted), { readOnly: true });
        return json(200, result, requestId);
      }

      const pesticideReviewDecision = url.pathname.match(/^\/api\/v1\/pesticide-master\/reviews\/([^/]+)\/decision$/);
      if (request.method === "POST" && pesticideReviewDecision) {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        const result = await database.transaction(trusted, (client, canonical) => repository.decidePesticideMasterReview(client, canonical ? { ...trusted, authContext: canonical } : trusted, decodeURIComponent(pesticideReviewDecision[1]), body));
        return json(200, result, requestId);
      }

      const reviewMatch = url.pathname.match(/^\/api\/v1\/journals\/([^/]+)\/review$/);
      if (request.method === "POST" && reviewMatch) {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        const result = await database.transaction(trusted, (client, canonical) => repository.reviewJournal(client, canonical ? { ...trusted, authContext: canonical } : trusted, decodeURIComponent(reviewMatch[1]), body));
        return json(200, result, requestId);
      }

      if (request.method === "POST" && url.pathname === "/api/v1/journal-attachments") {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const attachment = await readAttachment(request);
        const objectKey = attachmentStorage.objectKey(trusted.authContext.tenantId, attachment);
        const reserved = await database.transaction(trusted, (client, canonical) => repository.saveJournalAttachment(client, canonical ? { ...trusted, authContext: canonical } : trusted, { ...attachment, objectKey }));
        await attachmentStorage.stage({ tenantId: trusted.authContext.tenantId, userId: trusted.userId, attachment, key: reserved.objectKey });
        await attachmentStorage.markReady({ key: reserved.objectKey });
        const result = await database.transaction(trusted, (client, canonical) => repository.markJournalAttachmentReady(client, canonical ? { ...trusted, authContext: canonical } : trusted, attachment.attachmentId, attachment.sha256));
        return json(201, result, requestId);
      }

      const attachmentAccess = url.pathname.match(/^\/api\/v1\/journal-attachments\/([^/]+)\/access$/);
      if (request.method === "GET" && attachmentAccess) {
        const attachment = await database.transaction(trusted, (client, canonical) => repository.getJournalAttachment(client, canonical ? { ...trusted, authContext: canonical } : trusted, decodeURIComponent(attachmentAccess[1])), { readOnly: true });
        return json(200, await attachmentStorage.signedDownload(attachment), requestId);
      }

      if (request.method === "POST" && url.pathname === "/api/v1/work-instructions") {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        const result = await database.transaction(trusted, (client, canonical) => repository.createWorkInstruction(client, canonical ? { ...trusted, authContext: canonical } : trusted, body));
        return json(201, result, requestId);
      }

      const assignmentMatch = url.pathname.match(/^\/api\/v1\/work-instructions\/([^/]+)\/assignment$/);
      if (request.method === "PATCH" && assignmentMatch) {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        const result = await database.transaction(trusted, (client, canonical) => repository.reassignWorkInstruction(client, canonical ? { ...trusted, authContext: canonical } : trusted, decodeURIComponent(assignmentMatch[1]), body));
        return json(200, result, requestId);
      }

      const progressMatch = url.pathname.match(/^\/api\/v1\/work-instructions\/([^/]+)\/progress$/);
      if (request.method === "PATCH" && progressMatch) {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        const result = await database.transaction(trusted, (client, canonical) => repository.updateWorkProgress(client, canonical ? { ...trusted, authContext: canonical } : trusted, decodeURIComponent(progressMatch[1]), body));
        return json(200, result, requestId);
      }

      if (request.method === "POST" && url.pathname === "/api/v1/sync/push") {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const input = await readPush(request);
        const adjustsInventory = input.bundles.some((bundle) => bundle.events.some((event) => event.kind === "stock" && event.payload.eventType === "adjustment"));
        if (adjustsInventory && !hasRecentStepUp(trusted, clock())) {
          return problem(403, "step_up_required", "Recent MFA authentication required", requestId, undefined, {
            stepUpUrl: "/api/bff/login?step_up=1&return_to=%2Finventory",
          });
        }
        const results = [];
        // Each dependency bundle is its own atomic unit. One rejected bundle must not roll back an independent bundle.
        for (const bundle of input.bundles) {
          results.push(await database.transaction(trusted, (client, canonical) => repository.pushBundle(client, canonical ? { ...trusted, authContext: canonical } : trusted, bundle)));
        }
        logger.info?.("sync_push_completed", {
          bundles: results.length,
          rejected: results.filter((result) => result.status === "rejected").length,
          conflicted: results.filter((result) => result.status === "conflicted").length,
        });
        return json(200, { results }, requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/sync/pull") {
        const scope = url.searchParams.get("scope");
        const priority = url.searchParams.get("priority") || "normal";
        const cursor = url.searchParams.get("cursor");
        if (!scope || !["priority", "normal"].includes(priority)) return problem(400, "invalid_request", "Invalid pull request", requestId);
        const result = await database.transaction(trusted, (client, canonical) => repository.pull(client, canonical ? { ...trusted, authContext: canonical } : trusted, { scope, priority, cursor }), { readOnly: true, poolClass: priority === "priority" ? "p0" : "p1" });
        return json(200, result, requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/sync/queues") {
        const result = await database.transaction(trusted, (client, canonical) => repository.getQueues(client, canonical ? { ...trusted, authContext: canonical } : trusted), { readOnly: true });
        return json(200, result, requestId);
      }

      const conflictMatch = url.pathname.match(/^\/api\/v1\/sync\/conflicts\/([^/]+)\/resolve$/);
      if (request.method === "POST" && conflictMatch) {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await readJsonObject(request);
        if (!body || typeof body.resolution !== "object" || Array.isArray(body.resolution)) return problem(400, "invalid_request", "Invalid conflict resolution", requestId);
        const result = await database.transaction(trusted, (client, canonical) => repository.resolveConflict(client, canonical ? { ...trusted, authContext: canonical } : trusted, decodeURIComponent(conflictMatch[1]), body.resolution));
        return json(200, result, requestId);
      }

      return problem(404, "not_found", "Not found", requestId);
    } catch (error) {
      logger.error?.("api_request_failed", { path: url.pathname, code: error?.code || "unhandled", message: error instanceof Error ? error.message : "unknown" });
      if (error instanceof SyntaxError || error instanceof TypeError) return problem(400, "invalid_request", "Invalid request", requestId);
      if (error instanceof RangeError) return problem(413, "request_too_large", "Request too large", requestId);
      if (error?.code === "scope_revoked") return problem(409, "scope_revoked", "Scope was revoked", requestId, undefined, { purgeScope: error.scope });
      if (error?.code === "username_conflict") return problem(409, "username_conflict", "Username already exists", requestId);
      if (error?.code === "invalid_range") return problem(416, "invalid_range", "A valid byte range is required", requestId);
      if (error?.code === "offline_tileset_changed") return problem(409, "offline_tileset_changed", "Offline tileset changed", requestId);
      if (error?.code === "forbidden") return problem(403, "forbidden", "Forbidden", requestId);
      if (error?.code === "42501") return problem(403, "forbidden", "Forbidden", requestId);
      if (["22023", "23505", "23514", "22P02"].includes(error?.code)) return problem(400, "invalid_request", "Invalid request", requestId);
      if (error?.code === "version_conflict") return problem(409, "version_conflict", "Version conflict", requestId, undefined, { currentVersion: error.currentVersion });
      if (error?.code === "40001") return problem(409, "version_conflict", "Version conflict", requestId);
      if (error?.code === "idempotency_conflict") return problem(409, "idempotency_conflict", "Idempotency conflict", requestId);
      return problem(500, "request_failed", "Request failed", requestId);
    }
  };
}
