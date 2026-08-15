# ISAS Japan Phase 1 OpenTofu

このroot moduleは、日本初期配備profileをAWS東京regionへ実装する。stagingとproductionは別AWS account、別backend、別variable fileで適用し、同一stateへ混在させない。現在のproduction profileは3 AZ、ECS Fargate、RDS PostgreSQL Multi-AZ DB cluster、Cognito、DynamoDB、S3、SQS、KMS、AWS Backup、CloudWatch／X-Ray、ECR、GitHub OIDCを採用する。

OpenTofu `1.12.5`とAWS provider `6.51.0`を固定している。upgradeはstagingの保存planと本書の受入を再実施する別changeとする。

## 1. 事前条件

- staging専用AWS account、Route 53 hosted zone、ALB用東京region ACM証明書、Cognito custom domain用`us-east-1` ACM証明書を用意する。後者はCognito APIの必須条件で、業務dataの法域外複製には使わない。
- AWS CLI profileは短期credential／IAM Identity Centerを使い、long-lived access keyを作らない。
- Web、BFF、worker、PgBouncer、ADOT、migrationのimageをbuild／scan／署名し、ECR digestを確定する。
- `tofu`、AWS CLI v2（`SetUserPoolMfaConfig`の`FactorConfiguration`対応版）、`jq`、`node`、`curl`、`shasum`を実行端末へ導入する。
- AWS利用料が発生する。特に3 NAT Gateway、RDS Multi-AZ DB cluster、Fargate、WAF、Backupを含むため、承認済みbudget／quotaを確認する。

次の確認で別account／別regionへの誤適用を止める。

```bash
aws sts get-caller-identity
aws configure get region
```

account IDがstaging台帳と一致し、regionが`ap-northeast-1`でなければ中止する。

## 2. backend bootstrap

backendはS3 versioning＋SSE-KMS、DynamoDB lock、OpenTofu native S3 lockfileを併用する。bootstrap自身は初回だけlocal stateを生成するため、二人作業で暗号化済み運用領域へ直ちに保管し、backend作成後は変更を禁止する。

```bash
cd infra/opentofu/bootstrap
tofu init
tofu plan \
  -var='expected_aws_account_id=123456789012' \
  -var='state_bucket_name=isas-jp-staging-123456789012-opentofu-state' \
  -var='lock_table_name=isas-jp-staging-opentofu-lock' \
  -out=bootstrap.tfplan
tofu apply bootstrap.tfplan
tofu output -json backend
```

出力を`environments/staging/backend.hcl`へ転記する。この実ファイルと`bootstrap.tfstate`はGitへ入れない。

## 3. staging planとapply

exampleをコピーして実値だけを編集する。placeholderのaccount、ARN、domain、digestを残さない。

```bash
cd infra/opentofu
cp environments/staging/backend.hcl.example environments/staging/backend.hcl
cp environments/staging/staging.tfvars.example environments/staging/staging.tfvars
tofu init -backend-config=environments/staging/backend.hcl
tofu fmt -check -recursive
tofu validate
tofu plan -var-file=environments/staging/staging.tfvars -out=staging.tfplan
tofu show -no-color staging.tfplan > staging.plan.txt
```

plan reviewerは次を確認する。

1. account ID、`ap-northeast-1`、3個の相異なるAZ IDが正しい。
2. public resourceはALBだけで、ECSとRDSにpublic IPがない。
3. KMSはsingle-region、S3 CRR、DynamoDB global table、CloudFrontがない。
4. RDSはwriter 1＋reader 2のMulti-AZ DB cluster、暗号化、backup 30日である。
5. image参照がすべて`@sha256:`、GitHub trustが対象repositoryの`environment:staging`だけである。
6. productionで`deletion_protection=false`または`force_destroy=true`になるplanを拒否する。

AWS provider `6.51.0`はCognito WebAuthnの`FactorConfiguration`をまだ公開していないため、この1項目だけ`terraform_data.cognito_webauthn_mfa`がAWS CLIの`SetUserPoolMfaConfig`を呼ぶ。値は`MULTI_FACTOR_WITH_USER_VERIFICATION`に固定し、受入collectorが`GetUserPoolMfaConfig`でread-backする。providerが属性を公開した時点で通常resourceへ移し、このadapterを削除する。

二人承認後、保存したplanだけを適用する。

```bash
tofu apply staging.tfplan
tofu output -json deployment_manifest > /tmp/isas-staging-deployment-manifest.json
```

