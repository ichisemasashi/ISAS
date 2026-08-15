# PWA更新・データ消失試験

| 項目 | 内容 |
|---|---|
| 実施日 | 2026-08-15 |
| 対象 | Phase 1 Web MVP (`apps/web`) |
| 判定 | **PASS（実装・自動試験範囲）** |

## 確認した不変条件

1. 新しいService Workerは自動で`skipWaiting`せず、利用者の明示操作までwaiting状態を維持する。
2. 更新適用前に、tenantを限定しないoutbox総件数をIndexedDBから再読込する。
3. 未同期が1件以上なら更新を保留し、件数と送信が必要である旨を表示する。
4. 未同期が0件の場合だけ`SKIP_WAITING`を送り、`controllerchange`後に再読込する。
5. Service Worker更新時に削除するのは`isas-shell-*`だけとし、他用途・他機能のキャッシュへ波及させない。
6. IndexedDBのoutboxはページ再読込後も同じ`eventUuid`で残る。
7. production起動時にStorage Persistenceを要求し、拒否または確認失敗時は早期同期を促す。

## 自動試験証跡

```text
pnpm test
  Test Files 10 passed
  Tests      32 passed

pnpm build
  built successfully

pnpm test:pwa
  1 passed
```

単体試験は未同期3件で更新メッセージが送られないこと、0件でのみ送られることを確認した。Playwright試験は、同期障害中に生成したoutboxのUUIDを取得し、ページ再読込後の件数とUUIDが一致することを実ブラウザのIndexedDBで確認した。

## 残る受入条件

- iOS／Android実機でのStorage Persistence、OS eviction、PWA更新UIはS6実機マトリクスで継続確認する。
- ブラウザPWAは端末紛失時の保存時暗号化を保証できない。高機微運用はADR-0017に従いネイティブ＋OSキーストア／MDMを用いる。
- 破壊的なIndexedDBスキーマ変更を導入する際は、旧バージョンからのmigration fixtureを追加してからキャッシュ版を更新する。
