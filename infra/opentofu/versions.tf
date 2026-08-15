terraform {
  required_version = "= 1.12.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "= 6.51.0"
    }
  }

  backend "s3" {
    key          = "isas/staging/opentofu.tfstate"
    region       = "ap-northeast-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = local.tags
  }
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

data "aws_availability_zones" "available" {
  state = "available"

  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}
