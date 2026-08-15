BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
SET LOCAL ROLE app_owner;

ALTER TABLE app.journal_attachment
  ALTER COLUMN content DROP NOT NULL,
  ADD COLUMN object_key text,
  ADD COLUMN storage_status text NOT NULL DEFAULT 'legacy'
    CHECK (storage_status IN ('legacy', 'pending', 'ready', 'quarantined', 'deleted')),
  ADD COLUMN retention_class text NOT NULL DEFAULT 'supporting'
    CHECK (retention_class IN ('statutory', 'supporting')),
  ADD COLUMN ready_at timestamptz,
  ADD COLUMN last_storage_check_at timestamptz;

ALTER TABLE app.journal_attachment
  ADD CONSTRAINT journal_attachment_storage_shape CHECK (
    (storage_status = 'legacy' AND content IS NOT NULL AND object_key IS NULL)
    OR (storage_status <> 'legacy' AND content IS NULL AND object_key ~ '^attachments/[0-9a-f-]{36}/[0-9a-f-]{36}/[0-9a-f]{64}$')
  ) NOT VALID;
ALTER TABLE app.journal_attachment VALIDATE CONSTRAINT journal_attachment_storage_shape;

CREATE UNIQUE INDEX journal_attachment_object_key_idx
  ON app.journal_attachment (object_key) WHERE object_key IS NOT NULL;
CREATE INDEX journal_attachment_pending_storage_idx
  ON app.journal_attachment (created_at, tenant_id, attachment_id)
  WHERE storage_status = 'pending';

CREATE POLICY journal_attachment_self_update ON app.journal_attachment AS RESTRICTIVE FOR UPDATE TO app_user
  USING ((worker_user_id = app.current_user_id() AND app.has_capability('journal:write')) OR app.has_capability('security:manage'))
  WITH CHECK ((worker_user_id = app.current_user_id() AND app.has_capability('journal:write')) OR app.has_capability('security:manage'));
GRANT UPDATE (storage_status, ready_at, last_storage_check_at) ON app.journal_attachment TO app_user;

RESET ROLE;
COMMIT;
