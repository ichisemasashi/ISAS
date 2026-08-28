# Mac本番相当ローカル統合環境 操作ガイド

`local-integration` はDocker／Composeを使わず、macOSのlaunchd user agentでPostgreSQL、PgBouncer、Keycloak、OpenTelemetry Collector、BFF、Caddyを起動する本番相当の統合プロファイルです。外部公開用のProductionではありません。

## 1. 初回準備

HomebrewとNode.js 22以上を準備し、次を実行します。

```sh
brew install postgresql@16 pgbouncer caddy mkcert
mkcert -install
```

PostGIS 3.6.4をPostgreSQL 16へインストールし、次のファイルが存在することを確認します。

```sh
test -r "$(brew --prefix postgresql@16)/share/postgresql@16/extension/postgis.control"
```

version固定されたTemurin、Keycloak、OpenTelemetry Collectorを検証付きで配置します。

```sh
ops/local/install-native-components.sh
node ops/local/doctor.mjs
```

フル受入試験を行う場合はWeb依存も準備します。

```sh
npm --prefix apps/web ci
npx --prefix apps/web playwright install chromium webkit
```

## 2. 起動・状態確認・再起動・終了

```sh
ops/local/local-up.sh
ops/local/local-status.sh
ops/local/local-restart.sh
ops/local/local-stop.sh
```

起動後の入口は `https://isas.localhost:8443` です。TLS ingressはloopbackだけをlistenするため、LANへは公開されません。

launchd定義、ログ、状態、秘密情報、ローカルobjectは、repository外の次の場所に保存されます。

```text
~/Library/Application Support/ISAS/local-integration/
```

障害調査では、まず `local-status.sh` の出力と同ディレクトリの `log/` を確認します。

## 3. 管理者ログイン

起動時に調停される `local-operator` は管理操作用です。認証情報はterminalへ表示せず、所有者だけが読める次のファイルから取得します。

```sh
set -a
. "$HOME/Library/Application Support/ISAS/local-integration/secrets/runtime.env"
set +a
printf 'username=%s\n' "$LOCAL_OPERATOR_USERNAME"
printf 'password=%s\n' "$LOCAL_OPERATOR_PASSWORD"
printf 'totp-secret=%s\n' "$LOCAL_OPERATOR_TOTP_SECRET"
```

ブラウザのログイン画面でユーザー名、password、認証アプリが生成したTOTPを入力します。秘密値を文書、issue、chat、shell履歴へ転記しないでください。

## 4. テスト利用者の登録

一般利用者はusernameまたはemailとpasswordでログインします。管理者roleはMFA必須です。

```sh
ops/local/register-test-user.sh \
  --username test-worker \
  --display-name 'テスト作業者' \
  --role worker
```

利用可能なroleは `worker`、`field_supervisor`、`organization_admin`、`group_admin`、`contractor` です。生成された認証情報は次に保存されます。

```text
~/Library/Application Support/ISAS/local-integration/secrets/test-users/<username>.env
```

`migration:manage` を必要とする管理者は `group_admin` として登録します。

```sh
ops/local/register-test-user.sh \
  --username migration-admin \
  --display-name '移行管理者' \
  --role group_admin
```

## 5. 受入確認

基盤と境界の確認は次を実行します。

```sh
ops/local/verify-local-environment.sh
```

OIDC authorization code＋PKCE、password＋TOTP、step-up、session、RLS業務経路、CSP／CORPまで確認する場合は次を実行します。

```sh
ops/local/verify-local-environment.sh --full
```

## 6. 障害時の基本操作

個別processを直接killせず、まず全体を再起動します。

```sh
ops/local/local-status.sh
ops/local/local-restart.sh
ops/local/verify-local-environment.sh
```

解消しない場合は `~/Library/Application Support/ISAS/local-integration/log/` の対象service logを確認します。Docker daemonや`docker compose`はこのプロファイルの構成要素ではありません。

## 7. データを破棄するreset

次の操作はDB、IdP、object、監視状態、TLS鍵、local keyを削除し、復旧できません。対象は固定されたlocal-integration data rootに限定されます。

```sh
ops/local/local-reset.sh --confirm-local-data-loss
```

実行前に未同期dataがないことと、必要な退避が完了していることを確認してください。
