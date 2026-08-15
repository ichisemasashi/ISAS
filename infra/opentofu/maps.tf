resource "aws_s3_bucket" "offline_maps" {
  bucket        = "${local.name}-${data.aws_caller_identity.current.account_id}-offline-maps"
  force_destroy = var.force_destroy_nonproduction
}

resource "aws_s3_bucket_ownership_controls" "offline_maps" {
  bucket = aws_s3_bucket.offline_maps.id
  rule { object_ownership = "BucketOwnerEnforced" }
}

resource "aws_s3_bucket_public_access_block" "offline_maps" {
  bucket                  = aws_s3_bucket.offline_maps.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "offline_maps" {
  bucket = aws_s3_bucket.offline_maps.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "offline_maps" {
  bucket = aws_s3_bucket.offline_maps.id
  rule {
    bucket_key_enabled = true
    apply_server_side_encryption_by_default {
      kms_master_key_id = aws_kms_key.object.arn
      sse_algorithm     = "aws:kms"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "offline_maps" {
  bucket = aws_s3_bucket.offline_maps.id
  rule {
    id     = "superseded-tileset-retention"
    status = "Enabled"
    filter { prefix = "tilesets/" }
    abort_incomplete_multipart_upload { days_after_initiation = 7 }
    noncurrent_version_expiration { noncurrent_days = 365 }
  }
  depends_on = [aws_s3_bucket_versioning.offline_maps]
}

data "aws_iam_policy_document" "offline_maps" {
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.offline_maps.arn, "${aws_s3_bucket.offline_maps.arn}/*"]
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

resource "aws_s3_bucket_policy" "offline_maps" {
  bucket = aws_s3_bucket.offline_maps.id
  policy = data.aws_iam_policy_document.offline_maps.json
}

resource "aws_s3_object" "offline_map_manifest" {
  bucket       = aws_s3_bucket.offline_maps.id
  key          = "tilesets/${var.offline_tileset_version}/manifest.json"
  content_type = "application/json"
  content = jsonencode({
    schemaVersion  = 1
    jurisdiction   = "JP"
    tilesetVersion = var.offline_tileset_version
    archiveKey     = "tilesets/${var.offline_tileset_version}/japan.pmtiles"
    archiveSha256  = var.offline_tileset_archive_sha256
    minZoom        = 8
    maxZoom        = 16
    attribution    = "© OpenStreetMap contributors"
    license        = "ODbL-1.0"
  })
  server_side_encryption = "aws:kms"
  kms_key_id             = aws_kms_key.object.arn
  depends_on = [
    aws_s3_bucket_versioning.offline_maps,
    aws_s3_bucket_server_side_encryption_configuration.offline_maps,
  ]
}
