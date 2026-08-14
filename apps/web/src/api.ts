import type { OutboxRecord } from "./storage";

export type TodayTask = { id: string; time: string; field: string; crop: string; work: string; status: "next" | "today" | "safety_check" | "completed" | "cancelled" };
export type PushBundle = { bundleId: string; events: OutboxRecord[] };
export type PushResult = { bundleId: string; status: "accepted" | "duplicate" | "rejected" | "conflict"; events?: Array<{ eventUuid: string; eventTs: string }>; rejection?: { reason: string; recoveryAction: string } };
export type PullChange = { serverSeq: string; type: string; operation: "upsert" | "delete" | "revoke"; entityId?: string | null; eventUuid?: string | null; data: Record<string, unknown> };
export type PullResult = { changes: PullChange[]; nextCursor: string; snapshotUpper?: string; hasMore: boolean };
export type SyncRejection = { id: string; bundleId: string; eventUuids: string[]; reason: string; recoveryAction: string; createdAt: string };
export type SyncConflict = { id: string; documentId: string; eventUuid: string; baseVersion: number; currentVersion: number; currentValue: Record<string, unknown>; proposedValue: Record<string, unknown>; conflictingFields: string[]; status: string; createdAt: string };
export type QueueSnapshot = { rejections: SyncRejection[]; conflicts: SyncConflict[] };

export class ApiProblem extends Error { constructor(public status: number, public type: string, public body: Record<string, unknown>) { super(`${type} (${status})`); } }
export interface MvpGateway {
  getToday(contextId: string, signal?: AbortSignal): Promise<{ tasks: TodayTask[]; serverTime: string }>;
  push(contextId: string, csrfToken: string, bundles: PushBundle[], signal?: AbortSignal): Promise<{ results: PushResult[] }>;
  pull(contextId: string, scope: string, priority: "priority" | "normal", cursor: string | null, signal?: AbortSignal): Promise<PullResult>;
  getQueues(contextId: string, signal?: AbortSignal): Promise<QueueSnapshot>;
  resolveConflict(contextId: string, csrfToken: string, conflictId: string, resolution: Record<string, unknown>, signal?: AbortSignal): Promise<Record<string, unknown>>;
}
type FetchLike = typeof fetch;
async function result<T>(response: Response): Promise<T> { const body = await response.json() as Record<string, unknown>; if (!response.ok) throw new ApiProblem(response.status, typeof body.type === "string" ? body.type : "request_failed", body); return body as T; }
function headers(contextId: string, extra: Record<string, string> = {}) { return { Accept: "application/json", "X-ISAS-Context": contextId, ...extra }; }

export function createMvpGateway(fetcher: FetchLike = fetch): MvpGateway {
  return {
    getToday: (contextId, signal) => fetcher("/api/v1/today", { credentials: "include", cache: "no-store", headers: headers(contextId), signal }).then((response) => result<{ tasks: TodayTask[]; serverTime: string }>(response)),
    push: (contextId, csrfToken, bundles, signal) => fetcher("/api/v1/sync/push", { method: "POST", credentials: "include", cache: "no-store", headers: headers(contextId, { "Content-Type": "application/json", "X-CSRF-Token": csrfToken }), body: JSON.stringify({ bundles }), signal }).then((response) => result<{ results: PushResult[] }>(response)),
    pull: (contextId, scope, priority, cursor, signal) => { const query = new URLSearchParams({ scope, priority }); if (cursor) query.set("cursor", cursor); return fetcher(`/api/v1/sync/pull?${query}`, { credentials: "include", cache: "no-store", headers: headers(contextId), signal }).then((response) => result<PullResult>(response)); },
    getQueues: (contextId, signal) => fetcher("/api/v1/sync/queues", { credentials: "include", cache: "no-store", headers: headers(contextId), signal }).then((response) => result<QueueSnapshot>(response)),
    resolveConflict: (contextId, csrfToken, conflictId, resolution, signal) => fetcher(`/api/v1/sync/conflicts/${encodeURIComponent(conflictId)}/resolve`, { method: "POST", credentials: "include", cache: "no-store", headers: headers(contextId, { "Content-Type": "application/json", "X-CSRF-Token": csrfToken }), body: JSON.stringify({ resolution }), signal }).then((response) => result<Record<string, unknown>>(response)),
  };
}
