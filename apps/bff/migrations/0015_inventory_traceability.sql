\set ON_ERROR_STOP on

BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
SET LOCAL ROLE app_owner;

CREATE TABLE app.purchase_order (
  tenant_id uuid NOT NULL,
  purchase_order_id uuid NOT NULL,
  order_number text NOT NULL CHECK (length(order_number) BETWEEN 1 AND 80),
  supplier_name text NOT NULL CHECK (length(supplier_name) BETWEEN 1 AND 200),
  ordered_on date NOT NULL,
  expected_on date,
  status text NOT NULL DEFAULT 'ordered' CHECK (status IN ('draft','ordered','partially_received','received','cancelled')),
  currency char(3) NOT NULL DEFAULT 'JPY' CHECK (currency ~ '^[A-Z]{3}$'),
  note text NOT NULL DEFAULT '' CHECK (length(note) <= 2000),
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  created_by uuid NOT NULL,
  updated_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, purchase_order_id),
  UNIQUE (tenant_id, order_number),
  CHECK (expected_on IS NULL OR expected_on >= ordered_on)
);

CREATE TABLE app.purchase_order_line (
  tenant_id uuid NOT NULL,
  purchase_order_line_id uuid NOT NULL,
  purchase_order_id uuid NOT NULL,
  chemical_id uuid NOT NULL,
  ordered_quantity numeric(14,3) NOT NULL CHECK (ordered_quantity > 0),
  received_quantity numeric(14,3) NOT NULL DEFAULT 0 CHECK (received_quantity >= 0 AND received_quantity <= ordered_quantity),
  unit text NOT NULL CHECK (length(unit) BETWEEN 1 AND 32),
  unit_cost numeric(14,4) NOT NULL CHECK (unit_cost >= 0),
  expected_on date,
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  PRIMARY KEY (tenant_id, purchase_order_line_id),
  FOREIGN KEY (tenant_id, purchase_order_id) REFERENCES app.purchase_order (tenant_id, purchase_order_id),
  FOREIGN KEY (tenant_id, chemical_id) REFERENCES app.agrochemical (tenant_id, chemical_id)
);

CREATE TABLE app.stock_lot (
  tenant_id uuid NOT NULL,
  lot_id uuid NOT NULL,
  chemical_id uuid NOT NULL,
  purchase_order_line_id uuid,
  lot_number text NOT NULL CHECK (length(lot_number) BETWEEN 1 AND 120),
  supplier_name text NOT NULL CHECK (length(supplier_name) BETWEEN 1 AND 200),
  received_on date NOT NULL,
  expires_on date,
  initial_quantity numeric(14,3) NOT NULL CHECK (initial_quantity > 0),
  unit text NOT NULL CHECK (length(unit) BETWEEN 1 AND 32),
  unit_cost numeric(14,4) NOT NULL CHECK (unit_cost >= 0),
  currency char(3) NOT NULL DEFAULT 'JPY' CHECK (currency ~ '^[A-Z]{3}$'),
  status text NOT NULL DEFAULT 'available' CHECK (status IN ('available','quarantined','consumed','expired','recalled')),
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  created_by uuid NOT NULL,
  updated_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, lot_id),
  FOREIGN KEY (tenant_id, chemical_id) REFERENCES app.agrochemical (tenant_id, chemical_id),
  FOREIGN KEY (tenant_id, purchase_order_line_id) REFERENCES app.purchase_order_line (tenant_id, purchase_order_line_id),
  UNIQUE (tenant_id, chemical_id, lot_number),
  CHECK (expires_on IS NULL OR expires_on >= received_on)
);

ALTER TABLE app.stock_event
  ADD COLUMN lot_id uuid,
  ADD COLUMN unit_cost numeric(14,4),
  ADD COLUMN currency char(3),
  ADD COLUMN jgap_attributes jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE app.stock_event
  ADD CONSTRAINT stock_event_lot_fk FOREIGN KEY (tenant_id, lot_id)
    REFERENCES app.stock_lot (tenant_id, lot_id) NOT VALID,
  ADD CONSTRAINT stock_event_unit_cost_check CHECK (unit_cost IS NULL OR unit_cost >= 0) NOT VALID,
  ADD CONSTRAINT stock_event_currency_check CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$') NOT VALID,
  ADD CONSTRAINT stock_event_jgap_attributes_check CHECK (jsonb_typeof(jgap_attributes) = 'object') NOT VALID;
