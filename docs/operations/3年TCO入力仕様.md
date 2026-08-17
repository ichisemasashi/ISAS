# 3年TCO入力仕様（KCOMP-M7）

100／1,000／3,000圃場ごと、macOS／Linux／FreeBSD hostごとに36か月のTCOを算出する。価格を推測せず、導入組織が見積日・通貨・税区分・根拠URIを付けた実値を入力する。

必須費目はhost／予備機、storage、電力、回線、off-site backup、IdP、監視、証明書、保守契約、通常運用者工数、security update工数、月次restore、四半期DR、incident期待工数、計画停止・非計画停止cost、3年間の更新回数である。人件費は時間単価と月間時間を分ける。共通費の按分規則も明記する。

```bash
node ops/product/calculate-tco.mjs ops/product/tco-input.example.json
```

exampleは仮値を含むため計算を拒否する。実見積がない現在は`未処置`である。
