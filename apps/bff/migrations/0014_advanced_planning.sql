\set ON_ERROR_STOP on

BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
SET LOCAL ROLE app_owner;

ALTER TABLE app.work_instruction
  ADD COLUMN progress_percent smallint NOT NULL DEFAULT 0,
  ADD COLUMN progress_updated_at timestamptz;
UPDATE app.work_instruction
SET progress_percent = 100, progress_updated_at = coalesce(updated_at, clock_timestamp())
WHERE status = 'completed';
ALTER TABLE app.work_instruction
  ADD CONSTRAINT work_instruction_progress_check CHECK (
    progress_percent BETWEEN 0 AND 100
    AND (status <> 'completed' OR progress_percent = 100)
  );

DROP POLICY work_instruction_manager_update ON app.work_instruction;
CREATE POLICY work_instruction_manager_or_assignee_update ON app.work_instruction AS RESTRICTIVE FOR UPDATE TO app_user
  USING (app.has_capability('instruction:manage') OR EXISTS (
    SELECT 1 FROM app.work_assignment assignment
    WHERE assignment.tenant_id = work_instruction.tenant_id
      AND assignment.instruction_id = work_instruction.instruction_id
      AND assignment.assignee_user_id = app.current_user_id()
      AND assignment.unassigned_at IS NULL
  ))
  WITH CHECK (app.has_capability('instruction:manage') OR EXISTS (
    SELECT 1 FROM app.work_assignment assignment
    WHERE assignment.tenant_id = work_instruction.tenant_id
      AND assignment.instruction_id = work_instruction.instruction_id
      AND assignment.assignee_user_id = app.current_user_id()
      AND assignment.unassigned_at IS NULL
  ));

CREATE FUNCTION app.guard_assignee_progress_update()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, app AS $$
BEGIN
  IF NOT app.has_capability('instruction:manage') THEN
    IF (to_jsonb(NEW) - ARRAY['progress_percent','status','progress_updated_at','updated_at','updated_by','version'])
       IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['progress_percent','status','progress_updated_at','updated_at','updated_by','version'])
       OR NEW.updated_by <> app.current_user_id()
       OR NEW.version <> OLD.version + 1 THEN
      RAISE EXCEPTION 'assignee may update progress only' USING ERRCODE = '42501';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER work_instruction_assignee_progress_guard
BEFORE UPDATE ON app.work_instruction FOR EACH ROW EXECUTE FUNCTION app.guard_assignee_progress_update();

CREATE TABLE app.work_plan_template (
  tenant_id uuid NOT NULL,
  template_id uuid NOT NULL,
  name text NOT NULL CHECK (length(name) BETWEEN 1 AND 160),
  crop_name text CHECK (crop_name IS NULL OR length(crop_name) BETWEEN 1 AND 120),
  description text NOT NULL DEFAULT '' CHECK (length(description) <= 2000),
  active boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  created_by uuid NOT NULL,
  updated_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, template_id)
);

CREATE TABLE app.work_plan_template_step (
  tenant_id uuid NOT NULL,
  template_id uuid NOT NULL,
  step_key text NOT NULL CHECK (step_key ~ '^[a-z][a-z0-9_-]{0,63}$'),
  title text NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
  work_type text NOT NULL CHECK (length(work_type) BETWEEN 1 AND 100),
  details text NOT NULL DEFAULT '' CHECK (length(details) <= 4000),
  start_offset_days integer NOT NULL CHECK (start_offset_days BETWEEN -366 AND 1095),
  duration_minutes integer NOT NULL CHECK (duration_minutes BETWEEN 1 AND 525600),
  priority smallint NOT NULL DEFAULT 1 CHECK (priority BETWEEN 0 AND 2),
  predecessor_step_key text,
  dependency_type text NOT NULL DEFAULT 'finish_start' CHECK (dependency_type IN ('finish_start', 'start_start', 'finish_finish', 'start_finish')),
  lag_minutes integer NOT NULL DEFAULT 0 CHECK (lag_minutes BETWEEN -525600 AND 525600),
  required_resource_type text CHECK (required_resource_type IS NULL OR required_resource_type IN ('person', 'team', 'machine', 'facility', 'material', 'other')),
  required_quantity numeric(14,3) CHECK (required_quantity IS NULL OR required_quantity > 0),
  sort_order integer NOT NULL DEFAULT 0,
  PRIMARY KEY (tenant_id, template_id, step_key),
  FOREIGN KEY (tenant_id, template_id) REFERENCES app.work_plan_template (tenant_id, template_id) ON DELETE CASCADE,
  FOREIGN KEY (tenant_id, template_id, predecessor_step_key)
    REFERENCES app.work_plan_template_step (tenant_id, template_id, step_key) DEFERRABLE INITIALLY DEFERRED,
  CHECK ((required_resource_type IS NULL) = (required_quantity IS NULL)),
  CHECK (predecessor_step_key IS NULL OR predecessor_step_key <> step_key)
);

