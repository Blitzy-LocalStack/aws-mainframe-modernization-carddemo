# =============================================================================
# infra/modules/ecs-service/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input contract of the reusable `ecs-service` module -- the
#   one module in infra/ that exists in order to be instantiated many times
#   rather than once. Each of the two environment roots, infra/envs/dev and
#   infra/envs/prod, calls it eight times, once per bounded context: auth,
#   account, card, transaction, reference, batch, authorization and
#   reporting. Sixteen module instances across the two roots are therefore
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
#   - Nine variables declare no `default`, which makes each one a hard
#     requirement: omitting one fails in the CALLING ROOT at `terraform
#     validate` with a missing-required-argument error, before any resource
#     in this module is evaluated.
#   - Six variables carry a `validation` block, so a bad value is rejected
#     at plan time instead of by the AWS API mid-apply: name_prefix and
#     service_name (charset and length, bounding the composed target-group
#     name), environment (dev or prod only), task_cpu (the Fargate CPU set),
#     autoscaling_target_cpu_utilization (a usable percentage band) and
#     log_retention_in_days (the CloudWatch Logs retention set).
#   - Three variables select the module's SHAPE rather than one of its
#     values -- create_service, attach_load_balancer and enable_autoscaling.
#     Disabling one is not an error and raises no message; it produces fewer
#     resources and turns the matching outputs null. Each of the three
#     states its own coupling, because none of it is inferable from the
#     type.
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: eight per-service module copies, one per
#     bounded context, instead of one parameterised module with this input
#     surface. Rejected on the evidence of app/csd/CARDDEMO.CSD rather than
#     on taste: all 18 `DEFINE TRANSACTION` stanzas (L306-L488) are
#     attribute-identical -- ISOLATE(YES), TASKDATAKEY(USER),
#     ACTION(BACKOUT), PRIORITY(1), RESTART(NO), PROFILE(DFHCICST) and
#     TRANCLASS(DFHTCL00) each occur exactly 18 times -- so the CICS region
#     already expressed 18 workloads as one repeated template differing only
#     in transaction name and target program. Eight copies would fork that
#     template eight ways, and any later change to the health-check or
#     deployment contract would then have to land in eight files to keep the
#     eight services behaving alike.
#   - Trade-off: every `description` is written as a heredoc rather than as
#     a single-line string. One line cannot carry what a value is for, which
#     resource consumes it AND where the caller obtains it without running
#     far past the 79-column width the rest of this module holds to, and a
#     caller reading the generated README is the party that needs all three.
#     The accepted cost is a considerably taller file.
#   - Assumption: comments sit above each `variable` block and never between
#     its arguments. `terraform fmt` treats a comment as the end of an
#     alignment run, so an interleaved comment silently re-aligns the `=`
#     signs that follow it, and `terraform fmt -check -recursive infra/` is
#     gating.
# =============================================================================

# -----------------------------------------------------------------------------
# TIER 1 -- REQUIRED INPUTS. Every variable in this tier omits `default`.
#
# WHY : Alternatives Considered: ordering all forty-six variables strictly
#       alphabetically, which is the obvious scheme and does help a reader
#       hunting for one name already known. Rejected because it interleaves
#       the nine inputs a caller MUST supply with the thirty-seven it may
#       ignore, so a new `module` block could only be written correctly by
#       reading every block in the file to discover which ones lack a
#       default. Required-first answers the question a caller actually
#       arrives with. Within each tier the grouping follows the direction a
#       value travels: identity, the cluster the task joins, the network it
#       runs in, the image it runs, the container runtime, the load balancer
#       in front of it, the service itself, its autoscaling, its logs, its
#       injected configuration, its IAM and finally its tags.
# -----------------------------------------------------------------------------

