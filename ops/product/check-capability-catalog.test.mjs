import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { validateCatalog } from "./check-capability-catalog.mjs";

const catalog = JSON.parse(readFileSync(new URL("../../docs/product/capability-catalog.json", import.meta.url), "utf8"));

test("accepts the tracked catalog with explicit comparison scope", () => assert.deepEqual(validateCatalog(catalog), []));
test("rejects an availability-like status outside the four-state vocabulary", () => {
  const changed = structuredClone(catalog); changed.capabilities[0].status = "available";
  assert.match(validateCatalog(changed).join("\n"), /invalid status/);
});
test("does not allow the catalog to claim Production before release acceptance", () => {
  assert.match(validateCatalog({ ...catalog, productionAvailability: "AVAILABLE" }).join("\n"), /must remain BLOCKED/);
});
test("blocks an unreviewed KSAS equivalence claim without a validated connector", () => {
  const changed = structuredClone(catalog);
  changed.comparisonClaims.ksasEquivalent = true;
  changed.comparisonClaims.status = "ALLOWED";
  assert.match(validateCatalog(changed).join("\n"), /validated contracted machinery connector/);
});
test("does not permit public scope expansion", () => {
  assert.match(validateCatalog({ ...catalog, publicScope: "all KSAS capabilities" }).join("\n"), /publicScope/);
});
