# ISAS

イチセ・スマート・アグリ・システム

営農支援Webアプリケーション。ライセンスは MIT（[LICENSE](LICENSE)）。

第1版の要求は [docs/要求仕様書.md](docs/要求仕様書.md) を正とする。工程ごとの振る舞いは [docs/基本仕様書.md](docs/基本仕様書.md) を正とする。論理構成は [docs/基本設計書.md](docs/基本設計書.md) を正とする。作り方と順番は [docs/工程表.md](docs/工程表.md) を正とする。

## 工程1・工程2 の動かし方

JDK 21 と Clojure CLI と Node.js が要る。置くマシンの `data/isas.conf` を直す（サンプルのパスワードはコミットしない）。

```bash
npm install
./scripts/run.sh
```

ブラウザで `http://localhost:8080/`（利用者）と `http://localhost:8080/admin`（管理者）を開く。利用者はログイン後、`/fields`（圃場台帳）と `/map`（地図）を使う。下地画像は `data/basemaps/` に残り、Git には入れない。

操作ログは端末と `data/isas.log` に残る。古いファイルは `data/isas.日付.番号.log` に回り、Git には入れない。

## テスト

試験項目は二段である。論理は各工程の基本試験仕様書（基本設計の表が目次）、経路と API は各工程の詳細試験仕様書（詳細設計の表が目次）。いずれも [工程表.md](docs/工程表.md) から辿る。ソースのカバレッジはこれらに代えない。

Clojure / ClojureScript のソースを cloverage 100% と cljs.test で確認する。

```bash
npm install
./scripts/test.sh
```
