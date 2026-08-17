import { StrictMode, useState } from "react";
import { createRoot } from "react-dom/client";
import { AuthBoundary } from "./AuthBoundary";
import { createMvpGateway } from "./api";
import { createBffAuthGateway, demoAuthGateway } from "./auth";
import { PwaUpdateGate } from "./pwa-update";
import "./styles.css";
import { configureRecoveryPublicKey } from "./device-security";
import { tr } from "./i18n";
import { BuildStatusBanner } from "./BuildStatusBanner";

const root = createRoot(document.getElementById("root")!);
const search = new URLSearchParams(window.location.search);
const utMode = import.meta.env.DEV && search.get("ut") === "1";

async function renderApplication() {
  if (utMode) {
    const { resetUtBrowserStorage, utGateway } = await import("./ut-fixture");
    if (search.get("reset") === "1") await resetUtBrowserStorage();
    await configureDeviceRecovery();
    root.render(<StrictMode><BuildStatusBanner/><UtModeBanner/><PwaUpdateGate/><AuthBoundary gateway={demoAuthGateway} api={utGateway} /></StrictMode>);
    return;
  }
  await configureDeviceRecovery();
  root.render(<StrictMode><BuildStatusBanner/><PwaUpdateGate/><AuthBoundary gateway={createBffAuthGateway()} api={createMvpGateway()} /></StrictMode>);
}

async function configureDeviceRecovery() {
  if (utMode) {
    const pair = await crypto.subtle.generateKey({ name: "RSA-OAEP", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["wrapKey", "unwrapKey"]);
    await configureRecoveryPublicKey("ut-ephemeral-recovery", await crypto.subtle.exportKey("jwk", pair.publicKey));
    return;
  }
  try {
    const response = await fetch("/device-security-config.json", { cache: "no-store", credentials: "same-origin" });
    if (!response.ok) return;
    const value = await response.json() as { keyId?: unknown; recoveryPublicJwk?: unknown };
    if (typeof value.keyId !== "string" || !value.recoveryPublicJwk) return;
    await configureRecoveryPublicKey(value.keyId, value.recoveryPublicJwk as JsonWebKey);
  } catch {
    // Read-only online use may continue; encrypted outbox creation remains fail-closed.
  }
}

function UtModeBanner() {
  const [offline, setOffline] = useState(false);
  const toggle = () => {
    const next = !offline;
    setOffline(next);
    window.dispatchEvent(new Event(next ? "offline" : "online"));
  };
  return <div className={`ut-mode-banner ${offline ? "is-offline" : ""}`} role="note"><span>{tr("main.l50.1")}</span><button type="button" tabIndex={-1} onClick={toggle}>{offline ? tr("main.l50.2") : tr("main.l50.3")}</button></div>;
}

void renderApplication();
