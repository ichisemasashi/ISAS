resource "aws_sns_topic" "incident" {
  name              = "${local.name}-incident"
  kms_master_key_id = aws_kms_key.queue.id
}

resource "aws_sns_topic_subscription" "email" {
  count = var.alert_email == null ? 0 : 1

  topic_arn = aws_sns_topic.incident.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

resource "aws_cloudwatch_log_metric_filter" "auth_rejection" {
  name           = "${local.name}-authorization-rejection"
  pattern        = "{ $.event = \"authorization.rejected\" }"
  log_group_name = aws_cloudwatch_log_group.service["bff"].name

  metric_transformation {
    name      = "AuthorizationRejections"
    namespace = "ISAS/${var.environment}"
    value     = "1"
  }
}

resource "aws_cloudwatch_log_metric_filter" "sync_rejection" {
  name           = "${local.name}-sync-authorization-rejection"
  pattern        = "{ $.event = \"sync_push_completed\" && $.rejected > 0 }"
  log_group_name = aws_cloudwatch_log_group.service["bff"].name

  metric_transformation {
    name      = "SyncAuthorizationRejections"
    namespace = "ISAS/${var.environment}"
    value     = "$.rejected"
  }
}

resource "aws_cloudwatch_log_metric_filter" "object_integrity" {
  for_each = {
    missing = { field = "missing", metric = "AttachmentMissingObjects" }
    orphan  = { field = "orphanBacklog", metric = "AttachmentOrphanBacklog" }
  }

  name           = "${local.name}-${each.key}-object"
  pattern        = "{ $.event = \"attachment_storage_reconciled\" && $.${each.value.field} > 0 }"
  log_group_name = aws_cloudwatch_log_group.service["bff"].name

  metric_transformation {
    name      = each.value.metric
    namespace = "ISAS/${var.environment}"
    value     = "$.${each.value.field}"
  }
}

resource "aws_cloudwatch_log_metric_filter" "telemetry_dropped" {
  name           = "${local.name}-telemetry-dropped"
  pattern        = "?dropped ?refused ?exporterhelper"
  log_group_name = aws_cloudwatch_log_group.service["adot"].name

  metric_transformation {
    name      = "TelemetryDroppedItems"
    namespace = "ISAS/${var.environment}"
    value     = "1"
  }
}

resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  alarm_name          = "${local.name}-alb-5xx"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "HTTPCode_ELB_5XX_Count"
  namespace           = "AWS/ApplicationELB"
  period              = 60
  statistic           = "Sum"
  threshold           = 5
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.incident.arn]

  dimensions = {
    LoadBalancer = aws_lb.main.arn_suffix
  }
}

resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  alarm_name          = "${local.name}-rds-cpu"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 5
  metric_name         = "CPUUtilization"
  namespace           = "AWS/RDS"
  period              = 60
  statistic           = "Average"
  threshold           = 80
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.incident.arn]

  dimensions = {
    DBClusterIdentifier = aws_rds_cluster.core.cluster_identifier
  }
}

resource "aws_cloudwatch_metric_alarm" "dlq" {
  for_each = local.queue_names

  alarm_name          = "${local.name}-${each.key}-dlq-not-empty"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Maximum"
  threshold           = 0
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.incident.arn]

  dimensions = {
    QueueName = aws_sqs_queue.dead_letter[each.key].name
  }
}

resource "aws_cloudwatch_metric_alarm" "dlq_age" {
  for_each = local.queue_names

  alarm_name          = "${local.name}-${each.key}-dlq-age"
  alarm_description   = "owner=ISAS-JP-OnCall severity=${each.key == "authorization-revocation" || each.key == "audit-anchor" ? "Sev-1" : "Sev-2"} runbook=docs/operations/障害対応手順.md"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateAgeOfOldestMessage"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Maximum"
  threshold           = each.key == "authorization-revocation" || each.key == "audit-anchor" ? 60 : each.key == "offline-sync" ? 300 : 604800
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.incident.arn]

  dimensions = { QueueName = aws_sqs_queue.dead_letter[each.key].name }
}

resource "aws_cloudwatch_metric_alarm" "sync_queue_age" {
  alarm_name          = "${local.name}-offline-sync-oldest-message"
  alarm_description   = "owner=ISAS-JP-OnCall severity=Sev-2 runbook=docs/operations/障害対応手順.md"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "ApproximateAgeOfOldestMessage"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Maximum"
  threshold           = 300
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.incident.arn]
  dimensions          = { QueueName = aws_sqs_queue.main["offline-sync"].name }
}

