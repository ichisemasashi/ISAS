# 多言語全文翻訳・RTLレビュー

| 項目 | 内容 |
|---|---|
| 実施日 | 2026-08-16 |
| 対象 | `apps/web/src`の利用者向け固定文言、locale切替、CSS方向指定 |
| RTL構造 | **自動gate PASS** |
| 全文翻訳 | **BLOCKED** |
| 人的翻訳承認 | **未実施** |

## 1. 実測結果

```bash
cd apps/web
npm run i18n:report
npm run test:rtl
```

2026-08-16のscan結果は、翻訳resource外に日本語を含むものが**11ファイル・229行**である。対象は`App`、認証、CSV、圃場、ガント、security管理、PWA、農薬安全表示、UT fixture等に分布する。このため、ナビゲーション7語の日英切替だけを「英語対応済み」または「全文翻訳済み」と表示してはならない。

`ja`、`en`、RTL検査専用`ar-XB`のlocale metadata、`lang`／`dir`反映、catalog key parity、物理方向CSS禁止gateを実装した。既存CSSのleft／right依存は論理方向propertyへ変更し、自動試験はPASSした。`ar-XB`はレイアウト検査専用であり、アラビア語製品版ではない。

## 2. 翻訳単位と順番

| batch | 対象 | 追加のreview |
|---|---|---|
| L1 | login、tenant切替、接続／同期／失効、共通navigation、error | security用語、状態が逆の意味にならないこと |
| L2 | 今日、打刻、作業指示、ガント、圃場GIS | 暦日／時刻、担当・未割当、進捗状態 |
| L3 | 日誌、写真、テンプレ、農薬、安全警告、在庫 | **農業実務者＋native reviewer必須**。未review訳を安全判断に使用しない |
| L4 | CSV移行、管理、権限、Privacy、break-glass、監査 | security／privacy reviewer必須 |
| L5 | onboarding、help、empty state、PWA更新、UT／運用表示 | 高齢者・技能実習生を含む理解確認 |

各batchは、キー抽出→日本語原文確定→用語集適用→一次翻訳→別人review→UI実表示→修正→承認の順とする。圃場名、農薬商品名、利用者名、ID等は翻訳resourceへ入れず、Unicode bidi isolateまたは`bdi dir=auto`で固定文言と分離する。

## 3. 完了gate

1. `npm run i18n:review`が0終了する。非UI fixture、domain定数、固有名詞の例外は`i18n-ignore`に理由を併記し、reviewerが承認する。
2. 日本語と対象言語で全keyが存在し、空文字、原文コピー、未使用key、変数／複数形の不一致が0件である。
3. 農薬・法令・権限・Privacy文言はnative reviewerと領域reviewerの二者承認を持つ。未承認時は原文併記であり、機械翻訳だけを表示しない。
4. RTL対象言語でdesktop、320px mobile、200% zoom、keyboard、screen reader、MapLibre、ガント、表、dialog、toast、file inputを手動確認する。左右ではなく開始／終了として意味が反転することを確認する。
5. 数値、通貨、単位、暦日、UTC時点、電話／ID、混在文字列のbidi表示をfixtureで確認する。矢印iconは「戻る」等の方向をmirrorし、再生・地図方位・商標等の非方向iconはmirrorしない。
6. 対象言語の実利用者UTで主要task成功率90%以上、意味誤認0、SUS 75以上を満たす。

## 4. 現在の判定

RTLの構造的な後戻り防止は導入済みだが、全文辞書化、英語native review、実RTL言語翻訳、実画面手動確認、実利用者UTは未完了である。Phase 2でL1〜L2、Phase 3でL3〜L4、Phase 4の対象国確定後に対象言語L5と正式RTL受入を完了する。
