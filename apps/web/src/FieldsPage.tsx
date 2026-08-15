import { useEffect, useMemo, useRef, useState } from "react";
import { LngLatBounds, Map, NavigationControl, type GeoJSONSource, type StyleSpecification } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import type { FieldCollection, FieldFeature, MvpGateway } from "./api";
import type { AppAuthorization } from "./auth";
import type { StorageGateway } from "./storage";
import type { OfflineMapPackRecord } from "./storage";
import { downloadOfflineMapPack, registerOfflineMapProtocol } from "./offline-map";

const OFFLINE_STYLE: StyleSpecification = {
  version: 8,
  sources: {},
  layers: [{ id: "background", type: "background", paint: { "background-color": "#e8eee7" } }],
};
const GSI_STYLE: StyleSpecification = {
  version: 8,
  sources: { gsi: { type: "raster", tiles: ["https://cyberjapandata.gsi.go.jp/xyz/std/{z}/{x}/{y}.png"], tileSize: 256, attribution: "地理院タイル" } },
  layers: [
    { id: "background", type: "background", paint: { "background-color": "#e8eee7" } },
    { id: "gsi-standard", type: "raster", source: "gsi" },
  ],
};

function offlinePackStyle(pack: OfflineMapPackRecord): StyleSpecification {
  return {
    version: 8,
    sources: { offline: { type: "vector", tiles: [`isas-offline://${encodeURIComponent(pack.packId)}/{z}/{x}/{y}`], minzoom: pack.minZoom, maxzoom: pack.maxZoom, attribution: pack.attribution } },
    layers: [
      { id: "background", type: "background", paint: { "background-color": "#e8eee7" } },
      { id: "water", type: "fill", source: "offline", "source-layer": "water", paint: { "fill-color": "#b7d9e8" } },
      { id: "landcover", type: "fill", source: "offline", "source-layer": "landcover", paint: { "fill-color": "#d9e4cf", "fill-opacity": 0.7 } },
      { id: "roads", type: "line", source: "offline", "source-layer": "transportation", paint: { "line-color": "#ffffff", "line-width": 1.4 } },
      { id: "boundaries", type: "line", source: "offline", "source-layer": "boundary", paint: { "line-color": "#89958c", "line-dasharray": [2, 2] } },
    ],
  };
}

export function geometryBounds(fields: FieldFeature[]): [[number, number], [number, number]] | null {
  const points = fields.flatMap((field) => field.geometry.coordinates.flatMap((polygon) => polygon.flatMap((ring) => ring)));
  if (!points.length) return null;
  return [
    [Math.min(...points.map(([lng]) => lng)), Math.min(...points.map(([, lat]) => lat))],
    [Math.max(...points.map(([lng]) => lng)), Math.max(...points.map(([, lat]) => lat))],
  ];
}

function asCollection(fields: FieldFeature[]): FieldCollection { return { type: "FeatureCollection", features: fields, nextCursor: null }; }

