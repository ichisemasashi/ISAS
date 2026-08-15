import assert from "node:assert/strict";
import test from "node:test";
import { artifactFragment, buildManifest } from "../build-artifact-manifest.mjs";

const digest = `sha256:${"a".repeat(64)}`;

test("build manifest requires one immutable artifact for every implemented component", () => {
  const artifacts = ["web", "bff", "migration"].map((name) => artifactFragment(name, `123.dkr.ecr.ap-northeast-1.amazonaws.com/isas-jp-stg/${name}`, digest, `${name}.spdx.json`));
  const manifest = buildManifest({ version: "v1.2.3", sourceCommit: "b".repeat(40), runId: "123", createdAt: "2026-08-16T00:00:00Z", artifacts });
  assert.deepEqual(manifest.artifacts.map(({ name }) => name), ["bff", "migration", "web"]);
  assert.equal(manifest.artifacts[0].reference.endsWith(`@${digest}`), true);
});

test("build manifest rejects tags and incomplete artifact sets", () => {
  assert.throws(() => artifactFragment("bff", "registry/isas/bff", "latest", "bff.spdx.json"), /digest/);
  assert.throws(() => buildManifest({ version: "v1.2.3", sourceCommit: "b".repeat(40), runId: "1", createdAt: "2026-08-16T00:00:00Z", artifacts: [] }), /exactly/);
});