# WHAT: the bounded-context short name that distinguishes one instantiation
#       from the other seven inside the same root.
# WHY : Assumption: no `default` deliberately. This is the only input that
#       tells the eight instantiations apart, so a default would let two
#       `module` blocks in one root compose the same ECS service name, log
#       group and target-group name and then collide during apply. The
#       charset bound is not cosmetic either -- the value is concatenated
#       into an ALB target-group name, which AWS accepts only as
#       alphanumerics and hyphens, so an underscore or a capital letter
#       would pass unnoticed here and fail mid-apply. The 14-character
#       ceiling is derived from the same AWS limit; the arithmetic is
#       recorded on name_prefix.
variable "service_name" {
  description = <<-EOT
    Bounded-context short name for this instance: one of auth, account,
    card, transaction, reference, batch, authorization or reporting.
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
      "most 14 characters so the composed target-group name stays within",
      "its 32-character ceiling.",
    ])
  }
}

# WHAT: which of the two provisioned environments this instance belongs to.
# WHY : Assumption: the accepted set is closed at exactly two because the
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

# WHAT: the ECS cluster this service is created in.
# WHY : Assumption: the ARN form rather than the name, because that is what
#       the `cluster` argument of aws_ecs_service takes. It is one of a PAIR
#       of cluster inputs -- see cluster_name immediately below for why both
#       forms of one identity are needed.
variable "cluster_arn" {
  description = <<-EOT
    ARN of the ECS Fargate cluster that will host this service, consumed by
    the `cluster` argument of aws_ecs_service. Comes from the ecs-cluster
    module's output; all eight instantiations in a root share one cluster.
  EOT
  type        = string
}

# WHAT: the same cluster as cluster_arn, in its bare-name form.
# WHY : Assumption: Application Auto Scaling identifies an ECS service by a
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

# WHAT: the VPC the load-balancer target group is registered in.
# WHY : Assumption: aws_lb_target_group requires vpc_id whenever its target
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

# WHAT: the subnets the Fargate tasks are placed in.
# WHY : Assumption: these must be the PRIVATE-APPLICATION tier
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
}

# WHAT: the security groups attached to each task's network interface.
# WHY : Trade-off: a list rather than a single id. One group -- the
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
}

# WHAT: the container image this task runs.
# WHY : Assumption: the value arrives already complete -- registry host,
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
}

# WHAT: the one repository the task execution role is allowed to pull from.
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
#       Assumption: the registry authorization-token action accepts no
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
}

# -----------------------------------------------------------------------------
# TIER 2 -- OPTIONAL INPUTS. Every variable below carries a `default`, so a
# caller may omit all of them and still get a working service.
#
# WHY : Assumption: each default is chosen to be the value seven of the eight
#       instantiations want, so a root's `module` block stays short and the
#       lines it does write are the ones that genuinely differ. Three axes
#       are expected to diverge between dev and prod -- task count, CPU and
#       memory, and log retention -- and those defaults are the ones most
#       often overridden; the rest exist so that a divergence is possible
#       without being routine.
# -----------------------------------------------------------------------------

# WHAT: the common leading token in every resource name this module composes.
# WHY : Assumption: the binding constraint on this value is an AWS naming
#       ceiling, and it comes from the shortest limit among the names built
#       out of it -- an ALB target-group name, capped at 32 characters, well
#       below the ceiling on an ECS service name or an IAM role name. The
#       arithmetic that fixes the 12-character bound is
#       len(prefix) + 1 + len(service_name) + 1 + len("prod") <= 32, and with
#       service_name bounded at 14 that leaves exactly 12. The pattern
#       additionally forbids a leading digit, a trailing hyphen and doubled
#       hyphens, because a target-group name may not begin or end with a
#       hyphen and concatenation is what would otherwise produce one.
#       Trade-off: the default matches the same-named variable in the
#       bootstrap root so one prefix identifies every resource in the
#       package; the cost is that the two must be changed together to stay
#       consistent.
variable "name_prefix" {
  description = <<-EOT
    Leading token shared by every resource name this module composes -- the
    ECS service, the task-definition family, the log group, both IAM roles
    and the ALB target group. Kept short because the target-group name is the
    tightest AWS ceiling those names have to clear. Defaulted rather than
    required so a root states it only when it wants something other than the
    package-wide prefix.
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
      "most 12 characters so the composed target-group name stays within",
      "its 32-character ceiling.",
    ])
  }
}

# -----------------------------------------------------------------------------
# Container runtime.
# -----------------------------------------------------------------------------

