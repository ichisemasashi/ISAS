import { vi } from "vitest";
import { activateWaitingWorker } from "./pwa-update";

describe("PWA update safety gate", () => {
  test("blocks activation while any tenant has pending outbox records", async () => {
    const postMessage = vi.fn();
    const result = await activateWaitingWorker(
      { waiting: { postMessage } as unknown as ServiceWorker },
      { pendingCount: vi.fn(async () => 3) },
    );
    expect(result).toEqual({ status: "blocked", pending: 3 });
    expect(postMessage).not.toHaveBeenCalled();
  });

  test("activates the waiting worker only after the outbox is empty", async () => {
    const postMessage = vi.fn();
    const result = await activateWaitingWorker(
      { waiting: { postMessage } as unknown as ServiceWorker },
      { pendingCount: vi.fn(async () => 0) },
    );
    expect(result).toEqual({ status: "activating", pending: 0 });
    expect(postMessage).toHaveBeenCalledWith({ type: "SKIP_WAITING" });
  });
});
