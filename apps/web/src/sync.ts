import { ApiProblem, type MvpGateway, type QueueSnapshot } from "./api";
import type { AppAuthorization } from "./auth";
import type { OutboxRecord, StorageGateway } from "./storage";

export type SyncSummary = { accepted: number; rejected: number; conflicts: number; pending: number; queues: QueueSnapshot; reauthenticationRequired: boolean };
function bundles(records: OutboxRecord[]) { const grouped = new Map<string, OutboxRecord[]>(); for (const record of records) grouped.set(record.bundleId, [...(grouped.get(record.bundleId) || []), record]); return [...grouped].map(([bundleId, events]) => ({ bundleId, events })); }
async function pullChannel(api: MvpGateway, storage: StorageGateway, authorization: AppAuthorization, priority: "priority" | "normal", signal?: AbortSignal) {
  const tenantId = authorization.context.tenantId; const scope = "tenant"; const cursor = await storage.getCursor(tenantId, scope, priority);
  try {
    let next = cursor;
    do { const page = await api.pull(authorization.context.contextId, scope, priority, next, signal); await storage.applyChanges(tenantId, scope, page.changes); await storage.setCursor(tenantId, scope, priority, page.nextCursor); next = page.nextCursor; if (!page.hasMore) break; } while (!signal?.aborted);
  } catch (error) { if (error instanceof ApiProblem && error.type === "scope_revoked") await storage.purgeScope(tenantId, scope); throw error; }
}
export async function synchronize({ api, storage, authorization, csrfToken, signal }: { api: MvpGateway; storage: StorageGateway; authorization: AppAuthorization; csrfToken: string; signal?: AbortSignal }): Promise<SyncSummary> {
  const tenantId = authorization.context.tenantId; let accepted = 0; let rejected = 0; let conflicts = 0;
  try {
    await pullChannel(api, storage, authorization, "priority", signal);
    const records = await storage.listOutbox(tenantId);
    if (records.length) {
      const response = await api.push(authorization.context.contextId, csrfToken, bundles(records), signal);
      for (const item of response.results) {
        const grouped = records.filter((record) => record.bundleId === item.bundleId);
        if (item.status === "accepted" || item.status === "duplicate") { await storage.acknowledge(grouped.map((record) => record.eventUuid)); accepted += grouped.length; }
        else if (item.status === "rejected") { await storage.quarantine(grouped, "rejections", item.rejection?.reason || "rejected", item.rejection?.recoveryAction || "manager_review"); rejected += grouped.length; }
        else { await storage.quarantine(grouped, "conflicts", "optimistic_lock_conflict", "manager_resolution"); conflicts += grouped.length; }
      }
    }
    await pullChannel(api, storage, authorization, "normal", signal);
    const queues = await api.getQueues(authorization.context.contextId, signal); await storage.saveServerQueues(tenantId, queues);
    return { accepted, rejected, conflicts, pending: await storage.pendingCount(tenantId), queues, reauthenticationRequired: false };
  } catch (error) {
    if (error instanceof ApiProblem && (error.status === 401 || error.type === "scope_revoked")) return { accepted, rejected, conflicts, pending: await storage.pendingCount(tenantId), queues: { rejections: [], conflicts: [] }, reauthenticationRequired: true };
    throw error;
  }
}
