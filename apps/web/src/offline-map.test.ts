import { FetchSource } from "pmtiles";
import { authorizedArchive, tilesForBounds } from "./offline-map";

test("adds the server-derived AuthContext to every same-origin PMTiles range", () => {
  const archive = authorizedArchive("/api/v1/offline-map-archive?fieldGroupId=group", "context-1");
  expect(archive.source).toBeInstanceOf(FetchSource);
  expect((archive.source as FetchSource).customHeaders.get("X-ISAS-Context")).toBe("context-1");
});

test("enumerates only the assigned bbox tile ranges for zoom 8 through 16", () => {
  const tiles = tilesForBounds([140.30, 38.20, 140.34, 38.24], 8, 16);
  expect(tiles.length).toBeGreaterThan(9);
  expect(tiles.length).toBeLessThan(500);
  expect(tiles[0].z).toBe(8);
  expect(tiles.at(-1)?.z).toBe(16);
  expect(new Set(tiles.map(({ z, x, y }) => `${z}/${x}/${y}`)).size).toBe(tiles.length);
});

test("clamps coordinates at the web mercator world edge", () => {
  const tiles = tilesForBounds([-180, -90, 180, 90], 1, 1);
  expect(tiles).toEqual([
    { z: 1, x: 0, y: 0 }, { z: 1, x: 0, y: 1 },
    { z: 1, x: 1, y: 0 }, { z: 1, x: 1, y: 1 },
  ]);
});
