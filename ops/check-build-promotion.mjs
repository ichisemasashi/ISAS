#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";

export function validateBuildPromotion(build, release, stagingEvidence) {
  const errors = [];
  if (build?.source_commit !== release?.release?.source_commit) errors.push("build and release source commits differ");
  if (release?.deployment?.jurisdiction !== "JP") errors.push("release jurisdiction must be JP");
  const normalizeVersion = (value) => String(value || "").replace(/^v/, "");
  if (normalizeVersion(build?.version) !== normalizeVersion(release?.release?.version)) errors.push("build and release versions differ");
  for (const built of build?.artifacts || []) {
    const approved = (release?.artifacts || []).find(({ name }) => name === built.name);
    if (!approved || approved.digest !== built.digest) errors.push(`${built.name} digest is not approved by the release manifest`);
    if (approved && (!approved.signature_verified || !approved.provenance_verified)) errors.push(`${built.name} supply-chain evidence is not approved`);
  }
  if ((build?.artifacts || []).length !== (release?.artifacts || []).length) errors.push("build and release artifact sets differ");
  if (stagingEvidence) {
    if (stagingEvidence.environment !== "staging" || stagingEvidence.region !== "ap-northeast-1") errors.push("staging evidence environment is invalid");
    if (stagingEvidence.commitSha !== build?.source_commit) errors.push("staging evidence was collected for a different commit");
    if (!Array.isArray(stagingEvidence.checks) || stagingEvidence.checks.some(({ status }) => status !== "PASS")) errors.push("staging evidence contains a non-PASS check");
  }
  return errors;
}

async function main(argv) {
  if (argv.length < 2 || argv.length > 3) throw new Error("usage: check-build-promotion.mjs BUILD_MANIFEST RELEASE_MANIFEST [STAGING_EVIDENCE]");
  const [buildPath, releasePath, evidencePath] = argv;
  const build = JSON.parse(await readFile(buildPath, "utf8"));
  const release = JSON.parse(await readFile(releasePath, "utf8"));
  const evidence = evidencePath ? JSON.parse(await readFile(evidencePath, "utf8")) : undefined;
  const errors = validateBuildPromotion(build, release, evidence);
  if (errors.length) {
    console.error(`build promotion: BLOCKED (${errors.length})`);
    errors.forEach((error) => console.error(`- ${error}`));
    return 1;
  }
  console.log(`build promotion: PASS ${build.version} ${build.artifact_set_digest}`);
  return 0;
}

if (import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main(process.argv.slice(2)).then((code) => { process.exitCode = code; }).catch((error) => { console.error(error.message); process.exitCode = 2; });
}
