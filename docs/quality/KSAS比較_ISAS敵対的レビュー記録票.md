# KSAS比較 ISASシステム・文書 敵対的レビュー記録票

| 項目 | 内容 |
|---|---|
| レビュー日 | 2026-08-17 |
| レビュー対象 | ISAS Web／BFF／DB migration／Compose／IaC、要求仕様、ADR、運用・利用者文書、release・品質証跡 |
| 比較対象 | クボタ営農支援システムKSASの公式公開機能、FAQ、マニュアル、API開発者サイト、会員規約 |
| ユーザー確定要求 | ISASはmacOS、LinuxまたはFreeBSDのいずれでもProduction hostできること。この要求を既存ADR・IaC・運用文書より上位の正本としてレビューする |
| 訂正履歴 | 初版がAWS Production前提とMac非本番限定を所与として扱った判断を撤回。KCOMP-H1では要求仕様、ADR-0002／0017／0019〜0021／0023、IaC registry、runbook、管理者ガイド、roadmap、release manifest validatorを3 OS Production＋任意providerへ訂正した |
| 手法 | 公開機能比較、正本間の矛盾探索、実装／試験／運用証跡の三点照合、障害・移行・保守・競争力の反対仮説 |
| 総合判定 | **BLOCKED**。3 OS Production要求とprovider非依存契約はKCOMP-H1で是正し、FreeBSD／macOSのnative実装を追加したが、Linux実装と全hostのProduction受入は未成立。KSAS代替製品としての同等性も未証明 |
| 指摘集計 | High 6件、Medium 8件、Low 2件。現在はKCOMP-H1、H2、M2、M3、M4、M6対応済、KCOMP-H3は実装済・受入待ち、未処置9件 |

## 1. 結論

ISASは、圃場GIS、作業指示・日誌、農薬・在庫、CSV移行、offline outbox、RLS・監査という中核に実装上の強みがある。しかし、次の3点を混同すると誤った導入判断になる。

1. **KSASは運営主体とサポート窓口を持つcloud serviceである。** ISASはself-hostを目指すsoftwareであり、availability、backup、security update、問い合わせ対応を導入組織が引き受ける。画面機能だけを並べても代替性は証明できない。
2. **ISASの`v1.0.0`はProduction承認版ではない。** release note自身が`BLOCKED`と明記している。そこで列挙されたAWS固有の未完了事項は本来要求ではなく、macOS／Linux／FreeBSD Production対応から逸脱して作られた実装計画の残件である。実利用者UT、実端末、restore／DR、段階配備等のprovider非依存gateは引き続き必要である。
3. **要求追跡欠陥はKCOMP-H1で訂正した。** Production必須hostをmacOS／Linux／FreeBSD、AWSを任意adapter、Mac Composeを非本番`local-integration`だけのprofileとして再定義し、要求仕様・ADR・IaC registry・runbook・roadmap・release manifestへ反映した。FreeBSD JailとmacOS native profileは実装したが実host受入は未完で、Linux実装はKCOMP-H4として残る。

macOS、Linux、FreeBSDはすべて**正規のProduction host対象**であり、品質上の上下関係を設けない。実装順としてLinuxを最初のreference profileにすることはできるが、それを理由にmacOSを非本番・縮退版、FreeBSDを任意対応へ格下げしてはならない。OS固有の構成と試験を分けながら、共通の業務要件、security、backup／restore、監査、release gateを3 OSすべてで満たす必要がある。

## 2. 比較の証拠範囲と限界

### 2.1 KSAS側で確認できた公開事実

- KSASはinternet cloudを利用し、PC／smartphoneから圃場、日誌、進捗を管理するserviceである。
- 圃場管理、作業指示・日誌、作付計画、台帳、Excel出力、在庫、作業時間分析を公開している。
- 対応農機による自動日誌、食味・収量、可変施肥、乾燥調製、機械monitoring、drone、remote sensing等を公開している。
- KSAS APIは組織、圃場、機械、農薬、肥料、指示・日誌、作付計画等の取得と、一部dataの登録を公開している。農家が連携accountを作成して開始する同意境界がある。
- FAQでは他systemからKSASへのdata移行は不可、圃場／日誌はExcel出力可能、推奨圃場数は3,000枚以内、smartphoneだけでは作付計画作成と圃場詳細設定ができないとしている。
- 機能別PDF、PC／smartphone別manual、動画、平日service deskが公開されている。

### 2.2 比較してはいけないもの

KSASの非公開な内部architecture、RLS、MFA、暗号鍵、backup方式、SLO実績、脆弱性、障害履歴は公開資料だけでは確認できない。このため、ISASの詳細ADRが存在することを理由に「ISASのsecurityがKSASより高い」とは判定しない。同様に、KSASの販売中機能とISASの未受入codeを「同等に完成」と数えない。

