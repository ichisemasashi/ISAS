import assert from "node:assert/strict";
import test from "node:test";
import { validateBuildPromotion } from "../check-build-promotion.mjs";

const digest = `sha256:${"a".repeat(64)}`;
const build = { version: "v1.2.3", source_commit: "b".repeat(40), artifacts: [{ name: "bff", digest }] };
const release = { release: { version: "1.2.3", source_commit: "b".repeat(40) }, deployment: { jurisdiction: "JP" }, artifacts: [{ name: "bff", digest, signature_verified: true, provenance_verified: true }] };
const evidence = { environment: "staging", region: "ap-northeast-1", commitSha: "b".repeat(40), checks: [{ status: "PASS" }] };

test("permits only the tested and approved build artifact set", () => {
  assert.deepEqual(validateBuildPromotion(build, release, evidence), []);
});

test("blocks rebuild, digest drift, and stale staging evidence", () => {
  const changed = structuredClone(release);
  changed.artifacts[0].digest = `sha256:${"c".repeat(64)}`;
  assert.ok(validateBuildPromotion(build, changed, { ...evidence, commitSha: "d".repeat(40) }).length >= 2);
});
