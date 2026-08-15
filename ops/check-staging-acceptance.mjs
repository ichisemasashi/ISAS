#!/usr/bin/env node
import { readFile } from "node:fs/promises";

export const REQUIRED_CHECKS = Object.freeze([
  "account-region",
  "three-availability-zones",
  "regional-kms-keys",
  "tls-ingress",
  "rds-multi-az",
  "rds-wal-pitr",
  "postgres-postgis",
  "auth-migration",
  "auth-production-security",
  "ecs-services",
  "web-bff-failure-domains",
  "digest-images",
  "cognito-mfa",
  "dynamodb-session",
  "s3-private-storage",
  "private-attachment-delivery",
  "sqs-dead-letter",
  "queue-quarantine",
  "shard-manifest-signature",
  "backup-recovery-point",
  "monitoring-alert-route",
  "https-health",
  "waf-association",
  "github-oidc",
]);

export function evaluateStagingAcceptance(evidence, now = new Date()) {
  const errors = [];
  if (evidence?.schemaVersion !== 1) errors.push("schemaVersion must be 1");
  if (evidence?.environment !== "staging") errors.push("environment must be staging");
  if (evidence?.region !== "ap-northeast-1") errors.push("region must be ap-northeast-1");
  if (!/^\d{12}$/.test(evidence?.accountId ?? "")) errors.push("accountId must be a 12-digit value");
  if (!/^isas-jp-stg-\d{2}$/.test(evidence?.deploymentId ?? "")) errors.push("deploymentId is not a staging ID");
  if (!/^[0-9a-f]{40}$/.test(evidence?.commitSha ?? "")) errors.push("commitSha must be a full Git SHA");
  if (!/^sha256:[0-9a-f]{64}$/.test(evidence?.tofuPlanSha256 ?? "")) errors.push("tofuPlanSha256 is invalid");
  const collectedAt = Date.parse(evidence?.collectedAt ?? "");
  if (!Number.isFinite(collectedAt)) errors.push("collectedAt must be an ISO date");
  else if (collectedAt > now.getTime() || now.getTime() - collectedAt > 24 * 60 * 60 * 1000) {
    errors.push("collectedAt must be within the last 24 hours and not in the future");
  }

  const checks = Array.isArray(evidence?.checks) ? evidence.checks : [];
  const byId = new Map();
  for (const check of checks) {
    if (!check || typeof check.id !== "string") {
      errors.push("every check needs an id");
      continue;
    }
    if (byId.has(check.id)) errors.push(`duplicate check: ${check.id}`);
    byId.set(check.id, check);
  }

  for (const id of REQUIRED_CHECKS) {
    const check = byId.get(id);
    if (!check) {
      errors.push(`missing check: ${id}`);
      continue;
    }
    if (check.status !== "PASS") errors.push(`${id} is ${check.status ?? "unset"}`);
    if (typeof check.evidence !== "string" || check.evidence.trim().length < 8) {
      errors.push(`${id} needs concrete evidence`);
    }
  }

  if (checks.some((check) => !REQUIRED_CHECKS.includes(check.id))) {
    errors.push("unknown acceptance check is present");
  }

  return { ready: errors.length === 0, errors };
}

async function main() {
  const path = process.argv[2];
  if (!path) throw new Error("usage: node ops/check-staging-acceptance.mjs <evidence.json>");
  const evidence = JSON.parse(await readFile(path, "utf8"));
  const result = evaluateStagingAcceptance(evidence);
  if (!result.ready) {
    console.error("STAGING ACCEPTANCE: BLOCKED");
    for (const error of result.errors) console.error(`- ${error}`);
    process.exitCode = 1;
    return;
  }
  console.log(`STAGING ACCEPTANCE: PASS (${REQUIRED_CHECKS.length}/${REQUIRED_CHECKS.length})`);
}

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
