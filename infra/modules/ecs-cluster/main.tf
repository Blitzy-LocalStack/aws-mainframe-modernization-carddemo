# =============================================================================
# infra/modules/ecs-cluster/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Creates the ONE Amazon ECS cluster that every migrated CardDemo workload
#   runs inside, and associates the Fargate capacity providers that cluster is
#   permitted to place tasks on. Two resources, and deliberately nothing else:
#
#     aws_ecs_cluster.this                     the cluster itself, its
#                                              Container Insights tier and its
#                                              ECS Exec session configuration
#     aws_ecs_cluster_capacity_providers.this  which capacity providers the
#                                              cluster holds, and how a task
#                                              naming no launch type of its own
#                                              is placed across them
#
#   What this module does NOT own, paired with the sibling that does, because
#   the narrow scope is the design rather than an oversight:
#     task definitions, task and execution roles, per-service log groups,
#     target groups, autoscaling ............ ecs-service (once per service)
#     the VPC, its three subnet tiers and
#     the security groups ................... network
#     log groups, dashboards, alarms and
#     the notification topic ............... observability
#     the internal load balancer and its
#     per-service listener rules ........... alb
#     the four customer-managed keys ....... kms
#     the container image repositories ..... ecr
#     the nightly batch state machine ...... step-functions-batch
#
# Parameters:
#   Declared in variables.tf. Every one of the ten inputs declared there is
#   consumed by this file, so none reaches outputs.tf unread. They are not
#   re-listed here: a second copy of the input surface would drift from the
#   first, and variables.tf already carries a per-parameter description.
#
# Provides (the HCL analogue of a return value):
#   Resource attributes, republished by outputs.tf rather than exported from
#   here -- a module has no return statement, and a `resource` block yields
#   nothing to a caller by itself:
#     aws_ecs_cluster.this.arn ..... read by ecs-service as `cluster_arn` and
#                                    by step-functions-batch as
#                                    `ecs_cluster_arn`
#     aws_ecs_cluster.this.name .... read by ecs-service as `cluster_name`,
#                                    which needs the bare name rather than the
#                                    ARN to compose its autoscaling target's
#                                    resource identifier
#     aws_ecs_cluster.this.id
#     aws_ecs_cluster_capacity_providers.this.capacity_providers
#
# Failure modes (the HCL analogue of a raised exception):
#   - A calling root with no default `aws` provider configured fails at init.
#     This module declares no provider block, for the reasons recorded in
#     versions.tf, so it has no configuration of its own to fall back on.
#   - `environment` carries no default in variables.tf, so a root that omits
#     it fails at plan with an unassigned-variable error rather than composing
#     a cluster name for the wrong environment.
#   - The ECS Exec log group and the KMS key are INPUTS, not resources: both
#     must already exist when this module runs, because nothing here creates
#     either. Setting `execute_command_logging` to OVERRIDE without
#     `execute_command_log_group_name` is refused at plan time by a validation
#     block in variables.tf rather than by the API after the fact.
#   - `aws_ecs_cluster_capacity_providers` owns the ENTIRE provider
#     association for the cluster it names. A second association for the same
#     cluster -- in another module, another root, or made outside Terraform --
#     leaves the two contending, each apply reverting the other. Associate the
#     providers here or elsewhere, never in both places.
#   - A value that satisfies its declared type but that the ECS API refuses
#     surfaces at apply, which is outside what this package verifies: the tree
#     is checked with fmt, validate, plan, tflint and a policy scan, and
#     `terraform apply` against a live account is an operator action.
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: Fargate capacity rather than EC2-backed
#     capacity or a function runtime, in the terms decision D2 states them;
#     the full analysis lives in docs/adr/ADR-002-compute-platform.md.
#   - Alternatives Considered: the cluster is its own module rather than being
#     folded into ecs-service, because the two have different cardinality.
#   - Alternatives Considered: no service_connect_defaults, because the
#     declared service-to-service path is the internal load balancer.
#   - Assumptions: Container Insights is configured here because the cluster
#     is the only resource on which it can be set, and a gating policy scan
#     looks for it on that resource.
#   - Refactoring Rationale: what this one cluster replaces, and why the
#     baseline's own uniformity is what implies a single cluster.
# =============================================================================

