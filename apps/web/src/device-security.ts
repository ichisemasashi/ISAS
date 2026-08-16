const VAULT_DB = "isas-device-vault";
const VAULT_VERSION = 1;
const encoder = new TextEncoder();
const decoder = new TextDecoder();

export type VaultPurpose = "cache" | "outbox";
type KeyRow = { id: string; purpose: VaultPurpose; version: number; state: "active" | "retired"; key: CryptoKey; createdAt: string };
type CipherEnvelope = { algorithm: "AES-GCM"; keyVersion: number; iv: ArrayBuffer; aad: string; ciphertext: ArrayBuffer };
type CacheRow = { id: string; tenantId: string; scope: string; envelope: CipherEnvelope };
type OutboxRow = {
  id: string; tenantId: string; createdAt: string; state: "pending" | "recovery-uploaded";
  envelope: Omit<CipherEnvelope, "keyVersion">;
  localWrap: { algorithm: "AES-KW"; keyVersion: number; wrappedKey: ArrayBuffer };
  recoveryWrap: { algorithm: "RSA-OAEP-256"; keyId: string; wrappedKey: ArrayBuffer };
};
type RecoveryKeyRow = { id: "recovery-public-key"; keyId: string; key: CryptoKey };

export type RecoveryPackage = {
  format: "ISAS-OUTBOX-RECOVERY-1"; eventId: string; tenantId: string; createdAt: string;
  algorithm: "AES-GCM+RSA-OAEP-256"; recoveryKeyId: string; iv: string; aad: string;
  ciphertext: string; wrappedContentKey: string;
};

function request<T>(value: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    value.onsuccess = () => resolve(value.result);
    value.onerror = () => reject(value.error);
  });
}

function transactionDone(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error || new Error("device vault transaction aborted"));
  });
}

async function openVault(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const opening = indexedDB.open(VAULT_DB, VAULT_VERSION);
    opening.onupgradeneeded = () => {
      const db = opening.result;
      if (!db.objectStoreNames.contains("keys")) db.createObjectStore("keys", { keyPath: "id" });
      if (!db.objectStoreNames.contains("cache")) db.createObjectStore("cache", { keyPath: "id" });
      if (!db.objectStoreNames.contains("outbox")) db.createObjectStore("outbox", { keyPath: "id" });
      if (!db.objectStoreNames.contains("configuration")) db.createObjectStore("configuration", { keyPath: "id" });
    };
    opening.onsuccess = () => resolve(opening.result);
    opening.onerror = () => reject(opening.error);
  });
}

async function readAll<T>(store: "keys" | "cache" | "outbox" | "configuration"): Promise<T[]> {
  const db = await openVault();
  try { return await request<T[]>(db.transaction(store, "readonly").objectStore(store).getAll()); }
  finally { db.close(); }
}

async function getRow<T>(store: "keys" | "cache" | "outbox" | "configuration", id: string): Promise<T | undefined> {
  const db = await openVault();
  try { return await request<T | undefined>(db.transaction(store, "readonly").objectStore(store).get(id)); }
  finally { db.close(); }
}

async function putRows(store: "keys" | "cache" | "outbox" | "configuration", rows: unknown[]): Promise<void> {
  const db = await openVault();
  try {
    const tx = db.transaction(store, "readwrite");
    for (const row of rows) tx.objectStore(store).put(row);
    await transactionDone(tx);
  } finally { db.close(); }
}

async function deleteRows(store: "keys" | "cache" | "outbox", ids: string[]): Promise<void> {
  if (!ids.length) return;
  const db = await openVault();
  try {
    const tx = db.transaction(store, "readwrite");
    for (const id of ids) tx.objectStore(store).delete(id);
    await transactionDone(tx);
  } finally { db.close(); }
}

function aad(purpose: VaultPurpose, tenantId: string, id: string): string {
  return `ISAS:${purpose}:${tenantId}:${id}`;
}

