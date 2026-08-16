#!/usr/bin/env node

import { readFile, writeFile } from "node:fs/promises";
import process from "node:process";
import { pathToFileURL } from "node:url";

import { validateReleaseManifest } from "./check-release-readiness.mjs";

export function assembleReleaseManifest(candidate, now = new Date()) {
  const manifest = structuredClone(candidate);
  manifest.release = { ...(manifest.release ?? {}), status: "READY" };
  const errors = validateReleaseManifest(manifest, now);
  if (errors.length) return { errors, manifest: null };
  return { errors: [], manifest };
}

export async function main(argv = process.argv.slice(2)) {
  if (argv.length !== 2) {
    console.error("usage: node ops/assemble-release-manifest.mjs <candidate.json> <release-manifest.json>");
    return 2;
  }
  try {
    const candidate = JSON.parse(await readFile(argv[0], "utf8"));
    const result = assembleReleaseManifest(candidate);
    if (result.errors.length) {
      console.error(`release manifest assembly: BLOCKED (${result.errors.length})`);
      result.errors.forEach((error) => console.error(`- ${error}`));
      return 1;
    }
    await writeFile(argv[1], `${JSON.stringify(result.manifest, null, 2)}\n`, { flag: "wx", mode: 0o600 });
    console.log(`release manifest assembly: READY ${result.manifest.release.version}`);
    return 0;
  } catch (error) {
    console.error(`release manifest assembly: FAIL\n- ${error.message}`);
    return 2;
  }
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main();
