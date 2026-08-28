import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { validateComponentLock, versionAtLeast } from "./doctor.mjs";

test("semantic version floorを比較する", () => {
  assert.equal(versionAtLeast("v22.18.0", "22.0.0"), true);
  assert.equal(versionAtLeast("2.30.9", "2.31.0"), false);
});

test("全native componentとMac両architectureのartifactが固定される", () => {
  const lock = JSON.parse(readFileSync(new URL("../../infra/local/component-lock.json", import.meta.url)));
  assert.equal(validateComponentLock(lock), true);
});

test("digestなしのnative artifactを拒否する", () => {
  const invalid = JSON.parse(readFileSync(new URL("../../infra/local/component-lock.json", import.meta.url)));
  invalid.components.keycloak.sha256 = "latest";
  assert.throws(() => validateComponentLock(invalid), /version固定/);
});
