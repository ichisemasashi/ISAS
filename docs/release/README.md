# Release一覧

> **現在の製品状態はbaseline／Production BLOCKEDです。**

| Release | Tag | 種別 | Production状態 |
|---|---|---|---|
| [1.0.0](RELEASE-1.0.0.md) | `v1.0.0` | 文書・実装baseline | **BLOCKED／未承認** |

通常の`v<version>` tagはbaselineに使用する。Production承認済みreleaseだけが`production/v<version>` namespaceを使用できる。`ops/check-production-release.mjs`がhost別実manifest、段階配備、24時間監視、二人承認を検査し、`ops/create-production-release-tag.sh`が検査対象commitへ署名注釈tagを作成する。

一覧に`production/v<version>`を追加するのは、remote tag発行と同じcommitでrelease証跡URIを記録できる場合に限る。
