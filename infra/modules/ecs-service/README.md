# ECS service module

> **Purpose.** Document the reusable Fargate workload module that creates the
> task-level runtime for each CardDemo bounded context and for task-only
> workloads.
>
> **Source of truth.** AAP §0.2.1.1, §0.4.1.6, and §0.5.1.12, decision D2 in
> [ADR-002](../../../docs/adr/ADR-002-compute-platform.md), the sibling
> [`versions.tf`](versions.tf), [`variables.tf`](variables.tf),
> [`main.tf`](main.tf), and [`outputs.tf`](outputs.tf), plus the REFERENCE
> sources [`app/csd/CARDDEMO.CSD`](../../../app/csd/CARDDEMO.CSD) and
> [`app/cpy/COCOM01Y.cpy`](../../../app/cpy/COCOM01Y.cpy).

> If this README and the sibling `.tf` files disagree, the `.tf` files are
> authoritative. Correct this prose and regenerate the injected tables rather
> than making the implementation conform to stale documentation.


## 1. Purpose and documentation contract

This is the one reusable per-workload module in `infra/`: an environment root
supplies a bounded context's image, networking, configuration, permissions,
sizing, and retention, and the module turns those inputs into an ECS Fargate
task runtime. It can add a long-running, load-balanced service and autoscaling,
or stop at the task definition, roles, and log group for an orchestrated task.

Read this README to find out what the module composes, which inputs it
requires, and why each non-obvious argument is set the way it is; HCL has no
docstring construct, so this file is where that reasoning lives. AAP §0.2.1.6
lists a README in every Terraform module for exactly that reason. The
file-header comments and resource rationales in the `.tf` files, the
variable/output descriptions checked by [TFLint](../../.tflint.hcl), and the
generated contract checked by [terraform-docs](../../.terraform-docs.yml) carry
the machine-checkable part of the same obligation.

Assumptions: the module is consumed only through an environment root. It holds
no backend and no environment state of its own, so applying this directory
directly would bypass the composition layer that supplies every dependency.
The AWS path is additive; the REFERENCE implementation under `app/**` remains
unchanged and available.


## 2. Provenance: the CICS region decomposed

The measurements below come directly from
`app/csd/CARDDEMO.CSD:L1-L505`. The 505-line definition contains 8
`DEFINE FILE` stanzas in L1-L99, 17 `DEFINE MAPSET` stanzas in L100-L172,
18 `DEFINE PROGRAM` stanzas in L173-L305, 18 `DEFINE TRANSACTION` stanzas
in L306-L488, 2 `DEFINE LIBRARY` stanzas in L489-L498, and 1
`DEFINE TDQUEUE` stanza in L499-L505.

All 18 transaction stanzas repeat the same runtime template:
`ISOLATE(YES)`, `TASKDATAKEY(USER)`, `ACTION(BACKOUT)`, `PRIORITY(1)`,
`RESTART(NO)`, `PROFILE(DFHCICST)`, `TRANCLASS(DFHTCL00)`, `TWASIZE(0)`,
`DTIMOUT(NO)`, `CONFDATA(NO)`, `RESSEC(NO)`, and `CMDSEC(NO)` each occur
exactly 18 times. The 18 program stanzas likewise each carry
`EXECKEY(USER)` and `CONCURRENCY(QUASIRENT)`.

Refactoring Rationale: one repeated CICS template differing by transaction and
program name is evidence for one parameterized runtime module, not copied
Terraform per service. The service catalog groups those programs into eight
bounded contexts so they can be deployed and scaled independently.

| CICS attribute | Target expression | Category |
|---|---|---|
| `ISOLATE(YES)` | Each Fargate task is an isolated process and network boundary. | Refactoring Rationale: |
| `TASKDATAKEY(USER)` / `EXECKEY(USER)` | The application container runs as the non-root `container_user`; application work was not privileged in the baseline either. | Refactoring Rationale: |
| `ACTION(BACKOUT)` | Spring transaction boundaries remain inside the services; database transaction semantics are not owned by this infrastructure module. | Assumptions: |
| `RESTART(NO)` | ECS desired-count reconciliation and target-group health replacement add automatic process recovery; this is an improvement, not a literal port. | Refactoring Rationale: |
| `PRIORITY(1)` / `TRANCLASS(DFHTCL00)` | Each online service receives CPU target tracking. Step scaling was rejected because the uniform baseline attributes provide no per-transaction demand thresholds from which to derive steps. | Alternatives Considered: |
| `DTIMOUT(NO)` / `RUNAWAY(SYSTEM)` | Target-group probe intervals, timeouts, thresholds, and deregistration delay bound unhealthy routing and request draining. | Refactoring Rationale: |
| `LIBRARY(CARDDLIB)` at L489-L491 | `infra/modules/ecr` owns the image repository; this module consumes the selected image through `image_uri` and scopes pull access through `ecr_repository_arn`. | Refactoring Rationale: |

One source entry is deliberately not converted into a service.
`DEFINE TRANSACTION(CDV1)` at L388-L398 points to `PROGRAM(COCRDSEC)` at
L390, whose program definition is L211-L218, but no matching `COCRDSEC.cbl`
exists under `app/**`. AAP §0.5.2 records the pair with no target.

Assumptions: the dangling definition is preserved as provenance rather than
inventing a ninth bounded context for source code that does not exist.


## 3. Statelessness makes the module viable

The shared `01 CARDDEMO-COMMAREA` at
`app/cpy/COCOM01Y.cpy:L19-L44` carried navigation, identity, selection, and
re-entry state between terminal turns. The target decomposes it into four
mechanisms:

| COMMAREA fields | Target mechanism |
|---|---|
| `CDEMO-FROM-TRANID`, `CDEMO-FROM-PROGRAM`, `CDEMO-TO-TRANID`, `CDEMO-TO-PROGRAM`, `CDEMO-LAST-MAP`, `CDEMO-LAST-MAPSET` | Client-side router history |
| `CDEMO-USER-ID`, `CDEMO-USER-TYPE`, `CDEMO-USRTYP-ADMIN`, `CDEMO-USRTYP-USER` | Signed JWT identity and role claims |
| `CDEMO-CUST-ID`, `CDEMO-ACCT-ID`, `CDEMO-CARD-NUM` | REST path parameters |
| `CDEMO-PGM-CONTEXT`, `CDEMO-PGM-ENTER`, `CDEMO-PGM-REENTER` | Eliminated; a request handler has no terminal-turn re-entry state |

Refactoring Rationale: signed claims replace identity fields that the client
previously echoed back, so identity is authenticated rather than trusted as
session storage. Router state and selected resource identifiers move to the
request boundary, leaving no continuity that must survive on one server.

AAP §0.9.3 states the consequence: “all eight services are stateless with no
sticky sessions and no server-side session store, which is precisely what
makes horizontally-scaled Fargate tasks behind a load balancer a viable target
at all.”

Three module properties follow:

* The target group explicitly disables stickiness.
* Autoscaling may remove a task without losing session state.
* A rolling deployment may replace every task without transferring session
  state.

Assumptions: in-flight requests still receive the configured deregistration
delay; statelessness removes user-session affinity, not the need to drain active
connections.


## 4. What one instantiation provisions

