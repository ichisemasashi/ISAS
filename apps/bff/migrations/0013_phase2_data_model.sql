\set ON_ERROR_STOP on

BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

SET LOCAL ROLE auth_context_owner;
ALTER TABLE priv.auth_role_capability DROP CONSTRAINT auth_role_capability_capability_check;
ALTER TABLE priv.auth_role_capability ADD CONSTRAINT auth_role_capability_capability_check
  CHECK (capability IN (
    'view_others_tracks', 'view_others_punch', 'scope_all',
    'journal:write', 'pesticide:write', 'punch:write', 'conflict:resolve',
    'instruction:manage', 'journal:review',
    'pesticide:manage', 'pesticide:override', 'inventory:write', 'inventory:adjust',
    'migration:manage', 'export:read',
    'security:manage', 'privacy:manage', 'break_glass:approve',
    'planning:manage', 'resource:manage', 'inventory:policy:manage',
    'analytics:write', 'analytics:read'
  ));

INSERT INTO priv.auth_role_capability (role_key, capability)
SELECT role_key, capability
FROM (VALUES
  ('group_admin', 'planning:manage'),
  ('group_admin', 'resource:manage'),
  ('group_admin', 'inventory:policy:manage'),
  ('group_admin', 'analytics:write'),
  ('group_admin', 'analytics:read')
) AS seed(role_key, capability)
WHERE EXISTS (SELECT 1 FROM priv.auth_role role WHERE role.role_key = seed.role_key)
ON CONFLICT DO NOTHING;

SET LOCAL ROLE app_owner;

CREATE TABLE app.growing_season (
  tenant_id uuid NOT NULL,
  season_id uuid NOT NULL,
  name text NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
  starts_on date NOT NULL,
  ends_on date NOT NULL,
  status text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'active', 'closed', 'cancelled')),
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  created_by uuid NOT NULL,
  updated_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz,
  PRIMARY KEY (tenant_id, season_id),
  CHECK (ends_on >= starts_on)
);

CREATE TABLE app.crop_plan (
  tenant_id uuid NOT NULL,
  crop_plan_id uuid NOT NULL,
  season_id uuid NOT NULL,
  field_id uuid NOT NULL,
  field_group_id uuid NOT NULL,
  crop_name text NOT NULL CHECK (length(crop_name) BETWEEN 1 AND 120),
  variety_name text NOT NULL DEFAULT '' CHECK (length(variety_name) <= 120),
  planned_area_m2 numeric(14,2) NOT NULL CHECK (planned_area_m2 > 0),
  target_yield_kg numeric(14,3) CHECK (target_yield_kg IS NULL OR target_yield_kg >= 0),
  planting_window_start date,
  planting_window_end date,
  harvest_window_start date,
  harvest_window_end date,
  status text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'approved', 'in_progress', 'completed', 'cancelled')),
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  created_by uuid NOT NULL,
  updated_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz,
  PRIMARY KEY (tenant_id, crop_plan_id),
  FOREIGN KEY (tenant_id, season_id) REFERENCES app.growing_season (tenant_id, season_id),
  FOREIGN KEY (tenant_id, field_id) REFERENCES app.field (tenant_id, field_id),
  CHECK (planting_window_end IS NULL OR planting_window_start IS NOT NULL),
  CHECK (planting_window_end IS NULL OR planting_window_end >= planting_window_start),
  CHECK (harvest_window_end IS NULL OR harvest_window_start IS NOT NULL),
  CHECK (harvest_window_end IS NULL OR harvest_window_end >= harvest_window_start)
);

ALTER TABLE app.work_instruction ADD COLUMN crop_plan_id uuid;
ALTER TABLE app.work_instruction ADD CONSTRAINT work_instruction_crop_plan_fk
  FOREIGN KEY (tenant_id, crop_plan_id) REFERENCES app.crop_plan (tenant_id, crop_plan_id) NOT VALID;
