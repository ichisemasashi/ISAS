# PostgreSQL 実挙動と設計文書の整合検証記録

| 項目 | 内容 |
|---|---|
| 目的 | 設計文書群に書かれた **PostgreSQL の挙動に関する主張**が、実際の PostgreSQL の動作と一致しているかを検証する。**設計の良し悪しではなく「書かれていることが技術的に成立するか」**だけを見る。あわせて **`spikes/*.sql`（実際に実行されたSQL）と文書化された設計（データモデル設計書 §4/§6）が同一か**を突き合わせる。 |
| 検証日 | 2026-07-27（初回）／**2026-08-13（PostgreSQL 16＋PostGIS追試）**／**2026-08-14（AuthContext DB検証）** |
| 検証者 | **独立エージェント**（設計者のレビュー履歴・教訓を与えず、ADR本文＋データモデル設計書＋スパイクSQLのみ）。**ローカルに PostgreSQL 14.23 の一時クラスタを立て、文書の主張を実際にSQLで再現して確認**。 |
| 設計者による再検証 | **最も影響の大きい2件（PG-H1／PG-H6）を設計者自身が別の一時クラスタで再実測し、いずれも指摘が正しいことを確認**（下記「設計者による再実測」）。 |
| 対象文書 | [ADR-0001](ADR/ADR-0001-マルチテナント分離-行レベル-RLS.md)／[ADR-0003](ADR/ADR-0003-データライフサイクル-追記型-論理削除-版履歴-監査.md)／[ADR-0004](ADR/ADR-0004-DB-PostgreSQL-PostGIS.md)／[データモデル設計書](データモデル設計書.md)／[spikes/](../../spikes/README.md) |
| 結果 | 初回 **High 6／Medium 9／Low 13**。2026-08-14までにHigh 6件を全件処置し、Medium 9件は全件設計裁定済み。LowはPG-L13の同一tenant FKを含め規範文書へ反映または実装注意へ分類した。**ADR-0001 v19の残存High 0／Medium 0**。S2本番規模再測とS5は性能／運用受入として継続する。 |
| 最重要の結論 | 初回の3つの反証（空GUC、パーティション子、スパイクと本文の差異）は、正規化形、`part`スキーマ、S1文書同一DDLへの改訂で解消した。以後は「包含の外側でも成立するか」を、構造検査＋振る舞いテストで確認する。 |

---

## 設計者による再実測（PostgreSQL 14.23・独立の一時クラスタ）

**PG-H1：カスタムGUC の reset 値は NULL ではなく `''`** — ✅ **指摘は正しい**

```
--- [1] 一度も SET していない新規セッション ---
 is_null | rows_when_unset
 t       | 0                  ← 0行・エラーなし（文書どおり）

--- [2] SET LOCAL を1回使い COMMIT した後（＝プール返却相当）---
 is_null_after | val_after
 f             | ''          ← NULL ではなく空文字列に化ける

--- [3] 同じクエリを再実行 ---
ERROR:  malformed array literal: ""
DETAIL:  Array value must start with "{" or dimension information.
```

**ADR-0001 A1b-L1 が「トランザクションプーリング＋`SET LOCAL`」を必須と定めている**ため、**「注入漏れ＝0行」ではなく「注入漏れ＝DBエラー」が既定の挙動**になる。フェイルクローズのテスト（「未設定で0行」）は**セッションを使い回した2回目のトランザクションでは別の結果になる**（テストが緑でも本番で赤）。

**PG-H6：FORCE RLS はパーティション子に伝播しない** — ✅ **指摘は正しい**

```
=== 子テーブルへの RLS 伝播状況 ===
   relname    | rls | force_rls
 ll           | t   | t          ← 親のみ
 ll_202607    | f   | f
 ll_202607_h0 | f   | f
 ll_202607_h1 | f   | f
=== 子テーブルのポリシー本数 ===  → 親(ll)に2本のみ。子は0本

=== 親経由 ===        via_parent   = 50   （自テナントのみ）✅
=== 子を直接参照 ===  child_total  = 100  （全テナントが見える）❌
```

