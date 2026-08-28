import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";
import { readFileSync } from "node:fs";
import { isAbsolute, relative, resolve } from "node:path";

const VERSION = 1;

function aad(profile, purpose, recordId) {
  if (!/^[a-z][a-z0-9_.:-]{0,63}$/.test(purpose || "") || !/^[A-Za-z0-9_:-]{1,200}$/.test(recordId || "")) throw new Error("invalid local encryption binding");
  return Buffer.from(JSON.stringify({ profile, purpose, recordId }), "utf8");
}

export function readLocalKey(path, { secretRoot = "/run/isas/secrets" } = {}) {
  const child = relative(resolve(secretRoot), resolve(path || ""));
  if (!child || child.startsWith("..") || isAbsolute(child)) throw new Error("local key path is outside the secret mount");
  const value = readFileSync(path, "utf8").trim();
  const key = Buffer.from(value, "base64url");
  if (key.length !== 32) throw new Error("local key must be 256 bits");
  return key;
}

export function createLocalEnvelopeCrypto({ key, profile = "local-integration" }) {
  if (!Buffer.isBuffer(key) || key.length !== 32 || profile !== "local-integration") throw new Error("local envelope crypto configuration is invalid");
  return Object.freeze({
    seal(value, purpose, recordId) {
      const nonce = randomBytes(12);
      const cipher = createCipheriv("aes-256-gcm", key, nonce);
      cipher.setAAD(aad(profile, purpose, recordId));
      const ciphertext = Buffer.concat([cipher.update(JSON.stringify(value), "utf8"), cipher.final()]);
      return Buffer.concat([Buffer.from([VERSION]), nonce, cipher.getAuthTag(), ciphertext]);
    },
    open(envelope, purpose, recordId) {
      const bytes = Buffer.from(envelope || []);
      if (bytes.length < 30 || bytes[0] !== VERSION) throw new Error("invalid local ciphertext envelope");
      const decipher = createDecipheriv("aes-256-gcm", key, bytes.subarray(1, 13));
      decipher.setAAD(aad(profile, purpose, recordId));
      decipher.setAuthTag(bytes.subarray(13, 29));
      return JSON.parse(Buffer.concat([decipher.update(bytes.subarray(29)), decipher.final()]).toString("utf8"));
    },
    sealString(value, purpose, recordId) { return this.seal(value, purpose, recordId).toString("base64url"); },
    openString(value, purpose, recordId) { return this.open(Buffer.from(value, "base64url"), purpose, recordId); }
  });
}
