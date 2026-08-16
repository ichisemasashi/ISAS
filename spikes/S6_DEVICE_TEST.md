# S6 iOS／Android端末能力スパイク

## 目的と判定境界

Phase 1のフォアグラウンド現場操作をPWAで提供できるか、継続的な背景位置取得・強い端末暗号化・MDM失効にネイティブが必要かを実機で確定する。APIの存在確認だけでは合格にせず、インストールPWA、再起動、画面ロック、容量圧迫を含む。

## 実行

```bash
cd spikes
python3 -m http.server 18086 --bind 0.0.0.0
```

PCでは`http://127.0.0.1:18086/S6_device_capabilities.html`、同一LANの端末ではHTTPSを終端する開発用リバースプロキシ経由で開く。Service Worker、Storage、Push等はsecure contextが必須であり、端末からの平文LAN IPアクセスを正式結果に使わない。

1. `S6_manifest.webmanifest`を認識したことを開発者ツールで確認し、ブラウザ表示とホーム画面追加後のstandalone表示で「自動能力・32MiB保存性能」を実行する。
2. 位置取得を開始し、前景2分→画面ロックまたは別アプリ5分→復帰後2分を記録する。
3. JSONを保存する。
4. 端末を再起動し、同じ配布形態で「再起動後の保存データを確認」を実行して別JSONを保存する。
5. 空き容量を10%未満へ減らした状態で再確認する。試験後は容量を戻す。

## 必須マトリクス

| 系統 | 最低1台 | 配布形態 |
|---|---|---|
| iOS/iPadOS | サポート下限版のiPhone、現行版のiPhone | Safariブラウザ／ホーム画面Web App |
| Android | サポート下限版のAndroid、現行版のAndroid | Chromeブラウザ／インストールPWA |

エミュレータはUI・API存在確認の補助には使えるが、ストレージeviction、バックグラウンド実行、バッテリ、OSキーストアの合格証拠にはしない。

## 合格基準

| 項目 | Phase 1 PWA合格条件 | ネイティブ分岐条件 |
|---|---|---|
| オフライン起動 | standaloneで再起動後も測定ページと保存データを読める | 不成立なら当該端末をPWA対象外 |
| IndexedDB | 32MiB書込30秒以内、再起動後markerあり、14日分の運用見積容量をquotaが上回る | eviction試験で未同期データが消える運用はネイティブ必須 |
| `persist()` | 結果を記録する。`false`自体は不合格でなく、eviction実測で判定 | 永続性を運用上保証できなければネイティブ |
| Background Sync | 非対応でもonlineイベント＋起動時再送があるためPhase 1は合格可能 | OS任せの閉画面自動同期を必須にする場合 |
| Web Push | Phase 1の同期正当性には使わない | 閉画面の即時失効通知を必須にする場合はPush/MDM経路 |
| 前景位置 | 前景9分の取得が継続し、サンプル欠落2分未満 | 前景でも成立しない端末は対象外 |
| 背景位置 | PWAのPhase 1範囲外 | F-08a継続位置ログを提供する運用はCapacitor＋OS権限必須 |
| 端末暗号化・失効 | 低機微度のPWA範囲だけ | 機微PII、OSキーストア、リモートワイプが必要ならネイティブ／MDM |

## 証跡の扱い

結果JSONは`spikes/results/S6_<device>_<date>.json`へ保存し、端末型番、OSビルド、ブラウザ版、browser/standalone、電源・空き容量、ネットワーク条件を併記する。位置座標は証跡へコミットする前に削除し、時刻・visibility・精度・間隔だけを残す。

能力測定に加えて、各プロファイルで`offline_restart`、`vault_non_extractable`、`key_separation`、`key_rotation_interrupt`、`quota_pressure`、`os_update`、`browser_termination`、`device_loss_revocation`、`outbox_recovery`、`production_logout`、`shared_device`を実行する。結果は`results/S6_S9_DEVICE_ACCEPTANCE.template.json`を複製して記入し、次のgateを通す。

```bash
node ops/check-device-acceptance.mjs spikes/results/S6_S9_DEVICE_ACCEPTANCE.json
```

`PASS`にはiOS/Androidの下限・現行×browser/standaloneの8プロファイル、全11試験のartifact URI、端末試験責任者と独立security verifierの承認が必要である。Simulator、emulator、desktop診断、空欄、`BLOCKED`をgateは拒否する。

## 現時点の判定

2026-08-14にデスクトップChromiumで、自動能力測定、32MiB IndexedDB書込、ページ再読込後のmarker生存、Service Worker登録までを動作確認した。証跡は`results/S6_DESKTOP_2026-08-14.json`に置く。この結果はハーネス診断に限り、S6の端末合否には使わない。

同日の接続確認ではAndroid実機は0台、Apple側はiOS 26.5 Simulatorのみ利用可能だった。Simulatorは補助証跡にしかならないため起動試験で代替せず、実機マトリクスを残件とする。

ハーネス完成だけではS6 PASSとしない。iOS／Androidの上記4台相当で再起動・容量圧迫・背景遷移を実行して初めてクローズする。Phase 1実装はBackground SyncやWeb Pushの存在を正しさの前提にせず、前景保存と復帰時再送で動く。継続背景位置、強い端末鍵、オフライン端末の強制失効はネイティブ能力オプションとして分離する。