比較結果の`KSAS確認`は**公式公開資料から機能提供を確認した**という意味に限定し、内部品質保証を意味しない。

## 3. 機能・製品比較表

| 比較軸 | ISASの現状 | KSAS公式公開情報 | 敵対的判定 |
|---|---|---|---|
| 圃場GIS | MapLibre、PostGIS bbox、担当scope cache、eMAFF GeoJSON／境界付きCSV取込を実装 | Google Mapを使う圃場管理、住所・面積・所有者等をPC／smartphoneで管理 | coreは比較可能。ただしISASの実data精度、3,000枚級操作、地図provider契約は未受入 |
| 作業指示・日誌 | 指示、担当、打刻補完、写真、template、前回値、訂正・差戻しを実装 | 指示から日誌化、検索・振返り、Excel出力を提供 | 手入力coreは比較可能。ISASの実利用者時間目標は未測定 |
| 作付計画・工程 | Phase 2 migration／API／画面、高度gantt、依存・resource競合を実装したがPG16・実利用者gate未完 | 作付計画と資材費simulation、PC機能を提供 | ISASを「完成」と表示不可。運用規模とUXの実証が必要 |
| 農薬・在庫 | 農薬鮮度、安全check、追記型在庫、lot／期限／棚卸し／JGAP CSVを実装 | 農薬・肥料台帳、group別在庫、入出庫履歴、Excel出力を提供 | ISASの食品安全設計は強いが、法令master更新運用と実帳票照合が未完 |
| 分析 | tenant内の計画対実績、収量、作業時間、資材projectionを実装。実data／性能未受入 | 食味・収量分布、作業時間分析、生育・施肥関連機能を提供 | ISASは入力sourceが不足し、画面があっても実用分析へ到達しない危険 |
| 農機・drone | ADR-0012とPhase 3計画のみ。実adapter／connectorなし | 対応機の自動日誌、稼働・位置・食味・収量、可変施肥、drone連携を提供 | **重大な競争力gap**。設計書を接続済みと数えない |
| 乾燥・水管理・remote sensing | 個別の実連携なし | 乾燥調製、水管理連携、衛星／drone remote sensingを公開 | KSAS代替を名乗るならscope差を明示。短期に模倣せず優先顧客で裁定 |
| 外部API | ADR-0013とruntime基盤はあるが実client契約・公開service受入は未完 | KSAS APIで複数台帳・日誌等の取得／登録を公開 | ISASはAPIを「提供中」と表示不可。最初の実client contractが必要 |
| data移行 | 圃場→作業→農薬CSV、重複検査、RLS、eMAFF境界取込を実装 | FAQ上、他systemからKSASへのdata移行は不可 | ISASの差別化候補。ただし全項目round-trip、文字code、添付、履歴完全性は未証明 |
| data出力 | 日誌、圃場台帳、農薬記録等のCSV | 圃場情報・日誌のExcel出力 | 双方にportability手段。ISASは退役時の一括export／restore契約が不足 |
| offline | PWA cache／outbox／競合／失効を設計・実装、実機gate未完 | 公開資料から一般offline継続可否を確定できない | ISASの差別化候補だが、S6／S9未完のため販売主張不可 |
| mobile | PWAで現場機能を統合する方針 | smartphoneで圃場確認・日誌・進捗等を提供。作付計画と圃場詳細設定はPC必須 | ISASがmobile完結性を示せれば優位。ただし高齢者を含む実UTが必要 |
| i18n／RTL | 日英辞書、疑似RTL、法域profileを設計。native／領域reviewは残存 | 比較対象の公開日本向け資料では多言語範囲を確定できない | ISASの戦略差。ただし「全世界対応」は現時点で未達 |
| 認可・監査 | tenant RLS、scope、失効、MFA、監査chainを詳細設計・実装 | 公開情報だけでは内部方式を比較不能 | ISAS内の本番検証だけをgateにする。KSASへの根拠なき優越主張は禁止 |
| 導入・support | 管理者／利用者guideとrunbookはあるが、運用主体・SLA・service deskは未確定 | 機能別manual、動画、service deskを公開 | self-hostの運用負担を含むsupport modelが必要 |
| 費用 | software licenseだけではなくhost、運用者、backup、IdP、監視、incident対応費が必要。TCO表なし | 100圃場までの無料plan、有料planを公開 | license価格だけで比較すると誤認。3年TCOと要員工数を提示する |

## 4. Host OS適合性判定

