\set ON_ERROR_STOP on
BEGIN;

SET ROLE auth_context_owner;
ALTER TABLE priv.auth_role_capability DROP CONSTRAINT auth_role_capability_capability_check;
ALTER TABLE priv.auth_role_capability ADD CONSTRAINT auth_role_capability_capability_check
  CHECK (capability IN (
    'view_others_tracks', 'view_others_punch', 'scope_all',
    'journal:write', 'pesticide:write', 'punch:write', 'conflict:resolve',
    'instruction:manage', 'journal:review'
  ));
INSERT INTO priv.auth_role_capability (role_key, capability)
SELECT role_key, capability
FROM (VALUES
  ('group_admin', 'instruction:manage'), ('group_admin', 'journal:review')
) AS seed(role_key, capability)
WHERE EXISTS (SELECT 1 FROM priv.auth_role role WHERE role.role_key = seed.role_key)
ON CONFLICT DO NOTHING;
RESET ROLE;

SET ROLE app_owner;

CREATE OR REPLACE FUNCTION app.current_user_id()
RETURNS uuid LANGUAGE sql STABLE PARALLEL SAFE AS $$
  SELECT nullif(current_setting('app.user_id', true), '')::uuid
$$;

CREATE TABLE app.work_instruction (
  tenant_id uuid NOT NULL,
  instruction_id uuid NOT NULL,
  field_id uuid NOT NULL,
  field_group_id uuid NOT NULL,
  title text NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
  work_type text NOT NULL CHECK (length(work_type) BETWEEN 1 AND 100),
  details text NOT NULL DEFAULT '',
  scheduled_start timestamptz NOT NULL,
  scheduled_end timestamptz NOT NULL,
  priority smallint NOT NULL DEFAULT 1 CHECK (priority BETWEEN 0 AND 2),
  status text NOT NULL DEFAULT 'issued' CHECK (status IN ('issued', 'in_progress', 'completed', 'cancelled')),
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  created_by uuid NOT NULL,
  updated_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz,
  PRIMARY KEY (tenant_id, instruction_id),
  FOREIGN KEY (tenant_id, field_id) REFERENCES app.field (tenant_id, field_id),
  CHECK (scheduled_end >= scheduled_start)
);

CREATE TABLE app.work_assignment (
  tenant_id uuid NOT NULL,
  assignment_id uuid NOT NULL,
  instruction_id uuid NOT NULL,
  field_group_id uuid NOT NULL,
  assignee_user_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  assigned_by uuid NOT NULL,
  assigned_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  unassigned_at timestamptz,
  PRIMARY KEY (tenant_id, assignment_id),
  FOREIGN KEY (tenant_id, instruction_id) REFERENCES app.work_instruction (tenant_id, instruction_id)
);

CREATE UNIQUE INDEX work_assignment_one_active_idx
  ON app.work_assignment (tenant_id, instruction_id) WHERE unassigned_at IS NULL;
CREATE INDEX work_instruction_today_idx
  ON app.work_instruction (tenant_id, scheduled_start, instruction_id) WHERE deleted_at IS NULL;
CREATE INDEX work_assignment_assignee_idx
  ON app.work_assignment (tenant_id, assignee_user_id, instruction_id) WHERE unassigned_at IS NULL;

ALTER TABLE app.work_instruction ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.work_instruction FORCE ROW LEVEL SECURITY;
ALTER TABLE app.work_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.work_assignment FORCE ROW LEVEL SECURITY;

CREATE POLICY work_instruction_tenant ON app.work_instruction AS PERMISSIVE FOR ALL TO app_user
  USING (tenant_id = app.current_tenant_id())
  WITH CHECK (tenant_id = app.current_tenant_id());
CREATE POLICY work_instruction_scope ON app.work_instruction AS RESTRICTIVE FOR SELECT TO app_user
  USING (app.can_read_scope(field_group_id));
CREATE POLICY work_instruction_manager_insert ON app.work_instruction AS RESTRICTIVE FOR INSERT TO app_user
  WITH CHECK (app.has_capability('instruction:manage'));
CREATE POLICY work_instruction_manager_update ON app.work_instruction AS RESTRICTIVE FOR UPDATE TO app_user
  USING (app.has_capability('instruction:manage'))
  WITH CHECK (app.has_capability('instruction:manage'));

CREATE POLICY work_assignment_tenant ON app.work_assignment AS PERMISSIVE FOR ALL TO app_user
  USING (tenant_id = app.current_tenant_id())
  WITH CHECK (tenant_id = app.current_tenant_id());
CREATE POLICY work_assignment_visibility ON app.work_assignment AS RESTRICTIVE FOR SELECT TO app_user
  USING (app.can_read_scope(field_group_id)
    AND (assignee_user_id = app.current_user_id() OR app.has_capability('instruction:manage')));
CREATE POLICY work_assignment_manager_insert ON app.work_assignment AS RESTRICTIVE FOR INSERT TO app_user
  WITH CHECK (app.has_capability('instruction:manage'));
CREATE POLICY work_assignment_manager_update ON app.work_assignment AS RESTRICTIVE FOR UPDATE TO app_user
  USING (app.has_capability('instruction:manage'))
  WITH CHECK (app.has_capability('instruction:manage'));

GRANT SELECT, INSERT, UPDATE ON app.work_instruction, app.work_assignment TO app_user;
GRANT EXECUTE ON FUNCTION app.current_user_id() TO app_user;

RESET ROLE;
COMMIT;
