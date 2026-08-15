import {
  DeleteMessageCommand,
  GetQueueAttributesCommand,
  ReceiveMessageCommand,
  SendMessageCommand,
} from "@aws-sdk/client-sqs";

const MAX_MESSAGE_BYTES = 64 * 1024;

function parseMessage(body) {
  if (typeof body !== "string" || Buffer.byteLength(body) > MAX_MESSAGE_BYTES) throw new Error("Invalid revocation message size");
  let event;
  try { event = JSON.parse(body); } catch { throw new Error("Invalid revocation message JSON"); }
  if (!event || typeof event !== "object" || Array.isArray(event) || typeof event.type !== "string") throw new Error("Invalid revocation message");
  return event;
}

function authorizationEvent(value) {
  const version = Number(value.authorizationVersion);
  if (!/^\d+$/.test(String(value.eventId)) || typeof value.userId !== "string" || !value.userId
    || !Number.isSafeInteger(version) || version < 1 || !Number.isFinite(Date.parse(value.occurredAt))) {
    throw new Error("Invalid authorization revocation event");
  }
  return { ...value, authorizationVersion: version };
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

export function createRevocationService({ sqs, queueUrl, outbox, stores, identityProvider, logger = console }) {
  if (!sqs?.send || !queueUrl || !outbox || !stores?.invalidate || !identityProvider?.revokeNow) {
    throw new Error("Revocation service configuration is incomplete");
  }
  let running = false;
  let publisherPromise;
  let consumerPromise;
  const abortControllers = new Set();

  async function send(body) {
    await sqs.send(new SendMessageCommand({ QueueUrl: queueUrl, MessageBody: JSON.stringify(body) }));
  }

  async function publishOnce() {
    const event = await outbox.claim(30);
    if (!event) return false;
    try {
      await send({ type: "authorization_revoked", ...event });
      if (!await outbox.complete(event.eventId, event.claimId)) throw new Error("Revocation outbox claim was lost");
      return true;
    } catch (error) {
      await Promise.allSettled([outbox.release(event.eventId, event.claimId)]);
      throw error;
    }
  }

  async function publishDeferredOnce() {
    if (!stores.tokenRevocations) return false;
    const [job] = await stores.tokenRevocations.pending(1);
    if (!job) return false;
    await send({ type: job.type, tokenSetCiphertext: job.tokenSetCiphertext });
    await stores.tokenRevocations.delete(job.jobId);
    return true;
  }

  async function processEvent(event) {
    if (event.type === "authorization_revoked") {
      return stores.invalidate(authorizationEvent(event));
    }
    if (event.type === "cognito_token_revoke") {
      if (typeof event.tokenSetCiphertext !== "string" || !event.tokenSetCiphertext) throw new Error("Invalid Cognito token revocation event");
      await identityProvider.revokeNow(event.tokenSetCiphertext);
      return { applied: true };
    }
    if (event.type === "cognito_backchannel_logout") {
      if (typeof event.username !== "string" || !event.username) throw new Error("Invalid Cognito back-channel logout event");
      await identityProvider.adminGlobalSignOut(event.username);
      return stores.invalidate(authorizationEvent(event));
    }
    throw new Error("Unsupported revocation event type");
  }

  async function consumeOnce() {
    const controller = new AbortController();
    abortControllers.add(controller);
    let result;
    try {
      result = await sqs.send(new ReceiveMessageCommand({
        QueueUrl: queueUrl,
        MaxNumberOfMessages: 10,
        WaitTimeSeconds: 20,
        VisibilityTimeout: 60,
        AttributeNames: ["ApproximateReceiveCount"],
      }), { abortSignal: controller.signal });
    } finally {
      abortControllers.delete(controller);
    }
    for (const message of result.Messages || []) {
      try {
        await processEvent(parseMessage(message.Body));
        await sqs.send(new DeleteMessageCommand({ QueueUrl: queueUrl, ReceiptHandle: message.ReceiptHandle }));
      } catch (error) {
        logger.error?.("revocation_consume_failed", {
          code: error?.code || error?.name || "invalid_event",
          receiveCount: message.Attributes?.ApproximateReceiveCount,
        });
      }
    }
    return result.Messages?.length || 0;
  }

  async function publisherLoop() {
    while (running) {
      try {
        const published = await publishDeferredOnce() || await publishOnce();
        if (!published) await delay(500);
      } catch (error) {
        logger.error?.("revocation_publish_failed", { code: error?.code || error?.name || "publish_failed" });
        await delay(1000);
      }
    }
  }

  async function consumerLoop() {
    while (running) {
      try { await consumeOnce(); } catch (error) {
        if (running) {
          logger.error?.("revocation_poll_failed", { code: error?.code || error?.name || "poll_failed" });
          await delay(1000);
        }
      }
    }
  }

  return Object.freeze({
    publishOnce,
    publishDeferredOnce,
    consumeOnce,
    processEvent,
    async enqueueTokenRevocation(event) {
      try { await send(event); } catch (error) {
        if (!stores.tokenRevocations) throw error;
        await stores.tokenRevocations.put(event);
        logger.error?.("token_revocation_deferred", { code: error?.code || error?.name || "queue_unavailable" });
      }
    },
    async startupCheck() {
      const result = await sqs.send(new GetQueueAttributesCommand({ QueueUrl: queueUrl, AttributeNames: ["QueueArn", "RedrivePolicy"] }));
      if (!result.Attributes?.QueueArn || !result.Attributes?.RedrivePolicy) throw new Error("Revocation queue must have an ARN and dead-letter policy");
    },
    start() {
      if (running) return;
      running = true;
      publisherPromise = publisherLoop();
      consumerPromise = consumerLoop();
    },
    async close() {
      running = false;
      for (const controller of abortControllers) controller.abort();
      await Promise.allSettled([publisherPromise, consumerPromise]);
    },
  });
}

export const revocationInternals = Object.freeze({ parseMessage, authorizationEvent });
