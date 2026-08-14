import { createHash, timingSafeEqual } from "node:crypto";

const MAX_PUSH_BYTES = 1024 * 1024;
const MAX_BUNDLES = 100;
const MAX_EVENTS_PER_BUNDLE = 100;
const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;
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

async function readJsonObject(request) {
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get("Content-Type") || "")) throw new TypeError("content_type");
  const body = await request.json();
  if (!body || typeof body !== "object" || Array.isArray(body)) throw new TypeError("body");
  return body;
}

async function readAttachment(request) {
  const contentType = (request.headers.get("Content-Type") || "").toLowerCase();
  const declared = Number(request.headers.get("Content-Length") || 0);
  if (!IMAGE_TYPES.has(contentType)) throw new TypeError("content_type");
  if (declared > MAX_ATTACHMENT_BYTES) throw new RangeError("attachment_too_large");
  const bytes = Buffer.from(await request.arrayBuffer());
  if (!bytes.length || bytes.length > MAX_ATTACHMENT_BYTES) throw new RangeError("attachment_size");
  return {
    attachmentId: request.headers.get("X-Attachment-ID"),
    journalId: request.headers.get("X-Journal-ID"),
    fileName: decodeURIComponent(request.headers.get("X-File-Name") || "photo"),
    capturedAt: request.headers.get("X-Captured-At"),
    contentType,
    bytes,
    sha256: createHash("sha256").update(bytes).digest("hex"),
  };
}

export function createMvpApiHandler({ origin, resolveContext, database, repository }) {
  if (!origin || new URL(origin).origin !== origin) throw new Error("origin must be an exact URL origin");

  return async function handle(request) {
    const requestId = correlationId(request);
    const url = new URL(request.url);
    if (url.origin !== origin || !url.pathname.startsWith("/api/v1/")) return problem(404, "not_found", "Not found", requestId);

    const trusted = await resolveContext(request);
    if (!trusted) return problem(401, "authentication_required", "Authentication required", requestId);

    try {
      if (request.method === "GET" && url.pathname === "/api/v1/today") {
        const result = await database.transaction(trusted, (client, canonical) => repository.getToday(client, canonical ? { ...trusted, authContext: canonical } : trusted), { readOnly: true });
        return json(200, result, requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/fields") {
        const search = fieldSearch(url);
        const result = await database.transaction(trusted, (client, canonical) => repository.searchFields(client, canonical ? { ...trusted, authContext: canonical } : trusted, search), { readOnly: true });
        return json(200, result, requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/work-instructions") {
        const result = await database.transaction(trusted, (client, canonical) => repository.listWorkInstructions(client, canonical ? { ...trusted, authContext: canonical } : trusted), { readOnly: true });
        return json(200, result, requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/journal-bootstrap") {
        const instructionId = url.searchParams.get("instructionId");
        const fieldId = url.searchParams.get("fieldId");
        const result = await database.transaction(trusted, (client, canonical) => repository.getJournalBootstrap(client, canonical ? { ...trusted, authContext: canonical } : trusted, { instructionId, fieldId }), { readOnly: true });
        return json(200, result, requestId);
      }

      if (request.method === "POST" && url.pathname === "/api/v1/journal-attachments") {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const attachment = await readAttachment(request);
        const result = await database.transaction(trusted, (client, canonical) => repository.saveJournalAttachment(client, canonical ? { ...trusted, authContext: canonical } : trusted, attachment));
        return json(201, result, requestId);
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

      if (request.method === "POST" && url.pathname === "/api/v1/sync/push") {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const input = await readPush(request);
        const results = [];
        // Each dependency bundle is its own atomic unit. One rejected bundle must not roll back an independent bundle.
        for (const bundle of input.bundles) {
          results.push(await database.transaction(trusted, (client, canonical) => repository.pushBundle(client, canonical ? { ...trusted, authContext: canonical } : trusted, bundle)));
        }
        return json(200, { results }, requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/sync/pull") {
        const scope = url.searchParams.get("scope");
        const priority = url.searchParams.get("priority") || "normal";
        const cursor = url.searchParams.get("cursor");
        if (!scope || !["priority", "normal"].includes(priority)) return problem(400, "invalid_request", "Invalid pull request", requestId);
        const result = await database.transaction(trusted, (client, canonical) => repository.pull(client, canonical ? { ...trusted, authContext: canonical } : trusted, { scope, priority, cursor }), { readOnly: true });
        return json(200, result, requestId);
      }

      if (request.method === "GET" && url.pathname === "/api/v1/sync/queues") {
        const result = await database.transaction(trusted, (client, canonical) => repository.getQueues(client, canonical ? { ...trusted, authContext: canonical } : trusted), { readOnly: true });
        return json(200, result, requestId);
      }

      const conflictMatch = url.pathname.match(/^\/api\/v1\/sync\/conflicts\/([^/]+)\/resolve$/);
      if (request.method === "POST" && conflictMatch) {
        if (!validWrite(request, origin, trusted.csrfToken)) return problem(403, "request_rejected", "Request rejected", requestId);
        const body = await request.json();
        if (!body || typeof body.resolution !== "object" || Array.isArray(body.resolution)) return problem(400, "invalid_request", "Invalid conflict resolution", requestId);
        const result = await database.transaction(trusted, (client, canonical) => repository.resolveConflict(client, canonical ? { ...trusted, authContext: canonical } : trusted, decodeURIComponent(conflictMatch[1]), body.resolution));
        return json(200, result, requestId);
      }

      return problem(404, "not_found", "Not found", requestId);
    } catch (error) {
      if (error instanceof SyntaxError || error instanceof TypeError) return problem(400, "invalid_request", "Invalid request", requestId);
      if (error instanceof RangeError) return problem(413, "request_too_large", "Request too large", requestId);
      if (error?.code === "scope_revoked") return problem(409, "scope_revoked", "Scope was revoked", requestId, undefined, { purgeScope: error.scope });
      if (error?.code === "forbidden") return problem(403, "forbidden", "Forbidden", requestId);
      if (error?.code === "version_conflict") return problem(409, "version_conflict", "Version conflict", requestId, undefined, { currentVersion: error.currentVersion });
      if (error?.code === "idempotency_conflict") return problem(409, "idempotency_conflict", "Idempotency conflict", requestId);
      return problem(500, "request_failed", "Request failed", requestId);
    }
  };
}
