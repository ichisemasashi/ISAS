-- =====================================================================
-- S8: ADR-0009 AuthContext DB側検証関数
--
-- BFFが導出した候補集合を、現在のmembership / role / scope / 双方向確認済み
-- 雇用関係へ再照合し、正規値を1行または拒否（0行）で返す。
-- 本SQLはADR-0005 v8で採用した権限基表の最小物理形を検証する参照DDLである。
-- 次工程で版管理・失効イベント・索引・backfillを加え、本番migrationへ昇格する。
-- =====================================================================
\set ON_ERROR_STOP on
\set A  '11111111-1111-7111-8111-111111111111'
\set B  '22222222-2222-7222-8222-222222222222'
\set C  '33333333-3333-7333-8333-333333333333'
\set U1 'aaaaaaaa-0000-7000-8000-000000000001'
\set U2 'bbbbbbbb-0000-7000-8000-000000000002'
\set U3 'cccccccc-0000-7000-8000-000000000003'
\set U4 'dddddddd-0000-7000-8000-000000000004'
\set F1 'f1111111-1111-7111-8111-111111111111'
\set F2 'f2222222-2222-7222-8222-222222222222'

SELECT version();
SELECT extversion AS postgis_version FROM pg_extension WHERE extname = 'postgis';

CREATE SCHEMA app_private AUTHORIZATION auth_context_owner;
GRANT USAGE ON SCHEMA app_private TO app_user;
GRANT USAGE, CREATE ON SCHEMA priv TO auth_context_owner;

SET ROLE auth_context_owner;

CREATE TABLE priv.auth_role (
  role_key text PRIMARY KEY,
  can_cross_tenant boolean NOT NULL DEFAULT false
);

CREATE TABLE priv.auth_role_capability (
  role_key text NOT NULL REFERENCES priv.auth_role(role_key) ON DELETE CASCADE,
  capability text NOT NULL CHECK (capability IN ('view_others_tracks', 'view_others_punch', 'scope_all')),
  PRIMARY KEY (role_key, capability)
);

CREATE TABLE priv.auth_membership (
  tenant_id uuid NOT NULL,
  user_id uuid NOT NULL,
  role_key text NOT NULL REFERENCES priv.auth_role(role_key),
  status text NOT NULL CHECK (status IN ('active', 'suspended', 'revoked')),
  membership_version bigint NOT NULL CHECK (membership_version > 0),
  PRIMARY KEY (tenant_id, user_id)
);

CREATE TABLE priv.auth_membership_field_group (
  tenant_id uuid NOT NULL,
  user_id uuid NOT NULL,
  field_group_id uuid NOT NULL,
  PRIMARY KEY (tenant_id, user_id, field_group_id),
  FOREIGN KEY (tenant_id, user_id) REFERENCES priv.auth_membership(tenant_id, user_id) ON DELETE CASCADE
);

CREATE TABLE priv.auth_tenant_relation (
  parent_tenant_id uuid NOT NULL,
  child_tenant_id uuid NOT NULL,
  status text NOT NULL CHECK (status IN ('active', 'revoked')),
  PRIMARY KEY (parent_tenant_id, child_tenant_id),
  CHECK (parent_tenant_id <> child_tenant_id)
);

CREATE TABLE priv.auth_employer_delegate (
  employer_tenant_id uuid NOT NULL,
  manager_user_id uuid NOT NULL,
  employee_user_id uuid NOT NULL,
  employer_confirmed_at timestamptz,
  employee_confirmed_at timestamptz,
  revoked_at timestamptz,
  PRIMARY KEY (employer_tenant_id, manager_user_id, employee_user_id),
  CHECK (manager_user_id <> employee_user_id)
);

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
  employer_subject_users uuid[]
) LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
DECLARE
  v_role_key text;
  v_can_cross boolean;
  v_entitled_caps text[];
