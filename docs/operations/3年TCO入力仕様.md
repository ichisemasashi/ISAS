# 3年TCO入力仕様（KCOMP2-M6）

100／1,000／3,000圃場ごと、macOS／Linux／FreeBSD hostごとに36か月のTCOを算出する。価格を推測せず、導入組織が見積日・通貨・税区分・根拠URIを付けた実値を入力する。

入力はschema v2を使い、9組合せを重複なく全件記録する。必須費目はprimary host、予備機、storage、電力、回線、off-site backup、IdP、監視、保守者、security update、restore／DR、incident、計画停止・非計画停止costである。各費目は36か月分の金額、見積書等の証跡URI、そのSHA-256、見積有効期限を持つ。要員はservice ownerと保守FTE、更新頻度はOS、application、restore／DR、incidentの36か月回数を記録する。

実見積の収集者と承認者を分離し、`service_owner`と`financial_verifier`の異なる二者が証拠URI付きで承認する。0円の費目も省略せず、無償根拠の証跡を登録する。別の圃場数やOSからの比例推計を実見積として登録しない。

```bash
node ops/product/calculate-tco.mjs ops/product/tco-input.example.json
```

exampleは仮値を含むため計算を拒否する。現在repositoryには9組合せの実見積と二者承認がないため、価格比較や「KSASより安価」という表示には使用できない。
