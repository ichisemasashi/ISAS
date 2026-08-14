import { useEffect, useRef, useState } from "react";
import { App } from "./App";
import type { MvpGateway } from "./api";
import type { AppAuthorization, AuthBootstrap, AuthGateway, RequestContext } from "./auth";

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
        if (!firstTenant) return setState({ status: "error", message: "利用できる組織がありません。管理者へ連絡してください。" });
        const context = await gateway.createContext(firstTenant.id, bootstrap.csrfToken, controller.signal);
        setState({ status: "ready", bootstrap, context });
      } catch (error) {
        if (controller.signal.aborted) return;
        setState({ status: "error", message: error instanceof Error ? error.message : "認証状態を確認できませんでした。" });
      }
    })();
    return () => controller.abort();
  }, [gateway]);

  if (state.status === "loading") return <AuthScreen title="認証状態を確認しています" description="安全なセッションと所属組織を確認しています。" busy />;
  if (state.status === "anonymous") return <AuthScreen title="ISASへログイン" description="組織の認証画面へ移動します。" actionLabel="ログインする" onAction={() => gateway.login(window.location.href)} />;
  if (state.status === "error") return <AuthScreen title="ログイン状態を確認できません" description={state.message} actionLabel="再読み込み" onAction={() => window.location.reload()} />;

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
      setState({ status: "error", message: "組織の切り替えに失敗しました。元の画面を再読み込みしてください。" });
    }
  };

  return <App api={api} csrfToken={state.bootstrap.csrfToken} authorization={authorization} tenants={state.bootstrap.tenants} onTenantChange={switchTenant} />;
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
