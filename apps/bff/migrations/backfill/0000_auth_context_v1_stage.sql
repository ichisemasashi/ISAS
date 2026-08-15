\set ON_ERROR_STOP on

BEGIN;
CREATE SCHEMA migration_stage;
REVOKE ALL ON SCHEMA migration_stage FROM PUBLIC;

CREATE TABLE migration_stage.auth_user (
  user_id uuid PRIMARY KEY,
  issuer text NOT NULL,
  subject text NOT NULL,
  display_name text NOT NULL,
  status text NOT NULL CHECK (status IN ('active', 'suspended', 'revoked')),
  UNIQUE (issuer, subject)
);
CREATE TABLE migration_stage.auth_membership (
  tenant_id uuid NOT NULL,
  user_id uuid NOT NULL,
  role_key text NOT NULL,
  status text NOT NULL CHECK (status IN ('active', 'suspended', 'revoked')),
  membership_version bigint NOT NULL CHECK (membership_version > 0),
  valid_from timestamptz NOT NULL,
  valid_until timestamptz,
  PRIMARY KEY (tenant_id, user_id),
  CHECK (valid_until IS NULL OR valid_until > valid_from)
);
CREATE TABLE migration_stage.auth_scope (
  tenant_id uuid NOT NULL,
  user_id uuid NOT NULL,
  field_group_id uuid NOT NULL,
  PRIMARY KEY (tenant_id, user_id, field_group_id)
);
COMMIT;

\echo 'Load reviewed CSV data with psql \\copy into migration_stage, then run 0000_auth_context_v1_backfill.sql.'
