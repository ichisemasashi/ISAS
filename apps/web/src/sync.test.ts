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
    saveAttachment: vi.fn(), markAttachmentsReady: vi.fn(), listReadyAttachments: vi.fn(async () => []), acknowledgeAttachment: vi.fn(),
    acknowledge: vi.fn(async (ids) => { for (const id of ids) { const index = outbox.findIndex((row) => row.eventUuid === id); if (index >= 0) outbox.splice(index, 1); } }),
    quarantine: vi.fn(async (records, queue) => { quarantined.push(queue); for (const record of records) { const index = outbox.findIndex((row) => row.eventUuid === record.eventUuid); if (index >= 0) outbox.splice(index, 1); } }),
    getCursor: vi.fn(async () => null), setCursor: vi.fn(), applyChanges: vi.fn(), purgeScope: vi.fn(), saveToday: vi.fn(), getToday: vi.fn(async () => []), saveJournalBootstrap: vi.fn(), getJournalBootstrap: vi.fn(async () => null), saveFields: vi.fn(), getFields: vi.fn(async () => []), savePesticideBootstrap: vi.fn(), getPesticideBootstrap: vi.fn(async () => null), saveInventory: vi.fn(), getInventory: vi.fn(async () => null), saveServerQueues: vi.fn(), queueCounts: vi.fn(async () => ({ rejections: 0, conflicts: 0 })),
    beginOfflineMapPack: vi.fn(), saveOfflineMapTiles: vi.fn(), completeOfflineMapPack: vi.fn(), getLatestOfflineMapPack: vi.fn(async () => null), getOfflineMapTile: vi.fn(async () => null), removeOfflineMapPack: vi.fn(), reserveOfflineMapCapacity: vi.fn(),
  };
  const api: MvpGateway = { getToday: vi.fn(), getFields: vi.fn(), getOfflineMapPack: vi.fn(), getWorkInstructions: vi.fn(async () => ({ instructions: [] })), createWorkInstruction: vi.fn(), reassignWorkInstruction: vi.fn(), getJournalBootstrap: vi.fn(), getPesticideBootstrap: vi.fn(), getInventory: vi.fn(), getJournals: vi.fn(async () => ({ journals: [] })), reviewJournal: vi.fn(), uploadJournalAttachment: vi.fn(), push: vi.fn(async () => ({ results: [{ bundleId: "bundle-1", status, rejection: status === "rejected" ? { reason: "authorization_changed", recoveryAction: "manager_review" } : undefined }] })), pull: vi.fn(async () => ({ changes: [], nextCursor: "0", hasMore: false })), getQueues: vi.fn(async () => ({ rejections: [], conflicts: [] })), resolveConflict: vi.fn(), createMigrationJob: vi.fn(), getMigrationJobs: vi.fn(async () => ({ jobs: [] })), commitMigrationJob: vi.fn(), exportCsv: vi.fn(), getSecurityAdministration: vi.fn(), requestSecurityChange: vi.fn(), decideSecurityChange: vi.fn(), createPrivacyRequest: vi.fn(), transitionPrivacyRequest: vi.fn(), getPesticideMasterReviews: vi.fn(), requestPesticideMasterReview: vi.fn(), decidePesticideMasterReview: vi.fn() };
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

test("marks journal photos ready after journal acceptance before uploading them", async () => {
  const fx = fixture();
  const attachment = { id: "photo-1", tenantId: demoAuthorization.context.tenantId, journalId: "journal-1", fileName: "field.jpg", capturedAt: "2026-08-14T00:00:00Z", blob: new Blob(["image"], { type: "image/jpeg" }), ready: true };
  vi.mocked(fx.storage.listReadyAttachments).mockResolvedValue([attachment]);
  fx.outbox[0].payload = { aggregateId: "journal-1" };
  await synchronize({ api: fx.api, storage: fx.storage, authorization: demoAuthorization, csrfToken: "csrf-1" });
  expect(fx.storage.markAttachmentsReady).toHaveBeenCalledWith(["journal-1"]);
  expect(fx.api.uploadJournalAttachment).toHaveBeenCalledWith(demoAuthorization.context.contextId, "csrf-1", attachment, undefined);
  expect(fx.storage.acknowledgeAttachment).toHaveBeenCalledWith("photo-1");
});

test("purges the server-declared field scope after revocation", async () => {
  const fx = fixture();
  vi.mocked(fx.api.pull).mockRejectedValueOnce(new (await import("./api")).ApiProblem(409, "scope_revoked", { purgeScope: "field-group-revoked" }));
  const summary = await synchronize({ api: fx.api, storage: fx.storage, authorization: demoAuthorization, csrfToken: "csrf-1" });
  expect(summary.reauthenticationRequired).toBe(true);
  expect(fx.storage.purgeScope).toHaveBeenCalledWith(demoAuthorization.context.tenantId, "field-group-revoked");
});
