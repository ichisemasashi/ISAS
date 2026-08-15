# ADR-0016：UXデザインシステム＝CSSトークン＋所有Reactコンポーネント

| 項目 | 内容 |
|---|---|
| ステータス | **採用（クローズ） v1**（敵対的レビュー6件を全件処置、残存 High 0／Medium 0。[レビュー記録](レビュー記録_ADR-0016.md)） |
| 日付 | 2026-08-15 |
| 由来 | 要求仕様4.11／5.1、ADR-0006、実ユーザーUT、WCAG 2.1 AA品質gate |
| 関連 | [ADR-0006 React/PWA](ADR-0006-フロントエンド構成-React-PWA.md)、[ADR-0015 国際化](ADR-0015-国際化基盤-ICU-CLDR-UTC.md)、[ADR-0021 テスト](ADR-0021-テスト・リリース方式.md) |

## 1. 決定

- Phase 1は、`apps/web/src/styles.css`のCSS custom propertiesと、repository所有のsemantic React componentをデザインシステムの正本とする。Material UI等の包括的component frameworkは導入しない。
- tokenは意味層で提供する。色、文字、余白、radius、shadow、focus、motion、z-index、状態（success/warning/error/offline/sync）を定義し、業務componentから生の色値を増やさない。light、dark、高contrastを同じsemantic tokenで切り替える。
- button、input、select、dialog、banner、status、card、navigation、table/list、map controlを共有部品化する。見た目だけのdivへbutton操作を実装せず、native HTML semanticsを優先する。
- WCAG 2.1 AA、320 px、200%拡大、keyboard-only、screen reader名、44 CSS px以上の主要tap targetをrelease gateにする。色だけでoffline、差し戻し、警告を表現しない。
- 農薬の最重要警告、権限失効、未同期、競合は、一般themeより優先する固定semantic contractとし、文言・icon・状態名を併記する。
- UI文字列はcomponentへ直書きせずlocale resourceへ移す。RTL、複数形、日時・単位はADR-0015のformatter境界に従う。
- component変更はVitest＋Testing Library、Playwright、axe、主要画面の視覚確認で検証する。単独StorybookはPhase 1で採用せず、再利用componentが30個または3 teamを超えた時に別ADRで評価する。

## 2. 選択肢

| 選択肢 | 結論 |
|---|---|
| CSS token＋所有React component | 採用。既存MVPを活かし、業務状態とa11yを直接管理できる |
| Material UI等の全面採用 | 不採用。現状からの置換、theme上書き、bundle・操作差が大きい |
| 画面ごとのCSS | 不採用。状態表現とa11yが分岐する |
| Storybookを即時必須化 | Phase 1不採用。現行規模ではtest harnessを優先 |

## 3. 帰結・受入条件

- 新規画面は既存token・semantic componentを再利用し、例外は理由とa11y検証をPRへ記録する。
- tokenの破壊的変更は全主要画面を対象にする。snapshotだけで操作可能性を承認しない。
- 現行MVPは方式を実証済みだが、hard-coded文字列のresource移行とcomponent抽出は継続的な実装課題であり、本ADRの方式未決ではない。
