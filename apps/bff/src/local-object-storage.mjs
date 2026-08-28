import { createHash, randomBytes } from "node:crypto";
import { lstat, mkdir, open, readFile, rename, stat, unlink } from "node:fs/promises";
import { dirname, resolve, sep } from "node:path";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const SHA256 = /^[0-9a-f]{64}$/;

function storageConflict(message) { return Object.assign(new Error(message), { code: "idempotency_conflict" }); }

export function createLocalObjectStorage({ root, allowedRoot = "/var/lib/isas/objects", crypto, origin, clock = () => Date.now(), downloadTtlSeconds = 60 }) {
  const storageRoot = resolve(root || "");
  if (storageRoot !== resolve(allowedRoot) || !crypto?.seal || !crypto?.open || new URL(origin).origin !== origin) throw new Error("local object storage configuration is invalid");
  const downloads = new Map();
  const pathFor = (key) => {
    const path = resolve(storageRoot, key);
    if (!path.startsWith(`${storageRoot}${sep}`)) throw new Error("object path escapes storage root");
    return path;
  };
  const binding = (key) => createHash("sha256").update(key).digest("base64url");

  async function existing(path) {
    try { return await lstat(path); } catch (error) { if (error.code === "ENOENT") return null; throw error; }
  }

  return Object.freeze({
    objectKey(tenantId, attachment) {
      if (!UUID.test(tenantId) || !UUID.test(attachment?.attachmentId) || !SHA256.test(attachment?.sha256 || "")) throw new TypeError("invalid attachment object identity");
      return `attachments/${tenantId}/${attachment.attachmentId}/${attachment.sha256}.bin`;
    },
    async startupCheck() {
      await mkdir(storageRoot, { recursive: true, mode: 0o700 });
      const info = await lstat(storageRoot);
      if (!info.isDirectory() || info.isSymbolicLink()) throw new Error("local object root is unsafe");
    },
    async stage({ tenantId, userId, attachment, key }) {
      if (key !== this.objectKey(tenantId, attachment) || !UUID.test(userId)) throw new TypeError("invalid attachment storage request");
      const path = pathFor(key);
      await mkdir(dirname(path), { recursive: true, mode: 0o700 });
      const envelope = crypto.seal({ contentType: attachment.contentType, sha256: attachment.sha256, bytes: Buffer.from(attachment.bytes).toString("base64") }, "object", binding(key));
      const found = await existing(path);
      if (found) {
        if (!found.isFile() || found.isSymbolicLink()) throw new Error("local object path is unsafe");
        const value = crypto.open(await readFile(path), "object", binding(key));
        if (value.sha256 !== attachment.sha256) throw storageConflict("attachment object differs");
        return { key, staged: false };
      }
      const temporary = `${path}.${randomBytes(8).toString("hex")}.pending`;
      const handle = await open(temporary, "wx", 0o600);
      try { await handle.writeFile(envelope); await handle.sync(); } finally { await handle.close(); }
      await rename(temporary, path);
      return { key, staged: true };
    },
    async markReady({ key }) { const info = await stat(pathFor(key)); if (!info.isFile()) throw new Error("staged object is missing"); },
    async signedDownload(attachment) {
      if (!attachment?.objectKey || attachment.storageStatus !== "ready") throw new TypeError("attachment is not available");
      const token = randomBytes(32).toString("base64url");
      const tenantId = attachment.objectKey.split("/")[1];
      downloads.set(createHash("sha256").update(token).digest("base64url"), { ...attachment, tenantId, expiresAt: clock() + downloadTtlSeconds * 1000 });
      return { url: `${origin}/api/v1/local-objects/${token}`, expiresAt: new Date(clock() + downloadTtlSeconds * 1000).toISOString(), contentType: attachment.contentType, byteSize: attachment.byteSize, sha256: attachment.sha256 };
    },
    async readSigned(token, trusted) {
      const digest = createHash("sha256").update(token || "").digest("base64url");
      const item = downloads.get(digest);
      downloads.delete(digest);
      if (!item || item.expiresAt <= clock() || item.tenantId !== trusted?.authContext?.tenantId) throw Object.assign(new Error("download token is invalid"), { code: "forbidden" });
      const value = crypto.open(await readFile(pathFor(item.objectKey)), "object", binding(item.objectKey));
      const bytes = Buffer.from(value.bytes, "base64");
      if (createHash("sha256").update(bytes).digest("hex") !== item.sha256) throw new Error("local object integrity mismatch");
      return { bytes, contentType: item.contentType, fileName: item.fileName };
    },
    async reconcile({ tenantId, records, minimumAgeMs = 24 * 60 * 60 * 1000 }) {
      if (!UUID.test(tenantId) || !Array.isArray(records) || minimumAgeMs < 3600000) throw new TypeError("invalid attachment reconciliation request");
      const missingAttachmentIds = [];
      for (const record of records) {
        const found = await existing(pathFor(record.objectKey));
        if (!found && record.storageStatus === "pending" && clock() - Date.parse(record.createdAt) >= minimumAgeMs) missingAttachmentIds.push(record.id);
      }
      return { scanned: records.length - missingAttachmentIds.length, taggedOrphans: 0, readyAttachmentIds: [], missingAttachmentIds };
    },
    async removeForTest(key) { await unlink(pathFor(key)); }
  });
}
