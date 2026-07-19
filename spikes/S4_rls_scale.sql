-- =====================================================================
-- S4: RLS × 規模（許可集合注入・権限導出・スコープ）
-- 設計書 v4 §4 を検証。合格基準（5.2.1）: 一覧・API 500ms/p95。
--   ・1DB=5万ユーザー規模でメンバーシップから許可集合/スコープを導出しても性能内か
--   ・R3-M1/R4-L2: 子テーブルのスコープ解決（非正規化 vs セキュリティバリア関数）の比較
-- =====================================================================
\set ON_ERROR_STOP on
SET client_min_messages = NOTICE;
SET ROLE app_owner;

-- 圃場（S2 と独立の軽量版）＋作業日誌（子・スコープ対象）
CREATE TABLE fld (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  field_group_id uuid NOT NULL
);
CREATE TABLE worklog (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL,
  field_id uuid NOT NULL,
  field_group_id uuid NOT NULL,        -- R3-M1: 可変テーブルは非正規化スコープ列
  body text
);
ALTER TABLE fld ENABLE ROW LEVEL SECURITY;     ALTER TABLE fld FORCE ROW LEVEL SECURITY;
ALTER TABLE worklog ENABLE ROW LEVEL SECURITY; ALTER TABLE worklog FORCE ROW LEVEL SECURITY;

CREATE FUNCTION allowed_tenants4() RETURNS uuid[] LANGUAGE sql STABLE AS $$
  SELECT string_to_array(current_setting('app.allowed_tenants', true), ',')::uuid[] $$;
CREATE FUNCTION scope_groups4() RETURNS uuid[] LANGUAGE sql STABLE AS $$
  SELECT string_to_array(current_setting('app.scope_field_groups', true), ',')::uuid[] $$;

-- テナント分離(restrictive) + スコープ(restrictive, NULL 不可視化回避) + 読取(permissive)
CREATE POLICY b ON fld AS RESTRICTIVE USING (tenant_id = ANY(allowed_tenants4()));
CREATE POLICY s ON fld AS RESTRICTIVE USING (field_group_id IS NULL OR field_group_id = ANY(scope_groups4()));
CREATE POLICY r ON fld AS PERMISSIVE FOR SELECT USING (tenant_id = ANY(allowed_tenants4()));
CREATE POLICY b2 ON worklog AS RESTRICTIVE USING (tenant_id = ANY(allowed_tenants4()));
CREATE POLICY s2 ON worklog AS RESTRICTIVE USING (field_group_id IS NULL OR field_group_id = ANY(scope_groups4()));
CREATE POLICY r2 ON worklog AS PERMISSIVE FOR SELECT USING (tenant_id = ANY(allowed_tenants4()));

GRANT SELECT ON fld, worklog TO app_user;

-- データ: 10 テナント × 50 グループ × 圃場100 = 5万圃場、作業日誌 各圃場5件=25万件
\set T '00000000-0000-7000-8000-00000000000a'
INSERT INTO fld (tenant_id, field_group_id)
SELECT ('00000000-0000-7000-8000-'||lpad(to_hex(t),12,'0'))::uuid,
       ('00000000-0000-7000-9000-'||lpad(to_hex(t*100+grp),12,'0'))::uuid
FROM generate_series(1,10) t, generate_series(1,50) grp, generate_series(1,100) f;

INSERT INTO worklog (tenant_id, field_id, field_group_id, body)
SELECT f.tenant_id, f.id, f.field_group_id, 'log'
FROM fld f, generate_series(1,5) k;

CREATE INDEX ON fld (tenant_id, field_group_id);
CREATE INDEX ON worklog (tenant_id, field_group_id);
ANALYZE fld; ANALYZE worklog;
RESET ROLE;

-- ============ 計測 ============
SET ROLE app_user;
-- あるユーザーが テナント5 の 3 グループ(グループ番号 501,502,503)を担当
SET app.allowed_tenants = '00000000-0000-7000-8000-000000000005';
-- 担当グループ3件を動的にセット（テナント5 = 5*100+1..3）
SELECT set_config('app.scope_field_groups',
  string_agg(gid::text, ','), false)
FROM (SELECT ('00000000-0000-7000-9000-'||lpad(to_hex(501+g),12,'0'))::uuid gid
      FROM generate_series(0,2) g) x;

\echo '--- (A) 圃場一覧（テナント分離＋スコープ restrictive）---'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON)
SELECT id FROM fld;

\echo '--- (B) 作業日誌一覧（非正規化 field_group_id で子テーブル絞り = R3-M1 可変テーブル経路）---'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON)
SELECT id FROM worklog;

\echo '--- (C) 参考: 子を field 経由サブクエリで絞る場合（採用しない案・性能比較用）---'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON)
SELECT w.id FROM worklog w
WHERE w.field_id IN (SELECT id FROM fld);   -- fld 側 RLS が再帰適用される

RESET ROLE;
\echo '===== S4 完了（(A)(B) の実時間が 500ms/p95 目安内か、(C) との差を確認） ====='
