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
            [".", "RequestCount", ".", "."],
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
