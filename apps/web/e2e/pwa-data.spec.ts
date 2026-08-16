import { expect, test } from "@playwright/test";

test("未同期outboxは再読込後も同じUUIDで保持される", async ({ page }) => {
  await page.goto("/?ut=1&reset=1&sync=fail");
  await expect(page.getByRole("heading", { name: /おはようございます/ })).toBeVisible();
  await page.getByRole("button", { name: "進行役：圏外を模擬" }).click();
  await page.getByRole("button", { name: "作業を始める" }).click();
  await expect(page.getByText("未同期 1件")).toBeVisible();

  const before = await outboxEventUuids(page);
  expect(before).toHaveLength(1);
  await page.evaluate(() => history.replaceState(null, "", "/?ut=1&sync=fail"));
  await page.reload();
  await expect(page.getByRole("heading", { name: /おはようございます/ })).toBeVisible();
  await expect(page.getByText("未同期 1件")).toBeVisible();
  expect(await outboxEventUuids(page)).toEqual(before);
});

async function outboxEventUuids(page: import("@playwright/test").Page): Promise<string[]> {
  return page.evaluate(async () => (await import("/src/device-security.ts")).listEncryptedOutbox<{ eventUuid: string }>("tenant-yamagata-midori", 100).then((rows) => rows.map((row) => row.eventUuid).sort()));
}
