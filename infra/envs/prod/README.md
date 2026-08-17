# Production Terraform environment

This root composes all sixteen infrastructure modules, the API Gateway VPC Link
security-group edge and the non-secret Parameter Store discovery inventory for
production. Its module graph is intentionally identical to
[`../dev/README.md`](../dev/README.md); only sizing, retention and protection
values differ. [`../../README.md`](../../README.md) and the
[deploy runbook](../../../docs/runbooks/deploy.md) own package-wide procedures.

No live environment is claimed here. The configuration is authored and
statically validated; a backend-enabled plan or apply requires an operator's
short-lived federated AWS session and the production approval control.

## Parameters and expected result

| Parameter | Kind | Purpose |
|:---|:---|:---|
| `bucket` | backend config | Bootstrap state-bucket name |
| `region` | backend config | Bootstrap backend Region |
| `dynamodb_table` | backend config | Bootstrap lock-table name |
| `kms_key_id` | backend config | Bootstrap state CMK ARN |
| `terraform.tfvars` | tracked non-secret file | Production sizing, retention and protection values |
| deployment variables | runtime inputs | Image digests, IAM boundary, public DNS zone/domain names, the HMAC secret reference and glue Lambda ARNs |

A successful apply publishes the API/SPA entry points, service and queue
identifiers, database connection facts and the canonical Parameter Store paths
listed in the generated Outputs table. It publishes **no** certificate identifier
of any kind.

Refactoring Rationale: this said the apply publishes "automatically issued
edge/service certificates, the service-TLS secret identifier". Every part of that
was wrong, in two different ways. Nothing here issues a certificate: the two the
stack uses are operator-supplied INPUTS — `alb_certificate_arn` for the load
balancer listener and `cloudfront_acm_certificate_arn` for the distribution — so a
caller already holds both ARNs before the apply begins, and echoing an input back
as an output would only invite a reader to treat it as something this root minted.
Internal listener material is not issued here either: each image's
`config/docker/generate-listener-material.sh` mints that task's own key pair and
self-signed certificate at container start-up, which is why no service certificate
and no service-TLS secret exist to publish. Measured against `outputs.tf`, none of
the eighteen outputs carries a certificate in its value, and `runtime_configuration`
records the same withdrawal in its own description. An output summary is the first
thing a reader consults to learn what an apply hands back, so naming artifacts that
were never there sends them hunting the Outputs table for rows that do not exist.

## Bootstrap and initialization

`infra/bootstrap` must exist before backend-enabled initialization. The backend
is partial because its bucket and key contain account-resolved identifiers and
Terraform evaluates backends before input variables.

```bash
# Read the bootstrap outputs used by the partial backend.
terraform -chdir=infra/bootstrap output

# Initialize with the four values returned by bootstrap.
terraform -chdir=infra/envs/prod init \
  -backend-config="bucket=<state-bucket>" \
  -backend-config="region=<region>" \
  -backend-config="dynamodb_table=<lock-table>" \
  -backend-config="kms_key_id=<state-kms-key-arn>"
```

Alternatives Considered: committing a literal backend configuration or a
tfbackend file would store account-identifying values in source, while a
variable cannot be used because backend evaluation happens first.

## Plan and apply

```bash
# Create the review artifact from the tracked non-secret values.
# WHY : Assumptions: the artifact is named `prod.tfplan`, matching
#       [`infra/README.md`](../../README.md), because a saved plan embeds the
#       resolved value of every attribute the apply will set -- including the
#       generated Aurora master password and the Cognito seed-user passwords -- and
#       the repository ignore rules match on the `.tfplan` SUFFIX. A bare `tfplan`
#       has no suffix to match and stays trackable:
#       `git check-ignore -v infra/envs/prod/prod.tfplan` resolves, while the same
#       command on `infra/envs/prod/tfplan` returns nothing. The exposure is
#       largest here, because this root's plan carries the production credentials.
terraform -chdir=infra/envs/prod plan -out=prod.tfplan -var-file=terraform.tfvars

# Apply exactly the reviewed artifact after production approval.
terraform -chdir=infra/envs/prod apply prod.tfplan

# Remove the artifact once the apply completes.
# WHY : Trade-offs: an ignore rule reduces accidental staging and cannot defeat
#       `git add -f`, so deleting the plan is the primary local safeguard and the
#       naming convention above is the cheap backstop.
rm -f infra/envs/prod/prod.tfplan
```

