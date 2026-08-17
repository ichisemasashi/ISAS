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

農機・remote sensing等は名前だけから利用可能と推測せず、JSONの状態と証跡を確認する。