ALTER TABLE app.stock_event VALIDATE CONSTRAINT stock_event_lot_fk;
ALTER TABLE app.stock_event VALIDATE CONSTRAINT stock_event_unit_cost_check;
ALTER TABLE app.stock_event VALIDATE CONSTRAINT stock_event_currency_check;
ALTER TABLE app.stock_event VALIDATE CONSTRAINT stock_event_jgap_attributes_check;

CREATE TABLE app.inventory_count_session (
  tenant_id uuid NOT NULL,
  count_session_id uuid NOT NULL,
  location_name text NOT NULL CHECK (length(location_name) BETWEEN 1 AND 160),
  counted_at timestamptz NOT NULL,
  status text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','reviewed','posted','cancelled')),
  note text NOT NULL DEFAULT '' CHECK (length(note) <= 2000),
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  created_by uuid NOT NULL,
  reviewed_by uuid,
  posted_by uuid,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  posted_at timestamptz,
  PRIMARY KEY (tenant_id, count_session_id),
  CHECK (reviewed_by IS NULL OR reviewed_by <> created_by)
);

CREATE TABLE app.inventory_count_line (
  tenant_id uuid NOT NULL,
  count_session_id uuid NOT NULL,
  count_line_id uuid NOT NULL,
  chemical_id uuid NOT NULL,
  lot_id uuid,
  system_quantity numeric(14,3) NOT NULL,
  counted_quantity numeric(14,3) NOT NULL CHECK (counted_quantity >= 0),
  variance numeric(14,3) GENERATED ALWAYS AS (counted_quantity - system_quantity) STORED,
  unit text NOT NULL CHECK (length(unit) BETWEEN 1 AND 32),
  reason text NOT NULL DEFAULT '' CHECK (length(reason) <= 1000),
  jgap_attributes jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(jgap_attributes) = 'object'),
  PRIMARY KEY (tenant_id, count_line_id),
  FOREIGN KEY (tenant_id, count_session_id) REFERENCES app.inventory_count_session (tenant_id, count_session_id),
  FOREIGN KEY (tenant_id, chemical_id) REFERENCES app.agrochemical (tenant_id, chemical_id),
  FOREIGN KEY (tenant_id, lot_id) REFERENCES app.stock_lot (tenant_id, lot_id)
);

CREATE VIEW app.incoming_stock WITH (security_invoker = true) AS
SELECT purchase.tenant_id, line.purchase_order_line_id, purchase.purchase_order_id,
       purchase.order_number, purchase.supplier_name, line.chemical_id,
       line.ordered_quantity - line.received_quantity AS incoming_quantity,
       line.unit, line.unit_cost, purchase.currency,
       coalesce(line.expected_on, purchase.expected_on) AS expected_on
FROM app.purchase_order purchase
JOIN app.purchase_order_line line USING (tenant_id, purchase_order_id)
WHERE purchase.status IN ('ordered','partially_received')
  AND line.received_quantity < line.ordered_quantity;

CREATE VIEW app.inventory_lot_balance WITH (security_invoker = true) AS
SELECT lot.tenant_id, lot.lot_id, lot.chemical_id, lot.lot_number, lot.supplier_name,
       lot.received_on, lot.expires_on, lot.unit, lot.unit_cost, lot.currency, lot.status,
       coalesce(sum(event.quantity_delta), 0) AS quantity,
       max(event.event_ts) AS updated_at
FROM app.stock_lot lot
LEFT JOIN app.stock_event event ON event.tenant_id = lot.tenant_id AND event.lot_id = lot.lot_id
GROUP BY lot.tenant_id, lot.lot_id, lot.chemical_id, lot.lot_number, lot.supplier_name,
         lot.received_on, lot.expires_on, lot.unit, lot.unit_cost, lot.currency, lot.status;

CREATE VIEW app.inventory_valuation WITH (security_invoker = true) AS
SELECT tenant_id, chemical_id, currency,
       sum(quantity) AS quantity,
       sum(quantity * unit_cost) AS inventory_value,
       CASE WHEN sum(quantity) = 0 THEN 0 ELSE sum(quantity * unit_cost) / sum(quantity) END AS weighted_average_cost
FROM app.inventory_lot_balance
GROUP BY tenant_id, chemical_id, currency;

