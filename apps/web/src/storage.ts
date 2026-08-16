import type { FieldFeature, InventorySnapshot, JournalBootstrap, OfflineMapPackManifest, PesticideBootstrap, PullChange, QueueSnapshot, TodayTask } from "./api";
import { acknowledgeEncryptedOutbox, encryptedOutboxCount, enqueueEncryptedOutbox, getEncryptedCache, listEncryptedOutbox, putEncryptedCache } from "./device-security";

export type JournalDraft = { id: string; aggregateId: string; baseVersion: number; baseValue: Record<string, unknown>; instructionId?: string; fieldId?: string; fieldGroupId?: string; field: string; workType: string; startedAt: string; endedAt: string; memo: string; attachmentIds?: string[]; updatedAt: string };
export type OutboxRecord = {
  eventUuid: string; bundleId: string; kind: "journal" | "pesticide" | "punch" | "stock"; payload: Record<string, unknown>;
  createdAt: string; occurredAt: string; tenantId: string; scope?: string;
  authorizationSnapshotId: string; membershipVersion: string;
};
export type LocalQueueRecord = { eventUuid: string; bundleId: string; tenantId: string; reason: string; recoveryAction: string; payload: Record<string, unknown>; createdAt: string };
export type JournalAttachmentRecord = { id: string; tenantId: string; journalId: string; fileName: string; capturedAt: string; blob: Blob; ready: boolean };
export type OfflineMapPackRecord = Omit<OfflineMapPackManifest, "archiveUrl" | "archiveUrlExpiresAt"> & { tenantId: string; status: "downloading" | "complete"; byteSize: number; tileCount: number; lastAccessedAt: string };
export type OfflineMapTileRecord = { key: string; packId: string; tenantId: string; z: number; x: number; y: number; bytes: ArrayBuffer; byteSize: number; sha256: string };

export interface StorageGateway {
  saveDraft(draft: JournalDraft): Promise<void>;
  enqueue(record: OutboxRecord): Promise<void>;
  saveAttachment(record: JournalAttachmentRecord): Promise<void>;
  markAttachmentsReady(journalIds: string[]): Promise<void>;
  listReadyAttachments(tenantId: string): Promise<JournalAttachmentRecord[]>;
  acknowledgeAttachment(id: string): Promise<void>;
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
  saveJournalBootstrap(tenantId: string, value: JournalBootstrap): Promise<void>;
  getJournalBootstrap(tenantId: string): Promise<JournalBootstrap | null>;
  saveFields(tenantId: string, fields: FieldFeature[]): Promise<void>;
  getFields(tenantId: string): Promise<FieldFeature[]>;
  savePesticideBootstrap(tenantId: string, value: PesticideBootstrap): Promise<void>;
  getPesticideBootstrap(tenantId: string, fieldId: string): Promise<PesticideBootstrap | null>;
  saveInventory(tenantId: string, value: InventorySnapshot): Promise<void>;
  getInventory(tenantId: string): Promise<InventorySnapshot | null>;
  saveServerQueues(tenantId: string, queues: QueueSnapshot): Promise<void>;
  queueCounts(tenantId: string): Promise<{ rejections: number; conflicts: number }>;
  beginOfflineMapPack(tenantId: string, manifest: OfflineMapPackManifest): Promise<void>;
  saveOfflineMapTiles(tiles: OfflineMapTileRecord[]): Promise<void>;
  completeOfflineMapPack(packId: string, byteSize: number, tileCount: number): Promise<OfflineMapPackRecord>;
  getLatestOfflineMapPack(tenantId: string, assignmentVersion: string): Promise<OfflineMapPackRecord | null>;
  getOfflineMapTile(packId: string, z: number, x: number, y: number): Promise<OfflineMapTileRecord | null>;
  removeOfflineMapPack(packId: string): Promise<void>;
  reserveOfflineMapCapacity(tenantId: string, requiredBytes: number, limitBytes: number): Promise<void>;
}

