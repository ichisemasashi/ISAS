-- S2 production-scale concurrent PostGIS profile.
-- 100 tenants x 10,000 fields = 1,000,000 polygons, 64 clients over TCP.
-- bbox returns up to 1,000 fields and uses the map-initial-render budget (p95 <= 2s).
\set ON_ERROR_STOP on
SET client_min_messages = NOTICE;

SET ROLE app_owner;
CREATE TABLE field_load (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id uuid NOT NULL,
  name text NOT NULL,
  geom geometry(Polygon, 4326) NOT NULL,
  bbox_min_x double precision NOT NULL,
  bbox_max_x double precision NOT NULL,
  bbox_min_y double precision NOT NULL,
  bbox_max_y double precision NOT NULL,
  gis_area_sqm numeric GENERATED ALWAYS AS (ST_Area(geom::geography)) STORED
);

CREATE OR REPLACE FUNCTION s2_tenant(p_number integer) RETURNS uuid
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN ('20000000-0000-7000-8000-' || lpad(to_hex(p_number), 12, '0'))::uuid;

INSERT INTO field_load(tenant_id, name, geom, bbox_min_x, bbox_max_x, bbox_min_y, bbox_max_y)
SELECT s2_tenant(tenant_number), 'field-' || tenant_number || '-' || field_number,
       ST_MakeEnvelope(
         140.0 + ((field_number - 1) % 100) * 0.005,
         38.0 + ((field_number - 1) / 100) * 0.005,
         140.003 + ((field_number - 1) % 100) * 0.005,
         38.003 + ((field_number - 1) / 100) * 0.005,
         4326),
       140.0 + ((field_number - 1) % 100) * 0.005,
       140.003 + ((field_number - 1) % 100) * 0.005,
       38.0 + ((field_number - 1) / 100) * 0.005,
       38.003 + ((field_number - 1) / 100) * 0.005
FROM generate_series(1, 100) tenant_number
CROSS JOIN generate_series(1, 10000) field_number;

CREATE INDEX field_load_tenant_geom_gix ON field_load USING gist(tenant_id, geom);
-- PostGIS && is not leakproof and remains an RLS filter. These write-time bbox columns use
-- leakproof float comparisons to reduce candidates before the exact PostGIS predicate.
CREATE INDEX field_load_tenant_min_x_idx ON field_load(tenant_id, bbox_min_x);
CREATE INDEX field_load_tenant_max_x_idx ON field_load(tenant_id, bbox_max_x);
CREATE INDEX field_load_tenant_min_y_idx ON field_load(tenant_id, bbox_min_y);
CREATE INDEX field_load_tenant_max_y_idx ON field_load(tenant_id, bbox_max_y);
ANALYZE field_load;
ALTER TABLE field_load ENABLE ROW LEVEL SECURITY;
ALTER TABLE field_load FORCE ROW LEVEL SECURITY;
CREATE POLICY field_load_boundary ON field_load AS RESTRICTIVE
  USING (tenant_id = ANY(COALESCE(NULLIF(current_setting('app.allowed_tenants', true), ''), '{}')::uuid[]));
CREATE POLICY field_load_read ON field_load AS PERMISSIVE FOR SELECT
  USING (tenant_id = ANY(COALESCE(NULLIF(current_setting('app.allowed_tenants', true), ''), '{}')::uuid[]));
GRANT SELECT ON field_load TO app_user;
GRANT EXECUTE ON FUNCTION s2_tenant(integer) TO app_user;
RESET ROLE;

SELECT count(*) AS seeded_fields FROM field_load;
\echo 'S2 load setup PASS: 1,000,000 polygons with FORCE RLS and gist(tenant_id, geom)'
