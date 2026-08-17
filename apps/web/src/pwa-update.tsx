import { useEffect, useState } from "react";
import { browserStorage, type StorageGateway } from "./storage";
import { tr } from "./i18n";

export type PwaUpdateResult =
  | { status: "blocked"; pending: number }
  | { status: "blocked-input"; pending: 0 }
  | { status: "current"; pending: 0 }
  | { status: "activating"; pending: 0 };

export function registrationErrorMessage(error: unknown): string {
  const detail = error instanceof Error ? `${error.name}: ${error.message}` : String(error);
  return /\b(?:ssl|certificate|cert)\b/i.test(detail)
    ? tr("pwa_update.tls_error")
    : tr("pwa_update.l66.4");
}

export async function activateWaitingWorker(
  registration: Pick<ServiceWorkerRegistration, "waiting">,
  storage: Pick<StorageGateway, "pendingCount">,
  beforeActivate: () => void = () => undefined,
  hasOpenForm: () => boolean = () => typeof document !== "undefined" && document.querySelector("form") !== null,
): Promise<PwaUpdateResult> {
  const waiting = registration.waiting;
  const pending = await storage.pendingCount();
  if (pending > 0) return { status: "blocked", pending };
  if (hasOpenForm()) return { status: "blocked-input", pending: 0 };
  if (!waiting) return { status: "current", pending: 0 };
  beforeActivate();
  waiting.postMessage({ type: "SKIP_WAITING" });
  return { status: "activating", pending: 0 };
}

export function observeWaitingWorker(
  registration: ServiceWorkerRegistration,
  onWaiting: (registration: ServiceWorkerRegistration) => void,
  hasActiveController: () => boolean = () => typeof navigator !== "undefined" && navigator.serviceWorker.controller !== null,
): () => void {
  const notifyWhenInstalled = (worker: ServiceWorker | null) => {
    if (!worker) return () => undefined;
    const onStateChange = () => {
      if (worker.state === "installed" && registration.waiting && hasActiveController()) onWaiting(registration);
    };
    worker.addEventListener("statechange", onStateChange);
    onStateChange();
    return () => worker.removeEventListener("statechange", onStateChange);
  };
  if (registration.waiting && hasActiveController()) onWaiting(registration);
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
    navigator.serviceWorker.register("/sw.js", { updateViaCache: "none" }).then((value) => {
      detach = observeWaitingWorker(value, setRegistration);
    }).catch((registrationError: unknown) => setError(registrationErrorMessage(registrationError)));
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
      if (result.status === "current") {
        window.location.reload();
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