| Host候補 | 現行実装で確認できるもの | 破壊仮説 | 判定 |
|---|---|---|---|
| Linux | OCI imageとComposeはLinux containerを前提とし、技術的な移植可能性が高い | distribution、kernel、cgroup、firewall、SELinux／AppArmor、systemd、storage、backupを固定しておらず、同じComposeが動けば本番適合と誤認する | **Production必須対象、現状未承認** |
| macOS | Docker Desktop非依存のnative Production profile、6つの非login service identity、launchd／pf、署名済みpkg、監視、backup／restore、rolling updateを実装 | 実Mac 2台での全損復旧、sleep／update／disk障害、E2E、共通SLOの証跡がなければ、静的検査だけをProduction承認へ昇格できる | **Production必須対象、実装済み・受入未承認**。同一SLOの実証が必要 |
| FreeBSD | 文書・CI・runbook・実測なし | FreeBSDにはDocker Engineがなく、現行Linux image／Composeを実行基盤として流用できると仮定すると導入が停止する | **Production必須対象、現状未承認**。FreeBSD Jail上のnative service構成と、rc.d、VNET／pf、ZFS、rctlを含む運用受入が必要 |

### 4.1 FreeBSD ProductionはJailを前提とする

FreeBSD Production profileはDocker、Compose、OCI runtimeを要求せず、FreeBSD Jailでserviceを分離して構成する。Linux guestやvirtualizationをKCOMP-H2の代替解決案には含めない。JailはDocker互換containerとして扱わず、FreeBSDのOS-level isolationとして個別に設計・検証する。

基準構成は次の責任境界とする。

- 外部公開するTLS ingress／Webは`edge` Jail、BFFは`app` Jail、PostgreSQL 16＋PostGISとPgBouncerはdata用Jail、IdP、object／queue、telemetryは依存関係と権限境界に応じた専用Jailへ配置する。
- 各componentはFreeBSD native package／portsまたは署名済みapplication artifactとしてversionを固定し、rc.dで起動順序、health check、graceful shutdown、再起動を管理する。
- Jail networkはVNET／epair／pfで分離し、外部から到達できる入口を`edge` Jailに限定する。Jailにhost root、Docker socket、不要なdevice、host filesystem全体を渡さない。
- dataを持つJailには分離したZFS datasetとquotaを割り当て、rctlでresource上限を設定する。ZFS snapshotだけをDB backupとは見なさず、PostgreSQL整合backup、WAL archive／PITR、暗号化したoff-host recovery setを組み合わせる。
- ComposeはLinux／macOS用profileのartifactとして残せるが、FreeBSD Productionの起動・更新・rollback・backup・restore手順はJail manifestとrc.d設定を正本にする。

## 5. 敵対的指摘一覧

