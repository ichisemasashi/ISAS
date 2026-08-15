import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";
import { DecryptCommand, GenerateDataKeyCommand } from "@aws-sdk/client-kms";

const PROFILE = "ISAS-AES-256-GCM-v1";

function encoded(value) {
  return Buffer.from(value).toString("base64url");
}

function decoded(value) {
  if (typeof value !== "string" || !value) throw new Error("Invalid encrypted envelope");
  return Buffer.from(value, "base64url");
}

function encryptionContext({ deploymentId, jurisdiction, purpose, resourceId }) {
  return {
    deployment_id: deploymentId,
    jurisdiction,
    purpose,
    resource_id: resourceId,
    profile: PROFILE,
  };
}

export function createKmsEnvelopeCrypto({ kms, keyId, deploymentId, jurisdiction = "JP" }) {
  if (!kms?.send || !keyId || !deploymentId) throw new Error("KMS envelope configuration is incomplete");

  return Object.freeze({
    async encrypt(value, { purpose, resourceId }) {
      const context = encryptionContext({ deploymentId, jurisdiction, purpose, resourceId });
      const generated = await kms.send(new GenerateDataKeyCommand({
        KeyId: keyId,
        KeySpec: "AES_256",
        EncryptionContext: context,
      }));
      if (!generated.Plaintext || !generated.CiphertextBlob) throw new Error("KMS did not return a data key");
      const key = Buffer.from(generated.Plaintext);
      const iv = randomBytes(12);
      const aad = Buffer.from(JSON.stringify(context));
      try {
        const cipher = createCipheriv("aes-256-gcm", key, iv);
        cipher.setAAD(aad);
        const ciphertext = Buffer.concat([cipher.update(JSON.stringify(value), "utf8"), cipher.final()]);
        return JSON.stringify({
          version: 1,
          profile: PROFILE,
          encryptedKey: encoded(generated.CiphertextBlob),
          iv: encoded(iv),
          tag: encoded(cipher.getAuthTag()),
          ciphertext: encoded(ciphertext),
        });
      } finally {
        key.fill(0);
      }
    },

    async decrypt(serialized, { purpose, resourceId }) {
      let envelope;
      try { envelope = JSON.parse(serialized); } catch { throw new Error("Invalid encrypted envelope"); }
      if (envelope?.version !== 1 || envelope?.profile !== PROFILE) throw new Error("Unsupported encrypted envelope");
      const context = encryptionContext({ deploymentId, jurisdiction, purpose, resourceId });
      const result = await kms.send(new DecryptCommand({
        CiphertextBlob: decoded(envelope.encryptedKey),
        EncryptionContext: context,
        KeyId: keyId,
      }));
      if (!result.Plaintext) throw new Error("KMS did not decrypt the data key");
      const key = Buffer.from(result.Plaintext);
      try {
        const decipher = createDecipheriv("aes-256-gcm", key, decoded(envelope.iv));
        decipher.setAAD(Buffer.from(JSON.stringify(context)));
        decipher.setAuthTag(decoded(envelope.tag));
        const plaintext = Buffer.concat([decipher.update(decoded(envelope.ciphertext)), decipher.final()]);
        return JSON.parse(plaintext.toString("utf8"));
      } finally {
        key.fill(0);
      }
    },
  });
}

export const envelopeCryptoProfile = PROFILE;
