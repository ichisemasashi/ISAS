import { vi } from "vitest";
import { createBffAuthGateway } from "./auth";

const bootstrap = {
  user: { id: "user-1", displayName: "佐藤 一郎", initials: "佐", authenticationLevel: "phishing-resistant" },
  tenants: [{ id: "tenant-1", name: "山形みどり農園", roleLabel: "現場チーム" }],
  csrfToken: "csrf-1",
  accessMode: "online",
};

const context = {
  contextId: "ctx-1",
  tenantId: "tenant-1",
  tenantName: "山形みどり農園",
  roleLabel: "現場チーム",
  membershipVersion: "membership-1",
  authorizationSnapshotId: "snapshot-1",
  expiresAt: "2026-08-14T12:00:00+09:00",
};

describe("BFF authentication gateway", () => {
  test("uses an HttpOnly-cookie session without sending a browser bearer token", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(bootstrap), { status: 200 }));
    const gateway = createBffAuthGateway(fetcher);

    await expect(gateway.bootstrap()).resolves.toMatchObject({ accessMode: "online" });
    expect(fetcher).toHaveBeenCalledWith("/api/bff/session", expect.objectContaining({
      method: "GET",
      credentials: "include",
      cache: "no-store",
    }));
    expect(fetcher.mock.calls[0]?.[1]?.headers).not.toHaveProperty("Authorization");
  });

  test("creates a tab context with CSRF protection and tenant selection", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(context), { status: 200 }));
    const gateway = createBffAuthGateway(fetcher);

    await expect(gateway.createContext("tenant-1", "csrf-1")).resolves.toEqual(context);
    expect(fetcher).toHaveBeenCalledWith("/api/bff/contexts", expect.objectContaining({
      method: "POST",
      credentials: "include",
      body: JSON.stringify({ tenantId: "tenant-1" }),
      headers: expect.objectContaining({ "X-CSRF-Token": "csrf-1" }),
    }));
  });

  test("does not forward an external login return URL", () => {
    const assign = vi.fn();
    const gateway = createBffAuthGateway(vi.fn<typeof fetch>(), { origin: "https://isas.example", assign });

    gateway.login("https://attacker.example/steal");

    expect(assign).toHaveBeenCalledWith("/api/bff/login?return_to=%2F");
  });

  test("logs out through a same-origin JSON request with CSRF proof", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 204 }));
    const gateway = createBffAuthGateway(fetcher);

    await gateway.logout("csrf-1");

    expect(fetcher).toHaveBeenCalledWith("/api/bff/logout", expect.objectContaining({
      method: "POST",
      credentials: "include",
      body: "{}",
      headers: { "Content-Type": "application/json", "X-CSRF-Token": "csrf-1" },
    }));
  });
});
