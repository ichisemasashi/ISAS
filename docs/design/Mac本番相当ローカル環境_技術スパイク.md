# Mac本番相当ローカル統合環境 技術スパイク

| 項目 | 内容 |
|---|---|
| 実施日 | 2026-08-16 |
| 対象 | ADR-0023 §5.1〜5.3 |
| Host | macOS 26.5.2、Apple Silicon arm64、Node.js 26.7.0 |
| Container runtime | Docker Desktop daemon 27.4.0（linux/arm64）、Compose 2.31.0 |
| 結果 | **Compose基盤の実装開始可**。component 8種はarm64／amd64 manifestとindex digestを確認済み |

## 1. 検証仮説と結果

| 仮説 | 実測 | 判定 |
|---|---|---|
| Docker daemonへ接続できる | `docker version`でserver 27.4.0／linux/arm64を取得 | PASS |
| Compose Specificationを利用できる | `docker compose version` 2.31.0 | PASS |
| BFFのNode.js 22 floorを満たす | Node.js 26.7.0 | PASS |
| 全基盤imageをApple Siliconでnative実行できる | component lock全8種にlinux/arm64 manifestあり | PASS |
| Intel Mac向け互換imageが存在する | component lock全8種にlinux/amd64 manifestあり | PASS（実機実行は未測定） |
| mutable tagを排除できる | Composeが参照するindex digestを`component-lock.json`へ固定 | PASS |

## 2. PgBouncerの版変更

ADR-0004／0023で仮置きしていた1.24.xは採用しない。[公式release情報](https://github.com/pgbouncer/pgbouncer/releases)で1.25.2より前に該当するsecurity修正が公開され、取得したmulti-arch imageのbinaryが1.25.2であることを確認した。このためlocal基盤は1.25.2を下限かつ固定版とし、5独立instanceの構成判断は変更しない。

## 3. 再現手順

```bash
node ops/local/doctor.mjs
node --test ops/local/doctor.test.mjs
```

`doctor`はhost OS／architecture、Node、Compose、Docker daemon、空き容量、component lockを変更前に検査する。失敗時はComposeを起動しない。Docker socketへ接続できない場合はDocker Desktopの起動とcurrent contextを確認し、socket権限を迂回しない。

## 4. 証拠範囲と残件

- amd64はmanifest存在だけを確認した。Intel Mac実機gateは未測定である。
- imageのSBOM／license／脆弱性scanはCompose acceptance前の残件である。
- 単一Mac上の測定であり、HA、Production容量、AWS adapterを証明しない。
- Keycloak realm、OIDC、失効、写真、PWAの結合試験は後続工程で実施する。
