\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
SET LOCAL ROLE app_owner;

CREATE TABLE app.harvest_actual_event (
  tenant_id uuid NOT NULL,
  harvest_event_id uuid NOT NULL,
  event_uuid uuid NOT NULL,
  crop_plan_id uuid NOT NULL,
  field_id uuid NOT NULL,
  field_group_id uuid NOT NULL,
  harvested_on date NOT NULL,
  quantity_kg numeric(14,3) NOT NULL CHECK (quantity_kg > 0),
  grade text CHECK (grade IS NULL OR length(grade) BETWEEN 1 AND 80),
  note text NOT NULL DEFAULT '' CHECK (length(note) <= 1000),
  actor_user_id uuid NOT NULL,
  event_ts timestamptz NOT NULL DEFAULT statement_timestamp(),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, harvest_event_id),
  UNIQUE (tenant_id, event_uuid),
  FOREIGN KEY (tenant_id, crop_plan_id) REFERENCES app.crop_plan (tenant_id, crop_plan_id),
  FOREIGN KEY (tenant_id, field_id) REFERENCES app.field (tenant_id, field_id)
);

CREATE FUNCTION app.validate_harvest_scope()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, app AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM app.crop_plan plan
    WHERE plan.tenant_id = NEW.tenant_id AND plan.crop_plan_id = NEW.crop_plan_id
      AND plan.field_id = NEW.field_id AND plan.field_group_id = NEW.field_group_id AND plan.deleted_at IS NULL)
  THEN RAISE EXCEPTION 'harvest crop plan scope mismatch' USING ERRCODE = '23514'; END IF;
  NEW.event_ts := statement_timestamp();
  RETURN NEW;
END $$;
CREATE TRIGGER harvest_actual_scope_guard BEFORE INSERT ON app.harvest_actual_event
FOR EACH ROW EXECUTE FUNCTION app.validate_harvest_scope();

CREATE FUNCTION app.audit_harvest_actual()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, app AS $$
BEGIN
  INSERT INTO app.phase2_change_audit
    (tenant_id, table_name, operation, record_key, actor_user_id, actor_pseudonym, before_row, after_row)
  VALUES (NEW.tenant_id, TG_TABLE_NAME, TG_OP, NEW.harvest_event_id::text, app.current_user_id(),
    coalesce(nullif(current_setting('app.actor_pseudonym', true), ''), 'system'), NULL, to_jsonb(NEW));
  RETURN NEW;
END $$;

