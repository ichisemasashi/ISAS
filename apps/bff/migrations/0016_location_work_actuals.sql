\set ON_ERROR_STOP on
BEGIN;

SET ROLE app_owner;

CREATE TABLE app.location_consent_policy (
  tenant_id uuid NOT NULL,
  policy_version text NOT NULL CHECK (length(policy_version) BETWEEN 1 AND 64),
  purpose text NOT NULL CHECK (purpose IN ('work_evidence', 'safety', 'route_optimization')),
  locale text NOT NULL CHECK (locale ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
  title text NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
  body text NOT NULL CHECK (length(body) BETWEEN 1 AND 10000),
  content_sha256 text NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
  effective_from timestamptz NOT NULL,
  effective_until timestamptz,
  published_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  published_by uuid NOT NULL,
  PRIMARY KEY (tenant_id, policy_version, purpose, locale),
  CHECK (effective_until IS NULL OR effective_until > effective_from)
);

CREATE TABLE app.location_tracking_preference (
  tenant_id uuid NOT NULL,
  user_id uuid NOT NULL,
  purpose text NOT NULL DEFAULT 'work_evidence' CHECK (purpose IN ('work_evidence', 'safety', 'route_optimization')),
  enabled boolean NOT NULL DEFAULT false,
  punch_linked boolean NOT NULL DEFAULT true,
  retention_days smallint NOT NULL DEFAULT 14 CHECK (retention_days BETWEEN 1 AND 30),
  locale text NOT NULL CHECK (locale ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  updated_by uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, user_id, purpose)
);

CREATE TABLE app.location_track_point (
  tenant_id uuid NOT NULL,
  track_point_id uuid NOT NULL,
  event_uuid uuid NOT NULL,
  subject_user_id uuid NOT NULL,
  instruction_id uuid,
  field_group_id uuid,
  collection_session_id uuid NOT NULL,
  consent_event_id uuid NOT NULL,
  geom geography(Point, 4326) NOT NULL,
  accuracy_m numeric(8,2) NOT NULL CHECK (accuracy_m > 0 AND accuracy_m <= 10000),
  recorded_at timestamptz NOT NULL,
  event_ts timestamptz NOT NULL DEFAULT statement_timestamp(),
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, track_point_id),
  UNIQUE (tenant_id, event_uuid),
  FOREIGN KEY (tenant_id, instruction_id) REFERENCES app.work_instruction (tenant_id, instruction_id),
  FOREIGN KEY (tenant_id, consent_event_id) REFERENCES app.location_consent_event (tenant_id, consent_event_id),
  CHECK (expires_at > event_ts),
  CHECK (recorded_at <= event_ts + interval '1 day')
);

CREATE TABLE app.location_access_audit (
  audit_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id uuid NOT NULL,
  viewer_user_id uuid NOT NULL,
  subject_user_id uuid NOT NULL,
  purpose text NOT NULL CHECK (length(purpose) BETWEEN 1 AND 200),
  range_from timestamptz NOT NULL,
  range_to timestamptz NOT NULL,
  returned_count integer NOT NULL CHECK (returned_count >= 0),
  viewed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CHECK (range_to > range_from)
);

CREATE FUNCTION app.validate_location_track_point()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, app AS $$
DECLARE preference app.location_tracking_preference%ROWTYPE;
DECLARE consent app.location_consent_event%ROWTYPE;
DECLARE last_action text;
BEGIN
  IF NEW.tenant_id <> app.current_tenant_id() OR NEW.subject_user_id <> app.current_user_id() THEN
    RAISE EXCEPTION 'location subject mismatch' USING ERRCODE = '42501';
  END IF;

  SELECT * INTO preference
  FROM app.location_tracking_preference
  WHERE tenant_id = NEW.tenant_id AND user_id = NEW.subject_user_id AND purpose = 'work_evidence';
  IF NOT FOUND OR NOT preference.enabled THEN
    RAISE EXCEPTION 'location collection is disabled' USING ERRCODE = '23514';
  END IF;

  SELECT event.* INTO consent
  FROM app.location_consent_event event
  WHERE event.tenant_id = NEW.tenant_id AND event.subject_user_id = NEW.subject_user_id
    AND event.purpose = 'work_evidence' AND event.effective_at <= NEW.recorded_at
  ORDER BY event.effective_at DESC, event.created_at DESC, event.consent_event_id DESC LIMIT 1;
  IF NOT FOUND OR consent.action <> 'granted' OR (consent.expires_at IS NOT NULL AND consent.expires_at <= NEW.recorded_at)
     OR consent.consent_event_id <> NEW.consent_event_id THEN
    RAISE EXCEPTION 'valid location consent is required' USING ERRCODE = '23514';
  END IF;

  IF preference.punch_linked THEN
    SELECT action INTO last_action FROM app.work_punch
    WHERE tenant_id = NEW.tenant_id AND user_id = NEW.subject_user_id AND occurred_at <= NEW.recorded_at
    ORDER BY occurred_at DESC, created_at DESC LIMIT 1;
    IF last_action IS NULL OR last_action NOT IN ('start', 'resume') THEN
      RAISE EXCEPTION 'location collection is paused outside working time' USING ERRCODE = '23514';
    END IF;
  END IF;

  NEW.event_ts := statement_timestamp();
  NEW.expires_at := statement_timestamp() + make_interval(days => preference.retention_days);
  RETURN NEW;
