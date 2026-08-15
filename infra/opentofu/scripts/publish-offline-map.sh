#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <expected-account-id> <japan.pmtiles> <OSM-NOTICE.txt> <sbom.json>" >&2
  exit 64
}

[[ $# -eq 4 ]] || usage
expected_account="$1"
archive_file="$2"
notice_file="$3"
sbom_file="$4"
[[ "${expected_account}" =~ ^[0-9]{12}$ ]] || usage
for file in "${archive_file}" "${notice_file}" "${sbom_file}"; do
  [[ -s "${file}" ]] || { echo "required non-empty artifact not found: ${file}" >&2; exit 66; }
done
for command in aws jq shasum tofu; do
  command -v "${command}" >/dev/null || { echo "required command is missing: ${command}" >&2; exit 69; }
done

export AWS_PAGER=""
export AWS_REGION="ap-northeast-1"
export AWS_DEFAULT_REGION="ap-northeast-1"

account_id="$(aws sts get-caller-identity --query Account --output text)"
[[ "${account_id}" == "${expected_account}" ]] || { echo "AWS account mismatch: expected ${expected_account}, got ${account_id}" >&2; exit 65; }
[[ "$(aws configure get region || true)" == "ap-northeast-1" ]] || { echo "AWS CLI region must be ap-northeast-1" >&2; exit 65; }

manifest="$(tofu output -json deployment_manifest)"
bucket="$(jq -er '.storage.offline_map_bucket' <<<"${manifest}")"
archive_uri="$(jq -er '.offline_map.archive_uri' <<<"${manifest}")"
version="$(jq -er '.offline_map.tileset_version' <<<"${manifest}")"
expected_sha="$(jq -er '.offline_map.archive_sha256' <<<"${manifest}")"
[[ "${archive_uri}" == "s3://${bucket}/tilesets/${version}/japan.pmtiles" ]] || { echo "deployment manifest archive URI is inconsistent" >&2; exit 65; }

actual_sha="$(shasum -a 256 "${archive_file}" | awk '{print $1}')"
[[ "${actual_sha}" == "${expected_sha}" ]] || { echo "PMTiles SHA-256 mismatch: expected ${expected_sha}, got ${actual_sha}" >&2; exit 65; }

prefix="s3://${bucket}/tilesets/${version}"
aws s3 cp "${archive_file}" "${archive_uri}" --only-show-errors \
  --content-type application/vnd.pmtiles \
  --metadata "sha256=${actual_sha},tileset-version=${version},source-license=ODbL-1.0"
aws s3 cp "${notice_file}" "${prefix}/OSM-NOTICE.txt" --only-show-errors --content-type 'text/plain; charset=utf-8'
aws s3 cp "${sbom_file}" "${prefix}/sbom.json" --only-show-errors --content-type application/json

head="$(aws s3api head-object --bucket "${bucket}" --key "tilesets/${version}/japan.pmtiles")"
jq -e --arg sha "${actual_sha}" --arg version "${version}" \
  '.ContentLength > 0 and .ContentType == "application/vnd.pmtiles" and .Metadata.sha256 == $sha and .Metadata["tileset-version"] == $version and .ServerSideEncryption == "aws:kms"' \
  <<<"${head}" >/dev/null || { echo "uploaded PMTiles read-back verification failed" >&2; exit 74; }
aws s3api head-object --bucket "${bucket}" --key "tilesets/${version}/OSM-NOTICE.txt" >/dev/null
aws s3api head-object --bucket "${bucket}" --key "tilesets/${version}/sbom.json" >/dev/null
echo "offline PMTiles published and verified: ${archive_uri} sha256=${actual_sha}"
