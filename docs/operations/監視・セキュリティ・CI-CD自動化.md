# 監視・セキュリティ・CI/CD自動化 操作手順

## 1. 実装範囲

本手順は、法域内OpenTelemetry、CloudWatch SLO／error-budget、運用alert、供給網検査、保護branch、build-once、stagingからproductionへの段階配備を操作する管理者向けrunbookである。

| 領域 | 実装 | fail-closed条件 |
|---|---|---|
| telemetry | BFF NodeSDK→同一ECS taskのADOT→東京CloudWatch／X-Ray。cookie、authorization、tenant/user、query本文を削除 | productionでtask-local以外のOTLP endpointを指定するとBFF起動失敗 |
| SLO | 99.5% availabilityの5分＋1時間14.4倍、30分＋6時間6倍burn-rate composite alarm。28日error-budget widget | request signal欠落はbudget 0、blocking alarm欠落はpromotion停止 |
| 運用alert | RDS WAL容量／archive age、失効・監査・同期DLQ、同期queue年齢、監査chain不一致、写真欠損／孤立、collector drop | 安全系custom probe未報告は`breaching`。0と未報告を同一視しない |
| CI | BFF/Web test・build、OpenTofu、運用policy、dependency、secret、IaC、filesystem、container scan | Critical/High、test、format、immutable pinのいずれか失敗でmerge不可 |
| supply chain | Web/BFF/migrationを一度buildし、BuildKit provenance＋SBOM、AWS KMS cosign署名、公開鍵digest、OCI digestをbundle化 | tag参照、署名／attestation／公開鍵digest不一致で配備不可 |
| delivery | staging migration＋配備→25項目受入→production 5%→25%→100%→24時間後finalize | ALARM、INSUFFICIENT_DATA、alarm欠落、観測期限超過でstableへ自動rollback |

`worker`、`pgbouncer`、`adot`は本repositoryでbuildするapplication成果物ではなく、OpenTofu入力でdigest固定するplatform imageである。ECR enhanced scanとrelease manifestのsecurity gateで検査し、未署名・由来不明のdigestを承認しない。

## 2. 初回設定

### 2.1 OpenTofuを適用する

stagingとproductionをそれぞれ[OpenTofu手順](../../infra/opentofu/README.md)どおりapplyし、次を法域内のops evidence bucketへ保存する。

```bash
cd infra/opentofu
tofu output -json deployment_manifest > deployment-manifest.json
aws s3 cp deployment-manifest.json "s3://<ops-evidence-bucket>/releases/deployment-manifest.json" --region ap-northeast-1
```

manifestの`environment`、`deployment_id`、`account_id`、`region=ap-northeast-1`を二人で照合する。別環境のmanifestを流用しない。

### 2.2 GitHub Environment変数を登録する

Repository Settings → Environmentsで次を設定する。値はOpenTofuの`deployment_manifest`とAWS accountから転記し、秘密値をRepository variableへ置かない。

| Environment | Variables |
|---|---|
| `staging` | `AWS_DEPLOY_ROLE_ARN`、`AWS_ACCOUNT_ID`、`ECR_NAMESPACE=isas-jp-stg`、`ARTIFACT_SIGNING_KEY_ARN` |
| `production-canary` | `AWS_DEPLOY_ROLE_ARN`（production OpenTofu output） |
| `production` | `AWS_DEPLOY_ROLE_ARN`（production OpenTofu output） |

長寿命AWS access keyは登録しない。ActionsはGitHub OIDCで環境別roleを引き受ける。

### 2.3 branch／承認gateを設定する

GitHub userまたはteamの数値IDを取得し、stagingは1名以上、productionは異なる2名以上を指定する。

```bash
gh api users/<github-user> --jq .id
export STAGING_REVIEWERS_JSON='[{"type":"User","id":111111}]'
export PRODUCTION_REVIEWERS_JSON='[{"type":"User","id":111111},{"type":"User","id":222222}]'
ops/configure-github-controls.sh ichisemasashi/ISAS
```

この操作は`main`へ最新base、CI 7 check、2承認、CODEOWNERS、署名commit、linear history、会話解決を要求し、force-push／deleteを禁止する。`staging`、`production-canary`、`production`には自己承認禁止のreviewer gateを設定する。2人目が未登録ならproduction gate設定を成功扱いにせず、production releaseを`BLOCKED`のままにする。

## 3. 日常CIと脆弱性対応

PRを作ると`.github/workflows/ci.yml`が次を実行する。

1. BFF 84件以上、Web 44件以上のtestとproduction build。
2. `npm audit`／`pnpm audit`のHigh/Critical gate。
3. Trivy filesystem secret・misconfiguration・dependency scan。
4. OpenTofu format／validateとrelease policy test。
5. Web、BFF、migration imageのlocal buildとcontainer High/Critical scan。

Actionsやbase imageをtagだけへ戻す変更は`node ops/check-ci-policy.mjs`が拒否する。Dependabot PRも通常PRと同じreviewを行い、自動mergeでsecurity／migration／release policyを迂回しない。

scan失敗時はActionsの該当jobを開き、package、installed version、fixed version、image digestをticketへ記録する。Critical/Highをwaiverしてmergeせず、lockfileまたはdigestを更新して全jobを再実行する。

## 4. build-onceとstaging

### 4.1 release成果物を一度だけbuildする

`main`上のreview済みcommitへSemVer tagを作るか、Actionsの`Build release once`を手動実行する。手動例：

```bash
gh workflow run build-release.yml -f version=v1.1.0
gh run list --workflow build-release.yml --limit 5
gh run watch <build-run-id> --exit-status
```

