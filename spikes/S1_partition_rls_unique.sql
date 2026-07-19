-- =====================================================================
-- S1: パーティション × RLS × 一意 × FORCE RLS × 冪等(UUIDv7)
-- 設計書 v4 §5/§6 の location_log を検証。合格基準:
--   ・他テナント遮断 100%（app_user／FORCE RLS下の owner 両方）
--   ・同一 event_uuid 再送 → 0 重複（受理時刻が変わっても）
--   ・不整合 event_ts は CHECK で拒否（パーティション誤配置を防ぐ）
--   ・書込は app.tenant_id のテナントに限定（WITH CHECK）
-- 併せて R3-M2（event_ts の決定性を DB で束縛）を検証し、
-- 「生成列をパーティションキーにできない」PostgreSQL 制約も明示的に確認する。
-- =====================================================================
\set ON_ERROR_STOP on
SET client_min_messages = NOTICE;

-- ---- 生成列をパーティションキーにできるか？（設計の「生成列 or CHECK」の前者を確認）----
DO $$
BEGIN
  BEGIN
    EXECUTE $ddl$
      CREATE TABLE _probe_gen (
        event_uuid uuid NOT NULL,
        event_ts timestamptz GENERATED ALWAYS AS (uuidv7_time(event_uuid)) STORED
      ) PARTITION BY RANGE (event_ts)
    $ddl$;
    RAISE NOTICE 'S1 probe: 生成列をパーティションキーに採用可';
    EXECUTE 'DROP TABLE _probe_gen';
  EXCEPTION WHEN others THEN
    RAISE NOTICE 'S1 probe: 生成列はパーティションキー不可（%）→ CHECK+トリガ経路を採用', SQLERRM;
  END;
END $$;

-- ---- 本体テーブル（所有者 = app_owner。CHECK 経路を採用）----
SET ROLE app_owner;

CREATE TABLE location_log (
  event_uuid  uuid        NOT NULL,               -- クライアント生成 UUIDv7
  tenant_id   uuid        NOT NULL,
  user_id     uuid        NOT NULL,
  event_ts    timestamptz NOT NULL,               -- サーバがトリガで uuidv7_time から設定
  occurred_at timestamptz NOT NULL,               -- 業務時点（振り分けに使わない）
  geom        geometry(Point,4326),
  PRIMARY KEY (tenant_id, event_ts, event_uuid),  -- 全パーティションキー包含
  CONSTRAINT event_ts_derived CHECK (event_ts = uuidv7_time(event_uuid))  -- R3-M2 決定性束縛
) PARTITION BY RANGE (event_ts);

-- 月レンジ → tenant_id ハッシュのサブパーティション（副軸）
CREATE TABLE location_log_2026_07 PARTITION OF location_log
  FOR VALUES FROM ('2026-07-01') TO ('2026-08-01') PARTITION BY HASH (tenant_id);
CREATE TABLE location_log_2026_07_h0 PARTITION OF location_log_2026_07 FOR VALUES WITH (MODULUS 2, REMAINDER 0);
CREATE TABLE location_log_2026_07_h1 PARTITION OF location_log_2026_07 FOR VALUES WITH (MODULUS 2, REMAINDER 1);
CREATE TABLE location_log_2026_08 PARTITION OF location_log
  FOR VALUES FROM ('2026-08-01') TO ('2026-09-01') PARTITION BY HASH (tenant_id);
CREATE TABLE location_log_2026_08_h0 PARTITION OF location_log_2026_08 FOR VALUES WITH (MODULUS 2, REMAINDER 0);
CREATE TABLE location_log_2026_08_h1 PARTITION OF location_log_2026_08 FOR VALUES WITH (MODULUS 2, REMAINDER 1);

-- サーバ側で event_ts を UUIDv7 から導出（クライアント値を信頼しない・R3-M2）
CREATE FUNCTION set_event_ts() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.event_ts := uuidv7_time(NEW.event_uuid);
  RETURN NEW;
END $$;
CREATE TRIGGER trg_set_event_ts BEFORE INSERT ON location_log
  FOR EACH ROW EXECUTE FUNCTION set_event_ts();

-- RLS（親に定義 → 全（サブ）パーティションに適用。FORCE で owner も従わせる）
ALTER TABLE location_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE location_log FORCE ROW LEVEL SECURITY;

