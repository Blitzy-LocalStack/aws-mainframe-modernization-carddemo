# Development Terraform environment

This root composes all sixteen infrastructure modules, the API Gateway VPC Link
security-group edge and the non-secret Parameter Store discovery inventory for
development. The source of truth is the sibling HCL plus the AAP's identical
topology rule; [`../../README.md`](../../README.md) and the
[deploy runbook](../../../docs/runbooks/deploy.md) own package-wide procedures.

No live environment is claimed here. The configuration is authored and
statically validated; a backend-enabled plan or apply requires an operator's
short-lived federated AWS session.

## Parameters and expected result

| Parameter | Kind | Purpose |
|:---|:---|:---|
| `bucket` | backend config | Bootstrap state-bucket name |
| `region` | backend config | Bootstrap backend Region |
| `dynamodb_table` | backend config | Bootstrap lock-table name |
| `kms_key_id` | backend config | Bootstrap state CMK ARN |
| `terraform.tfvars` | tracked non-secret file | Development sizing, retention and protection values |
| deployment variables | runtime inputs | Image digests, IAM boundary, public DNS zone/domain names, the HMAC secret reference and glue Lambda ARNs |

A successful apply publishes the API/SPA entry points, service and queue
identifiers, automatically issued edge/service certificates, the service-TLS
secret identifier, database connection facts and canonical Parameter Store
paths listed in the generated Outputs table.

## Bootstrap and initialization

`infra/bootstrap` must exist before backend-enabled initialization. The backend
is partial because its bucket and key contain account-resolved identifiers and
Terraform evaluates backends before input variables.

```bash
# Read the bootstrap outputs used by the partial backend.
terraform -chdir=infra/bootstrap output

# Initialize with the four values returned by bootstrap.
terraform -chdir=infra/envs/dev init \
  -backend-config="bucket=<state-bucket>" \
  -backend-config="region=<region>" \
  -backend-config="dynamodb_table=<lock-table>" \
  -backend-config="kms_key_id=<state-kms-key-arn>"
```

**Alternatives Considered:** committing a literal backend configuration or a
tfbackend file would store account-identifying values in source, while a
variable cannot be used because backend evaluation happens first.

## Plan and apply

```bash
# Create the review artifact from the tracked non-secret values.
# WHY : Assumptions: the artifact is named `dev.tfplan`, matching
#       [`infra/README.md`](../../README.md), because a saved plan embeds the
#       resolved value of every attribute the apply will set -- including the
#       generated Aurora master password and the Cognito seed-user passwords -- and
#       the repository ignore rules match on the `.tfplan` SUFFIX. A bare `tfplan`
#       has no suffix to match and stays trackable:
#       `git check-ignore -v infra/envs/dev/dev.tfplan` resolves, while the same
#       command on `infra/envs/dev/tfplan` returns nothing.
terraform -chdir=infra/envs/dev plan -out=dev.tfplan -var-file=terraform.tfvars

# Apply exactly the reviewed artifact rather than replanning.
terraform -chdir=infra/envs/dev apply dev.tfplan

# Remove the artifact once the apply completes.
# WHY : Trade-offs: an ignore rule reduces accidental staging and cannot defeat
#       `git add -f`, so deleting the plan is the primary local safeguard and the
#       naming convention above is the cheap backstop.
rm -f infra/envs/dev/dev.tfplan
```

`apply -auto-approve` is not the normal path because it replans instead of
executing the artifact that was reviewed.

## Development posture

The module graph is identical to production. Only Aurora capacity/auto-pause,
ECS size/count, retention, CloudFront price class, and deletion/final-snapshot
protection differ. Development can scale Aurora to zero, runs one task per
online service, retains logs for seven days, uses the narrow edge class and
supports one-step teardown.

**Trade-offs:** a task replacement can briefly remove the only development
target, and a destroy has no final database snapshot. Those costs keep the
environment disposable while production retains redundancy and protection.

## Static validation

```bash
# Resolve modules/providers without contacting the remote backend.
terraform -chdir=infra/envs/dev init -backend=false

# Validate module interfaces and graph expressions.
terraform -chdir=infra/envs/dev validate

# Run the shared infrastructure linter.
tflint --chdir=infra/envs/dev --config="$(pwd)/infra/.tflint.hcl"
```

`-backend=false` validates configuration only; it cannot produce a deployable
remote-state plan.

## No secrets by construction

