# `infra/modules/kms/` — Customer-managed encryption keys

**Purpose.** This module creates four customer-managed KMS keys, one policy
resource and one alias per key. The keys separate Aurora records, shared
object/log/notification data, Secrets Manager values and SQS payloads into
independent cryptographic policy boundaries.

This README is the mandatory Explainability carrier for the module's HCL.
Terraform has no docstring construct, so the typed variables, output
descriptions and adjacent rationale in the four `.tf` files provide the
mechanical half of Rule 1 while this document provides the prose half. See the
[documentation standard](../../../docs/CODE_DOCUMENTATION_STANDARD.md).

## Why four keys

**Alternatives Considered:** one shared key for the complete stack would incur
one monthly key charge and require one policy instead of four. It was rejected
because the single policy would authorize every data class, so one mistaken
principal or condition would reach database records, dataset objects, secrets
and queue payloads together.

**Trade-offs:** four keys incur four monthly key charges and four policies to
review. The accepted benefit is a policy boundary per data class: a change to
the queue key cannot grant access to database records, and a change to the
Secrets Manager key cannot grant access to dataset generations.

## Measured source context

The immutable CICS definition contains eight VSAM `FILE` resources at
`app/csd/CARDDEMO.CSD:1,13,25,37,50,63,76,88`. Those eight resources specify
`JOURNAL(NO)` at lines 7, 19, 31, 44, 57, 70, 82 and 94, and
`RECOVERY(NONE)` at lines 9, 21, 33, 46, 59, 72, 84 and 96.

**Refactoring Rationale:** the target adds encryption at rest and explicit
policy boundaries to the VSAM replacement data path while leaving the source
path unchanged. This statement is scoped to the VSAM tier: the authorization
extension's Db2 index enables image copy at
`app/app-authorization-ims-db2-mq/ddl/XAUTHFRD.ddl:4` with `COPY YES`.
The migration adds a path; it does not remove one. The broader identity and
data-handling treatment is documented in
[security and identity](../../../docs/architecture/security-and-identity.md).

## Key and consumer map

| Key | Protected target data | Primary consumers |
|---|---|---|
| Aurora | Relational account, card, customer, ledger and authorization records. Source contracts include exact money at `app/cpy/CVACT01Y.cpy:7`, CVV at `app/cpy/CVACT02Y.cpy:7`, and national/government identifiers at `app/cpy/CVCUS01Y.cpy:17-18`. | `aurora-postgresql` |
| S3 | Ten dataset-generation families, the CloudFront SPA origin, CloudFront log delivery, explicitly named CloudWatch log groups and the encrypted alarm topic. | `s3-datasets`, `cloudfront-spa`, `network`, `step-functions-batch`, `observability` |
| Secrets Manager | Generated database credentials, Cognito client material and seed-user bootstrap values. The source password field at `app/cpy/CSUSR01Y.cpy:21` is not carried forward. | `secrets`, `cognito`, `aurora-postgresql` |
| SQS | All five application queues and their five dead-letter queues. | `sqs` |

**Assumptions:** each consuming module receives the appropriate key ARN from an
environment root. This module does not call siblings, so a dependency cycle
cannot be introduced by a consumer feeding back its resource identity for an
exact key-policy condition. Key creation and policy application are separate
resources specifically to permit that acyclic feedback edge.

## Policy model

The account root retains administrative key-policy authority. Cryptographic use
is separately constrained by exact role ARNs, service principals, service
context and resource-specific encryption context:

- Aurora use is bound to the database resource identifiers supplied by the
  environment root.
- S3, CloudFront, CloudWatch Logs and SNS use is bound to exact bucket,
  distribution, log-delivery, log-group or topic ARNs.
- Secrets Manager use is bound to exact secret ARNs.
- SQS use is bound to exact same-account queue role ARNs and the regional SQS
  service path.

**Assumptions:** role lists default to empty because a root may create keys
before task roles exist. Empty lists do not create wildcard grants; later
composition supplies exact principals and contexts. No default contains an
account identifier or ARN.

**Trade-offs:** key ARNs and IDs are not marked sensitive outputs. They are
resource identifiers rather than key material, and keeping them visible lets a
plan reviewer detect a data class wired to the wrong key. Authorization remains
in the key and IAM policies, not in output redaction.

## Module boundary and usage

This directory is a called module, not a Terraform root:

```hcl
module "kms" {
  source = "../../modules/kms"

  environment = var.environment
}
```

The calling `dev` and `prod` roots own provider configuration, default tags and
state backends. This module therefore has no `backend` block, no provider
configuration body and no sibling `module` block. Environment differences are
limited to caller-owned parameters such as the deletion window; both
environments retain the same four-key topology.

