import type { MvpGateway, PushResult, WorkInstruction } from "./api";

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
  fieldName: "練習用・北の1号圃場",
  cropName: "つや姫",
  title: "水位と取水口を確認",
  workType: "水管理",
  details: "水位が目印の線にあるか確認する",
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
  properties: { id: fieldId, fieldGroupId, name: "練習用・北の1号圃場", cropName: "つや姫", status: "active" as const, areaSqm: 1840, version: 1 },
};

export const utGateway: MvpGateway = {
  async getToday() {
    return { tasks: [{ id: "task-safety", time: "14:00", field: field.properties.name, crop: "つや姫", work: "除草剤散布（練習）", status: "safety_check" }], serverTime: new Date().toISOString() };
  },
  async getFields() { return { type: "FeatureCollection", features: [field], nextCursor: null }; },
  async getWorkInstructions() { return { instructions: [instruction] }; },
  async createWorkInstruction() { throw new Error("UT fixtureでは管理者操作を行いません"); },
  async reassignWorkInstruction() { throw new Error("UT fixtureでは管理者操作を行いません"); },
  async getJournalBootstrap(_contextId, search = {}) {
    return {
      instruction: search.instructionId ? { id: instruction.id, fieldId, fieldGroupId, fieldName: field.properties.name, workType: instruction.workType, details: instruction.details, scheduledStart: instruction.scheduledStart, scheduledEnd: instruction.scheduledEnd } : null,
      punchSuggestion: { startedAt: "08:12", endedAt: "09:36", warning: null },
      templates: [{ id: "template-water", name: "水管理", workType: "水管理", defaults: { memo: "水位は目印の範囲内。取水口を確認。" }, version: 1 }],
      previous: { id: "previous-journal", fieldId, fieldGroupId, body: { field: field.properties.name, workType: "水管理", memo: "水位は目印の範囲内。" }, version: 1, updatedAt: new Date().toISOString() },
    };
  },
  async getPesticideBootstrap() {
    return {
      field: { id: fieldId, fieldGroupId, name: field.properties.name, cropName: "つや姫", timezone: "Asia/Tokyo" },
      release: { id: "release-ut", version: "UT-2026.08", validUntil: "2099-12-31T00:00:00Z", publishedAt: "2026-08-14T00:00:00Z", syncedAt: new Date().toISOString() },
      chemicals: [
        { id: "chemical-safe", registrationNumber: "UT-001", name: "練習用フロアブル（基準内）", activeIngredient: "訓練成分A", applicableCrops: ["つや姫"], dilutionMin: 500, dilutionMax: 1500, maxUses: 3, preharvestDays: 7, revokedOn: null },
        { id: "chemical-warning", registrationNumber: "UT-002", name: "練習用乳剤（使用上限）", activeIngredient: "訓練成分B", applicableCrops: ["つや姫"], dilutionMin: 500, dilutionMax: 1500, maxUses: 1, preharvestDays: 7, revokedOn: null },
      ],
      usage: [{ chemicalId: "chemical-warning", usageCount: 1, lastAppliedOn: "2026-08-01" }],
      inventory: [{ chemicalId: "chemical-safe", quantity: 12, updatedAt: new Date().toISOString() }, { chemicalId: "chemical-warning", quantity: 4, updatedAt: new Date().toISOString() }],
    };
  },
  async getInventory() { return { balances: [], alerts: [] }; },
  async getJournals() { return { journals: [] }; },
  async reviewJournal() { throw new Error("UT fixtureではレビューを行いません"); },
  async uploadJournalAttachment(_contextId, _csrfToken, attachment) { return { id: attachment.id, journalId: attachment.journalId, byteSize: attachment.blob.size, sha256: "ut-fixture" }; },
  async push(_contextId, _csrfToken, bundles) {
    return { results: bundles.map((bundle): PushResult => ({ bundleId: bundle.bundleId, status: "accepted", events: bundle.events.map((event) => ({ eventUuid: event.eventUuid, eventTs: new Date().toISOString() })) })) };
  },
  async pull(_contextId, _scope, _priority, cursor) { return { changes: [], nextCursor: cursor || "0", hasMore: false }; },
  async getQueues() { return { rejections: [], conflicts: [] }; },
  async resolveConflict() { return {}; },
  async createMigrationJob() { throw new Error("UT fixtureではデータ移行を行いません"); },
  async getMigrationJobs() { return { jobs: [] }; },
  async commitMigrationJob() { throw new Error("UT fixtureではデータ移行を行いません"); },
  async exportCsv() { throw new Error("UT fixtureではCSV出力を行いません"); },
};

export async function resetUtBrowserStorage(): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    const request = indexedDB.deleteDatabase("isas-field-ops");
    request.onsuccess = () => resolve();
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error("別タブのISASを閉じてからリセットしてください"));
  });
}
