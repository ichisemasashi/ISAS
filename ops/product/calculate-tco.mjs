#!/usr/bin/env node
import { readFileSync } from "node:fs";

const [file] = process.argv.slice(2);
if (!file) { console.error("usage: calculate-tco.mjs INPUT.json"); process.exit(64); }
const input = JSON.parse(readFileSync(file, "utf8"));
const errors = [];
if (input.schemaVersion !== 1 || !/^[A-Z]{3}$/.test(input.currency || "")) errors.push("schemaVersion and currency are required");
if (!Number.isFinite(Date.parse(input.pricedAt || ""))) errors.push("pricedAt is required");
const expected = new Set(["macos:100", "macos:1000", "macos:3000", "linux:100", "linux:1000", "linux:3000", "freebsd:100", "freebsd:1000", "freebsd:3000"]);
const output = [];
for (const item of input.profiles || []) {
  expected.delete(`${item.hostOs}:${item.fieldCount}`);
  for (const key of ["oneTime", "monthly", "annual", "downtime", "updateCount36Months"]) if (!Number.isFinite(item[key]) || item[key] < 0) errors.push(`${item.hostOs}:${item.fieldCount}.${key} must be a non-negative number`);
  if (["oneTime", "monthly", "annual", "downtime"].every((key) => Number.isFinite(item[key]))) output.push({ hostOs: item.hostOs, fieldCount: item.fieldCount, total36Months: item.oneTime + 36 * item.monthly + 3 * item.annual + item.downtime, updateCount36Months: item.updateCount36Months });
}
if (expected.size) errors.push(`missing profiles: ${[...expected].join(", ")}`);
if (errors.length) { errors.forEach((error) => console.error(`ERROR: ${error}`)); process.exit(1); }
console.log(JSON.stringify({ currency: input.currency, pricedAt: input.pricedAt, profiles: output }, null, 2));