**データモデル設計書 §5「全パーティション/サブパーティションに FORCE RLS が伝播することを S1 で確認」は事実に反する。** ADR-0004「**子への非伝播に注意**」が正しい。**上位ADRが正しく、下位の設計書が誤り、しかも『スパイクで確認した』と書かれている**という最悪の組合せ。

---

## High（実装すると動かない／セキュリティ・可用性が破れる）

| ID | 表題 | 判定 | ステータス |
|---|---|---|---|
| PG-H1（**対応済 v15**） | **「未設定/空/不正は偽＝全拒否」は成立しない**。空/不正は**プラン時例外**（SQLSTATE 22P02）。`SET LOCAL` を一度使った接続では以後「未設定」が NULL ではなく `''` になる | ❌ 誤り | 未対応 |
| PG-H2 | **文書の述語は配列リテラル `{...}` を要求するが、スパイクの注入値はカンマ区切り**。文書の述語＋スパイクの値の組合せは100%エラー。**「全PASS」は文書 §4 の述語を一度も実行していない** | ❌ 別物 | 未対応 |
| PG-H3 | **基本 permissive を `TO app_role, auth_role` に限定すると、テーブル所有者が FORCE RLS 下で自表に読み書きできない**。**版履歴（`*_history`）の SECURITY DEFINER トリガと所有者経由の一括投入が壊れる**。「RLSは親 field に追随」という機構は**存在しない** | ⚠️ 条件付き（帰結の適用漏れ） | **対応済・PG16実測PASS** |
| PG-H4（**対応済 v16**） | **`agro_chemical_effective` ビューは基底表の RLS を「ビュー所有者」の文脈で評価する**。v7 設計と組み合わせると**誰が読んでも0行**。`security_invoker`（PG15+）の記述がどの文書にも無い。**ビューは `pg_policies` にも `relrowsecurity` にも現れないので検査1〜8のどれにも引っかからない** | ❌ 誤り | **対応済・PG16実測PASS** |
| PG-H5（**対応済 v16**） | **ブートストラップ例外が ADR-0001 内部で自己矛盾**。`current_user = auth_role` 判定は **auth_role が直接 SELECT する**場合のみ真。SECURITY DEFINER 経由では関数所有者になり**第2項が常に偽→0行→自己ブロック再発**。§2(3)「membership の SELECT のみ」と §2 3b/§5「テーブル権限ゼロ・EXECUTE のみ」が両立しない | ❌ 誤り | 未対応 |
| PG-H6（**対応済 v16**） | **FORCE RLS はパーティション子に伝播せず、子直接参照で RLS が完全に無効**。防御は「子に GRANT しない」だけだが `GRANT … ON ALL TABLES IN SCHEMA` で露出する。**パーティションは月次で自動生成される＝無防備な子が毎月増える** | ❌ 誤り | 未対応 |

## Medium

