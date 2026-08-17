import { readDeploymentOperations } from "./deployment-operations.mjs";

const INTEGER = /^[1-9][0-9]*$/;

export const POOL_CLASSES = Object.freeze(["p0", "authP1", "p1", "p2", "ops"]);

const POOL_PREFIX = Object.freeze({
  p0: "ISAS_DB_P0",
  authP1: "ISAS_DB_AUTH_P1",
  p1: "ISAS_DB_P1",
  p2: "ISAS_DB_P2",
  ops: "ISAS_DB_OPS",
});

const DEFAULT_POOL_MAX = Object.freeze({ p0: 8, authP1: 12, p1: 16, p2: 8, ops: 4 });
const DEFAULT_STATEMENT_TIMEOUT = Object.freeze({ p0: 3000, authP1: 5000, p1: 15000, p2: 30000, ops: 60000 });

function integer(env, name, fallback, { min = 1, max = Number.MAX_SAFE_INTEGER } = {}) {
  const raw = env[name];
  if (raw == null || raw === "") return fallback;
  if (!INTEGER.test(raw)) throw new Error(`${name} must be a positive integer`);
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < min || value > max) throw new Error(`${name} must be between ${min} and ${max}`);
  return value;
}

function exactOrigin(value) {
  if (!value) throw new Error("ISAS_PUBLIC_ORIGIN is required");
  const url = new URL(value);
  if (url.origin !== value || url.username || url.password || url.pathname !== "/") throw new Error("ISAS_PUBLIC_ORIGIN must be an exact URL origin");
  if (url.protocol !== "https:") throw new Error("ISAS_PUBLIC_ORIGIN must use HTTPS");
  return url.origin;
}

function adapterModule(value) {
  if (!value || value.includes("\0")) throw new Error("ISAS_RUNTIME_ADAPTER_MODULE is required");
  if (/^https?:/i.test(value)) throw new Error("ISAS_RUNTIME_ADAPTER_MODULE must be a local module");
  return value;
}

function connection(env, prefix, deploymentProfile) {
  let value = env[`${prefix}_URL`];
  if (!value) {
    for (const suffix of ["HOST", "NAME", "USER", "PASSWORD"]) {
      if (!env[`${prefix}_${suffix}`]) throw new Error(`${prefix}_${suffix} is required`);
    }
    const port = env[`${prefix}_PORT`] || "6432";
    if (!INTEGER.test(port) || Number(port) > 65535) throw new Error(`${prefix}_PORT is invalid`);
    const sslmode = env[`${prefix}_SSLMODE`] || (deploymentProfile === "production" ? "require" : "disable");
    const assembled = new URL("postgresql://runtime.invalid");
    assembled.hostname = env[`${prefix}_HOST`];
    assembled.port = port;
    assembled.pathname = `/${encodeURIComponent(env[`${prefix}_NAME`])}`;
    assembled.username = env[`${prefix}_USER`];
    assembled.password = env[`${prefix}_PASSWORD`];
    assembled.searchParams.set("sslmode", sslmode);
    value = assembled.toString();
  }
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error(`${prefix}_URL must be a PostgreSQL URL`);
  }
  if (!(["postgres:", "postgresql:"].includes(url.protocol)) || !url.hostname || !url.pathname.slice(1)) {
    throw new Error(`${prefix} must include PostgreSQL protocol, host, and database`);
  }
  if (!url.username || !url.password) throw new Error(`${prefix} must include a non-empty role and secret`);
  if ([...url.searchParams.keys()].some((key) => !["sslmode", "application_name"].includes(key))) {
    throw new Error(`${prefix} contains an unsupported connection option`);
  }
  if (deploymentProfile === "production") {
    if (["localhost", "127.0.0.1", "::1"].includes(url.hostname)) throw new Error(`${prefix} cannot use a loopback host in production`);
    if (url.searchParams.get("sslmode") !== "require" && url.searchParams.get("sslmode") !== "verify-full") {
      throw new Error(`${prefix} must set sslmode=require or verify-full in production`);
    }
  }
  return {
    connectionString: url.toString(),
    expectedRole: decodeURIComponent(url.username),
    endpointIdentity: `${url.hostname.toLowerCase()}:${url.port || "5432"}`,
  };
}

