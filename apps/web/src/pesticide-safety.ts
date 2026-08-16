import type { Agrochemical, PesticideBootstrap } from "./api";
import { tr } from "./i18n";

export type SafetyReason = "master_missing" | "master_stale" | "revoked" | "crop_not_applicable" | "dilution_out_of_range" | "maximum_uses_exceeded" | "preharvest_interval_short";
export type SafetyDecision = { status: "safe" | "warning" | "blocked"; reasons: SafetyReason[]; checkedAt: string; cacheVersion: string | null; cacheValidUntil: string | null; requiresManagerOverride: boolean };

const safetyReasonKeys: Record<SafetyReason, string> = {
  master_missing: "pesticide_safety.l7.1",
  master_stale: "pesticide_safety.l8.2",
  revoked: "pesticide_safety.l9.3",
  crop_not_applicable: "pesticide_safety.l10.4",
  dilution_out_of_range: "pesticide_safety.l11.5",
  maximum_uses_exceeded: "pesticide_safety.l12.6",
  preharvest_interval_short: "pesticide_safety.l13.7",
};

export function safetyReasonLabel(reason: SafetyReason): string { return tr(safetyReasonKeys[reason]); }

function calendarDays(from: string, to: string): number {
  return Math.floor((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86400000);
}

export function evaluatePesticideUse(input: { bootstrap: PesticideBootstrap | null; chemical: Agrochemical | null; cropName: string; dilution: number; appliedOn: string; plannedHarvestOn?: string; now?: Date }): SafetyDecision {
  const now = input.now || new Date();
  const release = input.bootstrap?.release;
  const reasons: SafetyReason[] = [];
  if (!release || !input.chemical) reasons.push("master_missing");
  if (release && Date.parse(release.validUntil) < now.getTime()) reasons.push("master_stale");
  const chemical = input.chemical;
  if (chemical) {
    if (chemical.revokedOn && chemical.revokedOn <= input.appliedOn) reasons.push("revoked");
    if (!chemical.applicableCrops.includes(input.cropName)) reasons.push("crop_not_applicable");
    if (!Number.isFinite(input.dilution) || input.dilution < chemical.dilutionMin || input.dilution > chemical.dilutionMax) reasons.push("dilution_out_of_range");
    const usage = input.bootstrap?.usage.find((item) => item.chemicalId === chemical.id)?.usageCount || 0;
    if (usage + 1 > chemical.maxUses) reasons.push("maximum_uses_exceeded");
    if (input.plannedHarvestOn && calendarDays(input.appliedOn, input.plannedHarvestOn) < chemical.preharvestDays) reasons.push("preharvest_interval_short");
  }
  const blocked = reasons.includes("revoked") || reasons.includes("master_missing");
  return {
    status: blocked ? "blocked" : reasons.length ? "warning" : "safe",
    reasons,
    checkedAt: now.toISOString(),
    cacheVersion: release?.version || null,
    cacheValidUntil: release?.validUntil || null,
    requiresManagerOverride: reasons.includes("master_stale"),
  };
}