-- allowed_tenants（横断の許可集合。単一テナントなら1件）を uuid[] で取得
CREATE FUNCTION allowed_tenants() RETURNS uuid[] LANGUAGE sql STABLE AS $$
  SELECT string_to_array(current_setting('app.allowed_tenants', true), ',')::uuid[]
$$;

-- restrictive: 認可テナント集合の外は常に不可視（ハード境界）
CREATE POLICY p_tenant_boundary ON location_log AS RESTRICTIVE
  USING (tenant_id = ANY(allowed_tenants()))
  WITH CHECK (tenant_id = ANY(allowed_tenants()));
-- permissive(read): 認可集合内は読める
CREATE POLICY p_read ON location_log AS PERMISSIVE FOR SELECT
  USING (tenant_id = ANY(allowed_tenants()));
-- permissive(write): 書込は現在アクティブなテナントに限定
CREATE POLICY p_write ON location_log AS PERMISSIVE FOR INSERT
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

GRANT SELECT, INSERT ON location_log TO app_user;
RESET ROLE;

-- テナントID固定
\set A '11111111-1111-7111-8111-111111111111'
\set B '22222222-2222-7222-8222-222222222222'

-- =========================== 試験 ===========================
SET ROLE app_user;

-- 投入: A/B 各テナントに数百件（app.tenant_id を切替えながら）
SET app.allowed_tenants = :'A';
SET app.tenant_id = :'A';
INSERT INTO location_log(event_uuid, tenant_id, user_id, occurred_at, geom)
SELECT gen_uuidv7('2026-07-15'::timestamptz + (g||' min')::interval),
       :'A'::uuid, gen_random_uuid(),
       '2026-07-15'::timestamptz + (g||' min')::interval,
       ST_SetSRID(ST_MakePoint(140.3, 38.2), 4326)
FROM generate_series(1,300) g;

SET app.allowed_tenants = :'B';
SET app.tenant_id = :'B';
INSERT INTO location_log(event_uuid, tenant_id, user_id, occurred_at, geom)
SELECT gen_uuidv7('2026-08-10'::timestamptz + (g||' min')::interval),
       :'B'::uuid, gen_random_uuid(),
       '2026-08-10'::timestamptz + (g||' min')::interval,
       ST_SetSRID(ST_MakePoint(140.4, 38.3), 4326)
FROM generate_series(1,300) g;

-- (1) テナント遮断: A のみ許可 → B は 0 件、全体は A の 300 件だけ
SET app.allowed_tenants = :'A';
SET app.tenant_id = :'A';
DO $$
DECLARE n_b int; n_all int;
BEGIN
  SELECT count(*) INTO n_b   FROM location_log WHERE tenant_id = '22222222-2222-7222-8222-222222222222';
  SELECT count(*) INTO n_all FROM location_log;
  IF n_b <> 0 THEN RAISE EXCEPTION 'S1-(1) FAIL: 他テナント % 件見えた', n_b; END IF;
  IF n_all <> 300 THEN RAISE EXCEPTION 'S1-(1) FAIL: 自テナント可視 % 件 (期待300)', n_all; END IF;
  RAISE NOTICE 'S1-(1) PASS: 他テナント遮断100%%（可視 % 件のみ）', n_all;
END $$;

-- (2) 冪等: 同一 event_uuid を「異なる受理時刻」で再送 → 0 重複
DO $$
DECLARE u uuid := gen_uuidv7('2026-07-20 00:00:00+00'); before int; after int;
BEGIN
  INSERT INTO location_log(event_uuid,tenant_id,user_id,occurred_at)
    VALUES (u, '11111111-1111-7111-8111-111111111111', gen_random_uuid(), now())
    ON CONFLICT (tenant_id, event_ts, event_uuid) DO NOTHING;
  SELECT count(*) INTO before FROM location_log WHERE event_uuid = u;
  -- 再送（occurred_at を変えても event_ts はサーバが uuid から再導出＝同一パーティション）
  INSERT INTO location_log(event_uuid,tenant_id,user_id,occurred_at)
    VALUES (u, '11111111-1111-7111-8111-111111111111', gen_random_uuid(), now() + interval '5 min')
    ON CONFLICT (tenant_id, event_ts, event_uuid) DO NOTHING;
  SELECT count(*) INTO after FROM location_log WHERE event_uuid = u;
  IF after <> 1 OR before <> 1 THEN RAISE EXCEPTION 'S1-(2) FAIL: 重複排除できず (before=%, after=%)', before, after; END IF;
  RAISE NOTICE 'S1-(2) PASS: 同一event_uuid再送で0重複（件数=%）', after;