| ID | 表題 | 判定 | ステータス |
|---|---|---|---|
| PG-M1 | **tenant_id ハッシュ副軸による「RLSプルーニング」は成立しない**。ハッシュプルーニングはプラン時定数を要求し、`current_setting()`／STABLE関数は該当しない。**§7 の S1 結果自身が証拠**（「July月の h0/h1 **のみ**scan」＝ハッシュ副軸は両方スキャンされている） | ❌ 誤り | 未対応 |
| PG-M2 | **RLS 下では非 leakproof な述語が索引条件に使えなくなる**。PostGIS 3.4.3 の `&&`／`<->` は実測で非leakproof。`gist(tenant_id, geom)` は **tenant_id 側しか効かず、bboxの空間条件は `Filter` に落ちた** | ❌ **実測確認** | **設計処置待ち** |
| PG-M3 | **restrictive 形(e)（③共有＋上書き）を `USING` のみで書くと、一般テナント接続が共有行を作れる／自テナント行を共有行に昇格できる**（`WITH CHECK` 省略時は `USING` が書込検査に流用される）。**検査項目は USING と `TO` と本数しか見ておらず WITH CHECK を一切見ない**。スパイク S4 の restrictive は全て `USING` のみ | ⚠️ 条件付き | 未対応 |
| PG-M4 | **検査4/5（qual に `current_user`／`app.user_id` が現れるか）は関数ラップで無効化される**。しかも**スパイクの実SQLがそのラップ形**（`allowed_tenants()` 等）。ADR-0001 自身が禁じた「文字列比較」でもある。**代替＝`pg_policy`→`pg_depend`→`pg_proc` を辿る**（実測で依存が記録されることを確認） | ⚠️ 判定不能 | 未対応 |
| PG-M5 | **「RLSを迂回できる主体の全列挙」に superuser が入っていない**。`CREATE ROLE … SUPERUSER` は `rolbypassrls = false` のまま RLS を迂回する（実測）。**BYPASSRLS はロールメンバーシップで継承されない**（`SET ROLE` して初めて有効） | ⚠️ 不完全 | 未対応 |
| PG-M6 | **`information_schema.role_table_grants` はカレントロール依存**で、CI が非特権ロールで走ると**静かに合格する**（実測：r_app からは r_other への付与が見えない）。**代替＝`pg_class.relacl` ＋ `aclexplode()`**。列単位権限・`pg_default_acl`・PUBLIC 付与・関数/シーケンス権限は別カタログ | ⚠️ 偽の合格 | 未対応 |
| PG-M7 | **パーティション子があるため検査1／3／6は素朴に実装すると成立しない**。素朴走査＝毎月増える子が全部違反として出る（ノイズで検査が無効化）／`relispartition` 除外＝**PG-H6 の実際の穴が検査から消える**。検査3「1本以上」では **PG-H3/H4（所有者に permissive が無い）を検出できない** | ⚠️ 条件付き | 未対応 |
| PG-M8 | **KNN + RLS の増幅がテナント数に比例する**。`ORDER BY geom <-> p LIMIT 20` は自テナントの20件が揃うまで索引を走り続ける。20テナントで276行をRLS除外。明示的な `tenant_id` 等値で複合GiSTを選択し、増幅を回避できた | ⚠️ **実測確認** | **設計処置・本番規模再測待ち** |
| PG-M9 | **監査の append-only が BYPASSRLS の「範囲」を誤解している**。「audit_writer は INSERT のみだから権限集中を抑えられる」は不正確——**BYPASSRLS はそのロールに切り替わっている間、全テーブルの RLS を迂回する**（テーブル権限とは独立）。BYPASSRLS 所有の SECURITY DEFINER 関数は**関数内の全ての文が RLS を迂回**する | ⚠️ 理由付けが不正確 | 未対応 |

## Low（表現の不正確・バージョン注記）

