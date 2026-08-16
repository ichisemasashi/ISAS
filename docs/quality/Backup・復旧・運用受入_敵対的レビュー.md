# Backup・復旧・運用受入 敵対的レビュー

| 項目 | 内容 |
|---|---|
| 実施日 | 2026-08-16 |
| 対象 | recovery set IaC／runner、隔離restore runner、運用受入gate、運用文書 |
| 結論 | **実装指摘は処置済み。実AWS・実CSV・実参加者・運用承認がないため受入はBLOCKED** |

## 指摘と処置

| ID | 重大度 | 攻撃・失敗仮説 | 処置 | 状態 |
|---|---|---|---|---|
| BR-H1 | High | RDS snapshot成功だけをrecovery set成功として、DynamoDB、object、queue、監査、構成、鍵が欠落する。 | AWS Backup 6資源、PITR、S3 inventory 4系統、queue checkpoint、監査anchor、deployment／shard／migration digest、KMSを同じIDへ束ね、gateで全要素を要求した。 | 処置済み |
| BR-H2 | High | restore jobを開始した時点でRTO達成と誤記する。 | runner出力は`STARTED`に固定し、10種の復旧後検査、独立確認完了までのRTOだけを受入する。 | 処置済み |
| BR-H3 | High | production networkへ実データをrestoreし、外部email／webhookを送信する。 | staging限定、application VPC拒否、sink-only egress、二者承認を開始器でfail-closedした。 | 処置済み |
| BR-H4 | High | SQSをbackupできると誤認し、失効／同期messageを消失する。 | SQSを配送路と明記し、PostgreSQL永続outbox／idempotency、quarantine archive、監査anchorから再構築してqueue/cursorを検証する。 | 処置済み |
| BR-M1 | Medium | 日次S3 inventoryが古いのにobject完全性を主張する。 | inventory ageを24時間以内とし、実復旧はversioning＋AWS Backup recovery point、検証はhash照合と分離した。 | 処置済み |
| BR-M2 | Medium | RPO/RTOの起点を復旧担当が都合よく選ぶ。 | RPOは障害基準時刻と復旧点、RTOは障害宣言からreadiness・合成transaction・独立確認完了までと定義した。 | 処置済み |
| BR-M3 | Medium | 論理on-call名だけを記載し、担当者0名や未確認SNSでも運用開始する。 | 5窓口のroute evidence、monitoring、運用台帳、Service Owner／Restore Verifier／Security On-callの三者承認をgate化した。 | 処置済み |
| BR-M4 | Medium | 起動は成功するが停止、drain、rolling restart、依存障害時の動作が未検証である。 | 実stagingのcold start、graceful stop、rolling restart、dependency failure、incident responseを個別の必須証跡にした。 | 処置済み |
| BR-L1 | Low | runbook内の仮値や汎用adapterを見落としてcopy&pasteする。 | 配備3文書を対象とするplaceholder scannerを追加し、具体的なmanifest keyとrecovery commandへ修正した。 | 処置済み |

## 残余リスク・実行待ち

- repositoryにはAWS staging account、recovery point、隔離VPC、実データ、実参加者、組織の担当者割当がない。したがって月次restore、四半期DR、実CSV、実UT、on-call到達確認はまだ`PASS`ではない。
- 初回S3 inventory生成には時間を要する。inventoryが24時間以内に生成されるまでrecovery setを承認しない。
- restore resourceごとのAWS metadataはresource typeと世代で異なる。`approved-request.json`は毎回plan reviewし、過去requestの無検証再利用を禁止する。
- production復旧はstaging演習の同一source commit・署名artifactを昇格する別workflowで行う。staging専用runnerのproduction拒否を外して流用しない。

現時点は「recovery set／隔離restore／受入gate／具体runbook完成、実環境演習待ち」である。
