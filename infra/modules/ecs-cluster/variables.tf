# =============================================================================
# infra/modules/ecs-cluster/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input surface of the `ecs-cluster` module. Everything main.tf
#   reads, and everything outputs.tf re-exposes, originates in one of the
#   `variable` blocks below; everything the calling roots pass in is named
#   here.
#
#   This directory is a REUSABLE MODULE and never a Terraform root, so no
#   terraform.tfvars accompanies this file. Values arrive from
#   infra/envs/dev/main.tf and infra/envs/prod/main.tf, which take them in
#   turn from infra/envs/<env>/terraform.tfvars.
#
# Parameters:
#   Identity and naming
#     name_prefix                         string        default "carddemo"
#     environment                         string        REQUIRED, no default
#     cluster_name                        string        nullable, default null
#   Cluster behaviour
#     container_insights                  string        default "enabled"
#     capacity_providers                  list(string)  default Fargate pair
#     default_capacity_provider_strategy  list(object)  default on-demand base
#   Optional ECS Exec observability
#     execute_command_log_group_name      string        nullable, default null
#     execute_command_logging             string        default "DEFAULT"
#     kms_key_arn                         string        nullable, default null
#   Tagging
#     tags                                map(string)   default {}
#
#   Each block carries the per-parameter description the HCL documentation
#   gate requires. The list above exists so the whole surface can be read in
#   one place without scrolling the file.
#
# Provides (the HCL analogue of a return value):
#   Nothing. A `variable` block yields no value of its own: it declares a
#   name, a type and a constraint that main.tf and outputs.tf then consume.
#   The module's results are exposed by outputs.tf. This file declares no
#   resource, data source, local value, output, nested module, provider or
#   terraform block, and it reads no other file.
#
# Failure modes (the HCL analogue of a raised exception):
#   - `environment` carries no default, so a calling root that omits it fails
#     at `terraform plan` with an unassigned-variable error instead of
#     provisioning a cluster named for the wrong environment.
#   - Every `validation` block below rejects a malformed value while the
#     variable is being evaluated, which is before any resource is created,
#     so the rejection costs no API call and leaves no partial state behind.
#   - A value that satisfies its declared type but that the ECS API refuses
#     surfaces later: at plan time for the arguments the pinned provider
#     validates itself, and only at apply time for the arguments it does not.
#     Which of the two applies is recorded at each input below, because it
#     determines whether a `validation` block here is redundant or load
#     bearing.
#   - Declaring an input that no other file in the module consumes fails the
#     `terraform_unused_declarations` rule configured in infra/.tflint.hcl,
#     so this file is deliberately limited to the ten inputs listed above.
#
# WHY (non-obvious design decisions):
#   - Assumptions: this module owns the cluster and nothing else, so there is
#     deliberately no input for a VPC, a subnet, a security group, task
#     sizing, a task count, a container image, a port, a load-balancer
#     target group, a task or execution role, an autoscaling bound or a log
#     retention period. Those belong to the `network`, `ecs-service` and
#     `observability` modules; restating any of them here would duplicate a
#     sibling surface and leave behind a declaration this module never reads.
#   - Alternatives Considered: no region input is declared. The region
#     arrives with the provider configuration the calling root owns, and this
#     module declares no provider block of its own, so such an input would
#     have no consumer and would fail the unused-declaration rule.
#   - Trade-offs: nine inputs carry a default and one does not. That makes the
#     module callable with a single argument while keeping the environment
#     discriminator impossible to omit by accident.
# =============================================================================

# -----------------------------------------------------------------------------
# Identity and naming
# -----------------------------------------------------------------------------

