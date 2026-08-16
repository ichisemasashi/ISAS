#!/bin/sh
set -eu

usage() {
  echo "usage: $0 staging|prepare|5|25|100|rollback|finalize DEPLOYMENT_MANIFEST BUILD_MANIFEST" >&2
  echo "required env: STATE_URI=s3://.../releases/...json AWS_REGION" >&2
  exit 64
}

[ "$#" -eq 3 ] || usage
command=$1
deployment_manifest=$2
build_manifest=$3
: "${STATE_URI:?STATE_URI is required}"
: "${AWS_REGION:?AWS_REGION is required}"
case "$STATE_URI" in s3://*/releases/*.json) ;; *) echo "STATE_URI must be an S3 releases/*.json object" >&2; exit 64 ;; esac

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
temporary_directory=$(mktemp -d)
trap 'find "$temporary_directory" -depth -delete' EXIT HUP INT TERM
state_file="$temporary_directory/state.json"
state_argument=-
if aws s3 cp "$STATE_URI" "$state_file" --region "$AWS_REGION" --only-show-errors 2>/dev/null; then state_argument=$state_file; fi
if [ "$command" != prepare ] && [ "$command" != staging ] && [ "$state_argument" = - ]; then echo "delivery state does not exist" >&2; exit 1; fi
node "$script_directory/progressive-delivery-policy.mjs" "$command" "$deployment_manifest" "$build_manifest" "$state_argument"

cluster=$(jq -er '.ecs.cluster' "$deployment_manifest")
deployment_id=$(jq -er '.deployment_id' "$deployment_manifest")
deployment_environment=$(jq -er '.environment' "$deployment_manifest")
if [ "$command" = staging ]; then
  [ "$deployment_environment" = staging ] || { echo "staging command requires a staging manifest" >&2; exit 64; }
else
  [ "$deployment_environment" = production ] || { echo "$command requires a production manifest" >&2; exit 64; }
fi
listener_arn=$(jq -er '.ecs.progressive_delivery.listener_arn' "$deployment_manifest")
bff_rule_arn=$(jq -er '.ecs.progressive_delivery.bff_rule_arn' "$deployment_manifest")
web_stable_tg=$(jq -er '.ecs.progressive_delivery.web_stable_tg' "$deployment_manifest")
web_canary_tg=$(jq -er '.ecs.progressive_delivery.web_canary_tg' "$deployment_manifest")
bff_stable_tg=$(jq -er '.ecs.progressive_delivery.bff_stable_tg' "$deployment_manifest")
bff_canary_tg=$(jq -er '.ecs.progressive_delivery.bff_canary_tg' "$deployment_manifest")
web_service=$(jq -er '.ecs.services.web' "$deployment_manifest")
bff_service=$(jq -er '.ecs.services.bff' "$deployment_manifest")
web_canary_service=$(jq -er '.ecs.services.web_canary' "$deployment_manifest")
bff_canary_service=$(jq -er '.ecs.services.bff_canary' "$deployment_manifest")

write_actions() {
  stable=$1
  canary=$2
  canary_weight=$3
  output=$4
  jq -n --arg stable "$stable" --arg canary "$canary" --argjson canaryWeight "$canary_weight" '[{
    Type: "forward",
    ForwardConfig: {
      TargetGroups: [
        {TargetGroupArn: $stable, Weight: (100 - $canaryWeight)},
        {TargetGroupArn: $canary, Weight: $canaryWeight}
      ],
      TargetGroupStickinessConfig: {Enabled: false, DurationSeconds: 1}
    }
  }]' >"$output"
}

