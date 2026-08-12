# =============================================================================
# infra/modules/ecs-service/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input contract of the reusable `ecs-service` module -- the
#   one module in infra/ that exists in order to be instantiated many times
#   rather than once. Each of the two environment roots, infra/envs/dev and
#   infra/envs/prod, calls it nine times, once per workload: the eight Java
#   services -- auth, account, card, transaction, reference, batch,
#   authorization and reporting -- plus the data-migration ETL, which needs a
#   task definition and no service. Eighteen module instances across the two
#   roots are therefore
#   configured entirely through the declarations below, and nothing else in
#   the module accepts a value from a caller.
#
# Parameters:
#   This file IS the parameter list, so the per-parameter obligation -- a
#   name, a type and a description for every one -- is discharged by the
#   blocks themselves rather than restated here: the `variable` label is the
#   name, an explicit `type` is present on every block, and each
#   `description` says what the value is for, which resource consumes it and
#   where the caller obtains it. The order is two-tier -- required inputs
#   first, then optional ones -- and the reason is recorded at the head of
#   each tier.
#
# Return values:
#   None, and the absence is deliberate rather than an omission. A
#   `variable` block returns nothing, and no `output` appears here because
#   outputs.tf owns the module's entire return surface; tflint's
#   terraform_standard_module_structure rule asserts exactly that split. The
#   values this file describes travel inward, from caller to module.
#
# Exceptions or errors:
#   - Ten variables declare no `default`, which makes each one a hard
#     requirement: omitting one fails in the CALLING ROOT at `terraform
#     validate` with a missing-required-argument error, before any resource
#     in this module is evaluated.
#   - Fifty-two variables carry sixty-four `validation` blocks between them, so a
#     bad value is rejected before the AWS API sees it. The checks cover
#     identifiers and ARN shapes, Fargate CPU/memory and network contracts,
#     HTTPS health checks, deployment/autoscaling bounds, CloudWatch retention,
#     the pinned telemetry image and sampling percentage, non-secret
#     environment-variable namespaces, store-specific references, the container
#     health-check argument vector and IAM policy document syntax.
#   - WHEN a rule is checked is not uniform. A `validation` reading only its
#     own variable is evaluated by `terraform validate`; one reading ANOTHER
#     variable is deferred to `terraform plan`, because the context that lets
#     one variable see another does not exist at validate time. Fifteen of the
#     sixty-four rules fall in the second group -- among them
#     writable_mount_paths reading readonly_root_filesystem, task_memory
#     reading task_cpu, the four autoscaling and desired_count rules reading
#     create_service, task_role_policy_json reading create_task_role_policy and
#     create_online_write_gate_policy reading online_write_gate_parameter_arn --
#     so `validate` alone reports none of those pairings and `plan` reports all
#     of them. Both precede any resource, so no task definition is created from
#     a broken pairing either way.
#   - Six variables select the module's SHAPE rather than one of its values:
#     create_service, attach_load_balancer, enable_autoscaling,
#     enable_telemetry_collector, create_task_role_policy and
#     create_online_write_gate_policy. Disabling one is not an error; it removes
#     the corresponding service/target/scaler, collector or task-role policy
#     resources. Each states its own coupling because none is inferable from the
#     boolean type. container_health_check_command belongs to the same group
#     without being a boolean: left null it removes the container healthCheck
#     block rather than changing a value inside it.
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: nine per-workload module copies, one per
#     workload, instead of one parameterised module with this input
#     surface. Rejected on the evidence of app/csd/CARDDEMO.CSD rather than
#     on taste: all 18 `DEFINE TRANSACTION` stanzas (L306-L488) are
#     attribute-identical -- ISOLATE(YES), TASKDATAKEY(USER),
#     ACTION(BACKOUT), PRIORITY(1), RESTART(NO), PROFILE(DFHCICST) and
#     TRANCLASS(DFHTCL00) each occur exactly 18 times -- so the CICS region
#     already expressed 18 workloads as one repeated template differing only
#     in transaction name and target program. Nine copies would fork that
#     template nine ways, and any later change to the health-check or
#     deployment contract would then have to land in nine files to keep the
#     nine workloads behaving alike.
#   - Trade-offs: every `description` is written as a heredoc rather than as
#     a single-line string. One line cannot carry what a value is for, which
#     resource consumes it AND where the caller obtains it without running
#     far past the 79-column width the rest of this module holds to, and a
#     caller reading the generated README is the party that needs all three.
#     The accepted cost is a considerably taller file.
#   - Assumptions: comments sit above each `variable` block and never between
#     its arguments. `terraform fmt` treats a comment as the end of an
#     alignment run, so an interleaved comment silently re-aligns the `=`
#     signs that follow it, and `terraform fmt -check -recursive infra/` is
#     gating.
#   - Refactoring Rationale: three of this module's postures are decided by
#     its DEFAULTS rather than by its callers, because not one of the nine
#     instantiations passes a value for any of them -- so for all nine the
#     default is the configuration, and a permissive default is a permissive
#     fleet. All three were corrected together for that reason.
#     (a) readonly_root_filesystem now defaults to true, with the writable
#         ephemeral mount it needs supplied by writable_mount_paths, so a
#         process that reaches code execution cannot leave anything behind on
#         the container filesystem for the next task to load.
#     (b) target_protocol fixes the load-balancer-to-task hop at HTTPS, so
#         encryption in transit reaches the task rather than stopping at the
#         load balancer, where bearer tokens and account data would otherwise
#         have crossed the private application subnets in the clear.
#     (c) environment_variables is now gated by an allowlist of the key
#         namespaces the images read plus a refusal of secret-bearing names,
#         so the documented split with ssm_parameter_arns and secret_sources is
#         enforced rather than merely described. A literal placed here is
#         durably readable in the task definition, in plan output and in
#         state, by a wider audience than the secret store's read policy.
#     Each is the module-side half of a change whose other half lives
#     elsewhere: (a) needs no other artifact, (b) requires the server.ssl
#     block in each service's application.yml, and (c) relies on the secrets
#     module already generating every credential at apply time.
# =============================================================================

# -----------------------------------------------------------------------------
# TIER 1 -- REQUIRED INPUTS. Every variable in this tier omits `default`.
#
# WHY : Alternatives Considered: ordering all sixty-one variables strictly
#       alphabetically, which is the obvious scheme and does help a reader
#       hunting for one name already known. Rejected because it interleaves
#       the ten inputs a caller MUST supply with the fifty-one it may
#       ignore, so a new `module` block could only be written correctly by
#       reading every block in the file to discover which ones lack a
#       default. Required-first answers the question a caller actually
#       arrives with. Within each tier the grouping follows the direction a
#       value travels: identity, the cluster the task joins, the network it
#       runs in, the image it runs, the container runtime, the load balancer
#       in front of it, the service itself, its autoscaling, its logs, its
#       injected configuration, its IAM and finally its tags.
# -----------------------------------------------------------------------------

# WHY : Assumptions: no `default` deliberately. This is the only input that
#       tells the nine instantiations apart, so a default would let two
#       `module` blocks in one root compose the same ECS service name, log
#       group and target-group name and then collide during apply. The
#       charset bound is not cosmetic either -- the value is composed into the
#       readable stem of an ALB target-group name, which accepts only
#       alphanumerics and hyphens. The 14-character ceiling keeps the shared
#       resource name compact and leaves a meaningful stem before the
#       replacement hash main.tf appends.
variable "service_name" {
  description = <<-EOT
    Bounded-context or task-only workload short name for this instance: one of
    auth, account, card, transaction, reference, batch, authorization,
    reporting or data-migration.
    Composed with name_prefix and environment into the ECS service name, the
    task-definition family, the CloudWatch log-group name, both IAM role
    names and the ALB target-group name, and used as the service segment of
    the Application Auto Scaling resource_id. The environment root supplies
    it as a literal; it is not read from another module's output.
  EOT
  type        = string

  validation {
    condition = alltrue([
      can(regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$", var.service_name)),
      length(var.service_name) <= 14,
    ])
    error_message = join(" ", [
      "service_name must start with a lowercase letter, contain only",
      "lowercase letters, digits and single interior hyphens, and be at",
      "most 14 characters so the shared resource name and the readable",
      "target-group stem remain compact.",
    ])
  }
}

# WHY : Assumptions: the accepted set is closed at exactly two because the
#       package provisions exactly two roots, infra/envs/dev and
#       infra/envs/prod, and those two differ only in sizing and retention
#       -- task count, CPU and memory, log retention -- never in topology. A
#       third value would name an environment with no root and no
#       terraform.tfvars behind it, so its resources would be created under
#       a name nothing else in the package resolves. Rejecting it here turns
#       that into a plan-time message instead of an orphaned stack.
variable "environment" {
  description = <<-EOT
    Environment discriminator, either dev or prod. Appended to every
    composed resource name so the two roots can coexist in one account
    without colliding, and emitted as a tag value for cost attribution. Each
    environment root passes its own name as a literal.
  EOT
  type        = string

  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment must be exactly \"dev\" or \"prod\"."
  }
}

# WHY : Assumptions: the ARN form rather than the name, because that is what
#       the `cluster` argument of aws_ecs_service takes. It is one of a PAIR
#       of cluster inputs -- see cluster_name immediately below for why both
#       forms of one identity are needed.
variable "cluster_arn" {
  description = <<-EOT
    ARN of the ECS Fargate cluster that will host this service, consumed by
    the `cluster` argument of aws_ecs_service. Comes from the ecs-cluster
    module's output; all nine instantiations in a root share one cluster.
  EOT
  type        = string

  # WHY : Assumptions: this is an ARN and the sibling variable below is a NAME, and
  #       both are typed string, so passing one where the other belongs
  #       type-checks and plans cleanly. The two are consumed in different places
  #       -- the ARN by aws_ecs_service.cluster, the name by the autoscaling
  #       resource identifier -- so a swap fails at apply against whichever
  #       resource reads it, in an error naming the AWS argument rather than the
  #       wiring mistake. Checking the shape here names the input.
  validation {
    condition     = can(regex("^arn:[a-z0-9-]+:ecs:[a-z0-9-]+:[0-9]{12}:cluster/[A-Za-z0-9_-]+$", var.cluster_arn))
    error_message = "cluster_arn must be an ECS cluster ARN of the form arn:<partition>:ecs:<region>:<account>:cluster/<name>, not a cluster name: the name belongs in cluster_name, and both are strings so a swap plans cleanly."
  }
}

# WHY : Assumptions: Application Auto Scaling identifies an ECS service by a
#       composite string, service/<cluster-name>/<service-name>, and that is
#       a documented AWS API contract rather than a naming preference, so
#       the NAME is required and the ARN cannot substitute for it.
#       Alternatives Considered: taking only cluster_arn and recovering the
#       name from it with split("/", ...) or a regex. Rejected as string
#       surgery on an identifier whose internal layout belongs to the
#       provider, when the ecs-cluster module already publishes the name as
#       an output; a second declared input is cheaper than a parsing
#       assumption. The two are deliberately not cross-validated, because a
#       module cannot confirm that an ARN and a name refer to the same
#       cluster without an API call.
variable "cluster_name" {
  description = <<-EOT
    Bare name -- not the ARN -- of the same ECS cluster that cluster_arn
    identifies. Consumed only by the Application Auto Scaling target's
    resource_id, which AWS requires in the literal form
    service/<cluster-name>/<service-name>. Comes from the ecs-cluster
    module's output alongside cluster_arn; both are needed because the ECS
    and Application Auto Scaling APIs demand different forms of one
    identity.
  EOT
  type        = string
}

# WHY : Assumptions: aws_lb_target_group requires vpc_id whenever its target
#       type is `ip`, and `ip` is the only target type Fargate's awsvpc
#       networking supports, so this input is unavoidable even though the
#       tasks themselves are placed by subnet rather than by VPC.
variable "vpc_id" {
  description = <<-EOT
    Identifier of the three-availability-zone VPC that owns the subnets in
    private_app_subnet_ids. Consumed by aws_lb_target_group, which cannot
    register IP targets without knowing the VPC they live in. Comes from the
    network module's output.
  EOT
  type        = string
}

# WHY : Assumptions: these must be the PRIVATE-APPLICATION tier
#       specifically, which is why the tier is named in the variable instead
#       of a neutral `subnet_ids`. The network module builds three tiers with
#       deliberately different reachability -- the public subnets carry only
#       the load balancer and the NAT gateways, and the data subnets have no
#       internet route at all -- so passing public subnet ids would place
#       application tasks in the one tier reachable from the internet, and
#       passing isolated data subnet ids would leave the tasks unable to
#       reach the image registry or the secret store. Either substitution
#       defeats the tiering rather than merely relocating the task.
variable "private_app_subnet_ids" {
  description = <<-EOT
    Subnet identifiers of the private application tier, one per availability
    zone, consumed by the awsvpc network_configuration of aws_ecs_service.
    Spreading them across three zones is what lets the service survive the
    loss of one. Comes from the network module's output; the public and
    isolated-data tiers it also publishes are not interchangeable with this
    one.
  EOT
  type        = list(string)

  # WHY : Assumptions: the tasks are placed across three availability zones, so an
  #       empty or single-element list is refused. One subnet plans and applies
  #       cleanly and puts every task of a service in one zone, which makes the
  #       three-zone network design pointless and turns one zone's loss into an
  #       outage -- the same reasoning desired_count's floor of two rests on, and
  #       neither guard is worth anything without the other.
  #       Assumptions: the shape is checked too, because a subnet id and a VPC id
  #       are both strings beginning "vpc"/"subnet" and a swapped pair is refused
  #       only when the network interface is created.
  validation {
    condition     = length(var.private_app_subnet_ids) >= 2 && alltrue([for id in var.private_app_subnet_ids : can(regex("^subnet-[0-9a-f]{8,17}$", id))])
    error_message = "private_app_subnet_ids must hold at least two subnet ids of the form subnet-<hex>: a single subnet places every task of a service in one availability zone, which makes the three-zone design and the two-task floor both pointless."
  }
}

# WHY : Trade-offs: a list rather than a single id. One group -- the
#       application group published by the network module, which admits the
#       load balancer on the container port and permits egress to the
#       database on 5432 and to the VPC interface endpoints on 443 -- is the
#       canonical value, so a scalar would be sufficient today. A list is
#       accepted so a root needing one additional group for a single service
#       can pass it without editing this module and therefore without
#       disturbing the other seven instantiations. The accepted cost is that
#       the module cannot assert the group actually admits traffic on
#       container_port; that stays the network module's responsibility.
variable "security_group_ids" {
  description = <<-EOT
    Security groups to attach to every task network interface, consumed by
    the awsvpc network_configuration of aws_ecs_service. Canonically the
    single application-tier group from the network module's output, which
    permits load-balancer ingress on the container port and egress to the
    database and the VPC interface endpoints. Additional groups may be
    appended by the caller.
  EOT
  type        = list(string)

  # WHY : Assumptions: an empty list is refused because it is not neutral. A task
  #       launched with no security group of its own falls back to the VPC's
  #       default group, whose rules this tree neither writes nor reviews, so the
  #       task's reach becomes whatever that group happens to allow -- the same
  #       failure the VPC Link guard in infra/modules/api-gateway-http refuses, for
  #       the same reason.
  validation {
    condition     = length(var.security_group_ids) > 0 && alltrue([for id in var.security_group_ids : can(regex("^sg-[0-9a-f]{8,17}$", id))])
    error_message = "security_group_ids must hold at least one security group id of the form sg-<hex>: a task with no group of its own falls back to the VPC default group, whose rules this configuration does not own."
  }
}