variable "name_prefix" {
  # WHAT: the leading component of the composed cluster name.
  # WHY : Assumptions: every resource in this infrastructure package is named
  #       carddemo-<component>-<env> -- the request queues, the dataset
  #       bucket and the nightly batch state machine all follow it. The
  #       default IS that convention, so a caller that wants the house name
  #       supplies nothing; the variable exists so the prefix is a parameter
  #       of the module rather than a literal buried in main.tf, which is
  #       what lets one root stamp out a second cluster under a different
  #       prefix with `for_each`.
  # WHY : Trade-offs: the charset is narrowed below to lowercase letters,
  #       digits and hyphens, which is stricter than what the pinned
  #       provider accepts for a cluster name -- it takes mixed case and
  #       underscores as well. The compromise is that a caller cannot
  #       express a name like CardDemo_Cluster. What it buys is that the
  #       composed name cannot diverge in case or in separator from every
  #       sibling resource name, and that a bad prefix is rejected while the
  #       variable is evaluated rather than when the ECS API refuses it.
  description = "Leading component of the composed cluster name; joined to `environment` with a hyphen."
  type        = string
  default     = "carddemo"

  validation {
    # WHY: Assumptions: 32 characters is the ceiling because the composed name
    #      is "<name_prefix>-<environment>" and the pinned provider bounds a
    #      cluster name to 255 characters. Thirty-two here plus the sixteen
    #      allowed for the environment leaves the composed name well inside
    #      that limit, with room for a caller that suffixes further.
    #      Anchoring both ends of the pattern is what rejects a leading or
    #      trailing hyphen, which would otherwise produce a doubled
    #      separator in the composed name.
    condition = (
      can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", var.name_prefix)) &&
      length(var.name_prefix) <= 32
    )
    error_message = "The name_prefix must be 1 to 32 characters of lowercase letters, digits and hyphens, starting and ending with a letter or digit."
  }
}

variable "environment" {
  # WHY : Alternatives Considered: defaulting this to "dev" was rejected.
  #       Terraform reports nothing when a default is silently accepted, so
  #       a prod root that forgot the argument would plan and apply a cluster
  #       named carddemo-dev in the production account, and the mistake would
  #       only surface as a name collision or a misrouted service later.
  #       Leaving the default off converts that silent outcome into an
  #       unassigned-variable error at plan time, before any API call.
  description = "Deployment-environment discriminator appended to `name_prefix` to compose the cluster name."
  type        = string

  validation {
    # WHY: Alternatives Considered: a membership test naming the two known
    #      environments was rejected in favour of checking the FORMAT. The
    #      infrastructure package is required to be parameterised for at
    #      least dev and prod, and a closed set would additionally forbid a
    #      third root -- a staging or a per-developer environment -- while
    #      catching no failure that the format check misses. The failure
    #      actually worth catching is a value that cannot appear in a
    #      resource name, and that is a question of charset and length, not
    #      of membership.
    #      Sixteen characters is the ceiling for the same arithmetic as
    #      name_prefix: it keeps the composed name inside the provider's
    #      255-character cluster-name bound.
    condition = (
      can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", var.environment)) &&
      length(var.environment) <= 16
    )
    error_message = "The environment must be 1 to 16 characters of lowercase letters, digits and hyphens, starting and ending with a letter or digit."
  }
}

variable "cluster_name" {
  # WHY : Alternatives Considered: two other shapes were rejected. Making the
  #       name a required input would force both roots, and every future
  #       root, to restate the carddemo-<component>-<env> convention by hand,
  #       so the convention would live in the callers instead of in the
  #       module. Composing the name unconditionally would leave no way to
  #       point the module at a cluster name that already exists in an
  #       account, which is the case an operator hits when adopting a cluster
  #       rather than creating one. A nullable override resolved by
  #       `coalesce` in main.tf gives the convention as the default path and
  #       still admits the exception; it is also the shape the bootstrap
  #       module already uses for its state-bucket and lock-table names.
  # WHY : Trade-offs: no charset or length validation is asserted here. A
  #       caller reaching for this input is naming a cluster that exists
  #       outside this module's control, so the only authority on whether the
  #       name is legal is the provider, which bounds it to 255 characters of
  #       alphanumerics, hyphens and underscores and reports a violation at
  #       plan time. Re-asserting a narrower rule here would reject exactly
  #       the pre-existing names this input was added to accommodate.
  description = "Explicit cluster name that overrides the composed `name_prefix`-`environment` value; null keeps the composed name."
  type        = string
  default     = null
}

# -----------------------------------------------------------------------------
# Cluster behaviour
# -----------------------------------------------------------------------------

