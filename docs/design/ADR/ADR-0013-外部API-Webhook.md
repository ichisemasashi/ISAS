# ADR-0013：外部API／Webhook＝tenant単位REST＋署名outbox配送

| 項目 | 内容 |
|---|---|
| ステータス | **採用（クローズ） v1**（敵対的レビュー9件を全件処置、残存 High 0／Medium 0。[レビュー記録](レビュー記録_ADR-0013.md)） |
| 日付 | 2026-08-16 |
| 由来 | 要求仕様 F-90〜93、ADR-0008からの委譲事項 |
| 関連 | [ADR-0002 配備](ADR-0002-配備モデル-1DB-1国.md)、[ADR-0005 権限](ADR-0005-権限モデル-RBAC-メンバーシップ.md)、[ADR-0008 API](ADR-0008-API方式.md)、[ADR-0014 DWH](ADR-0014-横断分析-DWH.md)、[ADR-0017 セキュリティ](ADR-0017-セキュリティ基盤-端末暗号化-失効-脅威モデル.md) |

## 1. 決定

### 1.1 外部REST API

- 公開面はADR-0008のversion付きRESTとし、browser BFF sessionとは別のservice identityを用いる。OIDC/OAuth 2.0の短期access tokenを検証し、`client_id`、issuer、audience、scope、単一tenant、法域、期限を法域内registryの現在設定から導出する。token claimだけで権限を拡張しない。
- client credentialは1connector・1tenant・1用途とし、複数tenant／複数国を横断するtokenやendpointを提供しない。人の権限をserviceへ委任・偽装せず、監査actorは`service + client_id`とする。
- readはfield group等の明示scopeとRLSを通し、安定sort＋opaque cursorでpage化する。cursorはtenant、filter、上限時刻、API versionへ署名束縛し、別条件への流用を拒否する。
- writeはoperationごとの最小scope、`Idempotency-Key`、payload hash、resource versionを要求する。再送は同じ結果、同じkeyで異なるpayloadは409、競合は上書きせず返す。農薬master公開、権限、privacy、break-glass等の管理操作は一般外部APIへ公開しない。
- request body、page、同時実行、rateをclient単位で制限し、外部処理はP2 poolを使う。`429`は`Retry-After`、errorは安定codeとcorrelation IDを返し、内部SQL・stack・tenant存在を露出しない。
- API version廃止は最低180日前に通知し、schema互換testと利用client台帳で追跡する。緊急security停止は即時可能とし、停止理由と影響clientを監査する。

### 1.2 Outbound Webhook

- subscriptionはtenant管理者が作成し、event allowlist、payload profile、送信先、法的根拠、保持期限、ownerを持つ。既定payloadは`event_id`、種別、発生時刻、tenant内resource ID、versionだけとし、詳細は認可済みAPIでpullさせる。
- 送信先はHTTPS 443、登録済みhostname、法域／契約policyを満たすendpointだけとする。登録時と配送時の両方でDNSを解決し、loopback、link-local、private、metadata、予約IP、user-info、非標準port、redirectを拒否する。proxyの宛先allowlistも同じregistryから生成し、DNS rebindingで内部networkへ到達させない。
- domain所有確認後に有効化し、subscriptionごとにsecretをKMSで保護する。body bytesへ`delivery_id.timestamp.body`のHMAC-SHA-256署名を付け、algorithm／key ID／timestampをheaderへ出す。secret rotation中は新旧keyを短期間併用し、平文secretを再表示しない。
- 業務transactionと同じDB transactionでoutboxへeventを追記する。dispatcherはcommit済みeventだけをleaseし、同じ`event_id`、subscriptionごとの一意`delivery_id`、attempt番号を記録する。受信側には重複排除を要求し、全eventの完全な全順序は保証しない。resource単位のversionで前後を判断させる。
- 2xxだけを成功とし、timeout、指数backoff＋jitter、`Retry-After`上限、最大24時間を適用する。4xxの恒久失敗、期限超過はDLQへ送りsubscription ownerへ通知する。手動replayは同じeventと新しいdelivery IDを使い、actor、理由、範囲を監査する。
- 送信先response bodyを保存せず、status、latency、attempt、分類済みerrorだけを法域内telemetryへ記録する。payload、署名secret、tokenをlogへ出さない。

### 1.3 Inbound Webhookと失効

外部から受けるWebhookはoutbound URLと共用しない。connector別の固定path、署名／mTLS、timestamp window、provider event IDによる冪等化、body上限、schema versionを要求し、検証前のpayloadを業務処理へ渡さない。受理後はqueueへ隔離し、現在のconnector／tenant bindingを再評価する。

client、subscription、secret、scope、destinationの変更・失効は`authorization_version`を増加させ、active deliveryを停止し、queue内未送信分にも冪等適用する。失効後に古いsnapshotやretryから復活させない。

## 2. レジデンシー・プライバシー

- 外向きAPIとWebhookはADR-0002を継承する。法域外送信は既定拒否とし、圃場geometry、作業者、位置、農薬法定記録等の生dataを「Webhookだから」という理由で越境させない。
- 法域外へ送れるのは、項目allowlist、最小化／集計、送信根拠、privacy／legal承認、受領者、保持・削除、再委託先がconnector台帳で承認されたprofileだけとする。仮名化だけで匿名とみなさない。
- 第三者由来dataはlicense／再配布registryを検査する。本人開示・法定CSV・DWH exportは別purpose／別契約であり、一般API scopeへ混在させない。

## 3. 選択肢

| 選択肢 | 結論 |
|---|---|
| tenant単位REST＋transactional outbox Webhook | 採用。RLS、再送、監査、法域制御を一貫化 |
| browser sessionを外部clientへ渡す | 不採用。人とserviceの主体・失効・CSRF境界が崩れる |
| 任意URLへ同期HTTP送信 | 不採用。業務transaction遅延、SSRF、再送欠落を招く |
| 1 tokenで全tenantを横断 | 不採用。侵害時のblast radiusとレジデンシー違反が過大 |

## 4. Phase 3受入条件

1. tenant越境、scope拡張、失効競合、cursor流用、idempotency payload差替えを拒否する。
2. loopback、private IP、IPv6、DNS rebinding、redirect、巨大／遅いresponseを含むSSRF試験を通す。
3. commit／rollbackとoutboxの原子性、重複配送、順序逆転、24時間retry、DLQ、範囲指定replayを試験する。
4. secret rotation中の署名検証、失効直後のqueue停止、logのsecret／payload非出力を確認する。
5. P2外部負荷下でもP0失効とP1現場APIのSLOを守る。

実IdP client、連携先domain、payload項目、法的根拠、SLAはconnectorごとの二者承認対象であり、本ADRだけでは接続を許可しない。
