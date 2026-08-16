import { useEffect, useState } from "react";
import type { MvpGateway, TenantAnalytics } from "./api";

function hours(seconds: number) { return `${(seconds / 3600).toLocaleString("ja-JP", { maximumFractionDigits: 1 })}時間`; }

export function TenantAnalyticsPanel({ api, contextId, online }: { api: MvpGateway; contextId: string; online: boolean }) {
  const [snapshot, setSnapshot] = useState<TenantAnalytics | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    if (!online || !api.getTenantAnalytics) return;
    const controller = new AbortController();
    api.getTenantAnalytics(contextId, controller.signal).then(setSnapshot).catch((reason) => {
      if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : "分析を取得できませんでした。");
    });
    return () => controller.abort();
  }, [api, contextId, online]);

  return <section className="queue-panel" aria-labelledby="tenant-analytics-title">
    <h2 id="tenant-analytics-title">計画対実績</h2>
    <p>業務データベースから直接集計しています。DWH停止中も利用できます。</p>
    {!online && <p role="status">分析の更新にはオンライン接続が必要です。</p>}
    {error && <p role="alert">{error}</p>}
    {!snapshot && online && !error && <p role="status">分析を読み込んでいます。</p>}
    {snapshot?.freshness.some((item) => item.status !== "fresh") && <p className="warning-banner" role="status">
      欠測または24時間以上更新されていない指標があります。各作付の欠測表示を確認してください。
    </p>}
    {snapshot?.plans.map((plan) => <article key={plan.cropPlanId}>
      <strong>{plan.cropName || "作付"}{plan.varietyName ? `・${plan.varietyName}` : ""}</strong>
      <p>進捗 {plan.progressPercent ?? 0}% ／ 作業時間 {hours(plan.actualWorkSeconds)}（計画 {hours(plan.plannedWorkSeconds)}）</p>
      <p>収量 {plan.actualYieldKg == null ? "欠測" : `${plan.actualYieldKg.toLocaleString("ja-JP")}kg`}（目標 {plan.targetYieldKg == null ? "未設定" : `${plan.targetYieldKg.toLocaleString("ja-JP")}kg`}）</p>
      <p>農薬使用量 {plan.pesticideAmount == null ? "欠測" : plan.pesticideAmount.toLocaleString("ja-JP")}</p>
      {plan.missingMetrics.length > 0 && <p>欠測: {plan.missingMetrics.join("、")}</p>}
    </article>)}
    {snapshot && snapshot.plans.length === 0 && <p>表示できる作付計画がありません。</p>}
  </section>;
}
