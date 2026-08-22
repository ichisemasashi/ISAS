#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import process from "node:process";
import { pathToFileURL } from "node:url";

const HOSTS = ["macos", "linux", "freebsd"];
const FIELD_COUNTS = [100, 1000, 3000];
const COSTS = ["primary_host", "spare_host", "storage", "power", "network", "backup", "idp", "monitoring", "maintenance_labor", "security_updates", "restore_dr", "incidents", "planned_downtime", "unplanned_downtime"];
const URI = /^(?:artifact|https|s3):\/\/.+/;
const DIGEST = /^sha256:[0-9a-f]{64}$/;
const date = (value) => typeof value === "string" && Number.isFinite(Date.parse(value));

export function validateTco(value, now = new Date()) {
  const errors = [];
  const add = (message) => errors.push(message);
  if (value?.schemaVersion !== 2 || !/^[A-Z]{3}$/.test(value?.currency ?? "")) add("schemaVersion 2 and ISO currency are required");
  if (!date(value?.pricedAt) || Date.parse(value.pricedAt) > now.getTime() || now.getTime() - Date.parse(value.pricedAt) > 93 * 86400000) add("pricedAt must be an actual date within 93 days");
  const expected = new Set(HOSTS.flatMap((host) => FIELD_COUNTS.map((count) => `${host}:${count}`)));
  for (const item of value?.profiles || []) {
    const key = `${item.hostOs}:${item.fieldCount}`;
    if (!expected.delete(key)) add(`unexpected or duplicate profile: ${key}`);
    if (!item.assumptions?.service_owner || !Number.isFinite(item.assumptions?.maintainer_fte) || item.assumptions.maintainer_fte <= 0) add(`${key} requires service owner and maintainer FTE`);
    for (const name of ["os_updates", "application_updates", "restore_dr_drills", "incidents"]) if (!Number.isInteger(item.frequencies36Months?.[name]) || item.frequencies36Months[name] < 0) add(`${key}.frequencies36Months.${name} must be a non-negative integer`);
    for (const name of COSTS) {
      const cost = item.costs?.[name];
      if (!Number.isFinite(cost?.amount) || cost.amount < 0) add(`${key}.costs.${name}.amount must be a non-negative actual estimate`);
      if (!URI.test(cost?.quote?.uri ?? "") || !DIGEST.test(cost?.quote?.digest ?? "") || !date(cost?.quote?.validUntil) || Date.parse(cost.quote.validUntil) < Date.parse(value?.pricedAt ?? "")) add(`${key}.costs.${name} requires a valid evidence URI, digest and validity date`);
    }
  }
  if (expected.size) add(`missing profiles: ${[...expected].join(", ")}`);
  const actors = new Set();
  const roles = new Set();
  for (const [index, approval] of (Array.isArray(value?.approvals) ? value.approvals : []).entries()) {
    if (!approval?.actor || !URI.test(approval?.evidence ?? "") || !date(approval?.approvedAt)) add(`approvals[${index}] requires actor, date and evidence`);
    else actors.add(approval.actor);
    roles.add(approval?.role);
  }
  if (actors.size < 2 || !roles.has("service_owner") || !roles.has("financial_verifier")) add("two distinct service_owner and financial_verifier approvals are required");
  return errors;
}

export function calculateTco(value) {
  return { currency: value.currency, pricedAt: value.pricedAt, profiles: value.profiles.map((item) => ({
    hostOs: item.hostOs, fieldCount: item.fieldCount,
    total36Months: Object.values(item.costs).reduce((sum, cost) => sum + cost.amount, 0),
    maintainerFte: item.assumptions.maintainer_fte, frequencies36Months: item.frequencies36Months,
  })) };
}

export async function main(argv = process.argv.slice(2)) {
  if (argv.length !== 1) { console.error("usage: calculate-tco.mjs INPUT.json"); return 64; }
  try {
    const value = JSON.parse(await readFile(argv[0], "utf8"));
    const errors = validateTco(value);
    if (errors.length) { errors.forEach((error) => console.error(`ERROR: ${error}`)); return 1; }
    console.log(JSON.stringify(calculateTco(value), null, 2)); return 0;
  } catch (error) { console.error(`ERROR: ${error.message}`); return 2; }
}
if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main();
