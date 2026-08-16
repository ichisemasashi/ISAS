# Recovery set・復旧演習

`create-recovery-set.sh`は隔離AWS stagingだけを対象に、同じ`recovery_set_id`でRDS、DynamoDB、保護対象S3のon-demand backup jobを開始し、RDS PITR時刻、S3 inventory構成、queue checkpoint、監査anchor、deployment／shard／migration digest、backup KMS参照を一つのmanifestへ束ねる。

```sh
ops/recovery/create-recovery-set.sh 123456789012 \
  ops/runtime/isas-jp-stg/deployment-manifest.json \
  /secure/recovery/rs.json
```

出力直後は`CAPTURED`であり、backup jobの完了やrestore成功を意味しない。全jobの`COMPLETED`、最新S3 inventory、二者承認を確認してから受入証跡へ転記する。実行器はproduction環境を拒否する。証跡objectにはbucket既定のObject Lock保持が適用される。
