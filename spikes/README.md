# spikes — 技術検証（PostgreSQL＋PostGIS の PoC）

| 項目 | 内容 |
|---|---|
| 目的 | データモデル設計書・ADR の**PostgreSQL に依存する設計**を、実際に動かして検証する。 |
| 原則（**v2 で確立**） | **スパイクSQLと文書のポリシー/DDL本文は同一でなければならない。** 別物なら PASS は設計を検証していない。**検証成果物（実行ログ・EXPLAIN 全文）を `results/` に保存する**（要約数値だけでは再現性がなく、後から「Index Cond に何が入ったか」を判定できない）。**最も内側の1経路だけを通しても PASS するので、包含の外側（子テーブル・ビュー・プール返却後・実行プラン・カタログ表現・ロール継承）を明示的に試験項目に含める。**（開発工程 教訓15/16） |

---

## v2 全面改訂の理由（2026-07-27）

**PostgreSQL 実挙動検証**（[検証記録](../docs/design/PostgreSQL実挙動検証記録.md)）で、**v1 のスパイクは文書 §4/§6 とは別のポリシー・別のDDLで走っており、「全PASS」は文書の設計を検証していなかった**ことが判明した（差異15件）。主なもの：

- restrictive の述語を**関数にラップ**していた（文書はインライン）→ **検査4/5 が偽陰性になる形**（PG-M4）
- 注入値が**カンマ区切り**（文書の述語は配列リテラル `{}` を要求）→ **組合せは100%エラー**（PG-H2）
- **基本 permissive（`TO` 付き）が存在しなかった** → **所有者・トリガ経路の破綻（PG-H3）を一度も踏んでいない**
- **述語形5種のうち (b) 本人起点／(e) ③共有＋上書き／(f) ブートストラップは1本も実装されていなかった**
- **FORCE RLS の子への非伝播を親経由でしか試験していなかった**（PG-H6）
- UPDATE/DELETE ポリシーが1本も無い／seed が RLS 有効化の**前**／EXPLAIN 全文が未保存

**v2 では「文書に書かれた本文をそのまま実行する」ことを原則とし、差異があれば落ちるようにした。**

---

## ファイル

| ファイル | 内容 | 状態 |
|---|---|---|
| `00_common.sql` | 拡張・**ロール7種**（app_owner／app_user／admin_role／auth_role／bootstrap_owner／**auth_context_owner**／audit_writer）・**スキーマ `part`・`priv`**・UUIDv7ヘルパ・**`clamp_event_ts`** | ✅ |
| `S1_partition_rls_unique.sql` | パーティション×RLS×冪等×FORCE RLS×**受理台帳**×**capability**×**ブートストラップ**×**子直接参照**×**注入漏れ**×**版履歴・監査トリガ** | ✅ **全項目PASS**（PG16で (14) `security_invoker`、(15) 所有者・トリガ・監査経路も実測） |
| `S2_spatial_rls.sql`＋`S2_spatial_concurrency.sql` | 空間索引×RLSと100万ポリゴン・64接続の本番規模負荷（**PostGIS 必須**） | ✅ **数値bbox事前絞込＋明示tenantでPASS**。2026-08-15に`0009_field_bbox_prefilter.sql`へ昇格済み |
| `S4_rls_scale.sql` | RLS×規模（10万行・スコープ restrictive） | ✅ 実行済 |
| `S5_audit_chain.sql`＋`load/S5_*.sql` | `(tenant_id, 月)`監査ハッシュチェーンの同一テナント集中／テナント分散並行負荷と鎖検証 | ✅ **PG16・32接続・500書込/秒でPASS** |
| `S6_device_capabilities.html`＋`S6_DEVICE_TEST.md` | iOS／Android PWAのStorage・SW・Background Sync・Push・位置・再起動後生存の実機証跡 | 🟡 **ハーネス完成、実機マトリクス未実測** |
| `S7_offline_sync.py` | オフライン同期の参照状態機械（束原子性・冪等・競合・在庫・カーソル・権限失効・移送・旧版保全） | ✅ **15シナリオ PASS** |
| `S7_integration.sql`＋`load/S7_integration_load.py` | 実PostgreSQL/FORCE RLS＋2 HTTPプロセス＋16 DB接続＋100ms/10Mbps模擬回線の統合負荷 | ✅ **push/pull・写真・冪等整合PASS** |
| `S8_auth_context.sql` | BFF候補集合を現在のmembership／role／scope／tenant関係／双方向確認済み雇用関係へ再照合するDB検証関数 | ✅ **PG16で12群PASS**。ADR-0005 v8採用済み。次工程で本番migrationへ昇格 |
| `results/` | **実行ログと EXPLAIN 全文**（教訓16） | — |