variable "container_insights" {
  # WHAT: the value main.tf writes into the cluster's containerInsights
  #       setting, which selects the cluster-level monitoring tier.
  # WHY : Assumptions: the module specification for `ecs-cluster` is "Fargate
  #       cluster, Container Insights", so the default is the on value. The
  #       policy scan in .github/workflows/infra-ci.yml runs at high and
  #       critical severity as a gating step and expects cluster-level
  #       monitoring to be configured on the cluster resource itself; a
  #       default of "disabled" would mean every caller had to remember to
  #       switch it on and that the gate failed on an unmodified call.
  # WHY : Trade-offs: the tier is a parameter rather than a literal because
  #       "enhanced" bills per additional observation for the extra task and
  #       container dimensions it collects, and a development environment
  #       gains nothing from container-level drill-down that it pays for on
  #       every task. Exposing the input lets dev sit on "enabled" or
  #       "disabled" while prod moves to "enhanced" without a code change.
  description = "Cluster-level Container Insights tier written into the containerInsights cluster setting."
  type        = string
  default     = "enabled"

  validation {
    # WHY: Assumptions: this validation is required because the pinned
    #      provider does not constrain the value. It validates the setting
    #      NAME against a single-entry list but applies no validator at all
    #      to the setting VALUE -- a probe against provider 6.57.1 accepted
    #      "bogusValue", "ENABLED" and "Enabled" without complaint, and each
    #      would then be rejected by the ECS API at apply time, after the
    #      cluster call had already been attempted. This block is therefore
    #      the only pre-apply guard on this input, which is why it cannot be
    #      left to the provider.
    #      Assumptions: the three accepted values are taken from the ECS
    #      ClusterSetting API contract, which documents the supported values
    #      as enhanced, enabled and disabled. The comparison is exact rather
    #      than case-folded because the provider forwards the casing it is
    #      given unchanged, so a capitalised variant would reach the API as
    #      written and is not one of the documented values.
    condition     = contains(["enabled", "disabled", "enhanced"], var.container_insights)
    error_message = "The container_insights value must be one of enabled, disabled or enhanced."
  }
}

variable "capacity_providers" {
  # WHAT: the capacity providers main.tf associates with the cluster before
  #       any service or task can reference them.
  # WHY : Assumptions: the compute decision recorded in
  #       docs/adr/ADR-002-compute-platform.md selects Fargate for the eight
  #       services and Step-Functions-invoked Fargate tasks for batch, so the
  #       Fargate pair is the default. The reasoning is specific to this
  #       workload: the services are long-running request and response APIs
  #       that hold warm JDBC connection pools, which needs a task process
  #       that outlives a single request; the batch steps run longer than
  #       Lambda's fifteen-minute execution ceiling, so a function cannot
  #       host them at all; and EC2 would add instance patching and capacity
  #       management for no benefit this workload can name.
  # WHY : Trade-offs: FARGATE_SPOT is in the default list even though Spot
  #       capacity is reclaimable with a two-minute warning, because
  #       attaching a provider does not by itself place any task on it. What
  #       decides how much work lands on Spot is
  #       default_capacity_provider_strategy below, and its default reserves
  #       a guaranteed on-demand baseline. Attaching the provider here keeps
  #       the Spot option available to a caller that wants it without
  #       requiring a change to the cluster's associations later, since
  #       changing an association is a separate API call from changing a
  #       service's placement.
  # WHY : Alternatives Considered: declared as a list even though the cluster
  #       argument it feeds is a set. A `set(string)` here would make a
  #       duplicate entry impossible by construction, but it would do so by
  #       discarding it silently, which is the very slip the second validation
  #       below is here to report. A list keeps the caller's input intact long
  #       enough to be checked, and Terraform converts it to the set the
  #       argument wants.
  description = "Capacity providers to associate with the cluster; only the two Fargate providers are supported."
  type        = list(string)
  default     = ["FARGATE", "FARGATE_SPOT"]

  validation {
    # WHY: Alternatives Considered: accepting an arbitrary provider name was
    #      rejected. An EC2-backed capacity provider is an auto-scaling group
    #      plus a launch template that this module does not create and that
    #      the compute decision rules out, so a caller naming one would get a
    #      cluster association pointing at a provider that does not exist and
    #      a failure only when a task was placed. Restricting the domain here
    #      turns that into a message at variable-evaluation time. The pinned
    #      provider applies no validator to this argument, so nothing else in
    #      the plan would catch it.
    condition = alltrue([
      for entry in var.capacity_providers :
      contains(["FARGATE", "FARGATE_SPOT"], entry)
    ])
    error_message = "Each entry in capacity_providers must be either FARGATE or FARGATE_SPOT."
  }

  validation {
    # WHY: Assumptions: the underlying cluster argument is a set, so a
    #      duplicated entry is collapsed silently rather than reported. A
    #      duplicate is almost always a copy-paste slip in a tfvars file, and
    #      silently absorbing it hides the slip; comparing the list length
    #      against the distinct length surfaces it instead.
    condition     = length(var.capacity_providers) == length(distinct(var.capacity_providers))
    error_message = "The capacity_providers list must not repeat a provider name."
  }
}

