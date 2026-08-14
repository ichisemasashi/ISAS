import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App } from "./App";
import type { JournalDraft, OutboxRecord, StorageGateway } from "./storage";

function memoryStorage() {
  const drafts: JournalDraft[] = [];
  const outbox: OutboxRecord[] = [];
  const gateway: StorageGateway = {
    async saveDraft(draft) { drafts.push(draft); },
    async enqueue(record) { outbox.push(record); },
    async pendingCount() { return outbox.length; },
  };
  return { gateway, drafts, outbox };
}

describe("ISAS MVP field flow", () => {
  test("shows today's work and persistent synchronization state", async () => {
    const store = memoryStorage();
    render(<App storage={store.gateway} />);
    expect(screen.getByRole("heading", { name: /おはようございます/ })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "今日の作業" })).toBeInTheDocument();
    expect(screen.getByText("未同期 0件")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "作業を始める" })).toBeEnabled();
  });

  test("queues a punch and a journal without requiring a network response", async () => {
    const user = userEvent.setup();
    const store = memoryStorage();
    render(<App storage={store.gateway} />);
    await user.click(screen.getByRole("button", { name: "作業を始める" }));
    await waitFor(() => expect(store.outbox).toHaveLength(1));
    expect(screen.getByText("未同期 1件")).toBeInTheDocument();

    await user.click(screen.getAllByRole("button", { name: "記録する" })[0]);
    expect(screen.getByRole("heading", { name: "作業日誌をつける" })).toBeInTheDocument();
    await user.type(screen.getByLabelText("作業メモ"), "水位は適正。取水口を確認。 ");
    await user.click(screen.getByRole("button", { name: "下書き保存" }));
    await waitFor(() => expect(store.drafts.length).toBeGreaterThan(0));
    await user.click(screen.getByRole("button", { name: "この内容で記録" }));
    await waitFor(() => expect(store.outbox).toHaveLength(2));
    expect(screen.getByText("未同期 2件")).toBeInTheDocument();
  });

  test("requires explicit acknowledgement when the safety cache reports a warning", async () => {
    const user = userEvent.setup();
    const store = memoryStorage();
    render(<App storage={store.gateway} />);
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
});
