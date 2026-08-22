# KSAS比較 ISASシステム・文書 敵対的レビュー記録票

| 項目 | 内容 |
|---|---|
| 版 | **全面再レビュー版 v2** |
| レビュー日 | 2026-08-22 |
| ISAS基準commit | `a15128c`（ADR-0024 Docker段階的撤去 R0完了） |
| レビュー対象 | 要求仕様、ADR-0010〜0013／0019〜0024、Web／BFF、DB migration、3 OS Production profile、local-integration、CI／release、運用・利用者文書、製品状態台帳、品質・復旧証跡 |
| 比較対象 | クボタ営農支援システムKSASの公式機能、FAQ、2026年版manual、Developers、service desk、会員規約 |
| 最上位の前提 | ISASはmacOS／Linux／FreeBSDを同格のProduction host対象とし、AWSは任意adapterとする。Docker／Compose／OCI runtimeは製品要件ではなく段階的に撤去する |
| 手法 | 公開機能差分、正本間の矛盾探索、実装・自動検査・実環境証跡の三点照合、導入失敗／権限侵害／復旧不能／競争力誤認の反対仮説 |
| 総合判定 | **BLOCKED**。業務coreと3 OS native構成の実装量は増えたが、native配布物、実host受入、証跡真正性、実利用者・実data・DRが未成立。KSASから業務正本を切り替えられる状態ではない |
| 現行指摘集計 | **High 5件、Medium 8件、Low 2件**。Highは全件未クローズ |
| 次回再確認 | 各Production候補release review時、または遅くとも2026-11-22 |

---

## 1. 旧レビュー票をそのまま更新しない理由

2026-08-17版は、当時欠落していたFreeBSD Jail、macOS launchd、Linux systemdのProduction profileを中心に評価していた。その後、3 OSのprofile、install／backup／restore／監視script、host validatorが追加され、さらにADR-0024でDocker撤去が決定された。このため、旧票に処置追記を重ねると次の誤認が残る。

1. FreeBSD／macOS／Linuxの構成定義が存在しない、という旧仮説は失効した。
2. Docker／OCI imageをcross-host artifactの目標とする旧工程は、現在の方向性と逆である。
3. 静的なOS分岐確認と、実hostでのProduction受入が同じ「対応済」に見える。
4. 2026年版KSAS manualに掲載されたKSASシンプルコネクト、Marketplace、AIチャット、病害虫・雑草AI診断、生育ステージ予測等が旧比較表に十分反映されていない。

したがって、本票は旧IDの続票ではなく、基準commitを固定した新しいレビューである。旧票の履歴はGit履歴で保持し、現行状態の判定には本票だけを使う。

## 2. 証拠の読み方

### 2.1 ISASの状態語

| 状態 | 本票での意味 |
|---|---|
| 設計済み | 要求、ADR、runbookまたはinterfaceがある |
| 静的実装済み | code／script／設定と自動検査があるが、対象OS・実data・実依存で動かした証拠がない |
| 統合検証済み | 指定された非本番環境で実componentを接続した証拠がある。Production合格ではない |
| Production受入済み | 対象host、署名artifact、実依存、復旧、security、SLO、実利用者を含む証跡と二人承認がある |

`implemented`を「Productionで利用可能」と読み替えない。repositoryに存在する3 OS profileは**静的実装済み**であり、各`profile.json`自身が`BLOCKED`を宣言している。

### 2.2 KSAS側の証拠限界

KSASの公開画面・manual・FAQからは提供機能、利用条件、料金、公開support窓口を確認できる。一方、内部architecture、RLS、MFA、暗号鍵、backup方式、SLO実績、脆弱性、障害履歴は確認できない。非公開事項を「KSASにない」と断定せず、ISASの詳細設計があるだけで優越とも判定しない。

## 3. 現在の製品比較

