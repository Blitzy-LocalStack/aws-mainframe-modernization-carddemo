# `infra/modules/aurora-postgresql/` — Aurora PostgreSQL (Serverless v2)

**Purpose.** This module provisions the single Aurora PostgreSQL Serverless v2
cluster that carries every record datastore of the migrated CardDemo
application — one writer instance in the isolated data subnets, encrypted at
rest with a customer-managed key, with automated backups, a DB subnet group, a
cluster parameter group, engine log exports and the non-secret connection
parameters its consumers read at startup. It provisions the cluster and the
database inside it, and nothing that lives *inside* that database.

**Source of truth.** Three sources govern this document, in this order:

1. the four Terraform files beside it — `versions.tf`, `variables.tf`,
   `main.tf` and `outputs.tf` — which are authoritative for every input,
   output, resource and version constraint. Section 7 is generated from them;
2. decision **D3**, recorded in
   [`ADR-003-datastore-targets.md`](../../../docs/adr/ADR-003-datastore-targets.md),
   which chose Aurora PostgreSQL Serverless v2 for all record data;
3. the mainframe baseline under `app/jcl/` and `app/csd/`, which is
   **reference-only** — cited throughout to ground a decision, and never
   modified. The COBOL path still runs and remains the behavioural oracle for
   functional parity; this migration adds a path beside it rather than removing
   one.


---


## 1. Why this README exists

This document is **rule-mandated, not migration-mandated**. A reusable
Terraform module needs no README to plan or apply, so its presence is a
deliberate obligation rather than a courtesy.

Rule 1, *Explainability*, requires that generated code document both what it
does and **why** each non-obvious decision was made. Every other language in
this repository has somewhere to put that: a Javadoc block, a JSDoc comment, a
Python docstring. **HCL has no docstring construct.** The obligation is
therefore split into two halves, and this file is one of them:

| Half | Carrier | What it guarantees |
|---|---|---|
| Mechanical | [`infra/.tflint.hcl`](../../.tflint.hcl) and [`infra/.terraform-docs.yml`](../../.terraform-docs.yml) | `terraform_documented_variables` and `terraform_documented_outputs` fail any variable or output declared without a `description`; the generator then lifts those descriptions into section 7 and the drift check keeps them current |
| Prose | **this README** | The reasoning a table cannot hold: why the capacity rules are coupled, why there is no reader instance, where the schema boundary falls, and what the ten VSAM clusters this replaces actually guaranteed |

Neither half is sufficient alone. A tree of `description` strings with nothing
publishing them is unread; a published table nobody keeps honest is worse than
none, because a reader trusts it.

Assumptions: Rule 1 and existing house style agree completely here, so
nothing had to be reconciled. `tests/README.md` §12 already imposes the
identical obligation on the COBOL test suite — a docstring stating Purpose,
Parameters, Returns and Exceptions, plus inline comments documenting at least
one of Alternatives Considered, Refactoring Rationale, Assumptions or
Trade-offs — and closes it with "This is a hard review gate." This document
extends an established convention to a new tree; it does not import a new one.
The convention itself is written down once, in
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../docs/CODE_DOCUMENTATION_STANDARD.md).


---


## 2. What this module provisions

Six resources, in dependency order. The generated table in section 7 is the
authoritative list; this one adds the reason each exists.

| Resource | Role |
|---|---|
| `random_id.final_snapshot_suffix` | Supplies the unique suffix in the final-snapshot identifier. A snapshot identifier is unique per account and outlives the cluster, so a fixed name would collide with the snapshot left by a previous teardown |
| `aws_db_subnet_group.this` | Pins the cluster into the **isolated** data-tier subnets — those with no route to the internet in either direction, neither to an internet gateway nor through a NAT gateway |
| `aws_rds_cluster_parameter_group.this` | Carries the two transport-security parameters the module refuses to let a caller weaken, plus any reviewed override |
| `aws_rds_cluster.this` | The cluster: Serverless v2 capacity, storage encrypted with a customer-managed key, automated backups, engine log exports to CloudWatch Logs |
| `aws_ssm_parameter.connection` | Publishes the **non-secret** host, port and database values consumers resolve at startup |
| `aws_rds_cluster_instance.this` | Exactly **one** instance, the writer, at `instance_class = "db.serverless"` and `publicly_accessible = false` |

Four properties of the cluster are worth stating explicitly, because each is
either easy to get wrong or easy to mistake for an accident.

Assumptions: Serverless v2 is selected by `engine_mode = "provisioned"`
together with a `serverlessv2_scaling_configuration` block — *not* by
`engine_mode = "serverless"`, which selects Aurora Serverless **v1**, a
different and older product. The argument reads as though it contradicts the
intent, which is exactly why it is called out here rather than left to be
inferred again.

Refactoring Rationale: `storage_encrypted` is fixed `true` against the
customer-managed key supplied in `kms_key_arn`, and `backup_retention_period`
is bounded to 1–35 days with no value that disables it. Neither is offered as a
lowerable knob. Section 8 records what the baseline datasets guaranteed
instead, which is what makes these two settings a correction rather than a
port.

Assumptions: the master credential is **not an input to this module.** RDS
generates and owns it — `manage_master_user_password` is set, and
`master_user_secret_kms_key_id` points at the *secrets* customer-managed key
rather than the cluster's data key, so that permission to read a credential and
permission to read the data it protects are separately grantable. The module
publishes only that secret's ARN, as `master_user_secret_arn`. No credential
appears in any variable, output, plan or state entry here, and consumers resolve
the value from Secrets Manager at runtime.

Trade-offs: the cluster parameter group defaults to exactly two parameters —
`rds.force_ssl` set to `1`, so the engine refuses an unencrypted connection, and
`password_encryption` set to `scram-sha-256`, so no service credential is stored
as a weaker verifier. No performance tuning ships; everything else runs the
engine defaults for the chosen family. An override **replaces** the map rather
than merging into it, so a root that adds a tuning parameter must restate both
security parameters, and a `validation` block rejects a map that omits either.
The cost is that the override is slightly awkward to use; the benefit is that a
security parameter cannot be dropped by forgetting it.


---


## 3. This is a module, not a root

**There is no `terraform init`, `plan` or `apply` for this directory.** It
declares no `provider`, `terraform` or `backend` block — a module cannot carry a
backend at all — and it is never applied on its own. It is called by
`infra/envs/dev` and `infra/envs/prod`, and each of those roots owns provider
configuration, region, default tags and remote state.

The call below is the real one, from `infra/envs/dev/main.tf`.

