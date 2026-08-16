#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <expected-account-id> <deployment-manifest.json> <recovery-set.json> <approved-request.json> <evidence.json>" >&2
  exit 64
}

[[ $# -eq 5 ]] || usage
expected_account="$1"
deployment_manifest="$2"
recovery_set="$3"
request_file="$4"
evidence_file="$5"
for file in "${deployment_manifest}" "${recovery_set}" "${request_file}"; do
  [[ -r "${file}" ]] || { echo "input is not readable: ${file}" >&2; exit 66; }
done
for command in aws date jq shasum; do command -v "${command}" >/dev/null || { echo "missing command: ${command}" >&2; exit 69; }; done

export AWS_PAGER=""
account_id="$(aws sts get-caller-identity --query Account --output text)"
region="$(jq -er '.region' "${deployment_manifest}")"
[[ "${expected_account}" =~ ^[0-9]{12}$ && "${account_id}" == "${expected_account}" ]] || { echo "AWS account mismatch" >&2; exit 65; }
[[ "$(jq -er '.environment' "${deployment_manifest}")" == "staging" && "$(jq -er '.environment' "${recovery_set}")" == "staging" ]] || { echo "restore runner is restricted to staging" >&2; exit 65; }
[[ "$(jq -er '.target_environment' "${request_file}")" == "isolated_restore" ]] || { echo "target_environment must be isolated_restore" >&2; exit 65; }
[[ "$(jq -er '.egress_mode' "${request_file}")" == "sink_only" ]] || { echo "external delivery must be sink_only" >&2; exit 65; }
production_vpc="$(jq -er '.recovery.vpc_id' "${deployment_manifest}")"
isolation_vpc="$(jq -er '.isolation_vpc_id' "${request_file}")"
[[ "${isolation_vpc}" != "${production_vpc}" ]] || { echo "restore must not attach to the application VPC" >&2; exit 65; }
[[ "$(jq -er '.approvals | map(.actor) | unique | length >= 2' "${request_file}")" == "true" ]] || { echo "two distinct approved actors are required" >&2; exit 65; }
[[ "$(jq -er '.status' "${recovery_set}")" == "APPROVED" ]] || { echo "recovery set must be APPROVED" >&2; exit 65; }

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/isas-isolated-restore.XXXXXX")"
trap 'rm -rf "${work_dir}"' EXIT
jobs_file="${work_dir}/jobs.ndjson"
role_arn="$(jq -er '.recovery.backup_role_arn' "${deployment_manifest}")"
started_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

count="$(jq '.restore_resources | length' "${request_file}")"
[[ "${count}" -ge 6 ]] || { echo "restore request must cover every protected resource" >&2; exit 65; }
for ((index=0; index<count; index++)); do
  component="$(jq -er ".restore_resources[${index}].component" "${request_file}")"
  recovery_point="$(jq -er ".restore_resources[${index}].recovery_point_arn" "${request_file}")"
  metadata_file="${work_dir}/metadata-${index}.json"
  jq -e ".restore_resources[${index}].metadata" "${request_file}" >"${metadata_file}"
  token="$(printf '%s' "$(jq -er '.recovery_set_id' "${recovery_set}"):${component}" | shasum -a 256 | awk '{print $1}')"
  job_id="$(aws backup start-restore-job --region "${region}" --recovery-point-arn "${recovery_point}" \
    --iam-role-arn "${role_arn}" --metadata "file://${metadata_file}" --idempotency-token "${token}" \
    --query RestoreJobId --output text)"
  jq -cn --arg component "${component}" --arg restoreJobId "${job_id}" \
    '{component:$component,restore_job_id:$restoreJobId,status:"STARTED"}' >>"${jobs_file}"
done

mkdir -p "$(dirname "${evidence_file}")"
jq -n --arg recoverySetId "$(jq -er '.recovery_set_id' "${recovery_set}")" \
  --arg deploymentId "$(jq -er '.deployment_id' "${deployment_manifest}")" --arg startedAt "${started_at}" \
  --arg isolationVpcId "${isolation_vpc}" --arg ticketRef "$(jq -er '.ticket_ref' "${request_file}")" \
  --slurpfile jobs "${jobs_file}" \
  '{schema_version:1,status:"STARTED",recovery_set_id:$recoverySetId,deployment_id:$deploymentId,environment:"staging",
    started_at:$startedAt,isolation:{vpc_id:$isolationVpcId,production_network_attached:false,egress_mode:"sink_only"},
    ticket_ref:$ticketRef,restore_jobs:$jobs,verification:{status:"NOT_RUN"}}' >"${evidence_file}"
echo "isolated restore STARTED; collect job completion and run verification before acceptance: ${evidence_file}"