BEGIN
  -- NULL、空のtenant集合、上限超過、NULL要素、重複はすべて拒否する。
  IF p_user_id IS NULL OR p_tenant_id IS NULL
     OR p_allowed_tenants IS NULL OR cardinality(p_allowed_tenants) < 1 OR cardinality(p_allowed_tenants) > 100
     OR p_scope_field_groups IS NULL OR cardinality(p_scope_field_groups) > 1000
     OR p_caps IS NULL OR cardinality(p_caps) > 128
     OR p_employer_subject_users IS NULL OR cardinality(p_employer_subject_users) > 1000
     OR array_position(p_allowed_tenants, NULL) IS NOT NULL
     OR array_position(p_scope_field_groups, NULL) IS NOT NULL
     OR array_position(p_caps, NULL) IS NOT NULL
     OR array_position(p_employer_subject_users, NULL) IS NOT NULL
     OR NOT (p_tenant_id = ANY(p_allowed_tenants)) THEN
    RETURN;
  END IF;

  IF EXISTS (SELECT 1 FROM unnest(p_allowed_tenants) value GROUP BY value HAVING count(*) > 1)
     OR EXISTS (SELECT 1 FROM unnest(p_scope_field_groups) value GROUP BY value HAVING count(*) > 1)
     OR EXISTS (SELECT 1 FROM unnest(p_caps) value GROUP BY value HAVING count(*) > 1)
     OR EXISTS (SELECT 1 FROM unnest(p_employer_subject_users) value GROUP BY value HAVING count(*) > 1) THEN
    RETURN;
  END IF;

  SELECT m.role_key, r.can_cross_tenant
    INTO v_role_key, v_can_cross
    FROM priv.auth_membership m
    JOIN priv.auth_role r USING (role_key)
   WHERE m.tenant_id = p_tenant_id AND m.user_id = p_user_id AND m.status = 'active';
  IF NOT FOUND THEN RETURN; END IF;

  SELECT COALESCE(array_agg(rc.capability ORDER BY rc.capability), '{}'::text[])
    INTO v_entitled_caps
    FROM priv.auth_role_capability rc
   WHERE rc.role_key = v_role_key;

  -- capabilityはrole由来集合の部分集合だけを許す。
  IF NOT (p_caps <@ v_entitled_caps) THEN RETURN; END IF;

  -- 通常は選択tenantだけ。横断roleでも、activeな親子関係にあるtenantだけを許す。
  IF EXISTS (
    SELECT 1 FROM unnest(p_allowed_tenants) requested(tenant_id)
     WHERE requested.tenant_id <> p_tenant_id
       AND NOT (v_can_cross AND EXISTS (
         SELECT 1 FROM priv.auth_tenant_relation relation
          WHERE relation.parent_tenant_id = p_tenant_id
            AND relation.child_tenant_id = requested.tenant_id
            AND relation.status = 'active'))
  ) THEN RETURN; END IF;

  -- scope_allが無ければ、選択tenantのmembershipへ明示付与された圃場groupだけ。
  IF NOT ('scope_all' = ANY(v_entitled_caps)) AND EXISTS (
    SELECT 1 FROM unnest(p_scope_field_groups) requested(field_group_id)
     WHERE NOT EXISTS (
       SELECT 1 FROM priv.auth_membership_field_group granted
        WHERE granted.tenant_id = p_tenant_id
          AND granted.user_id = p_user_id
          AND granted.field_group_id = requested.field_group_id)
  ) THEN RETURN; END IF;

  -- 雇用主横断は、tenant・管理者・従業員の組に対して双方確認済みかつ未失効だけ。
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
                      p_scope_field_groups, p_caps, p_employer_subject_users;
END $$;

