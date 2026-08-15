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
  "user_acceptance",
];

const DIGEST = /^sha256:[0-9a-f]{64}$/;
const COMMIT = /^[0-9a-f]{40}$/;

function nonPlaceholder(value) {
  return typeof value === "string" && value.trim() !== "" && !/replace-me|example/i.test(value);
}

function validDate(value) {
  return typeof value === "string" && Number.isFinite(Date.parse(value));
}

export function validateReleaseManifest(manifest, now = new Date()) {
  const errors = [];
  const add = (message) => errors.push(message);
  if (!manifest || typeof manifest !== "object" || Array.isArray(manifest)) return ["manifest must be an object"];

  if (manifest.schema_version !== 1) add("schema_version must be 1");

  const release = manifest.release ?? {};
  if (!nonPlaceholder(release.version)) add("release.version is required and must not be a placeholder");
  if (!COMMIT.test(release.source_commit ?? "") || /^0+$/.test(release.source_commit ?? "")) add("release.source_commit must be a non-zero 40-character lowercase hex commit");
  if (!validDate(release.created_at)) add("release.created_at must be an ISO date");
  if (release.status !== "READY") add("release.status must be READY");

  const deployment = manifest.deployment ?? {};
  for (const key of ["deployment_id", "jurisdiction", "shard_manifest_version"]) {
    if (!nonPlaceholder(deployment[key])) add(`deployment.${key} is required and must not be a placeholder`);
  }
  if (!DIGEST.test(deployment.shard_manifest_digest ?? "")) add("deployment.shard_manifest_digest must be sha256:<64 lowercase hex>");

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
      if (!nonPlaceholder(artifact?.sbom)) add(`${prefix}.sbom is required`);
    });
  }

  const gates = manifest.gates ?? {};
  for (const gate of REQUIRED_GATES) {
    if (gates[gate]?.status !== "pass") add(`gates.${gate}.status must be pass`);
    if (!nonPlaceholder(gates[gate]?.evidence)) add(`gates.${gate}.evidence is required and must not be a placeholder`);
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
  if (!nonPlaceholder(dr.evidence)) add("dr.evidence is required and must not be a placeholder");

  if (!Array.isArray(manifest.approvals) || manifest.approvals.length < 2) add("approvals must contain at least two approvals");
  else {
    const approvers = new Set();
    manifest.approvals.forEach((approval, index) => {
      const prefix = `approvals[${index}]`;
      if (!nonPlaceholder(approval?.actor)) add(`${prefix}.actor is required`);
      else approvers.add(approval.actor);
      if (!nonPlaceholder(approval?.role)) add(`${prefix}.role is required`);
      if (!validDate(approval?.approved_at)) add(`${prefix}.approved_at must be an ISO date`);
    });
    if (approvers.size < 2) add("approvals must contain at least two distinct actors");
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
