# =============================================================================
# infra/modules/ecs-cluster/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Creates the ONE Amazon ECS cluster every migrated CardDemo workload runs
#   inside, and associates the Fargate capacity providers that cluster is
#   permitted to place tasks on. Two resources and deliberately nothing else:
#   `aws_ecs_cluster.this` (the cluster, its Container Insights tier and its ECS
#   Exec session configuration) and `aws_ecs_cluster_capacity_providers.this`
#   (which providers the cluster holds, and how a task naming no launch type of
#   its own is placed across them). Inputs are declared with their descriptions
#   in variables.tf and every one of them is consumed here; the cluster ARN, name
#   and identifier plus the associated provider list are republished by
#   outputs.tf, since a `resource` block yields nothing to a caller by itself.
#
#   Assumptions: the narrow scope is the design. Task definitions, task and
#   execution roles, per-service log groups, target groups and autoscaling belong
#   to `ecs-service` (instantiated once per service); the VPC, subnet tiers and
#   security groups to `network`; dashboards, alarms and the notification topic
#   to `observability`; the internal load balancer and its listener rules to
#   `alb`; the four customer-managed keys to `kms`; the image repositories to
#   `ecr`; the nightly state machine to `step-functions-batch`. The ECS Exec log
#   group and the KMS key are INPUTS rather than resources, so both must already
#   exist when this module runs.
#
#   Alternatives Considered: a single combined module declaring the cluster AND
#   the services, rejected on cardinality -- the cluster exists once per
#   environment while ecs-service is instantiated once per service. Instantiating
#   a combined module per service would create eight clusters where one is
#   wanted; instantiating it once would drag the task definition, task role, log
#   group, target group and autoscaling into a module that runs a single time,
#   forfeiting exactly the per-service reuse eight services need. The accepted
#   cost is one more directory and one more output-to-input wiring step in each
#   root.
#
# Failure modes (the HCL analogue of a raised exception):
#   A calling root with no default `aws` provider configured fails at init,
#   because this module declares no provider block of its own (see versions.tf).
#   `environment` carries no default, so a root that omits it fails at plan
#   rather than composing a cluster name for the wrong environment. Setting
#   `execute_command_logging` to OVERRIDE without
#   `execute_command_log_group_name` is refused at plan time by a validation
#   block in variables.tf rather than by the API after the fact.
#   `aws_ecs_cluster_capacity_providers` owns the ENTIRE provider association for
#   the cluster it names, so a second association for the same cluster -- in
#   another module, another root, or made outside Terraform -- leaves the two
#   contending, each apply reverting the other. A value that satisfies its
#   declared type but that the ECS API refuses surfaces at apply, which is
#   outside what this package verifies: the tree is checked with fmt, validate,
#   plan, tflint and a policy scan, and `terraform apply` against a live account
#   is an operator action.
# =============================================================================

# -----------------------------------------------------------------------------
# Refactoring Rationale: ONE cluster, because the baseline it replaces was one
# homogeneous execution environment. The online half of the baseline runs inside
# a single CICS region catalogued in app/csd/CARDDEMO.CSD, whose 18 `DEFINE
# TRANSACTION` stanzas at L306-L488 each carry the same ISOLATE(YES),
# TASKDATAKEY(USER), ACTION(BACKOUT), TRANCLASS(DFHTCL00), PRIORITY(1) and
# RESTART(NO) -- one isolation setting, one transaction class, one priority and
# one backout discipline across every transaction in the region. The target is
# therefore one cluster hosting eight independently deployable and independently
# scaled services rather than eighteen runtimes or eighteen clusters. Where each
# region-level property lands in the target, and where the migrated behaviour
# diverges from the baseline, is recorded in
# docs/architecture/cobol-to-service-traceability.md and
# docs/architecture/context-and-container-diagrams.md; nothing here configures an
# analogue of ISOLATE(YES), which Fargate carries intrinsically. All of
# app/csd/CARDDEMO.CSD and both deployment paths that consume it remain exactly
# as they are: this module adds a path and removes none.
# -----------------------------------------------------------------------------

