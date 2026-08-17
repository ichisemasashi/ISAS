import assert from "node:assert/strict";
import { test } from "node:test";
import { createJsonLogger, runtimeContract } from "../src/runtime.mjs";
import { loadRuntimeConfig, POOL_CLASSES, publicRuntimeConfig } from "../src/runtime-config.mjs";

function environment(overrides = {}) {
  const env = {
    NODE_ENV: "development",
    ISAS_PUBLIC_ORIGIN: "https://staging.isas.example",
    ISAS_DEPLOYMENT_ID: "isas-jp-stg-01",
    ISAS_JURISDICTION: "JP",
    AWS_REGION: "ap-northeast-1",
    ISAS_RUNTIME_ADAPTER_MODULE: "./runtime-adapters/local.mjs",
  };
  const names = { p0: "P0", authP1: "AUTH_P1", p1: "P1", p2: "P2", ops: "OPS" };
  for (const [index, name] of POOL_CLASSES.entries()) {
    const prefix = `ISAS_DB_${names[name]}`;
    env[`${prefix}_HOST`] = `pool-${index}.internal`;
    env[`${prefix}_NAME`] = "isas";
    env[`${prefix}_USER`] = name === "p1" ? "app_user" : `app_${name.toLowerCase()}`;
    env[`${prefix}_PASSWORD`] = `secret-${index}`;
  }
  return { ...env, ...overrides };
}

test("runtime config validates and redacts five isolated priority pools", () => {
  const config = loadRuntimeConfig(environment());
  assert.deepEqual(Object.keys(config.pools), POOL_CLASSES);
  assert.deepEqual(POOL_CLASSES.map((name) => config.pools[name].max), [8, 12, 16, 8, 4]);
  assert.equal(config.pools.p1.expectedRole, "app_user");
  assert.equal(JSON.stringify(publicRuntimeConfig(config)).includes("secret-"), false);
});

test("production rejects shared endpoints, insecure transport, and the wrong P1 role", () => {
  const production = environment({
    NODE_ENV: "production",
    ISAS_DEPLOYMENT_ID: "isas-jp-prod-01",
    ISAS_DB_P0_SSLMODE: "disable",
  });
  const dependencies = { readDeploymentOperations: () => ({ value: {}, sha256: "a".repeat(64) }) };
  assert.throws(() => loadRuntimeConfig(production, dependencies), /sslmode=require/);

  const secure = { ...production };
  for (const prefix of ["P0", "AUTH_P1", "P1", "P2", "OPS"]) secure[`ISAS_DB_${prefix}_SSLMODE`] = "require";
  secure.ISAS_DB_AUTH_P1_HOST = secure.ISAS_DB_P0_HOST;
  assert.throws(() => loadRuntimeConfig(secure, dependencies), /five distinct/);

  secure.ISAS_DB_AUTH_P1_HOST = "pool-auth.internal";
  secure.ISAS_DB_P1_USER = "postgres";
  assert.throws(() => loadRuntimeConfig(secure, dependencies), /authenticate as app_user/);
});

test("production refuses to start without a valid deployment operations ledger", () => {
  const production = environment({ NODE_ENV: "production", ISAS_DEPLOYMENT_ID: "isas-jp-prod-01" });
  for (const prefix of ["P0", "AUTH_P1", "P1", "P2", "OPS"]) production[`ISAS_DB_${prefix}_SSLMODE`] = "require";
  assert.throws(() => loadRuntimeConfig(production), /ISAS_OPERATIONS_LEDGER is required/);
  assert.throws(() => loadRuntimeConfig({ ...production, ISAS_OPERATIONS_LEDGER: "/secure/operations.json" }, {
    readDeploymentOperations: () => { throw new Error("deployment operations ledger is invalid: contacts.security.owner is required"); },
  }), /contacts.security.owner is required/);
  assert.equal(loadRuntimeConfig({ ...production, ISAS_OPERATIONS_LEDGER: "/secure/operations.json" }, {
    readDeploymentOperations: () => ({ value: {}, sha256: "b".repeat(64) }),
  }).operationsLedgerSha256, "b".repeat(64));
});

test("HTTP header timeout cannot exceed the application request timeout", () => {
  assert.throws(() => loadRuntimeConfig(environment({
    ISAS_HEADERS_TIMEOUT_MS: "5000",
    ISAS_REQUEST_TIMEOUT_MS: "1000",
  })), /cannot exceed/);
});

test("local-integration separates production build mode and blocks cloud credentials", () => {
  const local = environment({
    NODE_ENV: "production",
    ISAS_ENV_PROFILE: "local-integration",
    ISAS_PUBLIC_ORIGIN: "https://isas.localhost:8443",
    ISAS_DEPLOYMENT_ID: "isas-jp-local-01",
    ISAS_RUNTIME_ADAPTER_MODULE: "./runtime-adapters/local-integration.mjs",
    AWS_REGION: ""
  });
  const hosts = { P0: "pgbouncer-p0", AUTH_P1: "pgbouncer-auth-p1", P1: "pgbouncer-p1", P2: "pgbouncer-p2", OPS: "pgbouncer-ops" };
  for (const [name, host] of Object.entries(hosts)) local[`ISAS_DB_${name}_HOST`] = host;
  assert.equal(loadRuntimeConfig(local).deploymentProfile, "local-integration");
  assert.throws(() => loadRuntimeConfig({ ...local, AWS_PROFILE: "production" }), /credential sources are forbidden/);
  assert.throws(() => loadRuntimeConfig({ ...local, ISAS_DB_P2_HOST: "prod.example" }), /outside the allowlist/);
});

test("structured logger removes secret fields and embedded database URLs", () => {
  let output = "";
  const stream = { write(chunk) { output += chunk; } };
  const logger = createJsonLogger(stream, stream);
  logger.error("failure", {
    password: "not-for-logs",
    message: "failed postgresql://app:super-secret@database/isas password=another-secret",
  });
  assert.equal(output.includes("super-secret"), false);
  assert.equal(output.includes("another-secret"), false);
  assert.equal(output.includes("not-for-logs"), false);
});

test("runtime adapter contract is checked before accepting traffic", () => {
  const adapters = {
    stores: {
      loginAttempts: { put() {}, take() {} },
      sessions: { get() {}, put() {}, delete() {}, touch() {} },
      contexts: { get() {}, put() {}, delete() {}, deleteForSession() {} },
    },
    identityProvider: { authorizationUrl() {}, exchangeCode() {} },
    users: { resolve() {} },
    authorization: { listTenants() {}, deriveContext() {} },
    securityAdministration: { snapshot() {}, requestChange() {}, decideChange() {}, createPrivacyRequest() {}, transitionPrivacyRequest() {} },
    attachmentStorage: { objectKey() {}, stage() {}, markReady() {}, signedDownload() {}, reconcile() {} },
    mapStorage: { packManifest() {}, readRange() {} },
  };
  assert.equal(runtimeContract.validateAdapters(adapters), adapters);
  delete adapters.stores.sessions.touch;
  assert.throws(() => runtimeContract.validateAdapters(adapters), /stores.sessions.touch/);
});
