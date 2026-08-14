\set ON_ERROR_STOP on
\set A  '11111111-1111-7111-8111-111111111111'
\set B  '22222222-2222-7222-8222-222222222222'
\set U1 'aaaaaaaa-0000-7000-8000-000000000001'
\set F1 'f1111111-1111-7111-8111-111111111111'
\set F2 'f2222222-2222-7222-8222-222222222222'
\set E1 '0198a6c0-0000-7000-8000-000000000001'
\set D1 '0198a6c0-0000-7000-8000-000000000002'
\set C1 '0198a6c0-0000-7000-8000-000000000003'

CREATE OR REPLACE FUNCTION pg_temp.ck(condition boolean, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF NOT condition THEN RAISE EXCEPTION 'FAIL  %', label; END IF;
  RAISE NOTICE 'PASS  %', label;
END $$;

INSERT INTO app.task (tenant_id, task_id, field_group_id, scheduled_at, field_name, crop_name, work_name, status) VALUES
  (:'A', '0198a6c0-0000-7000-8000-000000000011', :'F1', date_trunc('day', now()) + interval '8 hours', '北圃場', '米', '水位確認', 'today'),
  (:'A', '0198a6c0-0000-7000-8000-000000000012', :'F2', date_trunc('day', now()) + interval '9 hours', '南圃場', '米', '草刈り', 'today'),
  (:'B', '0198a6c0-0000-7000-8000-000000000013', :'F1', date_trunc('day', now()) + interval '10 hours', '他社圃場', '麦', '巡回', 'today');

SET ROLE app_user;
SELECT set_config('app.user_id', :'U1', false);
SELECT set_config('app.tenant_id', :'A', false);
SELECT set_config('app.allowed_tenants', ('{' || :'A' || '}'), false);
SELECT set_config('app.scope_field_groups', ('{' || :'F1' || '}'), false);
SELECT set_config('app.caps', '{journal:write}', false);
SELECT set_config('app.employer_subject_users', '{}', false);
SELECT set_config('app.actor_pseudonym', 'actor-test', false);

SELECT pg_temp.ck(count(*) = 1, '(1) taskはtenantとfield scopeの積集合だけ可視') FROM app.task;
SELECT pg_temp.ck(count(*) = 0, '(2) tenant越境taskは不可視') FROM app.task WHERE tenant_id = :'B';

DO $$ BEGIN
  BEGIN
    INSERT INTO app.event_receipt (tenant_id, event_uuid, event_ts)
    VALUES ('22222222-2222-7222-8222-222222222222', '0198a6c0-0000-7000-8000-000000000001', now());
    RAISE EXCEPTION 'FAIL  (3) tenant越境writeを許可した';
  EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'PASS  (3) tenant越境writeをRLS拒否';
  END;
END $$;

INSERT INTO app.event_receipt (tenant_id, event_uuid, event_ts) VALUES (:'A', :'E1', now());
INSERT INTO app.event_receipt (tenant_id, event_uuid, event_ts) VALUES (:'A', :'E1', now()) ON CONFLICT DO NOTHING;
SELECT pg_temp.ck(count(*) = 1, '(4) tenant＋event_uuid台帳で再送を一意化') FROM app.event_receipt WHERE event_uuid = :'E1';

INSERT INTO app.sync_conflict
  (tenant_id, conflict_id, document_id, event_uuid, base_version, current_version, current_value, proposed_value)
VALUES (:'A', :'C1', :'D1', :'E1', 1, 2, '{"memo":"server"}', '{"memo":"device"}');
UPDATE app.sync_conflict SET status = 'resolved', resolution = '{"memo":"unauthorized"}' WHERE conflict_id = :'C1';
SELECT pg_temp.ck(status = 'pending', '(5) capabilityなしでは競合を裁定不可') FROM app.sync_conflict WHERE conflict_id = :'C1';

SELECT set_config('app.caps', '{journal:write,conflict:resolve}', false);
UPDATE app.sync_conflict SET status = 'resolved', resolution = '{"memo":"manager"}', resolved_by = :'U1', resolved_at = now() WHERE conflict_id = :'C1';
SELECT pg_temp.ck(status = 'resolved' AND resolution->>'memo' = 'manager', '(6) conflict:resolveだけが競合を裁定') FROM app.sync_conflict WHERE conflict_id = :'C1';

RESET ROLE;
\echo 'MVP sync migration: 6 groups PASS'
