# 実データ移行rehearsal runner

このrunnerは、個人情報を除去または仮名化した実CSVを、専用のstaging tenantへ`圃場 → 作業記録 → 農薬履歴`の順で投入する。BFFの認証Cookie、context、CSRF、RLSを通るため、DBへの直接投入には使わない。

## 事前条件

- rehearsal専用tenantを空の状態で用意する。
- 全件を参照できる移行担当者と、field-groupを限定した照合担当者を別に用意する。
- 両者がブラウザでstagingへログインし、Netscape形式のCookie jarを安全な一時領域へ書き出す。
- 実CSV、同意原本、Cookie jarはGitへ追加しない。

`rehearsal-manifest.example.json`を実CSVと同じGit外ディレクトリへコピーし、列mapping、検査前の予想件数、確定後の全scope／制限scope出力件数を記入する。3 CSVはmanifestと同じディレクトリ直下へ置く。

## 実行

```sh
export ISAS_MIGRATION_BASE_URL='https://staging.example.invalid'
export ISAS_MIGRATION_TENANT_ID='rehearsal-tenant-uuid'
export ISAS_MIGRATION_COOKIE_FILE='/secure/tmp/migration-admin.cookies'
export ISAS_MIGRATION_RESTRICTED_COOKIE_FILE='/secure/tmp/restricted-verifier.cookies'
export ISAS_MIGRATION_SOURCE_COMMIT="$(git rev-parse HEAD)"
export ISAS_MIGRATION_DEPLOYMENT_ID='staging-deployment-id'
python3 ops/data-migration/run-rehearsal.py /secure/rehearsal/manifest.json /secure/rehearsal/evidence.json
```

runnerは次を照合し、一つでも不一致なら停止する。

1. 実由来区分、staging tenant、3種の順序
2. ローカルCSV行数とmanifest件数
3. BFF検査結果の`全件 = 有効 + 重複 + エラー`
4. 同一`Idempotency-Key`再送で同じjobが返ること
5. 確定件数と確定時競合重複
6. 全scopeと制限scopeのCSV出力件数

出力証跡は意図的に`PARTIAL`で生成される。実行担当者と独立照合者が原本、取込結果、RLS範囲を照合して承認を追記した後、受入gateで`PASS`にする。CSV本文、氏名、メール、Cookie、CSRF tokenは証跡へ転記しない。