| 比較軸 | ISAS `a15128c` | KSAS公式公開情報（2026-08-22確認） | 敵対的判定 |
|---|---|---|---|
| 圃場GIS | MapLibre＋PostGIS、地理院背景、担当scope、eMAFF GeoJSON／境界CSV取込を実装 | Google Mapを用いた圃場、住所、面積、所有者管理をPC／smartphoneへ提供 | ISAS coreは実装済み。ただし実農地3,000枚、実地図成果物、現地操作性は未受入 |
| 作業指示・日誌 | 指示、担当、打刻補完、写真、template、前回値、訂正・差戻しを実装 | 指示から日誌化、絞込、振返り、Excel出力、対応農機の自動日誌を提供 | 手入力coreは比較可能。自動化と実利用者時間では未証明 |
| 作付・工程 | 作期、作付、高度Gantt、依存、進捗、resource競合を実装 | 作付計画、資材費simulation、作業進捗、2026年manualでは作業計画体験版を掲載 | ISASはcodeを完成表示できない。PG16 native gateと実利用者受入が残る |
| 農薬・在庫 | 鮮度、安全check、lot、期限、棚卸、調整event、JGAP CSVを実装 | 農薬DB、在庫、各種台帳、Excel出力を提供。FAQはFAMIC DBを月1回程度更新と説明 | ISASは法令masterの実更新責任者と実帳票照合が未成立 |
| Offline／mobile | cache／outbox／競合／失効／offline mapを実装。実端末gateは未完 | smartphoneで圃場、日誌、進捗等を提供。FAQでは作付計画と圃場詳細設定にPCが必要 | ISASの差別化候補だが、iOS／Android実機と共有端末試験前には優位を主張しない |
| 分析 | tenant内の計画対実績、収量、時間、資材、鮮度／coverageを実装 | 食味・収量、作業時間、可変施肥、生育関連機能を公開 | ISASは実農機／sensor sourceがなく、分析値の実用性を未証明 |
| 農機・drone | ADR、受入契約、Phase 3計画。実connectorなし | 自動日誌、KSASシンプルコネクト、drone、食味・収量、可変施肥、機械monitoringを提供 | **最大の機能差**。設計書を接続済み機能として数えない |
| 周辺ecosystem | remote sensing／診断はplanned、水管理／乾燥調製はout-of-scope | 水管理、乾燥調製、remote sensing、土壌診断、生育予測、AI診断、Marketplace、AIチャットをmanualへ掲載 | 全模倣は不要。ただし採否未分類の機能をcatalogへ反映しないと比較が再び陳腐化する |
| 外部API | ADR-0013と最小受入契約のみ。実client／公開service受入なし | KSAS Developersを公開 | ISASはAPI提供中と表示不可 |
| 移行 | 圃場→作業→農薬CSV、重複検査、eMAFF境界取込を実装 | FAQでは他systemからKSASへのdata移行は不可 | ISASの差別化候補。ただし実顧客CSV、添付、完全restoreは未証明 |
| 出力・離脱 | 個別CSVとfull export仕様があるが、full export／再import実装なし | 圃場情報、日誌、作付、食味・収量等をExcel出力 | ISASのdata control主張には空ISASへの完全restoreが必要 |
| Support | guide、runbook、RACI schema、severity定義を実装。実配備の担当者・窓口・契約は未登録 | 平日service deskと24時間AIチャットを公開 | self-host運用者が不在なら、機能があってもKSAS代替にならない |
| 費用 | TCO仕様と計算器だけ。実見積なし | 100圃場以下無料、有料plan税込2,200円／月を公開 | OSS licenseだけで安価と主張することを禁止する |
| Security／privacy | RLS、MFA、失効、監査、位置同意を設計・実装 | 公開資料だけでは内部方式を比較不能 | ISAS自身の実環境gateで評価し、KSASへの根拠なき優越主張をしない |

## 4. 現行の敵対的指摘

