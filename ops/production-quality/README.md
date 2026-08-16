# 本番相当統合品質試験

## 安全境界

この試験は大量の同期eventを追記するため、production本体ではなく、本番と同じIaC・artifact・設定を使う隔離stagingのsynthetic tenantだけで実行する。対象tenant、実行時間、送信元IP、最大request数をchange ticketで承認し、監視担当と停止担当を分ける。実利用者tenant、個人情報、実農薬判断、実在庫を使わない。

実行環境がなければ結果を作らず、`spikes/results/PRODUCTION_QUALITY_ACCEPTANCE.template.json`をgateへ渡して`BLOCKED`を確認する。loopback、自己署名TLS、memory repository、開発fixture、emulated networkは合格証拠にならない。

## 1. 事前準備

1. stagingへ試験対象commitから署名済みWeb/BFF artifactをbuild-once配備する。
2. TLS入口、BFF 2 AZ、PG16/PostGIS、P0/P1/P2の異なるPgBouncer endpoint、Cognito、DynamoDB、SQS、object storage、OTelをreadyにする。
3. synthetic利用者へ試験tenant、`migration:manage`、同期、圃場scopeを期限付き付与しMFA loginする。browserのCookieをNetscape形式の権限600一時fileへ保存し、試験後に破棄する。
4. synthetic tenantへ圃場10,000件、地図polygon 1,000件、ガント500件、写真付き日誌用fixture、競合用version、失効対象利用者、禁止tenant markerを投入する。
5. dashboardで`p0/p1/p2 total/idle/waiting`、API latency/status、WAL、CPU、connection、queue、BFF task/AZを1分粒度以下で保存する。

secret値をcommand line、JSON、logへ書かない。次はすべて環境変数または権限600のfileで渡す。

```bash
export ISAS_ACCEPTANCE_BASE_URL=https://staging.example.jp
export ISAS_ACCEPTANCE_COOKIE_FILE=/secure/tmp/isas-quality.cookies
export ISAS_ACCEPTANCE_TENANT_ID=00000000-0000-7000-8000-000000000000
export ISAS_ACCEPTANCE_SCOPE=00000000-0000-7000-8000-000000000000
export ISAS_ACCEPTANCE_SOURCE_COMMIT=$(git rev-parse HEAD)
export ISAS_ACCEPTANCE_DEPLOYMENT_ID=isas-jp-stg-01
export ISAS_ACCEPTANCE_DB_P0_URL='postgresql://...sslmode=verify-full'
export ISAS_ACCEPTANCE_DB_P2_URL='postgresql://...sslmode=verify-full'
```

## 2. S7再実行とpool飽和

```bash
python3 ops/production-quality/run-production-load.py /secure/evidence/production-load.json
```

runnerはCA／hostname検証付き実TLS、HSTS、BFF live/ready、P0/P2のPG16＋PostGIS＋TLS、実session/context、1,000件以上のpushと10%再送、P2 endpoint連続負荷中のP0 1,000 sampleを測る。P0は成功かつ500ms以内が99.9%以上でなければ停止する。

HTTP負荷を流しただけではpool飽和を証明できない。CloudWatch/OTelから同じ時間窓のP2 utilization最大90%以上かつwaiting最大1以上、P0 waiting/latency、P0/P2 endpoint IDが異なる証跡を取得し、`pool_saturation`へ追記する。runnerが出す`p2_utilization_max`と`p2_waiting_max`の`null`を推測値で埋めない。

## 3. 機能統合試験

すべてTLS入口から実BFF、実DBへ到達させ、request/監査/DB/queueの相関IDをartifactへ保存する。

