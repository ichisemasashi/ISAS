# 初回農機connector受入契約

農機OpenAPI（WAGRI）connectorは`planned`であり、現在利用可能ではない。次の全項目を実値で満たし、提供者と導入者が承認するまで状態を`implemented`または`validated`へ変更しない。

- 契約主体、API提供者、対象tenant、利用規約版、再配布可否、保持期間、終了時exportを記録する。
- 認証方式、sandbox／production endpoint、schema version、rate limit、revocation、障害・security窓口を確定する。
- 匿名化した実sampleを固定し、source hash、期待する共通観測、単位・時刻・座標変換、重複／再送結果を保存する。
- 契約対象の実機から取得した位置、稼働、収量または散布のうち契約範囲を受入し、機種・firmware・adapter digestを記録する。
- 欠測、遅延、契約終了、API停止時に、既存記録とfile取込が継続することを確認する。
- 実行者と別の承認者が証跡URI、日時、結果へ署名する。

契約書、sample、実機はリポジトリへ捏造して置かない。実証開始時に機密管理領域へ保存し、release manifestにはhashと証跡URIだけを登録する。

実装incrementは`apps/bff/src/external-read-api.mjs`と`apps/bff/src/machinery-connector.mjs`で分離する。前者はGET限定でservice identity、tenant、`external:fields:read` scope、期限付き同意を検査し、service actorとして閲覧を監査する。後者はconnector別HTTPS origin、client credential scope、cursorをpage commitと同じtransactionで保存し、provider event IDの冪等化、明示した単位変換、429／5xx再試行、401／403失効、provider停止時のfile取込継続を実装する。

このgeneric incrementの自動testは実provider sandbox、契約済みsample、実機、実support窓口を代替しない。契約成立後、provider固有schema mappingとtoken adapterを追加し、`ops/product/check-machinery-connector-acceptance.mjs`へ実証跡を入力するまでconnectorは`designed`表示を維持する。
