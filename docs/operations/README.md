# リリース運用手順

本ディレクトリはADR-0019〜0021を実行する当番者向けrunbookである。設計の正本はADR、作業順と停止条件の正本は本runbook、実releaseの証跡は`ops/release-manifest.json`（未追跡の実ファイル）とartifact registryに置く。

| 手順 | 用途 |
|---|---|
| [配備別サービス操作手順](配備別サービス操作手順.md) | 起動、終了、再起動、状態・log確認の実command。provider選定後に実値化するtemplate |
| [デプロイ・ロールバック手順](デプロイ・ロールバック手順.md) | release candidateの事前確認、migration、5%→25%→100%、停止／rollback |
| [バックアップ・復旧手順](バックアップ・復旧手順.md) | backup取得、月次restore、四半期DR、実障害からの復旧 |
| [障害対応手順](障害対応手順.md) | Sev判定、役割、write freeze、法域内連絡、事後処置 |

## 共通原則

- 対象を`deployment_id`、`jurisdiction`、`shard_id`、release digestで復唱し、別法域／別shardへの誤操作を防ぐ。
- production操作は二人で行い、実行者と確認者を分ける。break-glassはSev-1だけ、期限付き、全操作監査とする。
- 平文secret、token、個人情報、DB dumpをticket、chat、terminal transcriptへ貼らない。通知は非PII correlation IDを使う。
- 不明な状態を成功扱いしない。`no_data`、checksum不明、鍵不足、shard一覧不一致、未同期消失疑いは停止条件である。
- provider固有commandは配備者が承認したadapter runbookに置く。本書の`<backup-adapter>`等は入力位置であり、そのままshellへ貼り付けない。

## Release manifest検査

例をコピーし、Git管理外の安全な作業領域で実release値を記録する。

```bash
cp ops/release-manifest.example.json ops/release-manifest.json
node ops/check-release-readiness.mjs ops/release-manifest.json
```

exampleは意図的に`BLOCKED`であり、コピー直後の検査は失敗する。全gateと証跡を実値で埋め、検査が0終了しても、二人承認とrunbookの判断を代替しない。
