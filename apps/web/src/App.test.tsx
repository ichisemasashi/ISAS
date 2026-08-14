import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import { App } from "./App";
import type { MvpGateway } from "./api";
import { demoAuthorization } from "./auth";
import type { JournalDraft, OutboxRecord, StorageGateway } from "./storage";

function memoryStorage() {
  const drafts: JournalDraft[] = [];
  const outbox: OutboxRecord[] = [];
  const attachments: Array<{ id: string; journalId: string; fileName: string }> = [];
  let pesticideCache: Awaited<ReturnType<MvpGateway["getPesticideBootstrap"]>> | null = null;
  let inventoryCache: Awaited<ReturnType<MvpGateway["getInventory"]>> | null = null;
  const gateway: StorageGateway = {
    async saveDraft(draft) { drafts.push(draft); },
    async enqueue(record) { outbox.push(record); },
    async saveAttachment(record) { attachments.push({ id: record.id, journalId: record.journalId, fileName: record.fileName }); },
    async markAttachmentsReady() {},
    async listReadyAttachments() { return []; },
    async acknowledgeAttachment() {},
    async pendingCount() { return outbox.length; },
    async listOutbox() { return [...outbox]; },
    async acknowledge(ids) { for (const id of ids) { const index = outbox.findIndex((row) => row.eventUuid === id); if (index >= 0) outbox.splice(index, 1); } },
    async quarantine() {},
    async getCursor() { return null; },
    async setCursor() {},
    async applyChanges() {},
    async purgeScope() {},
    async saveToday() {},
    async getToday() { return []; },
    async saveJournalBootstrap() {},
    async getJournalBootstrap() { return null; },
    async saveFields() {},
    async getFields() { return [{ type: "Feature", id: "0198a6c0-0000-7000-8000-000000000101", geometry: { type: "MultiPolygon", coordinates: [] }, properties: { id: "0198a6c0-0000-7000-8000-000000000101", fieldGroupId: "f1111111-1111-7111-8111-111111111111", name: "南の3号圃場", cropName: "雪若丸", status: "active", areaSqm: 1000, version: 1 } }]; },
    async savePesticideBootstrap(_tenantId, value) { pesticideCache = value; },
    async getPesticideBootstrap() { return pesticideCache; },
    async saveInventory(_tenantId, value) { inventoryCache = value; },
    async getInventory() { return inventoryCache; },
    async saveServerQueues() {},
    async queueCounts() { return { rejections: 0, conflicts: 0 }; },
  };
  return { gateway, drafts, outbox, attachments };
}

