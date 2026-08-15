# MVP統合品質 敵対的レビュー

| 項目 | 内容 |
|---|---|
| 実施日 | 2026-08-15 |
| 対象 | Web／PWA、同期、BFF、PostgreSQL migration、E2E・WCAG・security・SLO証跡 |
| 手法 | 要件・ADR・実測ログ・本番コードの相互照合、失効／競合／更新／tenant切替の反対仮説 |
| 収束判定 | **今回変更範囲のHigh／Mediumは全件処置。実配備阻害条件は未クローズ** |

## 第1回 指摘と処置

| ID | 重要度 | 攻撃仮説 | 処置 | 状態 |
|---|---|---|---|---|
| MVP-AR-H1 | High | S2は数値bboxでPASSしたのに本番migration／APIは不合格だったPostGIS-only経路のまま | `0009_field_bbox_prefilter.sql`と実repositoryへ昇格しPG16＋PostGISで再検証 | 対応済 |
| MVP-AR-H2 | High | `scope_revoked`応答の削除対象を無視し、固定`tenant`をpurgeしていたため失効field cacheが残る | serverの`purgeScope`を使用し、field/pesticide/change/cursorに加えてscope混在のtoday/journal bootstrap/server queueを削除 | 対応済 |
| MVP-AR-H3 | High | tenant切替後、非同期再取得まで旧tenantのReact stateを一時表示する | context IDをReact keyにしてAppを完全remountし、旧stateを同期的に破棄。回帰試験追加 | 対応済 |
| MVP-AR-H4 | High | scope失効後もメモリ上の旧参照データと管理操作が表示され、古いcapabilityで端末書込を継続できる | 失効検出時に参照stateを消去し、未同期outboxは保持したまま再認証専用画面へfail closed | 対応済 |
| MVP-AR-M1 | Medium | PWA更新がoutbox 0だけを条件とし、フォーム入力中に再読込して未確定値を失う | 画面内にformがある間は更新を保留し、保存／記録後に「今日」へ戻す導線を表示 | 対応済 |
| MVP-AR-M2 | Medium | API一般JSONにbyte上限がなく、添付が自己申告MIMEだけで受理される | 256KiB上限、画像signature・UUID・日時・安全な名前検証を追加 | 対応済 |
| MVP-AR-M3 | Medium | Service Worker更新時にorigin内のISAS外cacheまで削除する | 削除対象を`isas-shell-*` namespaceへ限定 | 対応済 |
| MVP-AR-L1 | Low | mobile toastが操作を覆い、オフライン状態表示が隠れる | pointer eventとmobile表示を是正済み | 対応済 |

## 第2回 再レビュー

第1回処置後に次を再攻撃した。

- tenant切替：新context確定時に旧タスクがDOMから消えることを自動試験で確認。
- scope失効：server指定field groupがpurgeされ、作業ボタン／旧tenant参照／権限付き画面が再認証まで表示されないことを確認。
- PWA更新：未同期あり、formあり、双方なしの3状態で`SKIP_WAITING`送信可否を確認。
- 性能波及：S2合格SQLがrepository単体試験と追加migration検証の双方に存在することを確認。
- セキュリティ回帰：BFF 74件、Web 36件、本番buildを通過。

新規High 0件、Medium 0件。今回実装差分は収束とする。

## 未クローズのリリース阻害条件

以下は今回のコード不整合ではなく、実AWS stagingまたは実端末・実利用者で未検証のHighリスクである。完了扱いにはしない。

1. 実Cognito userによるMFA／step-up／回復／複数browser logoutと、実DynamoDB／SQS停止を含む失効event配信。
2. TLS ingress、SPA CSP／Trusted Types、rate limit、実HTTP runtime／poolerのAWS staging受入。
3. S9端末暗号化・暗号消去・offline recovery・鍵交代とiOS／Android S6実機。
4. object storage、画像scan／再encode、輸出期限・step-up・監査。
5. 本番相当データによる地図描画、一覧1万件、ガント500件、写真保存、ログインのend-to-end p95。
6. 作業員・高齢者・技能実習生の実ユーザーUT。現在はpreflightのみで、成功率／時間／SUSは未測定。

これらは[セキュリティレビュー](MVPセキュリティレビュー.md)、[性能SLO確認](MVP性能SLO確認.md)、[実ユーザーUT手順](../実ユーザーUT実施手順.md)をrelease checklistとして継続する。
