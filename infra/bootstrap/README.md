# Terraform State Bootstrap

> **Purpose.** This root creates the protected S3 bucket, DynamoDB lock table,
> customer-managed KMS key, and object-level audit trail used by the CardDemo
> Terraform environments.
>
> **Source of truth.** The resources, inputs, and outputs in this directory are
> authoritative for bootstrap behavior. The generated reference below is kept
> synchronized by `infra/.terraform-docs.yml`.

Apply this root once before initializing `infra/envs/dev` or
`infra/envs/prod`. It intentionally uses local state during its first apply:
remote state cannot protect the resources that create the remote-state backend.

Assumptions: the operator supplies an AWS identity allowed to create KMS, S3,
DynamoDB, CloudTrail, CloudWatch Logs, and IAM policy resources in the selected
account and region. The operator records the outputs before configuring either
environment backend.

Trade-offs: bootstrap state remains local until the backend exists. The state
contains resource identifiers but no long-lived generated credential; it must
still be stored on an encrypted, access-controlled workstation and removed only
after the backend resources and their recovery procedure have been verified.

## Deploy

```bash
# WHAT: initialize and apply the remote-state bootstrap root.
# WHY : Assumptions: bootstrap must run before either environment can initialize
#       its S3 backend, because Terraform cannot store this root in infrastructure
#       that does not exist yet.
terraform -chdir=infra/bootstrap init
terraform -chdir=infra/bootstrap apply
```

Record `state_bucket_name`, `state_lock_table_name`, and `state_kms_key_arn`, then
place those non-secret identifiers in each environment's backend configuration.
Do not place credentials in `backend.tf`, command history, or committed
variable files.

## Validate

```bash
# WHAT: verify formatting, configuration validity, lint, and generated contract
#       drift without changing AWS resources.
# WHY : Refactoring Rationale: the same commands run in CI, so local validation
#       catches a malformed backend contract before an environment depends on it.
terraform fmt -check -recursive infra/bootstrap
terraform -chdir=infra/bootstrap init -backend=false -lockfile=readonly
terraform -chdir=infra/bootstrap validate
tflint --chdir=infra/bootstrap --config="$(pwd)/infra/.tflint.hcl"
terraform-docs --config infra/.terraform-docs.yml \
  --output-check infra/bootstrap
```

## Teardown

Destroy bootstrap **last**, after both environment roots are destroyed and
their state objects, retained versions, and audit evidence have been exported
or deleted under the approved retention procedure.

```bash
# WHAT: destroy the remote-state controls after every dependent environment has
#       been removed and its retained state has been dispositioned.
# WHY : Trade-offs: the backend cannot destroy itself while active, so teardown
#       requires an explicit final operator step; keeping it last prevents loss
#       of the state needed to remove the application stack safely.
terraform -chdir=infra/bootstrap destroy
```

## Generated Terraform Contract

