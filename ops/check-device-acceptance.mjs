#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import process from "node:process";
import { pathToFileURL } from "node:url";

export const REQUIRED_PROFILES = [
  "ios-min-browser", "ios-min-standalone", "ios-current-browser", "ios-current-standalone",
  "android-min-browser", "android-min-standalone", "android-current-browser", "android-current-standalone",
];

export const REQUIRED_TESTS = [
  "offline_restart", "vault_non_extractable", "key_separation", "key_rotation_interrupt",
  "quota_pressure", "os_update", "browser_termination", "device_loss_revocation",
  "outbox_recovery", "production_logout", "shared_device",
];

function evidence(value) {
  return typeof value === "string" && /^(artifact|file|https):\/\//.test(value) && !/replace-me|example|todo/i.test(value);
}

function date(value) { return typeof value === "string" && Number.isFinite(Date.parse(value)); }

export function validateDeviceAcceptance(value, now = new Date()) {
  const errors = [];
  const add = (message) => errors.push(message);
  if (!value || typeof value !== "object" || Array.isArray(value)) return ["evidence must be an object"];
  if (value.schema_version !== 1) add("schema_version must be 1");
  if (value.status !== "PASS") add("status must be PASS");
  if (!Array.isArray(value.profiles)) return [...errors, "profiles must be an array"];

  const byId = new Map();
  value.profiles.forEach((profile, index) => {
    const prefix = `profiles[${index}]`;
    if (!profile || typeof profile !== "object") return add(`${prefix} must be an object`);
    if (typeof profile.id !== "string" || byId.has(profile.id)) add(`${prefix}.id must be present and unique`);
    else byId.set(profile.id, profile);
    if (profile.physical_device !== true) add(`${prefix}.physical_device must be true; simulator/emulator evidence is diagnostic only`);
    if (!/^(iOS|Android)$/.test(profile.platform ?? "")) add(`${prefix}.platform must be iOS or Android`);
    if (typeof profile.model !== "string" || profile.model.length < 2) add(`${prefix}.model is required`);
    if (typeof profile.os_build !== "string" || profile.os_build.length < 2) add(`${prefix}.os_build is required`);
    if (typeof profile.browser_version !== "string" || profile.browser_version.length < 2) add(`${prefix}.browser_version is required`);
    if (!date(profile.measured_at)) add(`${prefix}.measured_at must be an ISO date`);
    else {
      const age = now.getTime() - Date.parse(profile.measured_at);
      if (age < 0 || age > 93 * 24 * 60 * 60 * 1000) add(`${prefix}.measured_at must be within 93 days and not in the future`);
    }
    for (const testName of REQUIRED_TESTS) {
      const result = profile.tests?.[testName];
      if (result?.status !== "pass") add(`${prefix}.tests.${testName}.status must be pass`);
      if (!evidence(result?.evidence)) add(`${prefix}.tests.${testName}.evidence must be a non-placeholder artifact URI`);
    }
  });

  for (const id of REQUIRED_PROFILES) {
    const profile = byId.get(id);
    if (!profile) { add(`required profile ${id} is missing`); continue; }
    const expectedPlatform = id.startsWith("ios-") ? "iOS" : "Android";
    const expectedTier = id.includes("-min-") ? "minimum" : "current";
    const expectedMode = id.endsWith("-browser") ? "browser" : "standalone";
    if (profile.platform !== expectedPlatform) add(`${id}.platform must be ${expectedPlatform}`);
    if (profile.support_tier !== expectedTier) add(`${id}.support_tier must be ${expectedTier}`);
    if (profile.display_mode !== expectedMode) add(`${id}.display_mode must be ${expectedMode}`);
  }

  const approvalActors = new Set();
  if (!Array.isArray(value.approvals) || value.approvals.length < 2) add("approvals must contain device test lead and independent security verifier");
  else value.approvals.forEach((approval, index) => {
    if (typeof approval?.actor !== "string" || !approval.actor) add(`approvals[${index}].actor is required`);
    else approvalActors.add(approval.actor);
    if (!date(approval?.approved_at)) add(`approvals[${index}].approved_at must be an ISO date`);
    if (!evidence(approval?.evidence)) add(`approvals[${index}].evidence must be a non-placeholder artifact URI`);
  });
  if (approvalActors.size < 2) add("approvals must contain two distinct actors");
  return errors;
}

export async function main(argv = process.argv.slice(2)) {
  if (argv.length !== 1) {
    console.error("usage: node ops/check-device-acceptance.mjs <device-acceptance.json>");
    return 2;
  }
  try {
    const result = JSON.parse(await readFile(argv[0], "utf8"));
    const errors = validateDeviceAcceptance(result);
    if (errors.length) {
      console.error(`device acceptance: BLOCKED (${errors.length})`);
      errors.forEach((error) => console.error(`- ${error}`));
      return 1;
    }
    console.log(`device acceptance: PASS (${REQUIRED_PROFILES.length} profiles, ${REQUIRED_TESTS.length} tests each)`);
    return 0;
  } catch (error) {
    console.error(`device acceptance: FAIL\n- ${error.message}`);
    return 2;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main();