| ID | 重要度 | 区分 | 攻撃仮説／確認事実 | 要求処置 | 状態 |
|---|---|---|---|---|---|
| KCOMP2-H1 | High | 配布・3 OS | 3 OSのinstall scriptは`database`、`identity`、`object-queue`、`app`、`edge`、`telemetry`のnative packageを要求するが、repositoryにそれらを生成するpipelineと成果物がない。現行`build-release.yml`はAWS ECRへ`bff`／`web`／`migration`のOCI imageだけをbuildする。静的profileを実装済みと呼んでもempty hostでは導入できない | ADR-0024 R3として、macOS arm64／amd64、Linux x86_64／aarch64、FreeBSD amd64／arm64のnative artifact build、6 serviceの構成、署名、SBOM、provenance、checksum、保管、install検証を実装する。AWS KMS／ECRを共通必須にしない | **未処置** |
| KCOMP2-H2 | High | Release security | release validatorは`artifact://`／`https://`／`s3://`形式の文字列と任意のactor名を受理し、証跡本文のdigest、署名、発行主体、承認者identityを検証しない。tag script単体も4証跡の再検証を強制しない。書込権限を得た者が整合するJSONを作れば、二人承認や24時間監視を偽装できる | 全証跡をcontent digestと署名attestationへ束縛し、承認者をIdP／GitHub Environmentの検証済みidentityへ結ぶ。tag発行入口自身がrelease／build／delivery／bakeの検証済みdigestを要求し、保護tag rulesetと監査eventを確認する | **未処置** |
| KCOMP2-H3 | High | Host受入 | FreeBSD Jail、macOS launchd、Linux systemdの定義は存在するが、実hostのinstall、reboot、upgrade、rollback、PITR、全損restore、E2E、SLO証跡が0件で、acceptanceはplaceholder exampleだけである。OS分岐確認をProduction対応と表示すると復旧不能な配備を販売できる | 各OSを実host 2 failure domainで受入し、同一source／migration／fixtureで12 gate、二人承認、artifact digestを記録する。完了までは状態を`静的実装済み／Production BLOCKED`に統一する | **実装済み・実受入待ち** |
| KCOMP2-H4 | High | 業務正本化 | 実CSV rehearsal、実作業員・高齢作業員・技能実習生UT、iOS／Android、手動WCAG、独立penetration、月次restore／四半期DR、24時間監視が未実施である。local testのPASSをKSASからの切替根拠にすると、現場入力・offline・復旧で初めて欠陥が発覚する | 選択hostの隔離Stagingで実data、実端末、実利用者、security、SLO、復旧を実施し、release manifestへ同一commitの証跡を登録する | **未処置** |
| KCOMP2-H5 | High | 製品表示 | KSASは農機自動日誌、シンプルコネクト、drone、食味・収量、可変施肥、機械monitoring等を提供するが、ISASの農機連携はplannedである。「KSAS代替」「KSAS同等」と無限定に表示すると、手入力負担と分析sourceの差を隠す | 対外scopeを「圃場・指示・日誌・農薬・在庫のself-host／offline core」に限定する。KSAS同等表示は、契約済み1 connectorを実sample・実機で縦切り受入し、未提供ecosystemを明示した後だけ再審査する | **未処置** |
| KCOMP2-M1 | Medium | Docker撤去 | ADR-0024は採用済みだがR0のみ完了し、local-integration、DB spike、CI、任意AWS adapterの4群がactive transitionalのままである。daemon障害が開発・検証を再び停止できる | R1 native PG/PostGIS runner→R2 native local-integration→R3 native CI→R4 AWS adapter→R5物理削除の順で、代替合格後に旧経路を削除する | **着手済み（R0完了、R1〜R5未処置）** |
| KCOMP2-M2 | Medium | 製品状態台帳 | `capability-catalog.json`は2026-08-17時点の9機能だけで、eMAFF取込、管理者security、CSV、作付／在庫高度化、位置・分析、3 OS profile、Docker撤去、KSAS 2026追加機能を網羅しない。validatorも`asOf`鮮度、evidence path存在、状態根拠を検査しない | capabilityを業務・platform・運用に分けて網羅し、`designed／static-implemented／integration-validated／production-authorized`を表現する。確認期限とevidence digestをCI検査する | **未処置** |
| KCOMP2-M3 | Medium | API・農機 | 外部APIと初回農機connectorは受入契約までで、実client、service identity、sandbox、実sample、rate／失効、supportがない | 最小read-only APIと1実connectorを別incrementで実装し、tenant／scope、同意、再送、単位、停止時継続、監査を実証する | **設計済み・未実装** |
| KCOMP2-M4 | Medium | GIS・offline map | eMAFF importとoffline map codeはあるが、実日本PMTiles／NOTICE／SBOM、端末容量、地理院停止時の縮退、実農地境界精度を実環境で受入していない | 選択hostで実成果物を署名・配布し、担当scope、Range再認可、容量上限、失効、地図停止、現地境界照合を実機確認する | **実装済み・実受入待ち** |
| KCOMP2-M5 | Medium | Capacity | 3,000圃場benchmarkは仕様だけで、3 OSの同一fixture結果がない。KSASの推奨件数をISASの処理能力として引用できない | 複雑polygon、履歴、写真、同時20／50／100利用者を含む実測を3 OS別に公開し、ISAS SLOへの合否だけを判定する | **未処置** |
| KCOMP2-M6 | Medium | TCO | 3年TCO計算器はあるが9組合せが仮値で、host、予備機、電力、backup、IdP、監視、保守者、incident、停止costを比較できない | macOS／Linux／FreeBSD×100／1,000／3,000圃場の実見積、要員、更新頻度、停止costを承認する | **未処置** |
| KCOMP2-M7 | Medium | Portability | full export仕様はあるがexport／import code、attachment、監査chain、別の空ISASへのrestore、削除証明がない | snapshot manifest、全dataset／object、hash、再import、RLS、監査chain、二人承認付き削除証明を実装・実証する | **未処置** |
| KCOMP2-M8 | Medium | Support | 運用台帳schemaと起動時検査はあるが、実配備のservice owner、on-call、security／privacy窓口、保守時間、EOL、契約、費用の承認証跡がない | Production候補ごとに実名ではなく検証可能な組織identity／連絡経路を登録し、訓練・引継ぎ・不在時escalationを演習する | **実装済み・実運用受入待ち** |
| KCOMP2-L1 | Low | 文書整合 | 運用文書の一部にAWS固有commandが一般Production手順のように残り、`v<version>`と`production/v<version>`の表記揺れもある | host-neutral入口から選択adapterへ分岐させ、tag namespace、artifact語、Docker移行表示を全文scanで統一する | **未処置** |
| KCOMP2-L2 | Low | 比較鮮度 | KSASは2026-07-22にも更新され、manual掲載機能と料金・規約は変化する。Markdownの確認日だけでは期限超過をCIで検出できない | source URL、確認日、再確認日、確認範囲、hashまたはsnapshot IDを機械可読台帳にし、release reviewで期限切れを拒否する | **本票で暫定対応、CI未実装** |