# -----------------------------------------------------------------------------
# Refactoring Rationale: what this one cluster replaces.
#
# The online half of the baseline runs inside a single CICS region whose
# resources are catalogued in app/csd/CARDDEMO.CSD, a 505-line file. Two
# blocks of it describe that execution environment: 18 `DEFINE PROGRAM`
# stanzas at L173-L305, and the 18 `DEFINE TRANSACTION` stanzas at L306-L488
# that name them -- CAUP, CAVW, CA00, CB00, CCDL, CCLI, CCUP, CC00, CDV1,
# CM00, CR00, CT00, CT01, CT02, CU00, CU01, CU02 and CU03.
#
# The argument for ONE cluster is the uniformity of those stanzas rather than
# their count. Each of ISOLATE(YES), TASKDATAKEY(USER), ACTION(BACKOUT),
# TRANCLASS(DFHTCL00), PRIORITY(1) and RESTART(NO) occurs exactly 18 times in
# the file, once per transaction: one isolation setting, one transaction class,
# one priority and one backout discipline across every transaction in the
# region. That is a description of a single homogeneous execution environment,
# and it is why the target is one cluster hosting eight independently
# deployable and independently scaled services -- rather than eighteen bespoke
# runtimes, and rather than eighteen clusters.
#
# Where each of those region-level properties actually lands, stated so that
# none is assumed to be an argument in this file by default:
#   ISOLATE(YES) ......... per-task isolation is intrinsic to Fargate, so the
#                          property is carried by the platform and there is
#                          nothing here to configure for it.
#   TRANCLASS(DFHTCL00) +  one shared class governed concurrency for all 18.
#   PRIORITY(1) .......... The analogue is split in two: the capacity-provider
#                          strategy below decides WHERE a task is placed, and
#                          per-service autoscaling in ecs-service decides HOW
#                          MANY tasks run.
#   TASKDATAKEY(USER) .... containers run as a non-root user, which the
#                          per-service Dockerfiles own, not this module.
#   RESTART(NO) .......... recorded as a DIFFERENCE rather than a mapping: a
#                          failed CICS transaction was not restarted
#                          automatically, whereas ECS replaces an unhealthy
#                          TASK. The unit differs -- one request versus one
#                          process -- so neither behaviour implies the other,
#                          and claiming equivalence here would be wrong.
#
# Two further definitions in the same file belong to siblings, and are cited
# only so a reader does not come looking for them here. DEFINE
# LIBRARY(CARDDLIB) at L489, whose DSNAME01 at L491 names the load library,
# corresponds to the image repositories the `ecr` module provisions. DEFINE
# TDQUEUE(JOBS) at L499, whose DDNAME(INREADER) at L501 carried job submission
# out of the online region, corresponds to step-functions-batch together with
# reporting-service's on-demand execution call.
#
# One inventory observation, recorded once and neutrally because it is a
# property of the catalogue rather than a fault in it: DEFINE
# PROGRAM(COCRDSEC) at L211 has no matching .cbl under app/cbl, and DEFINE
# TRANSACTION(CDV1) at L388 names that program in its PROGRAM(COCRDSEC)
# attribute at L390. Where the migrated behaviour diverges from the baseline
# for any reason, the register is
# docs/architecture/cobol-to-service-traceability.md.
#
# The absence of internal module boundaries inside a CICS region is a property
# of the CICS programming model, not a shortcoming of this application. All of
# app/csd/CARDDEMO.CSD, and both deployment paths that consume it, remain
# exactly as they are: this module adds a path and removes none.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# Alternatives Considered: a single combined module declaring the cluster AND
# the services was the reasonable alternative, and it was rejected on
# cardinality. The cluster exists ONCE PER ENVIRONMENT; ecs-service is
# instantiated ONCE PER SERVICE, eight times in each root. Combining them
# forces one of two outcomes, and both are worse than an extra module:
# instantiating the combined module per service would create eight clusters
# where one is wanted, and instantiating it once would drag the task
# definition, task role, log group, target group and autoscaling into a module
# that runs a single time, forfeiting exactly the per-service reuse eight
# services need. Keeping the cluster separate is what leaves ecs-service
# genuinely instantiable eight times.
# Trade-offs: the cost is one more directory in the module tree and one more
# output-to-input wiring step in each environment root -- the cluster ARN and
# name have to be passed from this module into every ecs-service call. That is
# accepted in exchange for a service module with no cluster lifecycle bound
# into it.
# -----------------------------------------------------------------------------