The module contains no key material, secret value, credential, committed AWS
account identifier or literal resource ARN. Account and Region identity are
resolved during planning, trusted principals arrive through typed variables,
and created key identifiers flow outward through outputs.

## Validation

Run these checks from the repository root:

```bash
. /etc/profile.d/00-carddemo-toolchain.sh
terraform fmt -check -recursive infra/modules/kms
terraform -chdir=infra/modules/kms init -backend=false -input=false
terraform -chdir=infra/modules/kms validate
tflint --chdir=infra/modules/kms --config="$(pwd)/infra/.tflint.hcl"
terraform-docs --config infra/.terraform-docs.yml --output-check infra/modules/kms
```

Formatting, validation, linting and generated-document drift are gating checks.
Rotation and policy scope are expressed by resources and policy conditions
rather than by scanner suppressions.

**Trade-offs:** no `prevent_destroy` lifecycle is set because the target
acceptance criteria require an environment root to be destroyable. KMS still
enforces its configured deletion window, so destruction schedules key deletion
rather than erasing key material immediately.

The module is authored and statically validated. Applying a root against a live
AWS account remains an operator action outside this scope; no claim is made that
a key has been created, rotated, audited or assessed against a compliance
standard.

See the [infrastructure guide](../../README.md), the
[deployment runbook](../../../docs/runbooks/deploy.md), and the
[documentation standard](../../../docs/CODE_DOCUMENTATION_STANDARD.md).

## Generated Terraform reference

The block below is generated by terraform-docs v0.20.0 from this module's HCL.