| ID | 表題 | ステータス |
|---|---|---|
| PG-L1 | `NULL = ANY(array)` は「偽」ではなく **NULL**（結論は同じだが3値論理を跨ぐ合成で前提が崩れる）。ADR-0001 の「NULL（偽扱い）」が最も正確 | 未対応 |
| PG-L2 | 「パーティションローカル一意でしかない」は不正確——**パーティションキー列を含む一意索引は表全体で一意**が保証される（制約は「キー定義の範囲」の問題）。結論（決定的抽出が必須）は正しい | 未対応 |
| PG-L3 | **CHECK 制約の IMMUTABLE 性は PostgreSQL が強制しない**（VOLATILE 関数を含む CHECK が作成できる）。「設計規約として守る」と書くのが正確 | 未対応 |
| PG-L4 | **UUIDv7 のバージョン依存が未記載**。組込み `uuidv7()` は **PG18 以降**（要確認）。PG16/17 は自作関数が必須で、**CHECK 制約が自作関数に依存**するため PG18 で差し替えるには制約の DROP/ADD（大規模テーブルの全件再検証）が要る | 未対応 |
| PG-L5 | `location_log` の追加 UNIQUE 索引は PK と**同一列集合**で不要（高頻度追記テーブルに純損）。`ON CONFLICT` の列順が2箇所で違う | 未対応 |
| PG-L6 | トランザクションプーリングの制約記述が現状より厳しい（**PgBouncer 1.21 以降は prepared statements をサポート**＝要確認）。**プーラの製品・最低版が未指定** | 未対応 |
| PG-L7 | `current_user` 判定は `SET ROLE` で変わる。**`auth_role` のメンバーである任意のロールが `SET ROLE auth_role` でブートストラップ例外を得る**→「`auth_role` をどのロールにも `GRANT` しないこと」を検査に追加 | 未対応 |
| PG-L8 | `pg_policies.qual` の正規化についての ADR-0001 の指摘は**正しい**（実測：`::text` 付与・括弧2重化）。ただし **`pg_policies` は非特権ロールからも全件読める**——どのテーブルに例外があるかが一般ロールに開示される | 未対応 |
| PG-L9 | 「permissive が1本も無いテーブルは誰にも0行」は正しいが**コマンドで現れ方が違う**：SELECT/UPDATE/DELETE は**静かに0行**、INSERT は**エラー**。検査の期待値をコマンド別に分ける必要 | 未対応 |
| PG-L10 | `ST_Area(geography)` 生成列は成立（✅）。ただし①既定 `use_spheroid=true` で投影面積と値が異なる（§6 の「参考・検証用」限定は妥当）、②**PostGIS メジャーアップグレードで生成列/CHECK/索引の依存が阻害され得る**（要確認） | 未対応 |
| PG-L11 | `agro_chemical_effective` の `DISTINCT ON` 構文自体は成立（✅）。`*` を使うため列追加で `CREATE OR REPLACE VIEW` が失敗しやすい | 未対応 |
| PG-L12 | **§6 の DDL には RLS 有効化が `field` にしか書かれていない**（`work_actor`／`pesticide_record`／`agro_chemical`／`location_log`／`field_history`／`labor_summary`／`audit_log` に `ENABLE/FORCE ROW LEVEL SECURITY` が無い）。検査6と正面から食い違う状態 | 未対応 |
| PG-L13 | **`labor_summary.corrects` の自己 FK は RLS を迂回する**（FK 検査は RLS をバイパス）。**他テナントの原本を指す訂正レコードを作れる**。ADR-0001 A1e-L1「FKは原則同一テナント内」の適用漏れ | 未対応 |

---

## 実測で「✅ 正しい」と確認された主張（20項目）

restrictive は AND・permissive は OR ／ permissive 0本＝0行 ／ `TO` なしは `{public}` ／ FORCE RLS は所有者バイパスを止める ／ SECURITY DEFINER 内の RLS 判定は関数所有者 ／ BYPASSRLS は RLS を迂回 ／ 生成列はパーティションキーに使えない ／ BEFORE ROW トリガはタプルルーティングの後 ／ 行トリガは SELECT を捕捉できない ／ COPY は行トリガを発火する ／ FK 検査は RLS をバイパス ／ 全パーティションキー列を PK に含める必要 ／ ③は部分一意索引で表現できる ／ `gist(tenant_id, geom)` には btree_gist が必要 ／ `uuidv7_time` は IMMUTABLE で ms 精度一致 ／ `event_ts` レンジのプルーニングは成立 ／ 各カタログで判定できる（限界あり） ／ `pg_policies.qual` は正規化される ／ `SET LOCAL` はトランザクション境界・セッション `SET` はプールでリーク ／ restrictive で `WITH CHECK` 省略時は `USING` が流用される

> **この20項目が正しかったことは重要な情報である**——**単一オブジェクト・単一機能の性質についての記述はほぼ全て正確**だった。誤りは後述のとおり**「包含関係を跨いだときに規則が自動で効くと仮定した」場所に集中**している。

