#!/usr/bin/env bash
# M4 スパイク実行スクリプト
# 前提: Docker Desktop 起動済み（docker info が通ること）
# 使い方: cd spikes && ./run.sh [S1|S2|S4|all]
set -euo pipefail
cd "$(dirname "$0")"

TARGET="${1:-all}"
PSQL="docker compose exec -T db psql -v ON_ERROR_STOP=1 -U postgres -d spike"

echo "== 1) DB 起動 =="
docker compose up -d
echo "== 2) ヘルスチェック待ち =="
until docker compose exec -T db pg_isready -U postgres -d spike >/dev/null 2>&1; do
  sleep 1; echo -n "."
done
echo " ready"

echo "== 3) 使い捨て検証DBを再作成（文書どおり public で実行） =="
docker compose exec -T db dropdb --if-exists -U postgres spike
docker compose exec -T db createdb -U postgres spike
echo "== 4) 共通セットアップ（拡張・ロール・UUIDv7）: 1回だけ =="
$PSQL -f - < 00_common.sql

run_one () {
  local f="$1"
  echo ""
  echo "########################################################"
  echo "# 実行: $f"
  echo "########################################################"
  # DB自体が使い捨てなので、文書と同じ public スキーマでSQL本文をそのまま実行する。
  # 別スキーマへ差し替えると、固定search_pathを持つSECURITY DEFINER関数の検証が別物になる。
  $PSQL -f - < "$f"
}

case "$TARGET" in
  S1) run_one S1_partition_rls_unique.sql ;;
  S2) run_one S2_spatial_rls.sql ;;
  S4) run_one S4_rls_scale.sql ;;
  all)
     run_one S1_partition_rls_unique.sql
     run_one S2_spatial_rls.sql
     run_one S4_rls_scale.sql ;;
  *) echo "unknown target: $TARGET (S1|S2|S4|all)"; exit 1 ;;
esac

echo ""
echo "== 完了。結果は上のログ（PASS/NOTICE, EXPLAIN）を参照。停止は: docker compose down -v =="
