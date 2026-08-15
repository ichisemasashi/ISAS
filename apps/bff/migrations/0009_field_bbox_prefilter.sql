\set ON_ERROR_STOP on
BEGIN;

SET ROLE app_owner;

ALTER TABLE app.field
  ADD COLUMN bbox_min_x double precision GENERATED ALWAYS AS (ST_XMin(Box3D(geom))) STORED,
  ADD COLUMN bbox_max_x double precision GENERATED ALWAYS AS (ST_XMax(Box3D(geom))) STORED,
  ADD COLUMN bbox_min_y double precision GENERATED ALWAYS AS (ST_YMin(Box3D(geom))) STORED,
  ADD COLUMN bbox_max_y double precision GENERATED ALWAYS AS (ST_YMax(Box3D(geom))) STORED;

CREATE INDEX field_tenant_bbox_min_x_idx ON app.field (tenant_id, bbox_min_x) WHERE deleted_at IS NULL;
CREATE INDEX field_tenant_bbox_max_x_idx ON app.field (tenant_id, bbox_max_x) WHERE deleted_at IS NULL;
CREATE INDEX field_tenant_bbox_min_y_idx ON app.field (tenant_id, bbox_min_y) WHERE deleted_at IS NULL;
CREATE INDEX field_tenant_bbox_max_y_idx ON app.field (tenant_id, bbox_max_y) WHERE deleted_at IS NULL;

RESET ROLE;
COMMIT;
