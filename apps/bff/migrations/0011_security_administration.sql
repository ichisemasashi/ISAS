\set ON_ERROR_STOP on

BEGIN;

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

GRANT USAGE, CREATE ON SCHEMA priv TO auth_context_owner;
SET ROLE auth_context_owner;

ALTER TABLE priv.auth_role_capability DROP CONSTRAINT auth_role_capability_capability_check;
ALTER TABLE priv.auth_role_capability ADD CONSTRAINT auth_role_capability_capability_check
  CHECK (capability IN (
    'view_others_tracks', 'view_others_punch', 'scope_all',
    'journal:write', 'pesticide:write', 'punch:write', 'conflict:resolve',
    'instruction:manage', 'journal:review',
    'pesticide:manage', 'pesticide:override', 'inventory:write', 'inventory:adjust',
    'migration:manage', 'export:read',
    'security:manage', 'privacy:manage', 'break_glass:approve'
  ));

INSERT INTO priv.auth_role_capability (role_key, capability)
SELECT role_key, capability
FROM (VALUES
  ('group_admin', 'security:manage'),
  ('group_admin', 'privacy:manage'),
  ('group_admin', 'break_glass:approve')
) AS seed(role_key, capability)
WHERE EXISTS (SELECT 1 FROM priv.auth_role role WHERE role.role_key = seed.role_key)
ON CONFLICT DO NOTHING;

CREATE TABLE priv.auth_admin_change_request (
  request_id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  change_type text NOT NULL CHECK (change_type IN ('user_register', 'user_change', 'user_revoke', 'break_glass')),
  target_user_id uuid NOT NULL,
  requested_by uuid NOT NULL REFERENCES priv.auth_user(user_id),
  requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  request_expires_at timestamptz NOT NULL DEFAULT (clock_timestamp() + interval '24 hours'),
  reason text NOT NULL CHECK (length(reason) BETWEEN 10 AND 1000),
  ticket_ref text NOT NULL CHECK (length(ticket_ref) BETWEEN 1 AND 200),
  before_state jsonb CHECK (before_state IS NULL OR jsonb_typeof(before_state) = 'object'),
  proposed_state jsonb NOT NULL CHECK (jsonb_typeof(proposed_state) = 'object'),
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'executed', 'rejected', 'expired')),
  decided_by uuid REFERENCES priv.auth_user(user_id),
  decided_at timestamptz,
  decision_note text,
  executed_at timestamptz,
  CHECK (decided_by IS NULL OR decided_by <> requested_by)
);

CREATE TABLE priv.auth_break_glass_grant (
  grant_id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  user_id uuid NOT NULL REFERENCES priv.auth_user(user_id),
  capabilities text[] NOT NULL CHECK (cardinality(capabilities) BETWEEN 1 AND 20),
  valid_from timestamptz NOT NULL DEFAULT clock_timestamp(),
  valid_until timestamptz NOT NULL,
  reason text NOT NULL CHECK (length(reason) BETWEEN 10 AND 1000),
  ticket_ref text NOT NULL CHECK (length(ticket_ref) BETWEEN 1 AND 200),
  request_id uuid NOT NULL UNIQUE REFERENCES priv.auth_admin_change_request(request_id),
  issued_by uuid NOT NULL REFERENCES priv.auth_user(user_id),
  approved_by uuid NOT NULL REFERENCES priv.auth_user(user_id),
  revoked_at timestamptz,
  revoked_by uuid REFERENCES priv.auth_user(user_id),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CHECK (valid_until > valid_from),
  CHECK (approved_by <> issued_by)
);

CREATE TABLE priv.privacy_request (
  request_id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  subject_user_id uuid REFERENCES priv.auth_user(user_id),
  request_type text NOT NULL CHECK (request_type IN ('disclosure', 'correction', 'deletion')),
  status text NOT NULL DEFAULT 'submitted' CHECK (status IN (
    'submitted', 'approved', 'rejected', 'in_progress', 'completed', 'blocked_legal_hold'
  )),
  details jsonb NOT NULL CHECK (jsonb_typeof(details) = 'object'),
  legal_hold boolean NOT NULL DEFAULT false,
  due_at timestamptz NOT NULL,
  requested_by uuid NOT NULL REFERENCES priv.auth_user(user_id),
  requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  approved_by uuid REFERENCES priv.auth_user(user_id),
  approved_at timestamptz,
  completed_at timestamptz,
  evidence_ref text,
  CHECK (approved_by IS NULL OR approved_by <> requested_by)
);

