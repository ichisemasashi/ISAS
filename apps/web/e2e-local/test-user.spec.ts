import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { expect, test } from "@playwright/test";

function credentials() {
  const values: Record<string, string> = {};
  const path = resolve(import.meta.dirname, "../../../.local/secrets/test-users/test-worker.env");
  for (const line of readFileSync(path, "utf8").split(/\r?\n/)) {
    const separator = line.indexOf("=");
    if (separator > 0) values[line.slice(0, separator)] = line.slice(separator + 1);
  }
  for (const key of ["USERNAME", "PASSWORD"]) if (!values[key]) throw new Error(`test-worker credential is missing ${key}`);
  return values;
}

test("registered non-administrator signs in with email and password only and receives assigned scope", async ({ page }) => {
  const user = credentials();
  await page.goto("/api/bff/login?return_to=%2F");
  await page.locator("#username").fill(`${user.USERNAME}@invalid.example`);
  await page.locator("#password").fill(user.PASSWORD);
  await page.locator("#kc-login").click();
  await page.waitForURL("https://isas.localhost:8443/");

  const result = await page.evaluate(async () => {
    const sessionResponse = await fetch("/api/bff/session", { cache: "no-store" });
    const session = await sessionResponse.json();
    const contextResponse = await fetch("/api/bff/contexts", {
      method: "POST", credentials: "include",
      headers: { "Content-Type": "application/json", "X-CSRF-Token": session.csrfToken },
      body: JSON.stringify({ tenantId: session.tenants[0].id }),
    });
    const context = await contextResponse.json();
    const get = async (path: string) => {
      const response = await fetch(path, { credentials: "include", cache: "no-store", headers: { "X-ISAS-Context": context.contextId } });
      return { status: response.status, body: await response.json() };
    };
    return { sessionStatus: sessionResponse.status, session, contextStatus: contextResponse.status, context,
      fields: await get("/api/v1/fields"), instructions: await get("/api/v1/work-instructions") };
  });

  expect(result.sessionStatus).toBe(200);
  expect(result.session.user.authenticationLevel).toBe("single-factor");
  expect(result.contextStatus).toBe(201);
  expect(result.context.capabilities).toEqual(expect.arrayContaining(["journal:write", "pesticide:write", "inventory:write"]));
  expect(result.context.capabilities).not.toContain("instruction:manage");
  expect(result.fields).toMatchObject({ status: 200, body: { features: [expect.objectContaining({ properties: expect.objectContaining({ name: "ローカル実証圃場" }) })] } });
  expect(result.instructions).toMatchObject({ status: 200, body: { instructions: [expect.objectContaining({ title: "実証圃場の生育確認" })] } });

  const tile = page.waitForResponse((response) => response.url().startsWith("https://cyberjapandata.gsi.go.jp/xyz/std/") && response.ok());
  const mapWorker = page.waitForResponse((response) => new URL(response.url()).pathname === "/assets/maplibre-gl-worker.mjs" && response.ok());
  const mapWorkerShared = page.waitForResponse((response) => new URL(response.url()).pathname === "/assets/maplibre-gl-shared.mjs" && response.ok());
  await page.locator(".side-nav").getByRole("button", { name: "圃場" }).click();
  await expect(page.locator(".field-map canvas")).toBeVisible();
  await expect.poll(async () => (await page.locator(".field-map").boundingBox())?.height || 0).toBeGreaterThan(400);
  await expect(page.locator(".field-map .maplibregl-ctrl-zoom-in")).toBeVisible();
  await expect(page.locator('.field-map[data-fields-fitted="true"]')).toBeVisible();
  await expect(page.getByText("ローカル実証圃場").first()).toBeVisible();
  expect((await tile).headers()["content-type"]).toContain("image/png");
  expect((await mapWorker).headers()["content-type"]).toContain("javascript");
  expect((await mapWorkerShared).headers()["content-type"]).toContain("javascript");
});
