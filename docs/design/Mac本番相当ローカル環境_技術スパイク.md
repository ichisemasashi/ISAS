# Mac本番相当ローカル統合環境 技術スパイク

| 項目 | 内容 |
|---|---|
| 実施日 | 2026-08-16 |
| 対象 | ADR-0023 実装順序1〜5のうち基盤、DB、local BFF adapter、OIDC／MFA、同一origin HTTPS、主要業務API接続 |
| Host | macOS 26.5.2、Apple Silicon arm64、Node.js 26.7.0 |
| Container runtime | Docker Desktop daemon 27.4.0（linux/arm64）、Compose 2.31.0 |
| 結果 | **実装順序1〜5の対象範囲PASS**。component 8種のmulti-arch固定、PG16/PostGIS、5 pool、Keycloak OIDC＋TOTP MFA／step-up、同一origin HTTPS、RLS業務APIを実測 |

## 1. 検証仮説と結果

| 仮説 | 実測 | 判定 |
|---|---|---|
| Docker daemonへ接続できる | `docker version`でserver 27.4.0／linux/arm64を取得 | PASS |
| Compose Specificationを利用できる | `docker compose version` 2.31.0 | PASS |
| BFFのNode.js 22 floorを満たす | Node.js 26.7.0 | PASS |
| 全基盤imageをApple Siliconでnative実行できる | component lock全8種にlinux/arm64 manifestあり | PASS |
| Intel Mac向け互換imageが存在する | component lock全8種にlinux/amd64 manifestあり | PASS（実機実行は未測定） |
| mutable tagを排除できる | Composeが参照するindex digestを`component-lock.json`へ固定 | PASS |
| 正式migrationをPG16/PostGISへ適用できる | application migration 18本とlocal migrationをchecksum付きで適用し、再実行差分0 | PASS |
| DB境界が設計どおり閉じる | owner分離、FORCE RLS、監査trigger、`security_invoker`、PostGIS、`priv`／local schema非公開を検査 | PASS |
| 優先度poolを分離できる | P0／Auth-P1／P1／P2／Opsを5つのPgBouncer 1.25.2 instanceと専用roleで実接続 | PASS |
| production buildのBFFをlocal依存へ接続できる | `NODE_ENV=production`＋`ISAS_ENV_PROFILE=local-integration`で起動し、5 pool、PG store、Keycloak、object、queue、OTLPのstartup checkに合格 | PASS |
| browserとBFFで同じOIDC issuerを使える | Caddy経由`https://isas.localhost:8443/oidc/realms/isas-local`のdiscovery 200、login 302、PKCE S256 | PASS |
| 実IdPでMFAとstep-upを強制できる | Keycloakのpassword＋TOTPでloginし、AMRを検証。step-up後も同一subjectを維持し、logout後session 401 | PASS |
| browser通信をHTTPS同一originへ限定できる | Web／BFF／OIDCを`https://isas.localhost:8443`だけで完結し、HSTS／CSP／CORPとSecure HttpOnly Cookieを検査 | PASS |
| 主要業務を実DB＋RLSへ接続できる | AuthContextをDBから導出し、本日の作業、圃場、指示、日誌、農薬、在庫を読取り、進捗eventを冪等書込み | PASS |
| 外部へ不用意に公開しない | host公開は`127.0.0.1:8443`の1 portだけ。他componentは内部network限定 | PASS |

## 2. PgBouncerの版変更

ADR-0004／0023で仮置きしていた1.24.xは採用しない。[公式release情報](https://github.com/pgbouncer/pgbouncer/releases)で1.25.2より前に該当するsecurity修正が公開され、取得したmulti-arch imageのbinaryが1.25.2であることを確認した。このためlocal基盤は1.25.2を下限かつ固定版とし、5独立instanceの構成判断は変更しない。

## 3. 再現手順

```bash
node ops/local/doctor.mjs
node --test ops/local/doctor.test.mjs
ops/local/local-up.sh
ops/local/verify-local-environment.sh
ops/local/verify-local-environment.sh --full
```

`doctor`はhost OS／architecture、Node、Compose、Docker daemon、空き容量、component lockを変更前に検査する。失敗時はComposeを起動しない。Docker socketへ接続できない場合はDocker Desktopの起動とcurrent contextを確認し、socket権限を迂回しない。

## 4. 実測結果

2026-08-16にApple Silicon Mac上で次を確認した。

- TLS入口：live 200、BFF readiness 200、未認証session 401、login 302、OIDC discovery 200
- DB検査：PostgreSQL 16.4／PostGIS 3.4.3、production security verification PASS、5独立pool role一致
- BFF回帰：102 tests中102 PASS、syntax／runtime adapter contract check PASS
- Web回帰：Vitest 48/48、production build PASS
- 実browser E2E：Keycloak password＋TOTP、PKCE、AuthContext、RLS業務API、進捗書込み、step-up、logout、同一origin／security headerの2/2 PASS
- 再起動境界：migrationはchecksum一致で再適用せず、DB／IdP／object／鍵volumeを保持

`verify-local-environment.sh`は秘密値を表示せず、host条件、構成、health、loopback bind、DB境界、TLS／OIDCを再現可能に検査する。`--full`はさらに実browserでMFA、step-up、logout、同一originと合成業務workflowを検査する。

## 5. 証拠範囲と残件

- amd64はmanifest存在だけを確認した。Intel Mac実機gateは未測定である。
- imageのSBOM／license／脆弱性scanはCompose acceptance前の残件である。
- 単一Mac上の測定であり、HA、Production容量、AWS adapterを証明しない。
- local Keycloak realm、PKCE OIDC、TOTP MFA／step-up／logout、PG永続session/context、RLS業務APIと進捗書込みは実browserまで確認した。back-channel logoutの実IdP event、失効consumerの障害注入、写真upload/downloadの実画面E2Eは後続である。
- 地図はadapter境界用synthetic fixtureであり、review済み実PMTiles／attribution／NOTICEの受入は未実施である。
- PWA offline同期、stop後の未同期data保持、reset拒否、PII telemetry canary、SBOM／license／脆弱性scan、負荷試験は`verify-local-environment`の後続gateである。