function base64(value: ArrayBuffer | Uint8Array): string {
  const bytes = value instanceof Uint8Array ? value : new Uint8Array(value);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

async function activeKey(purpose: VaultPurpose): Promise<KeyRow> {
  const rows = await readAll<KeyRow>("keys");
  const existing = rows.filter((row) => row.purpose === purpose && row.state === "active").sort((a, b) => b.version - a.version)[0];
  if (existing) return existing;
  const key = await crypto.subtle.generateKey({ name: purpose === "cache" ? "AES-GCM" : "AES-KW", length: 256 }, false, [purpose === "cache" ? "encrypt" : "wrapKey", purpose === "cache" ? "decrypt" : "unwrapKey"]);
  const row: KeyRow = { id: `${purpose}:1`, purpose, version: 1, state: "active", key, createdAt: new Date().toISOString() };
  await putRows("keys", [row]);
  return row;
}

async function keyVersion(purpose: VaultPurpose, version: number): Promise<KeyRow> {
  const row = await getRow<KeyRow>("keys", `${purpose}:${version}`);
  if (!row) throw new Error(`${purpose} key version ${version} is unavailable`);
  return row;
}

async function encryptJson(value: unknown, key: CryptoKey, keyVersion: number, boundAad: string): Promise<CipherEnvelope> {
  const iv = crypto.getRandomValues(new Uint8Array(12)).buffer as ArrayBuffer;
  const ciphertext = await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: encoder.encode(boundAad), tagLength: 128 }, key, encoder.encode(JSON.stringify(value)));
  return { algorithm: "AES-GCM", keyVersion, iv, aad: boundAad, ciphertext };
}

async function decryptJson<T>(envelope: CipherEnvelope, key: CryptoKey): Promise<T> {
  const plaintext = await crypto.subtle.decrypt({ name: "AES-GCM", iv: envelope.iv, additionalData: encoder.encode(envelope.aad), tagLength: 128 }, key, envelope.ciphertext);
  return JSON.parse(decoder.decode(plaintext)) as T;
}

export async function configureRecoveryPublicKey(keyId: string, jwk: JsonWebKey): Promise<void> {
  if (!keyId || jwk.kty !== "RSA") throw new Error("an RSA recovery public key and stable key id are required");
  const key = await crypto.subtle.importKey("jwk", jwk, { name: "RSA-OAEP", hash: "SHA-256" }, false, ["wrapKey"]);
  await putRows("configuration", [{ id: "recovery-public-key", keyId, key } satisfies RecoveryKeyRow]);
}

export async function putEncryptedCache<T>(tenantId: string, scope: string, id: string, value: T): Promise<void> {
  const key = await activeKey("cache");
  const row: CacheRow = { id, tenantId, scope, envelope: await encryptJson(value, key.key, key.version, aad("cache", tenantId, id)) };
  await putRows("cache", [row]);
}

export async function getEncryptedCache<T>(tenantId: string, id: string): Promise<T | null> {
  const row = await getRow<CacheRow>("cache", id);
  if (!row) return null;
  if (row.tenantId !== tenantId || row.envelope.aad !== aad("cache", tenantId, id)) throw new Error("cache authorization binding mismatch");
  return decryptJson<T>(row.envelope, (await keyVersion("cache", row.envelope.keyVersion)).key);
}

