import { expect, test } from "@playwright/test";

test("production shell installs its Service Worker without an update error", async ({ page }) => {
  await page.goto("/");

  const registration = await page.evaluate(async () => {
    try {
      const value = await navigator.serviceWorker.ready;
      return { ok: true, scope: value.scope, active: value.active?.state };
    } catch (error) {
      return { ok: false, error: error instanceof Error ? `${error.name}: ${error.message}` : String(error) };
    }
  });

  expect(registration).toMatchObject({ ok: true, scope: "https://isas.localhost:8443/" });
  expect(registration.active).toMatch(/activating|activated/);
  await expect(page.getByText("アプリ更新を確認できませんでした。通信状態を確認してください。")).toHaveCount(0);
  await expect(page.getByText("アプリの更新があります")).toHaveCount(0);
});
