# Mac本番相当ローカル統合環境 操作コマンド

## 初回準備

Docker Desktopを起動し、Node.js 22以上とmkcertを用意する。

```bash
brew install mkcert
mkcert -install
node ops/local/doctor.mjs
```

`mkcert -install`はmacOS管理者passwordを要求する。自動処理でpasswordを渡さず、利用者自身がTerminalで実行する。CA未登録でもcontainerは起動できるが、ブラウザは証明書警告を表示するため受入試験には使わない。

## 起動・確認・停止

```bash
ops/local/local-up.sh
ops/local/local-status.sh
open https://isas.localhost:8443
ops/local/local-restart.sh
ops/local/local-stop.sh
```

`stop`と`restart`はDB、IdP、object、監視data、鍵を保持する。起動時に`.local/`へ生成する秘密値、証明書、objectはGitの追跡対象外であり、commandは値を表示しない。

## 全消去

次の操作はlocal DB、IdP、未配送queue、object、監視data、暗号鍵を回復不能にする。対象が`isas-local-integration`であることを`local-status.sh`で確認してから実行する。

```bash
ops/local/local-reset.sh --confirm-local-data-loss
```

確認flagなしのresetは終了code 64で拒否する。本番環境やAWS credentialをこのprofileへ設定してはならない。
