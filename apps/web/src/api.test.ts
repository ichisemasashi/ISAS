import { vi } from "vitest";
import { createMvpGateway } from "./api";

describe("MVP REST gateway", () => {
  test("sends opaque context and CSRF proof without a bearer token", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ results: [] }), { status: 200 }));
    await createMvpGateway(fetcher).push("context-1", "csrf-1", []);
    expect(fetcher).toHaveBeenCalledWith("/api/v1/sync/push", expect.objectContaining({ method: "POST", credentials: "include", headers: expect.objectContaining({ "X-ISAS-Context": "context-1", "X-CSRF-Token": "csrf-1" }) }));
    expect(fetcher.mock.calls[0]?.[1]?.headers).not.toHaveProperty("Authorization");
  });
  test("keeps scope and priority cursors independent", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ changes: [], nextCursor: "7", hasMore: false }), { status: 200 }));
    await createMvpGateway(fetcher).pull("context-1", "field-group-1", "priority", "6");
    expect(fetcher.mock.calls[0]?.[0]).toBe("/api/v1/sync/pull?scope=field-group-1&priority=priority&cursor=6");
  });
  test("encodes PostGIS bbox and search terms for field search", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ type: "FeatureCollection", features: [], nextCursor: null }), { status: 200 }));
    await createMvpGateway(fetcher).getFields("context-1", { bbox: [140.2, 38.1, 140.5, 38.4], query: "北 圃場", limit: 200 });
    expect(fetcher.mock.calls[0]?.[0]).toBe("/api/v1/fields?bbox=140.2%2C38.1%2C140.5%2C38.4&q=%E5%8C%97+%E5%9C%83%E5%A0%B4&limit=200");
  });
  test("sends migration idempotency and reads an attachment file name", async () => {
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: "job-1" }), { status: 201, headers: { "Content-Type": "application/json" } }))
      .mockResolvedValueOnce(new Response("\uFEFF圃場コード,圃場名\nF-1,北圃場", { status: 200, headers: { "Content-Type": "text/csv", "Content-Disposition": 'attachment; filename="fields-20260814.csv"' } }));
    const gateway = createMvpGateway(fetcher);
    await gateway.createMigrationJob("context-1", "csrf-1", { dataset: "fields", sourceName: "fields.csv", csv: "code\nF-1", mapping: { externalKey: "code" } }, "migration-1");
    expect(fetcher.mock.calls[0]?.[1]?.headers).toEqual(expect.objectContaining({ "Idempotency-Key": "migration-1", "X-CSRF-Token": "csrf-1" }));
    const exported = await gateway.exportCsv("context-1", "fields");
    expect(exported.fileName).toBe("fields-20260814.csv");
    expect(await exported.blob.text()).toContain("北圃場");
  });
  test("uses the step-up protected security administration routes", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ requestId: "request-1", status: "pending" }), { status: 201, headers: { "Content-Type": "application/json" } }));
    await createMvpGateway(fetcher).requestSecurityChange("context-1", "csrf-1", { changeType: "user_revoke" });
    expect(fetcher).toHaveBeenCalledWith("/api/v1/security-admin/change-requests", expect.objectContaining({ method: "POST", headers: expect.objectContaining({ "X-CSRF-Token": "csrf-1", "X-ISAS-Context": "context-1" }) }));
  });

  test("reconciles private attachment storage with CSRF and AuthContext", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ scanned: 3, taggedOrphans: 1, finalized: 1, quarantined: 0 }), { status: 200, headers: { "Content-Type": "application/json" } }));
    const api = createMvpGateway(fetcher);
    await api.reconcileAttachmentStorage("context-1", "csrf-1");
    expect(fetcher).toHaveBeenCalledWith("/api/v1/security-admin/attachment-storage/reconcile", expect.objectContaining({ method: "POST", headers: expect.objectContaining({ "X-CSRF-Token": "csrf-1", "X-ISAS-Context": "context-1" }) }));
  });
});