`apply -auto-approve` is not the normal path because it replans instead of
executing the artifact that was reviewed.

## Production posture

The database minimum remains above zero, two tasks preserve a target during
replacement, logs retain one year of operational evidence, and CloudFront uses
the full edge class. Deletion protection is enabled and a final snapshot is
required.

**Note:** a direct production destroy fails while deletion protection is active.
That is the intended control. Clearing protection is a separate reviewed apply,
followed by a saved destroy plan; the final snapshot remains as a rollback
asset. The remote-state bootstrap is removed only after the environment because
destroy needs the state it stores.

## Static validation

```bash
# Resolve modules/providers without contacting the remote backend.
terraform -chdir=infra/envs/prod init -backend=false

# Validate module interfaces and graph expressions.
terraform -chdir=infra/envs/prod validate

# Run the shared infrastructure linter.
tflint --chdir=infra/envs/prod --config="$(pwd)/infra/.tflint.hcl"
```

`-backend=false` validates configuration only; it cannot produce a deployable
remote-state plan.

## No secrets by construction

The tracked tfvars file contains no credential. Database and seed-user
credentials are generated into Secrets Manager; the card-selector signing key, the
messaging HMAC key, the two internal-identity signing keys, the pagination cursor
signing key and the reporting artifact-identity key -- six in all -- are generated
by this root itself through `ephemeral` resources written with `secret_string_wo`,
so none of
those values is recorded in state or in a plan artifact; the
extract-transform-load masking key arrives only as a secret ARN; and deployment
uses short-lived role assumption. Parameter Store contains endpoints and
identifiers only.

Refactoring Rationale: this paragraph previously said "TLS and HMAC values arrive
only as secret ARNs", which was true of one HMAC key and not of the other. The
masking key does arrive as an ARN because its derived tags must stay stable across
extract loads that may predate this root. Every other key **is** generated here,
and deliberately: nothing outside this stack produces or consumes any of them, so
an operator-supplied value would have a tfvars file to be committed in for no
benefit.

Refactoring Rationale: this paragraph singled out a **messaging** HMAC key as the
generated counterpart to the supplied masking key, and explained that the two were
separate so that a job reading cardholder extracts could not compute production
queue group identities. That key is **withdrawn** — see the record in `main.tf`
under "Messaging HMAC key -- WITHDRAWN" — because nothing injected the one Spring
bean it keyed once specification §0.4.1.8 fixed the queue group and deduplication
identities as the literal card number and transaction identifier. The
supplied-versus-generated distinction the paragraph was drawing still holds and is
restated without it.

### The six generated secrets are six, not one

Each of the generated values above exists for a different holder, and the
separation is what bounds the damage a single disclosure does. The Held-by column
is the set `infra/modules/ecs-service` asserts **biconditionally**, so a root that
hands a key to a workload outside its set — or withholds it from one inside — fails
at `terraform plan` rather than at run time:

