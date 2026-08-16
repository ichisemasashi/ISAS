import assert from "node:assert/strict";
import test from "node:test";

import { findPhysicalDirections } from "./check-rtl-css.mjs";

test("rejects physical directional CSS", () => {
  assert.equal(findPhysicalDirections(".x { margin-left: 1rem; text-align: right; }").length, 1);
});

test("accepts logical directional CSS", () => {
  assert.deepEqual(findPhysicalDirections(".x { margin-inline-start: 1rem; text-align: start; }"), []);
});
