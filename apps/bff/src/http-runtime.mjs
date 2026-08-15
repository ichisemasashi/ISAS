import { once } from "node:events";
import { createServer } from "node:http";

class BodyTooLargeError extends Error {}
class RequestDeadlineError extends Error {}

function jsonResponse(status, body, extraHeaders = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Cache-Control": "no-store",
      "Content-Type": "application/json; charset=utf-8",
      "X-Content-Type-Options": "nosniff",
      ...extraHeaders,
    },
  });
}

function incomingHeaders(message) {
  const headers = new Headers();
  for (let index = 0; index < message.rawHeaders.length; index += 2) {
    headers.append(message.rawHeaders[index], message.rawHeaders[index + 1]);
  }
  return headers;
}

async function incomingBody(message, limit) {
  if (message.method === "GET" || message.method === "HEAD") return undefined;
  const declared = message.headers["content-length"];
  if (declared != null) {
    if (!/^[0-9]+$/.test(declared)) throw new TypeError("Invalid Content-Length");
    if (Number(declared) > limit) throw new BodyTooLargeError("Request body exceeds the runtime limit");
  }
  const chunks = [];
  let length = 0;
  for await (const chunk of message) {
    length += chunk.length;
    if (length > limit) throw new BodyTooLargeError("Request body exceeds the runtime limit");
    chunks.push(chunk);
  }
  return length ? Buffer.concat(chunks, length) : undefined;
}

async function toRequest(message, config, signal) {
  const body = await incomingBody(message, config.bodyLimitBytes);
  const init = { method: message.method, headers: incomingHeaders(message), signal };
  if (body) init.body = body;
  return new Request(new URL(message.url || "/", config.origin), init);
}

async function writeResponse(message, response, nodeResponse) {
  const headers = {};
  for (const [name, value] of response.headers) headers[name] = value;
  if (typeof response.headers.getSetCookie === "function") {
    const cookies = response.headers.getSetCookie();
    if (cookies.length) headers["set-cookie"] = cookies;
  }
  nodeResponse.writeHead(response.status, headers);
  if (message.method === "HEAD" || !response.body) {
    nodeResponse.end();
    return;
  }
  try {
    for await (const chunk of response.body) {
      if (!nodeResponse.write(chunk)) await once(nodeResponse, "drain");
    }
    nodeResponse.end();
  } catch {
    nodeResponse.destroy();
  }
}

function deadline(promise, milliseconds, controller) {
  let timer;
  const expired = new Promise((_, reject) => {
    timer = setTimeout(() => {
      controller.abort(new RequestDeadlineError("Request deadline exceeded"));
      reject(new RequestDeadlineError("Request deadline exceeded"));
    }, milliseconds);
  });
  return Promise.race([promise, expired]).finally(() => clearTimeout(timer));
}

function closeServer(server) {
  return new Promise((resolve, reject) => {
    try {
      server.close((error) => {
        if (!error || error.code === "ERR_SERVER_NOT_RUNNING") resolve();
        else reject(error);
      });
      server.closeIdleConnections?.();
    } catch (error) {
      if (error?.code === "ERR_SERVER_NOT_RUNNING") resolve();
      else reject(error);
    }
  });
}

function wait(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

export function createHttpRuntime({ config, handler, readinessProbe, closeResources, logger = console, serverFactory = createServer }) {
  if (typeof handler !== "function") throw new Error("HTTP handler is required");
  if (typeof readinessProbe !== "function") throw new Error("readinessProbe is required");
  if (typeof closeResources !== "function") throw new Error("closeResources is required");

  let state = "starting";
  let shutdownPromise;
  let readinessResult = { checkedAt: 0, ready: false };
  const inflight = new Set();

  async function ready() {
    if (state !== "ready") return false;
    const now = Date.now();
    if (now - readinessResult.checkedAt < config.readinessCacheMs) return readinessResult.ready;
    try {
      await readinessProbe();
      readinessResult = { checkedAt: now, ready: true };
    } catch (error) {
      readinessResult = { checkedAt: now, ready: false };
      logger.warn?.("readiness_failed", { code: error?.code || "dependency_unavailable" });
    }
    return readinessResult.ready;
  }

  async function dispatch(message) {
    const path = new URL(message.url || "/", config.origin).pathname;
    if (path === "/health/live") {
      return jsonResponse(200, { status: "live", deploymentId: config.deploymentId });
    }
    if (path === "/health/ready" || path === "/healthz") {
      const isReady = await ready();
      return jsonResponse(isReady ? 200 : 503, {
        status: isReady ? "ready" : "not_ready",
        deploymentId: config.deploymentId,
      }, isReady ? {} : { "Retry-After": "5" });
    }
    if (state !== "ready") return jsonResponse(503, { error: "service_draining" }, { Connection: "close", "Retry-After": "5" });

    const controller = new AbortController();
    message.once("aborted", () => controller.abort(new Error("Client aborted request")));
    const work = (async () => handler(await toRequest(message, config, controller.signal)))();
    inflight.add(work);
    work.then(() => inflight.delete(work), () => inflight.delete(work));
    try {
      return await deadline(work, config.requestTimeoutMs, controller);
    } catch (error) {
      if (error instanceof BodyTooLargeError) return jsonResponse(413, { error: "request_too_large" }, { Connection: "close" });
      if (error instanceof RequestDeadlineError) return jsonResponse(504, { error: "request_timeout" }, { Connection: "close" });
      if (error?.name === "AbortError") return null;
      logger.error?.("request_failed", { code: error?.code || "unhandled" });
      return jsonResponse(500, { error: "request_failed" });
    }
  }

  const server = serverFactory({
    keepAliveTimeout: config.keepAliveTimeoutMs,
    requestTimeout: config.requestTimeoutMs,
    headersTimeout: config.headersTimeoutMs,
    maxHeaderSize: 16 * 1024,
  }, (message, response) => {
    dispatch(message).then((result) => {
      if (result && !response.destroyed) return writeResponse(message, result, response);
      if (!response.destroyed) response.destroy();
      return undefined;
    }).catch(() => response.destroy());
  });
  server.maxHeadersCount = 100;
  server.maxRequestsPerSocket = 1000;

  return Object.freeze({
    server,
    get state() { return state; },
    get inflightCount() { return inflight.size; },
    async start() {
      if (state !== "starting") throw new Error("HTTP runtime has already started");
      server.listen({ host: config.host, port: config.port });
      await once(server, "listening");
      state = "ready";
      logger.info?.("bff_started", { deploymentId: config.deploymentId, address: server.address() });
      return server.address();
    },
    async shutdown(reason = "operator") {
      if (shutdownPromise) return shutdownPromise;
      shutdownPromise = (async () => {
        state = "draining";
        readinessResult = { checkedAt: Date.now(), ready: false };
        logger.info?.("bff_draining", { reason, inflight: inflight.size });
        const serverClosed = closeServer(server);
        const drained = Promise.allSettled([...inflight]);
        let forced = false;
        await Promise.race([
          Promise.all([serverClosed, drained]),
          wait(config.drainTimeoutMs).then(() => { forced = true; }),
        ]);
        if (forced) {
          server.closeAllConnections?.();
          logger.error?.("bff_drain_timeout", { inflight: inflight.size });
        }
        try {
          await Promise.race([
            closeResources(),
            wait(config.drainTimeoutMs).then(() => { throw new Error("Resource drain timed out"); }),
          ]);
        } finally {
          state = "stopped";
        }
        if (forced) throw new Error("HTTP connection drain timed out");
        logger.info?.("bff_stopped", { reason });
      })();
      return shutdownPromise;
    },
  });
}
