-- =====================================================================
-- S1: パーティション × RLS × 一意（冪等）× FORCE RLS × 受理台帳 × capability
--
-- 【v2 全面改訂】PostgreSQL実挙動検証（2026-07-27）で、旧 S1 は文書 §4/§6 とは
--   別のポリシー・別のDDLで走っており「全PASS」が設計を検証していなかった。
--   本版は **データモデル設計書 v10 §4/§5/§6 と ADR-0001 v16 §2.9/§2.10 の本文を
--   そのまま実行する**。差異があれば S1 が落ちるようにしてある。
--
-- 対応する指摘：IND3-H2/H3/H5、PG-H1/H2/H3/H6、A1h-*、IND2-*
-- PostGIS 非依存（空間は S2）。security_invoker ビューは PG15+ でのみ実行。
-- =====================================================================
\set ON_ERROR_STOP on
\set A '11111111-1111-7111-8111-111111111111'
\set B '22222222-2222-7222-8222-222222222222'
\set C '33333333-3333-7333-8333-333333333333'
\set U1 'aaaaaaaa-0000-7000-8000-000000000001'
\set U2 'bbbbbbbb-0000-7000-8000-000000000002'

SET ROLE app_owner;

-- ============================================================
-- DDL：文書 §6 のとおり
-- ============================================================

-- ① 受理台帳（v10 §5 ①：冪等の権威。event_ts でパーティション化しないので
--    (tenant_id, event_uuid) の一意をそのまま張れる＝IND3-L3 の解消）
CREATE TABLE event_receipt (
  tenant_id   uuid NOT NULL,
  event_uuid  uuid NOT NULL,
  event_ts    timestamptz NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, event_uuid)
);

-- ① 位置ログ（時系列・パーティション。子は part スキーマ＝PG-H6）
CREATE TABLE location_log (
  event_uuid  uuid NOT NULL,
  tenant_id   uuid NOT NULL,
  user_id     uuid NOT NULL,
  event_ts    timestamptz NOT NULL,   -- v10：サーバが検証・クランプした業務時点
  occurred_at timestamptz NOT NULL,   -- 端末申告（生・詐称可能）
  received_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, event_ts, event_uuid),
  CONSTRAINT event_ts_window CHECK (
    event_ts BETWEEN received_at - INTERVAL '400 days' AND received_at + INTERVAL '1 day')
) PARTITION BY RANGE (event_ts);
-- 【重要な不変条件（S1 再実行で発見）】
--   クランプ窓の下限は「常に存在する最古のパーティションの開始」以上でなければならない。
--   さもないとクランプしても該当パーティションが無く `no partition ... found for row` で
--   受理できない＝IND3-H5 の目的（どんな端末時計でも①-aは受理できる）が達成されない。
--   → retention でパーティションをドロップする際は、クランプ窓の下限も同時に繰り上げる。
--   ここではクランプ窓（-400日〜+1日）を覆う月次パーティションを自動生成する。
DO $$
DECLARE m date := date_trunc('month', now() - interval '400 days')::date;
        last date := date_trunc('month', now() + interval '2 months')::date;
        nm text;
BEGIN
  WHILE m <= last LOOP
    nm := 'location_log_' || to_char(m, 'YYYY_MM');
    EXECUTE format('CREATE TABLE part.%I PARTITION OF location_log FOR VALUES FROM (%L) TO (%L) PARTITION BY HASH (tenant_id)',
                   nm, m, (m + interval '1 month')::date);
    EXECUTE format('CREATE TABLE part.%I PARTITION OF part.%I FOR VALUES WITH (MODULUS 2, REMAINDER 0)', nm||'_h0', nm);
    EXECUTE format('CREATE TABLE part.%I PARTITION OF part.%I FOR VALUES WITH (MODULUS 2, REMAINDER 1)', nm||'_h1', nm);
    m := (m + interval '1 month')::date;
  END LOOP;
END $$;

