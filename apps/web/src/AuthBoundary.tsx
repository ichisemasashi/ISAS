import { useEffect, useRef, useState } from "react";
import { App } from "./App";
import type { MvpGateway } from "./api";
import type { AppAuthorization, AuthBootstrap, AuthGateway, RequestContext } from "./auth";
import { revokeDeviceAccess } from "./device-security";
import { browserStorage } from "./storage";
import { tr } from "./i18n";

type BoundaryState =
  | { status: "loading" }
  | { status: "anonymous" }
  | { status: "error"; message: string }
  | { status: "ready"; bootstrap: AuthBootstrap; context: RequestContext };

export function AuthBoundary({ gateway, api }: { gateway: AuthGateway; api: MvpGateway }) {
  const [state, setState] = useState<BoundaryState>({ status: "loading" });
  const contextRequest = useRef(0);

  useEffect(() => {
    const controller = new AbortController();
    (async () => {
      try {
        const bootstrap = await gateway.bootstrap(controller.signal);
        if (!bootstrap) return setState({ status: "anonymous" });
        const firstTenant = bootstrap.tenants[0];
        if (!firstTenant) return setState({ status: "error", message: tr("authboundary.l25.1") });
        const context = await gateway.createContext(firstTenant.id, bootstrap.csrfToken, controller.signal);
        setState({ status: "ready", bootstrap, context });
      } catch (error) {
        if (controller.signal.aborted) return;
        setState({ status: "error", message: error instanceof Error ? error.message : tr("authboundary.l30.2") });
      }
    })();
    return () => controller.abort();
  }, [gateway]);

  if (state.status === "loading") return <AuthScreen title={tr("authboundary.l36.3")} description={tr("authboundary.l36.4")} busy />;
  if (state.status === "anonymous") return <AuthScreen title={tr("authboundary.l37.5")} description={tr("authboundary.l37.6")} actionLabel={tr("authboundary.l37.7")} onAction={() => gateway.login(window.location.href)} />;
  if (state.status === "error") return <AuthScreen title={tr("authboundary.l38.8")} description={state.message} actionLabel={tr("authboundary.l38.9")} onAction={() => window.location.reload()} />;

  const authorization: AppAuthorization = {
    user: state.bootstrap.user,
    context: state.context,
    accessMode: state.bootstrap.accessMode,
    accessModeExpiresAt: state.bootstrap.accessModeExpiresAt,
  };

  const switchTenant = async (tenantId: string) => {
    const requestId = ++contextRequest.current;
    try {
      const context = await gateway.createContext(tenantId, state.bootstrap.csrfToken);
      if (requestId !== contextRequest.current) return;
      setState({ ...state, context });
    } catch {
      if (requestId !== contextRequest.current) return;
      setState({ status: "error", message: tr("authboundary.l55.10") });
    }
  };

  const logout = async () => {
    const pending = await browserStorage.pendingCount();
    if (pending > 0) throw new Error(tr("authboundary.l61.11", [pending]));
    await revokeDeviceAccess(state.context.tenantId, []);
    await gateway.logout(state.bootstrap.csrfToken);
  };

  return <App key={state.context.contextId} api={api} csrfToken={state.bootstrap.csrfToken} authorization={authorization} tenants={state.bootstrap.tenants} onTenantChange={switchTenant} onLogout={logout} />;
}

function AuthScreen({ title, description, actionLabel, onAction, busy = false }: { title: string; description: string; actionLabel?: string; onAction?: () => void; busy?: boolean }) {
  return <main className="auth-screen" aria-busy={busy}>
    <div className="auth-card">
      <span className="auth-logo" aria-hidden="true">ISAS</span>
      <h1>{title}</h1>
      <p>{description}</p>
      {actionLabel && <button className="primary-action" onClick={onAction}>{actionLabel}</button>}
    </div>
  </main>;
}
