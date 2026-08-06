# ECS cluster module

This reusable Terraform module provisions the single Amazon ECS cluster in which
every CardDemo service task and every Step-Functions-invoked batch task runs. Its
target contract is fixed by AAP section 0.4.1.6, which specifies this module as a
Fargate cluster with Container Insights, and the compute model it implements is
AAP decision D2, recorded in
[ADR-002](../../../docs/adr/ADR-002-compute-platform.md). The baseline it
decomposes is [`app/csd/CARDDEMO.CSD`](../../../app/csd/CARDDEMO.CSD), which is
read-only lineage: this migration adds a compute path beside the existing
mainframe path and does not modify it.

This README is the prose half of Rule 1 Explainability and is required by AAP
section 0.2.1.6. HCL has no docstring construct, so that obligation is split in
two. The mechanical half is enforced by [TFLint](../../.tflint.hcl), which fails
any variable or output carrying no `description`, and by the
[terraform-docs configuration](../../.terraform-docs.yml), which lifts those
descriptions into the generated tables below and then checks that they still
match the HCL. This document carries the reasoning neither tool can hold. The
four Terraform files beside it are the executable source of truth.


## What this module provisions

* **The ECS cluster.** Its name is composed as `<name_prefix>-cluster-<environment>`,
  so `carddemo-cluster-<env>` at the defaults, matching the
  `carddemo-<component>-<env>` convention every resource in this package follows.
* **The Fargate capacity-provider association**, attaching `FARGATE` and
  `FARGATE_SPOT` and setting the cluster-level default placement strategy that
  applies to any task naming neither a launch type nor a strategy of its own.
* **Container Insights**, written into the cluster's `containerInsights` setting.
* **Cluster-level ECS Exec session logging**, configured only when the caller
  supplies `execute_command_log_group_name`, with the session channel encrypted
  under the customer-managed key named in `kms_key_arn` when one is given.

That is the entire resource graph: two resources, `aws_ecs_cluster` and
`aws_ecs_cluster_capacity_providers`. The generated reference below renders no
Modules and no Data Sources section because this module genuinely has neither.


## What this module deliberately does not provision

A reader looking for a task definition is in the wrong directory. Every concern
below is owned by exactly one sibling module:

| Concern | Owning module |
|---|---|
| task definition, task role, log group, target group, autoscaling | `ecs-service`, instantiated once per service |
| VPC, public / private-application / isolated-data subnets, security groups | `network` |
| internal load balancer and per-service listener rules | `alb` |
| log groups, dashboards, alarms, SNS topic | `observability` |
| the four customer-managed keys | `kms` |
| container image repositories | `ecr` |
| the eleven-state nightly batch state machine, whose task states run in this cluster | `step-functions-batch` |

**This module calls no sibling module.** It consumes only its own ten variables,
reads no data source, and declares no dependency on any other module.
Composition is the environment root's job, because a root is the only layer that
can see every producer's outputs at once.

Assumptions: an input here for a subnet, a task size, a container port or a log
retention period would restate a sibling's surface and leave behind a
declaration nothing in this module reads. The `terraform_unused_declarations`
rule configured in [TFLint](../../.tflint.hcl) reports exactly that as a lint
failure, so the boundary is mechanically enforced rather than merely intended.


## Why Fargate, and not EC2 or Lambda

Decision D2 selects Fargate for the eight services, Step-Functions-invoked
Fargate tasks for batch, and reserves Lambda for bounded glue.
[ADR-002](../../../docs/adr/ADR-002-compute-platform.md) owns the full analysis,
including the cost model and the container base-image pin. What follows is only
the part that explains why this module creates a Fargate cluster and nothing
else.

Alternatives Considered: a per-invocation function model for the eight services.
The services are long-running request-and-response APIs that hold warm JDBC
connection pools, and a function process does not survive between invocations, so
a pool cannot be held across them. Recovering pooling would mean introducing a
separate connection-proxy component in front of Aurora — adding a component to
solve a problem a long-lived task process does not have in the first place.

Alternatives Considered: hosting the batch chain on functions. This is the
cleanest rejection in the option set because it is a hard service limit rather
than a preference: batch steps run longer than Lambda's fifteen-minute execution
ceiling, and a step that cannot finish inside the ceiling cannot run there at
all.