| ID | 重要度 | 区分 | 攻撃仮説／不整合 | 要求処置 | 状態 |
|---|---|---|---|---|---|
| KCOMP-H1 | High | 要求逸脱 | ユーザー要求はmacOS／Linux／FreeBSD Production hostなのに、ISAS文書が無断でAWSをProduction正本、Macを非本番限定に変更している。このままでは正しい要求を満たさない構成が承認される | ユーザー要求を最上位の正本として要求仕様・ADR・IaC・runbook・roadmapを訂正し、AWS前提を必須条件から除去する | 対応済 |
| KCOMP-H2 | High | FreeBSD | FreeBSDにはDocker Engineがなく、Docker Compose／OCI stackを実行基盤にするとdaemon・network・volume・health・image実行の入口で停止する | FreeBSD Jailを正規Production profileとして設計し、native package／ports、rc.d、VNET／pf、ZFS、rctlによるservice分離、runbook、backup／restore、E2Eを実装・受入する | 対応済 |
| KCOMP-H3 | High | macOS | 検証用Docker Desktop stackをそのまま本番化すると、user session、Desktop update、sleep、単一disk／電源、support対象外のproduction runtimeに業務を依存させる | macOS Productionを正規profileとして`local-integration`から分離し、runtime保守、auto-start、sleep、backup、監視、更新、復旧、共通SLOを受入する | 実装済・受入待ち |
| KCOMP-H4 | High | Linux | Linuxにproduction IaC、OS hardening、service manager、backup、upgrade、firewall、secret、support matrixがない | support対象distribution／version／archを定義し、empty hostからrestoreまで自動化・実証する。Linuxだけを上位hostとは扱わない | 未処置 |
| KCOMP-H5 | High | 競争力 | 農機adapterがADRだけなのにKSAS相当のsmart agricultureを想起させると、自動日誌・収量・可変施肥を期待した導入が失敗する | capability catalogに`implemented／validated／planned／out-of-scope`を表示し、最初の実connectorを契約・sample・実機で受入する | 未処置 |
| KCOMP-H6 | High | release | codeとlocal testのPASSを、販売中KSASと同じ「利用可能」と数えると、実data、実利用者、端末、restore、incident未検証のsystemへ業務正本を移す | host profileごとの実release manifest、実UT、実CSV rehearsal、restore／DR、security、24時間監視を完了するまでProduction表示を禁止する | 未処置 |
| KCOMP-M1 | Medium | API | 外部API／WebhookはADR中心で、実client、公開endpoint、version運用、supportがない | 最小read-only APIから実client contract、sandbox、rate／revocation、運用窓口を受入する | 未処置 |
| KCOMP-M2 | Medium | 分析 | 分析画面に実農機・sensor inputがなく、入力品質の低いdashboardが精密農業のように見える | source freshness、coverage、manual／machine比率を表示し、data sourceがない指標を非表示にする | 対応済 |
| KCOMP-M3 | Medium | 機能scope | KSASのremote sensing、水管理、乾燥調製、診断supportまで無計画に追随するとcore品質が悪化する | 対象顧客jobと契約可能性で採否を決め、非対象を明示。core Production gateより前へ割り込ませない | 対応済 |
| KCOMP-M4 | Medium | 文書 | `v1.0.0`というtagだけを見た利用者がProduction版と誤認できる | README最上部、UI build情報、release一覧へ`baseline／Production BLOCKED`を常時表示し、Production tag namespaceを分離する | 対応済 |
| KCOMP-M5 | Medium | capacity | KSASの公開推奨3,000圃場と、ISASのsynthetic test／目標値は条件が違い、単純な件数比較ができない | 3,000圃場、複雑polygon、履歴、写真、同時利用者を含むhost別reference benchmarkを公開する | 未処置 |
| KCOMP-M6 | Medium | 運用 | KSASはservice deskを公開するが、ISAS self-hostではon-call、保守契約、責任者、脆弱性窓口が導入先ごとに空欄になり得る | RACI、support時間、severity、response、EOL、security窓口を必須配備台帳にし、空欄なら稼働禁止する | 対応済 |
| KCOMP-M7 | Medium | TCO | ISASを無料softwareとしてKSAS料金と比較すると、server、電力、backup、IdP、監視、保守者、incident工数を隠す | 100／1,000／3,000圃場の3年TCO、必要要員、停止cost、更新頻度をhost profile別に作る | 未処置 |
| KCOMP-M8 | Medium | portability | CSV出力があっても、添付、監査、membership、作付、在庫event、位置同意を別ISASへ完全restoreできる保証がない | vendor exit用full export manifest、schema version、hash、添付、import検証、削除証明を実装する | 未処置 |
| KCOMP-L1 | Low | 比較品質 | KSAS公開資料にないsecurity仕様を「未実装」と断定すると、不公正かつ根拠のない比較になる | `確認済み／非公開で比較不能／ISAS未受入`を分け、推測値をscoreへ入れない | 未処置 |
| KCOMP-L2 | Low | 鮮度 | KSAS機能・料金・規約は更新されるため、固定比較表が短期間で陳腐化する | source URL、確認日、再確認期限を記録し、release reviewごとに差分確認する | 未処置 |

## 6. High指摘の攻撃詳細

### KCOMP-H1：配備正本がユーザーの確定要求を無断変更している

ユーザーの確定要求は、ISASをmacOS、LinuxまたはFreeBSDでhostできることである。それにもかかわらず、ISAS要求仕様§5.6とADR-0023はMacを非本番Integrationへ限定し、ADR-0019と開発工程はAWS serviceをProduction前提として採用した。これはユーザー要求の変更ではなく、作成側による要求逸脱である。AWSの採用判断とMacの非本番限定を、過去に文書化されたという理由で正当化してはならない。

是正では、macOS／Linux／FreeBSDをすべてProduction対象へ戻し、AWS固有構成を必須の正本から外す。AWS artifactを残す場合は、3 OS self-host要件を置換しない任意adapterとして明示する。単なる文言置換ではなく、次の正本を同時に変更する必要がある。

- 要求仕様：self-host Production class、許容host、単一node／HA class、責任分担
- ADR-0002／0019／0020／0021／0023：3 OS Productionを正本とするprovider-neutral interfaceとhost別profile
- threat model：host root、Docker socket、hypervisor、backup媒体、operator端末
- deployment：secret、TLS、IdP、object、queue、telemetry、backup、upgrade
- release manifest：`host_os`、kernel、runtime、filesystem、encryption、failure domain、support期限
- 管理者guide：OSごとの起動・停止・復旧・更新・incident

これらが揃う前に「3 OS対応」と記載すると、最も危険な部分だけが運用者の推測へ落ちる。

