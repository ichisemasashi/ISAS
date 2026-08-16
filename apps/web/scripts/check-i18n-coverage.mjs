#!/usr/bin/env node

import { readdir, readFile } from "node:fs/promises";
import { extname, join, relative, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";

const TRANSLATABLE = /[\u3040-\u30ff\u3400-\u9fff]/u;
const L1_L2_PREFIXES = ["app.", "authboundary.", "fieldspage.", "locationtrackingpanel.", "main.", "pesticide_safety.", "pwa_update.", "schedulepage.", "tenantanalyticspanel."];
const PLACEHOLDER = /\{\d+\}/gu;

export function findHardcodedText(source) {
  return source.split(/\r?\n/u).flatMap((line, index) => TRANSLATABLE.test(line) && !line.includes("i18n-ignore") ? [{ line: index + 1, text: line.trim().slice(0, 240) }] : []);
}

async function sourceFiles(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) files.push(...await sourceFiles(path));
    else if ([".ts", ".tsx"].includes(extname(entry.name)) && !/\.test\.|\.d\.ts$|^\._/u.test(entry.name) && entry.name !== "i18n.ts") files.push(path);
  }
  return files;
}

export async function reviewCoverage(root) {
  const findings = [];
  for (const file of await sourceFiles(root)) {
    const matches = findHardcodedText(await readFile(file, "utf8"));
    if (matches.length) findings.push({ file: relative(root, file), matches });
  }
  const localeRoot = join(root, "locales");
  const locales = Object.fromEntries(await Promise.all(["ja", "en", "ar-XB"].map(async (locale) => [locale, JSON.parse(await readFile(join(localeRoot, `${locale}.json`), "utf8"))])));
  const referenceKeys = Object.keys(locales.ja).sort();
  const catalogErrors = [];
  for (const [locale, catalog] of Object.entries(locales)) {
    const keys = Object.keys(catalog).sort();
    if (JSON.stringify(keys) !== JSON.stringify(referenceKeys)) catalogErrors.push(`${locale}: catalog key set differs from ja`);
    for (const key of referenceKeys) {
      if (typeof catalog[key] !== "string") catalogErrors.push(`${locale}:${key}: message is missing`);
      const expected = [...(locales.ja[key]?.matchAll(PLACEHOLDER) || [])].map(([value]) => value).sort();
      const actual = [...(catalog[key]?.matchAll(PLACEHOLDER) || [])].map(([value]) => value).sort();
      if (JSON.stringify(actual) !== JSON.stringify(expected)) catalogErrors.push(`${locale}:${key}: placeholders differ from ja`);
    }
  }
  const untranslatedL1L2 = referenceKeys.filter((key) => L1_L2_PREFIXES.some((prefix) => key.startsWith(prefix)) && TRANSLATABLE.test(locales.en[key]));
  if (untranslatedL1L2.length) catalogErrors.push(`en: ${untranslatedL1L2.length} L1-L2 messages still contain Japanese text`);
  return {
    status: findings.length || catalogErrors.length ? "BLOCKED" : "PASS",
    files_with_findings: findings.length,
    lines_with_findings: findings.reduce((sum, item) => sum + item.matches.length, 0),
    catalog_keys: referenceKeys.length,
    untranslated_l1_l2: untranslatedL1L2.length,
    catalog_errors: catalogErrors,
    findings,
  };
}

async function main(argv) {
  const reportOnly = argv.includes("--report-only");
  const rootArgument = argv.find((value) => value !== "--report-only");
  const root = resolve(rootArgument || fileURLToPath(new URL("../src", import.meta.url)));
  const report = await reviewCoverage(root);
  console.log(JSON.stringify(report, null, 2));
  return report.status === "PASS" || reportOnly ? 0 : 1;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) process.exitCode = await main(process.argv.slice(2));
