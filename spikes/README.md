# M4 スパイク（PoC）ハーネス

[データモデル設計書 v4](../docs/design/データモデル設計書.md)（クローズ済）の設計仮説と残Low（R4-L1/L2）を、実 PostgreSQL+PostGIS で検証する。

## 対象スパイク

| ID | 検証 | 合格基準 | 主に確認する設計判断 |
|---|---|---|---|
| **S1** | パーティション×RLS×一意×FORCE RLS×冪等(UUIDv7) | 他テナント遮断100%／同一event_uuid再送で0重複／不整合event_ts拒否 | §5/§6 location_log、R3-M2、R4-L1（uuidv7_time の IMMUTABLE 性・生成列可否） |
| **S2** | PostGIS 空間×RLS 性能 | 地図初期表示 2秒/p95、複合索引でtenant絞り→空間 | §6 field、gist(tenant_id,geom)、R2-L1/R4-L1（ST_Area(geography) 生成列可否） |
| **S4** | RLS×規模（許可集合・スコープ導出） | 一覧/API 500ms/p95 | §4 ポリシー結合、R3-M1/R4-L2（子テーブルのスコープ解決） |

## 実行

```bash
# 前提: Docker Desktop 起動済み
cd spikes
./run.sh all      # または ./run.sh S1 / S2 / S4
docker compose down -v   # 後片付け
```

`psql` をローカルに持つ場合は、`docker compose up -d` 後に
`psql "postgresql://postgres:spike@localhost:55432/spike" -f 00_common.sql -f S1_partition_rls_unique.sql` でも可。

## 判定の見かた

- 各スクリプトは `RAISE NOTICE '... PASS'` を出す。`FAIL` が出たら（例外で停止）その行が不合格。
- `EXPLAIN (ANALYZE)` の出力で、
  - S1-(7): 該当月パーティションだけがスキャンされる（他月が出ない）＝プルーニング成立。
  - S2-(A)(B): `Index Scan using field_tenant_geom_gix`（または geom 索引）が使われ、実時間が基準内。
  - S4-(A)(B): restrictive 2枚（テナント＋スコープ）込みで実時間が基準内。(C) は不採用案との比較。

## 結果のフィードバック先

- 合否と実測は [データモデル設計書 §7/§8](../docs/design/データモデル設計書.md) と
  [レビュー記録 R4-L1/L2](../docs/design/レビュー記録_データモデル設計書.md) に反映する。
- 破綻時の代替（設計書 §7 の「中止/代替」列）へ切替える。

## 環境が用意できない場合

この環境は `psql` 未導入・Docker デーモン停止のため、ハーネスのみ用意している。
Docker Desktop を起動（`open -a Docker`）してから `./run.sh` を実行すること。
