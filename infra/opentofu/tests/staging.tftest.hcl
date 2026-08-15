mock_provider "aws" {
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }

  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
      arn        = "arn:aws:iam::123456789012:root"
      user_id    = "123456789012"
    }
  }

  mock_data "aws_partition" {
    defaults = {
      partition  = "aws"
      dns_suffix = "amazonaws.com"
    }
  }

  mock_data "aws_region" {
    defaults = {
      id          = "ap-northeast-1"
      name        = "ap-northeast-1"
      description = "Asia Pacific (Tokyo)"
    }
  }

  mock_data "aws_availability_zones" {
    defaults = {
      names    = ["ap-northeast-1a", "ap-northeast-1c", "ap-northeast-1d"]
      zone_ids = ["apne1-az4", "apne1-az1", "apne1-az2"]
    }
  }

  mock_resource "aws_lb" {
    defaults = {
      arn        = "arn:aws:elasticloadbalancing:ap-northeast-1:123456789012:loadbalancer/app/isas-test/0000000000000000"
      arn_suffix = "app/isas-test/0000000000000000"
      dns_name   = "isas-test.ap-northeast-1.elb.amazonaws.com"
      zone_id    = "Z14GRHDCWA56QT"
    }
  }

  mock_resource "aws_lb_target_group" {
    defaults = {
      arn = "arn:aws:elasticloadbalancing:ap-northeast-1:123456789012:targetgroup/isas-test/0000000000000000"
    }
  }

  mock_resource "aws_lb_listener" {
    defaults = {
      arn = "arn:aws:elasticloadbalancing:ap-northeast-1:123456789012:listener/app/isas-test/0000000000000000/0000000000000000"
    }
  }

  mock_resource "aws_iam_role" {
    defaults = {
      arn = "arn:aws:iam::123456789012:role/isas-mock-role"
    }
  }

  mock_resource "aws_kms_key" {
    defaults = {
      arn    = "arn:aws:kms:ap-northeast-1:123456789012:key/00000000-0000-0000-0000-000000000000"
      key_id = "00000000-0000-0000-0000-000000000000"
    }
  }

  mock_resource "aws_sns_topic" {
    defaults = {
      arn = "arn:aws:sns:ap-northeast-1:123456789012:isas-test"
    }
  }

  mock_resource "aws_s3_access_point" {
    defaults = {
      arn = "arn:aws:s3:ap-northeast-1:123456789012:accesspoint/isas-test-attachments"
    }
  }

  mock_resource "aws_rds_cluster" {
    defaults = {
      arn                   = "arn:aws:rds:ap-northeast-1:123456789012:cluster:isas-test"
      endpoint              = "isas-test.cluster.example.ap-northeast-1.rds.amazonaws.com"
      reader_endpoint       = "isas-test.cluster-ro.example.ap-northeast-1.rds.amazonaws.com"
      engine_version_actual = "16.14"
      master_user_secret = [{
        kms_key_id    = "arn:aws:kms:ap-northeast-1:123456789012:key/00000000-0000-0000-0000-000000000000"
        secret_arn    = "arn:aws:secretsmanager:ap-northeast-1:123456789012:secret:isas-test"
        secret_status = "active"
      }]
    }
  }

  mock_resource "aws_service_discovery_service" {
    defaults = {
      arn = "arn:aws:servicediscovery:ap-northeast-1:123456789012:service/srv-00000000000000000"
    }
  }

  mock_resource "aws_wafv2_web_acl" {
    defaults = {
      arn = "arn:aws:wafv2:ap-northeast-1:123456789012:regional/webacl/isas-test/00000000-0000-0000-0000-000000000000"
    }
  }
}

variables {
  deployment_id                     = "isas-jp-stg-01"
  environment                       = "staging"
  region                            = "ap-northeast-1"
  expected_aws_account_id           = "123456789012"
  domain_name                       = "staging.isas.example.jp"
  route53_zone_id                   = "Z0000000000000000000"
  certificate_arn                   = "arn:aws:acm:ap-northeast-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
  cognito_custom_domain             = "auth.staging.isas.example.jp"
  cognito_certificate_arn_us_east_1 = "arn:aws:acm:us-east-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
  github_repository                 = "example/isas"
  offline_tileset_version           = "jp-test-v1"
  offline_tileset_archive_sha256    = "abababababababababababababababababababababababababababababababab"

  container_images = {
    web       = "example.invalid/web@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    bff       = "example.invalid/bff@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    worker    = "example.invalid/worker@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    pgbouncer = "example.invalid/pgbouncer@sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    adot      = "example.invalid/adot@sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    migration = "example.invalid/migration@sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
  }
}

run "staging_plan" {
  command = plan

  assert {
    condition     = output.deployment_manifest.account_id == "123456789012"
    error_message = "The account guard must reach the deployment manifest."
  }

  assert {
    condition     = output.deployment_manifest.offline_map.installation_limit_bytes == 262144000 && output.deployment_manifest.offline_map.pack_retention_days == 30
    error_message = "ADR-0011 default capacity and retention controls must be in the deployment manifest."
  }

  assert {
    condition     = length(output.deployment_manifest.availability_zones) == 3
    error_message = "The staging plan must contain exactly three application AZs."
  }

  assert {
    condition     = output.deployment_manifest.environment == "staging"
    error_message = "The test plan must not target production."
  }

  assert {
    condition     = length(output.deployment_manifest.ecs.services.poolers) == 5
    error_message = "All five PgBouncer isolation classes must be planned."
  }

  assert {
    condition     = output.deployment_manifest.ingress.tls_policy == "ELBSecurityPolicy-TLS13-1-2-2021-06"
    error_message = "The public ingress must enforce the selected TLS 1.2/1.3 policy."
  }

  assert {
    condition     = output.deployment_manifest.ecs.desired_tasks.web >= 2 && output.deployment_manifest.ecs.desired_tasks.bff >= 2
    error_message = "Web and BFF must each have at least two tasks for failure-domain tolerance."
  }

  assert {
    condition     = output.deployment_manifest.database.pitr && output.deployment_manifest.database.backup_retention_days == 30 && output.deployment_manifest.database.reader_endpoint != ""
    error_message = "RDS must expose a reader endpoint with 30-day PITR retention."
  }

  assert {
    condition     = length(output.deployment_manifest.queues) == 4 && contains(keys(output.deployment_manifest.queues), "quarantine")
    error_message = "The four encrypted queues must include the quarantine path."
  }

  assert {
    condition     = output.deployment_manifest.storage.private_attachment_access_point_arn != "" && output.deployment_manifest.storage.quarantine_archive_bucket != ""
    error_message = "Private attachment delivery and quarantine archive resources must be in the manifest."
  }

  assert {
    condition     = output.deployment_manifest.kms.backing == "AWS KMS FIPS validated HSM" && !output.deployment_manifest.kms.multi_region
    error_message = "The deployment must use regional AWS KMS HSM-backed keys."
  }

  assert {
    condition     = output.deployment_manifest.shard_manifest.shard_id == "jp-primary-01" && can(regex("^[0-9a-f]{64}$", output.deployment_manifest.shard_manifest.sha256))
    error_message = "The immutable shard manifest must include its shard ID and SHA-256 digest."
  }

  assert {
    condition     = contains(keys(output.deployment_manifest.kms_key_arns), "token_session")
    error_message = "The deployment manifest must identify the dedicated token and session KMS key."
  }

  assert {
    condition     = output.deployment_manifest.secrets.actor_pseudonym_secret_arn != ""
    error_message = "The deployment manifest must identify the actor pseudonym secret without exposing its value."
  }
}
