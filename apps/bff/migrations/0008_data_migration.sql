\set ON_ERROR_STOP on
BEGIN;

SET ROLE auth_context_owner;
ALTER TABLE priv.auth_role_capability DROP CONSTRAINT auth_role_capability_capability_check;
ALTER TABLE priv.auth_role_capability ADD CONSTRAINT auth_role_capability_capability_check
  CHECK (capability IN (
    'view_others_tracks', 'view_others_punch', 'scope_all',
    'journal:write', 'pesticide:write', 'punch:write', 'conflict:resolve',
    'instruction:manage', 'journal:review',
    'pesticide:manage', 'pesticide:override', 'inventory:write', 'inventory:adjust',
    'migration:manage', 'export:read'
  ));
INSERT INTO priv.auth_role_capability (role_key, capability)
SELECT role_key, capability
FROM (VALUES ('group_admin', 'migration:manage'), ('group_admin', 'export:read')) AS seed(role_key, capability)
WHERE EXISTS (SELECT 1 FROM priv.auth_role role WHERE role.role_key = seed.role_key)
ON CONFLICT DO NOTHING;
RESET ROLE;

SET ROLE app_owner;

CREATE TABLE app.migration_job (
  tenant_id uuid NOT NULL,
  job_id uuid NOT NULL,
  idempotency_key text NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 200),
  dataset text NOT NULL CHECK (dataset IN ('fields', 'journals', 'pesticide_history')),
  source_name text NOT NULL CHECK (length(source_name) BETWEEN 1 AND 255),
  source_sha256 text NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
  mapping jsonb NOT NULL CHECK (jsonb_typeof(mapping) = 'object'),
  status text NOT NULL CHECK (status IN ('validated', 'needs_review', 'committing', 'committed', 'failed')),
  row_count integer NOT NULL CHECK (row_count >= 0),
  valid_count integer NOT NULL CHECK (valid_count >= 0),
  duplicate_count integer NOT NULL CHECK (duplicate_count >= 0),
  error_count integer NOT NULL CHECK (error_count >= 0),
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  committed_at timestamptz,
  PRIMARY KEY (tenant_id, job_id),
  UNIQUE (tenant_id, idempotency_key)
);

CREATE TABLE app.migration_row (
  tenant_id uuid NOT NULL,
  job_id uuid NOT NULL,
  line_number integer NOT NULL CHECK (line_number >= 2),
  raw_data jsonb NOT NULL CHECK (jsonb_typeof(raw_data) = 'object'),
  normalized_data jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(normalized_data) = 'object'),
  row_status text NOT NULL CHECK (row_status IN ('valid', 'duplicate', 'invalid', 'committed')),
  duplicate_key text,
  errors text[] NOT NULL DEFAULT ARRAY[]::text[],
  entity_id uuid,
  PRIMARY KEY (tenant_id, job_id, line_number),
  FOREIGN KEY (tenant_id, job_id) REFERENCES app.migration_job (tenant_id, job_id)
);

ALTER TABLE app.field ADD COLUMN external_key text;
ALTER TABLE app.field ADD COLUMN import_job_id uuid;
ALTER TABLE app.field ADD COLUMN import_source_row integer;
CREATE UNIQUE INDEX field_external_key_idx ON app.field (tenant_id, external_key)
  WHERE external_key IS NOT NULL AND deleted_at IS NULL;
ALTER TABLE app.field ADD FOREIGN KEY (tenant_id, import_job_id)
  REFERENCES app.migration_job (tenant_id, job_id);

ALTER TABLE app.work_journal ADD COLUMN external_key text;
ALTER TABLE app.work_journal ADD COLUMN import_job_id uuid;
ALTER TABLE app.work_journal ADD COLUMN import_source_row integer;
CREATE UNIQUE INDEX work_journal_external_key_idx ON app.work_journal (tenant_id, external_key)
  WHERE external_key IS NOT NULL;
ALTER TABLE app.work_journal ADD FOREIGN KEY (tenant_id, import_job_id)
  REFERENCES app.migration_job (tenant_id, job_id);

CREATE TABLE app.pesticide_usage_summary (
  tenant_id uuid NOT NULL,
  summary_id uuid NOT NULL,
  field_id uuid NOT NULL,
  field_group_id uuid NOT NULL,
  crop_name text NOT NULL CHECK (length(crop_name) BETWEEN 1 AND 200),
  chemical_id uuid NOT NULL,
  season_year integer NOT NULL CHECK (season_year BETWEEN 1900 AND 2200),
  usage_count integer NOT NULL CHECK (usage_count >= 0),
  last_applied_on date NOT NULL,
  import_job_id uuid NOT NULL,
  import_source_row integer NOT NULL CHECK (import_source_row >= 2),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, summary_id),
  UNIQUE (tenant_id, field_id, crop_name, chemical_id, season_year),
  FOREIGN KEY (tenant_id, field_id) REFERENCES app.field (tenant_id, field_id),
  FOREIGN KEY (tenant_id, chemical_id) REFERENCES app.agrochemical (tenant_id, chemical_id),
  FOREIGN KEY (tenant_id, import_job_id) REFERENCES app.migration_job (tenant_id, job_id)
);

CREATE INDEX migration_job_created_idx ON app.migration_job (tenant_id, created_at DESC);
CREATE INDEX migration_row_status_idx ON app.migration_row (tenant_id, job_id, row_status, line_number);
CREATE INDEX pesticide_summary_check_idx ON app.pesticide_usage_summary
  (tenant_id, field_id, crop_name, chemical_id, season_year);

DO $$
DECLARE table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY['migration_job', 'migration_row', 'pesticide_usage_summary'] LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', table_name);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON app.%I AS PERMISSIVE FOR ALL TO app_user USING (tenant_id = app.current_tenant_id()) WITH CHECK (tenant_id = app.current_tenant_id())',
      table_name
    );
  END LOOP;
END $$;

CREATE POLICY migration_job_manager ON app.migration_job AS RESTRICTIVE FOR ALL TO app_user
  USING (app.has_capability('migration:manage')) WITH CHECK (app.has_capability('migration:manage'));
CREATE POLICY migration_row_manager ON app.migration_row AS RESTRICTIVE FOR ALL TO app_user
  USING (app.has_capability('migration:manage')) WITH CHECK (app.has_capability('migration:manage'));
CREATE POLICY pesticide_summary_scope ON app.pesticide_usage_summary AS RESTRICTIVE FOR SELECT TO app_user
  USING (app.can_read_scope(field_group_id));
CREATE POLICY pesticide_summary_import ON app.pesticide_usage_summary AS RESTRICTIVE FOR INSERT TO app_user
  WITH CHECK (app.has_capability('migration:manage') AND app.can_read_scope(field_group_id));
CREATE POLICY field_import_insert ON app.field AS RESTRICTIVE FOR INSERT TO app_user
  WITH CHECK (app.has_capability('migration:manage') AND app.can_read_scope(field_group_id));

DROP POLICY work_journal_self_insert ON app.work_journal;
CREATE POLICY work_journal_self_insert ON app.work_journal AS RESTRICTIVE FOR INSERT TO app_user
  WITH CHECK ((worker_user_id = app.current_user_id() AND app.has_capability('journal:write'))
    OR (app.has_capability('migration:manage') AND app.can_read_scope(field_group_id)));

GRANT SELECT, INSERT, UPDATE ON app.migration_job, app.migration_row TO app_user;
GRANT SELECT, INSERT ON app.pesticide_usage_summary TO app_user;
GRANT INSERT ON app.field TO app_user;

RESET ROLE;
COMMIT;
