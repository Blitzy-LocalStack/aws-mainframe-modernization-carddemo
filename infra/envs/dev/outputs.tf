# =============================================================================
# infra/envs/dev/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Re-export each child module's complete public object so every producer
#   output has a concrete operator/automation consumer at the environment root.
#   Grouping by module keeps the root contract stable as a module adds a
#   documented output, without flattening hundreds of names into one namespace.
#
# Parameters:
#   None. Inputs are declared in variables.tf.
#
# Returns:
#   One grouped output per module family plus root-owned runtime resources.
# =============================================================================

output "network" {
  description = "Complete network module output object: VPC, subnet, route, endpoint, security-group, port and flow-log handles."
  value       = module.network
}

output "encryption" {
  description = "Complete KMS module output object for the Aurora, S3/log, Secrets Manager and SQS keys."
  value       = module.kms
}

output "registry" {
  description = "Complete ECR repository URL, name, ARN and registry-id contract."
  value       = module.ecr
}

output "database" {
  description = "Complete Aurora endpoint, identifier, subnet, security-group and RDS-managed master-secret handle contract."
  value       = module.aurora
}

output "ecs_cluster" {
  description = "Complete ECS cluster and capacity-provider contract."
  value       = module.ecs_cluster
}

output "ecs_workloads" {
  description = "Complete ecs-service output object per online service, batch task and data-migration task."
  value       = module.ecs_service
}

output "load_balancer" {
  description = "Complete internal ALB, HTTPS listener, rule and health-path contract."
  value       = module.alb
}

output "api_gateway" {
  description = "Complete HTTP API endpoint, identifier, stage, authorizer and VPC-link contract."
  value       = module.api_gateway
}

output "identity" {
  description = "Complete Cognito pool, client, scope, group, domain and generated-secret-handle contract."
  value       = module.cognito
}

output "messaging" {
  description = "Complete SQS URL, ARN and name contract for all primary and dead-letter queues."
  value       = module.sqs
}

output "batch_orchestration" {
  description = "Complete daily, ad-hoc report, dataset round-trip and authorization-extract state-machine contract: the four machines, their per-machine execution roles, their log groups, the two bracket-release rules with their dead-letter queue and alarms, and the resolved dataset staging root."
  value       = module.step_functions
}

output "batch_schedule" {
  description = "Complete EventBridge Scheduler schedule, group and execution-role contract."
  value       = module.eventbridge_scheduler
}

output "datasets" {
  description = "Complete versioned dataset-bucket, prefix and generation-retention contract."
  value       = module.s3_datasets
}

output "spa" {
  description = "Complete CloudFront distribution, origin bucket and origin-access-control contract."
  value       = module.cloudfront_spa
}

output "spa_publication" {
  description = "Non-secret values to copy into the protected GitHub dev environment variables consumed by deploy.yml."
  value = {
    aws_region          = var.aws_region
    deploy_role_arn     = aws_iam_role.spa_publication.arn
    spa_bucket_name     = module.cloudfront_spa.spa_bucket_name
    spa_distribution_id = module.cloudfront_spa.distribution_id
    spa_kms_key_arn     = module.kms.s3_key_arn
    api_endpoint_url    = module.api_gateway.api_endpoint_url
  }
}

output "observability" {
  description = "Complete notification, access-log bucket, managed log-group, dashboard and alarm contract."
  value       = module.observability
}

output "service_credentials" {
  description = "Role-keyed service database secret handles; values remain in Secrets Manager."
  value       = module.secrets.service_credential_secrets
}

output "runtime_configuration" {
  description = "Root-owned SSM parameter ARNs, operational Lambda ARNs, internal certificate handle and bootstrap result."
  value = {
    runtime_parameter_arns = {
      for key, parameter in aws_ssm_parameter.runtime : key => parameter.arn
    }
    platform_parameter_arns = {
      for key, parameter in aws_ssm_parameter.platform : key => parameter.arn
    }
    online_writes_parameter_arn = aws_ssm_parameter.online_writes_enabled.arn
    lambda_arns = {
      quiesce           = aws_lambda_function.quiesce.arn
      resume            = aws_lambda_function.resume.arn
      database_admin    = aws_lambda_function.database_admin.arn
      dataset_retention = aws_lambda_function.dataset_retention.arn

      # WHY : Assumptions: there is deliberately no credential-rotation entry. No
      #       rotation function exists anywhere in this stack -- infra/modules/secrets
      #       implements none and this root supplies none through its
      #       rotation_lambda_arn hook -- so a key here would have no ARN to carry.
      #       Its absence is recorded rather than left to be read as an oversight.
    }
    # WHY : Refactoring Rationale: two members were published here and are DELETED --
    #       `internal_tls_secret_arns`, the pair of Secrets Manager handles the
    #       listener certificate and private key were stored under, and
    #       `internal_acm_certificate_arn`, the ARN of a self-signed certificate this
    #       root imported. Neither resource exists any more. The key they described
    #       was generated by this root, persisted in Terraform state and injected into
    #       every online task, and each task now mints its own instead. The imported
    #       certificate was additionally unreachable: no listener ever selected it,
    #       because var.alb_certificate_arn is non-nullable with no default.
    # WHY : Assumptions: nothing needs a replacement member. There is no shared
    #       listener secret for a consumer to locate, and the certificate the load
    #       balancer actually presents is the operator-supplied ARN the caller already
    #       holds in var.alb_certificate_arn -- echoing an input back as an output
    #       would only invite a reader to treat it as a discovered value.
    database_bootstrap_result = aws_lambda_invocation.database_bootstrap.result
  }
}