## 5. High指摘の破壊経路

### 5.1 KCOMP2-H1：native host定義と配布物が接続されていない

3 OSのmanifestは6 serviceを列挙するが、release buildは3 OCI imageだけを生成する。名前、粒度、署名方式、registry、architectureのどれも一致しない。`install.sh`の静的検査がPASSしても、入力となるpackageを生成できないためempty host installは開始できない。これは単なる受入不足ではなく、buildからinstallまでの実装欠落である。

再レビューでは、各native packageについてsource commit、OS／architecture、dependency lock、SBOM、provenance、signature、install先、service entrypointを一つのbuild manifestへ固定し、実hostがそのmanifest以外を拒否することを確認する。

### 5.2 KCOMP2-H2：JSONの整合性は証拠の真正性ではない

現行validatorは日付、status、URI形式、actorの重複を検査している。しかし、実際の証拠本文を取得してhash／署名／発行者まで検査しない限り、値が実測由来とは証明できない。承認者も任意文字列であり、二人の実identityを保証しない。

Production tag発行では、CI Environment承認だけに暗黙依存せず、承認event ID、repository／workflow／run identity、OIDC subject、artifact／evidence digestを署名attestationへ含める。tag作成scriptを直接呼び出して検証を迂回できない権限設計も必要である。

