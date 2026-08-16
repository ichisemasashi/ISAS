import { createHmac } from "node:crypto";
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
  for (const key of ["USERNAME", "PASSWORD", "TOTP_SECRET"]) if (!values[key]) throw new Error(`test-worker credential is missing ${key}`);
  return values;
}

function decodeBase32(value: string) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const character of value.replace(/=+$/, "").toUpperCase()) {
    const index = alphabet.indexOf(character);
    if (index < 0) throw new Error("invalid test-worker TOTP secret");
    bits += index.toString(2).padStart(5, "0");
  }
  const bytes = [];
  for (let offset = 0; offset + 8 <= bits.length; offset += 8) bytes.push(Number.parseInt(bits.slice(offset, offset + 8), 2));
  return Buffer.from(bytes);
}

function totp(secret: string) {
  const message = Buffer.alloc(8);
  message.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30_000)));
  const digest = createHmac("sha1", decodeBase32(secret)).update(message).digest();
  const offset = digest[digest.length - 1] & 0x0f;
  return String((digest.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).padStart(6, "0");
}

test("registered test worker authenticates with MFA and receives only assigned scope", async ({ page }) => {
  const user = credentials();
  await page.goto("/api/bff/login?return_to=%2F");
  await page.locator("#username").fill(user.USERNAME);
  await page.locator("#password").fill(user.PASSWORD);
  await page.locator("#kc-login").click();
  await page.locator("#otp").fill(totp(user.TOTP_SECRET));
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
  expect(result.session.user.authenticationLevel).toBe("mfa");
  expect(result.contextStatus).toBe(201);
  expect(result.context.capabilities).toEqual(expect.arrayContaining(["journal:write", "pesticide:write", "inventory:write"]));
  expect(result.context.capabilities).not.toContain("instruction:manage");
  expect(result.fields).toMatchObject({ status: 200, body: { features: [expect.objectContaining({ properties: expect.objectContaining({ name: "ローカル実証圃場" }) })] } });
  expect(result.instructions).toMatchObject({ status: 200, body: { instructions: [expect.objectContaining({ title: "実証圃場の生育確認" })] } });
});