| ケース | 操作 | 合格条件 |
|---|---|---|
| tenant越境read | tenant A contextでtenant B marker、field ID、attachment IDを照会 | 403または一般化404、本文・件数・timingからBの存在を識別不能、DB漏洩0 |
| tenant越境write | A contextでBのfieldを参照するpush／指示／添付 | transaction全体拒否、B変更0、監査あり |
| 権限失効 | 端末Aをofflineにし未同期1件作成、別管理者二人でmembership/scope失効、端末A復帰 | P0が先行、旧context拒否、cache purge、outboxはrecovery queue、再送で収束 |
| 競合 | 同じbase versionから異なるfield／同一field更新 | 非重複fieldはmerge、同一fieldだけconflict queue、裁定に現権限と監査 |
| 冪等 | 同じevent UUIDを同時・逆順・timeout後に再送 | 同一結果、receipt/change/在庫/監査の副作用1回 |
| PWA更新 | 未同期1件＋入力form中に新worker配備 | activation拒否、UUID・暗号文保持。同期／保存後だけactivate |
| PWA rollback | 新版で未同期作成後、旧署名済みartifactへrollback | outbox purgeなし、互換読込または明示quarantine、data loss 0 |

失効試験は実利用者を対象にせず、開始前に回復責任者、recovery key、queue監視を揃える。試験後は失効を取り消して合格にせず、新しいmembership/versionを正規手順で再付与する。

## 4. 全画面性能SLO

RUMまたはremote Playwright traceで各ケースをwarm/cold双方30回以上測る。login 2秒、今日1.5秒、圃場一覧10,000件1.5秒、地図1,000 polygon初期2秒、pan/zoom 200ms、ガント500件3秒、写真付き日誌保存1秒、農薬・在庫1秒をp95上限とする。HTTPだけでなく描画完了・利用可能状態までを測り、error rateは0.1%以下とする。MapLibre背景tile、object scan、IdPを除外しない。

## 5. 手動WCAG 2.1 AA

accessibility担当者がSafari＋VoiceOverとAndroid Chrome＋TalkBackを含む実機で全画面を確認する。

- キーボードのみで全操作、skip link、focus順・可視性、modal復帰を確認。
- VoiceOver/TalkBackで見出し、landmark、label、error、警告、同期状態、地図代替情報を読み上げる。
- 200% zoom、320 CSS px reflow、縦横向き、文字間隔変更で欠落・二軸scroll・重なりがない。
- contrast、色だけに依存しない状態、target size/間隔、reduced motion、language/reading orderを確認。
- 農薬警告、失効、競合、未同期、PWA更新の動的通知が適切な順序と緊急度で伝わる。

各項目は動画または連続screenshot、端末/OS/browser/screen reader版、実行者、時刻、期待値を残す。axeの自動PASSで代替しない。

## 6. penetration／敵対的レビュー

書面承認した隔離stagingで独立testerが実施する。productionへのDoS、実dataの取得、social engineering、永続化、第三者serviceへのscanは禁止する。

対象はOIDC/PKCE/nonce/MFA/logout、Cookie/CSRF/CORS、context/tenant/scope、RLS、IDOR、SQL/CSV/XSS、upload signature/size/object key/署名URL、SSRF/path/range、同期replay/conflict、失効race、Service Worker/cache、rate limit、header/TLS、secret/SBOM/IaCである。Critical/High/Mediumを修正後に独立retestし、未解決0件でなければPASSにしない。

敵対的レビューは設計意図を外し、次の反証を試す：P2負荷が本当にP0と別endpointか、99.9%の分母にtimeoutが含まれるか、tenant Bの存在をerror/timingで推測できないか、失効とPWA更新の競合でoutboxを失わないか、監視no-dataを0件扱いしていないか、試験fixtureだけの特別経路が本番権限を広げないか。

## 7. 最終判定

```bash
node --test ops/production-quality/test_check_production_quality.mjs
node ops/production-quality/check-production-quality.mjs /secure/evidence/production-quality.json
```

最終JSONには負荷、全画面、7機能ケース、手動WCAG 12項目、独立penetration、敵対的レビュー、release/security/accessibilityの3者承認をまとめる。各`evidence`はObject Lock付き法域内artifact URIとし、Gitへsession、Cookie、個人情報、内部endpointを入れない。