const tasks = [
  { id: "t1", time: "08:30", field: "北の1号圃場", crop: "つや姫", work: "水位を確認", status: "next" as const },
  { id: "t2", time: "10:00", field: "西のハウス", crop: "ミニトマト", work: "誘引・わき芽取り", status: "today" as const },
  { id: "t3", time: "14:00", field: "南の3号圃場", crop: "雪若丸", work: "除草剤散布", status: "safety_check" as const },
];
const api: MvpGateway = {
  async getToday() { return { tasks, serverTime: new Date().toISOString() }; },
  async getFields() { return { type: "FeatureCollection", features: [{ type: "Feature", id: "0198a6c0-0000-7000-8000-000000000101", geometry: { type: "MultiPolygon", coordinates: [] }, properties: { id: "0198a6c0-0000-7000-8000-000000000101", fieldGroupId: "f1111111-1111-7111-8111-111111111111", name: "南の3号圃場", cropName: "雪若丸", status: "active", areaSqm: 1000, version: 1 } }], nextCursor: null }; },
  async getWorkInstructions() { return { instructions: [] }; },
  async createWorkInstruction() { throw new Error("not used"); },
  async reassignWorkInstruction() { throw new Error("not used"); },
  async getJournalBootstrap() { return { instruction: null, punchSuggestion: { startedAt: "08:12", endedAt: "09:36", warning: null }, templates: [{ id: "template-1", name: "水管理", workType: "水管理", defaults: {}, version: 1 }], previous: { id: "previous-1", body: { field: "北の1号圃場", workType: "水管理" }, version: 1, updatedAt: new Date().toISOString() } }; },
  async getPesticideBootstrap(_contextId, fieldId) { return { field: { id: fieldId, fieldGroupId: "f1111111-1111-7111-8111-111111111111", name: "南の3号圃場", cropName: "雪若丸", timezone: "Asia/Tokyo" }, release: { id: "release-1", version: "2026.08.14-1", validUntil: "2099-08-21T00:00:00Z", publishedAt: "2026-08-14T00:00:00Z", syncedAt: new Date().toISOString() }, chemicals: [
    { id: "chemical-safe", registrationNumber: "1", name: "グリーンフロアブル", activeIngredient: "A", applicableCrops: ["雪若丸"], dilutionMin: 500, dilutionMax: 1500, maxUses: 3, preharvestDays: 0, revokedOn: null },
    { id: "chemical-warning", registrationNumber: "2", name: "テスト乳剤（要確認）", activeIngredient: "B", applicableCrops: ["雪若丸"], dilutionMin: 500, dilutionMax: 1500, maxUses: 1, preharvestDays: 0, revokedOn: null },
  ], usage: [{ chemicalId: "chemical-warning", usageCount: 1, lastAppliedOn: "2026-08-01" }], inventory: [] }; },
  async getInventory() { return { balances: [], alerts: [] }; },
  async getJournals() { return { journals: [] }; },
  async reviewJournal() { throw new Error("not used"); },
  async uploadJournalAttachment() { throw new Error("not used"); },
  async push() { throw new Error("offline test transport"); },
  async pull() { return { changes: [], nextCursor: "0", hasMore: false }; },
  async getQueues() { return { rejections: [], conflicts: [] }; },
  async resolveConflict() { return {}; },
  async createMigrationJob() { throw new Error("not used"); },
  async getMigrationJobs() { return { jobs: [] }; },
  async commitMigrationJob() { throw new Error("not used"); },
  async exportCsv() { throw new Error("not used"); },
};
const renderApp = (storage: StorageGateway, authorization = demoAuthorization, gateway = api) => render(<App api={gateway} csrfToken="csrf-1" storage={storage} authorization={authorization} />);

