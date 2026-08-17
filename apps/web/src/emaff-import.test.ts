import { geometryToWkt, prepareEmaffImport } from "./emaff-import";

test("converts eMAFF GeoJSON polygons to canonical migration CSV", () => {
  const input = JSON.stringify({ type: "FeatureCollection", features: [
    { type: "Feature", properties: { DaichoId: "D-3335", Address: "山形県米沢市簗沢3335" }, geometry: { type: "Polygon", coordinates: [[[140.01, 37.91], [140.02, 37.91], [140.02, 37.92], [140.01, 37.91]]] } },
    { type: "Feature", properties: { DaichoId: "PIN" }, geometry: { type: "Point", coordinates: [140.01, 37.91] } },
  ] });
  const result = prepareEmaffImport(input, "30000000-0000-4000-8000-000000000001");
  expect(result.count).toBe(1);
  expect(result.csv).toContain('"emaff:D-3335","山形県米沢市簗沢3335"');
  expect(result.csv).toContain('"POLYGON((140.01 37.91,140.02 37.91,140.02 37.92,140.01 37.91))"');
});

test("accepts converted eMAFF CSV with WKT geometry", () => {
  const input = 'DaichoId,Address,WKT\nD-1,簗沢3335,"POLYGON((140.01 37.91,140.02 37.91,140.02 37.92,140.01 37.91))"';
  expect(prepareEmaffImport(input, "group-1").count).toBe(1);
});

test("rejects metadata-only CSV and unclosed polygons", () => {
  expect(() => prepareEmaffImport("DaichoId,Address\nD-1,簗沢3335", "group-1")).toThrow("csv_polygon_missing");
  expect(() => geometryToWkt({ type: "Polygon", coordinates: [[[140.01, 37.91], [140.02, 37.91], [140.02, 37.92], [140.03, 37.93]]] })).toThrow("unclosed_ring");
});