---

## スパイクSQL と 文書化された設計の差異（15件）

**結論：スパイクは文書 §4/§6 とは別のポリシー・別のDDLで走っている。「全PASS」は文書の設計を検証していない。**

| # | 項目 | 文書（§4/§6） | スパイク | 影響 |
|---|---|---|---|---|
| 1 | restrictive 述語の実装形 | `current_setting(...)::uuid[]` をインライン | `allowed_tenants()` = `string_to_array(...)` の **STABLE SQL 関数** | **別物**。文書形は空/不正で例外（PG-H1）。関数ラップは検査4/5を無効化（PG-M4） |
| 2 | 注入値のフォーマット | 未記載（インラインは `{...}` を要求） | **カンマ区切り** | 文書の述語＋この値は**必ずエラー**（PG-H2） |
| 3 | **基本 permissive**（v7 で必須と決定） | `FOR ALL TO app_role, auth_role USING(true)` | **存在しない** | **一度も実行されていない**。実装すると PG-H3/H4 が発生（スパイクが `TO` を付けなかったから通った） |
| 4 | `TO` 句 | restrictive は無し／permissive は列挙 | **両方 `TO` なし（全て PUBLIC）** | v7 の `TO` 原則は未検証 |
| 5 | restrictive の述語形 | **5種** | **(a) と (c) の2種のみ**。(b)(e)(f) は**1本も未実装** | 本人起点横断・③横断・ブートストラップは**全て未検証** |
| 6 | 書込ポリシー | USING と WITH CHECK の両方を定義 | S1 は permissive 側に書込境界／S2・S4 は **USING のみ** | 合成結果が違う。S4 の形は形(e) で共有汚染（PG-M3） |
| 7 | UPDATE/DELETE | ①は更新あり | **ポリシーが1本も無い**（＝静かに全拒否） | ソフトデリート・楽観ロックの経路が未検証 |
| 8 | `field` の DDL | MultiPolygon・`deleted_at`・`version`・面積権威列 | Polygon・**ソフトデリート列/version/面積列なし** | `deleted_at IS NULL` を含むクエリのプランは未測定 |
| 9 | `location_log` の索引 | PK ＋ 追加 UNIQUE 索引 | PK のみ | 追記性能に追加索引のコストが入っていない |
| 10 | RLS 有効化のタイミング | 常時有効 | S2/S4 は **seed 後に ENABLE** | 所有者による一括投入が FORCE RLS 下で通るか未検証（実際は通らない＝PG-H3） |
| 11 | 未登場テーブル | membership／labor_summary／work_actor／pesticide_record／agro_chemical＋解決ビュー／*_history／audit_log | **すべて未登場** | 監査トリガ・ハッシュチェーン・③解決ビュー・ブートストラップ・本人起点横断は**すべて未実行** |
| 12 | セキュリティバリア関数 | ①-a のスコープ解決は SECURITY DEFINER のセキュリティバリア関数 | 比較対象は「非正規化列 vs **素のサブクエリ**」。**バリア関数は未実装** | §7 の「R4-L2 解決」は片方の案しか測っていない（§7 と §8 が不整合） |
| 13 | 検証成果物 | §7 に要約数値のみ | **EXPLAIN 出力・実行ログが未保存** | 「Index Cond に何が入ったか」判定不能→PG-M1/M2 の判定が今できない |
| 14 | FORCE RLS の伝播 | 「S1 で確認」 | **親経由のみ試験**。子直接参照の試験なし | 実際は非伝播（PG-H6）。**S1 は「確認」していない** |
| 15 | ポリシー用関数 | 記述なし | スパイクごとに別名で再定義 | 本番の関数名・所有者・volatility・`search_path` 固定の規約が未定 |

---

## 文書が「検査する」と述べている項目のうち、カタログから判定できないもの

