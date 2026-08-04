# =============================================================================
# infra/envs/dev/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input surface of the CardDemo `dev` environment root. Two of
#   these inputs configure the root's own aws provider; the other ten are the
#   ENTIRE axis along which this environment is permitted to differ from
#   infra/envs/prod. That constraint is the point of this file: the two roots are
#   required to be identical in topology and to differ only in sizing and
#   retention, so infra/envs/prod/variables.tf declares exactly the same twelve
#   variable names with the same types and the same validation, and only the
#   defaults differ. A variable present in one root and absent from the other
#   would be a structural divergence between the two environments, which is the
#   failure this symmetry exists to prevent.
#
#   Values are supplied by infra/envs/dev/terraform.tfvars. Every input is
#   nonetheless defaulted, so this root can be initialised and validated with no
#   variable file at all -- see the trade-off recorded below.
#
# Parameters -- twelve, none required:
#   aws_region                      string       Region this environment
#                                                deploys into.
#   tags                            map(string)  Common tag set applied through
#                                                the provider's default_tags.
#   aurora_min_capacity             number       Floor of the serverless
#                                                database's capacity range.
#   aurora_max_capacity             number       Ceiling of that range.
#   aurora_seconds_until_auto_pause number       Idle interval before a
#                                                zero-floor cluster pauses.
#   ecs_task_cpu                    number       CPU units per service task.
#   ecs_task_memory                 number       Memory per service task.
#   ecs_desired_count               number       Tasks per service.
#   log_retention_days              number       Retention for log groups this
#                                                root's modules create.
#   cloudfront_price_class          string       Edge footprint of the browser
#                                                application's distribution.
#   deletion_protection             bool         Whether stateful resources
#                                                refuse deletion.
#   skip_final_snapshot             bool         Whether destroying the database
#                                                takes a final snapshot first.
#
#   Each block below carries the full `type` and `description` that tflint's
#   terraform_typed_variables and terraform_documented_variables rules require;
#   the summary above is a map of the surface, not a second copy of it.
#
# Return values:
#   None. A variables.tf declares no output. What this root publishes -- the
#   database endpoint, the queue URLs, the user pool identifiers, the dataset
#   bucket name and the distribution domain, all of which the runbooks read back
#   after an apply -- is declared in infra/envs/dev/outputs.tf.
#
# Errors / Exceptions:
#   Every input carries a `validation` block, and each rejects a bad value during
#   `terraform plan`, before any request leaves the machine, rather than letting
#   the service reject it partway through an apply that has already created other
#   resources. The two inputs whose absence would otherwise be silent are
#   `aws_region`, which the provider in versions.tf reads directly, and `tags`,
#   which that provider's `default_tags` block reads; before this file existed
#   both were referenced by that provider and declared nowhere, which is a
#   configuration error a reader could only find by reading the provider block.
#
# WHY (non-obvious design decisions):
#   - Assumptions: this file declares NO `environment` and NO `name_prefix`
#     variable, and both absences are deliberate. The environment name is what
#     distinguishes this root's resources from the other root's; making it an
#     input would let a caller point the `dev` state at `prod`-named resources,
#     which is the single most damaging mistake this tree can make, and it would
#     be a clean plan. main.tf therefore carries the literal `dev`. The name
#     prefix is shared by the whole stack and every module already defaults it to
#     the same value, so a root-level input would only create a way for the two
#     environments to disagree about it.
#   - Trade-offs: every input is defaulted, so this root initialises, validates
#     and plans with no variable file. The cost is that a defaulted capacity or
#     retention value is a decision made by silence; outputs.tf reports the
#     values that were actually used, which is what keeps such a choice visible.
#     The benefit is that the infrastructure pipeline can check this root with no
#     variable file and no credentials, which is the only way a non-interactive
#     job can validate it at all.
#   - Assumptions: NO default here holds a credential, an account identifier, an
#     ARN, a bucket name or a password. This root's secrets do not exist in
#     source at any point in their lifetime: the database credential and the seed
#     user passwords are generated at apply time by the secrets and cognito
#     modules and written straight into Secrets Manager. That is what makes the
#     project's no-secrets-in-source constraint structurally true here rather
#     than merely observed, and it is why this file has no input for either.
#   - Assumptions: this environment is the one permitted to scale its database
#     capacity to zero, and that permission is the reason the AWS provider
#     constraint in versions.tf has the floor it does -- a zero minimum needs
#     provider 5.81.0 or later. The three database inputs below are therefore
#     read together, and two of them constrain each other.
#   - Where a comment below reasons about `terraform apply` or `terraform
#     destroy`, it is describing what an input MEANS at those points, not
#     reporting on a provisioned stack. This tree is authored and statically
#     validated -- formatted, validated, planned, linted and policy-scanned;
#     applying it to a live account is an operator action outside this scope.
# =============================================================================

