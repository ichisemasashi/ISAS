\set ON_ERROR_STOP on
\set A      '11111111-1111-7111-8111-111111111111'
\set B      '22222222-2222-7222-8222-222222222222'
\set M      'aaaaaaaa-0000-7000-8000-000000000010'
\set W      'aaaaaaaa-0000-7000-8000-000000000011'
\set F1     'f1111111-1111-7111-8111-111111111111'
\set FIELD  '0198a6c0-0000-7000-8000-000000000101'
\set INST1  '0198a6c0-0000-7000-8000-000000000401'
\set CHEM   '0198a6c0-0000-7000-8000-000000000502'
\set SEASON '0298a6c0-0000-7000-8000-000000000101'
\set PLAN   '0298a6c0-0000-7000-8000-000000000102'
\set INST2  '0298a6c0-0000-7000-8000-000000000103'
\set RES    '0298a6c0-0000-7000-8000-000000000104'
\set ALLOC  '0298a6c0-0000-7000-8000-000000000105'
\set POLICY '0298a6c0-0000-7000-8000-000000000106'
\set AEVENT '0298a6c0-0000-7000-8000-000000000107'
\set CEVENT '0298a6c0-0000-7000-8000-000000000108'

CREATE OR REPLACE FUNCTION pg_temp.ck(condition boolean, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF NOT condition THEN RAISE EXCEPTION 'FAIL  %', label; END IF;
  RAISE NOTICE 'PASS  %', label;
END $$;

BEGIN;
SELECT pg_temp.ck(count(*) = 9, '(1) Phase 2表はowner=app_ownerかつFORCE RLS')
FROM pg_class class JOIN pg_namespace namespace ON namespace.oid = class.relnamespace
WHERE namespace.nspname = 'app'
  AND class.relname IN ('growing_season', 'crop_plan', 'work_instruction_dependency', 'planning_resource',
    'work_resource_allocation', 'inventory_policy', 'analytics_event', 'location_consent_event', 'phase2_change_audit')
  AND class.relowner = 'app_owner'::regrole AND class.relrowsecurity AND class.relforcerowsecurity;

SELECT pg_temp.ck(count(*) = 8, '(2) 全Phase 2業務表に監査trigger')
FROM pg_trigger trigger JOIN pg_class class ON class.oid = trigger.tgrelid
JOIN pg_namespace namespace ON namespace.oid = class.relnamespace
WHERE namespace.nspname = 'app' AND trigger.tgname = 'z_phase2_change_audit' AND NOT trigger.tgisinternal
  AND class.relname IN ('growing_season', 'crop_plan', 'work_instruction_dependency',
    'planning_resource', 'work_resource_allocation', 'inventory_policy',
    'analytics_event', 'location_consent_event');

SET LOCAL ROLE app_user;
SELECT set_config('app.user_id', :'M', true);
SELECT set_config('app.tenant_id', :'A', true);
SELECT set_config('app.allowed_tenants', ('{' || :'A' || '}'), true);
SELECT set_config('app.scope_field_groups', ('{' || :'F1' || '}'), true);
SELECT set_config('app.caps', '{scope_all,instruction:manage,planning:manage,resource:manage,inventory:write,inventory:policy:manage,analytics:write,analytics:read,security:manage}', true);
SELECT set_config('app.employer_subject_users', '{}', true);
SELECT set_config('app.actor_pseudonym', 'phase2-manager', true);

INSERT INTO app.growing_season (tenant_id, season_id, name, starts_on, ends_on, created_by, updated_by)
VALUES (:'A', :'SEASON', '2027年水稲', DATE '2027-01-01', DATE '2027-12-31', :'M', :'M');
INSERT INTO app.crop_plan
  (tenant_id, crop_plan_id, season_id, field_id, field_group_id, crop_name, variety_name,
   planned_area_m2, target_yield_kg, planting_window_start, planting_window_end, created_by, updated_by)
VALUES (:'A', :'PLAN', :'SEASON', :'FIELD', :'F1', '水稲', 'つや姫', 1000, 540,
  DATE '2027-05-01', DATE '2027-05-15', :'M', :'M');
INSERT INTO app.work_instruction
  (tenant_id, instruction_id, field_id, field_group_id, crop_plan_id, title, work_type,
   scheduled_start, scheduled_end, created_by, updated_by)
VALUES (:'A', :'INST2', :'FIELD', :'F1', :'PLAN', '施肥', '施肥',
  TIMESTAMPTZ '2027-05-01 00:00:00Z', TIMESTAMPTZ '2027-05-01 01:00:00Z', :'M', :'M');
INSERT INTO app.work_instruction_dependency
  (tenant_id, predecessor_instruction_id, successor_instruction_id, created_by)
VALUES (:'A', :'INST1', :'INST2', :'M');
SELECT pg_temp.ck(count(*) = 1, '(3) 作期・作付計画と作業依存を同一tenant/scopeで作成')
FROM app.crop_plan WHERE crop_plan_id = :'PLAN';

DO $$ BEGIN
  BEGIN
    INSERT INTO app.work_instruction_dependency
      (tenant_id, predecessor_instruction_id, successor_instruction_id, created_by)
    VALUES ('11111111-1111-7111-8111-111111111111',
      '0298a6c0-0000-7000-8000-000000000103', '0198a6c0-0000-7000-8000-000000000401',
      'aaaaaaaa-0000-7000-8000-000000000010');
    RAISE EXCEPTION 'FAIL  (4) 循環依存を許可した';
  EXCEPTION WHEN check_violation THEN
    RAISE NOTICE 'PASS  (4) 作業依存の循環をDBで拒否';
  END;
END $$;

INSERT INTO app.planning_resource
  (tenant_id, resource_id, field_group_id, resource_type, name, capacity, capacity_unit, created_by, updated_by)
VALUES (:'A', :'RES', :'F1', 'machine', 'トラクター1号', 1, 'machine', :'M', :'M');
INSERT INTO app.work_resource_allocation
  (tenant_id, allocation_id, instruction_id, resource_id, field_group_id, quantity,
   allocated_start, allocated_end, created_by, updated_by)
VALUES (:'A', :'ALLOC', :'INST2', :'RES', :'F1', 1,
  TIMESTAMPTZ '2027-05-01 00:00:00Z', TIMESTAMPTZ '2027-05-01 01:00:00Z', :'M', :'M');
INSERT INTO app.inventory_policy
  (tenant_id, policy_id, chemical_id, reorder_point, target_level, safety_stock,
   effective_from, status, created_by, updated_by)
VALUES (:'A', :'POLICY', :'CHEM', 5, 20, 3, DATE '2027-01-01', 'active', :'M', :'M');
SELECT pg_temp.ck(count(*) = 1, '(5) resource割当と在庫policyを登録') FROM app.work_resource_allocation WHERE allocation_id = :'ALLOC';

INSERT INTO app.analytics_event
  (tenant_id, analytics_event_id, event_uuid, event_type, source_type, source_id,
   field_id, field_group_id, dimensions, measures, occurred_at, actor_user_id)
VALUES (:'A', :'AEVENT', :'AEVENT', 'plan.approved', 'crop_plan', :'PLAN', :'FIELD', :'F1',
  '{"crop":"rice"}', '{"plannedAreaM2":1000}', statement_timestamp(), :'M');
SELECT pg_temp.ck(count(*) = 1, '(6) 分析eventをserver受理時刻付きで追記') FROM app.analytics_event WHERE analytics_event_id = :'AEVENT';

SELECT set_config('app.user_id', :'W', true);
SELECT set_config('app.caps', '{}', true);
INSERT INTO app.location_consent_event
  (tenant_id, consent_event_id, event_uuid, subject_user_id, action, purpose,
   policy_version, consent_text_sha256, locale, actor_user_id)
VALUES (:'A', :'CEVENT', :'CEVENT', :'W', 'granted', 'work_evidence', '2027-01', repeat('a', 64), 'ja-JP', :'W');
SELECT pg_temp.ck(action = 'granted', '(7) 本人が位置情報同意を追記しcurrent viewで確認')
FROM app.location_consent_current WHERE subject_user_id = :'W' AND purpose = 'work_evidence';

DO $$ BEGIN
  BEGIN
    INSERT INTO app.location_consent_event
      (tenant_id, consent_event_id, event_uuid, subject_user_id, action, purpose,
       policy_version, consent_text_sha256, locale, actor_user_id)
    VALUES ('11111111-1111-7111-8111-111111111111', gen_random_uuid(), gen_random_uuid(),
      'aaaaaaaa-0000-7000-8000-000000000012', 'granted', 'work_evidence', '2027-01', repeat('b', 64), 'ja-JP',
      'aaaaaaaa-0000-7000-8000-000000000011');
    RAISE EXCEPTION 'FAIL  (8) 他人への同意付与を許可した';
  EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'PASS  (8) 他人への位置情報同意付与をRLSで拒否';
  END;
END $$;

SELECT set_config('app.user_id', :'M', true);
SELECT set_config('app.caps', '{security:manage}', true);
SELECT pg_temp.ck(count(*) >= 8, '(9) INSERT監査をtenant分離された監査表へ記録') FROM app.phase2_change_audit;

DO $$ BEGIN
  BEGIN
    UPDATE app.analytics_event SET measures = '{}' WHERE analytics_event_id = '0298a6c0-0000-7000-8000-000000000107';
    RAISE EXCEPTION 'FAIL  (10) 分析eventのUPDATEを許可した';
  EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'PASS  (10) 分析eventと同意eventは追記専用';
  END;
END $$;

RESET ROLE;
ROLLBACK;
\echo 'Phase 2 data model migration: 10 groups PASS'