locals {
  # Alternatives Considered: `coalesce` over a nullable override, rather than
  #   either a required name input or an unconditionally composed name. A
  #   required input would push the carddemo-<component>-<env> convention out
  #   into every calling root; composing unconditionally would leave an operator
  #   adopting a cluster that already exists in an account no way to name it.
  #   This shape gives the convention as the default path and still admits the
  #   exception, and it is the shape the bootstrap module uses for its own state
  #   bucket and lock table names.
  # Assumptions: the composed shape is <prefix>-cluster-<env> because every
  #   resource in this package is named carddemo-<component>-<env>, which is what
  #   lets an operator select every resource belonging to one environment by
  #   prefix. At the ceilings variables.tf allows, the worst case is 57
  #   characters against a 255-character cluster-name bound.
  cluster_name = coalesce(var.cluster_name, "${var.name_prefix}-cluster-${var.environment}")

  # Assumptions: this set is ADDITIVE. The calling root configures the aws
  #   provider with `default_tags` and resources declared here inherit that set
  #   without restating it, so var.tags carries only the keys specific to this
  #   module or to one environment. A key set in both places resolves in favour
  #   of the resource-level value, so anything named here silently wins over the
  #   root -- which is the reason not to compose a broad set locally.
  # Trade-offs: the local has exactly one reader on purpose.
  #   aws_ecs_cluster_capacity_providers exposes no tag argument at all in the
  #   pinned provider schema, so the association below is untagged because it
  #   CANNOT be tagged, not because it was overlooked.
  tags = var.tags
}

# -----------------------------------------------------------------------------
# The cluster
# -----------------------------------------------------------------------------