**処置結果（2026-08-17）**：ユーザー確定要求を要求仕様§5.7と[Productionホスト共通契約](../operations/Productionホスト共通契約.md)へ固定し、ADR-0002／0017／0019〜0021／0023、`infra/README.md`、OpenTofu README、運用runbook、管理者ガイド、開発工程へ波及した。release manifest schema v2は`host_os`を`macos`／`linux`／`freebsd`に限定し、OS、architecture、service管理／isolation、filesystem／暗号化、provider／site、2 failure domain以上を必須検査する。AWS固有成果物は任意adapterとして残し、3 OS実装の代替にしない。

### KCOMP-H2：FreeBSDはJail前提のProduction profileが必要

Docker公式のEngine install対象はLinux distribution群で、FreeBSDはsupported platform表にない。現行imageは`linux/amd64`／`linux/arm64`を前提とするため、FreeBSD hostでの`docker compose up`を受入手順にできない。「Jailはcontainerだから同じ」という読み替えも、network、health check、filesystem permission、resource制御、package更新、support責任を未検証のままにする。

解決案はFreeBSD Jailによるnative Production profileに固定する。最初の技術spikeでは、少なくとも次を実装して計測する。

1. `edge`、`app`、data、IdP、object／queue、telemetryのJail manifestと、Jail間通信を必要最小限に制限するVNET／epair／pf ruleset。
2. FreeBSD native package／portsまたは署名済みapplication artifactのversion固定、provenance／SBOM確認、rc.dによる起動順序、health check、graceful shutdown、restart。
3. JailごとのZFS dataset／quotaとrctl、secret分離、host filesystem／deviceへの不要なaccess拒否。
4. PostgreSQL整合backup、WAL archive／PITR、暗号化したoff-host recovery set、および空のFreeBSD hostへの復旧手順。

受入では、host reboot後の自動復旧、service停止検知、certificate／package更新、rollback、disk full、Jail停止・侵害時の横展開防止、backup／PITR／全損restoreを実行する。その上でRLS、認証・失効、監査chain、queue／object障害、性能SLOの共通E2Eを合格させる。単にログイン画面が出るだけでは不合格とする。

**処置結果（2026-08-17）**：ADR-0019 v4に従い、FreeBSD native Jail実装を`infra/hosts/freebsd/`へ追加した。6 service境界、短い固定Jail名、VNET／epair、pf default deny、Jail別ZFS quota・secret dataset、rctl、rc.d起動／停止順、署名済みnative pkg導入、PostgreSQL base backupを含むrecovery set、WAL／object／監査／鍵参照のhash検証、restoreを実装した。`ops/host-profiles/install-host.sh`はFreeBSD／Darwin／Linuxを明示分岐し、静的validatorとshell構文検査を通過した。ユーザー指定により現段階の完了条件はOS分岐確認までとし、実FreeBSD上のE2Eは未実施であるため、`freebsd-production` profileおよびProduction releaseは引き続き`BLOCKED`とする。

### KCOMP-H3：Mac検証環境をProductionへ流用できない

現行Mac profileはsynthetic data、loopback入口、単一Mac、Docker DesktopのLinux VMを意図した検証用構成にすぎない。これはmacOSをProduction対象外にしてよい理由ではなく、macOS Production profileが欠落している証拠である。さらにDocker公式support scopeはDocker Desktopをproduction runtimeとして対象外にしているため、Docker Desktopだけを正規runtimeにするならISAS側がruntime障害まで保守する必要がある。別runtimeまたはnative service構成も含めて比較し、macOS上で共通Production gateを満たす方式を選定する。

macOS Productionでは、少なくとも次の構成classを要件に照らして評価する。

- **single-node class**：暗号化外部backupと代替Macへのrestoreを必須にする。HA／SLOを下げる場合はユーザーの明示承認が必要で、実装側が一方的に縮退させない。
- **externalized state class**：MacはWeb／BFF nodeに限定し、DB、object、queue、IdP、backupを別failure domainへ置く。これは「Mac 1台host」ではなく分散配備である。

どちらも`local-integration`とは別profile、別data、別secret、別tag／manifestを使用する。

**処置結果（2026-08-17）**：ADR-0019 v4とmacOS Production runbookに従い、Docker Desktop／対話login非依存のnative実装を`infra/hosts/macos/`へ追加した。`macos-production`専用root、6つの非login service identity、loopback分離とedge 443だけを公開するpf、`RunAtLoad`／`KeepAlive`付きlaunchd、signed pkg検証、FileVault／sleep事前検査、PostgreSQL base backupを含むoff-host recovery set、hash検証restore、片系drain付きrolling update、liveness／readinessと共通P0 SLO監視を実装した。host dispatcherのDarwin分岐、profile validator、shell構文、全plistを静的検査した。実Mac 2台の全損restore／E2E／SLOは未実施のため、`macos-production` profileとProduction releaseは引き続き`BLOCKED`とする。

### KCOMP-H4：Linux Production profileが欠落している