CREATE TABLE priv.privacy_request_event (
  event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  request_id uuid NOT NULL REFERENCES priv.privacy_request(request_id),
  tenant_id uuid NOT NULL,
  from_status text,
  to_status text NOT NULL,
  note text NOT NULL CHECK (length(note) BETWEEN 1 AND 2000),
  evidence_ref text,
  actor_user_id uuid NOT NULL REFERENCES priv.auth_user(user_id),
  occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX auth_admin_change_pending_idx
  ON priv.auth_admin_change_request (tenant_id, requested_at, request_id) WHERE status = 'pending';
CREATE INDEX auth_break_glass_active_idx
  ON priv.auth_break_glass_grant (tenant_id, user_id, valid_until) WHERE revoked_at IS NULL;
CREATE INDEX privacy_request_status_idx
  ON priv.privacy_request (tenant_id, status, due_at, request_id);
CREATE INDEX privacy_request_event_idx
  ON priv.privacy_request_event (request_id, occurred_at, event_id);

DO $$
DECLARE table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'auth_admin_change_request', 'auth_break_glass_grant',
    'privacy_request', 'privacy_request_event'
  ] LOOP
    EXECUTE format('ALTER TABLE priv.%I OWNER TO auth_context_owner', table_name);
    EXECUTE format('ALTER TABLE priv.%I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE priv.%I FORCE ROW LEVEL SECURITY', table_name);
    EXECUTE format(
      'CREATE POLICY auth_context_owner_only ON priv.%I AS PERMISSIVE FOR ALL TO auth_context_owner USING (true) WITH CHECK (true)',
      table_name
    );
    IF table_name <> 'privacy_request_event' THEN
      EXECUTE format(
        'CREATE TRIGGER z_auth_change_audit AFTER INSERT OR UPDATE OR DELETE ON priv.%I FOR EACH ROW EXECUTE FUNCTION app_private.audit_auth_change()',
        table_name
      );
    END IF;
  END LOOP;
END $$;

CREATE TRIGGER auth_break_glass_version
AFTER INSERT OR UPDATE OR DELETE ON priv.auth_break_glass_grant
FOR EACH ROW EXECUTE FUNCTION app_private.auth_subject_version_trigger();

CREATE FUNCTION app_private.permanent_capability(p_user_id uuid, p_tenant_id uuid, p_capability text)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
  SELECT EXISTS (
    SELECT 1
      FROM priv.auth_user u
      JOIN priv.auth_membership m USING (user_id)
      JOIN priv.auth_role_capability c USING (role_key)
     WHERE u.user_id = p_user_id
       AND m.tenant_id = p_tenant_id
       AND u.status = 'active'
       AND m.status = 'active'
       AND m.valid_from <= statement_timestamp()
       AND (m.valid_until IS NULL OR m.valid_until > statement_timestamp())
       AND c.capability = p_capability
  )
$$;

