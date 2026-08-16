#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import process from "node:process";
import { pathToFileURL } from "node:url";

import { validateBuildPromotion } from "./check-build-promotion.mjs";
import { validateReleaseManifest } from "./check-release-readiness.mjs";

const URI = /^(?:artifact|https|s3):\/\/.+/;
const REQUIRED_OBSERVATIONS = { "5": [1800, 1000], "25": [7200, 1000], "100": [1800, 1000] };
const date = (value) => typeof value === "string" && Number.isFinite(Date.parse(value));
const evidence = (value) => typeof value === "string" && URI.test(value) && !/replace-me|example|todo|unset|未設定/i.test(value);

export function validateProductionRelease({ release, build, delivery, bake, releaseBytes, now = new Date() }) {
  const errors = [...validateReleaseManifest(release, now), ...validateBuildPromotion(build, release)];
  const add = (message) => errors.push(message);
  if (delivery?.stage !== "finalized") add("delivery.stage must be finalized");
  if (delivery?.source_commit !== release?.release?.source_commit) add("delivery source_commit must match release");
  if (delivery?.artifact_set_digest !== build?.artifact_set_digest) add("delivery artifact_set_digest must match build");

  const history = Array.isArray(delivery?.history) ? delivery.history : [];
  let previousIndex = -1;
  for (const stage of ["prepared", "5", "25", "100", "finalized"]) {
    const index = history.findIndex((entry, candidateIndex) => candidateIndex > previousIndex && entry?.stage === stage && date(entry?.entered_at));
    if (index < 0) add(`delivery history must contain ordered ${stage} transition`); else previousIndex = index;
  }
  const observations = Array.isArray(delivery?.observations) ? delivery.observations : [];
  for (const [stage, [minimumSeconds, minimumTransactions]] of Object.entries(REQUIRED_OBSERVATIONS)) {
    const observation = observations.find((entry) => entry?.stage === stage && entry?.status === "PASS");
    if (!observation || observation.duration_seconds < minimumSeconds || observation.eligible_transactions < minimumTransactions || !date(observation.started_at) || !date(observation.completed_at)) {
      add(`delivery observation ${stage}% must meet duration and transaction gates`);
    }
  }

  if (bake?.schema_version !== 1 || bake?.status !== "PASS" || bake?.environment !== "production") add("24-hour production evidence must be PASS for production");
  if (bake?.source_commit !== release?.release?.source_commit || bake?.artifact_set_digest !== build?.artifact_set_digest) add("24-hour evidence must match source commit and artifact set");
  const expectedManifestDigest = releaseBytes ? `sha256:${createHash("sha256").update(releaseBytes).digest("hex")}` : null;
  if (expectedManifestDigest && bake?.release_manifest_digest !== expectedManifestDigest) add("24-hour evidence release_manifest_digest does not match");
  if (!date(bake?.started_at) || !date(bake?.completed_at) || Date.parse(bake?.completed_at) - Date.parse(bake?.started_at) < 86400000 || Date.parse(bake?.completed_at) > now.getTime()) add("enhanced monitoring must cover at least 24 hours and end in the past");
  for (const key of ["alarm_breaches", "no_data_count", "active_sev1", "active_sev2", "unresolved_high", "unresolved_medium"]) if (bake?.[key] !== 0) add(`24-hour evidence ${key} must be 0`);
  if (typeof bake?.error_budget_remaining_percent !== "number" || bake.error_budget_remaining_percent < 25 || bake.error_budget_remaining_percent > 100) add("24-hour evidence error budget must be between 25 and 100");
  if (!evidence(bake?.evidence)) add("24-hour monitoring evidence URI is required");

  const approvals = Array.isArray(bake?.approvals) ? bake.approvals : [];
  const actors = new Set();
  const roles = new Set();
  approvals.forEach((approval, index) => {
    if (!approval?.actor) add(`24-hour approvals[${index}].actor is required`); else actors.add(approval.actor);
    roles.add(approval?.role);
    if (!date(approval?.approved_at) || Date.parse(approval.approved_at) < Date.parse(bake?.completed_at) || Date.parse(approval.approved_at) > now.getTime()) add(`24-hour approvals[${index}].approved_at must follow monitoring completion`);
    if (!evidence(approval?.evidence)) add(`24-hour approvals[${index}].evidence is required`);
  });
  if (actors.size < 2 || !roles.has("release_manager") || !roles.has("independent_verifier")) add("24-hour evidence requires two distinct release_manager and independent_verifier approvals");
  if (bake?.tag?.name !== `v${release?.release?.version}`) add("production tag must equal v<release.version>");
  if (bake?.tag?.target_commit !== release?.release?.source_commit) add("production tag target must match release source_commit");
  return errors;
}

export async function main(argv = process.argv.slice(2)) {
  if (argv.length !== 4) {
    console.error("usage: node ops/check-production-release.mjs RELEASE BUILD DELIVERY_STATE BAKE_EVIDENCE");
    return 2;
  }
  try {
    const releaseBytes = await readFile(argv[0]);
    const [release, build, delivery, bake] = await Promise.all([
      JSON.parse(releaseBytes), ...argv.slice(1).map(async (path) => JSON.parse(await readFile(path, "utf8"))),
    ]);
    const errors = validateProductionRelease({ release, build, delivery, bake, releaseBytes });
    if (errors.length) {
      console.error(`production release: BLOCKED (${errors.length})`);
      errors.forEach((error) => console.error(`- ${error}`));
      return 1;
    }
    console.log(`production release: AUTHORIZED ${bake.tag.name} -> ${bake.tag.target_commit}`);
    return 0;
  } catch (error) {
    console.error(`production release: FAIL\n- ${error.message}`);
    return 2;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main();