resource "aws_cloudwatch_metric_alarm" "rds_wal_disk" {
  alarm_name          = "${local.name}-wal-disk-usage"
  alarm_description   = "owner=ISAS-JP-OnCall severity=Sev-1 runbook=docs/operations/障害対応手順.md"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "TransactionLogsDiskUsage"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Maximum"
  threshold           = 5368709120
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.incident.arn]
  dimensions          = { DBClusterIdentifier = aws_rds_cluster.core.cluster_identifier }
}

locals {
  operational_metric_alarms = {
    wal_archive_age       = { metric = "WalArchiveAgeSeconds", threshold = 900, severity = "Sev-1" }
    audit_chain_mismatch  = { metric = "AuditChainMismatches", threshold = 0, severity = "Sev-1" }
    object_missing        = { metric = "AttachmentMissingObjects", threshold = 0, severity = "Sev-2" }
    object_orphan_backlog = { metric = "AttachmentOrphanBacklog", threshold = 0, severity = "Sev-2" }
    sync_rejections       = { metric = "SyncAuthorizationRejections", threshold = 0, severity = "Sev-2" }
    telemetry_dropped     = { metric = "TelemetryDroppedItems", threshold = 0, severity = "Sev-2" }
  }
}

resource "aws_cloudwatch_metric_alarm" "operational" {
  for_each = local.operational_metric_alarms

  alarm_name          = "${local.name}-${replace(each.key, "_", "-")}"
  alarm_description   = "owner=ISAS-JP-OnCall severity=${each.value.severity} jurisdiction=JP runbook=docs/operations/障害対応手順.md"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = each.value.metric
  namespace           = "ISAS/${var.environment}"
  period              = 60
  statistic           = "Maximum"
  threshold           = each.value.threshold
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.incident.arn]
}

resource "aws_cloudwatch_metric_alarm" "availability_fast_burn" {
  for_each = { short = { period = 300, evaluations = 1 }, long = { period = 3600, evaluations = 1 } }

  alarm_name          = "${local.name}-availability-14x-${each.key}"
  alarm_description   = "99.5% availability error budget fast burn; owner=ISAS-JP-OnCall severity=Sev-1"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = each.value.evaluations
  threshold           = 0.072
  treat_missing_data  = "breaching"

  metric_query {
    id          = "error_rate"
    expression  = "IF(requests>0,(target_errors+alb_errors)/requests,0)"
    label       = "Availability error rate"
    return_data = true
  }
  metric_query {
    id = "target_errors"
    metric {
      metric_name = "HTTPCode_Target_5XX_Count"
      namespace   = "AWS/ApplicationELB"
      period      = each.value.period
      stat        = "Sum"
      dimensions  = { LoadBalancer = aws_lb.main.arn_suffix }
    }
  }
  metric_query {
    id = "alb_errors"
    metric {
      metric_name = "HTTPCode_ELB_5XX_Count"
      namespace   = "AWS/ApplicationELB"
      period      = each.value.period
      stat        = "Sum"
      dimensions  = { LoadBalancer = aws_lb.main.arn_suffix }
    }
  }
  metric_query {
    id = "requests"
    metric {
      metric_name = "RequestCount"
      namespace   = "AWS/ApplicationELB"
      period      = each.value.period
      stat        = "Sum"
      dimensions  = { LoadBalancer = aws_lb.main.arn_suffix }
    }
  }
}

resource "aws_cloudwatch_composite_alarm" "availability_fast_burn" {
  alarm_name        = "${local.name}-availability-fast-burn"
  alarm_description = "14.4x burn in both 5m and 1h windows; automatic deployment pause signal"
  alarm_rule        = "ALARM(\"${aws_cloudwatch_metric_alarm.availability_fast_burn["short"].alarm_name}\") AND ALARM(\"${aws_cloudwatch_metric_alarm.availability_fast_burn["long"].alarm_name}\")"
  alarm_actions     = [aws_sns_topic.incident.arn]
}

resource "aws_cloudwatch_metric_alarm" "availability_slow_burn" {
  for_each = { short = { period = 1800 }, long = { period = 21600 } }

  alarm_name          = "${local.name}-availability-6x-${each.key}"
  alarm_description   = "99.5% availability error budget slow burn; owner=ISAS-JP-OnCall severity=Sev-2"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  threshold           = 0.03
  treat_missing_data  = "breaching"
  metric_query {
    id          = "error_rate"
    expression  = "IF(requests>0,(target_errors+alb_errors)/requests,0)"
    return_data = true
  }
  metric_query {
    id = "target_errors"
    metric {
      metric_name = "HTTPCode_Target_5XX_Count"
      namespace   = "AWS/ApplicationELB"
      period      = each.value.period
      stat        = "Sum"
      dimensions  = { LoadBalancer = aws_lb.main.arn_suffix }
    }
  }
  metric_query {
    id = "alb_errors"
    metric {
      metric_name = "HTTPCode_ELB_5XX_Count"
      namespace   = "AWS/ApplicationELB"
      period      = each.value.period
      stat        = "Sum"
      dimensions  = { LoadBalancer = aws_lb.main.arn_suffix }
    }
  }
  metric_query {
    id = "requests"
    metric {
      metric_name = "RequestCount"
      namespace   = "AWS/ApplicationELB"
      period      = each.value.period
      stat        = "Sum"
      dimensions  = { LoadBalancer = aws_lb.main.arn_suffix }
    }
  }
}

