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
});