ALTER TABLE app.work_instruction VALIDATE CONSTRAINT work_instruction_crop_plan_fk;

CREATE TABLE app.work_instruction_dependency (
  tenant_id uuid NOT NULL,
  predecessor_instruction_id uuid NOT NULL,
  successor_instruction_id uuid NOT NULL,
  dependency_type text NOT NULL DEFAULT 'finish_start' CHECK (dependency_type IN ('finish_start', 'start_start', 'finish_finish', 'start_finish')),
  lag_minutes integer NOT NULL DEFAULT 0 CHECK (lag_minutes BETWEEN -525600 AND 525600),
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, predecessor_instruction_id, successor_instruction_id),
  FOREIGN KEY (tenant_id, predecessor_instruction_id) REFERENCES app.work_instruction (tenant_id, instruction_id),
  FOREIGN KEY (tenant_id, successor_instruction_id) REFERENCES app.work_instruction (tenant_id, instruction_id),
  CHECK (predecessor_instruction_id <> successor_instruction_id)
);

CREATE TABLE app.planning_resource (
  tenant_id uuid NOT NULL,
  resource_id uuid NOT NULL,
  field_group_id uuid,
  resource_type text NOT NULL CHECK (resource_type IN ('person', 'team', 'machine', 'facility', 'material', 'other')),
  name text NOT NULL CHECK (length(name) BETWEEN 1 AND 160),
  capacity numeric(14,3) NOT NULL DEFAULT 1 CHECK (capacity > 0),
  capacity_unit text NOT NULL DEFAULT 'unit' CHECK (length(capacity_unit) BETWEEN 1 AND 32),
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'inactive', 'maintenance')),
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  created_by uuid NOT NULL,
  updated_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz,
  PRIMARY KEY (tenant_id, resource_id)
);

CREATE TABLE app.work_resource_allocation (
  tenant_id uuid NOT NULL,
  allocation_id uuid NOT NULL,
  instruction_id uuid NOT NULL,
  resource_id uuid NOT NULL,
  field_group_id uuid NOT NULL,
  quantity numeric(14,3) NOT NULL CHECK (quantity > 0),
  allocated_start timestamptz NOT NULL,
  allocated_end timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  created_by uuid NOT NULL,
  updated_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, allocation_id),
  FOREIGN KEY (tenant_id, instruction_id) REFERENCES app.work_instruction (tenant_id, instruction_id),
  FOREIGN KEY (tenant_id, resource_id) REFERENCES app.planning_resource (tenant_id, resource_id),
  CHECK (allocated_end >= allocated_start)
);

CREATE TABLE app.inventory_policy (
  tenant_id uuid NOT NULL,
  policy_id uuid NOT NULL,
  chemical_id uuid NOT NULL,
  reorder_point numeric(14,3) NOT NULL CHECK (reorder_point >= 0),
  target_level numeric(14,3) NOT NULL CHECK (target_level >= reorder_point),
  safety_stock numeric(14,3) NOT NULL DEFAULT 0 CHECK (safety_stock >= 0 AND safety_stock <= target_level),
  allow_negative boolean NOT NULL DEFAULT false,
  adjustment_requires_approval boolean NOT NULL DEFAULT true,
  effective_from date NOT NULL,
  effective_to date,
  status text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'active', 'retired')),
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  created_by uuid NOT NULL,
  updated_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz,
  PRIMARY KEY (tenant_id, policy_id),
  FOREIGN KEY (tenant_id, chemical_id) REFERENCES app.agrochemical (tenant_id, chemical_id),
  CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE UNIQUE INDEX inventory_policy_one_active_idx
  ON app.inventory_policy (tenant_id, chemical_id) WHERE status = 'active' AND deleted_at IS NULL;

