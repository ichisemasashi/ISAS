import assert from "node:assert/strict";
import test from "node:test";
import { evaluateStagingAcceptance, REQUIRED_CHECKS } from "../check-staging-acceptance.mjs";

function evidence(status = "PASS") {
  return {
    schemaVersion: 1,
    deploymentId: "isas-jp-stg-01",
    environment: "staging",
    accountId: "123456789012",
    region: "ap-northeast-1",
    commitSha: "a".repeat(40),
    tofuPlanSha256: `sha256:${"b".repeat(64)}`,
    collectedAt: "2026-08-15T00:00:00.000Z",
    checks: REQUIRED_CHECKS.map((id) => ({ id, status, evidence: `${id} verified by AWS API` })),
  };
}

test("all live checks PASS makes staging ready", () => {
  assert.deepEqual(evaluateStagingAcceptance(evidence(), new Date("2026-08-15T12:00:00.000Z")), { ready: true, errors: [] });
});

test("BLOCKED cannot be mistaken for acceptance", () => {
  const result = evaluateStagingAcceptance(evidence("BLOCKED"), new Date("2026-08-15T12:00:00.000Z"));
  assert.equal(result.ready, false);
  assert.match(result.errors.join("\n"), /account-region is BLOCKED/);
});

test("missing check and weak evidence are rejected", () => {
  const input = evidence();
  input.checks = input.checks.filter(({ id }) => id !== "auth-production-security");
  input.checks[0].evidence = "none";
  const result = evaluateStagingAcceptance(input, new Date("2026-08-15T12:00:00.000Z"));
  assert.equal(result.ready, false);
  assert.match(result.errors.join("\n"), /missing check: auth-production-security/);
  assert.match(result.errors.join("\n"), /needs concrete evidence/);
});

test("stale evidence is rejected", () => {
  const result = evaluateStagingAcceptance(evidence(), new Date("2026-08-17T00:00:01.000Z"));
  assert.equal(result.ready, false);
  assert.match(result.errors.join("\n"), /within the last 24 hours/);
});
