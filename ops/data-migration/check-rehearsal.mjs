#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import process from "node:process";
import { pathToFileURL } from "node:url";

const COMMIT = /^[0-9a-f]{40}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const URI = /^(?:artifact|https|s3):\/\/.+/;
const ORDER = ["fields", "journals", "pesticide_history"];
const EXPORTS = ["fields", "journals", "pesticide-records"];

function artifact(value) { return typeof value === "string" && URI.test(value) && !/replace-me|example|todo/i.test(value); }
function date(value) { return typeof value === "string" && Number.isFinite(Date.parse(value)); }
function count(value) { return Number.isInteger(value) && value >= 0; }

export function validateRehearsal(value, now = new Date()) {
  const errors = [];
  const add = (message) => errors.push(message);
  if (!value || typeof value !== "object" || Array.isArray(value)) return ["evidence must be an object"];
  if (value.schema_version !== 1) add("schema_version must be 1");
  if (value.status !== "PASS") add("status must be PASS after independent review");
  if (!["real_anonymized", "production_export"].includes(value.evidence_class)) add("evidence_class must identify real-derived data");
  if (!date(value.measured_at) || Date.parse(value.measured_at) > now.getTime() || now.getTime() - Date.parse(value.measured_at) > 31 * 86400000) add("measured_at must be within 31 days and not in the future");
  if (!COMMIT.test(value.source_commit ?? "") || /^0+$/.test(value.source_commit ?? "")) add("source_commit must be a non-zero 40-character lowercase commit");
  if (!value.round_id || /replace-me|example|todo/i.test(value.round_id)) add("round_id is required");
  if (value.environment?.kind !== "staging") add("an isolated staging environment is required");
  try {
    const origin = new URL(value.environment?.base_origin);
    if (origin.protocol !== "https:" || origin.origin !== value.environment.base_origin) add("environment.base_origin must be an exact HTTPS origin");
  } catch { add("environment.base_origin must be an exact HTTPS origin"); }

  if (!Array.isArray(value.imports) || value.imports.length !== ORDER.length) add("imports must contain exactly three datasets");
  else value.imports.forEach((item, index) => {
    const name = ORDER[index];
    if (item?.dataset !== name) add(`imports[${index}] must be ${name}`);
    if (item?.status !== "pass" || item?.idempotent_replay !== "pass") add(`${name} import and idempotent replay must pass`);
    if (!SHA256.test(item?.source_sha256 ?? "") || /^0+$/.test(item?.source_sha256 ?? "")) add(`${name} source_sha256 is required`);
    if (!count(item?.source_rows) || item.source_rows < 1) add(`${name} must contain at least one real source row`);
    const inspected = item?.validated ?? {};
    if (![inspected.rows, inspected.valid, inspected.duplicates, inspected.errors].every(count)) add(`${name} validated counts must be non-negative integers`);
    else if (inspected.rows !== inspected.valid + inspected.duplicates + inspected.errors || inspected.rows !== item.source_rows) add(`${name} validated counts do not reconcile`);
    if (inspected.errors !== 0) add(`${name} must have zero validation errors before acceptance`);
    if (!count(item?.committed) || item.committed !== inspected.valid) add(`${name} committed count must equal validated count`);
    if (item?.duplicates_at_commit !== 0) add(`${name} must have zero concurrent duplicates at commit`);
  });

  for (const name of EXPORTS) {
    if (!count(value.exports?.[name])) add(`exports.${name} must be an exact count`);
    if (!count(value.rls_scope?.restricted_exports?.[name])) add(`rls_scope.restricted_exports.${name} must be an exact count`);
    else if (count(value.exports?.[name]) && value.rls_scope.restricted_exports[name] > value.exports[name]) add(`restricted ${name} count cannot exceed full-scope count`);
  }
  if (value.rls_scope?.status !== "pass") add("RLS scope reconciliation must pass");
  else if (EXPORTS.every((name) => value.rls_scope.restricted_exports?.[name] === value.exports?.[name])) add("RLS proof must demonstrate a narrower result in at least one dataset");

  const requiredRoles = new Set(["data_owner", "independent_verifier"]);
  const actors = new Set();
  if (!Array.isArray(value.approvals)) add("approvals are required");
  else value.approvals.forEach((approval, index) => {
    requiredRoles.delete(approval?.role);
    if (!approval?.actor) add(`approvals[${index}].actor is required`); else actors.add(approval.actor);
    if (!date(approval?.approved_at)) add(`approvals[${index}].approved_at must be an ISO date`);
    if (!artifact(approval?.evidence)) add(`approvals[${index}].evidence is required`);
  });
  if (requiredRoles.size) add(`missing approval roles: ${[...requiredRoles].join(", ")}`);
  if (actors.size < 2) add("approvals require two distinct actors");
  return errors;
}

export async function main(argv = process.argv.slice(2)) {
  if (argv.length !== 1) { console.error("usage: node check-rehearsal.mjs <evidence.json>"); return 2; }
  try {
    const evidence = JSON.parse(await readFile(argv[0], "utf8"));
    const errors = validateRehearsal(evidence);
    if (errors.length) {
      console.error(`data migration rehearsal: BLOCKED (${errors.length})`);
      errors.forEach((error) => console.error(`- ${error}`));
      return 1;
    }
    console.log(`data migration rehearsal: PASS ${evidence.round_id}`);
    return 0;
  } catch (error) {
    console.error(`data migration rehearsal: FAIL\n- ${error.message}`);
    return 2;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main();