---

## 実行

```bash
# Docker（PostGIS 込み。S2 を含む全スパイク）
open -a Docker && ./run.sh

# S7だけ（Python 3のみ。Docker/DB不要）
./run.sh S7

# AuthContext DB検証だけ
./run.sh S8

# 監査ハッシュチェーン並行性能（PostgreSQL over TCP + pgbench）
./run.sh S5

# PostGIS本番規模（100万ポリゴン、64接続）
./run.sh S2LOAD

# 同期の実DB・HTTP/TCP統合負荷
./run.sh S7LOAD

# ローカル PostgreSQL（PostGIS 無しでも S1/S4 は実行可能）
export PATH=/opt/homebrew/opt/postgresql@NN/bin:$PATH
initdb -D /tmp/pg/data -U postgres -A trust
pg_ctl -D /tmp/pg/data -o "-p 5544 -k /tmp/pg" start
psql -h /tmp/pg -p 5544 -U postgres -v ON_ERROR_STOP=1 -f 00_common.sql
psql -h /tmp/pg -p 5544 -U postgres -v ON_ERROR_STOP=1 -f S1_partition_rls_unique.sql
```

> **最低要求 PostgreSQL バージョンは 15 以上**（`security_invoker` ビュー。ADR-0004 §2.5）。**S1 の項目(14) は PG15 未満ではスキップされる。**

## 2026-08-14 の実行結果（S5・監査ハッシュチェーン並行性能）

実行ログ：[`S5_2026-08-14_PG16.log`](results/S5_2026-08-14_PG16.log)。既定の`(tenant_id, 月)`チェーンを行ロックで直列化し、PostgreSQL TCP接続32本から**500監査書込/秒を15秒**投入した。同一最大テナント集中ケースは504.7 tps、p95 6.60 ms、失敗・遅延スキップ0で、14,985行の`prev_hash`連続性と全`row_hash`再計算も不一致0だった。テナント分散ケースだけでなく、同一テナント集中ケースを規範的な合格条件としている。

この500書込/秒はADR-0019 v1で**最低認証プロファイル**へ確定した。要求のオンライン保存1秒/p95を十分下回るため月次チェーンを維持し、日／時間分割やMerkleサブチェーンは現時点で導入しない。配備ごとの予測ピークが250件/秒を超える場合は予測ピークの2倍へ`rate`を置換し、同じハーネスで再受入する。外部アンカ署名は期間クローズの非同期処理であり、このホットパス測定には含めない。

## 2026-08-14 の実行結果（S2・本番規模PostGIS並行負荷）

実行ログ：[`S2_LOAD_2026-08-14_PG16_PostGIS.log`](results/S2_LOAD_2026-08-14_PG16_PostGIS.log)。100テナント×1万圃場＝100万ポリゴン、FORCE RLS、64接続からbbox/KNNを各200 query/秒で実行した。

PostGIS `&&`だけの初回はp95 2,499.26msで不合格となり、PG-M2が本番規模で顕在化した。危険な`LEAKPROOF`指定やRLS迂回は行わず、書込時に数値bbox 4列を保持し、明示tenant＋leakproofな浮動小数比較で候補を減らしてからPostGISで厳密判定する方式へ変更した。再測はbbox 1,000件が199.9 tps／p95 80.71ms、KNN 20件が194.5 tps／p95 54.41ms、失敗・skip・他tenant漏洩0でPASSした。

したがってS2の方式検証と本番規模負荷は完了とする。数値bbox列・tenant付き索引は2026-08-15に`apps/bff/migrations/0009_field_bbox_prefilter.sql`へ、事前絞込＋厳密PostGIS判定は実repositoryへ昇格した。配備先では同migration適用後に同じハーネスを再受入する。

## 2026-08-14 の実行結果（S8・PostgreSQL 16.4／PostGIS 3.4.3）

