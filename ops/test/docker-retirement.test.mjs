import assert from "node:assert/strict";
import test from "node:test";
import { checkRepository, validateInventory } from "../docker-retirement/check-docker-retirement.mjs";

test("tracked Docker dependencies are registered for staged retirement", async () => {
  assert.deepEqual(await checkRepository(), []);
});

test("retirement inventory rejects missing replacement ownership", () => {
  const invalid = {
    schema_version: 1,
    decision: "ADR-0024",
    target: "zero-active-docker-dependencies",
    phases: [
      { id: "R0", status: "completed" },
      ...["R1", "R2", "R3", "R4", "R5"].map((id) => ({ id, status: "pending" }))
    ],
    dependencies: [{ id: "bad", status: "active-transitional", retirement_phase: "R2", category: "local", owner: "", replacement: "", paths: [], completion_checks: [] }]
  };
  assert.ok(validateInventory(invalid).some((error) => error.includes("owner")));
  assert.ok(validateInventory(invalid).some((error) => error.includes("replacement")));
});

test("retirement phases cannot complete out of order", () => {
  const invalid = {
    schema_version: 1,
    decision: "ADR-0024",
    target: "zero-active-docker-dependencies",
    phases: [
      { id: "R0", status: "completed" },
      { id: "R1", status: "pending" },
      { id: "R2", status: "completed" },
      ...["R3", "R4", "R5"].map((id) => ({ id, status: "pending" }))
    ],
    dependencies: []
  };
  assert.ok(validateInventory(invalid).some((error) => error.includes("R2 cannot be completed")));
});
