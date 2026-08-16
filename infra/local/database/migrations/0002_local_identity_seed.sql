\set ON_ERROR_STOP on
BEGIN;
SET ROLE auth_context_owner;
INSERT INTO priv.auth_user(user_id,issuer,subject,display_name,status)
VALUES('10000000-0000-4000-8000-000000000001','https://isas.localhost:8443/oidc/realms/isas-local','10000000-0000-4000-8000-000000000001','Local Operator','active')
ON CONFLICT(issuer,subject) DO UPDATE SET display_name=excluded.display_name,status='active';
INSERT INTO priv.auth_membership(tenant_id,user_id,role_key,status)
VALUES('20000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000001','organization_admin','active')
ON CONFLICT(tenant_id,user_id) DO UPDATE SET role_key=excluded.role_key,status='active';
RESET ROLE;
COMMIT;