# WHAT: an explicit override for the name of the container inside the task
#       definition.
# WHY : Assumption: this matters far beyond cosmetics, for one instantiation
#       in particular. Step Functions starts each batch step through the
#       synchronous run-task integration and passes that step's arguments as
#       container overrides, which address the container being overridden BY
#       NAME. The batch instance's container name is therefore a contract
#       shared with the step-functions-batch module, and a name derived
#       silently inside this module would be a contract neither side
#       declares. Exposing it lets the root pin one literal and hand the same
#       one to both modules.
#       Trade-off: the default is null rather than a computed string, because
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

# WHAT: the TCP port the container listens on and the target group forwards
#       to.
# WHY : Assumption: 8080 is not an arbitrary preference but the one port the
#       network tiering admits -- the application security group permits
#       load-balancer-to-application traffic on 8080 and nothing else
#       inbound. Any other value would produce a target group whose health
#       probes and forwarded requests are dropped by the security group
#       rather than refused by the container, which is much the harder of the
#       two failures to diagnose. It remains a variable rather than a
#       hard-coded literal only so a root changing the security group can
#       change both together.
variable "container_port" {
  description = <<-EOT
    Container port exposed by the task, also used as the target-group port
    and the health-check port. Must match both the port the service's Spring
    Boot process binds and the port the application security group admits
    from the load balancer.
  EOT
  type        = number
  default     = 8080
}

# WHAT: the identity the container process runs as.
# WHY : Refactoring Rationale: running as root is the container default and
#       is what this replaces. It is also not a policy imported from outside
#       the application -- app/csd/CARDDEMO.CSD sets TASKDATAKEY(USER) on all
#       18 transaction stanzas and EXECKEY(USER) on all 18 program stanzas,
#       so the migrated system already refused to run application work in the
#       privileged storage key. Defaulting to root here would be a regression
#       against the system being migrated rather than a neutral choice, and
#       the CI policy scan separately treats a container with no non-root
#       user as a finding.
#       Assumption: the value must name a user the image actually has. Each
#       service's Dockerfile creates one as part of its multi-stage build,
#       and a mismatch fails at task start with an unresolvable-user error
#       rather than at plan time, so the two are changed together.
#       Trade-off: typed as a string, not a number, because the ECS container
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
}

# WHAT: the CPU units reserved for the whole task.
# WHY : Assumption: Fargate accepts only a fixed set of task CPU sizes, and
#       only certain memory sizes alongside each one, so an arbitrary integer
#       is rejected by the API during apply. The validation restates that set
#       to move the rejection to plan time, where the message names this
#       variable rather than the task definition. The set is an AWS contract
#       this module depends on rather than something verified here.
#       Trade-off: the pairing with task_memory cannot be validated as a pair
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

# WHAT: the memory reserved for the whole task, in MiB.
# WHY : Assumption: Fargate constrains this to a set that DEPENDS ON
#       task_cpu -- the same API contract task_cpu depends on -- so no
#       standalone condition can bound it correctly and none is written here;
#       an invalid pair surfaces at apply.
#       Trade-off: that is the accepted cost of not embedding the whole
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
}

# WHAT: whether the container's root filesystem is mounted read-only.
# WHY : Trade-off: declared and defaulted to false as a reviewed decision
#       rather than left out. A read-only root would be the stronger posture
#       and the CI policy scan prefers it, but the service images run a JVM
#       that writes to a temporary directory in normal operation, so enabling
#       it without also mounting a writable volume would make every task fail
#       after start rather than harden it -- and no such volume is part of
#       this module's surface. Defaulting to false therefore keeps the module
#       working, and declaring the variable is what turns a silent gap into a
#       choice a root can reverse once its image is built to run without
#       writing to the root filesystem. Omitting the variable altogether was
#       the alternative and is strictly worse: the same posture, with nothing
#       recording that anyone weighed it.
variable "readonly_root_filesystem" {
  description = <<-EOT
    Sets readonlyRootFilesystem on the container definition. Left false
    because the service images run a JVM that writes to a temporary directory
    and this module mounts no writable volume for it. A root whose image is
    prepared to run without writing to the root filesystem may set it true.
  EOT
  type        = bool
  default     = false
}

