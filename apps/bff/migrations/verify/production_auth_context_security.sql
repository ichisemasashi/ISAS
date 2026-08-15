\set ON_ERROR_STOP on
\if :{?EXPECTED_POSTGIS_VERSION}
\else
\set EXPECTED_POSTGIS_VERSION '3.4.6'
\endif

SELECT set_config('isas.expected_postgis_version', :'EXPECTED_POSTGIS_VERSION', false);

DO $verify$
DECLARE
  missing_owner integer;
  missing_force_rls integer;
  missing_audit integer;
  unsafe_owner integer;
  missing_runtime_function integer;
BEGIN
  SELECT count(*) INTO missing_owner
  FROM pg_class class
  JOIN pg_namespace namespace ON namespace.oid = class.relnamespace
  WHERE namespace.nspname = 'priv'
    AND (class.relname LIKE 'auth_%' OR class.relname LIKE 'privacy_%')
    AND class.relkind = 'r'
    AND pg_get_userbyid(class.relowner) <> 'auth_context_owner';
  IF missing_owner <> 0 THEN
    RAISE EXCEPTION 'AuthContext production check: % table(s) have an unexpected owner', missing_owner;
  END IF;

  SELECT count(*) INTO missing_force_rls
  FROM pg_class class
  JOIN pg_namespace namespace ON namespace.oid = class.relnamespace
  WHERE namespace.nspname = 'priv'
    AND (class.relname LIKE 'auth_%' OR class.relname LIKE 'privacy_%')
    AND class.relkind = 'r'
    AND (NOT class.relrowsecurity OR NOT class.relforcerowsecurity);
  IF missing_force_rls <> 0 THEN
    RAISE EXCEPTION 'AuthContext production check: % table(s) lack ENABLE/FORCE RLS', missing_force_rls;
  END IF;

  SELECT count(*) INTO missing_audit
  FROM pg_class class
  JOIN pg_namespace namespace ON namespace.oid = class.relnamespace
  WHERE namespace.nspname = 'priv'
    AND (class.relname LIKE 'auth_%' OR class.relname LIKE 'privacy_%')
    AND class.relkind = 'r'
    AND class.relname NOT IN ('auth_change_audit', 'privacy_request_event')
    AND NOT EXISTS (
      SELECT 1
      FROM pg_trigger trigger
      WHERE trigger.tgrelid = class.oid
        AND trigger.tgname = 'z_auth_change_audit'
        AND trigger.tgenabled = 'O'
        AND NOT trigger.tgisinternal
    );
  IF missing_audit <> 0 THEN
    RAISE EXCEPTION 'AuthContext production check: % table(s) lack an enabled audit trigger', missing_audit;
  END IF;

  SELECT count(*) INTO unsafe_owner
  FROM pg_roles
  WHERE rolname IN ('auth_context_owner', 'app_owner')
    AND (rolsuper OR rolbypassrls OR rolcanlogin);
  IF unsafe_owner <> 0 THEN
    RAISE EXCEPTION 'AuthContext production check: owner role is privileged, BYPASSRLS, or LOGIN';
  END IF;

  SELECT count(*) INTO missing_runtime_function
  FROM unnest(ARRAY[
    'resolve_oidc_user(text,text)',
    'list_authorized_tenants(uuid)',
    'derive_authorization_context(uuid,uuid)',
    'claim_auth_revocation(uuid,integer)',
    'complete_auth_revocation(bigint,uuid)',
    'release_auth_revocation(bigint,uuid)',
    'security_admin_snapshot(uuid,uuid)',
    'create_security_change_request(uuid,uuid,uuid,text,uuid,text,text,jsonb)',
    'decide_security_change_request(uuid,uuid,boolean,text)',
    'create_privacy_request(uuid,uuid,uuid,uuid,text,jsonb,timestamptz,text)',
    'transition_privacy_request(uuid,uuid,text,text,text)'
  ]) expected(signature)
  WHERE to_regprocedure('app_private.' || expected.signature) IS NULL;
  IF missing_runtime_function <> 0 THEN
    RAISE EXCEPTION 'AuthContext production check: % identity runtime function(s) missing', missing_runtime_function;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_extension
    WHERE extname = 'postgis'
      AND extversion = current_setting('isas.expected_postgis_version')
  ) THEN
    RAISE EXCEPTION 'Production check: expected PostGIS %, found %',
      current_setting('isas.expected_postgis_version'),
      coalesce((SELECT extversion FROM pg_extension WHERE extname = 'postgis'), 'not installed');
  END IF;

  RAISE NOTICE 'Production AuthContext security: owner, FORCE RLS, audit trigger, PostGIS PASS';
END
$verify$;
