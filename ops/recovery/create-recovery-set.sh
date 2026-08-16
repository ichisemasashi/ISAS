#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <expected-account-id> <deployment-manifest.json> <output.json>" >&2
  exit 64
}

[[ $# -eq 3 ]] || usage
expected_account="$1"
deployment_manifest="$2"
output_file="$3"
[[ "${expected_account}" =~ ^[0-9]{12}$ ]] || usage
[[ -r "${deployment_manifest}" ]] || { echo "deployment manifest is not readable" >&2; exit 66; }

for command in aws date git jq openssl shasum; do
  command -v "${command}" >/dev/null || { echo "required command is missing: ${command}" >&2; exit 69; }
done

export AWS_PAGER=""
region="$(jq -er '.region' "${deployment_manifest}")"
environment="$(jq -er '.environment' "${deployment_manifest}")"
deployment_id="$(jq -er '.deployment_id' "${deployment_manifest}")"
account_id="$(aws sts get-caller-identity --query Account --output text)"
[[ "${account_id}" == "${expected_account}" ]] || { echo "AWS account mismatch" >&2; exit 65; }
[[ "${region}" == "ap-northeast-1" ]] || { echo "recovery set is restricted to ap-northeast-1" >&2; exit 65; }
[[ "${environment}" == "staging" ]] || { echo "this runner is restricted to staging" >&2; exit 65; }

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/isas-recovery-set.XXXXXX")"
trap 'rm -rf "${work_dir}"' EXIT
mkdir -p "$(dirname "${output_file}")"
recovery_set_id="rs-$(date -u '+%Y%m%dT%H%M%SZ')-$(openssl rand -hex 8)"
started_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
vault="$(jq -er '.recovery.vault_name' "${deployment_manifest}")"
role_arn="$(jq -er '.recovery.backup_role_arn' "${deployment_manifest}")"
evidence_bucket="$(jq -er '.recovery.evidence_bucket' "${deployment_manifest}")"
jobs_file="${work_dir}/jobs.ndjson"

while IFS=$'\t' read -r component resource_arn; do
  token="$(printf '%s' "${recovery_set_id}:${component}" | shasum -a 256 | awk '{print $1}')"
  job_id="$(aws backup start-backup-job --region "${region}" --backup-vault-name "${vault}" \
    --resource-arn "${resource_arn}" --iam-role-arn "${role_arn}" --idempotency-token "${token}" \
    --recovery-point-tags "recovery-set-id=${recovery_set_id},deployment-id=${deployment_id}" \
    --query BackupJobId --output text)"
  jq -cn --arg component "${component}" --arg resourceArn "${resource_arn}" --arg backupJobId "${job_id}" \
    '{component:$component,resource_arn:$resourceArn,backup_job_id:$backupJobId,status:"CREATED"}' >>"${jobs_file}"
done < <(jq -r '.recovery.protected_resources | to_entries[] | [.key,.value] | @tsv' "${deployment_manifest}")

cluster_id="$(jq -er '.database.cluster_identifier' "${deployment_manifest}")"
aws rds describe-db-clusters --region "${region}" --db-cluster-identifier "${cluster_id}" >"${work_dir}/rds.json"

queues_file="${work_dir}/queues.ndjson"
while IFS=$'\t' read -r name url; do
  attributes="$(aws sqs get-queue-attributes --region "${region}" --queue-url "${url}" --attribute-names \
    ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible KmsMasterKeyId)"
  jq -cn --arg name "${name}" --argjson attributes "${attributes}" \
    '{name:$name,attributes:$attributes.Attributes}' >>"${queues_file}"
done < <(jq -r '.queues | to_entries[] | [.key,.value.url] | @tsv' "${deployment_manifest}")

manifest_sha="$(shasum -a 256 "${deployment_manifest}" | awk '{print $1}')"
source_commit="$(git rev-parse HEAD)"
jq -n \
  --arg recoverySetId "${recovery_set_id}" --arg deploymentId "${deployment_id}" --arg environment "${environment}" \
  --arg accountId "${account_id}" --arg region "${region}" --arg startedAt "${started_at}" \
  --arg sourceCommit "${source_commit}" --arg deploymentManifestSha256 "${manifest_sha}" \
  --arg latestRestorableTime "$(jq -er '.DBClusters[0].LatestRestorableTime' "${work_dir}/rds.json")" \
  --arg backupVault "${vault}" --arg backupKeyArn "$(jq -er '.kms_key_arns.backup' "${deployment_manifest}")" \
  --arg auditAnchorQueue "$(jq -er '.queues["audit-anchor"].url' "${deployment_manifest}")" \
  --argjson inventoryNames "$(jq -ec '.recovery.inventory_names' "${deployment_manifest}")" \
  --arg shardManifestSha256 "$(jq -er '.shard_manifest.sha256' "${deployment_manifest}")" \
  --arg migrationTaskDefinition "$(jq -er '.ecs.migration_task_definition' "${deployment_manifest}")" \
  --slurpfile jobs "${jobs_file}" --slurpfile queues "${queues_file}" \
  '{schema_version:1,status:"CAPTURED",recovery_set_id:$recoverySetId,deployment_id:$deploymentId,environment:$environment,
    account_id:$accountId,region:$region,started_at:$startedAt,source_commit:$sourceCommit,
    database:{latest_restorable_time:$latestRestorableTime},backup:{vault:$backupVault,key_arn:$backupKeyArn,jobs:$jobs},
    object_inventory:{status:"configured",inventory_names:$inventoryNames},
    queues:$queues,audit:{anchor_queue:$auditAnchorQueue},configuration:{deployment_manifest_sha256:$deploymentManifestSha256,
    shard_manifest_sha256:$shardManifestSha256,migration_task_definition:$migrationTaskDefinition},approvals:[]}' >"${output_file}"

aws s3api put-object --region "${region}" --bucket "${evidence_bucket}" \
  --key "recovery-sets/${recovery_set_id}/manifest.json" --body "${output_file}" \
  --server-side-encryption aws:kms --ssekms-key-id "$(jq -er '.kms_key_arns.backup' "${deployment_manifest}")" >/dev/null

echo "recovery set CAPTURED: ${recovery_set_id}; wait for every backup job to complete before approval"
