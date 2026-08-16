\set ON_ERROR_STOP on

BEGIN;
CREATE SCHEMA IF NOT EXISTS migration_stage;
REVOKE ALL ON SCHEMA migration_stage FROM PUBLIC;

CREATE TABLE migration_stage.work_instruction_crop_plan (
  tenant_id uuid NOT NULL,
  instruction_id uuid NOT NULL,
  crop_plan_id uuid NOT NULL,
  reviewed_by text NOT NULL CHECK (length(reviewed_by) BETWEEN 1 AND 200),
  reviewed_at timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, instruction_id)
);
COMMIT;

\echo 'Load reviewed instruction-to-crop-plan mappings with psql \\copy, then run 0013_phase2_data_model_backfill.sql.'
