#!/usr/bin/env node

import { readFile } from "node:fs/promises";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";

const PHYSICAL_DIRECTION = /(?:^|[;{\s])(?:left|right|margin-left|margin-right|padding-left|padding-right|border-left|border-right)\s*:|text-align\s*:\s*(?:left|right)/u;

export function findPhysicalDirections(css) {
  return css.split(/\r?\n/u).flatMap((line, index) => PHYSICAL_DIRECTION.test(line) ? [{ line: index + 1, text: line.trim() }] : []);
}

async function main(argv) {
  const path = argv[0] || fileURLToPath(new URL("../src/styles.css", import.meta.url));
  const findings = findPhysicalDirections(await readFile(path, "utf8"));
  if (findings.length) {
    console.error(JSON.stringify({ status: "BLOCKED", findings }, null, 2));
    return 1;
  }
  console.log("RTL CSS logical-direction gate: PASS");
  return 0;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main(process.argv.slice(2));