apply_weights() {
  weight=$1
  write_actions "$web_stable_tg" "$web_canary_tg" "$weight" "$temporary_directory/web-actions.json"
  write_actions "$bff_stable_tg" "$bff_canary_tg" "$weight" "$temporary_directory/bff-actions.json"
  if ! aws elbv2 modify-listener --region "$AWS_REGION" --listener-arn "$listener_arn" --default-actions "file://$temporary_directory/web-actions.json" >/dev/null \
    || ! aws elbv2 modify-rule --region "$AWS_REGION" --rule-arn "$bff_rule_arn" --actions "file://$temporary_directory/bff-actions.json" >/dev/null; then
    write_actions "$web_stable_tg" "$web_canary_tg" 0 "$temporary_directory/web-rollback.json"
    write_actions "$bff_stable_tg" "$bff_canary_tg" 0 "$temporary_directory/bff-rollback.json"
    aws elbv2 modify-listener --region "$AWS_REGION" --listener-arn "$listener_arn" --default-actions "file://$temporary_directory/web-rollback.json" >/dev/null || true
    aws elbv2 modify-rule --region "$AWS_REGION" --rule-arn "$bff_rule_arn" --actions "file://$temporary_directory/bff-rollback.json" >/dev/null || true
    echo "traffic update failed; rollback to stable requested" >&2
    exit 1
  fi
}

task_definition_for_service() {
  aws ecs describe-services --region "$AWS_REGION" --cluster "$cluster" --services "$1" --query 'services[0].taskDefinition' --output text
}

clone_task_definition() {
  source_task=$1
  container_name=$2
  image=$3
  output=$4
  aws ecs describe-task-definition --region "$AWS_REGION" --task-definition "$source_task" --query taskDefinition >"$temporary_directory/source-task.json"
  jq --arg container "$container_name" --arg image "$image" '
    del(.taskDefinitionArn,.revision,.status,.requiresAttributes,.compatibilities,.registeredAt,.registeredBy)
    | .containerDefinitions |= map(if .name == $container then .image = $image else . end)
    | if any(.containerDefinitions[]; .name == $container and .image == $image) then . else error("container not found") end
  ' "$temporary_directory/source-task.json" >"$temporary_directory/register-task.json"
  aws ecs register-task-definition --region "$AWS_REGION" --cli-input-json "file://$temporary_directory/register-task.json" --query 'taskDefinition.taskDefinitionArn' --output text >"$output"
}

wait_service() {
  aws ecs wait services-stable --region "$AWS_REGION" --cluster "$cluster" --services "$1"
}

save_state() {
  aws s3 cp "$state_file" "$STATE_URI" --region "$AWS_REGION" --only-show-errors
}

set_stage() {
  stage=$1
  jq --arg stage "$stage" --arg updatedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '.stage=$stage | .updated_at=$updatedAt' "$state_file" >"$temporary_directory/next-state.json"
  mv "$temporary_directory/next-state.json" "$state_file"
  save_state
}

