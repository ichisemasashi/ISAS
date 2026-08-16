# Recovery set・復旧演習

`create-recovery-set.sh`は隔離AWS stagingだけを対象に、同じ`recovery_set_id`でRDS、DynamoDB、保護対象S3のon-demand backup jobを開始し、RDS PITR時刻、S3 inventory構成、queue checkpoint、監査anchor、deployment／shard／migration digest、backup KMS参照を一つのmanifestへ束ねる。

```sh
ops/recovery/create-recovery-set.sh 123456789012 \
  ops/runtime/isas-jp-stg/deployment-manifest.json \
  /secure/recovery/rs.json
```

出力直後は`CAPTURED`であり、backup jobの完了やrestore成功を意味しない。全jobの`COMPLETED`、最新S3 inventory、二者承認を確認してから受入証跡へ転記する。実行器はproduction環境を拒否する。証跡objectにはbucket既定のObject Lock保持が適用される。

## 隔離restoreと受入gate

`restore-request.example.json`をGit外へコピーし、承認済みrecovery point、隔離VPC、resource別restore metadata、異なる二人の承認を記入する。隔離VPCがapplication VPCと同じ場合、外部配送が`sink_only`でない場合、recovery setが`APPROVED`でない場合は開始しない。

```sh
ops/recovery/start-isolated-restore.sh 123456789012 deployment-manifest.json approved-recovery-set.json approved-restore-request.json /secure/recovery/restore-started.json
```

この出力も`STARTED`であり合格証跡ではない。全restore job完了後に、schema、RLS/FORCE/owner、trigger/security-invoker、監査chain、object hash、queue/cursor、冪等、失効、tenant越境、合成transactionを検査し、月次restoreと四半期DRの実測を`operational-acceptance.example.json`へ記録する。

```sh
node ops/recovery/check-operational-acceptance.mjs /secure/recovery/operational-acceptance.json
```

gateはRPO 900秒、RTO 14,400秒、実stagingでの起動・終了・rolling restart・依存障害・incident演習、実CSV rehearsal、実参加者UT、連絡先・監視・運用台帳、三者承認をすべて要求する。
