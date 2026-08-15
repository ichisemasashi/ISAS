import assert from "node:assert/strict";
import { test } from "node:test";
import { createS3ObjectStorage } from "../src/s3-object-storage.mjs";

const TENANT = "11111111-1111-7111-8111-111111111111";
const USER = "22222222-2222-7222-8222-222222222222";
const ATTACHMENT = {
  attachmentId: "33333333-3333-7333-8333-333333333333",
  journalId: "44444444-4444-7444-8444-444444444444",
  fileName: "圃場.jpg",
  contentType: "image/jpeg",
  bytes: Buffer.from([0xff, 0xd8, 0xff]),
  sha256: "a".repeat(64),
};

test("stages a server-named object with checksum and promotes its lifecycle tag", async () => {
  const commands = [];
  const s3 = { async send(command) {
    commands.push(command);
    if (command.constructor.name === "HeadObjectCommand") throw Object.assign(new Error("missing"), { name: "NotFound" });
    return {};
  } };
  const storage = createS3ObjectStorage({ s3, bucket: "arn:aws:s3:ap-northeast-1:123456789012:accesspoint/attachments" });
  const key = storage.objectKey(TENANT, ATTACHMENT);
  await storage.stage({ tenantId: TENANT, userId: USER, attachment: ATTACHMENT, key });
  await storage.markReady({ key });
  assert.equal(key, `attachments/${TENANT}/${ATTACHMENT.attachmentId}/${ATTACHMENT.sha256}`);
  assert.equal(commands[1].input.ContentType, "image/jpeg");
  assert.equal(commands[1].input.ChecksumSHA256, Buffer.from("a".repeat(64), "hex").toString("base64"));
  assert.equal(commands[1].input.Tagging, "upload-state=pending&retention-class=supporting");
  assert.deepEqual(commands[2].input.Tagging.TagSet.map(({ Value }) => Value), ["ready", "supporting"]);
});

test("rejects an idempotency retry when stored metadata differs", async () => {
  const s3 = { async send() { return { Metadata: { sha256: "b".repeat(64) }, ContentType: "image/jpeg", ContentLength: 3 }; } };
  const storage = createS3ObjectStorage({ s3, bucket: "bucket" });
  const key = storage.objectKey(TENANT, ATTACHMENT);
  await assert.rejects(storage.stage({ tenantId: TENANT, userId: USER, attachment: ATTACHMENT, key }), { code: "idempotency_conflict" });
});

test("issues a download URL for at most five minutes without exposing the object key", async () => {
  let expiresIn;
  const storage = createS3ObjectStorage({
    s3: { send: async () => ({}) }, bucket: "bucket", downloadTtlSeconds: 60, clock: () => 0,
    signer: async (_client, command, options) => { expiresIn = options.expiresIn; assert.equal(command.input.ResponseContentType, "image/jpeg"); return "https://signed.example/download"; },
  });
  const result = await storage.signedDownload({ objectKey: "attachments/a", storageStatus: "ready", fileName: "圃場.jpg", contentType: "image/jpeg", byteSize: 3, sha256: "a".repeat(64) });
  assert.equal(expiresIn, 60);
  assert.equal(result.url, "https://signed.example/download");
  assert.equal(result.expiresAt, "1970-01-01T00:01:00.000Z");
  assert.equal("objectKey" in result, false);
});

test("quarantines an old orphan and repairs a ready tag left before the DB finalization", async () => {
  const commands = [];
  const now = Date.parse("2026-08-15T00:00:00Z");
  const key = `attachments/${TENANT}/${ATTACHMENT.attachmentId}/${ATTACHMENT.sha256}`;
  const orphan = `attachments/${TENANT}/55555555-5555-7555-8555-555555555555/${"b".repeat(64)}`;
  const s3 = { async send(command) {
    commands.push(command);
    if (command.constructor.name === "ListObjectsV2Command") return { Contents: [
      { Key: key, LastModified: new Date(now - 2 * 86400000) },
      { Key: orphan, LastModified: new Date(now - 2 * 86400000) },
    ], IsTruncated: false };
    if (command.constructor.name === "GetObjectTaggingCommand") return { TagSet: [{ Key: "upload-state", Value: "ready" }] };
    return {};
  } };
  const storage = createS3ObjectStorage({ s3, bucket: "bucket", clock: () => now });
  const result = await storage.reconcile({ tenantId: TENANT, records: [{ id: ATTACHMENT.attachmentId, objectKey: key, storageStatus: "pending", createdAt: "2026-08-13T00:00:00Z" }] });
  assert.deepEqual(result, { scanned: 2, taggedOrphans: 1, readyAttachmentIds: [ATTACHMENT.attachmentId], missingAttachmentIds: [] });
  const quarantine = commands.find((command) => command.constructor.name === "PutObjectTaggingCommand");
  assert.equal(quarantine.input.Key, orphan);
  assert.equal(quarantine.input.Tagging.TagSet[0].Value, "orphaned");
});
