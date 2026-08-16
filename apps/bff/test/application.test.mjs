import assert from "node:assert/strict";
import { test } from "node:test";
import { createApplicationRouter, createPriorityDatabase } from "../src/application.mjs";

test("application router mounts BFF and real REST surfaces on one origin", async () => {
  const calls = [];
  const router = createApplicationRouter({
    bffHandler: async () => { calls.push("bff"); return new Response(null, { status: 204 }); },
    apiHandler: async () => { calls.push("api"); return new Response(null, { status: 200 }); },
  });
  assert.equal((await router(new Request("https://isas.example/api/bff/session"))).status, 204);
  const api = await router(new Request("https://isas.example/api/v1/today"));
  assert.equal(api.status, 200);
  assert.equal(api.headers.get("X-Content-Type-Options"), "nosniff");
  assert.equal(api.headers.get("X-Frame-Options"), "DENY");
  assert.match(api.headers.get("Content-Security-Policy"), /frame-ancestors 'none'/);
  assert.equal((await router(new Request("https://isas.example/unknown"))).status, 404);
  assert.deepEqual(calls, ["bff", "api"]);
});

test("routes only server-classified work to isolated P0, P1 and P2 pools", async () => {
  const calls = [];
  const database = createPriorityDatabase({
    p0: { pool: { name: "p0" }, expectedRole: "p0_role" },
    p1: { pool: { name: "p1" }, expectedRole: "app_user" },
    p2: { pool: { name: "p2" }, expectedRole: "p2_role" },
  }, {
    adapterFactory(pool, { expectedRole }) {
      return { async transaction(_trusted, operation, options) { calls.push({ pool: pool.name, expectedRole, options }); return operation({}); } };
    },
  });
  await database.transaction({}, async () => "p0", { readOnly: true, poolClass: "p0" });
  await database.transaction({}, async () => "p1");
  await database.transaction({}, async () => "p2", { poolClass: "p2" });
  assert.deepEqual(calls.map(({ pool, expectedRole }) => ({ pool, expectedRole })), [
    { pool: "p0", expectedRole: "p0_role" }, { pool: "p1", expectedRole: "app_user" }, { pool: "p2", expectedRole: "p2_role" },
  ]);
  await assert.rejects(async () => database.transaction({}, async () => undefined, { poolClass: "ops" }), /unsupported application pool class/);
});
