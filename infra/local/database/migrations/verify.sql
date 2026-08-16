\set ON_ERROR_STOP on
DO $$
DECLARE unsafe integer; missing integer;
BEGIN
  SELECT count(*) INTO unsafe FROM pg_roles WHERE rolname IN ('auth_context_owner','app_owner','local_support_owner') AND (rolsuper OR rolbypassrls OR rolcanlogin);
  IF unsafe <> 0 THEN RAISE EXCEPTION 'unsafe owner roles: %', unsafe; END IF;
  SELECT count(*) INTO missing FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='local_support' AND c.relkind='r' AND pg_get_userbyid(c.relowner)<>'local_support_owner';
  IF missing <> 0 THEN RAISE EXCEPTION 'local_support owner mismatch: %', missing; END IF;
  IF has_schema_privilege('app_user','local_support','USAGE') OR has_schema_privilege('auth_role','local_support','USAGE') THEN RAISE EXCEPTION 'business/auth role can use local_support'; END IF;
  IF NOT has_function_privilege('ops_user','local_support.put_session(text,uuid,bigint,bytea,timestamp with time zone,timestamp with time zone)','EXECUTE') THEN RAISE EXCEPTION 'ops_user fixed function grant missing'; END IF;
END $$;
SELECT 'local support ownership and grants: PASS' AS result;