function FieldMap({ fields, selectedId, onSelect, onBounds, backgroundStyle }: { fields: FieldFeature[]; selectedId: string | null; onSelect: (id: string) => void; onBounds: (bbox: [number, number, number, number]) => void; backgroundStyle: string | StyleSpecification }) {
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<Map | null>(null);
  const fitted = useRef(false);
  const fieldsRef = useRef(fields);
  fieldsRef.current = fields;

  useEffect(() => {
    if (!container.current) return;
    const value = new Map({ container: container.current, style: backgroundStyle, center: [140.35, 38.25], zoom: 10 });
    map.current = value;
    value.addControl(new NavigationControl({ showCompass: false }), "top-right");
    value.on("load", () => {
      value.addSource("assigned-fields", { type: "geojson", data: asCollection(fieldsRef.current), promoteId: "id" });
      value.addLayer({ id: "assigned-fields-fill", type: "fill", source: "assigned-fields", paint: { "fill-color": ["case", ["boolean", ["feature-state", "selected"], false], "#dce94f", "#216a4d"], "fill-opacity": 0.48 } });
      value.addLayer({ id: "assigned-fields-line", type: "line", source: "assigned-fields", paint: { "line-color": "#0d3327", "line-width": 2 } });
      value.on("click", "assigned-fields-fill", (event) => { const id = event.features?.[0]?.properties?.id; if (typeof id === "string") onSelect(id); });
      value.on("mouseenter", "assigned-fields-fill", () => { value.getCanvas().style.cursor = "pointer"; });
      value.on("mouseleave", "assigned-fields-fill", () => { value.getCanvas().style.cursor = ""; });
      const bounds = geometryBounds(fieldsRef.current);
      if (bounds) { value.fitBounds(new LngLatBounds(bounds[0], bounds[1]), { padding: 48, maxZoom: 16, duration: 0 }); fitted.current = true; }
    });
    value.on("moveend", () => { const bounds = value.getBounds(); onBounds([bounds.getWest(), bounds.getSouth(), bounds.getEast(), bounds.getNorth()]); });
    return () => { value.remove(); map.current = null; };
  }, []);

  useEffect(() => {
    const value = map.current;
    if (!value?.isStyleLoaded()) return;
    (value.getSource("assigned-fields") as GeoJSONSource | undefined)?.setData(asCollection(fields));
    if (!fitted.current) {
      const bounds = geometryBounds(fields);
      if (bounds) { value.fitBounds(new LngLatBounds(bounds[0], bounds[1]), { padding: 48, maxZoom: 16, duration: 0 }); fitted.current = true; }
    }
  }, [fields]);

  useEffect(() => {
    const value = map.current;
    if (!value?.getSource("assigned-fields")) return;
    for (const field of fields) value.setFeatureState({ source: "assigned-fields", id: field.id }, { selected: field.id === selectedId });
  }, [fields, selectedId]);

  return <div ref={container} className="field-map" role="img" aria-label="担当圃場の地図" />;
}

