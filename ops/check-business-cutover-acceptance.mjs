#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import process from "node:process";
import { pathToFileURL } from "node:url";

import { validateDeviceAcceptance } from "./check-device-acceptance.mjs";
import { validateRehearsal } from "./data-migration/check-rehearsal.mjs";
import { validateProductionQuality } from "./production-quality/check-production-quality.mjs";
import { validateOperationalAcceptance } from "./recovery/check-operational-acceptance.mjs";

const COMMIT = /^[0-9a-f]{40}$/;
const HOSTS = new Set(["macos", "linux", "freebsd"]);
const DOCUMENTS = ["migration", "user_acceptance", "device", "quality", "operations", "staging_bake"];
const sha256 = (bytes) => `sha256:${createHash("sha256").update(bytes).digest("hex")}`;
const date = (value) => typeof value === "string" && Number.isFinite(Date.parse(value));

export function validateUserAcceptance(value) {
  const errors = [];
  const add = (message) => errors.push(message);
  if (value?.schema_version !== 1 || value?.status !== "PASS" || value?.evidence_class !== "real_participants") add("user acceptance must be PASS with real_participants evidence");
  for (const group of ["workers", "senior_workers", "technical_interns"]) {
    if (!Number.isInteger(value?.participant_groups?.[group]) || value.participant_groups[group] < 2) add(`participant_groups.${group} must contain at least two real participants`);
  }
  if (typeof value?.metrics?.task_success_percent !== "number" || value.metrics.task_success_percent < 90) add("task success must be at least 90 percent");
  if (typeof value?.metrics?.journal_median_seconds !== "number" || value.metrics.journal_median_seconds > 30) add("journal median must be 30 seconds or less");
  if (typeof value?.metrics?.pesticide_median_seconds !== "number" || value.metrics.pesticide_median_seconds > 60) add("pesticide median must be 60 seconds or less");
  if (typeof value?.metrics?.sus !== "number" || value.metrics.sus < 75) add("SUS must be at least 75");
  if (value?.metrics?.offline_understanding_percent !== 100) add("all participants must understand offline and unsynchronized state");
  if (!Array.isArray(value?.sessions) || value.sessions.length < 6 || value.sessions.some((session) => session?.real_participant !== true || !session?.evidence_uri)) add("six or more real participant session evidence records are required");
  return errors;
}

export function validateBusinessCutoverAcceptance({ index, release, documents, documentBytes, now = new Date() }) {
  const errors = [];
  const add = (message) => errors.push(message);
  const sourceCommit = release?.release?.source_commit;
  const deployment = release?.deployment ?? {};
  if (index?.schema_version !== 1 || index?.status !== "PASS") add("cutover acceptance index must be schema 1 and PASS");
  if (!COMMIT.test(sourceCommit ?? "") || index?.source_commit !== sourceCommit) add("cutover acceptance source_commit must match release source_commit");
  if (!HOSTS.has(deployment.host_os) || index?.host_os !== deployment.host_os) add("cutover acceptance host_os must match the selected production host");
  if (index?.deployment_id !== deployment.deployment_id) add("cutover acceptance deployment_id must match release deployment");
  if (index?.environment !== "isolated-staging") add("cutover acceptance must run in isolated-staging");

  for (const name of DOCUMENTS) {
    const expected = index?.documents?.[name]?.digest;
    if (!documentBytes?.[name] || sha256(documentBytes[name]) !== expected) add(`documents.${name}.digest must match the supplied evidence content`);
    if (documents?.[name]?.source_commit !== sourceCommit) add(`${name}.source_commit must match release source_commit`);
    if (documents?.[name]?.deployment_id !== deployment.deployment_id) add(`${name}.deployment_id must match release deployment`);
    if (documents?.[name]?.host_os !== deployment.host_os) add(`${name}.host_os must match the selected host`);
  }

  errors.push(...validateRehearsal(documents.migration, now).map((error) => `migration: ${error}`));
  errors.push(...validateUserAcceptance(documents.user_acceptance).map((error) => `user_acceptance: ${error}`));
  errors.push(...validateDeviceAcceptance(documents.device, now).map((error) => `device: ${error}`));
  errors.push(...validateProductionQuality(documents.quality, now).map((error) => `quality: ${error}`));
  errors.push(...validateOperationalAcceptance(documents.operations, now).map((error) => `operations: ${error}`));

  const bake = documents.staging_bake ?? {};
  if (bake.status !== "PASS" || bake.environment !== "isolated-staging" || !date(bake.started_at) || !date(bake.completed_at)
    || Date.parse(bake.completed_at) - Date.parse(bake.started_at) < 86400000 || Date.parse(bake.completed_at) > now.getTime()) {
    add("staging_bake must provide a completed 24-hour isolated-staging observation");
  }
  for (const key of ["alarm_breaches", "no_data_count", "active_sev1", "active_sev2", "unresolved_high", "unresolved_medium"]) {
    if (bake[key] !== 0) add(`staging_bake.${key} must be 0`);
  }
  return errors;
}

export async function main(argv = process.argv.slice(2)) {
  if (argv.length !== 8) {
    console.error("usage: node ops/check-business-cutover-acceptance.mjs INDEX RELEASE MIGRATION USER_ACCEPTANCE DEVICE QUALITY OPERATIONS STAGING_BAKE");
    return 2;
  }
  try {
    const bytes = await Promise.all(argv.map((path) => readFile(path)));
    const [index, release, migration, userAcceptance, device, quality, operations, stagingBake] = bytes.map((value) => JSON.parse(value));
    const documents = { migration, user_acceptance: userAcceptance, device, quality, operations, staging_bake: stagingBake };
    const documentBytes = Object.fromEntries(DOCUMENTS.map((name, index) => [name, bytes[index + 2]]));
    const errors = validateBusinessCutoverAcceptance({ index, release, documents, documentBytes });
    if (errors.length) {
      console.error(`business cutover acceptance: BLOCKED (${errors.length})`);
      errors.forEach((error) => console.error(`- ${error}`));
      return 1;
    }
    console.log(`business cutover acceptance: PASS ${index.deployment_id} ${index.source_commit}`);
    return 0;
  } catch (error) {
    console.error(`business cutover acceptance: FAIL\n- ${error.message}`);
    return 2;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main();
