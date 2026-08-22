import assert from "node:assert/strict";
import test from "node:test";
import { createDeletionCertificate, createVendorExitBundle, PORTABILITY_DATASETS, restoreVendorExitBundle, verifyVendorExitBundle } from "../src/portability.mjs";

function source() {
  return { async withFrozenTenantSnapshot(_tenant, callback) { return callback({ id: "snapshot-1", at: "2026-08-22T00:00:00Z",
    async readDataset(name) { return name === "tenant" ? [{ id: "tenant-1", name: "Tenant" }] : []; },
    async listAttachments() { return [{ attachmentId: "attachment-1", objectKey: "attachments/tenant-1/a" }]; },
    async thirdPartyExclusions() { return [{ provider: "provider", reason: "redistribution prohibited" }]; },
  }); } };
}
const objects = { async readForExport() { return { bytes: Buffer.from("photo"), contentType: "image/jpeg" }; } };

test("exports every dataset and object from one frozen snapshot, then restores and validates an empty target", async () => {
  const bundle = await createVendorExitBundle({ source: source(), objectStorage: objects, tenantId: "tenant-1", sourceRelease: "1.2.3", migrationVersion: "0018" });
  assert.deepEqual(verifyVendorExitBundle(bundle), []);
  assert.equal(bundle.manifest.files.filter(({ mediaType }) => mediaType === "application/x-ndjson").length, PORTABILITY_DATASETS.length);
  const imported = [];
  const result = await restoreVendorExitBundle({ bundle, target: { async assertEmpty() {}, async beginRestore() { return {
    async importDataset(name, rows) { imported.push([name, rows.length]); }, async putAttachment() {},
    async validateRestore() { return { counts: "PASS", hashes: "PASS", references: "PASS", rls: "PASS", attachmentDownloads: "PASS", auditChain: "PASS" }; },
    async commit() { return { targetDeploymentId: "empty-isas" }; }, async rollback() { throw new Error("unexpected rollback"); },
  }; } } });
  assert.equal(result.status, "PASS"); assert.equal(imported.length, PORTABILITY_DATASETS.length);
});

test("rejects object tampering", async () => {
  const bundle = await createVendorExitBundle({ source: source(), objectStorage: objects, tenantId: "tenant-1", sourceRelease: "1.2.3", migrationVersion: "0018" });
  bundle.files["objects/attachment-1"] = Buffer.from("changed");
  assert.ok(verifyVendorExitBundle(bundle).some((error) => error.includes("hash")));
});

test("rolls back when an empty-target restore fails RLS validation", async () => {
  const bundle = await createVendorExitBundle({ source: source(), objectStorage: objects, tenantId: "tenant-1", sourceRelease: "1.2.3", migrationVersion: "0018" });
  let rolledBack = false;
  await assert.rejects(restoreVendorExitBundle({ bundle, target: { async assertEmpty() {}, async beginRestore() { return {
    async importDataset() {}, async putAttachment() {}, async validateRestore() { return { counts: "PASS", hashes: "PASS", references: "PASS", rls: "FAIL", attachmentDownloads: "PASS", auditChain: "PASS" }; },
    async commit() { throw new Error("must not commit"); }, async rollback() { rolledBack = true; },
  }; } } }), /rls/);
  assert.equal(rolledBack, true);
});

test("issues deletion proof only after successful restore and two-person approval", () => {
  const certificate = createDeletionCertificate({ tenantId: "tenant-1", restoreEvidence: { status: "PASS" }, legalHold: false, deletionMethod: "crypto-erase", deletedAt: "2026-08-22", backupExpiryAt: "2026-09-22", approvals: [
    { actor: "operator", role: "deletion_operator" }, { actor: "verifier", role: "independent_verifier" },
  ] });
  assert.equal(certificate.schemaVersion, 1);
  assert.throws(() => createDeletionCertificate({ ...certificate, approvals: [{ actor: "same", role: "deletion_operator" }, { actor: "same", role: "independent_verifier" }] }), /two-person/);
});
