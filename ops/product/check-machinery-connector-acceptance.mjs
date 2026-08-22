#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import process from "node:process";
import { pathToFileURL } from "node:url";

const DIGEST = /^sha256:[0-9a-f]{64}$/;
const URI = /^(?:artifact|https|s3):\/\/.+/;
const FLOWS = ["import", "field_match", "journal_candidate", "human_confirmation", "audit", "retry_idempotency", "unit_conversion", "provider_outage_file_continuity"];
const evidence = (value) => typeof value === "string" && URI.test(value) && !/replace-me|example|todo/i.test(value);

export function validateMachineryConnectorAcceptance(value) {
  const errors = [];
  const add = (message) => errors.push(message);
  if (value?.schema_version !== 1 || value?.status !== "PASS") add("connector acceptance must be schema 1 and PASS");
  if (value?.contract?.executed !== true || !value.contract?.provider || !value.contract?.customer || !evidence(value.contract?.evidence)) add("an executed provider/customer contract with evidence is required");
  if (value?.sample?.class !== "real_anonymized" || !DIGEST.test(value.sample?.digest ?? "") || !evidence(value.sample?.custody_evidence)) add("a real anonymized sample digest and custody evidence are required");
  if (value?.machine?.physical !== true || !value.machine?.manufacturer || !value.machine?.model || !value.machine?.firmware || !evidence(value.machine?.evidence)) add("physical machine identity and evidence are required");
  if (!DIGEST.test(value?.adapter?.artifact_digest ?? "") || value.adapter?.signature_verified !== true || !evidence(value.adapter?.provenance)) add("a signed adapter artifact and provenance are required");
  for (const flow of FLOWS) {
    const result = value?.vertical_acceptance?.[flow];
    if (result?.status !== "PASS" || !evidence(result?.evidence)) add(`vertical_acceptance.${flow} must PASS with evidence`);
  }
  const actors = new Set();
  const roles = new Set();
  for (const [index, approval] of (Array.isArray(value?.approvals) ? value.approvals : []).entries()) {
    if (!approval?.actor || !evidence(approval?.evidence)) add(`approvals[${index}] requires actor and evidence`);
    else actors.add(approval.actor);
    roles.add(approval?.role);
  }
  if (actors.size < 2 || !roles.has("connector_owner") || !roles.has("independent_verifier")) add("two distinct connector_owner and independent_verifier approvals are required");
  if (!Array.isArray(value?.missing_ecosystem) || !value.missing_ecosystem.length) add("missing_ecosystem disclosure must not be empty");
  return errors;
}

export async function main(argv = process.argv.slice(2)) {
  if (argv.length !== 1) { console.error("usage: node ops/product/check-machinery-connector-acceptance.mjs ACCEPTANCE.json"); return 2; }
  try {
    const value = JSON.parse(await readFile(argv[0], "utf8"));
    const errors = validateMachineryConnectorAcceptance(value);
    if (errors.length) {
      console.error(`machinery connector acceptance: BLOCKED (${errors.length})`);
      errors.forEach((error) => console.error(`- ${error}`));
      return 1;
    }
    console.log(`machinery connector acceptance: PASS ${value.connector_id}`);
    return 0;
  } catch (error) {
    console.error(`machinery connector acceptance: FAIL\n- ${error.message}`);
    return 2;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main();
