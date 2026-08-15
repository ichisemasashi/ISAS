#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <expected-account-id>" >&2
  exit 64
}

[[ $# -eq 1 ]] || usage
expected_account="$1"
[[ "${expected_account}" =~ ^[0-9]{12}$ ]] || usage

for command in aws jq openssl shasum tofu; do
  command -v "${command}" >/dev/null || {
    echo "required command is missing: ${command}" >&2
    exit 69
  }
done

export AWS_PAGER=""
export AWS_REGION="ap-northeast-1"
export AWS_DEFAULT_REGION="ap-northeast-1"

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/isas-shard-manifest.XXXXXX")"
trap 'rm -rf "${work_dir}"' EXIT

manifest="${work_dir}/deployment-manifest.json"
tofu output -json deployment_manifest >"${manifest}"

account_id="$(aws sts get-caller-identity --query Account --output text)"
environment="$(jq -er '.environment' "${manifest}")"
manifest_uri="$(jq -er '.shard_manifest.object_uri' "${manifest}")"
signature_uri="$(jq -er '.shard_manifest.signature_uri' "${manifest}")"
expected_sha256="$(jq -er '.shard_manifest.sha256' "${manifest}")"
signing_key_arn="$(jq -er '.shard_manifest.signing_key_arn' "${manifest}")"
data_key_arn="$(jq -er '.kms_key_arns.data' "${manifest}")"

if [[ "${account_id}" != "${expected_account}" ]]; then
  echo "AWS account mismatch: expected ${expected_account}, got ${account_id}" >&2
  exit 65
fi
if [[ "$(aws configure get region || true)" != "ap-northeast-1" ]]; then
  echo "AWS CLI region must be ap-northeast-1" >&2
  exit 65
fi
if [[ "${environment}" != "staging" && "${environment}" != "production" ]]; then
  echo "unsupported environment: ${environment}" >&2
  exit 65
fi

manifest_file="${work_dir}/shard-manifest.json"
digest_file="${work_dir}/shard-manifest.sha256"
signature_file="${work_dir}/shard-manifest.sig"

aws s3 cp "${manifest_uri}" "${manifest_file}" --only-show-errors
actual_sha256="$(shasum -a 256 "${manifest_file}" | awk '{print $1}')"
if [[ "${actual_sha256}" != "${expected_sha256}" ]]; then
  echo "shard manifest digest mismatch: expected ${expected_sha256}, got ${actual_sha256}" >&2
  exit 70
fi

openssl dgst -sha256 -binary "${manifest_file}" >"${digest_file}"
aws kms sign \
  --key-id "${signing_key_arn}" \
  --message "fileb://${digest_file}" \
  --message-type DIGEST \
  --signing-algorithm ECDSA_SHA_256 \
  --query Signature \
  --output text | openssl base64 -d -A >"${signature_file}"

signature_valid="$(aws kms verify \
  --key-id "${signing_key_arn}" \
  --message "fileb://${digest_file}" \
  --message-type DIGEST \
  --signature "fileb://${signature_file}" \
  --signing-algorithm ECDSA_SHA_256 \
  --query SignatureValid \
  --output text)"
[[ "${signature_valid}" == "True" ]] || {
  echo "KMS signature verification failed" >&2
  exit 70
}

aws s3 cp "${signature_file}" "${signature_uri}" \
  --content-type application/octet-stream \
  --sse aws:kms \
  --sse-kms-key-id "${data_key_arn}" \
  --metadata "algorithm=ECDSA_SHA_256,manifest-sha256=${actual_sha256},signing-key-arn=${signing_key_arn}" \
  --only-show-errors

printf 'signed shard manifest: %s\nsignature: %s\nsha256: %s\n' \
  "${manifest_uri}" "${signature_uri}" "${actual_sha256}"
