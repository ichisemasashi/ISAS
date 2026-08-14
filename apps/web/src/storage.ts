import type { FieldFeature, PullChange, QueueSnapshot, TodayTask } from "./api";

export type JournalDraft = { id: string; aggregateId: string; baseVersion: number; baseValue: Record<string, unknown>; field: string; workType: string; startedAt: string; endedAt: string; memo: string; updatedAt: string };
export type OutboxRecord = {
  eventUuid: string; bundleId: string; kind: "journal" | "pesticide" | "punch"; payload: Record<string, unknown>;
  createdAt: string; occurredAt: string; tenantId: string; scope?: string;
  authorizationSnapshotId: string; membershipVersion: string;
};
export type LocalQueueRecord = { eventUuid: string; bundleId: string; tenantId: string; reason: string; recoveryAction: string; payload: Record<string, unknown>; createdAt: string };

export interface StorageGateway {
  saveDraft(draft: JournalDraft): Promise<void>;
  enqueue(record: OutboxRecord): Promise<void>;
  pendingCount(tenantId?: string): Promise<number>;
  listOutbox(tenantId: string, limit?: number): Promise<OutboxRecord[]>;
  acknowledge(eventUuids: string[]): Promise<void>;
  quarantine(records: OutboxRecord[], queue: "rejections" | "conflicts", reason: string, recoveryAction: string): Promise<void>;
  getCursor(tenantId: string, scope: string, priority: "priority" | "normal"): Promise<string | null>;
  setCursor(tenantId: string, scope: string, priority: "priority" | "normal", cursor: string): Promise<void>;
  applyChanges(tenantId: string, scope: string, changes: PullChange[]): Promise<void>;
  purgeScope(tenantId: string, scope: string): Promise<void>;
  saveToday(tenantId: string, tasks: TodayTask[]): Promise<void>;
  getToday(tenantId: string): Promise<TodayTask[]>;
  saveFields(tenantId: string, fields: FieldFeature[]): Promise<void>;
  getFields(tenantId: string): Promise<FieldFeature[]>;
  saveServerQueues(tenantId: string, queues: QueueSnapshot): Promise<void>;
  queueCounts(tenantId: string): Promise<{ rejections: number; conflicts: number }>;
}

const DB_NAME = "isas-field-ops";
const DB_VERSION = 3;
const STORES = ["drafts", "outbox", "rejections", "conflicts", "cursors", "changes", "today", "fields", "serverQueues"] as const;
type StoreName = typeof STORES[number];

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const value = indexedDB.open(DB_NAME, DB_VERSION);
    value.onupgradeneeded = () => {
      const db = value.result;
      for (const [name, keyPath] of [["drafts", "id"], ["outbox", "eventUuid"], ["rejections", "eventUuid"], ["conflicts", "eventUuid"], ["cursors", "key"], ["changes", "key"], ["today", "tenantId"], ["fields", "key"], ["serverQueues", "tenantId"]] as const) {
        if (!db.objectStoreNames.contains(name)) db.createObjectStore(name, { keyPath });
      }
    };
    value.onsuccess = () => resolve(value.result);
    value.onerror = () => reject(value.error);
  });
}

function idbRequest<T>(value: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => { value.onsuccess = () => resolve(value.result); value.onerror = () => reject(value.error); });
}

async function inTransaction<T>(storeNames: StoreName | StoreName[], mode: IDBTransactionMode, operation: (stores: Record<string, IDBObjectStore>) => Promise<T> | T): Promise<T> {
  const db = await openDatabase();
  const names = Array.isArray(storeNames) ? storeNames : [storeNames];
  const tx = db.transaction(names, mode);
  const stores = Object.fromEntries(names.map((name) => [name, tx.objectStore(name)]));
  try {
    const result = await operation(stores);
    await new Promise<void>((resolve, reject) => { tx.oncomplete = () => resolve(); tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error || new Error("IndexedDB transaction aborted")); });
    return result;
  } finally { db.close(); }
}

function cursorKey(tenantId: string, scope: string, priority: string) { return `${tenantId}:${scope}:${priority}`; }

