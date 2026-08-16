import assert from "node:assert/strict";
import test from "node:test";

import { findHardcodedText } from "./check-i18n-coverage.mjs";

test("finds untranslated Japanese source text", () => {
  assert.deepEqual(findHardcodedText('const label = "作業日誌";'), [{ line: 1, text: 'const label = "作業日誌";' }]);
});

test("allows an explicit non-UI identifier exemption", () => {
  assert.deepEqual(findHardcodedText('const fixture = "圃場"; // i18n-ignore'), []);
});
