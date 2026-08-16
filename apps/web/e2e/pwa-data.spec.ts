import { expect, test } from "@playwright/test";

test("Service Worker更新後も未同期outboxを同じUUIDで保持し旧shellだけを削除する", async ({ page }) => {
  await page.goto("/?ut=1&reset=1&sync=fail");
  await expect(page.getByRole("heading", { name: /おはようございます/ })).toBeVisible();
  await page.getByRole("button", { name: "進行役：圏外を模擬" }).click();
  await page.getByRole("button", { name: "作業を始める" }).click();
  await expect(page.getByText("未同期 1件")).toBeVisible();

  const before = await outboxEventUuids(page);
  expect(before).toHaveLength(1);
  await page.evaluate(async () => {
    await caches.open("isas-shell-obsolete").then((cache) => cache.put("/obsolete-quality-probe", new Response("old")));
    for (const registration of await navigator.serviceWorker.getRegistrations()) await registration.unregister();
    const registration = await navigator.serviceWorker.register("/sw.js?quality-update=1");
    await new Promise<void>((resolve, reject) => {
      const worker = registration.installing || registration.waiting || registration.active;
      if (!worker) return reject(new Error("Service Worker was not created"));
      if (worker.state === "activated") return resolve();
      const timeout = window.setTimeout(() => reject(new Error("Service Worker activation timed out")), 5000);
      worker.addEventListener("statechange", () => {
        if (worker.state === "activated") { window.clearTimeout(timeout); resolve(); }
      });
    });
  });
  await page.evaluate(() => history.replaceState(null, "", "/?ut=1&sync=fail"));
  await page.reload();
  await expect(page.getByRole("heading", { name: /おはようございます/ })).toBeVisible();
  await expect(page.getByText("未同期 1件")).toBeVisible();
  expect(await outboxEventUuids(page)).toEqual(before);
  expect(await page.evaluate(async () => (await caches.keys()).includes("isas-shell-obsolete"))).toBe(false);
});

async function outboxEventUuids(page: import("@playwright/test").Page): Promise<string[]> {
  return page.evaluate(async () => (await import("/src/device-security.ts")).listEncryptedOutbox<{ eventUuid: string }>("tenant-yamagata-midori", 100).then((rows) => rows.map((row) => row.eventUuid).sort()));
}
