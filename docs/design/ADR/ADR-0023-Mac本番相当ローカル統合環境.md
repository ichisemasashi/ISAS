# ADR-0023：Mac本番相当ローカル統合環境＝Compose＋local adapter

| 項目 | 内容 |
|---|---|
| ステータス | **採用（クローズ） v3**（v2の`local-integration`構成判断を維持。KCOMP-H1により本ADRの非本番判定は当該profileだけに適用し、macOS Productionを禁止しないことを明記。[レビュー記録](レビュー記録_ADR-0023.md)） |
| 日付 | 2026-08-16 |
| 由来 | [要求仕様書 v1.1 §5.6](../../農業営農支援システム_要求仕様書.md#56-mac本番相当ローカル統合環境) |
| 関連 | [ADR-0004 DB](ADR-0004-DB-PostgreSQL-PostGIS.md)、[ADR-0009 認証](ADR-0009-認証-セッション-MFA.md)、[ADR-0017 セキュリティ](ADR-0017-セキュリティ基盤-端末暗号化-失効-脅威モデル.md)、[ADR-0019 インフラ・運用](ADR-0019-インフラ・運用.md)、[ADR-0020 監視・SLO](ADR-0020-監視・SLO.md)、[ADR-0021 テスト・リリース](ADR-0021-テスト・リリース方式.md)、[ADR-0022 配布ライセンス](ADR-0022-配布ライセンス.md)、[責任境界設計](../Mac本番相当ローカル環境_責任境界設計.md) |

---

## 1. 背景・判断基準

現在のMac向け手順はViteのfixtureと破棄可能なPostGIS spikeを個別に起動するもので、実BFF、OIDC、永続session/context、優先度別pool、object、失効queue、telemetry、PWA同期を一つの境界で検証できない。一方、Cognito、DynamoDB、S3、SQS、KMS、RDSをMacで忠実に再現しようとすると、重いemulator群とproductionとは異なるIAM／failure特性を「本番同等」と誤認する。

このADRは、要求仕様v1.1の`local-integration`を、Mac 1台で反復可能かつ誤用しにくいIntegration環境として成立させる方式を決める。判断軸は次の順とする。

本ADRはmacOS全体の配備classを決めない。`local-integration`が非本番であることと、macOSをProduction hostとして実装・受入する要求は両立する。macOS Productionは別profile、別data／secret、別起動管理、別backup／restore、別release manifestを持ち、ADR-0019〜0021の共通gateに従う。

1. productionと共通化すべきdomain、認可、RLS、migration、同期protocolを迂回しない
2. stop／restartで未同期dataを失わず、resetだけが明示的に消去する
3. production credential／endpoint／実データへ構造的に到達させない
4. Apple Siliconを主対象としつつ、OCI multi-archとCompose標準でhost runtimeを交換可能にする
5. AWS固有保証、HA、実RPO/RTOをlocalの合格へ混ぜない

## 2. 決定

### 2.1 Profileと共通化境界

- 正式名称は**Mac本番相当ローカル統合環境**、profile IDは`local-integration`とする。ADR-0021のIntegration環境であり、Productionではない。
- orchestrationは[Compose Specification](https://docs.docker.com/compose/compose-file/)に準拠する。標準入口はrepository rootの`compose.local.yml`と`ops/local/local-{up,status,stop,restart,reset}.sh`とする。
- Webはproduction modeでbuildした静的assets、BFFはproduction HTTP runtimeを使う。共通化するのはdomain、security middleware、AuthContext、DB transaction、同期、object port、queue port、telemetry portである。
- `NODE_ENV=production`と配備profileを分離する。production mode buildであっても`ISAS_ENV_PROFILE=local-integration`なら非本番であり、local adapter以外へfallbackしない。
- local固有差分は`apps/bff/runtime-adapters/local-integration.mjs`配下のadapter境界へ閉じる。route handlerやdomain serviceに`if local`を散在させず、AWS adapterと同じinterface contract testを通す。
- component image、host tool、schemaの正確なversion／digestは`infra/local/component-lock.json`へ固定する。ADRは製品とprotocol／version floorを決め、mutable tagと未検証の自動major更新を禁止する。

### 2.2 採用構成

| 責務 | 採用 | 契約／理由 |
|---|---|---|
| Orchestration | Docker Compose v2互換 | Macで導入しやすく、宣言構成、health、volume、profile、resource limitをversion管理できる。Docker DesktopまたはColimaをhost adapterとして許す |
| HTTPS ingress／Web | [Caddy 2](https://caddyserver.com/docs/automatic-https)＋mkcertでhost生成するlocal CA証明書 | `https://isas.localhost:8443`を既定とし、production-built Web配信と`/api` reverse proxyをsame-origin化。Caddy admin APIは公開しない |
| BFF | Node.js 22系の既存Production BFF runtime＋local adapter | graceful shutdown、body／timeout／drain、health、AuthContext、RLS、同期domainをproductionと共有する |
| OIDC／MFA | [Keycloak](https://www.keycloak.org/docs/latest/server_admin/)のサポート対象releaseをdigest固定 | authorization code＋PKCE、issuer／audience／nonce、TOTP／WebAuthn、step-up、logout／back-channel logoutを実IdPで検証する。realmはversion管理し、test userのsecret値は生成する |
| 権威DB | PostgreSQL 16＋PostGIS 3.4系 | production採用majorと揃え、正式migration、RLS／FORCE、owner、trigger、`security_invoker`、GISを同じSQLで検証する |
| DB pool | PgBouncer **1.25.2**を5独立instance | P0／Auth-P1／P1／P2／Opsごとにcontainer、port、DB role、connection上限、timeoutを分ける。1.25.2未満は既知security修正を欠くため禁止し、単一processの別portは障害・上限分離を偽装するため不採用 |
| Session／context | `local_support` schemaのPostgreSQL永続store | encrypted token、opaque session、AuthContext、TTL、`authorization_version`、logout tombstoneを再起動後も保持。DynamoDBのAPIではなくBFF store contractを検証する |
| 失効queue／DLQ | `local_support` schemaのPostgreSQL durable queue | `FOR UPDATE SKIP LOCKED`相当、attempt、visibility時刻、idempotency key、DLQ／quarantine、最古年齢を持つ。SQS固有IAM／可用性はstagingへ残す |
| Object | local adapter管理のprivate filesystem volume＋DB metadata | content hash、tenant／用途binding、AES-256-GCM、MIME／signature、size、短期BFF URL、pending→accepted、孤立照合を検証。S3互換を装わずS3 contractはstagingへ残す |
| Envelope key | host生成の用途別256-bit key file | session、object、offline recovery test keyを分離し、AEAD AADへprofile／tenant／用途／record IDを束縛。KMS/HSM、非抽出性、二人承認を証明しない |
| Telemetry | OpenTelemetry Collector＋Prometheus＋Jaeger＋[Perses](https://github.com/perses/perses) | BFFのOTLP metrics／trace、低cardinality、redaction、pool／queue／object dashboardを検証。auditはDB権威経路のまま一般logへ置換しない |
| Operational log | stdoutの構造化JSON＋runtimeのlocal log保持 | token／Cookie／secret／payloadをallowlist外とし、profile／release digest／correlation IDを含める。無期限保持しない |

標準構成はすべてを起動する`acceptance` profileとする。依存を減らした開発用`fast` profileを設けてもよいが、OIDC、5 pool、object、queue、telemetryのいずれかを省いた結果には`evidence_scope=local-fast`を付け、`local-integration`合格証跡として受理しない。

### 2.3 Networkと到達制御

1. hostへ公開するのは既定でCaddyの`127.0.0.1:8443`だけとする。Keycloak管理、PostgreSQL、PgBouncer、telemetry、object volumeをhost portへ公開しない。
2. Caddyはedge networkと内部network、その他のserviceは`internal: true`の内部networkだけへ接続する。runtimeからinternetへ出ることを前提にしない。地図、email、webhookはsynthetic local fixture／sinkへ固定する。
3. BFF起動時にDB、issuer、object、queue、OTLPのscheme／host／portとcredential sourceを検査し、Compose service名、loopback、署名済みtest allowlist以外を拒否する。denylistだけに依存しない。
4. cloud metadata endpoint、AWS credential chain、hostのAWS profile、production secret mountをlocal adapterから使用しない。`AWS_*`等のproduction credential候補を検出した場合は値をlogせず起動を拒否する。
5. LAN公開は別の`lan` profileを明示指定した時だけ許す。host certificate SAN、許可CIDR、macOS firewall、IdP redirect URIを再生成し、Caddy以外のportは公開しない。公共Wi-Fi／router port forwardingを禁止する。
6. Keycloak admin consoleはloopback ingress上の管理pathだけから利用し、LAN profileでも公開しない。bootstrap admin passwordとrealm client secretは毎環境生成し、既定値を置かない。
7. OIDC issuerはbrowserとBFFで同じ`https://isas.localhost:8443/oidc/...`を使う。Caddyへ内部network alias `isas.localhost`を付け、BFFのdiscovery／JWKS／token通信もCaddy経由にする。Keycloak内部service名を別issuerとして使わない。mkcert root CAはBFFへread-only mountし、Nodeの追加trust storeへ限定登録する。

### 2.4 Databaseと永続化境界

- empty DBへproductionの全version付きmigrationを番号順に適用し、verify SQLを実行する。application起動時の暗黙migrationは禁止する。
- 業務schemaと`local_support` schemaはowner、role、migration履歴を分離する。BFF業務roleは`local_support`を直接任意queryできず、local adapter専用roleだけが固定SQLで扱う。
- P0／Auth-P1／P1／P2／Opsは別PgBouncer instanceと別login roleを使う。全instanceの接続上限合計＋migration／monitor reserveがPostgreSQL `max_connections`を超える構成は検証scriptが拒否する。
- `SET LOCAL`、transaction pooling、commit／rollback後GUC消去、ROLLBACK失敗時の接続破棄を5 poolすべてで検証する。
- 通常の`stop`／`restart`はDB、Keycloak、object、telemetry、未配送queue、未同期browser outboxのvolumeを保持する。`reset`は対象volume、profile、未配送件数を表示し、`--confirm-local-data-loss`がない限り実行しない。
- backup／restore commandをlocal data保全の便宜として提供しても、ADR-0019のWAL/PITR、recovery set、RPO/RTO証跡には算入しない。

### 2.5 認証・失効

- browserはCaddy same-originだけへ接続し、BFFはKeycloakとauthorization code＋PKCEを完了する。access／refresh／ID tokenをbrowser storageへ保存せず、不透明な`__Host-isas_session` Cookieだけを渡す。
- Keycloak claimを業務権限の真実源にしない。subjectと認証強度を確認後、membership／role／scope／`authorization_version`を正式DBからサーバ導出し、ADR-0009の単一注入経路を通す。
- session/context tokenは用途別local keyで暗号化して`local_support`へ保存する。平文token、TOTP seed、回復codeをDB、log、fixture、evidenceへ出さない。
- user／membership／role／scope／MFA変更は正式DBの失効event→local durable queue→session/context／offline snapshot invalidationへ冪等反映する。consumer停止、重複、逆順、DLQ、再送をfault testする。
- production Cognito／DynamoDB／SQS／KMS adapterとのcontract差分をtest catalog化する。Keycloak/local storeのPASSはCognito署名、IAM、Dynamo条件書込、SQS visibility、KMS policyの証明ではない。

### 2.6 Objectと鍵

- object keyはclientに選ばせず、BFFがtenant、用途、record ID、random suffixから生成する。filesystem pathへ正規化後のroot containment検査を必須にし、`..`、symlink、absolute pathでvolume外へ出られないようにする。
- uploadはpending領域へstreamし、body上限、magic-byte signature、MIME／extension allowlist、hashを検査後にacceptedへ原子的に移す。失敗／中断fileとDB参照なしobjectを期限付き回収する。
- downloadは短寿命・単回・session／tenant／scope／object versionへ束縛したBFF署名tokenを使う。filesystemをCaddyから直接配信しない。
- key fileは`.local/secrets/`へ生成し、directory `0700`、file `0600`を検査する。Git ignoreだけでなくtracked file検査とsecret scanを行う。
- key rotationは新旧重ね読取→新規writeを新鍵へ→rewrap／reencrypt→旧鍵参照0→旧鍵削除の順とする。resetは鍵だけ先に消してciphertextを回復不能にしないよう、dataと鍵を一つの破棄計画で扱う。

### 2.7 Telemetryと証跡

- `deployment_id=local-integration`、`evidence_scope=local-integration`、source commit、Web／BFF digest、component lock digest、migration set、host arch、runtime versionを全証跡へ含める。
- tenant／user／field／device ID、URL実値、SQL bind、token、Cookie、写真、自由入力をmetric label／trace attribute／logへ出さない。synthetic PII canaryを流し、Collector以後に残らないことを検査する。
- dashboardはP0／Auth-P1／P1／P2／Opsのpool待ち、request latency／error、失効queue／DLQ、object pending／orphan、RLS拒否、migration、collector dropを表示する。
- localのp95は30試行以上で算出し、`no_data`をPASSにしない。ただし単一Macのresource競合値をproduction capacity／SLO証跡へ転用しない。
- `ops/local/verify-local-environment.mjs`は構成、health、migration、RLS、OIDC、MFA、session再起動、5 pool、失効、object、offline、telemetry、production非到達を検査し、1つでも未測定なら非0終了する。

### 2.8 Supply chain、platform、resource

- Apple Silicon（arm64）をprimary gate、Intel（amd64）を互換gateとする。全採用imageは両architectureのdigestをlockへ記録し、architectureごとのdigest差を許容する。QEMU emulationだけをamd64合格にしない。
- host prerequisiteはmacOS、Compose v2互換runtime、Node.js 22、mkcert、空きdisk／memoryである。最小versionと必要資源は実装spikeで測り、`ops/local/doctor`が不足を変更前に報告する。
- root権限、Docker socketのBFF mount、`privileged`、host network、host filesystem全体のmountを禁止する。read-only root filesystem、capability drop、non-root userを可能なserviceで強制する。
- imageはdigest固定、source／license／SBOMを台帳化し、Critical／High vulnerability 0をacceptance条件にする。lock更新はbot任せに自動適用せず、起動・migration・E2E・security test後にreviewする。
- local標準runtimeもADR-0022のlicense gate対象とする。Phase 1はpermissive licenseを原則とし、dashboardはPersesを採用する。AGPL／SSPL／source-available componentへ置換する場合は、利便性だけで変更せずADR-0022が要求する個別ADRと法務確認を先に行う。
- 標準`up`はresource上限を持ち、host逼迫で未同期browser dataを破損させない。disk不足はwrite前に検知し、新規upload／queue受理を安全側へ停止する。

## 3. 検討した選択肢

| 選択肢 | 評価 | 結論 |
|---|---|---|
| Vite fixture＋spike DBの継続 | 軽いが認証、永続session、queue、object、RLS接合を通らない | デモ／unit用途だけに限定 |
| AWS accountへ常時接続 | service忠実度は高いがoffline、費用、credential誤用、production境界混入が起きる | stagingで実施。Mac標準には不採用 |
| LocalStack等でAWS APIを全面emulate | adapter差分は減るがIAM、KMS、managed可用性を再現せず、重さとlicense／機能差を抱える | 不採用。AWS adapterはstagingで受入 |
| KeycloakではなくOIDC mock | 軽いがPKCE、nonce、MFA、logout、key rotationの接合を証明しない | unit testだけ。IntegrationはKeycloak |
| session／queueごとに専用emulator | production製品に近づく一方、Mac資源と運用が増え、契約より製品挙動へtestが固定される | local adapter＋PG永続storeを採用 |
| S3互換object server | presigned APIは近いが、S3 IAM／lifecycle／consistencyを証明できず第三者serviceが増える | private filesystem adapterを採用。S3はstaging |
| 1つのPgBouncerで5 port | 設定は少ないがprocess障害と接続上限を共有し、優先度分離を過大評価する | 5独立instanceを採用 |
| Grafana dashboard | 機能は広いがPhase 1のpermissive-license原則に対する例外判断が必要になる | 標準はPerses。例外採用はADR-0022の手続を先行 |
| Kubernetes | production候補には使えるがMac反復環境にはresource／運用が過大 | Composeを採用 |

## 4. 帰結と残留リスク

**得られるもの**

- mockを外してもproduction共通のdomain、security、migration、RLS、同期protocolを日常的に検証できる。
- AWS credentialなしでOIDC、MFA、永続session、失効、object、queue、offline、telemetryのfailure testを反復できる。
- local固有差分がadapterとsupport schemaへ閉じ、AWS adapterとのcontract差を明示できる。
- stop／restart／resetの意味が分かれ、未同期data消失とProduction誤認を機械的に防げる。

**残留するもの**

- 全serviceは同じMac、Docker daemon、filesystem、電源にあり、HA、failure domain、RPO/RTOを証明しない。
- PostgreSQL session／queueはDynamoDB／SQSの条件書込、IAM、partition、visibility、managed failureを再現しない。
- filesystem objectはS3署名、bucket policy、versioning、inventory、lifecycleを再現しない。
- local key fileはextract可能であり、KMS/HSMのpolicy、non-exportability、監査、recoveryを証明しない。
- KeycloakはCognitoのtoken claim、Hosted UI、risk、admin／logout挙動と完全同一ではない。
- 単一Macの性能値はhost負荷に左右され、本番capacity planningへ使えない。

これらは欠陥の黙認ではなく、`local-integration`の証拠範囲である。ADR-0021の選択host用Staging、S6/S9実機、S7実TLS／DB／network、選択したprovider adapter、recovery set、手動security reviewを通るまでProduction releaseは`BLOCKED`のままとする。

## 5. 実装順序と完了条件

1. `component-lock`、doctor、secret bootstrap、Caddy／Keycloak／PG／5 PgBouncer／telemetryのCompose foundation
2. production全migration＋`local_support` migration、owner／role／RLS／trigger／pool検証
3. BFF local adapter（session/context、queue/DLQ、object、envelope key）と起動時guard
4. OIDC＋PKCE、MFA／step-up、logout／失効consumer、synthetic realm／tenant seed
5. production-built Web、same-origin HTTPS、写真、GIS fixture、PWA push／pull／outbox
6. restart、dependency停止、duplicate／out-of-order、pool飽和、disk不足、key rotation、orphan回収のfault test
7. OTel dashboard、PII canary、E2E、security／license／SBOM、arm64／amd64 gate
8. 管理者runbookの`up/status/stop/restart/reset/backup/diagnose`と自動acceptance

完了は、要求仕様§5.6.2の全項目が`verify-local-environment`で測定済みPASSとなり、High／Medium security finding 0、文書の未設定0、通常stop後のsession／queue／object／outbox保持、resetの明示拒否、production非到達を実証した時とする。ADRの採用は実装完了を意味しない。
