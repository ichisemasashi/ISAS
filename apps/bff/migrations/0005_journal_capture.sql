\set ON_ERROR_STOP on
BEGIN;

SET ROLE app_owner;

CREATE TABLE app.work_punch (
  tenant_id uuid NOT NULL,
  punch_id uuid NOT NULL,
  event_uuid uuid NOT NULL,
  user_id uuid NOT NULL,
  instruction_id uuid,
  field_group_id uuid,
  action text NOT NULL CHECK (action IN ('start', 'break', 'resume', 'finish')),
  occurred_at timestamptz NOT NULL,
  event_ts timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, punch_id),
  UNIQUE (tenant_id, event_uuid),
  FOREIGN KEY (tenant_id, instruction_id) REFERENCES app.work_instruction (tenant_id, instruction_id)
);

CREATE TABLE app.journal_template (
  tenant_id uuid NOT NULL,
  template_id uuid NOT NULL,
  field_group_id uuid,
  name text NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
  work_type text NOT NULL CHECK (length(work_type) BETWEEN 1 AND 100),
  defaults jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(defaults) = 'object'),
  sort_order integer NOT NULL DEFAULT 0,
  active boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, template_id)
);

CREATE TABLE app.work_journal (
  tenant_id uuid NOT NULL,
  journal_id uuid NOT NULL,
  instruction_id uuid,
  field_id uuid,
  field_group_id uuid,
  worker_user_id uuid NOT NULL,
  body jsonb NOT NULL CHECK (jsonb_typeof(body) = 'object'),
  status text NOT NULL DEFAULT 'submitted' CHECK (status IN ('submitted', 'approved', 'returned', 'corrected')),
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  submitted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, journal_id),
  FOREIGN KEY (tenant_id, instruction_id) REFERENCES app.work_instruction (tenant_id, instruction_id),
  FOREIGN KEY (tenant_id, field_id) REFERENCES app.field (tenant_id, field_id)
);

CREATE TABLE app.journal_attachment (
  tenant_id uuid NOT NULL,
  attachment_id uuid NOT NULL,
  journal_id uuid NOT NULL,
  worker_user_id uuid NOT NULL,
  file_name text NOT NULL CHECK (length(file_name) BETWEEN 1 AND 255),
  content_type text NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp', 'image/heic')),
  byte_size integer NOT NULL CHECK (byte_size BETWEEN 1 AND 10485760),
  sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  content bytea NOT NULL,
  captured_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, attachment_id),
  FOREIGN KEY (tenant_id, journal_id) REFERENCES app.work_journal (tenant_id, journal_id)
);

CREATE INDEX work_punch_user_time_idx ON app.work_punch (tenant_id, user_id, occurred_at DESC);
CREATE INDEX journal_template_scope_idx ON app.journal_template (tenant_id, field_group_id, sort_order) WHERE active;
CREATE INDEX work_journal_previous_idx ON app.work_journal (tenant_id, worker_user_id, field_id, updated_at DESC);
CREATE INDEX journal_attachment_journal_idx ON app.journal_attachment (tenant_id, journal_id, created_at);

DO $$
DECLARE table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY['work_punch','journal_template','work_journal','journal_attachment'] LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', table_name);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON app.%I AS PERMISSIVE FOR ALL TO app_user USING (tenant_id = app.current_tenant_id()) WITH CHECK (tenant_id = app.current_tenant_id())',
      table_name
    );
  END LOOP;
END $$;

CREATE POLICY work_punch_visibility ON app.work_punch AS RESTRICTIVE FOR SELECT TO app_user
  USING (user_id = app.current_user_id() OR app.has_capability('view_others_punch'));
CREATE POLICY work_punch_self_insert ON app.work_punch AS RESTRICTIVE FOR INSERT TO app_user
  WITH CHECK (user_id = app.current_user_id() AND app.has_capability('punch:write'));
CREATE POLICY journal_template_scope ON app.journal_template AS RESTRICTIVE FOR SELECT TO app_user
  USING (app.can_read_scope(field_group_id));
CREATE POLICY work_journal_visibility ON app.work_journal AS RESTRICTIVE FOR SELECT TO app_user
  USING (worker_user_id = app.current_user_id() OR app.has_capability('journal:review'));
CREATE POLICY work_journal_self_insert ON app.work_journal AS RESTRICTIVE FOR INSERT TO app_user
  WITH CHECK (worker_user_id = app.current_user_id() AND app.has_capability('journal:write'));
CREATE POLICY work_journal_self_update ON app.work_journal AS RESTRICTIVE FOR UPDATE TO app_user
  USING (worker_user_id = app.current_user_id() AND app.has_capability('journal:write'))
  WITH CHECK (worker_user_id = app.current_user_id() AND app.has_capability('journal:write'));
CREATE POLICY journal_attachment_visibility ON app.journal_attachment AS RESTRICTIVE FOR SELECT TO app_user
  USING (worker_user_id = app.current_user_id() OR app.has_capability('journal:review'));
CREATE POLICY journal_attachment_self_insert ON app.journal_attachment AS RESTRICTIVE FOR INSERT TO app_user
  WITH CHECK (worker_user_id = app.current_user_id() AND app.has_capability('journal:write'));

GRANT SELECT, INSERT ON app.work_punch, app.journal_attachment TO app_user;
GRANT SELECT ON app.journal_template TO app_user;
GRANT SELECT, INSERT, UPDATE ON app.work_journal TO app_user;

RESET ROLE;
COMMIT;
