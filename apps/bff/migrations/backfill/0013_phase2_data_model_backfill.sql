\set ON_ERROR_STOP on

BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $$
BEGIN
  IF to_regclass('migration_stage.work_instruction_crop_plan') IS NULL THEN
    RAISE EXCEPTION 'required staging table migration_stage.work_instruction_crop_plan is missing';
  END IF;
  IF EXISTS (
    SELECT 1
    FROM migration_stage.work_instruction_crop_plan mapping
    LEFT JOIN app.work_instruction instruction
      ON instruction.tenant_id = mapping.tenant_id
     AND instruction.instruction_id = mapping.instruction_id
    LEFT JOIN app.crop_plan plan
      ON plan.tenant_id = mapping.tenant_id
     AND plan.crop_plan_id = mapping.crop_plan_id
    WHERE instruction.instruction_id IS NULL OR plan.crop_plan_id IS NULL
  ) THEN
    RAISE EXCEPTION 'staging contains an unknown or cross-tenant instruction/crop plan';
  END IF;
  IF EXISTS (
    SELECT 1
    FROM migration_stage.work_instruction_crop_plan mapping
    JOIN app.work_instruction instruction
      ON instruction.tenant_id = mapping.tenant_id
     AND instruction.instruction_id = mapping.instruction_id
    JOIN app.crop_plan plan
      ON plan.tenant_id = mapping.tenant_id
     AND plan.crop_plan_id = mapping.crop_plan_id
    WHERE instruction.field_id <> plan.field_id
       OR instruction.field_group_id <> plan.field_group_id
  ) THEN
    RAISE EXCEPTION 'staging maps an instruction to a crop plan in another field or scope';
  END IF;
END $$;

GRANT USAGE ON SCHEMA migration_stage TO app_owner;
GRANT SELECT ON migration_stage.work_instruction_crop_plan TO app_owner;
SET LOCAL ROLE app_owner;

UPDATE app.work_instruction instruction
SET crop_plan_id = mapping.crop_plan_id,
    version = instruction.version + 1,
    updated_at = clock_timestamp()
FROM migration_stage.work_instruction_crop_plan mapping
WHERE instruction.tenant_id = mapping.tenant_id
  AND instruction.instruction_id = mapping.instruction_id
  AND instruction.crop_plan_id IS DISTINCT FROM mapping.crop_plan_id;

RESET ROLE;
DROP TABLE migration_stage.work_instruction_crop_plan;
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_class class
    JOIN pg_namespace namespace ON namespace.oid = class.relnamespace
    WHERE namespace.nspname = 'migration_stage' AND class.relkind IN ('r', 'p')
  ) THEN
    DROP SCHEMA migration_stage;
  END IF;
END $$;
COMMIT;
