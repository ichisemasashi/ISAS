import { POOL_CLASSES } from "./runtime-config.mjs";

function timeout(promise, milliseconds, label) {
  let timer;
  return Promise.race([
    promise,
    new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(`${label} timed out`)), milliseconds); }),
  ]).finally(() => clearTimeout(timer));
}

export function createPriorityPools(config, { PoolClass, logger = console } = {}) {
  if (typeof PoolClass !== "function") throw new Error("PoolClass is required");
  const pools = {};

  for (const name of POOL_CLASSES) {
    const item = config.pools[name];
    const pool = new PoolClass({
      connectionString: item.connectionString,
      application_name: `${config.deploymentId}-bff-${name}`,
      max: item.max,
      connectionTimeoutMillis: item.connectionTimeoutMs,
      idleTimeoutMillis: item.idleTimeoutMs,
      statement_timeout: item.statementTimeoutMs,
      query_timeout: item.statementTimeoutMs + 1000,
      keepAlive: true,
      allowExitOnIdle: false,
    });
    pool.on?.("error", (error) => logger.error?.("postgres_pool_error", { pool: name, code: error?.code || "unknown" }));
    pools[name] = pool;
  }

  let closed = false;
  async function checkOne(name) {
    if (closed) throw new Error("PostgreSQL pools are closed");
    const item = config.pools[name];
    const result = await timeout(pools[name].query(
      "SELECT current_user AS role_name, current_database() AS database_name, pg_is_in_recovery() AS is_replica",
    ), item.connectionTimeoutMs + 1000, `${name} readiness`);
    if (result.rows?.length !== 1 || result.rows[0].role_name !== item.expectedRole) {
      throw new Error(`${name} connected with an unexpected database role`);
    }
    if (name === "p1" && result.rows[0].is_replica) throw new Error("p1 cannot target a read-only replica");
    return true;
  }

  return Object.freeze({
    ...pools,
    async startupCheck() {
      await Promise.all(POOL_CLASSES.map(checkOne));
    },
    async readinessCheck() {
      await Promise.all(POOL_CLASSES.map(checkOne));
      return true;
    },
    stats() {
      return Object.fromEntries(POOL_CLASSES.map((name) => [name, {
        total: pools[name].totalCount ?? 0,
        idle: pools[name].idleCount ?? 0,
        waiting: pools[name].waitingCount ?? 0,
      }]));
    },
    async end() {
      if (closed) return;
      closed = true;
      const results = await Promise.allSettled(POOL_CLASSES.map((name) => pools[name].end()));
      const errors = results.filter((result) => result.status === "rejected").map((result) => result.reason);
      if (errors.length) throw new AggregateError(errors, "One or more PostgreSQL pools failed to close");
    },
  });
}
