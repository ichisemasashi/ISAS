import { createHash, X509Certificate } from "node:crypto";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { defineConfig, devices } from "@playwright/test";

const localCertificate = new X509Certificate(readFileSync(resolve(import.meta.dirname, "../../.local/tls/isas.localhost.pem")));
const localCertificateSpki = createHash("sha256")
  .update(localCertificate.publicKey.export({ type: "spki", format: "der" }))
  .digest("base64");

export default defineConfig({
  testDir: "./e2e-local",
  testIgnore: "**/._*",
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  outputDir: "/tmp/isas-local-playwright-results",
  reporter: [["line"]],
  use: {
    baseURL: "https://isas.localhost:8443",
    ignoreHTTPSErrors: true,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    { name: "local-chromium", use: { ...devices["Desktop Chrome"], launchOptions: { args: [`--ignore-certificate-errors-spki-list=${localCertificateSpki}`] } } },
    { name: "local-webkit", use: { ...devices["Desktop Safari"] } },
  ],
});
