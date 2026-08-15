import { useEffect, useState } from "react";
import { browserStorage, type StorageGateway } from "./storage";

export type PwaUpdateResult =
  | { status: "blocked"; pending: number }
  | { status: "activating"; pending: 0 };

export async function activateWaitingWorker(
  registration: Pick<ServiceWorkerRegistration, "waiting">,
  storage: Pick<StorageGateway, "pendingCount">,
  beforeActivate: () => void = () => undefined,
): Promise<PwaUpdateResult> {
  const pending = await storage.pendingCount();
  if (pending > 0) return { status: "blocked", pending };
  if (!registration.waiting) throw new Error("待機中の更新が見つかりません");
  beforeActivate();
  registration.waiting.postMessage({ type: "SKIP_WAITING" });
  return { status: "activating", pending: 0 };
}

export function observeWaitingWorker(
  registration: ServiceWorkerRegistration,
  onWaiting: (registration: ServiceWorkerRegistration) => void,
): () => void {
  const notifyWhenInstalled = (worker: ServiceWorker | null) => {
    if (!worker) return () => undefined;
    const onStateChange = () => {
      if (worker.state === "installed" && registration.waiting) onWaiting(registration);
    };
    worker.addEventListener("statechange", onStateChange);
    onStateChange();
    return () => worker.removeEventListener("statechange", onStateChange);
  };
  if (registration.waiting) onWaiting(registration);
  let detachWorker = notifyWhenInstalled(registration.installing);
  const onUpdateFound = () => {
    detachWorker();
    detachWorker = notifyWhenInstalled(registration.installing);
  };
  registration.addEventListener("updatefound", onUpdateFound);
  return () => {
    detachWorker();
    registration.removeEventListener("updatefound", onUpdateFound);
  };
}

export function PwaUpdateGate({ storage = browserStorage }: { storage?: Pick<StorageGateway, "pendingCount"> }) {
  const [registration, setRegistration] = useState<ServiceWorkerRegistration | null>(null);
  const [blocked, setBlocked] = useState<number | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!("serviceWorker" in navigator) || !import.meta.env.PROD) return;
    let detach: () => void = () => undefined;
    if (navigator.storage?.persist) {
      navigator.storage.persist().then((granted) => {
        if (!granted) setError("端末による自動削除を防げません。未同期件数を確認し、早めにオンライン同期してください。");
      }).catch(() => setError("端末保存の永続化を確認できませんでした。未同期件数を確認してください。"));
    }
    navigator.serviceWorker.register("/sw.js").then((value) => {
      detach = observeWaitingWorker(value, setRegistration);
    }).catch(() => setError("アプリ更新を確認できませんでした。通信状態を確認してください。"));
    return () => detach();
  }, []);

  const update = async () => {
    if (!registration) return;
    setError("");
    try {
      const result = await activateWaitingWorker(registration, storage, () => {
        navigator.serviceWorker.addEventListener("controllerchange", () => window.location.reload(), { once: true });
      });
      if (result.status === "blocked") {
        setBlocked(result.pending);
        return;
      }
    } catch {
      setError("更新を適用できませんでした。時間をおいて再度お試しください。");
    }
  };

  if (!registration && !error) return null;
  return (
    <aside className="pwa-update" role="status" aria-live="polite">
      {registration && <>
        <strong>アプリの更新があります</strong>
        <span>{blocked === null ? "未同期データがないことを確認してから安全に更新します。" : `未同期${blocked}件を送信するまで更新を保留します。`}</span>
        <button type="button" onClick={() => void update()}>更新する</button>
      </>}
      {error && <span>{error}</span>}
    </aside>
  );
}
