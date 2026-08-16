#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import process from "node:process";
import { pathToFileURL } from "node:url";

const COMMIT = /^[0-9a-f]{40}$/;
const URI = /^(?:artifact|https|s3):\/\/.+/;
const COHORTS = ["worker", "older_worker", "technical_intern"];
const REQUIRED_GATES = ["対象者構成", "記録完全性", "主要タスク成功率", "日誌時間中央値", "農薬記録時間中央値", "農薬警告見落とし", "オフライン保存・未同期理解", "SUS平均", "未解決Severity 1"];

function artifact(value) { return typeof value === "string" && URI.test(value) && !/replace-me|example|todo/i.test(value); }
function date(value) { return typeof value === "string" && Number.isFinite(Date.parse(value)); }

export function validateRealUt(result, evidence, now = new Date()) {
  const errors = [];
  const add = (message) => errors.push(message);
  if (result?.status !== "PASS") add("UT analyzer result must be PASS");
  if (!Number.isInteger(result?.participant_count) || result.participant_count < 6 || result.participant_count > 9) add("participant_count must be 6 to 9");
  for (const cohort of COHORTS) if (!Number.isInteger(result?.cohort_counts?.[cohort]) || result.cohort_counts[cohort] < 2) add(`${cohort} requires at least two participants`);
  const gates = new Map(Array.isArray(result?.gates) ? result.gates.map((item) => [item.name, item]) : []);
  for (const name of REQUIRED_GATES) if (gates.get(name)?.passed !== true) add(`analyzer gate must pass: ${name}`);

  if (evidence?.schema_version !== 1) add("evidence schema_version must be 1");
  if (evidence?.status !== "PASS") add("evidence status must be PASS after independent review");
  if (evidence?.evidence_class !== "real_participant") add("evidence_class must be real_participant");
  if (evidence?.environment !== "staging_actual_device") add("UT must use staging on actual devices");
  if (!COMMIT.test(evidence?.source_commit ?? "") || /^0+$/.test(evidence?.source_commit ?? "")) add("source_commit must be a non-zero 40-character lowercase commit");
  if (!evidence?.round_id || /replace-me|example|todo/i.test(evidence.round_id)) add("round_id is required");
  if (!date(evidence?.completed_at) || Date.parse(evidence.completed_at) > now.getTime() || now.getTime() - Date.parse(evidence.completed_at) > 31 * 86400000) add("completed_at must be within 31 days and not in the future");
  if (evidence?.participant_count !== result?.participant_count) add("evidence participant_count must match analyzer result");
  for (const cohort of COHORTS) {
    if (evidence?.cohort_counts?.[cohort] !== result?.cohort_counts?.[cohort]) add(`evidence cohort count must match: ${cohort}`);
    if (!artifact(evidence?.recruitment_evidence?.[cohort])) add(`real recruitment evidence is required for ${cohort}`);
  }
  if (!artifact(evidence?.consent_register)) add("consent register evidence is required");
  if (!artifact(evidence?.observation_records)) add("timestamped observation evidence is required");
  if (!artifact(evidence?.device_matrix)) add("actual device matrix evidence is required");
  if (!artifact(evidence?.analysis_inputs_digest)) add("immutable digest evidence for anonymous analysis inputs is required");

  const roles = new Set(["ut_owner", "independent_verifier"]);
  const actors = new Set();
  if (!Array.isArray(evidence?.approvals)) add("approvals are required");
  else evidence.approvals.forEach((approval, index) => {
    roles.delete(approval?.role);
    if (!approval?.actor) add(`approvals[${index}].actor is required`); else actors.add(approval.actor);
    if (!date(approval?.approved_at)) add(`approvals[${index}].approved_at must be an ISO date`);
    if (!artifact(approval?.evidence)) add(`approvals[${index}].evidence is required`);
  });
  if (roles.size) add(`missing approval roles: ${[...roles].join(", ")}`);
  if (actors.size < 2) add("approvals require two distinct actors");
  return errors;
}

export async function main(argv = process.argv.slice(2)) {
  if (argv.length !== 2) { console.error("usage: node check-real-ut.mjs <analyzer-result.json> <real-evidence.json>"); return 2; }
  try {
    const [result, evidence] = await Promise.all(argv.map(async (path) => JSON.parse(await readFile(path, "utf8"))));
    const errors = validateRealUt(result, evidence);
    if (errors.length) {
      console.error(`real user UT: BLOCKED (${errors.length})`);
      errors.forEach((error) => console.error(`- ${error}`));
      return 1;
    }
    console.log(`real user UT: PASS ${evidence.round_id}`);
    return 0;
  } catch (error) {
    console.error(`real user UT: FAIL\n- ${error.message}`);
    return 2;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main();