CREATE TABLE app.analytics_event (
  tenant_id uuid NOT NULL,
  analytics_event_id uuid NOT NULL,
  event_uuid uuid NOT NULL,
  event_type text NOT NULL CHECK (event_type ~ '^[a-z][a-z0-9_.-]{0,99}$'),
  schema_version integer NOT NULL DEFAULT 1 CHECK (schema_version > 0),
  source_type text NOT NULL CHECK (length(source_type) BETWEEN 1 AND 80),
  source_id uuid,
  field_id uuid,
  field_group_id uuid,
  dimensions jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(dimensions) = 'object'),
  measures jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(measures) = 'object'),
  occurred_at timestamptz NOT NULL,
  event_ts timestamptz NOT NULL DEFAULT statement_timestamp(),
  actor_user_id uuid,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, analytics_event_id),
  UNIQUE (tenant_id, event_uuid),
  FOREIGN KEY (tenant_id, field_id) REFERENCES app.field (tenant_id, field_id)
);

CREATE TABLE app.location_consent_event (
  tenant_id uuid NOT NULL,
  consent_event_id uuid NOT NULL,
  event_uuid uuid NOT NULL,
  subject_user_id uuid NOT NULL,
  action text NOT NULL CHECK (action IN ('granted', 'withdrawn')),
  purpose text NOT NULL CHECK (purpose IN ('work_evidence', 'safety', 'route_optimization')),
  policy_version text NOT NULL CHECK (length(policy_version) BETWEEN 1 AND 64),
  consent_text_sha256 text NOT NULL CHECK (consent_text_sha256 ~ '^[0-9a-f]{64}$'),
  locale text NOT NULL CHECK (locale ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
  effective_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  expires_at timestamptz,
  actor_user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, consent_event_id),
  UNIQUE (tenant_id, event_uuid),
  CHECK (expires_at IS NULL OR expires_at > effective_at)
);

CREATE VIEW app.location_consent_current WITH (security_invoker = true) AS
SELECT DISTINCT ON (tenant_id, subject_user_id, purpose)
  tenant_id, subject_user_id, purpose, action, policy_version,
  consent_text_sha256, locale, effective_at, expires_at, consent_event_id
FROM app.location_consent_event
ORDER BY tenant_id, subject_user_id, purpose, effective_at DESC, created_at DESC, consent_event_id DESC;

CREATE TABLE app.phase2_change_audit (
  audit_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id uuid NOT NULL,
  table_name text NOT NULL,
  operation text NOT NULL CHECK (operation IN ('INSERT', 'UPDATE', 'DELETE')),
  record_key text NOT NULL,
  actor_user_id uuid,
  actor_pseudonym text NOT NULL,
  before_row jsonb,
  after_row jsonb,
  occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE FUNCTION app.audit_phase2_change()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, app AS $$
DECLARE row_before jsonb; row_after jsonb; row_value jsonb; tenant_value uuid; key_value text;
BEGIN
  row_before := CASE WHEN TG_OP IN ('UPDATE', 'DELETE') THEN to_jsonb(OLD) END;
  row_after := CASE WHEN TG_OP IN ('INSERT', 'UPDATE') THEN to_jsonb(NEW) END;
  row_value := coalesce(row_after, row_before);
  tenant_value := (row_value ->> 'tenant_id')::uuid;
  key_value := coalesce(
    row_value ->> 'season_id', row_value ->> 'crop_plan_id', row_value ->> 'allocation_id',
    row_value ->> 'resource_id', row_value ->> 'policy_id', row_value ->> 'analytics_event_id',
    row_value ->> 'consent_event_id',
    concat_ws(':', row_value ->> 'predecessor_instruction_id', row_value ->> 'successor_instruction_id')
  );
  INSERT INTO app.phase2_change_audit
    (tenant_id, table_name, operation, record_key, actor_user_id, actor_pseudonym, before_row, after_row)
  VALUES (
    tenant_value, TG_TABLE_NAME, TG_OP, key_value,
    app.current_user_id(), coalesce(nullif(current_setting('app.actor_pseudonym', true), ''), 'system'),
    row_before, row_after
  );
  RETURN coalesce(NEW, OLD);
END $$;

CREATE FUNCTION app.reject_work_dependency_cycle()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, app AS $$
BEGIN
  IF EXISTS (
    WITH RECURSIVE successors(instruction_id) AS (
      SELECT NEW.successor_instruction_id
      UNION
      SELECT dependency.successor_instruction_id
      FROM app.work_instruction_dependency dependency
      JOIN successors current_node
        ON dependency.tenant_id = NEW.tenant_id
       AND dependency.predecessor_instruction_id = current_node.instruction_id
      WHERE (dependency.predecessor_instruction_id, dependency.successor_instruction_id)
         <> (NEW.predecessor_instruction_id, NEW.successor_instruction_id)
    )
    SELECT 1 FROM successors WHERE instruction_id = NEW.predecessor_instruction_id
  ) THEN
    RAISE EXCEPTION 'work instruction dependency cycle' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE FUNCTION app.validate_crop_plan_scope()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, app AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM app.field field
    WHERE field.tenant_id = NEW.tenant_id
      AND field.field_id = NEW.field_id
      AND field.field_group_id = NEW.field_group_id
  ) THEN
    RAISE EXCEPTION 'crop plan field scope mismatch' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE FUNCTION app.validate_resource_allocation_scope()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, app AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM app.work_instruction instruction
    WHERE instruction.tenant_id = NEW.tenant_id
      AND instruction.instruction_id = NEW.instruction_id
      AND instruction.field_group_id = NEW.field_group_id
  ) OR NOT EXISTS (
    SELECT 1 FROM app.planning_resource resource
    WHERE resource.tenant_id = NEW.tenant_id
      AND resource.resource_id = NEW.resource_id
      AND (resource.field_group_id IS NULL OR resource.field_group_id = NEW.field_group_id)
  ) THEN
    RAISE EXCEPTION 'resource allocation scope mismatch' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER work_dependency_no_cycle