DO $$
DECLARE table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY['purchase_order','purchase_order_line','stock_lot','inventory_count_session','inventory_count_line'] LOOP
    EXECUTE format('ALTER TABLE app.%I OWNER TO app_owner', table_name);
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', table_name);
    EXECUTE format('CREATE POLICY tenant_isolation ON app.%I AS PERMISSIVE FOR ALL TO app_user USING (tenant_id=app.current_tenant_id()) WITH CHECK (tenant_id=app.current_tenant_id())', table_name);
    EXECUTE format('CREATE POLICY inventory_reader ON app.%I AS RESTRICTIVE FOR SELECT TO app_user USING (app.has_capability(''inventory:write'') OR app.has_capability(''inventory:adjust''))', table_name);
    EXECUTE format('CREATE POLICY inventory_manager_insert ON app.%I AS RESTRICTIVE FOR INSERT TO app_user WITH CHECK (app.has_capability(''inventory:adjust''))', table_name);
    EXECUTE format('CREATE POLICY inventory_manager_update ON app.%I AS RESTRICTIVE FOR UPDATE TO app_user USING (app.has_capability(''inventory:adjust'')) WITH CHECK (app.has_capability(''inventory:adjust''))', table_name);
  END LOOP;
END $$;
ALTER VIEW app.incoming_stock OWNER TO app_owner;
ALTER VIEW app.inventory_lot_balance OWNER TO app_owner;
ALTER VIEW app.inventory_valuation OWNER TO app_owner;

CREATE OR REPLACE FUNCTION app.audit_phase2_change()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, app AS $$
DECLARE row_before jsonb; row_after jsonb; row_value jsonb; tenant_value uuid; key_value text;
BEGIN
  row_before := CASE WHEN TG_OP IN ('UPDATE','DELETE') THEN to_jsonb(OLD) END;
  row_after := CASE WHEN TG_OP IN ('INSERT','UPDATE') THEN to_jsonb(NEW) END;
  row_value := coalesce(row_after,row_before); tenant_value := (row_value->>'tenant_id')::uuid;
  key_value := coalesce(row_value->>'season_id',row_value->>'crop_plan_id',row_value->>'allocation_id',
    row_value->>'resource_id',row_value->>'policy_id',row_value->>'analytics_event_id',row_value->>'consent_event_id',
    row_value->>'template_id',row_value->>'progress_event_id',row_value->>'purchase_order_id',
    row_value->>'purchase_order_line_id',row_value->>'lot_id',row_value->>'count_session_id',row_value->>'count_line_id',
    nullif(concat_ws(':',row_value->>'predecessor_instruction_id',row_value->>'successor_instruction_id'),''),
    nullif(row_value->>'step_key',''),'unknown');
  INSERT INTO app.phase2_change_audit(tenant_id,table_name,operation,record_key,actor_user_id,actor_pseudonym,before_row,after_row)
  VALUES(tenant_value,TG_TABLE_NAME,TG_OP,key_value,app.current_user_id(),
    coalesce(nullif(current_setting('app.actor_pseudonym',true),''),'system'),row_before,row_after);
  RETURN coalesce(NEW,OLD);
END $$;

DO $$
DECLARE table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY['purchase_order','purchase_order_line','stock_lot','inventory_count_session','inventory_count_line'] LOOP
    EXECUTE format('CREATE TRIGGER z_phase2_change_audit AFTER INSERT OR UPDATE OR DELETE ON app.%I FOR EACH ROW EXECUTE FUNCTION app.audit_phase2_change()', table_name);
  END LOOP;
END $$;

CREATE INDEX purchase_order_expected_idx ON app.purchase_order (tenant_id,expected_on,purchase_order_id) WHERE status IN ('ordered','partially_received');
CREATE INDEX purchase_order_line_chemical_idx ON app.purchase_order_line (tenant_id,chemical_id,purchase_order_id);
CREATE INDEX stock_lot_expiry_idx ON app.stock_lot (tenant_id,expires_on,chemical_id) WHERE status='available';
CREATE INDEX stock_event_lot_time_idx ON app.stock_event (tenant_id,lot_id,event_ts) WHERE lot_id IS NOT NULL;
CREATE INDEX inventory_count_status_idx ON app.inventory_count_session (tenant_id,status,counted_at DESC);
CREATE INDEX inventory_count_line_session_idx ON app.inventory_count_line (tenant_id,count_session_id,chemical_id,lot_id);
CREATE UNIQUE INDEX inventory_count_line_lot_unique
  ON app.inventory_count_line (tenant_id,count_session_id,chemical_id,lot_id) WHERE lot_id IS NOT NULL;
CREATE UNIQUE INDEX inventory_count_line_unlotted_unique
  ON app.inventory_count_line (tenant_id,count_session_id,chemical_id) WHERE lot_id IS NULL;

GRANT SELECT,INSERT,UPDATE ON app.purchase_order,app.purchase_order_line,app.stock_lot,
  app.inventory_count_session,app.inventory_count_line TO app_user;
GRANT SELECT ON app.incoming_stock,app.inventory_lot_balance,app.inventory_valuation TO app_user;

COMMIT;
