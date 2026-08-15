BEGIN;

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

GRANT USAGE ON SCHEMA app_private TO auth_role;
GRANT USAGE, CREATE ON SCHEMA priv TO auth_context_owner;
SET ROLE auth_context_owner;

ALTER TABLE priv.auth_revocation_event
  ADD COLUMN delivery_claim_id uuid,
  ADD COLUMN delivery_claimed_until timestamptz;

CREATE INDEX auth_revocation_claimable_idx
  ON priv.auth_revocation_event (delivery_claimed_until, event_id)
  WHERE delivered_at IS NULL;

CREATE FUNCTION app_private.resolve_oidc_user(p_issuer text, p_subject text)
RETURNS TABLE (user_id uuid, display_name text, authorization_version bigint)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
  SELECT u.user_id, u.display_name, u.authorization_version
    FROM priv.auth_user u
   WHERE u.issuer = p_issuer
     AND u.subject = p_subject
     AND u.status = 'active'
$$;

CREATE FUNCTION app_private.list_authorized_tenants(p_user_id uuid)
RETURNS TABLE (tenant_id text, role_label text, membership_version bigint, authorization_version bigint)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
  SELECT m.tenant_id::text, r.role_label, m.membership_version, u.authorization_version
    FROM priv.auth_user u
    JOIN priv.auth_membership m USING (user_id)
    JOIN priv.auth_role r USING (role_key)
   WHERE u.user_id = p_user_id
     AND u.status = 'active'
     AND m.status = 'active'
     AND m.valid_from <= statement_timestamp()
     AND (m.valid_until IS NULL OR m.valid_until > statement_timestamp())
   ORDER BY m.tenant_id
$$;

CREATE FUNCTION app_private.derive_authorization_context(p_user_id uuid, p_tenant_id uuid)
RETURNS TABLE (
  user_id uuid,
  tenant_id text,
  role_label text,
  membership_version bigint,
  authorization_version bigint,
  scope_field_groups uuid[],
  capabilities text[]
) LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
  SELECT u.user_id,
         m.tenant_id::text,
         r.role_label,
         m.membership_version,
         u.authorization_version,
         CASE WHEN 'scope_all' = ANY(COALESCE(cap.capabilities, '{}'::text[]))
              THEN '{}'::uuid[]
              ELSE COALESCE(scope.field_groups, '{}'::uuid[]) END,
         COALESCE(cap.capabilities, '{}'::text[])
    FROM priv.auth_user u
    JOIN priv.auth_membership m USING (user_id)
    JOIN priv.auth_role r USING (role_key)
    LEFT JOIN LATERAL (
      SELECT array_agg(c.capability ORDER BY c.capability) AS capabilities
        FROM priv.auth_role_capability c
       WHERE c.role_key = m.role_key
    ) cap ON true
    LEFT JOIN LATERAL (
      SELECT array_agg(s.field_group_id ORDER BY s.field_group_id) AS field_groups
        FROM priv.auth_membership_field_group s
       WHERE s.tenant_id = m.tenant_id AND s.user_id = m.user_id
    ) scope ON true
   WHERE u.user_id = p_user_id
     AND m.tenant_id = p_tenant_id
     AND u.status = 'active'
     AND m.status = 'active'
     AND m.valid_from <= statement_timestamp()
     AND (m.valid_until IS NULL OR m.valid_until > statement_timestamp())
$$;

CREATE FUNCTION app_private.claim_auth_revocation(p_claim_id uuid, p_lease_seconds integer)
RETURNS TABLE (
  event_id bigint,
  user_id uuid,
  tenant_id uuid,
  authorization_version bigint,
  reason text,
  occurred_at timestamptz
) LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
BEGIN
  IF p_claim_id IS NULL OR p_lease_seconds < 5 OR p_lease_seconds > 300 THEN RETURN; END IF;
  RETURN QUERY
  WITH candidate AS (
    SELECT e.event_id
      FROM priv.auth_revocation_event e
     WHERE e.delivered_at IS NULL
       AND (e.delivery_claimed_until IS NULL OR e.delivery_claimed_until < statement_timestamp())
     ORDER BY e.event_id
     FOR UPDATE SKIP LOCKED
     LIMIT 1
  ), claimed AS (
    UPDATE priv.auth_revocation_event e
       SET delivery_claim_id = p_claim_id,
           delivery_claimed_until = statement_timestamp() + make_interval(secs => p_lease_seconds),
           delivery_attempts = e.delivery_attempts + 1
      FROM candidate
     WHERE e.event_id = candidate.event_id
     RETURNING e.*
  )
  SELECT c.event_id, c.user_id, c.tenant_id, c.authorization_version, c.reason, c.occurred_at FROM claimed c;
END $$;

CREATE FUNCTION app_private.complete_auth_revocation(p_event_id bigint, p_claim_id uuid)
RETURNS boolean LANGUAGE sql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
  WITH completed AS (
    UPDATE priv.auth_revocation_event
       SET delivered_at = COALESCE(delivered_at, clock_timestamp()),
           delivery_claim_id = NULL,
           delivery_claimed_until = NULL
     WHERE event_id = p_event_id
       AND delivery_claim_id = p_claim_id
     RETURNING 1
  ) SELECT EXISTS (SELECT 1 FROM completed)
$$;

CREATE FUNCTION app_private.release_auth_revocation(p_event_id bigint, p_claim_id uuid)
RETURNS boolean LANGUAGE sql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
  WITH released AS (
    UPDATE priv.auth_revocation_event
       SET delivery_claim_id = NULL,
           delivery_claimed_until = NULL
     WHERE event_id = p_event_id
       AND delivered_at IS NULL
       AND delivery_claim_id = p_claim_id
     RETURNING 1
  ) SELECT EXISTS (SELECT 1 FROM released)
$$;

REVOKE ALL ON FUNCTION app_private.resolve_oidc_user(text,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.list_authorized_tenants(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.derive_authorization_context(uuid,uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.claim_auth_revocation(uuid,integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.complete_auth_revocation(bigint,uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.release_auth_revocation(bigint,uuid) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION app_private.resolve_oidc_user(text,text) TO auth_role;
GRANT EXECUTE ON FUNCTION app_private.list_authorized_tenants(uuid) TO auth_role;
GRANT EXECUTE ON FUNCTION app_private.derive_authorization_context(uuid,uuid) TO auth_role;
GRANT EXECUTE ON FUNCTION app_private.claim_auth_revocation(uuid,integer) TO auth_role;
GRANT EXECUTE ON FUNCTION app_private.complete_auth_revocation(bigint,uuid) TO auth_role;
GRANT EXECUTE ON FUNCTION app_private.release_auth_revocation(bigint,uuid) TO auth_role;

RESET ROLE;
REVOKE CREATE ON SCHEMA priv FROM auth_context_owner;

COMMIT;
