#!/usr/bin/env node
import { readFileSync } from "node:fs";
import { validateDeploymentOperations } from "../apps/bff/src/deployment-operations.mjs";

const [file, deploymentId] = process.argv.slice(2);
if (!file || !deploymentId) { console.error("usage: check-deployment-operations.mjs LEDGER.json DEPLOYMENT_ID"); process.exit(64); }
try {
  const errors = validateDeploymentOperations(JSON.parse(readFileSync(file, "utf8")), deploymentId);
  if (errors.length) { errors.forEach((error) => console.error(`ERROR: ${error}`)); process.exit(1); }
  console.log("deployment operations ledger: PASS");
} catch (error) { console.error(`ERROR: ${error.message}`); process.exit(2); }
