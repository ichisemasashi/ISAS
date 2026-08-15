\set ON_ERROR_STOP on

BEGIN;

-- Cluster roles are provisioned before application migrations. Fail closed when
-- a deployment attempts to run this migration with an incomplete role set.
DO $$
DECLARE required_role text;
BEGIN
  FOREACH required_role IN ARRAY ARRAY['auth_context_owner', 'app_user', 'auth_role'] LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = required_role) THEN
      RAISE EXCEPTION 'required database role % does not exist', required_role;
    END IF;
  END LOOP;
END $$;

CREATE SCHEMA IF NOT EXISTS priv;
CREATE SCHEMA IF NOT EXISTS app_private AUTHORIZATION auth_context_owner;
ALTER SCHEMA app_private OWNER TO auth_context_owner;
GRANT USAGE ON SCHEMA app_private TO app_user;
GRANT USAGE, CREATE ON SCHEMA priv TO auth_context_owner;

SET ROLE auth_context_owner;

CREATE SEQUENCE priv.auth_authorization_version_seq AS bigint START WITH 1 INCREMENT BY 1 NO CYCLE;

CREATE TABLE priv.auth_user (
  user_id uuid PRIMARY KEY,
  issuer text NOT NULL CHECK (length(issuer) BETWEEN 1 AND 2048),
  subject text NOT NULL CHECK (length(subject) BETWEEN 1 AND 2048),
  display_name text NOT NULL CHECK (length(display_name) BETWEEN 1 AND 200),
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended', 'revoked')),
  authorization_version bigint NOT NULL DEFAULT nextval('priv.auth_authorization_version_seq') CHECK (authorization_version > 0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE (issuer, subject)
);

CREATE TABLE priv.auth_role (
  role_key text PRIMARY KEY CHECK (role_key ~ '^[a-z][a-z0-9_:-]{0,127}$'),
  role_label text NOT NULL CHECK (length(role_label) BETWEEN 1 AND 200),
  can_cross_tenant boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE priv.auth_role_capability (
  role_key text NOT NULL REFERENCES priv.auth_role(role_key) ON DELETE CASCADE,
  capability text NOT NULL CHECK (capability IN ('view_others_tracks', 'view_others_punch', 'scope_all')),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (role_key, capability)
);

CREATE TABLE priv.auth_membership (
  tenant_id uuid NOT NULL,
  user_id uuid NOT NULL REFERENCES priv.auth_user(user_id),
  role_key text NOT NULL REFERENCES priv.auth_role(role_key),
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended', 'revoked')),
  membership_version bigint NOT NULL DEFAULT 1 CHECK (membership_version > 0),
  valid_from timestamptz NOT NULL DEFAULT clock_timestamp(),
  valid_until timestamptz,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, user_id),
  CHECK (valid_until IS NULL OR valid_until > valid_from)
);

CREATE TABLE priv.auth_membership_field_group (
  tenant_id uuid NOT NULL,
  user_id uuid NOT NULL,
  field_group_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, user_id, field_group_id),
  FOREIGN KEY (tenant_id, user_id) REFERENCES priv.auth_membership(tenant_id, user_id) ON DELETE CASCADE
);

CREATE TABLE priv.auth_tenant_relation (
  parent_tenant_id uuid NOT NULL,
  child_tenant_id uuid NOT NULL,
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'revoked')),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (parent_tenant_id, child_tenant_id),
  CHECK (parent_tenant_id <> child_tenant_id)
);

CREATE TABLE priv.auth_employer_delegate (
  employer_tenant_id uuid NOT NULL,
  manager_user_id uuid NOT NULL REFERENCES priv.auth_user(user_id),
  employee_user_id uuid NOT NULL REFERENCES priv.auth_user(user_id),
  employer_confirmed_at timestamptz,
  employee_confirmed_at timestamptz,
  revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (employer_tenant_id, manager_user_id, employee_user_id),
  CHECK (manager_user_id <> employee_user_id)
);

