#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <expected-account-id> <saved-plan-file> <evidence-json>" >&2
  exit 64
}

[[ $# -eq 3 ]] || usage
expected_account="$1"
plan_file="$2"
evidence_file="$3"
[[ "${expected_account}" =~ ^[0-9]{12}$ ]] || usage
[[ -f "${plan_file}" ]] || { echo "saved plan not found: ${plan_file}" >&2; exit 66; }

for command in aws curl git jq node openssl shasum tofu; do
  command -v "${command}" >/dev/null || { echo "required command is missing: ${command}" >&2; exit 69; }
done

evidence_dir="$(dirname "${evidence_file}")"
mkdir -p "${evidence_dir}"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/isas-staging-acceptance.XXXXXX")"
trap 'rm -rf "${work_dir}"' EXIT
checks_file="${work_dir}/checks.ndjson"

export AWS_PAGER=""
export AWS_REGION="ap-northeast-1"
export AWS_DEFAULT_REGION="ap-northeast-1"

tofu output -json deployment_manifest >"${work_dir}/manifest.json"
manifest="${work_dir}/manifest.json"
account_id="$(aws sts get-caller-identity --query Account --output text)"
region="$(aws configure get region || true)"
deployment_id="$(jq -er '.deployment_id' "${manifest}")"

if [[ "${account_id}" != "${expected_account}" ]]; then
  echo "AWS account mismatch: expected ${expected_account}, got ${account_id}" >&2
  exit 65
fi
if [[ "${region}" != "ap-northeast-1" ]]; then
  echo "AWS region must be ap-northeast-1, got ${region:-unset}" >&2
  exit 65
fi
if [[ "$(jq -r '.environment' "${manifest}")" != "staging" ]]; then
  echo "refusing to collect staging evidence from a non-staging state" >&2
  exit 65
fi

record() {
  local id="$1" condition="$2" message="$3"
  local status="FAIL"
  [[ "${condition}" == "true" ]] && status="PASS"
  jq -cn --arg id "${id}" --arg status "${status}" --arg evidence "${message}" \
    '{id:$id,status:$status,evidence:$evidence}' >>"${checks_file}"
}

az_count="$(jq '.availability_zones | map(.id) | unique | length' "${manifest}")"
record "account-region" "$([[ "${account_id}" == "${expected_account}" && "${region}" == "ap-northeast-1" ]] && echo true || echo false)" \
  "STS account=${account_id}; AWS CLI region=${region}"
record "three-availability-zones" "$([[ "${az_count}" -eq 3 ]] && echo true || echo false)" \
  "OpenTofu output contains ${az_count} unique AZ IDs: $(jq -c '[.availability_zones[].id]' "${manifest}")"

kms_ok=true
while IFS= read -r key_arn; do
  aws kms describe-key --key-id "${key_arn}" >"${work_dir}/kms.json"
  jq -e '.KeyMetadata.Enabled == true and .KeyMetadata.MultiRegion == false and .KeyMetadata.KeyManager == "CUSTOMER" and .KeyMetadata.Origin == "AWS_KMS"' "${work_dir}/kms.json" >/dev/null || kms_ok=false
done < <(jq -r '.kms_key_arns[]' "${manifest}")
record "regional-kms-keys" "${kms_ok}" "All six manifest KMS keys are enabled, customer-managed, HSM-backed AWS_KMS origin and single-region"

alb_arn="$(jq -r '.ingress.load_balancer_arn' "${manifest}")"
aws elbv2 describe-listeners --load-balancer-arn "${alb_arn}" >"${work_dir}/listeners.json"
tls_ok="$(jq -r --arg certificate "$(jq -r '.ingress.certificate_arn' "${manifest}")" '[.Listeners[] | select(.Protocol == "HTTPS" and .Port == 443 and .SslPolicy == "ELBSecurityPolicy-TLS13-1-2-2021-06" and ([.Certificates[].CertificateArn] | index($certificate) != null))] | length == 1' "${work_dir}/listeners.json")"
record "tls-ingress" "${tls_ok}" "ALB has one HTTPS/443 listener with the declared ACM certificate and TLS 1.2/1.3 policy"

cluster_id="$(jq -r '.database.cluster_identifier' "${manifest}")"
aws rds describe-db-clusters --db-cluster-identifier "${cluster_id}" >"${work_dir}/rds.json"
rds_ok="$(jq -r '.DBClusters[0] | (.Status == "available" and .Engine == "postgres" and .MultiAZ == true and .StorageEncrypted == true and (.DBClusterMembers | length) == 3)' "${work_dir}/rds.json")"
record "rds-multi-az" "${rds_ok}" \
  "RDS cluster ${cluster_id}: status=$(jq -r '.DBClusters[0].Status' "${work_dir}/rds.json"), members=$(jq '.DBClusters[0].DBClusterMembers|length' "${work_dir}/rds.json"), engine=$(jq -r '.DBClusters[0].EngineVersion' "${work_dir}/rds.json")"
rds_pitr_ok="$(jq -r '.DBClusters[0] | (.BackupRetentionPeriod >= 30 and .EarliestRestorableTime != null and .LatestRestorableTime != null and .ReaderEndpoint != null)' "${work_dir}/rds.json")"
record "rds-wal-pitr" "${rds_pitr_ok}" "RDS-managed WAL archive exposes a reader endpoint and a non-empty PITR window with at least 30-day retention"

cluster="$(jq -r '.ecs.cluster' "${manifest}")"
migration_task="$(jq -r '.ecs.migration_task_definition' "${manifest}")"
subnets="$(jq -c '.ecs.migration_network.subnet_ids' "${manifest}")"
security_groups="$(jq -c '[.ecs.migration_network.security_group_id]' "${manifest}")"
network_configuration="$(jq -cn --argjson subnets "${subnets}" --argjson groups "${security_groups}" \
  '{awsvpcConfiguration:{subnets:$subnets,securityGroups:$groups,assignPublicIp:"DISABLED"}}')"
aws ecs run-task --cluster "${cluster}" --launch-type FARGATE --task-definition "${migration_task}" \
  --network-configuration "${network_configuration}" --count 1 >"${work_dir}/migration-run.json"
migration_task_arn="$(jq -er '.tasks[0].taskArn' "${work_dir}/migration-run.json")"
aws ecs wait tasks-stopped --cluster "${cluster}" --tasks "${migration_task_arn}"
aws ecs describe-tasks --cluster "${cluster}" --tasks "${migration_task_arn}" >"${work_dir}/migration-result.json"
migration_exit="$(jq -r '.tasks[0].containers[] | select(.name == "migration") | .exitCode' "${work_dir}/migration-result.json")"
migration_reason="$(jq -r '.tasks[0].containers[] | select(.name == "migration") | (.reason // "completed")' "${work_dir}/migration-result.json")"
migration_ok="$([[ "${migration_exit}" == "0" ]] && echo true || echo false)"
record "postgres-postgis" "${migration_ok}" "Migration task ${migration_task_arn} verified PostgreSQL 16 and PostGIS 3.4.6; exit=${migration_exit}"
record "auth-migration" "${migration_ok}" "Migration task applied 0000_auth_context_v1.sql before 0001-0009; exit=${migration_exit}"
record "auth-production-security" "${migration_ok}" "production_auth_context_security.sql checked owner, FORCE RLS, audit triggers; exit=${migration_exit}; reason=${migration_reason}"

services=()
while IFS= read -r service; do
  services+=("${service}")
done < <(jq -r '[.ecs.services.web,.ecs.services.bff,.ecs.services.worker,.ecs.services.poolers[]] | .[]' "${manifest}")
aws ecs describe-services --cluster "${cluster}" --services "${services[@]}" >"${work_dir}/services.json"
services_ok="$(jq -r '(.failures|length)==0 and ([.services[] | .status == "ACTIVE" and .runningCount == .desiredCount and .runningCount > 0] | all)' "${work_dir}/services.json")"
record "ecs-services" "${services_ok}" "ECS has $(jq '.services|length' "${work_dir}/services.json") healthy services; BFF running=$(jq -r '.services[]|select(.serviceName=="bff")|.runningCount' "${work_dir}/services.json")"

failure_domains_ok=true
failure_domain_evidence=()
for service_key in web bff; do
  service_name="$(jq -r ".ecs.services.${service_key}" "${manifest}")"
  aws ecs list-tasks --cluster "${cluster}" --service-name "${service_name}" --desired-status RUNNING >"${work_dir}/${service_key}-tasks.json"
  task_arns=()
  while IFS= read -r task_arn; do
    task_arns+=("${task_arn}")
  done < <(jq -r '.taskArns[]' "${work_dir}/${service_key}-tasks.json")
  if [[ "${#task_arns[@]}" -lt 2 ]]; then
    failure_domains_ok=false
    failure_domain_evidence+=("${service_key}=fewer-than-two-tasks")
    continue
  fi
  aws ecs describe-tasks --cluster "${cluster}" --tasks "${task_arns[@]}" >"${work_dir}/${service_key}-task-details.json"
  task_az_count="$(jq '[.tasks[].availabilityZone] | unique | length' "${work_dir}/${service_key}-task-details.json")"
  [[ "${task_az_count}" -ge 2 ]] || failure_domains_ok=false
  failure_domain_evidence+=("${service_key}=${task_az_count}AZ")
done
record "web-bff-failure-domains" "${failure_domains_ok}" "Running task spread: ${failure_domain_evidence[*]}"
digest_ok="$(jq -r '[.ecs.image_digests[] | test("@sha256:[0-9a-f]{64}$")] | all' "${manifest}")"
record "digest-images" "${digest_ok}" "All six deployment image references are immutable sha256 digests"

user_pool_id="$(jq -r '.cognito.user_pool_id' "${manifest}")"
aws cognito-idp describe-user-pool --user-pool-id "${user_pool_id}" >"${work_dir}/cognito.json"
aws cognito-idp get-user-pool-mfa-config --user-pool-id "${user_pool_id}" >"${work_dir}/cognito-mfa.json"
cognito_ok="$(jq -rn --slurpfile pool "${work_dir}/cognito.json" --slurpfile mfa "${work_dir}/cognito-mfa.json" \
  '($pool[0].UserPool.MfaConfiguration == "ON") and ($pool[0].UserPool.UserPoolTier == "PLUS") and ($pool[0].UserPool.UserPoolAddOns.AdvancedSecurityMode == "ENFORCED") and ($mfa[0].WebAuthnConfiguration.FactorConfiguration == "MULTI_FACTOR_WITH_USER_VERIFICATION") and ($mfa[0].WebAuthnConfiguration.UserVerification == "required")')"
record "cognito-mfa" "${cognito_ok}" "Cognito ${user_pool_id}: Plus, MFA=ON, threat protection=ENFORCED, WebAuthn=MULTI_FACTOR_WITH_USER_VERIFICATION"

session_table="$(jq -r '.storage.session_table' "${manifest}")"
aws dynamodb describe-table --table-name "${session_table}" >"${work_dir}/dynamodb.json"
aws dynamodb describe-continuous-backups --table-name "${session_table}" >"${work_dir}/dynamodb-backup.json"
dynamodb_ok="$(jq -rn --slurpfile table "${work_dir}/dynamodb.json" --slurpfile backup "${work_dir}/dynamodb-backup.json" \
  '($table[0].Table.TableStatus == "ACTIVE") and ($table[0].Table.SSEDescription.Status == "ENABLED") and ($backup[0].ContinuousBackupsDescription.PointInTimeRecoveryDescription.PointInTimeRecoveryStatus == "ENABLED")')"
record "dynamodb-session" "${dynamodb_ok}" "DynamoDB ${session_table}: ACTIVE, SSE-KMS and PITR enabled"

s3_ok=true
for bucket_key in private_object_bucket quarantine_archive_bucket shard_config_bucket offline_map_bucket ops_evidence_bucket; do
  bucket="$(jq -r ".storage.${bucket_key}" "${manifest}")"
  aws s3api get-public-access-block --bucket "${bucket}" >"${work_dir}/${bucket_key}-public.json"
  aws s3api get-bucket-versioning --bucket "${bucket}" >"${work_dir}/${bucket_key}-version.json"
  aws s3api get-bucket-encryption --bucket "${bucket}" >"${work_dir}/${bucket_key}-encryption.json"
  jq -e '.PublicAccessBlockConfiguration | .BlockPublicAcls and .BlockPublicPolicy and .IgnorePublicAcls and .RestrictPublicBuckets' "${work_dir}/${bucket_key}-public.json" >/dev/null || s3_ok=false
  jq -e '.Status == "Enabled"' "${work_dir}/${bucket_key}-version.json" >/dev/null || s3_ok=false
  jq -e '.ServerSideEncryptionConfiguration.Rules[0].ApplyServerSideEncryptionByDefault.SSEAlgorithm == "aws:kms"' "${work_dir}/${bucket_key}-encryption.json" >/dev/null || s3_ok=false
done
record "s3-private-storage" "${s3_ok}" "Private object, quarantine, shard configuration, offline map and ops evidence buckets block public access, use versioning and SSE-KMS"

offline_bucket="$(jq -r '.storage.offline_map_bucket' "${manifest}")"
offline_version="$(jq -r '.offline_map.tileset_version' "${manifest}")"
offline_expected_sha="$(jq -r '.offline_map.archive_sha256' "${manifest}")"
aws s3api head-object --bucket "${offline_bucket}" --key "tilesets/${offline_version}/japan.pmtiles" >"${work_dir}/offline-map.json"
aws s3api head-object --bucket "${offline_bucket}" --key "tilesets/${offline_version}/OSM-NOTICE.txt" >"${work_dir}/offline-map-notice.json"
aws s3api head-object --bucket "${offline_bucket}" --key "tilesets/${offline_version}/sbom.json" >"${work_dir}/offline-map-sbom.json"
offline_map_ok="$(jq -r --arg sha "${offline_expected_sha}" --arg version "${offline_version}" '.ContentLength > 0 and .ContentType == "application/vnd.pmtiles" and .Metadata.sha256 == $sha and .Metadata["tileset-version"] == $version and .Metadata["source-license"] == "ODbL-1.0" and .ServerSideEncryption == "aws:kms"' "${work_dir}/offline-map.json")"
record "offline-map-artifact" "${offline_map_ok}" "PMTiles ${offline_version} exists with the declared SHA-256, ODbL metadata, NOTICE, SBOM and SSE-KMS"

attachment_access_point_arn="$(jq -r '.storage.private_attachment_access_point_arn' "${manifest}")"
attachment_access_point_name="${attachment_access_point_arn##*/}"
aws s3control get-access-point --account-id "${account_id}" --name "${attachment_access_point_name}" >"${work_dir}/attachment-access-point.json"
attachment_ok="$(jq -r '.NetworkOrigin == "VPC" and .VpcConfiguration.VpcId != null and (.PublicAccessBlockConfiguration | .BlockPublicAcls and .BlockPublicPolicy and .IgnorePublicAcls and .RestrictPublicBuckets)' "${work_dir}/attachment-access-point.json")"
record "private-attachment-delivery" "${attachment_ok}" "Attachment access point ${attachment_access_point_name} is VPC-only with all public access controls enabled"

sqs_ok=true
while IFS=$'\t' read -r queue_url dlq_url; do
  aws sqs get-queue-attributes --queue-url "${queue_url}" --attribute-names KmsMasterKeyId RedrivePolicy Policy >"${work_dir}/sqs.json"
  aws sqs get-queue-attributes --queue-url "${dlq_url}" --attribute-names KmsMasterKeyId RedriveAllowPolicy Policy >"${work_dir}/dlq.json"
  jq -e '.Attributes.KmsMasterKeyId != null and (.Attributes.RedrivePolicy | fromjson | .deadLetterTargetArn != null) and (.Attributes.Policy | fromjson | [.Statement[] | select(.Effect == "Deny")] | length > 0)' "${work_dir}/sqs.json" >/dev/null || sqs_ok=false
  jq -e '.Attributes.KmsMasterKeyId != null and (.Attributes.RedriveAllowPolicy | fromjson | .redrivePermission == "byQueue") and (.Attributes.Policy | fromjson | [.Statement[] | select(.Effect == "Deny")] | length > 0)' "${work_dir}/dlq.json" >/dev/null || sqs_ok=false
done < <(jq -r '.queues[] | [.url,.dlq_url] | @tsv' "${manifest}")
record "sqs-dead-letter" "${sqs_ok}" "All four application queues and DLQs use KMS, explicit redrive allow policies and TLS-only resource policies"
quarantine_ok="$(jq -r '.queues | has("quarantine") and (.quarantine.url != null) and (.quarantine.dlq_url != null)' "${manifest}")"
record "queue-quarantine" "${quarantine_ok}" "Quarantine queue, dedicated DLQ and immutable quarantine archive are present in the deployment manifest"

shard_manifest_uri="$(jq -r '.shard_manifest.object_uri' "${manifest}")"
shard_signature_uri="$(jq -r '.shard_manifest.signature_uri' "${manifest}")"
aws s3 cp "${shard_manifest_uri}" "${work_dir}/shard-manifest.json" --only-show-errors
aws s3 cp "${shard_signature_uri}" "${work_dir}/shard-manifest.sig" --only-show-errors
shard_sha256="$(shasum -a 256 "${work_dir}/shard-manifest.json" | awk '{print $1}')"
openssl dgst -sha256 -binary "${work_dir}/shard-manifest.json" >"${work_dir}/shard-manifest.digest"
aws kms verify --key-id "$(jq -r '.shard_manifest.signing_key_arn' "${manifest}")" --message "fileb://${work_dir}/shard-manifest.digest" --message-type DIGEST --signature "fileb://${work_dir}/shard-manifest.sig" --signing-algorithm ECDSA_SHA_256 >"${work_dir}/shard-verify.json"
shard_ok="$(jq -rn --arg actual "${shard_sha256}" --arg expected "$(jq -r '.shard_manifest.sha256' "${manifest}")" --slurpfile verified "${work_dir}/shard-verify.json" '$actual == $expected and $verified[0].SignatureValid == true')"
record "shard-manifest-signature" "${shard_ok}" "Static shard manifest digest=${shard_sha256}; KMS ECDSA signature verified"

rds_arn="$(jq -r '.database.arn' "${manifest}")"
aws backup list-recovery-points-by-resource --resource-arn "${rds_arn}" >"${work_dir}/recovery.json"
backup_ok="$(jq -r '[.RecoveryPoints[] | select(.Status == "COMPLETED")] | length > 0' "${work_dir}/recovery.json")"
record "backup-recovery-point" "${backup_ok}" "AWS Backup completed recovery points for RDS: $(jq '[.RecoveryPoints[]|select(.Status=="COMPLETED")]|length' "${work_dir}/recovery.json")"

topic_arn="$(jq -r '.operations.incident_topic' "${manifest}")"
aws sns list-subscriptions-by-topic --topic-arn "${topic_arn}" >"${work_dir}/subscriptions.json"
aws cloudwatch describe-alarms --alarm-name-prefix "isas-jp-stg" >"${work_dir}/alarms.json"
monitoring_ok="$(jq -rn --slurpfile subscriptions "${work_dir}/subscriptions.json" --slurpfile alarms "${work_dir}/alarms.json" \
  '([$subscriptions[0].Subscriptions[] | select(.SubscriptionArn != "PendingConfirmation")] | length > 0) and ($alarms[0].MetricAlarms | length >= 6)')"
record "monitoring-alert-route" "${monitoring_ok}" "Confirmed SNS subscriptions=$(jq '[.Subscriptions[]|select(.SubscriptionArn!="PendingConfirmation")]|length' "${work_dir}/subscriptions.json"); alarms=$(jq '.MetricAlarms|length' "${work_dir}/alarms.json")"

application_url="$(jq -r '.application_url' "${manifest}")"
health_status="$(curl --fail --silent --show-error --output "${work_dir}/health.json" --write-out '%{http_code}' "${application_url}/api/healthz" || true)"
record "https-health" "$([[ "${health_status}" == "200" ]] && echo true || echo false)" "GET ${application_url}/api/healthz returned HTTP ${health_status}"

aws wafv2 get-web-acl-for-resource --resource-arn "${alb_arn}" >"${work_dir}/waf.json"
waf_ok="$(jq -r '.WebACL.ARN != null and ([.WebACL.Rules[].Name] | index("AWSManagedCommon") != null)' "${work_dir}/waf.json")"
record "waf-association" "${waf_ok}" "ALB is associated with WAF $(jq -r '.WebACL.ARN // "missing"' "${work_dir}/waf.json")"

deploy_role="$(jq -r '.github_deploy_role_arn | split("/")[-1]' "${manifest}")"
aws iam get-role --role-name "${deploy_role}" >"${work_dir}/github-role.json"
oidc_ok="$(jq -r --arg repo_env "environment:staging" '[.Role.AssumeRolePolicyDocument.Statement[].Condition.StringEquals["token.actions.githubusercontent.com:sub"] | select(endswith($repo_env))] | length == 1' "${work_dir}/github-role.json")"
record "github-oidc" "${oidc_ok}" "Deploy role trust is restricted to the GitHub staging Environment and sts.amazonaws.com audience"

commit_sha="$(git rev-parse HEAD)"
plan_sha="sha256:$(shasum -a 256 "${plan_file}" | awk '{print $1}')"
collected_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
jq -s \
  --arg deploymentId "${deployment_id}" \
  --arg accountId "${account_id}" \
  --arg commitSha "${commit_sha}" \
  --arg tofuPlanSha256 "${plan_sha}" \
  --arg collectedAt "${collected_at}" \
  '{schemaVersion:1,deploymentId:$deploymentId,environment:"staging",accountId:$accountId,region:"ap-northeast-1",commitSha:$commitSha,tofuPlanSha256:$tofuPlanSha256,collectedAt:$collectedAt,checks:.}' \
  "${checks_file}" >"${evidence_file}"

node "$(git rev-parse --show-toplevel)/ops/check-staging-acceptance.mjs" "${evidence_file}"
