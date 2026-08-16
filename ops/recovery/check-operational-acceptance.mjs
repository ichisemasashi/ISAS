#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import process from "node:process";
import { pathToFileURL } from "node:url";

const COMMIT = /^[0-9a-f]{40}$/;
const URI = /^(?:artifact|https|s3):\/\/.+/;
const RECOVERY_COMPONENTS = ["database", "session_context", "private_objects", "quarantine_archive", "shard_config", "offline_maps", "queues", "audit", "configuration", "kms"];
const BACKUP_COMPONENTS = ["database", "session_context", "private_objects", "quarantine_archive", "shard_config", "offline_maps"];
const VERIFY_CASES = ["schema", "rls_force_owner", "triggers_security_invoker", "audit_chain", "object_hashes", "queue_cursor", "idempotency", "revocation", "tenant_crossing", "synthetic_transaction"];
const OPERATION_CASES = ["cold_start", "graceful_stop", "rolling_restart", "dependency_failure", "incident_response"];
const CONTACTS = ["service_owner", "on_call", "security", "privacy", "legal"];

function artifact(value) { return typeof value === "string" && URI.test(value) && !/replace-me|example|todo|unset|未設定/i.test(value); }
function date(value) { return typeof value === "string" && Number.isFinite(Date.parse(value)); }
function recent(value, now, days) { return date(value) && Date.parse(value) <= now.getTime() && now.getTime() - Date.parse(value) <= days * 86400000; }
function passedWithEvidence(value) { return value?.status === "PASS" && artifact(value?.evidence); }

export function validateOperationalAcceptance(value, now = new Date()) {
  const errors = [];
  const add = (message) => errors.push(message);
  if (!value || typeof value !== "object" || Array.isArray(value)) return ["evidence must be an object"];
  if (value.schema_version !== 1) add("schema_version must be 1");
  if (value.status !== "PASS") add("status must be PASS after all drills and approvals");
  if (value.environment !== "staging") add("acceptance must be performed in staging");
  if (!COMMIT.test(value.source_commit ?? "") || /^0+$/.test(value.source_commit ?? "")) add("source_commit must be a non-zero commit");
  if (!recent(value.completed_at, now, 31)) add("completed_at must be within 31 days and not in the future");
  if (!passedWithEvidence(value.real_data_and_ut?.migration)) add("real CSV migration rehearsal must pass with evidence");
  if (!passedWithEvidence(value.real_data_and_ut?.ut)) add("real participant UT must pass with evidence");

  const recovery = value.recovery_set ?? {};
  if (recovery.status !== "PASS" || !/^rs-[A-Za-z0-9-]+$/.test(recovery.id ?? "") || !artifact(recovery.evidence)) add("approved recovery set evidence is required");
  const present = new Set(Array.isArray(recovery.components) ? recovery.components : []);
  for (const component of RECOVERY_COMPONENTS) if (!present.has(component)) add(`recovery set is missing ${component}`);
  if (!Number.isFinite(recovery.pitr_lag_seconds) || recovery.pitr_lag_seconds < 0 || recovery.pitr_lag_seconds > 900) add("recovery set PITR lag must be 900 seconds or less");
  if (!Number.isFinite(recovery.object_inventory_age_seconds) || recovery.object_inventory_age_seconds < 0 || recovery.object_inventory_age_seconds > 86400) add("recovery object inventory must be no more than 24 hours old");
  if (!Array.isArray(recovery.backup_jobs) || recovery.backup_jobs.some((job) => job?.status !== "COMPLETED" || !job?.recovery_point_arn)) add("all backup jobs must be COMPLETED with recovery points");
  else {
    const completedComponents = new Set(recovery.backup_jobs.map((job) => job?.component));
    for (const component of BACKUP_COMPONENTS) if (!completedComponents.has(component)) add(`completed backup jobs are missing ${component}`);
  }

  for (const [name, drill, maxAge] of [["monthly_restore", value.monthly_restore, 35], ["quarterly_dr", value.quarterly_dr, 100]]) {
    if (drill?.status !== "PASS" || !recent(drill?.executed_at, now, maxAge) || !artifact(drill?.evidence)) add(`${name} must pass recently with evidence`);
    if (drill?.isolated_account !== true || drill?.production_network_attached !== false || drill?.egress_mode !== "sink_only") add(`${name} must run in an isolated account/network with sink-only egress`);
    if (!Number.isFinite(drill?.data_loss_seconds) || drill.data_loss_seconds < 0 || drill.data_loss_seconds > 900) add(`${name} RPO must be 900 seconds or less`);
    if (!Number.isFinite(drill?.recovery_seconds) || drill.recovery_seconds < 0 || drill.recovery_seconds > 14400) add(`${name} RTO must be 14400 seconds or less`);
    for (const check of VERIFY_CASES) if (drill?.verification?.[check] !== "PASS") add(`${name}.verification.${check} must PASS`);
  }
  if (value.quarterly_dr?.unannounced_generation_selection !== true) add("quarterly DR must use an unannounced generation selection");

  for (const name of OPERATION_CASES) if (!passedWithEvidence(value.operations?.[name]) || value.operations[name]?.actual_staging !== true) add(`operations.${name} must pass in actual staging with evidence`);
  const inventory = value.operations_inventory ?? {};
  if (inventory.placeholder_matches !== 0 || !passedWithEvidence(inventory.placeholder_scan)) add("deployment runbooks must have zero unresolved placeholders");
  for (const name of CONTACTS) {
    const contact = inventory.contacts?.[name];
    if (!contact?.group || /replace-me|example|todo|unset|未設定/i.test(contact.group) || !artifact(contact.route_evidence)) add(`approved ${name} contact and route evidence are required`);
  }
  if (!passedWithEvidence(inventory.monitoring) || !passedWithEvidence(inventory.ledger)) add("monitoring and operations ledger must be approved with evidence");

  const roles = new Set(["service_owner", "restore_verifier", "security_oncall"]);
  const actors = new Set();
  if (!Array.isArray(value.approvals)) add("approvals are required");
  else value.approvals.forEach((approval, index) => {
    roles.delete(approval?.role);
    if (!approval?.actor) add(`approvals[${index}].actor is required`); else actors.add(approval.actor);
    if (!date(approval?.approved_at)) add(`approvals[${index}].approved_at is invalid`);
    if (!artifact(approval?.evidence)) add(`approvals[${index}].evidence is required`);
  });
  if (roles.size) add(`missing approval roles: ${[...roles].join(", ")}`);
  if (actors.size < 3) add("three distinct approval actors are required");
  return errors;
}

export async function main(argv = process.argv.slice(2)) {
  if (argv.length !== 1) { console.error("usage: node check-operational-acceptance.mjs <evidence.json>"); return 2; }
  try {
    const evidence = JSON.parse(await readFile(argv[0], "utf8"));
    const errors = validateOperationalAcceptance(evidence);
    if (errors.length) {
      console.error(`BACKUP / OPERATIONS ACCEPTANCE: BLOCKED (${errors.length})`);
      errors.forEach((error) => console.error(`- ${error}`));
      return 1;
    }
    console.log(`BACKUP / OPERATIONS ACCEPTANCE: PASS ${evidence.deployment_id}`);
    return 0;
  } catch (error) {
    console.error(`BACKUP / OPERATIONS ACCEPTANCE: FAIL\n- ${error.message}`);
    return 2;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main();
