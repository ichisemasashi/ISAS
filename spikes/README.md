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

## 実行状況

- **S1／S2／S4：2026-07-20 に実行済・全PASS**（PostgreSQL 16＋PostGIS 3.4、arm64）。実測値と確定した設計事項は [データモデル設計書 §7](../docs/design/データモデル設計書.md) を参照。
- 再実行するときは Docker Desktop を起動（`open -a Docker`）してから `./run.sh` を実行すること（`psql` は未導入でもコンテナ内で実行される）。

## 未実行のスパイク

| ID | 検証 | 出所 | 状態 |
|---|---|---|---|
| **S5** | 監査ハッシュチェーンの並行性（単位分割/アンカ署名が高並行で成立するか） | ADR-0004 A4b-M2 | 実装フェーズで実施 |
| **S7** | 同期整合（束の粒度・依存グラフの凍結伝播/循環検出・在庫の受理時残高並行性・シャード別カーソル） | ADR-0007 §5／ADR-0008 §5 | **未着手（ハーネス未作成）**。ADR-0007の残Lowと、ADR-0008の同期API契約検証の前提 |
| — | S2/S4 の**本番規模・並行負荷での再測** | データモデル設計書 §7 | 実装フェーズで実施（S1/S2/S4は合成データ・単一ノード実測） |

> S3（冪等×パーティション）はS1に統合済のため単独では実施しない。S6はADR-0007で言及のみで未定義（S7へ包含するか起票時に判断）。
