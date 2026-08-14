import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App } from "./App";
import type { MvpGateway } from "./api";
import { demoAuthorization } from "./auth";
import type { JournalDraft, OutboxRecord, StorageGateway } from "./storage";

function memoryStorage() {
  const drafts: JournalDraft[] = [];
  const outbox: OutboxRecord[] = [];
  const gateway: StorageGateway = {
    async saveDraft(draft) { drafts.push(draft); },
    async enqueue(record) { outbox.push(record); },
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
    async saveServerQueues() {},
    async queueCounts() { return { rejections: 0, conflicts: 0 }; },
  };
  return { gateway, drafts, outbox };
}

const tasks = [
  { id: "t1", time: "08:30", field: "北の1号圃場", crop: "つや姫", work: "水位を確認", status: "next" as const },
  { id: "t2", time: "10:00", field: "西のハウス", crop: "ミニトマト", work: "誘引・わき芽取り", status: "today" as const },
  { id: "t3", time: "14:00", field: "南の3号圃場", crop: "雪若丸", work: "除草剤散布", status: "safety_check" as const },
];
const api: MvpGateway = {
  async getToday() { return { tasks, serverTime: new Date().toISOString() }; },
  async push() { throw new Error("offline test transport"); },
  async pull() { return { changes: [], nextCursor: "0", hasMore: false }; },
  async getQueues() { return { rejections: [], conflicts: [] }; },
  async resolveConflict() { return {}; },
};
const renderApp = (storage: StorageGateway, authorization = demoAuthorization) => render(<App api={api} csrfToken="csrf-1" storage={storage} authorization={authorization} />);

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
    await user.selectOptions(screen.getByLabelText("薬剤名"), "テスト乳剤（要確認）");
    expect(screen.getByRole("alert")).toHaveTextContent("使用回数を確認してください");
    const submit = screen.getByRole("button", { name: "安全確認して記録" });
    expect(submit).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: "警告内容と使用履歴を確認しました" }));
    expect(submit).toBeEnabled();
    await user.click(submit);
    await waitFor(() => expect(store.outbox[0]?.kind).toBe("pesticide"));
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
});