OCI stackの自然な実行基盤はLinuxだが、現在のrepositoryにはself-host Productionのgolden pathがない。AWS adapterをlocal adapterへ差し替えただけでは、OS update、daemon restart、disk full、certificate renewal、firewall、log rotation、backup整合、restore、secret rotationを所有できない。

Linux profileは、特定distributionのsupport期間、x86_64／arm64、minimum CPU／RAM／disk、LUKS等の暗号化、SELinux／AppArmor、systemd、nftables／Docker chain、rootless可否、UPS、NTP、registry、署名検証をversion固定する。最小hostからのinstallと、全損hostへのrestoreを別担当者が再現して初めてProduction対応になる。Linuxを先に実装しても、macOS／FreeBSDより上位の製品classにはしない。

### KCOMP-H5：KSASとの差は農機ecosystemで拡大する

KSASの差別化は単なる日誌画面ではなく、対応農機からの自動日誌、食味・収量、可変施肥、機械monitoring、drone、乾燥調製を一体化したecosystemにある。ISASのADR-0012は安全な設計だが、Phase 3計画であり実connectorはない。ADRの存在を製品機能として数えると、導入後に手入力負担が残り、分析dataも集まらない。

最初の目標はKSAS全体の模倣ではない。実顧客が所有する1種類のmachine／file formatについて、取込、圃場照合、日誌候補、人の確定、再送、単位、監査まで縦に通す。その後にKSAS API連携を検討する場合は、農家の明示連携、利用条件、rate、data保持、再配布をconnector台帳で承認する。

### KCOMP-H6：実装済みと運用可能を分ける

ISAS文書は未承認を比較的正直に記録しているが、repository tag、画面の完成度、ADRのクローズ数だけを見る利用者はProduction準備済みと誤認できる。KSASの公開中機能との比較では、この認知差がさらに大きくなる。

host profileごとに次の4状態を機械可読にする。

- `designed`：ADR／contractのみ
- `implemented`：codeとcomponent testあり
- `validated`：指定host上の実data／実依存／運用試験を合格
- `production-authorized`：実manifest、独立承認、段階配備、監視を完了

比較表、README、UI build情報、release noteは最も低い依存状態へ揃える。例えば農機連携は`designed`、Linux self-hostは未設計、Mac localは`validated for integration only`である。

### KCOMP-M2：分析の入力品質表示

**処置結果（2026-08-17）**：分析APIへsource freshnessに加えて作付単位のcoverageとmanual／machine入力件数・比率を追加した。画面はdata sourceのない作業実績、収量実績、資材実績を数値`0`や「欠測」の指標として描画せず、coverage欄に「データ源なし（指標は非表示）」と表示する。BFF API test、画面test、TypeScript build、i18n coverageで検証した。

### KCOMP-M3：KSAS比較機能のscope

**処置結果（2026-08-17）**：[KSAS比較機能scope決定](../product/KSAS比較機能scope決定.md)で対象顧客jobと採否基準を固定した。remote sensingと診断supportは契約・責任・実data受入が成立するまで`planned`、水管理と乾燥調製は現行roadmapの`out-of-scope`とし、いずれもcore Production gateより前へ割り込ませない。状態は機械可読な[capability catalog](../product/capability-catalog.json)で検査する。

### KCOMP-M4：baselineとProduction表示の分離

**処置結果（2026-08-17）**：README最上部、常時描画するWeb build banner、[release一覧](../release/README.md)に`baseline／Production BLOCKED`を固定表示した。既存`v1.0.0`はbaselineのままとし、Production承認tagを`production/v<version>` namespaceへ分離した。production release validatorとtag発行scriptは旧`v<version>`を拒否し、専用namespaceだけを受理することを自動testで確認した。

### KCOMP-M6：配備別の運用責任

**処置結果（2026-08-17）**：配備別運用台帳へRACI、support時間・timezone、Sev 1〜4定義と初動時間、service owner／on-call／security／脆弱性／privacy窓口、version EOLと移行通知期間を必須化した。Production BFFは`ISAS_OPERATIONS_LEDGER`を起動時に読み、空欄、placeholder、不正窓口、配備ID不一致を拒否する。release manifestも同台帳のdigestと証跡URIを要求する。意図的に不完全なexampleが検査失敗し、完全fixtureが起動・release検査を通ることを自動testで確認した。

## 6.1 未クローズ指摘の実装進捗（2026-08-17）

状態を`対応済`へ変更していない項目には、次の成果物と外部gateが残る。成果物の追加だけを受入完了とみなさない。

