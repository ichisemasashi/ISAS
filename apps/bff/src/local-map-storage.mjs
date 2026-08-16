import { createHash } from "node:crypto";
import { mkdir, open, readFile, stat, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

export function createLocalMapStorage({ root, clock = () => Date.now() }) {
  const archive = resolve(root || "", "maps/japan.pmtiles");
  if (!archive.startsWith("/var/lib/isas/objects/")) throw new Error("local map path is outside the object volume");
  let metadata;
  async function load() {
    const bytes = await readFile(archive);
    metadata = { byteSize: bytes.length, sha256: createHash("sha256").update(bytes).digest("hex"), version: "local-fixture-v1" };
  }
  return Object.freeze({
    async startupCheck() {
      await mkdir(resolve(root, "maps"), { recursive: true, mode: 0o700 });
      try { await stat(archive); } catch (error) { if (error.code !== "ENOENT") throw error; await writeFile(archive, Buffer.from("PMTiles\u0003ISAS-local-fixture"), { mode: 0o600, flag: "wx" }); }
      await load();
    },
    async packManifest(authorization) {
      if (!metadata || !authorization?.fieldGroupId || !Array.isArray(authorization.bbox)) throw new TypeError("invalid offline map authorization");
      return { packId: `${authorization.tenantId}:${authorization.userId}:${authorization.fieldGroupId}:${authorization.assignmentVersion}:${metadata.version}`, fieldGroupId: authorization.fieldGroupId, assignmentVersion: authorization.assignmentVersion, tilesetVersion: metadata.version, archiveSha256: metadata.sha256, archiveUrl: `/api/v1/offline-map-archive?fieldGroupId=${encodeURIComponent(authorization.fieldGroupId)}&tilesetVersion=${metadata.version}`, archiveUrlExpiresAt: new Date(clock() + 300000).toISOString(), bbox: authorization.bbox, minZoom: 8, maxZoom: 16, maxBytes: 250 * 1024 * 1024, expiresAt: new Date(clock() + 30 * 86400000).toISOString(), attribution: "Synthetic local fixture", licenseUrl: "about:blank" };
    },
    async readRange(range, requestedVersion) {
      if (!metadata || requestedVersion !== metadata.version) throw Object.assign(new Error("offline tileset version is unavailable"), { code: "offline_tileset_changed" });
      const match = /^bytes=(\d+)-(\d+)$/.exec(range || "");
      if (!match) throw Object.assign(new Error("a single bounded byte range is required"), { code: "invalid_range" });
      const start = Number(match[1]); const end = Number(match[2]);
      if (start > end || end - start + 1 > 4 * 1024 * 1024 || end >= metadata.byteSize) throw Object.assign(new Error("offline map range is invalid"), { code: "invalid_range" });
      const handle = await open(archive, "r");
      try { const bytes = Buffer.alloc(end - start + 1); await handle.read(bytes, 0, bytes.length, start); return { bytes, contentRange: `bytes ${start}-${end}/${metadata.byteSize}`, etag: `\"${metadata.sha256}\"` }; } finally { await handle.close(); }
    }
  });
}
