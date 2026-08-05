# Secrets Manager credentials and rotation module

> **Purpose.** This called Terraform module creates one Secrets Manager
> credential for each CardDemo service database role, applies and rotates those
> credentials through Aurora RDS Data API, and can store a caller-supplied
> service TLS certificate and private key.
>
> **Source of truth.** The authoritative implementation is
> [`versions.tf`](versions.tf), [`variables.tf`](variables.tf),
> [`main.tf`](main.tf), [`outputs.tf`](outputs.tf), and
> [`rotation_lambda.py`](rotation_lambda.py). The database-role inventory comes
> from
> [`data-migration/sql/V0__schemas_and_roles.sql`](../../../data-migration/sql/V0__schemas_and_roles.sql).
> The untouched baseline context is
> [`app/cpy/CSUSR01Y.cpy`](../../../app/cpy/CSUSR01Y.cpy); it is cited as
> REFERENCE material and is never modified.
>
> **Status.** This README is in scope because Rule 1 requires a prose
> explanation for each Terraform module, not because a migration requirement
> independently requires this file. Terraform has no docstring construct, so
> the HCL headers and descriptions provide the in-code half while this document
> provides the prose half. The
> [documentation standard](../../../docs/CODE_DOCUMENTATION_STANDARD.md)
> defines that obligation.

Rule 1 uses the same four rationale categories as the pre-existing
`tests/README.md` explainability convention. This module therefore extends an
established house convention rather than introducing a competing one.
A document missing either its purpose-and-source header or specific reasoning
for a non-obvious choice fails review; the terraform-docs drift check below is
the mechanical half of that validation gate.


## What the module provisions

For each member of `service_credential_names`, the module creates a
customer-managed-key-encrypted secret, generates an initial alphanumeric
password through the `random` provider's ephemeral resource, and sends the
initial JSON document to Secrets Manager through the AWS provider's write-only
argument. It then attaches a module-owned rotation Lambda that applies the
credential to Aurora and performs scheduled alternating-user rotation.

The service-role inventory is closed and defaulted so an omitted role fails
configuration validation instead of surfacing later as a service that cannot
resolve a credential:

| Bounded context | Login role stored and rotated by this module |
|---|---|
| Auth | `carddemo_auth` |
| Account | `carddemo_account` |
| Card | `carddemo_card` |
| Ledger | `carddemo_ledger` |
| Reference | `carddemo_reference` |
| Batch | `carddemo_batch` |
| Authorization | `carddemo_authorization` |
| Reporting | `carddemo_reporting` |

`carddemo_reporting_owner` is deliberately absent. The bootstrap SQL creates it
as a `NOLOGIN` owner for the reporting schema, so generating a connection
credential for it would contradict its database contract.

The module also packages `rotation_lambda.py` with the `archive` provider and
creates the rotation log group, permissions-bounded execution role, inline
policy, Lambda function, and one Secrets Manager invocation permission per
service secret. The function implements the standard `createSecret`,
`setSecret`, `testSecret`, and `finishSecret` steps and alternates each base role
with a bounded `_clone` login.

When both sensitive TLS inputs are supplied, two additional scalar secrets hold
the service certificate and private key. Supplying only one half is rejected;
supplying neither creates no TLS entries and makes `service_tls_secrets` empty.

Assumptions: the caller supplies two different KMS key ARNs. `kms_key_arn`
comes from the Secrets Manager key owned by [`../kms/`](../kms/) and encrypts
database credentials and optional TLS material. `rotation_log_kms_key_arn`
encrypts the CloudWatch log group under the shared data/logging key, so the Logs
service grant does not widen the credential-store key policy.

Aurora owns its RDS-managed master credential. This module consumes that
credential's ARN only so the rotation Lambda can authenticate to RDS Data API;
it neither creates nor publishes the master credential.

The version contract is Terraform `>= 1.15.0` with `hashicorp/aws ~> 6.56`,
`hashicorp/random ~> 3.9`, and `hashicorp/archive ~> 2.7`. The generated
reference below remains authoritative for those constraints and for the locked
provider releases that terraform-docs renders.


## The structural database-credential boundary

No database password is accepted as a variable, committed in a variable file,
or written into module source. The initial value exists only as an ephemeral
`random_password` result while the provider sends `secret_string_wo` to
Secrets Manager. Terraform records the non-secret
`secret_string_wo_version`, not the generated password. Scheduled rotation
then generates pending values inside Secrets Manager and the Lambda runtime,
outside Terraform.

Alternatives Considered: accepting a `password`, `master_password`, or
role-to-password map would force a caller to provide the credential through a
variable file, environment variable, or automation input. Each alternative
moves the literal rather than eliminating its input surface and makes
repository safety depend on every author and reviewer noticing it. A database
password that the module cannot accept cannot be committed through its
contract.