CREATE TABLE priv.auth_revocation_event (
  event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES priv.auth_user(user_id),
  tenant_id uuid,
  authorization_version bigint NOT NULL CHECK (authorization_version > 0),
  reason text NOT NULL CHECK (reason ~ '^[a-z][a-z0-9_.:-]{0,127}$'),
  detail jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(detail) = 'object'),
  occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  delivered_at timestamptz,
  delivery_attempts integer NOT NULL DEFAULT 0 CHECK (delivery_attempts >= 0),
  UNIQUE (user_id, authorization_version)
);

CREATE TABLE priv.auth_change_audit (
  audit_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  table_name text NOT NULL,
  operation text NOT NULL CHECK (operation IN ('INSERT', 'UPDATE', 'DELETE')),
  row_key jsonb NOT NULL CHECK (jsonb_typeof(row_key) = 'object'),
  before_value jsonb,
  after_value jsonb,
  actor_pseudonym text NOT NULL,
  transaction_id bigint NOT NULL DEFAULT txid_current()
);

CREATE INDEX auth_user_status_idx ON priv.auth_user (status, user_id);
CREATE INDEX auth_membership_user_idx ON priv.auth_membership (user_id, status, tenant_id);
CREATE INDEX auth_membership_role_idx ON priv.auth_membership (role_key, status, user_id);
CREATE INDEX auth_membership_active_tenant_idx ON priv.auth_membership (tenant_id, user_id)
  WHERE status = 'active';
CREATE INDEX auth_scope_user_idx ON priv.auth_membership_field_group (user_id, tenant_id, field_group_id);
CREATE INDEX auth_relation_child_idx ON priv.auth_tenant_relation (child_tenant_id, parent_tenant_id)
  WHERE status = 'active';
CREATE INDEX auth_delegate_manager_idx ON priv.auth_employer_delegate (manager_user_id, employer_tenant_id)
  WHERE revoked_at IS NULL;
CREATE INDEX auth_revocation_pending_idx ON priv.auth_revocation_event (event_id)
  WHERE delivered_at IS NULL;
CREATE INDEX auth_revocation_user_version_idx ON priv.auth_revocation_event (user_id, authorization_version DESC);
CREATE INDEX auth_revocation_tenant_event_idx ON priv.auth_revocation_event (tenant_id, event_id)
  WHERE tenant_id IS NOT NULL;
CREATE INDEX auth_audit_time_idx ON priv.auth_change_audit (occurred_at DESC, audit_id DESC);

DO $$
DECLARE table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'auth_user', 'auth_role', 'auth_role_capability', 'auth_membership',
    'auth_membership_field_group', 'auth_tenant_relation', 'auth_employer_delegate',
    'auth_revocation_event', 'auth_change_audit'
  ] LOOP
    EXECUTE format('ALTER TABLE priv.%I OWNER TO auth_context_owner', table_name);
    EXECUTE format('ALTER TABLE priv.%I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE priv.%I FORCE ROW LEVEL SECURITY', table_name);
    EXECUTE format(
      'CREATE POLICY auth_context_owner_only ON priv.%I AS PERMISSIVE FOR ALL TO auth_context_owner USING (true) WITH CHECK (true)',
      table_name
    );
  END LOOP;
END $$;

CREATE FUNCTION app_private.bump_authorization_version(
  p_user_id uuid,
  p_tenant_id uuid,
  p_reason text,
  p_detail jsonb DEFAULT '{}'::jsonb
) RETURNS bigint
LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
DECLARE next_version bigint;
BEGIN
  IF p_user_id IS NULL OR p_reason !~ '^[a-z][a-z0-9_.:-]{0,127}$' OR jsonb_typeof(p_detail) <> 'object' THEN
    RAISE EXCEPTION 'invalid authorization version bump';
  END IF;
  next_version := nextval('priv.auth_authorization_version_seq');
  UPDATE priv.auth_user
     SET authorization_version = next_version,
         updated_at = clock_timestamp()
   WHERE user_id = p_user_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'authorization user % does not exist', p_user_id; END IF;
  INSERT INTO priv.auth_revocation_event (user_id, tenant_id, authorization_version, reason, detail)
  VALUES (p_user_id, p_tenant_id, next_version, p_reason, COALESCE(p_detail, '{}'::jsonb));
  RETURN next_version;
END $$;

