#!/usr/bin/env node
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";

const STATUSES = new Set(["designed", "static-implemented", "integration-validated", "production-authorized", "out-of-scope"]);
const CATEGORIES = new Set(["business", "platform", "operations"]);
const DIGEST = /^sha256:[0-9a-f]{64}$/;
const REQUIRED = ["field-gis", "emaff-import", "work-journal", "pesticide-inventory", "csv-migration", "advanced-planning", "inventory-traceability", "location-work-actuals", "tenant-analytics", "offline-sync", "i18n", "security-administration", "production-bff-runtime", "macos-native-profile", "linux-native-profile", "freebsd-native-profile", "native-artifact-supply", "docker-retirement", "external-read-api", "wagri-machinery-connector", "remote-sensing", "water-management", "drying-preparation", "diagnostic-support", "signed-release-security", "business-cutover", "backup-dr", "operations-guide"];
const sha256 = (bytes) => `sha256:${createHash("sha256").update(bytes).digest("hex")}`;
const validDate = (value) => typeof value === "string" && Number.isFinite(Date.parse(value));

export async function validateCatalog(value, { root = ".", now = new Date() } = {}) {
  const errors = [];
  if (value?.schemaVersion !== 3) errors.push("schemaVersion must be 3");
  if (value?.productionAvailability !== "BLOCKED") errors.push("productionAvailability must remain BLOCKED until release acceptance");
  if (value?.publicScope !== "圃場・指示・日誌・農薬・在庫のself-host／offline core") errors.push("publicScope must remain limited to the approved self-host/offline core");
  if (!validDate(value?.asOf) || !validDate(value?.reviewDueAt)) errors.push("asOf and reviewDueAt must be valid dates");
  else if (Date.parse(value.reviewDueAt) < now.getTime()) errors.push("capability catalog review is expired");
  else if (Date.parse(value.reviewDueAt) - Date.parse(value.asOf) > 93 * 86400000) errors.push("capability catalog review interval must be 93 days or less");
  if (!Array.isArray(value?.capabilities) || !value.capabilities.length) errors.push("capabilities are required");
  const ids = new Set();
  const categories = new Set();
  for (const item of value?.capabilities || []) {
    if (!item.id || ids.has(item.id)) errors.push(`capability id is missing or duplicated: ${item.id || "<blank>"}`);
    ids.add(item.id);
    categories.add(item.category);
    if (!CATEGORIES.has(item.category)) errors.push(`invalid category for ${item.id}`);
    if (!STATUSES.has(item.status)) errors.push(`invalid status for ${item.id}`);
    if (!Array.isArray(item.evidence) || !item.evidence.length) errors.push(`evidence is required for ${item.id}`);
    else for (const [index, evidence] of item.evidence.entries()) {
      if (typeof evidence?.path !== "string" || !DIGEST.test(evidence?.digest ?? "")) { errors.push(`${item.id}.evidence[${index}] requires path and sha256 digest`); continue; }
      try {
        const actual = sha256(await readFile(resolve(root, evidence.path)));
        if (actual !== evidence.digest) errors.push(`${item.id}.evidence[${index}] digest mismatch: ${evidence.path}`);
      } catch { errors.push(`${item.id}.evidence[${index}] path does not exist: ${evidence.path}`); }
    }
  }
  for (const category of CATEGORIES) if (!categories.has(category)) errors.push(`category is missing: ${category}`);
  for (const required of REQUIRED) if (!ids.has(required)) errors.push(`required capability is missing: ${required}`);
  const connector = value?.capabilities?.find(({ id }) => id === "wagri-machinery-connector");
  const claims = value?.comparisonClaims ?? {};
  if (claims.ksasEquivalent === true) {
    if (connector?.status !== "production-authorized") errors.push("KSAS equivalence requires a production-authorized contracted machinery connector");
    if (!Array.isArray(claims.comparisonReviewEvidence) || !claims.comparisonReviewEvidence.length) errors.push("KSAS equivalence requires comparison re-review evidence");
    if (!Array.isArray(claims.missingEcosystem) || !claims.missingEcosystem.length) errors.push("KSAS equivalence must disclose missing ecosystem capabilities");
  } else if (claims.status !== "PROHIBITED") errors.push("KSAS equivalence status must remain PROHIBITED while the claim is false");
  return errors;
}

export async function main(argv = process.argv.slice(2)) {
  if (argv.length !== 1) { console.error("usage: check-capability-catalog.mjs CATALOG.json"); return 64; }
  try {
    const value = JSON.parse(await readFile(argv[0], "utf8"));
    const errors = await validateCatalog(value);
    if (errors.length) { errors.forEach((error) => console.error(`ERROR: ${error}`)); return 1; }
    console.log("capability catalog: PASS (coverage, freshness, evidence paths and digests)");
    return 0;
  } catch (error) { console.error(`ERROR: ${error.message}`); return 2; }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main();
