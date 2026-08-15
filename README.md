# ISAS

イチセ・スマート・アグリ・システム — 営農支援Webアプリケーション。

現場での入力（作業日誌・農薬記録・打刻）を核に、圃場GIS・作付計画・在庫・労務・農機連携までを扱う。オフライン前提・多テナント・多言語（将来の海外展開）を初期から設計に織り込む。ライセンスは MIT（[LICENSE](LICENSE)）。サービス実施範囲・データレジデンシー・法令遵守は運用者責任。

## 現在のフェーズ

本リポジトリは、要求・設計文書と技術検証（スパイク）を管理する。プロジェクト全体の現行フェーズ、進捗、次に行う作業は、[開発工程.md §6](docs/開発工程.md#6-マイルストーンと現在地)だけを正本とする。このREADMEには変動する状態を複製しない。

## ドキュメント

| 場所 | 内容 |
|---|---|
| [docs/農業営農支援システム_要求仕様書.md](docs/農業営農支援システム_要求仕様書.md) | 要求仕様 v1.0（確定） |
| [docs/開発工程.md](docs/開発工程.md) | 全体工程・マイルストーン・現在地 |
| [docs/design/ADR/](docs/design/ADR/README.md) | アーキテクチャ決定記録（一覧・優先度・状態の参照先） |
| [docs/design/データモデル設計書.md](docs/design/データモデル設計書.md) | 論理/物理ERD・RLSポリシー・パーティション設計 |
| [docs/CSVデータ移行・出力ガイド.md](docs/CSVデータ移行・出力ガイド.md) | CSV取込の列・重複検査・確定手順と出力仕様 |
| [docs/design/PostgreSQL実挙動検証記録.md](docs/design/PostgreSQL実挙動検証記録.md) | 設計文書の PostgreSQL に関する主張を実測で検証した記録（スパイクSQLと文書の差異を含む） |
| [docs/design/UXデザインシステム.md](docs/design/UXデザインシステム.md) | Phase 1のUXデザインシステム、IA、主要導線、UT計画 |
| [docs/operations/](docs/operations/README.md) | デプロイ、ロールバック、バックアップ、復旧、障害対応runbook |
| [spikes/](spikes/README.md) | 技術検証（PostgreSQL＋PostGIS の PoC ハーネス） |
| [apps/web/](apps/web/) | React＋TypeScript＋PWAによるPhase 1 MVPフロントエンド |
| [apps/bff/](apps/bff/) | ADR-0009のOIDC／Cookie session／context／AuthContext境界を担うBFFコア |
| `docs/**/レビュー記録_*.md`、`docs/要求仕様書_敵対的レビュー記録票_*.md` | 敵対的レビューの監査証跡 |

## MVPフロントエンド

```bash
cd apps/web
pnpm install
pnpm dev       # http://127.0.0.1:4173
pnpm test
pnpm build
```

Viteの`/api`は開発時に`http://127.0.0.1:3000`へproxyする。認証BFF、当日作業、作業指示・割当、打刻補完、写真付き日誌、訂正・差し戻し、農薬マスタ・安全再判定、在庫イベント、S7 push/pull、圃場PostGIS、同期キューを同一オリジンで提供するBFFを先に起動する。MapLibreの背景地図は`VITE_MAP_STYLE_URL`で配備時に指定する。未設定またはオフラインでも、IndexedDBに保存した担当圃場ポリゴンを背景色上へ描画する。Phase 1全体の完了状況は[開発工程.md §6](docs/開発工程.md#6-マイルストーンと現在地)を参照する。

## BFFコア

```bash
cd apps/bff
npm test
npm run check
```

PostgreSQL AuthContextトランザクション、MVP REST、S7 push/pull、圃場PostGIS検索、作業指示・割当、打刻・写真付き日誌、訂正・差し戻し、農薬マスタ・使用基準再判定、追記型在庫・マイナス在庫裁定、RLS migration、フィールド競合裁定を実装し、PG16で検証済み。具体的なOIDC IdP、永続session store、HTTP runtimeが生成する実pool/driverは配備アダプタとして残る。migration順序、API契約、検証方法は[apps/bff/README.md](apps/bff/README.md)を参照する。

## 進め方（プロジェクトを貫く原則）

1. **反復＋敵対的レビュー**：成果物は「作成→敵対的レビュー（矛盾・欠落・非現実性の摘出）→修正」を収束するまで反復し、指摘を追跡IDで記録票に残す。接合の多い重要ADRは**独立レビュー（第三者視点）を1回入れる**。
2. **UXを第一級要求**：機能と同格に扱い、実ユーザー参加型で検証する。
3. **技術リスク先行**：オフライン同期・地図性能・RLS×規模は本実装前にスパイクで潰す。
4. **食品安全・法令遵守を優先**：農薬記録・トレーサビリティ・GDPR 関連は Must。
5. **段階リリース**：Phase 1（MVP）で現場入力の核を最速で価値化する。

詳細は [開発工程.md](docs/開発工程.md) を参照。
