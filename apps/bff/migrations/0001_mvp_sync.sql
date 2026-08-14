\set ON_ERROR_STOP on

BEGIN;

-- Promote the S8 reference capability vocabulary to the MVP write paths.
SET ROLE auth_context_owner;
ALTER TABLE priv.auth_role_capability DROP CONSTRAINT auth_role_capability_capability_check;
ALTER TABLE priv.auth_role_capability ADD CONSTRAINT auth_role_capability_capability_check
  CHECK (capability IN (
    'view_others_tracks', 'view_others_punch', 'scope_all',
    'journal:write', 'pesticide:write', 'punch:write', 'conflict:resolve'
  ));
INSERT INTO priv.auth_role_capability (role_key, capability)
SELECT role_key, capability
FROM (VALUES
  ('worker', 'journal:write'), ('worker', 'pesticide:write'), ('worker', 'punch:write'),
  ('group_admin', 'journal:write'), ('group_admin', 'pesticide:write'), ('group_admin', 'punch:write'),
  ('group_admin', 'conflict:resolve')
) AS seed(role_key, capability)
WHERE EXISTS (SELECT 1 FROM priv.auth_role role WHERE role.role_key = seed.role_key)
ON CONFLICT DO NOTHING;
RESET ROLE;

CREATE SCHEMA IF NOT EXISTS app AUTHORIZATION app_owner;
GRANT USAGE ON SCHEMA app TO app_user;

SET ROLE app_owner;

CREATE OR REPLACE FUNCTION app.current_tenant_id()
RETURNS uuid LANGUAGE sql STABLE PARALLEL SAFE AS $$
  SELECT nullif(current_setting('app.tenant_id', true), '')::uuid
$$;

CREATE OR REPLACE FUNCTION app.has_capability(required text)
RETURNS boolean LANGUAGE sql STABLE PARALLEL SAFE AS $$
  SELECT required = ANY(coalesce(nullif(current_setting('app.caps', true), '')::text[], ARRAY[]::text[]))
$$;

CREATE OR REPLACE FUNCTION app.can_read_scope(scope_id uuid)
RETURNS boolean LANGUAGE sql STABLE PARALLEL SAFE AS $$
  SELECT scope_id IS NULL OR scope_id = ANY(coalesce(nullif(current_setting('app.scope_field_groups', true), '')::uuid[], ARRAY[]::uuid[]))
$$;

CREATE TABLE app.task (
  tenant_id uuid NOT NULL,
  task_id uuid NOT NULL,
  field_group_id uuid,
  scheduled_at timestamptz NOT NULL,
  field_name text NOT NULL,
  crop_name text NOT NULL,
  work_name text NOT NULL,
  status text NOT NULL CHECK (status IN ('next', 'today', 'safety_check', 'completed', 'cancelled')),
  deleted_at timestamptz,
  PRIMARY KEY (tenant_id, task_id)
);

CREATE TABLE app.event_receipt (
  tenant_id uuid NOT NULL,
  event_uuid uuid NOT NULL,
  event_ts timestamptz NOT NULL,
  received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, event_uuid)
);

CREATE TABLE app.domain_event (
  tenant_id uuid NOT NULL,
  event_uuid uuid NOT NULL,
  event_ts timestamptz NOT NULL,
  occurred_at timestamptz NOT NULL,
  event_kind text NOT NULL CHECK (event_kind IN ('journal', 'pesticide', 'punch')),
  scope_field_group_id uuid,
  payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
  authorization_snapshot_id text NOT NULL,
  membership_version text NOT NULL,
  actor_pseudonym text NOT NULL,
  clock_skewed boolean NOT NULL DEFAULT false,
  PRIMARY KEY (tenant_id, event_ts, event_uuid),
  FOREIGN KEY (tenant_id, event_uuid) REFERENCES app.event_receipt (tenant_id, event_uuid)
);

CREATE TABLE app.sync_document (
  tenant_id uuid NOT NULL,
  document_id uuid NOT NULL,
  field_group_id uuid,
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  body jsonb NOT NULL CHECK (jsonb_typeof(body) = 'object'),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, document_id)
);

