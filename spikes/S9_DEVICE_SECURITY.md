# S9 端末暗号化・失効・鍵rotation検証

## 実装境界

PWAは`apps/web/src/device-security.ts`の専用IndexedDB vaultを使う。同期済みcacheは非抽出AES-256-GCM鍵、未同期outboxはイベントごとのAES-256-GCM content keyを使い、content keyを非抽出AES-256-KW端末鍵と法域`offline_recovery` RSA-OAEP-256公開鍵の二経路でwrapする。鍵用途、tenant、record IDはAEAD AADへ束縛する。

公開鍵はbuild成果物へ埋め込まず、配備時に`/device-security-config.json`を次の形式でmountする。秘密鍵はAWS KMS/HSM外へ出さない。

```json
{
  "schemaVersion": 1,
  "keyId": "arn:aws:kms:ap-northeast-1:ACCOUNT:key/KEY_ID",
  "recoveryPublicJwk": { "kty": "RSA", "n": "...", "e": "AQAB", "alg": "RSA-OAEP-256" }
}
```

空の同梱fileはfail-closed用である。公開鍵がないproduction端末ではoutboxを平文またはlocal keyだけで保存せず、記録確定を失敗させる。公開鍵変更は先に新公開鍵を配布し、既存packageの`recoveryKeyId`参照が0になるまで旧private keyを無効化しない。

## 自動検証

```bash
cd apps/web
pnpm test
pnpm build
pnpm test:device-security
pnpm test:pwa

cd ../..
node --test ops/test/check-device-acceptance.test.mjs
node ops/check-device-acceptance.mjs spikes/results/S6_S9_DEVICE_ACCEPTANCE.template.json
```

最後のcommandは実機証跡が空なので`BLOCKED`終了（exit 1）が正しい。desktop Chromium試験は次を確認する。

- cache/outbox鍵の用途分離と`extractable=false`
- cache再暗号化、outbox content key rewrap、旧鍵参照0後の削除
- recovery upload証跡がない失効の拒否
- cache鍵の暗号消去後もoutbox ciphertext＋RSA recovery wrapを保持し、private keyで回復可能
- quota安全余白（10%または32MiBの大きい方）
- 再読込後も暗号化outboxのUUIDと内容を保持
- 未同期0件のproduction logoutでcache鍵を破棄
- 未同期ありのlogoutを拒否し、outbox回復可能性を維持

## 実機手順

各プロファイルで`spikes/S6_DEVICE_TEST.md`の能力測定後、production候補URLを使う。開発fixtureやSimulatorを合格証跡にしない。

1. online loginし、cacheと1件の未同期outboxを作る。DevTools remote inspectionでvault行に平文payloadがなく、cache/outboxのCryptoKey用途とversionが異なることを記録する。鍵exportは`InvalidAccessError`になることを確認する。
2. browser終了、OS再起動、空き容量10%未満の各条件でoutboxを再読込・同期する。event UUIDが不変でserver重複が0であることを記録する。
3. rotationを途中でbrowser強制終了し、再起動後に新旧どちらの行も読めること、再実行で新versionへ収束し旧version参照が0になることを確認する。
4. 未同期ありでlogoutし、拒否表示とoutbox残存を確認する。同期後にlogoutし、cache/outbox local key、画面履歴、別利用者の前利用者dataが読めないことを確認する。
5. 管理者が端末紛失を宣言し、membership/session/context/offline snapshotを失効する。次回onlineでcache鍵が消去され、回復packageだけが隔離されることを確認する。
6. 二人承認でHSM内private keyを使用し、event/tenant/key ID一致、監査event、再pushの冪等受理を確認する。平文packageを端末やticketへ添付しない。
7. OS更新前後で同じ操作を行い、更新失敗・rollback・browser data migrationによる鍵欠落時も、平文縮退やoutbox消去がないことを確認する。

## 現在の判定（2026-08-16）

Chromium自動試験はPASSした。PWAのWeb Crypto非抽出性はAPI上の制約であり、OS hardware-backed keyや活性XSS耐性を保証しない。iOS/Android実機8プロファイル、OS更新、実Cognito logout、実KMS回復は環境未接続のため未実施で、S6/S9総合gateは`BLOCKED`のままである。
