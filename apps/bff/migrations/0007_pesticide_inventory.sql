\set ON_ERROR_STOP on
BEGIN;

SET ROLE auth_context_owner;
ALTER TABLE priv.auth_role_capability DROP CONSTRAINT auth_role_capability_capability_check;
ALTER TABLE priv.auth_role_capability ADD CONSTRAINT auth_role_capability_capability_check
  CHECK (capability IN (
    'view_others_tracks', 'view_others_punch', 'scope_all',
    'journal:write', 'pesticide:write', 'punch:write', 'conflict:resolve',
    'instruction:manage', 'journal:review',
    'pesticide:manage', 'pesticide:override', 'inventory:write', 'inventory:adjust'
  ));
INSERT INTO priv.auth_role_capability (role_key, capability)
SELECT role_key, capability
FROM (VALUES
  ('worker', 'inventory:write'),
  ('group_admin', 'pesticide:manage'), ('group_admin', 'pesticide:override'),
  ('group_admin', 'inventory:write'), ('group_admin', 'inventory:adjust')
) AS seed(role_key, capability)
WHERE EXISTS (SELECT 1 FROM priv.auth_role role WHERE role.role_key = seed.role_key)
ON CONFLICT DO NOTHING;
RESET ROLE;

SET ROLE app_owner;

ALTER TABLE app.domain_event DROP CONSTRAINT domain_event_event_kind_check;
ALTER TABLE app.domain_event ADD CONSTRAINT domain_event_event_kind_check
  CHECK (event_kind IN ('journal', 'pesticide', 'punch', 'stock'));
ALTER TABLE app.field ADD COLUMN timezone text NOT NULL DEFAULT 'Asia/Tokyo'
  CHECK (length(timezone) BETWEEN 1 AND 100);

CREATE TABLE app.pesticide_master_release (
  tenant_id uuid NOT NULL,
  release_id uuid NOT NULL,
  version text NOT NULL CHECK (length(version) BETWEEN 1 AND 100),
  valid_until timestamptz NOT NULL,
  published_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  published_by uuid NOT NULL,
  PRIMARY KEY (tenant_id, release_id),
  UNIQUE (tenant_id, version)
);

CREATE TABLE app.agrochemical (
  tenant_id uuid NOT NULL,
  chemical_id uuid NOT NULL,
  release_id uuid NOT NULL,
  registration_number text NOT NULL CHECK (length(registration_number) BETWEEN 1 AND 100),
  name text NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
  active_ingredient text NOT NULL DEFAULT '',
  applicable_crops text[] NOT NULL CHECK (cardinality(applicable_crops) > 0),
  dilution_min numeric NOT NULL CHECK (dilution_min > 0),
  dilution_max numeric NOT NULL CHECK (dilution_max >= dilution_min),
  max_uses integer NOT NULL CHECK (max_uses > 0),
  preharvest_days integer NOT NULL CHECK (preharvest_days >= 0),
  revoked_on date,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, chemical_id),
  FOREIGN KEY (tenant_id, release_id) REFERENCES app.pesticide_master_release (tenant_id, release_id),
  UNIQUE (tenant_id, release_id, registration_number)
);

CREATE TABLE app.pesticide_usage (
  tenant_id uuid NOT NULL,
  usage_id uuid NOT NULL,
  event_uuid uuid NOT NULL,
  field_id uuid NOT NULL,
  field_group_id uuid NOT NULL,
  crop_name text NOT NULL,
  chemical_id uuid NOT NULL,
  applied_on date NOT NULL,
  dilution numeric NOT NULL CHECK (dilution > 0),
  amount numeric NOT NULL CHECK (amount > 0),
  target_pest text NOT NULL CHECK (length(target_pest) BETWEEN 1 AND 200),
  worker_name text NOT NULL CHECK (length(worker_name) BETWEEN 1 AND 200),
  equipment text NOT NULL CHECK (length(equipment) BETWEEN 1 AND 200),
  planned_harvest_on date,
  client_safety jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(client_safety) = 'object'),
  server_safety jsonb NOT NULL CHECK (jsonb_typeof(server_safety) = 'object'),
  occurred_at timestamptz NOT NULL,
  event_ts timestamptz NOT NULL,
  actor_user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, usage_id),
  UNIQUE (tenant_id, event_uuid),
  FOREIGN KEY (tenant_id, field_id) REFERENCES app.field (tenant_id, field_id),
  FOREIGN KEY (tenant_id, chemical_id) REFERENCES app.agrochemical (tenant_id, chemical_id)
);