Assumptions: Lambda remains the right host for exactly three of the eleven states
in the nightly chain, which is what "glue" means here precisely — state 1
`QuiesceOnlineWrites` sets a read-only flag in Parameter Store, state 10
`AnalyzeTables` runs `VACUUM ANALYZE`, and state 11 `ResumeOnlineWrites` clears
the flag. Each is short, stateless and well inside the ceiling. The
data-moving states between them are the ones that need a task, and they run in
this cluster. See [ADR-005](../../../docs/adr/ADR-005-batch-orchestration.md) and
[the batch orchestration architecture](../../../docs/architecture/batch-orchestration.md).

Alternatives Considered: an EC2-backed cluster. In the AAP's own words, EC2
"would add patching and capacity management for no benefit". The cost shape is
the second half of that argument: instance-hours are billed whether or not work
is running, and this workload's heaviest demand is a nightly chain, so a cluster
sized for that peak would sit idle through most of the day and still be charged
for it. A Fargate task is billed for the duration it actually runs.

Alternatives Considered: EKS. Rejected on component count, not on any property of
Kubernetes: a control plane and a cluster-operations discipline repay their
overhead as the scheduled estate grows, and this estate is eight services and one
nightly chain. At that size the overhead is operational burden the cluster below
does not carry.

Trade-offs: Fargate gives up host-level control — no custom kernel, no
daemonset, no instance-store volume, and task startup includes an image pull
rather than landing on an already-warm host. None of those is required by any of
the eight services or by any batch step, so the compromise costs this workload
nothing it can name.

Two boundaries belong here so this module is not read as offering more than it
does. **Rolling ECS service deployment only** — blue-green and canary deployment
are out of scope per AAP section 0.2.2, so no deployment-controller configuration
appears on this cluster. **No cache tier** — Redis and ElastiCache are out of
scope because the baseline has no cache and functional parity does not require
one.


## Why the cluster is a separate module from the services

Alternatives Considered: a single combined module creating a cluster together
with its service. That is the reasonable alternative, and it fails on arity.
`ecs-service` is instantiated once per service — eight times — while the cluster
exists once per environment. A combined module would therefore either create
eight clusters where exactly one is wanted, or push the per-service resources
into a module that may only run once, forfeiting precisely the reuse the eight
services need.

Trade-offs: the split costs one more directory in the module tree and one more
output-to-input wiring step in each environment root, since `cluster_name` and
`cluster_arn` have to be passed from here into every `ecs-service` call. That is
accepted in exchange for a service module with no cluster lifecycle bound into
it — destroying or replacing one service must not be able to take the cluster,
and the other seven services with it.


## Container Insights

`container_insights` defaults to `enabled` rather than to `disabled`.

Assumptions: AAP section 0.4.1.6 specifies this module as "Fargate cluster,
Container Insights", so the on value is the specified state and not a local
preference. The material-security policy gate in
[the infrastructure workflow](../../../.github/workflows/infra-ci.yml) is gating
and expects cluster-level monitoring to be configured on the cluster resource
itself, so a default of `disabled` would mean every caller had to remember to
switch it on and that an otherwise-correct module call failed the gate.

What this setting is not: it collects cluster-level task and service metrics. It
is not application instrumentation — the services export their own metrics
through the Micrometer configuration in `common-lib` — and it creates no
dashboard and no alarm, both of which the `observability` module owns.

Trade-offs: the tier is a variable rather than a literal because the `enhanced`
value bills per additional observation for the task and container dimensions it
collects, and a development environment gains nothing from container-level
drill-down that it pays for on every task. Exposing the input lets one
environment sit on `enabled` while another moves to `enhanced` with no code
change. The `validation` block on that variable is load-bearing rather than
decorative: the pinned provider validates the setting *name* but applies no
validator at all to the setting *value*, so without it a typo would reach the ECS
API and fail at apply time, after the cluster call had already been attempted.


## The CICS region this decomposes

AAP section 0.5.1.12 records this module's reference source as the CICS resource
definitions, with the change note "the CICS region's role, decomposed". The
figures below were measured directly from
[`app/csd/CARDDEMO.CSD`](../../../app/csd/CARDDEMO.CSD), a 505-line file that
this migration reads and never edits.

