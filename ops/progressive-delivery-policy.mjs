#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";

const TRANSITIONS = Object.freeze({
  staging: new Set([null, "staging", "finalized", "rolled_back"]),
  prepare: new Set([null, "finalized", "rolled_back"]),
  "5": new Set(["prepared"]),
  "25": new Set(["5"]),
  "100": new Set(["25"]),
  finalize: new Set(["100"]),
  rollback: new Set(["prepared", "5", "25", "100"]),
});

export function validateDelivery({ command, deployment, build, state }) {
  const errors = [];
  if (!TRANSITIONS[command]) errors.push("unsupported delivery command");
  const progressive = deployment?.ecs?.progressive_delivery;
  for (const key of ["listener_arn", "bff_rule_arn", "web_stable_tg", "web_canary_tg", "bff_stable_tg", "bff_canary_tg", "fast_burn_alarm", "slow_burn_alarm"]) {
    if (typeof progressive?.[key] !== "string" || !progressive[key]) errors.push(`deployment progressive_delivery.${key} is required`);
  }
  for (const key of ["web", "bff", "web_canary", "bff_canary"]) {
    if (typeof deployment?.ecs?.services?.[key] !== "string" || !deployment.ecs.services[key]) errors.push(`deployment ecs.services.${key} is required`);
  }
  if (!Array.isArray(build?.artifacts)) errors.push("build artifacts are required");
  else for (const component of ["web", "bff", "migration"]) {
    const artifact = build.artifacts.find(({ name }) => name === component);
    if (!artifact || !/^.+@sha256:[0-9a-f]{64}$/.test(artifact.reference || "")) errors.push(`immutable ${component} artifact is required`);
  }
  const current = state?.stage ?? null;
  if (TRANSITIONS[command] && !TRANSITIONS[command].has(current)) errors.push(`invalid delivery transition ${current ?? "none"} -> ${command}`);
  if (state && state.deployment_id !== deployment?.deployment_id) errors.push("state deployment_id does not match manifest");
  if (!["prepare", "staging"].includes(command) && state && state.source_commit !== build?.source_commit) errors.push("state source_commit does not match build");
  if (!/^sha256:[0-9a-f]{64}$/.test(build?.artifact_set_digest || "")) errors.push("build artifact_set_digest is required");
  if (!["prepare", "staging"].includes(command) && state && state.artifact_set_digest !== build?.artifact_set_digest) errors.push("state artifact_set_digest does not match build");
  return errors;
}

async function main(argv) {
  if (argv.length !== 4) throw new Error("usage: progressive-delivery-policy.mjs COMMAND DEPLOYMENT BUILD STATE_OR_DASH");
  const [command, deploymentPath, buildPath, statePath] = argv;
  const deployment = JSON.parse(await readFile(deploymentPath, "utf8"));
  const build = JSON.parse(await readFile(buildPath, "utf8"));
  const state = statePath === "-" ? null : JSON.parse(await readFile(statePath, "utf8"));
  const errors = validateDelivery({ command, deployment, build, state });
  if (errors.length) {
    errors.forEach((error) => console.error(`- ${error}`));
    return 1;
  }
  console.log(`delivery policy: PASS ${state?.stage ?? "none"} -> ${command}`);
  return 0;
}

if (import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main(process.argv.slice(2)).then((code) => { process.exitCode = code; }).catch((error) => { console.error(error.message); process.exitCode = 2; });
}
