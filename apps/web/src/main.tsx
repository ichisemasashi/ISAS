import { StrictMode, useState } from "react";
import { createRoot } from "react-dom/client";
import { AuthBoundary } from "./AuthBoundary";
import { createMvpGateway } from "./api";
import { createBffAuthGateway, demoAuthGateway } from "./auth";
import { PwaUpdateGate } from "./pwa-update";
import "./styles.css";

const root = createRoot(document.getElementById("root")!);
const search = new URLSearchParams(window.location.search);
const utMode = import.meta.env.DEV && search.get("ut") === "1";

async function renderApplication() {
  if (utMode) {
    const { resetUtBrowserStorage, utGateway } = await import("./ut-fixture");
    if (search.get("reset") === "1") await resetUtBrowserStorage();
    root.render(<StrictMode><UtModeBanner/><PwaUpdateGate/><AuthBoundary gateway={demoAuthGateway} api={utGateway} /></StrictMode>);
    return;
  }
  root.render(<StrictMode><PwaUpdateGate/><AuthBoundary gateway={createBffAuthGateway()} api={createMvpGateway()} /></StrictMode>);
}

function UtModeBanner() {
  const [offline, setOffline] = useState(false);
  const toggle = () => {
    const next = !offline;
    setOffline(next);
    window.dispatchEvent(new Event(next ? "offline" : "online"));
  };
  return <div className={`ut-mode-banner ${offline ? "is-offline" : ""}`} role="note"><span>ユーザビリティ試験用・架空データです。実際の農薬散布には使用しないでください。</span><button type="button" tabIndex={-1} onClick={toggle}>{offline ? "進行役：通信を戻す" : "進行役：圏外を模擬"}</button></div>;
}

void renderApplication();
