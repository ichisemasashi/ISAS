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

BEGIN;

CREATE OR REPLACE FUNCTION pg_temp.ck(condition boolean, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF NOT condition THEN RAISE EXCEPTION 'FAIL  %', label; END IF;
  RAISE NOTICE 'PASS  %', label;
END $$;

SET LOCAL app.actor_pseudonym = 'migration-verifier';
SET ROLE auth_context_owner;
INSERT INTO priv.auth_user (user_id, issuer, subject, display_name, status) VALUES
  (:'U1', 'https://idp.example/tenant', 'subject-1', '管理者1', 'active'),
  (:'U2', 'https://idp.example/tenant', 'subject-2', '作業者2', 'active'),
  (:'U3', 'https://idp.example/tenant', 'subject-3', '従業員3', 'active'),
  (:'U4', 'https://idp.example/tenant', 'subject-4', '失効者4', 'revoked');
INSERT INTO priv.auth_role_capability VALUES
  ('worker', 'view_others_punch', clock_timestamp()),
  ('group_admin', 'scope_all', clock_timestamp()),
  ('group_admin', 'view_others_tracks', clock_timestamp()),
  ('group_admin', 'view_others_punch', clock_timestamp());
INSERT INTO priv.auth_membership (tenant_id, user_id, role_key, status) VALUES
  (:'A', :'U1', 'group_admin', 'active'),
  (:'A', :'U2', 'worker', 'active'),
  (:'B', :'U3', 'worker', 'active'),
  (:'A', :'U4', 'worker', 'revoked');
INSERT INTO priv.auth_membership_field_group (tenant_id, user_id, field_group_id) VALUES (:'A', :'U2', :'F1');
INSERT INTO priv.auth_tenant_relation (parent_tenant_id, child_tenant_id, status) VALUES
  (:'A', :'B', 'active'), (:'A', :'C', 'revoked');
INSERT INTO priv.auth_employer_delegate
  (employer_tenant_id, manager_user_id, employee_user_id, employer_confirmed_at, employee_confirmed_at)
VALUES
  (:'A', :'U1', :'U3', clock_timestamp(), clock_timestamp()),
  (:'A', :'U1', :'U4', clock_timestamp(), NULL);
RESET ROLE;

SET ROLE app_user;
SELECT pg_temp.ck(count(*) = 1 AND min(membership_version) > 0 AND min(authorization_version) > 0,
  '(1) active user/membershipのversion付きAuthContextを受理')
  FROM app_private.validate_auth_context(:'U2', :'A', ARRAY[:'A']::uuid[], ARRAY[:'F1']::uuid[], ARRAY['view_others_punch'], '{}'::uuid[]);
SELECT pg_temp.ck(count(*) = 0, '(2) role由来でないcapabilityを拒否')
  FROM app_private.validate_auth_context(:'U2', :'A', ARRAY[:'A']::uuid[], ARRAY[:'F1']::uuid[], ARRAY['view_others_tracks'], '{}'::uuid[]);
SELECT pg_temp.ck(count(*) = 0, '(3) membership外scopeを拒否')
  FROM app_private.validate_auth_context(:'U2', :'A', ARRAY[:'A']::uuid[], ARRAY[:'F2']::uuid[], ARRAY['view_others_punch'], '{}'::uuid[]);
SELECT pg_temp.ck(count(*) = 1, '(4) activeな配下tenant横断を受理')
  FROM app_private.validate_auth_context(:'U1', :'A', ARRAY[:'A', :'B']::uuid[], '{}'::uuid[], ARRAY['scope_all'], '{}'::uuid[]);
SELECT pg_temp.ck(count(*) = 0, '(5) revokedな配下tenant横断を拒否')
  FROM app_private.validate_auth_context(:'U1', :'A', ARRAY[:'A', :'C']::uuid[], '{}'::uuid[], ARRAY['scope_all'], '{}'::uuid[]);
SELECT pg_temp.ck(count(*) = 1, '(6) 双方向確認済み雇用関係を受理')
  FROM app_private.validate_auth_context(:'U1', :'A', ARRAY[:'A']::uuid[], '{}'::uuid[], '{}'::text[], ARRAY[:'U3']::uuid[]);
SELECT pg_temp.ck(count(*) = 0, '(7) 片側確認またはrevoked userを拒否')
  FROM app_private.validate_auth_context(:'U1', :'A', ARRAY[:'A']::uuid[], '{}'::uuid[], '{}'::text[], ARRAY[:'U4']::uuid[]);
RESET ROLE;

SET ROLE auth_context_owner;
CREATE TEMP TABLE version_before AS
  SELECT user_id, authorization_version FROM priv.auth_user WHERE user_id = :'U2';
INSERT INTO priv.auth_membership_field_group (tenant_id, user_id, field_group_id) VALUES (:'A', :'U2', :'F2');
SELECT pg_temp.ck(auth_user.authorization_version > version_before.authorization_version,
  '(8a) scope変更でauthorization_versionが単調増加')
FROM priv.auth_user auth_user JOIN version_before USING (user_id);
SELECT pg_temp.ck(EXISTS (
  SELECT 1 FROM priv.auth_revocation_event event
  JOIN priv.auth_user auth_user USING (user_id)
  WHERE event.user_id = :'U2' AND event.authorization_version = auth_user.authorization_version
    AND event.reason = 'scope.changed' AND event.delivered_at IS NULL
), '(8b) versionと同一transactionで永続失効eventを作成');

CREATE TEMP TABLE membership_before AS
  SELECT membership_version FROM priv.auth_membership WHERE tenant_id = :'A' AND user_id = :'U2';
UPDATE priv.auth_membership SET status = 'suspended' WHERE tenant_id = :'A' AND user_id = :'U2';
SELECT pg_temp.ck(membership.membership_version = membership_before.membership_version + 1,
  '(9) membership更新はcaller値でなくDB triggerで単調増加')
FROM priv.auth_membership membership CROSS JOIN membership_before
WHERE membership.tenant_id = :'A' AND membership.user_id = :'U2';

SELECT pg_temp.ck(count(*) >= 1, '(10) 権限変更をappend-only監査へ記録')
FROM priv.auth_change_audit WHERE actor_pseudonym = 'migration-verifier';
RESET ROLE;

SET ROLE app_user;
DO $$ BEGIN
  BEGIN
    PERFORM count(*) FROM priv.auth_membership;
    RAISE EXCEPTION 'FAIL  (11) app_userが権限基表を直接参照できた';
  EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'PASS  (11) app_userの権限基表直接参照を拒否';
  END;
END $$;
RESET ROLE;

SELECT pg_temp.ck(count(*) = 11, '(12a) 全権限基表・失効・監査表がFORCE RLS')
FROM pg_class class JOIN pg_namespace namespace ON namespace.oid = class.relnamespace
WHERE namespace.nspname = 'priv' AND class.relname LIKE 'auth_%'
  AND class.relkind = 'r' AND class.relrowsecurity AND class.relforcerowsecurity;
SELECT pg_temp.ck(count(*) = 0, '(12b) 権限表所有者はauth_context_ownerだけ')
FROM pg_class class JOIN pg_namespace namespace ON namespace.oid = class.relnamespace
WHERE namespace.nspname = 'priv' AND class.relname LIKE 'auth_%' AND class.relkind = 'r'
  AND pg_get_userbyid(class.relowner) <> 'auth_context_owner';
SELECT pg_temp.ck(count(*) >= 14, '(12c) version・監査triggerが全対象表に有効')
FROM pg_trigger trigger JOIN pg_class class ON class.oid = trigger.tgrelid
JOIN pg_namespace namespace ON namespace.oid = class.relnamespace
WHERE namespace.nspname = 'priv' AND class.relname LIKE 'auth_%'
  AND NOT trigger.tgisinternal AND trigger.tgenabled = 'O';
SELECT pg_temp.ck(count(*) = 3, '(12d) 失効配送・user version・tenant索引が存在')
FROM pg_indexes WHERE schemaname = 'priv' AND indexname IN
  ('auth_revocation_pending_idx', 'auth_revocation_user_version_idx', 'auth_revocation_tenant_event_idx');
SELECT pg_temp.ck(has_function_privilege('app_user',
  'app_private.validate_auth_context(uuid,uuid,uuid[],uuid[],text[],uuid[])', 'EXECUTE'),
  '(12e) app_userは固定AuthContext関数だけ実行可');
SELECT pg_temp.ck(NOT has_schema_privilege('app_user', 'priv', 'USAGE')
  AND NOT has_schema_privilege('auth_role', 'priv', 'USAGE'),
  '(12f) app_user/auth_roleはpriv schemaを利用不可');
SELECT pg_temp.ck(NOT rolsuper AND NOT rolbypassrls AND NOT rolcanlogin,
  '(12g) auth_context_ownerはNOLOGIN・非特権')
FROM pg_roles WHERE rolname = 'auth_context_owner';
SELECT pg_temp.ck(p.prosecdef AND p.proconfig @> ARRAY['search_path=pg_catalog, priv'],
  '(12h) 検証・version・監査関数はSECURITY DEFINER＋固定search_path')
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'app_private' AND p.proname = 'validate_auth_context';

ROLLBACK;
\echo 'AuthContext production migration: 12 groups PASS'