if [ "$command" = staging ]; then
  apply_weights 0
  previous_web=$(task_definition_for_service "$web_service")
  previous_bff=$(task_definition_for_service "$bff_service")
  previous_migration=$(jq -er '.ecs.migration_task_definition' "$deployment_manifest")
  web_image=$(jq -er '.artifacts[] | select(.name == "web") | .reference' "$build_manifest")
  bff_image=$(jq -er '.artifacts[] | select(.name == "bff") | .reference' "$build_manifest")
  migration_image=$(jq -er '.artifacts[] | select(.name == "migration") | .reference' "$build_manifest")
  clone_task_definition "$previous_web" web "$web_image" "$temporary_directory/web-task-arn"
  clone_task_definition "$previous_bff" bff "$bff_image" "$temporary_directory/bff-task-arn"
  clone_task_definition "$previous_migration" migration "$migration_image" "$temporary_directory/migration-task-arn"
  candidate_web=$(cat "$temporary_directory/web-task-arn")
  candidate_bff=$(cat "$temporary_directory/bff-task-arn")
  candidate_migration=$(cat "$temporary_directory/migration-task-arn")
  jq -n \
    --argjson subnets "$(jq -c '.ecs.migration_network.subnet_ids' "$deployment_manifest")" \
    --arg group "$(jq -er '.ecs.migration_network.security_group_id' "$deployment_manifest")" \
    '{awsvpcConfiguration:{subnets:$subnets,securityGroups:[$group],assignPublicIp:"DISABLED"}}' >"$temporary_directory/migration-network.json"
  aws ecs run-task --region "$AWS_REGION" --cluster "$cluster" --launch-type FARGATE \
    --task-definition "$candidate_migration" --network-configuration "file://$temporary_directory/migration-network.json" \
    >"$temporary_directory/migration-run.json"
  migration_task=$(jq -er '.tasks[0].taskArn' "$temporary_directory/migration-run.json")
  aws ecs wait tasks-stopped --region "$AWS_REGION" --cluster "$cluster" --tasks "$migration_task"
  aws ecs describe-tasks --region "$AWS_REGION" --cluster "$cluster" --tasks "$migration_task" >"$temporary_directory/migration-result.json"
  jq -e '.tasks[0].containers[] | select(.name == "migration") | .exitCode == 0' "$temporary_directory/migration-result.json" >/dev/null \
    || { echo "staging migration failed; application was not updated" >&2; exit 1; }
  aws ecs update-service --region "$AWS_REGION" --cluster "$cluster" --service "$web_service" --task-definition "$candidate_web" --force-new-deployment >/dev/null
  aws ecs update-service --region "$AWS_REGION" --cluster "$cluster" --service "$bff_service" --task-definition "$candidate_bff" --force-new-deployment >/dev/null
  wait_service "$web_service"
  wait_service "$bff_service"
  jq -n \
    --arg deploymentId "$deployment_id" --arg sourceCommit "$(jq -er '.source_commit' "$build_manifest")" \
    --arg artifactSetDigest "$(jq -er '.artifact_set_digest' "$build_manifest")" \
    --arg previousWeb "$previous_web" --arg previousBff "$previous_bff" \
    --arg candidateWeb "$candidate_web" --arg candidateBff "$candidate_bff" \
    --arg createdAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg candidateMigration "$candidate_migration" \
    '{schema_version:1,stage:"staging",deployment_id:$deploymentId,source_commit:$sourceCommit,artifact_set_digest:$artifactSetDigest,
      previous:{web:$previousWeb,bff:$previousBff},candidate:{web:$candidateWeb,bff:$candidateBff,migration:$candidateMigration},created_at:$createdAt,updated_at:$createdAt}' >"$state_file"
  save_state
elif [ "$command" = prepare ]; then
  apply_weights 0
  previous_web=$(task_definition_for_service "$web_service")
  previous_bff=$(task_definition_for_service "$bff_service")
  previous_migration=$(jq -er '.ecs.migration_task_definition' "$deployment_manifest")
  web_image=$(jq -er '.artifacts[] | select(.name == "web") | .reference' "$build_manifest")
  bff_image=$(jq -er '.artifacts[] | select(.name == "bff") | .reference' "$build_manifest")
  migration_image=$(jq -er '.artifacts[] | select(.name == "migration") | .reference' "$build_manifest")
  clone_task_definition "$previous_web" web "$web_image" "$temporary_directory/web-task-arn"
  clone_task_definition "$previous_bff" bff "$bff_image" "$temporary_directory/bff-task-arn"
  clone_task_definition "$previous_migration" migration "$migration_image" "$temporary_directory/migration-task-arn"
  candidate_web=$(cat "$temporary_directory/web-task-arn")
  candidate_bff=$(cat "$temporary_directory/bff-task-arn")
  candidate_migration=$(cat "$temporary_directory/migration-task-arn")
  jq -n \
    --argjson subnets "$(jq -c '.ecs.migration_network.subnet_ids' "$deployment_manifest")" \
    --arg group "$(jq -er '.ecs.migration_network.security_group_id' "$deployment_manifest")" \
    '{awsvpcConfiguration:{subnets:$subnets,securityGroups:[$group],assignPublicIp:"DISABLED"}}' >"$temporary_directory/migration-network.json"
  aws ecs run-task --region "$AWS_REGION" --cluster "$cluster" --launch-type FARGATE \
    --task-definition "$candidate_migration" --network-configuration "file://$temporary_directory/migration-network.json" \
    >"$temporary_directory/migration-run.json"
  migration_task=$(jq -er '.tasks[0].taskArn' "$temporary_directory/migration-run.json")
  aws ecs wait tasks-stopped --region "$AWS_REGION" --cluster "$cluster" --tasks "$migration_task"
  aws ecs describe-tasks --region "$AWS_REGION" --cluster "$cluster" --tasks "$migration_task" >"$temporary_directory/migration-result.json"
  jq -e '.tasks[0].containers[] | select(.name == "migration") | .exitCode == 0' "$temporary_directory/migration-result.json" >/dev/null \
    || { echo "production migration failed; canary was not updated" >&2; exit 1; }
  aws ecs update-service --region "$AWS_REGION" --cluster "$cluster" --service "$web_canary_service" --task-definition "$candidate_web" --desired-count 1 --force-new-deployment >/dev/null
  aws ecs update-service --region "$AWS_REGION" --cluster "$cluster" --service "$bff_canary_service" --task-definition "$candidate_bff" --desired-count 1 --force-new-deployment >/dev/null
  wait_service "$web_canary_service"
  wait_service "$bff_canary_service"
  aws elbv2 wait target-in-service --region "$AWS_REGION" --target-group-arn "$web_canary_tg"
  aws elbv2 wait target-in-service --region "$AWS_REGION" --target-group-arn "$bff_canary_tg"
  jq -n \
    --arg deploymentId "$deployment_id" \
    --arg sourceCommit "$(jq -er '.source_commit' "$build_manifest")" \
    --arg artifactSetDigest "$(jq -er '.artifact_set_digest' "$build_manifest")" \
    --arg previousWeb "$previous_web" --arg previousBff "$previous_bff" \
    --arg candidateWeb "$candidate_web" --arg candidateBff "$candidate_bff" --arg candidateMigration "$candidate_migration" \
    --arg createdAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    '{schema_version:1,stage:"prepared",deployment_id:$deploymentId,source_commit:$sourceCommit,artifact_set_digest:$artifactSetDigest,
      previous:{web:$previousWeb,bff:$previousBff},candidate:{web:$candidateWeb,bff:$candidateBff,migration:$candidateMigration},created_at:$createdAt,updated_at:$createdAt}' >"$state_file"
  save_state
