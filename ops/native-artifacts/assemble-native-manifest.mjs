#!/usr/bin/env node

import { readdir, readFile, stat, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import process from "node:process";

import { validateNativeManifest } from "./check-native-artifact-manifest.mjs";

async function walk(directory, records = []) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = resolve(directory, entry.name);
    if (entry.isDirectory()) await walk(path, records);
    else if (entry.name.endsWith(".record.json")) records.push(path);
  }
  return records;
}
const [contractFile, directory, version, sourceCommit, output] = process.argv.slice(2);
if (![contractFile, directory, version, sourceCommit, output].every(Boolean)) process.exit(64);
const contract = JSON.parse(await readFile(contractFile, "utf8"));
const recordFiles = await walk(directory);
const artifacts = [];
for (const recordFile of recordFiles) {
  const record = JSON.parse(await readFile(recordFile, "utf8"));
  const parent = resolve(recordFile, "..");
  for (const field of ["artifact", "checksum", "signature", "sbom", "provenance"]) await stat(resolve(parent, record[field]));
  artifacts.push(record);
}
const manifest = { schema_version: 1, version, source_commit: sourceCommit, signing_provider: "repository-neutral-approved-key", artifacts };
const errors = validateNativeManifest(contract, manifest);
if (errors.length) { errors.forEach((error) => console.error(`ERROR: ${error}`)); process.exit(1); }
await writeFile(output, `${JSON.stringify(manifest, null, 2)}\n`, { flag: "wx", mode: 0o444 });
console.log(`native artifact manifest assembled: ${artifacts.length}`);
