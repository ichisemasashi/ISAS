#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import process from "node:process";
import { pathToFileURL } from "node:url";

const REQUIRED_GATES = [
  "unit_contract",
  "postgres_rls",
  "e2e_pwa",
  "accessibility",
  "security",
  "supply_chain",
  "performance_slo",
  "device_encryption",
  "staging_acceptance",
  "data_migration",
  "user_acceptance",
  "operational_acceptance",
  "business_cutover_acceptance",
];

const DIGEST = /^sha256:[0-9a-f]{64}$/;
const COMMIT = /^[0-9a-f]{40}$/;
const VERSION = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/;
const EVIDENCE_URI = /^(?:artifact|https|s3):\/\/.+/;
const PRODUCTION_HOSTS = new Set(["macos", "linux", "freebsd"]);

function nonPlaceholder(value) {
  return typeof value === "string" && value.trim() !== "" && !/replace-me|example/i.test(value);
}

function validDate(value) {
  return typeof value === "string" && Number.isFinite(Date.parse(value));
}

function evidenceUri(value) {
  return nonPlaceholder(value) && EVIDENCE_URI.test(value);
}

function recentDate(value, now, days) {
  if (!validDate(value)) return false;
  const age = now.getTime() - Date.parse(value);
  return age >= 0 && age <= days * 24 * 60 * 60 * 1000;
}

