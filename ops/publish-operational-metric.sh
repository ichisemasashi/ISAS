#!/bin/sh
set -eu

usage() {
  echo "usage: $0 METRIC VALUE" >&2
  echo "required env: ISAS_ENVIRONMENT, DEPLOYMENT_ID, AWS_REGION" >&2
  exit 64
}

[ "$#" -eq 2 ] || usage
metric=$1
value=$2

case "$metric" in
  WalArchiveAgeSeconds|AuditChainMismatches|AttachmentMissingObjects|AttachmentOrphanBacklog|SyncAuthorizationRejections|TelemetryDroppedItems|DeploymentStage|DeploymentRollback|ErrorBudgetRemainingPercent) ;;
  *) echo "unsupported operational metric: $metric" >&2; exit 64 ;;
esac

case "$value" in
  ''|*[!0-9.-]*) echo "VALUE must be numeric" >&2; exit 64 ;;
esac

: "${ISAS_ENVIRONMENT:?ISAS_ENVIRONMENT is required}"
: "${DEPLOYMENT_ID:?DEPLOYMENT_ID is required}"
: "${AWS_REGION:?AWS_REGION is required}"

case "$ISAS_ENVIRONMENT" in production|staging) ;; *) echo "ISAS_ENVIRONMENT must be production or staging" >&2; exit 64 ;; esac

case "$metric" in
  DeploymentStage|DeploymentRollback|ErrorBudgetRemainingPercent)
    case "$metric" in ErrorBudgetRemainingPercent) unit=Percent ;; *) unit=Count ;; esac
    datum="MetricName=$metric,Value=$value,Unit=$unit,Dimensions=[{Name=DeploymentId,Value=$DEPLOYMENT_ID}]"
    ;;
  WalArchiveAgeSeconds) datum="MetricName=$metric,Value=$value,Unit=Seconds" ;;
  *) datum="MetricName=$metric,Value=$value,Unit=Count" ;;
esac

aws cloudwatch put-metric-data \
  --region "$AWS_REGION" \
  --namespace "ISAS/$ISAS_ENVIRONMENT" \
  --metric-data "$datum"
