\set ON_ERROR_STOP on
BEGIN;

CREATE SCHEMA local_support AUTHORIZATION local_support_owner;
REVOKE ALL ON SCHEMA local_support FROM PUBLIC, app_user, auth_role, p0_user, p2_user, ops_user;
GRANT USAGE, CREATE ON SCHEMA local_support TO local_support_owner;
SET ROLE local_support_owner;

CREATE TABLE local_support.login_attempt (
  state_hash text PRIMARY KEY CHECK (state_hash ~ '^[A-Za-z0-9_-]{32,128}$'),
  ciphertext bytea NOT NULL,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX login_attempt_expiry_idx ON local_support.login_attempt(expires_at);

CREATE TABLE local_support.authorization_marker (
  user_id uuid PRIMARY KEY,
  authorization_version bigint NOT NULL CHECK (authorization_version > 0),
  revoked_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE local_support.session (
  session_hash text PRIMARY KEY CHECK (session_hash ~ '^[A-Za-z0-9_-]{32,128}$'),
  user_id uuid NOT NULL,
  authorization_version bigint NOT NULL CHECK (authorization_version > 0),
  ciphertext bytea NOT NULL,
  last_seen_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX session_user_version_idx ON local_support.session(user_id, authorization_version);
CREATE INDEX session_expiry_idx ON local_support.session(expires_at);

CREATE TABLE local_support.context (
  context_hash text PRIMARY KEY CHECK (context_hash ~ '^[A-Za-z0-9_-]{32,128}$'),
  session_hash text NOT NULL REFERENCES local_support.session(session_hash) ON DELETE CASCADE,
  user_id uuid NOT NULL,
  authorization_version bigint NOT NULL CHECK (authorization_version > 0),
  ciphertext bytea NOT NULL,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX context_session_idx ON local_support.context(session_hash);
CREATE INDEX context_user_version_idx ON local_support.context(user_id, authorization_version);

CREATE TABLE local_support.offline_snapshot (
  snapshot_hash text PRIMARY KEY CHECK (snapshot_hash ~ '^[A-Za-z0-9_-]{32,128}$'),
  user_id uuid NOT NULL,
  authorization_version bigint NOT NULL CHECK (authorization_version > 0),
  ciphertext bytea NOT NULL,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX offline_snapshot_user_version_idx ON local_support.offline_snapshot(user_id, authorization_version);

CREATE TABLE local_support.revocation_queue (
  queue_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  idempotency_key text NOT NULL UNIQUE CHECK (length(idempotency_key) BETWEEN 1 AND 200),
  ciphertext bytea NOT NULL,
  available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  claimed_until timestamptz,
  claim_id uuid,
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  completed_at timestamptz,
  quarantined_at timestamptz,
  last_error_code text,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX revocation_queue_claim_idx ON local_support.revocation_queue(available_at, queue_id) WHERE completed_at IS NULL AND quarantined_at IS NULL;

CREATE FUNCTION local_support.put_login_attempt(p_hash text, p_ciphertext bytea, p_expires_at timestamptz) RETURNS void
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,local_support AS $$
  INSERT INTO login_attempt(state_hash,ciphertext,expires_at) VALUES(p_hash,p_ciphertext,p_expires_at)
  ON CONFLICT(state_hash) DO UPDATE SET ciphertext=excluded.ciphertext,expires_at=excluded.expires_at,created_at=clock_timestamp()
$$;
CREATE FUNCTION local_support.take_login_attempt(p_hash text) RETURNS bytea
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,local_support AS $$
DECLARE result bytea; BEGIN
  DELETE FROM login_attempt WHERE state_hash=p_hash AND expires_at>statement_timestamp() RETURNING ciphertext INTO result;
  DELETE FROM login_attempt WHERE state_hash=p_hash;
  RETURN result;
END $$;

CREATE FUNCTION local_support.put_session(p_hash text,p_user uuid,p_version bigint,p_ciphertext bytea,p_last_seen timestamptz,p_expires timestamptz) RETURNS boolean
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,local_support AS $$
BEGIN
  IF EXISTS(SELECT 1 FROM authorization_marker WHERE user_id=p_user AND authorization_version>=p_version) THEN RETURN false; END IF;
  INSERT INTO session(session_hash,user_id,authorization_version,ciphertext,last_seen_at,expires_at)
  VALUES(p_hash,p_user,p_version,p_ciphertext,p_last_seen,p_expires)
  ON CONFLICT(session_hash) DO UPDATE SET ciphertext=excluded.ciphertext,last_seen_at=excluded.last_seen_at,expires_at=excluded.expires_at
  WHERE session.user_id=excluded.user_id AND session.authorization_version=excluded.authorization_version;
  RETURN true;
END $$;
CREATE FUNCTION local_support.get_session(p_hash text) RETURNS bytea
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,local_support AS $$ SELECT ciphertext FROM session WHERE session_hash=p_hash AND expires_at>statement_timestamp() $$;
CREATE FUNCTION local_support.touch_session(p_hash text,p_last_seen timestamptz) RETURNS void
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,local_support AS $$ UPDATE session SET last_seen_at=GREATEST(last_seen_at,p_last_seen) WHERE session_hash=p_hash $$;
CREATE FUNCTION local_support.delete_session(p_hash text) RETURNS void
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,local_support AS $$ DELETE FROM session WHERE session_hash=p_hash $$;

CREATE FUNCTION local_support.put_context(p_hash text,p_session text,p_user uuid,p_version bigint,p_ciphertext bytea,p_expires timestamptz) RETURNS boolean
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,local_support AS $$
BEGIN
  IF EXISTS(SELECT 1 FROM authorization_marker WHERE user_id=p_user AND authorization_version>=p_version) THEN RETURN false; END IF;
  INSERT INTO context(context_hash,session_hash,user_id,authorization_version,ciphertext,expires_at) VALUES(p_hash,p_session,p_user,p_version,p_ciphertext,p_expires)
  ON CONFLICT(context_hash) DO UPDATE SET ciphertext=excluded.ciphertext,expires_at=excluded.expires_at
  WHERE context.session_hash=excluded.session_hash AND context.user_id=excluded.user_id AND context.authorization_version=excluded.authorization_version;
  RETURN true;
END $$;
CREATE FUNCTION local_support.get_context(p_hash text) RETURNS bytea
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,local_support AS $$ SELECT ciphertext FROM context WHERE context_hash=p_hash AND expires_at>statement_timestamp() $$;
CREATE FUNCTION local_support.delete_context(p_hash text) RETURNS void
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,local_support AS $$ DELETE FROM context WHERE context_hash=p_hash $$;
CREATE FUNCTION local_support.delete_contexts_for_session(p_session text) RETURNS void
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,local_support AS $$ DELETE FROM context WHERE session_hash=p_session $$;

CREATE FUNCTION local_support.invalidate_user(p_user uuid,p_version bigint) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,local_support AS $$
DECLARE deleted_sessions integer; deleted_snapshots integer; BEGIN
  INSERT INTO authorization_marker(user_id,authorization_version) VALUES(p_user,p_version)
  ON CONFLICT(user_id) DO UPDATE SET authorization_version=GREATEST(authorization_marker.authorization_version,excluded.authorization_version),revoked_at=clock_timestamp();
  DELETE FROM session WHERE user_id=p_user AND authorization_version<=p_version; GET DIAGNOSTICS deleted_sessions=ROW_COUNT;
  DELETE FROM offline_snapshot WHERE user_id=p_user AND authorization_version<=p_version; GET DIAGNOSTICS deleted_snapshots=ROW_COUNT;
  RETURN jsonb_build_object('sessions',deleted_sessions,'offlineSnapshots',deleted_snapshots);
END $$;

CREATE FUNCTION local_support.enqueue_revocation(p_key text,p_ciphertext bytea) RETURNS bigint
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,local_support AS $$
  INSERT INTO revocation_queue(idempotency_key,ciphertext) VALUES(p_key,p_ciphertext)
  ON CONFLICT(idempotency_key) DO UPDATE SET idempotency_key=excluded.idempotency_key RETURNING queue_id
$$;
CREATE FUNCTION local_support.claim_revocation(p_claim uuid,p_lease_seconds integer) RETURNS TABLE(queue_id bigint,ciphertext bytea,attempts integer)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,local_support AS $$ BEGIN RETURN QUERY
  WITH candidate AS (SELECT q.queue_id FROM revocation_queue q WHERE q.completed_at IS NULL AND q.quarantined_at IS NULL AND q.available_at<=statement_timestamp() AND (q.claimed_until IS NULL OR q.claimed_until<statement_timestamp()) ORDER BY q.queue_id FOR UPDATE SKIP LOCKED LIMIT 1)
  UPDATE revocation_queue q SET claim_id=p_claim,claimed_until=statement_timestamp()+make_interval(secs=>p_lease_seconds),attempts=q.attempts+1 FROM candidate c WHERE q.queue_id=c.queue_id RETURNING q.queue_id,q.ciphertext,q.attempts;
END $$;
CREATE FUNCTION local_support.complete_revocation(p_queue bigint,p_claim uuid) RETURNS boolean
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,local_support AS $$ WITH done AS (UPDATE revocation_queue SET completed_at=clock_timestamp(),claim_id=NULL,claimed_until=NULL WHERE queue_id=p_queue AND claim_id=p_claim RETURNING 1) SELECT EXISTS(SELECT 1 FROM done) $$;
CREATE FUNCTION local_support.fail_revocation(p_queue bigint,p_claim uuid,p_error text,p_max_attempts integer DEFAULT 5) RETURNS boolean
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,local_support AS $$ WITH failed AS (UPDATE revocation_queue SET claim_id=NULL,claimed_until=NULL,last_error_code=left(p_error,100),available_at=clock_timestamp()+make_interval(secs=>LEAST(300,attempts*attempts)),quarantined_at=CASE WHEN attempts>=p_max_attempts THEN clock_timestamp() END WHERE queue_id=p_queue AND claim_id=p_claim RETURNING 1) SELECT EXISTS(SELECT 1 FROM failed) $$;

RESET ROLE;
REVOKE CREATE ON SCHEMA local_support FROM local_support_owner;
REVOKE ALL ON ALL TABLES IN SCHEMA local_support FROM PUBLIC, app_user, auth_role, p0_user, p2_user, ops_user;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA local_support FROM PUBLIC, app_user, auth_role, p0_user, p2_user, ops_user;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA local_support FROM PUBLIC, app_user, auth_role, p0_user, p2_user, ops_user;
GRANT USAGE ON SCHEMA local_support TO ops_user;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA local_support TO ops_user;

COMMIT;
