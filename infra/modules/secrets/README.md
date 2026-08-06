# Secrets Manager database-credential module

> **Purpose.** This called Terraform module creates one Secrets Manager
> credential for each CardDemo service database role, generates each initial
> value at apply time so that no credential is expressible in source, and
> attaches a rotation schedule when — and only when — the calling root supplies a
> rotation function of its own.
>
> **Source of truth.** The authoritative implementation is
> [`versions.tf`](versions.tf), [`variables.tf`](variables.tf),
> [`main.tf`](main.tf) and [`outputs.tf`](outputs.tf). The database-role
> inventory comes from
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
argument. Each document carries the role's own login name, the generated
password, and the master role's login NAME as the escalation identity — never the
master password, which RDS generates and owns.

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

### What the module deliberately does NOT provision

**No rotation function.** Refactoring Rationale: this module previously packaged
a Python rotation Lambda from a committed source file, together with an execution
role, an inline policy, a log group and one invoke permission per secret. All of
it was removed. Rotation of a secret VALUE is not this module's remit — the only
rotation this infrastructure package owns is KMS *key* rotation, in
[`../kms/`](../kms/) — and owning a function here pulled eight cross-module
coordinates into the input contract (the cluster ARN, the RDS-managed master
secret ARN, the writer endpoint, the port, the database name, a log-group key, a
log retention value and an IAM permissions boundary) plus a third Terraform
provider to build the deployment package. What remains is a pass-through hook: a
root that has a rotation function passes `rotation_lambda_arn` together with
`rotation_automatically_after_days`, and the module attaches one
`aws_secretsmanager_secret_rotation` per secret. A root that has neither — which
is the state of both environment roots in this package — gets no rotation
resource at all. Trade-offs: the accepted cost is that credentials are not
re-issued on a schedule until an operator supplies a function. Alternatives
Considered: recreating the same function at the environment root instead, which
was rejected because it relocates out-of-scope work rather than removing it.

**No TLS certificate or private-key entries.** Refactoring Rationale: two
`sensitive` PEM inputs and the two scalar secrets they fed were removed as well.
A reusable module is the wrong custodian for private-key material, and — the
sharper point — an *input* is the wrong channel for it: a Terraform variable can
only be given a value from a committed tfvars file, a committed default, or a
CI-carried environment variable, so `sensitive = true` changed how a plan
*rendered* the value and nothing about whether a tfvars or state file could hold
it. Both environment roots now generate the pair with the `tls` provider and
write it into two root-owned Secrets Manager entries and into
`aws_acm_certificate`, so the material has no input to arrive through.
Alternatives Considered: keeping the inputs nullable and relying on both roots
passing null — rejected as a convention rather than a control, and in fact both
roots were passing them.

**No connection coordinates.** Assumptions: the writer endpoint, the listener
port and the database name are non-secret, and
[`../aurora-postgresql/`](../aurora-postgresql/) already publishes them to
Parameter Store under the same `<prefix>/<environment>/aurora` path this module
composes its secret names from. A consumer therefore reads the coordinates from
Parameter Store and only the credential from Secrets Manager. Accepting them here
as well would make this module a second, silently divergent copy of values
another module owns.

**No master credential.** Aurora owns its RDS-managed master credential; this
module neither creates, stores nor publishes it. It records only the master
role's login NAME, through `database_master_username`, whose default is the same
value `aurora-postgresql`'s own `master_username` input defaults to.

Assumptions: exactly one KMS key ARN is required. `kms_key_arn` comes from the
Secrets Manager key owned by [`../kms/`](../kms/) and encrypts every value this
module writes. There is no second, log-group key, because this module now creates
no log group.

The version contract is Terraform `>= 1.15.0` with `hashicorp/aws ~> 6.56` and
`hashicorp/random ~> 3.9` — the two providers the package admits, and no third.
The generated reference below remains authoritative for those constraints and for
the locked provider releases that terraform-docs renders.


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

This module owns the eight service database credentials and nothing else.
Four neighbouring owners remain separate:

* [`../cognito/`](../cognito/) owns the Cognito app-client and seed-user
  secrets, together with the `carddemo-admin` and `carddemo-user` groups.
* [`../aurora-postgresql/`](../aurora-postgresql/) owns the cluster and its
  RDS-managed master secret.
* [`../kms/`](../kms/) owns the customer-managed keys; this module receives
  their ARNs and cannot change their policies.