| 検査項目 | 判定可否 | 代替 |
|---|---|---|
| 検査1「`tenant_id` 列を持つ全テーブルに RESTRICTIVE」 | △ | パーティション子／`*_history`／ビューの扱いが未定義。**ビューは `pg_policies` の対象外なので PG-H4 が検査で全く見えない** |
| 検査2「RESTRICTIVE に `TO` が無い」 | ✅ **判定可** | — |
| 検査3「①③に PERMISSIVE が1本以上」 | △ | 「1本以上」では **所有者・トリガ主体に permissive が無い状態（PG-H3/H4）を検出できない**。`pg_policies.roles` × `pg_has_role` × `cmd` で**主体別**に判定 |
| 検査4「述語に `current_user` が現れる RESTRICTIVE が membership のみ」 | ❌ **判定不能** | 関数ラップで偽陰性。**`pg_policy`→`pg_depend`→`pg_proc` を辿る**＋関数のホワイトリスト化 |
| 検査5「`app.user_id` を参照する RESTRICTIVE が labor_summary のみ」 | ❌ **同上** | 同上 |
| 「述語形が5種のいずれか」 | ❌ **判定不能** | 正規化テキスト比較を ADR 自身が禁じており、関数ラップで内容も消える |
| 検査6「FORCE RLS 未設定の業務テーブルなし」 | △ | 階層対応が必要（子は `f` が正常。除外すると PG-H6 が検査外に） |
| 検査7-a「`rolbypassrls` が一覧に限る」 | △ | **`rolsuper` も見る必要**。「SET ROLE できるメンバー」「BYPASSRLS 所有の SECURITY DEFINER 関数」も列挙対象 |
| 検査7-b「`auth_role` がいかなるテーブルにも権限を持たない」 | ⚠️ **判定可だが設計と矛盾** | 満たすと PG-H5 で認証が0行 |
| 「`priv` のオブジェクトに通常ロールの権限が1件も無い」 | ❌ **`role_table_grants` では判定不能** | **`pg_class.relacl` ＋ `aclexplode()`**。列単位/`pg_default_acl`/PUBLIC/関数権限は別カタログ |
| （**文書に無いが必要**）RLS 有効な表を参照するビューが `security_invoker` であること | — | PG-H4 の再発防止に必須。`pg_class.reloptions`（PG15+） |
| （**文書に無いが必要**）パーティション子に通常ロールの権限が無いこと | — | PG-H6 の再発防止に必須 |
| （**文書に無いが必要**）restrictive に WITH CHECK が明示されていること | — | PG-M3 の再発防止に必須 |

---

## バージョン前提の欠落

**最低要求 PostgreSQL バージョンが、どのADRにも設計書にも書かれていない**（言及は §7 と `spikes/README.md` の「実行環境＝PG16＋PostGIS 3.4」だけ。ADR-0004 §5 は「バージョン方針は ADR-0019」と先送り）。

| 機能 | 必要版 | 影響する記述 |
|---|---|---|
| `CREATE VIEW … WITH (security_invoker = true)` | **PG15 以降** | §6 解決ビュー（PG-H4）。**これが実質的な最低要求版を PG15 に固定する** |
| 組込み `uuidv7()` / `uuid_extract_timestamp()` | **PG18 以降**（要確認） | §5/§6（PG-L4）。PG16/17 は自作関数必須で CHECK がそれに依存 |
| パーティション親への BEFORE ROW トリガ | PG13 以降 | §5 の「BEFOREはルーティング後」という結論の前提 |
| パーティション表への UNIQUE 索引 | PG11 以降 | §5／§6 |
| `gen_random_uuid()` 組込み | PG13 以降 | §6 |
| PgBouncer のトランザクションプーリング＋prepared statements | PgBouncer **1.21 以降** | ADR-0001（PG-L6）。**プーラの製品・版が未指定** |
| PostGIS 関数の volatility / leakproof | PostGIS 版依存 | §6 生成列（PG-L10）、S2 の索引利用（PG-M2） |