実行ログ：[`S8_2026-08-14_PG16.log`](results/S8_2026-08-14_PG16.log)。共通ロール定義変更後のS1回帰ログ：[`S1_2026-08-14_PG16.log`](results/S1_2026-08-14_PG16.log)。

S8は`app_private.validate_auth_context`を実DDL化し、次を**12群すべてPASS**した。

- 通常tenantのactive membership、付与scope、role由来capabilityを受理。
- role外capability、membership外scope、revoked membership、空／重複集合を拒否。
- group管理者はactiveな配下tenantだけ横断でき、revokedなtenant関係は拒否。
- 雇用主横断はtenant・管理者・従業員の組について双方確認済みかつ未失効の場合だけ受理。
- `app_user`は検証関数だけを実行でき、`priv`の権限基表を直接参照できない。
- 関数は`auth_context_owner`所有の`SECURITY DEFINER`＋固定`search_path`。所有者はNOLOGIN・非superuser・非BYPASSRLSで、ログインロールへ委譲しない。

S1も全15項目に加えて、関数所有者／BYPASSRLSロールがログインロールへ委譲されないカタログ検査をPASSした。S8の権限基表は**ADR-0005 v8で採用した参照物理形**である。次工程では、このPASSに加えて版管理・失効イベント・索引・backfillを含む本番migration回帰へ移す。

## 2026-08-14 の実行結果（S7・Python 3.14.6）

実行ログ：[`S7_2026-08-14.log`](results/S7_2026-08-14.log)

S7は、ADR-0007/0008の同期契約を実行可能な参照状態機械にし、次の15シナリオを検証して**全件PASS**した。

- 最小依存束の全成功/全保留、tenant跨ぎ・最大サイズ超過の拒否、再送キー不変と重複ゼロ
- ②の非競合フィールド自動マージ、競合候補の耐久保全、依存グラフの凍結・解除・循環/深さ上限
- 在庫500並行要求の資材単位直列化（受理100、裁定400、残高0、負数なし）と、`occurred_at`/受理時刻の裁定材料保持
- `(shard, scope)` カーソル、固定上限スナップショット、①/②の共通変更ログ、P2 10,000件中のP0先行
- 権限失効時の法定記録保全、±1年の端末時計でも①-a受理、14日保持窓の再付与初回pull
- 移送凍結中のoutbox保持、旧shardからのredirect、再送冪等、移送後チェーン再検証
- 強制更新前のoutbox保護、変換不能旧版原文のquarantine、1 shard欠損時の`partial`、弱参照切れ検知

計測値は、在庫500要求が26.7 ms、P2 10,000件からのP0選択が0.366 ms、再付与モデルが15行/8,192 bytes（1 Mbps換算0.066秒）だった。これは**インメモリ参照モデルの構造検証値**であり、実ネットワーク、写真、PostgreSQL、RLS、複数プロセスを含む本番SLOの合格根拠ではない。ADR-0019 v1のP0予約pool／最低負荷profileとADR-0020 v1のP0 99.9%／500msを、TLS ingress、実BFF、pooler、object storageを含む本番候補環境で最終受入する。

### S7 実DB・HTTP/TCP統合負荷

実行ログ：[`S7_INTEGRATION_2026-08-14_PG16_HTTP.log`](results/S7_INTEGRATION_2026-08-14_PG16_HTTP.log)。上の状態機械とは別に、PostgreSQL 16のFORCE RLS表、2つのHTTPサーバプロセス、計16本の永続DB接続を実装し、実HTTP/TCPループバック上で100ms RTT／10Mbpsを模擬した。

- push 1,200要求（固有1,000＋再送200）：p95 135.73ms、accepted 1,000、duplicate 200、重複change 0。
- 優先度別pull 8ページ：p95 78.05ms。
- 1日分50記録＋100KB写真10枚：0.632秒（許容5分以内）。
- DB整合：receipt 1,050＝change 1,050、attachment 10、失敗0。

これにより「インメモリだけ」という証拠範囲は解消した。ただし回線条件は再現可能な**アプリ層エミュレーション**であり、キャリア／圃場Wi-Fiの実測トレースではない。また負荷用HTTP契約であり、本番BFF runtime、TLS ingress、pooler、オブジェクトストレージを含むリリース受入は残る。

---

## 2026-08-13 の実行結果（PostgreSQL 16.4・PostGIS 3.4.3・arm64）