workflowは3 imageをECRへpushし、各digestをTrivyで再検査し、SPDX JSON SBOM、BuildKit SLSA provenance、KMS cosign署名／attestationを作る。出力artifact `build-manifest-<commit>`の`source_commit`、`artifact_set_digest`、3つの`reference`がすべて`@sha256:`であることを確認する。

### 4.2 release manifestを承認する

`ops/release-manifest.example.json`をGit管理外で複製し、実証拠と二人の承認を記録する。`artifacts`のWeb/BFF/migration digestはbuild manifestと一致させ、platform imageもreleaseのsecurity evidenceへ含める。

```bash
node ops/check-release-readiness.mjs /secure/path/release-manifest.json
aws s3 cp /secure/path/release-manifest.json "s3://<staging-ops-evidence>/releases/v1.1.0.json" --region ap-northeast-1
```

### 4.3 stagingへ同じdigestを配備する

```bash
gh workflow run deploy-staging.yml \
  -f build_run_id=<build-run-id> \
  -f release_manifest_s3_uri=s3://<staging-ops-evidence>/releases/v1.1.0.json \
  -f deployment_manifest_s3_uri=s3://<staging-ops-evidence>/releases/deployment-manifest.json
```

workflowは署名・SBOM attestation・公開鍵digestを再検証し、migration checksum ledgerを確認して未適用分だけを実行する。checksum driftまたは`applying`の中断記録があれば自動再実行せず停止する。Web/BFFは同一digestで更新される。

配備後に[AWS staging受入手順](AWS-staging受入手順.md)の25項目を採取し、`node ops/check-staging-acceptance.mjs <evidence>`のPASSをops evidence bucketへ保存する。24時間を超えた証拠はproductionに使えない。

## 5. production段階配備

### 5.1 5%→25%→100%

```bash
gh workflow run promote-production.yml \
  -f build_run_id=<build-run-id> \
  -f release_manifest_s3_uri=s3://<staging-ops-evidence>/releases/v1.1.0.json \
  -f staging_acceptance_s3_uri=s3://<staging-ops-evidence>/releases/v1.1.0-staging-acceptance.json \
  -f production_deployment_manifest_s3_uri=s3://<production-ops-evidence>/releases/deployment-manifest.json
```

実行順は固定される。

1. staging証拠の全25 PASS、24時間以内、source commit一致を検査する。
2. production境界で公開鍵digest、cosign署名、SBOM attestationを再検証する。
3. forward migrationを専用taskで実行し、canary Web/BFFをweight 0でhealthyにする。
4. 5%を最低30分かつ1,000 transactionまで観測する。
5. 25%を最低2時間かつ1,000 transactionまで観測する。
6. production Environmentの二人承認後、100%を最低30分かつ1,000 transactionまで観測する。

各観測でblocking alarmが全件存在して`OK`であることを毎分確認する。`ALARM`、`INSUFFICIENT_DATA`、欠落、期限内にtransaction不足なら`progressive-deploy.sh rollback`がWeb/BFFをstable 100%へ戻し、`DeploymentRollback=1`を記録する。失敗後にjobだけ再実行せずincidentと原因を処置し、新しい承認から開始する。

### 5.2 24時間後にstable slotへ確定する

100%直後はcanary slotが全trafficを受け、stable slotは直前digestを保持する。24時間の強化監視後に次を実行する。

```bash
gh workflow run finalize-production.yml \
  -f build_run_id=<build-run-id> \
  -f production_deployment_manifest_s3_uri=s3://<production-ops-evidence>/releases/deployment-manifest.json
```

workflowは24時間経過と全blocking alarmの`OK`を再検査し、stable slotへ同じtask definitionを反映してtrafficをstable 100%／canary 0%へ戻す。24時間未満、state不一致、alarm異常では確定しない。

## 6. dashboard・alertの操作

OpenTofu outputの`operations.dashboard_name`を開く。既定名はstaging `ISAS-jp-stg-overview`、production `ISAS-jp-prod-overview`である。

```bash
aws cloudwatch get-dashboard --dashboard-name ISAS-jp-prod-overview --region ap-northeast-1
aws cloudwatch describe-alarms --alarm-name-prefix isas-jp-prod --region ap-northeast-1 \
  --query 'MetricAlarms[].{Name:AlarmName,State:StateValue,Reason:StateReason}'
aws sqs get-queue-attributes --queue-url <authorization-revocation-dlq-url> \
  --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible
```

custom verifierは結果が0件でもmetricを報告する。例：

```bash
export AWS_REGION=ap-northeast-1 ISAS_ENVIRONMENT=production DEPLOYMENT_ID=isas-jp-prod-01
ops/publish-operational-metric.sh WalArchiveAgeSeconds 120
ops/publish-operational-metric.sh AuditChainMismatches 0
ops/publish-operational-metric.sh AttachmentMissingObjects 0
ops/publish-operational-metric.sh AttachmentOrphanBacklog 0
```

これは検査そのものではなく、法域内scheduled verifierの出力adapterである。値を推測して手動で0にしない。WAL 15分超、監査不一致1以上、失効／監査DLQ 60秒超はSev-1、同期5分超とobject欠損／孤立はSev-2とする。custom verifierが未配備または停止してmetricが欠落する間、alarmが赤になるのは意図したfail-closed動作であり、production promotionを実行しない。

## 7. ローカル検証

AWS変更を伴わず、repository実装だけを確認する。

```bash
node ops/check-ci-policy.mjs
node --test ops/test/*.test.mjs
sh -n apps/bff/bin/run-migrations.sh ops/progressive-deploy.sh ops/monitor-progressive-delivery.sh
cd infra/opentofu && tofu fmt -check -recursive && tofu validate
```

実AWS apply、GitHub保護設定、workflow実行、SNS subscription確認は外部状態を変更する。対象account、environment、deployment ID、承認者を復唱してから実行する。