# -----------------------------------------------------------------------------
# Load balancer and health check.
#
# The three booleans that select this module's SHAPE -- attach_load_balancer
# here, then create_service and enable_autoscaling below -- are described
# together once, because none of the three makes sense in isolation.
#
# WHAT: seven of the eight instantiations are long-running online services
#       behind the internal load balancer. The batch instance is not: its
#       steps are started one at a time by Step Functions through the
#       synchronous run-task integration, with each step's arguments passed
#       as container overrides. It needs the task definition, the task role,
#       the execution role and the log group, and it must NOT get a target
#       group, a permanently running service or an autoscaling target --
#       there is nothing for a load balancer to route to and nothing for a
#       scaling policy to act on.
# WHY : Alternatives Considered: a second module, `ecs-task`, emitting only
#       the task definition, the two roles and the log group. Rejected
#       because those four ARE the bulk of this module, so a second module
#       would duplicate all of them -- precisely the duplication a reusable
#       module exists to prevent. The two copies would then drift on exactly
#       the details that must not differ between how a service and a batch
#       step are packaged: the non-root user, the log-group naming, and the
#       execution role's resource-scoped pull and decrypt permissions.
#       Trade-off: the cost is three booleans that seven of the eight callers
#       never mention, accepted so one module covers both shapes and the four
#       shared resources are defined exactly once.
# -----------------------------------------------------------------------------

# WHY : Alternatives Considered: the rejected `ecs-task` module recorded in
#       the section note above; this flag is part of what lets one module
#       serve both shapes.
#       Assumption: the coupling is not inferable from the type and is
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
    balancer fronts. Set false for the batch instance, which Step Functions
    invokes rather than anything reaching it over HTTP; that also suppresses
    the health-check grace period and turns the target-group output null.
  EOT
  type        = bool
  default     = true
}

# WHAT: the HTTP path the target group probes.
# WHY : Assumption: the services expose Spring Boot Actuator, whose health
#       endpoint is the one path guaranteed to answer without touching
#       business data or requiring a token, and the same endpoint backs both
#       this target-group probe and the image's own container health check.
#       Probing a business route instead was the alternative and is rejected
#       because it would make target-group membership depend on a database
#       round trip, so one slow query would deregister healthy tasks.
#       Assumption: this module owns the TARGET GROUP and its health check,
#       and nothing else about routing. The listener and the per-service
#       listener rules that send traffic to the group belong to the alb
#       module. Keeping that boundary explicit is why no listener or
#       listener-rule input appears anywhere in this file.
variable "health_check_path" {
  description = <<-EOT
    Path the target-group health check requests on container_port. Defaults
    to the Spring Boot Actuator health endpoint every service exposes.
    Consumed only by the target group this module creates; the listener rule
    that routes traffic to that group is the alb module's to define.
  EOT
  type        = string
  default     = "/actuator/health"
}

# WHAT: the HTTP status codes the probe treats as healthy.
# WHY : Assumption: the Actuator health endpoint answers 200 when its status
#       is UP and a server error when it is not, so a single code is the
#       whole contract and a range would widen it to include responses that
#       mean the service is not healthy.
#       Trade-off: typed as a string, not a number, because the AWS matcher
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
}

# WHAT: how often the target group probes each registered task, in seconds.
# WHY : Trade-off: a deliberately bounded compromise rather than a tuned
#       value. Probing less often lets a wedged task keep receiving requests
#       for more consecutive probes before unhealthy_threshold is reached;
#       probing more often multiplies probe traffic against every task of
#       every service, and this module is instantiated eight times per root.
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
}

# WHAT: how long a single probe may take before it counts as a failure.
# WHY : Assumption: AWS requires this to be strictly less than
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
}

# WHAT: consecutive successful probes before a task starts receiving traffic.
# WHY : Trade-off: set higher than unhealthy_threshold on purpose, and the
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
}