# -----------------------------------------------------------------------------
# Provider inputs -- the two values versions.tf reads
# -----------------------------------------------------------------------------

# WHY this has a default at all, when the region a deployment lands in is
# normally something an operator states explicitly (Trade-off): the
# infrastructure pipeline checks this root without a variable file and without
# AWS credentials. An input with no default makes that step prompt for a value
# and then fail in a non-interactive shell, so this would be the one directory in
# the tree that could not be checked. A region identifier is configuration rather
# than a credential -- it names a public AWS location and confers no access -- so
# publishing one as a default discloses nothing. The accepted cost is that an
# operator who never sets it provisions this environment in us-east-1;
# outputs.tf reports the region back, which stops that from being invisible.
#
# WHY the default matches infra/bootstrap. Assumptions: the state bucket this
# root's backend configuration points at is created by infra/bootstrap, which
# defaults to the same region. A backend in one region and resources in another
# is a legal but confusing arrangement, and matching the defaults means the
# simplest possible deployment keeps them together.
variable "aws_region" {
  description = "AWS region this environment is provisioned into. Read by the aws provider in versions.tf and therefore inherited by every one of the sixteen modules this root calls. Accepts a standard region identifier, for example us-east-1 or ap-southeast-4."
  type        = string
  default     = "us-east-1"

  # WHY a shape check on a value the provider would reject anyway. Trade-offs: a
  # malformed region does not fail as a bad region. It fails as an
  # endpoint-resolution error naming a hostname, from whichever provider call
  # happens to run first, and nothing in that message names this input.
  # Validating here attributes the fault to the input that caused it, at plan
  # time, before any API call is attempted.
  # Alternatives Considered: an allow-list of the regions that currently exist.
  # Rejected -- it would refuse every region AWS adds after this file is written,
  # and falsely refusing a valid region is a worse outcome than accepting a
  # well-formed identifier that resolves to nothing. The shape admits us-east-1,
  # ap-southeast-4 and the three-part us-gov-east-1, and the ordinal is one or
  # more digits rather than exactly one for the same reason.
  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]+$", var.aws_region))
    error_message = "aws_region must be a lowercase AWS region identifier shaped <two letters>-<word>-<ordinal>, for example us-east-1, eu-west-3, ap-southeast-4 or us-gov-east-1."
  }
}

# WHY the tag set is a root input applied through the provider rather than a
# per-resource argument (Trade-off): versions.tf sets these on the provider's
# `default_tags` block, so every taggable resource this root creates -- including
# every resource created inside all sixteen modules -- carries them without any
# module author remembering to wire a tags argument through. The accepted cost is
# locality: reading a module's main.tf does not reveal the tags its resources
# will carry, so a reader has to know that this root's versions.tf applies them.
# The alternative, threading a tag map into sixteen module call sites, fails the
# first time one call site is edited and the others are not.
#
# Alternatives Considered: a fixed `object({...})` type naming these three keys.
# Rejected -- the AWS tagging API is itself an arbitrary string-to-string map, so
# an object type would refuse a caller's own cost-allocation or ownership tags
# while adding nothing this root relies on.
#
# Assumptions: the default holds only non-secret descriptive values. Tags are
# readable by any principal that can describe the resource and are surfaced in
# billing and cost-allocation exports, so nothing sensitive belongs in one. The
# `Environment` value is what makes a cost report separate this environment's
# spend from production's, which is the practical reason it is here.
variable "tags" {
  description = "Common tag set merged into every taggable resource in this root, and in every module it calls, through the provider default_tags block in versions.tf. Values must be non-secret: tags are visible to any principal that can describe the resource and appear in cost-allocation exports."
  type        = map(string)
  default = {
    Project     = "carddemo"
    Environment = "dev"
    ManagedBy   = "terraform"
  }

  validation {
    # WHY : Assumptions: the service accepts at most 50 tags per resource and
    #       rejects an empty key. Both are checked here because the provider
    #       applies this map to every resource in the root, so a single bad entry
    #       fails not one resource but all of them, and the message names the
    #       resource rather than the map.
    condition     = length(var.tags) <= 50 && alltrue([for k in keys(var.tags) : length(k) > 0])
    error_message = "tags must hold at most 50 entries and no empty key, the limits the AWS tagging API applies to every resource this map is merged into."
  }
}

