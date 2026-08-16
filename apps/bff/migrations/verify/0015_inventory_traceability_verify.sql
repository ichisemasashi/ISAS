\set ON_ERROR_STOP on
\set A    '11111111-1111-7111-8111-111111111111'
\set M    'aaaaaaaa-0000-7000-8000-000000000010'
\set F1   'f1111111-1111-7111-8111-111111111111'
\set CHEM '0198a6c0-0000-7000-8000-000000000502'

CREATE OR REPLACE FUNCTION pg_temp.ck(condition boolean,label text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN IF NOT condition THEN RAISE EXCEPTION 'FAIL  %',label; END IF; RAISE NOTICE 'PASS  %',label; END $$;

BEGIN;
SELECT pg_temp.ck(count(*)=5,'(1) 発注・lot・棚卸し表はowner=app_ownerかつFORCE RLS')
FROM pg_class class JOIN pg_namespace namespace ON namespace.oid=class.relnamespace
WHERE namespace.nspname='app' AND class.relname IN ('purchase_order','purchase_order_line','stock_lot','inventory_count_session','inventory_count_line')
  AND class.relowner='app_owner'::regrole AND class.relrowsecurity AND class.relforcerowsecurity;
SELECT pg_temp.ck(count(*)=5,'(2) mutable在庫表に監査trigger')
FROM pg_trigger trigger JOIN pg_class class ON class.oid=trigger.tgrelid JOIN pg_namespace namespace ON namespace.oid=class.relnamespace
WHERE namespace.nspname='app' AND class.relname IN ('purchase_order','purchase_order_line','stock_lot','inventory_count_session','inventory_count_line')
  AND trigger.tgname='z_phase2_change_audit' AND NOT trigger.tgisinternal;

SET LOCAL ROLE app_user;
SELECT set_config('app.user_id',:'M',true);
SELECT set_config('app.tenant_id',:'A',true);
SELECT set_config('app.allowed_tenants',('{'||:'A'||'}'),true);
SELECT set_config('app.scope_field_groups',('{'||:'F1'||'}'),true);
SELECT set_config('app.caps','{scope_all,inventory:write,inventory:adjust,inventory:policy:manage,security:manage}',true);
SELECT set_config('app.actor_pseudonym','inventory-manager',true);

INSERT INTO app.purchase_order
  (tenant_id,purchase_order_id,order_number,supplier_name,ordered_on,expected_on,created_by,updated_by)
VALUES(:'A','0598a6c0-0000-7000-8000-000000000101','PO-2027-001','農業資材店','2027-01-01','2027-01-10',:'M',:'M');
INSERT INTO app.purchase_order_line
  (tenant_id,purchase_order_line_id,purchase_order_id,chemical_id,ordered_quantity,received_quantity,unit,unit_cost,expected_on)
VALUES(:'A','0598a6c0-0000-7000-8000-000000000102','0598a6c0-0000-7000-8000-000000000101',:'CHEM',10,6,'L',1200,'2027-01-10');
INSERT INTO app.stock_lot
  (tenant_id,lot_id,chemical_id,purchase_order_line_id,lot_number,supplier_name,received_on,expires_on,initial_quantity,unit,unit_cost,created_by,updated_by)
VALUES(:'A','0598a6c0-0000-7000-8000-000000000103',:'CHEM','0598a6c0-0000-7000-8000-000000000102','LOT-A','農業資材店','2027-01-09','2028-01-09',6,'L',1200,:'M',:'M');
INSERT INTO app.stock_event
  (tenant_id,stock_event_id,event_uuid,chemical_id,lot_id,event_type,quantity_delta,reason,unit_cost,currency,jgap_attributes,occurred_at,event_ts,actor_user_id)
VALUES(:'A','0598a6c0-0000-7000-8000-000000000104','0598a6c0-0000-7000-8000-000000000105',:'CHEM',
  '0598a6c0-0000-7000-8000-000000000103','receipt',6,'発注入荷',1200,'JPY','{"storage":"locked"}','2027-01-09T00:00Z',statement_timestamp(),:'M');
UPDATE app.purchase_order SET status='partially_received' WHERE purchase_order_id='0598a6c0-0000-7000-8000-000000000101';
SELECT pg_temp.ck(incoming_quantity=4,'(3) 発注残4を入荷予定として導出') FROM app.incoming_stock;
SELECT pg_temp.ck(lot.quantity=6 AND value.inventory_value=7200,'(4) lot残と評価額をeventから導出')
FROM app.inventory_lot_balance lot JOIN app.inventory_valuation value USING(tenant_id,chemical_id,currency)
WHERE lot.lot_id='0598a6c0-0000-7000-8000-000000000103';

INSERT INTO app.inventory_count_session
  (tenant_id,count_session_id,location_name,counted_at,created_by)
VALUES(:'A','0598a6c0-0000-7000-8000-000000000106','農薬庫','2027-01-31T00:00Z',:'M');
INSERT INTO app.inventory_count_line
  (tenant_id,count_session_id,count_line_id,chemical_id,lot_id,system_quantity,counted_quantity,unit,reason,jgap_attributes)
VALUES(:'A','0598a6c0-0000-7000-8000-000000000106','0598a6c0-0000-7000-8000-000000000107',:'CHEM',
  '0598a6c0-0000-7000-8000-000000000103',6,5,'L','月次棚卸し','{"witness":"reviewer"}');
SELECT pg_temp.ck(variance=-1,'(5) 棚卸し差異をgenerated列で固定') FROM app.inventory_count_line;
SELECT pg_temp.ck(count(*)>=5,'(6) 発注・lot・棚卸し変更を監査') FROM app.phase2_change_audit
WHERE table_name IN ('purchase_order','purchase_order_line','stock_lot','inventory_count_session','inventory_count_line');

RESET ROLE;
ROLLBACK;
\echo 'Inventory traceability migration: 6 groups PASS'