END $$;

CREATE FUNCTION app.read_location_tracks(
  requested_subject uuid, requested_from timestamptz, requested_to timestamptz, access_purpose text
) RETURNS TABLE (
  track_point_id uuid, instruction_id uuid, field_group_id uuid, longitude double precision,
  latitude double precision, accuracy_m numeric, recorded_at timestamptz, expires_at timestamptz
) LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, app AS $$
DECLARE row_count integer;
BEGIN
  IF requested_to <= requested_from OR requested_to - requested_from > interval '31 days'
     OR length(trim(access_purpose)) NOT BETWEEN 1 AND 200 THEN
    RAISE EXCEPTION 'invalid location access request' USING ERRCODE = '22023';
  END IF;
  IF requested_subject <> app.current_user_id() AND NOT app.has_capability('view_others_tracks') THEN
    RAISE EXCEPTION 'location access denied' USING ERRCODE = '42501';
  END IF;

  RETURN QUERY SELECT point.track_point_id, point.instruction_id, point.field_group_id,
    ST_X(point.geom::geometry), ST_Y(point.geom::geometry), point.accuracy_m,
    point.recorded_at, point.expires_at
  FROM app.location_track_point point
  WHERE point.tenant_id = app.current_tenant_id() AND point.subject_user_id = requested_subject
    AND point.recorded_at >= requested_from AND point.recorded_at < requested_to
    AND point.expires_at > statement_timestamp()
  ORDER BY point.recorded_at;
  GET DIAGNOSTICS row_count = ROW_COUNT;

  INSERT INTO app.location_access_audit
    (tenant_id, viewer_user_id, subject_user_id, purpose, range_from, range_to, returned_count)
  VALUES (app.current_tenant_id(), app.current_user_id(), requested_subject, trim(access_purpose),
    requested_from, requested_to, row_count);
END $$;

CREATE FUNCTION app.purge_expired_location_tracks(batch_size integer DEFAULT 10000)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, app AS $$
DECLARE deleted_count integer;
BEGIN
  IF batch_size NOT BETWEEN 1 AND 100000 THEN
    RAISE EXCEPTION 'invalid purge batch size' USING ERRCODE = '22023';
  END IF;
  WITH expired AS (
    SELECT tenant_id, track_point_id FROM app.location_track_point
    WHERE expires_at <= statement_timestamp() ORDER BY expires_at LIMIT batch_size FOR UPDATE SKIP LOCKED
  )
  DELETE FROM app.location_track_point point USING expired
  WHERE point.tenant_id = expired.tenant_id AND point.track_point_id = expired.track_point_id;
  GET DIAGNOSTICS deleted_count = ROW_COUNT;
  RETURN deleted_count;
END $$;

