output "deployment_manifest" {
  description = "Non-secret runtime inventory consumed by staging acceptance."
  value = {
    schema_version = 1
    deployment_id  = var.deployment_id
    environment    = var.environment
    jurisdiction   = "JP"
    account_id     = data.aws_caller_identity.current.account_id
    region         = var.region
    availability_zones = [for index, subnet in aws_subnet.app : {
      name      = subnet.availability_zone
      id        = subnet.availability_zone_id
      subnet_id = subnet.id
    }]
    application_url = "https://${var.domain_name}"
    ingress = {
      load_balancer_arn = aws_lb.main.arn
      waf_acl_arn       = aws_wafv2_web_acl.main.arn
    }
    ecs = {
      cluster = aws_ecs_cluster.main.name
      services = {
        web     = aws_ecs_service.web.name
        bff     = aws_ecs_service.bff.name
        worker  = aws_ecs_service.worker.name
        poolers = { for key, service in aws_ecs_service.pooler : key => service.name }
      }
      migration_task_definition = aws_ecs_task_definition.migration.arn
      migration_network = {
        subnet_ids        = aws_subnet.app[*].id
        security_group_id = aws_security_group.pooler.id
      }
      image_digests = var.container_images
    }
    database = {
      cluster_identifier = aws_rds_cluster.core.cluster_identifier
      arn                = aws_rds_cluster.core.arn
      engine_version     = aws_rds_cluster.core.engine_version_actual
      endpoint           = aws_rds_cluster.core.endpoint
      port               = aws_rds_cluster.core.port
    }
    cognito = {
      user_pool_id         = aws_cognito_user_pool.main.id
      client_id            = aws_cognito_user_pool_client.web.id
      issuer               = "https://cognito-idp.${var.region}.amazonaws.com/${aws_cognito_user_pool.main.id}"
      managed_login_domain = var.cognito_custom_domain
    }
    storage = {
      session_table         = aws_dynamodb_table.session_context.name
      private_object_bucket = aws_s3_bucket.private_objects.id
      ops_evidence_bucket   = aws_s3_bucket.ops_evidence.id
      backup_vault          = aws_backup_vault.main.name
    }
    secrets = {
      actor_pseudonym_secret_arn = aws_secretsmanager_secret.actor_pseudonym.arn
    }
    queues = { for key, queue in aws_sqs_queue.main : key => {
      url     = queue.url
      dlq_arn = aws_sqs_queue.dead_letter[key].arn
    } }
    kms_key_arns = {
      data          = aws_kms_key.data.arn
      token_session = aws_kms_key.token_session.arn
      object        = aws_kms_key.object.arn
      queue         = aws_kms_key.queue.arn
      backup        = aws_kms_key.backup.arn
      signing       = aws_kms_key.signing.arn
    }
    operations = {
      dashboard_name = aws_cloudwatch_dashboard.overview.dashboard_name
      incident_topic = aws_sns_topic.incident.arn
    }
    github_deploy_role_arn = aws_iam_role.github_deploy.arn
  }
}

output "rds_master_secret_arn" {
  description = "Secret ARN only; secret value is never emitted."
  value       = aws_rds_cluster.core.master_user_secret[0].secret_arn
}