# WHAT: consecutive failed probes before a task is pulled from rotation and
#       replaced.
# WHY : Refactoring Rationale: this replaces having no automatic recovery at
#       all. app/csd/CARDDEMO.CSD sets RESTART(NO) on all 18 transaction
#       stanzas, so a failed CICS task was not restarted and recovery was an
#       operator action. Health-check-driven replacement is therefore a
#       documented improvement on the migrated system rather than a port of
#       one of its behaviours, and this threshold is what decides how many
#       consecutive failures are tolerated first.
#       Trade-off: set to the smallest value AWS accepts, so a task that has
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
}

# WHAT: how long the load balancer keeps draining a task after it is
#       deregistered.
# WHY : Assumption: a short delay is safe here specifically because the
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
}

# WHAT: how long ECS ignores load-balancer health for a freshly started task.
# WHY : Assumption: without a grace period a JVM service cannot start at all
#       under a load balancer. The container is registered as soon as it is
#       running, but the Actuator health endpoint does not report UP until the
#       Spring context has finished refreshing, so the first probes fail and
#       ECS kills the task before it can ever pass -- a loop that looks like
#       a crash and is not one.
#       Assumption: aws_ecs_service REJECTS this argument on a service with
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
}

# -----------------------------------------------------------------------------
# The service itself.
# -----------------------------------------------------------------------------

# WHY : Alternatives Considered: the rejected `ecs-task` module recorded in
#       the shape note above the load-balancer section -- a second module
#       holding only the task definition, the two roles and the log group
#       would have duplicated the majority of this one. This flag is what
#       lets the single module emit either shape.
#       Assumption: the coupling in main.tf is wider than the name suggests.
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
    True for the seven online services. Set false for the batch instance,
    whose tasks Step Functions starts one at a time; that leaves the task
    definition, both IAM roles and the log group in place for
    step-functions-batch to reference, and suppresses the service, the
    autoscaling target and the outputs describing them.
  EOT
  type        = bool
  default     = true
}

# WHAT: the task count the service is created with.
# WHY : Assumption: this is the INITIAL count only. main.tf stops tracking it
#       afterwards, because Application Auto Scaling owns the running count
#       once the target is registered; without that exclusion every plan
#       after the first scaling event would propose reverting the count, so
#       Terraform and the scaling policy would fight over one field. A root
#       wanting a different floor should raise min_capacity, not this.
#       Trade-off: two rather than one. One task is cheaper and can serve
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
}

# WHAT: the Fargate platform version the tasks run on.
# WHY : Trade-off: tracking the current platform accepts Fargate platform
#       updates as AWS ships them, so the tasks are not reproducible to one
#       exact platform build and a platform change arrives without a
#       Terraform diff. Pinning an explicit version was the alternative and
#       is rejected because the pin would then have to be raised by hand in
#       eight module blocks in each of two roots -- sixteen places -- every
#       time it went stale, and a stale pin is how a platform reaches end of
#       support while every plan still reports no changes. The variable
#       exists so a root that needs one specific platform can pin it
#       deliberately.
variable "platform_version" {
  description = <<-EOT
    Value of the platform_version argument of aws_ecs_service, selecting the
    Fargate platform the tasks run on. Left tracking the current platform so
    updates are picked up without an edit in sixteen places; set an explicit
    version when one specific platform build is required. Supplied by the
    caller as a literal, not from another module's output.
  EOT
  type        = string
  default     = "LATEST"
}

# WHAT: the lower bound, as a percentage of desired_count, on how much
#       capacity stays in service during a deployment.
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
}

# WHY : Trade-off: the upper half of the same rolling strategy, and it is
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
}

# WHAT: whether a failing deployment is detected and rolled back
#       automatically.
# WHY : Assumption: this is a property of the ROLLING deployment controller,
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
#       Assumption: it is gated on create_service in main.tf and is not
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
    and attach a CPU target-tracking policy to it. Effective only when
    create_service is also true, since a scaling target must name an existing
    service. The batch instance leaves both false.
  EOT
  type        = bool
  default     = true
}

# WHAT: the floor Application Auto Scaling may reduce the task count to.
# WHY : Assumption: matched to the desired_count default rather than set
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
}

# WHAT: the ceiling Application Auto Scaling may grow the task count to.
# WHY : Trade-off: a bounded ceiling rather than a generous one. The point of
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
}

