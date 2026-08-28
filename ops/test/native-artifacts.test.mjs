import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, readdir, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { readFile } from "node:fs/promises";
import { promisify } from "node:util";

import { validateNativeManifest } from "../native-artifacts/check-native-artifact-manifest.mjs";

const contract = JSON.parse(await readFile("ops/native-artifacts/native-artifact-contract.json", "utf8"));
const execFileAsync = promisify(execFile);
const version = "1.2.3";
const artifacts = contract.targets.flatMap((target) => target.architectures.flatMap((architecture) => contract.services.map((service) => {
  const artifact = `${target.host_os}-${architecture}-${service}.${target.format}`;
  return { host_os: target.host_os, architecture, service, version, artifact, bytes: 1, digest: `sha256:${"a".repeat(64)}`, checksum: `${artifact}.sha256`, signature: `${artifact}.sig`, sbom: `${artifact}.sbom.spdx.json`, provenance: `${artifact}.provenance.json`, signature_verified: true, install_verified: true };
})));

test("requires the full 3 OS, 2 architecture, 6 service native matrix", () => {
  assert.equal(artifacts.length, 36);
  assert.deepEqual(validateNativeManifest(contract, { schema_version: 1, version, source_commit: "a".repeat(40), signing_provider: "offline-pem-or-approved-hsm", artifacts }), []);
});

test("rejects missing install verification and AWS-only native supply chain", () => {
  const broken = structuredClone(artifacts);
  broken[0].install_verified = false;
  const errors = validateNativeManifest(contract, { schema_version: 1, version, source_commit: "a".repeat(40), signing_provider: "aws-kms", registry: "ecr", artifacts: broken });
  assert(errors.some((error) => error.includes("must not require AWS")));
  assert(errors.some((error) => error.includes("signature and install")));
});

test("native payload sources exist and host metadata is excluded", async () => {
  for (const spec of Object.values(contract.service_payloads)) for (const source of spec.sources) await stat(source);
  const directory = await mkdtemp(join(tmpdir(), "isas-native-payload-test-"));
  try {
    await execFileAsync(process.execPath, ["ops/native-artifacts/prepare-native-payload.mjs", "ops/native-artifacts/native-artifact-contract.json", "macos", "arm64", "database", version, directory]);
    const walk = async (root) => (await Promise.all((await readdir(root, { withFileTypes: true })).map(async (entry) => entry.isDirectory() ? walk(join(root, entry.name)) : [entry.name]))).flat();
    const names = await walk(directory);
    assert.equal(names.some((name) => name.startsWith("._") || name === ".DS_Store"), false);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
