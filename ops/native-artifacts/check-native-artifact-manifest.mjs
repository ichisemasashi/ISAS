#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import process from "node:process";
import { pathToFileURL } from "node:url";

const DIGEST = /^sha256:[0-9a-f]{64}$/;
const VERSION = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/;
const RECORD_SUFFIXES = { checksum: ".sha256", signature: ".sig", sbom: ".sbom.spdx.json", provenance: ".provenance.json" };

export function validateNativeManifest(contract, manifest) {
  const errors = [];
  if (contract?.schema_version !== 1 || manifest?.schema_version !== 1) errors.push("schema_version must be 1");
  if (!VERSION.test(manifest?.version || "")) errors.push("version must be SemVer");
  if (!/^[0-9a-f]{40}$/.test(manifest?.source_commit || "")) errors.push("source_commit must be 40 lowercase hex");
  if (manifest?.signing_provider === "aws-kms" || manifest?.registry?.includes?.("ecr")) errors.push("native supply chain must not require AWS KMS or ECR");
  const expected = new Set();
  for (const target of contract?.targets || []) for (const architecture of target.architectures || []) for (const service of contract.services || []) expected.add(`${target.host_os}:${architecture}:${service}`);
  const seen = new Set();
  for (const item of manifest?.artifacts || []) {
    const key = `${item?.host_os}:${item?.architecture}:${item?.service}`;
    if (!expected.has(key) || seen.has(key)) errors.push(`unexpected or duplicate artifact: ${key}`); else seen.add(key);
    if (item.version !== manifest.version || !DIGEST.test(item.digest || "")) errors.push(`${key} version or digest is invalid`);
    if (!item.artifact?.endsWith(item.host_os === "linux" ? ".deb" : ".pkg")) errors.push(`${key} package format is invalid`);
    for (const [field, suffix] of Object.entries(RECORD_SUFFIXES)) if (item[field] !== `${item.artifact}${suffix}`) errors.push(`${key} ${field} is not bound to the artifact`);
    if (item.signature_verified !== true || item.install_verified !== true) errors.push(`${key} signature and install verification are required`);
  }
  for (const key of expected) if (!seen.has(key)) errors.push(`native artifact is missing: ${key}`);
  return errors;
}

async function main(argv = process.argv.slice(2)) {
  if (argv.length !== 2) return 64;
  const [contract, manifest] = await Promise.all(argv.map(async (file) => JSON.parse(await readFile(file, "utf8"))));
  const errors = validateNativeManifest(contract, manifest);
  if (errors.length) { errors.forEach((error) => console.error(`ERROR: ${error}`)); return 1; }
  console.log(`native artifact manifest: PASS (${manifest.artifacts.length} artifacts)`); return 0;
}
if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main();