variable "default_capacity_provider_strategy" {
  # WHAT: how the cluster places a task that names neither a launch type nor
  #       a strategy of its own.
  # WHY : Trade-offs: the default reserves a base of one task on on-demand
  #       Fargate and then apportions everything above that base four to one
  #       in favour of on-demand. The mechanism matters and is worth stating
  #       exactly: ECS satisfies the base first, so one task always runs on
  #       non-interruptible capacity, and it then splits the remainder by
  #       weight, so four of every five further tasks are on-demand and one
  #       is Spot. A full reclamation of Spot capacity therefore removes at
  #       most a fifth of the tasks above the baseline and never the baseline
  #       itself, which bounds the blast radius of an interruption to
  #       something a rolling replacement can absorb.
  # WHY : Alternatives Considered: an all-on-demand strategy was rejected
  #       because it pays the full on-demand rate for capacity that is
  #       replaceable without loss, and it makes the FARGATE_SPOT
  #       association above pointless. An all-Spot strategy was rejected
  #       because a reclamation event can take every task at once, leaving
  #       the service with no running capacity at all until replacements are
  #       placed, which turns a cost optimisation into an outage.
  #       A caller overrides this per environment: setting the Spot weight to
  #       zero makes the provider ineligible for placement while leaving it
  #       associated, which is how prod opts out without diverging from dev's
  #       cluster topology.
  # WHY : Alternatives Considered: the numeric bounds on the two optional
  #       members are deliberately NOT restated as validation blocks below.
  #       The pinned provider already bounds weight to 0-1000 and base to
  #       0-100000 and already refuses a fractional value, and it reports all
  #       three at plan time, so a second copy here would add no coverage and
  #       would risk drifting from the provider's own bounds at the next
  #       upgrade. The three blocks that do exist assert the rules the
  #       provider does NOT check.
  # WHY : Alternatives Considered: an ordered list of objects rather than a
  #       set, for the same reason as capacity_providers and one more. The two
  #       optional members are declared with `optional(number)` rather than
  #       given defaults of 0, so that an omitted value stays distinguishable
  #       from an explicit zero -- the at-most-one-base rule below can only be
  #       expressed if an absent base is null rather than 0.
  description = "Default placement strategy for tasks that specify no launch type or strategy of their own."
  type = list(object({
    capacity_provider = string
    weight            = optional(number)
    base              = optional(number)
  }))
  default = [
    {
      capacity_provider = "FARGATE"
      weight            = 4
      base              = 1
    },
    {
      capacity_provider = "FARGATE_SPOT"
      weight            = 1
    },
  ]

  validation {
    # WHY: Assumptions: the strategy may only name a provider that is also
    #      associated with the cluster, because ECS refuses a strategy entry
    #      for a provider it does not hold. Checking that here, against the
    #      other input rather than against a literal list, is what makes a
    #      caller who narrows capacity_providers to on-demand but forgets to
    #      narrow the strategy fail at plan time instead of at the first task
    #      placement. Cross-variable references in a validation condition
    #      require Terraform 1.9 or later, which the module's declared floor
    #      of 1.15.0 already guarantees.
    condition = alltrue([
      for entry in var.default_capacity_provider_strategy :
      contains(var.capacity_providers, entry.capacity_provider)
    ])
    error_message = "Every capacity_provider named in default_capacity_provider_strategy must also appear in capacity_providers."
  }

  validation {
    # WHY: Assumptions: an omitted weight is treated by ECS as zero, and a
    #      provider with a weight of zero cannot be used to place a task, so
    #      a strategy in which every weight is zero or absent fails every
    #      RunTask and CreateService call that relies on it. An empty list is
    #      allowed and is not that case: main.tf then emits no strategy block
    #      and services must name a launch type themselves.
    condition = length(var.default_capacity_provider_strategy) == 0 || anytrue([
      for entry in var.default_capacity_provider_strategy :
      coalesce(entry.weight, 0) > 0
    ])
    error_message = "At least one default_capacity_provider_strategy entry must set a weight greater than 0."
  }

  validation {
    # WHY: Assumptions: ECS permits a base on at most one entry in a strategy,
    #      because the base is a floor on total task count rather than a
    #      per-provider share. Two bases are rejected by the API, and the
    #      pinned provider forwards the strategy without checking the rule,
    #      so this block is what turns that into a plan-time message.
    condition = length([
      for entry in var.default_capacity_provider_strategy :
      entry.base
      if entry.base != null
    ]) <= 1
    error_message = "At most one default_capacity_provider_strategy entry may set base."
  }
}

