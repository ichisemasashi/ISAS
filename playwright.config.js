// @ts-check
const { defineConfig } = require('@playwright/test');
const fs = require('fs');
const path = require('path');

const readyPath = path.join(__dirname, 'target/e2e-run/ready.json');
let baseURL = 'http://127.0.0.1:8080';
if (fs.existsSync(readyPath)) {
  baseURL = JSON.parse(fs.readFileSync(readyPath, 'utf8')).baseURL;
}

module.exports = defineConfig({
  testDir: 'e2e',
  testMatch: /.*\.spec\.js$/,
  testIgnore: [/^\._/, /\/\._/],
  timeout: 60000,
  expect: { timeout: 15000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL,
    viewport: { width: 1280, height: 900 },
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium', channel: 'chrome' } }],
});
