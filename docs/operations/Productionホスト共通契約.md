# Productionホスト共通契約

| 項目 | 内容 |
|---|---|
| 正本 | ユーザー確定要求：ISASはmacOS、Linux、FreeBSDのいずれでもProduction hostできること |
| 適用範囲 | Production、Staging、release manifest、IaC／宣言構成、運用runbook |
| 現在の状態 | 3 OSともProduction必須対象。個別profile定義とrunbookは実装済みだが、実host受入は未完了のためProduction releaseは`BLOCKED` |

## 1. 優先順位

本契約はprovider固有のADR、IaC、runbook、過去の配備台帳より上位に置く。AWSは利用可能な配備adapterの一つであり、必須providerでもProductionの唯一の正本でもない。`local-integration`はMac上の非本番検証profileを指すだけで、macOS Productionを禁止または置換しない。

## 2. 同格のProduction host profile

| `host_os` | Production方式 | 現在の実装・受入状態 |
|---|---|---|
| `linux` | support対象distribution上の署名済みnative service、systemd、AppArmor、nftables、LUKS2。ISAS runtimeはDocker／OCI daemon非依存 | [native実装](../../infra/hosts/linux/)・[runbook](Linux-Production-runbook.md)・静的検査済み。実host受入は未完了 |
| `macos` | macOS用Production service構成、OS起動管理、sleep／update／disk／暗号化／backup対策。`local-integration`とdata・secret・profileを分離 | [native実装](../../infra/hosts/macos/)・[runbook](macOS-Production-runbook.md)・静的検査済み。実host 2台の受入は未完了 |
| `freebsd` | FreeBSD Jail、native package／ports、rc.d、VNET／pf、ZFS、rctl | [定義](../../infra/hosts/freebsd/profile.json)・[runbook](FreeBSD-Production-runbook.md)実装済み、実host受入は未完了（KCOMP-H2） |

実装順は製品classの上下を意味しない。各profileはADR-0019〜0021の同じ業務、RLS、認証・失効、監査、SLO、backup／restore、release gateを満たす。

## 3. Provider adapter

AWS東京region向けOpenTofu、Cognito／DynamoDB／S3／SQS／KMS adapter、AWS用CI/CDと操作手順は、`provider=aws`を選んだ配備だけに適用する任意adapterである。他のhost profileの完了条件へAWS account、AWS managed service、ECR、AWS KMSを要求しない。各hostは同じ論理契約をnative serviceまたは明示した外部serviceで満たし、製品名とversionをdeployment manifestへ固定する。

## 4. 必須manifestと受入

全Production候補は少なくとも次を署名済みdeployment manifestへ記録する。

- `host_os`、OS／kernel version、architecture、service manager／isolation方式、filesystem、保存時暗号化
- provider／region（使用しない場合は`self-hosted`とsite／failure domain）、法域、shard、network入口
- IdP、DB／pool、session/context、object、queue、鍵、telemetry、backupの製品・version・secret参照
- source、artifact／package digest、SBOM、provenance、migration set、config schema
- backup／PITR／全損restore、RPO／RTO、SLO、security、実端末、UT、承認者の証跡

host別のStagingでinstall、reboot、upgrade、rollback、disk full、certificate更新、依存障害、backup、全損restore、共通E2Eを実行する。個別profileのrunbookと証跡がないOSをProduction対応済みと表示しない。

定義は`node ops/host-profiles/check-host-profile.mjs <profile.json>`で検査する。実host受入時は同commandの第2引数へ、`ops/host-profiles/acceptance.example.json`を基にしたGit管理外の証跡台帳を渡す。全gate、異なる2つのfailure domain、二人承認が揃わない限り検査は失敗する。