BEFORE INSERT OR UPDATE ON app.work_instruction_dependency
FOR EACH ROW EXECUTE FUNCTION app.reject_work_dependency_cycle();
CREATE TRIGGER crop_plan_scope_match
BEFORE INSERT OR UPDATE OF tenant_id, field_id, field_group_id ON app.crop_plan
FOR EACH ROW EXECUTE FUNCTION app.validate_crop_plan_scope();
CREATE TRIGGER work_resource_allocation_scope_match
BEFORE INSERT OR UPDATE OF tenant_id, instruction_id, resource_id, field_group_id ON app.work_resource_allocation
FOR EACH ROW EXECUTE FUNCTION app.validate_resource_allocation_scope();

DO $$
DECLARE table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'growing_season', 'crop_plan', 'work_instruction_dependency',
    'planning_resource', 'work_resource_allocation', 'inventory_policy',
    'analytics_event', 'location_consent_event', 'phase2_change_audit'
  ] LOOP
    EXECUTE format('ALTER TABLE app.%I OWNER TO app_owner', table_name);
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', table_name);
  END LOOP;
END $$;
ALTER VIEW app.location_consent_current OWNER TO app_owner;

CREATE POLICY phase2_audit_owner ON app.phase2_change_audit FOR ALL TO app_owner USING (true) WITH CHECK (true);
CREATE POLICY phase2_dependency_owner ON app.work_instruction_dependency FOR SELECT TO app_owner USING (true);
CREATE POLICY phase2_resource_owner ON app.planning_resource FOR SELECT TO app_owner USING (true);
CREATE POLICY work_instruction_phase2_owner ON app.work_instruction FOR ALL TO app_owner USING (true) WITH CHECK (true);
CREATE POLICY field_phase2_owner ON app.field FOR SELECT TO app_owner USING (true);

DO $$
DECLARE table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'growing_season', 'crop_plan', 'work_instruction_dependency',
    'planning_resource', 'work_resource_allocation', 'inventory_policy',
    'analytics_event', 'location_consent_event', 'phase2_change_audit'
  ] LOOP
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON app.%I AS PERMISSIVE FOR ALL TO app_user USING (tenant_id = app.current_tenant_id()) WITH CHECK (tenant_id = app.current_tenant_id())',
      table_name
    );
  END LOOP;
