import { expect, test } from "@playwright/test";

test.beforeEach(async ({ page }) => {
  await page.goto("/?ut=1&reset=1");
  await expect(page.getByRole("heading", { name: /おはようございます/ })).toBeVisible();
});

test("作業確認、打刻、日誌、農薬警告、オフライン保存を一連で完遂できる", async ({ page }) => {
  await expect(page.getByRole("note")).toContainText("架空データ");
  await expect(page.getByRole("heading", { name: "水位と取水口を確認" })).toBeVisible();
  await expect(page.getByText("練習用・北の1号圃場")).toBeVisible();

  await page.getByRole("button", { name: "作業を始める" }).click();
  await expect(page.getByRole("heading", { name: "作業中です" })).toBeVisible();
  await page.getByRole("button", { name: "休憩する" }).click();
  await expect(page.getByRole("heading", { name: "休憩中です" })).toBeVisible();
  await page.getByRole("button", { name: "作業に戻る" }).click();
  await page.getByRole("button", { name: "作業を終える" }).click();
  await expect(page.getByRole("heading", { name: "まだ作業を開始していません" })).toBeVisible();

  await page.getByRole("button", { name: "記録する" }).click();
  await expect(page.getByLabel("圃場", { exact: true })).toHaveValue("練習用・北の1号圃場");
  await expect(page.getByLabel("作業", { exact: true })).toHaveValue("水管理");
  await expect(page.getByLabel("開始", { exact: true })).toHaveValue("08:12");
  await expect(page.getByLabel("終了", { exact: true })).toHaveValue("09:36");
  await page.getByRole("button", { name: "この内容で記録" }).click();
  await expect(page.getByText("端末に保存しました。まもなく同期します。")).toBeVisible();

  await page.getByRole("button", { name: "農薬記録を始める" }).click();
  await page.getByLabel("薬剤名").selectOption("chemical-warning");
  await expect(page.getByRole("alert")).toContainText("使用回数が上限を超えます");
  const pesticideSubmit = page.getByRole("button", { name: "安全確認して記録" });
  await expect(pesticideSubmit).toBeDisabled();
  await page.getByRole("checkbox", { name: "警告内容と使用履歴を確認しました" }).check();
  await expect(pesticideSubmit).toBeEnabled();
  await pesticideSubmit.click();

  await page.getByRole("button", { name: "進行役：圏外を模擬" }).click();
  await expect(page.getByText("オフライン", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "記録する" }).click();
  await page.getByRole("button", { name: "この内容で記録" }).click();
  await expect(page.getByText("未同期 1件")).toBeVisible();
  await expect(page.getByText("端末に保存しました。電波が戻ると自動で同期します。")).toBeVisible();
});

test("320px相当の表示で主要画面に横スクロールが生じない", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 800 });
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(1);
  await expect(page.getByRole("navigation", { name: "メインナビゲーション" })).toBeVisible();
});