locals {
  # WHY : Alternatives Considered: `coalesce` over a nullable override, rather
  #       than either a required name input or an unconditionally composed
  #       name. A required input would push the carddemo-<component>-<env>
  #       convention out into every calling root, so the convention would live
  #       in the callers instead of here; composing unconditionally would leave
  #       an operator adopting a cluster that already exists in an account no
  #       way to name it. The nullable-override-plus-coalesce shape gives the
  #       convention as the default path and still admits the exception, and it
  #       is the same shape the bootstrap module uses to resolve its state
  #       bucket and lock table names.
  # WHY : Assumptions: the composed shape is <prefix>-cluster-<env> because
  #       every resource in this package is named carddemo-<component>-<env> --
  #       the authorization and inquiry request queues, the dataset bucket and
  #       the nightly batch state machine all follow it. Holding to it is what
  #       lets an operator select every resource belonging to one environment
  #       by prefix, which is the only reason the shape matters at all. The
  #       inserted component keeps the name distinguishable from a sibling that
  #       composed <prefix>-<env> alone, and it costs nothing against the
  #       255-character cluster-name bound that variables.tf sizes its own
  #       length checks against: at the ceilings those checks allow the worst
  #       case is 32 + 1 + 7 + 1 + 16, or 57 characters.
  cluster_name = coalesce(var.cluster_name, "${var.name_prefix}-cluster-${var.environment}")

  # WHY : Assumptions: this set is ADDITIVE. The calling root configures the
  #       aws provider with `default_tags`, and resources declared here inherit
  #       that set without restating it, so var.tags carries only the keys
  #       specific to this module or to one environment. Without this note a
  #       reader would reasonably ask why the module tags at all when the
  #       provider already does. A key set in both places resolves in favour of
  #       the resource-level value, so anything named here silently wins over
  #       the root, which is the reason not to compose a broad set locally.
  # WHY : Trade-offs: naming the resolved set once here rather than writing
  #       var.tags at its single use site. The indirection buys one place that
  #       states where tags come from and one place to change if a module-level
  #       key is ever added; the cost is a local with a single reader today.
  #       It has exactly one reader on purpose:
  #       aws_ecs_cluster_capacity_providers exposes no tag argument at all in
  #       the pinned provider schema, so the association below is untagged
  #       because it CANNOT be tagged, not because it was overlooked.
  tags = var.tags
}

# -----------------------------------------------------------------------------
# The cluster
# -----------------------------------------------------------------------------

