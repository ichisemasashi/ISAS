import assert from "node:assert/strict";
import { test } from "node:test";
import { createS3MapStorage } from "../src/s3-map-storage.mjs";

test("verifies the deployed PMTiles artifact and proxies bounded ranges through the BFF", async () => {
  const sent = [];
  const storage = createS3MapStorage({
    s3: { async send(command) { sent.push(command); return command.input.Range
      ? { ContentRange: "bytes 0-2/100", ETag: '"etag"', Body: { async transformToByteArray() { return new Uint8Array([1, 2, 3]); } } }
      : { ContentLength: 100, Metadata: { sha256: "a".repeat(64), "tileset-version": "jp-2026-08", "source-license": "ODbL-1.0" }, ContentType: "application/vnd.pmtiles" }; } },
    bucket: "private-map-bucket", archiveKey: "tilesets/jp-2026-08/japan.pmtiles", tilesetVersion: "jp-2026-08",
    archiveSha256: "a".repeat(64), clock: () => 0,
  });
  await storage.startupCheck();
  const manifest = await storage.packManifest({
    tenantId: "tenant", userId: "user", fieldGroupId: "group", assignmentVersion: "12",
    bbox: [140.1, 38.1, 140.5, 38.5],
  });
  assert.match(manifest.archiveUrl, /^\/api\/v1\/offline-map-archive\?/);
  assert.equal(manifest.maxBytes, 250 * 1024 * 1024);
  assert.equal(manifest.archiveUrlExpiresAt, "1970-01-01T00:05:00.000Z");
  assert.match(manifest.attribution, /OpenStreetMap/);
  const range = await storage.readRange("bytes=0-2", "jp-2026-08");
  assert.deepEqual([...range.bytes], [1, 2, 3]);
  assert.equal(sent.at(-1).input.Range, "bytes=0-2");
  await assert.rejects(() => storage.readRange("bytes=0-9999999", "jp-2026-08"), /range is invalid/);
});
