const HTTPS = /^https:\/\/[^/]+$/;
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function convert(value, conversion) {
  if (!conversion) return value;
  if (!Number.isFinite(value) || !Number.isFinite(conversion.factor) || !Number.isFinite(conversion.offset ?? 0)) throw new TypeError("invalid unit conversion");
  return value * conversion.factor + (conversion.offset ?? 0);
}

export function createMachineryConnector({ registration, tokenProvider, store, fetcher = fetch, delay = sleep, clock = () => Date.now() }) {
  if (registration?.status !== "active" || !HTTPS.test(registration?.baseOrigin ?? "") || !registration?.tenantId || !registration?.connectorId) throw new TypeError("active HTTPS connector registration is required");
  const allowedOrigin = new URL(registration.baseOrigin).origin;
  return Object.freeze({
    async pull() {
      if (registration.consent?.status !== "granted" || Date.parse(registration.consent.expiresAt) <= clock()) throw new Error("connector tenant consent is absent or expired");
      let cursor = await store.getCursor(registration.tenantId, registration.connectorId);
      let imported = 0;
      try {
        for (;;) {
          const url = new URL("/observations", allowedOrigin);
          if (cursor) url.searchParams.set("cursor", cursor);
          if (url.origin !== allowedOrigin) throw new Error("connector origin escape rejected");
          let response;
          for (let attempt = 0; attempt < 3; attempt += 1) {
            const token = await tokenProvider.accessToken({ tenantId: registration.tenantId, connectorId: registration.connectorId, scopes: ["observations:read"] });
            response = await fetcher(url, { headers: { Authorization: `Bearer ${token}`, Accept: "application/json" }, signal: AbortSignal.timeout(15000) });
            if (![429, 500, 502, 503, 504].includes(response.status)) break;
            if (attempt < 2) await delay(Math.min(1000 * (2 ** attempt), 4000));
          }
          if ([401, 403].includes(response.status)) { await store.revoke({ tenantId: registration.tenantId, connectorId: registration.connectorId, reason: `provider_${response.status}` }); throw new Error("connector credential revoked"); }
          if (!response.ok) throw new Error(`provider unavailable: ${response.status}`);
          const body = await response.json();
          if (!Array.isArray(body.items) || (body.nextCursor != null && typeof body.nextCursor !== "string")) throw new TypeError("invalid provider response");
          const observations = body.items.map((item) => ({
            providerEventId: String(item.id), observedAt: new Date(item.observedAt).toISOString(), fieldExternalKey: String(item.fieldExternalKey),
            operation: String(item.operation), areaM2: convert(Number(item.area), registration.unitConversions?.area),
            sourceUnit: registration.unitConversions?.area?.source, targetUnit: registration.unitConversions?.area?.target,
            raw: item,
          }));
          const result = await store.commitPage({ tenantId: registration.tenantId, connectorId: registration.connectorId, inputCursor: cursor, nextCursor: body.nextCursor ?? null, observations });
          imported += result.inserted;
          await store.audit({ tenantId: registration.tenantId, connectorId: registration.connectorId, action: "connector.page.committed", inserted: result.inserted, duplicates: result.duplicates, cursor: body.nextCursor ?? null });
          cursor = body.nextCursor ?? null;
          if (!cursor) break;
        }
        return { status: "PASS", imported, cursor: null };
      } catch (error) {
        await store.audit({ tenantId: registration.tenantId, connectorId: registration.connectorId, action: "connector.pull.failed", reason: error.message, fileImportAvailable: true });
        return { status: "DEGRADED", imported, cursor, fileImportAvailable: true, reason: error.message };
      }
    },
  });
}
