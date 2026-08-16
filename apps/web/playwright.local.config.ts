import { defineConfig, devices } from "@playwright/test";

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
  projects: [{ name: "local-chromium", use: { ...devices["Desktop Chrome"] } }],
});