| ID | 今回追加した実装・仕様 | 状態を維持する理由 |
|---|---|---|
| KCOMP-H3 | `infra/hosts/macos/`のnative実装、launchd／pf、署名済みpkg検査、監視、backup／restore、rolling update、静的validator | 実Mac 2台の全損restore、障害試験、E2E、共通SLO、二人承認の証跡がないため`実装済・受入待ち` |
| KCOMP-H4 | 要求仕様§5.7、ADR-0019 v4、`infra/hosts/linux/profile.json`、Linux runbook、実機受入validator | Linux native Production実装と、実host 2 failure domain、backup／restore、E2E証跡がない |
| KCOMP-H5 | 要求仕様§5.8、ADR-0012 v2、[capability catalog](../product/capability-catalog.json)、[初回農機connector受入契約](../product/初回農機connector受入契約.md) | 契約済み実connector、実sample、実機受入がない |
| KCOMP-H6 | 要求仕様§5.8、ADR-0021 v4、Production表示の強制BLOCKED、host別release manifest validator、専用tag namespace | 実UT、実CSV、DR、security、24時間監視を完了したhost別manifestがない |
| KCOMP-M1 | 要求仕様F-94、ADR-0013 v2、[外部API最小受入契約](../product/外部API最小受入契約.md) | 実client、service identity、sandbox、rate／失効、support受入がない |
| KCOMP-M5 | 要求仕様§5.2.3、ADR-0019 v4、[host別reference benchmark仕様](host別reference-benchmark仕様.md) | 3 OS上の同一fixtureによる公開実測結果がない |
| KCOMP-M7 | 要求仕様§5.8、ADR-0019 v4、[3年TCO入力仕様](../operations/3年TCO入力仕様.md)と計算器 | 9組合せの実見積、人件費、停止costが未入力 |
| KCOMP-M8 | 要求仕様F-85／§5.3、ADR-0003 v10、[vendor exit full export仕様](../operations/vendor-exit-full-export仕様.md) | 完全export／import実装と空ISASへのrestore・削除証明がない |

## 7. ISASが維持すべき差別化候補

敵対的レビューはKSASの機能表をそのまま模倣する提案ではない。次のISAS特性は、実証できれば明確な価値になる。

1. **self-hostとdata control**：cloud vendor依存を避け、法域・組織がdata、鍵、保持、削除を管理できる。ただし運用能力とTCOを隠さない。
2. **移行入口**：KSAS FAQが他systemからの移行不可とする一方、ISASはCSVとeMAFF境界取込を持つ。完全性とrollbackを実dataで示す。
3. **offline first**：圏外での日誌、農薬事前警告、未同期状態、競合・失効を一貫して扱う。実機のdata loss試験を通す。
4. **tenant／scope securityと監査可能性**：設計の詳細ではなく、越境拒否、権限失効、監査chain、restore後整合を第三者試験で示す。
5. **多言語・RTLとprivacy**：技能実習生を含む利用者、位置同意、短期保持を実利用者と法務が受入する。
6. **open adapter境界**：特定メーカーへcoreを結合せず、署名adapterとfile importを継続できる。ただし最初の実connectorなしに価値を主張しない。

## 8. 是正工程と再レビューgate

| 順序 | 工程 | 成果物 | 再レビュー合格条件 |
|---:|---|---|---|
| 1 | Product status是正 | capability catalog、README／UI／release表示 | plannedとvalidatedが混在せず、`v1.0.0`がProductionと誤認されない |
| 2 | ユーザー要求への復帰 | **KCOMP-H1対応済**：要求仕様、共通host契約、ADR、IaC registry、runbook、roadmap、manifest validator | macOS／Linux／FreeBSDを正規Production対象とし、AWSを任意adapter、`local-integration`をMac非本番profileだけに限定 |
| 3 | 配備ADR再編 | provider-neutral ADR、同格のhost profile、FreeBSD方式裁定 | AWS前提による要求逸脱0件。各OSの完了条件が同じ業務・security・復旧gateへ接続 |
| 4 | Linux Production | IaC、install／upgrade／rollback／backup／restore／incident runbook | empty host→稼働、全損→restore、RLS／監査／SLO／securityを別担当者がPASS |
| 5 | macOS Production | production専用profile、runtime保守、auto-start、sleep／update／disk／backup対策 | 共通SLO、再起動・全損復旧・外部監視をPASS。縮退はユーザー承認なしに許可しない |
| 6 | FreeBSD Production | Jail manifest、native package／ports、rc.d、VNET／pf、ZFS／rctl、install／運用／復旧runbook | 空のFreeBSD hostから構築し、reboot、network分離、resource制御、upgrade／rollback、backup／PITR／全損restore、securityをE2E PASS |
| 7 | Cross-host artifact | linux/amd64／arm64署名image、SBOM、provenance、compatibility matrix | 同一source／migration／contract test、host固有差分がmanifest化済み |
| 8 | Core実受入 | 実CSV、3,000圃場benchmark、実端末、UT、法令master、DR | host別manifestへ実証跡を登録しProduction blocker 0件 |
| 9 | 最初の農機縦切り | 1実format／connector、日誌候補、監査、運用 | 実sample・実機または契約済sandboxで再送／停止／単位／圃場照合PASS |
| 10 | 製品support | SLA、service desk／on-call、security窓口、EOL、3年TCO | 配備台帳の責任者・連絡先・費用に空欄0件 |

