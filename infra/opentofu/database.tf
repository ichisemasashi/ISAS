resource "aws_db_subnet_group" "main" {
  name       = "${local.name}-database"
  subnet_ids = aws_subnet.db[*].id
}

resource "aws_rds_cluster_parameter_group" "postgres16" {
  name        = "${local.name}-postgres16"
  family      = "postgres16"
  description = "ISAS PostgreSQL 16 cluster settings"

  parameter {
    name         = "rds.force_ssl"
    value        = "1"
    apply_method = "pending-reboot"
  }

  parameter {
    name         = "log_connections"
    value        = "1"
    apply_method = "immediate"
  }

  parameter {
    name         = "log_disconnections"
    value        = "1"
    apply_method = "immediate"
  }
}

resource "aws_rds_cluster" "core" {
  cluster_identifier = "${local.name}-core"

  engine                        = "postgres"
  engine_version                = var.rds_engine_version
  database_name                 = "isas"
  master_username               = "isas_cluster_admin"
  manage_master_user_password   = true
  master_user_secret_kms_key_id = aws_kms_key.data.arn

  availability_zones        = local.azs
  db_cluster_instance_class = var.rds_instance_class
  storage_type              = "io1"
  allocated_storage         = var.rds_allocated_storage_gib
  iops                      = var.rds_iops

  db_subnet_group_name            = aws_db_subnet_group.main.name
  db_cluster_parameter_group_name = aws_rds_cluster_parameter_group.postgres16.name
  vpc_security_group_ids          = [aws_security_group.database.id]
  port                            = 5432
  network_type                    = "IPV4"

  storage_encrypted                   = true
  kms_key_id                          = aws_kms_key.data.arn
  iam_database_authentication_enabled = true

  backup_retention_period         = 30
  preferred_backup_window         = "17:00-18:00"
  preferred_maintenance_window    = "sun:18:30-sun:19:30"
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
  copy_tags_to_snapshot           = true

  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = var.environment != "production"
  final_snapshot_identifier = var.environment == "production" ? "${local.name}-core-final" : null

  lifecycle {
    precondition {
      condition     = var.environment != "production" || var.deletion_protection
      error_message = "Production RDS requires deletion_protection=true."
    }
  }
}

resource "aws_backup_vault" "main" {
  name        = "${local.name}-vault"
  kms_key_arn = aws_kms_key.backup.arn
}

resource "aws_backup_plan" "main" {
  name = "${local.name}-backup"

  rule {
    rule_name         = "daily-30-days"
    target_vault_name = aws_backup_vault.main.name
    schedule          = "cron(0 19 * * ? *)"

    lifecycle {
      delete_after = 30
    }
  }

  rule {
    rule_name         = "weekly-12-weeks"
    target_vault_name = aws_backup_vault.main.name
    schedule          = "cron(0 20 ? * SUN *)"

    lifecycle {
      delete_after = 84
    }
  }
}

data "aws_iam_policy_document" "backup_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["backup.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "backup" {
  name               = "${local.name}-backup"
  assume_role_policy = data.aws_iam_policy_document.backup_assume.json
}

resource "aws_iam_role_policy_attachment" "backup" {
  role       = aws_iam_role.backup.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AWSBackupServiceRolePolicyForBackup"
}

resource "aws_backup_selection" "rds" {
  name         = "${local.name}-rds"
  iam_role_arn = aws_iam_role.backup.arn
  plan_id      = aws_backup_plan.main.id
  resources    = [aws_rds_cluster.core.arn]
}
