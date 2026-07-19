-- =====================================================================
-- S2: PostGIS 空間クエリ × RLS 性能
-- 設計書 v4 §6 field を検証。合格基準（5.2.1）: 地図初期表示 2秒/p95。
--   ・gist(tenant_id, geom) 複合索引で「tenant 絞り → 空間」の順にプルーニング
--   ・RLS 述語込みで近傍/包含クエリが索引を使う
--   ・R2-L1/R4-L1: gis_area_sqm = ST_Area(geom::geography) を生成列にできるか（IMMUTABLE性）
-- =====================================================================
\set ON_ERROR_STOP on
SET client_min_messages = NOTICE;

SET ROLE app_owner;

-- R2-L1/R4-L1: ST_Area(geography) を生成列にできるか
DO $$
BEGIN
  BEGIN
    EXECUTE 'CREATE TABLE _probe_area (g geometry(Polygon,4326),
             a numeric GENERATED ALWAYS AS (ST_Area(g::geography)) STORED)';
    RAISE NOTICE 'S2 probe PASS: ST_Area(geography) は生成列に採用可（IMMUTABLE）';
    EXECUTE 'DROP TABLE _probe_area';
  EXCEPTION WHEN others THEN
    RAISE NOTICE 'S2 probe: ST_Area(geography) は生成列不可（%）→ トリガ/ビュー導出へ', SQLERRM;
  END;
END $$;

CREATE TABLE field (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    uuid NOT NULL,
  field_group_id uuid,
  name         text NOT NULL,
  geom         geometry(Polygon,4326) NOT NULL,
  gis_area_sqm numeric GENERATED ALWAYS AS (ST_Area(geom::geography)) STORED,
  timezone     text NOT NULL DEFAULT 'Asia/Tokyo'
);

-- 先に seed（RLS 有効化前。所有者が投入）: 20 テナント × 各 5000 圃場 = 10万件
INSERT INTO field (tenant_id, name, geom)
SELECT t.tid,
       'field-'||t.tid||'-'||g,
       ST_SetSRID(ST_MakeEnvelope(
         140.0 + random()*0.8, 38.0 + random()*0.6,
         140.0 + random()*0.8 + 0.001, 38.0 + random()*0.6 + 0.001), 4326)
FROM (SELECT ('00000000-0000-7000-8000-'||lpad(to_hex(s),12,'0'))::uuid AS tid
      FROM generate_series(1,20) s) t
CROSS JOIN generate_series(1,5000) g;

-- 複合空間索引（tenant_id を先頭に含む）: これがプルーニング＋空間を両立できるかが要点
CREATE INDEX field_tenant_geom_gix ON field USING gist (tenant_id, geom);
-- 比較用: geom 単独 GiST（代替案）
CREATE INDEX field_geom_gix ON field USING gist (geom);
ANALYZE field;

-- seed 後に RLS を有効化
ALTER TABLE field ENABLE ROW LEVEL SECURITY;
ALTER TABLE field FORCE ROW LEVEL SECURITY;
CREATE FUNCTION allowed_tenants2() RETURNS uuid[] LANGUAGE sql STABLE AS $$
  SELECT string_to_array(current_setting('app.allowed_tenants', true), ',')::uuid[]
$$;
CREATE POLICY p_field_boundary ON field AS RESTRICTIVE
  USING (tenant_id = ANY(allowed_tenants2())) WITH CHECK (tenant_id = ANY(allowed_tenants2()));
CREATE POLICY p_field_read ON field AS PERMISSIVE FOR SELECT USING (tenant_id = ANY(allowed_tenants2()));
GRANT SELECT, INSERT ON field TO app_user;
RESET ROLE;

-- ============ 計測 ============
SET ROLE app_user;
\set T '00000000-0000-7000-8000-000000000005'
SET app.allowed_tenants = :'T';

\echo '--- (A) RLS + 空間 bbox クエリ（複合索引が効くか / プルーニング）---'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON)
SELECT id FROM field
WHERE geom && ST_MakeEnvelope(140.2, 38.1, 140.5, 38.4, 4326);

\echo '--- (B) 近傍（KNN）クエリ ---'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON)
SELECT id FROM field
ORDER BY geom <-> ST_SetSRID(ST_MakePoint(140.3, 38.2),4326)
LIMIT 20;

\echo '--- (C) 生成列 gis_area_sqm のサンプル ---'
SELECT count(*) AS n, round(avg(gis_area_sqm)::numeric,2) AS avg_area_sqm FROM field;

RESET ROLE;
\echo '===== S2 完了（(A)(B) の Index Scan 使用・実時間、tenant 絞りの効きを確認） ====='