export async function enqueueEncryptedOutbox<T>(tenantId: string, eventId: string, value: T): Promise<void> {
  const recovery = await getRow<RecoveryKeyRow>("configuration", "recovery-public-key");
  if (!recovery) throw new Error("offline recovery public key is not configured; refusing plaintext or unrecoverable outbox storage");
  const wrappingKey = await activeKey("outbox");
  const contentKey = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, true, ["encrypt", "decrypt"]);
  const envelope = await encryptJson(value, contentKey, 0, aad("outbox", tenantId, eventId));
  const [localWrapped, recoveryWrapped] = await Promise.all([
    crypto.subtle.wrapKey("raw", contentKey, wrappingKey.key, "AES-KW"),
    crypto.subtle.wrapKey("raw", contentKey, recovery.key, { name: "RSA-OAEP" }),
  ]);
  const row: OutboxRow = {
    id: eventId, tenantId, createdAt: new Date().toISOString(), state: "pending",
    envelope: { algorithm: "AES-GCM", iv: envelope.iv, aad: envelope.aad, ciphertext: envelope.ciphertext },
    localWrap: { algorithm: "AES-KW", keyVersion: wrappingKey.version, wrappedKey: localWrapped },
    recoveryWrap: { algorithm: "RSA-OAEP-256", keyId: recovery.keyId, wrappedKey: recoveryWrapped },
  };
  await putRows("outbox", [row]);
}

export async function readEncryptedOutbox<T>(tenantId: string, eventId: string): Promise<T | null> {
  const row = await getRow<OutboxRow>("outbox", eventId);
  if (!row) return null;
  if (row.tenantId !== tenantId || row.envelope.aad !== aad("outbox", tenantId, eventId)) throw new Error("outbox authorization binding mismatch");
  const wrappingKey = await keyVersion("outbox", row.localWrap.keyVersion);
  const contentKey = await crypto.subtle.unwrapKey("raw", row.localWrap.wrappedKey, wrappingKey.key, "AES-KW", { name: "AES-GCM", length: 256 }, false, ["decrypt"]);
  return decryptJson<T>({ ...row.envelope, keyVersion: 0 }, contentKey);
}

export async function listEncryptedOutbox<T>(tenantId: string, limit = 100): Promise<T[]> {
  const rows = (await readAll<OutboxRow>("outbox"))
    .filter((row) => row.tenantId === tenantId && row.state === "pending")
    .sort((left, right) => left.createdAt.localeCompare(right.createdAt))
    .slice(0, limit);
  const values: T[] = [];
  for (const row of rows) {
    const value = await readEncryptedOutbox<T>(tenantId, row.id);
    if (value !== null) values.push(value);
  }
  return values;
}

export async function encryptedOutboxCount(tenantId?: string): Promise<number> {
  return (await readAll<OutboxRow>("outbox")).filter((row) => row.state === "pending" && (!tenantId || row.tenantId === tenantId)).length;
}

export async function listRecoveryPackages(tenantId: string): Promise<RecoveryPackage[]> {
  return (await readAll<OutboxRow>("outbox")).filter((row) => row.tenantId === tenantId).map((row) => ({
    format: "ISAS-OUTBOX-RECOVERY-1", eventId: row.id, tenantId: row.tenantId, createdAt: row.createdAt,
    algorithm: "AES-GCM+RSA-OAEP-256", recoveryKeyId: row.recoveryWrap.keyId,
    iv: base64(row.envelope.iv), aad: row.envelope.aad, ciphertext: base64(row.envelope.ciphertext), wrappedContentKey: base64(row.recoveryWrap.wrappedKey),
  }));
}

export async function acknowledgeEncryptedOutbox(eventIds: string[]): Promise<void> { await deleteRows("outbox", eventIds); }

