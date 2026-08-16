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
      tls_policy        = aws_lb_listener.https.ssl_policy
      certificate_arn   = var.certificate_arn
    }
    ecs = {
      cluster = aws_ecs_cluster.main.name
      desired_tasks = {
        web    = var.minimum_web_tasks
        bff    = var.minimum_bff_tasks
        worker = 3
      }
      services = {
        web        = aws_ecs_service.web.name
        bff        = aws_ecs_service.bff.name
        web_canary = aws_ecs_service.web_canary.name
        bff_canary = aws_ecs_service.bff_canary.name
        worker     = aws_ecs_service.worker.name
        poolers    = { for key, service in aws_ecs_service.pooler : key => service.name }
      }
      migration_task_definition = aws_ecs_task_definition.migration.arn
      progressive_delivery = {
        listener_arn             = aws_lb_listener.https.arn
        bff_rule_arn             = aws_lb_listener_rule.bff.arn
        web_stable_tg            = aws_lb_target_group.web.arn
        web_canary_tg            = aws_lb_target_group.web_canary.arn
        bff_stable_tg            = aws_lb_target_group.bff.arn
        bff_canary_tg            = aws_lb_target_group.bff_canary.arn
        fast_burn_alarm          = aws_cloudwatch_composite_alarm.availability_fast_burn.alarm_name
        slow_burn_alarm          = aws_cloudwatch_composite_alarm.availability_slow_burn.alarm_name
        load_balancer_arn_suffix = aws_lb.main.arn_suffix
        blocking_alarms = concat(
          [
            aws_cloudwatch_composite_alarm.availability_fast_burn.alarm_name,
            aws_cloudwatch_composite_alarm.availability_slow_burn.alarm_name,
            aws_cloudwatch_metric_alarm.rds_wal_disk.alarm_name,
            aws_cloudwatch_metric_alarm.sync_queue_age.alarm_name,
          ],
          [for alarm in aws_cloudwatch_metric_alarm.dlq : alarm.alarm_name],
          [for alarm in aws_cloudwatch_metric_alarm.dlq_age : alarm.alarm_name],
          [for alarm in aws_cloudwatch_metric_alarm.operational : alarm.alarm_name],
        )
      }
      migration_network = {
        subnet_ids        = aws_subnet.app[*].id
        security_group_id = aws_security_group.pooler.id
      }
      image_digests = var.container_images
    }
    database = {
      cluster_identifier    = aws_rds_cluster.core.cluster_identifier
      arn                   = aws_rds_cluster.core.arn
      engine_version        = aws_rds_cluster.core.engine_version_actual
      endpoint              = aws_rds_cluster.core.endpoint
      reader_endpoint       = aws_rds_cluster.core.reader_endpoint
      port                  = aws_rds_cluster.core.port
      topology              = "multi-az-db-cluster-writer-1-reader-2"
      backup_retention_days = aws_rds_cluster.core.backup_retention_period
      pitr                  = true
      poolers               = sort(tolist(local.db_roles))
    }
    cognito = {
      user_pool_id         = aws_cognito_user_pool.main.id
      client_id            = aws_cognito_user_pool_client.web.id
      issuer               = "https://cognito-idp.${var.region}.amazonaws.com/${aws_cognito_user_pool.main.id}"
      managed_login_domain = var.cognito_custom_domain
    }
    storage = {
      session_table                       = aws_dynamodb_table.session_context.name
      private_object_bucket               = aws_s3_bucket.private_objects.id
      private_attachment_access_point_arn = aws_s3_access_point.private_attachments.arn
      quarantine_archive_bucket           = aws_s3_bucket.quarantine_archive.id
      shard_config_bucket                 = aws_s3_bucket.shard_config.id
      offline_map_bucket                  = aws_s3_bucket.offline_maps.id
      ops_evidence_bucket                 = aws_s3_bucket.ops_evidence.id
      backup_vault                        = aws_backup_vault.main.name
    }
    secrets = {
      actor_pseudonym_secret_arn = aws_secretsmanager_secret.actor_pseudonym.arn
    }
    queues = { for key, queue in aws_sqs_queue.main : key => {
      url     = queue.url
      dlq_arn = aws_sqs_queue.dead_letter[key].arn
      dlq_url = aws_sqs_queue.dead_letter[key].url
    } }
    kms_key_arns = {
      data          = aws_kms_key.data.arn
      token_session = aws_kms_key.token_session.arn
      object        = aws_kms_key.object.arn
      queue         = aws_kms_key.queue.arn
      backup        = aws_kms_key.backup.arn
      signing       = aws_kms_key.signing.arn
    }
    kms = {
      backing          = "AWS KMS FIPS validated HSM"
      custom_key_store = false
      multi_region     = false
    }
    shard_manifest = {
      version         = var.shard_manifest_version
      shard_id        = var.shard_id
      object_uri      = "s3://${aws_s3_bucket.shard_config.id}/${aws_s3_object.shard_manifest.key}"
      signature_uri   = "s3://${aws_s3_bucket.shard_config.id}/${aws_s3_object.shard_manifest.key}.sig"
      sha256          = sha256(jsonencode(local.shard_manifest))
      signing_key_arn = aws_kms_key.signing.arn
    }
    offline_map = {
      tileset_version          = var.offline_tileset_version
      archive_uri              = "s3://${aws_s3_bucket.offline_maps.id}/tilesets/${var.offline_tileset_version}/japan.pmtiles"
      archive_sha256           = var.offline_tileset_archive_sha256
      installation_limit_bytes = var.offline_map_installation_limit_bytes
      pack_retention_days      = var.offline_map_pack_retention_days
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