CREATE TABLE app.work_progress_event (
  tenant_id uuid NOT NULL,
  progress_event_id uuid NOT NULL,
  event_uuid uuid NOT NULL,
  instruction_id uuid NOT NULL,
  field_group_id uuid NOT NULL,
  progress_percent smallint NOT NULL CHECK (progress_percent BETWEEN 0 AND 100),
  note text NOT NULL DEFAULT '' CHECK (length(note) <= 1000),
  actor_user_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL,
  event_ts timestamptz NOT NULL DEFAULT statement_timestamp(),
  PRIMARY KEY (tenant_id, progress_event_id),
  UNIQUE (tenant_id, event_uuid),
  FOREIGN KEY (tenant_id, instruction_id) REFERENCES app.work_instruction (tenant_id, instruction_id)
);

CREATE VIEW app.resource_conflict WITH (security_invoker = true) AS
SELECT left_allocation.tenant_id,
       left_allocation.resource_id,
       resource.name AS resource_name,
       left_allocation.allocation_id AS left_allocation_id,
       right_allocation.allocation_id AS right_allocation_id,
       left_allocation.instruction_id AS left_instruction_id,
       right_allocation.instruction_id AS right_instruction_id,
       greatest(left_allocation.allocated_start, right_allocation.allocated_start) AS conflict_start,
       least(left_allocation.allocated_end, right_allocation.allocated_end) AS conflict_end,
       left_allocation.quantity + right_allocation.quantity AS allocated_quantity,
       resource.capacity
FROM app.work_resource_allocation left_allocation
JOIN app.work_resource_allocation right_allocation
  ON right_allocation.tenant_id = left_allocation.tenant_id
 AND right_allocation.resource_id = left_allocation.resource_id
 AND right_allocation.allocation_id > left_allocation.allocation_id
 AND tstzrange(right_allocation.allocated_start, right_allocation.allocated_end, '[)')
     && tstzrange(left_allocation.allocated_start, left_allocation.allocated_end, '[)')
JOIN app.planning_resource resource
  ON resource.tenant_id = left_allocation.tenant_id
 AND resource.resource_id = left_allocation.resource_id
WHERE left_allocation.quantity + right_allocation.quantity > resource.capacity
  AND resource.deleted_at IS NULL;

CREATE VIEW app.crop_plan_progress WITH (security_invoker = true) AS
SELECT plan.tenant_id, plan.crop_plan_id, plan.season_id, plan.field_id, plan.field_group_id,
       plan.crop_name, plan.variety_name, plan.planned_area_m2, plan.target_yield_kg,
       plan.status,
       coalesce(round(avg(instruction.progress_percent)), 0)::smallint AS progress_percent,
       count(instruction.instruction_id)::integer AS instruction_count,
       count(*) FILTER (WHERE instruction.status = 'completed')::integer AS completed_count
FROM app.crop_plan plan
LEFT JOIN app.work_instruction instruction
  ON instruction.tenant_id = plan.tenant_id
 AND instruction.crop_plan_id = plan.crop_plan_id
 AND instruction.deleted_at IS NULL
WHERE plan.deleted_at IS NULL
GROUP BY plan.tenant_id, plan.crop_plan_id, plan.season_id, plan.field_id, plan.field_group_id,
         plan.crop_name, plan.variety_name, plan.planned_area_m2, plan.target_yield_kg, plan.status;

