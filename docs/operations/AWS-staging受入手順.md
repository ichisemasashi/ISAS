# AWS staging受入手順

## 現在の状態

OpenTofu root module、migration image、静的shard manifest署名、private PMTiles公開、実AWS証跡collector、25項目の判定gateは実装済みである。AWS staging accountの認証情報、DNS／ACM実値、署名済みimage digest、実日本PMTiles／NOTICE／SBOM、課金承認がこのrepositoryにはないため、**実AWS applyとstaging受入は未実行で`BLOCKED`**である。ローカル検査の成功をstaging受入へ読み替えない。

## 実施順

1. [OpenTofu README](../../infra/opentofu/README.md)のbackend bootstrapをstaging accountで行う。
2. `backend.hcl`と`staging.tfvars`へ台帳の実値を設定する。
3. `tofu fmt -check -recursive`、`tofu validate`、保存planを実行し、二人でplanを承認する。
4. `tofu apply staging.tfplan`を実行する。
5. PgBouncer用の5 DB role secretと、32 byte以上のactor pseudonym keyを個別に投入する。
6. `sign-shard-manifest.sh <account-id>`を実行し、S3の静的manifestへKMS ECDSA署名を付ける。
7. `publish-offline-map.sh <account-id> <japan.pmtiles> <OSM-NOTICE.txt> <sbom.json>`を実行する。scriptがplanのversion／SHA-256と実ファイルを照合し、private S3へ公開後にMIME、metadata、SSE-KMSをread-backする。
8. `collect-staging-evidence.sh`を実行する。collectorがmigration taskを起動し、AuthContextを`0000`として最初に適用する。
9. backup recovery pointとSNS subscriptionが未準備なら、初回backup完了と通知確認後にcollectorを再実行する。
10. `STAGING ACCEPTANCE: PASS (25/25)`、証跡digest、実行者、確認者、日時をchange ticketへ記録する。
11. 証跡JSONと保存planのhashをops evidence bucketへ保存し、KMSで署名する。

## 必須25項目

| 区分 | 検査 |
|---|---|
| 配備境界 | account／東京region、3 AZ ID、AWS KMS HSM-backed single-region key、TLS listener |
| database | RDS PostgreSQL Multi-AZ 3 member、reader endpoint、RDS管理WAL archive／30日PITR、PostgreSQL／PostGIS |
| migration | version `0000`〜`0010`、owner／FORCE RLS／監査trigger／identity runtime関数の本番SQL |
| runtime | ECS全serviceのdesired=running、Web／BFF各2 AZ以上、全image digest固定 |
| 認証 | Cognito MFA／advanced security／WebAuthn user verification／public code flow／必須scope／token revocation |
| data | DynamoDB SSE／PITR、S3非公開／versioning／KMS、VPC-only添付access point、PMTiles＋NOTICE＋SBOMのdigest／ODbL／SSE-KMS、SQS／DLQ／redrive allow／quarantine、KMS署名済み静的shard manifest |
| 運用 | 完了backup recovery point、alarm＋確認済みSNS購読 |
| 入口／CI | HTTPS health、WAF association、GitHub staging Environment OIDC |

`FAIL`、`BLOCKED`、証跡文字列なし、未知の検査、commit／plan digest不正のどれか1件でもgateは非0終了する。