実行ログ：[`S1_2026-08-13_PG16.log`](results/S1_2026-08-13_PG16.log)／[`S2_2026-08-13_PG16_PostGIS.log`](results/S2_2026-08-13_PG16_PostGIS.log)

### S1：全項目 PASS（`security_invoker`、所有者・トリガ・監査経路を含む）

- (14) はビューの作成確認だけでなく、**テナントAにはAの上書き、テナントBには共有マスタが見えること**を呼出元RLSの文脈で実測した。
- PostgreSQL 15+ で必要な `security_invoker` が、設計どおり③共有＋上書きの解決に使えることを確認した。
- (15) は `app_owner` 所有の `SECURITY DEFINER` 版履歴トリガが FORCE RLS 下で更新前スナップショットを残すこと、注入無しの所有者は本体・履歴・監査を読めず履歴へ直接追記できないことを確認した。
- `audit_writer` 所有の監査トリガが、クライアントのtenant主張ではなく**対象行由来の `tenant_id`** でINSERT/UPDATEを記録することを確認した。`audit_writer` は **NOLOGIN・BYPASSRLS・`audit_log` へのINSERTのみ**で、監査関数は通常ロールから直接実行できない。
- S1が検証するのは**監査書込経路と最小権限**まで。`prev_hash` の直列化、チェーン生成、並行性能、アンカ署名は引き続き S5 の対象とする。

### S2：テスト規模ではSLO内、構造的課題あり

合成データは20テナント×5,000行＝10万行。PostGIS の `&&(geometry, geometry)` と `<->(geometry, geometry)` は、いずれも **`proleakproof = false`** だった。

| ケース | 実行時間 | 実行プランの要点 |
|---|---:|---|
| A1 RLSのみ＋bbox | 7.502 ms | 複合GiSTの `Index Cond` は `tenant_id` のみ。空間 `&&` は `Filter`、1,546行を除外 |
| A2 `tenant_id` 等値を明示＋bbox | 3.335 ms | `tenant_id` は複合GiSTに入るが、空間 `&&` は引き続き `Filter`、1,546行を除外 |
| B1 RLSのみ＋KNN | 1.233 ms | `geom` 単独GiSTを全テナント横断で走査し、20行を得るまで276行をRLSで除外 |
| B2 `tenant_id` 等値を明示＋KNN | 0.137 ms | 複合GiSTで `tenant_id` を `Index Cond`、距離を `Order By` に使用。RLS除外による増幅なし |

判定：

- **性能値**はすべて地図初期表示2秒の基準内。ただし合成10万行・単一接続の結果であり、**本番規模・並行負荷の合格根拠にはしない**。
- **PG-M2 を実測確認**：bbox は明示的な `tenant_id` 等値を加えても、非leakproofな `&&` が空間列の `Index Cond` に入らない。意図した「テナント絞り→空間索引」の構造的合格基準を満たさず、設計上の緩和策と再測が必要。
- **PG-M8 を実測確認**：RLSだけのKNNは他テナント行を読み捨てる。テナント単位のKNNでは**アプリクエリに `tenant_id` 等値を明示することが有力な緩和策**だが、本番テナント数で再測してから確定する。

実行に伴い、ハーネスも PostgreSQL 16 で再現可能な形へ修正した：検証DBを毎回再作成して文書どおり `public` で実行、PG15+の `public` スキーマ既定権限を明示付与、S2 のGUC注入を `set_config` に統一した。

### 現在残る未完了

| 項目 | 次にやること |
|---|---|
| **S6（iOS／Android端末能力）** | 測定PWAとデスクトップ診断は完了。サポート下限／現行iPhoneとAndroid実機でbrowser／standalone、再起動、容量圧迫、背景遷移を測定する |
| **S9（端末暗号化／失効／鍵交代）** | ADR-0017 v2のcache/outbox別鍵、offline recovery wrap、権限version付き失効収束、新旧鍵交代／backup復旧をPWA／ネイティブ／サーバ境界ごとに検証する |

---

## 2026-07-27 の実行結果（PostgreSQL 14.23・PostGIS 無し）

### S1：全項目 PASS（(14) を除く）