CREATE FUNCTION app_private.audit_auth_change()
RETURNS trigger LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
DECLARE before_row jsonb; after_row jsonb; key_row jsonb;
BEGIN
  before_row := CASE WHEN TG_OP IN ('UPDATE', 'DELETE') THEN to_jsonb(OLD) ELSE NULL END;
  after_row := CASE WHEN TG_OP IN ('INSERT', 'UPDATE') THEN to_jsonb(NEW) ELSE NULL END;
  key_row := COALESCE(after_row, before_row) - ARRAY[
    'issuer', 'subject', 'display_name', 'role_label', 'detail', 'before_value', 'after_value'
  ];
  INSERT INTO priv.auth_change_audit
    (table_name, operation, row_key, before_value, after_value, actor_pseudonym)
  VALUES (
    TG_TABLE_NAME,
    TG_OP,
    key_row,
    before_row,
    after_row,
    COALESCE(NULLIF(current_setting('app.actor_pseudonym', true), ''), session_user)
  );
  RETURN COALESCE(NEW, OLD);
END $$;

CREATE FUNCTION app_private.auth_user_version_trigger()
RETURNS trigger LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    INSERT INTO priv.auth_revocation_event (user_id, authorization_version, reason, detail)
    VALUES (NEW.user_id, NEW.authorization_version, 'user.created', '{}'::jsonb);
  ELSE
    PERFORM app_private.bump_authorization_version(
      NEW.user_id, NULL, 'user.changed',
      jsonb_build_object('status', NEW.status)
    );
  END IF;
  RETURN NEW;
END $$;

CREATE FUNCTION app_private.auth_membership_prepare()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' THEN
    NEW.membership_version := GREATEST(OLD.membership_version + 1, NEW.membership_version);
    NEW.updated_at := clock_timestamp();
  END IF;
  RETURN NEW;
END $$;

CREATE FUNCTION app_private.auth_subject_version_trigger()
RETURNS trigger LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
DECLARE affected_user uuid; affected_tenant uuid; event_reason text;
BEGIN
  event_reason := CASE TG_TABLE_NAME
    WHEN 'auth_membership' THEN 'membership.changed'
    WHEN 'auth_membership_field_group' THEN 'scope.changed'
    ELSE 'authorization.changed'
  END;
  affected_user := COALESCE(NEW.user_id, OLD.user_id);
  affected_tenant := COALESCE(NEW.tenant_id, OLD.tenant_id);
  PERFORM app_private.bump_authorization_version(
    affected_user, affected_tenant, event_reason,
    jsonb_build_object('operation', TG_OP, 'table', TG_TABLE_NAME)
  );
  RETURN COALESCE(NEW, OLD);
END $$;

CREATE FUNCTION app_private.auth_delegate_version_trigger()
RETURNS trigger LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
BEGIN
  PERFORM app_private.bump_authorization_version(
    COALESCE(NEW.manager_user_id, OLD.manager_user_id),
    COALESCE(NEW.employer_tenant_id, OLD.employer_tenant_id),
    'employer_delegate.changed',
    jsonb_build_object('operation', TG_OP, 'table', TG_TABLE_NAME)
  );
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;

CREATE FUNCTION app_private.auth_role_version_trigger()
RETURNS trigger LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
DECLARE affected_user uuid; changed_role text;
BEGIN
  changed_role := COALESCE(NEW.role_key, OLD.role_key);
  FOR affected_user IN
    SELECT DISTINCT membership.user_id
      FROM priv.auth_membership membership
     WHERE membership.role_key = changed_role
  LOOP
    PERFORM app_private.bump_authorization_version(
      affected_user, NULL, 'role.changed',
      jsonb_build_object('operation', TG_OP, 'role_key', changed_role)
    );
  END LOOP;
  RETURN COALESCE(NEW, OLD);
END $$;

CREATE FUNCTION app_private.auth_tenant_relation_version_trigger()
RETURNS trigger LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
DECLARE affected_user uuid; parent_tenant uuid;
BEGIN
  parent_tenant := COALESCE(NEW.parent_tenant_id, OLD.parent_tenant_id);
  FOR affected_user IN
    SELECT DISTINCT membership.user_id
      FROM priv.auth_membership membership
      JOIN priv.auth_role role USING (role_key)
     WHERE membership.tenant_id = parent_tenant AND role.can_cross_tenant
  LOOP
    PERFORM app_private.bump_authorization_version(
      affected_user, parent_tenant, 'tenant_relation.changed',
      jsonb_build_object('operation', TG_OP)
    );
  END LOOP;
  RETURN COALESCE(NEW, OLD);