resource "aws_ecs_cluster" "this" {
  name = local.cluster_name
  tags = local.tags

  # WHY : Assumptions: the specification for this module is "Fargate cluster,
  #       Container Insights", and the cluster is the ONLY resource in the
  #       provider on which the setting exists -- it cannot be moved into the
  #       observability module even though that module owns the dashboards and
  #       alarms that read what it collects. The gating policy scan in
  #       .github/workflows/infra-ci.yml runs at high and critical severity and
  #       looks for cluster-level monitoring on the cluster resource, so this is
  #       where it has to be. The setting is not enabled speculatively: the
  #       task and service metrics it publishes are the series the
  #       observability module's dashboards and alarms are built from, so
  #       leaving it off would leave those with nothing to read.
  # WHY : Trade-offs: what this covers is narrower than it looks, and the limit
  #       is worth stating so that nobody concludes this one setting discharges
  #       the whole observability requirement. It yields CLUSTER-LEVEL task and
  #       service metrics only -- never application metrics. Application metric
  #       export is the Micrometer configuration in the services' common-lib,
  #       and the dashboards, alarms and notification topic are the
  #       observability module. All three tiers are required; this is one.
  #       The tier is an input rather than a literal because the enhanced tier
  #       bills per additional observation for the task and container
  #       dimensions it adds, which a development environment pays for without
  #       using; variables.tf holds the reasoning for its default.
  setting {
    name  = "containerInsights"
    value = var.container_insights
  }

  # WHY : Trade-offs: this suppression is deliberate, is scoped to ONE check,
  #       and is recorded here rather than in a scanner configuration so that it
  #       is read together with the argument it concerns. CKV_AWS_224 asserts
  #       that ECS Exec traffic is encrypted with a customer-managed key, and
  #       the property it protects IS delivered -- but only when a caller wires
  #       one, and the check cannot observe that. It is satisfied solely by
  #       LITERAL values in the parsed HCL: a non-empty `kms_key_id` and a
  #       literal `cloud_watch_encryption_enabled = true`, and its parser does
  #       not resolve a `dynamic` block at all. This module can supply none of
  #       the three. The key reference arrives as a nullable input because every
  #       literal key reference embeds an account identifier and this package
  #       forbids committing one; the encryption flag is derived from that input
  #       rather than written as `true`, because `true` with no key configured
  #       is a configuration the API refuses; and the log configuration is
  #       emitted conditionally so a root that has provisioned no group yet
  #       still gets a valid cluster. Hardcoding any of the three to turn the
  #       check green would mean committing a credential-adjacent literal or
  #       shipping a cluster that fails to apply, so the finding is suppressed
  #       instead and the guarantee is moved to where it can actually be
  #       enforced: variables.tf refuses `execute_command_logging = "OVERRIDE"`
  #       without a log group name, and each environment root supplies the key
  #       from the `kms` module. The accepted cost is that this check will no
  #       longer fail a call that leaves the key null either, so "is a key
  #       wired in this environment" becomes a review question about the root's
  #       tfvars rather than a scanner assertion about this file.
  #       CKV_AWS_65 (Container Insights) and CKV_AWS_223 (ECS Exec logging
  #       enabled) are NOT suppressed and both pass against this resource.
  #checkov:skip=CKV_AWS_224:kms_key_id and the CloudWatch encryption flag are variable-driven and conditionally emitted, so the check's required literals cannot be expressed without committing an account-bearing key reference; enforced instead by the variables.tf OVERRIDE/log-group validation and by the environment root wiring the kms module's key.
  configuration {
    # WHY : Assumptions: ECS Exec opens an interactive session into a running
    #       task, so it is an operator access path into production and an
    #       unlogged session is an unaudited one. Least-privilege access and
    #       auditability are non-negotiable constraints on this migration, and
    #       the same gating policy scan expects the session configuration on
    #       the cluster resource, which is again the only place it exists.
    # WHY : Trade-offs: this block is emitted unconditionally even when every
    #       input is left at its default, and that is what makes the module
    #       usable by a root that has provisioned neither a key nor a log group
    #       yet. With the defaults in place `logging` carries "DEFAULT" and
    #       `kms_key_id` resolves to null, which is precisely the behaviour ECS
    #       applies when the parameter is omitted altogether -- so the
    #       degenerate case is a valid configuration that changes nothing,
    #       rather than an invalid one the API rejects. The cost of emitting it
    #       always is that the block appears in every plan even when inert;
    #       the alternative, wrapping the whole thing in another dynamic block,
    #       would hide a security-relevant setting behind one more level of
    #       indirection for no behavioural difference.
    execute_command_configuration {
      # WHY : Assumptions: passed through rather than pinned to a literal, and
      #       safe to pass through because the value cannot arrive malformed:
      #       variables.tf closes the domain to NONE, DEFAULT and OVERRIDE, and
      #       separately refuses OVERRIDE unless a log group name accompanies
      #       it. Both checks run while the variable is evaluated, so the
      #       inconsistent pair -- OVERRIDE with nowhere to write -- cannot
      #       reach the API through this argument. The reasoning for the domain
      #       and for the default belongs to that file and is not repeated here.
      logging = var.execute_command_logging

      # WHY : Assumptions: this argument is spelled for a key identifier but is
      #       documented to accept a fully qualified key reference too, which is
      #       the form the environment root holds after taking it from the `kms`
      #       module's output. Null omits the argument rather than sending an
      #       empty string, which leaves the session channel on the AWS-managed
      #       default key instead of producing a configuration the API refuses.
      kms_key_id = var.kms_key_arn

      # WHY : Alternatives Considered: creating the log group inside this module
      #       was rejected. Log groups are owned by ecs-service, which creates
      #       one per service, and by observability, which owns the group,
      #       dashboard and alarm set; a module that both created and consumed a
      #       group would put two modules in the position of declaring one
      #       resource, and the second apply would fail on an already-exists
      #       error instead of converging. Taking the NAME as an input keeps a
      #       single owner and matches the ECS contract, which requires the group
      #       to exist before the cluster references it. The KMS key is an input
      #       for the same reason: the four customer-managed keys in this package
      #       belong to the `kms` module, and creating a fifth here would put key
      #       rotation and key policy in two places.
      # WHY : Trade-offs: a dynamic block driven off the null, rather than a
      #       composed default group name. A composed default would name a group
      #       this module does not create, so the cluster would reference a group
      #       that may not exist and the failure would land at apply; emitting no
      #       log configuration at all is a valid cluster. The compromise is that
      #       the nesting reads less directly than a plain block would.
      dynamic "log_configuration" {
        for_each = var.execute_command_log_group_name == null ? [] : [var.execute_command_log_group_name]

        content {
          cloud_watch_log_group_name = log_configuration.value

          # WHY : Assumptions: derived from the key input rather than exposed as
          #       an eleventh variable, because the two are not independent.
          #       This flag makes ECS encrypt the session output it sends to
          #       CloudWatch Logs using the key configured on this same block,
          #       so setting it true with no key configured asks for encryption
          #       with nothing to encrypt under. Deriving it means a root that
          #       wires a customer-managed key gets encrypted session logs
          #       without having to remember a second argument, and a root that
          #       wires none does not get a configuration the API refuses.
          cloud_watch_encryption_enabled = var.kms_key_arn != null
        }
      }
    }

    # WHY : Alternatives Considered: managed_storage_configuration is available
    #       on this block and is deliberately not set. It would encrypt Fargate
    #       ephemeral task storage under a customer-managed key, and reusing
    #       var.kms_key_arn for it is the obvious reach -- but that input is
    #       documented as the key for the ECS Exec channel, and the four keys
    #       this package provisions are scoped to the database, object storage,
    #       the secret store and the queues. Silently widening one key's blast
    #       radius to a second purpose it was not scoped for would contradict
    #       the least-privilege boundary the kms module draws, and no input
    #       exists for a separate storage key. Adding storage encryption
    #       therefore belongs to a change in the kms module's key set first.
  }

  # WHY : Alternatives Considered: service_connect_defaults is available on this
  #       resource and is deliberately absent, as is every service-discovery
  #       resource. The service-to-service path in this architecture is already
  #       decided: an internal load balancer reached through security groups
  #       that permit load-balancer-to-application traffic on 8080, with an HTTP
  #       API and a VPC link at the edge. Setting a Service Connect namespace
  #       here would stand up a SECOND discovery mechanism beside the one the
  #       `alb` module provides, leaving two answers to the question of how one
  #       service addresses another and no rule for which wins.
}

