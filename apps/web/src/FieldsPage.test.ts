import { geometryBounds } from "./FieldsPage";

test("computes a MapLibre fit bound across assigned multipolygons", () => {
  expect(geometryBounds([{ type: "Feature", id: "field-1", geometry: { type: "MultiPolygon", coordinates: [[[[140.3, 38.2], [140.5, 38.2], [140.5, 38.4], [140.3, 38.2]]]] }, properties: { id: "field-1", fieldGroupId: "group-1", name: "北圃場", cropName: "米", status: "active", areaSqm: 1000, version: 1 } }])).toEqual([[140.3, 38.2], [140.5, 38.4]]);
});
