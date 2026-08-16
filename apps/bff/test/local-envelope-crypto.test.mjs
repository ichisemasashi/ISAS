import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import test from "node:test";
import { createLocalEnvelopeCrypto } from "../src/local-envelope-crypto.mjs";

test("local envelope binds ciphertext to purpose and record", () => {
  const crypto = createLocalEnvelopeCrypto({ key: randomBytes(32) });
  const sealed = crypto.seal({ token: "secret" }, "session", "record-1");
  assert.deepEqual(crypto.open(sealed, "session", "record-1"), { token: "secret" });
  assert.throws(() => crypto.open(sealed, "context", "record-1"));
  assert.throws(() => crypto.open(sealed, "session", "record-2"));
  assert.equal(sealed.includes(Buffer.from("secret")), false);
});

test("invalid key size is rejected", () => {
  assert.throws(() => createLocalEnvelopeCrypto({ key: randomBytes(16) }), /invalid/);
});
