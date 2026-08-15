import { render, screen, waitFor } from "@testing-library/react";
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
  async createMigrationJob() { throw new Error("not used"); }, async getMigrationJobs() { return { jobs: [] }; },
  async commitMigrationJob() { throw new Error("not used"); }, async exportCsv() { throw new Error("not used"); },
  async getSecurityAdministration() { return { users: [], roles: [], changeRequests: [], breakGlassGrants: [], privacyRequests: [] }; },
  async requestSecurityChange() { return { requestId: "request-1", status: "pending" }; }, async decideSecurityChange() { return {}; },
  async createPrivacyRequest() { return { requestId: "privacy-1", status: "submitted" }; }, async transitionPrivacyRequest() { return {}; },
  async getPesticideMasterReviews() { return { reviews: [] }; }, async requestPesticideMasterReview() { throw new Error("not used"); }, async decidePesticideMasterReview() { return {}; },
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

  test("removes the previous tenant view as soon as a new context is selected", async () => {
    const user = userEvent.setup();
    const auth = gateway({
      bootstrap: vi.fn().mockResolvedValue({
        user: { id: "user-1", displayName: "認証 利用者", initials: "認", authenticationLevel: "mfa" },
        tenants: [{ id: "tenant-1", name: "第一農園", roleLabel: "管理者" }, { id: "tenant-2", name: "第二農園", roleLabel: "管理者" }],
        csrfToken: "csrf-1", accessMode: "online",
      }),
      createContext: vi.fn()
        .mockResolvedValueOnce({ contextId: "ctx-1", tenantId: "tenant-1", tenantName: "第一農園", roleLabel: "管理者", membershipVersion: "m1", authorizationSnapshotId: "s1", capabilities: [], expiresAt: "2099-01-01T00:00:00Z" })
        .mockResolvedValueOnce({ contextId: "ctx-2", tenantId: "tenant-2", tenantName: "第二農園", roleLabel: "管理者", membershipVersion: "m2", authorizationSnapshotId: "s2", capabilities: [], expiresAt: "2099-01-01T00:00:00Z" }),
    });
    const tenantApi = { ...api, getWorkInstructions: vi.fn(async (contextId: string) => ({ instructions: contextId === "ctx-1" ? [{ id: "old-task", fieldId: "f1", fieldGroupId: "g1", fieldName: "第一農園だけの圃場", cropName: "米", title: "旧tenant限定作業", workType: "確認", details: "", scheduledStart: new Date().toISOString(), scheduledEnd: new Date().toISOString(), priority: 1, status: "issued" as const, version: 1, assignment: null }] : [] })) };
    render(<AuthBoundary gateway={auth} api={tenantApi} />);
    expect(await screen.findByText("旧tenant限定作業")).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText("表示する組織"), "tenant-2");
    await waitFor(() => expect(screen.getByLabelText("表示する組織")).toHaveValue("tenant-2"));
    expect(screen.queryByText("旧tenant限定作業")).not.toBeInTheDocument();
  });
});