CREATE FUNCTION app.read_own_work_actuals(requested_from timestamptz, requested_to timestamptz)
RETURNS TABLE (actual_type text, instruction_id uuid, field_id uuid, field_group_id uuid, actual_date date, seconds bigint)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, app AS $$
DECLARE row_count integer;
BEGIN
  IF requested_to <= requested_from OR requested_to - requested_from > interval '366 days' THEN
    RAISE EXCEPTION 'invalid actual range' USING ERRCODE = '22023';
  END IF;
  RETURN QUERY
  WITH punch_intervals AS (
    SELECT punch.instruction_id, punch.field_group_id, punch.action, punch.occurred_at,
      lead(punch.occurred_at) OVER (ORDER BY punch.occurred_at, punch.created_at, punch.punch_id) AS next_at
    FROM app.work_punch punch WHERE punch.tenant_id = app.current_tenant_id()
      AND punch.user_id = app.current_user_id() AND punch.occurred_at >= requested_from AND punch.occurred_at < requested_to
  ), location_samples AS (
    SELECT point.geom, point.recorded_at,
      lead(point.recorded_at) OVER (PARTITION BY point.collection_session_id ORDER BY point.recorded_at) AS next_at
    FROM app.location_track_point point WHERE point.tenant_id = app.current_tenant_id()
      AND point.subject_user_id = app.current_user_id() AND point.recorded_at >= requested_from
      AND point.recorded_at < requested_to AND point.expires_at > statement_timestamp()
  )
  SELECT 'work_time', interval_row.instruction_id, NULL::uuid, interval_row.field_group_id,
    interval_row.occurred_at::date,
    coalesce(sum(extract(epoch FROM interval_row.next_at - interval_row.occurred_at)) FILTER (
      WHERE interval_row.action IN ('start', 'resume') AND interval_row.next_at > interval_row.occurred_at
        AND interval_row.next_at - interval_row.occurred_at <= interval '16 hours'), 0)::bigint
  FROM punch_intervals interval_row
  GROUP BY interval_row.instruction_id, interval_row.field_group_id, interval_row.occurred_at::date
  UNION ALL
  SELECT 'field_presence', NULL::uuid, field.field_id, field.field_group_id, sample.recorded_at::date,
    sum(CASE WHEN sample.next_at > sample.recorded_at AND sample.next_at - sample.recorded_at <= interval '10 minutes'
      THEN extract(epoch FROM sample.next_at - sample.recorded_at) ELSE 0 END)::bigint
  FROM location_samples sample JOIN app.field field ON field.tenant_id = app.current_tenant_id()
    AND field.deleted_at IS NULL AND ST_Covers(field.geom, sample.geom::geometry)
  GROUP BY field.field_id, field.field_group_id, sample.recorded_at::date;
  GET DIAGNOSTICS row_count = ROW_COUNT;
  INSERT INTO app.location_access_audit
    (tenant_id, viewer_user_id, subject_user_id, purpose, range_from, range_to, returned_count)
  VALUES (app.current_tenant_id(), app.current_user_id(), app.current_user_id(), 'work_actuals',
    requested_from, requested_to, row_count);
END $$;

CREATE VIEW app.work_time_actual WITH (security_invoker = true) AS
WITH intervals AS (
  SELECT tenant_id, user_id, instruction_id, field_group_id, action, occurred_at,
    lead(occurred_at) OVER (PARTITION BY tenant_id, user_id ORDER BY occurred_at, created_at, punch_id) AS next_at
  FROM app.work_punch
)
SELECT tenant_id, user_id, instruction_id, field_group_id,
  min(occurred_at) FILTER (WHERE action = 'start') AS started_at,
  max(next_at) FILTER (WHERE action IN ('start', 'resume')) AS last_activity_at,
  coalesce(sum(extract(epoch FROM next_at - occurred_at)) FILTER (
    WHERE action IN ('start', 'resume') AND next_at > occurred_at AND next_at - occurred_at <= interval '16 hours'
  ), 0)::bigint AS worked_seconds
FROM intervals GROUP BY tenant_id, user_id, instruction_id, field_group_id;

CREATE VIEW app.field_presence_actual WITH (security_invoker = true) AS
WITH samples AS (
  SELECT point.*, lead(recorded_at) OVER (
    PARTITION BY point.tenant_id, point.subject_user_id, point.collection_session_id ORDER BY point.recorded_at
  ) AS next_at
  FROM app.location_track_point point WHERE point.expires_at > statement_timestamp()
), matched AS (
  SELECT sample.tenant_id, sample.subject_user_id AS user_id, field.field_id, field.field_group_id,
    sample.recorded_at::date AS presence_date,
    CASE WHEN sample.next_at > sample.recorded_at AND sample.next_at - sample.recorded_at <= interval '10 minutes'
      THEN extract(epoch FROM sample.next_at - sample.recorded_at) ELSE 0 END AS seconds
  FROM samples sample JOIN app.field field ON field.tenant_id = sample.tenant_id
    AND field.deleted_at IS NULL AND ST_Covers(field.geom, sample.geom::geometry)
)
SELECT tenant_id, user_id, field_id, field_group_id, presence_date,
  sum(seconds)::bigint AS presence_seconds