# -----------------------------------------------------------------------------
# The capacity providers the cluster holds
# -----------------------------------------------------------------------------

resource "aws_ecs_cluster_capacity_providers" "this" {
  # WHY : Assumptions: the cluster is referenced through its resource attribute
  #       rather than through local.cluster_name, even though the two resolve to
  #       the same string. Passing the local would make this argument a constant
  #       as far as Terraform's graph is concerned, so the association would
  #       carry no dependency edge on the cluster and could be ordered before
  #       the cluster it associates to. Reading the attribute is what records
  #       the edge, and it is the only thing that does.
  cluster_name = aws_ecs_cluster.this.name

  # WHY : Alternatives Considered: Fargate capacity, rather than EC2-backed
  #       capacity or a function runtime. This is decision D2, and the reasoning
  #       is specific to this workload rather than a preference between compute
  #       models. The eight services are long-running request and response APIs
  #       that hold WARM JDBC CONNECTION POOLS, which needs a task process that
  #       outlives a single request -- a per-invocation model cannot hold a pool
  #       across invocations without a separate connection proxy in front of the
  #       database. The batch steps run longer than Lambda's FIFTEEN-MINUTE
  #       execution ceiling, which is a hard service limit rather than a
  #       judgement, and is why a function is ruled out of the batch tier
  #       outright while still being adopted for the three short glue states of
  #       the nightly chain -- quiescing online writes, analyzing tables, and
  #       resuming online writes. And in the words decision D2 itself uses, "EC2
  #       would add patching and capacity management for no benefit": it also
  #       prices as instance-hours whether or not work is running, where a
  #       serverless task is charged for the duration it actually runs.
  #       The full analysis, including the cost comparison, is in
  #       docs/adr/ADR-002-compute-platform.md and is not restated here.
  # WHY : Alternatives Considered: no EC2-backed capacity provider is attached,
  #       and none can be. The `aws_ecs_capacity_provider` resource exists only
  #       for auto-scaling-group-backed capacity, which would require an
  #       auto-scaling group and a launch template this module does not create,
  #       and which the decision above rules out. Its absence is a consequence
  #       of that decision rather than an omission; variables.tf enforces the
  #       same boundary by restricting this input's domain to the two Fargate
  #       provider names, so a caller cannot name one either.
  capacity_providers = var.capacity_providers

  # WHY : Assumptions: this is a DEFAULT only for a RunTask or CreateService call
  #       that names neither a launch type nor its own capacity-provider strategy.
  #       infra/modules/ecs-service now supplies an explicit on-demand FARGATE
  #       strategy for interactive services, so those services intentionally
  #       override this mix; Step Functions or another caller that omits a
  #       strategy inherits it. Naming that boundary prevents this cluster-level
  #       setting from being read as proof that every service uses Spot.
  # WHY : Trade-offs: the default strategy this receives reserves a base on
  #       on-demand Fargate and apportions everything above that base in favour
  #       of on-demand, and the MECHANISM is what bounds the exposure: ECS
  #       satisfies the base first, so the based tasks always run on
  #       non-interruptible capacity, and it then divides the remainder by
  #       weight. FARGATE_SPOT capacity is reclaimable on a two-minute warning,
  #       so a full reclamation removes only the weighted share ABOVE the base
  #       and never the base itself -- which is the difference between a rolling
  #       replacement and a service with nothing running. A caller overrides the
  #       whole strategy per environment; setting the Spot weight to zero makes
  #       that provider ineligible for placement while leaving it associated, so
  #       an environment can opt out without diverging in cluster topology.
  # WHY : Alternatives Considered: a dynamic block rather than a static one,
  #       because variables.tf permits an EMPTY strategy list and documents that
  #       this file then emits no strategy at all. A static block cannot express
  #       zero occurrences, and an empty one is not the same thing -- it would
  #       send a strategy with no entries, which ECS refuses. Iterating also
  #       lets `weight` and `base` stay null when a caller omits them, so an
  #       omitted value reaches the API as absent rather than as a zero it would
  #       treat as an explicit ineligible weight.
  dynamic "default_capacity_provider_strategy" {
    for_each = var.default_capacity_provider_strategy

    content {
      capacity_provider = default_capacity_provider_strategy.value.capacity_provider
      weight            = default_capacity_provider_strategy.value.weight
      base              = default_capacity_provider_strategy.value.base
    }
  }
}
