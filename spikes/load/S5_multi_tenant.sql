\set payload_id random(1, 2147483647)
SET ROLE app_user;
SELECT append_audit_chain(
  s5_tenant((:client_id % 32) + 1),
  gen_random_uuid(),
  jsonb_build_object('operation', 'update', 'payloadId', :payload_id, 'bytes', repeat('x', 256))
);
