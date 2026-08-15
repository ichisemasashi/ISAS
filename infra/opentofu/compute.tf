data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ecs_execution" {
  name               = "${local.name}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "ecs_execution" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "ecs_execution_secrets" {
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = concat([for secret in aws_secretsmanager_secret.database_role : secret.arn], [aws_rds_cluster.core.master_user_secret[0].secret_arn])
  }

  statement {
    actions   = ["kms:Decrypt"]
    resources = [aws_kms_key.data.arn]
  }
}

resource "aws_iam_role_policy" "ecs_execution_secrets" {
  name   = "database-secret-injection"
  role   = aws_iam_role.ecs_execution.id
  policy = data.aws_iam_policy_document.ecs_execution_secrets.json
}

resource "aws_iam_role" "application" {
  name               = "${local.name}-application"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

data "aws_iam_policy_document" "application" {
  statement {
    actions = [
      "dynamodb:DeleteItem",
      "dynamodb:GetItem",
      "dynamodb:PutItem",
      "dynamodb:Query",
      "dynamodb:UpdateItem",
    ]
    resources = [aws_dynamodb_table.session_context.arn]
  }

  statement {
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.private_objects.arn}/*"]
  }

  statement {
    actions = [
      "sqs:ChangeMessageVisibility",
      "sqs:DeleteMessage",
      "sqs:GetQueueAttributes",
      "sqs:ReceiveMessage",
      "sqs:SendMessage",
    ]
    resources = concat(
      [for queue in aws_sqs_queue.main : queue.arn],
      [for queue in aws_sqs_queue.dead_letter : queue.arn],
    )
  }

  statement {
    actions = [
      "cognito-idp:GlobalSignOut",
      "cognito-idp:RevokeToken",
    ]
    resources = [aws_cognito_user_pool.main.arn]
  }

  statement {
    actions   = ["kms:Decrypt", "kms:Encrypt", "kms:GenerateDataKey"]
    resources = [aws_kms_key.data.arn, aws_kms_key.object.arn, aws_kms_key.queue.arn]
  }

  statement {
    actions   = ["xray:PutTraceSegments", "xray:PutTelemetryRecords", "cloudwatch:PutMetricData"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "application" {
  name   = "least-privilege-runtime"
  role   = aws_iam_role.application.id
  policy = data.aws_iam_policy_document.application.json
}

resource "aws_iam_role" "migration" {
  name               = "${local.name}-migration"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy" "migration" {
  name = "read-rds-managed-secret"
  role = aws_iam_role.migration.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue", "kms:Decrypt"]
      Resource = [aws_rds_cluster.core.master_user_secret[0].secret_arn, aws_kms_key.data.arn]
    }]
  })
}

resource "aws_cloudwatch_log_group" "service" {
  for_each = toset(["web", "bff", "worker", "pgbouncer", "migration", "adot"])

  name              = "/isas/${var.deployment_id}/${each.key}"
  retention_in_days = each.key == "adot" ? 30 : 90
  kms_key_id        = aws_kms_key.queue.arn
}

resource "aws_ecs_cluster" "main" {
  name = local.name

  setting {
    name  = "containerInsights"
    value = "enhanced"
  }
}

resource "aws_service_discovery_private_dns_namespace" "main" {
  name = "${local.name}.internal"
  vpc  = aws_vpc.main.id
}

resource "aws_service_discovery_service" "pooler" {
  for_each = local.db_roles

  name = "pgbouncer-${each.key}"

  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.main.id

    dns_records {
      ttl  = 10
      type = "A"
    }

    routing_policy = "MULTIVALUE"
  }

  health_check_custom_config {}
}

resource "aws_ecs_task_definition" "web" {
  family                   = "${local.name}-web"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.application.arn

  container_definitions = jsonencode([{
    name                   = "web"
    image                  = var.container_images.web
    essential              = true
    portMappings           = [{ containerPort = 8080, hostPort = 8080, protocol = "tcp" }]
    environment            = local.container_environment
    readonlyRootFilesystem = true
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.service["web"].name
        awslogs-region        = var.region
        awslogs-stream-prefix = "web"
      }
    }
  }])
}

resource "aws_ecs_task_definition" "bff" {
  family                   = "${local.name}-bff"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 1024
  memory                   = 2048
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.application.arn

  container_definitions = jsonencode([
    {
      name         = "bff"
      image        = var.container_images.bff
      essential    = true
      portMappings = [{ containerPort = 3000, hostPort = 3000, protocol = "tcp" }]
      environment = concat(local.container_environment, [
        { name = "DATABASE_HOST", value = "pgbouncer-p1.${local.name}.internal" },
        { name = "DATABASE_PORT", value = "6432" },
        { name = "SESSION_TABLE", value = aws_dynamodb_table.session_context.name },
        { name = "OBJECT_BUCKET", value = aws_s3_bucket.private_objects.id },
        { name = "COGNITO_USER_POOL_ID", value = aws_cognito_user_pool.main.id },
        { name = "COGNITO_CLIENT_ID", value = aws_cognito_user_pool_client.web.id },
      ])
      readonlyRootFilesystem = true
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.service["bff"].name
          awslogs-region        = var.region
          awslogs-stream-prefix = "bff"
        }
      }
    },
    {
      name                   = "adot"
      image                  = var.container_images.adot
      essential              = true
      command                = ["--config=/etc/ecs/ecs-default-config.yaml"]
      readonlyRootFilesystem = true
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.service["adot"].name
          awslogs-region        = var.region
          awslogs-stream-prefix = "bff"
        }
      }
    },
  ])
}

resource "aws_ecs_task_definition" "worker" {
  family                   = "${local.name}-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.application.arn

  container_definitions = jsonencode([{
    name      = "worker"
    image     = var.container_images.worker
    essential = true
    environment = concat(local.container_environment, [
      { name = "DATABASE_HOST", value = "pgbouncer-p2.${local.name}.internal" },
      { name = "DATABASE_PORT", value = "6432" },
      { name = "SYNC_QUEUE_URL", value = aws_sqs_queue.main["offline-sync"].url },
    ])
    readonlyRootFilesystem = true
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.service["worker"].name
        awslogs-region        = var.region
        awslogs-stream-prefix = "worker"
      }
    }
  }])
}

resource "aws_ecs_task_definition" "pooler" {
  for_each = local.db_roles

  family                   = "${local.name}-pgbouncer-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.application.arn

  container_definitions = jsonencode([{
    name         = "pgbouncer"
    image        = var.container_images.pgbouncer
    essential    = true
    portMappings = [{ containerPort = 6432, hostPort = 6432, protocol = "tcp" }]
    environment = [
      { name = "POOL_CLASS", value = each.key },
      { name = "DB_HOST", value = aws_rds_cluster.core.endpoint },
      { name = "DB_PORT", value = tostring(aws_rds_cluster.core.port) },
      { name = "POOL_MODE", value = "transaction" },
    ]
    secrets                = [{ name = "DATABASE_CREDENTIAL", valueFrom = aws_secretsmanager_secret.database_role[each.key].arn }]
    readonlyRootFilesystem = true
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.service["pgbouncer"].name
        awslogs-region        = var.region
        awslogs-stream-prefix = each.key
      }
    }
  }])
}

resource "aws_ecs_task_definition" "migration" {
  family                   = "${local.name}-migration"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.migration.arn

  container_definitions = jsonencode([{
    name      = "migration"
    image     = var.container_images.migration
    essential = true
    command   = ["/isas/run-migrations.sh"]
    environment = [
      { name = "DB_NAME", value = aws_rds_cluster.core.database_name },
      { name = "MIGRATION_REQUIRED_FIRST", value = "0000_auth_context_v1.sql" },
      { name = "EXPECTED_POSTGIS_VERSION", value = "3.4.6" },
    ]
    secrets = [
      { name = "DB_HOST", valueFrom = "${aws_rds_cluster.core.master_user_secret[0].secret_arn}:host::" },
      { name = "DB_PORT", valueFrom = "${aws_rds_cluster.core.master_user_secret[0].secret_arn}:port::" },
      { name = "DB_USER", valueFrom = "${aws_rds_cluster.core.master_user_secret[0].secret_arn}:username::" },
      { name = "DB_PASSWORD", valueFrom = "${aws_rds_cluster.core.master_user_secret[0].secret_arn}:password::" },
    ]
    readonlyRootFilesystem = true
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.service["migration"].name
        awslogs-region        = var.region
        awslogs-stream-prefix = "migration"
      }
    }
  }])
}

resource "aws_ecs_service" "web" {
  name                               = "web"
  cluster                            = aws_ecs_cluster.main.id
  task_definition                    = aws_ecs_task_definition.web.arn
  desired_count                      = 3
  launch_type                        = "FARGATE"
  availability_zone_rebalancing      = "ENABLED"
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  network_configuration {
    subnets          = aws_subnet.app[*].id
    security_groups  = [aws_security_group.web.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.web.arn
    container_name   = "web"
    container_port   = 8080
  }

  depends_on = [aws_lb_listener.https]
}

resource "aws_ecs_service" "bff" {
  name                               = "bff"
  cluster                            = aws_ecs_cluster.main.id
  task_definition                    = aws_ecs_task_definition.bff.arn
  desired_count                      = var.minimum_bff_tasks
  launch_type                        = "FARGATE"
  availability_zone_rebalancing      = "ENABLED"
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  network_configuration {
    subnets          = aws_subnet.app[*].id
    security_groups  = [aws_security_group.bff.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.bff.arn
    container_name   = "bff"
    container_port   = 3000
  }

  depends_on = [aws_lb_listener_rule.bff, aws_ecs_service.pooler]
}

resource "aws_ecs_service" "worker" {
  name                          = "worker"
  cluster                       = aws_ecs_cluster.main.id
  task_definition               = aws_ecs_task_definition.worker.arn
  desired_count                 = 3
  launch_type                   = "FARGATE"
  availability_zone_rebalancing = "ENABLED"

  network_configuration {
    subnets          = aws_subnet.app[*].id
    security_groups  = [aws_security_group.bff.id]
    assign_public_ip = false
  }

  depends_on = [aws_ecs_service.pooler]
}

resource "aws_ecs_service" "pooler" {
  for_each = local.db_roles

  name                          = "pgbouncer-${each.key}"
  cluster                       = aws_ecs_cluster.main.id
  task_definition               = aws_ecs_task_definition.pooler[each.key].arn
  desired_count                 = 3
  launch_type                   = "FARGATE"
  availability_zone_rebalancing = "ENABLED"

  network_configuration {
    subnets          = aws_subnet.app[*].id
    security_groups  = [aws_security_group.pooler.id]
    assign_public_ip = false
  }

  service_registries {
    registry_arn = aws_service_discovery_service.pooler[each.key].arn
  }
}