export function validateReleaseManifest(manifest, now = new Date()) {
  const errors = [];
  const add = (message) => errors.push(message);
  if (!manifest || typeof manifest !== "object" || Array.isArray(manifest)) return ["manifest must be an object"];

  if (manifest.schema_version !== 2) add("schema_version must be 2");

  const release = manifest.release ?? {};
  if (!VERSION.test(release.version ?? "")) add("release.version must be a semantic version without a v prefix");
  if (!COMMIT.test(release.source_commit ?? "") || /^0+$/.test(release.source_commit ?? "")) add("release.source_commit must be a non-zero 40-character lowercase hex commit");
  if (!validDate(release.created_at)) add("release.created_at must be an ISO date");
  if (release.status !== "READY") add("release.status must be READY");

  const deployment = manifest.deployment ?? {};
  for (const key of [
    "deployment_id", "os_version", "architecture", "service_manager", "isolation",
    "filesystem", "storage_encryption", "provider", "region_or_site", "jurisdiction",
    "shard_manifest_version",
  ]) {
    if (!nonPlaceholder(deployment[key])) add(`deployment.${key} is required and must not be a placeholder`);
  }
  if (!PRODUCTION_HOSTS.has(deployment.host_os)) add("deployment.host_os must be macos, linux, or freebsd");
  if (!Array.isArray(deployment.failure_domains) || deployment.failure_domains.length < 2 || deployment.failure_domains.some((value) => !nonPlaceholder(value))) {
    add("deployment.failure_domains must contain at least two named failure domains");
  }
  if (!DIGEST.test(deployment.shard_manifest_digest ?? "")) add("deployment.shard_manifest_digest must be sha256:<64 lowercase hex>");

  const operations = manifest.operations ?? {};
  if (!DIGEST.test(operations.ledger_digest ?? "")) add("operations.ledger_digest must be sha256:<64 lowercase hex>");
  if (!evidenceUri(operations.ledger_evidence)) add("operations.ledger_evidence must be an evidence URI");
  if (operations.deployment_id !== deployment.deployment_id) add("operations.deployment_id must match deployment.deployment_id");

  if (!Array.isArray(manifest.artifacts) || manifest.artifacts.length === 0) {
    add("artifacts must contain at least one promoted artifact");
  } else {
    const names = new Set();
    manifest.artifacts.forEach((artifact, index) => {
      const prefix = `artifacts[${index}]`;
      if (!nonPlaceholder(artifact?.name)) add(`${prefix}.name is required`);
      else if (names.has(artifact.name)) add(`${prefix}.name must be unique`);
      else names.add(artifact.name);
      if (!DIGEST.test(artifact?.digest ?? "")) add(`${prefix}.digest must be sha256:<64 lowercase hex>`);
      if (artifact?.signature_verified !== true) add(`${prefix}.signature_verified must be true`);
      if (artifact?.provenance_verified !== true) add(`${prefix}.provenance_verified must be true`);
      if (!evidenceUri(artifact?.sbom)) add(`${prefix}.sbom must be an evidence URI`);
    });
  }

  const gates = manifest.gates ?? {};
  for (const gate of REQUIRED_GATES) {
    if (gates[gate]?.status !== "pass") add(`gates.${gate}.status must be pass`);
    if (!evidenceUri(gates[gate]?.evidence)) add(`gates.${gate}.evidence must be an evidence URI`);
    if (gates[gate]?.source_commit !== release.source_commit) add(`gates.${gate}.source_commit must match release.source_commit`);
    if (!recentDate(gates[gate]?.collected_at, now, 31)) add(`gates.${gate}.collected_at must be within 31 days and not in the future`);
  }

  const quality = manifest.quality ?? {};
  for (const key of ["no_data_count", "unresolved_high", "unresolved_medium", "active_sev1", "active_sev2"]) {
    if (quality[key] !== 0) add(`quality.${key} must be 0`);
  }
  if (typeof quality.error_budget_remaining_percent !== "number" || quality.error_budget_remaining_percent < 25 || quality.error_budget_remaining_percent > 100) {
    add("quality.error_budget_remaining_percent must be between 25 and 100");
  }

  const dr = manifest.dr ?? {};
  if (dr.status !== "pass") add("dr.status must be pass");
  if (!validDate(dr.tested_at)) add("dr.tested_at must be an ISO date");
  else {
    const ageMs = now.getTime() - Date.parse(dr.tested_at);
    if (ageMs < 0 || ageMs > 93 * 24 * 60 * 60 * 1000) add("dr.tested_at must be within the last 93 days and not in the future");
  }
  if (typeof dr.rpo_minutes !== "number" || dr.rpo_minutes < 0 || dr.rpo_minutes > 15) add("dr.rpo_minutes must be between 0 and 15");
  if (typeof dr.rto_minutes !== "number" || dr.rto_minutes < 0 || dr.rto_minutes > 240) add("dr.rto_minutes must be between 0 and 240");
  if (!nonPlaceholder(dr.recovery_set_id)) add("dr.recovery_set_id is required and must not be a placeholder");
  if (!evidenceUri(dr.evidence)) add("dr.evidence must be an evidence URI");

  if (!Array.isArray(manifest.approvals) || manifest.approvals.length < 2) add("approvals must contain at least two approvals");
  else {
    const approvers = new Set();
    manifest.approvals.forEach((approval, index) => {
      const prefix = `approvals[${index}]`;
      if (!nonPlaceholder(approval?.actor)) add(`${prefix}.actor is required`);
      else approvers.add(approval.actor);
      if (!nonPlaceholder(approval?.role)) add(`${prefix}.role is required`);
      if (!validDate(approval?.approved_at)) add(`${prefix}.approved_at must be an ISO date`);
      else if (Date.parse(approval.approved_at) < Date.parse(release.created_at) || Date.parse(approval.approved_at) > now.getTime()) add(`${prefix}.approved_at must be after manifest creation and not in the future`);
      if (!evidenceUri(approval?.evidence)) add(`${prefix}.evidence must be an evidence URI`);
    });
    if (approvers.size < 2) add("approvals must contain at least two distinct actors");
    const roles = new Set(manifest.approvals.map(({ role }) => role));
    if (!roles.has("release_manager") || !roles.has("independent_verifier")) add("approvals must include release_manager and independent_verifier roles");
  }

  return errors;
}

export async function main(argv = process.argv.slice(2)) {
  if (argv.length !== 1) {
    console.error("usage: node ops/check-release-readiness.mjs <release-manifest.json>");
    return 2;
  }
  let manifest;
  try {
    manifest = JSON.parse(await readFile(argv[0], "utf8"));
  } catch (error) {
    console.error(`release readiness: FAIL\n- cannot read manifest: ${error.message}`);
    return 2;
  }
  const errors = validateReleaseManifest(manifest);
  if (errors.length > 0) {
    console.error(`release readiness: BLOCKED (${errors.length})`);
    errors.forEach((error) => console.error(`- ${error}`));
    return 1;
  }
  console.log(`release readiness: READY ${manifest.release.version} ${manifest.deployment.deployment_id}`);
  return 0;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main();
