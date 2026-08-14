-- S5: (tenant_id, month) audit hash-chain concurrency.
-- Acceptance profile: 32 clients, 500 writes/s for 15 seconds, p95 <= 1 second.
-- The same-tenant hotspot is the normative case; multi-tenant is diagnostic only.
\set ON_ERROR_STOP on
SET client_min_messages = NOTICE;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

SET ROLE app_owner;
CREATE TABLE audit_chain_head (
  tenant_id uuid NOT NULL,
  period_start date NOT NULL,
  last_seq bigint NOT NULL DEFAULT 0,
  last_hash bytea,
  PRIMARY KEY (tenant_id, period_start)
);
CREATE TABLE audit_chain_log (
  tenant_id uuid NOT NULL,
  period_start date NOT NULL,
  seq bigint NOT NULL,
  event_uuid uuid NOT NULL,
  payload jsonb NOT NULL,
  prev_hash bytea,
  row_hash bytea NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, period_start, seq),
  UNIQUE (tenant_id, event_uuid)
);
ALTER TABLE audit_chain_head ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_chain_head FORCE ROW LEVEL SECURITY;
ALTER TABLE audit_chain_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_chain_log FORCE ROW LEVEL SECURITY;

GRANT SELECT, INSERT, UPDATE ON audit_chain_head TO audit_writer;
GRANT SELECT, INSERT ON audit_chain_log TO audit_writer;
REVOKE ALL ON audit_chain_head, audit_chain_log FROM PUBLIC, app_user;

CREATE OR REPLACE FUNCTION append_audit_chain(p_tenant uuid, p_event uuid, p_payload jsonb)
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
  v_period date := date_trunc('month', current_date)::date;
  v_prev bytea;
  v_seq bigint;
  v_hash bytea;
BEGIN
  INSERT INTO public.audit_chain_head(tenant_id, period_start)
  VALUES (p_tenant, v_period)
  ON CONFLICT DO NOTHING;

  SELECT last_hash, last_seq + 1 INTO v_prev, v_seq
  FROM public.audit_chain_head
  WHERE tenant_id = p_tenant AND period_start = v_period
  FOR UPDATE;

  v_hash := public.digest(
    p_tenant::text || '|' || v_period::text || '|' || v_seq::text || '|' ||
    coalesce(encode(v_prev, 'hex'), '') || '|' || p_payload::text,
    'sha256'
  );
  INSERT INTO public.audit_chain_log
    (tenant_id, period_start, seq, event_uuid, payload, prev_hash, row_hash)
  VALUES (p_tenant, v_period, v_seq, p_event, p_payload, v_prev, v_hash);
  UPDATE public.audit_chain_head SET last_seq = v_seq, last_hash = v_hash
  WHERE tenant_id = p_tenant AND period_start = v_period;
  RETURN v_seq;
END $$;
RESET ROLE;
ALTER FUNCTION append_audit_chain(uuid, uuid, jsonb) OWNER TO audit_writer;
REVOKE ALL ON FUNCTION append_audit_chain(uuid, uuid, jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION append_audit_chain(uuid, uuid, jsonb) TO app_user;

SET ROLE app_owner;
CREATE OR REPLACE FUNCTION s5_tenant(p_number integer) RETURNS uuid
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN ('50000000-0000-7000-8000-' || lpad(to_hex(p_number), 12, '0'))::uuid;
GRANT EXECUTE ON FUNCTION s5_tenant(integer) TO app_user;
RESET ROLE;

\echo 'S5 setup PASS: append function serializes only the selected (tenant, month) head'