export const browserStorage: StorageGateway = {
  saveDraft: (draft) => inTransaction("drafts", "readwrite", ({ drafts }) => idbRequest(drafts.put(draft)).then(() => undefined)),
  enqueue: (record) => inTransaction("outbox", "readwrite", ({ outbox }) => idbRequest(outbox.put(record)).then(() => undefined)),
  pendingCount: (tenantId) => inTransaction("outbox", "readonly", async ({ outbox }) => { const rows = await idbRequest<OutboxRecord[]>(outbox.getAll()); return tenantId ? rows.filter((row) => row.tenantId === tenantId).length : rows.length; }),
  listOutbox: (tenantId, limit = 100) => inTransaction("outbox", "readonly", async ({ outbox }) => (await idbRequest<OutboxRecord[]>(outbox.getAll())).filter((row) => row.tenantId === tenantId).sort((a, b) => a.createdAt.localeCompare(b.createdAt)).slice(0, limit)),
  acknowledge: (ids) => inTransaction("outbox", "readwrite", ({ outbox }) => { ids.forEach((id) => outbox.delete(id)); }),
  quarantine: (records, queue, reason, recoveryAction) => inTransaction(["outbox", queue], "readwrite", (stores) => {
    for (const record of records) {
      stores[queue].put({ eventUuid: record.eventUuid, bundleId: record.bundleId, tenantId: record.tenantId, reason, recoveryAction, payload: record.payload, createdAt: new Date().toISOString() } satisfies LocalQueueRecord);
      stores.outbox.delete(record.eventUuid);
    }
  }),
  getCursor: (tenantId, scope, priority) => inTransaction("cursors", "readonly", async ({ cursors }) => (await idbRequest<{ cursor: string } | undefined>(cursors.get(cursorKey(tenantId, scope, priority))))?.cursor || null),
  setCursor: (tenantId, scope, priority, cursor) => inTransaction("cursors", "readwrite", ({ cursors }) => idbRequest(cursors.put({ key: cursorKey(tenantId, scope, priority), tenantId, scope, priority, cursor })).then(() => undefined)),
  applyChanges: (tenantId, scope, changes) => inTransaction("changes", "readwrite", ({ changes: store }) => { for (const change of changes) store.put({ ...change, key: `${tenantId}:${scope}:${change.type}:${change.entityId || change.eventUuid || change.serverSeq}`, tenantId, scope }); }),
  purgeScope: (tenantId, scope) => inTransaction(["cursors", "changes", "fields"], "readwrite", async ({ cursors, changes, fields }) => {
    for (const row of await idbRequest<Array<{ key: string; tenantId: string; scope: string }>>(cursors.getAll())) if (row.tenantId === tenantId && row.scope === scope) cursors.delete(row.key);
    for (const row of await idbRequest<Array<{ key: string; tenantId: string; scope: string }>>(changes.getAll())) if (row.tenantId === tenantId && row.scope === scope) changes.delete(row.key);
    for (const row of await idbRequest<Array<{ key: string; tenantId: string; properties: { fieldGroupId: string } }>>(fields.getAll())) if (row.tenantId === tenantId && row.properties.fieldGroupId === scope) fields.delete(row.key);
  }),
  saveToday: (tenantId, tasks) => inTransaction("today", "readwrite", ({ today }) => idbRequest(today.put({ tenantId, tasks, savedAt: new Date().toISOString() })).then(() => undefined)),
  getToday: (tenantId) => inTransaction("today", "readonly", async ({ today }) => (await idbRequest<{ tasks: TodayTask[] } | undefined>(today.get(tenantId)))?.tasks || []),
  saveFields: (tenantId, fields) => inTransaction("fields", "readwrite", async ({ fields: store }) => {
    for (const row of await idbRequest<Array<{ key: string; tenantId: string }>>(store.getAll())) if (row.tenantId === tenantId) store.delete(row.key);
    for (const field of fields) store.put({ ...field, key: `${tenantId}:${field.id}`, tenantId, cachedAt: new Date().toISOString() });
  }),
  getFields: (tenantId) => inTransaction("fields", "readonly", async ({ fields }) => (await idbRequest<Array<FieldFeature & { tenantId: string }>>(fields.getAll())).filter((field) => field.tenantId === tenantId).map(({ tenantId: _tenantId, ...field }) => field)),
  saveServerQueues: (tenantId, queues) => inTransaction("serverQueues", "readwrite", ({ serverQueues }) => idbRequest(serverQueues.put({ tenantId, ...queues, savedAt: new Date().toISOString() })).then(() => undefined)),
  queueCounts: (tenantId) => inTransaction(["rejections", "conflicts", "serverQueues"], "readonly", async ({ rejections, conflicts, serverQueues }) => {
    const localRejections = (await idbRequest<LocalQueueRecord[]>(rejections.getAll())).filter((row) => row.tenantId === tenantId).length;
    const localConflicts = (await idbRequest<LocalQueueRecord[]>(conflicts.getAll())).filter((row) => row.tenantId === tenantId).length;
    const server = await idbRequest<{ rejections: unknown[]; conflicts: unknown[] } | undefined>(serverQueues.get(tenantId));
    return { rejections: localRejections + (server?.rejections.length || 0), conflicts: localConflicts + (server?.conflicts.length || 0) };
  }),
};
