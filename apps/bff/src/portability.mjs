import { createHash } from "node:crypto";

export const PORTABILITY_DATASETS = Object.freeze([
  "tenant", "users", "roles", "memberships", "field_group_scopes", "revocation_events",
  "fields", "growing_seasons", "crop_plans", "work_dependencies", "resources", "resource_allocations",
  "work_instructions", "work_assignments", "work_punches", "journals", "journal_revisions",
  "pesticide_master_releases", "pesticide_usages", "stock_events", "stock_lots", "inventory_counts",
  "location_consents", "location_preferences", "location_points", "location_access_audit",
  "domain_events", "phase2_change_audit", "auth_change_audit", "attachments",
]);

const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");
const canonical = (value) => Array.isArray(value) ? `[${value.map(canonical).join(",")}]`
  : value && typeof value === "object" ? `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(",")}}`
    : JSON.stringify(value);
const ndjson = (rows) => Buffer.from(rows.map((row) => canonical(row)).join("\n") + (rows.length ? "\n" : ""));

export function verifyVendorExitBundle(bundle) {
  const errors = [];
  if (bundle?.manifest?.schemaVersion !== 1) return ["manifest schemaVersion must be 1"];
  const files = bundle.files ?? {};
  for (const entry of bundle.manifest.files ?? []) {
    const bytes = files[entry.path];
    if (!Buffer.isBuffer(bytes)) { errors.push(`missing file: ${entry.path}`); continue; }
    if (bytes.length !== entry.bytes || sha256(bytes) !== entry.sha256) errors.push(`file hash or size mismatch: ${entry.path}`);
    if (entry.mediaType === "application/x-ndjson") {
      const count = bytes.length ? bytes.toString("utf8").trimEnd().split("\n").length : 0;
      if (count !== entry.records) errors.push(`record count mismatch: ${entry.path}`);
    }
  }
  const declared = new Set((bundle.manifest.files ?? []).map(({ path }) => path));
  for (const path of Object.keys(files)) if (!declared.has(path)) errors.push(`undeclared file: ${path}`);
  for (const attachment of bundle.manifest.attachments ?? []) {
    if (!declared.has(attachment.path) || !attachment.objectKey || !attachment.attachmentId) errors.push(`invalid attachment mapping: ${attachment.path}`);
  }
  return errors;
}

export async function createVendorExitBundle({ source, objectStorage, tenantId, sourceRelease, migrationVersion, exportedAt = new Date().toISOString() }) {
  if (!source || !objectStorage || !tenantId || !sourceRelease || !migrationVersion) throw new TypeError("complete export identity is required");
  return source.withFrozenTenantSnapshot(tenantId, async (snapshot) => {
    const files = {};
    const entries = [];
    for (const dataset of PORTABILITY_DATASETS) {
      const rows = await snapshot.readDataset(dataset);
      if (!Array.isArray(rows)) throw new TypeError(`dataset ${dataset} must be an array`);
      const path = `datasets/${dataset}.ndjson`;
      const bytes = ndjson(rows);
      files[path] = bytes;
      entries.push({ path, mediaType: "application/x-ndjson", records: rows.length, bytes: bytes.length, sha256: sha256(bytes) });
    }
    const attachments = [];
    for (const record of await snapshot.listAttachments()) {
      const object = await objectStorage.readForExport({ tenantId, objectKey: record.objectKey, snapshotId: snapshot.id });
      const path = `objects/${record.attachmentId}`;
      files[path] = Buffer.from(object.bytes);
      entries.push({ path, mediaType: object.contentType, records: 1, bytes: files[path].length, sha256: sha256(files[path]) });
      attachments.push({ attachmentId: record.attachmentId, objectKey: record.objectKey, path, contentType: object.contentType });
    }
    const manifest = { schemaVersion: 1, tenantId, snapshotId: snapshot.id, snapshotAt: snapshot.at, exportedAt, sourceRelease, migrationVersion,
      files: entries, attachments, thirdPartyExclusions: await snapshot.thirdPartyExclusions() };
    return { manifest, files };
  });
}

export async function restoreVendorExitBundle({ bundle, target }) {
  const errors = verifyVendorExitBundle(bundle);
  if (errors.length) throw new Error(`bundle verification failed: ${errors.join("; ")}`);
  await target.assertEmpty();
  const transaction = await target.beginRestore(bundle.manifest);
  try {
    for (const dataset of PORTABILITY_DATASETS) {
      const bytes = bundle.files[`datasets/${dataset}.ndjson`];
      const rows = bytes.length ? bytes.toString("utf8").trimEnd().split("\n").map((line) => JSON.parse(line)) : [];
      await transaction.importDataset(dataset, rows);
    }
    for (const attachment of bundle.manifest.attachments) await transaction.putAttachment(attachment, bundle.files[attachment.path]);
    const checks = await transaction.validateRestore({ tenantId: bundle.manifest.tenantId, expectedFiles: bundle.manifest.files });
    for (const name of ["counts", "hashes", "references", "rls", "attachmentDownloads", "auditChain"]) if (checks?.[name] !== "PASS") throw new Error(`restore validation failed: ${name}`);
    const result = await transaction.commit();
    return { status: "PASS", sourceManifestSha256: sha256(Buffer.from(canonical(bundle.manifest))), checks, ...result };
  } catch (error) { await transaction.rollback(); throw error; }
}

export function createDeletionCertificate({ tenantId, restoreEvidence, legalHold, deletionMethod, deletedAt, backupExpiryAt, approvals }) {
  if (restoreEvidence?.status !== "PASS" || legalHold !== false || !deletionMethod || !Number.isFinite(Date.parse(deletedAt)) || !Number.isFinite(Date.parse(backupExpiryAt)) || Date.parse(backupExpiryAt) < Date.parse(deletedAt)) throw new TypeError("deletion certificate prerequisites are not met");
  const actors = new Set((approvals ?? []).map(({ actor }) => actor).filter(Boolean));
  const roles = new Set((approvals ?? []).map(({ role }) => role));
  if (actors.size < 2 || !roles.has("deletion_operator") || !roles.has("independent_verifier")) throw new TypeError("two-person deletion approval is required");
  return { schemaVersion: 1, tenantId, restoreEvidence, legalHold, deletionMethod, deletedAt, backupExpiryAt, approvals };
}