# -----------------------------------------------------------------------------
# Optional ECS Exec observability
#
# The three inputs below configure the cluster's execute-command behaviour,
# which is how an operator opens a shell into a running task. All three are
# optional and all three default to the no-override path, so a root that has
# provisioned neither a log group nor a key still gets a valid cluster.
# -----------------------------------------------------------------------------

variable "execute_command_log_group_name" {
  # WHY : Alternatives Considered: creating the log group inside this module
  #       was rejected. Log groups are owned by `ecs-service`, which creates
  #       one per service, and by `observability`, which owns the group,
  #       dashboard and alarm set; a module that both created and consumed a
  #       group would put two modules in the position of declaring the same
  #       resource, and the second `terraform apply` would fail on an
  #       already-exists error rather than converge. Taking the name as an
  #       input keeps a single owner and matches the ECS contract, which
  #       requires the group to exist before the cluster references it.
  # WHY : Trade-offs: null rather than a composed default name. A composed
  #       default would name a group this module does not create, so the
  #       cluster would reference a group that may not exist and the failure
  #       would land at apply time; null instead omits the log-configuration
  #       argument entirely, which is a valid cluster.
  description = "Name of an existing CloudWatch log group for ECS Exec session output; null omits the log configuration."
  type        = string
  default     = null
}

variable "execute_command_logging" {
  # WHAT: which log destination the cluster uses for execute-command output.
  # WHY : Assumptions: the default is "DEFAULT" because that is the value the
  #       ECS API itself applies when the parameter is omitted -- it routes
  #       session output through whatever awslogs configuration the task
  #       definition already carries. Defaulting to the API's own behaviour
  #       means supplying nothing here changes nothing, which is the property
  #       that makes the input safe to add to an existing call. "NONE" was
  #       rejected as the default because it would silently discard an audit
  #       trail of interactive access to a production task.
  # WHY : Trade-offs: "OVERRIDE" is accepted but not defaulted. It is the only
  #       value that consults execute_command_log_group_name, and the ECS
  #       contract requires a log configuration whenever it is set, so
  #       defaulting to it would make the log-group input mandatory in
  #       practice and defeat the point of that input being optional.
  description = "ECS Exec log destination; one of NONE, DEFAULT or OVERRIDE."
  type        = string
  default     = "DEFAULT"

  validation {
    # WHY: Assumptions: these three values are the provider's own domain, not
    #      a guess -- the pinned provider rejects anything else with
    #      "expected logging to be one of [NONE DEFAULT OVERRIDE]". The block
    #      is kept even though the provider duplicates it, because the
    #      provider reports against a generated resource argument while this
    #      reports against the input the caller actually wrote, and the
    #      caller is several files away from the resource.
    condition     = contains(["NONE", "DEFAULT", "OVERRIDE"], var.execute_command_logging)
    error_message = "The execute_command_logging value must be one of NONE, DEFAULT or OVERRIDE."
  }

  validation {
    # WHY: Assumptions: ECS requires a log configuration whenever the logging
    #      mode is OVERRIDE, and the only log destination this module exposes
    #      is the CloudWatch group name. OVERRIDE with no group name therefore
    #      produces an empty log configuration that the API refuses after the
    #      cluster call has been attempted. Pairing the two inputs in one
    #      condition moves that refusal to plan time; neither input can catch
    #      it alone, which is why the check lives on the one that creates the
    #      requirement.
    condition = (
      var.execute_command_logging != "OVERRIDE" ||
      var.execute_command_log_group_name != null
    )
    error_message = "Setting execute_command_logging to OVERRIDE also requires execute_command_log_group_name."
  }
}