END $$;

-- (3) 不整合 event_ts の挿入は CHECK で拒否（トリガを一時無効化して直接突く）
RESET ROLE;
SET ROLE app_owner;
ALTER TABLE location_log DISABLE TRIGGER trg_set_event_ts;  -- サーバ導出を止めて生値を試す
RESET ROLE;
SET ROLE app_user;
SET app.allowed_tenants = :'A';
SET app.tenant_id = :'A';
DO $$
DECLARE u uuid := gen_uuidv7('2026-07-20 00:00:00+00'); blocked boolean := false;
BEGIN
  BEGIN
    -- event_ts に uuidv7_time(u) と異なる値を入れる → CHECK(event_ts_derived) 違反のはず
    INSERT INTO location_log(event_uuid, tenant_id, user_id, event_ts, occurred_at)
      VALUES (u, '11111111-1111-7111-8111-111111111111', gen_random_uuid(),
              '2000-01-01 00:00:00+00', now());
  EXCEPTION WHEN check_violation THEN blocked := true;
  END;
  IF NOT blocked THEN RAISE EXCEPTION 'S1-(3) FAIL: 不整合 event_ts が CHECK を通過した'; END IF;
  RAISE NOTICE 'S1-(3) PASS: 不整合 event_ts を CHECK が拒否（誤配置を防止）';
END $$;
RESET ROLE;
SET ROLE app_owner;
ALTER TABLE location_log ENABLE TRIGGER trg_set_event_ts;   -- 復帰
RESET ROLE;
SET ROLE app_user;

-- (4) 書込境界: current=A で tenant_id=B を書くと WITH CHECK 違反
SET app.allowed_tenants = '11111111-1111-7111-8111-111111111111,22222222-2222-7222-8222-222222222222';
SET app.tenant_id = :'A';
DO $$
DECLARE u uuid := gen_uuidv7('2026-08-20 00:00:00+00'); blocked boolean := false;
BEGIN
  BEGIN
    INSERT INTO location_log(event_uuid,tenant_id,user_id,occurred_at)
      VALUES (u, '22222222-2222-7222-8222-222222222222', gen_random_uuid(), now());
  EXCEPTION WHEN check_violation OR insufficient_privilege THEN blocked := true;
  END;
  IF NOT blocked THEN RAISE EXCEPTION 'S1-(4) FAIL: 現テナント外への書込が通った'; END IF;
  RAISE NOTICE 'S1-(4) PASS: current=A から tenant=B への書込を WITH CHECK が拒否';
END $$;

-- (5) 横断: allowed={A,B} なら両テナント可視
DO $$
DECLARE n int;
BEGIN
  SELECT count(DISTINCT tenant_id) INTO n FROM location_log;
  IF n <> 2 THEN RAISE EXCEPTION 'S1-(5) FAIL: 横断で可視テナント数 % (期待2)', n; END IF;
  RAISE NOTICE 'S1-(5) PASS: 許可集合横断で2テナント可視';
END $$;

RESET ROLE;

-- (6) FORCE RLS: 所有者 app_owner でも RLS に従う（他テナント遮断）
SET ROLE app_owner;
SET app.allowed_tenants = :'A';
SET app.tenant_id = :'A';
DO $$
DECLARE n_b int;
BEGIN
  SELECT count(*) INTO n_b FROM location_log WHERE tenant_id = '22222222-2222-7222-8222-222222222222';
  IF n_b <> 0 THEN RAISE EXCEPTION 'S1-(6) FAIL: FORCE RLS 下の owner に他テナント % 件見えた', n_b; END IF;
  RAISE NOTICE 'S1-(6) PASS: FORCE RLS が所有者にも適用（他テナント0件）';
END $$;
RESET ROLE;

-- (7) パーティションプルーニング: event_ts 範囲で該当月のみスキャンされるか（R4-L2）
SET ROLE app_user;
SET app.allowed_tenants = :'A';
SET app.tenant_id = :'A';
EXPLAIN (COSTS OFF)
SELECT count(*) FROM location_log
WHERE event_ts >= '2026-07-01' AND event_ts < '2026-08-01';
RESET ROLE;

\echo '===== S1 完了（各 PASS/NOTICE を確認。EXPLAIN に 2026_08 が出ないこと＝プルーニング） ====='
