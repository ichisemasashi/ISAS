terraform {
  required_version = "= 1.12.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "= 6.51.0"
    }
  }
}

provider "aws" {
  region = var.region
}

data "aws_caller_identity" "current" {}

variable "region" {
  type    = string
  default = "ap-northeast-1"
}

variable "expected_aws_account_id" {
  type = string
}

variable "state_bucket_name" {
  type = string
}

variable "lock_table_name" {
  type    = string
  default = "isas-jp-opentofu-lock"
}

check "correct_account" {
  assert {
    condition     = data.aws_caller_identity.current.account_id == var.expected_aws_account_id
    error_message = "AWS caller account does not match expected_aws_account_id."
  }
}

resource "aws_kms_key" "state" {
  description             = "ISAS OpenTofu state in ${var.region}"
  enable_key_rotation     = true
  multi_region            = false
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "state" {
  name          = "alias/isas-jp-opentofu-state"
  target_key_id = aws_kms_key.state.key_id
}

resource "aws_s3_bucket" "state" {
  bucket = var.state_bucket_name

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    bucket_key_enabled = true

    apply_server_side_encryption_by_default {
      kms_master_key_id = aws_kms_key.state.arn
      sse_algorithm     = "aws:kms"
    }
  }
}

resource "aws_dynamodb_table" "lock" {
  name         = var.lock_table_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = aws_kms_key.state.arn
  }

  lifecycle {
    prevent_destroy = true
  }
}

output "backend" {
  value = {
    bucket         = aws_s3_bucket.state.id
    dynamodb_table = aws_dynamodb_table.lock.name
    kms_key_id     = aws_kms_key.state.arn
    region         = var.region
  }
}
