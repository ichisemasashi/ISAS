-- =====================================================================
-- 共通: 拡張・ロール・スキーマ・UUIDv7ヘルパ（S1/S2/S4 で共有）
-- 対応：データモデル設計書 v13 §4/§5/§6、ADR-0001 v19 §2.9/§2.10
--
-- 【v2 全面改訂の理由】PostgreSQL実挙動検証（2026-07-27）で、
--   「スパイクは文書 §4/§6 とは別のポリシー・別のDDLで走っており、
--    『全PASS』は文書の設計を検証していない」（差異15件）ことが判明した。
--   本ファイル以降は「文書に書かれた本文をそのまま実行する」ことを原則とする。
-- =====================================================================
\set ON_ERROR_STOP on

-- PostGIS は S2（空間）でのみ必要。無い環境でも S1/S4 が走るよう任意扱いにする。
DO $$
BEGIN
  BEGIN
    CREATE EXTENSION IF NOT EXISTS postgis;
    CREATE EXTENSION IF NOT EXISTS btree_gist;   -- gist(tenant_id, geom) 複合索引用（S2）
    RAISE NOTICE 'postgis/btree_gist: 有効（S2 実行可）';
  EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'postgis 未導入: S2（空間）はスキップされる。S1/S4 は実行可能。';
  END;
END $$;

-- --- スキーマ（ADR-0001 v19 §2.10.1 / A1g-H1）---
--   part : パーティション子の置き場。通常ロールに USAGE を与えない（PG-H6 の構造的封じ込め）
--   priv : 特権経路専用テーブルの置き場（現時点で対象0件だが枠として作る）
DROP SCHEMA IF EXISTS part CASCADE;
DROP SCHEMA IF EXISTS priv CASCADE;
DROP SCHEMA IF EXISTS spike CASCADE;

-- --- ロール（ADR-0001 v19）---
DO $$
DECLARE r text;
BEGIN
  FOREACH r IN ARRAY ARRAY['app_owner','app_user','admin_role','auth_role','bootstrap_owner','auth_context_owner','audit_writer'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
      EXECUTE format('DROP OWNED BY %I CASCADE', r);
      EXECUTE format('DROP ROLE %I', r);
    END IF;
  END LOOP;
END $$;

CREATE ROLE app_owner       NOLOGIN NOSUPERUSER NOBYPASSRLS;  -- テーブル所有者（FORCE RLS 検証用）
CREATE ROLE app_user        NOLOGIN NOSUPERUSER NOBYPASSRLS;  -- 業務ロール（唯一の業務接続ロール）
CREATE ROLE admin_role      NOLOGIN NOSUPERUSER NOBYPASSRLS;  -- 管理用の別接続（共有マスタ書込＝A1c-L2）
CREATE ROLE auth_role       NOLOGIN NOSUPERUSER NOBYPASSRLS;  -- 認証専用（テーブル権限ゼロ・EXECUTE のみ）
CREATE ROLE bootstrap_owner NOLOGIN NOSUPERUSER NOBYPASSRLS;  -- ブートストラップ関数の所有者（IND3-H1/PG-H5）
CREATE ROLE auth_context_owner NOLOGIN NOSUPERUSER NOBYPASSRLS; -- AuthContext検証関数と権限基表の所有者
CREATE ROLE audit_writer    NOLOGIN NOSUPERUSER BYPASSRLS;    -- 監査書込専用（R3-L1・INSERT のみ）
-- superuserはmembership無しでSET ROLEできる。関数所有者・BYPASSRLSロールを
-- ログインロールへGRANTした検証環境を本番形と誤認しないよう、通常試験ロールだけを委譲する。
GRANT app_owner, app_user, admin_role, auth_role TO postgres;  -- SET ROLE 用

CREATE SCHEMA part;   -- パーティション子（USAGE を通常ロールに与えない）
CREATE SCHEMA priv;   -- 特権経路専用（同上）
GRANT USAGE ON SCHEMA public TO app_owner, app_user, admin_role, auth_role, bootstrap_owner, auth_context_owner, audit_writer;
-- PostgreSQL 15+ は public スキーマの CREATE を PUBLIC へ既定付与しない。
-- DDL所有者だけに明示し、バージョン既定に依存せず文書のDDLをそのまま実行できるようにする。
GRANT CREATE ON SCHEMA public TO app_owner;
-- part / priv は「テーブル所有者だけが作成・管理する」。
--   通常ロール（app_user）・認証ロールには USAGE を与えない ← これが PG-H6 / A1g-H1 の防御。
GRANT USAGE, CREATE ON SCHEMA part, priv TO app_owner;

-- --- UUIDv7 のタイムスタンプ抽出（診断用。v10 以降 event_ts の導出には使わない）---
CREATE OR REPLACE FUNCTION uuidv7_time(u uuid)
RETURNS timestamptz LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
  SELECT to_timestamp(
    ( ('x' || substr(replace(u::text,'-',''), 1, 12))::bit(48)::bigint ) / 1000.0 )
$$;

-- --- 試験データ用 UUIDv7 生成（端末が生成する想定。時刻は端末の壁時計）---
CREATE OR REPLACE FUNCTION gen_uuidv7(ts timestamptz DEFAULT clock_timestamp())
RETURNS uuid LANGUAGE sql VOLATILE AS $$
  SELECT (
       lpad(to_hex((extract(epoch FROM ts)*1000)::bigint), 12, '0')
    || '7' || lpad(to_hex((random()*4095)::int), 3, '0')
    || to_hex(8 + (random()*3)::int)
    || lpad(to_hex((random()*268435455)::bigint), 7, '0')
    || lpad(to_hex((random()*4294967295)::bigint), 8, '0') )::uuid
$$;

-- --- サーバ側のクランプ（データモデル設計書 v10 §5 ②）---
--   端末申告の occurred_at を許容ウィンドウ [received_at-400d, received_at+1d] に収める。
--   これにより event_ts が構造的に範囲外にならない＝受理は必ず成功する。
CREATE OR REPLACE FUNCTION clamp_event_ts(occurred timestamptz, received timestamptz)
RETURNS timestamptz LANGUAGE sql IMMUTABLE AS $$
  SELECT greatest(least(occurred, received + interval '1 day'), received - interval '400 days')
$$;

DO $$
DECLARE u uuid; t0 timestamptz := '2026-07-20 01:02:03+00'; t1 timestamptz;
BEGIN
  u := gen_uuidv7(t0); t1 := uuidv7_time(u);
  IF abs(extract(epoch FROM (t1 - t0))) > 0.001 THEN
    RAISE EXCEPTION 'uuidv7_time 復元誤差 % 秒', extract(epoch FROM (t1-t0));
  END IF;
  RAISE NOTICE 'uuidv7_time: IMMUTABLE かつ時刻復元一致（診断用として維持）';
END $$;
