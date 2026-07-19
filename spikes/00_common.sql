-- =====================================================================
-- 共通: 拡張・ロール・UUIDv7ヘルパ（S1/S2/S4 で共有）
-- データモデル設計書 v4 §4/§5/§6 に対応。R4-L1（uuidv7_time の IMMUTABLE 性）を検証する。
-- =====================================================================
\set ON_ERROR_STOP on

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS btree_gist;   -- gist(tenant_id, geom) 複合索引用

-- --- ロール（ADR-0001: 非所有者・非BYPASSRLS のアプリロール／監査は BYPASSRLS 別ロール）---
DROP ROLE IF EXISTS app_owner;
DROP ROLE IF EXISTS app_user;
DROP ROLE IF EXISTS audit_writer;
CREATE ROLE app_owner   NOLOGIN NOSUPERUSER NOBYPASSRLS;  -- テーブル所有者（FORCE RLS 検証用）
CREATE ROLE app_user    NOLOGIN NOSUPERUSER NOBYPASSRLS;  -- アプリ実行ロール
CREATE ROLE audit_writer NOLOGIN NOSUPERUSER BYPASSRLS;   -- 監査書込専用（R3-L1）
GRANT app_owner TO postgres;   -- postgres が SET ROLE できるように
GRANT app_user  TO postgres;
GRANT audit_writer TO postgres;

-- --- UUIDv7 のタイムスタンプ抽出（R4-L1: IMMUTABLE で作れるか＝生成列/CHECK の前提）---
-- UUIDv7 先頭48bit = unix_ts_ms(ビッグエンディアン)。text化して先頭12hexを取り出す。
CREATE OR REPLACE FUNCTION uuidv7_time(u uuid)
RETURNS timestamptz
LANGUAGE sql
IMMUTABLE PARALLEL SAFE          -- ← IMMUTABLE で作成できること自体が R4-L1 の合否
AS $$
  SELECT to_timestamp(
    ( ('x' || substr(replace(u::text,'-',''), 1, 12))::bit(48)::bigint ) / 1000.0
  )
$$;

-- --- 試験データ用 UUIDv7 生成（VOLATILE。指定msの時刻を先頭に埋め込む）---
CREATE OR REPLACE FUNCTION gen_uuidv7(ts timestamptz DEFAULT clock_timestamp())
RETURNS uuid
LANGUAGE sql VOLATILE
AS $$
  SELECT (
       lpad(to_hex((extract(epoch FROM ts)*1000)::bigint), 12, '0')  -- 48bit ms
    || '7'                                                           -- version=7
    || lpad(to_hex((random()*4095)::int), 3, '0')                    -- rand_a(12bit)
    || to_hex(8 + (random()*3)::int)                                 -- variant 8..b
    || lpad(to_hex((random()*268435455)::bigint), 7, '0')            -- 28bit
    || lpad(to_hex((random()*4294967295)::bigint), 8, '0')           -- 32bit
  )::uuid
$$;

-- R4-L1 確認: uuidv7_time が IMMUTABLE で、埋め込んだ時刻を復元できるか
DO $$
DECLARE u uuid; t0 timestamptz := '2026-07-20 01:02:03+00'; t1 timestamptz;
BEGIN
  u := gen_uuidv7(t0);
  t1 := uuidv7_time(u);
  IF abs(extract(epoch FROM (t1 - t0))) > 0.001 THEN
    RAISE EXCEPTION 'R4-L1 FAIL: uuidv7_time 復元誤差 % 秒 (u=%)', extract(epoch FROM (t1-t0)), u;
  END IF;
  RAISE NOTICE 'R4-L1 PASS: uuidv7_time は IMMUTABLE かつ時刻復元一致 (%)', t1;
END $$;
