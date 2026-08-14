\set ON_ERROR_STOP on
BEGIN;

SET ROLE app_owner;

CREATE TABLE app.journal_revision (
  tenant_id uuid NOT NULL,
  revision_id uuid NOT NULL,
  journal_id uuid NOT NULL,
  worker_user_id uuid NOT NULL,
  action text NOT NULL CHECK (action IN ('approved', 'returned', 'corrected')),
  from_status text NOT NULL,
  to_status text NOT NULL,
  reason text,
  body_snapshot jsonb NOT NULL CHECK (jsonb_typeof(body_snapshot) = 'object'),
  actor_user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, revision_id),
  FOREIGN KEY (tenant_id, journal_id) REFERENCES app.work_journal (tenant_id, journal_id),
  CHECK (action <> 'returned' OR nullif(btrim(reason), '') IS NOT NULL)
);

CREATE INDEX journal_revision_journal_idx ON app.journal_revision (tenant_id, journal_id, created_at);
ALTER TABLE app.journal_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.journal_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY journal_revision_tenant ON app.journal_revision AS PERMISSIVE FOR ALL TO app_user
  USING (tenant_id = app.current_tenant_id())
  WITH CHECK (tenant_id = app.current_tenant_id());
CREATE POLICY journal_revision_visibility ON app.journal_revision AS RESTRICTIVE FOR SELECT TO app_user
  USING (worker_user_id = app.current_user_id() OR app.has_capability('journal:review'));
CREATE POLICY journal_revision_append ON app.journal_revision AS RESTRICTIVE FOR INSERT TO app_user
  WITH CHECK (
    actor_user_id = app.current_user_id()
    AND ((action IN ('approved', 'returned') AND app.has_capability('journal:review'))
      OR (action = 'corrected' AND worker_user_id = app.current_user_id() AND app.has_capability('journal:write')))
  );

DROP POLICY work_journal_self_update ON app.work_journal;
CREATE POLICY work_journal_update ON app.work_journal AS RESTRICTIVE FOR UPDATE TO app_user
  USING ((worker_user_id = app.current_user_id() AND app.has_capability('journal:write')
      AND status IN ('submitted', 'returned', 'corrected'))
    OR app.has_capability('journal:review'))
  WITH CHECK ((worker_user_id = app.current_user_id() AND app.has_capability('journal:write')
      AND status IN ('submitted', 'corrected'))
    OR app.has_capability('journal:review'));

GRANT SELECT, INSERT ON app.journal_revision TO app_user;

RESET ROLE;
COMMIT;