| Secret | Held by | What holding it permits |
| --- | --- | --- |
| `card/selector-signing-key` | card only | minting or opening the opaque selector every single-card route addresses its row by, so a holder can address a card row it was never listed |
| `messaging/hmac-key` | authorization only | computing pending-authorization queue group and correlation identities |
| `internal-identity/authorization-signing-key` | authorization **and** account | minting or verifying the bearer token the AUTHORIZATION service presents to every internal account-context read the chain in `InternalApiSecurityConfig.internalPaths()` claims. Authorization signs with it, account verifies it. A cell describing what a leaked key unlocks is the one place a count must not be short, so it names the enumerating method rather than a number |
| `internal-identity/transaction-signing-key` | transaction **and** account | the same capability for the TRANSACTION service's account-context client. Transaction signs with it, account verifies it. Splitting the two callers is what lets account attribute a token to a caller it can actually verify: under one shared key either caller could mint a token carrying the other's subject |
| `pagination/cursor-signing-key` | auth, account, card, transaction, reference, reporting **and** authorization | sealing and opening a keyset page boundary, so a holder can forge a cursor and page into rows no query scoped to it |
| `reporting/artifact-hmac-key` | reporting only | recomputing the token a stored statement's object key is named under, so a holder can locate a named cardholder's statement objects by listing a prefix — with no read on the object itself, because the disclosure is carried by the key rather than the content |

Refactoring Rationale: this section has now been short twice, and the reason it
drifted both times is that the count was written as prose. It first named **three**
secrets and listed an "Internal TLS pair" held by every online service; the count
was short by two (`pagination/cursor-signing-key` and `card/selector-signing-key`
were both generated here and neither appeared) and the TLS row named a secret this
root does not create at all, because each image's
`config/docker/generate-listener-material.sh` mints that task's own key pair and
self-signed certificate at start-up. It was then corrected to **five**, which went
stale in turn when the single `internal-identity/signing-key` was split into the two
pairwise keys above. Two rows in the table had drifted with it: the
internal-identity row still described one key shared by three services, and the
pagination row named five holders where the gate admits seven — it had been widened
to include auth and card, both of which construct a `CursorToken` unconditionally
and crash-loop without the key. The count is therefore stated as a measurement —
six `aws_secretsmanager_secret` resources in this root's `main.tf`, each with an
`ephemeral` generator, and each table row's holder set copied from the gate in
`local.secret_sources_by_workload` rather than described — and the holder sets are
the ones `infra/modules/ecs-service` asserts biconditionally, so a drifted set now
fails at `terraform plan`.

Three of the six are deliberately held by more than one workload, and each is
shared for its own reason. The two internal-identity keys are **pairwise**: they are
symmetric, so a signer and its verifier must hold the same bytes, and the pairing is
the narrowest sharing that can work — authorization signs with one and transaction
with the other, while account, as the verifier of both, is the only workload holding
two. `pagination/cursor-signing-key` is symmetric in the same way but genuinely
service-wide, because any of the seven services that seals a page boundary may be
asked to open one.

The pairwise split is what makes rotation independent, and that is the point of it:
re-issuing one caller's key stalls that caller alone, where re-issuing the former
shared key stalled both signers at once. Rotating either internal-identity key is
still **coordinated across its own pair** — its signer and account must be redeployed
together, because account rejects a token signed under a key it has not yet received
and the signer cannot sign under a key it has already lost. Rotating one pair does
not require touching the other.

Rotating any of the six is an **attended** procedure that redeploys its holders
together, documented in [`docs/runbooks/deploy.md`](../../../docs/runbooks/deploy.md).
Automatic rotation is deliberately not configured for any of them, and each
resource carries its own recorded reason: changing either internal-identity value
while its signer still holds the previous one refuses that caller's internal
account-context reads with a 401, which stalls the authorization consumer rather
than degrading it; a rotated cursor key refuses every page boundary a client
currently holds; a rotated selector key makes every single-card route reachable
from a list already on screen stop resolving; and a rotated artifact key orphans
every statement object already published, because nothing recomputes the name it
was written under.

## Generated Terraform reference

