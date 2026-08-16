\set ON_ERROR_STOP on
BEGIN;

-- Local-only synthetic identity. The production schema and authorization
-- functions remain unchanged; this account receives the same role/scope rows
-- that production administration would create.
SET ROLE auth_context_owner;
UPDATE priv.auth_membership
SET role_key = 'group_admin', status = 'active', valid_until = NULL
WHERE tenant_id = '20000000-0000-4000-8000-000000000001'
  AND user_id = '10000000-0000-4000-8000-000000000001'
  AND (role_key, status, valid_until IS NULL) IS DISTINCT FROM ('group_admin', 'active', true);
INSERT INTO priv.auth_membership_field_group(tenant_id,user_id,field_group_id)
VALUES('20000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001')
ON CONFLICT DO NOTHING;
RESET ROLE;

-- Stable IDs make this data idempotent and suitable for repeatable E2E. These
-- rows are synthetic and are never copied to AWS or production tenants.
INSERT INTO app.field(tenant_id,field_id,field_group_id,name,crop_name,status,geom,external_key)
VALUES(
  '20000000-0000-4000-8000-000000000001',
  '40000000-0000-4000-8000-000000000001',
  '30000000-0000-4000-8000-000000000001',
  'ローカル実証圃場', '水稲', 'active',
  ST_Multi(ST_GeomFromText('POLYGON((139.7500 35.6800,139.7520 35.6800,139.7520 35.6820,139.7500 35.6820,139.7500 35.6800))',4326)),
  'LOCAL-FIELD-001'
)
ON CONFLICT(tenant_id,field_id) DO UPDATE SET
  field_group_id=excluded.field_group_id,name=excluded.name,crop_name=excluded.crop_name,
  status='active',geom=excluded.geom,external_key=excluded.external_key,deleted_at=NULL,
  version=app.field.version+1,updated_at=clock_timestamp();

INSERT INTO app.task(tenant_id,task_id,field_group_id,scheduled_at,field_name,crop_name,work_name,status)
VALUES(
  '20000000-0000-4000-8000-000000000001','41000000-0000-4000-8000-000000000001',
  '30000000-0000-4000-8000-000000000001',
  (date_trunc('day',now() AT TIME ZONE 'Asia/Tokyo') + interval '9 hours') AT TIME ZONE 'Asia/Tokyo',
  'ローカル実証圃場','水稲','生育確認','today'
)
ON CONFLICT(tenant_id,task_id) DO UPDATE SET
  field_group_id=excluded.field_group_id,scheduled_at=excluded.scheduled_at,
  field_name=excluded.field_name,crop_name=excluded.crop_name,work_name=excluded.work_name,
  status='today',deleted_at=NULL;

INSERT INTO app.work_instruction(
  tenant_id,instruction_id,field_id,field_group_id,title,work_type,details,
  scheduled_start,scheduled_end,priority,status,created_by,updated_by
)
VALUES(
  '20000000-0000-4000-8000-000000000001','42000000-0000-4000-8000-000000000001',
  '40000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',
  '実証圃場の生育確認','巡回','葉色と水位を確認し、日誌へ記録する',
  (date_trunc('day',now() AT TIME ZONE 'Asia/Tokyo') + interval '9 hours') AT TIME ZONE 'Asia/Tokyo',
  (date_trunc('day',now() AT TIME ZONE 'Asia/Tokyo') + interval '10 hours') AT TIME ZONE 'Asia/Tokyo',
  1,'issued','10000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000001'
)
ON CONFLICT(tenant_id,instruction_id) DO UPDATE SET
  field_id=excluded.field_id,field_group_id=excluded.field_group_id,title=excluded.title,
  work_type=excluded.work_type,details=excluded.details,scheduled_start=excluded.scheduled_start,
  scheduled_end=excluded.scheduled_end,status='issued',progress_percent=0,deleted_at=NULL,
  version=app.work_instruction.version+1,updated_by=excluded.updated_by,updated_at=clock_timestamp();

INSERT INTO app.work_assignment(
  tenant_id,assignment_id,instruction_id,field_group_id,assignee_user_id,assigned_by
)
VALUES(
  '20000000-0000-4000-8000-000000000001','43000000-0000-4000-8000-000000000001',
  '42000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',
  '10000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000001'
)
ON CONFLICT(tenant_id,assignment_id) DO UPDATE SET
  instruction_id=excluded.instruction_id,field_group_id=excluded.field_group_id,
  assignee_user_id=excluded.assignee_user_id,assigned_by=excluded.assigned_by,
  unassigned_at=NULL,version=app.work_assignment.version+1;

