import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";

const PLACEHOLDER = /replace-me|example|todo|unset|未設定/i;
const ROUTE = /^(?:mailto:|https:\/\/|tel:)[^\s]+$/;
const nonblank = (value) => typeof value === "string" && value.trim().length > 0 && !PLACEHOLDER.test(value);

export function validateDeploymentOperations(value, expectedDeploymentId) {
  const errors = [];
  const add = (message) => errors.push(message);
  if (value?.schemaVersion !== 1) add("schemaVersion must be 1");
  if (!nonblank(value?.deploymentId)) add("deploymentId is required");
  else if (expectedDeploymentId && value.deploymentId !== expectedDeploymentId) add("deploymentId must match runtime deployment");
  if (!nonblank(value?.support?.timezone) || !nonblank(value?.support?.hours)) add("support timezone and hours are required");
  for (const severity of ["sev1", "sev2", "sev3", "sev4"]) {
    const response = value?.severity?.[severity];
    if (!Number.isInteger(response?.responseMinutes) || response.responseMinutes < 1) add(`severity.${severity}.responseMinutes is required`);
    if (!nonblank(response?.definition)) add(`severity.${severity}.definition is required`);
  }
  for (const contact of ["serviceOwner", "onCall", "security", "vulnerability", "privacy"]) {
    const entry = value?.contacts?.[contact];
    if (!nonblank(entry?.owner)) add(`contacts.${contact}.owner is required`);
    if (!ROUTE.test(entry?.route || "") || PLACEHOLDER.test(entry?.route || "")) add(`contacts.${contact}.route must be a non-placeholder mailto, https, or tel route`);
  }
  for (const role of ["serviceAccountable", "runtimeResponsible", "databaseResponsible", "securityResponsible", "backupResponsible", "releaseResponsible"]) {
    if (!nonblank(value?.raci?.[role])) add(`raci.${role} is required`);
  }
  if (!nonblank(value?.lifecycle?.productVersion) || !/^\d+\.\d+\.\d+/.test(value?.lifecycle?.productVersion || "")) add("lifecycle.productVersion is required");
  if (!Number.isFinite(Date.parse(value?.lifecycle?.supportEndsOn || ""))) add("lifecycle.supportEndsOn must be a date");
  if (!Number.isInteger(value?.lifecycle?.migrationNoticeDays) || value.lifecycle.migrationNoticeDays < 30) add("lifecycle.migrationNoticeDays must be at least 30");
  return errors;
}

export function readDeploymentOperations(path, expectedDeploymentId, read = readFileSync) {
  if (!nonblank(path)) throw new Error("ISAS_OPERATIONS_LEDGER is required for production");
  const bytes = read(path);
  const value = JSON.parse(bytes.toString("utf8"));
  const errors = validateDeploymentOperations(value, expectedDeploymentId);
  if (errors.length) throw new Error(`deployment operations ledger is invalid: ${errors.join("; ")}`);
  return { value, sha256: createHash("sha256").update(bytes).digest("hex") };
}
