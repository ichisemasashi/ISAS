import assert from "node:assert/strict";
import test from "node:test";
import { validateMachineryConnectorAcceptance } from "./check-machinery-connector-acceptance.mjs";

function accepted() {
  const pass = (name) => ({ status: "PASS", evidence: `artifact://connector/${name}` });
  return {
    schema_version: 1, status: "PASS", connector_id: "contracted-connector-1",
    contract: { executed: true, provider: "provider", customer: "customer", evidence: "artifact://connector/contract" },
    sample: { class: "real_anonymized", digest: `sha256:${"a".repeat(64)}`, custody_evidence: "artifact://connector/sample-custody" },
    machine: { physical: true, manufacturer: "maker", model: "model", firmware: "1.0", evidence: "artifact://connector/machine" },
    adapter: { artifact_digest: `sha256:${"b".repeat(64)}`, signature_verified: true, provenance: "artifact://connector/provenance" },
    vertical_acceptance: Object.fromEntries(["import", "field_match", "journal_candidate", "human_confirmation", "audit", "retry_idempotency", "unit_conversion", "provider_outage_file_continuity"].map((name) => [name, pass(name)])),
    approvals: [
      { actor: "owner", role: "connector_owner", evidence: "artifact://connector/owner" },
      { actor: "verifier", role: "independent_verifier", evidence: "artifact://connector/verifier" },
    ],
    missing_ecosystem: ["drone", "variable-rate fertilization"],
  };
}

test("accepts a contracted real-sample physical-machine vertical slice", () => assert.deepEqual(validateMachineryConnectorAcceptance(accepted()), []));
test("rejects a design-only connector or hidden ecosystem gap", () => {
  const value = accepted();
  value.contract.executed = false;
  value.machine.physical = false;
  value.missing_ecosystem = [];
  const errors = validateMachineryConnectorAcceptance(value);
  assert.ok(errors.some((error) => error.includes("executed")));
  assert.ok(errors.some((error) => error.includes("physical")));
  assert.ok(errors.some((error) => error.includes("missing_ecosystem")));
});