END $$;

CREATE POLICY crop_plan_scope ON app.crop_plan AS RESTRICTIVE FOR SELECT TO app_user USING (app.can_read_scope(field_group_id));
CREATE POLICY dependency_manager_read ON app.work_instruction_dependency AS RESTRICTIVE FOR SELECT TO app_user USING (app.has_capability('planning:manage'));
CREATE POLICY resource_scope ON app.planning_resource AS RESTRICTIVE FOR SELECT TO app_user
  USING (field_group_id IS NULL OR app.can_read_scope(field_group_id));
CREATE POLICY allocation_scope ON app.work_resource_allocation AS RESTRICTIVE FOR SELECT TO app_user USING (app.can_read_scope(field_group_id));
CREATE POLICY inventory_policy_reader ON app.inventory_policy AS RESTRICTIVE FOR SELECT TO app_user
  USING (app.has_capability('inventory:write') OR app.has_capability('inventory:policy:manage'));
CREATE POLICY analytics_reader ON app.analytics_event AS RESTRICTIVE FOR SELECT TO app_user
  USING (app.has_capability('analytics:read') AND (field_group_id IS NULL OR app.can_read_scope(field_group_id)));
CREATE POLICY location_consent_reader ON app.location_consent_event AS RESTRICTIVE FOR SELECT TO app_user
  USING (subject_user_id = app.current_user_id() OR app.has_capability('privacy:manage'));
CREATE POLICY phase2_audit_reader ON app.phase2_change_audit AS RESTRICTIVE FOR SELECT TO app_user
  USING (app.has_capability('security:manage'));

CREATE POLICY growing_season_manager_insert ON app.growing_season AS RESTRICTIVE FOR INSERT TO app_user WITH CHECK (app.has_capability('planning:manage'));
CREATE POLICY growing_season_manager_update ON app.growing_season AS RESTRICTIVE FOR UPDATE TO app_user USING (app.has_capability('planning:manage')) WITH CHECK (app.has_capability('planning:manage'));
CREATE POLICY crop_plan_manager_insert ON app.crop_plan AS RESTRICTIVE FOR INSERT TO app_user WITH CHECK (app.has_capability('planning:manage') AND app.can_read_scope(field_group_id));
CREATE POLICY crop_plan_manager_update ON app.crop_plan AS RESTRICTIVE FOR UPDATE TO app_user USING (app.has_capability('planning:manage')) WITH CHECK (app.has_capability('planning:manage') AND app.can_read_scope(field_group_id));
CREATE POLICY dependency_manager_insert ON app.work_instruction_dependency AS RESTRICTIVE FOR INSERT TO app_user WITH CHECK (app.has_capability('planning:manage'));
CREATE POLICY dependency_manager_update ON app.work_instruction_dependency AS RESTRICTIVE FOR UPDATE TO app_user USING (app.has_capability('planning:manage')) WITH CHECK (app.has_capability('planning:manage'));
CREATE POLICY dependency_manager_delete ON app.work_instruction_dependency AS RESTRICTIVE FOR DELETE TO app_user USING (app.has_capability('planning:manage'));
CREATE POLICY resource_manager_insert ON app.planning_resource AS RESTRICTIVE FOR INSERT TO app_user WITH CHECK (app.has_capability('resource:manage') AND (field_group_id IS NULL OR app.can_read_scope(field_group_id)));
CREATE POLICY resource_manager_update ON app.planning_resource AS RESTRICTIVE FOR UPDATE TO app_user USING (app.has_capability('resource:manage')) WITH CHECK (app.has_capability('resource:manage') AND (field_group_id IS NULL OR app.can_read_scope(field_group_id)));
CREATE POLICY allocation_manager_insert ON app.work_resource_allocation AS RESTRICTIVE FOR INSERT TO app_user WITH CHECK (app.has_capability('resource:manage') AND app.can_read_scope(field_group_id));
CREATE POLICY allocation_manager_update ON app.work_resource_allocation AS RESTRICTIVE FOR UPDATE TO app_user USING (app.has_capability('resource:manage')) WITH CHECK (app.has_capability('resource:manage') AND app.can_read_scope(field_group_id));
CREATE POLICY inventory_policy_manager_insert ON app.inventory_policy AS RESTRICTIVE FOR INSERT TO app_user WITH CHECK (app.has_capability('inventory:policy:manage'));
CREATE POLICY inventory_policy_manager_update ON app.inventory_policy AS RESTRICTIVE FOR UPDATE TO app_user USING (app.has_capability('inventory:policy:manage')) WITH CHECK (app.has_capability('inventory:policy:manage'));
CREATE POLICY analytics_writer ON app.analytics_event AS RESTRICTIVE FOR INSERT TO app_user WITH CHECK (app.has_capability('analytics:write') AND (field_group_id IS NULL OR app.can_read_scope(field_group_id)) AND event_ts = statement_timestamp());
CREATE POLICY location_consent_writer ON app.location_consent_event AS RESTRICTIVE FOR INSERT TO app_user
  WITH CHECK (
    actor_user_id = app.current_user_id()
    AND (subject_user_id = app.current_user_id() OR (action = 'withdrawn' AND app.has_capability('privacy:manage')))
  );