<!-- BEGIN_TF_DOCS -->
### Requirements

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 1.15.0 |
| <a name="requirement_aws"></a> [aws](#requirement\_aws) | ~> 6.56 |

### Providers

| Name | Version |
|------|---------|
| <a name="provider_aws"></a> [aws](#provider\_aws) | 6.57.1 |

### Resources

| Name | Type |
|------|------|
| [aws_kms_alias.aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_alias) | resource |
| [aws_kms_alias.s3](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_alias) | resource |
| [aws_kms_alias.secrets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_alias) | resource |
| [aws_kms_alias.sqs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_alias) | resource |
| [aws_kms_key.aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_key) | resource |
| [aws_kms_key.s3](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_key) | resource |
| [aws_kms_key.secrets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_key) | resource |
| [aws_kms_key.sqs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_key) | resource |
| [aws_kms_key_policy.aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_key_policy) | resource |
| [aws_kms_key_policy.s3](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_key_policy) | resource |
| [aws_kms_key_policy.secrets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_key_policy) | resource |
| [aws_kms_key_policy.sqs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_key_policy) | resource |
| [aws_caller_identity.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_iam_policy_document.aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.s3](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.secrets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.sqs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_partition.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/partition) | data source |
| [aws_region.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/region) | data source |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_cloudfront_distribution_arn"></a> [cloudfront\_distribution\_arn](#input\_cloudfront\_distribution\_arn) | ARN of the CloudFront distribution allowed to decrypt the SPA origin under the S3 data-domain key. Required so the service-principal grant is always scoped to the exact distribution rather than omitted or widened. | `string` | n/a | yes |
| <a name="input_environment"></a> [environment](#input\_environment) | Environment name interpolated into all four KMS alias names, so one environment's keys are distinguishable from the other's in the console and in any alias-based key reference; must be `dev` or `prod`, the two environments that have a Terraform root under infra/envs/. | `string` | n/a | yes |
| <a name="input_aurora_encryption_context_ids"></a> [aurora\_encryption\_context\_ids](#input\_aurora\_encryption\_context\_ids) | Aurora cluster resource identifiers accepted in the `aws:rds:db-id` KMS encryption context. A non-empty Aurora role trust list requires at least one exact identifier. | `list(string)` | `[]` | no |
| <a name="input_aurora_key_user_role_arns"></a> [aurora\_key\_user\_role\_arns](#input\_aurora\_key\_user\_role\_arns) | Exact IAM role ARNs the Aurora key policy permits to use the key through Amazon RDS for the named Aurora encryption contexts. Wildcards, assumed-role session ARNs, users, roots and service principals are refused. | `list(string)` | `[]` | no |
| <a name="input_cloudfront_distribution_arns"></a> [cloudfront\_distribution\_arns](#input\_cloudfront\_distribution\_arns) | Exact same-account CloudFront distribution ARNs allowed to decrypt the SSE-KMS SPA origin through an origin access control. Empty means no CloudFront service-principal grant is installed. | `list(string)` | `[]` | no |
| <a name="input_cloudwatch_log_delivery_source_arns"></a> [cloudwatch\_log\_delivery\_source\_arns](#input\_cloudwatch\_log\_delivery\_source\_arns) | Exact same-account CloudWatch Logs delivery-source ARNs allowed to generate data keys for the CloudFront standard logging v2 S3 destination. Empty means no log-delivery service-principal grant is installed. | `list(string)` | `[]` | no |
| <a name="input_cloudwatch_log_group_arns"></a> [cloudwatch\_log\_group\_arns](#input\_cloudwatch\_log\_group\_arns) | Exact same-account CloudWatch log-group ARNs the regional Logs service may encrypt with the S3/data key. Empty installs no CloudWatch Logs service-principal grant. | `list(string)` | `[]` | no |
| <a name="input_deletion_window_in_days"></a> [deletion\_window\_in\_days](#input\_deletion\_window\_in\_days) | Days a destroyed key spends pending deletion before the service removes it and every ciphertext under it becomes permanently unreadable; the service accepts 7 through 30, and this is one of the retention values the dev and prod roots are permitted to set differently without changing the stack's shape. | `number` | `7` | no |
| <a name="input_enable_key_rotation"></a> [enable\_key\_rotation](#input\_enable\_key\_rotation) | Whether all four customer-managed keys rotate their key material automatically on the service's own interval. The module accepts only true because rotation is an architecture invariant rather than an environment preference. | `bool` | `true` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Prefix concatenated into each KMS alias name ahead of the key's purpose and the environment, giving the four keys one greppable identity shared with the rest of the stack's resource names; lowercase letters, digits and hyphens only, at most 32 characters, matching the characters an alias name accepts. | `string` | `"carddemo"` | no |
| <a name="input_s3_cloudfront_distribution_arns"></a> [s3\_cloudfront\_distribution\_arns](#input\_s3\_cloudfront\_distribution\_arns) | CloudFront distribution ARNs the S3 key's mandatory `cloudfront.amazonaws.com` decrypt grant is confined to; empty narrows the grant to every distribution in THIS account and partition, which is the tightest scope expressible without a dependency cycle. | `list(string)` | `[]` | no |
| <a name="input_s3_encryption_context_bucket_arns"></a> [s3\_encryption\_context\_bucket\_arns](#input\_s3\_encryption\_context\_bucket\_arns) | Exact S3 bucket ARNs accepted by the S3 key policy. The policy derives both bucket and object encryption-context forms so S3 Bucket Keys and direct object keys remain scoped to these buckets. | `list(string)` | `[]` | no |
| <a name="input_s3_key_user_role_arns"></a> [s3\_key\_user\_role\_arns](#input\_s3\_key\_user\_role\_arns) | Exact IAM role ARNs the S3 key policy permits to use the key through Amazon S3 for the named bucket encryption contexts. Wildcards, assumed-role session ARNs, users, roots and service principals are refused. | `list(string)` | `[]` | no |
| <a name="input_secrets_encryption_context_arns"></a> [secrets\_encryption\_context\_arns](#input\_secrets\_encryption\_context\_arns) | Exact Secrets Manager secret ARNs accepted in the `SecretARN` KMS encryption context. A non-empty Secrets Manager role trust list requires at least one exact secret ARN. | `list(string)` | `[]` | no |
| <a name="input_secrets_key_user_role_arns"></a> [secrets\_key\_user\_role\_arns](#input\_secrets\_key\_user\_role\_arns) | Exact IAM role ARNs the Secrets Manager key policy permits to use the key through Secrets Manager for the named secret encryption contexts. Wildcards, assumed-role session ARNs, users, roots and service principals are refused. | `list(string)` | `[]` | no |
| <a name="input_sns_topic_arns"></a> [sns\_topic\_arns](#input\_sns\_topic\_arns) | Exact same-account SNS topic ARNs that CloudWatch alarms and SNS may encrypt with the S3/data key. Empty installs no alert-topic service-principal grant. | `list(string)` | `[]` | no |
| <a name="input_sqs_key_user_role_arns"></a> [sqs\_key\_user\_role\_arns](#input\_sqs\_key\_user\_role\_arns) | Exact IAM role ARNs the SQS key policy permits to use the key through Amazon SQS. Wildcards, assumed-role session ARNs, users, roots and service principals are refused. | `list(string)` | `[]` | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Key-specific tags merged onto each of the four keys, layered on top of the common tag set the calling root already applies through its provider's `default_tags`; defaults to none, because the baseline tags arrive from the root rather than from this module. | `map(string)` | `{}` | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_aurora_key_alias_name"></a> [aurora\_key\_alias\_name](#output\_aurora\_key\_alias\_name) | Alias name this module assigns to the Aurora PostgreSQL key, carrying the module's name prefix and the environment so one environment's key is distinguishable from the other's. It remains valid if the key behind it is replaced, so a runbook step or a stored parameter that identifies the database's key should reference this rather than the identifier. |
| <a name="output_aurora_key_arn"></a> [aurora\_key\_arn](#output\_aurora\_key\_arn) | ARN of the customer-managed key that encrypts the Aurora PostgreSQL cluster at rest -- the account, customer, card, ledger, reference and authorization records, together with the cluster's automated backups and its managed master-credential secret. A calling environment root passes this into the aurora-postgresql module's `kms_key_arn` input: it is the key that module's `storage_encrypted` cluster is encrypted with, and the value its `kms_key_id`, master-credential-secret and Performance Insights arguments each take. |
| <a name="output_aurora_key_id"></a> [aurora\_key\_id](#output\_aurora\_key\_id) | Bare identifier -- not the ARN -- of the key that encrypts the Aurora PostgreSQL cluster, for a consumer whose resource argument or IAM policy condition key is written against a key identifier rather than a full ARN, and for naming this key unambiguously in an operator procedure. |
| <a name="output_s3_key_alias_name"></a> [s3\_key\_alias\_name](#output\_s3\_key\_alias\_name) | Alias name this module assigns to the S3 key, carrying the module's name prefix and the environment. It survives replacement of the key behind it, so it is the reference an operator procedure should use when recording which key a stored dataset generation was encrypted under. |
| <a name="output_s3_key_arn"></a> [s3\_key\_arn](#output\_s3\_key\_arn) | ARN of the customer-managed key for stored objects, CloudWatch log groups and the encrypted alert topic. A calling root passes it to s3-datasets and cloudfront-spa for bucket SSE-KMS, to network/ecs-service/api-gateway-http/step-functions-batch for log-group encryption, and to observability for its managed groups and SNS topic; the key policy admits only the regional logging and notification service paths in this account. |
| <a name="output_s3_key_id"></a> [s3\_key\_id](#output\_s3\_key\_id) | Bare identifier -- not the ARN -- of the key that encrypts the object, log and alert data class, for a consumer whose resource argument or IAM policy condition key is written against a key identifier rather than a full ARN. |
| <a name="output_s3_key_policy_id"></a> [s3\_key\_policy\_id](#output\_s3\_key\_policy\_id) | Provider identifier of the fully applied S3 key policy. The CloudFront logging v2 delivery consumes this as an ordering token so log delivery is not enabled before the exact distribution and delivery-source grants exist. |
| <a name="output_secrets_key_alias_name"></a> [secrets\_key\_alias\_name](#output\_secrets\_key\_alias\_name) | Alias name this module assigns to the Secrets Manager key, carrying the module's name prefix and the environment. It remains valid across replacement of the key behind it, so a credential-rotation or recovery procedure should identify the key by this name rather than by its identifier. |
| <a name="output_secrets_key_arn"></a> [secrets\_key\_arn](#output\_secrets\_key\_arn) | ARN of the customer-managed key that encrypts the Secrets Manager entries holding the generated database credential and the seed-user passwords -- values the stack generates at provisioning time rather than committing, which is the mechanism that lets no password field be carried into any target schema. A calling environment root passes this into the secrets module's `kms_key_arn` input and into the cognito module's `secrets_kms_key_arn` input, each of which sets it as the `kms_key_id` of the entries that module creates. |
| <a name="output_secrets_key_id"></a> [secrets\_key\_id](#output\_secrets\_key\_id) | Bare identifier -- not the ARN -- of the key that encrypts the stored credentials, for a consumer whose resource argument or IAM policy condition key is written against a key identifier rather than a full ARN. |
| <a name="output_sqs_key_alias_name"></a> [sqs\_key\_alias\_name](#output\_sqs\_key\_alias\_name) | Alias name this module assigns to the SQS key, carrying the module's name prefix and the environment. It survives replacement of the key behind it, so a procedure that inspects or redrives a queue should identify the key by this name rather than by its identifier. |
| <a name="output_sqs_key_arn"></a> [sqs\_key\_arn](#output\_sqs\_key\_arn) | ARN of the customer-managed key that encrypts queue message payloads at rest -- the authorization request and reply, the split account/date inquiry requests, the shared inquiry reply and the error sink. A calling environment root passes this into the sqs module's `kms_key_arn` input, which sets it on all six queues and their six dead-letter queues. |
| <a name="output_sqs_key_id"></a> [sqs\_key\_id](#output\_sqs\_key\_id) | Bare identifier -- not the ARN -- of the key that encrypts the queue payloads, for a consumer whose resource argument or IAM policy condition key is written against a key identifier rather than a full ARN. |
<!-- END_TF_DOCS -->
