import type { Agrochemical, PesticideBootstrap } from "./api";

export type SafetyReason = "master_missing" | "master_stale" | "revoked" | "crop_not_applicable" | "dilution_out_of_range" | "maximum_uses_exceeded" | "preharvest_interval_short";
export type SafetyDecision = { status: "safe" | "warning" | "blocked"; reasons: SafetyReason[]; checkedAt: string; cacheVersion: string | null; cacheValidUntil: string | null; requiresManagerOverride: boolean };

export const safetyReasonLabel: Record<SafetyReason, string> = {
  master_missing: "農薬マスタが端末にありません。オンラインで更新してください。",
  master_stale: "農薬マスタの有効期限が切れています。責任者確認が必要です。",
  revoked: "この農薬は失効済みです。使用できません。",
  crop_not_applicable: "選択した作物は適用対象外です。",
  dilution_out_of_range: "希釈倍率が登録範囲外です。",
  maximum_uses_exceeded: "今作の使用回数が上限を超えます。",
  preharvest_interval_short: "収穫予定日までの日数が不足しています。",
};

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
