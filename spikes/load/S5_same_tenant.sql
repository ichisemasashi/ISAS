\set payload_id random(1, 2147483647)
SET ROLE app_user;
SELECT append_audit_chain(
  '50000000-0000-7000-8000-000000000001'::uuid,
  gen_random_uuid(),
  jsonb_build_object('operation', 'update', 'payloadId', :payload_id, 'bytes', repeat('x', 256))
);
