# Mac本番相当ローカル統合環境 操作コマンド

これはproduction releaseではなく、production buildと共通業務coreをMac 1台で結合する`local-integration` profileである。既定入口はloopback限定の`https://isas.localhost:8443`で、AWS credentialとproduction dataを使用しない。

## 初回準備

Docker Desktopを起動し、Node.js 22以上とmkcertを用意する。

```bash
brew install mkcert
mkcert -install
node ops/local/doctor.mjs
```

`mkcert -install`はmacOS管理者passwordを要求する。自動処理でpasswordを渡さず、利用者自身がTerminalで実行する。CA未登録でもcontainerは起動できるが、ブラウザは証明書警告を表示するため受入試験には使わない。

Docker daemonを確認する。`Server`欄まで表示されなければDocker Desktopを起動し、readyになるまで待つ。

```bash
docker version
docker compose version
```

## 起動・確認・停止

```bash
ops/local/local-up.sh
ops/local/local-status.sh
open https://isas.localhost:8443
ops/local/local-restart.sh
ops/local/local-stop.sh
```

`stop`と`restart`はDB、IdP、object、監視data、鍵を保持する。起動時に`.local/`へ生成する秘密値、証明書、objectはGitの追跡対象外であり、commandは値を表示しない。

初回起動時はimage取得、Web／BFF build、PostgreSQL初期化、Keycloak importのため数分かかる。終了を待たずに中断しない。別Terminalから次で進行を確認できる。

```bash
ops/local/local-status.sh
docker compose --project-directory . --env-file .local/secrets/runtime.env -f compose.local.yml logs --tail=100 database keycloak bff caddy
```

## ローカル利用者でログイン

ブラウザで`https://isas.localhost:8443`を開き、次を使用する。

- 利用者名：`local-operator`
- password：`.local/secrets/runtime.env`の`LOCAL_OPERATOR_PASSWORD`

passwordを画面やlogへ貼らない。本人がTerminalで確認する場合だけ、repository rootで次を実行する。

```bash
sed -n 's/^LOCAL_OPERATOR_PASSWORD=//p' .local/secrets/runtime.env
```

この利用者とsynthetic tenantはlocal migrationでのみ作成される。本番の利用者・tenant・credentialを流用しない。

## 受入検証

stack起動後、次の1 commandでhost条件、Compose、公開port、全health、migration、PostGIS、owner、FORCE RLS、監査trigger、5 pool、TLS、BFF、OIDCを検証する。

```bash
ops/local/verify-local-environment.sh
```

`local-integration foundation verification: PASS`になることを確認する。この検証は基盤とlocal BFF adapterの受入であり、実PMTiles、PWA offline E2E、MFA操作、負荷、端末実機、AWS staging、本番release gateを代替しない。

## 起動失敗時

`Cannot connect to the Docker daemon`ならDocker Desktopを起動し、`docker context use desktop-linux`後に`docker version`のServer欄を確認する。`BFF request failed (502)`ならWebだけでなくBFF／DB／Keycloakのhealthを確認する。

```bash
ops/local/local-status.sh
docker compose --project-directory . --env-file .local/secrets/runtime.env -f compose.local.yml logs --tail=200 bff keycloak database
ops/local/local-restart.sh
```

証明書警告だけが残る場合は`mkcert -install`を利用者Terminalで再実行してブラウザを再起動する。秘密値をlogへ出力したり、socket権限を変更して回避したりしない。

## 全消去

次の操作はlocal DB、IdP、未配送queue、object、監視data、暗号鍵を回復不能にする。対象が`isas-local-integration`であることを`local-status.sh`で確認してから実行する。

```bash
ops/local/local-reset.sh --confirm-local-data-loss
```

確認flagなしのresetは終了code 64で拒否する。本番環境やAWS credentialをこのprofileへ設定してはならない。
