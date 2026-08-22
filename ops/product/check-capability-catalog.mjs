#!/usr/bin/env node
import { readFileSync } from "node:fs";

export function validateCatalog(value) {
  const errors = [];
  const statuses = new Set(["implemented", "validated", "planned", "out-of-scope"]);
  if (value?.schemaVersion !== 2) errors.push("schemaVersion must be 2");
  if (value?.productionAvailability !== "BLOCKED") errors.push("productionAvailability must remain BLOCKED until release acceptance");
  if (value?.publicScope !== "圃場・指示・日誌・農薬・在庫のself-host／offline core") errors.push("publicScope must remain limited to the approved self-host/offline core");
  if (!Array.isArray(value?.capabilities) || !value.capabilities.length) errors.push("capabilities are required");
  const ids = new Set();
  for (const item of value?.capabilities || []) {
    if (!item.id || ids.has(item.id)) errors.push(`capability id is missing or duplicated: ${item.id || "<blank>"}`);
    ids.add(item.id);
    if (!statuses.has(item.status)) errors.push(`invalid status for ${item.id}`);
    if (!Array.isArray(item.evidence) || !item.evidence.length) errors.push(`evidence is required for ${item.id}`);
  }
  for (const required of ["wagri-machinery-connector", "remote-sensing", "water-management", "drying-preparation", "diagnostic-support"])
    if (!ids.has(required)) errors.push(`required comparison capability is missing: ${required}`);
  const connector = value?.capabilities?.find(({ id }) => id === "wagri-machinery-connector");
  const claims = value?.comparisonClaims ?? {};
  if (claims.ksasEquivalent === true) {
    if (connector?.status !== "validated") errors.push("KSAS equivalence requires a validated contracted machinery connector");
    if (!Array.isArray(claims.comparisonReviewEvidence) || !claims.comparisonReviewEvidence.length) errors.push("KSAS equivalence requires comparison re-review evidence");
    if (!Array.isArray(claims.missingEcosystem) || !claims.missingEcosystem.length) errors.push("KSAS equivalence must still disclose missing ecosystem capabilities");
  } else if (claims.status !== "PROHIBITED") errors.push("KSAS equivalence status must remain PROHIBITED while the claim is false");
  return errors;
}

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href) {
  const file = process.argv[2];
  if (!file) { console.error("usage: check-capability-catalog.mjs CATALOG.json"); process.exit(64); }
  const errors = validateCatalog(JSON.parse(readFileSync(file, "utf8")));
  if (errors.length) { errors.forEach((error) => console.error(`ERROR: ${error}`)); process.exit(1); }
  console.log("capability catalog: PASS");
}
