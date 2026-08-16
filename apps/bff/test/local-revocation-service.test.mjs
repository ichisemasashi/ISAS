import assert from "node:assert/strict";
import test from "node:test";
import { createLocalRevocationService } from "../src/local-revocation-service.mjs";

test("local revocation publishes outbox and invalidates idempotently", async () => {
  const completed = [];
  let queued;
  const pool = { async query(sql, values) {
    if (sql.includes("enqueue_revocation")) { queued = values[1]; return { rows: [{ enqueue_revocation: 1 }] }; }
    if (sql.includes("claim_revocation")) return { rows: [{ queue_id: 1, ciphertext: queued, attempts: 1 }] };
    if (sql.includes("complete_revocation")) { completed.push(values[0]); return { rows: [{ complete_revocation: true }] }; }
    return { rows: [{ available: true }] };
  } };
  let claimed = false; const outbox = { async claim() { if (claimed) return null; claimed = true; return { eventId: "7", claimId: "00000000-0000-4000-8000-000000000007", userId: "00000000-0000-4000-8000-000000000001", authorizationVersion: "3" }; }, async complete() { return true; }, async release() {} };
  const invalidated = [];
  const crypto = { seal(value) { return Buffer.from(JSON.stringify(value)); }, open(value) { return JSON.parse(Buffer.from(value).toString()); } };
  const service = createLocalRevocationService({ pool, outbox, stores: { async invalidate(event) { invalidated.push(event); } }, identityProvider: { async revokeNow() {} }, crypto });
  assert.equal(await service.publishOnce(), true);
  assert.equal(await service.consumeOnce(), true);
  assert.equal(invalidated[0].authorizationVersion, 3);
  assert.deepEqual(completed, [1]);
});
