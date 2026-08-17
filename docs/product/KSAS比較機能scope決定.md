# KSAS比較機能scope決定

| 項目 | 決定 |
|---|---|
| 対象顧客job | 圃場・作業指示・日誌・農薬・在庫を、offlineを含めて自組織が管理する。macOS／Linux／FreeBSDでself-hostし、法定記録と監査を自ら保持する |
| 優先順位 | core Production gate、実data移行、実利用者UT、restore／DRを最優先とする |
| 採否基準 | 対象顧客の明示要求、data利用契約、責任分界、sample、実機または実data受入、3年TCOの全てが揃った機能だけを実装着手へ昇格する |

## 機能別決定

| 機能 | 現在の採否 | 理由と再判定条件 |
|---|---|---|
| remote sensing／生育map | `planned`、Phase 3より前には着手しない | 現在は契約と実data受入がない。利用する画像・解析結果の権利、更新頻度、圃場coverage、費用を契約できた場合だけ採用を再判定する |
| 水管理 | `out-of-scope` | coreの対象jobではなく、対応device、制御責任、保守契約が未確定。core gateへ割り込ませない |
| 乾燥調製 | `out-of-scope` | 現行要求の作業記録・在庫scopeを越え、設備接続と品質責任の契約がない |
| 病害虫診断support | `planned`、Phase 3より前には着手しない | 誤診時の責任、根拠表示、専門家review、対象作物dataの契約が成立した場合だけ採用を再判定する |

`planned`は採用確定でも利用可能でもない。再判定が成立しても、core Production gateを通過するまでは実装順を繰り上げない。
