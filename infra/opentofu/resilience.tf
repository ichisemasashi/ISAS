resource "aws_s3_bucket_lifecycle_configuration" "private_objects" {
  bucket = aws_s3_bucket.private_objects.id

  rule {
    id     = "private-attachment-version-retention"
    status = "Enabled"

    filter { prefix = "attachments/" }

    abort_incomplete_multipart_upload { days_after_initiation = 7 }
    noncurrent_version_expiration { noncurrent_days = var.attachment_noncurrent_retention_days }
  }

  rule {
    id     = "unfinished-attachment-recovery"
    status = "Enabled"
    filter {
      and {
        prefix = "attachments/"
        tags   = { "upload-state" = "pending" }
      }
    }
    expiration { days = 2 }
  }

  rule {
    id     = "orphaned-attachment-quarantine"
    status = "Enabled"
    filter {
      and {
        prefix = "attachments/"
        tags   = { "upload-state" = "orphaned" }
      }
    }
    expiration { days = 30 }
  }

  depends_on = [aws_s3_bucket_versioning.private_objects]
}

resource "aws_s3_access_point" "private_attachments" {
  name   = "${local.name}-attachments"
  bucket = aws_s3_bucket.private_objects.id

  public_access_block_configuration {
    block_public_acls       = true
    block_public_policy     = true
    ignore_public_acls      = true
    restrict_public_buckets = true
  }

  vpc_configuration { vpc_id = aws_vpc.main.id }
}

data "aws_iam_policy_document" "private_attachment_access_point" {
  statement {
    sid       = "ApplicationAttachmentList"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_access_point.private_attachments.arn]

    principals {
      type        = "AWS"
      identifiers = [aws_iam_role.application.arn]
    }
  }

  statement {
    sid       = "ApplicationAttachmentObjects"
    actions   = ["s3:GetObject", "s3:GetObjectTagging", "s3:PutObject", "s3:PutObjectTagging", "s3:DeleteObject"]
    resources = ["${aws_s3_access_point.private_attachments.arn}/object/attachments/*"]

    principals {
      type        = "AWS"
      identifiers = [aws_iam_role.application.arn]
    }
  }
}

resource "aws_s3control_access_point_policy" "private_attachments" {
  access_point_arn = aws_s3_access_point.private_attachments.arn
  policy           = data.aws_iam_policy_document.private_attachment_access_point.json
}

resource "aws_s3_bucket" "quarantine_archive" {
  bucket              = "${local.name}-${data.aws_caller_identity.current.account_id}-quarantine"
  object_lock_enabled = true
  force_destroy       = false
}

resource "aws_s3_bucket_public_access_block" "quarantine_archive" {
  bucket                  = aws_s3_bucket.quarantine_archive.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "quarantine_archive" {
  bucket = aws_s3_bucket.quarantine_archive.id
  rule { object_ownership = "BucketOwnerEnforced" }
}

resource "aws_s3_bucket_versioning" "quarantine_archive" {
  bucket = aws_s3_bucket.quarantine_archive.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "quarantine_archive" {
  bucket = aws_s3_bucket.quarantine_archive.id
  rule {
    bucket_key_enabled = true
    apply_server_side_encryption_by_default {
      kms_master_key_id = aws_kms_key.queue.arn
      sse_algorithm     = "aws:kms"
    }
  }
}

resource "aws_s3_bucket_object_lock_configuration" "quarantine_archive" {
  bucket = aws_s3_bucket.quarantine_archive.id
  rule {
    default_retention {
      mode = "GOVERNANCE"
      days = 30
    }
  }

  depends_on = [aws_s3_bucket_versioning.quarantine_archive]
}

resource "aws_s3_bucket_lifecycle_configuration" "quarantine_archive" {
  bucket = aws_s3_bucket.quarantine_archive.id
  rule {
    id     = "quarantine-retention"
    status = "Enabled"
    filter { prefix = "resolved/" }
    expiration { days = 30 }
    noncurrent_version_expiration { noncurrent_days = 30 }
  }
  depends_on = [aws_s3_bucket_versioning.quarantine_archive]
}

data "aws_iam_policy_document" "quarantine_archive" {
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.quarantine_archive.arn, "${aws_s3_bucket.quarantine_archive.arn}/*"]
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

resource "aws_s3_bucket_policy" "quarantine_archive" {
  bucket = aws_s3_bucket.quarantine_archive.id
  policy = data.aws_iam_policy_document.quarantine_archive.json
}

resource "aws_s3_bucket" "shard_config" {
  bucket              = "${local.name}-${data.aws_caller_identity.current.account_id}-shard-config"
  object_lock_enabled = true
  force_destroy       = false
}

resource "aws_s3_bucket_public_access_block" "shard_config" {
  bucket                  = aws_s3_bucket.shard_config.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "shard_config" {
  bucket = aws_s3_bucket.shard_config.id
  rule { object_ownership = "BucketOwnerEnforced" }
}

resource "aws_s3_bucket_versioning" "shard_config" {
  bucket = aws_s3_bucket.shard_config.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "shard_config" {
  bucket = aws_s3_bucket.shard_config.id
  rule {
    bucket_key_enabled = true
    apply_server_side_encryption_by_default {
      kms_master_key_id = aws_kms_key.data.arn
      sse_algorithm     = "aws:kms"
    }
  }
}

resource "aws_s3_bucket_object_lock_configuration" "shard_config" {
  bucket = aws_s3_bucket.shard_config.id
  rule {
    default_retention {
      mode = "GOVERNANCE"
      days = 365
    }
  }


  depends_on = [aws_s3_bucket_versioning.shard_config]
}

data "aws_iam_policy_document" "shard_config" {
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.shard_config.arn, "${aws_s3_bucket.shard_config.arn}/*"]
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

resource "aws_s3_bucket_policy" "shard_config" {
  bucket = aws_s3_bucket.shard_config.id
  policy = data.aws_iam_policy_document.shard_config.json
}

resource "aws_s3_object" "shard_manifest" {
  bucket                 = aws_s3_bucket.shard_config.id
  key                    = "shards/manifest-v${var.shard_manifest_version}.json"
  content                = jsonencode(local.shard_manifest)
  content_type           = "application/json"
  server_side_encryption = "aws:kms"
  kms_key_id             = aws_kms_key.data.arn

  depends_on = [
    aws_s3_bucket_versioning.shard_config,
    aws_s3_bucket_server_side_encryption_configuration.shard_config,
  ]
}

resource "aws_sqs_queue_redrive_allow_policy" "dead_letter" {
  for_each  = local.queue_names
  queue_url = aws_sqs_queue.dead_letter[each.key].id
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.main[each.key].arn]
  })
}

data "aws_iam_policy_document" "queue_transport" {
  for_each = local.queue_names
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sqs:*"]
    resources = [aws_sqs_queue.main[each.key].arn]
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

resource "aws_sqs_queue_policy" "main" {
  for_each  = local.queue_names
  queue_url = aws_sqs_queue.main[each.key].id
  policy    = data.aws_iam_policy_document.queue_transport[each.key].json
}

data "aws_iam_policy_document" "dlq_transport" {
  for_each = local.queue_names

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sqs:*"]
    resources = [aws_sqs_queue.dead_letter[each.key].arn]

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

resource "aws_sqs_queue_policy" "dead_letter" {
  for_each  = local.queue_names
  queue_url = aws_sqs_queue.dead_letter[each.key].id
  policy    = data.aws_iam_policy_document.dlq_transport[each.key].json
}
