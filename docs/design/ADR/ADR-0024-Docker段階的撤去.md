# ADR-0024：Dockerを段階的に撤去しnative artifactへ統一

| 項目 | 内容 |
|---|---|
| ステータス | **採用（クローズ v2、R1完了・R2以降進行中）** |
| 日付 | 2026-08-21 |
| 由来 | ユーザー確定要求「Dockerをプロジェクトから段階的に撤去する」 |
| 関連 | [ADR-0019](ADR-0019-インフラ・運用.md)、[ADR-0021](ADR-0021-テスト・リリース方式.md)、[ADR-0023](ADR-0023-Mac本番相当ローカル統合環境.md)、[Productionホスト共通契約](../../operations/Productionホスト共通契約.md) |

## 1. 背景

Dockerはユーザー要求ではない。過去の方式判断で、Mac `local-integration`の反復性、PostgreSQL 16＋PostGISの使い捨て検証、CIのbuild再現性、任意AWS/ECS adapterのartifact形式を揃える目的で導入された。しかし、その結果、非本番操作ガイドがDockerをISASの必須runtimeのように見せ、daemon停止がDB検証や開発工程を止める依存を作った。

macOS Productionはlaunchd、Linux Productionはsystemd、FreeBSD ProductionはJail／rc.dのnative profileをすでに正本としている。これに合わせ、非本番、CI、provider adapterからもDocker依存を段階的に撤去する。

## 2. 決定

1. 最終状態を、tracked Dockerfile、Compose定義、Docker daemon／socket呼出し、OCI image build／registry／container配備を**active dependencyとして0件**とする。
2. Docker DesktopをPodman、Colima等の別container runtimeへ単純置換しない。新しいcontainer runtimeを採用する場合は、ユーザー承認を伴う別ADRを必要とする。
3. Web、BFF、migrationはOS／architecture別のversion固定native artifactとしてbuildし、SBOM、provenance、署名、checksumを付ける。macOSはlaunchd、Linuxはsystemd、FreeBSDはJail／rc.dで起動する。
4. `local-integration`は、production共通artifactを非特権のlaunchd user agentとloopback networkで起動し、data／secretを`.local/native/`へ分離する方式へ移行する。既存Compose stackは移行完了までの一時互換経路であり、新機能の正本にしない。
5. PostgreSQL 16＋PostGIS spikeは、hostへ導入したversion固定native binaryと一時data directoryを使うrunnerへ移行する。test終了時にprocessと一時dataだけを破棄し、既存結果logは履歴証拠として保持する。
6. CI／releaseはhost OS別runnerでnative artifactを一度buildし、同一artifactをStagingからProductionへ昇格する。container scanはfilesystem／package／SBOM scanへ置換し、署名・provenance gateを弱めない。
7. 任意AWS adapterはECS／ECRを共通前提にせず、Linux native artifactをsystemdで動かすVM adapterへ置換するか、利用者がAWS adapterを不要と判断した場合は撤去する。
8. active Docker依存は[`docker-retirement-inventory.json`](../../../ops/docker-retirement/docker-retirement-inventory.json)へowner、代替、撤去phase、完了条件を登録する。台帳外のDocker／Compose artifactと実行commandをCIで拒否する。
9. 過去の検証log、敵対的レビュー、旧判断の説明に現れるDockerという語は履歴改変を避けるため保持できる。ただし、現在の起動手順や必須製品として再利用しない。

## 3. 撤去phase

| Phase | 対象 | 代替と完了条件 |
|---|---|---|
| R0 | 方針・棚卸し | 本ADR、要求仕様、依存台帳、台帳外依存検査を追加し、既存Docker経路を移行中と表示する |
| R1 | DB／PostGIS spike | PG16＋PostGIS native runnerでS1／S2／S5／S7／S8とmigration gateを再実行し、Compose spikeを削除する |
| R2 | Mac `local-integration` | native artifact、launchd user agent、loopback HTTPS、Keycloak、5 PgBouncer、telemetry、同一の永続／reset契約を受入後、`compose.local.yml`とDocker用local scriptsを削除する |
| R3 | CI／release | macOS／Linux／FreeBSD native artifact build、filesystem scan、SBOM、provenance、署名、build-once昇格を受入後、DockerfileとDocker GitHub Actionsを削除する |
| R4 | 任意AWS adapter | Linux native VM adapterのStaging受入、またはadapter廃止を承認後、ECS／ECR／container image変数を削除する |
| R5 | 最終撤去 | tracked Docker／Compose artifactとactive commandが0件、管理者ガイドの標準手順がnativeのみ、全回帰gate PASS |

各phaseは代替経路の同等以上の検証がPASSしてから旧経路を削除する。Dockerが動くことを新経路の合格条件にせず、旧経路の削除だけで機能を失わせない。

2026-08-22にR1を完了した。native macOS arm64上のPostgreSQL 16.15＋PostGIS 3.6.4で全migration／verify、rollback 0017〜0013、S1／S2／S5／S8、S7 15件を合格後、`spikes/docker-compose.yml`と`spikes/run.sh`を削除した。R2以降の旧経路は各phase合格まで保持する。

## 4. 帰結

- Docker daemon、Desktop login、socket、image registry停止がISASの開発・検証・運用を停止させなくなる。
- OS別artifact build、native dependency packaging、service lifecycleの保守範囲が増える。
- 移行中はnativeと旧Composeの二経路が存在するため、証跡に`runtime_profile`を必須化し、旧経路のPASSをnative経路へ読み替えない。
- 撤去は段階的であり、本ADR採用時点でDocker artifactが消えたとは表示しない。

## 5. 完了条件

R5完了は、依存台帳の全項目が`removed`、台帳外検査がPASS、tracked Docker／Compose artifact 0件、3 OSと`local-integration`のnative起動・停止・復旧・security／SLO gateがPASSした場合に限る。
