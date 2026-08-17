import { render, screen } from "@testing-library/react";
import { vi } from "vitest";
import type { MvpGateway, TenantAnalytics } from "./api";
import { TenantAnalyticsPanel } from "./TenantAnalyticsPanel";

test("shows source coverage and hides actual indicators with no data source", async () => {
  const snapshot: TenantAnalytics = {
    source: "operational_db", dwhRequired: false, generatedAt: "2026-08-17T00:00:00Z",
    sourceProfile: { manualRecords: 2, machineRecords: 0, manualPercent: 100, machinePercent: 0 },
    coverage: [
      { metric: "plan_progress", available: true, coveredPlans: 1, totalPlans: 1, percent: 100, inputMode: "manual", freshestAt: "2026-08-17T00:00:00Z" },
      { metric: "work_actual", available: false, coveredPlans: 0, totalPlans: 1, percent: 0, inputMode: "none", freshestAt: null },
      { metric: "yield_actual", available: false, coveredPlans: 0, totalPlans: 1, percent: 0, inputMode: "none", freshestAt: null },
      { metric: "material_actual", available: false, coveredPlans: 0, totalPlans: 1, percent: 0, inputMode: "none", freshestAt: null },
    ],
    freshness: [{ source: "plan", freshestAt: "2026-08-17T00:00:00Z", status: "fresh", ageSeconds: 0 }], materials: [],
    plans: [{ cropPlanId: "plan-1", cropName: "水稲", targetYieldKg: 600, actualYieldKg: null,
      progressPercent: 50, plannedWorkSeconds: 3600, actualWorkSeconds: 0, pesticideAmount: null,
      missingMetrics: ["work_actual", "yield_actual", "material_actual"], freshestAt: "2026-08-17T00:00:00Z" }],
  };
  const api = { getTenantAnalytics: vi.fn(async () => snapshot) } as unknown as MvpGateway;
  render(<TenantAnalyticsPanel api={api} contextId="context-1" online/>);

  expect(await screen.findByText("入力内訳: 手入力 100%、機械入力 0%")).toBeInTheDocument();
  expect(screen.getByText("作業実績: データ源なし（指標は非表示）")).toBeInTheDocument();
  expect(screen.queryByText(/^作業時間/)).not.toBeInTheDocument();
  expect(screen.queryByText(/^収量 /)).not.toBeInTheDocument();
  expect(screen.queryByText(/^農薬使用量/)).not.toBeInTheDocument();
});
