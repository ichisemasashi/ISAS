import assert from "node:assert/strict";
import test from "node:test";
import { calculateTco, validateTco } from "./calculate-tco.mjs";

function fixture() {
  const quote = () => ({ uri: "artifact://quotes/q", digest: `sha256:${"a".repeat(64)}`, validUntil: "2026-10-01" });
  const names = ["primary_host", "spare_host", "storage", "power", "network", "backup", "idp", "monitoring", "maintenance_labor", "security_updates", "restore_dr", "incidents", "planned_downtime", "unplanned_downtime"];
  const profiles = [];
  for (const hostOs of ["macos", "linux", "freebsd"]) for (const fieldCount of [100, 1000, 3000]) profiles.push({ hostOs, fieldCount,
    assumptions: { service_owner: "ops-group", maintainer_fte: 0.5 }, frequencies36Months: { os_updates: 36, application_updates: 12, restore_dr_drills: 15, incidents: 3 },
    costs: Object.fromEntries(names.map((name) => [name, { amount: 100, quote: quote() }])),
  });
  return { schemaVersion: 2, currency: "JPY", pricedAt: "2026-08-22", profiles, approvals: [
    { actor: "owner", role: "service_owner", approvedAt: "2026-08-22", evidence: "artifact://approval/owner" },
    { actor: "verifier", role: "financial_verifier", approvedAt: "2026-08-22", evidence: "artifact://approval/verifier" },
  ] };
}

test("accepts and totals all nine evidence-backed estimates", () => {
  const value = fixture(); assert.deepEqual(validateTco(value, new Date("2026-08-23T00:00:00Z")), []);
  assert.equal(calculateTco(value).profiles.length, 9); assert.equal(calculateTco(value).profiles[0].total36Months, 1400);
});
test("rejects a missing combination, cost source and duplicate approval", () => {
  const value = fixture(); value.profiles.pop(); delete value.profiles[0].costs.backup.quote.digest; value.approvals[1].actor = "owner";
  const errors = validateTco(value, new Date("2026-08-23T00:00:00Z"));
  assert.ok(errors.some((error) => error.includes("missing profiles"))); assert.ok(errors.some((error) => error.includes("costs.backup"))); assert.ok(errors.some((error) => error.includes("two distinct")));
});