Assumptions: every external value arrives as an **input variable** wired by the
root. This module never reaches into a sibling module, reads a sibling's
resources by name, or resolves a value by naming convention.

```hcl
module "aurora" {
  source = "../../modules/aurora-postgresql"

  name_prefix                  = var.name_prefix
  environment                  = var.environment
  isolated_subnet_ids          = module.network.isolated_data_subnet_ids
  security_group_ids           = [module.network.data_security_group_id]
  kms_key_arn                  = module.kms.aurora_key_arn
  secrets_kms_key_arn          = module.kms.secrets_key_arn
  engine_version               = var.aurora_engine_version
  parameter_group_family       = var.aurora_parameter_group_family
  port                         = module.network.database_port
  min_capacity                 = var.aurora_min_capacity
  max_capacity                 = var.aurora_max_capacity
  seconds_until_auto_pause     = var.aurora_seconds_until_auto_pause
  backup_retention_period      = var.aurora_backup_retention_period
  preferred_backup_window      = var.aurora_preferred_backup_window
  preferred_maintenance_window = var.aurora_preferred_maintenance_window
  deletion_protection          = var.deletion_protection
  skip_final_snapshot          = var.skip_final_snapshot
  enable_http_endpoint         = true
}
```

Note that `port` is passed from the network module rather than as a second
literal. Assumptions: the same value has to appear on both sides of a
matched rule pair — the cluster's listener and the security-group rule that
admits the application tier. Set on one side only, the cluster still plans,
still applies and still reports healthy to Terraform while refusing every
connection.

Where a real deploy happens, and its exact command sequence, is owned by
[`infra/README.md`](../../README.md) and the
[deployment runbook](../../../docs/runbooks/deploy.md): the state backend is
bootstrapped once per account, then one environment root at a time is
initialised, planned to a saved plan file and applied. Teardown reverses that
order, with the backend removed last, and is owned by the
[teardown runbook](../../../docs/runbooks/teardown.md).

Trade-offs: those sequences are deliberately **not** repeated here. A duplicated
command drifts from the original and then misleads.


### 3.1 The gates this directory does pass

Four whole-tree checks cover this module. All four are read-only: none of them
rewrites a committed file.

```bash
# WHAT: load the pinned toolchain, then check canonical HCL formatting across
#       the whole package without rewriting a single file.
# WHY : Trade-offs: `-check` reports drift and exits non-zero, where a bare
#       `terraform fmt` silently rewrites the tree -- which in CI would let a formatting
#       regression pass as green because the command "fixed" it and then
#       succeeded.
. /etc/profile.d/00-carddemo-toolchain.sh
terraform fmt -check -recursive infra

# WHAT: install providers and validate this module's configuration with no
#       backend and no remote state.
# WHY : Assumptions: `validate` refuses to run in an uninitialised directory,
#       but a plain `init` would want credentials and an already-bootstrapped account.
#       `-backend=false` gives `validate` everything it needs, which is what
#       lets a contributor with no AWS access review this module.
terraform -chdir=infra/modules/aurora-postgresql init -backend=false -input=false
terraform -chdir=infra/modules/aurora-postgresql validate

# WHAT: lint this module against the shared rule set.
# WHY : Assumptions: HCL has no docstring construct, so this is the mechanical
#       half of the documentation gate: `terraform_documented_variables` and
#       `terraform_documented_outputs` fail any variable or output declared
#       without a `description`, and `terraform_unused_declarations` fails one
#       that no longer has a consumer.
tflint --chdir=infra/modules/aurora-postgresql --config="$(pwd)/infra/.tflint.hcl"

# WHAT: verify that the generated region in section 7 still matches the HCL
#       beside it. This is a CHECK and changes nothing on disk.
# WHY : Alternatives Considered: the generator runs in `inject` mode, so
#       `--output-check` compares what WOULD be written against what is committed
#       and fails on a mismatch.
#       Regenerating in CI and committing the result was rejected: it converts
#       a review gate into a silent mutation, repairing the drift so the author
#       never learns the published contract was wrong.
terraform-docs --config infra/.terraform-docs.yml --output-check infra/modules/aurora-postgresql
```

The fourth gate is the policy scan, run over the whole `infra` tree by the
infrastructure CI workflow rather than per module. Its expectations for a data
tier — storage encrypted with a customer-managed key, no public accessibility,
automated backups enabled, engine logs exported, deletion protection where the
scanner expects it — are **satisfied by construction, not by suppression.**
That is a measured claim rather than a slogan: this module's four `.tf` files
carry **zero** scanner-suppression comments. Six sibling modules do carry
bounded, individually-reviewed exceptions; this one needs none, because each
expectation corresponds to an argument that is fixed or validated here.

Trade-offs: all four gates are static. The module is authored and
statically validated only. Applying a root against a live AWS account remains
an operator action outside this scope, and no claim is made here that a cluster
has been created, connected to, benchmarked, load-tested or assessed against a
compliance standard.


---


## 4. The capacity invariant

Serverless v2 capacity has five rules, and they are coupled: two of them fire
only in the presence of a third. Assumptions: every one of them belongs to
the provider and the engine rather than to this module's preferences, and each
is enforced at `terraform validate` — before any plan reaches AWS — so a
misconfiguration is a named error rather than a half-provisioned data tier.

| # | Rule | Enforced at |
|---|---|---|
| 1 | Capacity ranges from **0 to 256** Aurora Capacity Units | `variables.tf` — `min_capacity` and `max_capacity` range validations |
| 2 | Capacity moves in **half-unit** increments | `variables.tf` — a multiple-of-`0.5` validation on each |
| 3 | The maximum must be **at least** the minimum | `variables.tf` — a cross-variable validation hosted on `max_capacity` |
| 4 | When the minimum is **0**, the maximum must be **at least 1** | `variables.tf` — a conditional validation on `max_capacity` |
| 5 | When the minimum is **0**, the auto-pause delay becomes **mandatory**, as a whole number of seconds from **300 to 86,400** | `variables.tf` — a range validation plus a conditional validation on `seconds_until_auto_pause` |

Every `error_message` names the constraint that was violated and the input to
change, which is the HCL form of Rule 1's *Exceptions* element: a reader who
hits one of these should not have to open the file to learn which value is
wrong.

Assumptions: rule 3 is hosted on `max_capacity` rather than on
`min_capacity` because only one of the pair may reference the other without
creating a validation dependency cycle, and keeping `min_capacity` free of
outgoing references is what lets rules 4 and 5 also read it. Rules 4 and 5 are
deliberately **two** validations rather than one combined block: both fire only
when the minimum is zero, and a single message would have to name two different
inputs, leaving the operator to work out which to change.

