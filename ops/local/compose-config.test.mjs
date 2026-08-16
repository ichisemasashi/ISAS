import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const compose = readFileSync(new URL("../../compose.local.yml", import.meta.url), "utf8");
const lock = JSON.parse(readFileSync(new URL("../../infra/local/component-lock.json", import.meta.url)));

test("component lockの全image digestをComposeが参照する", () => {
  for (const [name, component] of Object.entries(lock.images)) assert.match(compose, new RegExp(component.image.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")), name);
});

test("host公開はloopback Caddyだけ", () => {
  assert.match(compose, /127\.0\.0\.1:8443:8443/);
  assert.equal((compose.match(/\n\s+ports:/g) || []).length, 1);
  assert.match(compose, /internal:\n\s+internal: true/);
});

test("5つの独立PgBouncer serviceがある", () => {
  for (const name of ["p0", "auth-p1", "p1", "p2", "ops"]) assert.match(compose, new RegExp(`  pgbouncer-${name}:`));
});
