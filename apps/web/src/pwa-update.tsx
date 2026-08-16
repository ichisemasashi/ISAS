import { useEffect, useState } from "react";
import { browserStorage, type StorageGateway } from "./storage";
import { tr } from "./i18n";

export type PwaUpdateResult =
  | { status: "blocked"; pending: number }
  | { status: "blocked-input"; pending: 0 }
  | { status: "activating"; pending: 0 };

export async function activateWaitingWorker(
  registration: Pick<ServiceWorkerRegistration, "waiting">,
  storage: Pick<StorageGateway, "pendingCount">,
  beforeActivate: () => void = () => undefined,
  hasOpenForm: () => boolean = () => typeof document !== "undefined" && document.querySelector("form") !== null,
): Promise<PwaUpdateResult> {
  const pending = await storage.pendingCount();
  if (pending > 0) return { status: "blocked", pending };
  if (hasOpenForm()) return { status: "blocked-input", pending: 0 };
  if (!registration.waiting) throw new Error(tr("pwa_update.l18.1"));
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
  const [blockedInput, setBlockedInput] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!("serviceWorker" in navigator) || !import.meta.env.PROD) return;
    let detach: () => void = () => undefined;
    if (navigator.storage?.persist) {
      navigator.storage.persist().then((granted) => {
        if (!granted) setError(tr("pwa_update.l61.2"));
      }).catch(() => setError(tr("pwa_update.l62.3")));
    }
    navigator.serviceWorker.register("/sw.js").then((value) => {
      detach = observeWaitingWorker(value, setRegistration);
    }).catch(() => setError(tr("pwa_update.l66.4")));
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
      if (result.status === "blocked-input") {
        setBlockedInput(true);
        return;
      }
    } catch {
      setError(tr("pwa_update.l86.5"));
    }
  };

  if (!registration && !error) return null;
  return (
    <aside className="pwa-update" role="status" aria-live="polite">
      {registration && <>
        <strong>{tr("pwa_update.l94.6")}</strong>
        <span>{blockedInput ? tr("pwa_update.l95.7") : blocked === null ? tr("pwa_update.l95.8") : tr("pwa_update.l95.9", [blocked])}</span>
        <button type="button" onClick={() => void update()}>{tr("pwa_update.l96.10")}</button>
      </>}
      {error && <span>{error}</span>}
    </aside>
  );
}
