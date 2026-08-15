resource "aws_cognito_user_pool" "main" {
  name                     = "${local.name}-users"
  user_pool_tier           = "PLUS"
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]
  mfa_configuration        = "ON"

  software_token_mfa_configuration {
    enabled = true
  }

  web_authn_configuration {
    relying_party_id  = var.cognito_custom_domain
    user_verification = "required"
  }

  sign_in_policy {
    allowed_first_auth_factors = ["WEB_AUTHN", "PASSWORD"]
  }

  password_policy {
    minimum_length                   = 14
    require_lowercase                = true
    require_numbers                  = true
    require_symbols                  = true
    require_uppercase                = true
    temporary_password_validity_days = 1
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }

  user_pool_add_ons {
    advanced_security_mode = "ENFORCED"
  }

  deletion_protection = var.environment == "production" ? "ACTIVE" : "INACTIVE"
}

# AWS provider 6.51.0 does not yet expose WebAuthn FactorConfiguration. Keep the
# user pool declarative above and bridge only this API field until the provider
# supports it. The staging collector reads the API value back before acceptance.
resource "terraform_data" "cognito_webauthn_mfa" {
  triggers_replace = [
    aws_cognito_user_pool.main.id,
    var.cognito_custom_domain,
  ]

  provisioner "local-exec" {
    command = <<-EOT
      aws cognito-idp set-user-pool-mfa-config \
        --region "$AWS_REGION" \
        --cli-input-json "$COGNITO_MFA_CONFIG_JSON"
    EOT

    environment = {
      AWS_REGION = var.region
      COGNITO_MFA_CONFIG_JSON = jsonencode({
        UserPoolId                    = aws_cognito_user_pool.main.id
        MfaConfiguration              = "ON"
        SoftwareTokenMfaConfiguration = { Enabled = true }
        WebAuthnConfiguration = {
          RelyingPartyId      = var.cognito_custom_domain
          UserVerification    = "required"
          FactorConfiguration = "MULTI_FACTOR_WITH_USER_VERIFICATION"
        }
      })
    }
  }
}

resource "aws_cognito_user_pool_client" "web" {
  name         = "${local.name}-web"
  user_pool_id = aws_cognito_user_pool.main.id

  generate_secret                      = false
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "email", "profile", "aws.cognito.signin.user.admin"]
  supported_identity_providers         = ["COGNITO"]
  callback_urls                        = ["https://${var.domain_name}/api/bff/callback"]
  logout_urls                          = ["https://${var.domain_name}/"]
  prevent_user_existence_errors        = "ENABLED"
  enable_token_revocation              = true

  access_token_validity  = 15
  id_token_validity      = 15
  refresh_token_validity = 30

  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }
}

resource "aws_cognito_user_pool_domain" "main" {
  domain                = var.cognito_custom_domain
  user_pool_id          = aws_cognito_user_pool.main.id
  certificate_arn       = var.cognito_certificate_arn_us_east_1
  managed_login_version = 2
}

resource "aws_route53_record" "cognito" {
  zone_id = var.route53_zone_id
  name    = var.cognito_custom_domain
  type    = "A"

  alias {
    name                   = aws_cognito_user_pool_domain.main.cloudfront_distribution
    zone_id                = aws_cognito_user_pool_domain.main.cloudfront_distribution_zone_id
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "application" {
  zone_id = var.route53_zone_id
  name    = var.domain_name
  type    = "A"

  alias {
    name                   = aws_lb.main.dns_name
    zone_id                = aws_lb.main.zone_id
    evaluate_target_health = true
  }
}
