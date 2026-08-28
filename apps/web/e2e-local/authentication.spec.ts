import { createHmac } from "node:crypto";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { expect, test, type Page } from "@playwright/test";

function localEnvironment() {
  const values: Record<string, string> = {};
  const dataRoot = process.env.ISAS_NATIVE_DATA_ROOT || resolve(process.env.HOME || "", "Library/Application Support/ISAS/local-integration");
  const path = resolve(dataRoot, "secrets/runtime.env");
  for (const line of readFileSync(path, "utf8").split(/\r?\n/)) {
    const separator = line.indexOf("=");
    if (separator > 0) values[line.slice(0, separator)] = line.slice(separator + 1);
  }
  return values;
}

function decodeBase32(value: string) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const character of value.replace(/=+$/, "").toUpperCase()) {
    const index = alphabet.indexOf(character);
    if (index < 0) throw new Error("invalid local TOTP secret");
    bits += index.toString(2).padStart(5, "0");
  }
  const bytes = [];
  for (let offset = 0; offset + 8 <= bits.length; offset += 8) bytes.push(Number.parseInt(bits.slice(offset, offset + 8), 2));
  return Buffer.from(bytes);
}

function totp(secret: string, now = Date.now()) {
  const counter = BigInt(Math.floor(now / 30_000));
  const message = Buffer.alloc(8);
  message.writeBigUInt64BE(counter);
  const digest = createHmac("sha1", decodeBase32(secret)).update(message).digest();
  const offset = digest[digest.length - 1] & 0x0f;
  const value = (digest.readUInt32BE(offset) & 0x7fffffff) % 1_000_000;
  return String(value).padStart(6, "0");
}

const env = localEnvironment();

const applicationUrl = (url: URL) => url.origin === "https://isas.localhost:8443" && !url.pathname.startsWith("/oidc/");

async function nextTotpWindow(page: Page) {
  await page.waitForTimeout(30_500 - (Date.now() % 30_000));
}

async function completeLogin(page: Page, requireNextWindow = false) {
  const username = page.locator("#username");
  if (await username.count()) await username.fill("local-operator");
  await page.locator("#password").fill(env.LOCAL_OPERATOR_PASSWORD);
  await page.locator("#kc-login").click();
  await page.locator("#otp").waitFor({ state: "visible" });
  if (requireNextWindow) await nextTotpWindow(page);
  for (let attempt = 0; attempt < 2; attempt += 1) {
    await page.locator("#otp").fill(totp(env.LOCAL_OPERATOR_TOTP_SECRET));
    await page.locator("#kc-login").click();
    try {
      await page.waitForURL(applicationUrl, { timeout: 5_000 });
      return;
    } catch {
      if (attempt === 1 || !(await page.locator("#otp").isVisible())) throw new Error("Keycloak did not accept a fresh local TOTP");
      await nextTotpWindow(page);
    }
  }
}