CREATE TABLE app.pesticide_safety_alert (
  tenant_id uuid NOT NULL,
  alert_id uuid NOT NULL,
  usage_id uuid NOT NULL,
  field_group_id uuid NOT NULL,
  reasons text[] NOT NULL CHECK (cardinality(reasons) > 0),
  client_safety jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(client_safety) = 'object'),
  server_safety jsonb NOT NULL CHECK (jsonb_typeof(server_safety) = 'object'),
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'acknowledged')),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  acknowledged_by uuid,
  acknowledged_at timestamptz,
  PRIMARY KEY (tenant_id, alert_id),
  FOREIGN KEY (tenant_id, usage_id) REFERENCES app.pesticide_usage (tenant_id, usage_id)
);

CREATE TABLE app.stock_event (
  tenant_id uuid NOT NULL,
  stock_event_id uuid NOT NULL,
  event_uuid uuid NOT NULL,
  chemical_id uuid NOT NULL,
  event_type text NOT NULL CHECK (event_type IN ('receipt', 'withdrawal', 'adjustment')),
  quantity_delta numeric NOT NULL CHECK (quantity_delta <> 0),
  reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
  occurred_at timestamptz NOT NULL,
  event_ts timestamptz NOT NULL,
  actor_user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, stock_event_id),
  UNIQUE (tenant_id, event_uuid),
  FOREIGN KEY (tenant_id, chemical_id) REFERENCES app.agrochemical (tenant_id, chemical_id),
  CHECK ((event_type = 'receipt' AND quantity_delta > 0)
      OR (event_type = 'withdrawal' AND quantity_delta < 0)
      OR event_type = 'adjustment')
);

CREATE VIEW app.stock_balance WITH (security_invoker = true) AS
SELECT tenant_id, chemical_id, sum(quantity_delta) AS quantity,
       max(event_ts) AS updated_at
FROM app.stock_event
GROUP BY tenant_id, chemical_id;

CREATE TABLE app.stock_alert (
  tenant_id uuid NOT NULL,
  alert_id uuid NOT NULL DEFAULT gen_random_uuid(),
  chemical_id uuid NOT NULL,
  triggering_event_id uuid NOT NULL,
  negative_quantity numeric NOT NULL CHECK (negative_quantity < 0),
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'resolved')),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  resolved_by uuid,
  resolved_at timestamptz,
  resolution_event_id uuid,
  PRIMARY KEY (tenant_id, alert_id),
  FOREIGN KEY (tenant_id, triggering_event_id) REFERENCES app.stock_event (tenant_id, stock_event_id),
  FOREIGN KEY (tenant_id, resolution_event_id) REFERENCES app.stock_event (tenant_id, stock_event_id)
);
CREATE UNIQUE INDEX stock_alert_one_pending_idx
  ON app.stock_alert (tenant_id, chemical_id) WHERE status = 'pending';

CREATE OR REPLACE FUNCTION app.detect_negative_stock()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, app AS $$
DECLARE current_quantity numeric;
BEGIN
  SELECT coalesce(sum(quantity_delta), 0) INTO current_quantity
  FROM app.stock_event
  WHERE tenant_id = NEW.tenant_id AND chemical_id = NEW.chemical_id;
  IF current_quantity < 0 THEN
    INSERT INTO app.stock_alert
      (tenant_id, chemical_id, triggering_event_id, negative_quantity)
    VALUES (NEW.tenant_id, NEW.chemical_id, NEW.stock_event_id, current_quantity)
    ON CONFLICT (tenant_id, chemical_id) WHERE status = 'pending'
    DO UPDATE SET triggering_event_id = EXCLUDED.triggering_event_id,
                  negative_quantity = EXCLUDED.negative_quantity;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER stock_event_negative_alert