The optional TLS inputs are a deliberate, narrower exception to the statement
above: they import certificate material rather than database credentials.
They are marked sensitive, must be supplied through an operator-controlled
secret channel, and must never be placed in a committed variable file. See
[Trade-offs and operational boundaries](#trade-offs-and-operational-boundaries)
for the resulting state-handling obligation.


## Measured baseline divergence

The baseline user-security record starts at
`app/cpy/CSUSR01Y.cpy:L17` with `01 SEC-USER-DATA.`. Its fields at L18-L23
total 80 bytes, including the clear-text eight-character
`05 SEC-USR-PWD PIC X(08).` field at L21. The
`READ-USER-SEC-FILE` paragraph begins at
`app/cbl/COSGN00C.cbl:L209` and compares that field directly at L223 with
`IF SEC-USR-PWD = WS-USER-PWD`.

The containing CICS resource is `DEFINE FILE(USRSEC)` at
`app/csd/CARDDEMO.CSD:L88`; its definition specifies `JOURNAL(NO)` at L94 and
`RECOVERY(NONE) FWDRECOVLOG(NO)` at L96. Those settings describe the baseline
as it exists; this migration does not edit them.

Refactoring Rationale: the target architecture deliberately does not carry
the password field forward. Cognito owns interactive identity, while generated
service-role credentials live behind Secrets Manager handles and customer-
managed encryption. This is a documented behavioural divergence, recorded in
the
[COBOL-to-service traceability register](../../../docs/architecture/cobol-to-service-traceability.md),
not a claim that the mainframe path was changed or removed. The migration adds
a second path and leaves the REFERENCE implementation intact.


## Ownership boundaries

This module owns the eight service database credentials, their rotation
function and optional storage of the service TLS pair. Three neighbouring
owners remain separate:

* [`../cognito/`](../cognito/) owns the Cognito app-client and seed-user
  secrets, together with the `carddemo-admin` and `carddemo-user` groups.
* [`../aurora-postgresql/`](../aurora-postgresql/) owns the cluster and its
  RDS-managed master secret.
* [`../kms/`](../kms/) owns the customer-managed keys; this module receives
  their ARNs and cannot change their policies.

Assumptions: each resource has one Terraform owner. If this module and the
Cognito module declared the same secret, each state would claim authority over
one remote object and successive applies could replace metadata, versions, or
recovery settings according to whichever module ran last. Keeping the
ownership boundary explicit prevents that state collision from being mistaken
for consolidation.


## Module usage

This directory is a reusable module, not a Terraform root. The `dev` and `prod`
environment roots call it with sibling-module outputs and caller-owned
variables:

    module "secrets" {
      source = "../../modules/secrets"

      name_prefix                       = var.name_prefix
      environment                       = var.environment
      kms_key_arn                       = module.kms.secrets_key_arn
      rotation_log_kms_key_arn          = module.kms.s3_key_arn
      aurora_cluster_arn                = module.aurora.cluster_arn
      aurora_master_secret_arn          = module.aurora.master_user_secret_arn
      aurora_host                       = module.aurora.writer_endpoint
      aurora_port                       = module.aurora.port
      aurora_database_name              = module.aurora.database_name
      rotation_permissions_boundary_arn = var.rotation_permissions_boundary_arn
    }

The references above are placeholders resolved by the calling root; the
example contains no deployment identifier or credential. Optional inputs,
including the TLS pair and environment-specific recovery and rotation values,
remain caller decisions and are listed in the generated contract.

The module is never applied directly. It has no backend block, provider
configuration body, or nested module call. CI initializes it without a backend
for isolated validation, while the complete dependency graph is initialized
and validated through the environment roots.

Deployment and teardown commands are intentionally not duplicated here. The
authoritative procedures are the [infrastructure guide](../../README.md), the
[deployment runbook](../../../docs/runbooks/deploy.md), and the
[teardown runbook](../../../docs/runbooks/teardown.md).


## Generated Terraform contract

The block below is generated by terraform-docs v0.20.0 from this directory's
HCL. Requirements, providers, resources, inputs, and outputs must be changed in
their source files and regenerated locally; hand-written prose belongs outside
the markers.

Trade-offs: CI uses `--output-check` and refuses to rewrite a stale README.
That costs an author a local regeneration step, but keeps a contract change
visible in the same review as the HCL that caused it instead of letting
automation silently repair and hide the drift.

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


## Consumer contract

Output names are a one-way contract with the environment roots. The module
publishes identifiers and runtime selectors only:

| Output | Consumer-facing contract |
|---|---|
| `service_credential_secrets` | Role-keyed secret ARN, name, username selector, and password selector for task-role IAM and ECS injection |
| `service_tls_secrets` | Conditional certificate and private-key ARN, name, and scalar value reference |
| `rotation_lambda_arn` / `rotation_lambda_name` | Function identity for policy wiring, alarms, and operator inspection |
| `rotation_role_arn` | Execution-role identity used by the Secrets Manager KMS policy |
| `rotation_log_group_arn` / `rotation_log_group_name` | Log destination identity for key-policy and observability wiring |

The dependency direction for the master credential is the reverse: the
Aurora module supplies `aurora_master_secret_arn` to this module, and this
module publishes no master-secret output.

No output contains a generated password, rotated value, TLS scalar, SecretString,
or secret-version identifier. ECS tasks resolve the database username and
password from Secrets Manager at runtime under a role-scoped
`GetSecretValue` grant; TLS consumers resolve the two scalar secret handles the
same way. Terraform outputs remain the only source of resource identifiers, but
Secrets Manager remains the only source of credential values.

Assumptions: a child-module output can move a value into the calling root's
state even when the child validates in isolation. Publishing a credential would
therefore create another durable copy and make accidental projection into plan
or CI output possible. Identifier-only outputs preserve reviewable IAM wiring
without moving the protected value across the module boundary.

Renaming an output requires changing every environment-root consumer in the
same commit. This module cannot detect a stale caller because it does not import
the roots that consume it.


## Trade-offs and operational boundaries

### Recovery window

Trade-offs: a non-zero `recovery_window_in_days` protects a deleted secret from
immediate permanent removal, but Secrets Manager reserves its name until the
window expires. Recreating the same environment during that interval fails
because the replacement secret cannot claim the scheduled-for-deletion name.
The module therefore accepts either `0` or a service-supported protected
window: the development root selects `0` for repeatable destroy/recreate, while
the production root selects `30` for recovery.

### Rotation

Rotation is implemented and mandatory for every service database credential.
The first attachment uses `rotate_immediately = true` so the Lambda applies a
credential to a role that the bootstrap SQL may have created without a
password. Later runs generate a pending password, apply it to the alternate
base-or-`_clone` login inside one database transaction, verify its bounded
attributes and membership, and move `AWSCURRENT` only after the test succeeds.

Trade-offs: a module-owned Lambda, IAM role, log group, packaging step, and Data
API dependency add resources and failure modes. They remove manual `ALTER ROLE`
handling, support the initial passwordless-role bridge, and keep generated
values out of Terraform and command text. A native PostgreSQL driver was not
packaged because its platform-specific binary dependency would make the Lambda
archive depend on the build host; RDS Data API keeps the package reproducible
and the target cluster IAM-scoped.

### State handling

Assumptions: generated database passwords do not enter Terraform state. The
ephemeral initial value is sent through `secret_string_wo`, and subsequent
values are generated and managed by Secrets Manager plus the rotation Lambda.
Only the non-secret write-only revision remains in state.

The optional TLS pair has a different authority: ordinary sensitive inputs feed
ordinary `secret_string` arguments so a caller-supplied renewal creates a new
version. Those values are represented in the calling root's state. A root that
uses this path must therefore protect state as credential-bearing material;
[`infra/bootstrap`](../../bootstrap/) provides the versioned, encrypted remote
state bucket and locking controls used by the environment roots.

### Tags

Assumptions: a child module cannot configure a provider and therefore cannot
use provider `default_tags`. This module merges `var.tags` into each taggable
secret, log group, role, and Lambda resource. The
[`infra/bootstrap`](../../bootstrap/) root can use `default_tags` because it
owns its provider configuration; the different mechanism follows the
root-versus-module boundary rather than representing inconsistent tagging.

### Static validation boundary

The module can be formatted, initialized without a backend, validated, linted,
documentation-drift checked, and policy scanned without claiming a live
deployment. Applying an environment root to an AWS account, observing runtime
rotation, and accepting the resulting cost remain operator actions outside
this document's evidence.


## Validation gates

The infrastructure workflow applies four direct Terraform/documentation gates
to this module:

1. `terraform fmt -check -recursive infra/` rejects formatting drift without
   rewriting the checkout.
2. Backend-free initialization and `terraform validate` of both environment
   roots exercise this module through its real callers.
3. Recursive TFLint uses
   [`infra/.tflint.hcl`](../../.tflint.hcl) to enforce documented and typed
   variables, documented outputs, provider constraints, comment syntax, and
   module structure.
4. terraform-docs uses
   [`infra/.terraform-docs.yml`](../../.terraform-docs.yml) in check-only mode
   to reject any generated region that differs from the HCL beside it.

The same workflow also scans migration-owned tracked files for committed
secrets and runs its explicit material-security Checkov set. The secrets module
is expected to use the supplied customer-managed key for every secret, attach
rotation to every service database credential, and keep any scanner exception
bounded and reviewable. See
[`infra-ci.yml`](../../../.github/workflows/infra-ci.yml) for the executable
contract.

Run the module-local checks from the repository root:

```bash
# WHAT: verify this module's formatting, provider schema, HCL contract, lint
#       rules, and generated documentation without changing AWS resources.
# WHY : Assumptions: backend-free initialization exercises provider and module
#       schemas without credentials, while check-only tools preserve the
#       reviewed working tree instead of silently repairing a defect.
terraform fmt -check -recursive infra/modules/secrets
terraform -chdir=infra/modules/secrets init \
  -backend=false -lockfile=readonly -input=false
terraform -chdir=infra/modules/secrets validate
tflint --chdir=infra/modules/secrets \
  --config="$(pwd)/infra/.tflint.hcl"
terraform-docs --config infra/.terraform-docs.yml \
  --output-check infra/modules/secrets
```

The terraform-docs command is gating. After an intentional HCL contract change,
run the same command without `--output-check`, inspect the generated diff, and
commit the README together with the source change.


## Troubleshooting

### A secret name is already scheduled for deletion

A protected recovery window keeps the name reserved after destroy, so a later
create with the same name fails. For a retained environment, restore the
scheduled secret and reconcile it with Terraform state, or wait until the
deletion window completes before recreating it. For a disposable development
environment, set the root-owned recovery value to `0` before destroy so the
name is released immediately. Do not weaken the production recovery window to
solve a one-off development collision.

### Rotation reports a password-grammar error

Module-owned generation excludes punctuation and requires 32 to 128
alphanumeric characters for both initial and pending passwords. A disallowed
special character therefore indicates that a secret version was written
outside the module's closed generation path or that the configured document
does not match the rotation contract. Inspect version stages and metadata
without printing the secret value, remove the external writer, and create a new
pending version through Secrets Manager rotation.

### terraform-docs reports that the README is out of date

Do not edit tables between the markers. Regenerate with terraform-docs v0.20.0,
review the resulting Requirements, Providers, Resources, Inputs, and Outputs
diff, and commit it with the HCL change. A newer terraform-docs binary is
rejected because renderer changes would make unchanged contracts produce
different bytes.

### Apply shows no database-password diff after rotation

This is expected. Terraform stores the stable `initial_secret_version`, not the
write-only initial value or the Lambda-managed current value. There is no
`ignore_changes` rule masking the credential. Increment
`initial_secret_version` only for a deliberate initial-document replacement;
ordinary applies must not take authority back from rotation.

### Rotation cannot reach Aurora or write a new version

Check the Lambda error log and the exact IAM/KMS boundaries rather than
widening them. The execution-role permissions boundary must belong to the
deployment account, the role must read the RDS-managed master secret, the
credential key must allow use through Secrets Manager, and the cluster must
accept RDS Data API calls. A failure in any one leaves the previous
`AWSCURRENT` version in place.

### Only one TLS input is configured

Certificate and private key are a pair. Supply both through the caller's secret
channel or leave both null. Creating one scalar secret without the other would
publish an unusable listener configuration, so variable validation rejects the
partial case before planning resources.


## Related documents

* [Infrastructure package guide](../../README.md) — module catalogue,
  validation ownership, and the authoritative deploy/teardown cross-links.
* [Security and identity ADR](../../../docs/adr/ADR-008-security-and-identity.md)
  — identity, credential, IAM, and encryption decisions.
* [Deployment runbook](../../../docs/runbooks/deploy.md) — environment-root
  deployment procedure.
* [Teardown runbook](../../../docs/runbooks/teardown.md) — ordered environment
  and bootstrap removal procedure.
* [Code documentation standard](../../../docs/CODE_DOCUMENTATION_STANDARD.md)
  — the Rule 1 Markdown and HCL obligations this README follows.
* [COBOL-to-service traceability](../../../docs/architecture/cobol-to-service-traceability.md)
  — the register of preserved behaviour and documented divergences.
* [KMS module](../kms/) — ownership of the Secrets Manager and logging keys.
* [Cognito module](../cognito/) — ownership of interactive identity and
  seed-user secrets.
* [Bootstrap root](../../bootstrap/) — remote-state protection and locking.
* [Schema and role bootstrap](../../../data-migration/sql/V0__schemas_and_roles.sql)
  — authoritative service-role and schema inventory.
