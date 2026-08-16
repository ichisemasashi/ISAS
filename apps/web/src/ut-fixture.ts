import type { MvpGateway, PushResult, WorkInstruction } from "./api";
import { tr } from "./i18n";

const fieldId = "0198a6c0-0000-7000-8000-000000000101";
const fieldGroupId = "f1111111-1111-7111-8111-111111111111";
const now = new Date();
const at = (hour: number, minute = 0) => {
  const value = new Date(now);
  value.setHours(hour, minute, 0, 0);
  return value.toISOString();
};

const instruction: WorkInstruction = {
  id: "0198a6c0-0000-7000-8000-000000000201",
  fieldId,
  fieldGroupId,
  fieldName: tr("ut_fixture.l16.1"),
  cropName: tr("ut_fixture.l17.2"),
  title: tr("ut_fixture.l18.3"),
  workType: tr("ut_fixture.l19.4"),
  details: tr("ut_fixture.l20.5"),
  scheduledStart: at(8, 30),
  scheduledEnd: at(9, 30),
  priority: 1,
  status: "issued",
  version: 1,
  assignment: { id: "0198a6c0-0000-7000-8000-000000000202", assigneeUserId: "user-sato", version: 1 },
};

const field = {
  type: "Feature" as const,
  id: fieldId,
  geometry: { type: "MultiPolygon" as const, coordinates: [] },
  properties: { id: fieldId, fieldGroupId, name: tr("ut_fixture.l33.6"), cropName: tr("ut_fixture.l33.7"), status: "active" as const, areaSqm: 1840, version: 1 },
};

export const utGateway: MvpGateway = {
  async getToday() {
    return { tasks: [{ id: "task-safety", time: "14:00", field: field.properties.name, crop: tr("ut_fixture.l38.8"), work: tr("ut_fixture.l38.9"), status: "safety_check" }], serverTime: new Date().toISOString() };
  },
  async getFields() { return { type: "FeatureCollection", features: [field], nextCursor: null }; },
  async getOfflineMapPack() { throw new Error(tr("ut_fixture.l41.10")); },
  async getWorkInstructions() { return { instructions: [instruction] }; },
  async createWorkInstruction() { throw new Error(tr("ut_fixture.l43.11")); },
  async reassignWorkInstruction() { throw new Error(tr("ut_fixture.l44.12")); },
  async getJournalBootstrap(_contextId, search = {}) {
    return {
      instruction: search.instructionId ? { id: instruction.id, fieldId, fieldGroupId, fieldName: field.properties.name, workType: instruction.workType, details: instruction.details, scheduledStart: instruction.scheduledStart, scheduledEnd: instruction.scheduledEnd } : null,
      punchSuggestion: { startedAt: "08:12", endedAt: "09:36", warning: null },
      templates: [{ id: "template-water", name: tr("ut_fixture.l49.13"), workType: tr("ut_fixture.l49.14"), defaults: { memo: tr("ut_fixture.l49.15") }, version: 1 }],
      previous: { id: "previous-journal", fieldId, fieldGroupId, body: { field: field.properties.name, workType: tr("ut_fixture.l50.16"), memo: tr("ut_fixture.l50.17") }, version: 1, updatedAt: new Date().toISOString() },
    };
  },
  async getPesticideBootstrap() {
    return {
      field: { id: fieldId, fieldGroupId, name: field.properties.name, cropName: tr("ut_fixture.l55.18"), timezone: "Asia/Tokyo" },
      release: { id: "release-ut", version: "UT-2026.08", validUntil: "2099-12-31T00:00:00Z", publishedAt: "2026-08-14T00:00:00Z", syncedAt: new Date().toISOString() },
      chemicals: [
        { id: "chemical-safe", registrationNumber: "UT-001", name: tr("ut_fixture.l58.19"), activeIngredient: tr("ut_fixture.l58.20"), applicableCrops: [tr("ut_fixture.l58.21")], dilutionMin: 500, dilutionMax: 1500, maxUses: 3, preharvestDays: 7, revokedOn: null },
        { id: "chemical-warning", registrationNumber: "UT-002", name: tr("ut_fixture.l59.22"), activeIngredient: tr("ut_fixture.l59.23"), applicableCrops: [tr("ut_fixture.l59.24")], dilutionMin: 500, dilutionMax: 1500, maxUses: 1, preharvestDays: 7, revokedOn: null },
      ],
      usage: [{ chemicalId: "chemical-warning", usageCount: 1, lastAppliedOn: "2026-08-01" }],
      inventory: [{ chemicalId: "chemical-safe", quantity: 12, updatedAt: new Date().toISOString() }, { chemicalId: "chemical-warning", quantity: 4, updatedAt: new Date().toISOString() }],
    };
  },
  async getInventory() { return { balances: [], alerts: [] }; },
  async getJournals() { return { journals: [] }; },
  async reviewJournal() { throw new Error(tr("ut_fixture.l67.25")); },
  async uploadJournalAttachment(_contextId, _csrfToken, attachment) { return { id: attachment.id, journalId: attachment.journalId, byteSize: attachment.blob.size, sha256: "ut-fixture" }; },
  async push(_contextId, _csrfToken, bundles) {
    if (new URLSearchParams(window.location.search).get("sync") === "fail") throw new Error("UT fixture sync failure");
    return { results: bundles.map((bundle): PushResult => ({ bundleId: bundle.bundleId, status: "accepted", events: bundle.events.map((event) => ({ eventUuid: event.eventUuid, eventTs: new Date().toISOString() })) })) };
  },
  async pull(_contextId, _scope, _priority, cursor) { return { changes: [], nextCursor: cursor || "0", hasMore: false }; },
  async getQueues() { return { rejections: [], conflicts: [] }; },
  async resolveConflict() { return {}; },
  async createMigrationJob() { throw new Error(tr("ut_fixture.l76.26")); },
  async getMigrationJobs() { return { jobs: [] }; },
  async commitMigrationJob() { throw new Error(tr("ut_fixture.l78.27")); },
  async exportCsv() { throw new Error(tr("ut_fixture.l79.28")); },
  async getSecurityAdministration() { return { users: [], roles: [], changeRequests: [], breakGlassGrants: [], privacyRequests: [] }; },
  async reconcileAttachmentStorage() { return { scanned: 0, taggedOrphans: 0, finalized: 0, quarantined: 0 }; },
  async requestSecurityChange() { throw new Error(tr("ut_fixture.l82.29")); }, async decideSecurityChange() { return {}; },
  async createPrivacyRequest() { throw new Error(tr("ut_fixture.l83.30")); }, async transitionPrivacyRequest() { return {}; },
  async getPesticideMasterReviews() { return { reviews: [] }; }, async requestPesticideMasterReview() { throw new Error(tr("ut_fixture.l84.31")); }, async decidePesticideMasterReview() { return {}; },
};

export async function resetUtBrowserStorage(): Promise<void> {
  const { resetDeviceVaultForTesting } = await import("./device-security");
  await resetDeviceVaultForTesting();
  await new Promise<void>((resolve, reject) => {
    const request = indexedDB.deleteDatabase("isas-field-ops");
    request.onsuccess = () => resolve();
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error(tr("ut_fixture.l94.32")));
  });
}