CREATE TABLE app.sync_change (
  server_seq bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id uuid NOT NULL,
  scope_field_group_id uuid,
  priority smallint NOT NULL DEFAULT 1 CHECK (priority IN (0, 1, 2)),
  entity_type text NOT NULL,
  operation text NOT NULL CHECK (operation IN ('upsert', 'delete', 'revoke')),
  entity_id uuid,
  event_uuid uuid,
  data jsonb NOT NULL DEFAULT '{}'::jsonb,
  committed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE app.sync_rejection (
  tenant_id uuid NOT NULL,
  rejection_id uuid NOT NULL,
  bundle_id text NOT NULL,
  event_uuids uuid[] NOT NULL,
  reason text NOT NULL,
  recovery_action text NOT NULL,
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, rejection_id)
);

CREATE TABLE app.sync_conflict (
  tenant_id uuid NOT NULL,
  conflict_id uuid NOT NULL,
  document_id uuid NOT NULL,
  event_uuid uuid NOT NULL,
  base_version bigint NOT NULL,
  current_version bigint NOT NULL,
  current_value jsonb NOT NULL,
  proposed_value jsonb NOT NULL,
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'resolved')),
  resolution jsonb,
  resolved_by uuid,
  resolved_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, conflict_id)
);

CREATE INDEX task_today_idx ON app.task (tenant_id, scheduled_at) WHERE deleted_at IS NULL;
CREATE INDEX domain_event_tenant_ts_idx ON app.domain_event (tenant_id, event_ts);
CREATE INDEX sync_change_pull_idx ON app.sync_change (tenant_id, scope_field_group_id, server_seq);
CREATE INDEX sync_change_priority_idx ON app.sync_change (tenant_id, priority, server_seq);
CREATE INDEX sync_rejection_created_idx ON app.sync_rejection (tenant_id, created_at DESC);
CREATE INDEX sync_conflict_pending_idx ON app.sync_conflict (tenant_id, created_at) WHERE status = 'pending';

DO $$
DECLARE table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY['task','event_receipt','domain_event','sync_document','sync_change','sync_rejection','sync_conflict'] LOOP
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', table_name);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON app.%I AS PERMISSIVE FOR ALL TO app_user USING (tenant_id = app.current_tenant_id()) WITH CHECK (tenant_id = app.current_tenant_id())',
      table_name
    );
  END LOOP;
END $$;

CREATE POLICY task_scope ON app.task AS RESTRICTIVE FOR SELECT TO app_user
  USING (app.can_read_scope(field_group_id));
CREATE POLICY document_scope ON app.sync_document AS RESTRICTIVE FOR SELECT TO app_user
  USING (app.can_read_scope(field_group_id));
CREATE POLICY change_scope ON app.sync_change AS RESTRICTIVE FOR SELECT TO app_user
  USING (app.can_read_scope(scope_field_group_id));
CREATE POLICY conflict_resolver ON app.sync_conflict AS RESTRICTIVE FOR UPDATE TO app_user
  USING (app.has_capability('conflict:resolve'))
  WITH CHECK (app.has_capability('conflict:resolve'));

GRANT SELECT ON app.task, app.sync_change, app.sync_rejection, app.sync_conflict TO app_user;
GRANT SELECT, INSERT ON app.event_receipt, app.domain_event TO app_user;
GRANT SELECT, INSERT, UPDATE ON app.sync_document TO app_user;
GRANT INSERT ON app.sync_change, app.sync_rejection, app.sync_conflict TO app_user;
GRANT UPDATE (status, resolution, resolved_by, resolved_at) ON app.sync_conflict TO app_user;
GRANT USAGE, SELECT ON SEQUENCE app.sync_change_server_seq_seq TO app_user;
GRANT EXECUTE ON FUNCTION app.current_tenant_id(), app.has_capability(text), app.can_read_scope(uuid) TO app_user;

RESET ROLE;
COMMIT;