* **18 `DEFINE PROGRAM` stanzas**, spanning L173–L305.
* **18 `DEFINE TRANSACTION` stanzas**, spanning L306–L488. In file order the
  transaction identifiers are `CAUP`, `CAVW`, `CA00`, `CB00`, `CCDL`, `CCLI`,
  `CCUP`, `CC00`, `CDV1`, `CM00`, `CR00`, `CT00`, `CT01`, `CT02`, `CU00`,
  `CU01`, `CU02` and `CU03`.
* **The per-transaction attributes are uniform across all eighteen.** Each of
  `ISOLATE(YES)`, `TASKDATAKEY(USER)`, `ACTION(BACKOUT)`,
  `TRANCLASS(DFHTCL00)`, `PRIORITY(1)` and `RESTART(NO)` occurs exactly 18
  times — once per stanza.

Refactoring Rationale: that uniformity is the whole argument for this module's
shape. One transaction class, one priority and one isolation setting across every
transaction describe a single homogeneous execution environment, and that is a
property of the CICS programming model rather than a choice these definitions
made: the region is the unit of execution, so a per-transaction definition
carries dispatch attributes and not an independent runtime. The target model
places the unit of execution one level lower, at the task. The same homogeneous
environment therefore decomposes into one Fargate cluster hosting eight
independently scaled services rather than into eighteen bespoke runtimes. What
changes is where the execution boundary sits, not whether the original had one —
which is exactly what makes this region a tractable decomposition exercise.

Four attributes map conceptually, and only these four:

| CICS attribute | Target treatment |
|---|---|
| `ISOLATE(YES)` | Per-task isolation is intrinsic to Fargate, so the property is preserved by the platform and is configured nowhere in this module. |
| `TRANCLASS(DFHTCL00)` with `PRIORITY(1)` | The cluster's capacity-provider strategy set here, plus per-service autoscaling in `ecs-service`. |
| `TASKDATAKEY(USER)` | Containers run as a non-root user, which the service Dockerfiles own. |
| `RESTART(NO)` | A difference rather than an equivalence, recorded as such: a failed CICS transaction was not automatically restarted, whereas ECS replaces an unhealthy *task* and does not retry a request. |

One inventory fact, for completeness: `DEFINE PROGRAM(COCRDSEC)` at L211 has no
matching program source under `app/cbl`, and the `PROGRAM(COCRDSEC)` attribute of
`DEFINE TRANSACTION(CDV1)` sits at L390. AAP section 0.5.2 records that entry as
documented with no target, and
[the traceability matrix](../../../docs/architecture/cobol-to-service-traceability.md)
is where every such observation is registered.

Two neighbouring parts of the same file are owned elsewhere and are not claimed
here: the two `DEFINE LIBRARY` stanzas at L489–L496 map to `ecr`, and
`DEFINE TDQUEUE(JOBS)` at L499–L505 maps to `step-functions-batch` together with
the reporting service's ad-hoc report path.


## Usage

```hcl
# WHAT: instantiate the cluster once per environment from an environment root.
# WHY : Assumptions: the root owns provider configuration and every cross-module
#       reference, so it is the only layer that can hand this module a log group
#       name and a key that already exist.
module "ecs_cluster" {
  source = "../../modules/ecs-cluster"

  name_prefix = var.name_prefix
  environment = var.environment

  execute_command_log_group_name = "/aws/ecs/${var.name_prefix}-${var.environment}/execute-command"
  kms_key_arn                    = module.kms.s3_key_arn
}
```

Consumers bind the outputs by name. `ecs-service` takes both identifiers, and
`step-functions-batch` takes the ARN alone:

```hcl
# WHAT: wire the cluster's identity into the two modules that place tasks in it.
# WHY : Assumptions: each consumer needs a different form of the identity, which
#       is why this module publishes both the bare name and the ARN rather than
#       one of them plus a string interpolation at each call site.
module "ecs_service" {
  source = "../../modules/ecs-service"

  cluster_arn  = module.ecs_cluster.cluster_arn
  cluster_name = module.ecs_cluster.cluster_name
  # ... the per-service inputs
}

module "step_functions" {
  source = "../../modules/step-functions-batch"

  ecs_cluster_arn = module.ecs_cluster.cluster_arn
  # ... the per-workflow inputs
}
```

