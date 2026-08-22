# 製品capability catalog

正本は[`capability-catalog.json`](capability-catalog.json)である。`implemented`はProduction利用可能を意味しない。全体のProduction表示は現在`BLOCKED`であり、host別release manifestと実受入が完了するまで変更しない。

```bash
node ops/product/check-capability-catalog.mjs docs/product/capability-catalog.json
```

| 状態 | 表示上の意味 |
|---|---|
| `implemented` | codeと自動testがある。実運用受入は別gate |
| `validated` | 指定された実環境・実data・実利用者の証跡がある |
| `planned` | 設計・計画のみ。利用可能と案内しない |
| `out-of-scope` | 現在の製品scope外 |

対外提供scopeは「圃場・指示・日誌・農薬・在庫のself-host／offline core」に限定する。農機・remote sensing等は名前だけから利用可能と推測せず、JSONの状態と証跡を確認する。「KSAS同等」「KSAS全面代替」は現在表示禁止である。

この比較表示を再審査できるのは、契約済み1 connectorについて実sample・実機で、取込→圃場照合→日誌候補→人の確定→監査、再送冪等、単位変換、provider停止時のfile継続を通し、次の検査をPASSした後だけである。PASS後も未提供ecosystemの明示を削除しない。

```bash
node ops/product/check-machinery-connector-acceptance.mjs /secure/evidence/connector-acceptance.json
```