resource "aws_ecs_cluster" "this" {
  name = local.cluster_name
  tags = local.tags

  # Assumptions: the cluster is the ONLY resource in the provider on which this
  #   setting exists, so it cannot be moved into the observability module even
  #   though that module owns the dashboards and alarms that read what it
  #   collects, and the gating policy scan in .github/workflows/infra-ci.yml
  #   looks for cluster-level monitoring on this resource. It is not enabled
  #   speculatively: the task and service metrics it publishes are the series
  #   those dashboards and alarms are built from.
  # Trade-offs: what this covers is narrower than it looks -- CLUSTER-LEVEL task
  #   and service metrics only, never application metrics, which are the
  #   Micrometer configuration in the services' common-lib. All three tiers are
  #   required and this is one. The tier is an input rather than a literal
  #   because the enhanced tier bills per additional observation for the task and
  #   container dimensions it adds, which a development environment pays for
  #   without using.
  setting {
    name  = "containerInsights"
    value = var.container_insights
  }

  # Trade-offs: this suppression is deliberate, scoped to ONE check, and recorded
  #   beside the argument it concerns rather than in a scanner configuration.
  #   CKV_AWS_224 asserts that ECS Exec traffic is encrypted with a
  #   customer-managed key, and the property IS delivered -- but only when a
  #   caller wires one, which the check cannot observe: it is satisfied solely by
  #   LITERAL values in the parsed HCL (a non-empty `kms_key_id` and a literal
  #   `cloud_watch_encryption_enabled = true`) and its parser does not resolve a
  #   `dynamic` block at all. This module can supply none of the three, because
  #   every literal key reference embeds an account identifier this package
  #   forbids committing, `true` with no key configured is a configuration the
  #   API refuses, and the log configuration is emitted conditionally so a root
  #   with no group yet still gets a valid cluster. The guarantee is moved to
  #   where it can be enforced: variables.tf refuses OVERRIDE without a log group
  #   name, and each environment root supplies the key from the `kms` module. The
  #   accepted cost is that "is a key wired in this environment" becomes a review
  #   question about the root's tfvars rather than a scanner assertion.
  #   CKV_AWS_65 and CKV_AWS_223 are NOT suppressed and both pass here.
  #checkov:skip=CKV_AWS_224:kms_key_id and the CloudWatch encryption flag are variable-driven and conditionally emitted, so the check's required literals cannot be expressed without committing an account-bearing key reference; enforced instead by the variables.tf OVERRIDE/log-group validation and by the environment root wiring the kms module's key.
  configuration {
    # Assumptions: ECS Exec opens an interactive session into a running task, so
    #   it is an operator access path into production and an unlogged session is
    #   an unaudited one; the gating policy scan expects the session
    #   configuration on the cluster resource, which is again the only place it
    #   exists.
    # Trade-offs: this block is emitted unconditionally even when every input is
    #   left at its default, which is what makes the module usable by a root that
    #   has provisioned neither a key nor a log group yet -- with the defaults in
    #   place `logging` carries "DEFAULT" and `kms_key_id` resolves to null,
    #   precisely the behaviour ECS applies when the parameter is omitted. The
    #   cost is that the block appears in every plan even when inert; wrapping it
    #   in another dynamic block would hide a security-relevant setting behind
    #   one more level of indirection for no behavioural difference.
    execute_command_configuration {
      # Assumptions: passed through rather than pinned to a literal, and safe to
      #   pass through because variables.tf closes the domain to NONE, DEFAULT
      #   and OVERRIDE and separately refuses OVERRIDE unless a log group name
      #   accompanies it, so the inconsistent pair cannot reach the API here.
      logging = var.execute_command_logging

      # Assumptions: this argument is spelled for a key identifier but is
      #   documented to accept a fully qualified key reference too, which is
      #   the form the environment root holds after taking it from the `kms`
      #   module's output. Null omits the argument rather than sending an
      #   empty string, which leaves the session channel on the AWS-managed
      #   default key instead of producing a configuration the API refuses.
      kms_key_id = var.kms_key_arn

      # Alternatives Considered: creating the log group inside this module.
      #   Rejected -- log groups are owned by ecs-service, which creates one per
      #   service, and by observability; a module that both created and consumed
      #   a group would put two modules in the position of declaring one
      #   resource, and the second apply would fail on an already-exists error
      #   instead of converging. Taking the NAME as an input keeps a single owner
      #   and matches the ECS contract, which requires the group to exist before
      #   the cluster references it. The KMS key is an input for the same reason.
      # Trade-offs: a dynamic block driven off the null rather than a composed
      #   default group name, which would name a group this module does not
      #   create so the failure would land at apply; emitting no log
      #   configuration at all is a valid cluster. The compromise is that the
      #   nesting reads less directly than a plain block would.
      dynamic "log_configuration" {
        for_each = var.execute_command_log_group_name == null ? [] : [var.execute_command_log_group_name]

        content {
          cloud_watch_log_group_name = log_configuration.value

          # Assumptions: derived from the key input rather than exposed as its
          #   own variable, because the two are not independent -- this flag
          #   makes ECS encrypt session output using the key configured on this
          #   same block, so setting it true with no key asks for encryption with
          #   nothing to encrypt under. Deriving it means a root that wires a key
          #   gets encrypted session logs without remembering a second argument.
          cloud_watch_encryption_enabled = var.kms_key_arn != null
        }
      }
    }

    # Alternatives Considered: `managed_storage_configuration`, which would
    #   encrypt Fargate ephemeral task storage under a customer-managed key, and
    #   for which reusing var.kms_key_arn is the obvious reach. Rejected: that
    #   input is documented as the key for the ECS Exec channel, and the four
    #   keys this package provisions are scoped to the database, object storage,
    #   the secret store and the queues, so silently widening one key's blast
    #   radius to a purpose it was not scoped for would contradict the
    #   least-privilege boundary the kms module draws. Adding storage encryption
    #   belongs to a change in that module's key set first.
  }

  # Alternatives Considered: `service_connect_defaults`, deliberately absent as
  #   is every service-discovery resource. The service-to-service path is already
  #   decided -- an internal load balancer reached through security groups that
  #   permit load-balancer-to-application traffic on 8080, with an HTTP API and a
  #   VPC link at the edge -- so a Service Connect namespace here would stand up
  #   a SECOND discovery mechanism beside the one `alb` provides, leaving two
  #   answers to how one service addresses another and no rule for which wins.
}

