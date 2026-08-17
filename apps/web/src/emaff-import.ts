type GeoJsonGeometry = { type: "Polygon" | "MultiPolygon"; coordinates: unknown };
type EmaffRow = { externalKey: string; name: string; geometryWkt: string };

const GEOMETRY_COLUMNS = ["geometryWkt", "GeometryWkt", "WKT", "wkt", "geom", "geometry", "Geometry"];
const ID_COLUMNS = ["DaichoId", "polygon_uuid", "農地ID", "id", "ID"]; // i18n-ignore -- eMAFF/CSV source column names
const NAME_COLUMNS = ["Address", "所在・地番", "Tiban", "地番", "name", "名称"]; // i18n-ignore -- eMAFF/CSV source column names

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

function firstString(value: Record<string, unknown>, keys: string[]): string | null {
  for (const key of keys) if (typeof value[key] === "string" && value[key].trim()) return value[key].trim();
  return null;
}

function coordinate(value: unknown): [number, number] {
  if (!Array.isArray(value) || value.length < 2 || !Number.isFinite(value[0]) || !Number.isFinite(value[1])) throw new TypeError("invalid_coordinate");
  const longitude = Number(value[0]); const latitude = Number(value[1]);
  if (longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) throw new TypeError("coordinate_out_of_range");
  return [longitude, latitude];
}

function ring(value: unknown): [number, number][] {
  if (!Array.isArray(value)) throw new TypeError("invalid_ring");
  const points = value.map(coordinate);
  if (points.length < 4 || points[0][0] !== points.at(-1)?.[0] || points[0][1] !== points.at(-1)?.[1]) throw new TypeError("unclosed_ring");
  return points;
}

function polygon(value: unknown): [number, number][][] {
  if (!Array.isArray(value) || value.length === 0) throw new TypeError("invalid_polygon");
  return value.map(ring);
}

function pointText([longitude, latitude]: [number, number]): string { return `${longitude} ${latitude}`; }
function polygonText(value: [number, number][][]): string { return `(${value.map((item) => `(${item.map(pointText).join(",")})`).join(",")})`; }

export function geometryToWkt(geometry: GeoJsonGeometry): string {
  if (geometry.type === "Polygon") return `POLYGON${polygonText(polygon(geometry.coordinates))}`;
  if (geometry.type === "MultiPolygon") {
    if (!Array.isArray(geometry.coordinates) || geometry.coordinates.length === 0) throw new TypeError("invalid_multipolygon");
    return `MULTIPOLYGON(${geometry.coordinates.map((item) => polygonText(polygon(item))).join(",")})`;
  }
  throw new TypeError("polygon_geometry_required");
}

function csvRecords(text: string): Array<Record<string, string>> {
  const rows: string[][] = []; let row: string[] = []; let cell = ""; let quoted = false;
  const source = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
  for (let index = 0; index < source.length; index += 1) {
    const character = source[index];
    if (character === '"') {
      if (quoted && source[index + 1] === '"') { cell += '"'; index += 1; } else quoted = !quoted;
    } else if (character === "," && !quoted) { row.push(cell); cell = ""; }
    else if ((character === "\n" || character === "\r") && !quoted) {
      row.push(cell); if (row.some((value) => value.trim())) rows.push(row); row = []; cell = "";
      if (character === "\r" && source[index + 1] === "\n") index += 1;
    } else cell += character;
  }
  if (cell || row.length) { row.push(cell); if (row.some((value) => value.trim())) rows.push(row); }
  const headers = rows.shift()?.map((value) => value.trim()) || [];
  if (!headers.length) throw new TypeError("empty_file");
  return rows.map((values) => Object.fromEntries(headers.map((header, index) => [header, values[index]?.trim() || ""])));
}

function geometryFromCell(value: string): string {
  if (/^(?:MULTI)?POLYGON\s*\(/i.test(value)) return value;
  const parsed = record(JSON.parse(value));
  if (!parsed || (parsed.type !== "Polygon" && parsed.type !== "MultiPolygon")) throw new TypeError("polygon_geometry_required");
  return geometryToWkt(parsed as GeoJsonGeometry);
}

function fromCsv(text: string): EmaffRow[] {
  return csvRecords(text).map((source, index) => {
    const geometryValue = firstString(source, GEOMETRY_COLUMNS);
    if (!geometryValue) throw new TypeError("csv_polygon_missing");
    const sourceId = firstString(source, ID_COLUMNS) || `row-${index + 2}`;
    return { externalKey: `emaff:${sourceId}`, name: firstString(source, NAME_COLUMNS) || `eMAFF ${sourceId}`, geometryWkt: geometryFromCell(geometryValue) };
  });
}

function fromGeoJson(text: string): EmaffRow[] {
  const root = record(JSON.parse(text));
  const features = root?.type === "FeatureCollection" && Array.isArray(root.features) ? root.features : root?.type === "Feature" ? [root] : null;
  if (!features) throw new TypeError("geojson_feature_collection_required");
  return features.flatMap((item, index) => {
    const feature = record(item); const properties = record(feature?.properties) || {};
    const geometry = record(feature?.geometry);
    if (!geometry || (geometry.type !== "Polygon" && geometry.type !== "MultiPolygon")) return [];
    const sourceId = firstString(properties, ID_COLUMNS) || (typeof feature?.id === "string" || typeof feature?.id === "number" ? String(feature.id) : `feature-${index + 1}`);
    return [{ externalKey: `emaff:${sourceId}`, name: firstString(properties, NAME_COLUMNS) || `eMAFF ${sourceId}`, geometryWkt: geometryToWkt(geometry as GeoJsonGeometry) }];
  });
}

function csvCell(value: string): string { return `"${value.replaceAll('"', '""')}"`; }

export function prepareEmaffImport(text: string, fieldGroupId: string): { csv: string; count: number } {
  if (new TextEncoder().encode(text).byteLength > 10 * 1024 * 1024) throw new RangeError("file_too_large");
  const trimmed = text.trimStart();
  const rows = trimmed.startsWith("{") ? fromGeoJson(text) : fromCsv(text);
  if (!rows.length) throw new TypeError("polygon_geometry_required");
  const header = "externalKey,name,fieldGroupId,geometryWkt,cropName,timezone";
  const values = rows.map((item) => [item.externalKey, item.name, fieldGroupId, item.geometryWkt, "", "Asia/Tokyo"].map(csvCell).join(","));
  return { csv: [header, ...values].join("\n"), count: rows.length };
}
