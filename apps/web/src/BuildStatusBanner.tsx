export function BuildStatusBanner() {
  const build = __ISAS_BUILD_INFO__;
  return <div className="build-status-banner" role="status" aria-label="build information">
    ISAS {build.version} ({build.commit.slice(0, 12)}) · {build.releaseClass} · Production {build.productionStatus}
  </div>;
}
