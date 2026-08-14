# ISAS BFF core

ADR-0009の同一オリジンBFF境界と、ADR-0007/0008のMVP REST・同期面を実装したコアである。HTTPサーバ、永続ストア、OIDC製品、PostgreSQL poolは配備アダプタとして注入する。

## 実装済みの境界

- `GET /api/bff/login`：単回state、nonce、PKCE S256を生成し、同一オリジンの`return_to`だけを保持する。
- `GET /api/bff/callback`：ログイン試行を単回消費し、検証済みOIDC主体から内部userを解決して、不透明sessionを発行する。
- `GET /api/bff/session`：HttpOnly Cookieから利用者と現在の所属候補だけを返す。OIDC tokenは返さない。
- `POST /api/bff/contexts`：Origin、CSRF、JSONを検証し、現在権限から短TTLのタブ用contextを発行する。
- `POST /api/bff/logout`：session/contextを失効し、IdP token失効アダプタを呼ぶ。
- `createContextResolver`：Cookieと`X-ISAS-Context`の束縛、期限、用途、現在権限を再検証し、業務API／DBアダプタだけが使う信頼済みAuthContextを返す。外部HTTPのuser／role／capabilityヘッダは入力に使わない。
- `createPostgresAuthContextAdapter`：`app_user`が非所有者・非superuser・非BYPASSRLSであることを確認し、`app_private.validate_auth_context(...)`が返した正規値だけを同一トランザクション内のGUCへ注入する。DBはBFFが提示したtenant／scope／capability集合を拒否または縮小できるが、主体・書込tenantの置換や集合の拡張はできない。業務コールバックには`SET`、`set_config`、transaction制御を許さない制限付きclientを渡し、ROLLBACK失敗時は接続を破棄する。
- `createIsasApplication`：上記BFFと実pool向けPostgreSQL repositoryを同一オリジンへ組み込み、認証で再導出した正規AuthContextだけをRESTへ渡す。

## MVP REST・同期API

- `GET /api/v1/today`：RLS適用後の当日作業を返す。Webは成功時にIndexedDB cacheを更新する。
- `POST /api/v1/sync/push`：tenant内の依存束を原子受理し、`(tenant_id,event_uuid)`受理台帳で再送を排除する。現在のcapability、membership version、authorization snapshotを受理時に再検証する。
- `GET /api/v1/sync/pull`：`(tenant,scope,priority)`別cursorと取得開始時の上限を使う。P0と通常差分は独立cursorである。
- `GET /api/v1/sync/queues`：権限変更による差し戻しと、フィールド単位の楽観競合を返す。
- `POST /api/v1/sync/conflicts/:id/resolve`：`conflict:resolve`を持つ現在の管理者だけがサーバ値／端末値を裁定し、状態と変更ログを同一transactionで更新する。

Webのoutboxは受理または重複応答を得るまで削除しない。差し戻しと競合は別のIndexedDB queueへ隔離し、オンライン復帰時はP0 pull→push→通常pullの順に処理する。

## PostgreSQL migration

MVP業務表は次の順で適用する。`0001`は現時点ではS8 AuthContext参照DDLが先に存在することを前提とする。

```bash
psql "$DATABASE_URL" -f spikes/S8_auth_context.sql
psql "$DATABASE_URL" -f apps/bff/migrations/0001_mvp_sync.sql
psql "$DATABASE_URL" -f apps/bff/migrations/0002_conflict_fields.sql
```

業務表はすべて`ENABLE/FORCE ROW LEVEL SECURITY`である。tenant policyをpermissive基底、field scopeと競合裁定capabilityをrestrictive条件にしてAND合成する。アプリ接続は必ず`app_user`を使う。

ローカル検証は次の順で再現できる。`00_common.sql`は検証DBのschema/roleを再作成するため、本番DBでは実行しない。

```bash
cd spikes && docker compose up -d && cd ..
PGPASSWORD=spike psql -h 127.0.0.1 -p 55432 -U postgres -d spike \
  -f spikes/00_common.sql -f spikes/S8_auth_context.sql \
  -f apps/bff/migrations/0001_mvp_sync.sql \
  -f apps/bff/migrations/0002_conflict_fields.sql \
  -f apps/bff/migrations/verify/0001_mvp_sync_verify.sql
```

## アダプタの保証条件

- `identityProvider.authorizationUrl`はallowlist済みissuerの認可endpointだけを返す。
- `identityProvider.exchangeCode`は認可コード交換に加え、ID Tokenの署名、`iss`、`aud`、`exp`、`nonce`、必要な`azp`と認証強度allowlistを検証し、token setは保存時暗号化済みの値だけを返す。
- `users.resolve`は`(issuer, subject)`だけを不変キーとして内部userを解決し、メールによる自動結合をしない。
- `authorization.listTenants`と`deriveContext`は認証専用DB経路から現在の所属・role・scope・capabilityを導出する。context IDや過去のsnapshotを権限の真実源にしない。
- `stores`の本番実装はsession ID、state、context IDのハッシュだけをキーとして保存し、絶対期限を持つ。メモリ実装は自動テスト専用であり、本番利用しない。
- PostgreSQL poolは`app_user`へ直接接続し、`app_private.validate_auth_context(user_id, tenant_id, allowed_tenants, scope_field_groups, caps, employer_subject_users)`を呼べるものとする。この関数の参照DDLは[`spikes/S8_auth_context.sql`](../../spikes/S8_auth_context.sql)にあり、PostgreSQL 16で12群PASS、ADR-0005 v8で採用済み。版管理・永続失効イベント・索引・backfillを含むAuthContext本番migrationへの昇格は残る。

## 検証

```bash
cd apps/bff
npm test
npm run check
```

2026-08-14時点でBFF 27テスト、Web 15テスト、本番Web build、PostgreSQL 16.4＋PostGIS 3.4.3上のS8 12群＋MVP RLS 6群がPASSしている。実配備に残るのは具体的なOIDCアダプタ、永続session/context store、pool driverを生成するHTTP runtime、S8参照DDLの本番migration化である。