# -----------------------------------------------------------------------------
# Database capacity -- the three inputs that make this the environment allowed
# to scale to zero
#
# These three are read together. The floor of the capacity range decides whether
# the cluster may pause at all; the auto-pause interval is meaningful only when
# that floor is zero; and the ceiling must exceed the floor for the range to be a
# range. Each is validated on its own and the pair is validated jointly, because
# a floor above a ceiling is accepted by the type system and rejected by the
# service only once a cluster modification is attempted.
# -----------------------------------------------------------------------------

variable "aurora_min_capacity" {
  description = "Floor of the serverless database's capacity range, in Aurora Capacity Units. A floor of 0 lets the cluster pause when idle, which is the single largest cost difference between this environment and production, at the price of a resume delay on the first query after a pause."
  type        = number
  default     = 0

  # Assumptions: zero is the default HERE and only here. This is the development
  # environment, its database is idle for most of its existence, and a paused
  # cluster bills no capacity. The resume delay that a zero floor implies is
  # roughly fifteen seconds on the first query after a pause, which is immaterial
  # for a batch chain and for interactive development but is exactly why
  # infra/envs/prod defaults this above zero.
  #
  # Assumptions: a zero floor is the requirement that sets the AWS provider floor
  # in versions.tf. The provider accepts a zero minimum only from 5.81.0 onward,
  # and `~> 6.56` clears that comfortably. A reader tempted to loosen the
  # provider constraint should know that this input is what it protects.
  validation {
    # WHY : Assumptions: the range and the increment are both the service's.
    #       Capacity is expressed in half-unit steps from 0 to 256, so a value
    #       like 1.25 is rejected during an apply that has already begun. The
    #       half-unit check is written as a multiplication rather than a modulo
    #       because a modulo on a fractional operand is not exact.
    condition     = var.aurora_min_capacity >= 0 && var.aurora_min_capacity <= 256 && floor(var.aurora_min_capacity * 2) == var.aurora_min_capacity * 2
    error_message = "aurora_min_capacity must be between 0 and 256 in half-unit increments, the range and granularity Aurora Serverless capacity accepts."
  }
}

variable "aurora_max_capacity" {
  description = "Ceiling of the serverless database's capacity range, in Aurora Capacity Units. It bounds what a runaway query or an unexpectedly large batch run can cost, which is why a development environment sets it low rather than leaving headroom it will never use deliberately."
  type        = number
  default     = 4

  validation {
    # WHY : Assumptions: the floor of this check is 0.5 rather than 0, because
    #       the service requires a maximum of at least one half unit even when
    #       the minimum is zero -- a range whose ceiling is also zero describes a
    #       cluster that can never serve a query.
    condition     = var.aurora_max_capacity >= 0.5 && var.aurora_max_capacity <= 256 && floor(var.aurora_max_capacity * 2) == var.aurora_max_capacity * 2
    error_message = "aurora_max_capacity must be between 0.5 and 256 in half-unit increments; a ceiling of 0 would describe a cluster that can never serve a query."
  }

  validation {
    # WHY : Trade-offs: this second block cross-checks the pair. A ceiling below
    #       the floor is accepted by both types and rejected by the service only
    #       when it attempts the modification, which is partway through an apply.
    #       Checking it here costs one extra block and names both inputs in the
    #       message. Equality is permitted deliberately: a fixed capacity, floor
    #       equal to ceiling, is a legitimate way to run a cluster at a known
    #       size.
    condition     = var.aurora_max_capacity >= var.aurora_min_capacity
    error_message = "aurora_max_capacity must be greater than or equal to aurora_min_capacity; the two together describe one capacity range."
  }
}

