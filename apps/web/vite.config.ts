import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import react from "@vitejs/plugin-react";
import { defineConfig, type Plugin } from "vitest/config";

const MAPLIBRE_ASSETS = ["maplibre-gl-worker.mjs", "maplibre-gl-shared.mjs"] as const;

function maplibreWorkerAssets(): Plugin {
  const directory = resolve(import.meta.dirname, "node_modules/maplibre-gl/dist");
  const sources = Object.fromEntries(MAPLIBRE_ASSETS.map((name) => [name, readFileSync(resolve(directory, name))]));
  return {
    name: "isas-maplibre-worker-assets",
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        const name = new URL(request.url || "/", "http://localhost").pathname.split("/").pop();
        if (!name || !MAPLIBRE_ASSETS.includes(name as typeof MAPLIBRE_ASSETS[number])) return next();
        response.setHeader("Content-Type", "application/javascript; charset=utf-8");
        response.end(sources[name]);
      });
    },
    generateBundle() {
      for (const name of MAPLIBRE_ASSETS) this.emitFile({ type: "asset", fileName: `assets/${name}`, source: sources[name] });
    },
  };
}

export default defineConfig({
  define: {
    __ISAS_BUILD_INFO__: JSON.stringify({
      version: process.env.ISAS_BUILD_VERSION || "0.1.0",
      commit: process.env.ISAS_BUILD_COMMIT || "development",
      releaseClass: "baseline",
      productionStatus: "BLOCKED",
    }),
  },
  plugins: [react(), maplibreWorkerAssets()],
  server: { host: "127.0.0.1", port: 4173, proxy: { "/api": { target: "http://127.0.0.1:3000", changeOrigin: false } } },
  preview: { host: "127.0.0.1", port: 4173 },
  test: {
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts",
    globals: true,
    include: ["src/**/*.test.{ts,tsx}"],
    exclude: ["src/**/._*"],
    css: true,
  },
});
