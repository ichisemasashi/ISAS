import assert from "node:assert/strict";
import test from "node:test";
import { validateReferenceCapacity } from "./check-reference-capacity.mjs";

const operations = ["field_bbox", "field_search", "field_detail", "map_pan", "journal", "photo_upload_download", "sync_pull", "analytics"];
function fixture() {
  const digest = `sha256:${"a".repeat(64)}`;
  const results = {};
  for (const concurrency of [20, 50, 100]) for (const temperature of ["cold", "warm"]) for (const operation of operations) results[`${concurrency}:${temperature}:${operation}`] = {
    status: "PASS", samples: 30, p50_ms: 10, p95_ms: 20, p99_ms: 30, error_rate: 0,
    db_pool_wait_ms: 1, cpu_percent: 20, memory_bytes: 1024, disk_latency_ms: 1, network_ms: 2, object_latency_ms: 3, evidence: "artifact://capacity/result",
  };
  const host = (host_os) => ({ host_os, architecture: "test-arch", cpu: "test-cpu", ram_bytes: 1024, disk: "test-disk", network: "test-network", component_set_digest: digest, fixture_digest: digest, load_script_digest: digest, results: structuredClone(results) });
  return { schema_version: 1, status: "PASS", fixture_digest: digest, load_script_digest: digest, hosts: [host("macos"), host("linux"), host("freebsd")], approvals: [
    { actor: "owner", role: "performance_owner" }, { actor: "verifier", role: "independent_verifier" },
  ] };
}
test("accepts the same complete fixture across all three hosts", () => assert.deepEqual(validateReferenceCapacity(fixture()), []));
test("rejects a missing host, digest drift and insufficient samples", () => {
  const value = fixture(); value.hosts.pop(); value.hosts[0].fixture_digest = `sha256:${"b".repeat(64)}`; value.hosts[1].results["100:cold:field_bbox"].samples = 29;
  const errors = validateReferenceCapacity(value); assert.ok(errors.some((error) => error.includes("missing host result"))); assert.ok(errors.some((error) => error.includes("common fixture"))); assert.ok(errors.some((error) => error.includes("30 samples")));
});