### 5.3 KCOMP2-H3／H4：静的実装と現場受入を分離する

FreeBSD／macOS／Linuxの実装追加は前進であり、旧レビューの「何もない」という指摘は解消した。しかし、実行できるpackageと実host証跡がない現在は`Production対応済み`ではない。同様に、BFF／Webの多数の自動testは、実CSVの崩れ、高齢作業員の操作、端末storage eviction、圏外復帰、証明書更新、全損restoreを代替しない。

公開状態は依存する最も低い状態へ揃える。業務coreが自動test済みでも、hostと運用が未受入なら製品全体は`Production BLOCKED`である。

### 5.4 KCOMP2-H5：比較対象を「機能数」ではなく顧客jobで限定する

KSAS ecosystemを短期に模倣すると、ISASのself-host、offline、data control、移行、監査という主目的を損なう。比較上必要なのは全機能追随ではなく、対象顧客jobと非対象を正確に表示することである。

最初の競争力実証は、圃場・指示・日誌・農薬・在庫を圏外でも記録し、自組織が復旧・移行できることに置く。農機は、実際の顧客が使う1形式を取込→圃場照合→日誌候補→人の確定→監査まで通してから拡大する。

## 6. 旧指摘との対応関係

| 旧ID | 現在の扱い |
|---|---|
| KCOMP-H1 | 要求仕様で3 OS Production＋任意providerへ訂正済み。再発防止はADR-0024と本票前提で継続確認 |
| KCOMP-H2／H3／H4 | 3 OSの静的実装は完了。実host受入不足をKCOMP2-H3へ再定義 |
| KCOMP-H5 | KCOMP2-H5へ再定義。catalog追加だけでは実connector不足を閉じない |
| KCOMP-H6 | KCOMP2-H4へ再定義。Production BLOCKED表示は維持できている |
| KCOMP-M1 | KCOMP2-M3へ継続 |
| KCOMP-M2／M3／M4／M6 | 実装上の旧要求処置は反映済み。ただしcatalog鮮度・実運用はKCOMP2-M2／M8で再評価 |
| KCOMP-M5／M7／M8 | KCOMP2-M5／M6／M7へ継続 |
| KCOMP-L1 | 比較不能事項の区分を本票§2.2へ維持 |
| KCOMP-L2 | KCOMP2-L2へ継続 |

## 7. 推奨是正順序

| 順序 | 工程 | 閉じる主指摘 | 完了条件 |
|---:|---|---|---|
| 1 | 製品状態と比較表示の再同期 | H5、M2、L1、L2 | catalogが全機能・host・運用状態を網羅し、KSAS同等の無限定表示がない |
| 2 | Docker撤去R1 | M1 | native PG16＋PostGIS runnerでmigration／S1／S2／S5／S7／S8を再現しCompose spikeを削除 |
| 3 | Native supply chain | H1 | 3 OS×architectureの署名package、SBOM、provenance、build-once manifestを生成・install |
| 4 | Release evidence hardening | H2 | 署名証跡、検証済み承認identity、保護tag、迂回不能なtag発行を実証 |
| 5 | Docker撤去R2〜R5 | M1 | local、CI、AWS adapterをnative化しactive Docker依存0件 |
| 6 | 3 OS host受入 | H3、M4、M5 | 各OSの2 failure domain、全損restore、E2E、SLO、3,000圃場benchmarkを二人承認 |
| 7 | 実data・実利用者・DR | H4、M8 | 実CSV、実端末、実UT、WCAG、penetration、月次restore、四半期DRをPASS |
| 8 | Portability・TCO | M6、M7 | full export→空ISAS restore、削除証明、9組合せTCOを承認 |
| 9 | 最小API・農機縦切り | H5、M3 | 実clientと1実connectorを契約済みsample／実機で受入 |

