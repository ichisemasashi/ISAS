\set ON_ERROR_STOP on
\set A      '11111111-1111-7111-8111-111111111111'
\set M      'aaaaaaaa-0000-7000-8000-000000000010'
\set W      'aaaaaaaa-0000-7000-8000-000000000011'
\set F1     'f1111111-1111-7111-8111-111111111111'
\set FIELD  '0198a6c0-0000-7000-8000-000000000101'
\set RELEASE '0198a6c0-0000-7000-8000-000000000501'
\set CHEM   '0198a6c0-0000-7000-8000-000000000502'

CREATE OR REPLACE FUNCTION pg_temp.ck(condition boolean, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF NOT condition THEN RAISE EXCEPTION 'FAIL  %', label; END IF;
  RAISE NOTICE 'PASS  %', label;
END $$;

SET ROLE app_user;
SELECT set_config('app.user_id', :'M', false);
SELECT set_config('app.tenant_id', :'A', false);
SELECT set_config('app.allowed_tenants', ('{' || :'A' || '}'), false);
SELECT set_config('app.scope_field_groups', ('{' || :'F1' || '}'), false);
SELECT set_config('app.caps', '{scope_all,pesticide:manage,pesticide:override,inventory:write,inventory:adjust}', false);
SELECT set_config('app.employer_subject_users', '{}', false);
SELECT set_config('app.actor_pseudonym', 'actor-manager', false);

INSERT INTO app.pesticide_master_release
  (tenant_id, release_id, version, valid_until, published_by)
VALUES (:'A', :'RELEASE', 'jp-2026-08-14', now() + interval '7 days', :'M');
INSERT INTO app.agrochemical
  (tenant_id, chemical_id, release_id, registration_number, name, active_ingredient,
   applicable_crops, dilution_min, dilution_max, max_uses, preharvest_days)
VALUES (:'A', :'CHEM', :'RELEASE', '農林水産省登録第1号', '検証水和剤', '成分A',
  ARRAY['つや姫'], 500, 1000, 3, 7);
SELECT pg_temp.ck(count(*) = 1, '(1) 管理者が鮮度期限付き農薬マスタを公開')
FROM app.agrochemical WHERE release_id = :'RELEASE';

SELECT set_config('app.user_id', :'W', false);
SELECT set_config('app.caps', '{pesticide:write,inventory:write}', false);
INSERT INTO app.pesticide_usage
  (tenant_id, usage_id, event_uuid, field_id, field_group_id, crop_name, chemical_id,
   applied_on, dilution, amount, target_pest, worker_name, equipment, planned_harvest_on,
   client_safety, server_safety, occurred_at, event_ts, actor_user_id)
VALUES (:'A', '0198a6c0-0000-7000-8000-000000000503', '0198a6c0-0000-7000-8000-000000000504',
  :'FIELD', :'F1', 'つや姫', :'CHEM', current_date, 750, 2, 'いもち病', '作業員', '動噴',
  current_date + 10, '{"status":"safe"}', '{"status":"safe","reasons":[]}', now(), now(), :'W');
SELECT pg_temp.ck(count(*) = 1, '(2) 担当者が法定農薬使用項目と両判定を追記')
FROM app.pesticide_usage WHERE chemical_id = :'CHEM';

INSERT INTO app.stock_event
  (tenant_id, stock_event_id, event_uuid, chemical_id, event_type, quantity_delta, reason,
   occurred_at, event_ts, actor_user_id)
VALUES (:'A', '0198a6c0-0000-7000-8000-000000000505', '0198a6c0-0000-7000-8000-000000000506',
  :'CHEM', 'withdrawal', -2, '散布用出庫', now(), now(), :'W');
SELECT pg_temp.ck(quantity = -2, '(3) 在庫残高を追記イベントから導出')
FROM app.stock_balance WHERE chemical_id = :'CHEM';

SELECT set_config('app.user_id', :'M', false);
SELECT set_config('app.caps', '{scope_all,pesticide:manage,pesticide:override,inventory:write,inventory:adjust}', false);
SELECT pg_temp.ck(count(*) = 1 AND min(negative_quantity) = -2, '(4) マイナス在庫を管理者キューへ通知')
FROM app.stock_alert WHERE chemical_id = :'CHEM' AND status = 'pending';

INSERT INTO app.stock_event
  (tenant_id, stock_event_id, event_uuid, chemical_id, event_type, quantity_delta, reason,
   occurred_at, event_ts, actor_user_id)
VALUES (:'A', '0198a6c0-0000-7000-8000-000000000507', '0198a6c0-0000-7000-8000-000000000508',
  :'CHEM', 'adjustment', 5, '実棚3Lを確認', now(), now(), :'M');
UPDATE app.stock_alert
SET status = 'resolved', resolved_by = :'M', resolved_at = now(),
    resolution_event_id = '0198a6c0-0000-7000-8000-000000000507'
WHERE chemical_id = :'CHEM' AND status = 'pending';
SELECT pg_temp.ck(balance.quantity = 3 AND alert.status = 'resolved' AND alert.resolution_event_id IS NOT NULL,
  '(5) 実棚確認後の調整イベントでのみ裁定')
FROM app.stock_balance balance JOIN app.stock_alert alert USING (tenant_id, chemical_id)
WHERE balance.chemical_id = :'CHEM';

SELECT set_config('app.user_id', :'W', false);
SELECT set_config('app.caps', '{pesticide:write,inventory:write}', false);
DO $$ BEGIN
  BEGIN
    INSERT INTO app.stock_event
      (tenant_id, stock_event_id, event_uuid, chemical_id, event_type, quantity_delta, reason,
       occurred_at, event_ts, actor_user_id)
    VALUES ('11111111-1111-7111-8111-111111111111', '0198a6c0-0000-7000-8000-000000000509',
      '0198a6c0-0000-7000-8000-000000000510', '0198a6c0-0000-7000-8000-000000000502',
      'adjustment', 1, '不正な直接調整', now(), now(), 'aaaaaaaa-0000-7000-8000-000000000011');
    RAISE EXCEPTION 'FAIL  (6) inventory:adjustなしの調整を許可した';
  EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'PASS  (6) 調整イベントは現在の管理者権限を要求';
  END;
END $$;

DO $$ BEGIN
  BEGIN
    UPDATE app.stock_event SET quantity_delta = 999
    WHERE stock_event_id = '0198a6c0-0000-7000-8000-000000000505';
    RAISE EXCEPTION 'FAIL  (7) 在庫イベントのUPDATEを許可した';
  EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'PASS  (7) 在庫イベントはINSERT-only';
  END;
END $$;

RESET ROLE;
\echo 'Pesticide and inventory migration: 7 groups PASS'
