import { describe, expect, test } from "vitest";
import { utGateway } from "./ut-fixture";

describe("UT fixture", () => {
  test("provides the journal and pesticide scenarios without production data", async () => {
    const instructions = await utGateway.getWorkInstructions("ctx-ut");
    expect(instructions.instructions).toHaveLength(1);
    expect(instructions.instructions[0].fieldName).toContain("練習用");
    const journal = await utGateway.getJournalBootstrap("ctx-ut", { instructionId: instructions.instructions[0].id });
    expect(journal.instruction?.fieldName).toContain("練習用");
    expect(journal.punchSuggestion).toEqual(expect.objectContaining({ startedAt: "08:12", endedAt: "09:36" }));
    const pesticide = await utGateway.getPesticideBootstrap("ctx-ut", instructions.instructions[0].fieldId);
    expect(pesticide.chemicals.some((chemical) => chemical.id === "chemical-warning")).toBe(true);
    expect(pesticide.usage).toContainEqual(expect.objectContaining({ chemicalId: "chemical-warning", usageCount: 1 }));
  });

  test("accepts fixture synchronization bundles idempotency-shape", async () => {
    const result = await utGateway.push("ctx-ut", "csrf-ut", [{ bundleId: "bundle-1", events: [{ eventUuid: "event-1" } as never] }]);
    expect(result.results).toEqual([{ bundleId: "bundle-1", status: "accepted", events: [expect.objectContaining({ eventUuid: "event-1" })] }]);
  });
});
