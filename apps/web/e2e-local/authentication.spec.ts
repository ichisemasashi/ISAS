import { createHmac } from "node:crypto";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { expect, test, type Page } from "@playwright/test";

function localEnvironment() {
  const values: Record<string, string> = {};
  const path = resolve(import.meta.dirname, "../../../.local/secrets/runtime.env");
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

async function completeLogin(page: Page, totpOffsetMs = 0) {
  const username = page.locator("#username");
  if (await username.count()) await username.fill("local-operator");
  await page.locator("#password").fill(env.LOCAL_OPERATOR_PASSWORD);
  await page.locator("#kc-login").click();
  await page.locator("#otp").waitFor({ state: "visible" });
  // Keycloak rejects reuse of the current TOTP. Its configured one-step
  // look-ahead accepts the next time window for an immediate step-up.
  await page.locator("#otp").fill(totp(env.LOCAL_OPERATOR_TOTP_SECRET, Date.now() + totpOffsetMs));
  await page.locator("#kc-login").click();
  await page.waitForURL("https://isas.localhost:8443/**");
}

test("authorization code PKCE login requires TOTP and step-up preserves same subject", async ({ page, context }) => {
  await page.goto("/api/bff/login?return_to=%2F");
  await completeLogin(page);

  const session = await page.evaluate(async () => { const response = await fetch("/api/bff/session"); return { status: response.status, body: await response.json() }; });
  expect(session.status).toBe(200);
  expect(session.body.user.authenticationLevel).toBe("mfa");
  expect(session.body.tenants).toHaveLength(1);
  const cookies = await context.cookies("https://isas.localhost:8443");
  const cookie = cookies.find((item) => item.name === "__Host-isas_session");
  expect(cookie).toMatchObject({ secure: true, httpOnly: true, sameSite: "Lax", path: "/" });

  await page.goto("/api/bff/login?step_up=1&return_to=%2F");
  await completeLogin(page, 30_000);
  const steppedUp = await page.evaluate(async () => (await fetch("/api/bff/session", { cache: "no-store" })).json());
  expect(steppedUp.user.id).toBe(session.body.user.id);
  expect(steppedUp.user.authenticationLevel).toBe("mfa");

  const logout = await page.evaluate(async (csrfToken) => {
    const response = await fetch("/api/bff/logout", { method: "POST", credentials: "include", headers: { "Content-Type": "application/json", "X-CSRF-Token": csrfToken }, body: "{}" });
    return { status: response.status, location: response.headers.get("X-ISAS-Logout-Location") };
  }, steppedUp.csrfToken);
  expect(logout.status).toBe(204);
  expect(logout.location).toMatch(/^https:\/\/isas\.localhost:8443\/oidc\//);
  expect((await page.request.get("/api/bff/session")).status()).toBe(401);
});
