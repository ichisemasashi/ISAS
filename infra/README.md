# Infrastructure adapter registry

ISASのProduction必須hostはmacOS、Linux、FreeBSDであり、providerは固定しない。インフラ成果物は[Productionホスト共通契約](../docs/operations/Productionホスト共通契約.md)を実装するhost／provider別adapterである。AWS用artifactの存在を、AWSの必須化または他hostの非対応根拠にしてはならない。

| Adapter | 用途 | 状態 |
|---|---|---|
| [`opentofu/`](opentofu/) | 任意のAWS東京region adapter | 実装済み、実AWS Staging受入は未実施 |
| [`hosts/linux/profile.json`](hosts/linux/profile.json) | Linux Production宣言構成・install／upgrade／restore | 定義・runbook実装済み、実host受入は`BLOCKED`（KCOMP-H4） |
| [`hosts/macos/`](hosts/macos/) | macOS native Production構成、launchd／pf、署名済みpkg、監視／backup／restore／rolling update | 実装・静的検査済み。実host 2台の受入は`BLOCKED` |
| [`hosts/freebsd/profile.json`](hosts/freebsd/profile.json) | FreeBSD Jail manifest、pkg／rc.d／VNET／pf／ZFS／rctl | native Jail実装・静的検査済み、実host受入は`BLOCKED` |
| [`local/`](local/) | Mac `local-integration`検証profile | 非本番。macOS Production adapterではない |

新しいadapterは、共通manifest schema、ADR-0019〜0021のgate、host別runbook、空hostからの構築と全損restore試験を同時に追加する。定義やrunbookだけを実機受入済みとして扱わず、AWS adapterへのfallbackで完了扱いにしない。
