SET ROLE app_user;
SELECT set_config('app.allowed_tenants', '{' || s2_tenant((:client_id % 100) + 1)::text || '}', false);
SELECT id FROM field_load
WHERE tenant_id = s2_tenant((:client_id % 100) + 1)
ORDER BY geom <-> ST_SetSRID(ST_MakePoint(140.25, 38.25), 4326)
LIMIT 20;
