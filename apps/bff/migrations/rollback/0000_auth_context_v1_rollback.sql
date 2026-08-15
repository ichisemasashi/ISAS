\set ON_ERROR_STOP on

BEGIN;

-- This rollback is intentionally safe-only. Once business migrations or real
-- identities exist, restore/roll-forward is required instead of dropping auth.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM pg_class class
    JOIN pg_namespace namespace ON namespace.oid = class.relnamespace
    WHERE namespace.nspname = 'app' AND class.relkind IN ('r', 'p')
  ) THEN RAISE EXCEPTION 'refusing AuthContext rollback: dependent app tables exist'; END IF;
  IF EXISTS (SELECT 1 FROM priv.auth_user) THEN
    RAISE EXCEPTION 'refusing AuthContext rollback: persistent users exist';
  END IF;
END $$;

DROP SCHEMA app_private CASCADE;
DROP TABLE priv.auth_change_audit CASCADE;
DROP TABLE priv.auth_revocation_event CASCADE;
DROP TABLE priv.auth_employer_delegate CASCADE;
DROP TABLE priv.auth_tenant_relation CASCADE;
DROP TABLE priv.auth_membership_field_group CASCADE;
DROP TABLE priv.auth_membership CASCADE;
DROP TABLE priv.auth_role_capability CASCADE;
DROP TABLE priv.auth_role CASCADE;
DROP TABLE priv.auth_user CASCADE;
DROP SEQUENCE priv.auth_authorization_version_seq;

COMMIT;
