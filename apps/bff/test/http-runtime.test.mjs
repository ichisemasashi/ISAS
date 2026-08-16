import assert from "node:assert/strict";
import { test } from "node:test";
import { createHttpRuntime } from "../src/http-runtime.mjs";

function config(overrides = {}) {
  return {
    origin: "https://staging.isas.example",
    deploymentId: "isas-jp-stg-01",
    deploymentProfile: "staging",
    host: "127.0.0.1",
    port: 0,
    requestTimeoutMs: 50,
    headersTimeoutMs: 50,
    keepAliveTimeoutMs: 50,
    drainTimeoutMs: 300,
    bodyLimitBytes: 8,
    readinessCacheMs: 1,
    ...overrides,
  };
}

async function start(options = {}) {
  const runtime = createHttpRuntime({
    config: config(options.config),
    handler: options.handler || (async () => new Response("ok")),
    readinessProbe: options.readinessProbe || (async () => undefined),
    closeResources: options.closeResources || (async () => undefined),
    logger: { info() {}, warn() {}, error() {} },
  });
  const address = await runtime.start();
  return { runtime, base: `http://127.0.0.1:${address.port}` };
}

test("exposes live and dependency-aware ready endpoints", async () => {
  let available = true;
  const { runtime, base } = await start({
    readinessProbe: async () => { if (!available) throw new Error("database unavailable"); },
  });
  assert.equal((await fetch(`${base}/health/live`)).status, 200);
  const ready = await fetch(`${base}/health/ready`);
  assert.equal(ready.status, 200);
  assert.equal((await ready.json()).deploymentProfile, "staging");
  available = false;
  await new Promise((resolve) => setTimeout(resolve, 3));
  const unavailable = await fetch(`${base}/healthz`);
  assert.equal(unavailable.status, 503);
  assert.equal(unavailable.headers.get("retry-after"), "5");
  await runtime.shutdown("test");
});

test("enforces streamed body and application deadline limits", async () => {
  const { runtime, base } = await start({
    handler: async (request) => {
      if (new URL(request.url).pathname === "/slow") {
        await new Promise((resolve) => setTimeout(resolve, 80));
      }
      return new Response("ok");
    },
  });
  const oversized = await fetch(`${base}/body`, { method: "POST", body: "123456789" });
  assert.equal(oversized.status, 413);
  assert.deepEqual(await oversized.json(), { error: "request_too_large" });
  const timedOut = await fetch(`${base}/slow`);
  assert.equal(timedOut.status, 504);
  assert.deepEqual(await timedOut.json(), { error: "request_timeout" });
  await runtime.shutdown("test");
});

test("marks not-ready immediately and drains an in-flight request before closing resources", async () => {
  let enter;
  const entered = new Promise((resolve) => { enter = resolve; });
  let release;
  const blocked = new Promise((resolve) => { release = resolve; });
  const order = [];
  const { runtime, base } = await start({
    config: { requestTimeoutMs: 500 },
    handler: async () => { enter(); await blocked; order.push("request"); return new Response("done"); },
    closeResources: async () => { order.push("resources"); },
  });
  const request = fetch(`${base}/work`);
  await entered;
  const shutdown = runtime.shutdown("SIGTERM");
  assert.equal(runtime.state, "draining");
  release();
  assert.equal(await (await request).text(), "done");
  await shutdown;
  assert.deepEqual(order, ["request", "resources"]);
  assert.equal(runtime.state, "stopped");
});