END $$;

CREATE FUNCTION app_private.validate_auth_context(
  p_user_id uuid,
  p_tenant_id uuid,
  p_allowed_tenants uuid[],
  p_scope_field_groups uuid[],
  p_caps text[],
  p_employer_subject_users uuid[]
) RETURNS TABLE (
  user_id uuid,
  tenant_id uuid,
  allowed_tenants uuid[],
  scope_field_groups uuid[],
  caps text[],
  employer_subject_users uuid[],
  membership_version bigint,
  authorization_version bigint
) LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
DECLARE
  v_role_key text;
  v_can_cross boolean;
  v_entitled_caps text[];
  v_membership_version bigint;
  v_authorization_version bigint;
BEGIN
  IF p_user_id IS NULL OR p_tenant_id IS NULL
     OR p_allowed_tenants IS NULL OR cardinality(p_allowed_tenants) < 1 OR cardinality(p_allowed_tenants) > 100
     OR p_scope_field_groups IS NULL OR cardinality(p_scope_field_groups) > 1000
     OR p_caps IS NULL OR cardinality(p_caps) > 128
     OR p_employer_subject_users IS NULL OR cardinality(p_employer_subject_users) > 1000
     OR array_position(p_allowed_tenants, NULL) IS NOT NULL
     OR array_position(p_scope_field_groups, NULL) IS NOT NULL
     OR array_position(p_caps, NULL) IS NOT NULL
     OR array_position(p_employer_subject_users, NULL) IS NOT NULL
     OR NOT (p_tenant_id = ANY(p_allowed_tenants)) THEN RETURN;
  END IF;
  IF EXISTS (SELECT 1 FROM unnest(p_allowed_tenants) value GROUP BY value HAVING count(*) > 1)
     OR EXISTS (SELECT 1 FROM unnest(p_scope_field_groups) value GROUP BY value HAVING count(*) > 1)
     OR EXISTS (SELECT 1 FROM unnest(p_caps) value GROUP BY value HAVING count(*) > 1)
     OR EXISTS (SELECT 1 FROM unnest(p_employer_subject_users) value GROUP BY value HAVING count(*) > 1) THEN RETURN;
  END IF;

  SELECT membership.role_key, role.can_cross_tenant, membership.membership_version, auth_user.authorization_version
    INTO v_role_key, v_can_cross, v_membership_version, v_authorization_version
    FROM priv.auth_membership membership
    JOIN priv.auth_role role USING (role_key)
    JOIN priv.auth_user auth_user USING (user_id)
   WHERE membership.tenant_id = p_tenant_id
     AND membership.user_id = p_user_id
     AND membership.status = 'active'
     AND auth_user.status = 'active'
     AND membership.valid_from <= statement_timestamp()
     AND (membership.valid_until IS NULL OR membership.valid_until > statement_timestamp());
  IF NOT FOUND THEN RETURN; END IF;

  SELECT COALESCE(array_agg(capability.capability ORDER BY capability.capability), '{}'::text[])
    INTO v_entitled_caps
    FROM priv.auth_role_capability capability
   WHERE capability.role_key = v_role_key;
  IF NOT (p_caps <@ v_entitled_caps) THEN RETURN; END IF;

  IF EXISTS (
    SELECT 1 FROM unnest(p_allowed_tenants) requested(tenant_id)
     WHERE requested.tenant_id <> p_tenant_id
       AND NOT (v_can_cross AND EXISTS (
         SELECT 1 FROM priv.auth_tenant_relation relation
          WHERE relation.parent_tenant_id = p_tenant_id
            AND relation.child_tenant_id = requested.tenant_id
            AND relation.status = 'active'))
  ) THEN RETURN; END IF;

  IF NOT ('scope_all' = ANY(v_entitled_caps)) AND EXISTS (
    SELECT 1 FROM unnest(p_scope_field_groups) requested(field_group_id)
     WHERE NOT EXISTS (
       SELECT 1 FROM priv.auth_membership_field_group granted
        WHERE granted.tenant_id = p_tenant_id
          AND granted.user_id = p_user_id
          AND granted.field_group_id = requested.field_group_id)
  ) THEN RETURN; END IF;

  IF EXISTS (
    SELECT 1 FROM unnest(p_employer_subject_users) requested(employee_user_id)
     WHERE NOT EXISTS (
       SELECT 1 FROM priv.auth_employer_delegate delegated
        WHERE delegated.employer_tenant_id = p_tenant_id
          AND delegated.manager_user_id = p_user_id
          AND delegated.employee_user_id = requested.employee_user_id
          AND delegated.employer_confirmed_at IS NOT NULL
          AND delegated.employee_confirmed_at IS NOT NULL
          AND delegated.revoked_at IS NULL)
  ) THEN RETURN; END IF;

  RETURN QUERY SELECT p_user_id, p_tenant_id, p_allowed_tenants,
                      p_scope_field_groups, p_caps, p_employer_subject_users,
                      v_membership_version, v_authorization_version;
