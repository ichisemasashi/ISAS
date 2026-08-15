data "aws_iam_policy_document" "kms" {
  statement {
    sid       = "RootAdministration"
    actions   = ["kms:*"]
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = ["arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
  }
}

data "aws_iam_policy_document" "kms_queue" {
  source_policy_documents = [data.aws_iam_policy_document.kms.json]

  statement {
    sid = "CloudWatchLogsEncryption"
    actions = [
      "kms:Decrypt",
      "kms:DescribeKey",
      "kms:Encrypt",
      "kms:GenerateDataKey*",
      "kms:ReEncrypt*",
    ]
    resources = ["*"]

    principals {
      type        = "Service"
      identifiers = ["logs.${var.region}.amazonaws.com"]
    }

    condition {
      test     = "ArnLike"
      variable = "kms:EncryptionContext:aws:logs:arn"
      values   = ["arn:${data.aws_partition.current.partition}:logs:${var.region}:${data.aws_caller_identity.current.account_id}:log-group:/isas/*"]
    }
  }

  statement {
    sid       = "SnsDataKeys"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey*"]
    resources = ["*"]

    principals {
      type        = "Service"
      identifiers = ["sns.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_kms_key" "data" {
  description             = "${var.deployment_id} database and session data"
  enable_key_rotation     = true
  multi_region            = false
  deletion_window_in_days = 30
  policy                  = data.aws_iam_policy_document.kms.json
}

resource "aws_kms_key" "token_session" {
  description             = "${var.deployment_id} application envelope encryption for tokens and sessions"
  enable_key_rotation     = true
  multi_region            = false
  deletion_window_in_days = 30
  policy                  = data.aws_iam_policy_document.kms.json
}

resource "aws_kms_key" "object" {
  description             = "${var.deployment_id} private objects"
  enable_key_rotation     = true
  multi_region            = false
  deletion_window_in_days = 30
  policy                  = data.aws_iam_policy_document.kms.json
}

resource "aws_kms_key" "queue" {
  description             = "${var.deployment_id} queues and logs"
  enable_key_rotation     = true
  multi_region            = false
  deletion_window_in_days = 30
  policy                  = data.aws_iam_policy_document.kms_queue.json
}

resource "aws_kms_key" "backup" {
  description             = "${var.deployment_id} backups and evidence"
  enable_key_rotation     = true
  multi_region            = false
  deletion_window_in_days = 30
  policy                  = data.aws_iam_policy_document.kms.json
}

resource "aws_kms_key" "signing" {
  description              = "${var.deployment_id} artifact and audit signing"
  key_usage                = "SIGN_VERIFY"
  customer_master_key_spec = "ECC_NIST_P256"
  multi_region             = false
  deletion_window_in_days  = 30
  policy                   = data.aws_iam_policy_document.kms.json
}

resource "aws_kms_alias" "data" {
  name          = "alias/${local.name}/data"
  target_key_id = aws_kms_key.data.key_id
}

resource "aws_kms_alias" "token_session" {
  name          = "alias/${local.name}/token-session"
  target_key_id = aws_kms_key.token_session.key_id
}

resource "aws_kms_alias" "object" {
  name          = "alias/${local.name}/object"
  target_key_id = aws_kms_key.object.key_id
}

resource "aws_kms_alias" "queue" {
  name          = "alias/${local.name}/queue"
  target_key_id = aws_kms_key.queue.key_id
}

resource "aws_kms_alias" "backup" {
  name          = "alias/${local.name}/backup"
  target_key_id = aws_kms_key.backup.key_id
}

resource "aws_kms_alias" "signing" {
  name          = "alias/${local.name}/signing"
  target_key_id = aws_kms_key.signing.key_id
}
