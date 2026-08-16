#!/usr/bin/env node
import { readdir, readFile, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { join, resolve } from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";

const DIGEST = /^sha256:[0-9a-f]{64}$/;
const COMMIT = /^[0-9a-f]{40}$/;
const COMPONENTS = new Set(["bff", "web", "migration"]);

export function artifactFragment(component, image, digest, sbom, publicKey, publicKeySha256, signingKeyArn) {
  if (!COMPONENTS.has(component)) throw new Error("unsupported component");
  if (typeof image !== "string" || !image.endsWith(`/${component}`)) throw new Error("image repository does not match component");
  if (!DIGEST.test(digest)) throw new Error("invalid artifact digest");
  if (typeof sbom !== "string" || !sbom.endsWith(".spdx.json") || sbom.includes("..")) throw new Error("invalid SBOM path");
  if (publicKey !== `${component}.cosign.pub` || !/^sha256:[0-9a-f]{64}$/.test(publicKeySha256 || "")) throw new Error("invalid signing public key evidence");
  if (!/^arn:aws:kms:ap-northeast-1:[0-9]{12}:key\/[0-9a-f-]+$/.test(signingKeyArn || "")) throw new Error("invalid signing key ARN");
  return { name: component, image, digest, reference: `${image}@${digest}`, sbom, public_key: publicKey, public_key_sha256: publicKeySha256, signing_key_arn: signingKeyArn, signature: "cosign-aws-kms", provenance: "buildkit-slsa" };
}

export function buildManifest({ version, sourceCommit, runId, createdAt, artifacts }) {
  if (!/^v?[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$/.test(version)) throw new Error("invalid release version");
  if (!COMMIT.test(sourceCommit)) throw new Error("invalid source commit");
  if (!/^\d+$/.test(String(runId))) throw new Error("invalid workflow run ID");
  const names = new Set(artifacts.map(({ name }) => name));
  if (names.size !== COMPONENTS.size || [...COMPONENTS].some((name) => !names.has(name))) throw new Error("exactly bff, web, and migration artifacts are required");
  const sortedArtifacts = [...artifacts].sort((a, b) => a.name.localeCompare(b.name));
  const artifactSetDigest = `sha256:${createHash("sha256").update(JSON.stringify({ sourceCommit, artifacts: sortedArtifacts })).digest("hex")}`;
  return { schema_version: 1, version, source_commit: sourceCommit, artifact_set_digest: artifactSetDigest, workflow_run_id: String(runId), created_at: createdAt, artifacts: sortedArtifacts };
}

async function main(argv) {
  if (argv[0] === "fragment" && argv.length === 9) {
    const fragment = artifactFragment(argv[1], argv[2], argv[3], argv[4], argv[5], argv[6], argv[7]);
    await writeFile(argv[8], `${JSON.stringify(fragment, null, 2)}\n`, { flag: "wx" });
    return;
  }
  if (argv[0] === "bundle" && argv.length === 6) {
    const [directory, version, sourceCommit, runId, output] = argv.slice(1);
    const files = (await readdir(directory)).filter((name) => name.endsWith(".fragment.json"));
    const artifacts = await Promise.all(files.map(async (name) => JSON.parse(await readFile(join(directory, name), "utf8"))));
    const manifest = buildManifest({ version, sourceCommit, runId, createdAt: new Date().toISOString(), artifacts });
    await writeFile(output, `${JSON.stringify(manifest, null, 2)}\n`, { flag: "wx" });
    return;
  }
  throw new Error("usage: build-artifact-manifest.mjs fragment COMPONENT IMAGE DIGEST SBOM PUBLIC_KEY PUBLIC_KEY_SHA256 SIGNING_KEY_ARN OUTPUT | bundle DIRECTORY VERSION COMMIT RUN_ID OUTPUT");
}

if (import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main(process.argv.slice(2)).catch((error) => { console.error(error.message); process.exitCode = 2; });
}