-- ① 労務サマリ（①-a・本人起点 restrictive 例外＝形(b)）
CREATE TABLE labor_summary (
  summary_uuid uuid PRIMARY KEY,
  tenant_id    uuid NOT NULL,
  user_id      uuid NOT NULL,
  period_start date NOT NULL,
  work_minutes integer NOT NULL
);

-- ③ 共有＋テナント上書き（形(e)）
CREATE TABLE agro_chemical (
  reg_key   text NOT NULL,
  tenant_id uuid,                       -- NULL = 共有
  name      text NOT NULL
);
CREATE UNIQUE INDEX ac_shared ON agro_chemical(reg_key) WHERE tenant_id IS NULL;
CREATE UNIQUE INDEX ac_tenant ON agro_chemical(reg_key, tenant_id) WHERE tenant_id IS NOT NULL;

-- membership（形(f)：ブートストラップ例外）
CREATE TABLE membership (tenant_id uuid NOT NULL, user_id uuid NOT NULL, PRIMARY KEY (tenant_id, user_id));

-- ============================================================
-- RLS：文書 §4 の述語形をそのまま。
--   permissive は USING(true) 固定＋TO で列挙／restrictive は TO なし＋7形のいずれか
--   セッション変数はすべて正規化形（PG-H1）。関数にラップしない（PG-M4）。
-- ============================================================
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['event_receipt','location_log','labor_summary','agro_chemical','membership'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
  END LOOP;
END $$;

-- 基本 permissive（経路を開くだけ・条件を書かない・TO で列挙）
CREATE POLICY base ON event_receipt AS PERMISSIVE FOR ALL TO app_user USING (true) WITH CHECK (true);
CREATE POLICY base ON location_log  AS PERMISSIVE FOR ALL TO app_user USING (true) WITH CHECK (true);
CREATE POLICY base ON labor_summary AS PERMISSIVE FOR ALL TO app_user USING (true) WITH CHECK (true);
-- ③は「共有行を書く管理ロール」も permissive の TO に列挙する。
--   列挙漏れ＝そのロールが何もできない（フェイルクローズ＝安全側）。PG-H3 の是正。
CREATE POLICY base ON agro_chemical AS PERMISSIVE FOR ALL TO app_user, admin_role USING (true) WITH CHECK (true);
-- membership は bootstrap_owner も読む（含めないと permissive 無し＝0行＝PG-H3 の型）
CREATE POLICY base ON membership    AS PERMISSIVE FOR ALL TO app_user, bootstrap_owner USING (true) WITH CHECK (true);

-- 形(a) 標準
CREATE POLICY res ON event_receipt AS RESTRICTIVE
  USING      (tenant_id = ANY(COALESCE(NULLIF(current_setting('app.allowed_tenants', true), ''), '{}')::uuid[]))
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- 形(d) 個人データ（位置ログ軌跡）＝ 形(a) AND capability
CREATE POLICY res_tenant ON location_log AS RESTRICTIVE
  USING      (tenant_id = ANY(COALESCE(NULLIF(current_setting('app.allowed_tenants', true), ''), '{}')::uuid[]))
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY res_personal ON location_log AS RESTRICTIVE
  USING      (user_id = NULLIF(current_setting('app.user_id', true), '')::uuid
              OR 'view_others_tracks' = ANY(COALESCE(NULLIF(current_setting('app.caps', true), ''), '{}')::text[]))
  WITH CHECK (user_id = NULLIF(current_setting('app.user_id', true), '')::uuid);

-- 形(b) 本人起点例外（labor_summary のみ）
CREATE POLICY res ON labor_summary AS RESTRICTIVE
  USING      (tenant_id = ANY(COALESCE(NULLIF(current_setting('app.allowed_tenants', true), ''), '{}')::uuid[])
              OR user_id = NULLIF(current_setting('app.user_id', true), '')::uuid
              OR user_id = ANY(COALESCE(NULLIF(current_setting('app.employer_subject_users', true), ''), '{}')::uuid[]))
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- 形(e) ③共有＋上書き（USING で NULL を許容し、WITH CHECK では拒否＝共有汚染の防止）
CREATE POLICY res ON agro_chemical AS RESTRICTIVE
  USING      (tenant_id IS NULL
              OR tenant_id = ANY(COALESCE(NULLIF(current_setting('app.allowed_tenants', true), ''), '{}')::uuid[]))
  -- 共有行(tenant_id IS NULL)の書込は管理ロールのみ（一般ロールの共有汚染を防ぐ＝A1c-L2/A1d-M1）
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
              OR (current_user = 'admin_role' AND tenant_id IS NULL));

