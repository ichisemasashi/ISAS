# ISAS BFF core

ADR-0009の同一オリジンBFF境界を、Node.js標準APIだけで実装したコアである。HTTPサーバ、永続ストア、OIDC製品、PostgreSQL接続はアダプタとして接続する。

## 実装済みの境界

- `GET /api/bff/login`：単回state、nonce、PKCE S256を生成し、同一オリジンの`return_to`だけを保持する。
- `GET /api/bff/callback`：ログイン試行を単回消費し、検証済みOIDC主体から内部userを解決して、不透明sessionを発行する。
- `GET /api/bff/session`：HttpOnly Cookieから利用者と現在の所属候補だけを返す。OIDC tokenは返さない。
- `POST /api/bff/contexts`：Origin、CSRF、JSONを検証し、現在権限から短TTLのタブ用contextを発行する。
- `POST /api/bff/logout`：session/contextを失効し、IdP token失効アダプタを呼ぶ。
- `createContextResolver`：Cookieと`X-ISAS-Context`の束縛、期限、用途、現在権限を再検証し、業務API／DBアダプタだけが使う信頼済みAuthContextを返す。外部HTTPのuser／role／capabilityヘッダは入力に使わない。
- `createPostgresAuthContextAdapter`：`app_user`が非所有者・非superuser・非BYPASSRLSであることを確認し、`app_private.validate_auth_context(...)`が返した正規値だけを同一トランザクション内のGUCへ注入する。業務コールバックには`SET`、`set_config`、transaction制御を許さない制限付きclientを渡す。

## アダプタの保証条件

- `identityProvider.authorizationUrl`はallowlist済みissuerの認可endpointだけを返す。
- `identityProvider.exchangeCode`は認可コード交換に加え、ID Tokenの署名、`iss`、`aud`、`exp`、`nonce`、必要な`azp`と認証強度allowlistを検証し、token setは保存時暗号化済みの値だけを返す。
- `users.resolve`は`(issuer, subject)`だけを不変キーとして内部userを解決し、メールによる自動結合をしない。
- `authorization.listTenants`と`deriveContext`は認証専用DB経路から現在の所属・role・scope・capabilityを導出する。context IDや過去のsnapshotを権限の真実源にしない。
- `stores`の本番実装はsession ID、state、context IDのハッシュだけをキーとして保存し、絶対期限を持つ。メモリ実装は自動テスト専用であり、本番利用しない。
- PostgreSQL poolは`app_user`へ直接接続し、`app_private.validate_auth_context(user_id, tenant_id, allowed_tenants, scope_field_groups, caps, employer_subject_users)`を呼べるものとする。この関数は現在のmembership／role／scopeから包含関係を検査して正規値を1行返し、不正・失効時は0行を返す。関数DDLと実DB試験は未実装であり、アダプタ単体テストの成功をDB権限検証済みとは扱わない。

## 検証

```bash
cd apps/bff
npm test
npm run check
```

次の実装単位は、具体的なOIDCアダプタ、永続session/context store、PostgreSQLのAuthContext検証・`SET LOCAL`トランザクションアダプタである。