The generated contract in [§7](#7-inputs-and-outputs) lists all 13 resources.
Their runtime responsibilities are:

| Shape | Provisioned responsibility |
|---|---|
| Always | A CloudWatch log group with caller-selected retention and optional customer-managed encryption |
| Always | An execution role and inline policy scoped to the selected ECR repository, this log group, exact Parameter Store and Secrets Manager references, and constrained KMS keys |
| Always | An application task role with a same-account permissions boundary; business-resource access arrives only through caller-selected policy inputs, exact SQS queue sets, or managed-policy attachments |
| Always | A Fargate task definition using `awsvpc`, Linux/X86_64, the selected CPU/memory pair, an unprivileged container user, `awslogs`, a read-only root, and one ephemeral volume per writable path |
| Online-service shape | An IP target group using HTTPS for traffic and health checks on `/actuator/health`, with stickiness disabled |
| Online-service shape | An ECS service using the `ECS` rolling controller, an explicit capacity-provider strategy, private application subnets, no public IP, and the pinned Fargate platform version |
| Online-service shape | An Application Auto Scaling target and CPU target-tracking policy |

Assumptions: the task role starts free of **business-resource** access, not
literally empty. Exact queue grants, a caller-owned business policy and optional
managed policies are separate because they have different owners and lifecycles.

Refactoring Rationale: this module composed an AWS Distro for OpenTelemetry
collector sidecar into every task, and that shape -- together with its four inputs,
its X-Ray export policy and its scratch volume -- has been **withdrawn**. The frozen
specification contains no collector, and the sidecar could not be delivered inside
the numbers the specification does state: pulling its image from a private
application subnet needed an eleventh ECR repository against the ten of section
0.4.1.6, because Amazon ECR Public is not served by the `ecr.api` and `ecr.dkr`
endpoints, and exporting its spans needed a ninth interface endpoint for `xray`
against the eight of section 0.4.1.9. What is kept for this concern is every
artifact the specification names: container logs in this module's own log group,
the `/actuator/prometheus` surface each service already exposes,
`common-lib`'s `MetricsConfig` common tags, and end-to-end request correlation
through `common-lib`'s `CorrelationIdFilter`. What is lost is span export to a
managed tracing backend; re-introducing it has to argue for its own endpoint or its
own egress, which is the argument that was previously skipped.

Trade-offs: `readonly_root_filesystem` is a fleet invariant and defaults to
`true`. Each path in `writable_mount_paths` becomes task-local ephemeral
storage, with `/tmp` supplied by default. Naming writable paths costs explicit
configuration for an image that needs another scratch directory, but avoids
leaving the entire image filesystem writable.

Configuration follows AAP §0.5.3.5: no service hard-codes an endpoint and no
endpoint is stored in `terraform.tfvars` as a secret. Plain settings are
allowlisted, while Parameter Store and Secrets Manager values are injected by
reference and the same exact resource identifiers scope the execution role.


## 5. Workload instances and task-only shapes

The eight bounded-context service deployables and their originating programs
are:

| Instance | Shape | Originating COBOL programs |
|---|---|---|
| `auth` | Online service | `COSGN00C`, `COUSR00C`, `COUSR01C`, `COUSR02C`, `COUSR03C` |
| `account` | Online service | `COACTVWC`, `COACTUPC`, `CBACT01C`, `CBACT03C`, `CBCUS01C`, `COACCT01` |
| `card` | Online service | `COCRDLIC`, `COCRDSLC`, `COCRDUPC`, `CBACT02C` |
| `transaction` | Online service | `COTRN00C`, `COTRN01C`, `COTRN02C`, `COBIL00C` |
| `reference` | Online service | `COTRTLIC`, `COTRTUPC`, `COBTUPDT`, `CODATE01`, `CSUTLDTC` |
| `batch` | Task only | `CBTRN01C`, `CBTRN02C`, `CBACT04C`, `CBEXPORT`, `CBIMPORT` |
| `authorization` | Online service | `COPAUS0C`, `COPAUS1C`, `COPAUS2C`, `COPAUA0C`, `CBPAUP0C`, `PAUDBLOD`, `PAUDBUNL`, `DBUNLDGS` |
| `reporting` | Online service | `CORPT00C`, `CBTRN03C`, `CBSTM03A`, `CBSTM03B` |

Each service has its own name, image, configuration inventory, and
least-privilege policy set. The current roots supply CPU, memory, desired count,
and retention as per-environment values shared by the service fleet rather than
inventing per-service topology.

Decision D2 assigns batch work to Step-Functions-invoked Fargate tasks.
AAP §0.4.1.7 specifies the synchronous `ecs:runTask.sync` integration and
container overrides; AAP §0.5.1.7 requires argument-driven jobs; and
AAP §0.4.1.6 assigns task-definition wiring to
`infra/modules/step-functions-batch`. The `batch` call therefore sets
`create_service = false`,
`attach_load_balancer = false`, and `enable_autoscaling = false`.
`service_name`, `service_arn`, `target_group_arn`, `target_group_name`,
`target_group_arn_suffix`, and `autoscaling_target_resource_id` are `null`;
the task definition, container name, both roles, and log group remain available
to `infra/modules/step-functions-batch`.

Alternatives Considered: a separate `ecs-task` module was rejected because it
would duplicate the task definition, both roles, logging and secret injection.
That duplication is the exact drift this reusable module is intended to prevent.

Trade-offs: the common module carries three shape booleans that the seven online
services leave enabled. The extra inputs are accepted because the resulting
task-only shape shares every invariant that should not diverge.

The environment roots also reuse this module for a ninth, task-only
`data-migration` workload. It is not a ninth bounded-context service; it is the
ETL image invoked by orchestration. The count relationship is therefore:

* 8 bounded-context service images and service-module instances;
* 1 additional `data-migration` image and task-only module instance;
* 1 `ui` image delivered through `infra/modules/cloudfront-spa`;
* 10 ECR repositories in total.

Assumptions: `services/common-lib` is the ninth Maven module but produces no
container image, so it creates neither an ECR repository nor an
`ecs-service` instance.


## 6. Usage

An online service call is composed in an environment root from sibling-module
outputs and root-owned policy/configuration maps:

```hcl
# WHAT: compose one long-running account-service task, target group, ECS service,
#       and autoscaling policy from environment-root contracts.
# WHY : Assumptions: the root is the only layer allowed to connect sibling module
#       outputs; the ecs-service module never reaches sideways into another module.
module "account_service" {
  source = "../../modules/ecs-service"

  service_name             = "account"
  environment              = var.environment
  cluster_arn              = module.ecs_cluster.cluster_arn
  cluster_name             = module.ecs_cluster.cluster_name
  vpc_id                   = module.network.vpc_id
  private_app_subnet_ids   = module.network.private_app_subnet_ids
  security_group_ids       = [module.network.app_security_group_id]
  image_uri                = "${module.ecr.repository_urls["account-service"]}@${var.image_digests["account-service"]}"
  ecr_repository_arn       = module.ecr.repository_arns["account-service"]
  permissions_boundary_arn = var.permissions_boundary_arn

  container_port        = module.network.app_container_port
  task_cpu              = var.ecs_task_cpu
  task_memory           = var.ecs_task_memory
  desired_count         = var.ecs_desired_count
  min_capacity          = var.ecs_desired_count
  max_capacity          = var.ecs_desired_count * 2
  log_retention_in_days = var.log_retention_days
  log_group_kms_key_arn = module.kms.s3_key_arn

  environment_variables   = local.environment_variables_by_workload["account"]
  ssm_parameter_arns      = local.runtime_parameter_arns_by_service["account"]
  secret_arns             = local.secret_sources_by_workload["account"]
  create_task_role_policy = true
  task_role_policy_json   = data.aws_iam_policy_document.account_runtime.json
}
```

The batch call uses the same runtime but suppresses service-only resources:

```hcl
# WHAT: register the batch task definition, roles, log group, and container
#       contract without creating a continuously running ECS service.
# WHY : Refactoring Rationale: Step Functions owns task invocation and passes
#       arguments through ContainerOverrides, so desired-count reconciliation,
#       a target group, and autoscaling would create idle infrastructure.
module "batch_task" {
  source = "../../modules/ecs-service"

  service_name             = "batch"
  container_name           = "batch"
  environment              = var.environment
  cluster_arn              = module.ecs_cluster.cluster_arn
  cluster_name             = module.ecs_cluster.cluster_name
  vpc_id                   = module.network.vpc_id
  private_app_subnet_ids   = module.network.private_app_subnet_ids
  security_group_ids       = [module.network.app_security_group_id]
  image_uri                = "${module.ecr.repository_urls["batch-service"]}@${var.image_digests["batch-service"]}"
  ecr_repository_arn       = module.ecr.repository_arns["batch-service"]
  permissions_boundary_arn = var.permissions_boundary_arn
  create_task_role_policy  = true
  task_role_policy_json    = data.aws_iam_policy_document.batch_runtime.json

  create_service       = false
  attach_load_balancer = false
  enable_autoscaling   = false
}
```

The module is never applied directly. Operators and CI reach it transitively
through `terraform -chdir=infra/envs/<env> ...`.


## 7. Inputs and outputs

The generated region represents all 56 inputs and all 17 outputs declared by
the sibling HCL.

`target_group_arn` is the hard service-routing contract: `infra/modules/alb`
attaches it to the listener rule that module owns. `container_name` and the
revision-qualified `task_definition_arn` are the hard run-task contracts:
`infra/modules/step-functions-batch` uses them for `ContainerOverrides` and
`ecs:RunTask`.

Assumptions: the region between the markers below is generated from
`versions.tf`, `variables.tf`, `main.tf`, and `outputs.tf`. Hand-editing a row
would be overwritten locally and rejected by the check-only CI drift gate.

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
| [aws_appautoscaling_policy.cpu](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/appautoscaling_policy) | resource |
| [aws_appautoscaling_target.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/appautoscaling_target) | resource |
| [aws_cloudwatch_log_group.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_log_group) | resource |
| [aws_ecs_service.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ecs_service) | resource |
| [aws_ecs_task_definition.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ecs_task_definition) | resource |
| [aws_iam_role.execution](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role) | resource |
| [aws_iam_role.task](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role) | resource |
| [aws_iam_role_policy.execution](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_iam_role_policy.task](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_iam_role_policy.task_online_write_gate](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_iam_role_policy.task_sqs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_iam_role_policy_attachment.task](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy_attachment) | resource |
| [aws_lb_target_group.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lb_target_group) | resource |
| [aws_caller_identity.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_iam_policy_document.execution](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.task_assume_role](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.task_online_write_gate](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.task_sqs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_partition.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/partition) | data source |
| [aws_region.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/region) | data source |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_cluster_arn"></a> [cluster\_arn](#input\_cluster\_arn) | ARN of the ECS Fargate cluster that will host this service, consumed by<br/>the `cluster` argument of aws\_ecs\_service. Comes from the ecs-cluster<br/>module's output; all nine instantiations in a root share one cluster. | `string` | n/a | yes |
| <a name="input_cluster_name"></a> [cluster\_name](#input\_cluster\_name) | Bare name -- not the ARN -- of the same ECS cluster that cluster\_arn<br/>identifies. Consumed only by the Application Auto Scaling target's<br/>resource\_id, which AWS requires in the literal form<br/>service/<cluster-name>/<service-name>. Comes from the ecs-cluster<br/>module's output alongside cluster\_arn; both are needed because the ECS<br/>and Application Auto Scaling APIs demand different forms of one<br/>identity. | `string` | n/a | yes |
| <a name="input_ecr_repository_arn"></a> [ecr\_repository\_arn](#input\_ecr\_repository\_arn) | ARN of the single ECR repository holding this service's images. Used as<br/>the Resource of the task execution role's image-pull statements, so that<br/>permission names one repository rather than all of them. Comes from the<br/>ecr module's output, which publishes one ARN per repository. | `string` | n/a | yes |
| <a name="input_environment"></a> [environment](#input\_environment) | Environment discriminator, either dev or prod. Appended to every<br/>composed resource name so the two roots can coexist in one account<br/>without colliding, and emitted as a tag value for cost attribution. Each<br/>environment root passes its own name as a literal. | `string` | n/a | yes |
| <a name="input_image_uri"></a> [image\_uri](#input\_image\_uri) | Fully qualified container image reference including its tag or digest,<br/>used verbatim as the `image` field of the container definition. Produced<br/>by the deploy workflow, which builds from the service's Dockerfile,<br/>pushes to the repository named by ecr\_repository\_arn and identifies the<br/>result by commit SHA. | `string` | n/a | yes |
| <a name="input_permissions_boundary_arn"></a> [permissions\_boundary\_arn](#input\_permissions\_boundary\_arn) | Same-account customer-managed IAM policy ARN used as the permissions boundary on both ECS roles. Required so no inline capability assembled by this module can exceed the account's deployment boundary. | `string` | n/a | yes |
| <a name="input_private_app_subnet_ids"></a> [private\_app\_subnet\_ids](#input\_private\_app\_subnet\_ids) | Subnet identifiers of the private application tier, one per availability<br/>zone, consumed by the awsvpc network\_configuration of aws\_ecs\_service.<br/>Spreading them across three zones is what lets the service survive the<br/>loss of one. Comes from the network module's output; the public and<br/>isolated-data tiers it also publishes are not interchangeable with this<br/>one. | `list(string)` | n/a | yes |
| <a name="input_security_group_ids"></a> [security\_group\_ids](#input\_security\_group\_ids) | Security groups to attach to every task network interface, consumed by<br/>the awsvpc network\_configuration of aws\_ecs\_service. Canonically the<br/>single application-tier group from the network module's output, which<br/>permits load-balancer ingress on the container port and egress to the<br/>database and the VPC interface endpoints. Additional groups may be<br/>appended by the caller. | `list(string)` | n/a | yes |
| <a name="input_service_name"></a> [service\_name](#input\_service\_name) | Bounded-context or task-only workload short name for this instance: one of<br/>auth, account, card, transaction, reference, batch, authorization,<br/>reporting or data-migration.<br/>Composed with name\_prefix and environment into the ECS service name, the<br/>task-definition family, the CloudWatch log-group name, both IAM role<br/>names and the ALB target-group name, and used as the service segment of<br/>the Application Auto Scaling resource\_id. The environment root supplies<br/>it as a literal; it is not read from another module's output. | `string` | n/a | yes |
| <a name="input_vpc_id"></a> [vpc\_id](#input\_vpc\_id) | Identifier of the three-availability-zone VPC that owns the subnets in<br/>private\_app\_subnet\_ids. Consumed by aws\_lb\_target\_group, which cannot<br/>register IP targets without knowing the VPC they live in. Comes from the<br/>network module's output. | `string` | n/a | yes |
| <a name="input_allow_service_managed_log_encryption"></a> [allow\_service\_managed\_log\_encryption](#input\_allow\_service\_managed\_log\_encryption) | Explicit opt-out permitting the service log group to fall back to CloudWatch Logs service-managed encryption when log\_group\_kms\_key\_arn is null. Intended only for planning this module in isolation without the kms module; neither environment root sets it. | `bool` | `false` | no |
| <a name="input_attach_load_balancer"></a> [attach\_load\_balancer](#input\_attach\_load\_balancer) | Whether to create an ALB target group for this service and register the<br/>service with it. True for the seven online services the internal load<br/>balancer fronts. Set false for the TWO task-only workloads, batch and<br/>data-migration, which Step Functions invokes rather than anything reaching<br/>them over HTTP; that also suppresses the health-check grace period and turns<br/>the target-group output null. | `bool` | `true` | no |
| <a name="input_autoscaling_scale_in_cooldown"></a> [autoscaling\_scale\_in\_cooldown](#input\_autoscaling\_scale\_in\_cooldown) | Seconds the target-tracking policy waits after removing tasks before it<br/>may remove more. Deliberately longer than the scale-out cooldown, because<br/>over-removing capacity is paid for in failed requests while over-adding it<br/>is paid for in task time. | `number` | `300` | no |
| <a name="input_autoscaling_scale_out_cooldown"></a> [autoscaling\_scale\_out\_cooldown](#input\_autoscaling\_scale\_out\_cooldown) | Seconds the target-tracking policy waits after adding tasks before it may<br/>add more. Shorter than the scale-in cooldown, but non-zero so each<br/>decision is taken on a utilisation reading that already reflects the<br/>previous addition. | `number` | `60` | no |
| <a name="input_autoscaling_target_cpu_utilization"></a> [autoscaling\_target\_cpu\_utilization](#input\_autoscaling\_target\_cpu\_utilization) | Average CPU utilisation percentage across the service's tasks that the<br/>target-tracking policy tries to maintain, adding tasks above it and<br/>removing them below. One number is the whole input; the policy derives its<br/>own thresholds from it. | `number` | `70` | no |
| <a name="input_capacity_provider_strategy"></a> [capacity\_provider\_strategy](#input\_capacity\_provider\_strategy) | Per-service ECS capacity-provider strategy. Defaults to one on-demand<br/>FARGATE entry, replacing the former launch\_type = "FARGATE" with the<br/>capacity-provider mechanism. A supplied strategy replaces the cluster<br/>default; use FARGATE\_SPOT only as an explicit availability/cost decision. | <pre>list(object({<br/>    capacity_provider = string<br/>    weight            = optional(number, 1)<br/>    base              = optional(number, 0)<br/>  }))</pre> | <pre>[<br/>  {<br/>    "base": 1,<br/>    "capacity_provider": "FARGATE",<br/>    "weight": 1<br/>  }<br/>]</pre> | no |
| <a name="input_container_health_check_command"></a> [container\_health\_check\_command](#input\_container\_health\_check\_command) | The exact command ECS runs inside the application container to judge its<br/>health, as the argv list the container's own entry point can execute. ECS<br/>monitors ONLY this; it never reads the image's HEALTHCHECK instruction, so a<br/>workload that supplies null has no container-level health signal and is judged<br/>by its target group if it has one and by its exit status otherwise. Supply the<br/>same command that workload's Dockerfile declares, so the two cannot disagree. | `list(string)` | `null` | no |
| <a name="input_container_name"></a> [container\_name](#input\_container\_name) | Name of the single container inside aws\_ecs\_task\_definition's container<br/>definitions, and the name the target group's port mapping refers to.<br/>Leave null to let the module derive one from service\_name, which is what<br/>the seven long-running services do. Set it explicitly for the batch<br/>instance, whose container name is also referenced by the<br/>step-functions-batch module's container overrides, so both modules must<br/>agree on one literal. | `string` | `null` | no |
| <a name="input_container_port"></a> [container\_port](#input\_container\_port) | Container port exposed by the task, also used as the target-group port<br/>and the health-check port. Must match both the port the service's Spring<br/>Boot process binds and the port the application security group admits<br/>from the load balancer, so the environment root passes the network<br/>module's app\_container\_port here rather than a second literal. | `number` | `8080` | no |
| <a name="input_container_user"></a> [container\_user](#input\_container\_user) | Value of the container definition's user field, given as a uid, a uid:gid<br/>pair or a user name. Defaults to the numeric uid the service Dockerfiles<br/>create their unprivileged account with, so the process never runs as<br/>root. Change it only together with the image that has to provide the<br/>account. | `string` | `"10001"` | no |
| <a name="input_create_online_write_gate_policy"></a> [create\_online\_write\_gate\_policy](#input\_create\_online\_write\_gate\_policy) | Whether to attach the inline task-role policy that reads the environment's<br/>online-writes flag. Set from root-owned topology -- true for the workloads<br/>that serve requests and honour a quiesce, false for the batch and<br/>data-migration workloads -- rather than inferred from<br/>online\_write\_gate\_parameter\_arn, whose value is unknown until the parameter<br/>exists. Must travel with that ARN: the flag's name without permission to read<br/>it denies every gated request, and permission without the name grants<br/>something the task cannot use. | `bool` | `false` | no |
| <a name="input_create_service"></a> [create\_service](#input\_create\_service) | Whether to create a long-running ECS service around the task definition.<br/>True for the seven online services. Set false for the TWO task-only<br/>workloads, batch and data-migration, whose tasks Step Functions starts one at<br/>a time; that leaves the task definition, both IAM roles and the log group in<br/>place for step-functions-batch to reference, and suppresses the service, the<br/>autoscaling target and the outputs describing them. It also selects the<br/>metrics path: a task-only workload has nothing to scrape, so the module<br/>enables Micrometer's OTLP push instead. | `bool` | `true` | no |
| <a name="input_create_task_role_policy"></a> [create\_task\_role\_policy](#input\_create\_task\_role\_policy) | Whether to create the service-specific inline task-role policy resource. Set from root-owned topology rather than inferred from task\_role\_policy\_json, whose module-output-derived value may remain unknown until apply. | `bool` | `false` | no |
| <a name="input_deployment_maximum_percent"></a> [deployment\_maximum\_percent](#input\_deployment\_maximum\_percent) | Maximum percentage of desired\_count ECS may run at once during a<br/>deployment. At 200 a full replacement set can start before the outgoing<br/>set is stopped, which is what allows deployment\_minimum\_healthy\_percent<br/>to stay at 100. | `number` | `200` | no |
| <a name="input_deployment_minimum_healthy_percent"></a> [deployment\_minimum\_healthy\_percent](#input\_deployment\_minimum\_healthy\_percent) | Minimum percentage of desired\_count that must stay RUNNING and healthy<br/>while a deployment is in progress. At 100 the rolling deployment never<br/>reduces serving capacity: replacements come up before their predecessors<br/>go down. | `number` | `100` | no |
| <a name="input_deregistration_delay"></a> [deregistration\_delay](#input\_deregistration\_delay) | Seconds the load balancer waits for in-flight requests to finish before<br/>completing deregistration of a task. Only in-flight requests are at<br/>stake, because the services keep no server-side session state, so this<br/>does not have to cover a user's think time. | `number` | `30` | no |
| <a name="input_desired_count"></a> [desired\_count](#input\_desired\_count) | Task count the ECS service is created with. Only the initial value: the<br/>module stops tracking it afterwards so Application Auto Scaling can own<br/>the running count without every later plan proposing to undo it. Use<br/>min\_capacity to raise the floor the service is held at. Expected to<br/>differ between the dev and prod roots. | `number` | `2` | no |
| <a name="input_enable_autoscaling"></a> [enable\_autoscaling](#input\_enable\_autoscaling) | Whether to register an Application Auto Scaling target for the service<br/>and attach a CPU target-tracking policy to it. Required whenever<br/>create\_service is true so Application Auto Scaling is the sole runtime<br/>owner of desired\_count; the batch instance leaves both false. | `bool` | `true` | no |
| <a name="input_enable_deployment_circuit_breaker"></a> [enable\_deployment\_circuit\_breaker](#input\_enable\_deployment\_circuit\_breaker) | Whether ECS aborts a rolling deployment whose tasks never reach a steady<br/>state and restores the previous task definition. Operates entirely within<br/>the rolling controller: no second target group, no alternate task set and<br/>no weighted traffic shifting are involved. | `bool` | `true` | no |
| <a name="input_environment_variables"></a> [environment\_variables](#input\_environment\_variables) | Non-secret environment variables for the container, as a map of name to<br/>literal value: the active Spring profile and similar plain settings. Values<br/>appear in clear text in the task definition, in plan output and in state, so<br/>anything sensitive belongs in ssm\_parameter\_arns or secret\_sources instead --<br/>and a name that reads as a secret is refused here rather than trusted.<br/>Accepted keys are upper-case, begin with one of the namespaces the CardDemo<br/>services read, and do not end in a secret-bearing word. | `map(string)` | `{}` | no |
| <a name="input_execution_secret_kms_key_arns"></a> [execution\_secret\_kms\_key\_arns](#input\_execution\_secret\_kms\_key\_arns) | Exact KMS key ARNs protecting the Secrets Manager entries in secret\_arns.<br/>Used only by the ECS EXECUTION role, and only through a statement carrying<br/>the Secrets Manager ViaService and SecretARN encryption-context conditions,<br/>so the key cannot be used against unrelated ciphertext. A key the<br/>APPLICATION itself must use -- to read an object, decrypt a queue message or<br/>open a database connection -- belongs in a statement of<br/>task\_role\_policy\_json, which is attached to the task role; this module<br/>composes no key permission for that role. | `list(string)` | `[]` | no |
| <a name="input_health_check_grace_period_seconds"></a> [health\_check\_grace\_period\_seconds](#input\_health\_check\_grace\_period\_seconds) | Seconds after a task starts during which ECS disregards failing<br/>load-balancer health checks, giving the Spring context time to finish<br/>refreshing before the first probe is allowed to count. Applies only when<br/>attach\_load\_balancer is true; the module resolves it to null otherwise,<br/>because AWS rejects the argument on a service with no load balancer. | `number` | `60` | no |
| <a name="input_health_check_interval"></a> [health\_check\_interval](#input\_health\_check\_interval) | Seconds between target-group health-check probes of each registered task.<br/>Read together with unhealthy\_threshold, the number of consecutive<br/>failures that removes a task from rotation, and with<br/>health\_check\_timeout, which AWS requires to be strictly smaller than this<br/>value. | `number` | `30` | no |
| <a name="input_health_check_matcher"></a> [health\_check\_matcher](#input\_health\_check\_matcher) | Status codes the target group accepts as a healthy response, given as a<br/>single code, a range or a comma-separated list. Defaults to the one code<br/>the Actuator health endpoint returns when the service reports UP. | `string` | `"200"` | no |
| <a name="input_health_check_path"></a> [health\_check\_path](#input\_health\_check\_path) | Path the target-group health check requests on container\_port, over HTTPS<br/>like the traffic it stands in for. Defaults to the Spring Boot Actuator<br/>health endpoint every service exposes. Consumed only by the target group<br/>this module creates; the listener rule that routes traffic to that group is<br/>the alb module's to define. | `string` | `"/actuator/health"` | no |
| <a name="input_health_check_timeout"></a> [health\_check\_timeout](#input\_health\_check\_timeout) | Seconds a single health-check probe may take before AWS records it as a<br/>failure. Must be strictly smaller than health\_check\_interval, so the two<br/>are changed together. | `number` | `5` | no |
| <a name="input_healthy_threshold"></a> [healthy\_threshold](#input\_healthy\_threshold) | Consecutive successful probes required before the target group begins<br/>sending requests to a newly registered task. Deliberately larger than<br/>unhealthy\_threshold, because a premature admission is paid for in failed<br/>requests and a premature removal only in capacity. | `number` | `3` | no |
| <a name="input_log_group_kms_key_arn"></a> [log\_group\_kms\_key\_arn](#input\_log\_group\_kms\_key\_arn) | ARN of a customer-managed KMS key to encrypt the service's log group with.<br/>Required unless allow\_service\_managed\_log\_encryption is explicitly set true,<br/>which is the opt-out reserved for planning this module in isolation without<br/>the kms module. Both environment roots pass the KMS module's S3 data-domain<br/>key. | `string` | `null` | no |
| <a name="input_log_retention_in_days"></a> [log\_retention\_in\_days](#input\_log\_retention\_in\_days) | Retention period for the service's CloudWatch log group, in days, or 0 to<br/>keep events indefinitely. Must be one of the values CloudWatch Logs<br/>accepts. Expected to differ between the dev and prod roots, which are<br/>otherwise identical in topology. | `number` | `30` | no |
| <a name="input_max_capacity"></a> [max\_capacity](#input\_max\_capacity) | Highest task count the scaling policy may grow the service to. Bounds the<br/>blast radius of a scale-out driven by a fault rather than by demand; raise<br/>it in the root when real demand needs more than the default headroom above<br/>min\_capacity. | `number` | `6` | no |
| <a name="input_min_capacity"></a> [min\_capacity](#input\_min\_capacity) | Lowest task count the scaling policy may reduce the service to. Held at<br/>the same value as the desired\_count default so scale-in cannot drop the<br/>service below the multi-zone redundancy it starts with. | `number` | `2` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Leading token shared by every resource name this module composes -- the<br/>ECS service, the task-definition family, the log group, both IAM roles<br/>and the readable stem of the ALB target group. Kept short so the common<br/>resource name remains legible before main.tf appends the target group's<br/>replacement hash. Defaulted rather than required so a root states it only<br/>when it wants something other than the package-wide prefix. | `string` | `"carddemo"` | no |
| <a name="input_online_write_gate_parameter_arn"></a> [online\_write\_gate\_parameter\_arn](#input\_online\_write\_gate\_parameter\_arn) | ARN of the single Parameter Store entry carrying the environment's<br/>online-writes flag, which every write-gated service reads at request time to<br/>decide whether mutating requests are currently accepted. When set, the module<br/>attaches an inline task-role policy granting exactly ssm:GetParameter on this<br/>one ARN and nothing else. Null for a workload that is not write-gated -- the<br/>batch workload in particular must keep writing while online writes are<br/>quiesced, because it is the workload the quiesce exists to protect. | `string` | `null` | no |
| <a name="input_platform_version"></a> [platform\_version](#input\_platform\_version) | Value of the platform\_version argument of aws\_ecs\_service, selecting the<br/>Fargate platform the tasks run on. Pinned to 1.4.0, the current Fargate<br/>Linux platform version, so a platform change is a reviewed edit rather than<br/>a silent substitution at task launch. Raise it deliberately when AWS ships a<br/>successor. Supplied by the caller as a literal, not from another module's<br/>output. | `string` | `"1.4.0"` | no |
| <a name="input_readonly_root_filesystem"></a> [readonly\_root\_filesystem](#input\_readonly\_root\_filesystem) | Sets readonlyRootFilesystem on the container definition. Defaults to true,<br/>which is the intended posture for all nine instantiations: writable paths<br/>the JVM needs are supplied as Fargate ephemeral volumes through<br/>writable\_mount\_paths rather than by leaving the whole root filesystem<br/>writable. The module rejects false because this is a fleet-wide invariant. | `bool` | `true` | no |
| <a name="input_secret_arns"></a> [secret\_arns](#input\_secret\_arns) | Map of container environment-variable name to an object with `value_from`,<br/>the ECS selector that may name one JSON key, and `resource_arn`, the base<br/>Secrets Manager ARN IAM authorizes. This separates runtime field selection<br/>from policy scope: a JSON-key selector is not a valid IAM Resource and a<br/>base ARN injects the whole credential document rather than one field. | <pre>map(object({<br/>    value_from   = string<br/>    resource_arn = string<br/>  }))</pre> | `{}` | no |
| <a name="input_sqs_kms_key_arn"></a> [sqs\_kms\_key\_arn](#input\_sqs\_kms\_key\_arn) | ARN of the customer-managed KMS key that encrypts the queues in sqs\_send\_queue\_arns and sqs\_receive\_queue\_arns. Required whenever those queues use SSE-KMS, because the queue permission alone does not authorise the key use the service performs on the caller's behalf. Null for a service that uses no queue. | `string` | `null` | no |
| <a name="input_sqs_receive_queue_arns"></a> [sqs\_receive\_queue\_arns](#input\_sqs\_receive\_queue\_arns) | Exact environment-owned SQS queue ARNs this application's task role may<br/>receive, delete and change visibility on. Keep empty for services that<br/>consume no queue. The module grants no wildcard SQS action or resource. | `set(string)` | `[]` | no |
| <a name="input_sqs_send_queue_arns"></a> [sqs\_send\_queue\_arns](#input\_sqs\_send\_queue\_arns) | Exact environment-owned SQS queue ARNs this application's task role may<br/>send to. Used as the Resource list of a dedicated sqs:SendMessage statement.<br/>Keep empty for services that publish no messages; never derive it from an<br/>inbound replyToQueueUrl. | `set(string)` | `[]` | no |
| <a name="input_ssm_parameter_arns"></a> [ssm\_parameter\_arns](#input\_ssm\_parameter\_arns) | Map of container environment-variable name to the ARN of the SSM Parameter<br/>Store parameter holding its value. ECS resolves each one when the task<br/>starts, so the value itself is never stored in the task definition. The<br/>same ARNs form the Resource list of the execution role's parameter-read<br/>statement. Supplied from the outputs of whichever modules published the<br/>parameters. | `map(string)` | `{}` | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Extra tags to merge onto every resource this module creates, for tagging<br/>that applies to one service rather than to the whole package. Combines with<br/>the tags the calling root applies through the provider's default\_tags; a<br/>key given here takes precedence over the same key there. | `map(string)` | `{}` | no |
| <a name="input_target_protocol"></a> [target\_protocol](#input\_target\_protocol) | Protocol the target group and its health check use to reach the task.<br/>Fixed at HTTPS: the load-balancer-to-task hop carries bearer tokens and<br/>account data across the private application subnets, so it is encrypted like<br/>the viewer hop in front of it. Declared as an input only so that an attempt<br/>to lower it is refused with a reason rather than silently accepted. | `string` | `"HTTPS"` | no |
| <a name="input_task_cpu"></a> [task\_cpu](#input\_task\_cpu) | Task-level CPU units for aws\_ecs\_task\_definition, where 1024 units is one<br/>vCPU. Must be one of the seven sizes this module admits -- 256, 512, 1024,<br/>2048, 4096, 8192 or 16384 -- and must pair with a task\_memory value Fargate<br/>allows alongside it. That is a deliberate SUBSET of what Fargate accepts:<br/>Fargate also offers a 32 vCPU tier, which this module excludes as a cost<br/>bound because it is far larger than any CardDemo service needs. Expected to<br/>differ between the dev and prod roots, which is one of the few axes on which<br/>those two roots are permitted to diverge. | `number` | `1024` | no |
| <a name="input_task_memory"></a> [task\_memory](#input\_task\_memory) | Task-level memory in MiB for aws\_ecs\_task\_definition. Must be a value<br/>Fargate permits alongside the chosen task\_cpu, so the two are always<br/>changed together. The matrix below covers the seven CPU sizes this module<br/>admits; it is a deliberate subset of Fargate's, which also has a 32 vCPU<br/>row. Expected to differ between the dev and prod roots for the same reason<br/>task\_cpu does. | `number` | `2048` | no |
| <a name="input_task_role_managed_policy_arns"></a> [task\_role\_managed\_policy\_arns](#input\_task\_role\_managed\_policy\_arns) | ARNs of existing managed policies to attach to the task role in addition to<br/>task\_role\_policy\_json. For permission sets a root already owns and shares<br/>across services; the role's effective permissions are the union of these<br/>and the inline document. | `list(string)` | `[]` | no |
| <a name="input_task_role_policy_json"></a> [task\_role\_policy\_json](#input\_task\_role\_policy\_json) | Complete IAM policy document, as JSON, granting this one service the AWS<br/>API permissions it needs at run time. Attached to the task role, which the<br/>module otherwise leaves free of business-resource access. Leave null for a<br/>service that needs none, in which case the role carries no inline policy at<br/>all. This is the TASK role used by<br/>the application, not the execution role the ECS agent uses to pull the image<br/>and read parameters. | `string` | `null` | no |
| <a name="input_unhealthy_threshold"></a> [unhealthy\_threshold](#input\_unhealthy\_threshold) | Consecutive failed probes after which the target group deregisters a task<br/>and ECS replaces it. Together with health\_check\_interval this is what<br/>bounds how long a wedged task can keep receiving requests. | `number` | `2` | no |
| <a name="input_writable_mount_paths"></a> [writable\_mount\_paths](#input\_writable\_mount\_paths) | Absolute container paths to keep writable when readonly\_root\_filesystem is<br/>true, each backed by its own Fargate ephemeral volume that is encrypted at<br/>rest and destroyed with the task. Defaults to the single temporary directory<br/>the JVM writes to, which is all the service images require. Must be<br/>non-empty whenever readonly\_root\_filesystem is true. | `list(string)` | <pre>[<br/>  "/tmp"<br/>]</pre> | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_autoscaling_target_resource_id"></a> [autoscaling\_target\_resource\_id](#output\_autoscaling\_target\_resource\_id) | Composite identifier string of the registered Application Auto Scaling<br/>scalable target, in the literal form<br/>service/<cluster-name>/<service-name>, or null when either<br/>var.create\_service or var.enable\_autoscaling is false -- the batch<br/>instantiation sets both. Consumed by an environment root attaching a<br/>further scaling policy to the same target, a memory-based one alongside the<br/>CPU target-tracking policy this module already creates, for instance: an<br/>aws\_appautoscaling\_policy must repeat this exact resource\_id, and<br/>re-deriving it at the call site would mean recomposing a string this module<br/>has already resolved. |
| <a name="output_container_name"></a> [container\_name](#output\_container\_name) | Name string of the single container inside the task definition, already<br/>resolved -- it is var.container\_name where a caller set one and<br/>var.service\_name otherwise, so a consumer never has to know which<br/>happened. Consumed by infra/modules/step-functions-batch, whose run-task<br/>container overrides address this container by name in order to pass each<br/>batch step its arguments. Never null. |
| <a name="output_container_port"></a> [container\_port](#output\_container\_port) | Port number -- a number, not a string -- that the container listens on and<br/>that the target group this module creates forwards to. Published so the<br/>environment's `ecs_workloads` output records the port each workload was<br/>registered on; infra/modules/alb does NOT consume it and declares no<br/>container-port input, because the target group lives here and the listener<br/>rules there forward to the group rather than to a port. Both roots supply<br/>the value in the other direction, from infra/modules/network's single<br/>app\_container\_port. Never null: variables.tf supplies a default and rejects<br/>any value outside 1024-65535, since a privileged port cannot be bound by the<br/>unprivileged container user this module runs as. |
| <a name="output_execution_role_arn"></a> [execution\_role\_arn](#output\_execution\_role\_arn) | ARN string of the IAM role ECS ITSELF assumes, before the container<br/>exists, to pull the image from Amazon ECR, resolve the configured<br/>Parameter Store parameters and Secrets Manager secrets, and open the log<br/>stream. This is not the role the application code runs as -- that is<br/>task\_role\_arn above, and conflating the two is the classic ECS IAM<br/>mistake, because a permission the application needs granted here is never<br/>seen by the application. Consumed by an environment root or an audit<br/>reviewer that needs to name the role; main.tf already grants it inline<br/>everything this module requires of it. Never null. |
| <a name="output_execution_role_name"></a> [execution\_role\_name](#output\_execution\_role\_name) | Name string -- not the ARN -- of the same task execution role, published<br/>for the same name-versus-ARN reason given at task\_role\_name above.<br/>Consumed by a root attaching an additional policy to the execution role.<br/>Never null. |
| <a name="output_log_group_arn"></a> [log\_group\_arn](#output\_log\_group\_arn) | ARN string of the same log group, normalised to carry no trailing ":*"<br/>suffix. Consumed wherever an ARN rather than a name is required -- a<br/>CloudWatch Logs resource policy, a subscription-filter destination, or an<br/>IAM statement scoping logs:PutLogEvents -- while the awslogs driver and<br/>observability's metric filters take the name form above. A consumer<br/>scoping to the log STREAMS inside the group appends ":*" to this value<br/>itself. Never null, for the same reason as the name. |
| <a name="output_log_group_name"></a> [log\_group\_name](#output\_log\_group\_name) | Name string of the CloudWatch Logs group every task in this service writes<br/>to, in the form /aws/ecs/<name\_prefix>-<service\_name>-<environment>.<br/>Reaches the environment's `ecs_workloads` output, which publishes this whole<br/>object per workload, and is read from there by an operator tailing one<br/>service during the batch window. No sibling MODULE consumes it: both roots<br/>build this service's dashboard and alarm set inside<br/>infra/modules/observability from the cluster name, the target-group ARN<br/>suffix and the metric dimensions, and pass this module no log-group name at<br/>all. Never null: the log group is created for all nine instantiations,<br/>including the two -- batch and data-migration -- that have a task definition<br/>and no service. |
| <a name="output_service_arn"></a> [service\_arn](#output\_service\_arn) | ARN string of the same ECS service, or null under exactly the same<br/>condition as service\_name. Consumed where the service must be identified<br/>unambiguously rather than merely described: an IAM statement scoping an<br/>action to this one service, a deployment audit record, or the runbook step<br/>that brackets the batch window with the SSM read-only flag and has to<br/>record which services it quiesced and resumed. |
| <a name="output_service_name"></a> [service\_name](#output\_service\_name) | Name string of the long-running ECS service, or null when<br/>var.create\_service is false -- the batch and data-migration instantiations,<br/>whose tasks Step Functions starts one at a time rather than a service<br/>holding a desired count. Reaches the environment's `ecs_workloads` output<br/>and is read from there by an operator running a describe-services or<br/>update-service command against one service. No sibling MODULE consumes it:<br/>infra/modules/observability alarms this fleet through the ALB target groups<br/>and the cluster-level AWS/ECS metrics and is passed no service name. |
| <a name="output_target_group_arn"></a> [target\_group\_arn](#output\_target\_group\_arn) | ARN string of the load-balancer target group this service's tasks register<br/>into, or null. This is the module's PRIMARY CROSS-MODULE CONTRACT:<br/>infra/modules/alb owns the load balancer, its listener and the per-service<br/>listener rules, and attaches this ARN as one rule's forward target. That<br/>boundary is deliberate and this output is the single point at which the two<br/>modules meet -- this module owns the target group, alb owns everything in<br/>front of it -- so renaming this output breaks infra/modules/alb, which<br/>resolves it by name at its own call site. Null when either<br/>var.create\_service or var.attach\_load\_balancer is false, which is the batch<br/>instantiation: Step Functions starts its tasks directly, nothing reaches<br/>them over HTTP, and there is no listener rule to attach. |
| <a name="output_target_group_arn_suffix"></a> [target\_group\_arn\_suffix](#output\_target\_group\_arn\_suffix) | Trailing portion of the target-group ARN, in the form<br/>targetgroup/<name>/<id>, or null under the same condition as<br/>target\_group\_arn. Consumed by infra/modules/observability: the TargetGroup<br/>dimension on the AWS/ApplicationELB metrics -- healthy host count,<br/>per-target response time, the HTTP 5xx counts -- takes this suffix and not<br/>the full ARN, so a dashboard or alarm built from the ARN matches nothing<br/>and reports nothing. Published separately for that reason alone. |
| <a name="output_target_group_name"></a> [target\_group\_name](#output\_target\_group\_name) | Name string of the same target group, or null under exactly the same<br/>condition as target\_group\_arn. At most 32 characters: a readable service<br/>stem followed by the stable replacement hash that lets create-before-destroy<br/>provision a new group before the old name is released.<br/>Consumed where a human-readable identifier is wanted rather than an ARN: an<br/>operator locating the group in the console, or a runbook step naming it.<br/>NOT the value a CloudWatch TargetGroup dimension takes -- that is<br/>target\_group\_arn\_suffix below. |
| <a name="output_task_definition_arn"></a> [task\_definition\_arn](#output\_task\_definition\_arn) | Revision-qualified ARN string of the task definition, so the value changes<br/>every time a new revision is registered. Consumed by<br/>infra/modules/step-functions-batch, whose responsibility includes<br/>task-definition wiring: its state machine starts a task through the<br/>synchronous run-task integration, and its execution role scopes<br/>ecs:RunTask to this ARN alongside ecs:StopTask, ecs:DescribeTasks and<br/>iam:PassRole. Never null -- the task definition is created for all nine<br/>instantiations, including the two -- batch and data-migration -- that have a<br/>task definition and no service. |
| <a name="output_task_definition_family"></a> [task\_definition\_family](#output\_task\_definition\_family) | Family name string of the task definition, carrying no revision suffix.<br/>Consumed by a caller that wants ECS to resolve the family's latest active<br/>revision at run time instead of the revision this apply registered -- a<br/>Step Functions state that should pick up a redeployed image without a<br/>Terraform apply, for instance. Never null. |
| <a name="output_task_definition_family_arn"></a> [task\_definition\_family\_arn](#output\_task\_definition\_family\_arn) | Revisionless task-definition family ARN assembled from the module's provider-resolved partition, Region and account identity. Step Functions consumes this form so workflow creation does not depend on the reporting task definition that later reads the workflow ARN from Parameter Store. |
| <a name="output_task_role_arn"></a> [task\_role\_arn](#output\_task\_role\_arn) | ARN string of the IAM role the APPLICATION assumes at run time, as<br/>distinct from execution\_role\_arn below, which ECS assumes in order to<br/>start the task. This ARN is the identity least privilege is expressed<br/>against, and both roots read it: infra/modules/step-functions-batch takes<br/>the batch, data-migration and reporting values as the Resource of its one<br/>iam:PassRole statement, and a resource-owning module names it in a resource<br/>policy where a grant belongs with the resource rather than with the<br/>workload. The role is not empty when it arrives: main.tf attaches the<br/>caller's task\_role\_policy\_json plus two module-composed statements -- the<br/>exact-queue actions and the one-parameter write-gate read -- so what the<br/>service may reach is those three things and nothing besides. Never null: both<br/>roles are<br/>created for all nine instantiations. |
| <a name="output_task_role_name"></a> [task\_role\_name](#output\_task\_role\_name) | Name string -- not the ARN -- of the same application task role. Consumed<br/>by an environment root that attaches a further policy to the role after<br/>this module returns, since the Terraform resources that attach a policy<br/>take a role name while the resource policies that grant to a role take the<br/>ARN above. Never null. |
<!-- END_TF_DOCS -->


## 8. Deployment strategy: rolling only

AAP §0.2.2 is explicit:

> “Blue-green and canary deployment. Rolling ECS service deployment only.”

The ECS service uses the native `ECS` deployment controller with a minimum
healthy percentage of 100 and a maximum percentage of 200 by default. A full
replacement set can become healthy before outgoing tasks stop, within the one
target group.

Alternatives Considered: blue-green through a `CODE_DEPLOY` controller was
rejected because it requires a second target group, a CodeDeploy application,
a deployment group, and an AppSpec traffic-shifting configuration. Canary
deployment uses the same CodeDeploy traffic-shifting machinery and therefore
adds the same resource family. Neither strategy is represented in this module.

Roll forward means updating the root's image reference and applying a reviewed
environment plan; ECS performs a rolling task replacement. When enabled, the
deployment circuit breaker returns a failed rollout to the last known-good task
set.

Assumptions: the circuit breaker is part of the rolling controller, not
blue-green under another name. It uses no second target group, alternate task
set, or weighted traffic shift.

The AAP uses `terraform -chdir=infra/envs/<env> destroy` as shorthand for a
full-environment rollback. That is an operator teardown, not a service-revision
rollback and never an `infra-ci` step. The
[teardown runbook](../../../docs/runbooks/teardown.md) uses a reviewed
`plan -destroy` and saved-plan apply, removes the environment in reverse
dependency order, and retains or removes `infra/bootstrap` last. The
[deployment runbook](../../../docs/runbooks/deploy.md) contains the rollout and
health-verification contract.

Trade-offs: rolling replacement avoids a second fleet and traffic-shifting
control plane, but it offers no independently addressable green environment.
The 100/200 bounds accept temporary replacement capacity so serving capacity
does not fall while new tasks become healthy.


## 9. Module boundaries

| Concern | Owning module |
|---|---|
| ALB, HTTPS listener, per-service listener rules | `infra/modules/alb`; this module owns only the target group |
| ECS cluster and Container Insights | `infra/modules/ecs-cluster` |
| ECR repositories, scan-on-push, lifecycle policy | `infra/modules/ecr` |
| VPC, subnets, NAT, interface/gateway endpoints, security groups | `infra/modules/network` |
| Customer-managed keys | `infra/modules/kms` |
| Secrets Manager entries and generated credentials | `infra/modules/secrets` |
| Batch state machines and their execution roles | `infra/modules/step-functions-batch` |
| Queues and dead-letter queues | `infra/modules/sqs` |
| Non-service log groups, dashboards, alarms, SNS | `infra/modules/observability` |
| HTTP API, Cognito JWT authorizer, VPC Link | `infra/modules/api-gateway-http` |
| SPA delivery path | `infra/modules/cloudfront-spa` |

Assumptions: every dependency is supplied as a variable by the environment
root, never by a sibling `module` call inside this module. That boundary keeps
the dependency graph visible at the composition layer and prevents a reusable
module from selecting an environment on its caller's behalf.

Refactoring Rationale: the target group is the seam with `infra/modules/alb`.
This module owns the container port, health path, protocol, and target type
needed to define the group; `alb` owns the listener and attaches
`target_group_arn` as the rule's forward target. Moving the group into `alb`
would make that module duplicate workload details it otherwise does not need.


## 10. Security posture

* Both ECS roles carry the required same-account permissions boundary.
* The execution role names the selected ECR repository, log group, parameters,
  secrets, and KMS keys. It does not receive a broad managed execution policy.
* The application task role receives only the capabilities selected for that
  workload: caller-composed business access, exact send/receive queue sets and
  reviewed managed policies.
* The application container runs as a non-root user with a read-only root
  filesystem. Fargate does not expose privileged mode, and the task definition
  deliberately omits that unsupported field.
* Tasks use private application subnets, security groups supplied by the
  network module, and `assign_public_ip = false`.
* Runtime values that require protection are resolved from Parameter Store or
  Secrets Manager. Tracked `terraform.tfvars` files carry non-secret sizing,
  retention, and environment configuration.

Refactoring Rationale: AAP §0.7.8 does not pretend RACF has a cloud analogue.
The target maps its control objective to least-privilege task identities and a
managed user pool rather than claiming a syntax-level port.

Assumptions: the only wildcard **resource** in the role policies accompanies the
one API that does not support resource scoping, ECR authorization-token retrieval.
No wildcard IAM **action** is present; image pull, configuration read, queue use,
logging, KMS, and business permissions all name their permitted actions.

Refactoring Rationale: this paragraph also named X-Ray ingestion as a wildcard
resource. That statement went with the withdrawn collector sidecar's task-role
policy, so the task role now holds neither a wildcard action nor a wildcard
resource -- a stronger property than the sentence was conceding, and worth stating
rather than leaving as a stale concession.

Refactoring Rationale: a public IP is withheld rather than offered as an
opt-out variable. The network module supplies private routes and VPC endpoints
for the AWS services tasks consume, so a single caller cannot bypass the
application-tier boundary.

Assumptions: deployment authentication uses short-lived OIDC-federated
credentials. Database credentials, seed credentials, and application client
secrets are generated or rotated into Secrets Manager by the composed
infrastructure; no resolved credential belongs in source.


## 11. Environment parameterization

For this module, `dev` and `prod` vary on three capacity/retention axes:

| Axis | Module inputs |
|---|---|
| Task count | `desired_count`, `min_capacity`, `max_capacity` |
| Task size | `task_cpu`, `task_memory` |
| Log retention | `log_retention_in_days` |

The environment value also namespaces names and tags, but it does not select a
different resource graph. Both environment roots call the same module block
and feed the same workload map.

Assumptions: AAP §0.4.1.6 requires the roots to differ in sizing and retention,
never topology. A missing sidecar, target group, security control, or scaling
resource in only one environment would therefore be a defect rather than an
environment option.

Trade-offs: shared topology makes the smaller environment exercise the same
dependency graph, while its reduced capacity cannot prove the larger
environment's load behaviour. Capacity evidence and topology evidence are
different claims.


## 12. Validation and gates

The infrastructure workflow enforces five Terraform contract gates. The module
is validated transitively through the environment roots rather than initialized
as an independent deployment root:

```bash
# WHAT: check canonical formatting across every Terraform directory without
#       rewriting reviewed files.
# WHY : Alternatives Considered: a bare terraform fmt was rejected because it
#       mutates the checkout and can hide the drift the gate is meant to report.
terraform fmt -check -recursive infra/

# WHAT: initialize each deployable root without its backend and validate the
#       complete composed module graph.
# WHY : Assumptions: provider schemas and child modules are required for
#       validate, while -backend=false keeps this credential-free and proves
#       every ecs-service input against real caller values.
for root in infra/bootstrap infra/envs/dev infra/envs/prod; do
  terraform -chdir="$root" init -backend=false -lockfile=readonly -input=false
  terraform -chdir="$root" validate
done

# WHAT: initialize the pinned AWS ruleset and lint the complete Terraform tree.
# WHY : Assumptions: the shared configuration checks documented and typed
#       variables/outputs, required versions/providers, unused declarations,
#       naming, comment syntax, and standard module structure.
tflint --init --config="$(pwd)/infra/.tflint.hcl"
tflint --recursive --config="$(pwd)/infra/.tflint.hcl"

# WHAT: compare every generated README region with its sibling HCL without
#       writing any file.
# WHY : Alternatives Considered: regeneration in CI was rejected because a
#       silent rewrite would turn reviewable contract drift into an unreviewed
#       mutation.
for dir in infra/bootstrap infra/modules/* infra/envs/dev infra/envs/prod; do
  terraform-docs --config "$(pwd)/infra/.terraform-docs.yml" \
    --output-check "$dir"
done

# WHAT: emit the complete Checkov report, then hard-fail on the reviewed
#       material-security policy set.
# WHY : Trade-offs: the offline scanner cannot select HIGH/CRITICAL severities
#       reliably, so explicit check identifiers prevent an empty selection from
#       returning a false-green result.
checkov -d infra --framework terraform --soft-fail \
  --skip-path '\.terraform' --compact --output json
material_checks_csv="<material-security-check-ids>"
checkov -d infra --framework terraform --check "$material_checks_csv" \
  --skip-path '\.terraform' --compact --output json
```

Assumptions: transitive validation is why every referenced input must be
declared in `variables.tf`; an undeclared reference surfaces through the
calling root. TFLint checks the reverse direction by rejecting declarations
that nothing consumes.

The workflow also runs a separate, hard gitleaks scan over migration-owned
tracked files and verifies that every bounded policy-scan exception retains the
condition that justified it. Those checks complement the five Terraform
contract gates; they do not replace any of them.

Assumptions: the workload-isolation policy is satisfied by construction. The
application container has a non-root user, Fargate exposes no privileged mode,
every container has `awslogs` configuration, and neither role contains a
wildcard action. `readonly_root_filesystem` defaults to `true`; required JVM
scratch paths are explicit ephemeral mounts rather than a writable image root.

This repository authors and statically validates the package. A live
`terraform apply` and any resulting cost remain operator actions outside this
documentation gate.


## 13. Related documents

* [Infrastructure package overview](../../README.md)
* [Remote-state bootstrap](../../bootstrap/README.md)
* [ADR-002: Compute platform](../../../docs/adr/ADR-002-compute-platform.md)
* [Service catalog](../../../docs/architecture/service-catalog.md)
* [COBOL-to-service traceability](../../../docs/architecture/cobol-to-service-traceability.md)
* [Deployment runbook](../../../docs/runbooks/deploy.md)
* [Teardown runbook](../../../docs/runbooks/teardown.md)
* [Code documentation standard](../../../docs/CODE_DOCUMENTATION_STANDARD.md)
* [Migration guide](../../../MIGRATION_README.md)

Assumptions: every link above resolves from this module directory and names an
authored contract, not a prospective file.
