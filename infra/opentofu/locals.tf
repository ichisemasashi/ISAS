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