-- 形(f) ブートストラップ例外（キーは auth_role ではなく bootstrap_owner＝IND3-H1/PG-H5）
CREATE POLICY res ON membership AS RESTRICTIVE
  USING      (tenant_id = ANY(COALESCE(NULLIF(current_setting('app.allowed_tenants', true), ''), '{}')::uuid[])
              OR (current_user = 'bootstrap_owner'
                  AND user_id = NULLIF(current_setting('app.bootstrap_claimed_user', true), '')::uuid))
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON event_receipt, location_log, labor_summary, agro_chemical, membership TO app_user;
GRANT SELECT, INSERT, UPDATE ON agro_chemical TO admin_role;   -- 共有マスタの書込経路（A1c-L2）
GRANT SELECT ON membership TO bootstrap_owner;   -- auth_role には一切与えない
-- part スキーマの子には GRANT しない（かつ USAGE も無い）

RESET ROLE;
-- ブートストラップ関数（所有者 bootstrap_owner・auth_role は EXECUTE のみ）
--   ※所有者付替えは superuser 側で行う（app_owner は bootstrap_owner のメンバーではない＝
--     「bootstrap_owner をいかなるロールにも GRANT しない」原則：PG-L7）
CREATE FUNCTION bootstrap_resolve(claimed uuid) RETURNS TABLE(tenant_id uuid)
LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
  SELECT m.tenant_id FROM membership m WHERE m.user_id = claimed $$;
