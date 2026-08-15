BEGIN;
SET LOCAL ROLE app_user;
SELECT set_config('app.tenant_id', '11111111-1111-7111-8111-111111111111', true);
SELECT set_config('app.user_id', '22222222-2222-7222-8222-222222222222', true);
SELECT set_config('app.capabilities', 'journal:write', true);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'app' AND table_name = 'journal_attachment'
      AND column_name = 'object_key' AND is_nullable = 'YES'
  ) THEN RAISE EXCEPTION 'journal attachment object metadata is missing'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_indexes
    WHERE schemaname = 'app' AND indexname = 'journal_attachment_pending_storage_idx'
  ) THEN RAISE EXCEPTION 'pending attachment recovery index is missing'; END IF;
END $$;

ROLLBACK;
