import { createHash, generateKeyPairSync, sign } from "node:crypto";
import assert from "node:assert/strict";
import test from "node:test";

import { authorizationPayload, validateProductionTagAuthorization } from "../check-production-tag-authorization.mjs";

const canonical = (value) => Array.isArray(value) ? `[${value.map(canonical).join(",")}]` : value && typeof value === "object"
  ? `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(",")}}` : JSON.stringify(value);
const digest = (value) => `sha256:${createHash("sha256").update(value).digest("hex")}`;

function fixture() {
  const inputBytes = Object.fromEntries(["release", "build", "delivery", "bake"].map((name) => [name, Buffer.from(`${name}\n`)]));
  const repository = "owner/isas";
  const snapshot = { id: 42, enforcement: "active", pattern: "production/v*" };
  const audit = { id: "evt-123", actor: "release-bot", action: "production_tag_authorized" };
  const authorization = {
    schema_version: 1,
    repository,
    workflow_run_id: "12345",
    environment: "production-release",
    tag: { name: "production/v1.2.3", target_commit: "1".repeat(40) },
    inputs: Object.fromEntries(Object.entries(inputBytes).map(([name, bytes]) => [name, { digest: digest(bytes) }])),
    approvals: [
      { actor: "manager", role: "release_manager", approval_id: "approval-1", provider: "github-environment", repository, environment: "production-release", verified_subject: `repo:${repository}:environment:production-release` },
      { actor: "verifier", role: "independent_verifier", approval_id: "approval-2", provider: "github-environment", repository, environment: "production-release", verified_subject: `repo:${repository}:environment:production-release` },
    ],
    tag_ruleset: { ruleset_id: 42, enforcement: "active", pattern: "production/v*", snapshot, snapshot_digest: digest(Buffer.from(canonical(snapshot))) },
    audit_event: { event_id: "evt-123", event_type: "production_tag_authorized", snapshot: audit, snapshot_digest: digest(Buffer.from(canonical(audit))) },
    attestation: { algorithm: "ed25519", issuer: "https://token.actions.githubusercontent.com", subject: `repo:${repository}:environment:production-release`, signature_base64: "" },
  };
  const { publicKey, privateKey } = generateKeyPairSync("ed25519");
  authorization.attestation.signature_base64 = sign(null, authorizationPayload(authorization), privateKey).toString("base64");
  return { authorization, inputBytes, trustedPublicKey: publicKey.export({ type: "spki", format: "pem" }) };
}

test("accepts content-bound evidence and verified environment approvals", () => {
  assert.deepEqual(validateProductionTagAuthorization(fixture()), []);
});

test("rejects substituted evidence after authorization", () => {
  const value = fixture();
  value.inputBytes.bake = Buffer.from("substituted\n");
  assert.ok(validateProductionTagAuthorization(value).some((error) => error.includes("bake.digest")));
});

test("rejects arbitrary approvers and an inactive tag ruleset", () => {
  const value = fixture();
  value.authorization.approvals[1].provider = "manual";
  value.authorization.tag_ruleset.enforcement = "evaluate";
  const errors = validateProductionTagAuthorization(value);
  assert.ok(errors.some((error) => error.includes("provider")));
  assert.ok(errors.some((error) => error.includes("protected tag ruleset")));
  assert.ok(errors.some((error) => error.includes("signature verification failed")));
});
