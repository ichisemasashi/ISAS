import { useEffect, useState } from "react";
import type { MvpGateway, TenantAnalytics } from "./api";
import { formatNumber, tr } from "./i18n";

function hours(seconds: number) { return tr("tenantanalyticspanel.l4.1", [formatNumber(seconds / 3600, { maximumFractionDigits: 1 })]); }

export function TenantAnalyticsPanel({ api, contextId, online }: { api: MvpGateway; contextId: string; online: boolean }) {
  const [snapshot, setSnapshot] = useState<TenantAnalytics | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    if (!online || !api.getTenantAnalytics) return;
    const controller = new AbortController();
    api.getTenantAnalytics(contextId, controller.signal).then(setSnapshot).catch((reason) => {
      if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : tr("tenantanalyticspanel.l13.2"));
    });
    return () => controller.abort();
  }, [api, contextId, online]);

  return <section className="queue-panel" aria-labelledby="tenant-analytics-title">
    <h2 id="tenant-analytics-title">{tr("tenantanalyticspanel.l19.3")}</h2>
    <p>{tr("tenantanalyticspanel.l20.4")}</p>
    {!online && <p role="status">{tr("tenantanalyticspanel.l21.5")}</p>}
    {error && <p role="alert">{error}</p>}
    {!snapshot && online && !error && <p role="status">{tr("tenantanalyticspanel.l23.6")}</p>}
    {snapshot?.freshness.some((item) => item.status !== "fresh") && <p className="warning-banner" role="status">
      {tr("tenantanalyticspanel.l24.7")}
    </p>}
    {snapshot?.plans.map((plan) => <article key={plan.cropPlanId}>
      <strong>{plan.cropName || tr("tenantanalyticspanel.l28.8")}{plan.varietyName ? tr("tenantanalyticspanel.l28.9", [plan.varietyName]) : ""}</strong>
      <p>{tr("tenantanalyticspanel.l29.10")} {plan.progressPercent ?? 0}{tr("tenantanalyticspanel.l29.11")} {hours(plan.actualWorkSeconds)}{tr("tenantanalyticspanel.l29.12")} {hours(plan.plannedWorkSeconds)}）</p>
      <p>{tr("tenantanalyticspanel.l30.13")} {plan.actualYieldKg == null ? tr("tenantanalyticspanel.l30.14") : `${formatNumber(plan.actualYieldKg)}kg`}{tr("tenantanalyticspanel.l30.15")} {plan.targetYieldKg == null ? tr("tenantanalyticspanel.l30.16") : `${formatNumber(plan.targetYieldKg)}kg`}）</p>
      <p>{tr("tenantanalyticspanel.l31.17")} {plan.pesticideAmount == null ? tr("tenantanalyticspanel.l31.18") : formatNumber(plan.pesticideAmount)}</p>
      {plan.missingMetrics.length > 0 && <p>{tr("tenantanalyticspanel.l32.19")} {plan.missingMetrics.join("、")}</p>}
    </article>)}
    {snapshot && snapshot.plans.length === 0 && <p>{tr("tenantanalyticspanel.l34.20")}</p>}
  </section>;
}