variable "kms_key_arn" {
  # WHAT: the customer-managed KMS key that encrypts the execute-command
  #       channel between the operator's client and the container. main.tf
  #       feeds it to the cluster's `kms_key_id` argument, which is spelled
  #       for a key identifier but documented to accept a full key reference
  #       as well; the input keeps the longer name because that is the form
  #       the environment root has, having taken it from the `kms` module's
  #       output.
  # WHY : Assumptions: the key is never created here. The four customer-managed
  #       keys in this package -- for the database, object storage, the secret
  #       store and the queues -- are owned by the sibling `kms` module and
  #       wired into this one by the environment root, so this input is a
  #       reference to a key that already exists. Creating a fifth key here
  #       would put key rotation and key policy in two places.
  # WHY : Trade-offs: deliberately not marked sensitive. A key reference is an
  #       identifier rather than a secret -- holding it grants nothing without
  #       a matching IAM grant and key policy -- and marking it sensitive
  #       would redact it from plan output, which is exactly where an operator
  #       confirms that the intended key was wired in.
  # WHY : Assumptions: the default is null and could not be anything else. Any
  #       literal key reference embeds an account identifier, and this package
  #       treats committing one as forbidden outright, so there is no legal
  #       non-null value to put here. Null also omits the argument rather than
  #       sending an empty string, which leaves the channel on the AWS-managed
  #       default instead of producing a configuration the API refuses.
  # WHY : Alternatives Considered: a shape check on the value was rejected.
  #       The underlying cluster argument accepts a bare key identifier, an
  #       alias or a fully qualified reference, and the qualified form varies
  #       by partition, so a pattern strict enough to catch a typo would also
  #       reject inputs that are valid. The key reference is resolved by KMS,
  #       which is the only authority on whether it names a usable key.
  description = "Customer-managed KMS key reference encrypting the ECS Exec channel; null uses the AWS-managed default."
  type        = string
  default     = null
}

# -----------------------------------------------------------------------------
# Tagging
# -----------------------------------------------------------------------------

variable "tags" {
  # WHAT: the module-specific keys main.tf merges onto the two resources it
  #       creates, alongside the set they inherit from the calling root.
  # WHY : Assumptions: this map is ADDITIVE, not a replacement. The calling
  #       root configures the aws provider with `default_tags`, and every
  #       resource in this module inherits that set without restating it, so
  #       what belongs here is only the keys that are specific to this module
  #       or to one environment. Without this note a reader would reasonably
  #       ask why the module tags at all when the provider already does.
  # WHY : Trade-offs: an empty map is the default rather than a composed set
  #       built from name_prefix and environment. Composing tags here would
  #       duplicate values the root's `default_tags` almost certainly already
  #       carries, and a key set by both places is resolved in favour of the
  #       resource-level value, so the module would silently win an argument
  #       it has no reason to enter.
  # WHY : Alternatives Considered: no validation is asserted, and the candidate
  #       that was weighed and rejected is a check rejecting the reserved key
  #       prefix that AWS refuses. It was rejected because the same map is also
  #       the one place a caller can attach a key this module has never heard
  #       of, so any charset rule risks refusing a legitimate key; AWS rejects
  #       a reserved prefix itself, and it does so per key with the offending
  #       key named, which is a clearer report than a whole-map condition.
  description = "Additional tags merged onto the cluster resources, on top of the provider's default_tags."
  type        = map(string)
  default     = {}
}
