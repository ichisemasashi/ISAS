\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
DO $$ BEGIN
  IF EXISTS(SELECT 1 FROM app.purchase_order) OR EXISTS(SELECT 1 FROM app.stock_lot)
     OR EXISTS(SELECT 1 FROM app.inventory_count_session) OR EXISTS(SELECT 1 FROM app.stock_event WHERE lot_id IS NOT NULL) THEN
    RAISE EXCEPTION 'refusing inventory traceability rollback: purchase, lot, count, or lot event data exists';
  END IF;
END $$;
SET LOCAL ROLE app_owner;
DROP VIEW app.inventory_valuation;
DROP VIEW app.inventory_lot_balance;
DROP VIEW app.incoming_stock;
DROP TABLE app.inventory_count_line;
DROP TABLE app.inventory_count_session;
ALTER TABLE app.stock_event DROP CONSTRAINT stock_event_jgap_attributes_check;
ALTER TABLE app.stock_event DROP CONSTRAINT stock_event_currency_check;
ALTER TABLE app.stock_event DROP CONSTRAINT stock_event_unit_cost_check;
ALTER TABLE app.stock_event DROP CONSTRAINT stock_event_lot_fk;
ALTER TABLE app.stock_event DROP COLUMN jgap_attributes;
ALTER TABLE app.stock_event DROP COLUMN currency;
ALTER TABLE app.stock_event DROP COLUMN unit_cost;
ALTER TABLE app.stock_event DROP COLUMN lot_id;
DROP TABLE app.stock_lot;
DROP TABLE app.purchase_order_line;
DROP TABLE app.purchase_order;
COMMIT;
