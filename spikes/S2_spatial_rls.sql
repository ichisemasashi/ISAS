-- 【v2】文書 §4 の述語形（正規化形・関数ラップなし）に揃えた。
-- ⚠️ 本ファイルは PostGIS が必要。
--   2026-08-13 に PostgreSQL 16.4＋PostGIS 3.4.3 で実行し、&& / <-> が非leakproofであること、
--   bbox の空間条件が Filter に落ちること、KNN は明示的 tenant_id 等値で複合GiSTを使うことを確認した。
-- =====================================================================
-- S2: PostGIS 空間クエリ × RLS 性能
-- 設計書 v4 §6 field を検証。合格基準（5.2.1）: 地図初期表示 2秒/p95。
--   ・gist(tenant_id, geom) 複合索引で「tenant 絞り → 空間」の順に絞り込めるか
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
CREATE POLICY p_field_boundary ON field AS RESTRICTIVE
  USING (tenant_id = ANY(COALESCE(NULLIF(current_setting('app.allowed_tenants', true), ''), '{}')::uuid[])) WITH CHECK (tenant_id = ANY(COALESCE(NULLIF(current_setting('app.allowed_tenants', true), ''), '{}')::uuid[]));
CREATE POLICY p_field_read ON field AS PERMISSIVE FOR SELECT USING (tenant_id = ANY(COALESCE(NULLIF(current_setting('app.allowed_tenants', true), ''), '{}')::uuid[]));
GRANT SELECT, INSERT ON field TO app_user;
RESET ROLE;

-- ============ 計測 ============
SET ROLE app_user;
\set T '00000000-0000-7000-8000-000000000005'
SELECT set_config('app.allowed_tenants', '{' || :'T' || '}', false);

\echo '--- PostGIS演算子のleakproof属性（RLSバリアを越えて索引条件へ入る前提）---'
SELECT o.oid::regoperator AS operator, p.oid::regprocedure AS function, p.proleakproof
FROM pg_operator o JOIN pg_proc p ON p.oid = o.oprcode
WHERE o.oprname IN ('&&','<->')
  AND o.oprleft = 'geometry'::regtype AND o.oprright = 'geometry'::regtype
ORDER BY o.oprname;

\echo '--- (A1) RLSのみ + 空間 bbox（複合索引の空間列までIndex Condへ入るか）---'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON)
SELECT id FROM field
WHERE geom && ST_MakeEnvelope(140.2, 38.1, 140.5, 38.4, 4326);

\echo '--- (A2) tenant_id等値をアプリが明示 + 空間 bbox ---'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON)
SELECT id FROM field
WHERE tenant_id = :'T'::uuid
  AND geom && ST_MakeEnvelope(140.2, 38.1, 140.5, 38.4, 4326);

\echo '--- (B1) RLSのみ + 近傍（KNN）---'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON)
SELECT id FROM field
ORDER BY geom <-> ST_SetSRID(ST_MakePoint(140.3, 38.2),4326)
LIMIT 20;

\echo '--- (B2) tenant_id等値をアプリが明示 + 近傍（KNN）---'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON)
SELECT id FROM field
WHERE tenant_id = :'T'::uuid
ORDER BY geom <-> ST_SetSRID(ST_MakePoint(140.3, 38.2),4326)
LIMIT 20;

\echo '--- (C) 生成列 gis_area_sqm のサンプル ---'
SELECT count(*) AS n, round(avg(gis_area_sqm)::numeric,2) AS avg_area_sqm FROM field;

RESET ROLE;
\echo '===== S2 完了（A1/A2/B1/B2 の索引条件・実時間・Rows Removed を比較） ====='