---

## 2026-08-14 追試（AuthContext DB検証・PostgreSQL 16.4）

ADR-0009が要求する「候補集合をDB側で現在権限へ再照合してから`SET LOCAL`する」境界を、[`S8_auth_context.sql`](../../spikes/S8_auth_context.sql)で実DDL化した。全文ログは[`S8_2026-08-14_PG16.log`](../../spikes/results/S8_2026-08-14_PG16.log)、共通ロール変更後のS1回帰は[`S1_2026-08-14_PG16.log`](../../spikes/results/S1_2026-08-14_PG16.log)。

- active membership、role由来capability、membership scope、activeな親子tenant、双方向確認済み雇用関係の包含だけを1行で返し、不正・失効時は0行とした。
- role外capability、scope外、revoked membership／tenant関係、片側確認だけの雇用関係、空／重複集合を拒否した。
- 権限基表は`priv`へ置き、`app_user`から直接参照不可。公開するのは`app_private.validate_auth_context`の`EXECUTE`だけとした。
- 関数所有者`auth_context_owner`はNOLOGIN・非superuser・非BYPASSRLS、関数は`SECURITY DEFINER`＋固定`search_path`で、所有者ロールをログインロールへ委譲しないことをカタログ検査した。
- 共通ロール定義から`bootstrap_owner`／`auth_context_owner`／`audit_writer`のログインロールへの明示GRANTを除去してS1を再実行し、RLS、ブートストラップ、版履歴、監査経路に退行がないことを確認した。

**限定**：S8の権限基表はADR-0005 v8で採用した参照物理形である。本番migrationへの昇格時に、命名・版管理・失効イベント・索引・backfillを追加し、同じ12群を回帰テストとして実行する。

## 2026-08-14 最終処置監査（ADR-0001 v19）

- **PG-H2**：配列はBFFで`$n::uuid[]::text`／`text[]::text`へ正規化し、S1もPostgreSQL配列リテラルを使用。文書と実行値を統一した。
- **PG-H3／M3〜M7**：正当な全主体をpermissiveの`TO`へ含め、WITH CHECK、superuser／BYPASSRLS、ACL、`part`、ビュー依存をカタログ検査。S1 (13)〜(15)で主要経路を実測した。
- **PG-M1／M2／M8**：単一tenant SQLは検証済み`tenant_id`等値を冗長に明示する。空間は複合GiST、複数tenant KNNはtenant別候補をマージし、非leakproof演算子をラップしない。本番規模再測だけを性能受入へ残す。
- **PG-M9**：BYPASSRLSの射程を「対象表だけ」と誤解しない。`audit_writer`／`audit_reader`をNOLOGIN・非委譲・限定ACL・固定関数で分離し、越境readを再監査する。
- **PG-L13**：`labor_summary.corrects`を`(tenant_id, corrects)`複合FKへ変更し、他tenantの訂正元を参照できない形にした。
- その他のLowは、NULLの3値論理、UUIDv7／PgBouncer版、明示列ビュー、受理台帳、コマンド別期待値、PostGIS upgrade注意として規範文書または後続運用へ反映した。

**判定：ADR-0001 v19に対するPostgreSQL実挙動上の残存High 0／Medium 0。**

---

## 2026-08-13 追試（PostgreSQL 16.4・PostGIS 3.4.3・arm64）

使い捨てDocker環境で S1／S2 を再実行した。全文は [`S1_2026-08-13_PG16.log`](../../spikes/results/S1_2026-08-13_PG16.log)／[`S2_2026-08-13_PG16_PostGIS.log`](../../spikes/results/S2_2026-08-13_PG16_PostGIS.log)。