# WHY : Assumptions: the value arrives already complete -- registry host,
#       repository path, and a tag or digest -- and this module composes none
#       of it. That is deliberate: composing a registry hostname would
#       require both the account identifier and the region, and no account
#       identifier, ARN, registry hostname or region literal is permitted to
#       appear anywhere in this tree, not even as a default. Passing the
#       whole reference in keeps every one of them out of this file. It is
#       also why there is no default: the deploy workflow tags each image by
#       commit SHA, so the correct value is only known at apply time and any
#       default would be a stale image that still deploys successfully.
variable "image_uri" {
  description = <<-EOT
    Fully qualified container image reference including its tag or digest,
    used verbatim as the `image` field of the container definition. Produced
    by the deploy workflow, which builds from the service's Dockerfile,
    pushes to the repository named by ecr_repository_arn and identifies the
    result by commit SHA.
  EOT
  type        = string

  # WHY : Assumptions: the image is referenced by an explicit registry URI, and a
  #       tag or digest is REQUIRED. Omitting it makes the container runtime resolve
  #       `latest`, so two tasks launched from one task definition can run
  #       different code, and a rollback has nothing to roll back to. That is the
  #       same determinism argument the pinned Fargate platform version makes,
  #       applied to the artifact rather than the platform.
  #       Trade-offs: a digest reference is admitted alongside a tag and is the
  #       stronger form, but a tag is not refused: the deploy pipeline pushes an
  #       immutable tag built from the commit, and requiring digests would mean the
  #       task definition could only be written after the push resolved.
  validation {
    condition     = can(regex("^[0-9]{12}\\.dkr\\.ecr\\.[a-z0-9-]+\\.amazonaws\\.com/[a-z0-9._/-]+(:[A-Za-z0-9._-]+|@sha256:[a-f0-9]{64})$", var.image_uri))
    error_message = "image_uri must be a full ECR image reference ending in an explicit :tag or @sha256:<digest> -- for example 111122223333.dkr.ecr.eu-west-1.amazonaws.com/carddemo-dev/auth-service:1.2.3. An untagged reference resolves to latest at task launch, so one task definition can run two different builds."
  }

  # WHY : Assumptions: a tag is mutable and a digest is not, and the difference
  #       matters only where a redeploy must be reproducible. In production a task
  #       definition pinned to a tag can silently run a different build after the
  #       tag is moved -- including on an unrelated scale-out event, which makes
  #       two tasks of one service run two builds. A digest cannot move.
  #       Trade-offs: dev keeps mutable tags on purpose, because iterating there
  #       means pushing over a tag and restarting; requiring a digest in dev would
  #       add a lookup step to every iteration for a reproducibility guarantee dev
  #       does not need.
  validation {
    condition     = var.environment != "prod" || can(regex("@sha256:[a-f0-9]{64}$", var.image_uri))
    error_message = "production image_uri values must end in an immutable @sha256:<64 lowercase hex characters> digest; mutable tags are accepted only in dev."
  }
}

# WHY : Alternatives Considered: this input exists for exactly one reason --
#       to scope the execution role's image-pull permission to a single
#       repository instead of to every repository in the account. The
#       wildcard was the alternative and is rejected because least privilege
#       is a stated non-negotiable constraint and the policy scan in CI
#       treats a wildcard IAM action or resource as a finding, so the
#       wildcard would redden a gating step rather than merely be untidy.
#       Deriving the ARN by parsing image_uri was also considered and
#       rejected: the account identifier and the region would have to be
#       pulled out of the registry hostname and reassembled, which would put
#       both literals into this module's expressions. Accepting the ARN as
#       its own input keeps the parsing, and those literals, out entirely.
#       Assumptions: the registry authorization-token action accepts no
#       resource narrower than the wildcard, so it is the one statement this
#       ARN cannot scope. Every statement that pulls a layer or reads the
#       image manifest is scoped by it.
variable "ecr_repository_arn" {
  description = <<-EOT
    ARN of the single ECR repository holding this service's images. Used as
    the Resource of the task execution role's image-pull statements, so that
    permission names one repository rather than all of them. Comes from the
    ecr module's output, which publishes one ARN per repository.
  EOT
  type        = string

  # WHY : Assumptions: this value scopes the execution role's image-pull permission
  #       to one repository, so a malformed value does not fail loudly -- it
  #       produces an IAM statement matching no repository, and the task then fails
  #       to start with an authorization error about pulling the image rather than
  #       anything pointing at this input. A repository NAME and a repository URI
  #       are both plausible things to pass here and both type-check.
  validation {
    condition     = can(regex("^arn:[a-z0-9-]+:ecr:[a-z0-9-]+:[0-9]{12}:repository/[a-z0-9._/-]+$", var.ecr_repository_arn))
    error_message = "ecr_repository_arn must be an ECR repository ARN of the form arn:<partition>:ecr:<region>:<account>:repository/<name>. A repository name or an image URI produces an IAM statement matching no repository, so the task fails to pull its image."
  }
}

# -----------------------------------------------------------------------------
# TIER 2 -- OPTIONAL INPUTS. Every variable below carries a `default`, so a
# caller may omit all of them and still get a working service.
#
# WHY : Assumptions: each default is chosen to be the value seven of the nine
#       instantiations want -- the two task-only workloads have no service,
#       target group or autoscaling for most of them to apply to -- so a root's
#       `module` block stays short and the
#       lines it does write are the ones that genuinely differ. Three axes
#       are expected to diverge between dev and prod -- task count, CPU and
#       memory, and log retention -- and those defaults are the ones most
#       often overridden; the rest exist so that a divergence is possible
#       without being routine.
# -----------------------------------------------------------------------------