| # | 項目 | 結果 |
|---|---|---|
| (1) | 遮断：他テナントは不可視 | ✅ |
| (2) | **横断が通る**：グループ管理者が `allowed_tenants` 内の他テナントを読む | ✅ **v1 では検査していなかった項目**（遮断だけ見ると横断が動かなくても緑になる） |
| (3) | **本人起点横断**：`allowed_tenants` の外の自分の労務サマリが読める／他人では読めない | ✅ 形(b) |
| (4) | **③共有行＋横断で配下の上書きも見える** | ✅ 形(e) |
| (5) | **capability**：無いと他者の位置ログが不可視／あると可視 | ✅ 形(d) |
| (6) | **書込側**：他テナント名義 INSERT／行移送／共有行書込 の拒否 | ✅ **v1 では読取しか見ていなかった** |
| (7) | **冪等**：受理台帳が再送を弾き、記録済み `event_ts` が不変 | ✅ サーバ採番でも決定性が保たれる |
| (8) | **クランプ**：端末時計が **-20年/+5年**ずれても①-aが受理できる | ✅ IND3-H5 |
| (9) | **子テーブルの直接参照が拒否される** | ✅ PG-H6（`part` スキーマの USAGE 無し） |
| (10) | **注入漏れ**：`SET LOCAL` を使った接続の**2回目**でも 0行（例外にならない） | ✅ PG-H1（正規化形） |
| (11) | **ブートストラップ**：`auth_role` が関数経由で解決／直接参照は拒否／主張と引数不一致で0件 | ✅ IND3-H1／PG-H5 |
| (12) | FORCE RLS：所有者も従う | ✅ |
| (13) | **カタログ構造検査8項目** | ✅ restrictive に `TO` 無し／permissive は `true` のみ／WITH CHECK 明示／`part` の権限無し 等 |
| (14) | `security_invoker` ビュー | ⏸ **PG15 必須のためスキップ**（PG14 環境） |

### 実行して新たに判明した設計の抜け（2件・いずれも文書へ反映済み）

1. **共有マスタの書込主体が設計に無かった（PG-H3 の実地再現）**：基本 permissive を `TO app_user` だけにすると、**FORCE RLS 下でテーブル所有者が自表に書けない**。A1c-L2 の「管理用の別接続/別ロール」を **`admin_role` として実体化**し、**permissive の `TO` に列挙**＋**形(e) の WITH CHECK に `admin_role` の共有行書込例外**を加えた。**permissive の `TO` には「そのテーブルに正当にアクセスするすべてのロール」を列挙する**（列挙漏れ＝そのロールが何もできない＝安全側）。
2. **クランプ窓とパーティション被覆の不変条件**：**クランプ窓の下限は「常に存在する最古のパーティションの開始」以上でなければならない**。さもないとクランプしても該当パーティションが無く `no partition ... found for row` になり、**IND3-H5 の目的（どんな端末時計でも①-aは受理できる）が達成されない**。**retention でパーティションをドロップする際は、クランプ窓の下限も同時に繰り上げる。**

### S4：実行済＋EXPLAIN 全文を保存

- スコープ restrictive 込みの一覧が**実時間 15.5ms**（10万行・合成データ・単一ノード）。
- **PG-M1 を実測で確認**：**RLS 述語だけでは tenant_id ハッシュ副軸のプルーニングが起きない**（S1 の EXPLAIN：RLS述語のみ→**h0/h1 の両方**をスキャン／アプリが `tenant_id = $1` を明示→**h0 のみ**）。**副軸の目的は書込分散と局所性であり、プルーニングにはアプリが明示的な `tenant_id` 等値述語を付けることが必要。**

### 2026-07-27 時点の未実行（履歴）

| 項目 | 理由 | 次にやること |
|---|---|---|
| **S2（空間）** | **PostGIS 未導入** | Docker 環境で実行し、**PG-M2（RLS 下で PostGIS の `&&`／`<->` が索引条件に使えるか＝leakproof 性）を EXPLAIN 全文で確認**する |
| **S1 (14) `security_invoker` ビュー** | **PostgreSQL 15 未満** | PG15+ 環境で実行（PG-H4／IND3-M6） |
| **所有者・トリガ経路（版履歴・監査）** | 2026-07-27時点で未実装（**2026-08-13のS1 (15)で解消**） | `*_history` の書き手と `audit_writer` 経路を S1 に追加（PG-H3 の残り） |
| **S5（監査並行性）／S7（同期整合）** | 未作成 | ADR-0004 v8 の合格基準「想定最大テナントの書込レートで直列化がSLOを侵さないこと」で設計する |
