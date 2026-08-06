# Non-secret development parameters. image_tag is intentionally supplied by
# the deployment workflow as TF_VAR_image_tag. TF_VAR_github_repository and
# TF_VAR_github_oidc_provider_arn come from the repository identity and the
# once-per-account bootstrap output. The SPA delivery values are deployment
# specific and are supplied the same way -- TF_VAR_cloudfront_aliases,
# TF_VAR_cloudfront_acm_certificate_arn (issued in us-east-1) and
# TF_VAR_cloudfront_api_connect_src_origins -- because a distribution with no
# certificate and no alias falls back to CloudFront's default certificate, which
# pins the viewer security policy to TLSv1, and because the content-security
# policy has to name the same API origin the SPA bundle was built against.
aws_region  = "us-east-1"
name_prefix = "carddemo"
environment = "dev"
vpc_cidr    = "10.0.0.0/16"

tags = {
  Project     = "carddemo"
  Environment = "dev"
  ManagedBy   = "terraform"
}

aurora_engine_version               = "16.6"
aurora_parameter_group_family       = "aurora-postgresql16"
aurora_min_capacity                 = 0
aurora_max_capacity                 = 4
aurora_seconds_until_auto_pause     = 300
aurora_backup_retention_period      = 1
aurora_preferred_backup_window      = "07:00-08:00"
aurora_preferred_maintenance_window = "sun:09:00-sun:10:00"

ecs_task_cpu      = 512
ecs_task_memory   = 1024
ecs_desired_count = 1

log_retention_days        = 7
cloudfront_price_class    = "PriceClass_100"
batch_schedule_expression = "cron(0 2 * * ? *)"

deletion_protection            = false
skip_final_snapshot            = true
secret_recovery_window_in_days = 0
alarm_email_endpoints          = []