CREATE FUNCTION app_private.auth_subject_snapshot(p_user_id uuid, p_tenant_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
  SELECT CASE WHEN u.user_id IS NULL THEN NULL ELSE jsonb_build_object(
    'userId', u.user_id,
    'issuer', u.issuer,
    'subject', u.subject,
    'displayName', u.display_name,
    'userStatus', u.status,
    'authorizationVersion', u.authorization_version,
    'tenantId', m.tenant_id,
    'roleKey', m.role_key,
    'membershipStatus', m.status,
    'membershipVersion', m.membership_version,
    'validFrom', m.valid_from,
    'validUntil', m.valid_until,
    'fieldGroupIds', COALESCE((
      SELECT jsonb_agg(s.field_group_id ORDER BY s.field_group_id)
        FROM priv.auth_membership_field_group s
       WHERE s.tenant_id = p_tenant_id AND s.user_id = p_user_id
    ), '[]'::jsonb)
  ) END
  FROM priv.auth_user u
  LEFT JOIN priv.auth_membership m ON m.user_id = u.user_id AND m.tenant_id = p_tenant_id
  WHERE u.user_id = p_user_id
$$;

CREATE FUNCTION app_private.create_security_change_request(
  p_request_id uuid,
  p_actor_user_id uuid,
  p_tenant_id uuid,
  p_change_type text,
  p_target_user_id uuid,
  p_reason text,
  p_ticket_ref text,
  p_proposed_state jsonb
) RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
DECLARE before_value jsonb;
BEGIN
  IF NOT app_private.permanent_capability(p_actor_user_id, p_tenant_id, 'security:manage') THEN
    RAISE EXCEPTION 'security administration denied' USING ERRCODE = '42501';
  END IF;
  IF p_request_id IS NULL OR p_target_user_id IS NULL
     OR p_change_type NOT IN ('user_register', 'user_change', 'user_revoke', 'break_glass')
     OR length(trim(COALESCE(p_reason, ''))) < 10
     OR length(trim(COALESCE(p_ticket_ref, ''))) < 1
     OR jsonb_typeof(p_proposed_state) <> 'object' THEN
    RAISE EXCEPTION 'invalid security change request' USING ERRCODE = '22023';
  END IF;
  before_value := app_private.auth_subject_snapshot(p_target_user_id, p_tenant_id);
  IF p_change_type = 'user_register' AND before_value->>'tenantId' IS NOT NULL THEN
    RAISE EXCEPTION 'tenant membership already exists' USING ERRCODE = '23505';
  ELSIF p_change_type = 'user_register' AND before_value IS NOT NULL
        AND (before_value->>'issuer' <> p_proposed_state->>'issuer'
             OR before_value->>'subject' <> p_proposed_state->>'subject') THEN
    RAISE EXCEPTION 'existing user identity does not match' USING ERRCODE = '22023';
  ELSIF p_change_type <> 'user_register'
        AND (before_value IS NULL OR before_value->>'tenantId' IS NULL) THEN
    RAISE EXCEPTION 'target user does not exist' USING ERRCODE = '22023';
  END IF;
  INSERT INTO priv.auth_admin_change_request
    (request_id, tenant_id, change_type, target_user_id, requested_by,
     reason, ticket_ref, before_state, proposed_state)
  VALUES
    (p_request_id, p_tenant_id, p_change_type, p_target_user_id, p_actor_user_id,
     trim(p_reason), trim(p_ticket_ref), before_value, p_proposed_state);
  RETURN jsonb_build_object('requestId', p_request_id, 'status', 'pending');
END $$;

CREATE FUNCTION app_private.decide_security_change_request(
  p_request_id uuid,
  p_actor_user_id uuid,
  p_approve boolean,
  p_note text
) RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
DECLARE request_row priv.auth_admin_change_request%ROWTYPE;
DECLARE scope_value text;
DECLARE allowed_break_glass text[] := ARRAY[
  'security:manage', 'privacy:manage', 'pesticide:manage', 'inventory:adjust',
  'export:read', 'conflict:resolve', 'journal:review', 'instruction:manage'
];
DECLARE requested_caps text[];
DECLARE grant_until timestamptz;
BEGIN
  SELECT * INTO request_row FROM priv.auth_admin_change_request
   WHERE request_id = p_request_id FOR UPDATE;
  IF NOT FOUND OR request_row.status <> 'pending' OR request_row.request_expires_at <= statement_timestamp() THEN
    RAISE EXCEPTION 'security change request is not pending' USING ERRCODE = '22023';
  END IF;
  IF request_row.requested_by = p_actor_user_id THEN
    RAISE EXCEPTION 'requester cannot approve own change' USING ERRCODE = '42501';
  END IF;
  IF request_row.change_type = 'break_glass' THEN
    IF NOT app_private.permanent_capability(p_actor_user_id, request_row.tenant_id, 'break_glass:approve') THEN
      RAISE EXCEPTION 'break-glass approval denied' USING ERRCODE = '42501';
    END IF;
  ELSIF NOT app_private.permanent_capability(p_actor_user_id, request_row.tenant_id, 'security:manage') THEN
    RAISE EXCEPTION 'security approval denied' USING ERRCODE = '42501';
  END IF;
  IF length(trim(COALESCE(p_note, ''))) < 1 THEN
    RAISE EXCEPTION 'decision note is required' USING ERRCODE = '22023';
  END IF;

  IF NOT p_approve THEN
    UPDATE priv.auth_admin_change_request
       SET status = 'rejected', decided_by = p_actor_user_id,
           decided_at = clock_timestamp(), decision_note = trim(p_note)
     WHERE request_id = p_request_id;
    RETURN jsonb_build_object('requestId', p_request_id, 'status', 'rejected');
  END IF;

  IF request_row.change_type = 'user_register' THEN
    INSERT INTO priv.auth_user (user_id, issuer, subject, display_name)
    VALUES (
      request_row.target_user_id,
      request_row.proposed_state->>'issuer',
      request_row.proposed_state->>'subject',
      request_row.proposed_state->>'displayName'
    ) ON CONFLICT (user_id) DO UPDATE
      SET display_name = EXCLUDED.display_name, updated_at = clock_timestamp()
      WHERE auth_user.issuer = EXCLUDED.issuer AND auth_user.subject = EXCLUDED.subject;
    INSERT INTO priv.auth_membership
      (tenant_id, user_id, role_key, valid_from, valid_until)
    VALUES (
      request_row.tenant_id,
      request_row.target_user_id,
      request_row.proposed_state->>'roleKey',
      COALESCE((request_row.proposed_state->>'validFrom')::timestamptz, clock_timestamp()),
      (request_row.proposed_state->>'validUntil')::timestamptz
    );
  ELSIF request_row.change_type = 'user_change' THEN
    UPDATE priv.auth_user
       SET display_name = COALESCE(NULLIF(request_row.proposed_state->>'displayName', ''), display_name),
           updated_at = clock_timestamp()
     WHERE user_id = request_row.target_user_id;
    UPDATE priv.auth_membership
       SET role_key = COALESCE(NULLIF(request_row.proposed_state->>'roleKey', ''), role_key),
           status = COALESCE(NULLIF(request_row.proposed_state->>'membershipStatus', ''), status),
           valid_from = COALESCE((request_row.proposed_state->>'validFrom')::timestamptz, valid_from),
           valid_until = CASE WHEN request_row.proposed_state ? 'validUntil'
                              THEN (request_row.proposed_state->>'validUntil')::timestamptz ELSE valid_until END
     WHERE tenant_id = request_row.tenant_id AND user_id = request_row.target_user_id;
  ELSIF request_row.change_type = 'user_revoke' THEN
    UPDATE priv.auth_membership
       SET status = 'revoked',
           valid_until = GREATEST(valid_from + interval '1 microsecond',
             LEAST(COALESCE(valid_until, clock_timestamp()), clock_timestamp()))
     WHERE tenant_id = request_row.tenant_id AND user_id = request_row.target_user_id;
  ELSE
    SELECT array_agg(value ORDER BY value) INTO requested_caps
      FROM jsonb_array_elements_text(request_row.proposed_state->'capabilities') value;
    grant_until := (request_row.proposed_state->>'validUntil')::timestamptz;
    IF requested_caps IS NULL OR NOT requested_caps <@ allowed_break_glass
       OR grant_until <= statement_timestamp() + interval '5 minutes'
       OR grant_until > statement_timestamp() + interval '1 hour' THEN
      RAISE EXCEPTION 'invalid break-glass scope or expiry' USING ERRCODE = '22023';
    END IF;
    INSERT INTO priv.auth_break_glass_grant
      (grant_id, tenant_id, user_id, capabilities, valid_until, reason,
       ticket_ref, request_id, issued_by, approved_by)
    VALUES (
      (request_row.proposed_state->>'grantId')::uuid,
      request_row.tenant_id,
      request_row.target_user_id,
      requested_caps,
      grant_until,
      request_row.reason,
      request_row.ticket_ref,
      request_row.request_id,
      request_row.requested_by,
      p_actor_user_id
    );
  END IF;

  IF request_row.change_type IN ('user_register', 'user_change')
     AND request_row.proposed_state ? 'fieldGroupIds' THEN
    DELETE FROM priv.auth_membership_field_group
     WHERE tenant_id = request_row.tenant_id AND user_id = request_row.target_user_id;
    FOR scope_value IN SELECT jsonb_array_elements_text(request_row.proposed_state->'fieldGroupIds') LOOP
      INSERT INTO priv.auth_membership_field_group (tenant_id, user_id, field_group_id)
      VALUES (request_row.tenant_id, request_row.target_user_id, scope_value::uuid);
    END LOOP;
  END IF;

  UPDATE priv.auth_admin_change_request
     SET status = 'executed', decided_by = p_actor_user_id,
         decided_at = clock_timestamp(), decision_note = trim(p_note),
         executed_at = clock_timestamp()
   WHERE request_id = p_request_id;
  RETURN jsonb_build_object(
    'requestId', p_request_id,
    'status', 'executed',
    'afterState', app_private.auth_subject_snapshot(request_row.target_user_id, request_row.tenant_id)
  );
END $$;

CREATE FUNCTION app_private.create_privacy_request(
  p_request_id uuid,
  p_actor_user_id uuid,
  p_tenant_id uuid,
  p_subject_user_id uuid,
  p_request_type text,
  p_details jsonb,
  p_due_at timestamptz,
  p_note text
) RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
BEGIN
  IF NOT app_private.permanent_capability(p_actor_user_id, p_tenant_id, 'privacy:manage') THEN
    RAISE EXCEPTION 'privacy administration denied' USING ERRCODE = '42501';
  END IF;
  IF p_request_id IS NULL OR p_request_type NOT IN ('disclosure', 'correction', 'deletion')
     OR jsonb_typeof(p_details) <> 'object' OR p_due_at <= statement_timestamp()
     OR length(trim(COALESCE(p_note, ''))) < 1 THEN
    RAISE EXCEPTION 'invalid privacy request' USING ERRCODE = '22023';
  END IF;
  INSERT INTO priv.privacy_request
    (request_id, tenant_id, subject_user_id, request_type, details, due_at, requested_by)
  VALUES (p_request_id, p_tenant_id, p_subject_user_id, p_request_type, p_details, p_due_at, p_actor_user_id);
  INSERT INTO priv.privacy_request_event
    (request_id, tenant_id, to_status, note, actor_user_id)
  VALUES (p_request_id, p_tenant_id, 'submitted', trim(p_note), p_actor_user_id);
  RETURN jsonb_build_object('requestId', p_request_id, 'status', 'submitted');
END $$;

CREATE FUNCTION app_private.transition_privacy_request(
  p_request_id uuid,
  p_actor_user_id uuid,
  p_action text,
  p_note text,
  p_evidence_ref text DEFAULT NULL
) RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
DECLARE request_row priv.privacy_request%ROWTYPE;
DECLARE next_status text;
BEGIN
  SELECT * INTO request_row FROM priv.privacy_request WHERE request_id = p_request_id FOR UPDATE;
  IF NOT FOUND OR NOT app_private.permanent_capability(p_actor_user_id, request_row.tenant_id, 'privacy:manage') THEN
    RAISE EXCEPTION 'privacy transition denied' USING ERRCODE = '42501';
  END IF;
  IF length(trim(COALESCE(p_note, ''))) < 1 THEN
    RAISE EXCEPTION 'privacy transition note is required' USING ERRCODE = '22023';
  END IF;
  next_status := CASE
    WHEN p_action = 'approve' AND request_row.status = 'submitted' THEN 'approved'
    WHEN p_action = 'reject' AND request_row.status = 'submitted' THEN 'rejected'
    WHEN p_action = 'start' AND request_row.status = 'approved' THEN 'in_progress'
    WHEN p_action = 'complete' AND request_row.status = 'in_progress' THEN 'completed'
    WHEN p_action = 'block_legal_hold' AND request_row.status IN ('approved', 'in_progress') THEN 'blocked_legal_hold'
    ELSE NULL END;
  IF next_status IS NULL THEN RAISE EXCEPTION 'invalid privacy transition' USING ERRCODE = '22023'; END IF;
  IF p_action IN ('approve', 'reject') AND request_row.requested_by = p_actor_user_id THEN
    RAISE EXCEPTION 'requester cannot decide own privacy request' USING ERRCODE = '42501';
  END IF;
  IF p_action = 'complete' AND (length(trim(COALESCE(p_evidence_ref, ''))) < 1 OR request_row.legal_hold) THEN
    RAISE EXCEPTION 'completion evidence is required and legal hold must be clear' USING ERRCODE = '22023';
  END IF;
  UPDATE priv.privacy_request
     SET status = next_status,
         approved_by = CASE WHEN next_status = 'approved' THEN p_actor_user_id ELSE approved_by END,
         approved_at = CASE WHEN next_status = 'approved' THEN clock_timestamp() ELSE approved_at END,
         legal_hold = CASE WHEN next_status = 'blocked_legal_hold' THEN true ELSE legal_hold END,
         completed_at = CASE WHEN next_status = 'completed' THEN clock_timestamp() ELSE completed_at END,
         evidence_ref = COALESCE(NULLIF(trim(p_evidence_ref), ''), evidence_ref)
   WHERE request_id = p_request_id;
  INSERT INTO priv.privacy_request_event
    (request_id, tenant_id, from_status, to_status, note, evidence_ref, actor_user_id)
  VALUES (p_request_id, request_row.tenant_id, request_row.status, next_status,
          trim(p_note), NULLIF(trim(p_evidence_ref), ''), p_actor_user_id);
  RETURN jsonb_build_object('requestId', p_request_id, 'status', next_status);
END $$;

CREATE OR REPLACE FUNCTION app_private.derive_authorization_context(p_user_id uuid, p_tenant_id uuid)
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
      SELECT array_agg(DISTINCT source.capability ORDER BY source.capability) AS capabilities
      FROM (
        SELECT c.capability
          FROM priv.auth_role_capability c
         WHERE c.role_key = m.role_key
        UNION ALL
        SELECT unnest(g.capabilities)
          FROM priv.auth_break_glass_grant g
         WHERE g.tenant_id = m.tenant_id AND g.user_id = m.user_id
           AND g.revoked_at IS NULL
           AND g.valid_from <= statement_timestamp()
           AND g.valid_until > statement_timestamp()
      ) source
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

CREATE FUNCTION app_private.security_admin_snapshot(p_actor_user_id uuid, p_tenant_id uuid)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = pg_catalog, priv AS $$
DECLARE result jsonb;
BEGIN
  IF NOT (
    app_private.permanent_capability(p_actor_user_id, p_tenant_id, 'security:manage')
    OR app_private.permanent_capability(p_actor_user_id, p_tenant_id, 'privacy:manage')
    OR app_private.permanent_capability(p_actor_user_id, p_tenant_id, 'break_glass:approve')
  ) THEN RAISE EXCEPTION 'security snapshot denied' USING ERRCODE = '42501'; END IF;
  SELECT jsonb_build_object(
    'users', COALESCE((SELECT jsonb_agg(app_private.auth_subject_snapshot(m.user_id, p_tenant_id) ORDER BY u.display_name)
      FROM priv.auth_membership m JOIN priv.auth_user u USING (user_id)
      WHERE m.tenant_id = p_tenant_id), '[]'::jsonb),
    'roles', COALESCE((SELECT jsonb_agg(jsonb_build_object(
      'roleKey', r.role_key, 'roleLabel', r.role_label,
      'capabilities', COALESCE((SELECT jsonb_agg(c.capability ORDER BY c.capability)
        FROM priv.auth_role_capability c WHERE c.role_key = r.role_key), '[]'::jsonb)
    ) ORDER BY r.role_key) FROM priv.auth_role r), '[]'::jsonb),
    'changeRequests', COALESCE((SELECT jsonb_agg(to_jsonb(q) ORDER BY q.requested_at DESC)
      FROM priv.auth_admin_change_request q WHERE q.tenant_id = p_tenant_id), '[]'::jsonb),
    'breakGlassGrants', COALESCE((SELECT jsonb_agg(to_jsonb(g) ORDER BY g.created_at DESC)
      FROM priv.auth_break_glass_grant g WHERE g.tenant_id = p_tenant_id), '[]'::jsonb),
    'privacyRequests', COALESCE((SELECT jsonb_agg(to_jsonb(p) || jsonb_build_object(
      'events', COALESCE((SELECT jsonb_agg(to_jsonb(e) ORDER BY e.event_id)
        FROM priv.privacy_request_event e WHERE e.request_id = p.request_id), '[]'::jsonb)
    ) ORDER BY p.requested_at DESC) FROM priv.privacy_request p WHERE p.tenant_id = p_tenant_id), '[]'::jsonb)
  ) INTO result;
  RETURN result;
END $$;

REVOKE ALL ON FUNCTION app_private.permanent_capability(uuid,uuid,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.auth_subject_snapshot(uuid,uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.create_security_change_request(uuid,uuid,uuid,text,uuid,text,text,jsonb) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.decide_security_change_request(uuid,uuid,boolean,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.create_privacy_request(uuid,uuid,uuid,uuid,text,jsonb,timestamptz,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.transition_privacy_request(uuid,uuid,text,text,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_private.security_admin_snapshot(uuid,uuid) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION app_private.create_security_change_request(uuid,uuid,uuid,text,uuid,text,text,jsonb) TO auth_role;
GRANT EXECUTE ON FUNCTION app_private.decide_security_change_request(uuid,uuid,boolean,text) TO auth_role;
GRANT EXECUTE ON FUNCTION app_private.create_privacy_request(uuid,uuid,uuid,uuid,text,jsonb,timestamptz,text) TO auth_role;
GRANT EXECUTE ON FUNCTION app_private.transition_privacy_request(uuid,uuid,text,text,text) TO auth_role;
GRANT EXECUTE ON FUNCTION app_private.security_admin_snapshot(uuid,uuid) TO auth_role;

RESET ROLE;
REVOKE CREATE ON SCHEMA priv FROM auth_context_owner;

SET ROLE app_owner;

CREATE TABLE app.pesticide_master_review_request (
  tenant_id uuid NOT NULL,
  request_id uuid NOT NULL,
  proposed_release jsonb NOT NULL CHECK (jsonb_typeof(proposed_release) = 'object'),
  before_release jsonb,
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'published', 'rejected')),
  reason text NOT NULL CHECK (length(reason) BETWEEN 10 AND 1000),
  ticket_ref text NOT NULL CHECK (length(ticket_ref) BETWEEN 1 AND 200),
  requested_by uuid NOT NULL,
  requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  decided_by uuid,
  decided_at timestamptz,
  decision_note text,
  release_id uuid,
  PRIMARY KEY (tenant_id, request_id),
  CHECK (decided_by IS NULL OR decided_by <> requested_by)
);

CREATE INDEX pesticide_review_pending_idx
  ON app.pesticide_master_review_request (tenant_id, requested_at, request_id) WHERE status = 'pending';

ALTER TABLE app.pesticide_master_review_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.pesticide_master_review_request FORCE ROW LEVEL SECURITY;
CREATE POLICY pesticide_review_read ON app.pesticide_master_review_request FOR SELECT TO app_user
  USING (tenant_id = app.current_tenant_id() AND app.has_capability('pesticide:manage'));
CREATE POLICY pesticide_review_insert ON app.pesticide_master_review_request FOR INSERT TO app_user
  WITH CHECK (tenant_id = app.current_tenant_id() AND requested_by = app.current_user_id()
    AND app.has_capability('pesticide:manage'));
CREATE POLICY pesticide_review_update ON app.pesticide_master_review_request FOR UPDATE TO app_user
  USING (tenant_id = app.current_tenant_id() AND app.has_capability('pesticide:manage'))
  WITH CHECK (tenant_id = app.current_tenant_id() AND app.has_capability('pesticide:manage'));
GRANT SELECT, INSERT, UPDATE ON app.pesticide_master_review_request TO app_user;

RESET ROLE;

COMMIT;
