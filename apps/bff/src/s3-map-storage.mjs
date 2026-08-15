import { GetObjectCommand, HeadObjectCommand } from "@aws-sdk/client-s3";

const SHA256 = /^[0-9a-f]{64}$/;

export function createS3MapStorage({
  s3,
  bucket,
  archiveKey,
  tilesetVersion,
  archiveSha256,
  manifestTtlSeconds = 300,
  installationLimitBytes = 250 * 1024 * 1024,
  packRetentionDays = 30,
  clock = () => Date.now(),
}) {
  if (!s3?.send || !bucket || !/^tilesets\/[A-Za-z0-9._-]+\/japan\.pmtiles$/.test(archiveKey || "")
    || !/^[A-Za-z0-9._-]{1,100}$/.test(tilesetVersion || "") || !SHA256.test(archiveSha256 || "")) throw new Error("Offline map storage configuration is invalid");
  if (!Number.isInteger(manifestTtlSeconds) || manifestTtlSeconds < 60 || manifestTtlSeconds > 300) throw new Error("Offline map manifest TTL must be between 60 and 300 seconds");

  return Object.freeze({
    async startupCheck() {
      const object = await s3.send(new HeadObjectCommand({ Bucket: bucket, Key: archiveKey }));
      if (!object.ContentLength || object.Metadata?.sha256 !== archiveSha256 || object.Metadata?.["tileset-version"] !== tilesetVersion
        || object.Metadata?.["source-license"] !== "ODbL-1.0" || object.ContentType !== "application/vnd.pmtiles") {
        throw new Error("Offline PMTiles metadata does not match the deployment manifest");
      }
    },
    async packManifest(authorization) {
      if (!authorization?.fieldGroupId || !Array.isArray(authorization.bbox) || authorization.bbox.length !== 4) throw new TypeError("invalid offline map authorization");
      return {
        packId: `${authorization.tenantId}:${authorization.userId}:${authorization.fieldGroupId}:${authorization.assignmentVersion}:${tilesetVersion}`,
        fieldGroupId: authorization.fieldGroupId,
        assignmentVersion: authorization.assignmentVersion,
        tilesetVersion,
        archiveSha256,
        archiveUrl: `/api/v1/offline-map-archive?fieldGroupId=${encodeURIComponent(authorization.fieldGroupId)}&tilesetVersion=${encodeURIComponent(tilesetVersion)}`,
        archiveUrlExpiresAt: new Date(clock() + manifestTtlSeconds * 1000).toISOString(),
        bbox: authorization.bbox,
        minZoom: 8,
        maxZoom: 16,
        maxBytes: installationLimitBytes,
        expiresAt: new Date(clock() + packRetentionDays * 86400000).toISOString(),
        attribution: "© OpenStreetMap contributors",
        licenseUrl: "https://www.openstreetmap.org/copyright",
      };
    },
    async readRange(range, requestedVersion) {
      if (requestedVersion !== tilesetVersion) throw Object.assign(new Error("offline tileset version is unavailable"), { code: "offline_tileset_changed" });
      const match = /^bytes=(\d+)-(\d+)$/.exec(range || "");
      if (!match) throw Object.assign(new Error("a single bounded byte range is required"), { code: "invalid_range" });
      const start = Number(match[1]); const end = Number(match[2]);
      if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start > end || end - start + 1 > 4 * 1024 * 1024) {
        throw Object.assign(new Error("offline map range is invalid"), { code: "invalid_range" });
      }
      const object = await s3.send(new GetObjectCommand({ Bucket: bucket, Key: archiveKey, Range: `bytes=${start}-${end}` }));
      if (!object.Body?.transformToByteArray || !object.ContentRange) throw new Error("Offline PMTiles range response is incomplete");
      const bytes = await object.Body.transformToByteArray();
      return { bytes, contentRange: object.ContentRange, etag: object.ETag };
    },
  });
}
