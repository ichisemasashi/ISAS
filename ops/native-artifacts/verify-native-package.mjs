#!/usr/bin/env node

import { readFile, writeFile } from "node:fs/promises";
import process from "node:process";

const [recordFile, expectedDigest, signatureVerified, installVerified] = process.argv.slice(2);
if (!recordFile || !/^[0-9a-f]{64}$/.test(expectedDigest || "") || ![signatureVerified, installVerified].every((value) => value === "true")) process.exit(64);
const record = JSON.parse(await readFile(recordFile, "utf8"));
if (record.digest !== `sha256:${expectedDigest}`) throw new Error("record digest does not match verified package");
record.signature_verified = true;
record.install_verified = true;
await writeFile(recordFile, `${JSON.stringify(record, null, 2)}\n`, { mode: 0o444 });
