import { useEffect, useMemo, useRef, useState } from "react";
import { LngLatBounds, Map, NavigationControl, type GeoJSONSource, type StyleSpecification } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import type { FieldCollection, FieldFeature, MvpGateway } from "./api";
import type { AppAuthorization } from "./auth";
import type { StorageGateway } from "./storage";

const OFFLINE_STYLE: StyleSpecification = {
  version: 8,
  sources: {},
  layers: [{ id: "background", type: "background", paint: { "background-color": "#e8eee7" } }],
};
const MAP_STYLE: string | StyleSpecification = import.meta.env.VITE_MAP_STYLE_URL || OFFLINE_STYLE;

export function geometryBounds(fields: FieldFeature[]): [[number, number], [number, number]] | null {
  const points = fields.flatMap((field) => field.geometry.coordinates.flatMap((polygon) => polygon.flatMap((ring) => ring)));
  if (!points.length) return null;
  return [
    [Math.min(...points.map(([lng]) => lng)), Math.min(...points.map(([, lat]) => lat))],
    [Math.max(...points.map(([lng]) => lng)), Math.max(...points.map(([, lat]) => lat))],
  ];
}

function asCollection(fields: FieldFeature[]): FieldCollection { return { type: "FeatureCollection", features: fields, nextCursor: null }; }

function FieldMap({ fields, selectedId, onSelect, onBounds }: { fields: FieldFeature[]; selectedId: string | null; onSelect: (id: string) => void; onBounds: (bbox: [number, number, number, number]) => void }) {
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<Map | null>(null);
  const fitted = useRef(false);
  const fieldsRef = useRef(fields);
  fieldsRef.current = fields;

  useEffect(() => {
    if (!container.current) return;
    const value = new Map({ container: container.current, style: MAP_STYLE, center: [140.35, 38.25], zoom: 10 });
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
  const boundsRequest = useRef<AbortController | null>(null);

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
      <div className="map-panel"><FieldMap fields={mapFields} selectedId={selectedId} onSelect={setSelectedId} onBounds={searchBounds} />{selected && <div className="field-detail"><strong>{selected.properties.name}</strong><span>{selected.properties.cropName || "作物未設定"}・{Math.round(selected.properties.areaSqm).toLocaleString()}㎡</span></div>}</div>
    </div>
  </div>;
}