# WHY : Assumptions: the 12-character ceiling keeps the common resource name at
#       or below 32 characters with the longest service and environment names.
#       The target group now reserves nine of its own 32 characters for a
#       separator plus replacement hash and truncates only its readable stem,
#       so this bound preserves legibility across every resource rather than
#       being the sole collision control. The pattern additionally forbids a
#       leading digit, trailing hyphen and doubled hyphens.
#       Trade-offs: the default matches the same-named variable in the
#       bootstrap root so one prefix identifies every resource in the
#       package; the cost is that the two must be changed together to stay
#       consistent.
variable "name_prefix" {
  description = <<-EOT
    Leading token shared by every resource name this module composes -- the
    ECS service, the task-definition family, the log group, both IAM roles
    and the readable stem of the ALB target group. Kept short so the common
    resource name remains legible before main.tf appends the target group's
    replacement hash. Defaulted rather than required so a root states it only
    when it wants something other than the package-wide prefix.
  EOT
  type        = string
  default     = "carddemo"

  validation {
    condition = alltrue([
      can(regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$", var.name_prefix)),
      length(var.name_prefix) <= 12,
    ])
    error_message = join(" ", [
      "name_prefix must start with a lowercase letter, contain only",
      "lowercase letters, digits and single interior hyphens, and be at",
      "most 12 characters so the shared resource name and the readable",
      "target-group stem remain compact.",
    ])
  }
}

# -----------------------------------------------------------------------------
# Container runtime.
# -----------------------------------------------------------------------------

# WHY : Assumptions: this matters far beyond cosmetics, for one instantiation
#       in particular. Step Functions starts each batch step through the
#       synchronous run-task integration and passes that step's arguments as
#       container overrides, which address the container being overridden BY
#       NAME. The batch instance's container name is therefore a contract
#       shared with the step-functions-batch module, and a name derived
#       silently inside this module would be a contract neither side
#       declares. Exposing it lets the root pin one literal and hand the same
#       one to both modules.
#       Trade-offs: the default is null rather than a computed string, because
#       a variable default cannot reference another variable. main.tf
#       coalesces null to a name derived from service_name, so the seven
#       services that have no cross-module contract never set it.
variable "container_name" {
  description = <<-EOT
    Name of the single container inside aws_ecs_task_definition's container
    definitions, and the name the target group's port mapping refers to.
    Leave null to let the module derive one from service_name, which is what
    the seven long-running services do. Set it explicitly for the batch
    instance, whose container name is also referenced by the
    step-functions-batch module's container overrides, so both modules must
    agree on one literal.
  EOT
  type        = string
  default     = null
}

# WHY : Assumptions: 8080 is not an arbitrary preference but the one port the
#       network tiering admits -- the application security group permits
#       load-balancer-to-application traffic on 8080 and nothing else
#       inbound. Any other value would produce a target group whose health
#       probes and forwarded requests are dropped by the security group
#       rather than refused by the container, which is much the harder of the
#       two failures to diagnose. It remains a variable rather than a
#       hard-coded literal only so a root changing the security group can
#       change both together.
# WHY : Refactoring Rationale: "both together" now names a mechanism rather
#       than an intention. infra/modules/network/variables.tf declares
#       app_container_port as the single settable source of that port, and the
#       environment root is expected to wire this input to it --
#       `container_port = module.network.app_container_port` -- so one
#       assignment reaches the security-group rule and the container it admits
#       traffic to. For a period this module's counterpart input did not exist
#       in the network module at all, which left the two halves of one flow
#       independently settable with nothing able to notice a disagreement; the
#       shared input was restored for that reason, and this comment records the
#       wiring so a root author does not have to infer it. Assumptions: the
#       validation domain below is deliberately IDENTICAL to the one that input
#       applies, whole numbers 1024 to 65535, so no value the network module
#       admits can be refused here -- a mismatch would fail the plan with a
#       message naming this task definition rather than the input the value
#       came from.
variable "container_port" {
  description = <<-EOT
    Container port exposed by the task, also used as the target-group port
    and the health-check port. Must match both the port the service's Spring
    Boot process binds and the port the application security group admits
    from the load balancer, so the environment root passes the network
    module's app_container_port here rather than a second literal.
  EOT
  type        = number
  default     = 8080

  # WHY : Refactoring Rationale: the network module admits ALB-to-application
  #       traffic on 8080 and on no other port. The previous range validation
  #       accepted values that ECS and the container could use but the security
  #       group would drop, producing an unreachable service whose plan looked
  #       valid. Pinning the input makes both halves of the network contract
  #       agree at plan time.
  validation {
    condition     = var.container_port == 8080
    error_message = "container_port must be 8080, the only application port admitted by the network module's ALB-to-service security-group contract."
  }
}

# WHY : Refactoring Rationale: running as root is the container default and
#       is what this replaces. It is also not a policy imported from outside
#       the application -- app/csd/CARDDEMO.CSD sets TASKDATAKEY(USER) on all
#       18 transaction stanzas and EXECKEY(USER) on all 18 program stanzas,
#       so the migrated system already refused to run application work in the
#       privileged storage key. Defaulting to root here would be a regression
#       against the system being migrated rather than a neutral choice, and
#       the CI policy scan separately treats a container with no non-root
#       user as a finding.
#       Assumptions: the value must name a user the image actually has. Each
#       service's Dockerfile creates one as part of its multi-stage build,
#       and a mismatch fails at task start with an unresolvable-user error
#       rather than at plan time, so the two are changed together.
#       Trade-offs: typed as a string, not a number, because the ECS container
#       definition's user field also accepts the uid:gid pair and named-user
#       forms, neither of which a numeric type could carry.
variable "container_user" {
  description = <<-EOT
    Value of the container definition's user field, given as a uid, a uid:gid
    pair or a user name. Defaults to the numeric uid the service Dockerfiles
    create their unprivileged account with, so the process never runs as
    root. Change it only together with the image that has to provide the
    account.
  EOT
  type        = string
  default     = "10001"

  # WHY : Assumptions: the value lands on the container definition's `user` field,
  #       which accepts a numeric uid, a uid:gid pair or a name that must exist in
  #       the image's own /etc/passwd. The numeric form is required here so that a
  #       policy check, and a reader, can confirm the process is not root without
  #       reading the image; a name would also break if an image were rebuilt on a
  #       base whose account table differed.
  #       Assumptions: uid 0 is refused explicitly. It is the one value that
  #       satisfies every shape check and defeats the purpose of the field, and
  #       nothing downstream would report it: the task runs, as root.
  validation {
    condition     = can(regex("^[1-9][0-9]*(:[1-9][0-9]*)?$", var.container_user))
    error_message = "container_user must be a numeric uid, or a numeric uid:gid pair, and must not be 0. A name is refused because it depends on the image's /etc/passwd, and uid 0 would run the task as root while satisfying every other check."
  }
}

# WHY : Assumptions: Fargate accepts only a fixed set of task CPU sizes, and
#       only certain memory sizes alongside each one, so an arbitrary integer
#       is rejected by the API during apply. The validation restates that set
#       to move the rejection to plan time, where the message names this
#       variable rather than the task definition. The set is an AWS contract
#       this module depends on rather than something verified here.
#       Trade-offs: the pairing with task_memory cannot be validated as a pair
#       without embedding the whole CPU-to-memory matrix in a condition, so
#       each is bounded independently and the coupling is documented on both.
#       The default of 1024 units with 2048 MiB is chosen because 2048 is the
#       smallest memory Fargate permits with one vCPU, which makes the pair
#       the least surprising valid combination for a JVM service. It is also
#       one of the three axes a root is expected to raise for prod.
variable "task_cpu" {
  description = <<-EOT
    Task-level CPU units for aws_ecs_task_definition, where 1024 units is one
    vCPU. Must be one of the sizes Fargate supports, and must pair with a
    task_memory value Fargate allows alongside it. Expected to differ between
    the dev and prod roots, which is one of the few axes on which those two
    roots are permitted to diverge.
  EOT
  type        = number
  default     = 1024

  validation {
    condition = contains([
      256, 512, 1024, 2048, 4096, 8192, 16384,
    ], var.task_cpu)
    error_message = join(" ", [
      "task_cpu must be one of the task CPU sizes AWS Fargate supports:",
      "256, 512, 1024, 2048, 4096, 8192 or 16384.",
    ])
  }
}

# WHY : Assumptions: Fargate constrains this to a set that DEPENDS ON
#       task_cpu -- the same API contract task_cpu depends on -- so no
#       standalone condition can bound it correctly and none is written here;
#       an invalid pair surfaces at apply.
#       Trade-offs: that is the accepted cost of not embedding the whole
#       CPU-to-memory matrix in a validation that would then have to be
#       maintained against a set AWS owns. The default is the smallest memory
#       Fargate permits with the default task_cpu, so the two ship as a pair
#       that is known to be valid.
variable "task_memory" {
  description = <<-EOT
    Task-level memory in MiB for aws_ecs_task_definition. Must be a value
    Fargate permits alongside the chosen task_cpu, so the two are always
    changed together. Expected to differ between the dev and prod roots for
    the same reason task_cpu does.
  EOT
  type        = number
  default     = 2048

  # WHY : Refactoring Rationale: the CPU-to-memory matrix IS validated here now,
  #       and the note above -- that no standalone condition can bound this
  #       correctly, so an invalid pair surfaces at apply -- is superseded. It was
  #       true of a condition reading only this variable; a validation may read
  #       ANOTHER variable, so the pair can be checked as a pair. What the earlier
  #       reasoning got right is that the matrix is an AWS contract this module
  #       depends on rather than something verified here, which is why the table
  #       below is transcribed rather than derived.
  #       Assumptions: Fargate permits, for each task CPU size, a fixed set of
  #       memory values: 256 units takes 512, 1024 or 2048 MiB; 512 takes 1024 to
  #       4096 in 1024-MiB steps; 1024 takes 2048 to 8192 in 1024-MiB steps; 2048
  #       takes 4096 to 16384 in 1024-MiB steps; 4096 takes 8192 to 30720 in
  #       1024-MiB steps; 8192 takes 16384 to 61440 in 4096-MiB steps; and 16384
  #       takes 32768 to 122880 in 8192-MiB steps. An out-of-set pair is rejected
  #       by the task-definition API during apply, in an error naming neither this
  #       variable nor task_cpu, and it is the single likeliest thing to get wrong
  #       when raising capacity for prod, because each value is individually
  #       plausible and only the COMBINATION is refused.
  #       Trade-offs: transcribing the matrix means a size AWS adds later is
  #       refused here until this list is extended. Accepted: the set has been
  #       stable across the platform generations this stack targets, and the
  #       failure it prevents lands partway through creating a task definition.
  validation {
    condition = contains(lookup({
      256   = [512, 1024, 2048]
      512   = [1024, 2048, 3072, 4096]
      1024  = [2048, 3072, 4096, 5120, 6144, 7168, 8192]
      2048  = [4096, 5120, 6144, 7168, 8192, 9216, 10240, 11264, 12288, 13312, 14336, 15360, 16384]
      4096  = [8192, 9216, 10240, 11264, 12288, 13312, 14336, 15360, 16384, 17408, 18432, 19456, 20480, 21504, 22528, 23552, 24576, 25600, 26624, 27648, 28672, 29696, 30720]
      8192  = [16384, 20480, 24576, 28672, 32768, 36864, 40960, 45056, 49152, 53248, 57344, 61440]
      16384 = [32768, 40960, 49152, 57344, 65536, 73728, 81920, 90112, 98304, 106496, 114688, 122880]
    }, var.task_cpu, []), var.task_memory)
    error_message = "task_memory must be a value AWS Fargate permits alongside the chosen task_cpu. The pairs are: 256 units with 512, 1024 or 2048 MiB; 512 with 1024 to 4096 in 1024-MiB steps; 1024 with 2048 to 8192; 2048 with 4096 to 16384; 4096 with 8192 to 30720; 8192 with 16384 to 61440 in 4096-MiB steps; 16384 with 32768 to 122880 in 8192-MiB steps. Change the two together."
  }
}

# WHY : Refactoring Rationale: this defaulted to false, and the reasoning given
#       for that -- a JVM writes to a temporary directory, and this module
#       mounts no writable volume -- was accurate about the obstacle and drew
#       the wrong conclusion from it. The missing volume was a gap in this
#       module's surface, so the fix was to close the gap rather than to leave
#       every task writable because of it. writable_mount_paths below is that
#       volume, and with it the default is true: a container whose root
#       filesystem cannot be written is one where a process that reaches code
#       execution cannot leave a modified binary, a cron entry, a shared object
#       on the loader path, or an altered configuration file behind for the next
#       task to load. Nine workloads times every task in every environment ran
#       without that property for the sake of one temporary directory.
#       Assumptions: this pairs with the read-only default rather than standing
#       alone. main.tf emits one Fargate ephemeral volume and one mount point per
#       entry in writable_mount_paths, so the JVM's temporary directory is
#       writable while the root filesystem is not, and the validation on that
#       variable refuses the half-configured combination -- read-only root, no
#       writable path -- which is the state that would break every task at start.
#       Alternatives Considered: leaving the default false and documenting that a
#       root may harden it. Rejected because a security posture reached only by a
#       caller opting in is the posture nobody has: all nine instantiations
#       take this module's default here, so the default IS the
#       configuration. Also considered: keeping it false for the batch
#       instantiation specifically, on the theory that a batch step writes more
#       than a service does. Rejected as unfounded -- batch output goes to the
#       database and to object storage, not to the container filesystem.
#       Refactoring Rationale: the earlier shape still allowed any caller to
#       lower the invariant after all this reasoning. The validation below
#       closes that fail-open path; a new writable path is named explicitly
#       instead of making the whole root writable.
variable "readonly_root_filesystem" {
  description = <<-EOT
    Sets readonlyRootFilesystem on the container definition. Defaults to true,
    which is the intended posture for all nine instantiations: writable paths
    the JVM needs are supplied as Fargate ephemeral volumes through
    writable_mount_paths rather than by leaving the whole root filesystem
    writable. The module rejects false because this is a fleet-wide invariant.
  EOT
  type        = bool
  default     = true

  validation {
    condition     = var.readonly_root_filesystem
    error_message = "readonly_root_filesystem must be true. Add an exact writable_mount_paths entry for a required scratch path rather than making the whole container filesystem writable."
  }
}

# WHY : Assumptions: /tmp is the whole of the default because it is the whole of
#       what the images need. A JVM writes to java.io.tmpdir -- heap dumps, JAR
#       extraction, the hsperfdata performance file, Tomcat's upload staging
#       directory -- and on Linux java.io.tmpdir IS /tmp unless something
#       overrides it. Mounting /tmp writable therefore satisfies the JVM with no
#       JVM flag at all.
#       Alternatives Considered: adding a jvm_tmp_dir input and passing
#       -Djava.io.tmpdir through JAVA_TOOL_OPTIONS. Rejected as a second way to
#       express one fact: the flag and this list would both have to name the same
#       path, nothing would check that they agreed, and a mismatch presents as a
#       JVM that cannot write its temporary files -- which looks like the
#       read-only root filesystem is broken rather than like two settings
#       disagreeing. Relying on the platform default and mounting that exact path
#       leaves one authority.
#       Alternatives Considered: an EFS or bind mount instead of an ephemeral
#       volume. Rejected because temporary files must NOT survive a task or be
#       shared between tasks -- a heap dump written by one task and readable by
#       the next is a data-exposure path, and the baseline had no equivalent of
#       shared scratch storage. Fargate ephemeral storage is encrypted at rest
#       and destroyed with the task, which is the lifetime a temporary directory
#       should have.
#       Trade-offs: an empty list is permitted, and is the correct value for an
#       image that provably writes nowhere. It is refused only in combination
#       with a read-only root, where it would break every task; that pairing is
#       checked on this variable rather than on the boolean above, because
#       Terraform evaluates a cross-variable validation at plan time and naming
#       the list is what tells the caller which value to add.
variable "writable_mount_paths" {
  description = <<-EOT
    Absolute container paths to keep writable when readonly_root_filesystem is
    true, each backed by its own Fargate ephemeral volume that is encrypted at
    rest and destroyed with the task. Defaults to the single temporary directory
    the JVM writes to, which is all the service images require. Must be
    non-empty whenever readonly_root_filesystem is true.
  EOT
  type        = list(string)
  nullable    = false
  default     = ["/tmp"]

  # WHY : Assumptions: a relative path is refused rather than resolved. A container
  #       mount point is interpreted by the container runtime, not by a shell, so
  #       "tmp" is not made absolute -- it produces a mount at an unintended
  #       location while /tmp stays read-only, which presents as the JVM failing
  #       to write and sends a reader looking at the image.
  validation {
    condition     = alltrue([for path in var.writable_mount_paths : startswith(path, "/")])
    error_message = "every writable_mount_paths entry must be an absolute container path beginning with a forward slash."
  }

  # WHY : Assumptions: "/" is refused explicitly. Mounting a writable volume over
  #       the root directory satisfies the letter of readonlyRootFilesystem and
  #       defeats all of it, and it is the shortest value anyone debugging a
  #       write failure would reach for.
  validation {
    condition     = !contains(var.writable_mount_paths, "/")
    error_message = "writable_mount_paths must not contain \"/\"; mounting a writable volume over the root directory defeats readonly_root_filesystem entirely. Name the specific directories that must be writable."
  }

  # WHY : Assumptions: duplicates are refused because main.tf derives one volume
  #       NAME per entry, so two identical paths would produce two volumes
  #       competing for one mount point -- which the ECS API rejects at apply,
  #       after the task definition revision has already been composed.
  validation {
    condition     = length(distinct(var.writable_mount_paths)) == length(var.writable_mount_paths)
    error_message = "writable_mount_paths must not repeat a path; each entry becomes its own volume and mount point."
  }

  # WHY : Assumptions: this is the cross-variable rule that keeps the pair
  #       coherent, and it is the reason the read-only default is safe to ship.
  #       A read-only root with no writable path is not a hardened task, it is a
  #       task that starts and then fails the moment the JVM writes -- which is
  #       during startup, so it fails every deployment rather than intermittently.
  #       Terraform defers a validation that reads another variable to plan time
  #       rather than validate time, so this one is checked by `plan` and by
  #       `apply`; both precede any resource, so no task definition is ever
  #       created from the broken pairing.
  validation {
    condition     = !var.readonly_root_filesystem || length(var.writable_mount_paths) > 0
    error_message = "writable_mount_paths must list at least one path when readonly_root_filesystem is true; the service images run a JVM that writes to its temporary directory, so a read-only root with no writable mount fails every task at startup. Pass [\"/tmp\"], which is the default, or set readonly_root_filesystem false with a stated reason."
  }
}

# -----------------------------------------------------------------------------
# Load balancer and health check.
#
# The three booleans that select this module's SHAPE -- attach_load_balancer
# here, then create_service and enable_autoscaling below -- are described
# together once, because none of the three makes sense in isolation.
#
# WHY : Alternatives Considered: a second module, `ecs-task`, emitting only
#       the task definition, the two roles and the log group. Rejected
#       because those four ARE the bulk of this module, so a second module
#       would duplicate all of them -- precisely the duplication a reusable
#       module exists to prevent. The two copies would then drift on exactly
#       the details that must not differ between how a service and a batch
#       step are packaged: the non-root user, the log-group naming, and the
#       execution role's resource-scoped pull and decrypt permissions.
#       Trade-offs: the cost is three booleans whose non-default value only two
#       of the nine callers need, accepted so one module covers both shapes and the four
#       shared resources are defined exactly once.
# -----------------------------------------------------------------------------

# WHY : Alternatives Considered: the rejected `ecs-task` module recorded in
#       the section note above; this flag is part of what lets one module
#       serve both shapes.
#       Assumptions: the coupling is not inferable from the type and is
#       load-bearing in main.tf. Setting this false creates no target group,
#       emits no `load_balancer` block on the service, and forces
#       health_check_grace_period_seconds to null, because aws_ecs_service
#       rejects that argument outright on a service with no load balancer
#       rather than ignoring it. The target-group output turns null in the
#       same case, so a caller wiring the alb module must read it before
#       assuming a listener rule can be attached.
variable "attach_load_balancer" {
  description = <<-EOT
    Whether to create an ALB target group for this service and register the
    service with it. True for the seven online services the internal load
    balancer fronts. Set false for the TWO task-only workloads, batch and
    data-migration, which Step Functions invokes rather than anything reaching
    them over HTTP; that also suppresses the health-check grace period and turns
    the target-group output null.
  EOT
  type        = bool
  default     = true

  # WHY : Assumptions: attaching a load balancer without creating a service is
  #       incoherent and cannot be expressed. main.tf would create a target group
  #       that no service ever registers with, so the group would exist with zero
  #       targets, its health check would report nothing, and a listener rule
  #       pointed at it would answer every request with a 503 -- while the plan and
  #       the apply both succeeded. The two flags describe one shape between them,
  #       so the incoherent quarter of the matrix is refused here rather than
  #       documented.
  #       Trade-offs: the reverse pairing stays legal. A service with no load
  #       balancer is exactly the batch shape this module also serves.
  validation {
    condition     = !var.attach_load_balancer || var.create_service
    error_message = "attach_load_balancer requires create_service: a target group with no service registered against it has zero targets, so a listener rule pointed at it answers 503 while the plan reports success. The batch shape sets both to false."
  }
}

# WHY : Assumptions: the services expose Spring Boot Actuator, whose health
#       endpoint is the one path guaranteed to answer without touching
#       business data or requiring a token, and the same endpoint backs both
#       this target-group probe and the image's own container health check.
#       Probing a business route instead was the alternative and is rejected
#       because it would make target-group membership depend on a database
#       round trip, so one slow query would deregister healthy tasks.
#       Assumptions: this module owns the TARGET GROUP and its health check,
#       and nothing else about routing. The listener and the per-service
#       listener rules that send traffic to the group belong to the alb
#       module. Keeping that boundary explicit is why no listener or
#       listener-rule input appears anywhere in this file.
# WHY : Refactoring Rationale: this input exists because the estate had NO
#       container-health signal at all and a comment in main.tf asserted the
#       opposite. That comment left the ECS healthCheck out on the reasoning that
#       "each Dockerfile therefore owns its HEALTHCHECK", which is true of `docker
#       run` and false of ECS: the agent monitors only the healthCheck declared in
#       the task definition and never reads the image's HEALTHCHECK instruction, so
#       the eight probes those Dockerfiles carry were never evaluated in the
#       deployed estate. The seven request-serving workloads still had the target
#       group's own check, but batch had no health signal of any kind.
# WHY : Assumptions: the COMMAND is an input rather than composed here, and the
#       original comment's objection is the reason -- only the image knows which
#       binaries its base layer ships, and the two schemes differ across this
#       estate: seven services answer HTTPS with a self-signed leaf and need
#       --insecure, while batch answers plain HTTP. Taking it as an input keeps that
#       knowledge with the root that also chooses the image, and leaving it null is
#       how a workload with no in-container probe -- the ETL image, whose own
#       Dockerfile records that orchestration judges it by exit code -- declines one.
# WHY : Trade-offs: the four durations are the module's own and are not per-workload
#       inputs, because the values in every Dockerfile of this estate already agree
#       apart from reporting's longer start period, and a task-definition check has a
#       separate startPeriod that ECS applies before the first failure counts. A
#       workload needing its own schedule would need three more inputs for a
#       difference no image currently has.
variable "container_health_check_command" {
  description = <<-EOT
    The exact command ECS runs inside the application container to judge its
    health, as the argv list the container's own entry point can execute. ECS
    monitors ONLY this; it never reads the image's HEALTHCHECK instruction, so a
    workload that supplies null has no container-level health signal and is judged
    by its target group if it has one and by its exit status otherwise. Supply the
    same command that workload's Dockerfile declares, so the two cannot disagree.
  EOT
  type        = list(string)
  default     = null

  # WHY : Assumptions: a non-null value must be non-empty and must open with an
  #       absolute path or the CMD-SHELL sentinel ECS defines, because those are the
  #       only two forms the agent can execute. An empty list type-checks and
  #       produces a task definition ECS rejects at registration.
  validation {
    condition = var.container_health_check_command == null || (
      length(var.container_health_check_command) > 0 &&
      (
        startswith(try(var.container_health_check_command[0], ""), "/") ||
        try(var.container_health_check_command[0], "") == "CMD-SHELL"
      )
    )
    error_message = "container_health_check_command must be null or a non-empty argv list whose first element is an absolute path inside the image or the literal CMD-SHELL. A relative command name depends on a PATH the agent does not guarantee."
  }
}

variable "health_check_path" {
  description = <<-EOT
    Path the target-group health check requests on container_port, over HTTPS
    like the traffic it stands in for. Defaults to the Spring Boot Actuator
    health endpoint every service exposes. Consumed only by the target group
    this module creates; the listener rule that routes traffic to that group is
    the alb module's to define.
  EOT
  type        = string
  default     = "/actuator/health"

  # WHY : Assumptions: the value is used verbatim as an absolute request path, so a
  #       value such as "actuator/health" is not corrected -- it probes a path that
  #       does not exist, which presents as every task failing its health check
  #       rather than as a configuration error, and sends a reader looking at the
  #       service instead of at this input. The same check guards the republished
  #       copy in infra/modules/alb, deliberately worded identically so the two
  #       cannot diverge.
  validation {
    condition     = startswith(var.health_check_path, "/")
    error_message = "health_check_path must begin with \"/\"; a health-check path is absolute, and a relative value probes a path that does not exist, so every target fails its check."
  }
}

# WHY : Refactoring Rationale: this module said nothing about the protocol, and
#       silence here is not neutral -- an aws_lb_target_group needs one, so main.tf
#       would have supplied HTTP and the load-balancer-to-task hop would have been
#       cleartext. The viewer hop was already TLS (the alb module pins a TLS 1.2
#       floor on its listener, and API Gateway reaches that listener over a VPC
#       Link), so the encrypted path stopped exactly at the load balancer and
#       every request then crossed the private application subnets in the clear:
#       the bearer token in the Authorization header, the account and card numbers
#       in the paths, and the response bodies. The design requires encryption in
#       transit end to end rather than up to the edge, and being inside a VPC
#       bounds who can observe that traffic without making it unreadable -- the
#       same distinction recorded on the Aurora cluster's rds.force_ssl parameter.
#       Assumptions: main.tf uses this one value for BOTH the target group's
#       protocol and its health check's protocol. They are deliberately not two
#       inputs: a health check that probes HTTP while traffic goes over HTTPS
#       reports a task healthy on a listener the traffic never uses, and the two
#       could then be configured to disagree with nothing detecting it.
#       Assumptions: the task must actually terminate TLS on container_port for
#       this to work, which is the other half of the same change --
#       services/*/src/main/resources/application.yml carries the server.ssl
#       block that makes each service serve HTTPS on 8080. The two halves must
#       move together, and each names the other.
#       Trade-offs: the target group does NOT verify the task's certificate, and
#       cannot -- an Application Load Balancer trusts any certificate a target
#       presents, including a self-signed one. So this defends against observation
#       on the path and not against a substituted target; that residual is
#       accepted because target registration is controlled by this module and the
#       security groups admit only the load balancer, and it is recorded so nobody
#       reads HTTPS here as mutual authentication.
variable "target_protocol" {
  description = <<-EOT
    Protocol the target group and its health check use to reach the task.
    Fixed at HTTPS: the load-balancer-to-task hop carries bearer tokens and
    account data across the private application subnets, so it is encrypted like
    the viewer hop in front of it. Declared as an input only so that an attempt
    to lower it is refused with a reason rather than silently accepted.
  EOT
  type        = string
  nullable    = false
  default     = "HTTPS"

  # WHY : Trade-offs: an input whose only accepted value is its default looks
  #       redundant and is not. Someone debugging a target group whose members
  #       will not turn healthy reaches for the protocol first; with no input to
  #       set, the next step is an edit to main.tf, which changes the hop for all
  #       nine workloads and appears in no review of the environment root. A
  #       variable that refuses the change states the reason at the moment it is
  #       attempted. The same pattern is used for the ETL's TLS mode in
  #       data-migration/src/carddemo_migration/config.py, for the same reason.
  validation {
    condition     = var.target_protocol == "HTTPS"
    error_message = "target_protocol must be \"HTTPS\". The load-balancer-to-task hop carries the Authorization header and account, card and transaction data across the private application subnets, so it is encrypted end to end rather than only up to the load balancer. If a task genuinely cannot serve TLS, fix the service's server.ssl configuration rather than lowering this."
  }
}

# WHY : Assumptions: the Actuator health endpoint answers 200 when its status
#       is UP and a server error when it is not, so a single code is the
#       whole contract and a range would widen it to include responses that
#       mean the service is not healthy.
#       Trade-offs: typed as a string, not a number, because the AWS matcher
#       accepts ranges and comma-separated lists as well as a single code,
#       none of which a numeric type could express.
variable "health_check_matcher" {
  description = <<-EOT
    Status codes the target group accepts as a healthy response, given as a
    single code, a range or a comma-separated list. Defaults to the one code
    the Actuator health endpoint returns when the service reports UP.
  EOT
  type        = string
  default     = "200"

  # WHY : Assumptions: the target group accepts either a single status code or a
  #       comma-separated list and range, and it validates the string during apply
  #       rather than normalising it. A value such as "OK" or "2xx" is
  #       syntactically plausible and is refused there, after the target group is
  #       already being created; checking the shape here names this input instead.
  #       Trade-offs: only the SHAPE is checked, not whether the codes are ones the
  #       actuator can return. A caller widening the matcher to admit a redirect
  #       is making a deliberate choice this module has no basis to refuse.
  validation {
    condition     = can(regex("^[1-5][0-9][0-9](-[1-5][0-9][0-9])?(,[1-5][0-9][0-9](-[1-5][0-9][0-9])?)*$", var.health_check_matcher))
    error_message = "health_check_matcher must be one HTTP status code, a hyphenated range, or a comma-separated list of either -- for example \"200\", \"200-299\" or \"200,204\". Class shorthands such as \"2xx\" are rejected by the load balancer at apply."
  }
}

# WHY : Trade-offs: a deliberately bounded compromise rather than a tuned
#       value. Probing less often lets a wedged task keep receiving requests
#       for more consecutive probes before unhealthy_threshold is reached;
#       probing more often multiplies probe traffic against every task of
#       every service, and this module is instantiated nine times per root.
#       The pairing that matters is with unhealthy_threshold, since the two
#       together are what decide when a bad task is pulled, so a root
#       changing either should look at both.
variable "health_check_interval" {
  description = <<-EOT
    Seconds between target-group health-check probes of each registered task.
    Read together with unhealthy_threshold, the number of consecutive
    failures that removes a task from rotation, and with
    health_check_timeout, which AWS requires to be strictly smaller than this
    value.
  EOT
  type        = number
  default     = 30

  # WHY : Assumptions: 5 to 300 seconds is the range an Application Load Balancer
  #       target-group health check accepts, and a value outside it is refused at
  #       apply while the target group is being created. The cross-check against
  #       health_check_timeout lives on that variable, where a reader changing the
  #       timeout will meet it.
  validation {
    condition     = var.health_check_interval >= 5 && var.health_check_interval <= 300 && floor(var.health_check_interval) == var.health_check_interval
    error_message = "health_check_interval must be a whole number of seconds between 5 and 300, the range an Application Load Balancer target-group health check accepts."
  }
}

# WHY : Assumptions: AWS requires this to be strictly less than
#       health_check_interval, so the two cannot be set independently -- a
#       timeout at or above the interval is rejected when the target group is
#       created. Keeping the default well below the interval also means a
#       probe cannot still be outstanding when the next one is due, which is
#       what makes each probe's result attributable to one interval.
variable "health_check_timeout" {
  description = <<-EOT
    Seconds a single health-check probe may take before AWS records it as a
    failure. Must be strictly smaller than health_check_interval, so the two
    are changed together.
  EOT
  type        = number
  default     = 5

  # WHY : Assumptions: 2 to 120 seconds is the accepted range, and the timeout must
  #       be STRICTLY LESS than health_check_interval. The load balancer refuses
  #       the equal case as well as the inverted one, and both are easy to reach by
  #       raising a timeout to chase a slow probe: the plan looks like a tuning
  #       change and the apply fails on a constraint neither value states on its
  #       own. Checking the pair here reports which two inputs disagree.
  #       Trade-offs: a timeout close to the interval leaves a probe barely any
  #       recovery margin, which this check permits rather than second-guesses --
  #       what it refuses is the configuration the service rejects outright.
  validation {
    condition     = var.health_check_timeout >= 2 && var.health_check_timeout <= 120 && floor(var.health_check_timeout) == var.health_check_timeout && var.health_check_timeout < var.health_check_interval
    error_message = "health_check_timeout must be a whole number of seconds between 2 and 120 AND strictly less than health_check_interval: a probe cannot be allowed longer to answer than the gap between probes, and the load balancer refuses the equal case too."
  }
}

# WHY : Trade-offs: set higher than unhealthy_threshold on purpose, and the
#       asymmetry is the whole point. Admitting a task that is not genuinely
#       ready sends real requests to a process that will fail them, whereas
#       removing a task that was in fact healthy only costs capacity the
#       scaling target replaces. Entering rotation is therefore made harder
#       than leaving it.
variable "healthy_threshold" {
  description = <<-EOT
    Consecutive successful probes required before the target group begins
    sending requests to a newly registered task. Deliberately larger than
    unhealthy_threshold, because a premature admission is paid for in failed
    requests and a premature removal only in capacity.
  EOT
  type        = number
  default     = 3

  # WHY : Assumptions: 2 to 10 consecutive successes is the range the target group
  #       accepts. A value of 1 is the one most likely to be tried, to shorten
  #       registration, and it is refused by the service rather than honoured.
  validation {
    condition     = var.healthy_threshold >= 2 && var.healthy_threshold <= 10 && floor(var.healthy_threshold) == var.healthy_threshold
    error_message = "healthy_threshold must be a whole number between 2 and 10, the range an Application Load Balancer target group accepts."
  }
}

# WHY : Refactoring Rationale: this replaces having no automatic recovery at
#       all. app/csd/CARDDEMO.CSD sets RESTART(NO) on all 18 transaction
#       stanzas, so a failed CICS task was not restarted and recovery was an
#       operator action. Health-check-driven replacement is therefore a
#       documented improvement on the migrated system rather than a port of
#       one of its behaviours, and this threshold is what decides how many
#       consecutive failures are tolerated first.
#       Trade-offs: set to the smallest value AWS accepts, so a task that has
#       genuinely stopped answering is removed after the fewest probes the
#       API allows while still requiring more than one -- a single failure
#       can be a lost packet rather than a broken task.
variable "unhealthy_threshold" {
  description = <<-EOT
    Consecutive failed probes after which the target group deregisters a task
    and ECS replaces it. Together with health_check_interval this is what
    bounds how long a wedged task can keep receiving requests.
  EOT
  type        = number
  default     = 2

  # WHY : Assumptions: 2 to 10 consecutive failures is the range the target group
  #       accepts, and the same bound applies as for the healthy threshold.
  validation {
    condition     = var.unhealthy_threshold >= 2 && var.unhealthy_threshold <= 10 && floor(var.unhealthy_threshold) == var.unhealthy_threshold
    error_message = "unhealthy_threshold must be a whole number between 2 and 10, the range an Application Load Balancer target group accepts."
  }
}

# WHY : Assumptions: a short delay is safe here specifically because the
#       services hold no session state. The migrated system carried
#       continuity between screen turns in one passed structure --
#       app/cpy/COCOM01Y.cpy L19-L44 declares CARDDEMO-COMMAREA with its
#       navigation, identity, selection and re-entry fields -- and that
#       structure is deliberately not reproduced server side: navigation
#       became client-side routing, identity became signed token claims,
#       selection became request path parameters, and the re-entry
#       discriminator has no successor at all. Draining a task therefore
#       cannot orphan anything a later request needs, so this delay only has
#       to outlast requests already in flight. It is not zero for exactly
#       that reason -- zero would cut those off.
variable "deregistration_delay" {
  description = <<-EOT
    Seconds the load balancer waits for in-flight requests to finish before
    completing deregistration of a task. Only in-flight requests are at
    stake, because the services keep no server-side session state, so this
    does not have to cover a user's think time.
  EOT
  type        = number
  default     = 30

  # WHY : Assumptions: 0 to 3600 seconds is the accepted range. Zero is legal and
  #       means a draining target is removed at once, which this module permits
  #       because a caller may deliberately trade in-flight requests for a faster
  #       deployment; what is refused is a value the service rejects at apply.
  validation {
    condition     = var.deregistration_delay >= 0 && var.deregistration_delay <= 3600 && floor(var.deregistration_delay) == var.deregistration_delay
    error_message = "deregistration_delay must be a whole number of seconds between 0 and 3600, the range an Application Load Balancer target group accepts."
  }
}

# WHY : Assumptions: without a grace period a JVM service cannot start at all
#       under a load balancer. The container is registered as soon as it is
#       running, but the Actuator health endpoint does not report UP until the
#       Spring context has finished refreshing, so the first probes fail and
#       ECS kills the task before it can ever pass -- a loop that looks like
#       a crash and is not one.
#       Assumptions: aws_ecs_service REJECTS this argument on a service with
#       no load balancer rather than ignoring it, so main.tf must resolve it
#       to null whenever attach_load_balancer is false. The coupling is
#       recorded on both variables, because setting either one alone is what
#       breaks.
variable "health_check_grace_period_seconds" {
  description = <<-EOT
    Seconds after a task starts during which ECS disregards failing
    load-balancer health checks, giving the Spring context time to finish
    refreshing before the first probe is allowed to count. Applies only when
    attach_load_balancer is true; the module resolves it to null otherwise,
    because AWS rejects the argument on a service with no load balancer.
  EOT
  type        = number
  default     = 60

  # WHY : Assumptions: 0 to 2147483647 is the range aws_ecs_service accepts, and
  #       the argument is only ever sent when attach_load_balancer is true -- the
  #       module resolves it to null otherwise, because AWS rejects it outright on
  #       a service with no load balancer. The bound is checked here regardless of
  #       that flag, so a value left behind in a root's variables cannot become
  #       invalid later merely by attaching a load balancer.
  #       Trade-offs: no upper guidance is imposed beyond the API's. A long grace
  #       period delays the detection of a task that never becomes healthy, and a
  #       short one deregisters a task still refreshing its context; that balance
  #       depends on the image and belongs to the caller.
  validation {
    condition     = var.health_check_grace_period_seconds >= 0 && floor(var.health_check_grace_period_seconds) == var.health_check_grace_period_seconds
    error_message = "health_check_grace_period_seconds must be a whole number of seconds and cannot be negative; it is applied only when attach_load_balancer is true, because AWS rejects the argument on a service with no load balancer."
  }
}

# -----------------------------------------------------------------------------
# The service itself.
# -----------------------------------------------------------------------------

# WHY : Alternatives Considered: the rejected `ecs-task` module recorded in
#       the shape note above the load-balancer section -- a second module
#       holding only the task definition, the two roles and the log group
#       would have duplicated the majority of this one. This flag is what
#       lets the single module emit either shape.
#       Assumptions: the coupling in main.tf is wider than the name suggests.
#       False suppresses the ECS service AND the autoscaling target, because
#       a scaling policy with no service has nothing to act on, which is why
#       enable_autoscaling is gated on this flag rather than independent of
#       it. The task definition, the task role, the execution role and the
#       log group are still created -- exactly the set the
#       step-functions-batch module needs in order to wire a task definition
#       into a state that runs it. The service and autoscaling outputs turn
#       null in that case.
variable "create_service" {
  description = <<-EOT
    Whether to create a long-running ECS service around the task definition.
    True for the seven online services. Set false for the TWO task-only
    workloads, batch and data-migration, whose tasks Step Functions starts one at
    a time; that leaves the task definition, both IAM roles and the log group in
    place for step-functions-batch to reference, and suppresses the service, the
    autoscaling target and the outputs describing them. It also selects the
    metrics path: a task-only workload has nothing to scrape, so the module
    enables Micrometer's OTLP push instead.
  EOT
  type        = bool
  default     = true
}
# WHY : Assumptions: this is the INITIAL count only. main.tf stops tracking it
#       afterwards, because Application Auto Scaling owns the running count
#       once the target is registered; without that exclusion every plan
#       after the first scaling event would propose reverting the count, so
#       Terraform and the scaling policy would fight over one field. A root
#       wanting a different floor should raise min_capacity, not this.
#       Trade-offs: two rather than one. One task is cheaper and can serve
#       traffic, but it puts the whole service in a single availability zone,
#       which makes the three-zone subnet list it is placed across pointless
#       and turns one zone's loss into an outage. Two is the smallest count
#       that survives that, and it is one of the three axes on which the dev
#       and prod roots are expected to differ.
variable "desired_count" {
  description = <<-EOT
    Task count the ECS service is created with. Only the initial value: the
    module stops tracking it afterwards so Application Auto Scaling can own
    the running count without every later plan proposing to undo it. Use
    min_capacity to raise the floor the service is held at. Expected to
    differ between the dev and prod roots.
  EOT
  type        = number
  default     = 2

  # WHY : Assumptions: this is only read when create_service is true, and it must be
  #       at least one when it is: aws_ecs_service accepts zero, which creates a
  #       service that registers no task at all, so the target group is empty and
  #       every request through the load balancer answers 503 with nothing in the
  #       plan indicating why.
  #       Assumptions: the count must also sit inside the autoscaling capacity
  #       range when autoscaling is enabled. Application Auto Scaling adjusts an
  #       out-of-range count on its first evaluation, so a desired_count of one
  #       under a min_capacity of two is not rejected anywhere -- it is silently
  #       corrected minutes later, which makes the value in source wrong without
  #       anything ever saying so.
  validation {
    condition     = !var.create_service || (var.desired_count >= 1 && floor(var.desired_count) == var.desired_count)
    error_message = "desired_count must be a whole number of at least 1 whenever create_service is true; a service created with zero tasks leaves its target group empty, so every request through the load balancer answers 503."
  }

  validation {
    condition     = !(var.create_service && var.enable_autoscaling) || (var.desired_count >= var.min_capacity && var.desired_count <= var.max_capacity)
    error_message = "desired_count must lie between min_capacity and max_capacity when autoscaling is enabled: Application Auto Scaling silently corrects an out-of-range count on its first evaluation, so the value recorded here would never be the one running."
  }
}

# WHY : Refactoring Rationale: main.tf previously set launch_type = "FARGATE",
#       which is mutually exclusive with a capacity-provider strategy and
#       bypassed the cluster's capacity-provider model entirely. Expressing the
#       same on-demand choice as a one-entry FARGATE strategy keeps today's
#       non-interruptible posture while making the placement mechanism
#       consistent with the cluster and allowing an environment to opt into
#       FARGATE_SPOT explicitly instead of editing the module.
# WHY : Trade-offs: the default deliberately does NOT inherit the cluster's
#       mixed FARGATE/FARGATE_SPOT default. Interactive sign-on, account, card
#       and transaction requests must not be terminated on a Spot reclaim
#       notice, so this service overrides the cluster with on-demand Fargate.
#       Step-Functions RunTask callers that name no strategy still inherit the
#       cluster default; a caller that wants Spot here must state it in this
#       input, making the availability trade visible in the environment root.
variable "capacity_provider_strategy" {
  description = <<-EOT
    Per-service ECS capacity-provider strategy. Defaults to one on-demand
    FARGATE entry, replacing the former launch_type = "FARGATE" with the
    capacity-provider mechanism. A supplied strategy replaces the cluster
    default; use FARGATE_SPOT only as an explicit availability/cost decision.
  EOT

  type = list(object({
    capacity_provider = string
    weight            = optional(number, 1)
    base              = optional(number, 0)
  }))

  default = [{
    capacity_provider = "FARGATE"
    weight            = 1
    base              = 1
  }]

  validation {
    condition = length(var.capacity_provider_strategy) >= 1 && length(var.capacity_provider_strategy) <= 2 && alltrue([
      for entry in var.capacity_provider_strategy :
      contains(["FARGATE", "FARGATE_SPOT"], entry.capacity_provider)
    ])
    error_message = "capacity_provider_strategy must contain one or two entries and may name only FARGATE or FARGATE_SPOT, the two providers associated by ecs-cluster."
  }

  validation {
    condition = length(distinct([
      for entry in var.capacity_provider_strategy : entry.capacity_provider
    ])) == length(var.capacity_provider_strategy)
    error_message = "capacity_provider_strategy must not repeat a capacity provider; duplicate entries make the effective weight ambiguous and ECS rejects them."
  }

  validation {
    condition = alltrue([
      for entry in var.capacity_provider_strategy :
      entry.weight >= 0 && entry.weight <= 1000 &&
      floor(entry.weight) == entry.weight &&
      entry.base >= 0 && entry.base <= 100000 &&
      floor(entry.base) == entry.base
    ])
    error_message = "Each capacity-provider weight must be a whole number from 0 to 1000 and each base a whole number from 0 to 100000, matching the ECS service API ranges."
  }

  validation {
    condition = anytrue([
      for entry in var.capacity_provider_strategy : entry.weight > 0
      ]) && length([
      for entry in var.capacity_provider_strategy : entry if entry.base > 0
    ]) <= 1
    error_message = "At least one capacity-provider entry must have weight greater than zero, and at most one entry may carry a non-zero base."
  }
}

# WHY : Refactoring Rationale: this defaulted to LATEST, and the reasoning for
#       that -- a pin would go stale in sixteen places and a stale pin is how a
#       platform reaches end of support while every plan reports no changes --
#       had the trade-off backwards. LATEST is resolved by ECS at task launch,
#       not at apply, so the platform underneath a running service can change
#       with no Terraform diff, no plan, and no record of when it changed. For a
#       stack whose parity is judged by comparing output bytes against a golden
#       master, an undated runtime substitution is the wrong kind of surprise:
#       when a result moves, the first question is what changed, and LATEST makes
#       that unanswerable from the repository. A pin makes the platform a
#       reviewable value like every other version in this migration.
#       Trade-offs: the pin does have to be raised deliberately, and the cost is
#       exactly the one the earlier note describes -- one edit, in one module
#       default, which both roots inherit. That is a single place rather than
#       sixteen, because the roots are not expected to override this: platform is
#       topology, and dev and prod are required to differ only in sizing and
#       retention. Staleness is answered by the version appearing in every plan
#       and in this module's generated README, so it is visible rather than
#       implicit.
#       Assumptions: 1.4.0 is the current Fargate Linux platform version and the
#       one this configuration is validated against. AWS has published no 1.5.x
#       Linux platform, and 1.3.0 is retired, so the pin names the only supported
#       Linux build rather than an arbitrary point in history.
variable "platform_version" {
  description = <<-EOT
    Value of the platform_version argument of aws_ecs_service, selecting the
    Fargate platform the tasks run on. Pinned to 1.4.0, the current Fargate
    Linux platform version, so a platform change is a reviewed edit rather than
    a silent substitution at task launch. Raise it deliberately when AWS ships a
    successor. Supplied by the caller as a literal, not from another module's
    output.
  EOT
  type        = string
  default     = "1.4.0"

  # WHY : Assumptions: the value is either an explicit x.y.z platform version or
  #       the literal LATEST, which the provider accepts and which this module
  #       does not choose by default. LATEST is not refused outright, because a
  #       root deliberately tracking the current platform is a legitimate choice
  #       to make explicitly -- what this module declines to do is make it
  #       silently. Any other string is a typo the API rejects at apply while
  #       creating the service, in an error that names the argument rather than
  #       this input.
  validation {
    condition     = var.platform_version == "LATEST" || can(regex("^[0-9]+\\.[0-9]+\\.[0-9]+$", var.platform_version))
    error_message = "platform_version must be an explicit Fargate platform version such as \"1.4.0\", or the literal \"LATEST\" if a root deliberately chooses to track the current platform. Anything else is rejected by ECS at apply."
  }
}

# WHY : Alternatives Considered: blue-green deployment and canary
#       deployment, both of which are out of scope for this migration --
#       rolling ECS deployment only. This pair of percentages IS the rolling
#       strategy: holding the minimum at 100 while allowing the maximum to
#       reach 200 lets ECS start replacement tasks alongside the running ones
#       and retire the old ones only once the new ones are healthy, so
#       capacity never dips below the full desired count. Blue-green would
#       need a second target group and a traffic-shifting controller, and
#       canary would need weighted routing; neither appears anywhere in this
#       module, and this comment is the record of why nothing here resembles
#       either of them.
variable "deployment_minimum_healthy_percent" {
  description = <<-EOT
    Minimum percentage of desired_count that must stay RUNNING and healthy
    while a deployment is in progress. At 100 the rolling deployment never
    reduces serving capacity: replacements come up before their predecessors
    go down.
  EOT
  type        = number
  default     = 100

  # WHY : Assumptions: 0 to 100 is the range aws_ecs_service accepts for this
  #       percentage. The rolling strategy this pair expresses depends on the value
  #       being 100, but lower values are legal and are a deliberate choice for a
  #       service that can shed capacity during a deployment, so the bound is the
  #       API's rather than the strategy's.
  validation {
    condition     = var.deployment_minimum_healthy_percent >= 0 && var.deployment_minimum_healthy_percent <= 100 && floor(var.deployment_minimum_healthy_percent) == var.deployment_minimum_healthy_percent
    error_message = "deployment_minimum_healthy_percent must be a whole percentage between 0 and 100, the range aws_ecs_service accepts."
  }
}

# WHY : Trade-offs: the upper half of the same rolling strategy, and it is
#       what makes a minimum of 100 achievable at all -- with no headroom
#       above the desired count ECS would have to stop a task before it could
#       start its replacement, contradicting the minimum. Allowing double
#       means a deployment briefly runs two full sets of tasks, and that
#       transient cost is the accepted price of never dropping below full
#       capacity.
variable "deployment_maximum_percent" {
  description = <<-EOT
    Maximum percentage of desired_count ECS may run at once during a
    deployment. At 200 a full replacement set can start before the outgoing
    set is stopped, which is what allows deployment_minimum_healthy_percent
    to stay at 100.
  EOT
  type        = number
  default     = 200

  # WHY : Assumptions: 100 to 200 is the range this module admits, and the maximum
  #       must be STRICTLY GREATER than deployment_minimum_healthy_percent. ECS
  #       refuses a deployment whose two percentages leave it no room to replace a
  #       task -- a minimum of 100 with a maximum of 100 permits neither starting a
  #       replacement first nor stopping the old task first -- and it does so when
  #       the deployment is attempted, not when the service is created. The
  #       configuration therefore applies cleanly and then wedges on the next
  #       image, which is the worst place to find it.
  #       Trade-offs: the pair is checked here rather than on the minimum, so a
  #       reader raising either one meets the constraint at the value they are
  #       raising toward.
  validation {
    condition     = var.deployment_maximum_percent >= 100 && var.deployment_maximum_percent <= 200 && var.deployment_maximum_percent > var.deployment_minimum_healthy_percent && floor(var.deployment_maximum_percent) == var.deployment_maximum_percent
    error_message = "deployment_maximum_percent must be a whole percentage between 100 and 200 AND strictly greater than deployment_minimum_healthy_percent: equal percentages leave ECS no room to replace a task, which wedges the next deployment rather than failing this apply."
  }
}

# WHY : Assumptions: this is a property of the ROLLING deployment controller,
#       and it needs saying plainly because the word rollback invites a
#       reader to look for the blue-green setup that is deliberately absent.
#       It adds no second target group, creates no alternate task set and
#       shifts no traffic by weight; it watches whether the replacement tasks
#       of the same rolling deployment reach a steady state and, if they do
#       not, restores the previous task definition. Nothing about it is out of
#       scope.
#       Refactoring Rationale: it is also the deployment-time counterpart to
#       what unhealthy_threshold does at run time. The migrated system had
#       neither -- app/csd/CARDDEMO.CSD sets RESTART(NO) on all 18
#       transaction stanzas -- so there a bad deployment stayed bad until an
#       operator intervened.
variable "enable_deployment_circuit_breaker" {
  description = <<-EOT
    Whether ECS aborts a rolling deployment whose tasks never reach a steady
    state and restores the previous task definition. Operates entirely within
    the rolling controller: no second target group, no alternate task set and
    no weighted traffic shifting are involved.
  EOT
  type        = bool
  default     = true
}

# -----------------------------------------------------------------------------
# Autoscaling.
# -----------------------------------------------------------------------------

# WHY : Alternatives Considered: the rejected `ecs-task` module recorded in
#       the shape note above the load-balancer section; this is the third of
#       the three flags that let one module emit both shapes.
#       Assumptions: it is gated on create_service in main.tf and is not
#       independent of it. An Application Auto Scaling target names the
#       service it scales through the resource_id
#       service/<cluster-name>/<service-name>, so with no service there is no
#       target to register and nothing for a policy to adjust; leaving this
#       true while create_service is false would ask AWS to scale something
#       that does not exist. Both autoscaling outputs turn null whenever this
#       resolves to disabled.
variable "enable_autoscaling" {
  description = <<-EOT
    Whether to register an Application Auto Scaling target for the service
    and attach a CPU target-tracking policy to it. Required whenever
    create_service is true so Application Auto Scaling is the sole runtime
    owner of desired_count; the batch instance leaves both false.
  EOT
  type        = bool
  default     = true

  # WHY : Assumptions: autoscaling requires a service to scale. With create_service
  #       false there is no ECS service for a scalable target to register against,
  #       so main.tf gates the target on that flag; asking for autoscaling in that
  #       shape is a request the module silently drops rather than honours, and a
  #       silently dropped scaling policy is indistinguishable from one that is
  #       working until load arrives. Refusing the combination here makes the batch
  #       shape state both flags explicitly.
  validation {
    condition     = !var.enable_autoscaling || var.create_service
    error_message = "enable_autoscaling requires create_service: without a service there is nothing for a scalable target to register against, so the scaling policy would be dropped silently. The batch shape sets both to false."
  }

  # WHY : Refactoring Rationale: aws_ecs_service ignores desired_count drift
  #       unconditionally so the scaler can own it. A long-running service with
  #       autoscaling disabled would therefore have no owner reconciling that
  #       value. Refusing that shape keeps the lifecycle rule truthful instead
  #       of preserving an unsupported fixed-size mode.
  validation {
    condition     = !var.create_service || var.enable_autoscaling
    error_message = "create_service requires enable_autoscaling: the service lifecycle ignores desired_count so Application Auto Scaling must be its runtime owner. The batch shape sets both flags false."
  }
}

# WHY : Assumptions: matched to the desired_count default rather than set
#       lower, because the two answer the same question at different times --
#       desired_count is where the service starts and this is where scaling is
#       allowed to leave it -- and a floor below the starting count would let
#       the first scale-in event undo the multi-zone redundancy that starting
#       count was chosen for. Keeping them equal by default means a root that
#       raises one is prompted to look at the other.
variable "min_capacity" {
  description = <<-EOT
    Lowest task count the scaling policy may reduce the service to. Held at
    the same value as the desired_count default so scale-in cannot drop the
    service below the multi-zone redundancy it starts with.
  EOT
  type        = number
  default     = 2

  # WHY : Assumptions: at least one task, for the same reason desired_count needs
  #       one: a floor of zero lets the scaling policy drain a load-balanced
  #       service to no tasks at all, and the service then answers 503 while
  #       reporting itself scaled correctly. The upper bound is checked against
  #       max_capacity on that variable.
  validation {
    condition     = !var.enable_autoscaling || (var.min_capacity >= 1 && floor(var.min_capacity) == var.min_capacity)
    error_message = "min_capacity must be a whole number of at least 1 when autoscaling is enabled; a floor of zero lets the policy drain the service to no tasks, which answers 503 while reporting a successful scale-in."
  }
}

# WHY : Trade-offs: a bounded ceiling rather than a generous one. The point of
#       the bound is that a runaway scale-out -- driven by a fault that raises
#       CPU rather than by real demand -- is capped at a known number of tasks
#       instead of consuming whatever the account allows, and the cost is that
#       genuine demand beyond the ceiling goes unmet until a root raises it. A
#       ceiling several times the floor leaves the target-tracking policy room
#       to act while keeping the cap small enough to be a meaningful bound.
variable "max_capacity" {
  description = <<-EOT
    Highest task count the scaling policy may grow the service to. Bounds the
    blast radius of a scale-out driven by a fault rather than by demand; raise
    it in the root when real demand needs more than the default headroom above
    min_capacity.
  EOT
  type        = number
  default     = 6

  # WHY : Assumptions: the ceiling must be at least the floor. Application Auto
  #       Scaling refuses a scalable target whose maximum is below its minimum, and
  #       it refuses it when the target is registered -- partway through an apply
  #       that has already created the service. The equal case is permitted, and it
  #       is a legitimate way to pin a service at a fixed size while keeping the
  #       target registered.
  validation {
    condition     = !var.enable_autoscaling || (var.max_capacity >= var.min_capacity && floor(var.max_capacity) == var.max_capacity)
    error_message = "max_capacity must be a whole number greater than or equal to min_capacity: Application Auto Scaling refuses a scalable target whose ceiling is below its floor, and it does so after the service has already been created."
  }
}

# WHY : Alternatives Considered: step scaling, which reacts to alarm
#       thresholds. Rejected because it needs hand-tuned thresholds and the
#       migrated system supplies no data to tune them with -- all 18
#       transaction stanzas in app/csd/CARDDEMO.CSD share one
#       undifferentiated PRIORITY(1) and the single TRANCLASS(DFHTCL00)
#       dispatch class, so the baseline never distinguished a heavy
#       transaction from a light one and there is nothing to derive
#       per-service thresholds from. Target tracking needs exactly one number
#       and derives the rest, which is the honest choice when one number is
#       the only defensible input.
#       Trade-offs: the band the validation enforces is narrower than the full
#       percentage range AWS accepts. Below the lower bound the target is
#       effectively unreachable, so the policy scales out until it reaches
#       max_capacity and stays there; above the upper bound there is no
#       headroom left for added tasks to become useful before the running ones
#       saturate. Both ends are rejected here rather than discovered later.
variable "autoscaling_target_cpu_utilization" {
  description = <<-EOT
    Average CPU utilisation percentage across the service's tasks that the
    target-tracking policy tries to maintain, adding tasks above it and
    removing them below. One number is the whole input; the policy derives its
    own thresholds from it.
  EOT
  type        = number
  default     = 70

  validation {
    condition = alltrue([
      var.autoscaling_target_cpu_utilization >= 10,
      var.autoscaling_target_cpu_utilization <= 90,
    ])
    error_message = join(" ", [
      "autoscaling_target_cpu_utilization must be between 10 and 90.",
      "A lower target is unreachable in practice and pins the service at",
      "max_capacity; a higher one leaves no headroom for added tasks to",
      "take effect before the running tasks saturate.",
    ])
  }
}

# WHY : Assumptions: scale-in is safe at all only because the services are
#       stateless -- no sticky sessions and no server-side session store,
#       which is what makes tasks behind a load balancer interchangeable and
#       therefore removable. Removing a task from a stateful service would
#       strand whatever that task held.
#       Trade-offs: set longer than the scale-out cooldown on purpose. The two
#       mistakes are not symmetric: capacity added and not needed costs task
#       time, while capacity removed and then needed costs requests arriving
#       before a replacement is serving. The longer wait is placed on the side
#       whose mistake is paid for by users.
variable "autoscaling_scale_in_cooldown" {
  description = <<-EOT
    Seconds the target-tracking policy waits after removing tasks before it
    may remove more. Deliberately longer than the scale-out cooldown, because
    over-removing capacity is paid for in failed requests while over-adding it
    is paid for in task time.
  EOT
  type        = number
  default     = 300

  # WHY : Assumptions: a cooldown is a whole, non-negative number of seconds. Zero
  #       is legal and means every evaluation may act, which is a deliberate choice
  #       for a service that scales cheaply; a negative value is refused by the
  #       API at apply, after the scaling policy is being created.
  validation {
    condition     = var.autoscaling_scale_in_cooldown >= 0 && floor(var.autoscaling_scale_in_cooldown) == var.autoscaling_scale_in_cooldown
    error_message = "autoscaling_scale_in_cooldown must be a whole number of seconds and cannot be negative."
  }
}

# WHY : Trade-offs: the shorter of the two cooldowns, for the reason recorded
#       on autoscaling_scale_in_cooldown -- adding capacity that turns out to
#       be unnecessary is the cheaper mistake, so the policy is allowed to
#       make it sooner. It is not zero, because a new task is not serving the
#       instant it is counted, and stacking further scale-outs on a reading
#       taken before the previous ones took effect is how a policy overshoots
#       to max_capacity on a single demand spike.
variable "autoscaling_scale_out_cooldown" {
  description = <<-EOT
    Seconds the target-tracking policy waits after adding tasks before it may
    add more. Shorter than the scale-in cooldown, but non-zero so each
    decision is taken on a utilisation reading that already reflects the
    previous addition.
  EOT
  type        = number
  default     = 60

  # WHY : Assumptions: the same bound as the scale-in cooldown, checked separately
  #       so the message names the value that is wrong. The two are deliberately
  #       allowed to differ: scaling out fast and in slowly is the asymmetry a
  #       target-tracking policy is usually tuned for.
  validation {
    condition     = var.autoscaling_scale_out_cooldown >= 0 && floor(var.autoscaling_scale_out_cooldown) == var.autoscaling_scale_out_cooldown
    error_message = "autoscaling_scale_out_cooldown must be a whole number of seconds and cannot be negative."
  }
}

# -----------------------------------------------------------------------------
# Logging.
# -----------------------------------------------------------------------------

# WHY : Assumptions: CloudWatch Logs accepts only a fixed set of retention
#       values and rejects any other integer, so the validation restates that
#       set in order to move the rejection from apply to plan. The set is an
#       AWS contract this module depends on rather than something verified
#       here. Zero is included because AWS accepts it and it means never
#       expire; it is deliberately not the default, because indefinite
#       retention accrues storage charges without bound and nothing in this
#       migration requires logs to be kept forever.
#       Trade-offs: this is one of the three axes on which the dev and prod
#       roots are expected to differ, alongside task count and CPU or memory,
#       and it is why retention is an input at all rather than a fixed value
#       -- keeping development logs as long as production logs pays for
#       storage nobody reads.
variable "log_retention_in_days" {
  description = <<-EOT
    Retention period for the service's CloudWatch log group, in days, or 0 to
    keep events indefinitely. Must be one of the values CloudWatch Logs
    accepts. Expected to differ between the dev and prod roots, which are
    otherwise identical in topology.
  EOT
  type        = number
  default     = 30

  validation {
    condition = contains([
      0, 1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731,
      1096, 1827, 2192, 2557, 2922, 3288, 3653,
    ], var.log_retention_in_days)
    error_message = join(" ", [
      "log_retention_in_days must be 0, meaning never expire, or one of the",
      "retention periods CloudWatch Logs accepts: 1, 3, 5, 7, 14, 30, 60,",
      "90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922,",
      "3288 or 3653.",
    ])
  }
}

# WHY : Assumptions: null remains an explicit escape hatch for a throwaway
#       caller, because CloudWatch Logs still encrypts events with its service
#       key. The dev and prod roots are expected to pass the KMS module's S3
#       data-domain key: the AAP defines four customer-managed keys rather than a
#       fifth log key, and the KMS policy grants the regional Logs principal
#       account-and-region-scoped use of that key.
#       Trade-offs: keeping null expressible preserves standalone module
#       validation and a minimal test root, while the environment composition
#       owns the stricter production contract. A policy scan against dev or prod
#       remains the gate that refuses the escape hatch there.
variable "log_group_kms_key_arn" {
  description = <<-EOT
    ARN of a customer-managed KMS key to encrypt the service's log group with.
    Required unless allow_service_managed_log_encryption is explicitly set true,
    which is the opt-out reserved for planning this module in isolation without
    the kms module. Both environment roots pass the KMS module's S3 data-domain
    key.
  EOT
  type        = string
  default     = null

  # WHY : Assumptions: when a value IS supplied it has to be a KMS key ARN:
  #       a key id or an alias name type-checks as a string, plans cleanly, and is
  #       rejected by CloudWatch Logs at apply -- after the log group exists, which
  #       leaves a group encrypted with the service key while the configuration
  #       claims a customer-managed one.
  validation {
    condition     = var.log_group_kms_key_arn == null || can(regex("^arn:[a-z0-9-]+:kms:[a-z0-9-]+:[0-9]{12}:key/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", var.log_group_kms_key_arn))
    error_message = "log_group_kms_key_arn must be null, or a KMS key ARN beginning arn:<partition>:kms:. A bare key id or an alias name is accepted by Terraform here and then rejected by CloudWatch Logs at apply."
  }

  # WHY : Refactoring Rationale: this validation is NEW and it reverses how the null
  #       default behaves. The block above previously reasoned that null was a path
  #       that "must stay expressible" for a standalone test root, which made
  #       service-managed encryption the SILENT outcome of simply not passing the
  #       input. That is a control that fails OPEN: a caller who forgets the key gets
  #       a log group encrypted with the CloudWatch Logs service key and no diagnostic
  #       anywhere, and this module's log group carries application request logs. The
  #       requirement is now explicit and the escape hatch has to be asked for by name.
  #       Alternatives Considered: dropping the default so the input becomes required
  #       outright. Rejected because it removes the isolated-plan path entirely, which
  #       the sibling network module keeps for the same reason -- planning one module
  #       without instantiating kms is a legitimate development action.
  #       Assumptions: neither environment root sets the opt-out, and both pass a real
  #       key, so this changes nothing about a correct deployment and rejects only the
  #       configurations that were previously silent.
  validation {
    condition     = var.log_group_kms_key_arn != null || var.allow_service_managed_log_encryption
    error_message = "log_group_kms_key_arn is required: this service's log group carries application request logs and must be encrypted with a customer-managed key. Pass the kms module's key ARN. To plan this module in isolation without the kms module, set allow_service_managed_log_encryption = true explicitly, which is not a supported configuration for either environment root."
  }
}

# WHY : Assumptions: this input exists ONLY so that the fail-closed requirement on
#       log_group_kms_key_arn has an explicit escape hatch, and it is declared as its
#       own variable so that using the hatch is a visible line in a caller's module
#       block rather than an absence a reviewer has to notice. It mirrors
#       allow_service_managed_flow_log_encryption in the sibling network module
#       deliberately: the same defect existed in both, so the same remedy is spelled
#       the same way and a reader who has understood one has understood both.
# WHY : Trade-offs: a bool rather than reusing a broader "test mode" flag. A single
#       coarse flag would couple this encryption decision to every other
#       isolation-only concession, so relaxing one would silently relax the rest.
variable "allow_service_managed_log_encryption" {
  description = "Explicit opt-out permitting the service log group to fall back to CloudWatch Logs service-managed encryption when log_group_kms_key_arn is null. Intended only for planning this module in isolation without the kms module; neither environment root sets it."
  type        = bool
  default     = false
  nullable    = false
}

# WHY : Refactoring Rationale: every service already exposes Micrometer metrics
#       on its Actuator Prometheus endpoint, but an endpoint with no scraper is
#       not centralized telemetry. The sidecar closes that path in the same task
#       network namespace and exports metrics and traces without making the
#       application image own AWS-specific collector configuration.
#       Trade-offs: enabled by default because an optional collector would let
#       a root produce a deployment whose dashboards exist but never receive
#       application metrics. The collector is essential, so a bad configuration
#       fails task placement visibly instead of leaving a healthy-looking task
#       with a silent telemetry gap.
variable "enable_telemetry_collector" {
  description = <<-EOT
    Whether to add the AWS Distro for OpenTelemetry collector sidecar that
    receives this workload's telemetry and exports it: traces over OTLP to
    X-Ray, and metrics to CloudWatch EMF. Metrics reach it one of two ways,
    selected by create_service so that no meter is exported twice -- a serving
    workload's Actuator Prometheus endpoint is scraped over loopback, while a
    task-only workload pushes through Micrometer's OTLP registry.
  EOT
  type        = bool
  default     = true
}

# WHY : Refactoring Rationale: the default was v0.48.0 named by TAG, and both
#       halves of that changed. v0.48.0 has been superseded upstream -- verified
#       against the publishing registry, whose tag list for this repository carries
#       84 entries of which the highest semantic version is v0.49.0 and `latest` is
#       the only non-semver one -- and a tag is not a pin. A tag is a label the
#       publisher can move, so "pinned by tag" means "pinned to whatever that label
#       resolves to on the day of the pull", which for the one container attached to
#       every workload in the estate is the weakest place in the supply chain.
#       The default is now the v0.49.0 OCI INDEX digest, read two independent ways
#       that agree: the registry's own Docker-Content-Digest header and
#       `docker buildx imagetools inspect`, which also confirms the index carries
#       linux/amd64 and linux/arm64 manifests.
# WHY : Trade-offs: the digest form drops the human-readable version from the
#       value, so the version is stated here and in the description instead. That
#       is accepted for the same reason the base-image pins in every Dockerfile take
#       the same shape: the digest is what is enforced and the version is what is
#       read. Refreshing it is a two-line change -- this default and the mirror pull
#       in .github/workflows/deploy.yml -- and a gate in
#       .github/workflows/infra-ci.yml keeps the two roots' tag from drifting from
#       the workflow's.
# WHY : Assumptions: the pure `@sha256:` form is used rather than `:tag@sha256:`.
#       Both are accepted by container tooling, but the ECS container-definition
#       `image` field is documented for the tag form OR the digest form, so the
#       combined spelling would rest on an undocumented acceptance for the sake of
#       carrying a version string that a comment carries instead.
# WHY : Refactoring Rationale: the validation REQUIRED a `public.ecr.aws`
#       reference, and that requirement made every task unstartable rather than
#       merely public. infra/modules/network enumerates the application tier's
#       egress instead of allowing 0.0.0.0/0, and the public registry has neither
#       an interface endpoint nor a managed prefix list, so the sidecar image
#       could not be pulled at all -- and because the sidecar is created for every
#       workload by default, no task in the environment could start while
#       `terraform plan` reported nothing. A PRIVATE registry reference is now
#       admissible and is what both roots pass, from the mirror repository
#       infra/modules/ecr provisions.
#       Assumptions: the public form is still admitted, deliberately. A caller
#       that has its own controlled egress -- or a local plan that never runs a
#       task -- can keep the upstream reference, so this input widens rather than
#       switches. What is NOT admitted is an unpinned reference, in either form.
#       Alternatives Considered: hard-requiring the private form, which would
#       have made the module unusable outside this deployment's network shape.
#       Rejected because a module input should not encode one root's egress
#       policy; the roots express that by what they pass.
variable "telemetry_collector_image" {
  description = <<-EOT
    Pinned AWS Distro for OpenTelemetry collector image used by the telemetry
    sidecar. Either a private Amazon ECR reference -- which is what both
    environment roots pass, from the mirror repository the ecr module provisions,
    because the application tier's egress is enumerated and admits no public
    registry -- or the upstream public reference for a caller whose egress
    reaches it. A private reference must carry an explicit non-latest tag or a
    digest; the public reference must carry a digest, because only the private
    registry is configured for immutable tags. The default is the upstream
    v0.49.0 index digest. Collector upgrades therefore stay reviewed
    task-definition changes rather than something a moved label delivers.
  EOT
  type        = string
  default     = "public.ecr.aws/aws-observability/aws-otel-collector@sha256:d2bdfff2c377c3d71d78bd5d9ce9862fd535b12134a5739d87a07801297cf9fd"

  # WHY : Refactoring Rationale: the public form must now be DIGEST-pinned, where it
  #       previously accepted a tag. The two registries do not offer the same
  #       guarantee: infra/modules/ecr sets image_tag_mutability to IMMUTABLE, so a
  #       tag in the private form cannot be moved onto different bytes and is a pin
  #       in practice, while nothing constrains a tag in a public registry this
  #       repository does not control. Admitting a public tag therefore admitted a
  #       reference whose meaning can change with no diff anywhere -- which is
  #       exactly what a pin is supposed to prevent, and which the previous comment
  #       claimed the explicit tag already prevented.
  #       Trade-offs: a caller keeping the upstream reference now has to look up a
  #       digest, which is one registry query. Accepted: that caller is bypassing
  #       the mirror and so has no immutability from the registry either, which is
  #       the case that needs the digest most.
  validation {
    condition = (
      (
        can(regex("^public\\.ecr\\.aws/aws-observability/aws-otel-collector@sha256:[a-f0-9]{64}$", var.telemetry_collector_image)) ||
        can(regex("^[0-9]{12}\\.dkr\\.ecr\\.[a-z0-9-]+\\.amazonaws\\.com/[a-z0-9._/-]+(:[A-Za-z0-9._-]+|@sha256:[a-f0-9]{64})$", var.telemetry_collector_image))
      ) &&
      !endswith(lower(var.telemetry_collector_image), ":latest")
    )
    error_message = "telemetry_collector_image must be either the upstream public AWS observability collector image pinned by @sha256 digest, or a private Amazon ECR reference carrying an explicit non-latest tag or a digest. A public TAG is refused because only the private registry is configured for immutable tags, so only there is a tag a pin."
  }
}

# WHY : Assumptions: this is a SECOND repository ARN rather than a widening of
#       ecr_repository_arn, and the separation is the least-privilege point. The
#       task execution role must pull two images when the sidecar is enabled --
#       the service's own and the mirrored collector -- and the alternative was to
#       accept a list and let a caller pass any number of repositories. A named
#       second input says exactly which second image the role may fetch, and the
#       statement in main.tf compacts a null away, so a caller that supplies no
#       mirror grants no second repository.
# WHY : Assumptions: nullable with a null default, because the collector may be
#       disabled and because a caller keeping the public reference has no
#       repository to name. Requiring it would force every caller into the
#       mirrored shape this deployment happens to use.
variable "telemetry_collector_repository_arn" {
  description = <<-EOT
    ARN of the Amazon ECR repository holding the mirrored telemetry collector
    image, added to the task execution role's image-pull statement so the sidecar
    can be fetched. Null when the collector is disabled or when
    telemetry_collector_image names a registry this role needs no grant for, in
    which case no second repository is authorized.
  EOT
  type        = string
  default     = null

  validation {
    condition = (
      var.telemetry_collector_repository_arn == null ||
      can(regex("^arn:[a-z0-9-]+:ecr:[a-z0-9-]+:[0-9]{12}:repository/[a-z0-9._/-]+$", var.telemetry_collector_repository_arn))
    )
    error_message = "telemetry_collector_repository_arn must be null or an ECR repository ARN of the form arn:<partition>:ecr:<region>:<account>:repository/<name>; a repository name or an image URI produces an IAM statement matching no repository, so the sidecar fails to pull."
  }
}

# WHY : Trade-offs: successful traffic is sampled to bound X-Ray ingest volume,
#       while status-code ERROR traces are a separate tail-sampling policy and
#       are retained independently. This is a cost control rather than a
#       service-level objective, so the environment root may choose the value.
variable "telemetry_success_sample_percentage" {
  description = <<-EOT
    Percentage of successful traces retained by the collector after its
    always-keep-error policy. Accepts 0 through 100 and may differ by
    environment without changing task topology.
  EOT
  type        = number
  default     = 5

  validation {
    condition     = var.telemetry_success_sample_percentage >= 0 && var.telemetry_success_sample_percentage <= 100
    error_message = "telemetry_success_sample_percentage must be between 0 and 100 inclusive."
  }
}

# -----------------------------------------------------------------------------
# Configuration injection.
# -----------------------------------------------------------------------------

# WHY : Assumptions: everything passed here is readable by anyone who can
#       describe the task definition, because a container definition's
#       environment entries are stored and returned in clear text. That is
#       exactly why the split with ssm_parameter_arns and secret_sources exists:
#       those two carry a REFERENCE that ECS resolves at task start, so the
#       value never enters the task definition and never enters a state or
#       plan file. A credential placed here instead would be written to state
#       and printed in plan output, which is what the no-secrets constraint
#       forbids.
#       Trade-offs: the empty default keeps the common case -- a service
#       needing only its active profile and a handful of references -- free of
#       ceremony.
#       Refactoring Rationale: the split with ssm_parameter_arns and secret_sources
#       was described here and enforced nowhere, and a described split is not a
#       control. Anything a caller put in this map was accepted, so the one
#       mistake the paragraph above warns against -- a credential passed as a
#       literal -- was the mistake this module made easiest to make and hardest
#       to notice. It does not fail: the task starts, the service connects, and
#       the credential is durably readable in the task definition, in every
#       `describe-task-definition` response, in plan output, and in Terraform
#       state, to every principal who can read any of those. That is a wider
#       audience than the secret store's read policy, and nothing reports the
#       difference. The three validations below turn the paragraph into a
#       plan-time gate: the first fixes the shape, the second admits only the
#       key namespaces the nine workloads actually read, and the third refuses a
#       name that says it carries a secret.
variable "environment_variables" {
  description = <<-EOT
    Non-secret environment variables for the container, as a map of name to
    literal value: the active Spring profile and similar plain settings. Values
    appear in clear text in the task definition, in plan output and in state, so
    anything sensitive belongs in ssm_parameter_arns or secret_sources instead --
    and a name that reads as a secret is refused here rather than trusted.
    Accepted keys are upper-case, begin with one of the namespaces the CardDemo
    services read, and do not end in a secret-bearing word.
  EOT
  type        = map(string)
  default     = {}

  # WHY : Assumptions: the shape is checked first and separately, because the
  #       later two checks reason about upper-case underscore-delimited
  #       segments and a lower-case or hyphenated key would slip past both. A
  #       container definition will accept almost any string as a name, but a
  #       Spring Boot process reads relaxed-binding environment variables in
  #       exactly this form, so a lower-case key is silently ignored by the
  #       application rather than rejected by ECS -- the value is present in the
  #       task definition, absent from the running configuration, and the
  #       service starts on its defaults.
  validation {
    condition = alltrue([
      for name in keys(var.environment_variables) : can(regex("^[A-Z][A-Z0-9_]*$", name))
    ])
    error_message = "every environment_variables key must be an upper-case name of letters, digits and underscores starting with a letter, which is the form Spring Boot relaxed binding reads; a lower-case or hyphenated key is ignored by the application rather than rejected by ECS."
  }

  # WHY : Alternatives Considered: refusing secret-bearing names only, with no
  #       allowlist. Rejected because a denylist can only refuse what it
  #       anticipated: a key named CARDDEMO_COGNITO_APP_CLIENT_MATERIAL carries
  #       a secret and matches no word anyone would have thought to list. An
  #       allowlist inverts the default, so an unanticipated key is refused
  #       until someone names it, and the two together mean a key has to be both
  #       recognised and non-secret-shaped to reach a task definition.
  #       Assumptions: these prefixes are not a policy choice but an inventory of
  #       what the images read -- Spring Boot's own relaxed-binding namespaces
  #       (SPRING_, SERVER_, MANAGEMENT_, LOGGING_, SPRINGDOC_), the
  #       application's single custom namespace (CARDDEMO_, the prefix every
  #       service's own properties sit under), the JVM's two option variables,
  #       the region the AWS SDK default chain reads, and the timezone. A key
  #       outside them is not a setting any CardDemo container consumes.
  #       Trade-offs: the list lives in this condition rather than in a variable
  #       of its own. A variable would let a root widen the allowlist from a
  #       tfvars file, which is the one place a widening is least likely to be
  #       reviewed; keeping it here means adding a namespace is a module edit
  #       that appears in a diff. The accepted cost is that a genuinely new
  #       namespace needs a change to this file.
  validation {
    condition = alltrue([
      for name in keys(var.environment_variables) : anytrue([
        for allowed in [
          "SPRING_", "SERVER_", "MANAGEMENT_", "LOGGING_", "SPRINGDOC_",
          "CARDDEMO_", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS",
          "AWS_REGION", "AWS_DEFAULT_REGION", "TZ",
        ] : startswith(name, allowed)
      ])
    ])
    error_message = "every environment_variables key must begin with a namespace the CardDemo images read: SPRING_, SERVER_, MANAGEMENT_, LOGGING_, SPRINGDOC_, CARDDEMO_, JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS, AWS_REGION, AWS_DEFAULT_REGION or TZ. Anything else is not a setting a container consumes, and a runtime endpoint or identifier belongs in ssm_parameter_arns."
  }

  # WHY : Assumptions: the match is on the FINAL underscore-delimited segment, not
  #       on the name as a substring, and that precision is load-bearing rather
  #       than tidiness. CARDDEMO_SECURITY_JWT_EXPECTED_TOKEN_USE is a real key
  #       this platform sets to the literal "access" -- it contains TOKEN and
  #       carries nothing sensitive -- so a substring test would refuse a
  #       legitimate setting and the obvious workaround would be to delete the
  #       validation. Ending in a secret-bearing word, by contrast, is how a
  #       secret is actually named: SPRING_DATASOURCE_PASSWORD,
  #       CARDDEMO_AUTH_COGNITO_CLIENT_SECRET, AWS_SESSION_TOKEN.
  #       Trade-offs: KEY is on the list, so a non-secret key would have to be
  #       named ..._KEY_ARN or ..._KEY_ID rather than ..._KEY. That rename is a
  #       clarification worth requiring: an ARN or an identifier is not key
  #       material, and a name that does not distinguish them is exactly how the
  #       two get confused. The three static AWS credential names are refused by
  #       exact match as well, because AWS_ACCESS_KEY_ID ends in ID and would
  #       otherwise pass -- and on Fargate the task role supplies credentials, so
  #       a static one is a defect however it is spelled.
  validation {
    condition = alltrue([
      for name in keys(var.environment_variables) : !contains([
        "PASSWORD", "PASSWD", "PWD", "SECRET", "TOKEN", "CREDENTIAL",
        "CREDENTIALS", "PASSPHRASE", "KEY", "APIKEY", "PRIVATEKEY",
        "SIGNATURE", "SALT",
        ], element(split("_", name), length(split("_", name)) - 1)) && !contains([
        "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
      ], name)
    ])
    error_message = "an environment_variables key must not end in PASSWORD, PASSWD, PWD, SECRET, TOKEN, CREDENTIAL, CREDENTIALS, PASSPHRASE, KEY, APIKEY, PRIVATEKEY, SIGNATURE or SALT, and must not be a static AWS credential name. Values here are stored in clear text in the task definition, in plan output and in state; pass the reference through secret_sources or ssm_parameter_arns so ECS resolves it at task start instead."
  }
}

# WHY : Assumptions: this is how a service learns any runtime endpoint or
#       identifier at all. Terraform module outputs are the only source of
#       those values -- the database writer endpoint, the queue URLs, the token
#       issuer, the dataset bucket -- and they are written to Parameter Store
#       and read by the service at startup, so nothing is hard-coded into an
#       image or a configuration file. Baking an endpoint into the image was
#       the alternative and is rejected because it would make the image
#       environment-specific, so the same artifact could not be promoted from
#       dev to prod.
#       Assumptions: these ARNs are also what scopes the execution role. Its
#       parameter-read permission names exactly these parameters, so passing
#       them is what keeps that statement free of a wildcard resource.
variable "ssm_parameter_arns" {
  description = <<-EOT
    Map of container environment-variable name to the ARN of the SSM Parameter
    Store parameter holding its value. ECS resolves each one when the task
    starts, so the value itself is never stored in the task definition. The
    same ARNs form the Resource list of the execution role's parameter-read
    statement. Supplied from the outputs of whichever modules published the
    parameters.
  EOT
  type        = map(string)
  default     = {}

  # WHY : Assumptions: every value is an SSM Parameter Store ARN, and the shape is
  #       checked because this map feeds two things at once -- the container's
  #       secrets list and the execution role's resource-scoped read permission. A
  #       bare parameter NAME, which is what an operator reads off the console,
  #       type-checks as a string and produces a task definition ECS cannot
  #       resolve plus an IAM statement scoped to nothing, and the task then fails
  #       to start with an error about the parameter rather than about this input.
  #       Assumptions: the key shape is the same environment-variable identifier
  #       rule as environment_variables, because each key becomes the variable name
  #       the resolved parameter is injected under.
  validation {
    condition = alltrue([
      for key, arn in var.ssm_parameter_arns :
      can(regex("^[A-Z][A-Z0-9_]*$", key)) && can(regex("^arn:[a-z0-9-]+:ssm:[a-z0-9-]+:[0-9]{12}:parameter/[A-Za-z0-9_.\\-/]+$", arn))
    ])
    error_message = "Each ssm_parameter_arns entry must map an upper-case environment-variable name to a full SSM parameter ARN of the form arn:<partition>:ssm:<region>:<account>:parameter/<path>. A bare parameter name leaves the execution role scoped to nothing and the task unable to start."
  }
}

# WHY : Refactoring Rationale: ECS and IAM consume different representations of
#       one Secrets Manager source. ECS valueFrom may carry a JSON-key and
#       version selector suffix; IAM Resource must carry only the base secret
#       ARN. Keeping them in one object preserves their relationship without
#       reusing the selector as a policy resource.
variable "secret_arns" {
  description = <<-EOT
    Map of container environment-variable name to an object with `value_from`,
    the ECS selector that may name one JSON key, and `resource_arn`, the base
    Secrets Manager ARN IAM authorizes. This separates runtime field selection
    from policy scope: a JSON-key selector is not a valid IAM Resource and a
    base ARN injects the whole credential document rather than one field.
  EOT
  type = map(object({
    value_from   = string
    resource_arn = string
  }))
  default = {}

  # WHY : Assumptions: resource_arn is the policy resource and value_from is
  #       either that same ARN or that ARN followed by the ECS JSON-key/version
  #       selector suffix. Requiring the prefix relationship prevents a caller
  #       from authorizing one secret while injecting a field from another.
  #       Assumptions: this is the ONLY path by which a credential reaches a
  #       container. Values here are resolved by the ECS agent at task start and
  #       never appear in the task definition, which is what distinguishes this map
  #       from environment_variables and why a password in that map is a finding
  #       rather than a style choice.
  validation {
    condition = alltrue([
      for key, source in var.secret_arns :
      can(regex("^[A-Z][A-Z0-9_]*$", key)) &&
      can(regex("^arn:[a-z0-9-]+:secretsmanager:[a-z0-9-]+:[0-9]{12}:secret:[^:]+$", source.resource_arn)) &&
      (
        source.value_from == source.resource_arn ||
        startswith(source.value_from, "${source.resource_arn}:")
      )
    ])
    error_message = "Each secret_arns entry must map an upper-case name to { value_from, resource_arn }; resource_arn must be a base Secrets Manager secret ARN and value_from must equal it or append an ECS JSON-key/version selector to that same ARN."
  }

  # WHY : Refactoring Rationale: an HTTPS target group is not sufficient if the
  #       task starts without the certificate and private key its Spring
  #       `server.ssl` block requires. This cross-variable check makes the
  #       load-balanced shape fail during plan instead of cycling unhealthy
  #       tasks after infrastructure reports success.
  # WHY : Assumptions: the environment-variable names and JSON member names are
  #       the shared application contract used by every online service. Batch
  #       has no load balancer and is deliberately exempt.
  # WHY : Refactoring Rationale: a validation here REQUIRED every load-balanced
  #       service to inject CARDDEMO_SERVER_TLS_CERTIFICATE and
  #       CARDDEMO_SERVER_TLS_PRIVATE_KEY through this input, on the reasoning
  #       that a listener with no certificate fails its target-group health check
  #       and presents as a crash loop rather than as a missing secret. The
  #       reasoning was sound and the requirement is now WRONG, because the
  #       material is no longer a deployment input: each image's entry point
  #       (config/docker/generate-listener-material.sh) mints that task's OWN key
  #       pair and self-signed certificate before the JVM starts. Keeping the rule
  #       would refuse every correct call.
  # WHY : Assumptions: the concern the deleted rule served has not been abandoned,
  #       it has been made unfalsifiable. A task cannot start without listener
  #       material now, because the entry point exits non-zero before `exec java`
  #       if keytool fails, and server.ssl.key-store-password carries no fallback
  #       so an absent password stops context startup. Both failures happen before
  #       the port opens, so there is no path on which a load-balanced task serves
  #       cleartext -- which is a stronger guarantee than a plan-time check on the
  #       presence of two ARNs, and it needs no input to assert it.
  # WHY : Trade-offs: this variable now carries no listener-specific rule at all,
  #       so a reader looking for where TLS is enforced will not find it here. The
  #       enforcement moved into the image, and the two places that record it are
  #       that script's header and the server.ssl block in each service's
  #       application.yml.
}

# -----------------------------------------------------------------------------
# IAM.
# -----------------------------------------------------------------------------

# WHY : Alternatives Considered: composing the policy inside this module as the
#       union of what the nine workloads need -- queue access for the
#       authorization consumer, state-machine execution for reporting, object
#       storage for batch. Rejected outright, because a union grants every
#       service every other service's permissions: the reporting service could
#       consume the authorization queue and the auth service could start batch
#       executions, which is the opposite of the least-privilege posture this
#       package is required to hold. Taking the document as an input means the
#       task role starts with no BUSINESS permission and receives only what one
#       caller passes for one service. The module's fixed telemetry policy is
#       separate and grants only writes to this service's own log group and
#       X-Ray ingestion, so it cannot widen a bounded context's data access.
#       Refactoring Rationale: the migrated system delegated this to an
#       external security manager that has no cloud equivalent and is not
#       pretended to have one. Its role is filled by these per-service task
#       roles together with the managed user directory, and the substitution
#       is documented as a mapping rather than presented as a port.
#       Assumptions: null means the role receives no SERVICE-SPECIFIC business
#       policy. That is a valid and useful state -- a service that only serves
#       HTTP and reaches the database through an injected credential needs no
#       queue, object-store or state-machine permission -- so null is not a
#       missing value.
variable "create_task_role_policy" {
  description = "Whether to create the service-specific inline task-role policy resource. Set from root-owned topology rather than inferred from task_role_policy_json, whose module-output-derived value may remain unknown until apply."
  type        = bool
  default     = false
}

variable "task_role_policy_json" {
  description = <<-EOT
    Complete IAM policy document, as JSON, granting this one service the AWS
    API permissions it needs at run time. Attached to the task role, which the
    module otherwise leaves free of business-resource access. Leave null for a
    service that needs none; the collector-only log and X-Ray export policy may
    still be present when telemetry is enabled. This is the TASK role used by
    the application, not the execution role the ECS agent uses to pull the image
    and read parameters.
  EOT
  type        = string
  default     = null

  # WHY : Refactoring Rationale: this input carried `nullable = false` with no
  #       default while its own description and the validation below both
  #       required null for a service that needs no business policy. Those three
  #       statements cannot all hold: `nullable = false` makes an explicit null a
  #       hard "required variable may not be set to null", so the state the
  #       validation demanded was unrepresentable and the documented state was
  #       unreachable. Both roots pass `lookup(local.task_role_policy_json,
  #       each.key, null)`, so transaction-service -- the one workload with no
  #       business policy -- failed the plan outright. Declaring `default = null`
  #       (which also makes the variable nullable) is what makes the null state
  #       the description promises actually representable.
  # WHY : Assumptions: the relationship validation below is KEPT rather than
  #       replaced by the type change. Nullability makes null legal; the
  #       validation is what keeps null and create_task_role_policy consistent,
  #       so a caller cannot ask for the policy resource and hand it nothing.
  # WHY : Refactoring Rationale: resource cardinality must be known during plan.
  #       A document assembled from sibling-module outputs is unknown until
  #       apply, so main.tf gates on create_task_role_policy and validates the
  #       document relationship separately.
  validation {
    condition     = var.create_task_role_policy || var.task_role_policy_json == null
    error_message = "task_role_policy_json must be null when create_task_role_policy is false."
  }
}

# WHY : Refactoring Rationale: request/reply messages may carry a
#       replyToQueueUrl supplied by the requester. Treating that value as
#       authority would make the task role a confused deputy able to send to any
#       queue named by an inbound message. The application still validates the
#       URL against its environment-owned allowlist, and this input supplies the
#       independent IAM backstop: sqs:SendMessage is granted only on these exact
#       ARNs, never on `*` and never on an ARN assembled from the message.
variable "sqs_send_queue_arns" {
  description = <<-EOT
    Exact environment-owned SQS queue ARNs this application's task role may
    send to. Used as the Resource list of a dedicated sqs:SendMessage statement.
    Keep empty for services that publish no messages; never derive it from an
    inbound replyToQueueUrl.
  EOT
  type        = set(string)
  default     = []

  validation {
    condition = alltrue([
      for arn in var.sqs_send_queue_arns :
      can(regex("^arn:[a-z0-9-]+:sqs:[a-z0-9-]+:[0-9]{12}:[A-Za-z0-9_-]+(\\.fifo)?$", arn))
    ])
    error_message = "Every sqs_send_queue_arns entry must be a full SQS queue ARN. Queue URLs, wildcards and partial ARNs are refused because this set becomes an IAM Resource allowlist."
  }
}

# WHY : Assumptions: receive and delete belong to the same processing
#       discipline. Splitting them into independently configurable lists could
#       create a service that receives a message it can never acknowledge, so
#       the one set drives ReceiveMessage, DeleteMessage and visibility changes
#       together.
variable "sqs_receive_queue_arns" {
  description = <<-EOT
    Exact environment-owned SQS queue ARNs this application's task role may
    receive, delete and change visibility on. Keep empty for services that
    consume no queue. The module grants no wildcard SQS action or resource.
  EOT
  type        = set(string)
  default     = []

  validation {
    condition = alltrue([
      for arn in var.sqs_receive_queue_arns :
      can(regex("^arn:[a-z0-9-]+:sqs:[a-z0-9-]+:[0-9]{12}:[A-Za-z0-9_-]+(\\.fifo)?$", arn))
    ])
    error_message = "Every sqs_receive_queue_arns entry must be a full SQS queue ARN. Queue URLs, wildcards and partial ARNs are refused because this set becomes an IAM Resource allowlist."
  }
}

# WHY : Refactoring Rationale: this input exists because the online-write gate was
#       WIRED BUT UNREADABLE. Both environment roots create the read-only flag the
#       AAP's QuiesceOnlineWrites and ResumeOnlineWrites states toggle around the
#       batch window, and both already inject its NAME as
#       CARDDEMO_ONLINE_WRITES_PARAMETER. But the only ssm action this module granted
#       was ssm:GetParameters on the EXECUTION role, which the ECS agent uses to
#       inject parameter values once at task start. A running task reads the flag per
#       request under the TASK role, and the task role held no ssm permission at all,
#       so a service that read the flag would have been denied and "quiesce" stopped
#       no writes. Supplying the ARN here is what lets the module grant that one read.
# WHY : Alternatives Considered: (1) requiring the caller to include the statement in
#       task_role_policy_json. Rejected because that document is assembled from
#       sibling-module outputs and is therefore UNKNOWN at plan time, so no validation
#       here could confirm the grant was present -- the pairing would be a convention
#       rather than a contract. (2) Composing the statement into that same document.
#       Rejected for the reason the sibling queue policy is kept separate: a caller
#       must not be able to widen or replace a fixed boundary while supplying
#       unrelated permissions. This input instead selects a module-composed inline
#       policy whose action and resource the caller cannot influence.
# WHY : Assumptions: it is an ARN rather than a name because an IAM Resource element
#       cannot be a bare parameter name, and it is a SINGLE value rather than a set
#       because there is exactly one flag for the whole environment -- a set would
#       invite granting a task read access to parameters that have nothing to do with
#       the batch window.
variable "online_write_gate_parameter_arn" {
  description = <<-EOT
    ARN of the single Parameter Store entry carrying the environment's
    online-writes flag, which every write-gated service reads at request time to
    decide whether mutating requests are currently accepted. When set, the module
    attaches an inline task-role policy granting exactly ssm:GetParameter on this
    one ARN and nothing else. Null for a workload that is not write-gated -- the
    batch workload in particular must keep writing while online writes are
    quiesced, because it is the workload the quiesce exists to protect.
  EOT
  type        = string
  default     = null

  validation {
    condition = var.online_write_gate_parameter_arn == null || can(regex(
      "^arn:[a-z0-9-]+:ssm:[a-z0-9-]+:[0-9]{12}:parameter/[A-Za-z0-9_.\\-/]+$",
      var.online_write_gate_parameter_arn
    ))
    error_message = "online_write_gate_parameter_arn must be a full SSM parameter ARN of the form arn:<partition>:ssm:<region>:<account>:parameter/<name>. A bare parameter name, a wildcard and a partial ARN are all refused because this value becomes an IAM Resource element."
  }
}

# WHY : Refactoring Rationale: the write-gate policy's cardinality used to be
#       selected by testing the ARN above against null, and that could not be
#       decided during plan. Both roots pass
#       `aws_ssm_parameter.online_writes_enabled.arn` for an online workload, and
#       on a first apply that parameter does not exist yet, so the ARN is
#       unknown; `unknown == null` is itself unknown, and Terraform refused the
#       plan with `Invalid count argument` once for every one of the seven
#       request-serving workloads. Selecting on a boolean the root already knows
#       -- whether the workload serves requests -- makes the same decision at
#       plan time. This is the identical remedy create_task_role_policy applies
#       to the same class of problem, and it is deliberately spelled the same way
#       so a reader meets one idiom rather than two.
# WHY : Alternatives Considered: assembling the parameter ARN in the root from
#       the partition, region, account and the parameter NAME, all of which are
#       known before the parameter exists. Rejected because it puts ARN
#       construction in a second place and would silently keep planning after a
#       rename that moved the real parameter elsewhere; a boolean states the
#       topology fact directly and leaves the ARN the resource's own attribute.
variable "create_online_write_gate_policy" {
  description = <<-EOT
    Whether to attach the inline task-role policy that reads the environment's
    online-writes flag. Set from root-owned topology -- true for the workloads
    that serve requests and honour a quiesce, false for the batch and
    data-migration workloads -- rather than inferred from
    online_write_gate_parameter_arn, whose value is unknown until the parameter
    exists. Must travel with that ARN: the flag's name without permission to read
    it denies every gated request, and permission without the name grants
    something the task cannot use.
  EOT
  type        = bool
  default     = false

  # WHY : Assumptions: this checks the pairing in the direction that can be
  #       decided from the inputs alone. A literal null ARN with the flag set is
  #       refused here; an ARN that is merely unknown leaves the condition
  #       unknown, which Terraform defers, and the task definition's own
  #       precondition then asserts the same pairing against the environment
  #       variable names, which are always known.
  validation {
    condition     = !var.create_online_write_gate_policy || var.online_write_gate_parameter_arn != null
    error_message = "create_online_write_gate_policy is true, so online_write_gate_parameter_arn must also be supplied; the policy has no resource to name without it."
  }
}
# WHY : Assumptions: a resource-scoped parameter-read or secret-read permission
#       is not sufficient on its own. When a parameter is a SecureString or a
#       secret is encrypted with a customer-managed key, reading it also
#       requires permission to decrypt with that key, so a role granted only
#       the read action fails at task start with an access-denied error naming
#       the key rather than the parameter -- a confusing failure that naming
#       the keys here prevents.
#       Alternatives Considered: granting the decrypt action on all keys.
#       Rejected for the same reason the image-pull permission names one
#       repository: least privilege is a stated non-negotiable constraint and
#       the CI policy scan treats a wildcard resource as a finding, so the
#       wildcard would redden a gating step as well as over-grant. Listing
#       exact ARNs keeps every statement in both roles resource-scoped.
variable "execution_secret_kms_key_arns" {
  description = <<-EOT
    Exact KMS key ARNs protecting the Secrets Manager entries in secret_arns.
    Used only by the ECS EXECUTION role, and only through a statement carrying
    the Secrets Manager ViaService and SecretARN encryption-context conditions,
    so the key cannot be used against unrelated ciphertext. A key the
    APPLICATION itself must use -- to read an object, decrypt a queue message or
    open a database connection -- belongs in a statement of
    task_role_policy_json, which is attached to the task role; this module
    composes no key permission for that role.
  EOT
  type        = list(string)
  default     = []

  # WHY : Assumptions: each entry is a KMS key ARN, for the same reason the log
  #       group's key is: a key id or an alias satisfies the type and produces an
  #       IAM statement whose resource matches no key, so the decrypt permission
  #       the caller believes it granted is absent. That failure appears when a
  #       secret or a queue message is first decrypted, not when this applies.
  #       Trade-offs: an empty list is permitted, because a workload that injects
  #       no Secrets Manager entry needs no execution-role decrypt grant at all.
  #       Refactoring Rationale: this note used to name the batch and reporting
  #       shapes as the workloads that "differ from the online ones in exactly
  #       that way", which was wrong on the measured wiring. Both roots gate this
  #       input on `length(secret_sources_by_workload[each.key]) > 0`, and every
  #       one of the nine workloads -- batch and reporting included -- injects at
  #       least the database credential, so every one receives the secrets key.
  #       The empty case is the shape of a FUTURE workload that reads no secret,
  #       not of any workload this repository deploys today.
  validation {
    condition = alltrue([
      for arn in var.execution_secret_kms_key_arns :
      can(regex("^arn:[a-z0-9-]+:kms:[a-z0-9-]+:[0-9]{12}:key/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", arn))
    ])
    error_message = "Each execution_secret_kms_key_arns entry must be an anchored KMS key ARN with a UUID key identifier."
  }
}

# -----------------------------------------------------------------------------
# Tagging.
# -----------------------------------------------------------------------------

# WHY : Assumptions: this MERGES with, rather than replaces, whatever the
#       calling root already applies through the provider's default_tags. A
#       module cannot set default_tags -- that belongs to a provider
#       configuration, and this module deliberately declares no provider so
#       the root keeps control of the region and of the package-wide tag set
#       -- so this input is the only way a caller can add a tag to one
#       service's resources without adding it to all sixteen instances.
#       Trade-offs: because the two sets combine, a key present in both
#       resolves to the value given here. That is what makes a per-service
#       override possible, and it also means a carelessly chosen key can
#       shadow a package-wide one; naming the precedence in the description
#       is the guard.
variable "tags" {
  description = <<-EOT
    Extra tags to merge onto every resource this module creates, for tagging
    that applies to one service rather than to the whole package. Combines with
    the tags the calling root applies through the provider's default_tags; a
    key given here takes precedence over the same key there.
  EOT
  type        = map(string)
  default     = {}
}

# -----------------------------------------------------------------------------
# The maximum permissions either role may ever hold
# -----------------------------------------------------------------------------
# WHY : Assumptions: this module composes inline policies from caller-supplied ARN
#       lists and, for the task role, from a caller-supplied policy document. A
#       boundary is the only control that bounds what those compositions can add
#       up to, because it is evaluated in addition to every identity policy: a
#       statement the boundary does not permit is denied even if an inline policy
#       allows it. Attaching it here rather than trusting each caller means a root
#       that widens task_role_policy_json cannot widen past the account's ceiling.
#       Alternatives Considered: reviewing each root's policy document instead.
#       Rejected because review does not bind a later edit, and the module cannot
#       see the document's contents in any case.
#       Trade-offs: the input is required, so a caller must own a boundary policy
#       before it can create a service. Accepted: an account deploying this system
#       has a deployment boundary already, and making it optional would leave the
#       control off in exactly the environments least likely to notice.
variable "permissions_boundary_arn" {
  description = "Same-account customer-managed IAM policy ARN used as the permissions boundary on both ECS roles. Required so no inline capability assembled by this module can exceed the account's deployment boundary."
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^arn:[a-z0-9-]+:iam::[0-9]{12}:policy/[A-Za-z0-9+=,.@_/-]+$", var.permissions_boundary_arn))
    error_message = "permissions_boundary_arn must be an anchored customer-managed IAM policy ARN in a twelve-digit AWS account."
  }
}

