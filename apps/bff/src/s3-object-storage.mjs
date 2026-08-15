import {
  GetObjectCommand,
  GetObjectTaggingCommand,
  HeadBucketCommand,
  HeadObjectCommand,
  ListObjectsV2Command,
  PutObjectCommand,
  PutObjectTaggingCommand,
} from "@aws-sdk/client-s3";
import { getSignedUrl } from "@aws-sdk/s3-request-presigner";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const SHA256 = /^[0-9a-f]{64}$/;

function storageConflict(message) {
  return Object.assign(new Error(message), { code: "idempotency_conflict" });
}

function notFound(error) {
  return error?.name === "NotFound" || error?.name === "NoSuchKey" || error?.$metadata?.httpStatusCode === 404;
}

function safeDisposition(fileName) {
  const ascii = fileName.replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 120) || "photo";
  return `inline; filename="${ascii}"; filename*=UTF-8''${encodeURIComponent(fileName)}`;
}

export function createS3ObjectStorage({ s3, bucket, signer = getSignedUrl, downloadTtlSeconds = 60, clock = () => Date.now() }) {
  if (!s3?.send || !bucket) throw new Error("S3 client and attachment bucket are required");
  if (!Number.isInteger(downloadTtlSeconds) || downloadTtlSeconds < 30 || downloadTtlSeconds > 300) {
    throw new Error("Attachment download TTL must be between 30 and 300 seconds");
  }

  function objectKey(tenantId, attachment) {
    if (!UUID.test(tenantId) || !UUID.test(attachment?.attachmentId) || !SHA256.test(attachment?.sha256 || "")) {
      throw new TypeError("invalid attachment object identity");
    }
    return `attachments/${tenantId}/${attachment.attachmentId}/${attachment.sha256}`;
  }

  async function head(key) {
    try {
      return await s3.send(new HeadObjectCommand({ Bucket: bucket, Key: key }));
    } catch (error) {
      if (notFound(error)) return null;
      throw error;
    }
  }

  return Object.freeze({
    objectKey,
    async startupCheck() {
      await s3.send(new HeadBucketCommand({ Bucket: bucket }));
    },
    async stage({ tenantId, userId, attachment, key }) {
      if (key !== objectKey(tenantId, attachment) || !UUID.test(userId)) throw new TypeError("invalid attachment storage request");
      const existing = await head(key);
      if (existing) {
        if (existing.Metadata?.sha256 !== attachment.sha256
          || existing.ContentType !== attachment.contentType
          || Number(existing.ContentLength) !== attachment.bytes.length) throw storageConflict("attachment object differs");
        return { key, staged: false };
      }
      await s3.send(new PutObjectCommand({
        Bucket: bucket,
        Key: key,
        Body: attachment.bytes,
        ContentLength: attachment.bytes.length,
        ContentType: attachment.contentType,
        ChecksumSHA256: Buffer.from(attachment.sha256, "hex").toString("base64"),
        Metadata: {
          sha256: attachment.sha256,
          "attachment-id": attachment.attachmentId,
          "journal-id": attachment.journalId,
          "uploader-id": userId,
        },
        Tagging: "upload-state=pending&retention-class=supporting",
      }));
      return { key, staged: true };
    },
    async markReady({ key }) {
      await s3.send(new PutObjectTaggingCommand({
        Bucket: bucket,
        Key: key,
        Tagging: { TagSet: [
          { Key: "upload-state", Value: "ready" },
          { Key: "retention-class", Value: "supporting" },
        ] },
      }));
    },
    async signedDownload(attachment) {
      if (!attachment?.objectKey || attachment.storageStatus !== "ready") throw new TypeError("attachment is not available");
      const command = new GetObjectCommand({
        Bucket: bucket,
        Key: attachment.objectKey,
        ResponseContentType: attachment.contentType,
        ResponseContentDisposition: safeDisposition(attachment.fileName),
      });
      const url = await signer(s3, command, { expiresIn: downloadTtlSeconds });
      return {
        url,
        expiresAt: new Date(clock() + downloadTtlSeconds * 1000).toISOString(),
        contentType: attachment.contentType,
        byteSize: attachment.byteSize,
        sha256: attachment.sha256,
      };
    },
    async reconcile({ tenantId, records, minimumAgeMs = 24 * 60 * 60 * 1000 }) {
      if (!UUID.test(tenantId) || !Array.isArray(records) || minimumAgeMs < 60 * 60 * 1000) throw new TypeError("invalid attachment reconciliation request");
      const prefix = `attachments/${tenantId}/`;
      const known = new Map(records.map((record) => [record.objectKey, record]));
      const present = new Set();
      const readyAttachmentIds = [];
      let taggedOrphans = 0;
      let continuationToken;
      do {
        const page = await s3.send(new ListObjectsV2Command({ Bucket: bucket, Prefix: prefix, ContinuationToken: continuationToken }));
        for (const object of page.Contents || []) {
          if (!object.Key) continue;
          present.add(object.Key);
          const age = clock() - new Date(object.LastModified || 0).getTime();
          const record = known.get(object.Key);
          if (!record && age >= minimumAgeMs) {
            await s3.send(new PutObjectTaggingCommand({ Bucket: bucket, Key: object.Key, Tagging: { TagSet: [
              { Key: "upload-state", Value: "orphaned" },
              { Key: "retention-class", Value: "supporting" },
            ] } }));
            taggedOrphans += 1;
          } else if (record?.storageStatus === "pending") {
            const tags = await s3.send(new GetObjectTaggingCommand({ Bucket: bucket, Key: object.Key }));
            if (tags.TagSet?.some(({ Key, Value }) => Key === "upload-state" && Value === "ready")) readyAttachmentIds.push(record.id);
          }
        }
        continuationToken = page.IsTruncated ? page.NextContinuationToken : undefined;
      } while (continuationToken);
      const missingAttachmentIds = records
        .filter((record) => record.storageStatus === "pending" && clock() - Date.parse(record.createdAt) >= minimumAgeMs && !present.has(record.objectKey))
        .map((record) => record.id);
      return { scanned: present.size, taggedOrphans, readyAttachmentIds, missingAttachmentIds };
    },
  });
}