Four properties of this module follow from its being a module rather than a root,
and each is worth stating because getting one wrong produces a confusing failure:

* **It is never applied directly.** It has no `backend` block and no state of its
  own. `infra/envs/dev/main.tf` and `infra/envs/prod/main.tf` instantiate it, and
  those roots are what an operator applies.
* **It declares no `provider` block.** Provider configuration — the region and
  the `default_tags` set that every resource here inherits — comes from the
  calling root. `var.tags` is additive on top of `default_tags`, and a key set in
  both places resolves in favour of the value set here.
* **It is validated transitively.** The infrastructure workflow runs
  `terraform init -backend=false` followed by `terraform validate` against the
  three roots — `infra/bootstrap`, `infra/envs/dev` and `infra/envs/prod` — and
  this module is checked whenever a calling root initialises. `-backend=false`
  is what keeps that static path free of credentials and of any dependency on
  provisioned state.
* **Placeholders only.** Anything account-specific is written here as
  `<aws-account-id>`, `<region>` or `<env>`. No account identifier, ARN or
  endpoint is committed anywhere in this package; see AAP section 0.9.1.


## Input and output notes

The generated tables below carry each input's name, type, default, description
and whether it is required. The reasoning a table cannot express is here.

* **`environment` is the one input with no default.** Alternatives Considered:
  defaulting it to `dev`. Terraform reports nothing when a default is silently
  accepted, so a production root that omitted the argument would plan and apply a
  cluster named for the wrong environment, and the mistake would surface later as
  a name collision or a misrouted service. With no default, the same omission is
  an unassigned-variable error at plan time, before any API call.
* **`cluster_name` is nullable and asserts no charset rule.** A caller reaching
  for it is naming a cluster that exists outside this module's control, so the
  provider is the only authority on whether that name is legal. Re-asserting the
  narrower in-house pattern would reject exactly the pre-existing names the
  input was added to accommodate.
* **`capacity_providers` is a `list` even though the argument it feeds is a
  set.** Alternatives Considered: declaring it `set(string)`, which would make a
  duplicate entry impossible by construction — but would do so by discarding the
  duplicate silently, which is the slip the variable's own validation reports. A
  list keeps the caller's input intact long enough to be checked.
* **`FARGATE_SPOT` is attached by default.** Trade-offs: Spot capacity is
  reclaimable on short notice, but attaching a provider places no task on it. How
  much work lands on Spot is decided by `default_capacity_provider_strategy`,
  whose default reserves a guaranteed on-demand base. Attaching it here keeps the
  option open without a later change to the cluster's associations, which is a
  separate API call from changing a service's placement.

The four outputs and the consumer each one exists for:

| Output | Bound by | Why that form |
|---|---|---|
| `cluster_name` | `ecs-service`, `observability` | `ecs-service` composes the Application Auto Scaling target identifier `service/<cluster-name>/<service-name>`, which AWS accepts in no other form. |
| `cluster_arn` | `ecs-service`, `step-functions-batch` | `aws_ecs_service` takes it as its `cluster` argument; the batch state machine carries it in each `ecs:runTask.sync` task state, and its execution-role policy conditions `ecs:RunTask`, `ecs:StopTask` and `ecs:DescribeTasks` on this cluster so a task may be started only here. |
| `capacity_provider_names` | any caller writing a per-service strategy | ECS refuses a strategy entry naming a provider the cluster does not hold, so a consumer may name only a provider that appears in this set. |
| `default_capacity_provider_strategy` | any caller deciding whether to set its own | A consumer's own strategy replaces this one outright rather than merging with it; an empty set means the cluster has no default and a launch type or strategy must be named per service. |


## Caller obligations and failure modes

* **A configured default `aws` provider is required.** This module declares none,
  so a root that has not configured one fails at `terraform init` or
  `terraform validate` rather than at apply.
* **A Terraform CLI at or above the module's floor is required.** `versions.tf`
  declares `>= 1.15.0` and `hashicorp/aws ~> 6.56`; an older CLI or a provider
  outside that constraint fails during initialisation.
* **`environment` must be supplied.** It has no default, so omitting it is a hard
  plan-time error, by design.
