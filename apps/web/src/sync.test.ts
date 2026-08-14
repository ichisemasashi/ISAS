import { vi } from "vitest";
import type { MvpGateway } from "./api";
import { demoAuthorization } from "./auth";
import type { OutboxRecord, StorageGateway } from "./storage";
import { synchronize } from "./sync";

function fixture(status: "accepted" | "duplicate" | "rejected" | "conflict" = "accepted") {
  const outbox: OutboxRecord[] = [{ eventUuid: "event-1", bundleId: "bundle-1", kind: "journal", payload: {}, createdAt: "2026-08-14T00:00:00Z", occurredAt: "2026-08-14T00:00:00Z", tenantId: demoAuthorization.context.tenantId, authorizationSnapshotId: demoAuthorization.context.authorizationSnapshotId, membershipVersion: demoAuthorization.context.membershipVersion }];
  const quarantined: string[] = [];
  const storage: StorageGateway = {
    saveDraft: vi.fn(), enqueue: vi.fn(), pendingCount: vi.fn(async () => outbox.length), listOutbox: vi.fn(async () => [...outbox]),
    acknowledge: vi.fn(async (ids) => { for (const id of ids) { const index = outbox.findIndex((row) => row.eventUuid === id); if (index >= 0) outbox.splice(index, 1); } }),
    quarantine: vi.fn(async (records, queue) => { quarantined.push(queue); for (const record of records) { const index = outbox.findIndex((row) => row.eventUuid === record.eventUuid); if (index >= 0) outbox.splice(index, 1); } }),
    getCursor: vi.fn(async () => null), setCursor: vi.fn(), applyChanges: vi.fn(), purgeScope: vi.fn(), saveToday: vi.fn(), getToday: vi.fn(async () => []), saveServerQueues: vi.fn(), queueCounts: vi.fn(async () => ({ rejections: 0, conflicts: 0 })),
  };
  const api: MvpGateway = { getToday: vi.fn(), push: vi.fn(async () => ({ results: [{ bundleId: "bundle-1", status, rejection: status === "rejected" ? { reason: "authorization_changed", recoveryAction: "manager_review" } : undefined }] })), pull: vi.fn(async () => ({ changes: [], nextCursor: "0", hasMore: false })), getQueues: vi.fn(async () => ({ rejections: [], conflicts: [] })), resolveConflict: vi.fn() };
  return { api, storage, outbox, quarantined };
}

test("runs P0 pull before push and acknowledges only an accepted result", async () => {
  const fx = fixture(); const summary = await synchronize({ api: fx.api, storage: fx.storage, authorization: demoAuthorization, csrfToken: "csrf-1" });
  expect(summary.accepted).toBe(1); expect(fx.outbox).toHaveLength(0);
  expect(vi.mocked(fx.api.pull).mock.invocationCallOrder[0]).toBeLessThan(vi.mocked(fx.api.push).mock.invocationCallOrder[0]);
  expect(vi.mocked(fx.api.pull).mock.calls.map((call) => call[2])).toEqual(["priority", "normal"]);
});
test.each([["rejected", "rejections"], ["conflict", "conflicts"]] as const)("moves %s results to a visible queue", async (status, queue) => {
  const fx = fixture(status); await synchronize({ api: fx.api, storage: fx.storage, authorization: demoAuthorization, csrfToken: "csrf-1" });
  expect(fx.quarantined).toEqual([queue]); expect(fx.outbox).toHaveLength(0);
});
