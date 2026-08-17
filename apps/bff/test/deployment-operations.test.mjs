import assert from "node:assert/strict";
import test from "node:test";
import { validateDeploymentOperations } from "../src/deployment-operations.mjs";

const valid = () => ({ schemaVersion: 1, deploymentId: "isas-jp-prod-01",
  support: { timezone: "Asia/Tokyo", hours: "Mon-Fri 09:00-17:00" },
  severity: Object.fromEntries(["sev1", "sev2", "sev3", "sev4"].map((key, index) => [key, { responseMinutes: [15, 60, 240, 1440][index], definition: `${key} impact definition` }])),
  contacts: Object.fromEntries(["serviceOwner", "onCall", "security", "vulnerability", "privacy"].map((key) => [key, { owner: `${key}-team`, route: `mailto:${key}@isas.invalid` }])),
  raci: { serviceAccountable: "service-owner", runtimeResponsible: "runtime-team", databaseResponsible: "database-team", securityResponsible: "security-team", backupResponsible: "backup-team", releaseResponsible: "release-team" },
  lifecycle: { productVersion: "1.2.3", supportEndsOn: "2027-08-17", migrationNoticeDays: 180 } });

test("accepts a complete deployment-specific operations ledger", () => assert.deepEqual(validateDeploymentOperations(valid(), "isas-jp-prod-01"), []));
test("rejects blank contacts and deployment substitution", () => {
  const value = valid(); value.contacts.security.owner = "未設定";
  const errors = validateDeploymentOperations(value, "different-deployment").join("\n");
  assert.match(errors, /deploymentId must match/); assert.match(errors, /contacts.security.owner/);
});