test("authorization code PKCE login requires TOTP and step-up preserves same subject", async ({ page, context }) => {
  test.setTimeout(120_000);
  const observedOrigins = new Set<string>();
  page.on("request", (request) => observedOrigins.add(new URL(request.url()).origin));
  await page.goto("/api/bff/login?return_to=%2F");
  await completeLogin(page);

  const session = await page.evaluate(async () => { const response = await fetch("/api/bff/session"); return { status: response.status, body: await response.json() }; });
  expect(session.status).toBe(200);
  expect(session.body.user.authenticationLevel).toBe("mfa");
  expect(session.body.tenants).toHaveLength(1);
  const cookies = await context.cookies("https://isas.localhost:8443");
  const cookie = cookies.find((item) => item.name === "__Host-isas_session");
  expect(cookie).toMatchObject({ secure: true, httpOnly: true, sameSite: "Lax", path: "/" });

  const business = await page.evaluate(async ({ csrfToken, tenantId }) => {
    const contextResponse = await fetch("/api/bff/contexts", {
      method: "POST", credentials: "include",
      headers: { "Content-Type": "application/json", "X-CSRF-Token": csrfToken },
      body: JSON.stringify({ tenantId }),
    });
    const requestContext = await contextResponse.json();
    const get = async (path: string) => {
      const response = await fetch(path, { credentials: "include", cache: "no-store", headers: { "X-ISAS-Context": requestContext.contextId } });
      return { status: response.status, body: await response.json() };
    };
    const today = await get("/api/v1/today");
    const fields = await get("/api/v1/fields");
    const instructions = await get("/api/v1/work-instructions");
    const instruction = instructions.body.instructions[0];
    const field = fields.body.features[0];
    const journal = await get(`/api/v1/journal-bootstrap?instructionId=${encodeURIComponent(instruction.id)}`);
    const pesticide = await get(`/api/v1/pesticide-bootstrap?fieldId=${encodeURIComponent(field.id)}`);
    const inventory = await get("/api/v1/inventory");
    const security = await get("/api/v1/security-admin");
    const progressResponse = await fetch(`/api/v1/work-instructions/${encodeURIComponent(instruction.id)}/progress`, {
      method: "PATCH", credentials: "include",
      headers: { "Content-Type": "application/json", "X-CSRF-Token": csrfToken, "X-ISAS-Context": requestContext.contextId },
      body: JSON.stringify({ eventUuid: "49000000-0000-4000-8000-000000000001", progressPercent: 10, expectedVersion: instruction.version, note: "local integration E2E", occurredAt: new Date().toISOString() }),
    });
    return { contextStatus: contextResponse.status, requestContext, today, fields, instructions, journal, pesticide, inventory, security,
      progress: { status: progressResponse.status, body: await progressResponse.json() } };
  }, { csrfToken: session.body.csrfToken, tenantId: session.body.tenants[0].id });
  expect(business.contextStatus).toBe(201);
  expect(business.requestContext.capabilities).toEqual(expect.arrayContaining(["instruction:manage", "journal:write", "pesticide:write", "inventory:write"]));
  expect(business.today).toMatchObject({ status: 200, body: { tasks: [expect.objectContaining({ field: "ローカル実証圃場" })] } });
  expect(business.fields).toMatchObject({ status: 200, body: { features: [expect.objectContaining({ properties: expect.objectContaining({ name: "ローカル実証圃場" }) })] } });
  expect(business.instructions).toMatchObject({ status: 200, body: { instructions: [expect.objectContaining({ title: "実証圃場の生育確認" })] } });
  expect(business.journal).toMatchObject({ status: 200, body: { templates: [expect.objectContaining({ name: "巡回確認" })] } });
  expect(business.pesticide).toMatchObject({ status: 200, body: { chemicals: [expect.objectContaining({ name: "ローカル確認剤" })] } });
  expect(business.inventory).toMatchObject({ status: 200, body: { balances: [expect.objectContaining({ name: "ローカル確認剤", quantity: 25 })] } });
  expect(business.security).toMatchObject({ status: 200, body: { localTestUserRegistration: true } });
  expect(business.progress).toMatchObject({ status: 200, body: { progressPercent: 10 } });
  await expect(page.getByText("ローカル実証圃場").first()).toBeVisible();

  await page.goto("/api/bff/login?step_up=1&return_to=%2F");
  await completeLogin(page, true);
  const steppedUp = await page.evaluate(async () => (await fetch("/api/bff/session", { cache: "no-store" })).json());
  expect(steppedUp.user.id).toBe(session.body.user.id);
  expect(steppedUp.user.authenticationLevel).toBe("mfa");

  const privileged = await page.evaluate(async ({ csrfToken, tenantId }) => {
    const contextResponse = await fetch("/api/bff/contexts", { method: "POST", credentials: "include",
      headers: { "Content-Type": "application/json", "X-CSRF-Token": csrfToken }, body: JSON.stringify({ tenantId }) });
    const context = await contextResponse.json();
    const securityResponse = await fetch("/api/v1/security-admin", { credentials: "include", cache: "no-store", headers: { "X-ISAS-Context": context.contextId } });
    const duplicateResponse = await fetch("/api/v1/security-admin/local-test-users", { method: "POST", credentials: "include",
      headers: { "Content-Type": "application/json", "X-CSRF-Token": csrfToken, "X-ISAS-Context": context.contextId },
      body: JSON.stringify({ username: "duplicate-check", email: "local-operator@invalid.example", displayName: "重複確認", roleKey: "worker" }) });
    return { security: { status: securityResponse.status, body: await securityResponse.json() },
      duplicate: { status: duplicateResponse.status, body: await duplicateResponse.json() } };
  }, { csrfToken: steppedUp.csrfToken, tenantId: steppedUp.tenants[0].id });
  expect(privileged.security).toMatchObject({ status: 200, body: { localTestUserRegistration: true } });
  expect(privileged.duplicate).toMatchObject({ status: 409, body: { type: "email_conflict" } });

  const logout = await page.evaluate(async (csrfToken) => {
    const response = await fetch("/api/bff/logout", { method: "POST", credentials: "include", headers: { "Content-Type": "application/json", "X-CSRF-Token": csrfToken }, body: "{}" });
    return { status: response.status, location: response.headers.get("X-ISAS-Logout-Location") };
  }, steppedUp.csrfToken);
  expect(logout.status).toBe(204);
  expect(logout.location).toMatch(/^https:\/\/isas\.localhost:8443\/oidc\//);
  expect((await page.request.get("/api/bff/session")).status()).toBe(401);
  expect([...observedOrigins]).toEqual(["https://isas.localhost:8443"]);
});

test("TLS ingress serves the web and APIs with one hardened origin", async ({ page, request }) => {
  const response = await request.get("/");
  expect(response.status()).toBe(200);
  expect(response.headers()["strict-transport-security"]).toBe("max-age=31536000");
  expect(response.headers()["content-security-policy"]).toContain("default-src 'self'");
  expect(response.headers()["content-security-policy"]).toContain("connect-src 'self'");
  expect(response.headers()["content-security-policy"]).toContain("https://cyberjapandata.gsi.go.jp");
  expect(response.headers()["cross-origin-resource-policy"]).toBe("same-origin");
  expect(response.headers()["access-control-allow-origin"]).toBeUndefined();

  await page.goto("/");
  await expect(page.locator("#root")).not.toBeEmpty();

  const plaintext = await request.get("http://isas.localhost:8443/", { failOnStatusCode: false }).catch(() => null);
  if (plaintext) {
    expect(plaintext.status()).toBeGreaterThanOrEqual(400);
    expect(await plaintext.text()).not.toContain("<title>ISAS</title>");
  }
});
