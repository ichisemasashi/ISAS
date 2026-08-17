#!/usr/bin/env node
import { readFileSync } from "node:fs";

export function validateCatalog(value) {
  const errors = [];
  const statuses = new Set(["implemented", "validated", "planned", "out-of-scope"]);
  if (value?.schemaVersion !== 1) errors.push("schemaVersion must be 1");
  if (value?.productionAvailability !== "BLOCKED") errors.push("productionAvailability must remain BLOCKED until release acceptance");
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
  return errors;
}

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href) {
  const file = process.argv[2];
  if (!file) { console.error("usage: check-capability-catalog.mjs CATALOG.json"); process.exit(64); }
  const errors = validateCatalog(JSON.parse(readFileSync(file, "utf8")));
  if (errors.length) { errors.forEach((error) => console.error(`ERROR: ${error}`)); process.exit(1); }
  console.log("capability catalog: PASS");
}
