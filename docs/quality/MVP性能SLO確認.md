# MVP性能SLO確認

| 項目 | 内容 |
|---|---|
| 実施日 | 2026-08-15 |
| 対象 | S2 PostGIS、S5監査chain、S7同期統合、MVP圃場検索migration |
| 判定 | **測定済み境界はPASS、MVP全体は条件付き** |

## 自動SLOゲート

`python3 research/quality/check_slo.py`は保存済み生ログを再解析し、PASSラベルの欠落、測定値の欠落、閾値超過、整合不一致のいずれかで終了コード1を返す。判定器自体は3件の単体試験で正常系、閾値超過、証跡欠落を確認した。

| 境界 | 負荷条件 | 実測 | 予算 | 判定 |
|---|---|---:|---:|---|
| S2 bbox | 100万Polygon、64接続、1,000件、200 qps投入 | p95 80.71ms、199.9 tps | 2,000ms、190 tps以上 | PASS |
| S2 KNN | 同上、20件 | p95 54.41ms、194.5 tps | 500ms、190 tps以上 | PASS |
| S2 分離 | FORCE RLS、100 tenant | 他tenant漏洩0 | 0 | PASS |
| S5監査 | 同一最大tenant、32接続、500書込/s | p95 6.60ms、504.7 tps | 1,000ms、475 tps以上 | PASS |
| S5 chain | 並行挿入14,985行 | prev/hash不一致0 | 0 | PASS |
| S7 push | PG16 FORCE RLS、2 HTTP process、16 DB接続、100ms RTT／10Mbps | p95 135.73ms | 500ms | PASS |
| S7 pull | 同上、8ページ | p95 78.05ms | 500ms | PASS |
| S7 1日分 | 50×1KiB＋10×100KB写真 | 0.632秒 | 300秒 | PASS |
| S7冪等 | 固有1,000＋再送200 | 重複change 0、receipt=change | 不一致0 | PASS |

## S2波及漏れの是正

保存済みS2ログではPostGIS-only bboxがp95 2,499.26msで不合格、数値bbox事前絞込後が80.71msで合格していた。しかしMVP本番migrationとrepositoryは前者のままだったため、次を是正した。

- `0009_field_bbox_prefilter.sql`でgeomから生成するbbox 4列とtenant付きB-tree索引4本を追加。
- REST圃場検索を「明示tenant＋leakproofな数値比較→厳密なPostGIS `&&`」へ変更。
- PostgreSQL 16.4／PostGIS 3.4.3でmigration全順序を再構築し、既存geomのbackfill、検索一致、索引4本を含む新規3群と既存31群をPASS。
- repository単体試験でSQLに数値事前絞込が残ることを固定。

## 未測定のMVP SLO

以下は代替指標で合格扱いせず、実配備runtimeと本番相当fixtureが揃うまでリリース阻害条件とする。

| 要求SLO | 現状 |
|---|---|
| 地図初期表示 1,000枚 p95 2秒 | DB検索境界のみPASS。TLS、BFF、GeoJSON転送、MapLibre描画、背景style／tileを含む端末E2Eは未測定 |
| 地図パン／ズーム 200ms | iOS／Android実機と本番tile構成が未確定 |
| 圃場一覧10,000件 p95 1.5秒 | APIは1ページ最大500件。10,000件ページング全体と端末描画は未測定 |
| ガント500タスク p95 3秒 | 現在の自動E2E fixtureは1件で、500件描画負荷は未測定 |
| 写真1枚付き日誌保存 p95 1秒 | S7の写真転送はPASSしたが、実BFF runtime、object storage、scan／再encodeを含まない |
| 一般GET p95 500ms | S7 pull代表経路はPASS。実ingress／pooler／全主要endpointの分位値は未測定 |
| ログイン p95 2秒 | 実IdP、所属導出、複数shard fan-outが未接続 |

Web buildでは地図lazy chunkがminified 941.40KB（gzip 244.95KB）で500KB警告を超える。初期main chunkとは分離済みだが、地図2秒SLOの端末受入まで性能リスクとして追跡する。

## 再受入条件

本番候補環境でTLS ingress、実BFF HTTP runtime、pooler、object storage、背景地図、iOS／Android実機を接続し、上表の全画面／操作を30回以上測定する。p95とerror率をCI成果物へ保存し、いずれか未測定または超過ならリリースを止める。
