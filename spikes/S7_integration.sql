-- S7 integrated load backing store: real PostgreSQL, FORCE RLS and idempotent push/pull.
\set ON_ERROR_STOP on
SET client_min_messages = NOTICE;

SET ROLE app_owner;
CREATE TABLE s7_event_receipt (
  tenant_id uuid NOT NULL,
  event_uuid uuid NOT NULL,
  bundle_id text NOT NULL,
  server_seq bigint,
  received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, event_uuid)
);
CREATE TABLE s7_change (
  server_seq bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id uuid NOT NULL,
  event_uuid uuid NOT NULL,
  priority smallint NOT NULL CHECK (priority IN (0, 2)),
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE (tenant_id, event_uuid)
);
CREATE TABLE s7_attachment (
  tenant_id uuid NOT NULL,
  attachment_id uuid NOT NULL,
  body bytea NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, attachment_id)
);
CREATE INDEX s7_change_pull_idx ON s7_change(tenant_id, priority, server_seq);

ALTER TABLE s7_event_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE s7_event_receipt FORCE ROW LEVEL SECURITY;
ALTER TABLE s7_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE s7_change FORCE ROW LEVEL SECURITY;
ALTER TABLE s7_attachment ENABLE ROW LEVEL SECURITY;
ALTER TABLE s7_attachment FORCE ROW LEVEL SECURITY;
CREATE POLICY s7_receipt_tenant ON s7_event_receipt AS RESTRICTIVE
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE POLICY s7_receipt_access ON s7_event_receipt AS PERMISSIVE FOR ALL TO app_user USING (true) WITH CHECK (true);
CREATE POLICY s7_change_tenant ON s7_change AS RESTRICTIVE
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE POLICY s7_change_access ON s7_change AS PERMISSIVE FOR ALL TO app_user USING (true) WITH CHECK (true);
CREATE POLICY s7_attachment_tenant ON s7_attachment AS RESTRICTIVE
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE POLICY s7_attachment_access ON s7_attachment AS PERMISSIVE FOR ALL TO app_user USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON s7_event_receipt TO app_user;
GRANT SELECT, INSERT ON s7_change, s7_attachment TO app_user;
GRANT USAGE, SELECT ON SEQUENCE s7_change_server_seq_seq TO app_user;

CREATE FUNCTION s7_push(p_tenant uuid, p_event uuid, p_bundle text, p_priority smallint, p_payload jsonb)
RETURNS TABLE(result_status text, result_seq bigint)
LANGUAGE plpgsql
AS $$
DECLARE
  v_inserted boolean;
  v_seq bigint;
BEGIN
  IF p_tenant IS DISTINCT FROM current_setting('app.tenant_id', true)::uuid THEN
    RAISE EXCEPTION 'tenant mismatch';
  END IF;
  INSERT INTO s7_event_receipt(tenant_id, event_uuid, bundle_id)
  VALUES (p_tenant, p_event, p_bundle)
  ON CONFLICT DO NOTHING
  RETURNING true INTO v_inserted;
  IF coalesce(v_inserted, false) THEN
    INSERT INTO s7_change(tenant_id, event_uuid, priority, payload)
    VALUES (p_tenant, p_event, p_priority, p_payload)
    RETURNING server_seq INTO v_seq;
    UPDATE s7_event_receipt SET server_seq = v_seq
    WHERE tenant_id = p_tenant AND event_uuid = p_event;
    RETURN QUERY SELECT 'accepted'::text, v_seq;
  ELSE
    SELECT server_seq INTO v_seq FROM s7_event_receipt
    WHERE tenant_id = p_tenant AND event_uuid = p_event;
    RETURN QUERY SELECT 'duplicate'::text, v_seq;
  END IF;
END $$;

CREATE FUNCTION s7_save_attachment(p_tenant uuid, p_attachment uuid, p_body bytea)
RETURNS text LANGUAGE plpgsql AS $$
DECLARE v_inserted boolean;
BEGIN
  IF p_tenant IS DISTINCT FROM current_setting('app.tenant_id', true)::uuid THEN RAISE EXCEPTION 'tenant mismatch'; END IF;
  INSERT INTO s7_attachment(tenant_id, attachment_id, body) VALUES (p_tenant, p_attachment, p_body)
  ON CONFLICT DO NOTHING RETURNING true INTO v_inserted;
  RETURN CASE WHEN coalesce(v_inserted, false) THEN 'accepted' ELSE 'duplicate' END;
END $$;
GRANT EXECUTE ON FUNCTION s7_push(uuid, uuid, text, smallint, jsonb), s7_save_attachment(uuid, uuid, bytea) TO app_user;
RESET ROLE;

\echo 'S7 integration setup PASS: FORCE RLS receipt/change/attachment store'