# WHY : Trade-offs: accepted as a second, separate way of granting the same role
#       permissions, which is redundant in the simple case. It earns its place
#       where a permission set is genuinely shared -- a policy a root already
#       owns and attaches to several services -- because inlining that set into
#       each service's task_role_policy_json would copy it per service and let
#       the copies drift. The accepted cost is that a reader must look in two
#       places to see a role's full permissions, which the description states
#       explicitly so nobody concludes the inline document is the whole story.
variable "task_role_managed_policy_arns" {
  description = <<-EOT
    ARNs of existing managed policies to attach to the task role in addition to
    task_role_policy_json. For permission sets a root already owns and shares
    across services; the role's effective permissions are the union of these
    and the inline document.
  EOT
  type        = list(string)
  default     = []

  # WHY : Assumptions: each entry is an IAM policy ARN, either AWS-managed
  #       (arn:aws:iam::aws:policy/...) or customer-managed in this account. A
  #       policy NAME type-checks and is rejected at attach time, after the role
  #       exists, so the task starts without the permission the caller intended
  #       and the failure surfaces as an access denial inside the application.
  #       Trade-offs: broad AWS-managed policies are not refused here. Naming
  #       specific policies to ban would put a policy judgement in a module input,
  #       and that judgement belongs to the pipeline's policy scan, which fails on
  #       over-broad grants wherever they are declared.
  validation {
    condition = alltrue([
      for arn in var.task_role_managed_policy_arns :
      can(regex("^arn:[a-z0-9-]+:iam::([0-9]{12}|aws):policy/", arn))
    ])
    error_message = "Each task_role_managed_policy_arns entry must be an IAM policy ARN of the form arn:<partition>:iam::aws:policy/<name> for an AWS-managed policy, or arn:<partition>:iam::<account>:policy/<name> for a customer-managed one. A bare policy name is rejected only when the attachment is attempted."
  }
}
