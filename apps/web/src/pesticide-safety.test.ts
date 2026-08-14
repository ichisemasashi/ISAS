import { describe, expect, test } from "vitest";
import type { PesticideBootstrap } from "./api";
import { evaluatePesticideUse } from "./pesticide-safety";

const bootstrap: PesticideBootstrap = {
  field: { id: "field-1", fieldGroupId: "group-1", name: "北圃場", cropName: "つや姫", timezone: "Asia/Tokyo" },
  release: { id: "release-1", version: "v1", validUntil: "2026-08-20T00:00:00Z", publishedAt: "2026-08-01T00:00:00Z", syncedAt: "2026-08-14T00:00:00Z" },
  chemicals: [{ id: "chemical-1", registrationNumber: "1", name: "水和剤", activeIngredient: "A", applicableCrops: ["つや姫"], dilutionMin: 500, dilutionMax: 1000, maxUses: 3, preharvestDays: 7, revokedOn: null }],
  usage: [{ chemicalId: "chemical-1", usageCount: 2, lastAppliedOn: "2026-08-01" }], inventory: [],
};

describe("offline pesticide safety", () => {
  test("accepts a fresh master within crop, dilution, count and harvest limits", () => {
    expect(evaluatePesticideUse({ bootstrap, chemical: bootstrap.chemicals[0], cropName: "つや姫", dilution: 750, appliedOn: "2026-08-14", plannedHarvestOn: "2026-08-21", now: new Date("2026-08-14T00:00:00Z") })).toMatchObject({ status: "safe", reasons: [] });
  });

  test("blocks a known revoked pesticide even while offline", () => {
    const revoked = { ...bootstrap.chemicals[0], revokedOn: "2026-08-10" };
    expect(evaluatePesticideUse({ bootstrap, chemical: revoked, cropName: "つや姫", dilution: 750, appliedOn: "2026-08-14", now: new Date("2026-08-14T00:00:00Z") })).toMatchObject({ status: "blocked", reasons: ["revoked"] });
  });

  test("marks an expired cache as a manager override warning", () => {
    const stale = { ...bootstrap, release: { ...bootstrap.release!, validUntil: "2026-08-13T00:00:00Z" } };
    expect(evaluatePesticideUse({ bootstrap: stale, chemical: stale.chemicals[0], cropName: "つや姫", dilution: 750, appliedOn: "2026-08-14", now: new Date("2026-08-14T00:00:00Z") })).toMatchObject({ status: "warning", reasons: ["master_stale"], requiresManagerOverride: true });
  });
});
