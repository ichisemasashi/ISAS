import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";

vi.mock("maplibre-gl", () => {
  class MockMap {
    sources = new Map();
    constructor() {}
    addControl() {}
    addSource(id: string, source: unknown) { this.sources.set(id, { ...source as object, setData: vi.fn() }); }
    addLayer() {}
    getSource(id: string) { return this.sources.get(id); }
    isStyleLoaded() { return true; }
    fitBounds() {}
    setFeatureState() {}
    getCanvas() { return { style: { cursor: "" } }; }
    getBounds() { return { getWest: () => 140.2, getSouth: () => 38.1, getEast: () => 140.5, getNorth: () => 38.4 }; }
    on(event: string, layerOrHandler: unknown, handler?: () => void) {
      if (event === "load") queueMicrotask(layerOrHandler as () => void);
      if (event === "moveend") (layerOrHandler as () => void)();
      return handler;
    }
    remove() {}
  }
  return { Map: MockMap, NavigationControl: class {}, LngLatBounds: class {}, addProtocol: vi.fn(), setWorkerUrl: vi.fn() };
});

import { FieldsPage } from "./FieldsPage";
import type { MvpGateway } from "./api";
import { demoAuthorization } from "./auth";
import type { StorageGateway } from "./storage";

const field = { type: "Feature" as const, id: "field-1", geometry: { type: "MultiPolygon" as const, coordinates: [[[[140.3, 38.2], [140.5, 38.2], [140.5, 38.4], [140.3, 38.2]]]] }, properties: { id: "field-1", fieldGroupId: "group-1", name: "北圃場", cropName: "つや姫", status: "active" as const, areaSqm: 1000, version: 1 } };

test("renders the assigned-field list from IndexedDB cache while offline", async () => {
  const api = { getFields: vi.fn() } as unknown as MvpGateway;
  const storage = { getFields: vi.fn(async () => [field]), getLatestOfflineMapPack: vi.fn(async () => null) } as unknown as StorageGateway;
  render(<FieldsPage api={api} storage={storage} authorization={demoAuthorization} online={false} csrfToken="csrf" setNotice={vi.fn()} />);
  expect(await screen.findByRole("button", { name: /北圃場/ })).toBeInTheDocument();
  expect(screen.getByText(/端末保存済み/)).toBeInTheDocument();
  expect(api.getFields).not.toHaveBeenCalled();
});

test("does not search the default map extent before assigned fields are fitted", async () => {
  const getFields = vi.fn(async (_contextId: string, options: { bbox?: number[] }) => {
    if (options.bbox) return { type: "FeatureCollection" as const, features: [], nextCursor: null };
    return { type: "FeatureCollection" as const, features: [field], nextCursor: null };
  });
  const api = { getFields } as unknown as MvpGateway;
  const storage = {
    getFields: vi.fn(async () => []),
    saveFields: vi.fn(async () => undefined),
    getLatestOfflineMapPack: vi.fn(async () => null),
  } as unknown as StorageGateway;

  render(<FieldsPage api={api} storage={storage} authorization={demoAuthorization} online csrfToken="csrf" setNotice={vi.fn()} />);

  expect(await screen.findByRole("button", { name: /北圃場/ })).toBeInTheDocument();
  expect(getFields).toHaveBeenCalledTimes(1);
  expect(getFields.mock.calls[0]?.[1]).not.toHaveProperty("bbox");
});

test("an administrator imports eMAFF GeoJSON and refreshes the field map from one button", async () => {
  const imported = { ...field, id: "field-2", properties: { ...field.properties, id: "field-2", name: "米沢市簗沢3335" } };
  const getFields = vi.fn()
    .mockResolvedValueOnce({ type: "FeatureCollection", features: [field], nextCursor: null })
    .mockResolvedValueOnce({ type: "FeatureCollection", features: [field, imported], nextCursor: null });
  const createMigrationJob = vi.fn(async () => ({ id: "job-1", dataset: "fields", sourceName: "emaff.geojson", sourceSha256: "a".repeat(64), mapping: {}, status: "validated", rowCount: 1, validCount: 1, duplicateCount: 0, errorCount: 0, version: 1, createdAt: new Date().toISOString(), committedAt: null, rows: [] }));
  const commitMigrationJob = vi.fn(async () => ({ id: "job-1", dataset: "fields", sourceName: "emaff.geojson", sourceSha256: "a".repeat(64), mapping: {}, status: "committed", rowCount: 1, validCount: 1, duplicateCount: 0, errorCount: 0, version: 2, createdAt: new Date().toISOString(), committedAt: new Date().toISOString(), rows: [] }));
  const api = { getFields, createMigrationJob, commitMigrationJob } as unknown as MvpGateway;
  const storage = { getFields: vi.fn(async () => []), saveFields: vi.fn(async () => undefined), getLatestOfflineMapPack: vi.fn(async () => null) } as unknown as StorageGateway;
  const authorization = { ...demoAuthorization, context: { ...demoAuthorization.context, capabilities: [...demoAuthorization.context.capabilities, "migration:manage"] } };
  const notice = vi.fn();
  render(<FieldsPage api={api} storage={storage} authorization={authorization} online csrfToken="csrf" setNotice={notice} />);
  await screen.findByRole("button", { name: /北圃場/ });
  const fileInput = document.querySelector<HTMLInputElement>('input[type="file"]');
  expect(fileInput).not.toBeNull();
  const geojson = JSON.stringify({ type: "FeatureCollection", features: [{ type: "Feature", properties: { DaichoId: "D-3335", Address: "米沢市簗沢3335" }, geometry: { type: "Polygon", coordinates: [[[140.01, 37.91], [140.02, 37.91], [140.02, 37.92], [140.01, 37.91]]] } }] });
  await userEvent.upload(fileInput!, new File([geojson], "emaff.geojson", { type: "application/geo+json" }));
  expect(await screen.findByRole("button", { name: /米沢市簗沢3335/ })).toBeInTheDocument();
  expect(createMigrationJob).toHaveBeenCalledOnce();
  expect(commitMigrationJob).toHaveBeenCalledWith(demoAuthorization.context.contextId, "csrf", "job-1", 1);
  expect(notice).toHaveBeenCalledWith(expect.stringContaining("1件登録"));
});