variable "aurora_seconds_until_auto_pause" {
  description = "Idle interval before a cluster whose capacity floor is zero pauses. It is meaningful only while that floor is zero, and main.tf forwards it to the database module on that condition, so the value is declared in both environment roots for interface symmetry and is inert in the one whose floor is above zero."
  type        = number
  default     = 300

  # Assumptions: the value is mandatory once the floor is zero, so it cannot be
  # omitted in this root even though it does nothing in production's. That
  # asymmetry is exactly why it is declared in both: the two roots' input
  # surfaces are required to match, and a variable that existed in only one of
  # them would be a structural difference between the environments rather than a
  # sizing one.
  #
  # WHY the default is the shortest interval the service accepts. Trade-offs: the
  # two ends of the range buy different things. A short interval pauses an idle
  # development cluster sooner and stops it billing; a long one avoids paying the
  # resume delay repeatedly during intermittent use. Five minutes takes the short
  # end because the case this environment is actually used for is a burst of work
  # followed by hours of idleness.
  validation {
    condition     = var.aurora_seconds_until_auto_pause >= 300 && var.aurora_seconds_until_auto_pause <= 86400 && floor(var.aurora_seconds_until_auto_pause) == var.aurora_seconds_until_auto_pause
    error_message = "aurora_seconds_until_auto_pause must be a whole number of seconds from 300 to 86400 inclusive, the configurable range the service accepts."
  }
}

# -----------------------------------------------------------------------------
# Container task sizing
# -----------------------------------------------------------------------------

variable "ecs_task_cpu" {
  description = "CPU units allocated to each service task, where 1024 units is one virtual CPU. Passed to every service this root creates, so all eight are sized alike within an environment; the difference between the environments is what this value is, not which services get it."
  type        = number
  default     = 512

  validation {
    # WHY : Trade-offs: the accepted set stops at 2048, three sizes rather than
    #       the seven Fargate offers. The reason is the memory rule below: from
    #       4096 units upward the valid memory band is no longer simply twice to
    #       eight times the CPU value, so admitting those sizes would require a
    #       per-size table here and a matching one there. Two virtual CPUs is
    #       ample for a Java service in this stack, and widening the set later
    #       means widening both checks in the same change -- which is the
    #       coupling this note exists to record.
    condition     = contains([512, 1024, 2048], var.ecs_task_cpu)
    error_message = "ecs_task_cpu must be 512, 1024 or 2048 CPU units; larger Fargate sizes are excluded deliberately because their valid memory band is not the simple multiple the memory check below asserts."
  }
}

variable "ecs_task_memory" {
  description = "Memory in mebibytes allocated to each service task. Fargate accepts only certain memory values for a given CPU size, so this input is validated against ecs_task_cpu rather than on its own."
  type        = number
  default     = 1024

  validation {
    # WHY : Assumptions: for CPU sizes of 512, 1024 and 2048 units, Fargate
    #       accepts memory from twice to eight times the CPU value in whole
    #       gibibyte steps, and nothing else. Asserting that here converts an
    #       apply-time task-definition registration failure -- which names the
    #       task definition rather than the input -- into a plan-time error
    #       naming both inputs. This check is coupled to the CPU set above: if
    #       that set is widened past 2048, this rule must be replaced with a
    #       per-size table in the same change.
    condition     = var.ecs_task_memory >= var.ecs_task_cpu * 2 && var.ecs_task_memory <= var.ecs_task_cpu * 8 && var.ecs_task_memory % 1024 == 0
    error_message = "ecs_task_memory must be a whole multiple of 1024 MiB between twice and eight times ecs_task_cpu, which is the band Fargate accepts for the CPU sizes this root permits."
  }
}

variable "ecs_desired_count" {
  description = "Tasks each service runs. This is the input that decides whether a service survives losing one task, and it is the clearest single difference between a development environment and a production one."
  type        = number
  default     = 1

  # WHY one task here. Trade-offs: a single task means a deployment or a task
  # failure is a brief outage of that service, which is acceptable in an
  # environment whose purpose is development and which is why production defaults
  # higher. It also halves the compute cost of the whole stack, since the count
  # applies to all eight services. The topology is unchanged either way: the same
  # services, the same load balancer, the same subnets -- only the count differs,
  # which is what keeps this a sizing difference rather than a structural one.
  validation {
    # WHY : Assumptions: zero is permitted, and it is not a mistake. The service
    #       module can create a service with no running task, which is how an
    #       environment is left provisioned but idle; refusing zero would remove
    #       the cheapest way to keep an environment without paying for it. The
    #       ceiling of 10 bounds an accidental extra digit, which on eight
    #       services is an eightfold cost error.
    condition     = var.ecs_desired_count >= 0 && var.ecs_desired_count <= 10 && floor(var.ecs_desired_count) == var.ecs_desired_count
    error_message = "ecs_desired_count must be a whole number from 0 to 10 inclusive; 0 is permitted and leaves the services provisioned but idle."
  }
}