AFTER INSERT ON app.stock_event FOR EACH ROW EXECUTE FUNCTION app.detect_negative_stock();

CREATE INDEX pesticide_release_current_idx ON app.pesticide_master_release (tenant_id, published_at DESC);
CREATE INDEX agrochemical_release_idx ON app.agrochemical (tenant_id, release_id, name);
CREATE INDEX pesticide_usage_check_idx ON app.pesticide_usage (tenant_id, field_id, crop_name, chemical_id, applied_on);
CREATE INDEX pesticide_safety_pending_idx ON app.pesticide_safety_alert (tenant_id, created_at) WHERE status = 'pending';
CREATE INDEX stock_event_balance_idx ON app.stock_event (tenant_id, chemical_id, event_ts);
CREATE INDEX stock_alert_pending_idx ON app.stock_alert (tenant_id, created_at) WHERE status = 'pending';

DO $$
DECLARE table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'pesticide_master_release', 'agrochemical', 'pesticide_usage',
    'pesticide_safety_alert', 'stock_event', 'stock_alert'
  ] LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', table_name);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON app.%I AS PERMISSIVE FOR ALL TO app_user USING (tenant_id = app.current_tenant_id()) WITH CHECK (tenant_id = app.current_tenant_id())',
      table_name
    );
  END LOOP;
END $$;

CREATE POLICY pesticide_release_manager_insert ON app.pesticide_master_release AS RESTRICTIVE FOR INSERT TO app_user
  WITH CHECK (app.has_capability('pesticide:manage'));
CREATE POLICY agrochemical_manager_insert ON app.agrochemical AS RESTRICTIVE FOR INSERT TO app_user
  WITH CHECK (app.has_capability('pesticide:manage'));
CREATE POLICY pesticide_usage_scope ON app.pesticide_usage AS RESTRICTIVE FOR SELECT TO app_user
  USING (app.can_read_scope(field_group_id));
CREATE POLICY pesticide_usage_writer ON app.pesticide_usage AS RESTRICTIVE FOR INSERT TO app_user
  WITH CHECK (app.has_capability('pesticide:write') AND app.can_read_scope(field_group_id));
CREATE POLICY pesticide_safety_manager ON app.pesticide_safety_alert AS RESTRICTIVE FOR SELECT TO app_user
  USING (app.has_capability('pesticide:manage'));
CREATE POLICY pesticide_safety_writer ON app.pesticide_safety_alert AS RESTRICTIVE FOR INSERT TO app_user
  WITH CHECK (app.has_capability('pesticide:write') AND app.can_read_scope(field_group_id));
CREATE POLICY stock_event_writer ON app.stock_event AS RESTRICTIVE FOR INSERT TO app_user
  WITH CHECK (app.has_capability('inventory:write')
    AND (event_type <> 'adjustment' OR app.has_capability('inventory:adjust')));
CREATE POLICY stock_event_trigger_reader ON app.stock_event AS PERMISSIVE FOR SELECT TO app_owner
  USING (true);
CREATE POLICY stock_alert_manager ON app.stock_alert AS RESTRICTIVE FOR SELECT TO app_user
  USING (app.has_capability('inventory:adjust'));
CREATE POLICY stock_alert_manager_update ON app.stock_alert AS RESTRICTIVE FOR UPDATE TO app_user
  USING (app.has_capability('inventory:adjust')) WITH CHECK (app.has_capability('inventory:adjust'));
CREATE POLICY stock_alert_trigger_writer ON app.stock_alert AS PERMISSIVE FOR ALL TO app_owner
  USING (true) WITH CHECK (true);

GRANT SELECT, INSERT ON app.pesticide_master_release, app.agrochemical, app.pesticide_usage,
  app.pesticide_safety_alert, app.stock_event TO app_user;
GRANT SELECT ON app.stock_balance, app.stock_alert TO app_user;
GRANT UPDATE (status, resolved_by, resolved_at, resolution_event_id) ON app.stock_alert TO app_user;
REVOKE ALL ON FUNCTION app.detect_negative_stock() FROM PUBLIC;

RESET ROLE;
COMMIT;
