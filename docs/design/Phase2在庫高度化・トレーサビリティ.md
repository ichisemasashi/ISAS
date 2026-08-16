# Phase 2 在庫高度化・トレーサビリティ

| 項目 | 実装 |
|---|---|
| migration | `0015_inventory_traceability.sql` |
| 発注点 | `inventory_policy`の補充点・目標量・安全在庫と現在残高を同じ在庫APIで判定 |
| 入荷予定 | `purchase_order`／`purchase_order_line`と`incoming_stock` security-invoker view |
| lot・期限 | `stock_lot`にlot番号、仕入先、受入日、期限、単位、単価、通貨、状態を保持 |
| 残高・評価 | 数量は`stock_event`から`inventory_lot_balance`へ導出し、評価額・移動平均単価は`inventory_valuation`で導出 |
| 棚卸し | `inventory_count_session`／`inventory_count_line`。system数量をserver取得し、差異はgenerated列 |
| 確定 | 別の管理者が棚卸しを確定し、差異ごとに追記型`adjustment` eventを生成 |
| JGAP CSV | 入出庫日時、農薬名、登録番号、lot、期限、仕入先、数量、単位、単価、理由、記録者、追加属性を出力 |

## API

- `GET /api/v1/inventory`：残高、発注点判定、入荷予定、lot別残・期限・評価額、棚卸し履歴、負在庫alertを返す。
- `POST /api/v1/inventory/purchase-orders`：発注headerと複数明細を1 transactionで登録する。
- `POST /api/v1/inventory/receipts`：入荷lotとreceipt eventを同時登録し、発注明細の受入量と発注状態を更新する。`eventUuid`再送は同じ結果を返す。
- `POST /api/v1/inventory/counts`：指定した農薬／lotのsystem残をserverで取得し、実棚数と差異を保存する。
- `POST /api/v1/inventory/counts/:id/post`：作成者とは別の`inventory:adjust`管理者が差異eventを確定する。各lineのevent UUIDを要求する。
- `GET /api/v1/exports/jgap-inventory.csv`：日付範囲とRLSを適用したUTF-8 CSVを出力する。

発注・入荷・棚卸し確定はrecent MFA、CSRF、`inventory:adjust`を要求する。入荷と棚卸し差異は残高を直接更新せず、既存の負在庫検知を含む`stock_event`経路だけを通る。lotを伴わない旧eventは引き続き有効で、0015適用前のオフライン端末ともN/N-1互換を保つ。

## 監査、RLS、rollback

追加5表はowner=`app_owner`、FORCE RLS、tenant policy、在庫capability policyを持つ。mutable表は`phase2_change_audit`へ前後像を記録し、数量確定は不変の`stock_event`自体が履歴となる。

`0015_inventory_traceability_verify.sql`はowner／FORCE RLS、監査、発注残、lot残、評価、棚卸し差異を検査する。`0015_inventory_traceability_rollback.sql`は発注、lot、棚卸し、lot付きeventが1件でもあれば停止し、空の場合だけ追加view・表・列を除去する。
