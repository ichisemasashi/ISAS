# Mac本番相当ローカル環境 要求追補

| 項目 | 内容 |
|---|---|
| 状態 | **v1.1へ統合済みの履歴文書**。現行要求は親文書§2／§5.6／§9 #23を正とする |
| 日付 | 2026-08-16 |
| 親文書 | [営農支援Webアプリケーション 要求仕様書 v1.1](農業営農支援システム_要求仕様書.md) |
| 関連 | [ADR-0019 インフラ・運用](design/ADR/ADR-0019-インフラ・運用.md)／[ADR-0021 テスト・リリース方式](design/ADR/ADR-0021-テスト・リリース方式.md)／[責任境界設計](design/Mac本番相当ローカル環境_責任境界設計.md) |

---

> **統合注記**：本書はv1.1改版前に用語と責任境界を先行確定した記録である。2026-08-16に全規範内容を要求仕様書v1.1へ統合したため、以後の変更は要求仕様書本体へ行う。本書だけを改版しない。

## 1. 目的

開発者または検証担当者のMac 1台で、デモfixtureではなく、production buildのWeb、Production BFFの業務core、PostgreSQL 16＋PostGIS、認証、永続session/context、object、queue、暗号化、監視、PWA offline同期を接続して反復検証できる環境を提供する。

この環境は、実AWS stagingへ進む前に構成・migration・RLS・認証・同期・再起動耐性を早く検出するための**非本番integration環境**である。本番運用、法令適合、可用性、災害復旧またはAWS managed service自体の適合を証明するものではない。

## 2. 正式用語

| 用語 | 定義／使用規則 |
|---|---|
| **Mac本番相当ローカル統合環境** | 本追補が定める正式な日本語名称。通常の文書・画面ではこの名称を使う。 |
| **macOS local integration profile** | 英語名称。構成識別子は`local-integration`とする。 |
| 本番相当 | application、protocol、schema、主要依存の契約を本番に近づける意味。HA、規模、provider固有機能、運用実績まで同等という意味ではない。必ず「ローカル統合環境」と組み合わせる。 |
| デモ環境 | `?ut=1`等のfixture／mockを用いる画面・UX確認環境。業務API、認証、RLS、永続同期の成立証拠にしない。 |
| Local/PR環境 | unit／component test向けの高速環境。外部依存stub、単一process、揮発dataを許す。 |
| Integration環境 | 複数componentの契約を実装相当の依存で検証する非本番環境。Mac本番相当ローカル統合環境はこの一種。 |
| Staging環境 | production候補artifactを、本番相当のTLS、network、規模、provider service、運用手順で受入する共有環境。Mac環境で代替しない。 |
| Production環境 | ADR-0019の本番HA profileとADR-0021のrelease gateを満たし、正式承認された法域デプロイ。 |

`Mac本番環境`、`ローカル本番`、`production on Mac`という表記は禁止する。運用画面、ログ、証跡には常に`local-integration`を含め、productionとの取り違えを防ぐ。

## 3. 要求範囲

### 3.1 必須対象

- productionと同じsourceからbuildしたWeb assetsと、同じ業務domain／security middlewareを通るBFF runtime
- PostgreSQL 16＋対象PostGIS、正式migration、RLS／FORCE RLS／owner／trigger／`security_invoker`検査
- P0、Auth-P1、P1、P2、Opsの接続経路分離とpool飽和試験。ただし単一Mac上の資源分離は論理分離であることを明示する
- OIDC authorization code＋PKCE、nonce／issuer／audience検証、MFA／step-up、logout、権限失効
- 再起動後も残るsession/context、object、queue／DLQ、idempotency ledger、監査、業務DB
- 写真upload、短期参照、MIME／signature検査、孤立object回収
- PWA cache／outbox、offline保存、push／pull、競合、失効、未同期data保持
- HTTPS入口、same-origin routing、readiness／liveness、構造化log、trace／metrics
- `up`、`status`、`stop`、`restart`、明示的`reset`と、合否を返す自動検証command

### 3.2 非対象

- 2 failure domain以上、standby、WAL archive／PITR、実RPO 15分／RTO 4時間、水平増減の実証
- Cognito、DynamoDB、S3、SQS、KMS等のAWS managed serviceそのものの互換性・可用性・IAM境界の証明
- HSM／Secure Enclave相当の鍵非抽出性、実端末紛失、iOS／Android実機、実provider障害の証明
- 本番規模、実internet、実tile provider、実email／webhook、法域外部serviceを含む性能・契約試験
- 実データ、個人データ、production credential、production endpointを用いる試験
- production release、staging受入、法令・契約・データレジデンシー適合の代替

## 4. 安全境界

1. runtimeはprofileを`local-integration`として明示し、画面の常時banner、health応答、log、証跡へ表示する。
2. AWS production account、production DB／IdP／object／queue endpoint、production credentialを検出した場合は起動をfail closedする。local profileからproduction networkへ到達させない。
3. dataはversion管理されたsynthetic seedだけを既定とする。本番dumpと実個人データの投入を禁止する。
4. 通常の`stop`／`restart`はvolumeと未同期outboxを保持する。消去は対象を表示した明示的`reset`だけに限定する。
5. local CAの秘密鍵、cookie署名鍵、envelope鍵、test client secretはGitへ保存しない。生成物の保存場所、権限、rotation、破棄手順を運用文書で定める。
6. LAN公開は既定offとする。有効化時はTLS、host firewall、許可CIDR、認証を必須とし、`0.0.0.0`への無条件公開を標準手順にしない。
7. localのPASSをstaging／productionの合格としてrelease manifestへ登録してはならない。証跡には`evidence_scope=local-integration`を付ける。

## 5. 責任境界

| 責任主体 | 責任を負う事項 | 責任を負わない事項 |
|---|---|---|
| ISAS製品保守者 | Compose／script／local adapter、version固定、構成schema、migration、synthetic seed、安全guard、検証suite、文書をrepositoryで保守する | 個々のMac、Docker Desktop、LAN、端末管理、利用者が投入したdataの管理 |
| ローカル環境管理者 | 対象Mac、Docker runtime、disk、local DNS／証明書、firewall、生成secret、volume、起動停止、更新、証跡、resetを管理する | ISAS sourceの欠陥修正、production承認、法的判断 |
| 検証責任者 | test計画、合否基準、失敗記録、local証跡のscope明示、stagingへ持ち越す残riskを承認する | local結果だけによるproduction release承認 |
| 本番配備・サービス運用者 | provider、法域、HA、IAM/KMS、backup／DR、監視、法令・契約、実データ、release gateを本番環境で成立させる | Mac local profileを本番として運用すること |
| 利用者 | 割り当てられたtest accountと端末を適切に扱い、実データを投入せず、異常を報告する | infrastructure、鍵、migration、resetの管理 |

同一人物が複数の責任主体を兼ねる場合も、承認記録上の役割は分ける。二人承認が必要なproduction gateを、一人のlocal作業結果で代替してはならない。

## 6. この確定の完了条件

- 正式名称、環境分類、禁止表記が一意である
- 必須対象と非対象が、機能・security・運用・品質の各観点で区別されている
- repository保守、Mac運用、検証、本番運用、利用の責任主体が定義されている
- localで証明できること／できないことと、stagingへ残すgateが明示されている
- ADR-0019の本番HA profileとADR-0021の環境分類を変更または緩和していない

構成製品、component配置、local adapter方式、秘密管理方式の裁定は後続のADR-0023で行う。
