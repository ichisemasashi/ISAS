#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import process from "node:process";
import { pathToFileURL } from "node:url";

const PATTERNS = [
  { name: "unset Japanese value", expression: /`未設定`/g },
  { name: "replacement marker", expression: /replace-me/gi },
  { name: "invalid example host", expression: /example\.invalid/gi },
  { name: "generic adapter", expression: /<[A-Za-z0-9_-]+-adapter>/g },
  { name: "TODO or TBD", expression: /\b(?:TODO|TBD)\b/g },
];

export function findPlaceholders(text) {
  const matches = [];
  const lines = text.split(/\r?\n/);
  lines.forEach((line, index) => PATTERNS.forEach(({ name, expression }) => {
    expression.lastIndex = 0;
    if (expression.test(line)) matches.push({ line: index + 1, kind: name });
  }));
  return matches;
}

export async function main(argv = process.argv.slice(2)) {
  if (!argv.length) { console.error("usage: node check-runbook-placeholders.mjs <runbook>..."); return 2; }
  let total = 0;
  for (const path of argv) {
    const matches = findPlaceholders(await readFile(path, "utf8"));
    total += matches.length;
    matches.forEach((match) => console.error(`${path}:${match.line}: ${match.kind}`));
  }
  if (total) { console.error(`RUNBOOK PLACEHOLDERS: BLOCKED (${total})`); return 1; }
  console.log(`RUNBOOK PLACEHOLDERS: PASS (${argv.length} files)`);
  return 0;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main();
