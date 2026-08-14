import type { OutboxRecord } from "./storage";

export type TodayTask = { id: string; time: string; field: string; crop: string; work: string; status: "next" | "today" | "safety_check" | "completed" | "cancelled" };
export type FieldFeature = { type: "Feature"; id: string; geometry: { type: "MultiPolygon"; coordinates: number[][][][] }; properties: { id: string; fieldGroupId: string; name: string; cropName: string | null; status: "active" | "fallow" | "archived"; areaSqm: number; version: number } };
export type FieldCollection = { type: "FeatureCollection"; features: FieldFeature[]; nextCursor: string | null };
export type PushBundle = { bundleId: string; events: OutboxRecord[] };
export type PushResult = { bundleId: string; status: "accepted" | "duplicate" | "rejected" | "conflict"; events?: Array<{ eventUuid: string; eventTs: string }>; rejection?: { reason: string; recoveryAction: string } };
export type PullChange = { serverSeq: string; type: string; operation: "upsert" | "delete" | "revoke"; entityId?: string | null; eventUuid?: string | null; data: Record<string, unknown> };
export type PullResult = { changes: PullChange[]; nextCursor: string; snapshotUpper?: string; hasMore: boolean };
export type SyncRejection = { id: string; bundleId: string; eventUuids: string[]; reason: string; recoveryAction: string; createdAt: string };
export type SyncConflict = { id: string; documentId: string; eventUuid: string; baseVersion: number; currentVersion: number; currentValue: Record<string, unknown>; proposedValue: Record<string, unknown>; conflictingFields: string[]; status: string; createdAt: string };
export type PesticideAlert = { id: string; usageId: string; reasons: string[]; clientSafety: Record<string, unknown>; serverSafety: Record<string, unknown>; status: string; createdAt: string };
export type StockAlert = { id: string; chemicalId: string; name?: string; triggeringEventId: string; negativeQuantity: number; status: string; createdAt: string };
export type QueueSnapshot = { rejections: SyncRejection[]; conflicts: SyncConflict[]; pesticideAlerts?: PesticideAlert[]; stockAlerts?: StockAlert[] };
export type WorkInstruction = { id: string; fieldId: string; fieldGroupId: string; fieldName: string | null; cropName: string | null; title: string; workType: string; details: string; scheduledStart: string; scheduledEnd: string; priority: number; status: "issued" | "in_progress" | "completed" | "cancelled"; version: number; assignment: { id: string; assigneeUserId: string; version: number } | null };
export type JournalEntry = { id: string; instructionId: string | null; fieldId: string | null; fieldName: string | null; workerUserId: string; body: Record<string, unknown>; status: "submitted" | "approved" | "returned" | "corrected"; version: number; returnReason?: string | null; submittedAt: string; updatedAt: string; attachments: Array<{ id: string; fileName: string; contentType: string }> };
export type JournalBootstrap = { instruction: null | { id: string; fieldId: string; fieldGroupId: string; fieldName: string; workType: string; details: string; scheduledStart: string; scheduledEnd: string }; punchSuggestion: { startedAt: string | null; endedAt: string | null; warning: "missing_start" | "missing_finish" | null }; templates: Array<{ id: string; name: string; workType: string; defaults: Record<string, unknown>; version: number }>; previous: null | { id: string; instructionId?: string | null; fieldId?: string | null; fieldGroupId?: string | null; body: Record<string, unknown>; status?: JournalEntry["status"]; version: number; updatedAt: string } };
export type Agrochemical = { id: string; registrationNumber: string; name: string; activeIngredient: string; applicableCrops: string[]; dilutionMin: number; dilutionMax: number; maxUses: number; preharvestDays: number; revokedOn: string | null };
export type PesticideBootstrap = { field: { id: string; fieldGroupId: string; name: string; cropName: string | null; timezone: string }; release: null | { id: string; version: string; validUntil: string; publishedAt: string; syncedAt: string }; chemicals: Agrochemical[]; usage: Array<{ chemicalId: string; usageCount: number; lastAppliedOn: string | null }>; inventory: Array<{ chemicalId: string; quantity: number; updatedAt: string | null }> };
export type InventorySnapshot = { balances: Array<{ chemicalId: string; name: string; registrationNumber: string; quantity: number; updatedAt: string | null }>; alerts: StockAlert[] };
export type MigrationDataset = "fields" | "journals" | "pesticide_history";
export type MigrationRow = { lineNumber: number; status: "valid" | "duplicate" | "invalid" | "committed"; duplicateKey: string | null; errors: string[]; normalized: Record<string, unknown>; entityId?: string | null };
export type MigrationJob = { id: string; dataset: MigrationDataset; sourceName: string; sourceSha256: string; mapping: Record<string, string>; status: "needs_review" | "validated" | "committing" | "committed"; rowCount: number; validCount: number; duplicateCount: number; errorCount: number; version: number; createdAt: string; committedAt: string | null; rows?: MigrationRow[] };
export type ExportDataset = "fields" | "journals" | "pesticide-records";

