# Secrets module

This module generates per-service PostgreSQL credentials, writes only
write-only secret documents, creates the rotation Lambda and applies each
credential to Aurora through the RDS Data API.

## Design decisions

**Refactoring Rationale:** no password is accepted as an input or published as
an output. Removing the input surface makes repository credentials impossible
to express rather than relying on every reviewer to notice one.

**Assumptions:** the credential key and rotation-log key are separate. Logs use
the shared data/logging key so the CloudWatch Logs service grant does not widen
the Secrets Manager credential key policy.

## Validation

```bash
terraform -chdir=infra/modules/secrets init -backend=false
terraform -chdir=infra/modules/secrets validate
tflint --chdir=infra/modules/secrets --config="$(pwd)/infra/.tflint.hcl"
```

<!-- BEGIN_TF_DOCS -->
### Requirements

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 1.15.0 |
| <a name="requirement_archive"></a> [archive](#requirement\_archive) | ~> 2.7 |
| <a name="requirement_aws"></a> [aws](#requirement\_aws) | ~> 6.56 |
| <a name="requirement_random"></a> [random](#requirement\_random) | ~> 3.9 |

### Providers

| Name | Version |
|------|---------|
| <a name="provider_archive"></a> [archive](#provider\_archive) | 2.8.0 |
| <a name="provider_aws"></a> [aws](#provider\_aws) | 6.57.1 |

### Resources

| Name | Type |
|------|------|
| [aws_cloudwatch_log_group.rotation](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_log_group) | resource |
| [aws_iam_role.rotation](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role) | resource |
| [aws_iam_role_policy.rotation](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_lambda_function.rotation](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_function) | resource |
| [aws_lambda_permission.secrets_manager](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_permission) | resource |
| [aws_secretsmanager_secret.service](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret) | resource |
| [aws_secretsmanager_secret.service_tls_certificate](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret) | resource |
| [aws_secretsmanager_secret.service_tls_private_key](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret) | resource |
| [aws_secretsmanager_secret_rotation.service](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret_rotation) | resource |
| [aws_secretsmanager_secret_version.service](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret_version) | resource |
| [aws_secretsmanager_secret_version.service_tls_certificate](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret_version) | resource |
| [aws_secretsmanager_secret_version.service_tls_private_key](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret_version) | resource |
| [archive_file.rotation](https://registry.terraform.io/providers/hashicorp/archive/latest/docs/data-sources/file) | data source |
| [aws_caller_identity.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_iam_policy_document.rotation](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.rotation_assume_role](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_region.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/region) | data source |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_aurora_cluster_arn"></a> [aurora\_cluster\_arn](#input\_aurora\_cluster\_arn) | ARN of the Aurora cluster whose service-role credentials this module<br/>rotates through RDS Data API. Supplied by aurora-postgresql. | `string` | n/a | yes |
| <a name="input_aurora_database_name"></a> [aurora\_database\_name](#input\_aurora\_database\_name) | Initial Aurora database name used by RDS Data API during credential rotation. | `string` | n/a | yes |
| <a name="input_aurora_host"></a> [aurora\_host](#input\_aurora\_host) | Writer endpoint of the Aurora cluster, embedded in each service credential document and checked by the rotation Lambda. | `string` | n/a | yes |
| <a name="input_aurora_master_secret_arn"></a> [aurora\_master\_secret\_arn](#input\_aurora\_master\_secret\_arn) | ARN of the RDS-managed Aurora master secret used only by the rotation Lambda through RDS Data API. | `string` | n/a | yes |
| <a name="input_environment"></a> [environment](#input\_environment) | Environment segment of every secret name this module composes, which is<br/>what allows a dev and a prod instantiation to coexist in one AWS account<br/>without name collisions. Deliberately has no default: the two environment<br/>roots differ only in values, so a silent default here would let a root that<br/>forgot to set it write secrets under another environment's names. | `string` | n/a | yes |
| <a name="input_kms_key_arn"></a> [kms\_key\_arn](#input\_kms\_key\_arn) | ARN of the customer-managed KMS key that the values of every secret this<br/>module creates are encrypted under. Supplied by the calling environment<br/>root from the `kms` module's Secrets Manager key output. Required: there is<br/>no default, because falling back to the AWS-managed key would silently<br/>abandon customer-managed encryption for these values. | `string` | n/a | yes |
| <a name="input_rotation_log_kms_key_arn"></a> [rotation\_log\_kms\_key\_arn](#input\_rotation\_log\_kms\_key\_arn) | Customer-managed KMS key ARN used only for the rotation Lambda CloudWatch log group. Kept separate from kms\_key\_arn so the Logs service grant does not widen the credential-store key policy. | `string` | n/a | yes |
| <a name="input_rotation_permissions_boundary_arn"></a> [rotation\_permissions\_boundary\_arn](#input\_rotation\_permissions\_boundary\_arn) | Same-account customer-managed IAM policy ARN applied as the rotation Lambda execution role's permissions boundary. | `string` | n/a | yes |
| <a name="input_aurora_port"></a> [aurora\_port](#input\_aurora\_port) | Aurora PostgreSQL listener port embedded in each service credential document. | `number` | `5432` | no |
| <a name="input_initial_secret_version"></a> [initial\_secret\_version](#input\_initial\_secret\_version) | Monotonic version for the write-only initial secret documents. Increment<br/>only when deliberately replacing every initial value before rotation owns<br/>the credentials; ordinary applies keep this stable and store no password. | `number` | `1` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | First segment of every secret name this module composes, so that all of<br/>this stack's secrets sort together and are addressable by one IAM resource<br/>pattern. Also the value sibling modules use as the leading component of<br/>their resource names, which is why the accepted charset is narrower than<br/>Secrets Manager alone would require. | `string` | `"carddemo"` | no |
| <a name="input_password_length"></a> [password\_length](#input\_password\_length) | Number of alphanumeric characters in each ephemeral initial service-role<br/>password. A generation parameter, not a credential. | `number` | `32` | no |
| <a name="input_recovery_window_in_days"></a> [recovery\_window\_in\_days](#input\_recovery\_window\_in\_days) | Days a deleted secret remains recoverable before Secrets Manager removes it<br/>permanently, or 0 to delete immediately with no recovery. Defaults to the<br/>protected value. Set 0 only in an environment that is expected to be<br/>destroyed and recreated under the same secret names, because a non-zero<br/>window keeps those names reserved until it expires and a recreating apply<br/>will fail while it does. | `number` | `30` | no |
| <a name="input_rotation_automatically_after_days"></a> [rotation\_automatically\_after\_days](#input\_rotation\_automatically\_after\_days) | Interval in days between automatic service-role credential rotations.<br/>Rotation is mandatory and the Lambda is created by this module. | `number` | `30` | no |
| <a name="input_rotation_log_retention_in_days"></a> [rotation\_log\_retention\_in\_days](#input\_rotation\_log\_retention\_in\_days) | CloudWatch retention for the rotation Lambda log group. | `number` | `90` | no |
| <a name="input_service_credential_names"></a> [service\_credential\_names](#input\_service\_credential\_names) | Names of the per-service database roles that each need their own generated<br/>credential; one secret is created per element. Fixed at the eight roles<br/>data-migration/sql/V0\_\_schemas\_and\_roles.sql creates: the seven that own one<br/>schema per bounded context (carddemo\_auth, carddemo\_account, carddemo\_card,<br/>carddemo\_ledger, carddemo\_reference, carddemo\_batch and<br/>carddemo\_authorization) plus carddemo\_reporting, the read-only role the<br/>reporting service connects as. Note the carddemo\_ prefix: these are ROLE<br/>names, not the bare schema names, and the two are not interchangeable --<br/>"ledger" is the schema and carddemo\_ledger is the role that owns it.<br/>carddemo\_reporting\_owner is deliberately NOT accepted: it owns the reporting<br/>schema, is created NOLOGIN, and so has no credential to generate. Defaulted<br/>and validated rather than left to the caller, because a service whose secret<br/>was never created starts and then fails to resolve it. | `set(string)` | <pre>[<br/>  "carddemo_auth",<br/>  "carddemo_account",<br/>  "carddemo_card",<br/>  "carddemo_ledger",<br/>  "carddemo_reference",<br/>  "carddemo_batch",<br/>  "carddemo_authorization",<br/>  "carddemo_reporting"<br/>]</pre> | no |
| <a name="input_service_tls_certificate"></a> [service\_tls\_certificate](#input\_service\_tls\_certificate) | PEM certificate or certificate chain presented by the CardDemo services'<br/>internal HTTPS listeners. Imported material: supply it through an operator<br/>secret channel, never a committed tfvars file. main.tf stores the complete<br/>scalar value under the Secrets Manager CMK so ECS can inject the base secret<br/>ARN directly into SERVER\_SSL\_CERTIFICATE. Leave null -- together with<br/>service\_tls\_private\_key -- when the calling root issues and owns the pair<br/>itself, in which case this module creates no TLS entry at all. Both<br/>environment roots in this package do exactly that, so null is their effective<br/>value and the two inputs exist for a root that imports material instead. | `string` | `null` | no |
| <a name="input_service_tls_private_key"></a> [service\_tls\_private\_key](#input\_service\_tls\_private\_key) | PEM private key paired with service\_tls\_certificate. Imported material:<br/>supply it through an operator secret channel, never a committed tfvars file.<br/>main.tf stores the scalar value under the Secrets Manager CMK so ECS can<br/>inject its base ARN directly into SERVER\_SSL\_CERTIFICATE\_PRIVATE\_KEY. Leave<br/>null -- together with service\_tls\_certificate -- when the calling root issues<br/>and owns the pair itself. | `string` | `null` | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Tags applied to every secret this module creates, merged by the caller into<br/>whatever this stack's common tag set contains. Applied per resource in<br/>main.tf because a module cannot configure a provider and therefore cannot<br/>use default\_tags. Defaults to empty so the module imposes no tag of its own. | `map(string)` | `{}` | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_rotation_lambda_arn"></a> [rotation\_lambda\_arn](#output\_rotation\_lambda\_arn) | ARN of the module-owned Lambda that applies and rotates every service-role credential through RDS Data API. |
| <a name="output_rotation_lambda_name"></a> [rotation\_lambda\_name](#output\_rotation\_lambda\_name) | Name of the module-owned service-role credential rotation Lambda, for alarms and operator inspection. |
| <a name="output_rotation_log_group_arn"></a> [rotation\_log\_group\_arn](#output\_rotation\_log\_group\_arn) | ARN of the rotation Lambda CloudWatch log group, consumed by the exact encryption-context KMS policy assembled in the environment root. |
| <a name="output_rotation_log_group_name"></a> [rotation\_log\_group\_name](#output\_rotation\_log\_group\_name) | Name of the rotation Lambda CloudWatch log group, consumed by operator diagnostics and root-level observability wiring. |
| <a name="output_rotation_role_arn"></a> [rotation\_role\_arn](#output\_rotation\_role\_arn) | ARN of the module-owned rotation Lambda role, consumed by the Secrets Manager KMS key policy so rotation can decrypt and replace the exact managed credential documents. |
| <a name="output_service_credential_secrets"></a> [service\_credential\_secrets](#output\_service\_credential\_secrets) | The Secrets Manager entry created for each per-service database role, as a<br/>map keyed by role name -- `carddemo_auth`, `carddemo_account` and the rest<br/>of the roles named in `service_credential_names` -- whose value carries<br/>that entry's base `arn` for IAM, its created `name` for by-name reads, and<br/>distinct `username_reference` / `password_reference` values in ECS's<br/>`<base-arn>:<json-key>::` syntax. One entry exists per element of that input,<br/>so the map is empty only if the input is. The calling root projects the<br/>`arn` fields into infra/modules/ecs-service's `secret_sources` input, keyed<br/>by the container environment-variable name each service expects, and scopes<br/>one `secretsmanager:GetSecretValue` statement per task role to the single<br/>ARN that role is entitled to read. The `name` fields are what a by-name<br/>reader passes as `SecretId`, matching the role name character for<br/>character.<br/><br/>These entries are not inert. Each value is APPLIED to the matching<br/>PostgreSQL role by the schema-bootstrap step, which reads the entry, passes<br/>the credential as a bound parameter on a session setting, and runs<br/>data-migration/sql/V0\_\_schemas\_and\_roles.sql -- which issues the ALTER ROLE<br/>inside the same transaction that creates the roles and refuses to commit<br/>while any role still lacks a credential. A caller therefore has two<br/>obligations, not one: grant the bootstrap identity read access to every<br/>entry in this map, and grant each task role read access to its own entry<br/>alone. |
| <a name="output_service_tls_secrets"></a> [service\_tls\_secrets](#output\_service\_tls\_secrets) | Handles for the scalar certificate and private-key secrets used by the<br/>services' internal HTTPS listeners. Each object publishes the base `arn`,<br/>the created `name`, and a `value_reference`. Because each secret stores one<br/>scalar PEM value, `value_reference` intentionally equals `arn`: the same<br/>base ARN is valid for ECS injection and for the task execution role's IAM<br/>Resource. Empty when the caller left service\_tls\_certificate and<br/>service\_tls\_private\_key null, which is how a root that issues and owns its own<br/>pair says it does not want this module's entries -- a consumer therefore reads<br/>this map through `lookup` or `try` rather than indexing it unconditionally. |
<!-- END_TF_DOCS -->
