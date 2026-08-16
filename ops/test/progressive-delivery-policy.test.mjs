import assert from "node:assert/strict";
import test from "node:test";
import { validateDelivery } from "../progressive-delivery-policy.mjs";

const digest = `sha256:${"a".repeat(64)}`;
const deployment = {
  deployment_id: "isas-jp-prod-01",
  ecs: {
    services: { web: "web", bff: "bff", web_canary: "web-canary", bff_canary: "bff-canary" },
    progressive_delivery: Object.fromEntries(["listener_arn", "bff_rule_arn", "web_stable_tg", "web_canary_tg", "bff_stable_tg", "bff_canary_tg", "fast_burn_alarm", "slow_burn_alarm"].map((key) => [key, key])),
  },
};
const build = { source_commit: "b".repeat(40), artifact_set_digest: digest, artifacts: ["web", "bff", "migration"].map((name) => ({ name, reference: `registry/${name}@${digest}` })) };

test("allows only prepared to 5 to 25 to 100 delivery", () => {
  let state = null;
  for (const command of ["prepare", "5", "25", "100", "finalize"]) {
    assert.deepEqual(validateDelivery({ command, deployment, build, state }), []);
    state = { stage: command === "prepare" ? "prepared" : command === "finalize" ? "finalized" : command, deployment_id: deployment.deployment_id, source_commit: build.source_commit, artifact_set_digest: digest };
  }
});

test("allows repeat staging deployments without weakening production transitions", () => {
  assert.deepEqual(validateDelivery({ command: "staging", deployment, build, state: null }), []);
  const state = { stage: "staging", deployment_id: deployment.deployment_id, source_commit: "c".repeat(40), artifact_set_digest: `sha256:${"c".repeat(64)}` };
  assert.deepEqual(validateDelivery({ command: "staging", deployment, build, state }), []);
});

test("blocks skipping a stage and artifact drift", () => {
  const state = { stage: "prepared", deployment_id: deployment.deployment_id, source_commit: build.source_commit, artifact_set_digest: digest };
  assert.ok(validateDelivery({ command: "25", deployment, build, state }).some((error) => error.includes("transition")));
  assert.ok(validateDelivery({ command: "5", deployment, build: { ...build, source_commit: "c".repeat(40) }, state }).some((error) => error.includes("source_commit")));
});

test("allows rollback from every active stage but never finalized", () => {
  for (const stage of ["prepared", "5", "25", "100"]) {
    const state = { stage, deployment_id: deployment.deployment_id, source_commit: build.source_commit, artifact_set_digest: digest };
    assert.deepEqual(validateDelivery({ command: "rollback", deployment, build, state }), []);
  }
  const finalized = { stage: "finalized", deployment_id: deployment.deployment_id, source_commit: build.source_commit, artifact_set_digest: digest };
  assert.ok(validateDelivery({ command: "rollback", deployment, build, state: finalized }).length > 0);
});
