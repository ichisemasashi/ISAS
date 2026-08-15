resource "aws_s3_bucket" "private_objects" {
  bucket        = "${local.name}-${data.aws_caller_identity.current.account_id}-objects"
  force_destroy = var.force_destroy_nonproduction
}

resource "aws_s3_bucket_ownership_controls" "private_objects" {
  bucket = aws_s3_bucket.private_objects.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "private_objects" {
  bucket                  = aws_s3_bucket.private_objects.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "private_objects" {
  bucket = aws_s3_bucket.private_objects.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "private_objects" {
  bucket = aws_s3_bucket.private_objects.id

  rule {
    bucket_key_enabled = true

    apply_server_side_encryption_by_default {
      kms_master_key_id = aws_kms_key.object.arn
      sse_algorithm     = "aws:kms"
    }
  }
}

data "aws_iam_policy_document" "private_objects" {
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.private_objects.arn, "${aws_s3_bucket.private_objects.arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "private_objects" {
  bucket = aws_s3_bucket.private_objects.id
  policy = data.aws_iam_policy_document.private_objects.json
}

resource "aws_s3_bucket" "ops_evidence" {
  bucket              = "${local.name}-${data.aws_caller_identity.current.account_id}-ops-evidence"
  object_lock_enabled = true
  force_destroy       = false
}

resource "aws_s3_bucket_ownership_controls" "ops_evidence" {
  bucket = aws_s3_bucket.ops_evidence.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "ops_evidence" {
  bucket                  = aws_s3_bucket.ops_evidence.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "ops_evidence" {
  bucket = aws_s3_bucket.ops_evidence.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "ops_evidence" {
  bucket = aws_s3_bucket.ops_evidence.id

  rule {
    bucket_key_enabled = true

    apply_server_side_encryption_by_default {
      kms_master_key_id = aws_kms_key.backup.arn
      sse_algorithm     = "aws:kms"
    }
  }
}

resource "aws_s3_bucket_object_lock_configuration" "ops_evidence" {
  bucket = aws_s3_bucket.ops_evidence.id

  rule {
    default_retention {
      mode = "GOVERNANCE"
      days = 2557
    }
  }
}

data "aws_iam_policy_document" "ops_evidence" {
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.ops_evidence.arn, "${aws_s3_bucket.ops_evidence.arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "ops_evidence" {
  bucket = aws_s3_bucket.ops_evidence.id
  policy = data.aws_iam_policy_document.ops_evidence.json
}

resource "aws_dynamodb_table" "session_context" {
  name         = "${local.name}-session-context"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "session_id"
  range_key    = "context_id"

  attribute {
    name = "session_id"
    type = "S"
  }

  attribute {
    name = "context_id"
    type = "S"
  }

  attribute {
    name = "user_id"
    type = "S"
  }

  global_secondary_index {
    name            = "user-index"
    projection_type = "ALL"

    key_schema {
      attribute_name = "user_id"
      key_type       = "HASH"
    }

    key_schema {
      attribute_name = "context_id"
      key_type       = "RANGE"
    }
  }

  ttl {
    attribute_name = "expires_at_epoch"
    enabled        = true
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = aws_kms_key.data.arn
  }
}

resource "aws_sqs_queue" "dead_letter" {
  for_each = local.queue_names

  name                              = "${local.name}-${each.key}-dlq"
  kms_master_key_id                 = aws_kms_key.queue.arn
  kms_data_key_reuse_period_seconds = 300
  message_retention_seconds         = 1209600
}

resource "aws_sqs_queue" "main" {
  for_each = local.queue_names

  name                              = "${local.name}-${each.key}"
  kms_master_key_id                 = aws_kms_key.queue.arn
  kms_data_key_reuse_period_seconds = 300
  visibility_timeout_seconds        = each.key == "offline-sync" ? 180 : 60
  message_retention_seconds         = 345600
  receive_wait_time_seconds         = 20

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dead_letter[each.key].arn
    maxReceiveCount     = 5
  })
}

resource "aws_secretsmanager_secret" "database_role" {
  for_each = local.db_roles

  name                    = "${local.name}/database/${each.key}"
  description             = "PgBouncer credential for ${each.key}; operator populates JSON keys username and password"
  kms_key_id              = aws_kms_key.data.arn
  recovery_window_in_days = 30
}

resource "aws_secretsmanager_secret" "actor_pseudonym" {
  name                    = "${local.name}/application/actor-pseudonym-key"
  description             = "At least 32 random bytes for one-way audit actor pseudonyms; operator populates the value"
  kms_key_id              = aws_kms_key.token_session.arn
  recovery_window_in_days = 30
}

resource "aws_ecr_repository" "application" {
  for_each = local.ecr_repositories

  name                 = "${local.name}/${each.key}"
  image_tag_mutability = "IMMUTABLE"

  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = aws_kms_key.object.arn
  }

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_registry_scanning_configuration" "enhanced" {
  scan_type = "ENHANCED"

  rule {
    scan_frequency = "CONTINUOUS_SCAN"

    repository_filter {
      filter      = "${local.name}/*"
      filter_type = "WILDCARD"
    }
  }
}
