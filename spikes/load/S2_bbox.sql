SET ROLE app_user;
SELECT set_config('app.allowed_tenants', '{' || s2_tenant((:client_id % 100) + 1)::text || '}', false);
SELECT id FROM field_load
WHERE tenant_id = s2_tenant((:client_id % 100) + 1)
  AND bbox_min_x <= 140.25 AND bbox_max_x >= 140.10
  AND bbox_min_y <= 38.25 AND bbox_max_y >= 38.10
  AND geom && ST_MakeEnvelope(140.10, 38.10, 140.25, 38.25, 4326)
ORDER BY id LIMIT 1000;
