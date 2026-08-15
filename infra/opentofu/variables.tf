variable "deployment_id" {
  description = "Globally meaningful ISAS deployment identifier."
  type        = string
  default     = "isas-jp-stg-01"

  validation {
    condition     = can(regex("^isas-jp-(stg|prod)-[0-9]{2}$", var.deployment_id))
    error_message = "deployment_id must follow isas-jp-stg-NN or isas-jp-prod-NN."
  }
}

variable "environment" {
  type    = string
  default = "staging"

  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "environment must be staging or production."
  }
}

variable "region" {
  type    = string
  default = "ap-northeast-1"

  validation {
    condition     = var.region == "ap-northeast-1"
    error_message = "The Japan Phase 1 profile permits ap-northeast-1 only."
  }
}

variable "expected_aws_account_id" {
  description = "Prevents applying a plan to the wrong AWS account."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.expected_aws_account_id))
    error_message = "expected_aws_account_id must be a 12-digit AWS account ID."
  }
}

variable "vpc_cidr" {
  type    = string
  default = "10.42.0.0/16"
}

variable "domain_name" {
  description = "Application FQDN and WebAuthn relying party ID."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$", var.domain_name))
    error_message = "domain_name must be a lowercase DNS name."
  }
}

variable "route53_zone_id" {
  type = string
}

variable "certificate_arn" {
  description = "ACM certificate in ap-northeast-1 for domain_name."
  type        = string
}

variable "cognito_custom_domain" {
  description = "Custom managed-login domain and WebAuthn relying party ID."
  type        = string
}

variable "cognito_certificate_arn_us_east_1" {
  description = "ACM certificate required by Cognito custom domains; Cognito requires us-east-1."
  type        = string

  validation {
    condition     = can(regex("^arn:aws:acm:us-east-1:[0-9]{12}:certificate/[0-9a-f-]+$", var.cognito_certificate_arn_us_east_1))
    error_message = "Cognito custom-domain certificate must be an ACM certificate ARN in us-east-1."
  }
}

variable "github_repository" {
  description = "GitHub owner/repository allowed to assume the deployment role."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must be owner/repository."
  }
}

variable "container_images" {
  description = "ECR image references pinned to immutable sha256 digests."
  type = object({
    web       = string
    bff       = string
    worker    = string
    pgbouncer = string
    adot      = string
    migration = string
  })

  validation {
    condition = alltrue([
      for image in values(var.container_images) : can(regex("^[^[:space:]]+@sha256:[0-9a-f]{64}$", image))
    ])
    error_message = "Every container image must be pinned as repository@sha256:<64 lowercase hex>."
  }
}

variable "rds_engine_version" {
  description = "AWS API engine version. Acceptance verifies the RDS release and PostGIS version."
  type        = string
  default     = "16.14"
}

variable "rds_instance_class" {
  type    = string
  default = "db.m6gd.large"
}

variable "rds_allocated_storage_gib" {
  type    = number
  default = 100
}

variable "rds_iops" {
  type    = number
  default = 1000
}

variable "deletion_protection" {
  type    = bool
  default = false
}

variable "force_destroy_nonproduction" {
  description = "Must remain false for production."
  type        = bool
  default     = false

  validation {
    condition     = var.environment != "production" || !var.force_destroy_nonproduction
    error_message = "Production buckets cannot enable force_destroy."
  }
}

variable "minimum_bff_tasks" {
  type    = number
  default = 3

  validation {
    condition     = var.minimum_bff_tasks >= 3
    error_message = "BFF must run at least three tasks."
  }
}

variable "alert_email" {
  description = "Optional operational email; confirmation is required before acceptance."
  type        = string
  default     = null
  nullable    = true
}
