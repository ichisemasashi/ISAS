#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";

const SHA = /^[0-9a-f]{40}$/;

export function validateCiPolicy(files) {
  const errors = [];
  const workflows = [files.ci, files.release];
  for (const [index, workflow] of workflows.entries()) {
    if (/\bpull_request_target\s*:/.test(workflow)) errors.push(`workflow ${index} must not use pull_request_target`);
    for (const match of workflow.matchAll(/\buses:\s*[^\s@]+@([^\s#]+)/g)) {
      if (!SHA.test(match[1])) errors.push(`workflow ${index} action is not pinned to a 40-character SHA: ${match[0]}`);
    }
  }
  for (const [name, dockerfile] of Object.entries(files.dockerfiles)) {
    for (const match of dockerfile.matchAll(/^FROM\s+(\S+)/gm)) {
      if (!/@sha256:[0-9a-f]{64}(?:\s+AS\s+\S+)?$/i.test(match[0])) errors.push(`${name} base image is not digest pinned: ${match[0]}`);
    }
  }
  for (const required of ["trivy-action@", "npm audit", "pnpm audit", "tofu validate", "Container (${{ matrix.component }})"]) {
    if (!files.ci.includes(required)) errors.push(`CI is missing required gate: ${required}`);
  }
  for (const required of ["provenance: mode=max", "sbom: true", "cosign sign", "cosign attest", "environment: staging", "steps.build.outputs.digest"]) {
    if (!files.release.includes(required)) errors.push(`release build is missing supply-chain control: ${required}`);
  }
  for (const required of ["/apps/bff/migrations/", "/infra/", "/.github/"]) {
    if (!files.codeowners.includes(required)) errors.push(`CODEOWNERS is missing sensitive path: ${required}`);
  }
  return errors;
}

export async function loadPolicyFiles(root = ".") {
  const read = (path) => readFile(resolve(root, path), "utf8");
  return {
    ci: await read(".github/workflows/ci.yml"),
    release: await read(".github/workflows/build-release.yml"),
    codeowners: await read(".github/CODEOWNERS"),
    dockerfiles: {
      bff: await read("apps/bff/Dockerfile"),
      web: await read("apps/web/Dockerfile"),
      migration: await read("apps/bff/Dockerfile.migration"),
    },
  };
}

async function main() {
  const errors = validateCiPolicy(await loadPolicyFiles());
  if (errors.length) {
    console.error(`CI policy: FAIL (${errors.length})`);
    errors.forEach((error) => console.error(`- ${error}`));
    return 1;
  }
  console.log("CI policy: PASS (immutable actions/base images, security gates, build-once attestations, CODEOWNERS)");
  return 0;
}

if (import.meta.url === pathToFileURL(resolve(process.argv[1])).href) process.exitCode = await main();
