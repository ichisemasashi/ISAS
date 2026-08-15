import assert from "node:assert/strict";
import { test } from "node:test";
import { createApplicationRouter } from "../src/application.mjs";

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
