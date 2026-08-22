#!/usr/bin/env node

import { createHash, createPublicKey, verify } from "node:crypto";
import { readFile } from "node:fs/promises";
import process from "node:process";
import { pathToFileURL } from "node:url";

const DIGEST = /^sha256:[0-9a-f]{64}$/;
const COMMIT = /^[0-9a-f]{40}$/;
const REQUIRED_INPUTS = ["release", "build", "delivery", "bake"];

function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function sha256(bytes) {
  return `sha256:${createHash("sha256").update(bytes).digest("hex")}`;
}

function snapshotDigest(snapshot) {
  return sha256(Buffer.from(canonical(snapshot)));
}

export function authorizationPayload(authorization) {
  const copy = structuredClone(authorization);
  if (copy.attestation) delete copy.attestation.signature_base64;
  return Buffer.from(canonical(copy));
}

export function validateProductionTagAuthorization({ authorization, inputBytes, trustedPublicKey, environment = {} }) {
  const errors = [];
  const add = (message) => errors.push(message);
  if (authorization?.schema_version !== 1) add("authorization.schema_version must be 1");
  if (!authorization?.repository || !authorization.repository.includes("/")) add("authorization.repository must be an owner/repository");
  if (!authorization?.workflow_run_id) add("authorization.workflow_run_id is required");
  if (authorization?.environment !== "production-release") add("authorization.environment must be production-release");
  if (!/^production\/v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(authorization?.tag?.name ?? "")) add("authorization.tag.name must use production/v<semver>");
  if (!COMMIT.test(authorization?.tag?.target_commit ?? "")) add("authorization.tag.target_commit must be a 40-character lowercase commit");

  for (const name of REQUIRED_INPUTS) {
    const expected = authorization?.inputs?.[name]?.digest;
    if (!DIGEST.test(expected ?? "")) add(`authorization.inputs.${name}.digest must be sha256:<64 lowercase hex>`);
    else if (!inputBytes?.[name] || sha256(inputBytes[name]) !== expected) add(`authorization.inputs.${name}.digest does not match verified content`);
  }

  const approvals = Array.isArray(authorization?.approvals) ? authorization.approvals : [];
  const actors = new Set();
  const roles = new Set();
  for (const [index, approval] of approvals.entries()) {
    const prefix = `authorization.approvals[${index}]`;
    if (approval?.provider !== "github-environment") add(`${prefix}.provider must be github-environment`);
    if (approval?.repository !== authorization?.repository) add(`${prefix}.repository must match authorization.repository`);
    if (approval?.environment !== "production-release") add(`${prefix}.environment must be production-release`);
    const expectedSubject = `repo:${authorization?.repository}:environment:production-release`;
    if (approval?.verified_subject !== expectedSubject) add(`${prefix}.verified_subject must bind the protected environment`);
    if (!approval?.actor || !approval?.approval_id) add(`${prefix}.actor and approval_id are required`);
    else actors.add(approval.actor);
    roles.add(approval?.role);
  }
  if (actors.size < 2 || !roles.has("release_manager") || !roles.has("independent_verifier")) {
    add("authorization requires two distinct verified release_manager and independent_verifier approvals");
  }

  const ruleset = authorization?.tag_ruleset;
  if (!Number.isInteger(ruleset?.ruleset_id) || ruleset.ruleset_id <= 0 || ruleset?.enforcement !== "active" || ruleset?.pattern !== "production/v*") {
    add("authorization.tag_ruleset must identify an active production/v* protected tag ruleset");
  }
  if (!DIGEST.test(ruleset?.snapshot_digest ?? "") || snapshotDigest(ruleset?.snapshot ?? null) !== ruleset?.snapshot_digest) {
    add("authorization.tag_ruleset snapshot digest does not match signed snapshot");
  }

  const event = authorization?.audit_event;
  if (!event?.event_id || event?.event_type !== "production_tag_authorized") add("authorization.audit_event must identify production_tag_authorized");
  if (!DIGEST.test(event?.snapshot_digest ?? "") || snapshotDigest(event?.snapshot ?? null) !== event?.snapshot_digest) {
    add("authorization.audit_event snapshot digest does not match signed snapshot");
  }

  const attestation = authorization?.attestation;
  const expectedSubject = `repo:${authorization?.repository}:environment:production-release`;
  if (attestation?.algorithm !== "ed25519" || attestation?.issuer !== "https://token.actions.githubusercontent.com" || attestation?.subject !== expectedSubject) {
    add("authorization.attestation must bind GitHub OIDC production-release identity with ed25519");
  } else {
    try {
      const signature = Buffer.from(attestation.signature_base64 ?? "", "base64");
      if (!trustedPublicKey || signature.length === 0 || !verify(null, authorizationPayload(authorization), createPublicKey(trustedPublicKey), signature)) {
        add("authorization.attestation signature verification failed");
      }
    } catch {
      add("authorization.attestation signature verification failed");
    }
  }

  if (environment.GITHUB_ACTIONS === "true") {
    if (environment.GITHUB_REPOSITORY !== authorization?.repository) add("GitHub repository context does not match authorization");
    if (environment.ISAS_GITHUB_ENVIRONMENT !== "production-release") add("GitHub protected environment context is not production-release");
  }
  return errors;
}

export async function main(argv = process.argv.slice(2)) {
  if (argv.length !== 6) {
    console.error("usage: node ops/check-production-tag-authorization.mjs RELEASE BUILD DELIVERY BAKE AUTHORIZATION TRUSTED_PUBLIC_KEY");
    return 2;
  }
  try {
    const [release, build, delivery, bake, authorizationBytes, trustedPublicKey] = await Promise.all(argv.map((path) => readFile(path)));
    const authorization = JSON.parse(authorizationBytes);
    const errors = validateProductionTagAuthorization({
      authorization,
      inputBytes: { release, build, delivery, bake },
      trustedPublicKey,
      environment: process.env,
    });
    if (errors.length) {
      console.error(`production tag authorization: BLOCKED (${errors.length})`);
      errors.forEach((error) => console.error(`- ${error}`));
      return 1;
    }
    console.log(`production tag authorization: VERIFIED ${authorization.tag.name}`);
    return 0;
  } catch (error) {
    console.error(`production tag authorization: FAIL\n- ${error.message}`);
    return 2;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main();