<!-- BEGIN_TF_DOCS -->
### Requirements

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | ~> 1.15.0 |
| <a name="requirement_aws"></a> [aws](#requirement\_aws) | ~> 6.56 |

### Providers

| Name | Version |
|------|---------|
| <a name="provider_aws"></a> [aws](#provider\_aws) | 6.57.1 |

### Resources

| Name | Type |
|------|------|
| [aws_cloudtrail.state_object_access](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudtrail) | resource |
| [aws_dynamodb_table.state_lock](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/dynamodb_table) | resource |
| [aws_iam_openid_connect_provider.github_actions](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_openid_connect_provider) | resource |
| [aws_kms_alias.state](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_alias) | resource |
| [aws_kms_key.state](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_key) | resource |
| [aws_s3_bucket.state](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket) | resource |
| [aws_s3_bucket.state_audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket) | resource |
| [aws_s3_bucket_lifecycle_configuration.state](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_lifecycle_configuration) | resource |
| [aws_s3_bucket_lifecycle_configuration.state_audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_lifecycle_configuration) | resource |
| [aws_s3_bucket_ownership_controls.state](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_ownership_controls) | resource |
| [aws_s3_bucket_ownership_controls.state_audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_ownership_controls) | resource |
| [aws_s3_bucket_policy.state](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_policy) | resource |
| [aws_s3_bucket_policy.state_audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_policy) | resource |
| [aws_s3_bucket_public_access_block.state](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_public_access_block) | resource |
| [aws_s3_bucket_public_access_block.state_audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_public_access_block) | resource |
| [aws_s3_bucket_server_side_encryption_configuration.state](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_server_side_encryption_configuration) | resource |
| [aws_s3_bucket_server_side_encryption_configuration.state_audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_server_side_encryption_configuration) | resource |
| [aws_s3_bucket_versioning.state](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_versioning) | resource |
| [aws_s3_bucket_versioning.state_audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_versioning) | resource |
| [aws_caller_identity.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_iam_policy_document.state_audit_bucket](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.state_bucket](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.state_key](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_partition.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/partition) | data source |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_audit_log_retention_days"></a> [audit\_log\_retention\_days](#input\_audit\_log\_retention\_days) | Finite lifecycle horizon for validated CloudTrail state-object data-event logs. | `number` | `2557` | no |
| <a name="input_aws_region"></a> [aws\_region](#input\_aws\_region) | AWS region the state bucket and lock table are created in. Read by the aws provider in versions.tf, composed into the default bucket name in main.tf, and echoed by outputs.tf so it can be transcribed into each environment root's backend configuration. Accepts a standard region identifier, for example us-east-1 or ap-southeast-4. | `string` | `"us-east-1"` | no |
| <a name="input_lock_table_name"></a> [lock\_table\_name](#input\_lock\_table\_name) | Explicit name for the DynamoDB state-lock table, overriding the name main.tf composes from name\_prefix. Resolved by the matching coalesce in main.tf, so leaving it null selects the composed name. Set it to adopt an existing table or to satisfy an account naming standard. | `string` | `null` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Leading component of the composed names for both halves of the backend. Read by the locals block in main.tf, which appends the fixed -tfstate- segment, the caller's account identifier and aws\_region to build the bucket name, and derives the lock table name from the same prefix. | `string` | `"carddemo"` | no |
| <a name="input_state_bucket_force_destroy"></a> [state\_bucket\_force\_destroy](#input\_state\_bucket\_force\_destroy) | Whether terraform destroy may delete the state bucket while it still holds objects and non-current versions. Read by force\_destroy on the bucket resource in main.tf. Keep false so destroying a populated bucket fails; set true only for a deliberate account decommission, which permanently discards all state history. | `bool` | `false` | no |
| <a name="input_state_bucket_name"></a> [state\_bucket\_name](#input\_state\_bucket\_name) | Explicit name for the Terraform state bucket, overriding the name main.tf composes from name\_prefix, the caller's account identifier and aws\_region. Resolved by coalesce in main.tf, so leaving it null selects the composed name. Set it to adopt an existing bucket or to satisfy an account naming standard. | `string` | `null` | no |
| <a name="input_state_noncurrent_versions_to_retain"></a> [state\_noncurrent\_versions\_to\_retain](#input\_state\_noncurrent\_versions\_to\_retain) | Number of newest noncurrent Terraform state versions retained regardless of age. | `number` | `20` | no |
| <a name="input_state_version_retention_days"></a> [state\_version\_retention\_days](#input\_state\_version\_retention\_days) | Minimum age in days before a state object version beyond the retained recent-version count may expire. | `number` | `365` | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Common tag set merged into every taggable resource in this root through the provider default\_tags block in versions.tf. Values must be non-secret: tags are visible to any principal that can describe the resource and appear in cost-allocation exports. | `map(string)` | <pre>{<br/>  "Component": "tfstate-backend",<br/>  "ManagedBy": "terraform",<br/>  "Project": "carddemo"<br/>}</pre> | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_aws_region"></a> [aws\_region](#output\_aws\_region) | AWS region containing both the state bucket and the lock table. Supply it as the `region` argument of each environment root's S3 backend block. |
| <a name="output_state_audit_bucket_name"></a> [state\_audit\_bucket\_name](#output\_state\_audit\_bucket\_name) | Name of the versioned S3 bucket receiving validated CloudTrail data-event logs for every Terraform state object read and write. |
| <a name="output_state_bucket_name"></a> [state\_bucket\_name](#output\_state\_bucket\_name) | Name of the versioned, encrypted S3 bucket that holds Terraform state for every other root in this repository. Supply it as the `bucket` argument of each environment root's S3 backend block. |
| <a name="output_state_kms_key_arn"></a> [state\_kms\_key\_arn](#output\_state\_kms\_key\_arn) | ARN of the bootstrap-owned customer-managed KMS key encrypting Terraform state, the lock table and immutable access-audit logs. |
| <a name="output_state_lock_table_name"></a> [state\_lock\_table\_name](#output\_state\_lock\_table\_name) | Name of the DynamoDB table Terraform uses to serialise concurrent writes to the state object. Supply it as the `dynamodb_table` argument of each environment root's S3 backend block. Its partition key is `LockID` of type String, which is the schema the S3 backend requires and writes the lock item under. |
| <a name="output_state_object_access_trail_arn"></a> [state\_object\_access\_trail\_arn](#output\_state\_object\_access\_trail\_arn) | ARN of the CloudTrail trail whose advanced selector records object-level access to the Terraform state bucket. |
<!-- END_TF_DOCS -->