const DB_NAME = "isas-field-ops";
const DB_VERSION = 6;
const STORES = ["drafts", "outbox", "attachments", "rejections", "conflicts", "cursors", "changes", "today", "journalBootstrap", "fields", "pesticideBootstrap", "inventory", "serverQueues", "offlinePacks", "offlineTiles"] as const;
type StoreName = typeof STORES[number];

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const value = indexedDB.open(DB_NAME, DB_VERSION);
    value.onupgradeneeded = () => {
      const db = value.result;
      for (const [name, keyPath] of [["drafts", "id"], ["outbox", "eventUuid"], ["attachments", "id"], ["rejections", "eventUuid"], ["conflicts", "eventUuid"], ["cursors", "key"], ["changes", "key"], ["today", "tenantId"], ["journalBootstrap", "tenantId"], ["fields", "key"], ["pesticideBootstrap", "key"], ["inventory", "tenantId"], ["serverQueues", "tenantId"], ["offlinePacks", "packId"], ["offlineTiles", "key"]] as const) {
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
  enqueue: (record) => enqueueEncryptedOutbox(record.tenantId, record.eventUuid, record),
  saveAttachment: (record) => inTransaction("attachments", "readwrite", ({ attachments }) => idbRequest(attachments.put(record)).then(() => undefined)),
  markAttachmentsReady: (journalIds) => inTransaction("attachments", "readwrite", async ({ attachments }) => {
    for (const row of await idbRequest<JournalAttachmentRecord[]>(attachments.getAll())) if (journalIds.includes(row.journalId)) attachments.put({ ...row, ready: true });
  }),
  listReadyAttachments: (tenantId) => inTransaction("attachments", "readonly", async ({ attachments }) => (await idbRequest<JournalAttachmentRecord[]>(attachments.getAll())).filter((row) => row.tenantId === tenantId && row.ready)),
  acknowledgeAttachment: (id) => inTransaction("attachments", "readwrite", ({ attachments }) => idbRequest(attachments.delete(id)).then(() => undefined)),
  pendingCount: async (tenantId) => {
    const legacy = await inTransaction("outbox", "readonly", ({ outbox }) => idbRequest<OutboxRecord[]>(outbox.getAll()));
    if (legacy.some((row) => !tenantId || row.tenantId === tenantId)) throw new Error("legacy plaintext outbox requires supervised recovery");
    return encryptedOutboxCount(tenantId);
  },
  listOutbox: (tenantId, limit = 100) => listEncryptedOutbox<OutboxRecord>(tenantId, limit),
  acknowledge: acknowledgeEncryptedOutbox,
  quarantine: async (records, queue, reason, recoveryAction) => {
    await inTransaction(queue, "readwrite", (stores) => {
    for (const record of records) {
      stores[queue].put({ eventUuid: record.eventUuid, bundleId: record.bundleId, tenantId: record.tenantId, reason, recoveryAction, payload: record.payload, createdAt: new Date().toISOString() } satisfies LocalQueueRecord);
    }
    });
    await acknowledgeEncryptedOutbox(records.map((record) => record.eventUuid));
  },
  getCursor: (tenantId, scope, priority) => inTransaction("cursors", "readonly", async ({ cursors }) => (await idbRequest<{ cursor: string } | undefined>(cursors.get(cursorKey(tenantId, scope, priority))))?.cursor || null),
  setCursor: (tenantId, scope, priority, cursor) => inTransaction("cursors", "readwrite", ({ cursors }) => idbRequest(cursors.put({ key: cursorKey(tenantId, scope, priority), tenantId, scope, priority, cursor })).then(() => undefined)),
  applyChanges: (tenantId, scope, changes) => inTransaction("changes", "readwrite", ({ changes: store }) => { for (const change of changes) store.put({ ...change, key: `${tenantId}:${scope}:${change.type}:${change.entityId || change.eventUuid || change.serverSeq}`, tenantId, scope }); }),
  purgeScope: (tenantId, scope) => inTransaction(["cursors", "changes", "fields", "pesticideBootstrap", "today", "journalBootstrap", "serverQueues", "offlinePacks", "offlineTiles"], "readwrite", async ({ cursors, changes, fields, pesticideBootstrap, today, journalBootstrap, serverQueues, offlinePacks, offlineTiles }) => {
    for (const row of await idbRequest<Array<{ key: string; tenantId: string; scope: string }>>(cursors.getAll())) if (row.tenantId === tenantId && row.scope === scope) cursors.delete(row.key);
    for (const row of await idbRequest<Array<{ key: string; tenantId: string; scope: string }>>(changes.getAll())) if (row.tenantId === tenantId && row.scope === scope) changes.delete(row.key);
    for (const row of await idbRequest<Array<{ key: string; tenantId: string; properties: { fieldGroupId: string } }>>(fields.getAll())) if (row.tenantId === tenantId && row.properties.fieldGroupId === scope) fields.delete(row.key);
    for (const row of await idbRequest<Array<{ key: string; tenantId: string; value: PesticideBootstrap }>>(pesticideBootstrap.getAll())) if (row.tenantId === tenantId && row.value.field.fieldGroupId === scope) pesticideBootstrap.delete(row.key);
    today.delete(tenantId);
    journalBootstrap.delete(tenantId);
    serverQueues.delete(tenantId);
    const removedPacks = new Set<string>();
    for (const pack of await idbRequest<OfflineMapPackRecord[]>(offlinePacks.getAll())) if (pack.tenantId === tenantId && pack.fieldGroupId === scope) { removedPacks.add(pack.packId); offlinePacks.delete(pack.packId); }
    for (const tile of await idbRequest<OfflineMapTileRecord[]>(offlineTiles.getAll())) if (removedPacks.has(tile.packId)) offlineTiles.delete(tile.key);
  }),
  saveToday: (tenantId, tasks) => putEncryptedCache(tenantId, "today", `today:${tenantId}`, { tasks, savedAt: new Date().toISOString() }),
  getToday: async (tenantId) => (await getEncryptedCache<{ tasks: TodayTask[] }>(tenantId, `today:${tenantId}`))?.tasks || [],
  saveJournalBootstrap: (tenantId, value) => inTransaction("journalBootstrap", "readwrite", ({ journalBootstrap }) => idbRequest(journalBootstrap.put({ tenantId, value, savedAt: new Date().toISOString() })).then(() => undefined)),
  getJournalBootstrap: (tenantId) => inTransaction("journalBootstrap", "readonly", async ({ journalBootstrap }) => (await idbRequest<{ value: JournalBootstrap } | undefined>(journalBootstrap.get(tenantId)))?.value || null),
  saveFields: (tenantId, fields) => inTransaction("fields", "readwrite", async ({ fields: store }) => {
    for (const row of await idbRequest<Array<{ key: string; tenantId: string }>>(store.getAll())) if (row.tenantId === tenantId) store.delete(row.key);
    for (const field of fields) store.put({ ...field, key: `${tenantId}:${field.id}`, tenantId, cachedAt: new Date().toISOString() });
  }),
  getFields: (tenantId) => inTransaction("fields", "readonly", async ({ fields }) => (await idbRequest<Array<FieldFeature & { tenantId: string }>>(fields.getAll())).filter((field) => field.tenantId === tenantId).map(({ tenantId: _tenantId, ...field }) => field)),
  savePesticideBootstrap: (tenantId, value) => inTransaction("pesticideBootstrap", "readwrite", ({ pesticideBootstrap }) => idbRequest(pesticideBootstrap.put({ key: `${tenantId}:${value.field.id}`, tenantId, value, savedAt: new Date().toISOString() })).then(() => undefined)),
  getPesticideBootstrap: (tenantId, fieldId) => inTransaction("pesticideBootstrap", "readonly", async ({ pesticideBootstrap }) => (await idbRequest<{ value: PesticideBootstrap } | undefined>(pesticideBootstrap.get(`${tenantId}:${fieldId}`)))?.value || null),
  saveInventory: (tenantId, value) => inTransaction("inventory", "readwrite", ({ inventory }) => idbRequest(inventory.put({ tenantId, value, savedAt: new Date().toISOString() })).then(() => undefined)),
  getInventory: (tenantId) => inTransaction("inventory", "readonly", async ({ inventory }) => (await idbRequest<{ value: InventorySnapshot } | undefined>(inventory.get(tenantId)))?.value || null),
  saveServerQueues: (tenantId, queues) => inTransaction("serverQueues", "readwrite", ({ serverQueues }) => idbRequest(serverQueues.put({ tenantId, ...queues, savedAt: new Date().toISOString() })).then(() => undefined)),
  queueCounts: (tenantId) => inTransaction(["rejections", "conflicts", "serverQueues"], "readonly", async ({ rejections, conflicts, serverQueues }) => {
    const localRejections = (await idbRequest<LocalQueueRecord[]>(rejections.getAll())).filter((row) => row.tenantId === tenantId).length;
    const localConflicts = (await idbRequest<LocalQueueRecord[]>(conflicts.getAll())).filter((row) => row.tenantId === tenantId).length;
    const server = await idbRequest<{ rejections: unknown[]; conflicts: unknown[] } | undefined>(serverQueues.get(tenantId));
    return { rejections: localRejections + (server?.rejections.length || 0), conflicts: localConflicts + (server?.conflicts.length || 0) };
  }),
  beginOfflineMapPack: (tenantId, manifest) => inTransaction(["offlinePacks", "offlineTiles"], "readwrite", async ({ offlinePacks, offlineTiles }) => {
    for (const tile of await idbRequest<OfflineMapTileRecord[]>(offlineTiles.getAll())) if (tile.packId === manifest.packId) offlineTiles.delete(tile.key);
    const { archiveUrl: _archiveUrl, archiveUrlExpiresAt: _archiveUrlExpiresAt, ...safe } = manifest;
    await idbRequest(offlinePacks.put({ ...safe, tenantId, status: "downloading", byteSize: 0, tileCount: 0, lastAccessedAt: new Date().toISOString() } satisfies OfflineMapPackRecord));
  }),
  saveOfflineMapTiles: (tiles) => inTransaction("offlineTiles", "readwrite", ({ offlineTiles }) => { for (const tile of tiles) offlineTiles.put(tile); }),
  completeOfflineMapPack: (packId, byteSize, tileCount) => inTransaction("offlinePacks", "readwrite", async ({ offlinePacks }) => {
    const pack = await idbRequest<OfflineMapPackRecord | undefined>(offlinePacks.get(packId));
    if (!pack) throw new Error("offline map pack is missing");
    const completed = { ...pack, status: "complete" as const, byteSize, tileCount, lastAccessedAt: new Date().toISOString() };
    await idbRequest(offlinePacks.put(completed)); return completed;
  }),
  getLatestOfflineMapPack: (tenantId, assignmentVersion) => inTransaction(["offlinePacks", "offlineTiles"], "readwrite", async ({ offlinePacks, offlineTiles }) => {
    const now = Date.now();
    const packs = await idbRequest<OfflineMapPackRecord[]>(offlinePacks.getAll());
    const removed = new Set(packs.filter((pack) => pack.tenantId === tenantId && (pack.assignmentVersion !== assignmentVersion || Date.parse(pack.expiresAt) <= now)).map((pack) => pack.packId));
    for (const packId of removed) offlinePacks.delete(packId);
    if (removed.size) for (const tile of await idbRequest<OfflineMapTileRecord[]>(offlineTiles.getAll())) if (removed.has(tile.packId)) offlineTiles.delete(tile.key);
    return packs.filter((pack) => pack.tenantId === tenantId && !removed.has(pack.packId) && pack.status === "complete").sort((a, b) => b.lastAccessedAt.localeCompare(a.lastAccessedAt))[0] || null;
  }),
  getOfflineMapTile: (packId, z, x, y) => inTransaction("offlineTiles", "readonly", async ({ offlineTiles }) => (await idbRequest<OfflineMapTileRecord | undefined>(offlineTiles.get(`${packId}:${z}:${x}:${y}`))) || null),
  removeOfflineMapPack: (packId) => inTransaction(["offlinePacks", "offlineTiles"], "readwrite", async ({ offlinePacks, offlineTiles }) => {
    offlinePacks.delete(packId);
    for (const tile of await idbRequest<OfflineMapTileRecord[]>(offlineTiles.getAll())) if (tile.packId === packId) offlineTiles.delete(tile.key);
  }),
  reserveOfflineMapCapacity: (tenantId, requiredBytes, limitBytes) => inTransaction(["offlinePacks", "offlineTiles"], "readwrite", async ({ offlinePacks, offlineTiles }) => {
    if (requiredBytes > limitBytes) throw new RangeError("offline map pack exceeds installation limit");
    const packs = (await idbRequest<OfflineMapPackRecord[]>(offlinePacks.getAll())).filter((pack) => pack.tenantId === tenantId).sort((a, b) => a.lastAccessedAt.localeCompare(b.lastAccessedAt));
    let used = packs.reduce((sum, pack) => sum + pack.byteSize, 0);
    for (const pack of packs) {
      if (used + requiredBytes <= limitBytes) break;
      offlinePacks.delete(pack.packId); used -= pack.byteSize;
      for (const tile of await idbRequest<OfflineMapTileRecord[]>(offlineTiles.getAll())) if (tile.packId === pack.packId) offlineTiles.delete(tile.key);
    }
    if (used + requiredBytes > limitBytes) throw new RangeError("offline map capacity could not be reserved");
  }),
};