INSERT INTO app.journal_template(tenant_id,template_id,field_group_id,name,work_type,defaults,sort_order)
VALUES(
  '20000000-0000-4000-8000-000000000001','44000000-0000-4000-8000-000000000001',
  '30000000-0000-4000-8000-000000000001','巡回確認','巡回',
  '{"memo":"葉色・水位・病害虫の有無を確認"}'::jsonb,10
)
ON CONFLICT(tenant_id,template_id) DO UPDATE SET
  field_group_id=excluded.field_group_id,name=excluded.name,work_type=excluded.work_type,
  defaults=excluded.defaults,sort_order=excluded.sort_order,active=true,
  version=app.journal_template.version+1,updated_at=clock_timestamp();

INSERT INTO app.pesticide_master_release(tenant_id,release_id,version,valid_until,published_by)
VALUES(
  '20000000-0000-4000-8000-000000000001','45000000-0000-4000-8000-000000000001',
  'local-2026.1',now()+interval '365 days','10000000-0000-4000-8000-000000000001'
)
ON CONFLICT(tenant_id,release_id) DO UPDATE SET
  version=excluded.version,valid_until=excluded.valid_until,published_at=clock_timestamp(),published_by=excluded.published_by;

INSERT INTO app.agrochemical(
  tenant_id,chemical_id,release_id,registration_number,name,active_ingredient,
  applicable_crops,dilution_min,dilution_max,max_uses,preharvest_days
)
VALUES(
  '20000000-0000-4000-8000-000000000001','46000000-0000-4000-8000-000000000001',
  '45000000-0000-4000-8000-000000000001','LOCAL-REG-001','ローカル確認剤','synthetic',
  ARRAY['水稲'],1000,2000,3,7
)
ON CONFLICT(tenant_id,chemical_id) DO UPDATE SET
  release_id=excluded.release_id,registration_number=excluded.registration_number,name=excluded.name,
  active_ingredient=excluded.active_ingredient,applicable_crops=excluded.applicable_crops,
  dilution_min=excluded.dilution_min,dilution_max=excluded.dilution_max,max_uses=excluded.max_uses,
  preharvest_days=excluded.preharvest_days,revoked_on=NULL;

INSERT INTO app.stock_event(
  tenant_id,stock_event_id,event_uuid,chemical_id,event_type,quantity_delta,reason,
  occurred_at,event_ts,actor_user_id
)
VALUES(
  '20000000-0000-4000-8000-000000000001','47000000-0000-4000-8000-000000000001',
  '47100000-0000-4000-8000-000000000001','46000000-0000-4000-8000-000000000001',
  'receipt',25,'local synthetic opening balance',now(),now(),'10000000-0000-4000-8000-000000000001'
)
ON CONFLICT(tenant_id,stock_event_id) DO UPDATE SET
  chemical_id=excluded.chemical_id,event_type='receipt',quantity_delta=25,
  reason=excluded.reason,occurred_at=excluded.occurred_at,event_ts=excluded.event_ts,
  actor_user_id=excluded.actor_user_id;

INSERT INTO app.inventory_policy(
  tenant_id,policy_id,chemical_id,reorder_point,target_level,safety_stock,
  allow_negative,adjustment_requires_approval,effective_from,status,created_by,updated_by
)
VALUES(
  '20000000-0000-4000-8000-000000000001','48000000-0000-4000-8000-000000000001',
  '46000000-0000-4000-8000-000000000001',5,30,2,false,true,current_date,'active',
  '10000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000001'
)
ON CONFLICT(tenant_id,policy_id) DO UPDATE SET
  reorder_point=excluded.reorder_point,target_level=excluded.target_level,safety_stock=excluded.safety_stock,
  allow_negative=excluded.allow_negative,adjustment_requires_approval=excluded.adjustment_requires_approval,
  effective_from=excluded.effective_from,status='active',deleted_at=NULL,
  version=app.inventory_policy.version+1,updated_by=excluded.updated_by,updated_at=clock_timestamp();

COMMIT;