export async function rotateDeviceKey(purpose: VaultPurpose): Promise<{ from: number; to: number; records: number }> {
  const old = await activeKey(purpose);
  const algorithm = purpose === "cache" ? "AES-GCM" : "AES-KW";
  const usages: KeyUsage[] = purpose === "cache" ? ["encrypt", "decrypt"] : ["wrapKey", "unwrapKey"];
  const nextKey = await crypto.subtle.generateKey({ name: algorithm, length: 256 }, false, usages);
  const next: KeyRow = { id: `${purpose}:${old.version + 1}`, purpose, version: old.version + 1, state: "active", key: nextKey, createdAt: new Date().toISOString() };
  await putRows("keys", [{ ...old, state: "retired" }, next]);
  if (purpose === "cache") {
    const rows = await readAll<CacheRow>("cache");
    const migrated: CacheRow[] = [];
    for (const row of rows) migrated.push({ ...row, envelope: await encryptJson(await decryptJson(row.envelope, (await keyVersion("cache", row.envelope.keyVersion)).key), next.key, next.version, row.envelope.aad) });
    await putRows("cache", migrated);
    await deleteRows("keys", [old.id]);
    return { from: old.version, to: next.version, records: rows.length };
  }
  const rows = await readAll<OutboxRow>("outbox");
  const migrated: OutboxRow[] = [];
  for (const row of rows) {
    const source = await keyVersion("outbox", row.localWrap.keyVersion);
    const contentKey = await crypto.subtle.unwrapKey("raw", row.localWrap.wrappedKey, source.key, "AES-KW", { name: "AES-GCM", length: 256 }, true, ["encrypt", "decrypt"]);
    migrated.push({ ...row, localWrap: { ...row.localWrap, keyVersion: next.version, wrappedKey: await crypto.subtle.wrapKey("raw", contentKey, next.key, "AES-KW") } });
  }
  await putRows("outbox", migrated);
  await deleteRows("keys", [old.id]);
  return { from: old.version, to: next.version, records: rows.length };
}

export async function revokeDeviceAccess(tenantId: string, recoveryUploadedEventIds: string[]): Promise<{ cacheErased: number; outboxQuarantined: number }> {
  const cacheRows = (await readAll<CacheRow>("cache")).filter((row) => row.tenantId === tenantId);
  const outboxRows = (await readAll<OutboxRow>("outbox")).filter((row) => row.tenantId === tenantId);
  const uploaded = new Set(recoveryUploadedEventIds);
  if (outboxRows.some((row) => !uploaded.has(row.id))) throw new Error("recovery upload evidence is required before revoking a device with pending outbox");
  await deleteRows("cache", cacheRows.map((row) => row.id));
  await deleteRows("keys", (await readAll<KeyRow>("keys")).filter((row) => row.purpose === "cache" || row.purpose === "outbox").map((row) => row.id));
  await putRows("outbox", outboxRows.map((row) => ({ ...row, state: "recovery-uploaded" })));
  return { cacheErased: cacheRows.length, outboxQuarantined: outboxRows.length };
}

export async function inspectDeviceVault(): Promise<{ keys: Array<Pick<KeyRow, "purpose" | "version" | "state"> & { extractable: boolean }>; cacheRecords: number; outboxRecords: number }> {
  const keys = await readAll<KeyRow>("keys");
  return {
    keys: keys.map(({ purpose, version, state, key }) => ({ purpose, version, state, extractable: key.extractable })),
    cacheRecords: (await readAll<CacheRow>("cache")).length,
    outboxRecords: (await readAll<OutboxRow>("outbox")).length,
  };
}

export async function assessStoragePressure(requiredOutboxBytes = 0): Promise<{ usage: number; quota: number; available: number; persistent: boolean; safeToWriteOutbox: boolean }> {
  const estimate = await navigator.storage?.estimate?.();
  const usage = estimate?.usage || 0;
  const quota = estimate?.quota || 0;
  const available = Math.max(0, quota - usage);
  const persistent = await navigator.storage?.persisted?.().catch(() => false) || false;
  // Keep 10% or 32 MiB, whichever is larger, for browser bookkeeping and an orderly recovery export.
  const reserve = Math.max(quota * 0.1, 32 * 1024 * 1024);
  return { usage, quota, available, persistent, safeToWriteOutbox: quota > 0 && available - requiredOutboxBytes >= reserve };
}

export async function requestPersistentDeviceStorage(): Promise<boolean> {
  return await navigator.storage?.persist?.().catch(() => false) || false;
}

export async function resetDeviceVaultForTesting(): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    const deleting = indexedDB.deleteDatabase(VAULT_DB);
    deleting.onsuccess = () => resolve(); deleting.onerror = () => reject(deleting.error);
  });
}