CREATE VIEW app.tenant_plan_actual WITH (security_invoker = true) AS
WITH planned AS (
  SELECT instruction.tenant_id, instruction.crop_plan_id,
    coalesce(sum(extract(epoch FROM instruction.scheduled_end - instruction.scheduled_start)), 0)::bigint AS planned_work_seconds,
    count(*)::integer AS instruction_count,
    count(*) FILTER (WHERE instruction.status = 'completed')::integer AS completed_instruction_count,
    max(instruction.updated_at) AS work_plan_updated_at
  FROM app.work_instruction instruction WHERE instruction.deleted_at IS NULL AND instruction.crop_plan_id IS NOT NULL
  GROUP BY instruction.tenant_id, instruction.crop_plan_id
), worked AS (
  SELECT instruction.tenant_id, instruction.crop_plan_id,
    coalesce(sum(actual.worked_seconds), 0)::bigint AS actual_work_seconds,
    max(actual.last_activity_at) AS work_actual_updated_at
  FROM app.work_instruction instruction JOIN app.work_time_actual actual
    ON actual.tenant_id = instruction.tenant_id AND actual.instruction_id = instruction.instruction_id
  WHERE instruction.deleted_at IS NULL AND instruction.crop_plan_id IS NOT NULL
  GROUP BY instruction.tenant_id, instruction.crop_plan_id
), harvested AS (
  SELECT tenant_id, crop_plan_id, sum(quantity_kg) AS actual_yield_kg, max(event_ts) AS yield_updated_at
  FROM app.harvest_actual_event GROUP BY tenant_id, crop_plan_id
), material AS (
  SELECT plan.tenant_id, plan.crop_plan_id, sum(usage.amount) AS pesticide_amount,
    count(usage.usage_id)::integer AS pesticide_application_count, max(usage.event_ts) AS material_updated_at
  FROM app.crop_plan plan JOIN app.growing_season season
    ON season.tenant_id = plan.tenant_id AND season.season_id = plan.season_id
  LEFT JOIN app.pesticide_usage usage ON usage.tenant_id = plan.tenant_id AND usage.field_id = plan.field_id
    AND usage.crop_name = plan.crop_name AND usage.applied_on BETWEEN season.starts_on AND season.ends_on
  WHERE plan.deleted_at IS NULL GROUP BY plan.tenant_id, plan.crop_plan_id
)
SELECT plan.tenant_id, plan.crop_plan_id, plan.season_id, plan.field_id, plan.field_group_id,
  plan.crop_name, plan.variety_name, plan.planned_area_m2, plan.target_yield_kg,
  coalesce(progress.progress_percent, 0) AS progress_percent,
  coalesce(planned.planned_work_seconds, 0) AS planned_work_seconds,
  coalesce(worked.actual_work_seconds, 0) AS actual_work_seconds,
  coalesce(planned.instruction_count, 0) AS instruction_count,
  coalesce(planned.completed_instruction_count, 0) AS completed_instruction_count,
  harvested.actual_yield_kg, material.pesticide_amount,
  coalesce(material.pesticide_application_count, 0) AS pesticide_application_count,
  greatest(plan.updated_at, planned.work_plan_updated_at, worked.work_actual_updated_at,
    harvested.yield_updated_at, material.material_updated_at) AS freshest_at,
  ARRAY_REMOVE(ARRAY[
    CASE WHEN plan.target_yield_kg IS NULL THEN 'target_yield' END,
    CASE WHEN coalesce(planned.instruction_count, 0) = 0 THEN 'work_plan' END,
    CASE WHEN worked.work_actual_updated_at IS NULL THEN 'work_actual' END,
    CASE WHEN harvested.yield_updated_at IS NULL THEN 'yield_actual' END,
    CASE WHEN material.material_updated_at IS NULL THEN 'material_actual' END
  ], NULL)::text[] AS missing_metrics
FROM app.crop_plan plan
LEFT JOIN app.crop_plan_progress progress USING (tenant_id, crop_plan_id)
LEFT JOIN planned USING (tenant_id, crop_plan_id)
LEFT JOIN worked USING (tenant_id, crop_plan_id)
LEFT JOIN harvested USING (tenant_id, crop_plan_id)
LEFT JOIN material USING (tenant_id, crop_plan_id)
WHERE plan.deleted_at IS NULL;

CREATE VIEW app.tenant_material_actual WITH (security_invoker = true) AS
SELECT usage.tenant_id, 'pesticide_application'::text AS usage_type, usage.chemical_id,
  chemical.name AS material_name, sum(usage.amount) AS quantity, NULL::text AS unit,
  count(*)::integer AS event_count, max(usage.event_ts) AS freshest_at
FROM app.pesticide_usage usage JOIN app.agrochemical chemical
  ON chemical.tenant_id = usage.tenant_id AND chemical.chemical_id = usage.chemical_id
GROUP BY usage.tenant_id, usage.chemical_id, chemical.name
UNION ALL
SELECT event.tenant_id, 'inventory_withdrawal', event.chemical_id, chemical.name,
  sum(abs(event.quantity_delta)), lot.unit, count(*)::integer, max(event.event_ts)
FROM app.stock_event event JOIN app.agrochemical chemical
  ON chemical.tenant_id = event.tenant_id AND chemical.chemical_id = event.chemical_id
