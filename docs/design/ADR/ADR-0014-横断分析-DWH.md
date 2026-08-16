# ADR-0014：横断分析＝システム外DWH＋policy付き非権威export

| 項目 | 内容 |
|---|---|
| ステータス | **採用（クローズ） v1**（敵対的レビュー8件を全件処置、残存 High 0／Medium 0。[レビュー記録](レビュー記録_ADR-0014.md)） |
| 日付 | 2026-08-16 |
| 由来 | 要求仕様 F-60〜64、確定事項#11・#13、ADR-0002からの委譲事項 |
| 関連 | [ADR-0001 RLS](ADR-0001-マルチテナント分離-行レベル-RLS.md)、[ADR-0002 配備](ADR-0002-配備モデル-1DB-1国.md)、[ADR-0003 ライフサイクル](ADR-0003-データライフサイクル-追記型-論理削除-版履歴-監査.md)、[ADR-0013 外部API](ADR-0013-外部API-Webhook.md) |

## 1. 境界

- Phase 2のtenant内dashboardは各ISAS deployment内のRLS済みprojection／集計tableで提供する。単一法域の業務表示まで外部DWHへ依存させない。
- 複数deployment／複数国の横断分析だけを、運用者が任意に設置する**システム外DWH**へexportする。DWH製品、cloud、regionは運用者が法域・契約・費用に基づいて選ぶため、本ADRでは固定しない。
- DWHは非権威・read-onlyである。作業日誌、在庫、農薬記録、権限、給与、法定集計をDWH値で確定・更新せず、欠落時も現場業務と失効を継続する。

## 2. Export契約

- DB replication／WALをDWHへ直接公開しない。各deployment内の専用export jobが現在のtenant opt-in、目的、項目allowlist、RLS、保持、第三者licenseを評価し、curated datasetだけをprivate object storageへ出す。
- datasetはversion付きdata contract、UTC期間、法域、source deployment、tenant cohort、schema hash、row count、content hash、生成時刻、watermark、policy versionをmanifestに持つ。objectは暗号化・署名し、DWH側はhash／署名／件数を検証してから原子的に公開する。
- 増分は`occurred_at`だけで切らず単調なsource sequence／watermarkを用い、late arrival、訂正、論理削除をupsert／tombstoneとして出す。再実行はdataset＋期間＋watermarkで冪等とし、部分fileを公開しない。
- 共通dimensionはdeploymentが発行した非可逆analysis keyを使う。生のuser ID、email、氏名、OIDC subject、端末ID、精細位置、自由記述、写真、object key、監査payload、credentialを汎用exportに含めない。

## 3. 再識別と越境

- 仮名化dataは個人dataとして扱う。国またぎ汎用datasetはPII-freeの集計値を既定とし、少数圃場、希少作物、精細geometry、狭い時間帯の組合せによる再識別も評価する。
- 公開cellは既定`k >= 10`のcontributorを要求し、未満は抑制または上位地域／長い期間へ粗粒化する。最大／最小だけから個体を推測できる指標、差分queryで抑制cellを復元できるdimensionを禁止する。閾値変更はprivacy review対象とする。
- 国／法域を越えるdatasetは、送信元・受領region、目的、適法根拠、項目、集計粒度、再委託、保持、削除、incident窓口をexport policy registryで承認する。未承認region、未知送信先、policy期限切れでは生成も転送もしない。
- 個人特定が目的上必要な法定帳票、本人開示、portabilityはDWH汎用exportへ混ぜず、各deploymentの専用workflowで扱う。

## 4. 保持・削除・品質

- DWH保持期間はdatasetごとに設定し、source側の削除／訂正を次回増分で伝播する。削除要求に法定保持例外がある時は理由・期限をmanifestへ記録する。analysis keyとmapping secretは別管理し、目的終了時にcrypto-shred可能にする。
- DWH側は受信、検証、load、公開、削除のledgerを持つ。source count／hash／watermarkとDWH countを照合し、差異、遅延、schema drift、抑制失敗、未知columnをalertする。未知columnを自動公開しない。
- dashboardはsource期間、最終成功時刻、法域／cohort、抑制、欠測、通貨／単位を表示する。異通貨を暗黙換算せず、集計値から業務正本へdrill-throughする場合は元deploymentで現在権限を再認証する。
- analyst権限、query、export、share、model trainingを監査する。汎用datasetをAI学習へ二次利用するには別purpose、opt-in、保持、評価を要求し、DWH接続だけで許可しない。

## 5. 選択肢

| 選択肢 | 結論 |
|---|---|
| policy付きcurated export→外部DWH | 採用。レジデンシーと非権威境界を維持 |
| 全deployment DBを直接federated query | 不採用。障害・資格情報・越境が業務DBへ波及 |
| WAL／CDCをそのまま中央集約 | 不採用。PII・内部schema・削除前dataを過剰複製 |
| DWHを横断業務の正本にする | 不採用。遅延・欠落・権限失効と整合しない |

## 6. Phase 2〜4受入条件

1. Phase 2 tenant dashboardがDWH停止時も動作し、RLS越境しない。
2. fixtureへ禁止PII、未知column、少数cell、精細geometry、異通貨を混ぜ、exportが除外／抑制／停止する。
3. late event、訂正、削除、同じwatermarkの再送、途中fileを試験し、DWH公開結果が収束する。
4. source／DWHの件数・hash・watermarkを照合し、欠落・重複・schema driftを検出する。
5. 法域外宛先、期限切れpolicy、第三者再配布禁止data、未承認AI利用を拒否する。

実DWH製品と越境policyは対象国・運用者・法務審査が確定してから配備ADRへ記録する。製品未選定を理由に、PII-free既定や非権威境界を緩めない。
