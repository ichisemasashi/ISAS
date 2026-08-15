# AWS staging受入手順

## 現在の状態

OpenTofu root module、migration image、実AWS証跡collector、18項目の判定gateは実装済みである。AWS staging accountの認証情報、DNS／ACM実値、署名済みimage digest、課金承認がこのrepositoryにはないため、**実AWS applyとstaging受入は未実行で`BLOCKED`**である。ローカル検査の成功をstaging受入へ読み替えない。

## 実施順

1. [OpenTofu README](../../infra/opentofu/README.md)のbackend bootstrapをstaging accountで行う。
2. `backend.hcl`と`staging.tfvars`へ台帳の実値を設定する。
3. `tofu fmt -check -recursive`、`tofu validate`、保存planを実行し、二人でplanを承認する。
4. `tofu apply staging.tfplan`を実行する。
5. PgBouncer用の5 DB role secretを個別に投入する。
6. `collect-staging-evidence.sh`を実行する。collectorがmigration taskを起動し、AuthContextを`0000`として最初に適用する。
7. backup recovery pointとSNS subscriptionが未準備なら、初回backup完了と通知確認後にcollectorを再実行する。
8. `STAGING ACCEPTANCE: PASS (18/18)`、証跡digest、実行者、確認者、日時をchange ticketへ記録する。
9. 証跡JSONと保存planのhashをops evidence bucketへ保存し、KMSで署名する。

## 必須18項目

| 区分 | 検査 |
|---|---|
| 配備境界 | account／東京region、3 AZ ID、single-region KMS |
| database | RDS PostgreSQL Multi-AZ 3 member、PostgreSQL／PostGIS |
| migration | version `0000` AuthContext、owner／FORCE RLS／監査trigger本番SQL |
| runtime | ECS全serviceのdesired=running、全image digest固定 |
| 認証 | Cognito MFA／advanced security |
| data | DynamoDB SSE／PITR、S3非公開／versioning／KMS、SQS DLQ／KMS |
| 運用 | 完了backup recovery point、alarm＋確認済みSNS購読 |
| 入口／CI | HTTPS health、WAF association、GitHub staging Environment OIDC |

`FAIL`、`BLOCKED`、証跡文字列なし、未知の検査、commit／plan digest不正のどれか1件でもgateは非0終了する。
