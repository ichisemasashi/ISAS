# host別reference benchmark仕様（KCOMP2-M5）

KSASの3,000圃場という公開推奨値と単純比較せず、各Production hostで同じfixtureとSLOを測る。

| 条件 | 固定値 |
|---|---|
| 圃場 | 3,000件／tenant。頂点10、50、200、1,000のpolygonを各25% |
| 履歴 | 1圃場あたり作業日誌36、農薬記録6、在庫event12、監査event60 |
| 写真 | 1圃場あたり6枚、各2 MiB、MIME／signature検査とprivate downloadを含む |
| 利用者 | 100名。同時20／50／100を段階測定 |
| 操作 | 圃場bbox／検索／詳細、地図pan、日誌、写真、同期pull、分析 |
| host | macOS、Linux、FreeBSDを別結果とし、OS／arch／CPU／RAM／disk／network／component digestを記録 |

各hostでcold／warmを30回以上測り、p50／p95／p99、error率、DB pool待ち、CPU、memory、disk latency、network、object latencyを公開する。fixture hashと負荷script digestが一致しない結果を横並びにしない。固定条件の機械可読正本は`ops/capacity/reference-capacity-contract.json`である。

3 OSの測定結果を単一evidenceへまとめ、次で検査する。

```bash
node ops/capacity/check-reference-capacity.mjs /secure/evidence/reference-capacity.json
```

validatorはmacOS／Linux／FreeBSD、同一fixture／load script digest、同時20／50／100、cold／warm、全8操作、各30 sample、host構成、全性能metric、ISAS error-rate SLO、二者承認を要求する。現在は3 OS実host結果が存在しないため、検査器の自動test結果を実測へ読み替えず、KSASの3,000圃場推奨値をISASの処理能力として引用しない。