The tracked tfvars file contains no credential. Database and seed-user
credentials are generated into Secrets Manager, TLS and HMAC values arrive only
as secret ARNs, and deployment uses short-lived role assumption. Parameter
Store contains endpoints and identifiers only.

## Generated Terraform reference

<!-- BEGIN_TF_DOCS -->
### Requirements

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 1.15.0 |
| <a name="requirement_archive"></a> [archive](#requirement\_archive) | ~> 2.7 |
| <a name="requirement_aws"></a> [aws](#requirement\_aws) | ~> 6.56 |
| <a name="requirement_random"></a> [random](#requirement\_random) | ~> 3.9 |
| <a name="requirement_tls"></a> [tls](#requirement\_tls) | ~> 4.1 |

### Providers

| Name | Version |
|------|---------|
| <a name="provider_archive"></a> [archive](#provider\_archive) | 2.8.0 |
| <a name="provider_aws"></a> [aws](#provider\_aws) | 6.57.1 |
| <a name="provider_terraform"></a> [terraform](#provider\_terraform) | n/a |
| <a name="provider_tls"></a> [tls](#provider\_tls) | 4.3.0 |

### Modules

| Name | Source | Version |
|------|--------|---------|
| <a name="module_alb"></a> [alb](#module\_alb) | ../../modules/alb | n/a |
| <a name="module_api_gateway"></a> [api\_gateway](#module\_api\_gateway) | ../../modules/api-gateway-http | n/a |
| <a name="module_aurora"></a> [aurora](#module\_aurora) | ../../modules/aurora-postgresql | n/a |
| <a name="module_cloudfront_spa"></a> [cloudfront\_spa](#module\_cloudfront\_spa) | ../../modules/cloudfront-spa | n/a |
| <a name="module_cognito"></a> [cognito](#module\_cognito) | ../../modules/cognito | n/a |
| <a name="module_ecr"></a> [ecr](#module\_ecr) | ../../modules/ecr | n/a |
| <a name="module_ecs_cluster"></a> [ecs\_cluster](#module\_ecs\_cluster) | ../../modules/ecs-cluster | n/a |
| <a name="module_ecs_service"></a> [ecs\_service](#module\_ecs\_service) | ../../modules/ecs-service | n/a |
| <a name="module_eventbridge_scheduler"></a> [eventbridge\_scheduler](#module\_eventbridge\_scheduler) | ../../modules/eventbridge-scheduler | n/a |
| <a name="module_kms"></a> [kms](#module\_kms) | ../../modules/kms | n/a |
| <a name="module_network"></a> [network](#module\_network) | ../../modules/network | n/a |
| <a name="module_observability"></a> [observability](#module\_observability) | ../../modules/observability | n/a |
| <a name="module_s3_datasets"></a> [s3\_datasets](#module\_s3\_datasets) | ../../modules/s3-datasets | n/a |
| <a name="module_secrets"></a> [secrets](#module\_secrets) | ../../modules/secrets | n/a |
| <a name="module_sqs"></a> [sqs](#module\_sqs) | ../../modules/sqs | n/a |
| <a name="module_step_functions"></a> [step\_functions](#module\_step\_functions) | ../../modules/step-functions-batch | n/a |

### Resources

| Name | Type |
|------|------|
| [aws_acm_certificate.internal_service](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/acm_certificate) | resource |
| [aws_iam_role.lambda](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role) | resource |
| [aws_iam_role.spa_publication](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role) | resource |
| [aws_iam_role_policy.dataset_retention_s3](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_iam_role_policy.lambda](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_iam_role_policy.spa_publication](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_lambda_function.database_admin](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_function) | resource |
| [aws_lambda_function.dataset_retention](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_function) | resource |
| [aws_lambda_function.quiesce](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_function) | resource |
| [aws_lambda_function.resume](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_function) | resource |
| [aws_lambda_invocation.database_bootstrap](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_invocation) | resource |
| [aws_lambda_permission.dataset_retention_from_s3](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_permission) | resource |
| [aws_s3_bucket_notification.dataset_generations](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_notification) | resource |
| [aws_secretsmanager_secret.internal_tls_certificate](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret) | resource |
| [aws_secretsmanager_secret.internal_tls_private_key](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret) | resource |
| [aws_secretsmanager_secret_version.internal_tls_certificate](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret_version) | resource |
| [aws_secretsmanager_secret_version.internal_tls_private_key](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret_version) | resource |
| [aws_ssm_parameter.online_writes_enabled](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ssm_parameter) | resource |
| [aws_ssm_parameter.platform](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ssm_parameter) | resource |
| [aws_ssm_parameter.runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ssm_parameter) | resource |
| [terraform_data.cross_module_contracts](https://registry.terraform.io/providers/hashicorp/terraform/latest/docs/resources/data) | resource |
| [tls_private_key.internal_service](https://registry.terraform.io/providers/hashicorp/tls/latest/docs/resources/private_key) | resource |
| [tls_self_signed_cert.internal_service](https://registry.terraform.io/providers/hashicorp/tls/latest/docs/resources/self_signed_cert) | resource |
| [archive_file.database_admin](https://registry.terraform.io/providers/hashicorp/archive/latest/docs/data-sources/file) | data source |
| [archive_file.dataset_retention](https://registry.terraform.io/providers/hashicorp/archive/latest/docs/data-sources/file) | data source |
| [archive_file.online_write_flag](https://registry.terraform.io/providers/hashicorp/archive/latest/docs/data-sources/file) | data source |
| [aws_caller_identity.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_iam_policy_document.account_runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.authorization_runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.batch_runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.data_migration_runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.database_admin_lambda](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.dataset_retention_lambda](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.dataset_retention_s3](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.lambda_assume_role](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.lambda_logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.online_write_lambda](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.reference_runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.reporting_runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.spa_publication](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.spa_publication_assume_role](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_partition.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/partition) | data source |
| [aws_region.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/region) | data source |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_alb_certificate_arn"></a> [alb\_certificate\_arn](#input\_alb\_certificate\_arn) | Regional ACM certificate ARN presented by the internal ALB HTTPS listener. Forwarded to the alb module; the certificate must be issued in this environment's region and cover internal\_service\_domain\_name. | `string` | n/a | yes |
| <a name="input_cloudfront_acm_certificate_arn"></a> [cloudfront\_acm\_certificate\_arn](#input\_cloudfront\_acm\_certificate\_arn) | ACM certificate ARN issued in us-east-1 for the SPA distribution. Forwarded to cloudfront-spa and required to cover every entry in cloudfront\_aliases. | `string` | n/a | yes |
| <a name="input_cloudfront_aliases"></a> [cloudfront\_aliases](#input\_cloudfront\_aliases) | Non-empty list of bare DNS names the SPA distribution serves. Every entry must be covered by cloudfront\_acm\_certificate\_arn and is forwarded unchanged to cloudfront-spa. | `list(string)` | n/a | yes |
| <a name="input_cloudfront_api_connect_src_origins"></a> [cloudfront\_api\_connect\_src\_origins](#input\_cloudfront\_api\_connect\_src\_origins) | Origins the SPA is permitted to reach with fetch or XHR, forwarded unchanged to cloudfront-spa as api\_connect\_src\_origins. Scheme and host only, no path and no trailing slash; normally the single API Gateway origin the SPA was built against. Supply it as TF\_VAR\_cloudfront\_api\_connect\_src\_origins, never in terraform.tfvars, so it tracks the deployed endpoint. | `list(string)` | n/a | yes |
| <a name="input_github_oidc_provider_arn"></a> [github\_oidc\_provider\_arn](#input\_github\_oidc\_provider\_arn) | ARN of the account-scoped GitHub Actions OIDC provider created by infra/bootstrap. | `string` | n/a | yes |
| <a name="input_github_repository"></a> [github\_repository](#input\_github\_repository) | GitHub repository in owner/name form whose protected dev environment may assume the SPA publication role. | `string` | n/a | yes |
| <a name="input_image_tag"></a> [image\_tag](#input\_image\_tag) | Immutable image tag applied to all ten ECR repositories for this deployment, normally the source commit SHA supplied by the OIDC deployment workflow. | `string` | n/a | yes |
| <a name="input_internal_service_domain_name"></a> [internal\_service\_domain\_name](#input\_internal\_service\_domain\_name) | Bare DNS name covered by alb\_certificate\_arn. Forwarded to the alb module as its certificate identity and to api-gateway-http as the private integration server name to verify. | `string` | n/a | yes |
| <a name="input_mask_hmac_secret_arn"></a> [mask\_hmac\_secret\_arn](#input\_mask\_hmac\_secret\_arn) | Secrets Manager ARN of the environment-separated HMAC key the data-migration image uses for protected-field fingerprints. Supplied by the operator; never created or rotated by this configuration. | `string` | n/a | yes |
| <a name="input_permissions_boundary_arn"></a> [permissions\_boundary\_arn](#input\_permissions\_boundary\_arn) | ARN of the same-account customer-managed IAM policy used as the permissions boundary on every role this deployment creates. Supplied by the operator or the deploy workflow; never created here. | `string` | n/a | yes |
| <a name="input_alarm_email_endpoints"></a> [alarm\_email\_endpoints](#input\_alarm\_email\_endpoints) | Email addresses subscribed to the environment observability topic; empty leaves notifications available for a later subscription without inventing an address. | `list(string)` | `[]` | no |
| <a name="input_aurora_backup_retention_period"></a> [aurora\_backup\_retention\_period](#input\_aurora\_backup\_retention\_period) | Days of automated backups the cluster retains, forwarded to the database module. Aurora does not permit automated backups to be switched off, so the module accepts 1 to 35 and there is no value here meaning `none`. | `number` | `7` | no |
| <a name="input_aurora_engine_version"></a> [aurora\_engine\_version](#input\_aurora\_engine\_version) | Aurora PostgreSQL engine version for this environment's cluster, forwarded to the database module, which requires the value and supplies no default of its own. Must be a numeric version such as "16.6", not an engine name or a parameter-group family. While aurora\_min\_capacity is 0, the release named here must be one that supports scaling to zero capacity. | `string` | `"16.6"` | no |
| <a name="input_aurora_max_capacity"></a> [aurora\_max\_capacity](#input\_aurora\_max\_capacity) | Ceiling of the cluster's capacity range, in Aurora Capacity Units, forwarded to the database module which requires it. It bounds what a runaway query or an unexpectedly large batch run can cost, which is why a development environment sets it low rather than leaving headroom it will never use. The module owns the range, the granularity and the rule relating this to the floor. | `number` | `4` | no |
| <a name="input_aurora_min_capacity"></a> [aurora\_min\_capacity](#input\_aurora\_min\_capacity) | Floor of the cluster's capacity range, in Aurora Capacity Units, forwarded to the database module which requires it. A floor of 0 lets the cluster pause when idle and is the single largest cost difference between this environment and production; the price is a resume delay on the first connection after a pause. The module owns the full range, granularity and zero-floor rules. | `number` | `0` | no |
| <a name="input_aurora_parameter_group_family"></a> [aurora\_parameter\_group\_family](#input\_aurora\_parameter\_group\_family) | Aurora PostgreSQL cluster parameter-group family matching aurora\_engine\_version. | `string` | `"aurora-postgresql16"` | no |
| <a name="input_aurora_preferred_backup_window"></a> [aurora\_preferred\_backup\_window](#input\_aurora\_preferred\_backup\_window) | Daily UTC window in which automated backups are taken, forwarded to the database module. Must be of the form `hh:mm-hh:mm` with no day-of-week prefix, and must not overlap the window batch\_schedule\_expression starts the nightly chain in: a backup running against the cluster while the posting and interest jobs are writing to it competes with them for the same capacity, and this environment's capacity ceiling is deliberately low. | `string` | `"07:00-08:00"` | no |
| <a name="input_aurora_preferred_maintenance_window"></a> [aurora\_preferred\_maintenance\_window](#input\_aurora\_preferred\_maintenance\_window) | Weekly UTC maintenance window for Aurora, kept outside the nightly batch and backup windows. | `string` | `"sun:09:00-sun:10:00"` | no |
| <a name="input_aurora_seconds_until_auto_pause"></a> [aurora\_seconds\_until\_auto\_pause](#input\_aurora\_seconds\_until\_auto\_pause) | Idle interval, in seconds, before a cluster whose capacity floor is zero pauses. It has an effect only while that floor is zero, and the database module requires it to be set in exactly that case, so it is declared in both environment roots for interface symmetry and is inert in the root whose floor is above zero. The module owns the accepted range. | `number` | `300` | no |
| <a name="input_aws_region"></a> [aws\_region](#input\_aws\_region) | AWS region this environment is provisioned into. Read by `provider "aws"` in versions.tf, so all sixteen modules main.tf calls inherit it instead of configuring a region of their own. Accepts a standard region identifier such as us-east-1 or ap-southeast-4. | `string` | `"us-east-1"` | no |
| <a name="input_batch_schedule_expression"></a> [batch\_schedule\_expression](#input\_batch\_schedule\_expression) | Schedule on which the nightly batch chain is started, forwarded to the scheduler module as the trigger for the batch state machine. Must be a `cron(...)` expression; the `rate(...)` and `at(...)` forms are not accepted by that module. Must not overlap aurora\_preferred\_backup\_window, because both act on the same database cluster. | `string` | `"cron(0 2 * * ? *)"` | no |
| <a name="input_cloudfront_price_class"></a> [cloudfront\_price\_class](#input\_cloudfront\_price\_class) | Edge locations the browser application's distribution is served from, forwarded to the content distribution module. Must be one of PriceClass\_100, PriceClass\_200 or PriceClass\_All. A narrower class serves fewer regions at lower cost; it changes latency for distant users and nothing else about how the distribution behaves. | `string` | `"PriceClass_100"` | no |
| <a name="input_deletion_protection"></a> [deletion\_protection](#input\_deletion\_protection) | Whether the stateful resources in this environment refuse deletion until the flag is cleared. Forwarded to both resources that offer the protection -- the database cluster and the user pool -- so one value governs the environment's whole teardown posture. | `bool` | `false` | no |
| <a name="input_ecs_desired_count"></a> [ecs\_desired\_count](#input\_ecs\_desired\_count) | Tasks each service runs. This is the input that decides whether a service survives losing one task, and it is the clearest single sizing difference between this environment and production. Forwarded to all eight services, so the value multiplies by eight across the stack. The service module cross-checks it against its autoscaling floor, so main.tf must keep that floor no higher than this value -- see the note below. | `number` | `1` | no |
| <a name="input_ecs_task_cpu"></a> [ecs\_task\_cpu](#input\_ecs\_task\_cpu) | CPU units allocated to each service task, where 1024 units is one virtual CPU. Forwarded to every service this root creates, so all eight are sized alike within an environment. Fargate accepts only certain memory values for a given CPU size, so this value and ecs\_task\_memory are not independently free -- see ecs\_task\_memory. | `number` | `512` | no |
| <a name="input_ecs_task_memory"></a> [ecs\_task\_memory](#input\_ecs\_task\_memory) | Memory in mebibytes allocated to each service task, forwarded to every service this root creates alongside ecs\_task\_cpu. Fargate accepts only certain memory values for a given CPU size, so this input is validated against ecs\_task\_cpu rather than on its own; the two must be changed together. | `number` | `1024` | no |
| <a name="input_environment"></a> [environment](#input\_environment) | Environment identity forwarded to all sixteen modules, where it is interpolated into resource names, name tags, log group names and composed secret names so that this environment's resources are distinguishable from the other's. Pinned to `dev`, the environment whose state this Terraform root owns; infra/envs/prod declares the same input pinned to `prod`. | `string` | `"dev"` | no |
| <a name="input_image_digests"></a> [image\_digests](#input\_image\_digests) | Immutable sha256 digests keyed by ECR artifact name, for example { "auth-service" = "sha256:<64 hex>" }. Any artifact named here is deployed by digest instead of by image\_tag. Required in production, where the ECS service module refuses a mutable tag. | `map(string)` | `{}` | no |
| <a name="input_log_retention_days"></a> [log\_retention\_days](#input\_log\_retention\_days) | Days that log groups and log-derived object lifecycles in this environment retain data. Forwarded to the service, API gateway, batch, observability and content distribution modules, so one value governs the whole environment's retention rather than each module carrying its own. Must be one of the periods the log service accepts, and must be greater than zero because one consumer reads it as an object lifecycle expiry. | `number` | `7` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Prefix concatenated into the name of every resource this root creates, ahead of the component and the environment, giving the whole stack one greppable identity. Lowercase letters, digits and hyphens only, beginning and ending with a letter or digit, at most 32 characters. | `string` | `"carddemo"` | no |
| <a name="input_secret_recovery_window_in_days"></a> [secret\_recovery\_window\_in\_days](#input\_secret\_recovery\_window\_in\_days) | Secrets Manager recovery window for generated database, TLS and Cognito credentials. Development uses immediate deletion so destroy/recreate remains repeatable. | `number` | `0` | no |
| <a name="input_skip_final_snapshot"></a> [skip\_final\_snapshot](#input\_skip\_final\_snapshot) | Whether destroying the database cluster skips taking a final snapshot first. Skipping makes the destroy fast and complete; taking one leaves a snapshot that survives the cluster and continues to bill until it is deleted by hand. | `bool` | `true` | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Common tag set merged into every taggable resource in this root, and in every module it calls, through the `default_tags` block in versions.tf. Values must be non-secret: tags are visible to any principal that can describe the resource and appear in cost-allocation exports. | `map(string)` | <pre>{<br/>  "Environment": "dev",<br/>  "ManagedBy": "terraform",<br/>  "Project": "carddemo"<br/>}</pre> | no |
| <a name="input_vpc_cidr"></a> [vpc\_cidr](#input\_vpc\_cidr) | IPv4 address space this environment's VPC is created with, and the block the network module carves all nine subnets from -- three public, three private-application and three isolated-data. The network module additionally requires a prefix length between /16 and /20 inclusive, the range in which nine usably sized subnets fit. | `string` | `"10.0.0.0/16"` | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_api_gateway"></a> [api\_gateway](#output\_api\_gateway) | Complete HTTP API endpoint, identifier, stage, authorizer and VPC-link contract. |
| <a name="output_batch_orchestration"></a> [batch\_orchestration](#output\_batch\_orchestration) | Complete daily/ad-hoc state-machine, execution-role and log-group contract. |
| <a name="output_batch_schedule"></a> [batch\_schedule](#output\_batch\_schedule) | Complete EventBridge Scheduler schedule, group and execution-role contract. |
| <a name="output_database"></a> [database](#output\_database) | Complete Aurora endpoint, identifier, subnet, security-group and RDS-managed master-secret handle contract. |
| <a name="output_datasets"></a> [datasets](#output\_datasets) | Complete versioned dataset-bucket, prefix and generation-retention contract. |
| <a name="output_ecs_cluster"></a> [ecs\_cluster](#output\_ecs\_cluster) | Complete ECS cluster and capacity-provider contract. |
| <a name="output_ecs_workloads"></a> [ecs\_workloads](#output\_ecs\_workloads) | Complete ecs-service output object per online service, batch task and data-migration task. |
| <a name="output_encryption"></a> [encryption](#output\_encryption) | Complete KMS module output object for the Aurora, S3/log, Secrets Manager and SQS keys. |
| <a name="output_identity"></a> [identity](#output\_identity) | Complete Cognito pool, client, scope, group, domain and generated-secret-handle contract. |
| <a name="output_load_balancer"></a> [load\_balancer](#output\_load\_balancer) | Complete internal ALB, HTTPS listener, rule and health-path contract. |
| <a name="output_messaging"></a> [messaging](#output\_messaging) | Complete SQS URL, ARN and name contract for all primary and dead-letter queues. |
| <a name="output_network"></a> [network](#output\_network) | Complete network module output object: VPC, subnet, route, endpoint, security-group, port and flow-log handles. |
| <a name="output_observability"></a> [observability](#output\_observability) | Complete notification, access-log bucket, managed log-group, dashboard and alarm contract. |
| <a name="output_registry"></a> [registry](#output\_registry) | Complete ECR repository URL, name, ARN and registry-id contract. |
| <a name="output_runtime_configuration"></a> [runtime\_configuration](#output\_runtime\_configuration) | Root-owned SSM parameter ARNs, operational Lambda ARNs, internal certificate handle and bootstrap result. |
| <a name="output_service_credentials"></a> [service\_credentials](#output\_service\_credentials) | Role-keyed service database secret handles; values remain in Secrets Manager. |
| <a name="output_spa"></a> [spa](#output\_spa) | Complete CloudFront distribution, origin bucket and origin-access-control contract. |
| <a name="output_spa_publication"></a> [spa\_publication](#output\_spa\_publication) | Non-secret values to copy into the protected GitHub dev environment variables consumed by deploy.yml. |
<!-- END_TF_DOCS -->

## Failure handling

| Failure | Response |
|:---|:---|
| Backend bucket/table/key missing | Apply bootstrap first and repeat initialization. |
| State lock held | Confirm another operation is not active before any unlock action. |
| Plan contains unexpected destroys | Stop; do not apply the plan. |
| Module validation rejects a value | Correct the owning variable rather than bypassing its invariant. |
| terraform-docs reports drift | Update the generated reference to match HCL before review. |
