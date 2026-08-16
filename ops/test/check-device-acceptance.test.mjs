import assert from "node:assert/strict";
import test from "node:test";
import { REQUIRED_PROFILES, REQUIRED_TESTS, validateDeviceAcceptance } from "../check-device-acceptance.mjs";

function readyEvidence() {
  const profiles = REQUIRED_PROFILES.map((id) => ({
    id,
    physical_device: true,
    platform: id.startsWith("ios-") ? "iOS" : "Android",
    support_tier: id.includes("-min-") ? "minimum" : "current",
    display_mode: id.endsWith("-browser") ? "browser" : "standalone",
    model: id.startsWith("ios-") ? "iPhone 16" : "Pixel 10",
    os_build: "build-12345",
    browser_version: "browser-123",
    measured_at: "2026-08-16T00:00:00Z",
    tests: Object.fromEntries(REQUIRED_TESTS.map((name) => [name, { status: "pass", evidence: `artifact://device/${id}/${name}` }])),
  }));
  return {
    schema_version: 1, status: "PASS", profiles,
    approvals: [
      { actor: "device-lead", approved_at: "2026-08-16T01:00:00Z", evidence: "artifact://approval/device-lead" },
      { actor: "security-verifier", approved_at: "2026-08-16T01:01:00Z", evidence: "artifact://approval/security-verifier" },
    ],
  };
}

test("accepts a complete physical-device matrix", () => {
  assert.deepEqual(validateDeviceAcceptance(readyEvidence(), new Date("2026-08-16T02:00:00Z")), []);
});

test("rejects simulator, missing shared-device test and unsafe placeholder evidence", () => {
  const evidence = readyEvidence();
  evidence.profiles[0].physical_device = false;
  evidence.profiles[0].tests.shared_device.status = "blocked";
  evidence.profiles[0].tests.outbox_recovery.evidence = "replace-me";
  const errors = validateDeviceAcceptance(evidence, new Date("2026-08-16T02:00:00Z"));
  assert.ok(errors.some((error) => error.includes("simulator/emulator")));
  assert.ok(errors.some((error) => error.includes("shared_device.status")));
  assert.ok(errors.some((error) => error.includes("outbox_recovery.evidence")));
});

test("rejects a desktop diagnostic substituted for a required profile", () => {
  const evidence = readyEvidence();
  evidence.profiles.shift();
  evidence.profiles.push({ ...evidence.profiles[0], id: "desktop-chromium", platform: "desktop" });
  const errors = validateDeviceAcceptance(evidence, new Date("2026-08-16T02:00:00Z"));
  assert.ok(errors.some((error) => error.includes("ios-min-browser is missing")));
  assert.ok(errors.some((error) => error.includes("platform must be iOS or Android")));
});