DO $$
DECLARE table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'growing_season', 'crop_plan', 'work_instruction_dependency',
    'planning_resource', 'work_resource_allocation', 'inventory_policy',
    'analytics_event', 'location_consent_event'
  ] LOOP
    EXECUTE format(
      'CREATE TRIGGER z_phase2_change_audit AFTER INSERT OR UPDATE OR DELETE ON app.%I FOR EACH ROW EXECUTE FUNCTION app.audit_phase2_change()',
      table_name
    );
  END LOOP;
END $$;

CREATE INDEX growing_season_dates_idx ON app.growing_season (tenant_id, starts_on, ends_on) WHERE deleted_at IS NULL;
CREATE INDEX crop_plan_field_idx ON app.crop_plan (tenant_id, field_id, season_id) WHERE deleted_at IS NULL;
CREATE INDEX work_dependency_successor_idx ON app.work_instruction_dependency (tenant_id, successor_instruction_id);
CREATE INDEX planning_resource_scope_idx ON app.planning_resource (tenant_id, field_group_id, resource_type) WHERE deleted_at IS NULL;
CREATE INDEX work_resource_time_idx ON app.work_resource_allocation (tenant_id, resource_id, allocated_start, allocated_end);
CREATE INDEX inventory_policy_effective_idx ON app.inventory_policy (tenant_id, chemical_id, effective_from DESC) WHERE deleted_at IS NULL;
CREATE INDEX analytics_event_time_idx ON app.analytics_event (tenant_id, event_type, event_ts DESC);
CREATE INDEX analytics_event_scope_idx ON app.analytics_event (tenant_id, field_group_id, event_ts DESC);
CREATE INDEX location_consent_subject_idx ON app.location_consent_event (tenant_id, subject_user_id, purpose, effective_at DESC);
CREATE INDEX phase2_change_audit_time_idx ON app.phase2_change_audit (tenant_id, occurred_at DESC, audit_id DESC);

GRANT SELECT, INSERT, UPDATE ON app.growing_season, app.crop_plan, app.planning_resource,
  app.work_resource_allocation, app.inventory_policy TO app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON app.work_instruction_dependency TO app_user;
GRANT SELECT, INSERT ON app.analytics_event, app.location_consent_event TO app_user;
GRANT SELECT ON app.location_consent_current, app.phase2_change_audit TO app_user;
GRANT USAGE, SELECT ON SEQUENCE app.phase2_change_audit_audit_id_seq TO app_owner;

COMMIT;
