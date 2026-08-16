import assert from "node:assert/strict";
import test from "node:test";
import { createPostgresLocalStores } from "../src/postgres-local-stores.mjs";

const crypto = { seal(value) { return Buffer.from(JSON.stringify(value)); }, open(value) { return JSON.parse(Buffer.from(value).toString()); } };

test("local stores use fixed functions and encrypted payloads", async () => {
  const calls = [];
  const pool = { async query(sql, values) {
    calls.push({ sql, values });
    if (sql.includes("put_session")) return { rows: [{ accepted: true }] };
    if (sql.includes("get_session")) return { rows: [{ ciphertext: Buffer.from(JSON.stringify({ user: { id: "u" } })) }] };
    return { rows: [] };
  } };
  const stores = createPostgresLocalStores({ pool, crypto });
  await stores.sessions.put("a".repeat(43), { user: { id: "00000000-0000-4000-8000-000000000001", authorizationVersion: "2" }, lastSeenAt: 1, expiresAt: 2 });
  assert.equal((await stores.sessions.get("a".repeat(43))).user.id, "u");
  assert.match(calls[0].sql, /local_support\.put_session/);
  assert.ok(Buffer.isBuffer(calls[0].values[3]));
});

test("revoked version cannot recreate a session", async () => {
  const stores = createPostgresLocalStores({ pool: { async query() { return { rows: [{ accepted: false }] }; } }, crypto });
  await assert.rejects(() => stores.sessions.put("a".repeat(43), { user: { id: "00000000-0000-4000-8000-000000000001", authorizationVersion: "1" }, lastSeenAt: 1, expiresAt: 2 }), /revoked/);
});
