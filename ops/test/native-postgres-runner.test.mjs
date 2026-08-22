import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const script = readFileSync(new URL("../../spikes/run-native.sh", import.meta.url), "utf8");
test("native runner requires PostgreSQL 16 and PostGIS without a container runtime", () => {
  assert.match(script, /PostgreSQL\\\) 16\\\./);
  assert.match(script, /postgis\.control/);
  assert.doesNotMatch(script, /docker\s+(?:compose|run|build)/i);
});
test("native runner covers ordered migrations, rollback and required spikes", () => {
  assert.match(script, /apply_migrations isas/);
  for (const version of ["0017", "0016", "0015", "0014", "0013"]) assert.match(script, new RegExp(version));
  for (const name of ["S1_partition_rls_unique", "S2_spatial_rls", "S5_audit_chain", "S7_offline_sync", "S8_auth_context"]) assert.match(script, new RegExp(name));
});
