# リリース運用手順

本ディレクトリはADR-0019〜0021を実行する当番者向けrunbookである。設計の正本はADR、作業順と停止条件の正本は本runbook、実releaseの証跡は`ops/release-manifest.json`（未追跡の実ファイル）とartifact registryに置く。

Production hostの最上位契約は[Productionホスト共通契約](Productionホスト共通契約.md)である。macOS、Linux、FreeBSDは同格の必須対象で、AWS文書は`provider=aws`を選んだ場合だけ使うadapter手順である。host別runbookが未完成のprofileは`BLOCKED`とし、AWS手順へ読み替えない。

| 手順 | 用途 |
|---|---|
| [Productionホスト共通契約](Productionホスト共通契約.md) | 3 OS共通の優先順位、manifest、受入gate、provider境界 |
| [FreeBSD Production runbook](FreeBSD-Production-runbook.md) | Jail、pkg／ports、rc.d、VNET／pf、ZFS、rctlを用いる構築・運用・復旧・受入 |
| [macOS Production runbook](macOS-Production-runbook.md) | `local-integration`と分離したlaunchd、sleep、更新、backup、復旧・受入 |
| [Linux Production runbook](Linux-Production-runbook.md) | support対象distributionのsystemd、firewall、暗号化、構築・復旧・受入 |
| [日本初期配備プロファイル](日本初期配備プロファイル.md) | 任意のAWS adapterにおける日本Phase 1の法域、製品、認証、保持・削除、CI/CD、監視の決定値 |
| [配備別サービス操作手順](配備別サービス操作手順.md) | 任意の日本AWS adapterの起動、終了、再起動、状態・log、backup、deploy commandと固定論理名。生成ARN等は署名済みmanifestから取得 |
| [デプロイ・ロールバック手順](デプロイ・ロールバック手順.md) | release candidateの事前確認、migration、5%→25%→100%、停止／rollback |
| [バックアップ・復旧手順](バックアップ・復旧手順.md) | recovery set作成、月次restore、四半期DR、RPO/RTO、実障害からの復旧 |
| [障害対応手順](障害対応手順.md) | Sev判定、役割、write freeze、法域内連絡、事後処置 |
| [AWS staging受入手順](AWS-staging受入手順.md) | OpenTofu apply、AuthContext migration、写真／PMTilesを含む25項目の実AWS受入と現在のBLOCKED理由 |
| [監視・セキュリティ・CI/CD自動化](監視・セキュリティ・CI-CD自動化.md) | GitHub保護、scan、SBOM／署名、dashboard／alert、staging→5%→25%→100%と自動rollbackの具体操作 |
| [3 OS native artifact受入runbook](../../ops/native-artifacts/README.md) | ADR-0024 R3のrunner要件、36 package build／scan／署名／install検証と停止条件 |

## 共通原則

- 対象を`deployment_id`、`jurisdiction`、`shard_id`、release digestで復唱し、別法域／別shardへの誤操作を防ぐ。
- production操作は二人で行い、実行者と確認者を分ける。break-glassはSev-1だけ、期限付き、全操作監査とする。
- 平文secret、token、個人情報、DB dumpをticket、chat、terminal transcriptへ貼らない。通知は非PII correlation IDを使う。
- 不明な状態を成功扱いしない。`no_data`、checksum不明、鍵不足、shard一覧不一致、未同期消失疑いは停止条件である。
- provider固有commandは[配備別サービス操作手順](配備別サービス操作手順.md)と`ops/recovery/`を正とし、stagingで証跡を得ていないcommandをproductionで初実行しない。

## Release manifest検査

例をコピーし、Git管理外の安全な作業領域で実release値を記録する。

```bash
cp ops/release-manifest.example.json ops/release-manifest.json
node ops/check-release-readiness.mjs ops/release-manifest.json
```

exampleは意図的に`BLOCKED`であり、コピー直後の検査は失敗する。全gateと証跡を実値で埋め、検査が0終了しても、二人承認とrunbookの判断を代替しない。

## 配備別の運用責任台帳

Production BFFは`ISAS_OPERATIONS_LEDGER`で指定したJSONを起動時に検査する。`ops/deployment-operations.example.json`を安全な配備管理領域へコピーし、RACI、support時間、Sev 1〜4の定義・初動時間、service owner、on-call、security／脆弱性／privacy窓口、EOLを実値で埋める。

```bash
node ops/check-deployment-operations.mjs /secure/isas/operations.json isas-jp-prod-01
export ISAS_OPERATIONS_LEDGER=/secure/isas/operations.json
```

placeholder、空欄、配備ID不一致があれば検査とBFF起動は失敗する。release manifestにも同じ台帳のSHA-256と証跡URIが必須である。repositoryのexampleを実台帳として使用しない。