## 8. 再レビュー提出物

1. 3 OS native build manifestと、実際に生成したpackage／signature／SBOM／provenance。
2. 各hostのinstall、reboot、start／stop、upgrade、rollback、backup、PITR、全損restore、security、E2E、SLO証跡。
3. content digestと署名に束縛されたrelease／delivery／bake証跡、検証済み二者承認、保護tag event。
4. Docker撤去台帳でR1〜R5が完了し、active dependencyが0件である検査結果。
5. 実CSV、実端末、実ユーザーUT、手動WCAG、独立penetration、PWA data保持結果。
6. 実日本PMTiles／NOTICE／SBOM、3,000圃場benchmark、実地図境界照合。
7. full exportから空ISASへのrestore、削除証明、3年TCO、実運用台帳。
8. 最初の外部API clientと農機connectorの契約・sample・再送・監査証跡。

## 9. 参照資料

### 9.1 ISAS正本

- [要求仕様書](../農業営農支援システム_要求仕様書.md)
- [開発工程](../開発工程.md)
- [ADR-0019 インフラ・運用](../design/ADR/ADR-0019-インフラ・運用.md)
- [ADR-0021 テスト・リリース](../design/ADR/ADR-0021-テスト・リリース方式.md)
- [ADR-0024 Docker段階的撤去](../design/ADR/ADR-0024-Docker段階的撤去.md)
- [Productionホスト共通契約](../operations/Productionホスト共通契約.md)
- [capability catalog](../product/capability-catalog.json)
- [host別reference benchmark仕様](host別reference-benchmark仕様.md)
- [vendor exit full export仕様](../operations/vendor-exit-full-export仕様.md)
- [Release一覧](../release/README.md)

### 9.2 KSAS公式公開資料

| 資料 | URL | 2026-08-22に確認した範囲 | 次回確認期限 |
|---|---|---|---|
| 機能紹介 | <https://agriculture.kubota.co.jp/ksas/function/> | 圃場、日誌、進捗、作付、帳票、農機、drone、収量、可変施肥、乾燥、monitoring | 2026-11-22 |
| FAQ | <https://agriculture.kubota.co.jp/ksas/faq/> | 移行不可、Excel出力、smartphone制約、3,000圃場、料金、農薬DB更新 | 2026-11-22 |
| 2026年manual一覧 | <https://agriculture.kubota.co.jp/ksas/member/03.html> | PC／smartphone機能、AI診断、remote sensing、Marketplace、AIチャット、生育予測 | 2026-11-22 |
| KSAS Developers | <https://developers.ksas.kubota.co.jp/> | API開発者サイトの公開 | 2026-11-22 |
| Service desk | <https://agriculture.kubota.co.jp/ksas/member/04.html> | 平日窓口、24時間AI chat | 2026-11-22 |
| 会員規約 | <https://agriculture.kubota.co.jp/ksas/terms/> | 機械・位置情報、料金、Marketplace、service変更条件 | 2026-11-22 |

## 10. 最終判定

| 判定対象 | 結果 |
|---|---|
| ISAS業務coreを隔離pilotで評価する | **条件付き可**。synthetic／複製data、非Production表示、手入力core、復旧可能な範囲に限定 |
| ISASをKSAS同等または全面代替と表示する | **不可**。農機ecosystem、support、実受入、native配布、APIに重大差がある |
| macOS／Linux／FreeBSDでProduction hostする | **要求上は対象、現状は全て不可**。静的profileはあるがnative配布物と実host受入がない |
| Docker非依存製品と表示する | **不可**。R0のみ完了し4依存群が移行中 |
| `v1.0.0`をProduction releaseとして扱う | **不可**。baselineでありProductionは`BLOCKED` |
| KSASから実データの業務正本を移す | **不可**。High 5件と実移行／復旧gateが未クローズ |

High 5件が全て閉じ、選択hostのProduction manifestが署名済み実証跡と二人承認を持つまで、KSASからの切替、Production tag、3 OS対応済みの対外表示を承認しない。
