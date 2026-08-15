import assert from "node:assert/strict";
import { test } from "node:test";
import { createRevocationService } from "../src/revocation-service.mjs";

test("publishes PostgreSQL outbox only before marking its claim complete", async () => {
  const order = [];
  const sqs = { async send(command) { order.push(command.constructor.name); return {}; } };
  const outbox = {
    async claim() { order.push("claim"); return { claimId: "claim-1", eventId: "1", userId: "u", authorizationVersion: "2", occurredAt: "2026-08-15T00:00:00Z" }; },
    async complete() { order.push("complete"); return true; },
    async release() { order.push("release"); },
  };
  const service = createRevocationService({
    sqs, queueUrl: "https://sqs.example/queue", outbox,
    stores: { async invalidate() {} }, identityProvider: { async revokeNow() {} },
  });
  assert.equal(await service.publishOnce(), true);
  assert.deepEqual(order, ["claim", "SendMessageCommand", "complete"]);
});

test("consumes authorization, token, and back-channel logout events idempotently", async () => {
  const calls = [];
  const service = createRevocationService({
    sqs: { async send() { return {}; } }, queueUrl: "https://sqs.example/queue",
    outbox: { async claim() { return null; }, async complete() {}, async release() {} },
    stores: { async invalidate(event) { calls.push(["invalidate", event.authorizationVersion]); return { applied: true }; } },
    identityProvider: {
      async revokeNow(value) { calls.push(["revoke", value]); },
      async adminGlobalSignOut(value) { calls.push(["global", value]); },
    },
  });
  await service.processEvent({ type: "authorization_revoked", eventId: "1", userId: "u", authorizationVersion: "3", occurredAt: "2026-08-15T00:00:00Z" });
  await service.processEvent({ type: "cognito_token_revoke", tokenSetCiphertext: "encrypted" });
  await service.processEvent({ type: "cognito_backchannel_logout", eventId: "2", userId: "u", username: "cognito-user", authorizationVersion: "4", occurredAt: "2026-08-15T00:01:00Z" });
  assert.deepEqual(calls, [
    ["invalidate", 3], ["revoke", "encrypted"], ["global", "cognito-user"], ["invalidate", 4],
  ]);
  await assert.rejects(() => service.processEvent({ type: "unknown" }), /Unsupported/);
});

test("persists token revocation when SQS is unavailable and republishes it later", async () => {
  const jobs = [];
  let queueAvailable = false;
  const sent = [];
  const service = createRevocationService({
    sqs: {
      async send(command) {
        if (!queueAvailable) throw Object.assign(new Error("queue unavailable"), { name: "TimeoutError" });
        sent.push(JSON.parse(command.input.MessageBody));
        return {};
      },
    },
    queueUrl: "https://sqs.example/queue",
    outbox: { async claim() { return null; }, async complete() {}, async release() {} },
    stores: {
      async invalidate() {},
      tokenRevocations: {
        async put(value) { jobs.push({ jobId: "job-1", ...value }); },
        async pending() { return jobs.slice(0, 1); },
        async delete(jobId) { jobs.splice(jobs.findIndex((job) => job.jobId === jobId), 1); },
      },
    },
    identityProvider: { async revokeNow() {} },
    logger: { error() {} },
  });

  await service.enqueueTokenRevocation({ type: "cognito_token_revoke", tokenSetCiphertext: "encrypted" });
  assert.equal(jobs.length, 1);
  queueAvailable = true;
  assert.equal(await service.publishDeferredOnce(), true);
  assert.deepEqual(sent, [{ type: "cognito_token_revoke", tokenSetCiphertext: "encrypted" }]);
  assert.equal(jobs.length, 0);
});
