export function BuildStatusBanner() {
  const build = __ISAS_BUILD_INFO__;
  return <div className="build-status-banner" role="status" aria-label="build information">
    ISAS {build.version} ({build.commit.slice(0, 12)}) · {build.releaseClass} · Production {build.productionStatus} · 提供範囲: 圃場・指示・日誌・農薬・在庫のself-host／offline core（KSAS同等ではありません）
  </div>;
}