## 9. 再レビューで提出する証拠

1. 変更後の要求仕様とADR差分。host／runtime／failure domainの用語が一意であること。
2. 各hostのmachine-readable manifest：OS、kernel、arch、container runtime、filesystem、暗号化、resource、component digest、migration set。
3. Linux、Mac、FreeBSD採用方式それぞれのinstall、reboot、upgrade、rollback、disk full、certificate更新、backup、bare-metal相当restore結果。
4. tenant越境、MFA／失効、監査chain、queue／object障害、未同期PWA更新、写真回収、RPO／RTOのhost別結果。
5. 3,000圃場と複雑polygon、日誌履歴、写真、同時利用者を含む性能data。KSAS値との優劣ではなくISAS SLOへの合否を示す。
6. 作業員、高齢作業員、技能実習生の実UTと、manualだけで管理者が復旧できる運用演習。
7. 機能状態catalogと、公開比較資料の根拠URL・確認日・承認者。
8. 3年TCO、運用要員、support時間、security連絡先、EOL／migration policy。

## 10. 参照資料

### ISAS正本

- [農業営農支援システム 要求仕様書](../農業営農支援システム_要求仕様書.md)
- [ADR-0012 農機連携](../design/ADR/ADR-0012-農機連携アーキテクチャ.md)
- [ADR-0013 外部API／Webhook](../design/ADR/ADR-0013-外部API-Webhook.md)
- [ADR-0019 インフラ・運用](../design/ADR/ADR-0019-インフラ・運用.md)
- [ADR-0021 テスト・リリース](../design/ADR/ADR-0021-テスト・リリース方式.md)
- [ADR-0023 Mac本番相当ローカル統合環境](../design/ADR/ADR-0023-Mac本番相当ローカル統合環境.md)
- [Phase 2〜4実装計画](../roadmap/Phase-2-4実装計画.md)
- [開発工程](../開発工程.md)
- [Release 1.0.0](../release/RELEASE-1.0.0.md)
- [Productionリリース承認 敵対的レビュー](Productionリリース承認_敵対的レビュー.md)
- [システム管理者運用ガイド](../manual/システム管理者運用ガイド.md)

### KSAS公式公開資料（2026-08-17確認）

- [KSASとは／初めての方へ](https://agriculture.kubota.co.jp/ksas/beginner/)
- [KSAS機能紹介](https://agriculture.kubota.co.jp/ksas/function/)
- [KSAS FAQ](https://agriculture.kubota.co.jp/ksas/faq/)
- [KSASマニュアル一覧](https://agriculture.kubota.co.jp/ksas/member/03.html)
- [KSAS作業日誌](https://agriculture.kubota.co.jp/ksas/function/02.html)
- [KSAS在庫管理・作業時間分析](https://agriculture.kubota.co.jp/ksas/versionup/vol30.html)
- [KSAS API開発者向けサイト](https://developers.ksas.kubota.co.jp/)
- [KSASサービス利用契約に関する会員規約](https://agriculture.kubota.co.jp/ksas/terms/)

### Runtime公式資料（2026-08-17確認）

- [Docker Engine supported installation platforms](https://docs.docker.com/engine/install/)
- [Docker Desktop for Mac requirements](https://docs.docker.com/desktop/setup/install/mac-install/)
- [Docker Desktop support scope](https://docs.docker.com/support/)

## 11. 最終判定

| 判定対象 | 結果 |
|---|---|
| ISAS業務coreをKSAS比較のpilotへ使う | **条件付き可**。synthetic／隔離data、非Production表示、手入力coreにscopeを限定 |
| ISASをKSAS同等の完成製品と表示する | **不可**。農機ecosystem、実API、support、実受入に重大差 |
| LinuxでProduction hostする | **要求上は必須、実装は未承認**。Production artifactと実運用gateが必要 |
| macOSでProduction hostする | **要求上は必須、実装は未承認**。`local-integration`とは別のProduction profileが必要 |
| FreeBSDでProduction hostする | **要求上は必須、実装は未承認**。FreeBSD Jailを前提とするProduction profileと共通運用gateの実装・受入が必要 |
| `v1.0.0`をProduction releaseとして扱う | **不可**。baseline tagでありProductionは`BLOCKED` |

未処置High 5件を閉じるまで、実データの業務正本化、KSASからの切替、Production release、3 OS対応の対外表明を承認しない。
