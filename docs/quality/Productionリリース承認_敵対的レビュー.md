# Productionリリース承認 敵対的レビュー

| 項目 | 内容 |
|---|---|
| レビュー日 | 2026-08-16 |
| 対象 | release manifest、二者承認、5%→25%→100%、24時間強化監視、production tag |
| 実装判定 | **ローカル実装・自動試験PASS** |
| Production判定 | **BLOCKED** |

## 実装した防御

1. release manifestは、全artifactのdigest／署名／provenance／SBOM、12 gate、品質値、93日以内のDR、RPO 15分／RTO 4時間、二者承認を検証してから新規生成する。gate証跡はreleaseと同じcommit、31日以内、証跡URI必須とした。
2. delivery stateへ`prepared→5→25→100→finalized`の履歴と、各段階の観測時間、eligible transaction数、blocking alarm数を保存する。5%は30分＋1,000件、25%は2時間＋1,000件、100%は30分＋1,000件を省略できない。
3. 24時間証跡はrelease manifestのSHA-256、同じsource commit／artifact-set digest、signal欠落0、alarm breach 0、Sev-1/2 0、High/Medium 0、error budget 25%以上を要求する。
4. 監視完了後に異なるactorの`release_manager`と`independent_verifier`を再度要求する。tag名は`v<release.version>`、対象はrelease source commitに固定し、既存tagは上書きしない。

## 攻撃観点と結果

| 攻撃 | 防御／結果 |
|---|---|
| 古い試験結果を新releaseへ流用 | gateのsource commit一致と31日期限でBLOCK |
| manifestだけ差し替える | bake evidenceのmanifest SHA-256不一致でBLOCK |
| 5%から100%へ飛ばす | ordered delivery history欠落でBLOCK |
| 観測0秒やtransaction不足で進める | 段階別下限未達でBLOCK |
| signal欠落を正常値0と扱う | `no_data_count=0`必須。blocking alarm欠落は既存monitorがrollback |
| 同一人物が二役で承認 | distinct actorと必須role検査でBLOCK |
| tagを別commitへ付ける／既存tagを更新 | target commit固定、既存local／remote tag検知でBLOCK |

## 現在の実環境blocker

このrepository／実行環境には、production AWS deployment manifest、build run artifact、実gate証跡、実二者承認、delivery state、24時間監視証跡が存在しない。したがって実release manifestの生成、段階配備、production tag発行は未実施であり、成功を装う値は登録していない。既存`v1.0.0`は文書・実装baselineであり、production承認tagへ読み替えない。

解除条件は、AWS staging受入、実データ／UT、実機、統合品質、Backup・復旧・運用受入を先にPASSさせ、runbookのworkflowへ実S3 URIと二者承認を与えることである。最終workflowが`production release: AUTHORIZED`を出してtag pushまで成功した時点だけ正式リリースと判定する。