Assumptions: rule 5 is the one most likely to be missed, because nothing
about a `null` default suggests another input can make it compulsory. A cluster
told to scale to zero with no auto-pause delay set does not fail obviously — it
simply never pauses, silently keeping the capacity it was told to release.
Catching that at `validate` turns a silent misconfiguration into a named one.

Two constraints sit outside the validation blocks because Terraform cannot
check them:

- **The engine minor version must support scaling to zero.** Not every
  PostgreSQL minor release does. `engine_version` is required with no default
  precisely so that the choice is an explicit, reviewed line in one file.
- **The provider must be recent enough.** Provider 5.80.0 introduced the zero
  minimum and 5.81.0 introduced the auto-pause-seconds argument, so 5.81.0 is
  the effective full-feature floor. `versions.tf` constrains the AWS provider
  to `~> 6.56`, which clears it comfortably.

Trade-offs: a cluster that has auto-paused takes on the order of fifteen
seconds to resume, and that cost is paid on the first connection after an idle
period. It is immaterial to the nightly batch chain, which is a scheduled
workload that absorbs a one-off resume before its first step; it is a real
hazard for interactive use, where it surfaces as an apparently hung first query.
That asymmetry is the whole reason the two environments differ: `dev` is
permitted a minimum of zero and accepts the resume, while `prod` holds its
minimum above zero and never pauses. The delay chosen within the 300-to-86,400
window decides how often the resume is paid at all — a short delay pauses
eagerly and saves the most while resuming most often — and the module takes no
position on where in that window the balance sits, because the answer differs
between a batch-only cluster and one somebody is developing against.


---


## 5. No read replica

There is **no reader instance**, no Aurora Global Database, no
`replication_source_identifier` and no cross-region resource in this module.
That is a decision, and it is recorded here so a future reader does not read the
absence as an oversight.

Alternatives Considered: a read replica was evaluated and rejected. The
reporting service reads through read-only cross-schema **views** against the
writer, under a dedicated database role holding `SELECT`-only grants, and it
owns no tables of its own — so a replica would add cost and replica-lag
semantics for no parity benefit. There is no lag behaviour in the baseline to
preserve, and introducing one would create a class of observable staleness that
the golden-master comparison would correctly flag. Multi-region and
disaster-recovery topology are likewise out of scope: the target is
single-region, three-availability-zone only.

**One consequence a consumer must not misread.** Aurora publishes a **reader
endpoint** whether or not a reader instance exists, and this module exports it.
With exactly one instance behind it, that endpoint resolves to the same writer.
It adds no capacity and gives no isolation from writer load, so no consumer may
treat it as a lag-free scale-out read path. It is exported for the reporting
service's read-only role, and for nothing else.


---


## 6. What this module does **not** do

**This module provisions the cluster and the database, and creates no database
objects inside it.** This is the boundary most likely to be misunderstood, so it
is stated flatly: there is no `postgresql` provider here, no `null_resource`, no
provisioner and no SQL string anywhere.

| Not created here | Created by |
|---|---|
| The eight schemas — `auth`, `account`, `card`, `ledger`, `reference`, `batch` and `authorization`, plus the read-only cross-schema views the reporting service reads through | [`data-migration/sql/V0__schemas_and_roles.sql`](../../../data-migration/sql/V0__schemas_and_roles.sql) and the per-service Flyway migrations at `services/*/src/main/resources/db/migration/V1__<schema>.sql` |
| Service roles and their grants | The same two, per the [data-migration runbook](../../../docs/runbooks/data-migration.md) |
| Tables, indexes and constraints | The per-service Flyway migrations |
| The Aurora security group and both halves of its matched rule pair | `infra/modules/network` |
| The customer-managed keys | `infra/modules/kms` |
| The master credential | RDS itself — see section 2 |

A consumer that expects a schema to exist because the cluster does will not find
one.

Alternatives Considered: one deliberate exception to database-per-service
purity is worth knowing about while reading this module, because it explains why
a single cluster is correct rather than a compromise. Transaction posting
commits the transaction, the category balance and the account as one unit of
work, so the batch service runs against this same cluster under a dedicated
database role holding narrowly-scoped cross-schema **write** grants on
`ledger.*` and `account.*` only. A transactional-outbox-plus-compensating-
reversal design was considered and rejected: it would introduce observable
partial-posting states that do not exist in the baseline — a posted transaction
with an unposted balance — which would break golden-master parity outright. The
**grants** that express this are the migration scripts' business, not this
module's; nothing here can widen or narrow them.

Refactoring Rationale: `IDCAMS BLDINDEX` is **retired rather than ported.**
The baseline built each alternate index as a separate, schedulable job step
(`app/jcl/CARDFILE.jcl:110`, `app/jcl/XREFFILE.jcl:100`,
`app/jcl/TRANFILE.jcl:109`, `app/jcl/TRANIDX.jcl:52`). PostgreSQL maintains an
index transactionally and has no separate build step to schedule, so there is
nothing left to orchestrate. The secondary indexes that replace those alternate
indexes are created by the per-service Flyway migrations — not here.


---


## 7. Inputs and outputs

The generated tables below are the contract. This section adds what a table
cannot say: where each input comes from, and who consumes each output.

**The consumed contract.**

Assumptions: ten inputs are required and carry no default, because each is
either an environment decision or a value only the calling root can supply.
`isolated_subnet_ids` and `security_group_ids` come from the
`network` module — the *isolated* data-tier subnets, deliberately not the
private application subnets that carry the ECS tasks, and at least two of them
because a DB subnet group must span two availability zones. `kms_key_arn` and
`secrets_kms_key_arn` both come from the `kms` module and are deliberately
different keys, one for the data and one for the credential. `engine_version`
and `parameter_group_family` are required together, because the family must
match the engine's **major** version and the two are only safe when set and
reviewed in the same change. `min_capacity` and `max_capacity` are required
because capacity is one of the few axes on which the environments differ.
`name_prefix` and `environment` compose every resource name.

**The defined contract.**

Assumptions: twelve outputs are published. Their consumption stories differ in
ways that matter:

- `writer_endpoint` is the single address every read and every write resolves
  to. The calling root publishes it into Parameter Store and each service reads
  it there at startup through its Spring profile; batch tasks receive it the
  same way, through Step Functions container overrides. Assumptions: no
  service, container image or `.tfvars` file may hard-code it, because it is
  unknown until apply and changes if the cluster is ever replaced.
