import test from "node:test";
import assert from "node:assert/strict";
import { findPlaceholders } from "./check-runbook-placeholders.mjs";

test("accepts concrete logical contacts and commands", () => {
  assert.deepEqual(findPlaceholders("ISAS-JP-OnCall / ops/recovery/create-recovery-set.sh"), []);
});

test("finds unresolved values without flagging prose about placeholders", () => {
  const matches = findPlaceholders("contact: `未設定`\nhost: example.invalid\nrun <backup-adapter>\nplaceholder scan");
  assert.deepEqual(matches.map((item) => item.line), [1, 2, 3]);
});
