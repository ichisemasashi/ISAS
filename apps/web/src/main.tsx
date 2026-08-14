import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { AuthBoundary } from "./AuthBoundary";
import { createBffAuthGateway, demoAuthGateway } from "./auth";
import "./styles.css";

if ("serviceWorker" in navigator && import.meta.env.PROD) {
  window.addEventListener("load", () => navigator.serviceWorker.register("/sw.js"));
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <AuthBoundary gateway={import.meta.env.DEV ? demoAuthGateway : createBffAuthGateway()} />
  </StrictMode>,
);
