#!/bin/sh
set -eu

usage() {
  echo "usage: $0 EXPECTED_STAGE MIN_SECONDS MIN_TRANSACTIONS DEPLOYMENT_MANIFEST BUILD_MANIFEST" >&2
  exit 64
}

[ "$#" -eq 5 ] || usage
expected_stage=$1
minimum_seconds=$2
minimum_transactions=$3
deployment_manifest=$4
build_manifest=$5
: "${STATE_URI:?STATE_URI is required}"
: "${AWS_REGION:?AWS_REGION is required}"
: "${POLL_SECONDS:=60}"
: "${MAX_MONITOR_SECONDS:=$((minimum_seconds + 21600))}"

case "$expected_stage" in 5|25|100) ;; *) usage ;; esac
case "$minimum_seconds:$minimum_transactions:$POLL_SECONDS:$MAX_MONITOR_SECONDS" in *[!0-9:]*) usage ;; esac

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
temporary_directory=$(mktemp -d)
trap 'find "$temporary_directory" -depth -delete' EXIT HUP INT TERM
state_file="$temporary_directory/state.json"
aws s3 cp "$STATE_URI" "$state_file" --region "$AWS_REGION" --only-show-errors
[ "$(jq -er '.stage' "$state_file")" = "$expected_stage" ] || { echo "delivery state is not at expected stage" >&2; exit 1; }

start_epoch=$(date -u +%s)
start_time=$(date -u +%Y-%m-%dT%H:%M:%SZ)
load_balancer=$(jq -er '.ecs.progressive_delivery.load_balancer_arn_suffix' "$deployment_manifest")
alarm_count=$(jq '.ecs.progressive_delivery.blocking_alarms | length' "$deployment_manifest")

rollback() {
  reason=$1
  echo "automatic rollback: $reason" >&2
  "$script_directory/progressive-deploy.sh" rollback "$deployment_manifest" "$build_manifest" || true
  deployment_id=$(jq -er '.deployment_id' "$deployment_manifest")
  ISAS_ENVIRONMENT=${ISAS_ENVIRONMENT:-production} DEPLOYMENT_ID=$deployment_id \
    "$script_directory/publish-operational-metric.sh" DeploymentRollback 1 || true
  exit 1
}

while :; do
  end_epoch=$(date -u +%s)
  end_time=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  elapsed=$((end_epoch - start_epoch))

  jq -r '.ecs.progressive_delivery.blocking_alarms[]' "$deployment_manifest" >"$temporary_directory/alarm-names"
  # Alarm names are provisioned by OpenTofu and contain no whitespace.
  # shellcheck disable=SC2046
  aws cloudwatch describe-alarms --region "$AWS_REGION" --alarm-names $(cat "$temporary_directory/alarm-names") >"$temporary_directory/alarms.json"
  observed_count=$(jq '[.MetricAlarms[],.CompositeAlarms[]] | length' "$temporary_directory/alarms.json")
  [ "$observed_count" -eq "$alarm_count" ] || rollback "one or more blocking alarm signals are missing"
  jq -e 'all((.MetricAlarms[],.CompositeAlarms[]); .StateValue == "OK")' "$temporary_directory/alarms.json" >/dev/null \
    || rollback "a blocking alarm is ALARM or INSUFFICIENT_DATA"

  aws cloudwatch get-metric-statistics --region "$AWS_REGION" \
    --namespace AWS/ApplicationELB --metric-name RequestCount \
    --dimensions "Name=LoadBalancer,Value=$load_balancer" \
    --start-time "$start_time" --end-time "$end_time" --period 60 --statistics Sum \
    >"$temporary_directory/transactions.json"
  transactions=$(jq '[.Datapoints[].Sum] | add // 0 | floor' "$temporary_directory/transactions.json")

  echo "stage=$expected_stage elapsed=${elapsed}s eligible_transactions=$transactions alarms=OK"
  if [ "$elapsed" -ge "$minimum_seconds" ] && [ "$transactions" -ge "$minimum_transactions" ]; then
    jq --arg stage "$expected_stage" --arg startedAt "$start_time" --arg completedAt "$end_time" \
      --argjson duration "$elapsed" --argjson transactions "$transactions" --argjson alarms "$alarm_count" \
      '.observations=((.observations // []) + [{stage:$stage,started_at:$startedAt,completed_at:$completedAt,
        duration_seconds:$duration,eligible_transactions:$transactions,blocking_alarm_count:$alarms,status:"PASS"}])' \
      "$state_file" >"$temporary_directory/observed-state.json"
    mv "$temporary_directory/observed-state.json" "$state_file"
    aws s3 cp "$state_file" "$STATE_URI" --region "$AWS_REGION" --only-show-errors
    echo "progressive delivery observation PASS"
    exit 0
  fi
  [ "$elapsed" -lt "$MAX_MONITOR_SECONDS" ] || rollback "observation limits were not met before the deadline"
  sleep "$POLL_SECONDS"
done