# WHAT: the average CPU utilisation the target-tracking policy holds the
#       service at.
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
#       Trade-off: the band the validation enforces is narrower than the full
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

# WHAT: how long the policy waits after a scale-in before scaling in again.
# WHY : Assumption: scale-in is safe at all only because the services are
#       stateless -- no sticky sessions and no server-side session store,
#       which is what makes tasks behind a load balancer interchangeable and
#       therefore removable. Removing a task from a stateful service would
#       strand whatever that task held.
#       Trade-off: set longer than the scale-out cooldown on purpose. The two
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
}

# WHAT: how long the policy waits after a scale-out before scaling out again.
# WHY : Trade-off: the shorter of the two cooldowns, for the reason recorded
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
}

# -----------------------------------------------------------------------------
# Logging.
# -----------------------------------------------------------------------------

# WHAT: how long the service's CloudWatch log group keeps its events.
# WHY : Assumption: CloudWatch Logs accepts only a fixed set of retention
#       values and rejects any other integer, so the validation restates that
#       set in order to move the rejection from apply to plan. The set is an
#       AWS contract this module depends on rather than something verified
#       here. Zero is included because AWS accepts it and it means never
#       expire; it is deliberately not the default, because indefinite
#       retention accrues storage charges without bound and nothing in this
#       migration requires logs to be kept forever.
#       Trade-off: this is one of the three axes on which the dev and prod
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

# WHAT: an optional customer-managed key to encrypt the log group with.
# WHY : Assumption: null is the expected value, not a gap. Log events are
#       encrypted at rest by CloudWatch Logs regardless; supplying a key here
#       changes who controls that encryption, not whether it happens. The
#       package provisions four customer-managed keys -- for the database,
#       object storage, the secret store and the queues -- and a log key is
#       deliberately not among them, so there is normally no ARN to pass.
#       Trade-off: the variable exists anyway so a root deciding its logs need
#       a key it controls can supply one without editing this module. The
#       alternative -- omitting the input and relying only on the service
#       default -- would turn one environment's policy decision into a module
#       change affecting all sixteen instances.
variable "log_group_kms_key_arn" {
  description = <<-EOT
    ARN of a customer-managed KMS key to encrypt the service's log group with.
    Leave null to let CloudWatch Logs encrypt events with its own
    service-managed key, which is the expected case because the package
    provisions no dedicated log key. Supply a key ARN from the kms module to
    take control of that encryption.
  EOT
  type        = string
  default     = null
}

# -----------------------------------------------------------------------------
# Configuration injection.
# -----------------------------------------------------------------------------

# WHAT: plain, non-secret values injected into the container as environment
#       variables.
# WHY : Assumption: everything passed here is readable by anyone who can
#       describe the task definition, because a container definition's
#       environment entries are stored and returned in clear text. That is
#       exactly why the split with ssm_parameter_arns and secret_arns exists:
#       those two carry a REFERENCE that ECS resolves at task start, so the
#       value never enters the task definition and never enters a state or
#       plan file. A credential placed here instead would be written to state
#       and printed in plan output, which is what the no-secrets constraint
#       forbids.
#       Trade-off: the empty default keeps the common case -- a service
#       needing only its active profile and a handful of references -- free of
#       ceremony.
variable "environment_variables" {
  description = <<-EOT
    Non-secret environment variables for the container, as a map of name to
    literal value: the active Spring profile and similar plain settings.
    Values appear in clear text in the task definition and in plan output, so
    anything sensitive belongs in ssm_parameter_arns or secret_arns instead.
  EOT
  type        = map(string)
  default     = {}
}

# WHAT: environment variables whose values ECS reads from Parameter Store when
#       the task starts.
# WHY : Assumption: this is how a service learns any runtime endpoint or
#       identifier at all. Terraform module outputs are the only source of
#       those values -- the database writer endpoint, the queue URLs, the token
#       issuer, the dataset bucket -- and they are written to Parameter Store
#       and read by the service at startup, so nothing is hard-coded into an
#       image or a configuration file. Baking an endpoint into the image was
#       the alternative and is rejected because it would make the image
#       environment-specific, so the same artifact could not be promoted from
#       dev to prod.
#       Assumption: these ARNs are also what scopes the execution role. Its
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
}

