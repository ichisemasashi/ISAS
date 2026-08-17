# ISAS 1.0.0 Release Note

| 項目 | 内容 |
|---|---|
| Tag | `v1.0.0` |
| 日付 | 2026-08-15 |
| 位置付け | MVP実装・設計・品質証跡・運用文書のversion 1.0 baseline |
| Production承認 | **未承認**。このtagだけでは本番配備不可 |

Production承認tagは別namespaceの`production/v<version>`だけを使用する。このbaselineに対応するProduction tagは存在しない。

## 含まれるもの

- 今日の作業、打刻、写真付き日誌、template／前回値、承認／差し戻し／訂正。
- 農薬master鮮度・offline事前警告・server再判定、追記型在庫とマイナス在庫裁定。
- 圃場一覧／MapLibre／PostGIS検索／担当圃場cache、作業指示と14日ガント。
- S7 push/pull、outbox、冪等受理、権限失効、差し戻し、競合queue。
- 圃場／作業記録／農薬履歴CSV取込、日誌／圃場台帳／農薬記録CSV出力。
- ADR-0019〜0021、deploy/backup/incident runbook、管理者／利用者guide、release readiness検査。

## 保存済み品質証跡

- BFF 50、Web 36、E2E 2、WCAG自動2、PWA保持1、SLO 13、S7状態機械15、release検査3がPASS。
- PostgreSQL 16.4＋PostGIS 3.4.3でRLS、AuthContext、GIS、日誌、農薬／在庫、CSV migrationを検証済み。
- 詳細は[MVP統合品質確認](../quality/MVP統合品質確認.md)を参照する。

## Production release blocker

1. 具体的OIDC IdP、MFA／step-up、production logout、永続session/context、HTTP runtime／pool driver。
2. AuthContext本番migration、単調authorization version、永続失効配信。
3. TLS ingress、2 failure domain、優先度別pool、object storage、queue、KMS/HSM。
4. 法域内telemetry、SLO alert、SBOM／署名／provenance、build-once CI/CD。
5. S6 iOS/Android実機、S9端末鍵、実network S7、本番相当全画面性能、手動WCAG。
6. 実ユーザーUT、実CSV移行rehearsal、月次restore、四半期DR。

全項目をrelease manifestへ記録し、[管理者guide](../manual/システム管理者運用ガイド.md)と[deploy runbook](../operations/デプロイ・ロールバック手順.md)に従って二人承認するまでstatusは`BLOCKED`である。
