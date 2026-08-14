\set ON_ERROR_STOP on
\set A  '11111111-1111-7111-8111-111111111111'
\set B  '22222222-2222-7222-8222-222222222222'
\set U2 'bbbbbbbb-0000-7000-8000-000000000002'
\set F1 'f1111111-1111-7111-8111-111111111111'
\set F2 'f2222222-2222-7222-8222-222222222222'

CREATE OR REPLACE FUNCTION pg_temp.ck(condition boolean, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF NOT condition THEN RAISE EXCEPTION 'FAIL  %', label; END IF;
  RAISE NOTICE 'PASS  %', label;
END $$;

INSERT INTO app.field (tenant_id, field_id, field_group_id, name, crop_name, geom) VALUES
  (:'A', '0198a6c0-0000-7000-8000-000000000101', :'F1', '北圃場', 'つや姫', ST_Multi(ST_MakeEnvelope(140.30, 38.20, 140.31, 38.21, 4326))),
  (:'A', '0198a6c0-0000-7000-8000-000000000102', :'F2', '南圃場', '雪若丸', ST_Multi(ST_MakeEnvelope(140.40, 38.30, 140.41, 38.31, 4326))),
  (:'B', '0198a6c0-0000-7000-8000-000000000103', :'F1', '他社圃場', '麦', ST_Multi(ST_MakeEnvelope(140.30, 38.20, 140.31, 38.21, 4326)));

SET ROLE app_user;
SELECT set_config('app.user_id', :'U2', false);
SELECT set_config('app.tenant_id', :'A', false);
SELECT set_config('app.allowed_tenants', ('{' || :'A' || '}'), false);
SELECT set_config('app.scope_field_groups', ('{' || :'F1' || '}'), false);
SELECT set_config('app.caps', '{journal:write}', false);
SELECT set_config('app.employer_subject_users', '{}', false);
SELECT set_config('app.actor_pseudonym', 'actor-gis-test', false);

SELECT pg_temp.ck(count(*) = 1, '(1) 圃場一覧はtenantと担当field groupの積集合') FROM app.field;
SELECT pg_temp.ck(count(*) = 1, '(2) bbox検索も担当圃場だけを返す')
FROM app.field
WHERE tenant_id = :'A' AND geom && ST_MakeEnvelope(140.29, 38.19, 140.32, 38.22, 4326);
SELECT pg_temp.ck(count(*) = 0, '(3) 同じbboxでもtenant越境しない') FROM app.field WHERE tenant_id = :'B';
SELECT pg_temp.ck(gis_area_sqm > 0, '(4) PostGIS面積を生成・取得できる') FROM app.field WHERE name = '北圃場';

RESET ROLE;
\echo 'Field GIS migration: 4 groups PASS'
