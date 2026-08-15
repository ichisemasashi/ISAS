import assert from "node:assert/strict";
import { test } from "node:test";
import { createPriorityPools } from "../src/postgres-pools.mjs";
import { POOL_CLASSES } from "../src/runtime-config.mjs";

test("creates, verifies, reports, and closes one driver pool per priority", async () => {
  const instances = [];
  class FakePool {
    constructor(options) {
      this.options = options;
      this.totalCount = 2;
      this.idleCount = 1;
      this.waitingCount = 0;
      this.listeners = {};
      instances.push(this);
    }
    on(event, listener) { this.listeners[event] = listener; }
    async query() {
      return { rows: [{
        role_name: new URL(this.options.connectionString).username,
        database_name: "isas",
        is_replica: false,
      }] };
    }
    async end() { this.ended = true; }
  }
  const config = {
    deploymentId: "isas-jp-prod-01",
    pools: Object.fromEntries(POOL_CLASSES.map((name, index) => [name, {
      connectionString: `postgresql://${name}:secret@pool-${index}.internal/isas?sslmode=require`,
      expectedRole: name,
      max: index + 1,
      connectionTimeoutMs: 50,
      idleTimeoutMs: 100,
      statementTimeoutMs: 200,
    }])),
  };

  const pools = createPriorityPools(config, { PoolClass: FakePool });
  await pools.startupCheck();
  assert.equal(instances.length, 5);
  assert.deepEqual(instances.map(({ options }) => options.max), [1, 2, 3, 4, 5]);
  assert.deepEqual(instances.map(({ options }) => options.application_name), POOL_CLASSES.map((name) => `isas-jp-prod-01-bff-${name}`));
  assert.deepEqual(pools.stats().p1, { total: 2, idle: 1, waiting: 0 });
  await pools.end();
  assert.equal(instances.every(({ ended }) => ended), true);
  await assert.rejects(() => pools.readinessCheck(), /closed/);
});

test("rejects a pool connected as an unexpected role or a replica for P1", async () => {
  class WrongRolePool {
    on() {}
    async query() { return { rows: [{ role_name: "wrong", database_name: "isas", is_replica: false }] }; }
    async end() {}
  }
  const config = {
    deploymentId: "isas-jp-prod-01",
    pools: Object.fromEntries(POOL_CLASSES.map((name) => [name, {
      connectionString: `postgresql://${name}:secret@pool/isas`, expectedRole: name, max: 1,
      connectionTimeoutMs: 10, idleTimeoutMs: 10, statementTimeoutMs: 10,
    }])),
  };
  const pools = createPriorityPools(config, { PoolClass: WrongRolePool });
  await assert.rejects(() => pools.startupCheck(), /unexpected database role/);
  await pools.end();
});
