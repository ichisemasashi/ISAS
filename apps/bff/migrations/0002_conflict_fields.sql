\set ON_ERROR_STOP on
BEGIN;
SET ROLE app_owner;
ALTER TABLE app.sync_conflict ADD COLUMN conflicting_fields text[] NOT NULL DEFAULT '{}';
RESET ROLE;
COMMIT;
