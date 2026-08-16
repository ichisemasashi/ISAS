import { expect, test } from "@playwright/test";

test.beforeEach(async ({ page }) => {
  await page.goto("/?ut=1&reset=1&locale=ar-XB");
  await expect(page.locator("html")).toHaveAttribute("dir", "rtl");
  await expect(page.locator("html")).toHaveAttribute("lang", "ar-XB");
});

test("common, work, Gantt, and GIS screens render in RTL without page overflow", async ({ page }) => {
  await expect(page.getByRole("button", { name: "اليوم" })).toBeVisible();
  await expect(page.locator("main")).toHaveCSS("direction", "rtl");

  await page.getByRole("button", { name: "الجدول" }).click();
  await expect(page.locator(".schedule-page")).toBeVisible();
  await expect(page.locator(".gantt-panel")).toHaveCSS("direction", "rtl");

  await page.getByRole("button", { name: "الحقول" }).click();
  await expect(page.locator(".fields-page")).toBeVisible();

  await page.getByRole("button", { name: "السجل" }).click();
  await expect(page.locator(".narrow-page")).toBeVisible();

  const overflow = await page.evaluate(() => ({ client: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth, elements: [...document.querySelectorAll<HTMLElement>("body *")].filter((element) => { const box = element.getBoundingClientRect(); return box.left < -1 || box.right > document.documentElement.clientWidth + 1; }).slice(0, 8).map((element) => { const box = element.getBoundingClientRect(); return `${element.tagName}.${element.className}:${Math.round(box.left)}..${Math.round(box.right)}`; }) }));
  expect(overflow.scroll, JSON.stringify(overflow)).toBeLessThanOrEqual(overflow.client);
});

test("RTL layout remains usable at 200 percent text size", async ({ page }) => {
  await page.evaluate(() => { document.documentElement.style.fontSize = "200%"; });
  await expect(page.getByRole("button", { name: "الجدول" })).toBeVisible();
  await page.getByRole("button", { name: "الجدول" }).click();
  await expect(page.locator(".mobile-schedule-list")).toBeVisible();
  await expect(page.getByRole("button", { name: "الحقول" })).toBeVisible();
  const overflow = await page.evaluate(() => ({ client: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth, elements: [...document.querySelectorAll<HTMLElement>("body *")].filter((element) => { const box = element.getBoundingClientRect(); return box.left < -1 || box.right > document.documentElement.clientWidth + 1; }).slice(0, 8).map((element) => { const box = element.getBoundingClientRect(); return `${element.tagName}.${element.className}:${Math.round(box.left)}..${Math.round(box.right)}`; }) }));
  expect(overflow.scroll, JSON.stringify(overflow)).toBeLessThanOrEqual(overflow.client);
});
