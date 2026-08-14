\set ON_ERROR_STOP on
\set A      '11111111-1111-7111-8111-111111111111'
\set M      'aaaaaaaa-0000-7000-8000-000000000010'
\set W      'aaaaaaaa-0000-7000-8000-000000000011'
\set F1     'f1111111-1111-7111-8111-111111111111'
\set JOB    '0198a6c0-0000-7000-8000-000000000601'
\set FIELD  '0198a6c0-0000-7000-8000-000000000602'
\set JOURNAL '0198a6c0-0000-7000-8000-000000000603'
\set SUMMARY '0198a6c0-0000-7000-8000-000000000604'
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
SELECT set_config('app.caps', '{scope_all,migration:manage,export:read,journal:review}', false);
SELECT set_config('app.employer_subject_users', '{}', false);
SELECT set_config('app.actor_pseudonym', 'actor-migration', false);

INSERT INTO app.migration_job
  (tenant_id, job_id, idempotency_key, dataset, source_name, source_sha256, mapping,
   status, row_count, valid_count, duplicate_count, error_count, created_by)
VALUES (:'A', :'JOB', 'verify-fields-v1', 'fields', 'fields.csv', repeat('a', 64),
  '{"externalKey":"code"}', 'validated', 1, 1, 0, 0, :'M');
INSERT INTO app.migration_row
  (tenant_id, job_id, line_number, raw_data, normalized_data, row_status, duplicate_key)
VALUES (:'A', :'JOB', 2, '{"code":"F-CSV-1"}', '{"externalKey":"F-CSV-1"}', 'valid', 'F-CSV-1');
SELECT pg_temp.ck(count(*) = 1, '(1) 管理者が検証済みジョブと行結果を保存')
FROM app.migration_row WHERE job_id = :'JOB';

INSERT INTO app.field
  (tenant_id, field_id, field_group_id, external_key, name, crop_name, timezone, geom, import_job_id, import_source_row)
VALUES (:'A', :'FIELD', :'F1', 'F-CSV-1', 'CSV北圃場', 'つや姫', 'Asia/Tokyo',
  ST_Multi(ST_GeomFromText('POLYGON((140 38,140.01 38,140.01 38.01,140 38))', 4326)), :'JOB', 2);
INSERT INTO app.work_journal
  (tenant_id, journal_id, field_id, field_group_id, worker_user_id, external_key, body,
   status, import_job_id, import_source_row)
VALUES (:'A', :'JOURNAL', :'FIELD', :'F1', :'W', 'J-CSV-1',
  '{"workType":"除草","workedOn":"2026-08-14","startedAt":"08:00","endedAt":"09:00"}',
  'approved', :'JOB', 3);
INSERT INTO app.pesticide_usage_summary
  (tenant_id, summary_id, field_id, field_group_id, crop_name, chemical_id, season_year,
   usage_count, last_applied_on, import_job_id, import_source_row)
VALUES (:'A', :'SUMMARY', :'FIELD', :'F1', 'つや姫', :'CHEM', 2026, 2, '2026-07-20', :'JOB', 4);
SELECT pg_temp.ck(count(*) = 3, '(2) 圃場・作業記録・農薬履歴サマリを移行元へ紐付け')
FROM (
  SELECT import_job_id FROM app.field WHERE field_id = :'FIELD'
  UNION ALL SELECT import_job_id FROM app.work_journal WHERE journal_id = :'JOURNAL'
  UNION ALL SELECT import_job_id FROM app.pesticide_usage_summary WHERE summary_id = :'SUMMARY'
) imported WHERE import_job_id = :'JOB';

DO $$ BEGIN
  BEGIN
    INSERT INTO app.field
      (tenant_id, field_id, field_group_id, external_key, name, geom, import_job_id, import_source_row)
    VALUES ('11111111-1111-7111-8111-111111111111', '0198a6c0-0000-7000-8000-000000000605',
      'f1111111-1111-7111-8111-111111111111', 'F-CSV-1', '重複',
      ST_Multi(ST_GeomFromText('POLYGON((140 38,140.01 38,140.01 38.01,140 38))', 4326)),
      '0198a6c0-0000-7000-8000-000000000601', 5);
    RAISE EXCEPTION 'FAIL  (3) 圃場自然キー重複を許可した';
  EXCEPTION WHEN unique_violation THEN
    RAISE NOTICE 'PASS  (3) tenant内の移行自然キー重複をDBでも拒否';
  END;
END $$;

SELECT set_config('app.user_id', :'W', false);
SELECT set_config('app.caps', '{journal:write}', false);
SELECT pg_temp.ck(count(*) = 0, '(4) migration:manageなしではジョブを参照不可') FROM app.migration_job;
DO $$ BEGIN
  BEGIN
    INSERT INTO app.migration_job
      (tenant_id, job_id, idempotency_key, dataset, source_name, source_sha256, mapping,
       status, row_count, valid_count, duplicate_count, error_count, created_by)
    VALUES ('11111111-1111-7111-8111-111111111111', '0198a6c0-0000-7000-8000-000000000606',
      'forbidden', 'fields', 'bad.csv', repeat('b', 64), '{}', 'validated', 0, 0, 0, 0,
      'aaaaaaaa-0000-7000-8000-000000000011');
    RAISE EXCEPTION 'FAIL  (5) migration:manageなしのジョブ作成を許可した';
  EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'PASS  (5) migration:manageなしのジョブ作成をRLS拒否';
  END;
END $$;

SELECT set_config('app.user_id', :'M', false);
SELECT set_config('app.caps', '{scope_all,export:read}', false);
SELECT pg_temp.ck(count(*) = 1, '(6) export:readは他作業者の日誌をRLS適用後に出力可能')
FROM app.work_journal WHERE journal_id = :'JOURNAL';

RESET ROLE;
\echo 'Data migration and CSV export: 6 groups PASS'