elif [ "$command" = rollback ]; then
  apply_weights 0
  set_stage rolled_back
elif [ "$command" = finalize ]; then
  : "${FINALIZE_MIN_AGE_SECONDS:=86400}"
  case "$FINALIZE_MIN_AGE_SECONDS" in *[!0-9]*) echo "FINALIZE_MIN_AGE_SECONDS must be numeric" >&2; exit 64 ;; esac
  stage_updated_epoch=$(node -e 'const value=Date.parse(process.argv[1]); if(!Number.isFinite(value)) process.exit(2); process.stdout.write(String(Math.floor(value/1000)))' "$(jq -er '.updated_at' "$state_file")")
  stage_age_seconds=$(($(date -u +%s) - stage_updated_epoch))
  [ "$stage_age_seconds" -ge "$FINALIZE_MIN_AGE_SECONDS" ] || { echo "100% rollback slot must remain available for at least $FINALIZE_MIN_AGE_SECONDS seconds" >&2; exit 1; }
  candidate_web=$(jq -er '.candidate.web' "$state_file")
  candidate_bff=$(jq -er '.candidate.bff' "$state_file")
  aws ecs update-service --region "$AWS_REGION" --cluster "$cluster" --service "$web_service" --task-definition "$candidate_web" --force-new-deployment >/dev/null
  aws ecs update-service --region "$AWS_REGION" --cluster "$cluster" --service "$bff_service" --task-definition "$candidate_bff" --force-new-deployment >/dev/null
  wait_service "$web_service"
  wait_service "$bff_service"
  apply_weights 0
  set_stage finalized
else
  apply_weights "$command"
  set_stage "$command"
fi

case "$command" in prepare) stage_metric=0 ;; staging) stage_metric=100 ;; rollback) stage_metric=-1 ;; finalize) stage_metric=100 ;; *) stage_metric=$command ;; esac
ISAS_ENVIRONMENT=${ISAS_ENVIRONMENT:-production} DEPLOYMENT_ID=$deployment_id \
  "$script_directory/publish-operational-metric.sh" DeploymentStage "$stage_metric" || true
echo "progressive delivery: $deployment_id stage $command complete"