CREATE OR REPLACE FUNCTION app.audit_phase2_change()
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
    row_value ->> 'consent_event_id', row_value ->> 'template_id', row_value ->> 'progress_event_id',
    nullif(concat_ws(':', row_value ->> 'predecessor_instruction_id', row_value ->> 'successor_instruction_id'), ''),
    nullif(row_value ->> 'step_key', ''), 'unknown'
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

DO $$
DECLARE table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY['work_plan_template', 'work_plan_template_step', 'work_progress_event'] LOOP
    EXECUTE format('ALTER TABLE app.%I OWNER TO app_owner', table_name);
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', table_name);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON app.%I AS PERMISSIVE FOR ALL TO app_user USING (tenant_id = app.current_tenant_id()) WITH CHECK (tenant_id = app.current_tenant_id())',
      table_name
    );
  END LOOP;
END $$;
ALTER VIEW app.resource_conflict OWNER TO app_owner;
ALTER VIEW app.crop_plan_progress OWNER TO app_owner;

CREATE POLICY work_template_reader ON app.work_plan_template AS RESTRICTIVE FOR SELECT TO app_user
  USING (app.has_capability('planning:manage'));
CREATE POLICY work_template_step_reader ON app.work_plan_template_step AS RESTRICTIVE FOR SELECT TO app_user
  USING (app.has_capability('planning:manage'));
CREATE POLICY work_template_writer ON app.work_plan_template AS RESTRICTIVE FOR INSERT TO app_user
  WITH CHECK (app.has_capability('planning:manage'));
CREATE POLICY work_template_updater ON app.work_plan_template AS RESTRICTIVE FOR UPDATE TO app_user
  USING (app.has_capability('planning:manage')) WITH CHECK (app.has_capability('planning:manage'));
CREATE POLICY work_template_step_writer ON app.work_plan_template_step AS RESTRICTIVE FOR INSERT TO app_user
  WITH CHECK (app.has_capability('planning:manage'));
CREATE POLICY work_template_step_updater ON app.work_plan_template_step AS RESTRICTIVE FOR UPDATE TO app_user
  USING (app.has_capability('planning:manage')) WITH CHECK (app.has_capability('planning:manage'));
CREATE POLICY work_template_step_deleter ON app.work_plan_template_step AS RESTRICTIVE FOR DELETE TO app_user
  USING (app.has_capability('planning:manage'));

CREATE POLICY progress_scope ON app.work_progress_event AS RESTRICTIVE FOR SELECT TO app_user
  USING (app.can_read_scope(field_group_id));
CREATE POLICY progress_writer ON app.work_progress_event AS RESTRICTIVE FOR INSERT TO app_user
  WITH CHECK (app.can_read_scope(field_group_id)
    AND (actor_user_id = app.current_user_id() OR app.has_capability('instruction:manage'))
    AND event_ts = statement_timestamp());

CREATE TRIGGER z_phase2_change_audit AFTER INSERT OR UPDATE OR DELETE ON app.work_plan_template
FOR EACH ROW EXECUTE FUNCTION app.audit_phase2_change();
CREATE TRIGGER z_phase2_change_audit AFTER INSERT OR UPDATE OR DELETE ON app.work_plan_template_step
FOR EACH ROW EXECUTE FUNCTION app.audit_phase2_change();

CREATE INDEX work_template_active_idx ON app.work_plan_template (tenant_id, crop_name, name) WHERE active;
CREATE INDEX work_template_step_order_idx ON app.work_plan_template_step (tenant_id, template_id, sort_order, step_key);
CREATE INDEX work_progress_instruction_idx ON app.work_progress_event (tenant_id, instruction_id, event_ts DESC);
CREATE INDEX work_instruction_crop_plan_schedule_idx
  ON app.work_instruction (tenant_id, crop_plan_id, scheduled_start, instruction_id) WHERE deleted_at IS NULL;

GRANT SELECT, INSERT, UPDATE ON app.work_plan_template TO app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON app.work_plan_template_step TO app_user;
GRANT SELECT, INSERT ON app.work_progress_event TO app_user;
GRANT SELECT ON app.resource_conflict, app.crop_plan_progress TO app_user;
GRANT EXECUTE ON FUNCTION app.guard_assignee_progress_update() TO app_user;

COMMIT;
