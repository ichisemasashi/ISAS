import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import { AuthBoundary } from "./AuthBoundary";
import type { MvpGateway } from "./api";
import type { AuthGateway } from "./auth";

function gateway(overrides: Partial<AuthGateway> = {}): AuthGateway {
  return {
    bootstrap: vi.fn().mockResolvedValue(null),
    createContext: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
    ...overrides,
  };
}

const api: MvpGateway = {
  async getToday() { return { tasks: [], serverTime: new Date().toISOString() }; },
  async getFields() { return { type: "FeatureCollection", features: [], nextCursor: null }; },
  async getWorkInstructions() { return { instructions: [] }; },
  async createWorkInstruction() { throw new Error("not used"); },
  async reassignWorkInstruction() { throw new Error("not used"); },
  async getJournalBootstrap() { return { instruction: null, punchSuggestion: { startedAt: null, endedAt: null, warning: "missing_start" }, templates: [], previous: null }; },
  async getPesticideBootstrap() { throw new Error("not used"); },
  async getInventory() { return { balances: [], alerts: [] }; },
  async getJournals() { return { journals: [] }; },
  async reviewJournal() { throw new Error("not used"); },
  async uploadJournalAttachment() { throw new Error("not used"); },
  async push() { return { results: [] }; }, async pull() { return { changes: [], nextCursor: "0", hasMore: false }; },
  async getQueues() { return { rejections: [], conflicts: [] }; }, async resolveConflict() { return {}; },
};

describe("AuthBoundary", () => {
  test("offers BFF login when the session is anonymous", async () => {
    const user = userEvent.setup();
    const auth = gateway();
    render(<AuthBoundary gateway={auth} api={api} />);

    await user.click(await screen.findByRole("button", { name: "ログインする" }));

    expect(auth.login).toHaveBeenCalledWith(window.location.href);
  });

  test("derives a tenant context before rendering the application", async () => {
    const auth = gateway({
      bootstrap: vi.fn().mockResolvedValue({
        user: { id: "user-1", displayName: "認証 利用者", initials: "認", authenticationLevel: "mfa" },
        tenants: [{ id: "tenant-1", name: "認証農園", roleLabel: "管理者" }],
        csrfToken: "csrf-1",
        accessMode: "online",
      }),
      createContext: vi.fn().mockResolvedValue({
        contextId: "ctx-1",
        tenantId: "tenant-1",
        tenantName: "認証農園",
        roleLabel: "管理者",
        membershipVersion: "membership-1",
        authorizationSnapshotId: "snapshot-1",
        capabilities: ["instruction:manage", "journal:review"],
        expiresAt: "2026-08-14T12:00:00+09:00",
      }),
    });

    render(<AuthBoundary gateway={auth} api={api} />);

    expect(await screen.findByRole("heading", { name: /認証 利用者さん/ })).toBeInTheDocument();
    expect(auth.createContext).toHaveBeenCalledWith("tenant-1", "csrf-1", expect.any(AbortSignal));
    expect(screen.getByText("認証農園")).toBeInTheDocument();
  });
});
