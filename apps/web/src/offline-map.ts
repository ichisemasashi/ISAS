import { addProtocol } from "maplibre-gl";
import { FetchSource, PMTiles } from "pmtiles";
import type { MvpGateway, OfflineMapPackManifest } from "./api";
import type { AppAuthorization } from "./auth";
import type { OfflineMapPackRecord, OfflineMapTileRecord, StorageGateway } from "./storage";

const HARD_LIMIT = 250 * 1024 * 1024;

function longitudeTile(lng: number, zoom: number) { return Math.floor(((lng + 180) / 360) * 2 ** zoom); }
function latitudeTile(lat: number, zoom: number) {
  const clamped = Math.max(-85.05112878, Math.min(85.05112878, lat));
  const radians = clamped * Math.PI / 180;
  return Math.floor((1 - Math.asinh(Math.tan(radians)) / Math.PI) / 2 * 2 ** zoom);
}

export function tilesForBounds(bbox: [number, number, number, number], minZoom: number, maxZoom: number) {
  const tiles: Array<{ z: number; x: number; y: number }> = [];
  for (let z = minZoom; z <= maxZoom; z += 1) {
    const max = 2 ** z - 1;
    const minX = Math.max(0, longitudeTile(bbox[0], z)); const maxX = Math.min(max, longitudeTile(bbox[2], z));
    const minY = Math.max(0, latitudeTile(bbox[3], z)); const maxY = Math.min(max, latitudeTile(bbox[1], z));
    for (let x = minX; x <= maxX; x += 1) for (let y = minY; y <= maxY; y += 1) tiles.push({ z, x, y });
  }
  return tiles;
}

async function sha256(bytes: ArrayBuffer) {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((value) => value.toString(16).padStart(2, "0")).join("");
}

export function authorizedArchive(url: string, contextId: string) {
  return new PMTiles(new FetchSource(url, new Headers({ "X-ISAS-Context": contextId })));
}

export async function downloadOfflineMapPack({ api, storage, authorization, fieldGroupId, signal, onProgress }: {
  api: MvpGateway; storage: StorageGateway; authorization: AppAuthorization; fieldGroupId: string; signal?: AbortSignal; onProgress?: (done: number, total: number, bytes: number) => void;
}): Promise<OfflineMapPackRecord> {
  const manifest: OfflineMapPackManifest = await api.getOfflineMapPack(authorization.context.contextId, fieldGroupId, signal);
  if (manifest.assignmentVersion !== authorization.context.membershipVersion || Date.parse(manifest.archiveUrlExpiresAt) <= Date.now()) throw new Error("offline map authorization is stale");
  const limit = Math.min(HARD_LIMIT, manifest.maxBytes);
  const coordinates = tilesForBounds(manifest.bbox, manifest.minZoom, manifest.maxZoom);
  const reservedBytes = Math.min(limit, Math.max(4 * 1024 * 1024, coordinates.length * 64 * 1024));
  await storage.reserveOfflineMapCapacity(authorization.context.tenantId, reservedBytes, limit);
  await storage.beginOfflineMapPack(authorization.context.tenantId, manifest);
  const archive = authorizedArchive(manifest.archiveUrl, authorization.context.contextId);
  let byteSize = 0; let tileCount = 0; let batch: OfflineMapTileRecord[] = [];
  try {
    for (const coordinate of coordinates) {
      if (signal?.aborted) throw signal.reason || new DOMException("Aborted", "AbortError");
      const tile = await archive.getZxy(coordinate.z, coordinate.x, coordinate.y, signal);
      if (!tile) continue;
      byteSize += tile.data.byteLength;
      if (byteSize > reservedBytes) throw new RangeError("offline map pack exceeds reserved installation capacity");
      const bytes = tile.data.slice(0);
      batch.push({ key: `${manifest.packId}:${coordinate.z}:${coordinate.x}:${coordinate.y}`, packId: manifest.packId,
        tenantId: authorization.context.tenantId, ...coordinate, bytes, byteSize: bytes.byteLength, sha256: await sha256(bytes) });
      tileCount += 1;
      if (batch.length >= 32) { await storage.saveOfflineMapTiles(batch); batch = []; }
      onProgress?.(tileCount, coordinates.length, byteSize);
    }
    if (batch.length) await storage.saveOfflineMapTiles(batch);
    return await storage.completeOfflineMapPack(manifest.packId, byteSize, tileCount);
  } catch (error) {
    await storage.removeOfflineMapPack(manifest.packId);
    throw error;
  }
}

let protocolRegistered = false;
export function registerOfflineMapProtocol(storage: StorageGateway) {
  if (protocolRegistered) return;
  addProtocol("isas-offline", async (params) => {
    const matched = params.url.match(/^isas-offline:\/\/([^/]+)\/(\d+)\/(\d+)\/(\d+)$/);
    if (!matched) throw new Error("invalid offline tile URL");
    const tile = await storage.getOfflineMapTile(decodeURIComponent(matched[1]), Number(matched[2]), Number(matched[3]), Number(matched[4]));
    if (!tile) return { data: new Uint8Array() };
    if (await sha256(tile.bytes) !== tile.sha256) throw new Error("offline tile integrity check failed");
    return { data: new Uint8Array(tile.bytes) };
  });
  protocolRegistered = true;
}
