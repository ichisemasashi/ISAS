import assert from "node:assert/strict";
import test from "node:test";
import { parseArguments } from "./register-test-user.mjs";

test("test user defaults to a scoped worker", () => {
  assert.deepEqual(parseArguments([]), { username: "test-worker", displayName: "テスト作業者", role: "worker" });
});

test("test user accepts an explicit local role and rejects unsafe input", () => {
  assert.deepEqual(parseArguments(["--username", "test-supervisor", "--display-name", "試験責任者", "--role", "field_supervisor"]),
    { username: "test-supervisor", displayName: "試験責任者", role: "field_supervisor" });
  assert.throws(() => parseArguments(["--username", "../../escape"]), /username/);
  assert.throws(() => parseArguments(["--role", "superuser"]), /role must be/);
  assert.throws(() => parseArguments(["--display-name", "bad\nname"]), /display name/);
});
