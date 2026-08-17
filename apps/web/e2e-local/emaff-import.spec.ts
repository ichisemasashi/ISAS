import { createHmac } from "node:crypto";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { expect, test } from "@playwright/test";

function credentials() {
  const values: Record<string, string> = {};
  const path = resolve(import.meta.dirname, "../../../.local/secrets/test-users/test-map-admin.env");
  for (const line of readFileSync(path, "utf8").split(/\r?\n/)) {
    const separator = line.indexOf("=");
    if (separator > 0) values[line.slice(0, separator)] = line.slice(separator + 1);
  }
  for (const key of ["USERNAME", "PASSWORD", "TOTP_SECRET"]) if (!values[key]) throw new Error(`test-map-admin credential is missing ${key}`);
  return values;
}

function totp(secret: string, offsetMs = 0) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; let bits = "";
  for (const character of secret.replace(/=+$/, "").toUpperCase()) bits += alphabet.indexOf(character).toString(2).padStart(5, "0");
  const bytes = []; for (let offset = 0; offset + 8 <= bits.length; offset += 8) bytes.push(Number.parseInt(bits.slice(offset, offset + 8), 2));
  const counter = BigInt(Math.floor((Date.now() + offsetMs) / 30_000)); const message = Buffer.alloc(8); message.writeBigUInt64BE(counter);
  const digest = createHmac("sha1", Buffer.from(bytes)).update(message).digest(); const offset = digest.at(-1)! & 0x0f;
  return String((digest.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).padStart(6, "0");
}

test("group administrator imports eMAFF GeoJSON from the field map with one button", async ({ page }) => {
  const user = credentials();
  await page.goto("/api/bff/login?return_to=%2F");
  await page.locator("#username").fill(user.USERNAME);
  await page.locator("#password").fill(user.PASSWORD);
  await page.locator("#kc-login").click();
  await page.locator("#otp").fill(totp(user.TOTP_SECRET, test.info().project.name === "local-webkit" ? 30_000 : 0));
  await page.locator("#kc-login").click();
  await page.waitForURL("https://isas.localhost:8443/");

  await page.locator(".side-nav").getByRole("button", { name: "圃場" }).click();
  const importButton = page.getByRole("button", { name: "eMAFF農地ナビから取込" });
  await expect(importButton).toBeVisible();
  const geojson = JSON.stringify({ type: "FeatureCollection", features: [{
    type: "Feature", properties: { DaichoId: "ISAS-E2E-3335", Address: "eMAFF取込確認圃場" },
    geometry: { type: "Polygon", coordinates: [[[140.1000, 37.9000], [140.1010, 37.9000], [140.1010, 37.9010], [140.1000, 37.9000]]] },
  }] });
  const chooser = page.waitForEvent("filechooser"); await importButton.click();
  await (await chooser).setFiles({ name: "emaff-e2e.geojson", mimeType: "application/geo+json", buffer: Buffer.from(geojson) });

  await expect(page.getByText("eMAFF取込確認圃場").first()).toBeVisible();
  await expect(page.locator('.field-map[data-fields-fitted="true"]')).toBeVisible();
  await expect(page.locator(".map-cache-controls [role=status]")).toContainText(/登録しました|登録済みです/);
});
