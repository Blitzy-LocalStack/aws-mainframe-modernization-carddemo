# ECR module

This module creates exactly ten repositories: the eight service images, UI and
data-migration. Repositories use immutable tags, scan-on-push and bounded image
lifecycle rules.

## Design decisions

**Alternatives Considered:** creating a repository for `common-lib` was
rejected because it is a Maven dependency, not a runnable image. An eleventh
repository would remain empty and make the deployment inventory disagree with
the artifacts the workflow builds.

**Trade-offs:** immutable tags require a new tag for every build, which prevents
an existing deployment label from silently resolving to different bytes.

## Validation

```bash
terraform -chdir=infra/modules/ecr init -backend=false
terraform -chdir=infra/modules/ecr validate
tflint --chdir=infra/modules/ecr --config="$(pwd)/infra/.tflint.hcl"
```

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
| [aws_ecr_lifecycle_policy.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ecr_lifecycle_policy) | resource |
| [aws_ecr_repository.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ecr_repository) | resource |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_environment"></a> [environment](#input\_environment) | Deployment environment segment of the repository namespace, `dev` or `prod`. Required with no default, because it is the only thing keeping the two environment roots from colliding on all ten repository names within one account and region. | `string` | n/a | yes |
| <a name="input_kms_key_arn"></a> [kms\_key\_arn](#input\_kms\_key\_arn) | Exact customer-managed KMS key ARN encrypting image layers at rest, supplied by the `kms` module through the calling root. Registry-managed AES256 is deliberately not an accepted fallback. | `string` | n/a | yes |
| <a name="input_force_delete"></a> [force\_delete](#input\_force\_delete) | Whether `terraform destroy` may delete a repository that still holds images. False makes such a destroy fail rather than discard the artifacts a redeploy would need. | `bool` | `false` | no |
| <a name="input_image_tag_mutability"></a> [image\_tag\_mutability](#input\_image\_tag\_mutability) | Repository tag-mutability mode. The module accepts only `IMMUTABLE`, binding every deployment tag permanently to one image digest in every environment. | `string` | `"IMMUTABLE"` | no |
| <a name="input_max_image_count"></a> [max\_image\_count](#input\_max\_image\_count) | Number of tagged images the lifecycle policy retains per repository before expiring the oldest, bounding a tag set that grows by one entry with every deployment. | `number` | `30` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Application prefix that opens every repository name, keeping CardDemo repositories grouped and legible within a registry shared by every workload in the same account and region. | `string` | `"carddemo"` | no |
| <a name="input_repository_names"></a> [repository\_names](#input\_repository\_names) | Trailing name segment of each container image repository to create, one per deployable artifact; main.tf namespaces each entry as `<name_prefix>-<environment>/<entry>`. Defaults to the ten deployables of this migration: the eight services plus the browser SPA and the ETL image. | `set(string)` | <pre>[<br/>  "auth-service",<br/>  "account-service",<br/>  "card-service",<br/>  "transaction-service",<br/>  "reference-service",<br/>  "batch-service",<br/>  "authorization-service",<br/>  "reporting-service",<br/>  "ui",<br/>  "data-migration"<br/>]</pre> | no |
| <a name="input_scan_on_push"></a> [scan\_on\_push](#input\_scan\_on\_push) | Whether the registry scans each image for vulnerabilities server-side as it is pushed, with findings read from the registry rather than gated in the pushing pipeline. | `bool` | `true` | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Per-module tags merged onto every repository, additive to whatever the calling root already applies through its provider `default_tags`. | `map(string)` | `{}` | no |
| <a name="input_untagged_image_expiry_days"></a> [untagged\_image\_expiry\_days](#input\_untagged\_image\_expiry\_days) | Age in days at which an untagged image becomes eligible for expiry, reclaiming manifests and layers that no container task definition can reference. | `number` | `14` | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_registry_id"></a> [registry\_id](#output\_registry\_id) | Single string, not a map, because all the repositories in this module share one registry: its identifier, for a consumer that must authenticate to that registry before pushing or pulling. It is an AWS account identifier, so it must not be echoed into a build log. |
| <a name="output_repository_arns"></a> [repository\_arns](#output\_repository\_arns) | Map keyed by logical artifact name whose values are each repository's ARN, for scoping least-privilege IAM policy statements to one repository instead of to every repository in the registry. |
| <a name="output_repository_names"></a> [repository\_names](#output\_repository\_names) | Map keyed by logical artifact name whose values are the full namespaced names the registry stores, in the composed `<name_prefix>-<environment>/<artifact>` form, for IAM resource matching and for registry CLI calls that take a repository name rather than an address. |
| <a name="output_repository_urls"></a> [repository\_urls](#output\_repository\_urls) | Map keyed by logical artifact name, such as `auth-service`, whose values are the registry addresses each image is pushed to and pulled from; each is read from that repository's provider-computed `repository_url` attribute rather than composed from an account identifier and a region. |
<!-- END_TF_DOCS -->