* **`name_prefix` and `environment` are format-checked, not membership-checked.**
  Each must be lowercase letters, digits and hyphens, starting and ending
  alphanumeric, within 32 and 16 characters respectively. A closed set of
  environment names would additionally forbid a third root while catching no
  failure the format check misses.
* **ECS Exec logging prerequisites are the caller's.** If
  `execute_command_log_group_name` is set, that log group must already exist, and
  if `kms_key_arn` is set the key must already exist and its policy must permit
  the ECS service to use it. This module creates neither; `observability` owns
  log groups and `kms` owns keys.
* **The capacity-provider association is exclusive.** This module manages the
  full set of providers for the cluster it names, so a caller must not attach the
  same providers to that cluster from anywhere else; two managers of one
  association will fight on every apply.
* **Renaming replaces.** `name_prefix`, `environment` and `cluster_name` compose
  the cluster name, which is immutable, so changing any of them after apply
  destroys and recreates the cluster and cascades into every service placed in
  it.


## Validation and gates

Every command below is gating in
[the infrastructure workflow](../../../.github/workflows/infra-ci.yml) and has no
tolerated non-zero return code.

```bash
# WHAT: verify canonical HCL formatting across the package without rewriting a
#       single committed file.
# WHY : Alternatives Considered: a bare `terraform fmt`, which silently rewrites
#       the checkout and then exits zero — in CI that would let a formatting
#       regression pass as green because the command "fixed" it. `-check`
#       reports drift and exits non-zero instead.
terraform fmt -check -recursive infra/

# WHAT: initialise and validate this module's provider schema in isolation.
# WHY : Assumptions: a module has no backend and no state, so `-backend=false`
#       resolves providers and modules without touching remote state or needing
#       credentials. CI validates the three roots, which reaches this module
#       transitively; running it here narrows a failure to this directory.
terraform -chdir=infra/modules/ecs-cluster init -backend=false
terraform -chdir=infra/modules/ecs-cluster validate

# WHAT: enforce documented, typed and consumed declarations plus the AWS ruleset.
# WHY : Assumptions: this is the mechanical half of Rule 1 for HCL. The rules
#       that bear on this module are terraform_documented_variables,
#       terraform_documented_outputs, terraform_typed_variables,
#       terraform_required_version, terraform_required_providers,
#       terraform_unused_declarations, terraform_naming_convention and
#       terraform_comment_syntax, which pins the `#` comment form.
tflint --chdir=infra/modules/ecs-cluster --config="$(pwd)/infra/.tflint.hcl"

# WHAT: verify the generated reference below still matches the four .tf files.
# WHY : Trade-offs: the check writes nothing. Regenerating and committing in CI
#       was rejected because silent mutation would hide the drift the gate
#       exists to make reviewable, so a stale README is a red build that a human
#       regenerates deliberately. Any input, output or resource change makes
#       this document incorrect until it is regenerated.
terraform-docs --config infra/.terraform-docs.yml \
  --output-check infra/modules/ecs-cluster
