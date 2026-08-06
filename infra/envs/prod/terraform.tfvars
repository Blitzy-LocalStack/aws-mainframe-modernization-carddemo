# Non-secret production parameters. The deployment workflow supplies
# TF_VAR_image_tag, TF_VAR_github_repository,
# TF_VAR_github_oidc_provider_arn, TF_VAR_cloudfront_aliases,
# TF_VAR_cloudfront_acm_certificate_arn and
# TF_VAR_cloudfront_api_connect_src_origins; those values are
# deployment-specific and
# have no safe repository default.
aws_region  = "us-east-1"
name_prefix = "carddemo"
environment = "prod"
vpc_cidr    = "10.1.0.0/16"

tags = {
  Project     = "carddemo"
  Environment = "prod"
  ManagedBy   = "terraform"
}

aurora_engine_version               = "16.6"
aurora_parameter_group_family       = "aurora-postgresql16"
aurora_min_capacity                 = 2
aurora_max_capacity                 = 32
aurora_seconds_until_auto_pause     = 300
aurora_backup_retention_period      = 35
aurora_preferred_backup_window      = "07:00-08:00"
aurora_preferred_maintenance_window = "sun:09:00-sun:10:00"

ecs_task_cpu      = 1024
ecs_task_memory   = 2048
ecs_desired_count = 2

log_retention_days        = 365
cloudfront_price_class    = "PriceClass_All"
batch_schedule_expression = "cron(0 2 * * ? *)"

deletion_protection            = true
skip_final_snapshot            = false
secret_recovery_window_in_days = 30
alarm_email_endpoints          = []
