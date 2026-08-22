#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import process from "node:process";
import { pathToFileURL } from "node:url";

const HOSTS = ["macos", "linux", "freebsd"];
const CONCURRENCY = [20, 50, 100];
const TEMPERATURES = ["cold", "warm"];
const OPERATIONS = ["field_bbox", "field_search", "field_detail", "map_pan", "journal", "photo_upload_download", "sync_pull", "analytics"];
const METRICS = ["p50_ms", "p95_ms", "p99_ms", "error_rate", "db_pool_wait_ms", "cpu_percent", "memory_bytes", "disk_latency_ms", "network_ms", "object_latency_ms"];
const DIGEST = /^sha256:[0-9a-f]{64}$/;
const URI = /^(?:artifact|https|s3):\/\/.+/;

export function validateReferenceCapacity(value) {
  const errors = [];
  const add = (message) => errors.push(message);
  if (value?.schema_version !== 1 || value?.status !== "PASS") add("capacity evidence must be schema 1 and PASS");
  if (!DIGEST.test(value?.fixture_digest ?? "") || !DIGEST.test(value?.load_script_digest ?? "")) add("fixture and load script digests are required");
  const hosts = new Map((Array.isArray(value?.hosts) ? value.hosts : []).map((host) => [host.host_os, host]));
  for (const hostOs of HOSTS) {
    const host = hosts.get(hostOs);
    if (!host) { add(`missing host result: ${hostOs}`); continue; }
    if (!host.architecture || !host.cpu || !Number.isFinite(host.ram_bytes) || !host.disk || !host.network || !DIGEST.test(host.component_set_digest ?? "")) add(`${hostOs} host identity and component digest are required`);
    if (host.fixture_digest !== value.fixture_digest || host.load_script_digest !== value.load_script_digest) add(`${hostOs} must use the common fixture and load script`);
    for (const concurrency of CONCURRENCY) for (const temperature of TEMPERATURES) for (const operation of OPERATIONS) {
      const key = `${concurrency}:${temperature}:${operation}`;
      const result = host.results?.[key];
      if (!result || result.samples < 30 || result.status !== "PASS" || !URI.test(result.evidence ?? "")) { add(`${hostOs}.${key} requires 30 samples, PASS and evidence`); continue; }
      for (const metric of METRICS) if (!Number.isFinite(result[metric]) || result[metric] < 0) add(`${hostOs}.${key}.${metric} must be measured`);
      if (Number.isFinite(result.p50_ms) && Number.isFinite(result.p95_ms) && result.p50_ms > result.p95_ms) add(`${hostOs}.${key} percentiles are invalid`);
      if (Number.isFinite(result.p95_ms) && Number.isFinite(result.p99_ms) && result.p95_ms > result.p99_ms) add(`${hostOs}.${key} percentiles are invalid`);
      if (Number.isFinite(result.error_rate) && result.error_rate > 0.001) add(`${hostOs}.${key} error_rate exceeds ISAS SLO`);
    }
  }
  if (hosts.size !== 3) add("capacity evidence must contain exactly macos, linux and freebsd");
  const actors = new Set((value?.approvals ?? []).map(({ actor }) => actor).filter(Boolean));
  if (actors.size < 2 || !(value?.approvals ?? []).some(({ role }) => role === "performance_owner") || !(value?.approvals ?? []).some(({ role }) => role === "independent_verifier")) add("two distinct performance_owner and independent_verifier approvals are required");
  return errors;
}

export async function main(argv = process.argv.slice(2)) {
  if (argv.length !== 1) { console.error("usage: node ops/capacity/check-reference-capacity.mjs EVIDENCE.json"); return 64; }
  try {
    const value = JSON.parse(await readFile(argv[0], "utf8")); const errors = validateReferenceCapacity(value);
    if (errors.length) { errors.forEach((error) => console.error(`ERROR: ${error}`)); return 1; }
    console.log("reference capacity: PASS (macos/linux/freebsd, common fixture, 20/50/100 users)"); return 0;
  } catch (error) { console.error(`ERROR: ${error.message}`); return 2; }
}
if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main();
