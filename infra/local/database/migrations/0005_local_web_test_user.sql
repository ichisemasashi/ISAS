\set ON_ERROR_STOP on
BEGIN;

GRANT CREATE ON SCHEMA app_private TO auth_context_owner;
SET ROLE auth_context_owner;

CREATE FUNCTION app_private.local_register_test_user(
  p_actor_user_id uuid,
  p_tenant_id uuid,
  p_user_id uuid,
  p_issuer text,
  p_display_name text,
  p_role_key text,
  p_field_group_ids uuid[]
) RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, priv, app_private AS $$
DECLARE field_group_id uuid;
BEGIN
  IF p_tenant_id <> '20000000-0000-4000-8000-000000000001'::uuid
     OR p_issuer <> 'https://isas.localhost:8443/oidc/realms/isas-local'
     OR p_field_group_ids IS DISTINCT FROM ARRAY['30000000-0000-4000-8000-000000000001'::uuid] THEN
    RAISE EXCEPTION 'local test user boundary rejected' USING ERRCODE = '42501';
  END IF;
  IF NOT app_private.permanent_capability(p_actor_user_id, p_tenant_id, 'security:manage') THEN
    RAISE EXCEPTION 'local test user registration denied' USING ERRCODE = '42501';
  END IF;
  IF length(trim(COALESCE(p_display_name, ''))) NOT BETWEEN 1 AND 200
     OR NOT EXISTS (SELECT 1 FROM priv.auth_role WHERE role_key = p_role_key) THEN
    RAISE EXCEPTION 'invalid local test user profile' USING ERRCODE = '22023';
  END IF;

  INSERT INTO priv.auth_user(user_id,issuer,subject,display_name,status)
  VALUES(p_user_id,p_issuer,p_user_id::text,trim(p_display_name),'active');
  INSERT INTO priv.auth_membership(tenant_id,user_id,role_key,status)
  VALUES(p_tenant_id,p_user_id,p_role_key,'active');
  FOREACH field_group_id IN ARRAY p_field_group_ids LOOP
    INSERT INTO priv.auth_membership_field_group(tenant_id,user_id,field_group_id)
    VALUES(p_tenant_id,p_user_id,field_group_id);
  END LOOP;

  RETURN jsonb_build_object(
    'userId',p_user_id,'tenantId',p_tenant_id,'roleKey',p_role_key,
    'fieldGroupIds',p_field_group_ids,'status','active'
  );
END $$;

RESET ROLE;
REVOKE CREATE ON SCHEMA app_private FROM auth_context_owner;
REVOKE ALL ON FUNCTION app_private.local_register_test_user(uuid,uuid,uuid,text,text,text,uuid[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_private.local_register_test_user(uuid,uuid,uuid,text,text,text,uuid[]) TO auth_role;

COMMIT;