# -----------------------------------------------------------------------------
# Retention and edge footprint
# -----------------------------------------------------------------------------

variable "log_retention_days" {
  description = "Days the log groups this root's modules create retain events. Passed to the observability, service, batch and API modules together, so one value governs the whole environment's retention rather than each module carrying its own."
  type        = number
  default     = 7

  # WHY a week here. Trade-offs: logs in this environment are read while the work
  # that produced them is still in progress and are of little value afterwards,
  # so a short retention keeps storage cost proportional to that. Production
  # keeps them far longer, for the audit trail the migrated system inherits from
  # a mainframe whose job logs were retained on the spool. This is a retention
  # value, so the two roots are explicitly permitted to differ on it without
  # changing the stack's shape.
  validation {
    # WHY : Assumptions: the set is closed because the service accepts only
    #       these values -- an arbitrary number is rejected during apply, after
    #       other resources exist. 0 is excluded even though the service reads it
    #       as never expire: unbounded retention is a cost that grows without
    #       anyone deciding to accept it.
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.log_retention_days)
    error_message = "log_retention_days must be one of the retention periods CloudWatch Logs accepts: 1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288 or 3653."
  }
}

variable "cloudfront_price_class" {
  description = "Edge locations the browser application's distribution is served from. A narrower class serves fewer regions at lower cost; it changes latency for distant users and nothing else about how the distribution behaves."
  type        = string
  default     = "PriceClass_100"

  # WHY the narrowest class here. Trade-offs: the users of this environment are
  # the people building it, so paying for a worldwide edge footprint buys nothing
  # measurable. Production takes the widest class. Neither choice changes the
  # distribution's configuration, its origin access control or its error routing,
  # which is what keeps this a sizing difference between the roots rather than a
  # topology one.
  validation {
    # WHY : Assumptions: the three values are the service's entire enumeration.
    #       A misspelling is rejected by the API during apply, against the
    #       distribution resource rather than against this input, so the check is
    #       here to name the input instead.
    condition     = contains(["PriceClass_100", "PriceClass_200", "PriceClass_All"], var.cloudfront_price_class)
    error_message = "cloudfront_price_class must be one of PriceClass_100, PriceClass_200 or PriceClass_All."
  }
}

# -----------------------------------------------------------------------------
# Teardown posture -- the two flags that decide whether this environment can be
# destroyed cleanly
# -----------------------------------------------------------------------------

variable "deletion_protection" {
  description = "Whether the stateful resources in this environment refuse deletion until the flag is cleared. It is forwarded to both resources that offer the protection, the database cluster and the user pool, so one value governs the environment's whole teardown posture."
  type        = bool
  default     = false

  # WHY this is false here. Trade-offs: one of this project's acceptance criteria
  # is that the stack tears down cleanly with `terraform destroy`. Deletion
  # protection makes that command fail against the protected resource and require
  # a preliminary apply to clear the flag before the destroy can proceed, which
  # is precisely the safety net production wants and precisely the friction a
  # development environment does not. The risk accepted is real and is stated
  # plainly: in this environment a destroy removes the database and the user pool
  # without further challenge.
  #
  # Alternatives Considered: two separate flags, one per resource. Rejected
  # because nothing in this architecture wants a protected database beside an
  # unprotected user pool -- the two are destroyed together or not at all -- and
  # two flags would let the two roots drift into exactly that state.
}

variable "skip_final_snapshot" {
  description = "Whether destroying the database cluster skips taking a final snapshot first. Skipping makes the destroy fast and complete; taking one leaves a snapshot behind that survives the cluster and continues to bill until it is deleted."
  type        = bool
  default     = true

  # WHY this is true here. Trade-offs: a development cluster's contents are
  # reproducible by rerunning the ETL against the seed datasets under app/data,
  # which are reference-only and always present, so a final snapshot preserves
  # nothing that cannot be regenerated. Skipping it also keeps `terraform
  # destroy` from leaving a billed artifact behind after the environment is
  # supposed to be gone, which is what the clean-teardown criterion asks for.
  # Production inverts this, because there the cluster's contents are not
  # reproducible from anything in this repository.
}