describe("ISAS MVP field flow", () => {
  test("shows today's work and persistent synchronization state", async () => {
    const store = memoryStorage();
    renderApp(store.gateway);
    expect(screen.getByRole("heading", { name: /おはようございます/ })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "今日の作業" })).toBeInTheDocument();
    expect(await screen.findByText("水位を確認")).toBeInTheDocument();
    expect(screen.getByText("未同期 0件")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "作業を始める" })).toBeEnabled();
  });

  test("queues a punch and a journal without requiring a network response", async () => {
    const user = userEvent.setup();
    const store = memoryStorage();
    renderApp(store.gateway);
    await user.click(screen.getByRole("button", { name: "作業を始める" }));
    await waitFor(() => expect(store.outbox).toHaveLength(1));
    expect(screen.getByRole("heading", { name: "作業中です" })).toBeInTheDocument();
    expect(store.outbox[0]).toMatchObject({
      tenantId: demoAuthorization.context.tenantId,
      authorizationSnapshotId: demoAuthorization.context.authorizationSnapshotId,
      membershipVersion: demoAuthorization.context.membershipVersion,
    });

    await user.click(screen.getAllByRole("button", { name: "記録する" })[0]);
    expect(screen.getByRole("heading", { name: "作業日誌をつける" })).toBeInTheDocument();
    await user.type(screen.getByLabelText("作業メモ"), "水位は適正。取水口を確認。 ");
    await user.click(screen.getByRole("button", { name: "下書き保存" }));
    await waitFor(() => expect(store.drafts.length).toBeGreaterThan(0));
    await user.click(screen.getByRole("button", { name: "この内容で記録" }));
    await waitFor(() => expect(store.outbox).toHaveLength(2));
  });

  test("requires explicit acknowledgement when the safety cache reports a warning", async () => {
    const user = userEvent.setup();
    const store = memoryStorage();
    renderApp(store.gateway);
    await user.click(screen.getByRole("button", { name: "農薬記録を始める" }));
    await user.selectOptions(await screen.findByLabelText("薬剤名"), "chemical-warning");
    expect(screen.getByRole("alert")).toHaveTextContent("使用回数が上限を超えます");
    const submit = screen.getByRole("button", { name: "安全確認して記録" });
    expect(submit).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: "警告内容と使用履歴を確認しました" }));
    expect(submit).toBeEnabled();
    await user.click(submit);
    await waitFor(() => expect(store.outbox[0]?.kind).toBe("pesticide"));
  });

  test("keeps a journal photo on the device and links it to the journal event", async () => {
    const user = userEvent.setup();
    const store = memoryStorage();
    renderApp(store.gateway);
    await user.click((await screen.findAllByRole("button", { name: "記録する" }))[0]);
    await user.upload(screen.getByLabelText("写真を追加"), new File([new Uint8Array([1, 2, 3])], "水位.jpg", { type: "image/jpeg" }));
    await waitFor(() => expect(store.attachments).toHaveLength(1));
    expect(screen.getByText("水位.jpg")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "この内容で記録" }));
    await waitFor(() => expect(store.outbox[0]?.payload.attachmentIds).toEqual([store.attachments[0].id]));
  });

  test("keeps drafts readable but blocks new outbox records after offline write grace", async () => {
    const user = userEvent.setup();
    const store = memoryStorage();
    renderApp(store.gateway, { ...demoAuthorization, accessMode: "offline-read" });

    expect(screen.getByText("読取専用へ移行しました")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "作業を始める" }));

    expect(await screen.findByText(/再認証するまで新しい記録は確定できません/)).toBeInTheDocument();
    expect(store.outbox).toHaveLength(0);
    expect(screen.getByRole("heading", { name: "まだ作業を開始していません" })).toBeInTheDocument();
  });

  test("shows online manager controls for issuing and reassigning work", async () => {
    const user = userEvent.setup();
    const store = memoryStorage();
    const manager = { ...demoAuthorization, context: { ...demoAuthorization.context, capabilities: [...demoAuthorization.context.capabilities, "instruction:manage", "journal:review"] } };
    const createWorkInstruction = vi.fn(async () => ({ id: "instruction-1", fieldId: "0198a6c0-0000-7000-8000-000000000101", fieldGroupId: "f1111111-1111-7111-8111-111111111111", fieldName: "北圃場", cropName: "つや姫", title: "水位確認", workType: "水管理", details: "", scheduledStart: "2026-08-14T00:00:00Z", scheduledEnd: "2026-08-14T01:00:00Z", priority: 1, status: "issued" as const, version: 1, assignment: { id: "assignment-1", assigneeUserId: "22222222-2222-7222-8222-222222222222", version: 1 } }));
    const gateway = { ...api, createWorkInstruction };
    renderApp(store.gateway, manager, gateway);
    await user.click(screen.getAllByRole("button", { name: "その他" })[0]);
    await user.type(screen.getByLabelText("圃場ID"), "0198a6c0-0000-7000-8000-000000000101");
    await user.type(screen.getByLabelText("担当者ID"), "22222222-2222-7222-8222-222222222222");
    await user.type(screen.getByLabelText("指示名"), "水位確認");
    await user.type(screen.getByLabelText("作業種別"), "水管理");
    await user.type(screen.getByLabelText("開始予定"), "2026-08-14T08:00");
    await user.type(screen.getByLabelText("終了予定"), "2026-08-14T09:00");
    await user.click(screen.getByRole("button", { name: "オンラインで発行" }));
    await waitFor(() => expect(createWorkInstruction).toHaveBeenCalled());
  });

  test("shows the same work instructions in the timeline and opens their mobile-list journal flow", async () => {
    const user = userEvent.setup();
    const store = memoryStorage();
    const instructions = [{ id: "instruction-schedule-1", fieldId: "0198a6c0-0000-7000-8000-000000000101", fieldGroupId: "f1111111-1111-7111-8111-111111111111", fieldName: "北圃場", cropName: "つや姫", title: "水位確認", workType: "水管理", details: "取水口を確認", scheduledStart: "2026-08-14T00:00:00Z", scheduledEnd: "2026-08-16T03:00:00Z", priority: 1, status: "issued" as const, version: 1, assignment: { id: "assignment-1", assigneeUserId: "22222222-2222-7222-8222-222222222222", version: 1 } }];
    renderApp(store.gateway, demoAuthorization, { ...api, async getWorkInstructions() { return { instructions }; } });
    expect(await screen.findByText("水位確認")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "すべて見る" }));
    expect(screen.getByRole("heading", { name: "作業予定" })).toBeInTheDocument();
    const bar = screen.getByRole("button", { name: /水位確認、.*から/ });
    await user.click(bar);
    expect(screen.getByText("予定開始")).toBeInTheDocument();
    expect(screen.getByText("予定終了")).toBeInTheDocument();
    expect(screen.getByText("22222222-2222-7222-8222-222222222222")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "この作業の日誌をつける" }));
    expect(screen.getByRole("heading", { name: "作業日誌をつける" })).toBeInTheDocument();
  });

  test("maps CSV columns, reports duplicates and commits a validated import", async () => {
    const user = userEvent.setup();
    const store = memoryStorage();
    const manager = { ...demoAuthorization, context: { ...demoAuthorization.context, capabilities: [...demoAuthorization.context.capabilities, "migration:manage"] } };
    const staged = { id: "job-1", dataset: "fields" as const, sourceName: "fields.csv", sourceSha256: "a".repeat(64), mapping: { externalKey: "code", name: "name", fieldGroupId: "group", geometryWkt: "wkt" }, status: "validated" as const, rowCount: 2, validCount: 1, duplicateCount: 1, errorCount: 0, version: 1, createdAt: new Date().toISOString(), committedAt: null, rows: [{ lineNumber: 3, status: "duplicate" as const, duplicateKey: "F-001", errors: [], normalized: {} }] };
    const createMigrationJob = vi.fn(async () => staged);
    const commitMigrationJob = vi.fn(async () => ({ ...staged, status: "committed" as const, version: 3, committedAt: new Date().toISOString() }));
    renderApp(store.gateway, manager, { ...api, createMigrationJob, commitMigrationJob });
    await user.click(screen.getAllByRole("button", { name: "その他" })[0]);
    await user.upload(screen.getByLabelText("CSVファイル"), new File(["code,name,group,wkt\nF-001,北圃場,g-1,POLYGON()"], "fields.csv", { type: "text/csv" }));
    await user.selectOptions(screen.getByLabelText("圃場コード（必須）"), "code");
    await user.selectOptions(screen.getByLabelText("圃場名（必須）"), "name");
    await user.selectOptions(screen.getByLabelText("圃場グループID（必須）"), "group");
    await user.selectOptions(screen.getByLabelText("区画（WKT）（必須）"), "wkt");
    await user.click(screen.getByRole("button", { name: "重複と入力内容を検査" }));
    await waitFor(() => expect(createMigrationJob).toHaveBeenCalled());
    expect(screen.getByText(/重複（F-001）/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "検査済み1件の取込を確定" }));
    await waitFor(() => expect(commitMigrationJob).toHaveBeenCalledWith(demoAuthorization.context.contextId, "csrf-1", "job-1", 1));
  });
});
