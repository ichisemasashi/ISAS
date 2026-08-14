import { render, screen } from "@testing-library/react";
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
    on(event: string, layerOrHandler: unknown, handler?: () => void) { if (event === "load") queueMicrotask(layerOrHandler as () => void); return handler; }
    remove() {}
  }
  return { Map: MockMap, NavigationControl: class {}, LngLatBounds: class {} };
});

import { FieldsPage } from "./FieldsPage";
import type { MvpGateway } from "./api";
import { demoAuthorization } from "./auth";
import type { StorageGateway } from "./storage";

const field = { type: "Feature" as const, id: "field-1", geometry: { type: "MultiPolygon" as const, coordinates: [[[[140.3, 38.2], [140.5, 38.2], [140.5, 38.4], [140.3, 38.2]]]] }, properties: { id: "field-1", fieldGroupId: "group-1", name: "北圃場", cropName: "つや姫", status: "active" as const, areaSqm: 1000, version: 1 } };

test("renders the assigned-field list from IndexedDB cache while offline", async () => {
  const api = { getFields: vi.fn() } as unknown as MvpGateway;
  const storage = { getFields: vi.fn(async () => [field]) } as unknown as StorageGateway;
  render(<FieldsPage api={api} storage={storage} authorization={demoAuthorization} online={false} />);
  expect(await screen.findByRole("button", { name: /北圃場/ })).toBeInTheDocument();
  expect(screen.getByText(/端末保存済み/)).toBeInTheDocument();
  expect(api.getFields).not.toHaveBeenCalled();
});
