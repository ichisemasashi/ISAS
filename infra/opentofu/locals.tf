locals {
  name = "isas-jp-${var.environment == "production" ? "prod" : "stg"}"
  azs  = slice(sort(data.aws_availability_zones.available.names), 0, 3)

  public_subnet_cidrs = [for index in range(3) : cidrsubnet(var.vpc_cidr, 4, index)]
  app_subnet_cidrs    = [for index in range(3) : cidrsubnet(var.vpc_cidr, 4, index + 3)]
  db_subnet_cidrs     = [for index in range(3) : cidrsubnet(var.vpc_cidr, 4, index + 6)]

  tags = {
    Application  = "ISAS"
    DeploymentId = var.deployment_id
    Environment  = var.environment
    Jurisdiction = "JP"
    ManagedBy    = "OpenTofu"
    RegionLock   = "ap-northeast-1"
  }

  db_roles = toset(["p0", "auth-p1", "p1", "p2", "ops"])

  queue_names = toset([
    "authorization-revocation",
    "offline-sync",
    "quarantine",
    "audit-anchor",
  ])

  ecr_repositories = toset(["web", "bff", "worker", "pgbouncer", "migration"])

  container_environment = [
    { name = "AWS_REGION", value = var.region },
    { name = "NODE_ENV", value = "production" },
    { name = "DEPLOYMENT_ID", value = var.deployment_id },
  ]

  adot_config = jsonencode({
    receivers = { otlp = { protocols = {
      grpc = { endpoint = "0.0.0.0:4317" }
      http = { endpoint = "0.0.0.0:4318" }
    } } }
    processors = {
      memory_limiter = { check_interval = "1s", limit_mib = 256, spike_limit_mib = 64 }
      attributes = { actions = [
        { key = "enduser.id", action = "delete" },
        { key = "user.id", action = "delete" },
        { key = "tenant.id", action = "delete" },
        { key = "http.request.header.cookie", action = "delete" },
        { key = "http.request.header.authorization", action = "delete" },
        { key = "url.full", action = "delete" },
        { key = "url.query", action = "delete" },
        { key = "db.statement", action = "delete" },
        { key = "db.query.text", action = "delete" },
      ] }
      resource = { attributes = [
        { key = "deployment.environment", value = var.environment, action = "upsert" },
        { key = "service.namespace", value = "isas", action = "upsert" },
        { key = "cloud.region", value = var.region, action = "upsert" },
        { key = "isas.jurisdiction", value = "JP", action = "upsert" },
      ] }
      batch = { send_batch_size = 512, timeout = "5s" }
    }
    exporters = {
      awsxray = { region = var.region, index_all_attributes = false }
      awsemf = {
        region                  = var.region
        namespace               = "ISAS/${var.environment}"
        log_group_name          = aws_cloudwatch_log_group.telemetry_metrics.name
        log_stream_name         = "otel-metrics"
        dimension_rollup_option = "NoDimensionRollup"
      }
    }
    extensions = { health_check = { endpoint = "0.0.0.0:13133" } }
    service = {
      extensions = ["health_check"]
      telemetry  = { logs = { level = "warn" } }
      pipelines = {
        traces  = { receivers = ["otlp"], processors = ["memory_limiter", "attributes", "resource", "batch"], exporters = ["awsxray"] }
        metrics = { receivers = ["otlp"], processors = ["memory_limiter", "attributes", "resource", "batch"], exporters = ["awsemf"] }
      }
    }
  })

  shard_manifest = {
    schemaVersion = 1
    version       = var.shard_manifest_version
    deploymentId  = var.deployment_id
    jurisdiction  = "JP"
    generatedBy   = "OpenTofu"
    shards = [{
      shardId           = var.shard_id
      status            = "active"
      region            = var.region
      availabilityZones = local.azs
      writerEndpoint    = aws_rds_cluster.core.endpoint
      readerEndpoint    = aws_rds_cluster.core.reader_endpoint
      databaseName      = aws_rds_cluster.core.database_name
      port              = aws_rds_cluster.core.port
    }]
  }
}

check "tls_certificate_account_and_region" {
  assert {
    condition     = split(":", var.certificate_arn)[4] == data.aws_caller_identity.current.account_id
    error_message = "ALB certificate must belong to the deployment AWS account."
  }
}

check "correct_account" {
  assert {
    condition     = data.aws_caller_identity.current.account_id == var.expected_aws_account_id
    error_message = "AWS caller account does not match expected_aws_account_id."
  }
}

check "three_availability_zones" {
  assert {
    condition     = length(data.aws_availability_zones.available.names) >= 3
    error_message = "At least three opt-in-not-required Availability Zones are required."
  }
}