export class ApiProblem extends Error { constructor(public status: number, public type: string, public body: Record<string, unknown>) { super(`${type} (${status})`); } }
export interface MvpGateway {
  getToday(contextId: string, signal?: AbortSignal): Promise<{ tasks: TodayTask[]; serverTime: string }>;
  getFields(contextId: string, search?: { bbox?: [number, number, number, number]; query?: string; limit?: number; cursor?: string | null }, signal?: AbortSignal): Promise<FieldCollection>;
  getWorkInstructions(contextId: string, signal?: AbortSignal): Promise<{ instructions: WorkInstruction[] }>;
  createWorkInstruction(contextId: string, csrfToken: string, input: Record<string, unknown>, signal?: AbortSignal): Promise<WorkInstruction>;
  reassignWorkInstruction(contextId: string, csrfToken: string, instructionId: string, input: { assigneeUserId: string; expectedVersion: number }, signal?: AbortSignal): Promise<{ id: string; assignmentId: string; assigneeUserId: string; version: number }>;
  getJournalBootstrap(contextId: string, search?: { instructionId?: string; fieldId?: string; journalId?: string }, signal?: AbortSignal): Promise<JournalBootstrap>;
  getPesticideBootstrap(contextId: string, fieldId: string, signal?: AbortSignal): Promise<PesticideBootstrap>;
  getInventory(contextId: string, signal?: AbortSignal): Promise<InventorySnapshot>;
  getJournals(contextId: string, signal?: AbortSignal): Promise<{ journals: JournalEntry[] }>;
  reviewJournal(contextId: string, csrfToken: string, journalId: string, input: { action: "approve" | "return"; expectedVersion: number; reason?: string }, signal?: AbortSignal): Promise<{ id: string; status: string; version: number; updatedAt: string }>;
  uploadJournalAttachment(contextId: string, csrfToken: string, attachment: { id: string; journalId: string; fileName: string; capturedAt: string; blob: Blob }, signal?: AbortSignal): Promise<{ id: string; journalId: string; byteSize: number; sha256: string }>;
  push(contextId: string, csrfToken: string, bundles: PushBundle[], signal?: AbortSignal): Promise<{ results: PushResult[] }>;
  pull(contextId: string, scope: string, priority: "priority" | "normal", cursor: string | null, signal?: AbortSignal): Promise<PullResult>;
  getQueues(contextId: string, signal?: AbortSignal): Promise<QueueSnapshot>;
  resolveConflict(contextId: string, csrfToken: string, conflictId: string, resolution: Record<string, unknown>, signal?: AbortSignal): Promise<Record<string, unknown>>;
  createMigrationJob(contextId: string, csrfToken: string, input: { dataset: MigrationDataset; sourceName: string; csv: string; mapping: Record<string, string> }, idempotencyKey: string, signal?: AbortSignal): Promise<MigrationJob>;
  getMigrationJobs(contextId: string, signal?: AbortSignal): Promise<{ jobs: MigrationJob[] }>;
  commitMigrationJob(contextId: string, csrfToken: string, jobId: string, expectedVersion: number, signal?: AbortSignal): Promise<MigrationJob>;
  exportCsv(contextId: string, dataset: ExportDataset, search?: { from?: string; to?: string }, signal?: AbortSignal): Promise<{ blob: Blob; fileName: string }>;
}
type FetchLike = typeof fetch;
async function result<T>(response: Response): Promise<T> { const body = await response.json() as Record<string, unknown>; if (!response.ok) throw new ApiProblem(response.status, typeof body.type === "string" ? body.type : "request_failed", body); return body as T; }
function headers(contextId: string, extra: Record<string, string> = {}) { return { Accept: "application/json", "X-ISAS-Context": contextId, ...extra }; }