export function loadRuntimeConfig(env = process.env, dependencies = {}) {
  const environment = env.NODE_ENV || "production";
  if (!(["production", "test", "development"].includes(environment))) throw new Error("NODE_ENV is invalid");
  const deploymentProfile = env.ISAS_ENV_PROFILE || (environment === "production" ? "production" : environment);
  if (!(deploymentProfile === "production" || deploymentProfile === "local-integration" || deploymentProfile === "test" || deploymentProfile === "development")) {
    throw new Error("ISAS_ENV_PROFILE is invalid");
  }
  const origin = exactOrigin(env.ISAS_PUBLIC_ORIGIN);
  const redirectUri = env.ISAS_OIDC_REDIRECT_URI || `${origin}/api/bff/callback`;
  if (new URL(redirectUri).origin !== origin || new URL(redirectUri).pathname !== "/api/bff/callback") {
    throw new Error("ISAS_OIDC_REDIRECT_URI must be the BFF callback on ISAS_PUBLIC_ORIGIN");
  }
  const deploymentPattern = deploymentProfile === "local-integration" ? /^isas-jp-local-[0-9]{2}$/ : /^isas-jp-(?:stg|prod)-[0-9]{2}$/;
  if (!deploymentPattern.test(env.ISAS_DEPLOYMENT_ID || "")) throw new Error("ISAS_DEPLOYMENT_ID is invalid");
  if (env.ISAS_JURISDICTION !== "JP") throw new Error("ISAS_JURISDICTION must be JP");
  if (deploymentProfile === "production" && env.AWS_REGION !== "ap-northeast-1") throw new Error("AWS_REGION must be ap-northeast-1 in production");
  if (deploymentProfile === "local-integration") {
    if (environment !== "production") throw new Error("local-integration must run the production-mode BFF build");
    if (origin !== "https://isas.localhost:8443") throw new Error("local-integration origin must be https://isas.localhost:8443");
    if (env.ISAS_RUNTIME_ADAPTER_MODULE !== "./runtime-adapters/local-integration.mjs") throw new Error("local-integration adapter module is fixed");
    const forbidden = ["AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_PROFILE", "AWS_WEB_IDENTITY_TOKEN_FILE", "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI"];
    if (forbidden.some((name) => env[name])) throw new Error("AWS credential sources are forbidden in local-integration");
  }
  const operations = deploymentProfile === "production"
    ? (dependencies.readDeploymentOperations || readDeploymentOperations)(env.ISAS_OPERATIONS_LEDGER, env.ISAS_DEPLOYMENT_ID)
    : null;

  const connectionTimeoutMs = integer(env, "ISAS_DB_CONNECTION_TIMEOUT_MS", 3000, { max: 30000 });
  const idleTimeoutMs = integer(env, "ISAS_DB_IDLE_TIMEOUT_MS", 30000, { max: 600000 });
  const pools = {};
  for (const name of POOL_CLASSES) {
    const envPrefix = name === "authP1" ? "AUTH_P1" : name.toUpperCase();
    pools[name] = {
      ...connection(env, POOL_PREFIX[name], deploymentProfile),
      max: integer(env, `ISAS_DB_${envPrefix}_MAX`, DEFAULT_POOL_MAX[name], { max: 100 }),
      connectionTimeoutMs,
      idleTimeoutMs,
      statementTimeoutMs: integer(env, `ISAS_DB_${envPrefix}_STATEMENT_TIMEOUT_MS`, DEFAULT_STATEMENT_TIMEOUT[name], { max: 300000 }),
    };
  }
  const endpointCount = new Set(POOL_CLASSES.map((name) => pools[name].endpointIdentity)).size;
  if ((deploymentProfile === "production" || deploymentProfile === "local-integration") && endpointCount !== POOL_CLASSES.length) {
    throw new Error("Priority pools must use five distinct PgBouncer endpoints");
  }
  if (deploymentProfile === "local-integration") {
    const expectedHosts = new Set(["pgbouncer-p0", "pgbouncer-auth-p1", "pgbouncer-p1", "pgbouncer-p2", "pgbouncer-ops"]);
    if (POOL_CLASSES.some((name) => !expectedHosts.has(new URL(pools[name].connectionString).hostname))) throw new Error("local-integration database host is outside the allowlist");
  }
  if (pools.p1.expectedRole !== "app_user") throw new Error("ISAS_DB_P1 must authenticate as app_user");

  const requestTimeoutMs = integer(env, "ISAS_REQUEST_TIMEOUT_MS", 30000, { max: 300000 });
  const headersTimeoutMs = integer(env, "ISAS_HEADERS_TIMEOUT_MS", 10000, { max: 60000 });
  const keepAliveTimeoutMs = integer(env, "ISAS_KEEP_ALIVE_TIMEOUT_MS", 5000, { max: 60000 });
  if (headersTimeoutMs > requestTimeoutMs) throw new Error("ISAS_HEADERS_TIMEOUT_MS cannot exceed ISAS_REQUEST_TIMEOUT_MS");

  return Object.freeze({
    environment,
    deploymentProfile,
    deploymentId: env.ISAS_DEPLOYMENT_ID,
    jurisdiction: "JP",
    region: env.AWS_REGION || "ap-northeast-1",
    origin,
    redirectUri,
    adapterModule: adapterModule(env.ISAS_RUNTIME_ADAPTER_MODULE),
    host: env.ISAS_HTTP_HOST || "0.0.0.0",
    port: integer(env, "ISAS_HTTP_PORT", 3000, { max: 65535 }),
    requestTimeoutMs,
    headersTimeoutMs,
    keepAliveTimeoutMs,
    drainTimeoutMs: integer(env, "ISAS_DRAIN_TIMEOUT_MS", 15000, { max: 120000 }),
    bodyLimitBytes: integer(env, "ISAS_BODY_LIMIT_BYTES", 11 * 1024 * 1024, { max: 16 * 1024 * 1024 }),
    readinessCacheMs: integer(env, "ISAS_READINESS_CACHE_MS", 1000, { max: 10000 }),
    operationsLedgerSha256: operations?.sha256 || null,
    pools: Object.freeze(pools),
  });
}

export function publicRuntimeConfig(config) {
  return Object.freeze({
    environment: config.environment,
    deploymentProfile: config.deploymentProfile,
    deploymentId: config.deploymentId,
    jurisdiction: config.jurisdiction,
    region: config.region,
    origin: config.origin,
    host: config.host,
    port: config.port,
    poolClasses: POOL_CLASSES.map((name) => ({ name, max: config.pools[name].max, expectedRole: config.pools[name].expectedRole })),
  });
}
