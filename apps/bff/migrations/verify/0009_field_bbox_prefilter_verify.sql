\set ON_ERROR_STOP on
\set A  '11111111-1111-7111-8111-111111111111'

CREATE OR REPLACE FUNCTION pg_temp.ck(condition boolean, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF NOT condition THEN RAISE EXCEPTION 'FAIL  %', label; END IF;
  RAISE NOTICE 'PASS  %', label;
END $$;

SELECT pg_temp.ck(
  bbox_min_x = 140.30 AND bbox_max_x = 140.31 AND bbox_min_y = 38.20 AND bbox_max_y = 38.21,
  '(1) 既存geomから数値bboxを生成'
) FROM app.field WHERE tenant_id = :'A' AND name = '北圃場';

SELECT pg_temp.ck(count(*) = 1, '(2) 数値bbox事前絞込と厳密PostGIS判定が一致')
FROM app.field
WHERE tenant_id = :'A'
  AND bbox_min_x <= 140.32 AND bbox_max_x >= 140.29
  AND bbox_min_y <= 38.22 AND bbox_max_y >= 38.19
  AND geom && ST_MakeEnvelope(140.29, 38.19, 140.32, 38.22, 4326);

SELECT pg_temp.ck(count(*) = 4, '(3) tenant付き数値bbox索引を4本作成')
FROM pg_indexes
WHERE schemaname = 'app' AND tablename = 'field' AND indexname LIKE 'field_tenant_bbox_%_idx';

\echo 'Field bbox prefilter migration: 3 groups PASS'
