\set ON_ERROR_STOP on

BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $$
DECLARE table_name text; row_count bigint;
BEGIN
  IF EXISTS (SELECT 1 FROM app.work_instruction WHERE crop_plan_id IS NOT NULL) THEN
    RAISE EXCEPTION 'refusing Phase 2 rollback: work instructions reference crop plans';
  END IF;
  FOREACH table_name IN ARRAY ARRAY[
    'growing_season', 'crop_plan', 'work_instruction_dependency',
    'planning_resource', 'work_resource_allocation', 'inventory_policy',
    'analytics_event', 'location_consent_event', 'phase2_change_audit'
  ] LOOP
    EXECUTE format('SELECT count(*) FROM app.%I', table_name) INTO row_count;
    IF row_count > 0 THEN
      RAISE EXCEPTION 'refusing Phase 2 rollback: app.% contains % rows', table_name, row_count;
    END IF;
  END LOOP;
  IF EXISTS (
    SELECT 1 FROM priv.auth_role_capability
    WHERE capability IN ('planning:manage', 'resource:manage', 'inventory:policy:manage', 'analytics:write', 'analytics:read')
      AND role_key <> 'group_admin'
  ) THEN
    RAISE EXCEPTION 'refusing Phase 2 rollback: custom roles use Phase 2 capabilities';
  END IF;
END $$;

SET LOCAL ROLE app_owner;
DROP VIEW app.location_consent_current;
DROP TABLE app.phase2_change_audit;
DROP TABLE app.location_consent_event;
DROP TABLE app.analytics_event;
DROP TABLE app.inventory_policy;
DROP TABLE app.work_resource_allocation;
DROP TABLE app.planning_resource;
DROP TABLE app.work_instruction_dependency;
DROP POLICY work_instruction_phase2_owner ON app.work_instruction;
DROP POLICY field_phase2_owner ON app.field;
ALTER TABLE app.work_instruction DROP CONSTRAINT work_instruction_crop_plan_fk;
ALTER TABLE app.work_instruction DROP COLUMN crop_plan_id;
DROP TABLE app.crop_plan;
DROP TABLE app.growing_season;
DROP FUNCTION app.reject_work_dependency_cycle();
DROP FUNCTION app.validate_resource_allocation_scope();
DROP FUNCTION app.validate_crop_plan_scope();
DROP FUNCTION app.audit_phase2_change();

SET LOCAL ROLE auth_context_owner;
DELETE FROM priv.auth_role_capability
WHERE role_key = 'group_admin'
  AND capability IN ('planning:manage', 'resource:manage', 'inventory:policy:manage', 'analytics:write', 'analytics:read');
ALTER TABLE priv.auth_role_capability DROP CONSTRAINT auth_role_capability_capability_check;
ALTER TABLE priv.auth_role_capability ADD CONSTRAINT auth_role_capability_capability_check
  CHECK (capability IN (
    'view_others_tracks', 'view_others_punch', 'scope_all',
    'journal:write', 'pesticide:write', 'punch:write', 'conflict:resolve',
    'instruction:manage', 'journal:review',
    'pesticide:manage', 'pesticide:override', 'inventory:write', 'inventory:adjust',
    'migration:manage', 'export:read',
    'security:manage', 'privacy:manage', 'break_glass:approve'
  ));

COMMIT;
