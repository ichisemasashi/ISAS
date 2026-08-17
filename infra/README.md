# Infrastructure adapter registry

ISASのProduction必須hostはmacOS、Linux、FreeBSDであり、providerは固定しない。インフラ成果物は[Productionホスト共通契約](../docs/operations/Productionホスト共通契約.md)を実装するhost／provider別adapterである。AWS用artifactの存在を、AWSの必須化または他hostの非対応根拠にしてはならない。

| Adapter | 用途 | 状態 |
|---|---|---|
| [`opentofu/`](opentofu/) | 任意のAWS東京region adapter | 実装済み、実AWS Staging受入は未実施 |
| `linux-production` | Linux Production宣言構成・install／upgrade／restore | 未実装（KCOMP-H4） |
| `macos-production` | macOS Production宣言構成・起動管理／backup／restore | 未実装（KCOMP-H3） |
| `freebsd-production` | FreeBSD Jail manifest、pkg／rc.d／VNET／pf／ZFS／rctl | 未実装（KCOMP-H2） |
| [`local/`](local/) | Mac `local-integration`検証profile | 非本番。macOS Production adapterではない |

新しいadapterは、共通manifest schema、ADR-0019〜0021のgate、host別runbook、空hostからの構築と全損restore試験を同時に追加する。未実装欄をAWS adapterへのfallbackで完了扱いにしない。