export function createMvpGateway(fetcher: FetchLike = fetch): MvpGateway {
  return {
    getToday: (contextId, signal) => fetcher("/api/v1/today", { credentials: "include", cache: "no-store", headers: headers(contextId), signal }).then((response) => result<{ tasks: TodayTask[]; serverTime: string }>(response)),
    getFields: (contextId, search = {}, signal) => {
      const query = new URLSearchParams();
      if (search.bbox) query.set("bbox", search.bbox.join(","));
      if (search.query) query.set("q", search.query);
      if (search.limit) query.set("limit", String(search.limit));
      if (search.cursor) query.set("cursor", search.cursor);
      const suffix = query.size ? `?${query}` : "";
      return fetcher(`/api/v1/fields${suffix}`, { credentials: "include", cache: "no-store", headers: headers(contextId), signal }).then((response) => result<FieldCollection>(response));
    },
    getWorkInstructions: (contextId, signal) => fetcher("/api/v1/work-instructions", { credentials: "include", cache: "no-store", headers: headers(contextId), signal }).then((response) => result<{ instructions: WorkInstruction[] }>(response)),
    createWorkInstruction: (contextId, csrfToken, input, signal) => fetcher("/api/v1/work-instructions", { method: "POST", credentials: "include", cache: "no-store", headers: headers(contextId, { "Content-Type": "application/json", "X-CSRF-Token": csrfToken }), body: JSON.stringify(input), signal }).then((response) => result<WorkInstruction>(response)),
    reassignWorkInstruction: (contextId, csrfToken, instructionId, input, signal) => fetcher(`/api/v1/work-instructions/${encodeURIComponent(instructionId)}/assignment`, { method: "PATCH", credentials: "include", cache: "no-store", headers: headers(contextId, { "Content-Type": "application/json", "X-CSRF-Token": csrfToken }), body: JSON.stringify(input), signal }).then((response) => result<{ id: string; assignmentId: string; assigneeUserId: string; version: number }>(response)),
    getJournalBootstrap: (contextId, search = {}, signal) => { const query = new URLSearchParams(); if (search.instructionId) query.set("instructionId", search.instructionId); if (search.fieldId) query.set("fieldId", search.fieldId); if (search.journalId) query.set("journalId", search.journalId); const suffix = query.size ? `?${query}` : ""; return fetcher(`/api/v1/journal-bootstrap${suffix}`, { credentials: "include", cache: "no-store", headers: headers(contextId), signal }).then((response) => result<JournalBootstrap>(response)); },
    getPesticideBootstrap: (contextId, fieldId, signal) => fetcher(`/api/v1/pesticide-bootstrap?fieldId=${encodeURIComponent(fieldId)}`, { credentials: "include", cache: "no-store", headers: headers(contextId), signal }).then((response) => result<PesticideBootstrap>(response)),
    getInventory: (contextId, signal) => fetcher("/api/v1/inventory", { credentials: "include", cache: "no-store", headers: headers(contextId), signal }).then((response) => result<InventorySnapshot>(response)),
    getJournals: (contextId, signal) => fetcher("/api/v1/journals", { credentials: "include", cache: "no-store", headers: headers(contextId), signal }).then((response) => result<{ journals: JournalEntry[] }>(response)),
    reviewJournal: (contextId, csrfToken, journalId, input, signal) => fetcher(`/api/v1/journals/${encodeURIComponent(journalId)}/review`, { method: "POST", credentials: "include", cache: "no-store", headers: headers(contextId, { "Content-Type": "application/json", "X-CSRF-Token": csrfToken }), body: JSON.stringify(input), signal }).then((response) => result<{ id: string; status: string; version: number; updatedAt: string }>(response)),
    uploadJournalAttachment: (contextId, csrfToken, attachment, signal) => fetcher("/api/v1/journal-attachments", { method: "POST", credentials: "include", cache: "no-store", headers: headers(contextId, { "Content-Type": attachment.blob.type, "X-CSRF-Token": csrfToken, "X-Attachment-ID": attachment.id, "X-Journal-ID": attachment.journalId, "X-File-Name": encodeURIComponent(attachment.fileName), "X-Captured-At": attachment.capturedAt }), body: attachment.blob, signal }).then((response) => result<{ id: string; journalId: string; byteSize: number; sha256: string }>(response)),
    push: (contextId, csrfToken, bundles, signal) => fetcher("/api/v1/sync/push", { method: "POST", credentials: "include", cache: "no-store", headers: headers(contextId, { "Content-Type": "application/json", "X-CSRF-Token": csrfToken }), body: JSON.stringify({ bundles }), signal }).then((response) => result<{ results: PushResult[] }>(response)),
    pull: (contextId, scope, priority, cursor, signal) => { const query = new URLSearchParams({ scope, priority }); if (cursor) query.set("cursor", cursor); return fetcher(`/api/v1/sync/pull?${query}`, { credentials: "include", cache: "no-store", headers: headers(contextId), signal }).then((response) => result<PullResult>(response)); },
    getQueues: (contextId, signal) => fetcher("/api/v1/sync/queues", { credentials: "include", cache: "no-store", headers: headers(contextId), signal }).then((response) => result<QueueSnapshot>(response)),
    resolveConflict: (contextId, csrfToken, conflictId, resolution, signal) => fetcher(`/api/v1/sync/conflicts/${encodeURIComponent(conflictId)}/resolve`, { method: "POST", credentials: "include", cache: "no-store", headers: headers(contextId, { "Content-Type": "application/json", "X-CSRF-Token": csrfToken }), body: JSON.stringify({ resolution }), signal }).then((response) => result<Record<string, unknown>>(response)),
    createMigrationJob: (contextId, csrfToken, input, idempotencyKey, signal) => fetcher("/api/v1/migration-jobs", { method: "POST", credentials: "include", cache: "no-store", headers: headers(contextId, { "Content-Type": "application/json", "X-CSRF-Token": csrfToken, "Idempotency-Key": idempotencyKey }), body: JSON.stringify(input), signal }).then((response) => result<MigrationJob>(response)),
    getMigrationJobs: (contextId, signal) => fetcher("/api/v1/migration-jobs", { credentials: "include", cache: "no-store", headers: headers(contextId), signal }).then((response) => result<{ jobs: MigrationJob[] }>(response)),
    commitMigrationJob: (contextId, csrfToken, jobId, expectedVersion, signal) => fetcher(`/api/v1/migration-jobs/${encodeURIComponent(jobId)}/commit`, { method: "POST", credentials: "include", cache: "no-store", headers: headers(contextId, { "Content-Type": "application/json", "X-CSRF-Token": csrfToken }), body: JSON.stringify({ expectedVersion }), signal }).then((response) => result<MigrationJob>(response)),
    exportCsv: async (contextId, dataset, search = {}, signal) => {
      const query = new URLSearchParams();
      if (search.from) query.set("from", search.from);
      if (search.to) query.set("to", search.to);
      const response = await fetcher(`/api/v1/exports/${dataset}.csv${query.size ? `?${query}` : ""}`, { credentials: "include", cache: "no-store", headers: headers(contextId, { Accept: "text/csv" }), signal });
      if (!response.ok) return result<never>(response);
      const disposition = response.headers.get("Content-Disposition") || "";
      const matched = disposition.match(/filename="([^"]+)"/i);
      return { blob: await response.blob(), fileName: matched?.[1] || `${dataset}.csv` };
    },
  };
}