- **PG-H4**：`security_invoker` ビューが呼出元RLSで評価され、テナントAにはAの上書き、Bには共有マスタが見えることを確認。S1 (14) はPASS。
- **PG-H3**：S1 (15) で、`app_owner` 所有の版履歴トリガが FORCE RLS 下で更新前スナップショットを追記すること、注入無しの所有者が本体・履歴・監査を迂回できないことを確認。監査は `audit_writer`（NOLOGIN・BYPASSRLS・INSERT-only）所有の固定search_path関数から対象行由来tenantへ2件追記し、通常ロールからの直接参照・関数実行を拒否した。チェーン直列化と並行性能はS5に残る。
- **PG-M2**：`&&`／`<->` の実体関数はともに `proleakproof = false`。bboxはRLSのみで7.502ms、明示的な `tenant_id` 等値付きで3.335msだったが、どちらも空間 `&&` は `Filter` に残り、1テナント5,000行のうち1,546行を除外した。**性能値はSLO内でも、意図した複合索引の構造的合格基準には未達**。
- **PG-M8**：RLSのみのKNNは `geom` 単独GiSTを使い、20行を得るまで他テナント276行を除外（1.233ms）。明示的な `tenant_id` 等値付きでは複合GiSTを使い0.137msとなり、読み捨ては発生しなかった。
- **判定の限界**：10万行・単一接続の合成データでは全ケースが地図2秒SLO内だが、本番規模・並行負荷への外挿はしない。bboxの緩和策、KNNのクエリ規約、再測条件を設計で確定する必要がある。

---

## 所見

### 誤りの傾向：「包含関係を跨いだときに規則が自動で効く」という仮定

**単一オブジェクト・単一機能の性質についての記述は20項目すべて正確**だった。誤りはすべて**包含関係を跨ぐ場所**に集中している：

- 親テーブル → **パーティション子**（PG-H6：RLS/FORCE RLS/ポリシーは伝播しない）
- テーブル → **ビュー**（PG-H4：RLS の評価主体がビュー所有者に変わる）
- セッション → **トランザクション**（PG-H1：`SET LOCAL` の reset 値は NULL ではなく `''`）
- ポリシー → **実行プラン**（PG-M1：STABLE述語ではハッシュプルーニングが起きない／PG-M2：非leakproof述語は索引条件になれない）
- ポリシー述語 → **カタログ表現**（PG-M4：関数に切り出すと検査から消える）
- ロール属性 → **メンバーシップ**（PG-M5：BYPASSRLS/SUPERUSER は継承されない）
- USING → **WITH CHECK**（PG-M3：省略すると流用される）

**そしてスパイクは常に「一番内側の1オブジェクト・1経路」しか試験していない**（親経由のみ／読取のみ／述語形(a)(c)のみ／seed は RLS 有効化前／ビューなし／トリガなし）ため、**この仮定が一度も反証されなかった**。

### 再実行時に足すべき試験項目（優先度順）

1. **子テーブル直接参照の拒否**
2. **述語形 (b)(e)(f) の実装と横断の成立**
3. **基本 permissive `TO` 付きでの所有者・トリガ経路**（版履歴・監査）
4. **`security_invoker` ビュー経由の③解決**
5. **注入漏れ時の挙動を、`SET LOCAL` を使った接続の「2回目のトランザクション」で確認**
6. **EXPLAIN 全文の保存**

---

## この検証から得た運用ルール（開発工程 教訓15）

> **PostgreSQL の挙動に依存する設計は、「単一機能の性質」と「包含関係を跨いだときの挙動」を別に検証する。** 前者は正確に書けていても、後者は「自動で効く」と仮定されがちである。**スパイクは最も内側の経路だけを通しても PASS するので、包含の外側（子テーブル・ビュー・プール返却後・プラン時・カタログ表現・ロール継承）を明示的に試験項目に含める。**
>
> **あわせて：スパイクSQLと文書のポリシー/DDL本文は同一でなければならない。** 別物であれば PASS は設計を検証していない。**検証成果物（EXPLAIN 全文・実行ログ）をリポジトリに保存する**（要約数値だけでは再現性がない）。
