\set ON_ERROR_STOP on
\set A  '11111111-1111-7111-8111-111111111111'
\set M  'aaaaaaaa-0000-7000-8000-000000000010'
\set W  'aaaaaaaa-0000-7000-8000-000000000011'
\set X  'aaaaaaaa-0000-7000-8000-000000000012'
\set F1 'f1111111-1111-7111-8111-111111111111'
\set FIELD '0198a6c0-0000-7000-8000-000000000101'
\set INST  '0198a6c0-0000-7000-8000-000000000401'
\set ASSIGN '0198a6c0-0000-7000-8000-000000000402'
\set PUNCH  '0198a6c0-0000-7000-8000-000000000403'
\set EVENT  '0198a6c0-0000-7000-8000-000000000404'
\set JOURNAL '0198a6c0-0000-7000-8000-000000000405'
\set PHOTO   '0198a6c0-0000-7000-8000-000000000406'

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
SELECT set_config('app.caps', '{scope_all,instruction:manage,journal:review}', false);
SELECT set_config('app.employer_subject_users', '{}', false);
SELECT set_config('app.actor_pseudonym', 'actor-manager', false);

INSERT INTO app.work_instruction
  (tenant_id, instruction_id, field_id, field_group_id, title, work_type, scheduled_start, scheduled_end, created_by, updated_by)
VALUES (:'A', :'INST', :'FIELD', :'F1', '北圃場の水位確認', '水管理', now(), now() + interval '1 hour', :'M', :'M');
INSERT INTO app.work_assignment
  (tenant_id, assignment_id, instruction_id, field_group_id, assignee_user_id, assigned_by)
VALUES (:'A', :'ASSIGN', :'INST', :'F1', :'W', :'M');
SELECT pg_temp.ck(count(*) = 1, '(1) instruction:manageで指示と担当を発行') FROM app.work_instruction WHERE instruction_id = :'INST';

SELECT set_config('app.user_id', :'W', false);
SELECT set_config('app.caps', '{journal:write,punch:write}', false);
SELECT pg_temp.ck(count(*) = 1, '(2) 担当者は自分の割当を参照') FROM app.work_assignment WHERE instruction_id = :'INST';
SELECT set_config('app.user_id', :'X', false);
SELECT pg_temp.ck(count(*) = 0, '(3) 別作業員には担当者情報を非表示') FROM app.work_assignment WHERE instruction_id = :'INST';

SELECT set_config('app.user_id', :'W', false);
INSERT INTO app.work_punch
  (tenant_id, punch_id, event_uuid, user_id, instruction_id, field_group_id, action, occurred_at, event_ts)
VALUES (:'A', :'PUNCH', :'EVENT', :'W', :'INST', :'F1', 'start', now(), now());
INSERT INTO app.work_journal
  (tenant_id, journal_id, instruction_id, field_id, field_group_id, worker_user_id, body)
VALUES (:'A', :'JOURNAL', :'INST', :'FIELD', :'F1', :'W', '{"field":"北圃場","workType":"水管理","startedAt":"08:00","endedAt":"09:00"}');
INSERT INTO app.journal_attachment
  (tenant_id, attachment_id, journal_id, worker_user_id, file_name, content_type, byte_size, sha256, content, captured_at)
VALUES (:'A', :'PHOTO', :'JOURNAL', :'W', 'field.jpg', 'image/jpeg', 3,
  repeat('a', 64), decode('ffd8ff', 'hex'), now());
SELECT pg_temp.ck(count(*) = 1, '(4) 打刻・日誌・写真を本人として追記') FROM app.journal_attachment WHERE journal_id = :'JOURNAL';

SELECT set_config('app.user_id', :'M', false);
SELECT set_config('app.caps', '{scope_all,instruction:manage,journal:review}', false);
UPDATE app.work_journal SET status = 'returned', version = version + 1 WHERE journal_id = :'JOURNAL';
INSERT INTO app.journal_revision
  (tenant_id, revision_id, journal_id, worker_user_id, action, from_status, to_status, reason, body_snapshot, actor_user_id)
SELECT :'A', '0198a6c0-0000-7000-8000-000000000407', journal_id, worker_user_id,
  'returned', 'submitted', 'returned', '終了時刻を確認', body, :'M'
FROM app.work_journal WHERE journal_id = :'JOURNAL';
SELECT pg_temp.ck(status = 'returned' AND version = 2, '(5) 管理者が理由付きで差し戻し') FROM app.work_journal WHERE journal_id = :'JOURNAL';

SELECT set_config('app.user_id', :'W', false);
SELECT set_config('app.caps', '{journal:write,punch:write}', false);
UPDATE app.work_journal SET body = body || '{"endedAt":"09:10"}', status = 'corrected', version = version + 1 WHERE journal_id = :'JOURNAL';
INSERT INTO app.journal_revision
  (tenant_id, revision_id, journal_id, worker_user_id, action, from_status, to_status, reason, body_snapshot, actor_user_id)
SELECT :'A', '0198a6c0-0000-7000-8000-000000000408', journal_id, worker_user_id,
  'corrected', 'returned', 'corrected', '終了打刻を確認して訂正', body, :'W'
FROM app.work_journal WHERE journal_id = :'JOURNAL';
SELECT pg_temp.ck(count(*) = 2, '(6) 差し戻しと訂正を追記履歴で保持') FROM app.journal_revision WHERE journal_id = :'JOURNAL';

SELECT set_config('app.user_id', :'M', false);
SELECT set_config('app.caps', '{scope_all,instruction:manage,journal:review}', false);
UPDATE app.work_journal SET status = 'approved', version = version + 1 WHERE journal_id = :'JOURNAL';
SELECT set_config('app.user_id', :'W', false);
SELECT set_config('app.caps', '{journal:write,punch:write}', false);
UPDATE app.work_journal SET body = body || '{"memo":"silent overwrite"}' WHERE journal_id = :'JOURNAL';
SELECT pg_temp.ck(status = 'approved' AND NOT (body ? 'memo'), '(7) 承認済み日誌は作業員の通常更新を遮断') FROM app.work_journal WHERE journal_id = :'JOURNAL';

DO $$ BEGIN
  BEGIN
    UPDATE app.journal_revision SET reason = '改ざん' WHERE journal_id = '0198a6c0-0000-7000-8000-000000000405';
    RAISE EXCEPTION 'FAIL  (8) 訂正履歴のUPDATEを許可した';
  EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'PASS  (8) 訂正履歴はINSERT-only';
  END;
END $$;

RESET ROLE;
\echo 'Work instruction and journal migration: 8 groups PASS'