- `reader_endpoint` carries the caveat in section 5.
- `cluster_resource_id`, **not** `cluster_arn`, is the value an IAM
  database-authentication policy needs inside its resource ARN, and the value by
  which Performance Insights metrics are addressed. It is also stable across a
  rename of the cluster identifier, which the ARN is not.
- `cluster_arn` and `cluster_identifier` confer nothing on their own. The ARN is
  what an `rds:Describe*`, snapshot or tagging policy names; the identifier is
  what appears in the console, on an invoice line and in a CloudTrail event, and
  is what the runbooks and the batch-window SSM steps use. Nothing is reachable
  at either value.
- `master_user_secret_arn` is a **reference to where the credential lives,
  never the credential.** A root uses it to grant one task role
  `secretsmanager:GetSecretValue` on that one secret, together with `kms:Decrypt`
  on the secrets key. Both grants are required, and the second is deliberately
  the secrets key rather than the cluster's data key.
- `connection_parameter_names` and `connection_parameter_arns` name the
  non-secret host, port and database parameters, for the ETL configuration
  resolver and for the IAM policy that lets a task read them.
- `security_group_ids` and `db_subnet_group_name` are echoed back so a consumer
  composing a matching rule has the effective value without re-deriving it.
  Exporting them grants nothing: `infra/modules/network` owns those groups.

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
| <a name="provider_terraform"></a> [terraform](#provider\_terraform) | n/a |

### Resources

| Name | Type |
|------|------|
| [aws_db_subnet_group.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/db_subnet_group) | resource |
| [aws_rds_cluster.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/rds_cluster) | resource |
| [aws_rds_cluster_instance.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/rds_cluster_instance) | resource |
| [aws_rds_cluster_parameter_group.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/rds_cluster_parameter_group) | resource |
| [aws_ssm_parameter.connection](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ssm_parameter) | resource |
| [terraform_data.final_snapshot_suffix](https://registry.terraform.io/providers/hashicorp/terraform/latest/docs/resources/data) | resource |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_engine_version"></a> [engine\_version](#input\_engine\_version) | Aurora PostgreSQL engine version for the cluster, for example a "16.x" or<br/>"17.x" release. Supplied by the environment root rather than defaulted, so<br/>that an engine upgrade is an explicit, reviewed change to one file. If the<br/>root sets min\_capacity to 0, the version chosen must be one whose minor<br/>release supports scaling to zero capacity -- not every minor release does. | `string` | n/a | yes |
| <a name="input_environment"></a> [environment](#input\_environment) | Deployment environment this cluster belongs to, used in resource tagging<br/>and in composed names. Restricted to "dev" or "prod" because those are<br/>the only two environment roots the infrastructure defines. The two differ<br/>only in sizing and retention -- capacity, backup retention, and the<br/>deletion-protection and final-snapshot flags -- and never in topology. | `string` | n/a | yes |
| <a name="input_isolated_subnet_ids"></a> [isolated\_subnet\_ids](#input\_isolated\_subnet\_ids) | Identifiers of the isolated data-tier subnets the DB subnet group is built<br/>from, supplied by the network module. These must be the ISOLATED subnets --<br/>the ones with no route to the internet in either direction -- and not the<br/>private application subnets that carry the ECS tasks. At least two are<br/>required because a DB subnet group must span two availability zones; the<br/>environment roots supply three, one per zone of the three-zone VPC. | `list(string)` | n/a | yes |
| <a name="input_kms_key_arn"></a> [kms\_key\_arn](#input\_kms\_key\_arn) | ARN of the customer-managed KMS key that encrypts the cluster's storage,<br/>its automated backups and its snapshots, supplied by the kms module.<br/>Required with no default, so that encryption is always performed with a key<br/>this project owns and can rotate, audit and revoke. | `string` | n/a | yes |
| <a name="input_max_capacity"></a> [max\_capacity](#input\_max\_capacity) | Maximum Aurora Capacity Units the cluster scales up to under load, from 0<br/>to 256 in half-unit steps, and never below min\_capacity. When min\_capacity<br/>is 0 this must be at least 1. Required with no default, for the same reason<br/>as min\_capacity: it is part of the sizing each environment states for<br/>itself. | `number` | n/a | yes |
| <a name="input_min_capacity"></a> [min\_capacity](#input\_min\_capacity) | Minimum Aurora Capacity Units the cluster scales down to while idle, from 0<br/>to 256 in half-unit steps. A value of 0 lets the cluster pause completely,<br/>which is intended for dev; prod holds this above 0. Setting it to 0 makes<br/>seconds\_until\_auto\_pause mandatory and requires max\_capacity to be at least<br/>1, and requires an engine minor version that supports scaling to zero.<br/>Required with no default, because capacity is one of the few axes on which<br/>the dev and prod environments deliberately differ. | `number` | n/a | yes |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Common prefix from which every resource name in this module is composed --<br/>the cluster identifier, the DB subnet group, the cluster parameter group<br/>and the final snapshot identifier. Must satisfy the RDS cluster identifier<br/>grammar: 1 to 40 characters of lower-case letters, digits and hyphens,<br/>beginning with a letter, with no consecutive and no trailing hyphen.<br/>Typically "<project>-<environment>", for example "carddemo-dev". | `string` | n/a | yes |
| <a name="input_parameter_group_family"></a> [parameter\_group\_family](#input\_parameter\_group\_family) | Cluster parameter group family, which must match the MAJOR version of<br/>engine\_version -- "aurora-postgresql16" for a 16.x engine,<br/>"aurora-postgresql17" for a 17.x engine. Supplied by the environment root<br/>alongside engine\_version so that the two are set, and reviewed, together. | `string` | n/a | yes |
| <a name="input_secrets_kms_key_arn"></a> [secrets\_kms\_key\_arn](#input\_secrets\_kms\_key\_arn) | ARN of the customer-managed KMS key that encrypts the master credential RDS<br/>generates and manages for this cluster, supplied by the kms module. This is<br/>the SECRETS key, deliberately not the same key as kms\_key\_arn, which<br/>encrypts the data. Required with no default, because falling back to the<br/>AWS-managed secretsmanager key would give up the key policy that separates<br/>who may read a credential from who may read the data. | `string` | n/a | yes |
| <a name="input_security_group_ids"></a> [security\_group\_ids](#input\_security\_group\_ids) | Identifiers of the security groups attached to the cluster, supplied by<br/>the network module, which owns the Aurora security group and its rules.<br/>The expected group permits ingress on the database port from the<br/>application tier only. At least one identifier is required. | `list(string)` | n/a | yes |
| <a name="input_apply_immediately"></a> [apply\_immediately](#input\_apply\_immediately) | Whether modifications are applied at once instead of being deferred to<br/>preferred\_maintenance\_window. Defaults to false, so a change waits for the<br/>window. Set it to true only for a change that is known to be<br/>non-disruptive or is urgent enough to accept an interruption. | `bool` | `false` | no |
| <a name="input_backup_retention_period"></a> [backup\_retention\_period](#input\_backup\_retention\_period) | Days of automated backups retained, from 1 to 35. This is also the window<br/>within which point-in-time recovery is possible, so it is the recovery<br/>capability rather than a storage setting. Defaults to 7; prod raises it and<br/>dev may lower it, but neither may disable it. | `number` | `7` | no |
| <a name="input_cluster_parameters"></a> [cluster\_parameters](#input\_cluster\_parameters) | Cluster-level PostgreSQL parameter overrides, as a map of parameter name to<br/>value. Defaults to the two parameters this module REQUIRES and will not let a<br/>caller weaken: rds.force\_ssl = "1", which makes the engine refuse an<br/>unencrypted connection, and password\_encryption = "scram-sha-256", so no<br/>service credential is ever stored as a weaker verifier. No performance tuning<br/>is shipped: everything else runs the engine defaults for the chosen family.<br/>An override REPLACES this map rather than merging into it, so a root adding a<br/>tuning parameter must restate both security parameters; the validation below<br/>rejects a map that omits either or sets either to anything else. | `map(string)` | <pre>{<br/>  "password_encryption": "scram-sha-256",<br/>  "rds.force_ssl": "1"<br/>}</pre> | no |
| <a name="input_copy_tags_to_snapshot"></a> [copy\_tags\_to\_snapshot](#input\_copy\_tags\_to\_snapshot) | Whether the cluster's tags are copied onto its snapshots. Defaults to true<br/>so that a snapshot stays attributable to its owner and cost centre after<br/>the cluster it came from no longer exists. | `bool` | `true` | no |
| <a name="input_database_name"></a> [database\_name](#input\_database\_name) | Name of the initial database created inside the cluster. This module<br/>creates the DATABASE only -- it creates none of the schemas, roles, tables<br/>or grants inside it. Lower case by requirement, because PostgreSQL folds<br/>unquoted identifiers to lower case. | `string` | `"carddemo"` | no |
| <a name="input_deletion_protection"></a> [deletion\_protection](#input\_deletion\_protection) | Whether RDS refuses to delete the cluster. Defaults to true, which is what<br/>prod runs. Dev sets it to false, because a cluster that cannot be deleted<br/>cannot be torn down, and clean teardown is a requirement of the dev<br/>environment rather than a convenience. | `bool` | `true` | no |
| <a name="input_enable_http_endpoint"></a> [enable\_http\_endpoint](#input\_enable\_http\_endpoint) | Whether the Aurora Data API endpoint is enabled. Fixed true because Terraform-invoked schema bootstrap, credential rotation and the AnalyzeTables batch state all use rds-data without a VPC-bound PostgreSQL driver. | `bool` | `true` | no |
| <a name="input_enabled_cloudwatch_logs_exports"></a> [enabled\_cloudwatch\_logs\_exports](#input\_enabled\_cloudwatch\_logs\_exports) | Engine log types the cluster publishes to CloudWatch Logs. For Aurora<br/>PostgreSQL the accepted values are "postgresql", the engine log, and<br/>"upgrade", the major-version upgrade log. Defaults to ["postgresql"]. The<br/>retention of the resulting log groups is set by the observability module,<br/>not here. | `list(string)` | <pre>[<br/>  "postgresql"<br/>]</pre> | no |
| <a name="input_master_username"></a> [master\_username](#input\_master\_username) | Login name of the cluster's master user, as 1 to 63 lower-case letters,<br/>digits and underscores beginning with a letter, and not "rdsadmin" which<br/>RDS reserves. The matching password is NOT an input to this module: RDS<br/>generates it, stores it in a secret of its own encrypted with<br/>secrets\_kms\_key\_arn, and this module publishes only that secret's ARN as<br/>master\_user\_secret\_arn. A user name is not a secret, so this input is not<br/>marked sensitive and does carry a default. | `string` | `"carddemo_admin"` | no |
| <a name="input_parameter_prefix"></a> [parameter\_prefix](#input\_parameter\_prefix) | Canonical Parameter Store namespace under which the host, port and database<br/>connection parameters are published. Must begin with one slash and carry no<br/>trailing slash; the default matches carddemo\_migration.config. | `string` | `"/carddemo"` | no |
| <a name="input_performance_insights_enabled"></a> [performance\_insights\_enabled](#input\_performance\_insights\_enabled) | Whether Performance Insights collects database load and query-level<br/>telemetry from the cluster. Defaults to true. Encrypted with the same<br/>customer-managed key given in kms\_key\_arn, and retained for<br/>performance\_insights\_retention\_period days. | `bool` | `true` | no |
| <a name="input_performance_insights_retention_period"></a> [performance\_insights\_retention\_period](#input\_performance\_insights\_retention\_period) | Days of Performance Insights telemetry retained. AWS accepts only 7, 731,<br/>or a multiple of 31 from 31 to 713. Defaults to 7, the no-additional-charge<br/>tier; ignored when performance\_insights\_enabled is false. | `number` | `7` | no |
| <a name="input_port"></a> [port](#input\_port) | TCP port the cluster listens on. Defaults to 5432, the PostgreSQL default,<br/>which is the port the Aurora security group in the network module admits<br/>from the application tier. Changing it here without changing that rule<br/>makes the cluster unreachable, so the environment root passes the network<br/>module's database\_port here rather than a second literal. | `number` | `5432` | no |
| <a name="input_preferred_backup_window"></a> [preferred\_backup\_window](#input\_preferred\_backup\_window) | Daily window in which automated backups are taken, in UTC, formatted<br/>"hh:mm-hh:mm" -- note there is NO day-of-week prefix on this one. Defaults<br/>to null, which lets RDS assign a window; the environment roots set it<br/>explicitly so it can be held clear of the nightly batch window. | `string` | `null` | no |
| <a name="input_preferred_maintenance_window"></a> [preferred\_maintenance\_window](#input\_preferred\_maintenance\_window) | Weekly window in which RDS applies maintenance, in UTC, formatted<br/>"ddd:hh:mm-ddd:hh:mm" with a lower-case three-letter day -- this one DOES<br/>take a day prefix, unlike preferred\_backup\_window. Defaults to null, which<br/>lets RDS assign a window; the environment roots set it explicitly so it can<br/>be held clear of the nightly batch window. | `string` | `null` | no |
| <a name="input_seconds_until_auto_pause"></a> [seconds\_until\_auto\_pause](#input\_seconds\_until\_auto\_pause) | Idle time in seconds before a cluster whose min\_capacity is 0 pauses, as a<br/>whole number from 300 to 86,400 inclusive. MANDATORY when min\_capacity is<br/>0 and ignored otherwise, which is why it defaults to null: null means "not<br/>set", so a cluster with a non-zero minimum passes nothing and the module<br/>omits the argument entirely. | `number` | `null` | no |
| <a name="input_skip_final_snapshot"></a> [skip\_final\_snapshot](#input\_skip\_final\_snapshot) | Whether deleting the cluster skips taking a final snapshot. Defaults to<br/>false, so a snapshot is taken and prod keeps a last recoverable copy. Dev<br/>sets it to true so that teardown leaves nothing behind to pay for or clean<br/>up. When false, the module supplies the snapshot identifier itself. | `bool` | `false` | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Additional tags merged onto the resources this module owns. Intended for<br/>module-specific or per-call additions only: account-wide tags are applied<br/>by the calling root's provider `default_tags` and must not be repeated<br/>here. Defaults to an empty map, so a caller that relies entirely on<br/>`default_tags` needs to pass nothing. | `map(string)` | `{}` | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_cluster_arn"></a> [cluster\_arn](#output\_cluster\_arn) | ARN of the cluster, for the IAM policy documents the environment root<br/>attaches to the ECS task roles and the batch execution role. It is the<br/>resource an rds:Describe*, snapshot or tagging action names. A reference<br/>that confers nothing on its own: every permission over this cluster comes<br/>from a policy that neither this module nor this output writes or grants.<br/>It is NOT the value an IAM database-authentication policy needs -- see<br/>cluster\_resource\_id. Unknown until apply. |
| <a name="output_cluster_identifier"></a> [cluster\_identifier](#output\_cluster\_identifier) | Identifier RDS knows the cluster by, and the name it appears under in the<br/>console, on an invoice line and in a CloudTrail event. Consumed by the<br/>deploy and batch-operations runbooks, by the SSM steps that bracket the<br/>nightly batch window, and by any Step Functions state that acts ON the<br/>cluster rather than connecting to it. It is not a connection address:<br/>nothing is reachable at this value. |
| <a name="output_cluster_resource_id"></a> [cluster\_resource\_id](#output\_cluster\_resource\_id) | Immutable resource identifier AWS assigns the cluster -- the literal<br/>"cluster-" followed by an opaque suffix -- which is stable across a<br/>rename of the cluster identifier. This, and NOT cluster\_arn or<br/>cluster\_identifier, is the value that goes inside an IAM<br/>database-authentication resource ARN --<br/>`arn:<partition>:rds-db:<region>:<account>:dbuser:<this>/<db-user>` --<br/>and the value by which the cluster's Performance Insights metrics are<br/>addressed. Unknown until apply. |
| <a name="output_connection_parameter_arns"></a> [connection\_parameter\_arns](#output\_connection\_parameter\_arns) | Map keyed by host, port and database containing the exact SSM parameter ARNs published under <parameter\_prefix>/<environment>/aurora/. |
| <a name="output_connection_parameter_names"></a> [connection\_parameter\_names](#output\_connection\_parameter\_names) | Map keyed by host, port and database containing the canonical SSM parameter names consumed by the ETL configuration resolver. |
| <a name="output_database_name"></a> [database\_name](#output\_database\_name) | Name of the initial database inside the cluster, for a consumer<br/>composing a datasource URL. It is a DATABASE and nothing more: this<br/>module creates none of the schemas, roles, tables, indexes or grants<br/>inside it. The eight schemas the application uses -- auth, account,<br/>card, ledger, reference, batch and authorization, plus the read-only<br/>cross-schema views the reporting service reads through -- are created by<br/>data-migration/sql/V0\_\_schemas\_and\_roles.sql together with the<br/>per-service Flyway migrations at<br/>services/*/src/main/resources/db/migration/V1\_\_<schema>.sql. A consumer<br/>that expects a schema to exist because the cluster does will not find<br/>one. |
| <a name="output_db_subnet_group_name"></a> [db\_subnet\_group\_name](#output\_db\_subnet\_group\_name) | Name of the DB subnet group the cluster is placed in, for a consumer that<br/>needs to describe or reference the cluster's network placement. The group<br/>spans the ISOLATED data-tier subnets -- those with no route to the<br/>internet in either direction, neither to an internet gateway nor to a NAT<br/>gateway -- and not the private application subnets that carry the ECS<br/>tasks. Exporting the name does not make the group reusable for another<br/>engine or cluster: it is built for this one. |
| <a name="output_master_user_secret_arn"></a> [master\_user\_secret\_arn](#output\_master\_user\_secret\_arn) | ARN of the Secrets Manager secret that RDS created and manages for the<br/>cluster's master credential. This is the ONLY master credential in the<br/>stack: RDS owns it, and no sibling module creates a second one. It is a<br/>REFERENCE to where the credential lives -- never the credential, which<br/>appears in no output, no variable, no plan and no state entry of this<br/>module. The environment root uses it to grant a task role<br/>secretsmanager:GetSecretValue on this one secret, together with<br/>kms:Decrypt on var.secrets\_kms\_key\_arn, the key that encrypts it -- both<br/>grants are required, and the second key is deliberately the secrets key<br/>rather than the cluster's data key. Consumers resolve the value at runtime<br/>from Secrets Manager, never from Terraform state. Unknown until apply. |
| <a name="output_port"></a> [port](#output\_port) | TCP port the cluster listens on, for a consumer composing a datasource<br/>URL or a matching security-group rule. It is the same port the Aurora<br/>security group in infra/modules/network admits from the application<br/>tier, so a root that overrides var.port must change that rule in the<br/>same change: altered on one side only, the cluster still plans, still<br/>applies and still reports healthy to Terraform while refusing every<br/>connection. |
| <a name="output_reader_endpoint"></a> [reader\_endpoint](#output\_reader\_endpoint) | DNS name of the cluster's reader endpoint. Aurora publishes this address<br/>whether or not a reader instance exists, and this module provisions<br/>exactly one instance -- the writer -- so it resolves to that same<br/>writer. It is exported for the reporting service, which reads through<br/>read-only cross-schema VIEWS under a dedicated SELECT-only database role<br/>rather than through a replica. It must not be treated as a lag-free<br/>scale-out read path: with no second instance behind it, it adds no<br/>capacity and gives no isolation from writer load. Unknown until apply. |
| <a name="output_security_group_ids"></a> [security\_group\_ids](#output\_security\_group\_ids) | Identifiers of the security groups attached to the cluster, echoed back<br/>from the module's input so that a consumer composing the application<br/>tier's matching egress rule has the effective value without re-deriving<br/>it. infra/modules/network OWNS these groups and both halves of the rule<br/>pair; this module only attaches what it is handed, so nothing about a<br/>group's rules can be changed through this output. |
| <a name="output_writer_endpoint"></a> [writer\_endpoint](#output\_writer\_endpoint) | DNS name of the cluster's writer endpoint, which is the single address<br/>every read and every write in the platform resolves to. The calling<br/>environment root publishes this into SSM Parameter Store, and each<br/>service reads it there at startup through its Spring profile to compose<br/>SPRING\_DATASOURCE\_URL; batch tasks receive it the same way, through Step<br/>Functions container overrides. No service, container image or tfvars file<br/>may hard-code it. Unknown until apply, and it changes if the cluster is<br/>ever replaced -- which is the reason consumers resolve it at startup<br/>rather than baking it in at build time. |
<!-- END_TF_DOCS -->


---


## 8. The baseline this replaces

Refactoring Rationale: this section exists so the count is measured once and
never re-derived. Every path below is reference-only: read to ground a decision,
never modified.

### 8.1 Ten VSAM KSDS base clusters

Each was created by an in-stream `IDCAMS DEFINE CLUSTER` step. `KEYS` is
*(length offset)*, so `KEYS(11 0)` is an eleven-byte key at offset zero.

| Dataset | Definition | `KEYS` | `RECORDSIZE` | `SHAREOPTIONS` |
|---|---|---|---|---|
| ACCTDATA | `app/jcl/ACCTFILE.jcl:36` | `(11 0)` | `(300 300)` | `(2 3)` |
| CARDDATA | `app/jcl/CARDFILE.jcl:50` | `(16 0)` | `(150 150)` | `(2 3)` |
| CARDXREF | `app/jcl/XREFFILE.jcl:39` | `(16 0)` | `(50 50)` | `(2 3)` |
| CUSTDATA | `app/jcl/CUSTFILE.jcl:46` | `(9 0)` | `(500 500)` | `(2 3)` |
| DISCGRP | `app/jcl/DISCGRP.jcl:36` | `(16 0)` | `(50 50)` | `(2 3)` |
| TCATBALF | `app/jcl/TCATBALF.jcl:36` | `(17 0)` | `(50 50)` | `(2 3)` |
| TRANCATG | `app/jcl/TRANCATG.jcl:36` | `(6 0)` | `(60 60)` | `(2 3)` |
| TRANSACT | `app/jcl/TRANFILE.jcl:49` | `(16 0)` | `(350 350)` | `(2 3)` |
| TRANTYPE | `app/jcl/TRANTYPE.jcl:36` | `(2 0)` | `(60 60)` | `(1 4)` |
| USRSEC | `app/jcl/DUSRSECJ.jcl:64` | `(8,0)` | `(80,80)` | — |

`TRANTYPE` is the only dataset with different share options, and `USRSEC` is the
only one that specifies none.

### 8.2 Three alternate indexes, four definitions

All three are `NONUNIQUEKEY` with `UPGRADE`.

| Index | Definition | `KEYS` |
|---|---|---|
| `CARDDATA.VSAM.AIX` — cards by account | `app/jcl/CARDFILE.jcl:83` | `(11 16)` |
| `CARDXREF.VSAM.AIX` — cross-reference by account | `app/jcl/XREFFILE.jcl:72` | `(11,25)` |
| `TRANSACT.VSAM.AIX` — transactions by processing timestamp | `app/jcl/TRANIDX.jcl:25` **and** `app/jcl/TRANFILE.jcl:82` | `(26 304)` |

### 8.3 Four definitions that must not inflate the count

These are not part of the ten, and are listed so a recount does not land on
fourteen: `app/jcl/CBEXPORT.jcl:30` (`EXPORT.DATA`),
`app/jcl/CREASTMT.JCL:29` (`TRXFL.VSAM.KSDS`), `app/jcl/ESDSRRDS.jcl:65` and
`:99` (a `USRSEC` ESDS and RRDS demonstration pair) and
`app/jcl/DEFCUST.jcl:35` (`AWS.CUSTDATA.CLUSTER`).

### 8.4 Two counting hazards, both measured

Assumptions: both of these were measured rather than estimated, and both
would otherwise have to be derived again by the next reader who verifies the
figure.

- ⚠️ **A naive `grep "DEFINE CLUSTER"` finds only nine of the ten base
  clusters.** `app/jcl/DUSRSECJ.jcl:64` writes `DEFINE    CLUSTER` with multiple
  spaces, as do `app/jcl/CREASTMT.JCL:29` and `app/jcl/ESDSRRDS.jcl:65` and
  `:99`. Use `grep -rnE "DEFINE +CLUSTER" app/`, which finds sixteen statements
  where the single-space form finds twelve.
- ⚠️ **There are three distinct alternate indexes but four `DEFINE
  ALTERNATEINDEX` statements** — the `TRANSACT` index is defined identically
  twice, at `app/jcl/TRANIDX.jcl:25` and `app/jcl/TRANFILE.jcl:82`. The
  `TRANSACT` **base cluster** is likewise defined twice, at
  `app/jcl/TRANFILE.jcl:49` and again at `app/jcl/TRANBKP.jcl:54`, which is the
  sixteenth statement and the reason the statement count exceeds the dataset
  count by more than the four above.

### 8.5 Why "encrypted, with automated backups" is a correction, not a port

⭐ Refactoring Rationale: this is the strongest reason the target differs
from the baseline rather than reproducing it. The CICS resource definition
declares eight VSAM `FILE` resources, and **all eight carry an identical
durability posture**: `JOURNAL(NO)`, `JNLREAD(NONE)`, **`RECOVERY(NONE)`**,
**`FWDRECOVLOG(NO)`**, `BACKUPTYPE(STATIC)`, `READINTEG(UNCOMMITTED)` and
`UPDATEMODEL(LOCKING)`.

| Resource | `DEFINE FILE` | `JOURNAL(NO)` | `RECOVERY(NONE)` / `FWDRECOVLOG(NO)` |
|---|---|---|---|
| ACCTDAT | `app/csd/CARDDEMO.CSD:1` | `:7` | `:9` |
| CARDAIX | `app/csd/CARDDEMO.CSD:13` | `:19` | `:21` |
| CARDDAT | `app/csd/CARDDEMO.CSD:25` | `:31` | `:33` |
| CCXREF | `app/csd/CARDDEMO.CSD:37` | `:44` | `:46` |
| CUSTDAT | `app/csd/CARDDEMO.CSD:50` | `:57` | `:59` |
| CXACAIX | `app/csd/CARDDEMO.CSD:63` | `:70` | `:72` |
| TRANSACT | `app/csd/CARDDEMO.CSD:76` | `:82` | `:84` |
| USRSEC | `app/csd/CARDDEMO.CSD:88` | `:94` | `:96` |

No journalling, no recovery, no forward-recovery log and no encryption at rest
anywhere. So `storage_encrypted`, the customer-managed key and the
non-disableable backup retention in section 2 are not preserving a baseline
guarantee — they are **supplying one the baseline never had.** Stating it as a
port would misrepresent both systems.

### 8.6 Eight resources, ten clusters — the reconciliation

A reader will meet both figures and should not have to reconcile them twice. Two
of those eight CICS `FILE` resources point at `.AIX.PATH` datasets rather than at
base clusters — `CARDAIX` at `app/csd/CARDDEMO.CSD:14` names
`CARDDATA.VSAM.AIX.PATH`, and `CXACAIX` at `:65` names
`CARDXREF.VSAM.AIX.PATH`. Those two are alternate-index access paths surfaced to
CICS as files, not datasets in their own right, which is why the verified
base-cluster figure is **ten** and the CICS resource figure is **eight**.


---


## 9. Environment parameterization

Assumptions: `dev` and `prod` differ only in sizing, retention and
protection, and **never in topology.** Both run the same six resources, the same
one writer, the same isolated placement and the same encryption. That narrowness
is deliberate: an environment that differs structurally cannot be a rehearsal
for the other.

| Axis | `dev` | `prod` |
|---|---|---|
| Minimum capacity | `0` — may pause | above zero — never pauses |
| Maximum capacity | sized down | sized up |
| Auto-pause delay | required, because the minimum is zero | supplied but inert |
| Backup retention | short | long |
| Deletion protection | `false` | `true` |
| Final snapshot | skipped | taken |

Trade-offs: deletion protection is deliberately **off** in `dev`, and that
is not a weakening by neglect. A cluster that cannot be deleted cannot be torn
down, and clean teardown is an acceptance criterion — the stack must provision
cleanly and `destroy` must remove it cleanly — not a convenience. The same
reasoning drives `skip_final_snapshot` in `dev`: teardown should leave nothing
behind to pay for or clean up. `prod` inverts both, keeping a last recoverable
copy and refusing deletion. The accepted cost is that a `dev` cluster is easy to
destroy; the benefit is that the teardown path in the
[teardown runbook](../../../docs/runbooks/teardown.md) is exercised rather than
theoretical.

Assumptions: **no secret value appears in any `.tfvars` file.** The
environment parameter files carry capacity, retention, windows and flags only.
The master credential is generated at apply time and written to Secrets Manager
by RDS, and deployment authenticates by short-lived federated role assumption
rather than a stored key. That is the mechanism that makes "no secrets
committed" structurally true rather than merely observed: there is no place in
this module's contract where a credential could be written down, so none can be.


---


## 10. Troubleshooting

| Symptom | Cause and resolution |
|---|---|
| `validate` fails naming `min_capacity` or `max_capacity` | A capacity rule from section 4. The message names the violated constraint and the single input to change; capacity must be 0–256 in half-unit steps, and the maximum may not be below the minimum |
| `validate` fails saying the auto-pause delay must be set | Rule 5: the minimum is zero, so `seconds_until_auto_pause` is mandatory. Supply a whole number of seconds from 300 to 86,400, or raise the minimum above zero |
| `validate` fails saying the maximum must be at least 1 | Rule 4, the other half of the zero-minimum coupling. Raise `max_capacity` to at least one ACU |
| `apply` rejects a zero minimum that `validate` accepted | The engine minor version does not support scaling to zero. Terraform cannot check this; choose an `engine_version` whose minor release supports it, or hold the minimum above zero |
| `apply` rejects the cluster parameter group | `parameter_group_family` does not match the **major** version of `engine_version`. The two are set together in the environment root; correct both in one change |
| `validate` fails comparing the two KMS ARNs | The cross-ARN precondition in `main.tf`: the data key and the secrets key must name the same partition, region and account. Both come from the `kms` module, so the defect is in the root's wiring rather than in either ARN |
| `destroy` refuses to remove the cluster | Deletion protection is on. That is expected in `prod`; in `dev` it means the flag was overridden. If the failure instead names a missing snapshot identifier, `skip_final_snapshot` is `false` and a final snapshot is being taken — expected in `prod` |
| The first connection after an idle period appears to hang | The cluster auto-paused and is resuming, which takes on the order of fifteen seconds. Expected wherever the minimum is zero. Raise the minimum above zero for an interactive workload, or lengthen the auto-pause delay |
| CI reports this README out of date | A `.tf` file changed and section 7 was not regenerated. Run the generator without `--output-check` from the repository root, review the regenerated table, and commit it in the same change as the cause. CI is check-only and will not fix it |
| `tflint` reports a missing `description` or an unused declaration | A new `variable` or `output` was added without a description, or one no longer has a consumer. Both are the mechanical half of Rule 1 and both are gating |


---


## 11. References

- [`infra/README.md`](../../README.md) — the package overview, the module index
  and the authoritative deploy and teardown staging
- [`ADR-003-datastore-targets.md`](../../../docs/adr/ADR-003-datastore-targets.md)
  — decision D3, with the options considered, the cost implications and the
  risks accepted
- [Deployment runbook](../../../docs/runbooks/deploy.md) and
  [teardown runbook](../../../docs/runbooks/teardown.md) — the exact command
  sequences, which this document cross-links rather than duplicates
- [Data-migration runbook](../../../docs/runbooks/data-migration.md) — the
  schema, role and grant creation that sits outside this module's boundary
- [`data-model-and-schema-mapping.md`](../../../docs/architecture/data-model-and-schema-mapping.md)
  — the field-by-field mapping from the copybook layouts to PostgreSQL columns
- [`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../docs/CODE_DOCUMENTATION_STANDARD.md)
  — the documentation convention this file is written to

> **Explainability rule (mandatory).** Every new variable, output, resource
> argument and document in this module must carry documentation stating
> **Purpose, Parameters, Returns, and Exceptions** — for HCL, that is the file
> header block plus a `description` on every variable and output — and its
> inline comments must explain **why** (documenting at least one of Alternatives
> Considered, Refactoring Rationale, Assumptions, or Trade-offs) — never restate
> what the code does. This is a hard review gate.