* Each environment root owns the service TLS certificate and private key. It
  generates the pair with the `tls` provider, stores it in two root-owned Secrets
  Manager entries and imports it into `aws_acm_certificate`. This module accepts
  no PEM material at all, so there is no channel through which key material
  could reach a tfvars file.

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

      name_prefix             = var.name_prefix
      environment             = var.environment
      kms_key_arn             = module.kms.secrets_key_arn
      recovery_window_in_days = var.secret_recovery_window_in_days
    }

That is the whole call in both environment roots. The references above are
placeholders resolved by the calling root; the example contains no deployment
identifier and no credential. Every other input has a default:
`database_master_username` defaults to the same value
[`../aurora-postgresql/`](../aurora-postgresql/) defaults its own
`master_username` to, so a root that overrides neither is consistent by
construction; `service_credential_names` defaults to the eight roles the
bootstrap SQL creates; and both rotation inputs default to null, which is why
neither root passes them and no rotation schedule is created.

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
| <a name="requirement_aws"></a> [aws](#requirement\_aws) | ~> 6.56 |
| <a name="requirement_random"></a> [random](#requirement\_random) | ~> 3.9 |

### Providers

| Name | Version |
|------|---------|
| <a name="provider_aws"></a> [aws](#provider\_aws) | 6.57.1 |

### Resources

| Name | Type |
|------|------|
| [aws_secretsmanager_secret.service](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret) | resource |
| [aws_secretsmanager_secret_rotation.service](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret_rotation) | resource |
| [aws_secretsmanager_secret_version.service](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret_version) | resource |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_environment"></a> [environment](#input\_environment) | Environment segment of every secret name this module composes, which is<br/>what allows a dev and a prod instantiation to coexist in one AWS account<br/>without name collisions. Deliberately has no default: the two environment<br/>roots differ only in values, so a silent default here would let a root that<br/>forgot to set it write secrets under another environment's names. | `string` | n/a | yes |
| <a name="input_kms_key_arn"></a> [kms\_key\_arn](#input\_kms\_key\_arn) | ARN of the customer-managed KMS key that the values of every secret this<br/>module creates are encrypted under. Supplied by the calling environment<br/>root from the `kms` module's Secrets Manager key output. Required: there is<br/>no default, because falling back to the AWS-managed key would silently<br/>abandon customer-managed encryption for these values. | `string` | n/a | yes |
| <a name="input_database_master_username"></a> [database\_master\_username](#input\_database\_master\_username) | Login NAME of the Aurora cluster's master role -- an identifier, never a<br/>credential -- recorded in each service credential document so that an<br/>operator-supplied rotation function (see rotation\_lambda\_arn) knows which<br/>role to escalate through. The matching PASSWORD is not an input to this<br/>module and is not stored by it: RDS generates and owns the master password,<br/>and infra/modules/aurora-postgresql publishes only that secret's ARN.<br/>Defaults to the same value as that module's own master\_username input. | `string` | `"carddemo_admin"` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | First segment of every secret name this module composes, so that all of<br/>this stack's secrets sort together and are addressable by one IAM resource<br/>pattern. Also the value sibling modules use as the leading component of<br/>their resource names, which is why the accepted charset is narrower than<br/>Secrets Manager alone would require. | `string` | `"carddemo"` | no |
| <a name="input_password_length"></a> [password\_length](#input\_password\_length) | Number of alphanumeric characters in each ephemeral initial service-role<br/>password. A generation parameter, not a credential. | `number` | `32` | no |
| <a name="input_recovery_window_in_days"></a> [recovery\_window\_in\_days](#input\_recovery\_window\_in\_days) | Days a deleted secret remains recoverable before Secrets Manager removes it<br/>permanently, or 0 to delete immediately with no recovery. Defaults to the<br/>protected value. Set 0 only in an environment that is expected to be<br/>destroyed and recreated under the same secret names, because a non-zero<br/>window keeps those names reserved until it expires and a recreating apply<br/>will fail while it does. | `number` | `30` | no |
| <a name="input_rotation_automatically_after_days"></a> [rotation\_automatically\_after\_days](#input\_rotation\_automatically\_after\_days) | Interval in days between automatic service-credential rotations, applied<br/>only when rotation\_lambda\_arn is also supplied. Null -- the default --<br/>configures no rotation schedule at all, which is the state of both<br/>environment roots in this package. | `number` | `null` | no |
| <a name="input_rotation_lambda_arn"></a> [rotation\_lambda\_arn](#input\_rotation\_lambda\_arn) | ARN of an operator-supplied Lambda function that rotates the service<br/>credentials, or null to configure no rotation. This module does not create a<br/>rotation function; it only attaches a rotation schedule when both this input<br/>and rotation\_automatically\_after\_days are supplied. Nullable with a null<br/>default, so a root that has no rotation function still applies cleanly. | `string` | `null` | no |
| <a name="input_service_credential_names"></a> [service\_credential\_names](#input\_service\_credential\_names) | Names of the per-service database roles that each need their own generated<br/>credential; one secret is created per element. Fixed at the eight roles<br/>data-migration/sql/V0\_\_schemas\_and\_roles.sql creates: the seven that own one<br/>schema per bounded context (carddemo\_auth, carddemo\_account, carddemo\_card,<br/>carddemo\_ledger, carddemo\_reference, carddemo\_batch and<br/>carddemo\_authorization) plus carddemo\_reporting, the read-only role the<br/>reporting service connects as. Note the carddemo\_ prefix: these are ROLE<br/>names, not the bare schema names, and the two are not interchangeable --<br/>"ledger" is the schema and carddemo\_ledger is the role that owns it.<br/>carddemo\_reporting\_owner is deliberately NOT accepted: it owns the reporting<br/>schema, is created NOLOGIN, and so has no credential to generate. Defaulted<br/>and validated rather than left to the caller, because a service whose secret<br/>was never created starts and then fails to resolve it. | `set(string)` | <pre>[<br/>  "carddemo_auth",<br/>  "carddemo_account",<br/>  "carddemo_card",<br/>  "carddemo_ledger",<br/>  "carddemo_reference",<br/>  "carddemo_batch",<br/>  "carddemo_authorization",<br/>  "carddemo_reporting"<br/>]</pre> | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Tags applied to every secret this module creates, merged by the caller into<br/>whatever this stack's common tag set contains. Applied per resource in<br/>main.tf because a module cannot configure a provider and therefore cannot<br/>use default\_tags. Defaults to empty so the module imposes no tag of its own. | `map(string)` | `{}` | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_service_credential_secrets"></a> [service\_credential\_secrets](#output\_service\_credential\_secrets) | The Secrets Manager entry created for each per-service database role, as a<br/>map keyed by role name -- `carddemo_auth`, `carddemo_account` and the rest<br/>of the roles named in `service_credential_names` -- whose value carries<br/>that entry's base `arn` for IAM, its created `name` for by-name reads, and<br/>distinct `username_reference` / `password_reference` values in ECS's<br/>`<base-arn>:<json-key>::` syntax. One entry exists per element of that input,<br/>so the map is empty only if the input is. The calling root projects the<br/>`arn` fields into infra/modules/ecs-service's `secret_sources` input, keyed<br/>by the container environment-variable name each service expects, and scopes<br/>one `secretsmanager:GetSecretValue` statement per task role to the single<br/>ARN that role is entitled to read. The `name` fields are what a by-name<br/>reader passes as `SecretId`, matching the role name character for<br/>character.<br/><br/>This module STORES each credential; it does not APPLY it. Binding a stored<br/>value to the matching PostgreSQL role -- the ALTER ROLE that lets the role<br/>authenticate with it -- belongs to whatever runs the schema bootstrap, and so<br/>does any later rotation. A caller therefore has three obligations, not one:<br/>grant each task role read access to its own entry alone, grant the<br/>bootstrapping identity read access to every entry in this map so it can bind<br/>the values it finds, and -- if the deployment wants rotation -- supply a<br/>rotation function through this module's `rotation_lambda_arn` input, because<br/>this module deliberately creates none. |
<!-- END_TF_DOCS -->


## Consumer contract

Output names are a one-way contract with the environment roots. The module
publishes identifiers and runtime selectors only:

| Output | Consumer-facing contract |
|---|---|
| `service_credential_secrets` | Role-keyed secret ARN, name, username selector, and password selector for task-role IAM and ECS injection |

That is the module's entire public surface: **one** output. Refactoring
Rationale: six others were withdrawn — a conditional TLS handle map, two
rotation-function identifiers, a rotation execution-role ARN and two rotation
log-group identifiers — because every resource they named was removed with the
rotation function and the TLS entries. Withdrawing a published name is a breaking
change for both environment roots, so it was done only after confirming that the
roots reference none of them. Trade-offs: a caller that wants a rotation
function's identity now holds it already, because the root that supplies the
function is the root that creates it — an output here would only echo an input
back.

This module holds no reference to the master credential in either direction. The
Aurora module creates and owns the RDS-managed master secret and publishes its
ARN; this module neither consumes that ARN nor publishes one of its own, and
records only the master role's login NAME so a rotation function has an
escalation identity to resolve.

No output contains a generated password, a rotated value, a SecretString, or a
secret-version identifier. ECS tasks resolve the database username and
password from Secrets Manager at runtime under a role-scoped
`GetSecretValue` grant. Terraform outputs remain the only source of resource
identifiers, but Secrets Manager remains the only source of credential values.

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

### Rotation is NOT implemented here

Rotation of a secret value is outside this module's remit, and the statement is
made positively rather than left to be inferred from an absent resource. The only
rotation this infrastructure package owns anywhere is KMS *key* rotation, in
[`../kms/`](../kms/). This module accepts `rotation_lambda_arn` and
`rotation_automatically_after_days` as a pass-through pair and attaches one
`aws_secretsmanager_secret_rotation` per credential when both are supplied;
supplying one without the other is refused at plan time, because an interval with
no function rotates nothing and a function with no interval is not a schedule.
Neither environment root supplies them.

Refactoring Rationale: a module-owned Python rotation Lambda, its execution role,
inline policy, log group, source archive and per-secret invoke permission were
all removed. Trade-offs: what that costs is real and is stated rather than
glossed — a stored initial credential is no longer applied to its PostgreSQL role
by this module, and is not re-issued on a schedule. Binding a stored value to its
role is the schema-bootstrap step's responsibility, and scheduled re-issue waits
on an operator-supplied function. What it buys is a module whose input contract is
ten non-secret values rather than eighteen: owning a function here required the
cluster ARN, the RDS-managed master secret ARN, the writer endpoint, the port, the
database name, a log-group key, a log retention value and an IAM permissions
boundary, none of which a credential store needs in order to store a credential,
plus a third Terraform provider purely to build a deployment package.
Alternatives Considered: recreating the same function at the environment root, so
that the behaviour survived the boundary move. Rejected — it relocates
out-of-scope work rather than removing it, and the root that would own it is the
root that already owns the schema-bootstrap function the credential binding
properly belongs to.

### State handling

Assumptions: generated database passwords do not enter Terraform state. The
ephemeral initial value is sent through `secret_string_wo`, and any subsequent
value is written by whatever rotation function the calling root supplies. Only
the non-secret write-only revision remains in state.

Assumptions: no PEM material reaches this module's state, because there is no
input through which it could arrive. The service certificate and private key are
generated and stored by the environment root, and the root's `tls_private_key`
resource does place key material in the ROOT's state. A root that uses that path
must therefore protect its state as credential-bearing material;
[`infra/bootstrap`](../../bootstrap/) provides the versioned, encrypted remote
state bucket and locking controls the environment roots use for exactly that
reason.

### Tags

Assumptions: a child module cannot configure a provider and therefore cannot
use provider `default_tags`. This module merges `var.tags` into each secret it
creates, which is now the only kind of taggable resource it has. The
[`infra/bootstrap`](../../bootstrap/) root can use `default_tags` because it
owns its provider configuration; the different mechanism follows the
root-versus-module boundary rather than representing inconsistent tagging.

### Static validation boundary

The module can be formatted, initialized without a backend, validated, linted,
documentation-drift checked, and policy scanned without claiming a live
deployment. Applying an environment root to an AWS account and accepting the
resulting cost remain operator actions outside this document's evidence.


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

### Apply shows no database-password diff

This is expected, and it is the write-only argument working rather than a masked
change. Terraform records the stable write-only revision — pinned to `1` in
`main.tf` — and never the value itself, so an apply has nothing to compare and
proposes nothing. There is no `ignore_changes` rule involved. Advancing that
revision is a reviewed edit to `main.tf` rather than a tfvars number, precisely
because an increment re-issues every initial credential and would overwrite
whatever a rotation function had since put in place.

### A service cannot authenticate with the credential in its secret

The credential exists but has not been bound to its PostgreSQL role. This module
stores values; it does not run `ALTER ROLE`. Check that the schema-bootstrap step
ran AFTER these secrets were created and that the bootstrapping identity holds
`GetSecretValue` on every entry in `service_credential_secrets` — it needs to read
each value in order to bind it, which is a wider grant than any single task role
holds and is the one identity for which that is correct.

### Only one rotation input is configured

`rotation_lambda_arn` and `rotation_automatically_after_days` are a pair. Supply
both or leave both null. An interval with no function rotates nothing, and a
function with no interval is not a schedule Secrets Manager will create, so
variable validation rejects the half-supplied case before any resource is
planned.


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
