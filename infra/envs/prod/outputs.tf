# =============================================================================
# infra/envs/prod/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Re-export each child module's complete public object so every producer
#   output has a concrete operator/automation consumer at the environment root.
#   Grouping by module keeps the root contract stable as a module adds a
#   documented output, without flattening hundreds of names into one namespace.
#
#   This file and the root-owned SSM parameters in main.tf are TOGETHER the only
#   sanctioned source of a runtime endpoint or identifier for this environment,
#   per specification section 0.5.3.5: no service hard-codes an endpoint. The two
#   mechanisms exist because they serve different readers, and neither
#   substitutes for the other. A running task resolves its configuration from
#   Parameter Store through its Spring profile and never reads a Terraform
#   output; an operator, a runbook step and a pipeline job read the outputs
#   below. That split is why `runtime_configuration` publishes the parameter
#   handles themselves -- a consumer that knows a parameter exists but cannot
#   locate it would have to rebuild its name by hand, which is the hard-coding
#   that section forbids.
#
# Parameters:
#   None. Inputs are declared in variables.tf and supplied by terraform.tfvars.
#
# Returns:
#   One grouped output per module family, plus the root-owned runtime resources
#   no module produces. Every value is resolved by the provider during apply, so
#   none is readable until this root has been applied successfully; a plan against
#   an unapplied root reports them as not yet known rather than as empty.
#
# WHY : Refactoring Rationale: the baseline this environment replaces had no
#       endpoint-publication mechanism of any kind, which is why this file exists
#       rather than being a convention it inherits. A program reached its data
#       through a name written into the job that ran it -- app/jcl/ACCTFILE.jcl
#       names the account cluster on its own DD statement, and each DEFINE FILE
#       stanza in app/csd/CARDDEMO.CSD repeats a dataset name for the online path
#       -- so every consumer carried its own copy of an address, and moving one
#       meant finding and editing all of them. The nearest thing to a published
#       name was a job-local JCL SET symbol, as app/jcl/CBADMCDJ.jcl uses to
#       derive its load library from a high-level qualifier: it parameterized
#       exactly one job, and nothing outside that job could read the result.
#       Publishing an address from one place, and only from there, is what
#       removes the copies.
#
# On `sensitive`: NOT ONE output below is marked, and the uniformity is a
#   decision rather than an omission.
#
# WHY : Assumptions: every value published here is an endpoint, an address, an
#       identifier, an ARN, a name or a plain integer -- never a credential, and
#       never derived from one. That is the fact that makes a blanket choice safe
#       rather than merely convenient. Measured rather than assumed: `sensitive`
#       appears as an argument zero times across all sixteen module output files,
#       so nothing reaching this root arrives already marked.
# WHY : Assumptions: the two precedents in this package look opposed and in fact
#       converge on the single rule this file adopts. infra/bootstrap/outputs.tf
#       marks nothing because "marking names sensitive would prevent normal
#       backend initialization without protecting credential material", its values
#       having to be transcribed by hand into a backend block that cannot take
#       variables. infra/modules/secrets/outputs.tf marks nothing because every
#       member it publishes is a handle a consumer exchanges for a value at run
#       time. One publishes backend configuration and the other publishes
#       credential handles, and both land on: publish the reference, never the
#       value.
# WHY : Trade-offs: marking these `sensitive` would cost real capability and buy
#       nothing. `terraform output` would redact them, and the readers named above
#       would be unable to obtain values that are already visible to any principal
#       able to describe the resource -- the deploy runbook could not transcribe
#       the SPA bucket and distribution id that `spa_publication` exists to hand
#       it, and a pipeline job could not read the API endpoint.
# WHY : Assumptions: `sensitive` redacts CLI and plan rendering ONLY. It does not
#       encrypt the value in state, and it does not withhold the value from
#       `terraform output -json`, which emits it in full alongside a flag saying
#       it is sensitive. This root is a ROOT module, so Terraform's own guard --
#       requiring a root output that carries sensitive data to be marked -- does
#       fire here, where in a child module it stays silent. That guard is a
#       display rule and not a boundary, so satisfying it would not make a
#       published credential acceptable.
# WHY : Alternatives Considered: publishing the database and seed-user credentials
#       themselves, so that a consumer needs one command rather than two.
#       Rejected outright. Terraform would persist each value in state, and this
#       root's state is a shared object under the prod key declared in backend.tf
#       -- server-side encrypted, but still a shared blob with none of a secret
#       store's per-entry access control, rotation or read auditing. The
#       credential would then exist in two places governed by two different
#       policies. `service_credentials` and the Cognito members inside `identity`
#       therefore publish Secrets Manager handles only; a consumer calls
#       `get-secret-value` on a published id under its own IAM identity, which
#       additionally leaves an audit record of the read that a Terraform output
#       never would.
#
# Deliberately NOT published:
#   - No database master password, no per-service database password, no seed-user
#     password and no key material -- only the Secrets Manager handle for each.
#   - No KMS key material. `encryption` carries key ARNs, key ids and alias names,
#     which are the identifiers a grant is written against; a customer-managed
#     key's material cannot leave the service and is not represented here even by
#     reference.
#   - No cardholder or personal data of any kind: no primary account number, no
#     card verification value, no national or government-issued identifier. Those
#     are Aurora columns and application DTOs. This file publishes the address of
#     the cluster, never anything stored inside it.
#   - No scale-out read path. `database` does carry the cluster's reader endpoint,
#     and a reader must not take that as evidence of a replica: read replicas are
#     excluded by specification section 0.2.2, this environment runs a single
#     instance -- the writer -- and the reporting service reads the writer through
#     read-only cross-schema VIEWS under a SELECT-only database role. Aurora
#     publishes the reader address whether or not a reader instance exists, so the
#     name resolves to that same writer. The full argument sits on the output
#     itself in infra/modules/aurora-postgresql/outputs.tf, where a consumer
#     reading the module is already looking.
#
# WHY : Assumptions: an absence a reader cannot account for reads as an oversight,
#       so each one is named above. This follows the precedent in
#       infra/modules/network/outputs.tf, which records that its
#       interface-endpoint security group, flow-log IAM role and internet gateway
#       are created by main.tf and deliberately not published.
# WHY : Trade-offs: an ARN published here frequently CONTAINS the AWS account
#       identifier, and that is accepted rather than engineered around. The
#       constraint this package holds is that no account identifier, ARN, bucket
#       name or endpoint is ever written as a LITERAL into source; a value the
#       provider read back from the account during apply is the opposite of a
#       committed literal. The distinction is recorded because the two are
#       indistinguishable to a grep, and a reader applying the literal rule to an
#       output would delete a legitimate handle -- after which a consumer would
#       compose the ARN by hand from an account id, which is precisely the
#       committed literal the rule exists to prevent.
#
# WHY : Alternatives Considered: flattening this file into one output per value,
#       so `terraform output database_writer_endpoint` replaces
#       `terraform output -json database | jq -r .writer_endpoint`. Rejected on
#       two grounds beyond the namespace size. Each module already documents its
#       own members at their point of definition -- the reason a reader must not
#       treat `database`.`reader_endpoint` as a scale-out read path is argued on
#       that output in infra/modules/aurora-postgresql/outputs.tf -- so a flat
#       copy here would either duplicate that text or summarise it and drift from
#       it. Flattening would also break the deliberate name parity with
#       infra/envs/dev/outputs.tf, which declares these same eighteen names in
#       this same order so that a consumer written against dev works against prod
#       unchanged. That parity is the whole point of the two roots being
#       parameterized rather than forked.
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
  description = "Complete daily, ad-hoc report, dataset round-trip and authorization-extract state-machine contract: the four machines, their per-machine execution roles, their log groups, the two bracket-release rules with their dead-letter queue and alarms, the resolved dataset staging root, and one deprecated dataset_source_extract_prefix alias the module retains for a compatibility window."
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
  description = "Non-secret values to copy into the protected GitHub prod environment variables consumed by deploy.yml."
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
  description = "Root-owned SSM parameter ARNs (runtime and platform), the online-write flag parameter ARN, the four operational Lambda ARNs, and the database bootstrap result. Publishes NO certificate or listener-secret handle: each task mints its own listener material, and the certificate the load balancer presents is the operator-supplied alb_certificate_arn the caller already holds."
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
