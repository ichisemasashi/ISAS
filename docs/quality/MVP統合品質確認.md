# MVP統合品質確認

| 項目 | 判定 |
|---|---|
| 実施日 | 2026-08-15 |
| 実装／自動試験範囲 | **PASS** |
| 本番リリース | **条件付き・未承認** |

## ゲート結果

| ゲート | 結果 | 証拠境界 |
|---|---|---|
| Web単体 | 36/36 PASS | React、同期、認証境界、失効、PWA更新、安全判定 |
| BFF単体／構文 | 50/50 PASS | OIDC/BFF契約、CSRF、AuthContext、REST、CSV、添付、repository |
| MVP E2E | 2/2 PASS | mobile Chromium、主要縦切りと320px overflow。開発時UT fixtureであり実BFF配備ではない |
| WCAG 2.1 AA自動 | 2/2 PASS | 主要3画面のaxe WCAG 2.1 A/AA、skip link。手動支援技術試験を代替しない |
| PWA更新／消失 | 1/1 E2E＋単体PASS | outbox UUID再読込保持、未同期／入力form中の更新禁止、明示activation |
| セキュリティ | コアPASS | production依存既知脆弱性0、BFF防御境界を是正。実配備項目は未完 |
| 性能SLO | 測定済み13/13 PASS | S2/S5/S7保存ログの再判定。全画面・実ingressは未測定 |
| PostgreSQL | PASS | PG16.4＋PostGIS 3.4.3、S8 12群、MVP既存31群、bbox追加3群 |
| S7状態機械 | 15/15 PASS | Python 3.9／3.14双方で実行可能 |
| UT集計器 | 3/3 PASS | 閾値判定器のみ。実参加者データは未収集 |
| Release readiness検査 | 3/3 PASS | 未合格gate、no-data、error budget、期限切れDR、二重承認を拒否。実manifestは未作成 |
| 敵対的レビュー | 2巡収束 | 実装範囲High 4／Medium 3／Low 1を全処置、再レビューH0/M0 |

## 実行コマンド

```bash
cd apps/web && pnpm test:quality
cd apps/bff && npm test && npm run check
cd research/quality && python3 -m unittest -v test_check_slo.py && python3 check_slo.py
cd research/ut && python3 -m unittest -v test_analyze_ut.py
/usr/bin/python3 spikes/S7_offline_sync.py
node --test ops/test/check-release-readiness.test.mjs
```

PostgreSQLは検証専用`spike` DBを再構築し、`0001`〜`0009`と対応verifyを順番適用した。production依存は`pnpm audit --prod --audit-level high`で2026-08-15時点の既知脆弱性0件を確認した。

## 今回完了した是正

- E2E／axe品質ゲートとmobile操作阻害の修正。
- outboxと入力中formを保護するService Worker更新制御、Storage Persistence要求、cache namespace限定。
- 一般JSON byte上限、画像signature／metadata検証、API防御header。
- S2数値bbox方式の`0009` migration／実repository昇格と自動SLO判定器。
- scope失効対象の正しいpurge、混在cache削除、UI fail-closed。
- tenant context切替時の旧React state破棄。
- S7参照試験のPython 3.9互換化。

## リリース前に必須の残作業

1. 実IdP、MFA／step-up、永続session/context、単調version失効、HTTP runtime／poolerを接続する。
2. TLS ingress、SPA CSP／Trusted Types、rate limit、secret manager／KMS、SBOM／成果物署名を配備する。
3. S9端末暗号化・暗号消去・offline recovery／鍵交代と、S6 iOS／Android実機を合格させる。
4. object storage、画像scan／再encode、期限付きdownload、輸出step-up／監査を接続する。
5. 本番相当fixtureと実ネットワークで地図、一覧1万、ガント500、写真保存、一般GET、ログインのp95を測る。
6. screen reader、200% zoom、contrast、端末向き、屋外／手袋を含む手動WCAG／端末確認を行う。
7. 作業員・高齢者・技能実習生の実ユーザーUTで成功率90%、日誌30秒、農薬60秒、SUS 75、全員のオフライン理解を実測する。
8. ADR-0019〜0021の法域別provider／製品／ownerを実manifestへ固定し、同一digest段階配備、月次restore、四半期DRを本番候補環境で合格させる。

詳細は[PWA試験](PWA更新・データ消失試験.md)、[セキュリティレビュー](MVPセキュリティレビュー.md)、[性能SLO](MVP性能SLO確認.md)、[敵対的レビュー](MVP敵対的レビュー.md)、[リリース運用手順](../operations/README.md)を参照する。
