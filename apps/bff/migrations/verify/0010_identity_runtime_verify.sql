\set ON_ERROR_STOP on

BEGIN;
SELECT set_config('app.actor_pseudonym', 'verify-identity-runtime', true);

INSERT INTO priv.auth_role_capability (role_key, capability)
VALUES ('worker', 'journal:write')
ON CONFLICT DO NOTHING;

INSERT INTO priv.auth_user (user_id, issuer, subject, display_name)
VALUES ('a0100000-0000-7000-8000-000000000001', 'https://issuer.verify.example/pool', 'subject-0010', '検証利用者');

INSERT INTO priv.auth_membership (tenant_id, user_id, role_key)
VALUES ('a0100000-0000-7000-8000-000000000002', 'a0100000-0000-7000-8000-000000000001', 'worker');

SET LOCAL ROLE auth_role;

DO $verify$
DECLARE
  identity_count integer;
  tenant_count integer;
  context_count integer;
  claimed record;
  completed boolean;
BEGIN
  SELECT count(*) INTO identity_count
    FROM app_private.resolve_oidc_user('https://issuer.verify.example/pool', 'subject-0010');
  IF identity_count <> 1 THEN RAISE EXCEPTION '0010 identity resolution failed'; END IF;

  SELECT count(*) INTO tenant_count
    FROM app_private.list_authorized_tenants('a0100000-0000-7000-8000-000000000001');
  IF tenant_count <> 1 THEN RAISE EXCEPTION '0010 tenant listing failed'; END IF;

  SELECT count(*) INTO context_count
    FROM app_private.derive_authorization_context(
      'a0100000-0000-7000-8000-000000000001',
      'a0100000-0000-7000-8000-000000000002'
    );
  IF context_count <> 1 THEN RAISE EXCEPTION '0010 context derivation failed'; END IF;

  SELECT * INTO claimed FROM app_private.claim_auth_revocation('a0100000-0000-7000-8000-000000000003', 30);
  IF claimed.event_id IS NULL THEN RAISE EXCEPTION '0010 revocation claim failed'; END IF;
  SELECT app_private.complete_auth_revocation(claimed.event_id, 'a0100000-0000-7000-8000-000000000003') INTO completed;
  IF NOT completed THEN RAISE EXCEPTION '0010 revocation completion failed'; END IF;
END
$verify$;

RESET ROLE;
ROLLBACK;

\echo '0010 identity runtime verification: PASS'