# WHAT: environment variables whose values ECS reads from Secrets Manager when
#       the task starts.
# WHY : Assumption: ECS resolves Parameter Store and Secrets Manager through
#       the SAME container secrets mechanism, distinguished only by the kind of
#       ARN in each entry, which is why two maps are accepted here and merged
#       into one list in main.tf. They are kept apart at the module boundary
#       because the execution role needs a different action for each store, and
#       merging them earlier would discard the information needed to write
#       those two statements separately.
#       Assumption: as with ssm_parameter_arns, these ARNs are what the
#       execution role's read permission is scoped to, so a caller passing them
#       is the mechanism that keeps the policy wildcard-free.
variable "secret_arns" {
  description = <<-EOT
    Map of container environment-variable name to the ARN of the Secrets
    Manager secret holding its value, such as the database credential.
    Resolved by ECS at task start through the same container secrets mechanism
    as ssm_parameter_arns, and used as the Resource list of the execution
    role's secret-read statement. Comes from the secrets module's outputs,
    which generate every credential at apply time.
  EOT
  type        = map(string)
  default     = {}
}

# -----------------------------------------------------------------------------
# IAM.
# -----------------------------------------------------------------------------

# WHAT: the entire permission set the running application is given, supplied
#       by the caller as a policy document.
# WHY : Alternatives Considered: composing the policy inside this module as the
#       union of what the eight services need -- queue access for the
#       authorization consumer, state-machine execution for reporting, object
#       storage for batch. Rejected outright, because a union grants every
#       service every other service's permissions: the reporting service could
#       consume the authorization queue and the auth service could start batch
#       executions, which is the opposite of the least-privilege posture this
#       package is required to hold. Taking the document as an input means the
#       task role starts with no permissions and receives only what one caller
#       passes for one service, so the module cannot widen it even by accident.
#       Refactoring Rationale: the migrated system delegated this to an
#       external security manager that has no cloud equivalent and is not
#       pretended to have one. Its role is filled by these per-service task
#       roles together with the managed user directory, and the substitution
#       is documented as a mapping rather than presented as a port.
#       Assumption: null means the role is created with a trust policy and
#       nothing else. That is a valid and useful state -- a service that only
#       serves HTTP and reaches the database through an injected credential
#       needs no AWS API permission at all -- so null is not a missing value.
variable "task_role_policy_json" {
  description = <<-EOT
    Complete IAM policy document, as JSON, granting this one service the AWS
    API permissions it needs at run time. Attached to the task role, which the
    module otherwise leaves empty because it composes no permissions of its
    own. Leave null for a service that needs none. This is the TASK role used
    by the application, not the execution role the ECS agent uses to pull the
    image and read parameters.
  EOT
  type        = string
  default     = null
}

# WHAT: existing managed policies to attach to the task role alongside the
#       inline document.
# WHY : Trade-off: accepted as a second, separate way of granting the same role
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
}

# WHAT: the specific keys the roles may use to decrypt what they read.
# WHY : Assumption: a resource-scoped parameter-read or secret-read permission
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
variable "kms_key_arns" {
  description = <<-EOT
    ARNs of the KMS keys the roles may decrypt with: the keys protecting the
    parameters and secrets named in ssm_parameter_arns and secret_arns, plus
    any key the application itself uses at run time. Used as the Resource list
    of the decrypt statements, so the permission never widens to every key in
    the account. Comes from the kms module's outputs.
  EOT
  type        = list(string)
  default     = []
}

# -----------------------------------------------------------------------------
# Tagging.
# -----------------------------------------------------------------------------

# WHAT: additional tags merged onto every resource this module creates.
# WHY : Assumption: this MERGES with, rather than replaces, whatever the
#       calling root already applies through the provider's default_tags. A
#       module cannot set default_tags -- that belongs to a provider
#       configuration, and this module deliberately declares no provider so
#       the root keeps control of the region and of the package-wide tag set
#       -- so this input is the only way a caller can add a tag to one
#       service's resources without adding it to all sixteen instances.
#       Trade-off: because the two sets combine, a key present in both
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

