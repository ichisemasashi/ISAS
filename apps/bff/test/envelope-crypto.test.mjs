import assert from "node:assert/strict";
import { test } from "node:test";
import { createKmsEnvelopeCrypto } from "../src/envelope-crypto.mjs";

test("KMS envelope encryption binds ciphertext to deployment, purpose, and resource", async () => {
  const key = Buffer.alloc(32, 7);
  const calls = [];
  const kms = { async send(command) {
    calls.push(command.input);
    if (command.constructor.name === "GenerateDataKeyCommand") return { Plaintext: key, CiphertextBlob: Buffer.from("encrypted-data-key") };
    return { Plaintext: key };
  } };
  const crypto = createKmsEnvelopeCrypto({ kms, keyId: "arn:aws:kms:key", deploymentId: "isas-jp-prod-01" });
  const encrypted = await crypto.encrypt({ accessToken: "secret-token" }, { purpose: "oidc-token-set", resourceId: "user:token" });
  assert.equal(encrypted.includes("secret-token"), false);
  assert.deepEqual(await crypto.decrypt(encrypted, { purpose: "oidc-token-set", resourceId: "user:token" }), { accessToken: "secret-token" });
  await assert.rejects(() => crypto.decrypt(encrypted, { purpose: "oidc-token-set", resourceId: "another-user" }));
  assert.equal(calls[0].EncryptionContext.resource_id, "user:token");
  assert.equal(calls[1].EncryptionContext.profile, "ISAS-AES-256-GCM-v1");
});
