\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM app.work_plan_template)
     OR EXISTS (SELECT 1 FROM app.work_progress_event) THEN
    RAISE EXCEPTION 'refusing advanced planning rollback: template or progress data exists';
  END IF;
END $$;

SET LOCAL ROLE app_owner;
DROP VIEW app.crop_plan_progress;
DROP VIEW app.resource_conflict;
DROP TABLE app.work_progress_event;
DROP TABLE app.work_plan_template_step;
DROP TABLE app.work_plan_template;
DROP INDEX app.work_instruction_crop_plan_schedule_idx;
ALTER TABLE app.work_instruction DROP CONSTRAINT work_instruction_progress_check;
ALTER TABLE app.work_instruction DROP COLUMN progress_updated_at;
ALTER TABLE app.work_instruction DROP COLUMN progress_percent;
DROP FUNCTION app.guard_assignee_progress_update() CASCADE;
DROP POLICY work_instruction_manager_or_assignee_update ON app.work_instruction;
CREATE POLICY work_instruction_manager_update ON app.work_instruction AS RESTRICTIVE FOR UPDATE TO app_user
  USING (app.has_capability('instruction:manage'))
  WITH CHECK (app.has_capability('instruction:manage'));
COMMIT;
