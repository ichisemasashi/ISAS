# Mac本番相当ローカル統合環境 責任境界設計

| 項目 | 内容 |
|---|---|
| 版 | v2（ADR-0023の方式決定を反映） |
| 日付 | 2026-08-16 |
| 状態 | **確定**。component方式と採用製品は[ADR-0023](ADR/ADR-0023-Mac本番相当ローカル統合環境.md)で確定、実装は未着手 |
| 上位要求 | [要求仕様書 v1.1 §5.6](../農業営農支援システム_要求仕様書.md#56-mac本番相当ローカル統合環境) |

---

## 1. 設計上の位置づけ

本環境のprofile IDは`local-integration`とし、ADR-0021 §2.4の**Integration環境**として扱う。ADR-0019のProduction環境ではない。production code pathとの共通性を高める一方、provider adapterとfailure domainはlocal専用境界へ差し替える。

```
Mac host（単一failure domain／非本番）
  ├─ Browser / installed PWA
  ├─ HTTPS ingress（local CA）
  │    ├─ production-built Web assets
  │    └─ BFF runtime
  │         ├─ production共通 domain・認可・同期core
  │         └─ local-integration adapter境界
  └─ local dependencies
       ├─ OIDC / MFA
       ├─ PostgreSQL 16 + PostGIS
       ├─ priority別pool endpoint
       ├─ session / context store
       ├─ object store
       ├─ queue / DLQ
       ├─ envelope key service
       └─ telemetry collector
```

枠内はすべて同じMac、Docker daemon、filesystem、電源、LANに依存する。containerやnetworkを分けてもfailure domain、管理者境界、物理鍵境界は分離されたとはみなさない。

## 2. 信頼境界

| 境界 | 内側で許すこと | 越境時の契約 |
|---|---|---|
| Browser ↔ HTTPS ingress | test account、暗号済みPWA data | TLS必須、Secure／HttpOnly／SameSite cookie、CORS allowlist、LAN公開は明示opt-in |
| Ingress ↔ BFF | same-origin API、request ID | client申告tenant／role／priorityを信用せず、body／timeout／drain制御を通す |
| BFF core ↔ local adapter | production共通のport/interface | adapter選択は起動時固定。production adapterとの暗黙fallback禁止 |
| BFF ↔ DB/pool | transaction単位のtenant／scope注入 | P0/Auth-P1/P1/P2/Ops endpoint、least privilege role、RLS、監査、timeoutを維持 |
| BFF ↔ identity/session | OIDC subjectとserver導出AuthContext | browser tokenを業務APIへ直接渡さず、nonce、issuer、audience、MFA、authorization versionを検証 |
| BFF ↔ object/queue/key | synthetic attachment、失効event、local envelope key | private access、冪等性、DLQ、MIME/signature、鍵用途分離。平文fallback禁止 |
| Local profile ↔ 外部network | loopbackを既定 | production endpoint拒否。許可するtest endpointは明示allowlistと台帳を必要とする |
| Local volume ↔ host operator | synthetic dataと生成secret | 最小権限、Git除外、stop時保持、reset時だけ消去、対象と結果を監査logへ記録 |

## 3. RACI

R=実施、A=最終責任、C=協議、I=通知。`PO`=ISAS製品保守者、`LO`=ローカル環境管理者、`QV`=検証責任者、`PR`=本番配備・サービス運用者。

| 作業 | PO | LO | QV | PR |
|---|:---:|:---:|:---:|:---:|
| local構成・adapter・scriptの設計保守 | A/R | C | C | I |
| Mac／Docker／disk／firewall／local CA管理 | C | A/R | I | I |
| migration・RLS・security回帰suite保守 | A/R | C | C | I |
| synthetic seedとtest accountの管理 | R | R | A | I |
| local test実行、証跡scope、残risk判定 | C | R | A/R | I |
| local secret rotation、volume保持・reset | C | A/R | I | I |
| provider／法域／HA／backup／DR | C | I | C | A/R |
| staging受入とproduction release判定 | C | I | R | A |
| incident時のlocal診断 | R | A/R | C | I |
| product脆弱性の修正・配布 | A/R | I | C | C |

## 4. 誤用を防ぐ強制点

文書上の注意だけでなく、後続実装は次を機械的に強制する。

- `ISAS_ENV_PROFILE=local-integration`以外ではlocal adapterを読み込まない
- `NODE_ENV=production`と配備profileを別概念にする。production buildを使ってもprofileは非本番のままとする
- account ID、hostname、ARN、connection stringを起動前に検査し、loopback／local networkと明示されたtest allowlist以外は停止する。production denylistだけに依存しない
- health、HTML banner、API response header、test evidenceへprofile IDを出す
- production credentialの環境変数名・secret mount・metadata credential取得をlocal profileで拒否する
- `stop`と`reset`を別commandにし、resetは明示確認と対象volume列挙を必要とする
- LAN listenerはloopback既定、明示設定時だけ許可し、TLSなしでは起動しない
- local evidenceをproduction release manifestの必須証跡として受理しないschema検査を設ける

## 5. ADR-0023への入力と裁定結果

次の事項は責任境界ではなく方式決定であるため、本書では確定せずADR-0023へ渡した。

1. local OIDC、object、queue、telemetry、ingress、poolerの採用製品とversion
2. session/contextと失効eventをPostgreSQLへ集約するか、専用emulatorを使うか
3. envelope keyの保存先、local CA、開発者ごとのsecret bootstrap方式
4. ARM64／x86_64 image matrix、最小macOS／Docker Desktop／Colima version
5. Compose topology、volume、port、resource limit、起動順、health contract

ADR-0023はCompose、Caddy、Keycloak、PG16/PostGIS、5独立PgBouncer、PG永続session/context／queue、private filesystem object、local key、OTel stackを採用した。本書の名称、安全境界、非本番分類、責任分担は変更していない。今後これらの境界変更が必要な場合は要求仕様書v1.1を先に改版する。