RESET ROLE;
REVOKE CREATE ON SCHEMA priv FROM auth_context_owner;
REVOKE ALL ON ALL TABLES IN SCHEMA priv FROM PUBLIC, app_user, auth_role;
REVOKE ALL ON FUNCTION app_private.validate_auth_context(uuid,uuid,uuid[],uuid[],text[],uuid[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_private.validate_auth_context(uuid,uuid,uuid[],uuid[],text[],uuid[]) TO app_user;

-- --- 検証データ ---
SET ROLE auth_context_owner;
INSERT INTO priv.auth_role VALUES ('worker', false), ('group_admin', true), ('revoked_role', false);
INSERT INTO priv.auth_role_capability VALUES
  ('worker', 'view_others_punch'),
  ('group_admin', 'scope_all'),
  ('group_admin', 'view_others_tracks'),
  ('group_admin', 'view_others_punch');
INSERT INTO priv.auth_membership VALUES
  (:'A', :'U1', 'group_admin', 'active', 1),
  (:'A', :'U2', 'worker', 'active', 1),
  (:'B', :'U3', 'worker', 'active', 1),
  (:'A', :'U4', 'revoked_role', 'revoked', 2);
INSERT INTO priv.auth_membership_field_group VALUES (:'A', :'U2', :'F1');
INSERT INTO priv.auth_tenant_relation VALUES (:'A', :'B', 'active'), (:'A', :'C', 'revoked');
INSERT INTO priv.auth_employer_delegate VALUES
  (:'A', :'U1', :'U3', now(), now(), NULL),
  (:'A', :'U1', :'U4', now(), NULL, NULL);
RESET ROLE;

CREATE OR REPLACE FUNCTION pg_temp.ck(condition boolean, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF NOT condition THEN RAISE EXCEPTION 'FAIL  %', label; END IF;
  RAISE NOTICE 'PASS  %', label;
END $$;

SET ROLE app_user;
SELECT pg_temp.ck(count(*) = 1, '(1) 通常tenant＋付与scope/capabilityを受理')
  FROM app_private.validate_auth_context(:'U2', :'A', ARRAY[:'A']::uuid[], ARRAY[:'F1']::uuid[], ARRAY['view_others_punch'], '{}'::uuid[]);
SELECT pg_temp.ck(count(*) = 0, '(2) role由来でないcapabilityを拒否')
  FROM app_private.validate_auth_context(:'U2', :'A', ARRAY[:'A']::uuid[], ARRAY[:'F1']::uuid[], ARRAY['view_others_tracks'], '{}'::uuid[]);
SELECT pg_temp.ck(count(*) = 0, '(3) membership外scopeを拒否')
  FROM app_private.validate_auth_context(:'U2', :'A', ARRAY[:'A']::uuid[], ARRAY[:'F2']::uuid[], ARRAY['view_others_punch'], '{}'::uuid[]);
SELECT pg_temp.ck(count(*) = 1, '(4) group管理者のactive配下tenant横断を受理')
  FROM app_private.validate_auth_context(:'U1', :'A', ARRAY[:'A', :'B']::uuid[], '{}'::uuid[], ARRAY['scope_all'], '{}'::uuid[]);
SELECT pg_temp.ck(count(*) = 0, '(5) revokedな配下tenant横断を拒否')
  FROM app_private.validate_auth_context(:'U1', :'A', ARRAY[:'A', :'C']::uuid[], '{}'::uuid[], ARRAY['scope_all'], '{}'::uuid[]);
SELECT pg_temp.ck(count(*) = 1, '(6) 双方向確認済み雇用関係だけを受理')
  FROM app_private.validate_auth_context(:'U1', :'A', ARRAY[:'A']::uuid[], '{}'::uuid[], '{}'::text[], ARRAY[:'U3']::uuid[]);
SELECT pg_temp.ck(count(*) = 0, '(7) 片側確認だけの雇用関係を拒否')
  FROM app_private.validate_auth_context(:'U1', :'A', ARRAY[:'A']::uuid[], '{}'::uuid[], '{}'::text[], ARRAY[:'U4']::uuid[]);
SELECT pg_temp.ck(count(*) = 0, '(8) revoked membershipを拒否')
  FROM app_private.validate_auth_context(:'U4', :'A', ARRAY[:'A']::uuid[], '{}'::uuid[], '{}'::text[], '{}'::uuid[]);
SELECT pg_temp.ck(count(*) = 0, '(9) 空allowed_tenantsを拒否')
  FROM app_private.validate_auth_context(:'U2', :'A', '{}'::uuid[], '{}'::uuid[], '{}'::text[], '{}'::uuid[]);
SELECT pg_temp.ck(count(*) = 0, '(10) 重複集合を拒否')
  FROM app_private.validate_auth_context(:'U2', :'A', ARRAY[:'A', :'A']::uuid[], '{}'::uuid[], '{}'::text[], '{}'::uuid[]);

DO $$ BEGIN
  BEGIN
    PERFORM count(*) FROM priv.auth_membership;
    RAISE EXCEPTION 'FAIL  (11) app_userが権限基表を直接参照できた';
  EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'PASS  (11) app_userの権限基表直接参照を拒否';
  END;
END $$;
RESET ROLE;

SELECT pg_temp.ck(has_function_privilege('app_user', 'app_private.validate_auth_context(uuid,uuid,uuid[],uuid[],text[],uuid[])', 'EXECUTE'), '(12a) app_userは検証関数だけ実行可');
SELECT pg_temp.ck(NOT has_function_privilege('auth_role', 'app_private.validate_auth_context(uuid,uuid,uuid[],uuid[],text[],uuid[])', 'EXECUTE'), '(12b) auth_roleに業務AuthContext関数を付与しない');
SELECT pg_temp.ck(NOT rolsuper AND NOT rolbypassrls AND NOT rolcanlogin, '(12c) auth_context_ownerはNOLOGIN・非特権') FROM pg_roles WHERE rolname='auth_context_owner';
SELECT pg_temp.ck(p.prosecdef AND p.proconfig @> ARRAY['search_path=pg_catalog, priv'], '(12d) SECURITY DEFINER＋固定search_path')
  FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
 WHERE n.nspname='app_private' AND p.proname='validate_auth_context';
SELECT pg_temp.ck(NOT has_schema_privilege('app_user', 'priv', 'USAGE'), '(12e) app_userはpriv schemaを利用不可');
SELECT pg_temp.ck(NOT EXISTS (
  SELECT 1 FROM pg_auth_members membership
  JOIN pg_roles member_role ON member_role.oid = membership.member
  WHERE membership.roleid = (SELECT oid FROM pg_roles WHERE rolname='auth_context_owner')
    AND member_role.rolcanlogin
), '(12f) auth_context_ownerをログインロールへ委譲しない');

\echo ''
\echo 'S8: AuthContext DB検証 12群 PASS'