FROM matched GROUP BY tenant_id, user_id, field_id, field_group_id, presence_date;

CREATE TRIGGER location_track_point_guard
BEFORE INSERT ON app.location_track_point FOR EACH ROW EXECUTE FUNCTION app.validate_location_track_point();

DO $$
DECLARE table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY[
    'location_consent_policy', 'location_tracking_preference', 'location_track_point', 'location_access_audit'
  ] LOOP
    EXECUTE format('ALTER TABLE app.%I OWNER TO app_owner', table_name);
    EXECUTE format('ALTER TABLE app.%I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE app.%I FORCE ROW LEVEL SECURITY', table_name);
    EXECUTE format('CREATE POLICY location_owner_access ON app.%I FOR ALL TO app_owner USING (true) WITH CHECK (true)', table_name);
    EXECUTE format('CREATE POLICY tenant_isolation ON app.%I AS PERMISSIVE FOR ALL TO app_user USING (tenant_id = app.current_tenant_id()) WITH CHECK (tenant_id = app.current_tenant_id())', table_name);
  END LOOP;
END $$;
ALTER VIEW app.work_time_actual OWNER TO app_owner;
ALTER VIEW app.field_presence_actual OWNER TO app_owner;

CREATE POLICY location_consent_owner_read ON app.location_consent_event FOR SELECT TO app_owner USING (true);
CREATE POLICY work_punch_location_owner_read ON app.work_punch FOR SELECT TO app_owner USING (true);
CREATE POLICY location_policy_reader ON app.location_consent_policy AS RESTRICTIVE FOR SELECT TO app_user USING (effective_from <= statement_timestamp() AND (effective_until IS NULL OR effective_until > statement_timestamp()));
CREATE POLICY location_policy_manager ON app.location_consent_policy AS RESTRICTIVE FOR INSERT TO app_user WITH CHECK (app.has_capability('privacy:manage'));
CREATE POLICY location_preference_self ON app.location_tracking_preference AS RESTRICTIVE FOR ALL TO app_user USING (user_id = app.current_user_id()) WITH CHECK (user_id = app.current_user_id() AND updated_by = app.current_user_id());
CREATE POLICY location_point_self_insert ON app.location_track_point AS RESTRICTIVE FOR INSERT TO app_user WITH CHECK (subject_user_id = app.current_user_id());
CREATE POLICY location_access_audit_reader ON app.location_access_audit AS RESTRICTIVE FOR SELECT TO app_user USING (viewer_user_id = app.current_user_id() OR app.has_capability('security:manage'));

CREATE INDEX location_policy_active_idx ON app.location_consent_policy (tenant_id, purpose, locale, effective_from DESC);
CREATE INDEX location_track_subject_time_idx ON app.location_track_point (tenant_id, subject_user_id, recorded_at DESC);
CREATE INDEX location_track_expiry_idx ON app.location_track_point (expires_at, tenant_id, track_point_id);
CREATE INDEX location_track_geom_idx ON app.location_track_point USING gist (geom);
CREATE INDEX location_access_audit_time_idx ON app.location_access_audit (tenant_id, viewed_at DESC, audit_id DESC);

GRANT SELECT, INSERT ON app.location_consent_policy TO app_user;
GRANT SELECT, INSERT, UPDATE ON app.location_tracking_preference TO app_user;
GRANT INSERT ON app.location_track_point TO app_user;
GRANT SELECT ON app.location_access_audit, app.work_time_actual, app.field_presence_actual TO app_user;
GRANT EXECUTE ON FUNCTION app.read_location_tracks(uuid, timestamptz, timestamptz, text) TO app_user;
GRANT EXECUTE ON FUNCTION app.read_own_work_actuals(timestamptz, timestamptz) TO app_user;
REVOKE ALL ON FUNCTION app.purge_expired_location_tracks(integer) FROM PUBLIC, app_user;
GRANT EXECUTE ON FUNCTION app.purge_expired_location_tracks(integer) TO app_owner;
GRANT USAGE, SELECT ON SEQUENCE app.location_access_audit_audit_id_seq TO app_owner;

RESET ROLE;
COMMIT;
