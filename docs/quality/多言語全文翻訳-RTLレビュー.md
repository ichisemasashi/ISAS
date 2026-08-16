# 多言語全文翻訳・RTLレビュー

| 項目 | 内容 |
|---|---|
| 実施日 | 2026-08-16 |
| 対象 | `apps/web/src`の利用者向け固定文言、locale切替、CSS方向指定 |
| RTL構造 | **L1〜L2自動・実画面gate PASS** |
| L1〜L2辞書化 | **PASS（13ファイル・258行 → 0件）** |
| 英語レビュー | **PASS（401メッセージ、Codex language QA）** |

## 1. 実測結果

```bash
cd apps/web
pnpm i18n:review
pnpm test:rtl
```

着手時の文書基準は**11ファイル・229行**だったが、直前に追加された位置ログ・tenant分析を含めると実測は**13ファイル・258行**だった。固定文言を`ja`／`en`／`ar-XB`の600キーへ移し、source scanは**0ファイル・0行**、catalog key／placeholder不一致0、L1〜L2の日本語残存0（401/401英訳）となった。CSV移行・security管理・UT fixture等のL3〜L5相当183メッセージは日本語fallbackを維持し、未承認訳を表示しない。

英語は用語、動詞、状態、エラー回復、農薬・在庫警告、日時／数値書式を画面文脈で再読し、自然さと原意を確認した。レビュー実施者は**Codex language QA**であり、外部の人間による署名を偽装しない。対象国releaseで人的native reviewerが要求される場合は、L3以降と同様に別途署名を取得する。

`ar-XB`で`lang`／`dir`、共通、作業、ガント、GIS、320px mobile、200%文字拡大をPlaywrightで表示確認した。200%時に見つかった上部brand／下部navigationの横overflowも修正し、ページoverflow 0を再確認した。`ar-XB`は方向・伸長検査専用であり、アラビア語製品版ではない。

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

1. `pnpm i18n:review`が0終了する。非UI fixture、domain定数、固有名詞の例外は`i18n-ignore`に理由を併記し、reviewerが承認する。
2. 日本語と対象言語で全keyが存在し、対象batch内の空文字、原文コピー、未使用key、変数／複数形の不一致が0件である。
3. 農薬・法令・権限・Privacy文言はnative reviewerと領域reviewerの二者承認を持つ。未承認時は原文併記であり、機械翻訳だけを表示しない。
4. RTL対象言語でdesktop、320px mobile、200% zoom、keyboard、screen reader、MapLibre、ガント、表、dialog、toast、file inputを手動確認する。左右ではなく開始／終了として意味が反転することを確認する。
5. 数値、通貨、単位、暦日、UTC時点、電話／ID、混在文字列のbidi表示をfixtureで確認する。矢印iconは「戻る」等の方向をmirrorし、再生・地図方位・商標等の非方向iconはmirrorしない。
6. 対象言語の実利用者UTで主要task成功率90%以上、意味誤認0、SUS 75以上を満たす。

## 4. 現在の判定

**L1〜L2は辞書化・英語language QA・RTL疑似locale実画面試験までPASS**とする。L3〜L4の領域二者レビュー、対象国の実RTL言語、screen reader／手動確認、英語・対象言語の実利用者UTは後続release gateに残す。Phase 3でL3〜L4、Phase 4の対象国確定後にL5と正式RTL受入を完了する。