END $$;

CREATE TRIGGER auth_user_version
  AFTER INSERT OR UPDATE OF issuer, subject, status ON priv.auth_user
  FOR EACH ROW EXECUTE FUNCTION app_private.auth_user_version_trigger();
CREATE TRIGGER auth_membership_prepare
  BEFORE UPDATE ON priv.auth_membership
  FOR EACH ROW EXECUTE FUNCTION app_private.auth_membership_prepare();
CREATE TRIGGER auth_membership_version
  AFTER INSERT OR UPDATE OR DELETE ON priv.auth_membership
  FOR EACH ROW EXECUTE FUNCTION app_private.auth_subject_version_trigger();
CREATE TRIGGER auth_scope_version
  AFTER INSERT OR UPDATE OR DELETE ON priv.auth_membership_field_group
  FOR EACH ROW EXECUTE FUNCTION app_private.auth_subject_version_trigger();
CREATE TRIGGER auth_delegate_version
  AFTER INSERT OR UPDATE OR DELETE ON priv.auth_employer_delegate
  FOR EACH ROW EXECUTE FUNCTION app_private.auth_delegate_version_trigger();
CREATE TRIGGER auth_role_version
  AFTER UPDATE OR DELETE ON priv.auth_role
  FOR EACH ROW EXECUTE FUNCTION app_private.auth_role_version_trigger();
CREATE TRIGGER auth_capability_version
  AFTER INSERT OR UPDATE OR DELETE ON priv.auth_role_capability
  FOR EACH ROW EXECUTE FUNCTION app_private.auth_role_version_trigger();
CREATE TRIGGER auth_relation_version
  AFTER INSERT OR UPDATE OR DELETE ON priv.auth_tenant_relation
  FOR EACH ROW EXECUTE FUNCTION app_private.auth_tenant_relation_version_trigger();

DO $$
DECLARE table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'auth_user', 'auth_role', 'auth_role_capability', 'auth_membership',
    'auth_membership_field_group', 'auth_tenant_relation', 'auth_employer_delegate'
  ] LOOP
    EXECUTE format(
      'CREATE TRIGGER z_auth_change_audit AFTER INSERT OR UPDATE OR DELETE ON priv.%I FOR EACH ROW EXECUTE FUNCTION app_private.audit_auth_change()',
      table_name
    );
  END LOOP;
END $$;

INSERT INTO priv.auth_role (role_key, role_label, can_cross_tenant) VALUES
  ('worker', '作業者', false),
  ('field_supervisor', '圃場責任者', false),
  ('organization_admin', '組織管理者', false),
  ('group_admin', 'グループ管理者', true),
  ('contractor', '受託作業者', false);

RESET ROLE;

REVOKE CREATE ON SCHEMA priv FROM auth_context_owner;
REVOKE ALL ON SCHEMA priv FROM PUBLIC, app_user, auth_role;
REVOKE ALL ON ALL TABLES IN SCHEMA priv FROM PUBLIC, app_user, auth_role;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA priv FROM PUBLIC, app_user, auth_role;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA app_private FROM PUBLIC, app_user, auth_role;
GRANT EXECUTE ON FUNCTION app_private.validate_auth_context(uuid,uuid,uuid[],uuid[],text[],uuid[]) TO app_user;

COMMIT;
