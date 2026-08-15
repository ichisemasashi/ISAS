\set ON_ERROR_STOP on

-- Contract: the migration operator creates and validates these staging tables
-- in schema migration_stage before running this script. They are populated from
-- a法域内 export and are dropped at the end so identity data is not retained.
--   migration_stage.auth_user(user_id, issuer, subject, display_name, status)
--   migration_stage.auth_membership(tenant_id, user_id, role_key, status,
--     membership_version, valid_from, valid_until)
--   migration_stage.auth_scope(tenant_id, user_id, field_group_id)

BEGIN;

DO $$
DECLARE required_table text;
BEGIN
  FOREACH required_table IN ARRAY ARRAY['auth_user', 'auth_membership', 'auth_scope'] LOOP
    IF to_regclass('migration_stage.' || required_table) IS NULL THEN
      RAISE EXCEPTION 'required backfill staging table migration_stage.% is missing', required_table;
    END IF;
  END LOOP;
  IF EXISTS (
    SELECT issuer, subject FROM migration_stage.auth_user
    GROUP BY issuer, subject HAVING count(*) > 1
  ) THEN RAISE EXCEPTION 'duplicate issuer/subject in auth_user staging'; END IF;
  IF EXISTS (
    SELECT 1 FROM migration_stage.auth_membership membership
    LEFT JOIN migration_stage.auth_user auth_user USING (user_id)
    WHERE auth_user.user_id IS NULL
  ) THEN RAISE EXCEPTION 'membership staging references an unknown user'; END IF;
END $$;

GRANT USAGE ON SCHEMA migration_stage TO auth_context_owner;
GRANT SELECT ON ALL TABLES IN SCHEMA migration_stage TO auth_context_owner;

SET ROLE auth_context_owner;

INSERT INTO priv.auth_user (user_id, issuer, subject, display_name, status)
SELECT user_id, issuer, subject, display_name, status
FROM migration_stage.auth_user
ON CONFLICT (user_id) DO UPDATE
SET issuer = EXCLUDED.issuer,
    subject = EXCLUDED.subject,
    display_name = EXCLUDED.display_name,
    status = EXCLUDED.status,
    updated_at = clock_timestamp();

INSERT INTO priv.auth_membership
  (tenant_id, user_id, role_key, status, membership_version, valid_from, valid_until)
SELECT tenant_id, user_id, role_key, status, membership_version, valid_from, valid_until
FROM migration_stage.auth_membership
ON CONFLICT (tenant_id, user_id) DO UPDATE
SET role_key = EXCLUDED.role_key,
    status = EXCLUDED.status,
    membership_version = GREATEST(priv.auth_membership.membership_version, EXCLUDED.membership_version),
    valid_from = EXCLUDED.valid_from,
    valid_until = EXCLUDED.valid_until,
    updated_at = clock_timestamp();

INSERT INTO priv.auth_membership_field_group (tenant_id, user_id, field_group_id)
SELECT tenant_id, user_id, field_group_id
FROM migration_stage.auth_scope
ON CONFLICT DO NOTHING;

DO $$
DECLARE target_user uuid;
BEGIN
  FOR target_user IN SELECT user_id FROM migration_stage.auth_user LOOP
    PERFORM app_private.bump_authorization_version(
      target_user, NULL, 'backfill.completed', jsonb_build_object('source', 'migration_stage')
    );
  END LOOP;
END $$;

RESET ROLE;

DROP SCHEMA migration_stage CASCADE;
COMMIT;
