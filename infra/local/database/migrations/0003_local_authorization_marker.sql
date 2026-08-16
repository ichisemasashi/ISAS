\set ON_ERROR_STOP on
BEGIN;
-- Migration 0001 removes CREATE after bootstrapping the sealed support schema.
-- Re-open it only for this owner-controlled function replacement, then seal it
-- again before committing.
GRANT CREATE ON SCHEMA local_support TO local_support_owner;
SET ROLE local_support_owner;

-- A revocation event carries the newly valid authorization_version. Records
-- below that watermark are stale; a freshly authenticated record at the same
-- version is valid. Keep this aligned with the production DynamoDB adapter.
CREATE OR REPLACE FUNCTION local_support.put_session(p_hash text,p_user uuid,p_version bigint,p_ciphertext bytea,p_last_seen timestamptz,p_expires timestamptz) RETURNS boolean
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,local_support AS $$
BEGIN
  IF EXISTS(SELECT 1 FROM authorization_marker WHERE user_id=p_user AND authorization_version>p_version) THEN RETURN false; END IF;
  INSERT INTO session(session_hash,user_id,authorization_version,ciphertext,last_seen_at,expires_at)
  VALUES(p_hash,p_user,p_version,p_ciphertext,p_last_seen,p_expires)
  ON CONFLICT(session_hash) DO UPDATE SET ciphertext=excluded.ciphertext,last_seen_at=excluded.last_seen_at,expires_at=excluded.expires_at
  WHERE session.user_id=excluded.user_id AND session.authorization_version=excluded.authorization_version;
  RETURN true;
END $$;

CREATE OR REPLACE FUNCTION local_support.put_context(p_hash text,p_session text,p_user uuid,p_version bigint,p_ciphertext bytea,p_expires timestamptz) RETURNS boolean
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,local_support AS $$
BEGIN
  IF EXISTS(SELECT 1 FROM authorization_marker WHERE user_id=p_user AND authorization_version>p_version) THEN RETURN false; END IF;
  INSERT INTO context(context_hash,session_hash,user_id,authorization_version,ciphertext,expires_at) VALUES(p_hash,p_session,p_user,p_version,p_ciphertext,p_expires)
  ON CONFLICT(context_hash) DO UPDATE SET ciphertext=excluded.ciphertext,expires_at=excluded.expires_at
  WHERE context.session_hash=excluded.session_hash AND context.user_id=excluded.user_id AND context.authorization_version=excluded.authorization_version;
  RETURN true;
END $$;

CREATE OR REPLACE FUNCTION local_support.invalidate_user(p_user uuid,p_version bigint) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,local_support AS $$
DECLARE deleted_sessions integer; deleted_snapshots integer; BEGIN
  INSERT INTO authorization_marker(user_id,authorization_version) VALUES(p_user,p_version)
  ON CONFLICT(user_id) DO UPDATE SET authorization_version=GREATEST(authorization_marker.authorization_version,excluded.authorization_version),revoked_at=clock_timestamp();
  DELETE FROM session WHERE user_id=p_user AND authorization_version<p_version; GET DIAGNOSTICS deleted_sessions=ROW_COUNT;
  DELETE FROM offline_snapshot WHERE user_id=p_user AND authorization_version<p_version; GET DIAGNOSTICS deleted_snapshots=ROW_COUNT;
  RETURN jsonb_build_object('sessions',deleted_sessions,'offlineSnapshots',deleted_snapshots);
END $$;

RESET ROLE;
REVOKE CREATE ON SCHEMA local_support FROM local_support_owner;
COMMIT;