export function FieldsPage({ api, storage, authorization, online }: { api: MvpGateway; storage: StorageGateway; authorization: AppAuthorization; online: boolean }) {
  const tenantId = authorization.context.tenantId;
  const [fields, setFields] = useState<FieldFeature[]>([]);
  const [mapFields, setMapFields] = useState<FieldFeature[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("担当圃場を読み込んでいます。");
  const [offlinePack, setOfflinePack] = useState<OfflineMapPackRecord | null>(null);
  const [preferOffline, setPreferOffline] = useState(false);
  const [mapDownload, setMapDownload] = useState("");
  const boundsRequest = useRef<AbortController | null>(null);

  useEffect(() => {
    registerOfflineMapProtocol(storage);
    storage.getLatestOfflineMapPack(tenantId, authorization.context.membershipVersion).then((pack) => {
      setOfflinePack(pack);
      if (!online && pack) setPreferOffline(true);
    }).catch(() => undefined);
  }, [authorization.context.membershipVersion, online, storage, tenantId]);

  useEffect(() => {
    const controller = new AbortController();
    storage.getFields(tenantId).then((cached) => { if (!controller.signal.aborted) { setFields(cached); setMapFields(cached); setStatus(cached.length ? "端末保存済みの担当圃場を表示しています。" : "保存済みの圃場はありません。"); } }).catch(() => setStatus("端末の圃場保存を確認できませんでした。"));
    if (online) (async () => {
      const all: FieldFeature[] = []; let cursor: string | null = null;
      do { const page = await api.getFields(authorization.context.contextId, { limit: 500, cursor }, controller.signal); all.push(...page.features); cursor = page.nextCursor; } while (cursor && !controller.signal.aborted);
      await storage.saveFields(tenantId, all);
      if (!controller.signal.aborted) { setFields(all); setMapFields(all); setStatus(`担当圃場${all.length}件を更新しました。`); }
    })().catch(() => { if (!controller.signal.aborted) setStatus("APIを利用できないため、端末保存済みの圃場を表示しています。"); });
    return () => controller.abort();
  }, [api, authorization.context.contextId, online, storage, tenantId]);

  const visible = useMemo(() => fields.filter((field) => !query || field.properties.name.includes(query) || field.properties.cropName?.includes(query)), [fields, query]);
  const selected = fields.find((field) => field.id === selectedId) || null;
  const useOffline = Boolean(offlinePack && (!online || preferOffline));
  const backgroundStyle: string | StyleSpecification = useOffline && offlinePack
    ? offlinePackStyle(offlinePack)
    : (online ? GSI_STYLE : OFFLINE_STYLE);
  const cacheSelectedScope = async () => {
    if (!selected || !online) return;
    setMapDownload("担当圃場周辺の背景を取得しています。Wi-Fi利用を推奨します。");
    try {
      const pack = await downloadOfflineMapPack({ api, storage, authorization, fieldGroupId: selected.properties.fieldGroupId,
        onProgress: (done, total, bytes) => { if (done % 25 === 0) setMapDownload(`${done}/${total}タイル・${Math.ceil(bytes / 1048576)} MiBを保存中です。`); } });
      setOfflinePack(pack); setPreferOffline(true);
      setMapDownload(`${pack.tileCount}タイル・${Math.ceil(pack.byteSize / 1048576)} MiBを端末へ保存しました。`);
    } catch (error) {
      setMapDownload(error instanceof RangeError ? "250 MiB上限を超えるため背景を保存できません。対象圃場を分けてください。" : "背景の取得を完了できませんでした。圃場境界は引き続き利用できます。");
    }
  };
  const searchBounds = (bbox: [number, number, number, number]) => {
    if (!online) return;
    boundsRequest.current?.abort();
    const controller = new AbortController(); boundsRequest.current = controller;
    api.getFields(authorization.context.contextId, { bbox, limit: 500 }, controller.signal).then((page) => setMapFields(page.features)).catch(() => undefined);
  };

  return <div className="page-content fields-page">
    <div className="form-heading"><span className="section-kicker">FIELD GIS</span><h1>担当圃場</h1><p role="status">{status}</p></div>
    <label className="field-search">圃場名・作物で絞り込み<input type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="例：北圃場、つや姫" /></label>
    <div className="field-gis-grid">
      <section className="field-list" aria-label="担当圃場一覧">
        <strong>{visible.length}件</strong>
        {visible.map((field) => <button key={field.id} className={selectedId === field.id ? "selected" : ""} onClick={() => setSelectedId(field.id)}><span><b>{field.properties.name}</b><small>{field.properties.cropName || "作物未設定"}</small></span><span>{Math.round(field.properties.areaSqm).toLocaleString()}㎡</span></button>)}
        {!visible.length && <p>条件に一致する担当圃場はありません。</p>}
      </section>
      <div className="map-panel">
        <div className="map-cache-controls">
          <button type="button" onClick={cacheSelectedScope} disabled={!online || !selected}>選択圃場周辺をオフライン保存</button>
          {offlinePack && <button type="button" onClick={() => setPreferOffline((value) => !value)}>{useOffline ? "オンライン背景へ切替" : "保存済み背景へ切替"}</button>}
          <span role="status">{mapDownload}</span>
        </div>
        <FieldMap key={useOffline ? offlinePack?.packId : online ? "online" : "blank"} fields={mapFields} selectedId={selectedId} onSelect={setSelectedId} onBounds={searchBounds} backgroundStyle={backgroundStyle} />
        <small className="map-attribution">{useOffline ? <><a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noreferrer">© OpenStreetMap contributors</a>・保存期限 {offlinePack && new Date(offlinePack.expiresAt).toLocaleDateString("ja-JP")}</> : online ? <a href="https://maps.gsi.go.jp/development/ichiran.html" target="_blank" rel="noreferrer">地理院タイル</a> : "背景なし（圃場境界のみ）"}</small>
        {selected && <div className="field-detail"><strong>{selected.properties.name}</strong><span>{selected.properties.cropName || "作物未設定"}・{Math.round(selected.properties.areaSqm).toLocaleString()}㎡</span></div>}
      </div>
    </div>
  </div>;
}
