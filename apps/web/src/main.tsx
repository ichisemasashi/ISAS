import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { AuthBoundary } from "./AuthBoundary";
import { createMvpGateway } from "./api";
import { createBffAuthGateway } from "./auth";
import "./styles.css";

if ("serviceWorker" in navigator && import.meta.env.PROD) {
  window.addEventListener("load", () => navigator.serviceWorker.register("/sw.js"));
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <AuthBoundary gateway={createBffAuthGateway()} api={createMvpGateway()} />
  </StrictMode>,
);
