\set ON_ERROR_STOP on
BEGIN;
SET ROLE app_owner;
DO $$
BEGIN
  IF to_regclass('app.harvest_actual_event') IS NULL OR to_regclass('app.tenant_plan_actual') IS NULL
     OR to_regclass('app.tenant_material_actual') IS NULL OR to_regclass('app.tenant_analytics_freshness') IS NULL THEN
    RAISE EXCEPTION 'operational analytics objects are incomplete';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = 'app.harvest_actual_event'::regclass AND relrowsecurity AND relforcerowsecurity) THEN
    RAISE EXCEPTION 'harvest_actual_event must FORCE RLS';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'app.harvest_actual_event'::regclass AND tgname = 'z_phase2_change_audit' AND tgenabled <> 'D') THEN
    RAISE EXCEPTION 'harvest audit trigger is missing';
  END IF;
END $$;
SELECT 1 FROM app.tenant_plan_actual LIMIT 0;
SELECT 1 FROM app.tenant_material_actual LIMIT 0;
SELECT 1 FROM app.tenant_analytics_freshness LIMIT 0;
ROLLBACK;