<!-- BEGIN_TF_DOCS -->
### Requirements

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 1.15.0 |
| <a name="requirement_aws"></a> [aws](#requirement\_aws) | ~> 6.56 |
| <a name="requirement_random"></a> [random](#requirement\_random) | ~> 3.9 |

### Providers

| Name | Version |
|------|---------|
| <a name="provider_aws"></a> [aws](#provider\_aws) | 6.57.1 |
| <a name="provider_terraform"></a> [terraform](#provider\_terraform) | n/a |

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
| [aws_cloudwatch_log_group.ecs_execute_command](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_log_group) | resource |
| [aws_dynamodb_table.online_write_lease](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/dynamodb_table) | resource |
| [aws_iam_role.lambda](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role) | resource |
| [aws_iam_role.spa_publication](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role) | resource |
| [aws_iam_role_policy.dataset_retention_s3](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_iam_role_policy.lambda](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_iam_role_policy.lambda_xray](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_iam_role_policy.online_write_reconcile](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_iam_role_policy.spa_publication](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_lambda_function.database_admin](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_function) | resource |
| [aws_lambda_function.dataset_retention](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_function) | resource |
| [aws_lambda_function.quiesce](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_function) | resource |
| [aws_lambda_function.resume](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_function) | resource |
| [aws_lambda_function_event_invoke_config.dataset_retention](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_function_event_invoke_config) | resource |
| [aws_lambda_invocation.database_bootstrap](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_invocation) | resource |
| [aws_lambda_permission.dataset_retention_from_s3](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_permission) | resource |
| [aws_route53_record.internal_service](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/route53_record) | resource |
| [aws_route53_zone.internal_service](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/route53_zone) | resource |
| [aws_s3_bucket_notification.dataset_generations](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_notification) | resource |
| [aws_secretsmanager_secret.card_selector](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret) | resource |
| [aws_secretsmanager_secret.internal_identity_authorization](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret) | resource |
| [aws_secretsmanager_secret.internal_identity_transaction](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret) | resource |
| [aws_secretsmanager_secret.pagination_cursor](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret) | resource |
| [aws_secretsmanager_secret.reporting_artifact](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret) | resource |
| [aws_secretsmanager_secret_version.card_selector](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret_version) | resource |
| [aws_secretsmanager_secret_version.internal_identity_authorization](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret_version) | resource |
| [aws_secretsmanager_secret_version.internal_identity_transaction](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret_version) | resource |
| [aws_secretsmanager_secret_version.pagination_cursor](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret_version) | resource |
| [aws_secretsmanager_secret_version.reporting_artifact](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret_version) | resource |
| [aws_ssm_parameter.online_writes_enabled](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ssm_parameter) | resource |
| [aws_ssm_parameter.platform](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ssm_parameter) | resource |
| [aws_ssm_parameter.runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ssm_parameter) | resource |
| [terraform_data.batch_window_disjoint](https://registry.terraform.io/providers/hashicorp/terraform/latest/docs/resources/data) | resource |
| [terraform_data.cross_module_contracts](https://registry.terraform.io/providers/hashicorp/terraform/latest/docs/resources/data) | resource |
| [terraform_data.dataset_retention_path_ready](https://registry.terraform.io/providers/hashicorp/terraform/latest/docs/resources/data) | resource |
| [aws_caller_identity.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_iam_policy_document.account_runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.auth_runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.authorization_runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.batch_runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.card_runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.data_migration_runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.database_admin_lambda](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.dataset_retention_lambda](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.dataset_retention_s3](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.lambda_assume_role](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.lambda_logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.lambda_xray](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.online_write_lambda](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.online_write_reconcile](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.reporting_runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.spa_publication](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.spa_publication_assume_role](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_partition.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/partition) | data source |
| [aws_region.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/region) | data source |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_alarm_email_endpoints"></a> [alarm\_email\_endpoints](#input\_alarm\_email\_endpoints) | Email addresses subscribed to the production observability topic. REQUIRED and deliberately absent from terraform.tfvars: supply it out of band, because the repository carries no operator identity. At least one address must be given, so a production deployment cannot create alarms that notify nobody. | `list(string)` | n/a | yes |
| <a name="input_alb_certificate_arn"></a> [alb\_certificate\_arn](#input\_alb\_certificate\_arn) | Regional ACM certificate ARN presented by the internal ALB HTTPS listener. Forwarded to the alb module; the certificate must be issued in this environment's region and cover internal\_service\_domain\_name. | `string` | n/a | yes |
| <a name="input_cloudfront_acm_certificate_arn"></a> [cloudfront\_acm\_certificate\_arn](#input\_cloudfront\_acm\_certificate\_arn) | ACM certificate ARN issued in us-east-1 for the SPA distribution. Forwarded to cloudfront-spa and required to cover every entry in cloudfront\_aliases. | `string` | n/a | yes |
| <a name="input_cloudfront_aliases"></a> [cloudfront\_aliases](#input\_cloudfront\_aliases) | Non-empty list of bare DNS names the SPA distribution serves, optionally with a leading wildcard label. Every entry must be covered by cloudfront\_acm\_certificate\_arn and is forwarded unchanged to cloudfront-spa; entries are compared case-insensitively so one name cannot appear twice. | `list(string)` | n/a | yes |
| <a name="input_cloudfront_api_connect_src_origins"></a> [cloudfront\_api\_connect\_src\_origins](#input\_cloudfront\_api\_connect\_src\_origins) | Origins the SPA is permitted to reach with fetch or XHR, forwarded unchanged to cloudfront-spa as api\_connect\_src\_origins. Scheme and host only, no path and no trailing slash; normally the single API Gateway origin the SPA was built against. Supply it as TF\_VAR\_cloudfront\_api\_connect\_src\_origins, never in terraform.tfvars, so it tracks the deployed endpoint. | `list(string)` | n/a | yes |
| <a name="input_github_oidc_provider_arn"></a> [github\_oidc\_provider\_arn](#input\_github\_oidc\_provider\_arn) | ARN of the account-scoped GitHub Actions OIDC provider created by infra/bootstrap. Must name the token.actions.githubusercontent.com issuer specifically; any other provider ARN would federate a different identity provider into this account. | `string` | n/a | yes |
| <a name="input_github_repository"></a> [github\_repository](#input\_github\_repository) | GitHub repository in owner/name form whose protected prod environment may assume the SPA publication role. Becomes a subject condition in that role's trust policy, so it is what decides which repository can publish to this environment. | `string` | n/a | yes |
| <a name="input_image_tag"></a> [image\_tag](#input\_image\_tag) | Immutable image tag applied to all ten ECR repositories for this deployment, normally the source commit SHA supplied by the OIDC deployment workflow. The tag latest is rejected: it cannot identify a revision to roll back to. | `string` | n/a | yes |
| <a name="input_internal_service_domain_name"></a> [internal\_service\_domain\_name](#input\_internal\_service\_domain\_name) | Bare DNS name covered by alb\_certificate\_arn, with no scheme, port, path or wildcard. Forwarded to the alb module as its certificate identity and to api-gateway-http as the TLS server name the private integration verifies, which is why it must be a host and not a URL. | `string` | n/a | yes |
| <a name="input_mask_hmac_secret_arn"></a> [mask\_hmac\_secret\_arn](#input\_mask\_hmac\_secret\_arn) | Secrets Manager ARN of the environment-separated HMAC key the data-migration image uses for protected-field fingerprints. Supplied by the operator; never created or rotated by this configuration. The secret VALUE must be canonical standard base64 decoding to at least 32 bytes, which the image enforces by refusing to run on weaker material; docs/runbooks/deploy.md gives the creation command. | `string` | n/a | yes |
| <a name="input_mask_hmac_secret_kms_key_arn"></a> [mask\_hmac\_secret\_kms\_key\_arn](#input\_mask\_hmac\_secret\_kms\_key\_arn) | ARN of the customer-managed KMS key that encrypts mask\_hmac\_secret\_arn. Supplied by the operator alongside the secret; never created here. The data-migration task role is granted kms:Decrypt on exactly this key, through Secrets Manager, so a secret protected by a different key fails to decrypt rather than succeeding through a wider key policy. | `string` | n/a | yes |
| <a name="input_permissions_boundary_arn"></a> [permissions\_boundary\_arn](#input\_permissions\_boundary\_arn) | ARN of the same-account customer-managed IAM policy used as the permissions boundary on every role this deployment creates -- all nine aws\_iam\_role resources under infra/, which is ten effective role instances per environment plus the two per ECS service. Passed into every module that creates a role, each of which asserts the ARN belongs to this account. Supplied by the operator or the deploy workflow; never created here. | `string` | n/a | yes |
| <a name="input_aurora_backup_retention_period"></a> [aurora\_backup\_retention\_period](#input\_aurora\_backup\_retention\_period) | Days of automated backups the production cluster retains, forwarded to the database module. Aurora cannot disable automated backups, so 1 is the floor and 35 the service maximum; there is no value here meaning none. | `number` | `35` | no |
| <a name="input_aurora_engine_version"></a> [aurora\_engine\_version](#input\_aurora\_engine\_version) | Aurora PostgreSQL engine version for the production cluster, forwarded to the database module, which requires the value and supplies no default. Must be a numeric version such as 16.8 -- not an engine name and not a parameter-group family -- and its major line must match aurora\_parameter\_group\_family, which that module verifies. | `string` | `"16.6"` | no |
| <a name="input_aurora_max_capacity"></a> [aurora\_max\_capacity](#input\_aurora\_max\_capacity) | Ceiling of the serverless database's capacity range, in Aurora Capacity Units. It has to leave room for the nightly batch chain, which is the heaviest thing this cluster does and which runs against the same writer the online services use. | `number` | `32` | no |
| <a name="input_aurora_min_capacity"></a> [aurora\_min\_capacity](#input\_aurora\_min\_capacity) | Floor of the serverless database's capacity range, in Aurora Capacity Units. Held above zero in this environment, so the cluster never pauses and no query ever pays a resume delay. | `number` | `2` | no |
| <a name="input_aurora_parameter_group_family"></a> [aurora\_parameter\_group\_family](#input\_aurora\_parameter\_group\_family) | Aurora PostgreSQL cluster parameter-group family whose trailing major must match aurora\_engine\_version's leading component, for example aurora-postgresql16 against 16.8. Forwarded to the database module, which rejects a mismatched pair. | `string` | `"aurora-postgresql16"` | no |
| <a name="input_aurora_preferred_backup_window"></a> [aurora\_preferred\_backup\_window](#input\_aurora\_preferred\_backup\_window) | Daily UTC window in which the production cluster's automated backups are taken, of the form hh:mm-hh:mm with no day-of-week prefix. Must stay clear of the window batch\_schedule\_expression can start the nightly chain in, which terraform\_data.batch\_window\_disjoint in main.tf asserts. | `string` | `"07:00-08:00"` | no |
| <a name="input_aurora_preferred_maintenance_window"></a> [aurora\_preferred\_maintenance\_window](#input\_aurora\_preferred\_maintenance\_window) | Weekly UTC maintenance window for the production cluster, of the form ddd:hh:mm-ddd:hh:mm such as sun:09:00-sun:10:00. Kept outside both the nightly batch start window and aurora\_preferred\_backup\_window, because maintenance may fail the cluster over and abort in-flight batch tasks. | `string` | `"sun:09:00-sun:10:00"` | no |
| <a name="input_aurora_seconds_until_auto_pause"></a> [aurora\_seconds\_until\_auto\_pause](#input\_aurora\_seconds\_until\_auto\_pause) | Idle interval, in seconds, before a cluster whose capacity floor is zero pauses. INERT in this environment, because this root's floor is held above zero and Aurora only pauses a cluster that can scale to zero. main.tf forwards it to the database module unconditionally, so the value must still be one the service accepts; it is declared here so the two environment roots' input surfaces match exactly. | `number` | `300` | no |
| <a name="input_aws_region"></a> [aws\_region](#input\_aws\_region) | AWS region this environment is provisioned into. Read by the aws provider in versions.tf and therefore inherited by every one of the sixteen modules this root calls. Accepts a standard region identifier, for example us-east-1 or ap-southeast-4. | `string` | `"us-east-1"` | no |
| <a name="input_batch_schedule_expression"></a> [batch\_schedule\_expression](#input\_batch\_schedule\_expression) | EventBridge Scheduler cron expression that starts the nightly batch chain, of the form cron(...) in UTC; the rate(...) and at(...) forms are rejected. main.tf trims the wrapper and reads the hour field to assert the start window is disjoint from the Aurora backup and maintenance windows, so the wrapper and a numeric hour are required for that check to be evaluable at all. | `string` | `"cron(0 2 * * ? *)"` | no |
| <a name="input_batch_schedule_maximum_event_age_seconds"></a> [batch\_schedule\_maximum\_event\_age\_seconds](#input\_batch\_schedule\_maximum\_event\_age\_seconds) | Outer bound, in seconds, on how long a failed delivery of the nightly batch trigger may keep being retried before it is sent to the dead-letter queue. Narrows the scheduler module's 86400 default so that a retried delivery cannot start the chain outside its intended window; must keep the start window clear of aurora\_preferred\_backup\_window and aurora\_preferred\_maintenance\_window, which terraform\_data.batch\_window\_disjoint asserts. | `number` | `3600` | no |
| <a name="input_cloudfront_price_class"></a> [cloudfront\_price\_class](#input\_cloudfront\_price\_class) | Edge locations the browser application's distribution is served from. A wider class serves more regions at higher cost; it changes latency for distant users and nothing else about how the distribution behaves. | `string` | `"PriceClass_All"` | no |
| <a name="input_deletion_protection"></a> [deletion\_protection](#input\_deletion\_protection) | Whether the stateful resources in this environment refuse deletion until the flag is cleared. It is forwarded to both resources that offer the protection, the database cluster and the user pool, so one value governs the environment's whole teardown posture. | `bool` | `true` | no |
| <a name="input_ecs_desired_count"></a> [ecs\_desired\_count](#input\_ecs\_desired\_count) | Tasks each service runs. This is the input that decides whether a service survives losing one task, and it is the clearest single difference between a production environment and a development one. | `number` | `2` | no |
| <a name="input_ecs_task_cpu"></a> [ecs\_task\_cpu](#input\_ecs\_task\_cpu) | CPU units allocated to each service task, where 1024 units is one virtual CPU. Passed to every service this root creates, so all eight are sized alike within an environment; the difference between the environments is what this value is, not which services get it. | `number` | `1024` | no |
| <a name="input_ecs_task_memory"></a> [ecs\_task\_memory](#input\_ecs\_task\_memory) | Memory in mebibytes allocated to each service task. Fargate accepts only certain memory values for a given CPU size, so this input is validated against ecs\_task\_cpu rather than on its own. | `number` | `2048` | no |
| <a name="input_environment"></a> [environment](#input\_environment) | Environment identity forwarded to every module and composed into the name of nearly every resource this root creates. Pinned to prod: this root's state and resource names are production's, so no other value is admissible here. | `string` | `"prod"` | no |
| <a name="input_image_digests"></a> [image\_digests](#input\_image\_digests) | Immutable sha256 digests keyed by ECR artifact name, for example { "auth-service" = "sha256:<64 hex>" }. Any artifact named here is deployed by digest instead of by image\_tag. Required in production, where the ECS service module refuses a mutable tag. | `map(string)` | `{}` | no |
| <a name="input_log_retention_days"></a> [log\_retention\_days](#input\_log\_retention\_days) | Days the log groups this root's modules create retain events. Passed to the observability, service, batch and API modules together, so one value governs the whole environment's retention rather than each module carrying its own. | `number` | `365` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Prefix concatenated into the name of every resource this root creates, ahead of the component and the environment, giving the whole deployment one greppable identity. Two to twelve characters of lower-case letters, digits and hyphens, beginning with a letter and ending with a letter or digit -- the intersection of the naming rules the sixteen modules this root calls each impose on their own namespace. | `string` | `"carddemo"` | no |
| <a name="input_secret_recovery_window_in_days"></a> [secret\_recovery\_window\_in\_days](#input\_secret\_recovery\_window\_in\_days) | Secrets Manager recovery window, in days, applied to every secret this deployment generates: the six purpose secrets this root creates -- card-selector, messaging HMAC, the two pairwise internal-identity keys, pagination-cursor and reporting-artifact -- plus the per-service database credentials from the secrets module and the Cognito seed-user secrets. | `number` | `30` | no |
| <a name="input_skip_final_snapshot"></a> [skip\_final\_snapshot](#input\_skip\_final\_snapshot) | Whether destroying the database cluster skips taking a final snapshot first. Taking one leaves a snapshot that survives the cluster and continues to bill until it is deleted; skipping it makes the destroy fast and irreversible. | `bool` | `false` | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Common tag set merged into every taggable resource in this root, and in every module it calls, through the provider default\_tags block in versions.tf. Values must be non-secret: tags are visible to any principal that can describe the resource and appear in cost-allocation exports. | `map(string)` | <pre>{<br/>  "Environment": "prod",<br/>  "ManagedBy": "terraform",<br/>  "Project": "carddemo"<br/>}</pre> | no |
| <a name="input_vpc_cidr"></a> [vpc\_cidr](#input\_vpc\_cidr) | IPv4 CIDR allocated to the production VPC. Must use a /16 prefix: the reporting service's trusted-proxy pattern is derived from the first two octets of this block, which is exact only for a /16. | `string` | `"10.1.0.0/16"` | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_api_gateway"></a> [api\_gateway](#output\_api\_gateway) | Complete HTTP API endpoint, identifier, stage, authorizer and VPC-link contract. |
| <a name="output_batch_orchestration"></a> [batch\_orchestration](#output\_batch\_orchestration) | Complete daily, ad-hoc report, dataset round-trip and authorization-extract state-machine contract: the four machines, their per-machine execution roles, their log groups, the two bracket-release rules with their dead-letter queue and alarms, the resolved dataset staging root, and one deprecated dataset\_source\_extract\_prefix alias the module retains for a compatibility window. |
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
| <a name="output_runtime_configuration"></a> [runtime\_configuration](#output\_runtime\_configuration) | Root-owned SSM parameter ARNs (runtime and platform), the online-write flag parameter ARN, the four operational Lambda ARNs, and the database bootstrap result. Publishes NO certificate or listener-secret handle: each task mints its own listener material, and the certificate the load balancer presents is the operator-supplied alb\_certificate\_arn the caller already holds. |
| <a name="output_service_credentials"></a> [service\_credentials](#output\_service\_credentials) | Role-keyed service database secret handles; values remain in Secrets Manager. |
| <a name="output_spa"></a> [spa](#output\_spa) | Complete CloudFront distribution, origin bucket and origin-access-control contract. |
| <a name="output_spa_publication"></a> [spa\_publication](#output\_spa\_publication) | Non-secret values to copy into the protected GitHub prod environment variables consumed by deploy.yml. |
<!-- END_TF_DOCS -->

## Failure handling

| Failure | Response |
|:---|:---|
| Backend bucket/table/key missing | Apply bootstrap first and repeat initialization. |
| State lock held | Confirm another operation is not active before any unlock action. |
| Plan contains unexpected destroys | Stop; do not apply the plan. |
| Destroy is blocked by protection | Review and apply the protection change separately before a destroy plan. |
| Module validation rejects a value | Correct the owning variable rather than bypassing its invariant. |
| terraform-docs reports drift | Update the generated reference to match HCL before review. |
