# Phase 2〜4 実装計画

| 項目 | 内容 |
|---|---|
| 基準日 | 2026-08-16 |
| 状態 | **計画・architecture確定。業務機能は未実装** |
| 前提 | Phase 1 production承認とはrelease trainを分離し、未承認MVPをPhase 2機能で覆い隠さない |
| 関連ADR | [0012 農機](../design/ADR/ADR-0012-農機連携アーキテクチャ.md)、[0013 外部API／Webhook](../design/ADR/ADR-0013-外部API-Webhook.md)、[0014 DWH](../design/ADR/ADR-0014-横断分析-DWH.md)、[0015 国際化](../design/ADR/ADR-0015-国際化基盤-ICU-CLDR-UTC.md) |

## 1. 実施原則

- 各incrementを「migration＋RLS＋API＋Web／offline＋監査＋運用＋test」の縦切りで完成させる。画面だけ、DWHだけ、adapterだけを「機能完成」としない。
- Phase 1の現場記録、食品安全、失効、P0 pool、RPO／RTOを回帰gateとして維持する。後続batch／分析／AIはP2から始め、P0／P1を枯渇させない。
- 個人位置、労務、農機operator、外部送信、国またぎ分析はpurpose、同意／根拠、保持、削除、閲覧監査をdata modelより先に決める。
- 各phaseは独立release manifest、二者承認、段階配備、強化監視を持つ。前phaseの未完了gateを「次phaseで直す」として出荷しない。

## 2. 推奨実装順

### Phase 2：計画・工程・資材・tenant内分析

| 順序 | increment | 主成果物 | 完了条件 |
|---|---|---|---|
| 2.0 | 契約とmodel拡張 | 作期／作付計画、作業依存、resource、在庫policy、分析event、位置同意のdata model review | migration backfill／rollback、owner／FORCE RLS／監査、N/N-1互換をPG16でPASS |
| 2.1 | 作付計画＋高度ガント | 作期、作物・品種、面積、収量目標、作業template展開、依存関係、進捗、resource競合 | cycle禁止、timezone／暦日、offline閲覧、mobile作業listとの同一正本、500 task p95をPASS |
| 2.2 | 在庫高度化＋traceability | 発注点、入荷予定、棚卸しsession、lot／期限、評価、F-43履歴、JGAP項目CSV | 追記型残高を維持し、並行棚卸し・負在庫・単位換算・RLS・帳票照合をPASS |
| 2.3 | 位置ログと作業実績 | 多言語同意、打刻連動ON/OFF、短期track、在圃時間、本人表示、管理者の最小権限 | 同意拒否でも全業務成立、休憩中0点、期限削除、失効／端末紛失、閲覧監査を実機PASS |
| 2.4 | tenant内分析 | 計画対実績、収量、作業時間、資材、欠測／鮮度表示、CSV | DWHなしで動作、通貨混在禁止、projection再構築一致、dashboard p95をPASS |
| 2.5 | UX／i18n batch L1〜L2 | 高度template、onboarding、横断検索、dashboard、共通／計画画面辞書化 | 翻訳scan対象をL1〜L2で0、英語native review、RTL疑似locale、実ユーザーUTをPASS |

Phase 2では位置ログ収集と消費表示を2.3で同時提供し、消費先のないPIIを先行収集しない。横断DWHは2.4のtenant内dashboardと別物であり、Phase 2 releaseの必須依存にしない。

### Phase 3：連携・労務・最適化

| 順序 | increment | 主成果物 | 完了条件 |
|---|---|---|---|
| 3.0 | 農機adapter SDK | ADR-0012 canonical model、署名adapter manifest、隔離runner、file import、mapping queue | 2 source、10万観測、再送／再開、archive攻撃、tenant越境、provider停止をPASS |
| 3.1 | 初回実connector | 契約済みsampleによる国内補助API、cursor、token rotation、日次取込 | provider sandbox実証、license／保持／再配布台帳、欠落照合、運用runbook承認 |
| 3.2 | 外部REST API | service identity、scope registry、version、cursor、冪等write、rate limit | tenant／scope／失効／競合／P2飽和／契約testをPASS |
| 3.3 | Webhook | subscription管理、SSRF防御、署名outbox、retry、DLQ、replay | DNS rebindingを含むpenetration、重複・逆順、secret rotation、24時間retryをPASS |
| 3.4 | 労務 | Punch→LaborSummary、本人起点横断、休憩、締め、訂正、export | 業務内容を横断露出せず、個人／管理者scope、法域内complete fan-out、労務reviewをPASS |
| 3.5 | 最適化・AI提案 | 天候適期、resource制約、critical path、根拠付き提案 | 人が承認するadvisory限定、学習purpose分離、再現可能性、危険提案抑止、bias reviewをPASS |
| 3.6 | i18n batch L3〜L4 | 安全・在庫・管理・Privacy・連携画面の辞書化 | native＋領域二者review、原文fallback、RTL、screen readerをPASS |

### Phase 4：国別展開

Phase 4は「翻訳を追加すれば世界対応」と扱わず、**1対象国ずつ**次のonboarding pipelineを通す。

1. 法域、data residency、保持、privacy、労務、農薬、帳票、越境根拠をlegal／privacyが承認する。
2. 国別profileへ言語、timezone、暦、単位、通貨、農薬規制data、地図／tile、address、電話、GAP要件をversion固定する。
3. 対象言語全文翻訳、農業用語集、native＋領域review、正式RTL、font／入力方式、現地a11yを受入する。
4. 国別農機adapterと外部connectorは契約・sample・再配布条件があるものだけ有効化する。
5. 法域別IaC、IdP、KMS、object、telemetry、backup／DR、incident連絡を構築し、他国dataが0件であることを検証する。
6. 現地実CSV移行、現地作業員UT、性能／security／penetration、5%→25%→100%、24時間監視を完了する。

国またぎdashboardが必要な運用だけADR-0014の外部DWHを追加する。最初はPII-free集計、`k >= 10`、未知column拒否、非権威read-onlyとし、DWH未提供でも各国ISASを運用可能にする。

## 3. 各phase共通release gate

1. 要求ID→ADR→model→migration→API→UI→test→runbook→証跡のtraceabilityが100%。
2. High／Mediumの設計・security・privacy指摘0件。dependency／container／SBOM／署名／provenance PASS。
3. PostgreSQL 16＋PostGISでowner、FORCE RLS、trigger、audit chain、backfill、rollback、tenant越境を実証。
4. offline更新／未同期保持、権限失効、PWA更新、iOS／Android実機、WCAG 2.1 AAをPASS。
5. P0 99.9%／500ms、画面別SLO、優先度pool飽和、RPO 15分／RTO 4時間を維持。
6. 対象利用者UT成功率90%以上、SUS 75以上。安全・privacy・未同期状態の重大誤認0件。
7. staging実証、実data rehearsal、運用演習、二人承認、段階配備、24時間監視後にphase release tagを発行。

## 4. 直近の開始条件

Phase 2の設計・fixture作成はM6の外部受入と並行できるが、production releaseは分ける。最初の実装着手は`2.0 model拡張`、次に`2.1 作付計画＋高度ガント`を推奨する。農機・外部API・DWHを先行すると、計画・在庫・分析の正規modelが未確定なまま外部契約を固定するため順序を逆転させない。
