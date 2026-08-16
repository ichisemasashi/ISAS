\set ON_ERROR_STOP on
BEGIN;
SET ROLE app_owner;

DO $$
DECLARE missing text;
BEGIN
  SELECT string_agg(name, ', ') INTO missing FROM (VALUES
    ('location_consent_policy'), ('location_tracking_preference'),
    ('location_track_point'), ('location_access_audit')
  ) expected(name) WHERE to_regclass('app.' || name) IS NULL;
  IF missing IS NOT NULL THEN RAISE EXCEPTION 'missing location objects: %', missing; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = 'app.location_track_point'::regclass AND relrowsecurity AND relforcerowsecurity) THEN
    RAISE EXCEPTION 'location_track_point must FORCE RLS';
  END IF;
  IF has_table_privilege('app_user', 'app.location_track_point', 'SELECT') THEN
    RAISE EXCEPTION 'raw location points must not be directly selectable';
  END IF;
  IF NOT has_function_privilege('app_user', 'app.read_location_tracks(uuid,timestamptz,timestamptz,text)', 'EXECUTE') THEN
    RAISE EXCEPTION 'audited track reader is not executable';
  END IF;
  IF NOT has_function_privilege('app_user', 'app.read_own_work_actuals(timestamptz,timestamptz)', 'EXECUTE') THEN
    RAISE EXCEPTION 'audited work actual reader is not executable';
  END IF;
END $$;

SELECT 1 FROM app.work_time_actual LIMIT 0;
SELECT 1 FROM app.field_presence_actual LIMIT 0;
ROLLBACK;
