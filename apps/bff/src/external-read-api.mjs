const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function problem(status, title) {
  return new Response(JSON.stringify({ status, title }), { status, headers: { "Content-Type": "application/problem+json", "Cache-Control": "no-store" } });
}

export function createExternalReadApi({ origin, resolveServiceIdentity, repository, audit, clock = () => Date.now() }) {
  if (!origin || new URL(origin).origin !== origin) throw new TypeError("exact origin is required");
  return async function handle(request) {
    const url = new URL(request.url);
    if (url.origin !== origin || !url.pathname.startsWith("/api/external/v1/")) return problem(404, "Not found");
    if (request.method !== "GET") return problem(405, "Read-only API");
    const service = await resolveServiceIdentity(request);
    if (!service || service.status !== "active" || !UUID.test(service.tenantId ?? "") || !service.subject || !service.clientId) return problem(401, "Service authentication required");
    if (Date.parse(service.expiresAt) <= clock() || service.revokedAt) return problem(401, "Service identity expired or revoked");
    if (service.consent?.status !== "granted" || Date.parse(service.consent.expiresAt) <= clock()) return problem(403, "Tenant consent required");
    if (!service.scopes?.includes("external:fields:read")) return problem(403, "Scope denied");
    if (url.pathname !== "/api/external/v1/fields") return problem(404, "Not found");
    const limit = Number(url.searchParams.get("limit") || 100);
    const cursor = url.searchParams.get("cursor");
    if (!Number.isInteger(limit) || limit < 1 || limit > 500 || (cursor && !UUID.test(cursor))) return problem(400, "Invalid pagination");
    const result = await repository.listFieldsForExternalService({ tenantId: service.tenantId, clientId: service.clientId, limit, cursor });
    await audit.record({ tenantId: service.tenantId, actorType: "service", actor: `${service.subject}:${service.clientId}`, action: "external.fields.read", recordCount: result.items.length, occurredAt: new Date(clock()).toISOString() });
    return new Response(JSON.stringify(result), { status: 200, headers: { "Content-Type": "application/json", "Cache-Control": "private, no-store", "X-Content-Type-Options": "nosniff" } });
  };
}
