import { randomUUID } from "node:crypto";

const delay = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

export function createLocalRevocationService({ pool, outbox, stores, identityProvider, crypto, logger = console }) {
  if (!pool?.query || !outbox?.claim || !stores?.invalidate || !identityProvider?.revokeNow || !crypto?.seal || !crypto?.open) throw new Error("local revocation configuration is incomplete");
  let running = false;
  let worker;

  async function enqueue(event, idempotencyKey) {
    await pool.query("SELECT local_support.enqueue_revocation($1::text,$2::bytea)", [idempotencyKey, crypto.seal(event, "revocation", "queue")]);
  }
  async function publishOnce() {
    const event = await outbox.claim(30);
    if (!event) return false;
    try {
      await enqueue({ type: "authorization_revoked", ...event }, `authorization:${event.eventId}`);
      if (!await outbox.complete(event.eventId, event.claimId)) throw new Error("revocation outbox claim was lost");
      return true;
    } catch (error) { await Promise.allSettled([outbox.release(event.eventId, event.claimId)]); throw error; }
  }
  async function consumeOnce() {
    const claimId = randomUUID();
    const result = await pool.query("SELECT * FROM local_support.claim_revocation($1::uuid,$2::integer)", [claimId, 30]);
    const row = result.rows?.[0];
    if (!row) return false;
    try {
      const event = crypto.open(row.ciphertext, "revocation", "queue");
      if (event.type === "authorization_revoked") await stores.invalidate({ userId: event.userId, authorizationVersion: Number(event.authorizationVersion) });
      else if (event.type === "oidc_token_revoke") await identityProvider.revokeNow(event.tokenSetCiphertext);
      else throw new Error("unsupported local revocation event");
      await pool.query("SELECT local_support.complete_revocation($1::bigint,$2::uuid)", [row.queue_id, claimId]);
      return true;
    } catch (error) {
      await pool.query("SELECT local_support.fail_revocation($1::bigint,$2::uuid,$3::text,$4::integer)", [row.queue_id, claimId, error.code || error.name || "processing_failed", 5]);
      logger.error?.("local_revocation_failed", { code: error.code || error.name || "processing_failed" });
      return false;
    }
  }
  async function loop() {
    while (running) {
      try { if (!await publishOnce() && !await consumeOnce()) await delay(500); }
      catch (error) { logger.error?.("local_revocation_loop_failed", { code: error.code || error.name || "loop_failed" }); await delay(1000); }
    }
  }
  return Object.freeze({
    publishOnce,
    consumeOnce,
    async enqueueTokenRevocation(event) { await enqueue({ type: "oidc_token_revoke", ...event }, `oidc:${randomUUID()}`); },
    async startupCheck() { const result = await pool.query("SELECT to_regprocedure('local_support.claim_revocation(uuid,integer)') IS NOT NULL AS available"); if (result.rows?.[0]?.available !== true) throw new Error("local revocation queue is unavailable"); },
    start() { if (!running) { running = true; worker = loop(); } },
    async close() { running = false; await worker; }
  });
}