`apply`後、Secrets Managerの5個のPgBouncer用secretへmigration担当が生成した個別DB role credentialをJSON `{"username":"<role>","password":"<random>"}` として投入する。P1の`username`は必ず`app_user`とし、5 secretを同じcredentialにしない。BFF taskはECS secret selectorで各JSON keyだけを対応する`ISAS_DB_<CLASS>_{USER,PASSWORD}`へ注入する。RDS master secretをapplicationへ渡さず、値をterminal、plan、ticketへ表示しない。

同時に`<deployment>/application/actor-pseudonym-key`へ32 byte以上のランダム値を投入する。値をshell履歴へ残さないため、承認済みsecret生成手段から一時ファイルを介さず`aws secretsmanager put-secret-value --secret-id <ARN> --secret-string fileb:///dev/stdin`へ渡し、投入後は`describe-secret`の`LastChangedDate`だけを確認する。BFFはこの値、Cognito、DynamoDB `user-index`、SQSのDLQ、token/session KMS key、Auth-P1 DB関数をlisten前にread-backし、どれか不整合なら起動しない。

BFF imageには`bff_runtime_adapter_module`（既定`/app/runtime-adapters/aws.mjs`）を含める。module欠落、adapter契約不一致、5 poolのrole不一致、P1のread replica接続のどれかがあればBFFはlisten前に失敗する。ECS container healthは`/health/live`、ALB traffic判定は全依存を検査する`/health/ready`を使用し、ECSの30秒停止猶予内でBFFが15秒drainを完了する。

## 4. AuthContext migration

`infra/images/migration/Dockerfile`はmigration専用imageである。最初にNOLOGIN／NOBYPASSRLS owner roleを用意し、`0000_auth_context_v1.sql`から`0010_identity_runtime.sql`までchecksum ledger付きで順番に適用する。その後、fixtureをtransaction rollbackするAuthContext／identity runtime verifyと`production_auth_context_security.sql`を実行する。`0001`以降の既存verifyはfixtureを永続化するためstaging／productionでは実行せず、使い捨てDBで`RUN_DESTRUCTIVE_FIXTURE_VERIFICATION=true`かつ`ALLOW_DISPOSABLE_DATABASE=true`を明示したCIだけで実行する。

最後の検査は次をDB catalogから判定し、1件でも不一致ならECS taskを非0終了させる。

- AuthContext 9表のownerが`auth_context_owner`
- 全9表がRLS有効かつ`FORCE ROW LEVEL SECURITY`
- append-only監査表以外の全AuthContext表に有効な`z_auth_change_audit` trigger
- owner roleがNOLOGIN、非superuser、NOBYPASSRLS
- OIDC主体解決、所属／scope導出、失効outboxの6 runtime関数が存在
- PostGISが正確に`3.4.6`

backfillは[migration backfill](../../apps/bff/migrations/backfill/0000_auth_context_v1_backfill.sql)をreview済みCSV staging後に別taskで実行する。rollbackは[安全rollback](../../apps/bff/migrations/rollback/0000_auth_context_v1_rollback.sql)が業務表／永続userを検出して停止するため、productionの通常rollbackはapplicationのroll-forwardを優先する。

## 5. staging受入

apply直後にmigrationを一度だけ実行し、AWS APIの実値を収集する。

```bash
chmod 0555 scripts/collect-staging-evidence.sh
scripts/collect-staging-evidence.sh \
  123456789012 \
  staging.tfplan \
  evidence/staging-acceptance.json
```

collectorはmigration ECS taskも起動する。18検査がすべて`PASS`の場合だけ0終了する。AWS Backupのrecovery point、SNS購読確認、HTTPS healthがまだない場合は正常に`BLOCKED`となるので、backup／通知／DNSを実動確認して再実行する。

```bash
node ../../ops/check-staging-acceptance.mjs evidence/staging-acceptance.json
```

実証跡はGitへ入れず、KMS署名後にops evidence bucketへObject Lock付きで保存する。exampleは意図的に全項目`BLOCKED`である。

## 6. destroy禁止とdrift

productionで`tofu destroy`を使わない。staging削除もchange承認、DB final snapshot、S3 object／legal hold確認後にresource単位で行う。日次CIはread-only credentialで`tofu plan -detailed-exitcode`を実行し、exit 2をdrift ticketへ送る。console変更はSev-1の時限break-glassだけとし、復旧後に宣言構成へ戻す。
