# 3 OS native release artifact 受入runbook

本runbookはADR-0024 R3の実行手順である。macOS、Linux、FreeBSDのnative packageを同一source commitからbuildし、filesystem scan、checksum、署名、SBOM、provenance、実installを検証する。R3完了までは旧image build経路を削除しない。

## 1. 現在の状態

| 項目 | 状態 |
|---|---|
| 3 OS×2 architecture×6 serviceのartifact契約 | 実装済み |
| AppleDouble／`.DS_Store`のpayload除外 | unit test・macOS arm64 package smoke PASS |
| filesystem Critical／High、secret、misconfiguration gate | workflow実装済み |
| checksum、署名、SPDX SBOM、SLSA provenance | workflow実装済み |
| ephemeral native hostへの実install | **未実施** |
| 完全な36 artifact manifest | **実runner受入待ち** |

静的testやmacOS package生成だけをR3完了証跡へ昇格しない。`signature_verified`と`install_verified`が全36 recordで`true`になったmanifestが必要である。

## 2. runner要件

使い捨てまたは実行後に再image化するself-hosted runnerを、次のlabelで用意する。

| OS | architecture label | package tool |
|---|---|---|
| `macos` | `arm64`、`amd64` | `pkgbuild`、`installer` |
| `linux` | `aarch64`、`x86_64` | `dpkg-deb`、`dpkg` |
| `freebsd` | `arm64`、`amd64` | `pkg` |

すべてのrunnerへ`self-hosted`、`ephemeral`、OS、architectureの4 labelを設定する。Node.js 22、OpenSSL、Trivy、Git、service別に必要なpackage tool、Web build用のCorepackを導入する。install検証はsystem領域へ書くため、対象package commandだけを非対話で実行できる承認済み`sudo`／root境界を用意する。常設hostや業務dataを持つhostでは実行しない。

GitHub Actionsのsecret `NATIVE_ARTIFACT_SIGNING_KEY`へ、対象release trainで承認されたPEM private keyを登録する。値をrepository variable、artifact、logへ出さない。AWS KMS／ECRはこのnative buildの共通必須条件ではない。

## 3. workflow実行

SemVerは`v`を付けずに指定する。

```sh
gh workflow run build-native-release.yml -f version=1.1.0-rc.1
gh run list --workflow build-native-release.yml --limit 5
gh run watch <run-id> --exit-status
```

workflowは各runnerで次を行う。

1. `uname`とrunner labelのOS一致を検査する。
2. 各OS／architectureでTrivy filesystem scanを実行する。
3. 6 serviceのnative packageをbuildする。
4. AppleDoubleと`.DS_Store`がartifact領域へ混入していないことを検査する。
5. checksumと署名を検証し、ephemeral hostへpackageをinstallする。
6. install済み`bin/start`の存在を確認し、recordの検証flagを更新する。
7. 36 recordを一つの`native-artifact-manifest.json`へ束縛する。

## 4. 受入判定

workflow artifactをGit管理外の検証directoryへ取得し、次を実行する。

```sh
node ops/native-artifacts/check-native-artifact-manifest.mjs \
  ops/native-artifacts/native-artifact-contract.json \
  /secure/evidence/native-artifact-manifest.json
```

次のすべてを二人で確認する。

- manifestの`source_commit`がreview済みcommitと一致する。
- 36 artifactが過不足なく存在する。
- 各artifactのdigest、checksum、署名、SBOM、provenanceが同じrecordへ束縛される。
- `signature_verified=true`かつ`install_verified=true`である。
- TrivyのCritical／Highが0で、secret／misconfiguration gateがPASSしている。
- Stagingへ渡すartifactがこのbuild結果と同じdigestであり、再buildされていない。

一つでも不足する場合はR3を`pending`のままとし、Dockerfile、旧image workflow、旧registry変数を削除しない。全条件合格後に、ADR-0024、撤去台帳、KCOMP2-M1、開発工程を同じコミットで更新してから旧R3経路を削除する。

## 5. ローカル検証の範囲

次は構造とpayload hygieneの回帰であり、実install受入の代替ではない。

```sh
node --test ops/test/native-artifacts.test.mjs ops/test/check-ci-policy.test.mjs
node ops/check-ci-policy.mjs
node ops/docker-retirement/check-docker-retirement.mjs
```

Mac上のpackage生成・署名smokeで`install_verified=false`のrecordが生成されるのは意図どおりである。そのrecordを手編集して合格にしてはならない。
