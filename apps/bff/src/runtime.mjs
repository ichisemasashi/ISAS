import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { Pool } from "pg";
import { createIsasApplication } from "./application.mjs";
import { createHttpRuntime } from "./http-runtime.mjs";
import { createPriorityPools } from "./postgres-pools.mjs";
import { loadRuntimeConfig, publicRuntimeConfig } from "./runtime-config.mjs";

function assertMethod(object, name, label) {
  if (typeof object?.[name] !== "function") throw new Error(`Runtime adapter ${label}.${name} is required`);
}

function validateAdapters(adapters) {
  if (!adapters || typeof adapters !== "object") throw new Error("createRuntimeAdapters must return an object");
  for (const method of ["put", "take"]) assertMethod(adapters.stores?.loginAttempts, method, "stores.loginAttempts");
  for (const method of ["get", "put", "delete", "touch"]) assertMethod(adapters.stores?.sessions, method, "stores.sessions");
  for (const method of ["get", "put", "delete", "deleteForSession"]) assertMethod(adapters.stores?.contexts, method, "stores.contexts");
  assertMethod(adapters.identityProvider, "authorizationUrl", "identityProvider");
  assertMethod(adapters.identityProvider, "exchangeCode", "identityProvider");
  assertMethod(adapters.users, "resolve", "users");
  assertMethod(adapters.authorization, "listTenants", "authorization");
  assertMethod(adapters.authorization, "deriveContext", "authorization");
  for (const method of ["snapshot", "requestChange", "decideChange", "createPrivacyRequest", "transitionPrivacyRequest"]) {
    assertMethod(adapters.securityAdministration, method, "securityAdministration");
  }
  for (const method of ["objectKey", "stage", "markReady", "signedDownload", "reconcile"]) assertMethod(adapters.attachmentStorage, method, "attachmentStorage");
  for (const method of ["packManifest", "readRange"]) assertMethod(adapters.mapStorage, method, "mapStorage");
  return adapters;
}

async function defaultAdapterLoader(specifier) {
  const url = specifier.startsWith("file:") ? specifier : pathToFileURL(resolve(specifier)).href;
  return import(url);
}

export function createJsonLogger(output = process.stdout, errorOutput = process.stderr) {
  const redact = (value) => typeof value === "string"
    ? value
      .replace(/postgres(?:ql)?:\/\/[^\s]+/gi, "[REDACTED_DATABASE_URL]")
      .replace(/(password|secret|token|credential)\s*[=:]\s*[^\s,;]+/gi, "$1=[REDACTED]")
    : value;
  const write = (stream, level, event, fields = {}) => {
    const safe = Object.fromEntries(Object.entries(fields)
      .filter(([key]) => !/password|secret|token|url|credential/i.test(key))
      .map(([key, value]) => [key, redact(value)]));
    stream.write(`${JSON.stringify({ timestamp: new Date().toISOString(), level, event, ...safe })}\n`);
  };
  return Object.freeze({
    info: (event, fields) => write(output, "info", event, fields),
    warn: (event, fields) => write(errorOutput, "warn", event, fields),
    error: (event, fields) => write(errorOutput, "error", event, fields),
  });
}

export async function startProductionRuntime({
  env = process.env,
  PoolClass = Pool,
  adapterLoader = defaultAdapterLoader,
  logger = createJsonLogger(),
  serverFactory,
} = {}) {
  const config = loadRuntimeConfig(env);
  logger.info?.("bff_config_valid", publicRuntimeConfig(config));
  const pools = createPriorityPools(config, { PoolClass, logger });
  let adapters;
  let runtime;
  try {
    await pools.startupCheck();
    const module = await adapterLoader(config.adapterModule);
    if (typeof module.createRuntimeAdapters !== "function") throw new Error("Adapter module must export createRuntimeAdapters");
    adapters = validateAdapters(await module.createRuntimeAdapters({ config, pools, logger, env }));
    if (typeof adapters.startupCheck === "function") await adapters.startupCheck();
    const handler = createIsasApplication({
      origin: config.origin,
      redirectUri: config.redirectUri,
      stores: adapters.stores,
      identityProvider: adapters.identityProvider,
      users: adapters.users,
      authorization: adapters.authorization,
      securityAdministration: adapters.securityAdministration,
      attachmentStorage: adapters.attachmentStorage,
      mapStorage: adapters.mapStorage,
      databasePools: Object.fromEntries(["p0", "p1", "p2"].map((name) => [name, { pool: pools[name], expectedRole: config.pools[name].expectedRole }])),
      logger,
    });
    runtime = createHttpRuntime({
      config,
      handler,
      readinessProbe: async () => {
        await pools.readinessCheck();
        if (typeof adapters.readinessCheck === "function") await adapters.readinessCheck();
      },
      closeResources: async () => {
        const errors = [];
        if (typeof adapters.close === "function") {
          try { await adapters.close(); } catch (error) { errors.push(error); }
        }
        try { await pools.end(); } catch (error) { errors.push(error); }
        if (errors.length) throw new AggregateError(errors, "Runtime resources failed to close");
      },
      logger,
      ...(serverFactory ? { serverFactory } : {}),
    });
    await runtime.start();
    return Object.freeze({ config: publicRuntimeConfig(config), pools, runtime, shutdown: (reason) => runtime.shutdown(reason) });
  } catch (error) {
    if (runtime) await Promise.allSettled([runtime.shutdown("startup_failure")]);
    else {
      if (typeof adapters?.close === "function") await Promise.allSettled([adapters.close()]);
      await Promise.allSettled([pools.end()]);
    }
    throw error;
  }
}

export const runtimeContract = Object.freeze({ validateAdapters });
