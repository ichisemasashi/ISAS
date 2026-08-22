import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { validateCatalog } from "./check-capability-catalog.mjs";

const catalog = JSON.parse(readFileSync(new URL("../../docs/product/capability-catalog.json", import.meta.url), "utf8"));

const context = { root: new URL("../..", import.meta.url).pathname, now: new Date("2026-08-22T12:00:00Z") };

test("accepts the tracked catalog with coverage, freshness and content-bound evidence", async () => assert.deepEqual(await validateCatalog(catalog, context), []));
test("rejects an availability-like status outside the lifecycle vocabulary", async () => {
  const changed = structuredClone(catalog); changed.capabilities[0].status = "available";
  assert.match((await validateCatalog(changed, context)).join("\n"), /invalid status/);
});
test("does not allow the catalog to claim Production before release acceptance", async () => {
  assert.match((await validateCatalog({ ...catalog, productionAvailability: "AVAILABLE" }, context)).join("\n"), /must remain BLOCKED/);
});
test("blocks an unreviewed KSAS equivalence claim without an authorized connector", async () => {
  const changed = structuredClone(catalog);
  changed.comparisonClaims.ksasEquivalent = true;
  changed.comparisonClaims.status = "ALLOWED";
  assert.match((await validateCatalog(changed, context)).join("\n"), /production-authorized contracted machinery connector/);
});
test("does not permit public scope expansion", async () => {
  assert.match((await validateCatalog({ ...catalog, publicScope: "all KSAS capabilities" }, context)).join("\n"), /publicScope/);
});
test("rejects expired reviews, missing files and changed evidence", async () => {
  const changed = structuredClone(catalog);
  changed.reviewDueAt = "2026-08-21";
  changed.capabilities[0].evidence[0].path = "missing.sql";
  changed.capabilities[1].evidence[0].digest = `sha256:${"0".repeat(64)}`;
  const errors = await validateCatalog(changed, context);
  assert.ok(errors.some((error) => error.includes("expired")));
  assert.ok(errors.some((error) => error.includes("does not exist")));
  assert.ok(errors.some((error) => error.includes("digest mismatch")));
});