LEFT JOIN app.stock_lot lot ON lot.tenant_id = event.tenant_id AND lot.lot_id = event.lot_id
WHERE event.event_type = 'withdrawal'
GROUP BY event.tenant_id, event.chemical_id, chemical.name, lot.unit;

CREATE VIEW app.tenant_analytics_freshness WITH (security_invoker = true) AS
WITH tenants AS (SELECT DISTINCT tenant_id FROM app.crop_plan WHERE deleted_at IS NULL), sources AS (
  SELECT tenant.tenant_id, 'plan'::text AS source, (SELECT max(updated_at) FROM app.crop_plan WHERE tenant_id = tenant.tenant_id AND deleted_at IS NULL) AS freshest_at FROM tenants tenant
  UNION ALL SELECT tenant.tenant_id, 'work_actual', (SELECT max(event_ts) FROM app.work_punch WHERE tenant_id = tenant.tenant_id) FROM tenants tenant
  UNION ALL SELECT tenant.tenant_id, 'yield_actual', (SELECT max(event_ts) FROM app.harvest_actual_event WHERE tenant_id = tenant.tenant_id) FROM tenants tenant
  UNION ALL SELECT tenant.tenant_id, 'material_actual', (SELECT max(event_ts) FROM app.pesticide_usage WHERE tenant_id = tenant.tenant_id) FROM tenants tenant
)
SELECT tenant_id, source, freshest_at,
  CASE WHEN freshest_at IS NULL THEN 'missing'
       WHEN freshest_at < statement_timestamp() - interval '24 hours' THEN 'stale'
       ELSE 'fresh' END AS freshness_status,
  CASE WHEN freshest_at IS NULL THEN NULL ELSE extract(epoch FROM statement_timestamp() - freshest_at)::bigint END AS age_seconds
FROM sources;

ALTER TABLE app.harvest_actual_event OWNER TO app_owner;
ALTER TABLE app.harvest_actual_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.harvest_actual_event FORCE ROW LEVEL SECURITY;
ALTER VIEW app.tenant_plan_actual OWNER TO app_owner;
ALTER VIEW app.tenant_material_actual OWNER TO app_owner;
ALTER VIEW app.tenant_analytics_freshness OWNER TO app_owner;

CREATE POLICY harvest_owner_access ON app.harvest_actual_event FOR ALL TO app_owner USING (true) WITH CHECK (true);
CREATE POLICY harvest_tenant ON app.harvest_actual_event AS PERMISSIVE FOR ALL TO app_user
  USING (tenant_id = app.current_tenant_id()) WITH CHECK (tenant_id = app.current_tenant_id());
CREATE POLICY harvest_reader ON app.harvest_actual_event AS RESTRICTIVE FOR SELECT TO app_user
  USING ((app.has_capability('analytics:read') OR app.has_capability('analytics:write')) AND app.can_read_scope(field_group_id));
CREATE POLICY harvest_writer ON app.harvest_actual_event AS RESTRICTIVE FOR INSERT TO app_user
  WITH CHECK (app.has_capability('analytics:write') AND actor_user_id = app.current_user_id() AND app.can_read_scope(field_group_id));

CREATE TRIGGER z_phase2_change_audit AFTER INSERT ON app.harvest_actual_event
FOR EACH ROW EXECUTE FUNCTION app.audit_harvest_actual();
CREATE INDEX harvest_crop_plan_date_idx ON app.harvest_actual_event (tenant_id, crop_plan_id, harvested_on DESC);
CREATE INDEX harvest_scope_time_idx ON app.harvest_actual_event (tenant_id, field_group_id, event_ts DESC);

GRANT SELECT, INSERT ON app.harvest_actual_event TO app_user;
GRANT SELECT ON app.tenant_plan_actual, app.tenant_material_actual, app.tenant_analytics_freshness TO app_user;
COMMIT;
