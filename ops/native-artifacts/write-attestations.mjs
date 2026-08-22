#!/usr/bin/env node

import { readFile, writeFile } from "node:fs/promises";
import { basename } from "node:path";
import process from "node:process";

const [artifact, hostOs, architecture, service, version, sha256] = process.argv.slice(2);
if (![artifact, hostOs, architecture, service, version, sha256].every(Boolean) || !/^[0-9a-f]{64}$/.test(sha256)) process.exit(64);
const bytes = (await readFile(artifact)).byteLength;
const createdAt = new Date().toISOString();
const sbom = {
  spdxVersion: "SPDX-2.3", dataLicense: "CC0-1.0", SPDXID: "SPDXRef-DOCUMENT",
  name: basename(artifact), documentNamespace: `https://isas.invalid/spdx/${sha256}`,
  creationInfo: { created: createdAt, creators: ["Tool: ISAS-native-artifact-builder"] },
  packages: [{ name: `isas-${service}`, SPDXID: `SPDXRef-Package-${service.replaceAll("-", "")}`, versionInfo: version, downloadLocation: "NOASSERTION", filesAnalyzed: false, checksums: [{ algorithm: "SHA256", checksumValue: sha256 }] }],
};
const provenance = {
  _type: "https://in-toto.io/Statement/v1", subject: [{ name: basename(artifact), digest: { sha256 } }],
  predicateType: "https://slsa.dev/provenance/v1", predicate: {
    buildDefinition: { buildType: "https://isas.invalid/build/native-package/v1", externalParameters: { host_os: hostOs, architecture, service, version }, resolvedDependencies: [] },
    runDetails: { builder: { id: "https://github.com/ISAS/native-release" }, metadata: { invocationId: process.env.GITHUB_RUN_ID || "local-untrusted", startedOn: createdAt, finishedOn: createdAt } },
  },
};
await writeFile(`${artifact}.sbom.spdx.json`, `${JSON.stringify(sbom, null, 2)}\n`, { flag: "wx", mode: 0o444 });
await writeFile(`${artifact}.provenance.json`, `${JSON.stringify(provenance, null, 2)}\n`, { flag: "wx", mode: 0o444 });
await writeFile(`${artifact}.record.json`, `${JSON.stringify({ schema_version: 1, host_os: hostOs, architecture, service, version, artifact: basename(artifact), bytes, digest: `sha256:${sha256}`, checksum: `${basename(artifact)}.sha256`, signature: `${basename(artifact)}.sig`, sbom: `${basename(artifact)}.sbom.spdx.json`, provenance: `${basename(artifact)}.provenance.json`, signature_verified: false, install_verified: false }, null, 2)}\n`, { flag: "wx", mode: 0o600 });
