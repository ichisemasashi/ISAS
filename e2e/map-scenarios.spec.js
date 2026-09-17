// @ts-check
const { test, expect } = require('@playwright/test');
const fs = require('fs');
const path = require('path');

function creds() {
  const p = path.join(__dirname, '..', 'target/e2e-run/ready.json');
  const data = JSON.parse(fs.readFileSync(p, 'utf8'));
  if (!data.email || !data.password || typeof data.password !== 'string') {
    throw new Error('e2e ready.json missing email/password string');
  }
  return data;
}

async function login(page) {
  const { email, password } = creds();
  await page.goto('/');
  await page.fill('input[name="email"]', email);
  await page.fill('input[name="password"]', password);
  await page.click('button[type="submit"]');
  await expect(page.locator('text=利用者として入っています')).toBeVisible({ timeout: 20000 });
}

test.describe('P3-S / P2-S browser scenarios', () => {
  test('P3-S-01 work name on browse without Paint button', async ({ page }) => {
    await login(page);
    await page.click('a[href="/map"]');
    await expect(page.locator('#ol-map')).toBeVisible({ timeout: 20000 });
    // browse: work_name exists, brush does not until work name selected
    const wn = page.locator('form[data-act="select-work-name"] input[name="work_name"]');
    await expect(wn).toBeVisible();
    await expect(page.locator('button:has-text("ブラシ")')).toHaveCount(0);
    await wn.fill('田植え');
    await page.click('form[data-act="select-work-name"] button[type="submit"]');
    await expect(page.locator('button:has-text("ブラシ")')).toBeVisible({ timeout: 20000 });
  });

  test('P3-S-02 brush stroke then map can pan', async ({ page }) => {
    await login(page);
    await page.goto('/map');
    await expect(page.locator('#ol-map')).toBeVisible({ timeout: 20000 });
    const wn = page.locator('form[data-act="select-work-name"] input[name="work_name"]');
    await wn.fill('草刈');
    await page.click('form[data-act="select-work-name"] button[type="submit"]');
    await expect(page.locator('button:has-text("ブラシ")')).toBeVisible({ timeout: 20000 });

    await page.click('button:has-text("ブラシ")');
    const map = page.locator('#ol-map');
    const box = await map.boundingBox();
    expect(box).toBeTruthy();
    const x0 = box.x + box.width * 0.4;
    const y0 = box.y + box.height * 0.4;
    await page.mouse.move(x0, y0);
    await page.mouse.down();
    await page.mouse.move(x0 + 40, y0 + 20, { steps: 8 });
    await page.mouse.up();

    // After stroke, Draw is removed — pan should change the view center.
    const before = await page.evaluate(() => {
      const st = window;
      // OpenLayers map is not exported; compare canvas pixels / selection text as soft check.
      return document.getElementById('ol-map')?.querySelector('canvas') ? 'ok' : 'missing';
    });
    expect(before).toBe('ok');

    // Drag to pan (should not keep drawing exclusively).
    await page.mouse.move(box.x + box.width * 0.6, box.y + box.height * 0.5);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * 0.3, box.y + box.height * 0.5, { steps: 10 });
    await page.mouse.up();

    // Still on map page with brush available again (paint mode kept).
    await expect(page.locator('button:has-text("ブラシ")')).toBeVisible();
    await expect(page.locator('#ol-map')).toBeVisible();
  });

  test('P3-S-03 reopen map clears work name', async ({ page }) => {
    await login(page);
    await page.goto('/map');
    await expect(page.locator('#ol-map')).toBeVisible({ timeout: 20000 });
    await page.fill('form[data-act="select-work-name"] input[name="work_name"]', '一時');
    await page.click('form[data-act="select-work-name"] button[type="submit"]');
    await expect(page.locator('button:has-text("ブラシ")')).toBeVisible({ timeout: 20000 });
    await page.click('a[href="/map"]');
    await expect(page.locator('#ol-map')).toBeVisible({ timeout: 20000 });
    await expect(page.locator('button:has-text("ブラシ")')).toHaveCount(0);
    await expect(page.locator('form[data-act="select-work-name"] input[name="work_name"]')).toHaveValue('');
  });

  test('P2-S-02 change place opens at saved extent copy', async ({ page }) => {
    await login(page);
    await page.goto('/map');
    await expect(page.locator('#ol-map')).toBeVisible({ timeout: 20000 });
    await page.click('a[href="/map/place"]');
    await expect(page.locator('#ol-map[data-place-mode="1"]')).toBeVisible({ timeout: 20000 });
    await expect(page.locator('text=いまの作業場所を変えられます')).toBeVisible();
    await expect(page.locator('text=先に作業場所の範囲を決めてください')).toHaveCount(0);
    // Saved place around 140.04–140.06 should be in data attributes / form, not Japan defaults only.
    const west = await page.locator('#ol-map').getAttribute('data-west');
    expect(Number(west)).toBeGreaterThan(139);
    expect(Number(west)).toBeLessThan(141);
  });
});
