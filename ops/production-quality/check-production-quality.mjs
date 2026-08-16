#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import process from "node:process";
import { pathToFileURL } from "node:url";

export const SCREEN_SLOS = Object.freeze({
  login: 2000,
  today: 1500,
  fields_10000: 1500,
  map_initial_1000: 2000,
  map_interaction: 200,
  gantt_500: 3000,
  journal_photo_save: 1000,
  pesticide_record: 1000,
  inventory: 1000,
});

export const FUNCTIONAL_CASES = Object.freeze([
  "tenant_cross_read", "tenant_cross_write", "authorization_revocation", "conflict_queue",
  "idempotent_retry", "pwa_update_pending", "pwa_rollback_pending",
]);

export const MANUAL_WCAG_CASES = Object.freeze([
  "keyboard_all_screens", "focus_visible_and_order", "screen_reader_ios", "screen_reader_android",
  "zoom_200_percent", "reflow_320_css_px", "contrast_and_non_color", "labels_errors_status",
  "orientation", "target_size_and_spacing", "reduced_motion", "language_and_reading_order",
]);

const COMMIT = /^[0-9a-f]{40}$/;
const URI = /^(?:artifact|https|s3):\/\/.+/;

function artifact(value) { return typeof value === "string" && URI.test(value) && !/replace-me|example|todo/i.test(value); }
function date(value) { return typeof value === "string" && Number.isFinite(Date.parse(value)); }

export function validateProductionQuality(value, now = new Date()) {
  const errors = [];
  const add = (message) => errors.push(message);
  if (!value || typeof value !== "object" || Array.isArray(value)) return ["evidence must be an object"];
  if (value.schema_version !== 1) add("schema_version must be 1");
  if (value.status !== "PASS") add("status must be PASS");
  if (!COMMIT.test(value.source_commit ?? "") || /^0+$/.test(value.source_commit ?? "")) add("source_commit must be a non-zero 40-character lowercase commit");
  if (!date(value.measured_at)) add("measured_at must be an ISO date");
  else {
    const age = now.getTime() - Date.parse(value.measured_at);
    if (age < 0 || age > 31 * 86400000) add("measured_at must be within 31 days and not in the future");
  }

  const environment = value.environment ?? {};
  try {
    const url = new URL(environment.base_origin);
    if (url.protocol !== "https:" || url.origin !== environment.base_origin) add("environment.base_origin must be an exact HTTPS origin");
  } catch { add("environment.base_origin must be an exact HTTPS origin"); }
  if (!/^TLSv1\.[23]$/.test(environment.tls?.version ?? "") || environment.tls?.hostname_verified !== true) add("environment.tls must verify hostname with TLS 1.2 or 1.3");
  if (environment.hsts !== true) add("environment.hsts must be true");
  if (environment.bff_live !== "pass" || environment.bff_ready !== "pass") add("real BFF liveness and readiness must pass");
  if (environment.p0_database?.postgres_major !== 16 || environment.p0_database?.postgis !== true || environment.p0_database?.tls !== true) add("P0 must use PostgreSQL 16 + PostGIS over TLS");
  if (environment.p2_database?.postgres_major !== 16 || environment.p2_database?.postgis !== true || environment.p2_database?.tls !== true) add("P2 must use PostgreSQL 16 + PostGIS over TLS");
  if (!environment.p0_database?.endpoint_id || !environment.p2_database?.endpoint_id || environment.p0_database.endpoint_id === environment.p2_database.endpoint_id) add("P0 and P2 endpoint_id must be present and distinct");
  if (environment.network?.kind !== "actual" || !artifact(environment.network?.evidence)) add("actual network evidence is required");

  const s7 = value.s7 ?? {};
  if (s7.status !== "pass" || s7.requests < 1000 || s7.failures !== 0 || s7.p95_ms > 500 || s7.duplicate_changes !== 0 || !artifact(s7.evidence)) {
    add("S7 must pass at least 1000 real TLS requests with p95 <= 500ms, zero failures and zero duplicate changes");
  }
  const saturation = value.pool_saturation ?? {};
  if (saturation.status !== "pass" || saturation.p2_concurrency < 2 || saturation.p0_samples < 1000
    || saturation.p0_within_500ms / saturation.p0_samples < .999 || !artifact(saturation.evidence)) {
    add("P2 saturation must retain at least 99.9% of 1000+ P0 samples within 500ms");
  }

  const screens = value.screens ?? {};
  for (const [name, budget] of Object.entries(SCREEN_SLOS)) {
    const result = screens[name];
    if (result?.status !== "pass" || result.iterations < 30 || typeof result.p95_ms !== "number" || result.p95_ms > budget
      || typeof result.error_rate !== "number" || result.error_rate > .001 || !artifact(result.evidence)) {
      add(`screens.${name} must pass 30+ iterations, p95 <= ${budget}ms and error_rate <= 0.001 with evidence`);
    }
  }

  for (const name of FUNCTIONAL_CASES) {
    const result = value.functional?.[name];
    if (result?.status !== "pass" || !artifact(result.evidence)) add(`functional.${name} must pass with evidence`);
  }
  for (const name of MANUAL_WCAG_CASES) {
    const result = value.manual_wcag?.[name];
    if (result?.status !== "pass" || !artifact(result.evidence)) add(`manual_wcag.${name} must pass with evidence`);
  }

  const penetration = value.penetration ?? {};
  if (penetration.status !== "pass" || penetration.independent_tester !== true || !artifact(penetration.report)
    || penetration.open_critical !== 0 || penetration.open_high !== 0 || penetration.open_medium !== 0
    || penetration.retest_complete !== true) add("penetration must have an independent report, completed retest and zero open Critical/High/Medium findings");
  const adversarial = value.adversarial_review ?? {};
  if (adversarial.status !== "pass" || adversarial.open_high !== 0 || adversarial.open_medium !== 0 || !artifact(adversarial.report)) {
    add("adversarial_review must pass with zero open High/Medium findings and a report");
  }

  const actors = new Set();
  if (!Array.isArray(value.approvals) || value.approvals.length < 3) add("approvals must contain release, security and accessibility approvals");
  else value.approvals.forEach((approval, index) => {
    if (typeof approval?.actor !== "string" || !approval.actor) add(`approvals[${index}].actor is required`);
    else actors.add(approval.actor);
    if (!date(approval?.approved_at)) add(`approvals[${index}].approved_at must be an ISO date`);
    if (!artifact(approval?.evidence)) add(`approvals[${index}].evidence is required`);
  });
  if (actors.size < 3) add("approvals must contain three distinct actors");
  return errors;
}

export async function main(argv = process.argv.slice(2)) {
  if (argv.length !== 1) { console.error("usage: node check-production-quality.mjs <evidence.json>"); return 2; }
  try {
    const evidence = JSON.parse(await readFile(argv[0], "utf8"));
    const errors = validateProductionQuality(evidence);
    if (errors.length) {
      console.error(`production quality: BLOCKED (${errors.length})`);
      errors.forEach((error) => console.error(`- ${error}`));
      return 1;
    }
    console.log(`production quality: PASS ${evidence.deployment_id} ${evidence.source_commit}`);
    return 0;
  } catch (error) {
    console.error(`production quality: FAIL\n- ${error.message}`);
    return 2;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main();
