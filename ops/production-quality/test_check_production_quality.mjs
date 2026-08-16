import assert from "node:assert/strict";
import test from "node:test";
import { FUNCTIONAL_CASES, MANUAL_WCAG_CASES, SCREEN_SLOS, validateProductionQuality } from "./check-production-quality.mjs";

const pass = (name) => ({ status: "pass", evidence: `artifact://quality/${name}` });

function ready() {
  return {
    schema_version: 1, status: "PASS", source_commit: "a".repeat(40), deployment_id: "isas-jp-stg-01", measured_at: "2026-08-16T00:00:00Z",
    environment: {
      base_origin: "https://staging.isas.example", tls: { version: "TLSv1.3", hostname_verified: true }, hsts: true, bff_live: "pass", bff_ready: "pass",
      p0_database: { postgres_major: 16, postgis: true, tls: true, endpoint_id: "p0-a" },
      p2_database: { postgres_major: 16, postgis: true, tls: true, endpoint_id: "p2-b" },
      network: { kind: "actual", evidence: "artifact://quality/network" },
    },
    s7: { ...pass("s7"), requests: 1100, failures: 0, p95_ms: 250, duplicate_changes: 0 },
    pool_saturation: { ...pass("pool"), p2_concurrency: 32, p2_utilization_max: .95, p2_waiting_max: 4, p0_samples: 1000, p0_within_500ms: 999 },
    screens: Object.fromEntries(Object.entries(SCREEN_SLOS).map(([name, budget]) => [name, { ...pass(`screen-${name}`), iterations: 30, p95_ms: budget, error_rate: .001 }])),
    functional: Object.fromEntries(FUNCTIONAL_CASES.map((name) => [name, pass(name)])),
    manual_wcag: Object.fromEntries(MANUAL_WCAG_CASES.map((name) => [name, pass(name)])),
    penetration: { status: "pass", independent_tester: true, report: "artifact://quality/pen", open_critical: 0, open_high: 0, open_medium: 0, retest_complete: true },
    adversarial_review: { status: "pass", open_high: 0, open_medium: 0, report: "artifact://quality/adversarial" },
    approvals: ["release", "security", "accessibility"].map((actor, index) => ({ actor, approved_at: `2026-08-16T0${index}:00:00Z`, evidence: `artifact://quality/approval-${actor}` })),
  };
}

test("accepts complete production-equivalent evidence", () => {
  assert.deepEqual(validateProductionQuality(ready(), new Date("2026-08-16T03:00:00Z")), []);
});

test("rejects no-data screens, shared pools and insufficient P0 availability", () => {
  const value = ready();
  value.screens.map_initial_1000.iterations = 0;
  value.environment.p2_database.endpoint_id = value.environment.p0_database.endpoint_id;
  value.pool_saturation.p0_within_500ms = 998;
  const errors = validateProductionQuality(value, new Date("2026-08-16T03:00:00Z"));
  assert.ok(errors.some((error) => error.includes("map_initial_1000")));
  assert.ok(errors.some((error) => error.includes("distinct")));
  assert.ok(errors.some((error) => error.includes("99.9%")));
});

test("rejects unresolved penetration findings and missing manual WCAG evidence", () => {
  const value = ready();
  value.penetration.open_high = 1;
  value.manual_wcag.screen_reader_ios.status = "blocked";
  const errors = validateProductionQuality(value, new Date("2026-08-16T03:00:00Z"));
  assert.ok(errors.some((error) => error.includes("penetration")));
  assert.ok(errors.some((error) => error.includes("screen_reader_ios")));
});
