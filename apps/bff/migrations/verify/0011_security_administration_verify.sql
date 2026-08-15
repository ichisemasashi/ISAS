\set ON_ERROR_STOP on
\set TENANT '11111111-1111-7111-8111-111111111111'
\set ADMIN1 'aaaaaaaa-0000-7000-8000-000000000001'
\set ADMIN2 'aaaaaaaa-0000-7000-8000-000000000002'
\set WORKER 'aaaaaaaa-0000-7000-8000-000000000003'
\set CHANGE 'bbbbbbbb-0000-7000-8000-000000000001'
\set BREAK_REQUEST 'bbbbbbbb-0000-7000-8000-000000000002'
\set GRANT_ID 'cccccccc-0000-7000-8000-000000000001'
\set PRIVACY 'dddddddd-0000-7000-8000-000000000001'

BEGIN;
CREATE OR REPLACE FUNCTION pg_temp.ck(condition boolean, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN IF NOT condition THEN RAISE EXCEPTION 'FAIL %', label; END IF; RAISE NOTICE 'PASS %', label; END $$;
CREATE OR REPLACE FUNCTION pg_temp.expect_own_security(request_id uuid, actor_id uuid) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  BEGIN
    PERFORM app_private.decide_security_change_request(request_id,actor_id,true,'自己承認を試行');
    RAISE EXCEPTION 'FAIL 自己承認を許可';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'PASS 自己承認をDBで拒否'; END;
END $$;
CREATE OR REPLACE FUNCTION pg_temp.expect_own_privacy(request_id uuid, actor_id uuid) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  BEGIN
    PERFORM app_private.transition_privacy_request(request_id,actor_id,'approve','自己承認を試行',NULL);
    RAISE EXCEPTION 'FAIL Privacy自己承認を許可';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'PASS Privacy自己承認をDBで拒否'; END;
END $$;

SET LOCAL app.actor_pseudonym = 'security-admin-verifier';
SET ROLE auth_context_owner;
INSERT INTO priv.auth_user(user_id,issuer,subject,display_name) VALUES
  (:'ADMIN1','https://idp.example','admin-1','管理者1'),
  (:'ADMIN2','https://idp.example','admin-2','管理者2'),
  (:'WORKER','https://idp.example','worker','作業者');
INSERT INTO priv.auth_membership(tenant_id,user_id,role_key) VALUES
  (:'TENANT',:'ADMIN1','group_admin'),(:'TENANT',:'ADMIN2','group_admin'),(:'TENANT',:'WORKER','worker');
RESET ROLE;

SET ROLE auth_role;
SELECT app_private.create_security_change_request(:'CHANGE',:'ADMIN1',:'TENANT','user_change',:'WORKER',
  '担当範囲と利用期限を更新する申請', 'SEC-001', jsonb_build_object('roleKey','worker','fieldGroupIds','[]'::jsonb));
SELECT pg_temp.expect_own_security(:'CHANGE',:'ADMIN1');
SELECT app_private.decide_security_change_request(:'CHANGE',:'ADMIN2',true,'変更前後を確認して承認');
SELECT pg_temp.ck((app_private.security_admin_snapshot(:'ADMIN1',:'TENANT')->'changeRequests'->0->>'status') = 'executed',
  '二人承認後に変更を実行し監査snapshotへ表示');

SELECT app_private.create_security_change_request(:'BREAK_REQUEST',:'ADMIN1',:'TENANT','break_glass',:'WORKER',
  '障害調査のため一時的な管理権限が必要', 'INC-001', jsonb_build_object(
    'grantId',:'GRANT_ID','capabilities',jsonb_build_array('conflict:resolve'),
    'validUntil', clock_timestamp() + interval '30 minutes'));
SELECT app_private.decide_security_change_request(:'BREAK_REQUEST',:'ADMIN2',true,'障害対応範囲と期限を確認');
SELECT pg_temp.ck((SELECT 'conflict:resolve' = ANY(capabilities)
  FROM app_private.derive_authorization_context(:'WORKER',:'TENANT')),
  '期限付きbreak-glass権限をAuthContextへ反映');

SELECT app_private.create_privacy_request(:'PRIVACY',:'ADMIN1',:'TENANT',:'WORKER','disclosure',
  jsonb_build_object('channels',jsonb_build_array('journal')), clock_timestamp() + interval '14 days','本人確認済みで受付');
SELECT pg_temp.expect_own_privacy(:'PRIVACY',:'ADMIN1');
SELECT app_private.transition_privacy_request(:'PRIVACY',:'ADMIN2','approve','法的要件と対象を確認',NULL);
SELECT pg_temp.ck((app_private.security_admin_snapshot(:'ADMIN2',:'TENANT')->'privacyRequests'->0->>'status') = 'approved',
  'Privacy requestを二人承認し履歴を保持');
RESET ROLE;

SELECT pg_temp.ck(count(*) >= 4, '管理操作をappend-only監査へ記録')
FROM priv.auth_change_audit WHERE actor_pseudonym = 'security-admin-verifier';
ROLLBACK;
\echo 'Security administration migration: 5 groups PASS'
