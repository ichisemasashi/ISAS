export type JournalDraft = {
  id: string;
  field: string;
  workType: string;
  startedAt: string;
  endedAt: string;
  memo: string;
  updatedAt: string;
};

export type OutboxRecord = {
  eventUuid: string;
  kind: "journal" | "pesticide" | "punch";
  payload: Record<string, unknown>;
  createdAt: string;
};

export interface StorageGateway {
  saveDraft(draft: JournalDraft): Promise<void>;
  enqueue(record: OutboxRecord): Promise<void>;
  pendingCount(): Promise<number>;
}

const DB_NAME = "isas-field-ops";
const DB_VERSION = 1;

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const database = request.result;
      if (!database.objectStoreNames.contains("drafts")) database.createObjectStore("drafts", { keyPath: "id" });
      if (!database.objectStoreNames.contains("outbox")) database.createObjectStore("outbox", { keyPath: "eventUuid" });
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function put(storeName: "drafts" | "outbox", value: JournalDraft | OutboxRecord): Promise<void> {
  const database = await openDatabase();
  await new Promise<void>((resolve, reject) => {
    const transaction = database.transaction(storeName, "readwrite");
    transaction.objectStore(storeName).put(value);
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error);
  });
  database.close();
}

export const browserStorage: StorageGateway = {
  saveDraft: (draft) => put("drafts", draft),
  enqueue: (record) => put("outbox", record),
  async pendingCount() {
    const database = await openDatabase();
    const count = await new Promise<number>((resolve, reject) => {
      const request = database.transaction("outbox").objectStore("outbox").count();
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    database.close();
    return count;
  },
};
