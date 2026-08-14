\set ON_ERROR_STOP on
BEGIN;

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS btree_gist;

SET ROLE app_owner;

CREATE OR REPLACE FUNCTION app.can_read_scope(scope_id uuid)
RETURNS boolean LANGUAGE sql STABLE PARALLEL SAFE AS $$
  SELECT scope_id IS NULL
    OR app.has_capability('scope_all')
    OR scope_id = ANY(coalesce(nullif(current_setting('app.scope_field_groups', true), '')::uuid[], ARRAY[]::uuid[]))
$$;

CREATE TABLE app.field (
  tenant_id uuid NOT NULL,
  field_id uuid NOT NULL,
  field_group_id uuid NOT NULL,
  name text NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
  crop_name text,
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'fallow', 'archived')),
  geom geometry(MultiPolygon, 4326) NOT NULL,
  gis_area_sqm numeric GENERATED ALWAYS AS (ST_Area(geom::geography)) STORED,
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  deleted_at timestamptz,
  PRIMARY KEY (tenant_id, field_id),
  CHECK (ST_IsValid(geom)),
  CHECK (NOT ST_IsEmpty(geom))
);

CREATE INDEX field_tenant_group_idx ON app.field (tenant_id, field_group_id, field_id) WHERE deleted_at IS NULL;
CREATE INDEX field_tenant_geom_gix ON app.field USING gist (tenant_id, geom) WHERE deleted_at IS NULL;
CREATE INDEX field_name_search_idx ON app.field (tenant_id, lower(name) text_pattern_ops) WHERE deleted_at IS NULL;

ALTER TABLE app.field ENABLE ROW LEVEL SECURITY;
ALTER TABLE app.field FORCE ROW LEVEL SECURITY;
CREATE POLICY field_tenant_isolation ON app.field AS PERMISSIVE FOR ALL TO app_user
  USING (tenant_id = app.current_tenant_id())
  WITH CHECK (tenant_id = app.current_tenant_id());
CREATE POLICY field_scope ON app.field AS RESTRICTIVE FOR SELECT TO app_user
  USING (app.can_read_scope(field_group_id));

GRANT SELECT ON app.field TO app_user;
GRANT EXECUTE ON FUNCTION app.can_read_scope(uuid) TO app_user;

RESET ROLE;
COMMIT;