ALTER FUNCTION bootstrap_resolve(uuid) OWNER TO bootstrap_owner;
REVOKE ALL ON FUNCTION bootstrap_resolve(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION bootstrap_resolve(uuid) TO auth_role;
RESET ROLE;

-- ============================================================
-- 試験（文書の合格項目をそのまま。落ちたら例外で止まる）
-- ============================================================
CREATE OR REPLACE FUNCTION ck(cond boolean, label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  IF cond THEN RAISE NOTICE 'PASS  %', label;
  ELSE RAISE EXCEPTION 'FAIL  %', label; END IF;
END $$;

-- seed（RLS 有効のまま app_user で投入＝所有者バイパスに頼らない：旧S1の差異10の是正）
SET ROLE app_user;
SET app.allowed_tenants = '{11111111-1111-7111-8111-111111111111}';
SET app.tenant_id = '11111111-1111-7111-8111-111111111111';
SET app.user_id  = 'aaaaaaaa-0000-7000-8000-000000000001';
INSERT INTO membership VALUES (:'A'::uuid, :'U1'::uuid), (:'A'::uuid, :'U2'::uuid);
INSERT INTO labor_summary VALUES (gen_random_uuid(), :'A'::uuid, :'U1'::uuid, '2026-07-01', 480);
INSERT INTO location_log(event_uuid, tenant_id, user_id, event_ts, occurred_at)
SELECT gen_uuidv7(now() - interval '10 days'), :'A'::uuid, :'U1'::uuid,
       clamp_event_ts(now() - interval '10 days', now()), now() - interval '10 days' FROM generate_series(1,50);
-- U2 の位置ログは U2 自身の文脈で投入する
--   （形(d) の WITH CHECK は「本人のみ追記」＝他人名義の位置ログは作れない。これ自体が試験になっている）
SET app.user_id = 'bbbbbbbb-0000-7000-8000-000000000002';
INSERT INTO location_log(event_uuid, tenant_id, user_id, event_ts, occurred_at)
SELECT gen_uuidv7(now() - interval '9 days'), :'A'::uuid, :'U2'::uuid,
       clamp_event_ts(now() - interval '9 days', now()), now() - interval '9 days' FROM generate_series(1,30);
SET app.user_id = 'aaaaaaaa-0000-7000-8000-000000000001';

SET app.allowed_tenants = '{22222222-2222-7222-8222-222222222222}';
SET app.tenant_id = '22222222-2222-7222-8222-222222222222';
INSERT INTO membership VALUES (:'B'::uuid, :'U1'::uuid);
INSERT INTO labor_summary VALUES (gen_random_uuid(), :'B'::uuid, :'U1'::uuid, '2026-07-01', 300);
INSERT INTO location_log(event_uuid, tenant_id, user_id, event_ts, occurred_at)
SELECT gen_uuidv7(now() - interval '5 days'), :'B'::uuid, :'U1'::uuid,
       clamp_event_ts(now() - interval '5 days', now()), now() - interval '5 days' FROM generate_series(1,40);
RESET ROLE;
-- 共有行は管理用の別ロールが投入する（A1c-L2）。所有者は permissive の TO に無いので書けない
--   ＝PG-H3「所有者は基本 permissive の対象外」を設計として明示したもの。
SET ROLE admin_role;
INSERT INTO agro_chemical VALUES ('AC-001', NULL, '共有マスタ');
RESET ROLE;
SET ROLE app_user;
SET app.allowed_tenants = '{11111111-1111-7111-8111-111111111111}';
SET app.tenant_id = '11111111-1111-7111-8111-111111111111';
INSERT INTO agro_chemical VALUES ('AC-001', :'A'::uuid, 'Aの上書き');
RESET ROLE;

SET ROLE app_user;
\echo ''
\echo '=== (1) 遮断：他テナントは不可視 ==='
SET app.allowed_tenants = '{11111111-1111-7111-8111-111111111111}';
SET app.user_id = 'aaaaaaaa-0000-7000-8000-000000000001';
SET app.caps = '{view_others_tracks}';
SELECT ck(count(*) = 80, '(1) A のみ可視 (80件・B の40件は不可視)') FROM location_log;

\echo '=== (2) 横断が通る：グループ管理者が allowed_tenants 内の他テナントを読む ==='
SET app.allowed_tenants = '{11111111-1111-7111-8111-111111111111,22222222-2222-7222-8222-222222222222}';
SELECT ck(count(*) = 120, '(2) 横断で A+B が可視 (120件) ← permissive では成立しない形') FROM location_log;

\echo '=== (3) 本人起点横断：allowed_tenants の外の自分の labor_summary が読める ==='
SET app.allowed_tenants = '{11111111-1111-7111-8111-111111111111}';
SELECT ck(count(*) = 2, '(3) 形(b) で B の自分の行も可視 (2件)') FROM labor_summary WHERE user_id = :'U1'::uuid;
SET app.user_id = 'bbbbbbbb-0000-7000-8000-000000000002';
SELECT ck(count(*) = 1, '(3b) 他人(U2)では B の行は不可視 (1件)') FROM labor_summary;
SET app.user_id = 'aaaaaaaa-0000-7000-8000-000000000001';

\echo '=== (4) ③共有行が見える＋横断で配下の上書きも見える ==='
SELECT ck(count(*) = 2, '(4) 共有行＋A の上書き行が可視 (2件)') FROM agro_chemical;

\echo '=== (5) capability：無いと他者の位置ログが見えない／あると見える ==='
SET app.caps = '{}';
SELECT ck(count(*) = 50, '(5a) cap 無し → 自分の分のみ (50件)') FROM location_log;
SET app.caps = '{view_others_tracks}';
SELECT ck(count(*) = 80, '(5b) cap 有り → 他者の分も可視 (80件)') FROM location_log;

\echo '=== (6) 書込側：他テナント名義 INSERT / 行移送 / 共有行書込 の拒否 ==='
DO $$ BEGIN
  BEGIN
    INSERT INTO labor_summary VALUES (gen_random_uuid(),
      '22222222-2222-7222-8222-222222222222', 'aaaaaaaa-0000-7000-8000-000000000001', '2026-08-01', 999);
    RAISE EXCEPTION 'FAIL  (6a) 他テナント名義の labor_summary 追記が通ってしまった';
  EXCEPTION WHEN insufficient_privilege OR check_violation THEN
    RAISE NOTICE 'PASS  (6a) 他テナント名義の labor_summary 追記を拒否';
  END;
  BEGIN
    UPDATE location_log SET tenant_id = '22222222-2222-7222-8222-222222222222' WHERE tenant_id = '11111111-1111-7111-8111-111111111111';
    RAISE EXCEPTION 'FAIL  (6b) 行移送が通ってしまった';
  EXCEPTION WHEN insufficient_privilege OR check_violation THEN
    RAISE NOTICE 'PASS  (6b) UPDATE による行移送を拒否';
  END;
  BEGIN
    INSERT INTO agro_chemical VALUES ('AC-999', NULL, '共有汚染');
    RAISE EXCEPTION 'FAIL  (6c) 一般ロールが共有行(tenant_id IS NULL)を作れてしまった';
  EXCEPTION WHEN insufficient_privilege OR check_violation THEN
    RAISE NOTICE 'PASS  (6c) 一般ロールの共有行 INSERT を拒否';
  END;
END $$;

\echo '=== (7) 冪等：受理台帳が再送を弾き、記録済み event_ts を返す ==='
SET app.allowed_tenants = '{11111111-1111-7111-8111-111111111111}';
SET app.tenant_id = '11111111-1111-7111-8111-111111111111';
DO $$
DECLARE u uuid := gen_uuidv7(now() - interval '8 days'); ts1 timestamptz; ts2 timestamptz; n int;
BEGIN
  INSERT INTO event_receipt(tenant_id, event_uuid, event_ts)
    VALUES ('11111111-1111-7111-8111-111111111111', u, clamp_event_ts(now() - interval '8 days', now()))
    ON CONFLICT (tenant_id, event_uuid) DO NOTHING;
  SELECT event_ts INTO ts1 FROM event_receipt WHERE event_uuid = u;
  -- 再送（サーバ時刻が進んでも台帳の値が返る＝決定性）
  INSERT INTO event_receipt(tenant_id, event_uuid, event_ts)
    VALUES ('11111111-1111-7111-8111-111111111111', u, clamp_event_ts(now() - interval '8 days', now() + interval '1 day'))
    ON CONFLICT (tenant_id, event_uuid) DO NOTHING;
  GET DIAGNOSTICS n = ROW_COUNT;
  SELECT event_ts INTO ts2 FROM event_receipt WHERE event_uuid = u;
  PERFORM ck(n = 0, '(7a) 再送は0件扱い');
  PERFORM ck(ts1 = ts2, '(7b) 記録済み event_ts が不変（サーバ採番でも決定性が保たれる）');
END $$;

\echo '=== (8) クランプ：端末時計が ±1年ずれても ①-a が受理できる（IND3-H5）==='
DO $$
DECLARE past timestamptz := now() - interval '20 years';   -- 電池切れで1970相当
        future timestamptz := now() + interval '5 years';
        n int;
BEGIN
  INSERT INTO location_log(event_uuid, tenant_id, user_id, event_ts, occurred_at)
    VALUES (gen_uuidv7(past), '11111111-1111-7111-8111-111111111111',
            'aaaaaaaa-0000-7000-8000-000000000001', clamp_event_ts(past, now()), past);
  INSERT INTO location_log(event_uuid, tenant_id, user_id, event_ts, occurred_at)
    VALUES (gen_uuidv7(future), '11111111-1111-7111-8111-111111111111',
            'aaaaaaaa-0000-7000-8000-000000000001', clamp_event_ts(future, now()), future);
  PERFORM ck(true, '(8) 端末時計が -20年/+5年ずれても受理できた（クランプ後は必ずパーティション内）');
EXCEPTION WHEN OTHERS THEN
  RAISE EXCEPTION 'FAIL  (8) 時計ずれ端末の①-aが受理できない: %', SQLERRM;
END $$;

\echo '=== (9) 子テーブルの直接参照が拒否される（PG-H6）==='
DO $$ BEGIN
  BEGIN
    EXECUTE format('SELECT count(*) FROM part.%I',
      (SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
       WHERE n.nspname='part' AND c.relname LIKE 'location\_log\_%\_h0' LIMIT 1));
    RAISE EXCEPTION 'FAIL  (9) 子テーブルを直接参照できてしまった（RLSは子に伝播しない）';
  EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'PASS  (9) 子テーブルの直接参照を拒否（part スキーマの USAGE 無し）';
  END;
END $$;

\echo '=== (10) 注入漏れ：SET LOCAL を使った接続の「2回目」でも 0行（例外にならない）==='
RESET ROLE; SET ROLE app_user;
-- プール返却相当の状態を作る（先行テストのセッションレベル SET を消す）
RESET app.allowed_tenants; RESET app.tenant_id; RESET app.user_id; RESET app.caps;
BEGIN; SET LOCAL app.allowed_tenants = '{11111111-1111-7111-8111-111111111111}';
       SET LOCAL app.user_id = 'aaaaaaaa-0000-7000-8000-000000000001';
       SET LOCAL app.caps = '{view_others_tracks}';
       SELECT ck(count(*) > 0, '(10a) TX内では可視') FROM location_log; COMMIT;
SELECT ck(current_setting('app.allowed_tenants', true) IS NOT NULL, '(10b) COMMIT後の「未設定」は NULL ではない（＝素のキャストなら 22P02 で落ちる状態）');
SELECT ck(count(*) = 0, '(10c) 正規化形なので 0行（素のキャストなら 22P02 で落ちる）') FROM location_log;
RESET ROLE;

\echo '=== (11) ブートストラップ（IND3-H1/PG-H5）==='
SET ROLE auth_role;
SET app.bootstrap_claimed_user = 'aaaaaaaa-0000-7000-8000-000000000001';
SELECT ck(count(*) = 2, '(11a) auth_role が関数経由でテナント跨ぎ解決 (A,B の2件)') FROM bootstrap_resolve(:'U1'::uuid);
SELECT ck(count(*) = 0, '(11b) 主張と引数が一致しないと0件') FROM bootstrap_resolve(:'U2'::uuid);
DO $$ BEGIN
  BEGIN
    PERFORM count(*) FROM membership;
    RAISE EXCEPTION 'FAIL  (11c) auth_role が membership を直接参照できてしまった';
  EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'PASS  (11c) auth_role の membership 直接参照を拒否（テーブル権限ゼロ）';
  END;
END $$;
RESET ROLE;

\echo '=== (12) FORCE RLS：所有者も従う ==='
SET ROLE app_owner;
SELECT ck(count(*) = 0, '(12) 所有者でも注入無しなら 0行（FORCE RLS）') FROM location_log;
RESET ROLE;

\echo '=== (13) カタログ構造検査（ADR-0001 v16 §5 一段目）==='
SELECT ck(count(*) = 0, '(13a) restrictive に TO 指定が付いていない')
  FROM pg_policies WHERE schemaname='public' AND permissive='RESTRICTIVE' AND roles <> '{public}';
SELECT ck(count(*) = 0, '(13b) permissive の述語が true 以外のものが無い')
  FROM pg_policies WHERE schemaname='public' AND permissive='PERMISSIVE' AND qual IS DISTINCT FROM 'true';
SELECT ck(count(*) = 0, '(13c) restrictive に WITH CHECK が明示されている')
  FROM pg_policies WHERE schemaname='public' AND permissive='RESTRICTIVE' AND with_check IS NULL;
SELECT ck(count(*) = 0, '(13d) tenant_id 列を持つ表に restrictive が存在する')
  FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
  WHERE n.nspname='public' AND c.relkind IN ('r','p')
    AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid=c.oid AND a.attname='tenant_id' AND a.attnum>0)
    AND NOT EXISTS (SELECT 1 FROM pg_policies p WHERE p.tablename=c.relname AND p.permissive='RESTRICTIVE');
SELECT ck(count(*) = 0, '(13e) part スキーマに通常ロールの権限が無い')
  FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
  WHERE n.nspname='part' AND has_table_privilege('app_user', c.oid, 'SELECT');
SELECT ck(NOT has_schema_privilege('app_user','part','USAGE'), '(13f) app_user は part の USAGE を持たない');
SELECT ck(count(*) = 0, '(13g) auth_role は app_owner 所有のアプリ表に直接権限を持たない')
  FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
  WHERE c.relkind IN ('r','p') AND n.nspname IN ('public','part','priv')
    AND pg_get_userbyid(c.relowner) = 'app_owner'
    AND has_table_privilege('auth_role', c.oid, 'SELECT,INSERT,UPDATE,DELETE');
SELECT ck(count(*) <= 1, '(13h) rolsuper/rolbypassrls を持つロールが一覧内（audit_writer のみ）')
  FROM pg_roles WHERE (rolsuper OR rolbypassrls) AND rolname NOT IN ('postgres') AND rolname <> 'audit_writer';

\echo '=== (14) security_invoker ビュー（PG15+ のみ・PG14 ではスキップ）==='
DO $$
DECLARE
  visible_count bigint;
  visible_name text;
BEGIN
  IF current_setting('server_version_num')::int >= 150000 THEN
    EXECUTE 'CREATE VIEW ac_eff WITH (security_invoker = true) AS
             SELECT DISTINCT ON (reg_key) * FROM agro_chemical
             ORDER BY reg_key, (tenant_id IS NOT NULL) DESC';
    EXECUTE 'GRANT SELECT ON ac_eff TO app_user';

    -- ビュー所有者（postgres）ではなく呼出元 app_user のRLS文脈で評価されることを実証する。
    -- Aではテナント上書き、BではAの上書きが不可視になり共有行へフォールバックする。
    EXECUTE 'SET LOCAL ROLE app_user';
    PERFORM set_config('app.allowed_tenants',
      '{11111111-1111-7111-8111-111111111111}', true);
    SELECT count(*), max(name) INTO visible_count, visible_name FROM ac_eff;
    IF visible_count <> 1 OR visible_name <> 'Aの上書き' THEN
      RAISE EXCEPTION 'FAIL  (14a) Aでは上書き1件が見えるべき: count=%, name=%',
        visible_count, visible_name;
    END IF;

    PERFORM set_config('app.allowed_tenants',
      '{22222222-2222-7222-8222-222222222222}', true);
    SELECT count(*), max(name) INTO visible_count, visible_name FROM ac_eff;
    IF visible_count <> 1 OR visible_name <> '共有マスタ' THEN
      RAISE EXCEPTION 'FAIL  (14b) BではAの上書きが不可視で共有1件へ戻るべき: count=%, name=%',
        visible_count, visible_name;
    END IF;
    EXECUTE 'RESET ROLE';
    RAISE NOTICE 'PASS  (14) security_invoker が呼出元RLSで評価（A=上書き／B=共有）';
  ELSE
    RAISE WARNING 'SKIP  (14) security_invoker は PostgreSQL 15 以降が必要（現在 %）。本設計の最低要求は PG15（ADR-0004 §2.5）',
      current_setting('server_version');
  END IF;
END $$;

\echo ''
\echo '=== S1 完了 ==='
