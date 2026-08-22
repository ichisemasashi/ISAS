# Release一覧

> **現在の製品状態はbaseline／Production BLOCKEDです。**

| Release | Tag | 種別 | Production状態 |
|---|---|---|---|
| [1.0.0](RELEASE-1.0.0.md) | `v1.0.0` | 文書・実装baseline | **BLOCKED／未承認** |

通常の`v<version>` tagはbaselineに使用する。Production承認済みreleaseだけが`production/v<version>` namespaceを使用できる。`ops/check-production-release.mjs`がhost別実manifest、段階配備、24時間監視を検査し、`ops/check-production-tag-authorization.mjs`が4証跡の実content digest、GitHub Environmentの検証済み二人承認、保護tag ruleset、監査eventを署名attestationに対して検証する。`ops/create-production-release-tag.sh`は両検査を再実行しない限りtagを作成しない。

署名authorizationは`production-release` Environment内の承認job／attestation serviceだけが発行する。信頼するEd25519公開鍵はEnvironment secret `ISAS_TAG_AUTHORIZATION_PUBLIC_KEY`にpinし、private keyをrepository、artifact、一般runnerへ置かない。authorization内の`release`、`build`、`delivery`、`bake` digestは、tag入口へ渡す4ファイルのbyte列と一致しなければならない。二人のapprovalにはGitHubが発行した固有`approval_id`と`repo:<owner>/<repo>:environment:production-release` subjectを保存する。rulesetとaudit eventはAPI応答snapshotそのものとそのdigestを保存し、`production/v*` rulesetがactiveでない場合は発行しない。

一覧に`production/v<version>`を追加するのは、remote tag発行と同じcommitでrelease証跡URIを記録できる場合に限る。
