\set ON_ERROR_STOP on
BEGIN;
SET ROLE app_owner;
DROP VIEW app.tenant_analytics_freshness;
DROP VIEW app.tenant_material_actual;
DROP VIEW app.tenant_plan_actual;
DROP TRIGGER z_phase2_change_audit ON app.harvest_actual_event;
DROP TRIGGER harvest_actual_scope_guard ON app.harvest_actual_event;
DROP FUNCTION app.audit_harvest_actual();
DROP FUNCTION app.validate_harvest_scope();
DROP TABLE app.harvest_actual_event;
RESET ROLE;
COMMIT;
