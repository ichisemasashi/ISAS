import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

async function expectWcag21Aa(page: Page, label: string) {
  const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  expect(results.violations, `${label}: ${results.violations.map((item) => `${item.id}(${item.nodes.length})`).join(", ")}`).toEqual([]);
}

test.beforeEach(async ({ page }) => {
  await page.goto("/?ut=1&reset=1");
  await expect(page.getByRole("heading", { name: /おはようございます/ })).toBeVisible();
});

test("今日、日誌、農薬画面がWCAG 2.1 AA自動検査を通る", async ({ page }) => {
  await expectWcag21Aa(page, "今日");
  await page.getByRole("button", { name: "記録する" }).click();
  await expect(page.getByRole("heading", { name: "作業日誌をつける" })).toBeVisible();
  await expectWcag21Aa(page, "作業日誌");
  await page.getByRole("button", { name: "← 今日の作業へ戻る" }).click();
  await page.getByRole("button", { name: "農薬記録を始める" }).click();
  await expect(page.getByRole("heading", { name: "農薬記録" })).toBeVisible();
  await expectWcag21Aa(page, "農薬記録");
});

test("キーボードでスキップリンクから本文へ移動できる", async ({ page }) => {
  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: "本文へ移動" })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.locator("#main")).toBeFocused();
});