# -----------------------------------------------------------------------------
# The capacity providers the cluster holds
# -----------------------------------------------------------------------------

resource "aws_ecs_cluster_capacity_providers" "this" {
  # Assumptions: the cluster is referenced through its resource attribute
  #   rather than through local.cluster_name, even though the two resolve to
  #   the same string. Passing the local would make this argument a constant
  #   as far as Terraform's graph is concerned, so the association would
  #   carry no dependency edge on the cluster and could be ordered before
  #   the cluster it associates to. Reading the attribute is what records
  #   the edge, and it is the only thing that does.
  cluster_name = aws_ecs_cluster.this.name

  # Alternatives Considered: Fargate capacity, rather than EC2-backed capacity or
  #   a function runtime. This is decision D2 and the reasoning is specific to
  #   this workload: the eight services are long-running request and response
  #   APIs holding WARM JDBC CONNECTION POOLS, which needs a task process that
  #   outlives a single request, and the batch steps run longer than Lambda's
  #   fifteen-minute execution ceiling -- a hard service limit, and why a function
  #   is ruled out of the batch tier while still being adopted for the three short
  #   glue states of the nightly chain. EC2 would add patching and capacity
  #   management for no benefit and prices as instance-hours whether or not work
  #   is running. The full analysis, including the cost comparison, is in
  #   docs/adr/ADR-002-compute-platform.md.
  # Assumptions: no EC2-backed capacity provider is attached and none can be --
  #   `aws_ecs_capacity_provider` exists only for auto-scaling-group-backed
  #   capacity, which needs a group and launch template this module does not
  #   create; variables.tf enforces the same boundary by restricting this input's
  #   domain to the two Fargate provider names.
  capacity_providers = var.capacity_providers

  # Assumptions: this is a DEFAULT only for a RunTask or CreateService call that
  #   names neither a launch type nor its own capacity-provider strategy.
  #   infra/modules/ecs-service supplies an explicit on-demand FARGATE strategy
  #   for interactive services, so those services intentionally override this
  #   mix, while Step Functions or another caller that omits a strategy inherits
  #   it -- which is why this cluster-level setting is not proof that every
  #   service uses Spot.
  # Trade-offs: the default strategy reserves a base on on-demand Fargate and
  #   apportions everything above that base in favour of on-demand, and the
  #   MECHANISM is what bounds the exposure: ECS satisfies the base first, so
  #   based tasks always run on non-interruptible capacity, and a full Spot
  #   reclamation removes only the weighted share ABOVE the base. Setting the Spot
  #   weight to zero makes that provider ineligible for placement while leaving it
  #   associated, so an environment can opt out without diverging in topology.
  # Alternatives Considered: a static block, rejected because variables.tf permits
  #   an EMPTY strategy list and a static block cannot express zero occurrences --
  #   an empty one would send a strategy with no entries, which ECS refuses.
  #   Iterating also lets `weight` and `base` stay null when a caller omits them,
  #   so an omitted value reaches the API as absent rather than as a zero it would
  #   treat as an explicit ineligible weight.
  dynamic "default_capacity_provider_strategy" {
    for_each = var.default_capacity_provider_strategy

    content {
      capacity_provider = default_capacity_provider_strategy.value.capacity_provider
      weight            = default_capacity_provider_strategy.value.weight
      base              = default_capacity_provider_strategy.value.base
    }
  }
}
