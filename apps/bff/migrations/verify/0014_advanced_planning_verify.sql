\set ON_ERROR_STOP on
\set A      '11111111-1111-7111-8111-111111111111'
\set M      'aaaaaaaa-0000-7000-8000-000000000010'
\set W      'aaaaaaaa-0000-7000-8000-000000000011'
\set F1     'f1111111-1111-7111-8111-111111111111'
\set FIELD  '0198a6c0-0000-7000-8000-000000000101'

CREATE OR REPLACE FUNCTION pg_temp.ck(condition boolean, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN IF NOT condition THEN RAISE EXCEPTION 'FAIL  %', label; END IF; RAISE NOTICE 'PASS  %', label; END $$;

BEGIN;
SELECT pg_temp.ck(count(*) = 3, '(1) template・step・progress表はowner=app_ownerかつFORCE RLS')
FROM pg_class class JOIN pg_namespace namespace ON namespace.oid = class.relnamespace
WHERE namespace.nspname = 'app' AND class.relname IN ('work_plan_template','work_plan_template_step','work_progress_event')
  AND class.relowner = 'app_owner'::regrole AND class.relrowsecurity AND class.relforcerowsecurity;

SET LOCAL ROLE app_user;
SELECT set_config('app.user_id', :'M', true);
SELECT set_config('app.tenant_id', :'A', true);
SELECT set_config('app.allowed_tenants', ('{' || :'A' || '}'), true);
SELECT set_config('app.scope_field_groups', ('{' || :'F1' || '}'), true);
SELECT set_config('app.caps', '{scope_all,instruction:manage,planning:manage,resource:manage,security:manage}', true);
SELECT set_config('app.actor_pseudonym', 'planning-manager', true);

INSERT INTO app.growing_season (tenant_id,season_id,name,starts_on,ends_on,created_by,updated_by)
VALUES (:'A','0498a6c0-0000-7000-8000-000000000101','2027','2027-01-01','2027-12-31',:'M',:'M');
INSERT INTO app.crop_plan
  (tenant_id,crop_plan_id,season_id,field_id,field_group_id,crop_name,variety_name,planned_area_m2,target_yield_kg,created_by,updated_by)
VALUES (:'A','0498a6c0-0000-7000-8000-000000000102','0498a6c0-0000-7000-8000-000000000101',:'FIELD',:'F1','水稲','つや姫',1000,540,:'M',:'M');
INSERT INTO app.work_plan_template
  (tenant_id,template_id,name,crop_name,created_by,updated_by)
VALUES (:'A','0498a6c0-0000-7000-8000-000000000103','水稲標準','水稲',:'M',:'M');
INSERT INTO app.work_plan_template_step
  (tenant_id,template_id,step_key,title,work_type,start_offset_days,duration_minutes,sort_order)
VALUES (:'A','0498a6c0-0000-7000-8000-000000000103','plant','田植え','定植',0,120,1),
       (:'A','0498a6c0-0000-7000-8000-000000000103','check','活着確認','生育確認',7,60,2);
SELECT pg_temp.ck(count(*) = 2, '(2) 作業templateと順序付きstepを登録') FROM app.work_plan_template_step;

INSERT INTO app.work_instruction
  (tenant_id,instruction_id,field_id,field_group_id,crop_plan_id,title,work_type,scheduled_start,scheduled_end,created_by,updated_by)
VALUES
  (:'A','0498a6c0-0000-7000-8000-000000000104',:'FIELD',:'F1','0498a6c0-0000-7000-8000-000000000102','田植え','定植','2027-05-01T00:00Z','2027-05-01T02:00Z',:'M',:'M'),
  (:'A','0498a6c0-0000-7000-8000-000000000105',:'FIELD',:'F1','0498a6c0-0000-7000-8000-000000000102','施肥','施肥','2027-05-01T01:00Z','2027-05-01T03:00Z',:'M',:'M');
INSERT INTO app.work_assignment
  (tenant_id,assignment_id,instruction_id,field_group_id,assignee_user_id,assigned_by)
VALUES
  (:'A','0498a6c0-0000-7000-8000-000000000111','0498a6c0-0000-7000-8000-000000000104',:'F1',:'W',:'M'),
  (:'A','0498a6c0-0000-7000-8000-000000000112','0498a6c0-0000-7000-8000-000000000105',:'F1',:'W',:'M');
INSERT INTO app.work_instruction_dependency
  (tenant_id,predecessor_instruction_id,successor_instruction_id,created_by)
VALUES (:'A','0498a6c0-0000-7000-8000-000000000104','0498a6c0-0000-7000-8000-000000000105',:'M');

INSERT INTO app.planning_resource
  (tenant_id,resource_id,field_group_id,resource_type,name,capacity,capacity_unit,created_by,updated_by)
VALUES (:'A','0498a6c0-0000-7000-8000-000000000106',:'F1','machine','トラクター',1,'machine',:'M',:'M');
INSERT INTO app.work_resource_allocation
  (tenant_id,allocation_id,instruction_id,resource_id,field_group_id,quantity,allocated_start,allocated_end,created_by,updated_by)
VALUES
  (:'A','0498a6c0-0000-7000-8000-000000000107','0498a6c0-0000-7000-8000-000000000104','0498a6c0-0000-7000-8000-000000000106',:'F1',1,'2027-05-01T00:00Z','2027-05-01T02:00Z',:'M',:'M'),
  (:'A','0498a6c0-0000-7000-8000-000000000108','0498a6c0-0000-7000-8000-000000000105','0498a6c0-0000-7000-8000-000000000106',:'F1',1,'2027-05-01T01:00Z','2027-05-01T03:00Z',:'M',:'M');
SELECT pg_temp.ck(count(*) = 1, '(3) capacity超過する時間重複をresource競合として導出') FROM app.resource_conflict;

SELECT set_config('app.user_id', :'W', true);
SELECT set_config('app.caps', '{}', true);
INSERT INTO app.work_progress_event
  (tenant_id,progress_event_id,event_uuid,instruction_id,field_group_id,progress_percent,note,actor_user_id,occurred_at)
VALUES (:'A','0498a6c0-0000-7000-8000-000000000109','0498a6c0-0000-7000-8000-000000000110',
  '0498a6c0-0000-7000-8000-000000000104',:'F1',50,'半分完了',:'W',statement_timestamp());
UPDATE app.work_instruction SET progress_percent=50,status='in_progress',progress_updated_at=clock_timestamp(),
  updated_at=clock_timestamp(),updated_by=app.current_user_id(),version=version+1
WHERE instruction_id='0498a6c0-0000-7000-8000-000000000104';
SELECT pg_temp.ck(progress_percent = 25 AND instruction_count = 2, '(4) 作付進捗を同じ作業指示から導出')
FROM app.crop_plan_progress WHERE crop_plan_id='0498a6c0-0000-7000-8000-000000000102';

DO $$ BEGIN
  BEGIN
    UPDATE app.work_progress_event SET note='改ざん' WHERE progress_event_id='0498a6c0-0000-7000-8000-000000000109';
    RAISE EXCEPTION 'FAIL  (5) progress event更新を許可した';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'PASS  (5) progress eventは追記専用'; END;
END $$;

RESET ROLE;
ROLLBACK;
\echo 'Advanced planning migration: 5 groups PASS'
