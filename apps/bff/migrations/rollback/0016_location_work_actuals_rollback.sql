\set ON_ERROR_STOP on
BEGIN;
SET ROLE app_owner;
DROP VIEW app.field_presence_actual;
DROP VIEW app.work_time_actual;
DROP FUNCTION app.read_own_work_actuals(timestamptz, timestamptz);
DROP FUNCTION app.purge_expired_location_tracks(integer);
DROP FUNCTION app.read_location_tracks(uuid, timestamptz, timestamptz, text);
DROP TRIGGER location_track_point_guard ON app.location_track_point;
DROP FUNCTION app.validate_location_track_point();
DROP POLICY work_punch_location_owner_read ON app.work_punch;
DROP POLICY location_consent_owner_read ON app.location_consent_event;
DROP TABLE app.location_access_audit;
DROP TABLE app.location_track_point;
DROP TABLE app.location_tracking_preference;
DROP TABLE app.location_consent_policy;
RESET ROLE;
COMMIT;