resource "aws_cloudwatch_composite_alarm" "availability_slow_burn" {
  alarm_name        = "${local.name}-availability-slow-burn"
  alarm_description = "6x burn in both 30m and 6h windows; release freeze signal"
  alarm_rule        = "ALARM(\"${aws_cloudwatch_metric_alarm.availability_slow_burn["short"].alarm_name}\") AND ALARM(\"${aws_cloudwatch_metric_alarm.availability_slow_burn["long"].alarm_name}\")"
  alarm_actions     = [aws_sns_topic.incident.arn]
}

resource "aws_cloudwatch_dashboard" "overview" {
  dashboard_name = "ISAS-jp-${var.environment == "production" ? "prod" : "stg"}-overview"

  dashboard_body = jsonencode({
    widgets = [
      {
        type   = "metric"
        x      = 0
        y      = 0
        width  = 12
        height = 6
        properties = {
          title  = "ALB responses"
          region = var.region
          metrics = [
            ["AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count", "LoadBalancer", aws_lb.main.arn_suffix],
            [".", "HTTPCode_Target_5XX_Count", ".", "."],
            [".", "RequestCount", ".", "."],
          ]
        }
      },
      {
        type = "metric", x = 0, y = 6, width = 12, height = 6,
        properties = { title = "Availability / latency SLO", region = var.region, view = "timeSeries",
          metrics = [
            ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", aws_lb.main.arn_suffix, { stat = "p95" }],
            [".", "HealthyHostCount", ".", ".", { stat = "Minimum", yAxis = "right" }],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 6, width = 12, height = 6,
        properties = { title = "Synchronization / revocation queue age", region = var.region,
          metrics = [for key in sort(tolist(local.queue_names)) : ["AWS/SQS", "ApproximateAgeOfOldestMessage", "QueueName", aws_sqs_queue.main[key].name]]
        }
      },
      {
        type = "metric", x = 0, y = 12, width = 12, height = 6,
        properties = { title = "WAL / audit safety", region = var.region,
          metrics = [
            ["AWS/RDS", "TransactionLogsDiskUsage", "DBClusterIdentifier", aws_rds_cluster.core.cluster_identifier],
            ["ISAS/${var.environment}", "WalArchiveAgeSeconds"],
            [".", "AuditChainMismatches"],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 12, width = 12, height = 6,
        properties = { title = "Object integrity / telemetry health", region = var.region,
          metrics = [
            ["ISAS/${var.environment}", "AttachmentMissingObjects"],
            [".", "AttachmentOrphanBacklog"],
            [".", "TelemetryDroppedItems"],
          ]
        }
      },
      {
        type = "metric", x = 0, y = 18, width = 24, height = 6,
        properties = { title = "Deploy / rollback", region = var.region,
          metrics = [
            ["ISAS/${var.environment}", "DeploymentStage", "DeploymentId", var.deployment_id],
            [".", "DeploymentRollback", ".", "."],
            [".", "ErrorBudgetRemainingPercent", ".", "."],
          ]
        }
      },
      {
        type = "metric", x = 0, y = 24, width = 24, height = 6,
        properties = {
          title  = "28-day availability error budget remaining (99.5% SLO)"
          region = var.region
          view   = "singleValue"
          period = 2419200
          start  = "-P28D"
          metrics = [
            [{ expression = "IF(requests>0,100*(1-(((target_errors+alb_errors)/requests)/0.005)),0)", label = "Error budget remaining %", id = "budget" }],
            ["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", "LoadBalancer", aws_lb.main.arn_suffix, { id = "target_errors", stat = "Sum", visible = false }],
            [".", "HTTPCode_ELB_5XX_Count", ".", ".", { id = "alb_errors", stat = "Sum", visible = false }],
            [".", "RequestCount", ".", ".", { id = "requests", stat = "Sum", visible = false }],
          ]
        }
      },
      {
        type   = "metric"
        x      = 12
        y      = 0
        width  = 12
        height = 6
        properties = {
          title  = "RDS"
          region = var.region
          metrics = [
            ["AWS/RDS", "CPUUtilization", "DBClusterIdentifier", aws_rds_cluster.core.cluster_identifier],
            [".", "DatabaseConnections", ".", "."],
          ]
        }
      },
    ]
  })
}
