import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { validateComponentLock, versionAtLeast } from "./doctor.mjs";

test("semantic version floorを比較する", () => {
  assert.equal(versionAtLeast("v22.18.0", "22.0.0"), true);
  assert.equal(versionAtLeast("2.30.9", "2.31.0"), false);
});

test("全imageがdigestとarm64/amd64 digestを持つ", () => {
  const lock = JSON.parse(readFileSync(new URL("../../infra/local/component-lock.json", import.meta.url)));
  assert.equal(validateComponentLock(lock), true);
});

test("mutable tagを拒否する", () => {
  const invalid = { schemaVersion: 1, profile: "local-integration", platforms: ["linux/arm64", "linux/amd64"], images: {} };
  for (let index = 0; index < 8; index += 1) invalid.images[`image${index}`] = {
    image: "example.invalid/image:latest",
    platformDigests: { "linux/arm64": `sha256:${"a".repeat(64)}`, "linux/amd64": `sha256:${"b".repeat(64)}` }
  };
  assert.throws(() => validateComponentLock(invalid), /digest固定/);
});