```

A fourth gate has no single command to quote here: the workflow's
material-security policy scan runs across `infra/` against a curated set of
material checks and asserts zero failures, and cluster-level monitoring is
configured on the cluster resource where that scan expects to find it.

**This module is authored and statically validated only.** `terraform apply`
against a live AWS account is an operator action outside this scope per AAP
section 0.2.2, and nothing here has been deployed, benchmarked or load-tested.
The operator commands live in
[the deploy runbook](../../../docs/runbooks/deploy.md) and
[the teardown runbook](../../../docs/runbooks/teardown.md) and are deliberately
not reproduced here, so there is one place to correct them.


## Generated reference

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
| [aws_ecs_cluster.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ecs_cluster) | resource |
| [aws_ecs_cluster_capacity_providers.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ecs_cluster_capacity_providers) | resource |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_environment"></a> [environment](#input\_environment) | Deployment-environment discriminator appended to `name_prefix` to compose the cluster name. | `string` | n/a | yes |
| <a name="input_capacity_providers"></a> [capacity\_providers](#input\_capacity\_providers) | Capacity providers to associate with the cluster; only the two Fargate providers are supported. | `list(string)` | <pre>[<br/>  "FARGATE",<br/>  "FARGATE_SPOT"<br/>]</pre> | no |
| <a name="input_cluster_name"></a> [cluster\_name](#input\_cluster\_name) | Explicit cluster name that overrides the composed `name_prefix`-`environment` value; null keeps the composed name. | `string` | `null` | no |
| <a name="input_container_insights"></a> [container\_insights](#input\_container\_insights) | Cluster-level Container Insights tier written into the containerInsights cluster setting. | `string` | `"enabled"` | no |
| <a name="input_default_capacity_provider_strategy"></a> [default\_capacity\_provider\_strategy](#input\_default\_capacity\_provider\_strategy) | Default placement strategy for tasks that specify no launch type or strategy of their own. | <pre>list(object({<br/>    capacity_provider = string<br/>    weight            = optional(number)<br/>    base              = optional(number)<br/>  }))</pre> | <pre>[<br/>  {<br/>    "base": 1,<br/>    "capacity_provider": "FARGATE",<br/>    "weight": 4<br/>  },<br/>  {<br/>    "capacity_provider": "FARGATE_SPOT",<br/>    "weight": 1<br/>  }<br/>]</pre> | no |
| <a name="input_execute_command_log_group_name"></a> [execute\_command\_log\_group\_name](#input\_execute\_command\_log\_group\_name) | Name of an existing CloudWatch log group for ECS Exec session output; null omits the log configuration. | `string` | `null` | no |
| <a name="input_execute_command_logging"></a> [execute\_command\_logging](#input\_execute\_command\_logging) | ECS Exec log destination; one of NONE, DEFAULT or OVERRIDE. | `string` | `"DEFAULT"` | no |
| <a name="input_kms_key_arn"></a> [kms\_key\_arn](#input\_kms\_key\_arn) | Customer-managed KMS key reference encrypting the ECS Exec channel; null uses the AWS-managed default. | `string` | `null` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Leading component of the composed cluster name; joined to `environment` with a hyphen. | `string` | `"carddemo"` | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Additional tags merged onto the cluster resources, on top of the provider's default\_tags. | `map(string)` | `{}` | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_capacity_provider_names"></a> [capacity\_provider\_names](#output\_capacity\_provider\_names) | Short names of the capacity providers associated with the cluster, as a set. A caller writing a per-service capacity-provider strategy may name only a provider that appears here, because ECS refuses a strategy entry naming a provider the cluster does not hold. |
| <a name="output_cluster_arn"></a> [cluster\_arn](#output\_cluster\_arn) | ARN of the ECS Fargate cluster this module creates. aws\_ecs\_service takes it as its `cluster` argument, the batch state machine carries it in each task state's parameters, and the execution and task role policies are scoped to it so a task may be started only in this cluster. |
| <a name="output_cluster_name"></a> [cluster\_name](#output\_cluster\_name) | Bare name -- not the ARN -- of the ECS Fargate cluster this module creates. ecs-service consumes it to compose the Application Auto Scaling target identifier `service/<cluster-name>/<service-name>`, which AWS accepts in no other form. |
| <a name="output_default_capacity_provider_strategy"></a> [default\_capacity\_provider\_strategy](#output\_default\_capacity\_provider\_strategy) | Cluster-level default placement strategy, as a set of objects carrying `capacity_provider`, `weight` and `base`. ECS applies it to any task or service naming neither a launch type nor a strategy of its own; a consumer that sets its own strategy replaces this one outright rather than merging with it. An empty set means the cluster has no default and a launch type or strategy must be named per service. |
<!-- END_TF_DOCS -->


## Related documents

* [The infrastructure package guide](../../README.md) — the module index, the
  version constraints, and the static-validation and operator boundary.
* [ADR-002: Compute platform](../../../docs/adr/ADR-002-compute-platform.md) —
  decision D2 in full, with its options, cost implications and risks.
* [ADR-005: Batch orchestration](../../../docs/adr/ADR-005-batch-orchestration.md)
  — why the batch chain invokes tasks in this cluster from a state machine.
* [The code documentation standard](../../../docs/CODE_DOCUMENTATION_STANDARD.md)
  — the convention this document is written to, including the four rationale
  labels and the paired what-and-why comment idiom.
* [The deploy runbook](../../../docs/runbooks/deploy.md) and
  [the teardown runbook](../../../docs/runbooks/teardown.md) — the exact operator
  command sequences.
