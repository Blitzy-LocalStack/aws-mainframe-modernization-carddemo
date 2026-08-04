# =============================================================================
# infra/envs/dev/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input surface of the CardDemo `dev` Terraform environment root.
#   Two of these inputs configure the root's own `provider "aws"` in versions.tf;
#   the rest are forwarded by main.tf into the sixteen modules under
#   infra/modules/. Values are supplied by infra/envs/dev/terraform.tfvars.
#
#   The set of names declared here is deliberately CLOSED, and that closure is
#   the point of the file. infra/envs/prod declares the same names with the same
#   types and the same validation, and only the defaults and the supplied values
#   differ, so that the two environments are identical in TOPOLOGY and differ
#   only in sizing and retention. A variable present in one root and absent from
#   the other would be a structural divergence between the environments, which is
#   precisely the failure this symmetry exists to prevent: `dev` stops being a
#   valid rehearsal for `prod` the moment their shapes disagree.
#
# Parameters:
#   Nineteen inputs, none required, in six groups -- the two values the provider
#   reads; naming and environment identity; the VPC address space; the serverless
#   database's version, capacity, backup and durability settings; container task
#   sizing; and log retention, edge footprint and the batch schedule.
#
#   Each `variable` block below carries its own authoritative `type` and
#   `description`. The contract for an input lives on the input rather than in a
#   second copy up here, which would drift out of step with it the first time one
#   of them changed and would then be worse than no summary at all.
#
# Errors / Exceptions:
#   Most inputs carry a `validation` block, and each rejects an out-of-domain
#   value during `terraform plan` -- before any request leaves the machine --
#   rather than letting the service reject it partway through an apply that has
#   already created other resources. Every `error_message` names the domain it
#   accepts and, where a correction is not obvious from that, says what to do
#   instead, so an operator can fix the value from the message alone.
#
#   Deliberately NOT re-validated here: the database capacity invariant, and the
#   subnet arithmetic derived from the VPC address space. Both are owned
#   elsewhere and are identified at the inputs concerned.
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: accepting the database and seed-user credentials
#     as inputs, the way a great many Terraform roots do -- a `db_master_password`
#     variable behind a `sensitive = true` marker. Rejected outright, and that
#     rejection is why no input of that shape appears anywhere below. Any value
#     handed to such an input has to come from somewhere, and every somewhere is
#     worse than the alternative: committed into the tracked terraform.tfvars
#     beside it, exported in an operator's shell history, or pasted into a CI
#     secret that then has to be rotated by hand. Instead the `random` provider
#     generates the database credential and the seed-user passwords at apply
#     time and the secrets and cognito modules write them straight into Secrets
#     Manager, so the value never exists in a file that git can see. That makes
#     this project's no-secrets-in-source constraint structurally true here
#     rather than merely observed -- there is no input to leak through, which is
#     a stronger guarantee than a convention against filling one in.
#   - Trade-offs: the set of things this root may vary is closed, and closing it
#     costs real flexibility. There is no input here to collapse the three NAT
#     gateways to one, to drop an availability zone, to skip the content
#     distribution or to switch an interface endpoint off, and every one of those
#     would lower the running cost of a development environment. Each is refused
#     for the same reason: it would change the SHAPE of the stack rather than its
#     size, and a `dev` whose shape differs from `prod` cannot rehearse a `prod`
#     deployment -- which is the only thing a development environment is for.
#     Cost is reduced instead along the axes where reducing it changes nothing
#     structural: database capacity, task count and task size, retention, and
#     edge footprint.
#   - Assumptions: every input is DEFAULTED, which is load-bearing rather than
#     merely convenient. The infrastructure pipeline checks this directory
#     non-interactively and with no AWS credentials present, and an input with no
#     default makes Terraform prompt for a value and then fail in a shell with no
#     terminal attached -- this would become the one directory in the tree that
#     could not be checked. The cost accepted is that a defaulted capacity or
#     retention value is a decision made by silence, which is why outputs.tf
#     reports back the values actually used.
#   - Assumptions: no default here holds a credential, an account identifier, an
#     ARN, a bucket name or a table name. Identifiers reach this root only as
#     module outputs wired together in main.tf -- the customer-managed keys, the
#     generated database secret and the state backend are each referenced by
#     output or by backend configuration, never transcribed into a default.
#   - Where a comment below reasons about `terraform apply` or `terraform
#     destroy`, it describes what an input MEANS at that point; it is not a
#     report on a provisioned stack. This tree is authored and statically
#     validated -- formatted, validated, planned, linted and policy-scanned --
#     and applying it to a live account is an operator action outside this scope.
# =============================================================================

# -----------------------------------------------------------------------------
# Provider inputs -- the two values versions.tf reads
# -----------------------------------------------------------------------------

# WHY this carries a default when the region a deployment lands in is normally
# something an operator states explicitly. Trade-offs: the infrastructure
# pipeline checks this root with no variable file and no AWS credentials. An
# input with no default makes that step prompt and then fail in a
# non-interactive shell, so this would be the one directory in the tree that
# could not be checked. A region identifier is configuration and not a
# credential -- it names a public AWS location and confers no access -- so
# publishing one as a default discloses nothing. The cost accepted is that an
# operator who never sets it provisions this environment in us-east-1, which
# outputs.tf reports back so the choice does not stay invisible.
#
# WHY the default matches infra/bootstrap. Assumptions: the state bucket this
# root's backend.tf points at is created by infra/bootstrap, which defaults to
# the same region. A backend in one region with resources in another is legal but
# confusing, and matching the defaults keeps the simplest possible deployment
# together in one place.
variable "aws_region" {
  description = "AWS region this environment is provisioned into. Read by `provider \"aws\"` in versions.tf, so all sixteen modules main.tf calls inherit it instead of configuring a region of their own. Accepts a standard region identifier such as us-east-1 or ap-southeast-4."
  type        = string
  default     = "us-east-1"

  # WHY : Trade-offs: this is a shape check on a value the provider would reject
  #       anyway. A malformed region does not fail as a bad region -- it fails as
  #       an endpoint-resolution error naming a hostname, raised by whichever
  #       provider call happens to run first, and nothing in that message names
  #       this input. Checking here attributes the fault to the input that caused
  #       it, at plan time, before any API call is attempted.
  #       Alternatives Considered: an allow-list of the regions that exist today.
  #       Rejected -- it would refuse every region AWS adds after this file is
  #       written, and falsely refusing a valid region is a worse outcome than
  #       accepting a well-formed identifier that resolves to nothing. The shape
  #       admits us-east-1, ap-southeast-4 and the three-part us-gov-east-1, and
  #       the ordinal is one or more digits for that same reason.
  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]+$", var.aws_region))
    error_message = "aws_region must be a lowercase AWS region identifier shaped <two letters>-<word>-<ordinal>, for example us-east-1, eu-west-3, ap-southeast-4 or us-gov-east-1."
  }
}

# WHY the tag set is a root input applied through the provider rather than a
# per-resource argument. Trade-offs: versions.tf sets these on the provider's
# `default_tags` block, so every taggable resource this root creates -- including
# every resource created inside all sixteen modules -- carries them without any
# module author remembering to wire a tags argument through. The cost accepted is
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
  description = "Common tag set merged into every taggable resource in this root, and in every module it calls, through the `default_tags` block in versions.tf. Values must be non-secret: tags are visible to any principal that can describe the resource and appear in cost-allocation exports."
  type        = map(string)
  default = {
    Project     = "carddemo"
    Environment = "dev"
    ManagedBy   = "terraform"
  }

  validation {
    # WHY : Assumptions: the service accepts at most 50 tags per resource and
    #       rejects an empty key. Both are checked here because the provider
    #       applies this map to every resource in the root, so one bad entry
    #       fails not a single resource but all of them, and the failure names
    #       the resource rather than the map that caused it.
    condition     = length(var.tags) <= 50 && alltrue([for k in keys(var.tags) : length(k) > 0])
    error_message = "tags must hold at most 50 entries and no empty key, the limits the AWS tagging API applies to every resource this map is merged into."
  }
}

# -----------------------------------------------------------------------------
# Naming and environment identity
# -----------------------------------------------------------------------------

# WHY the characters are checked rather than trusted. Trade-offs: this prefix is
# concatenated into resource names across every module this root calls, and
# several of those names land in namespaces with their own character rules -- an
# object storage bucket name, a log group name, a composed secret name.
# Restricting it to lowercase letters, digits and hyphens keeps all of them legal
# and greppable, and catches a space or an uppercase letter at plan time instead
# of against whichever resource happens to be created first. The cost paid is
# that a legitimately unusual prefix has to be spelled out here first.
#
# Assumptions: the name, the type, the default and this exact pattern match the
# corresponding input in every module under infra/modules/ and in
# infra/bootstrap. That agreement is what lets one root value name the whole
# stack consistently, and a root that widened the pattern would accept values a
# module then rejected.
variable "name_prefix" {
  description = "Prefix concatenated into the name of every resource this root creates, ahead of the component and the environment, giving the whole stack one greppable identity. Lowercase letters, digits and hyphens only, beginning and ending with a letter or digit, at most 32 characters."
  type        = string
  default     = "carddemo"

  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", var.name_prefix)) && length(var.name_prefix) <= 32
    error_message = "name_prefix must be 1 to 32 characters of lowercase letters, digits and hyphens, beginning and ending with a letter or digit -- for example \"carddemo\"."
  }
}

# WHY this input exists but accepts only one value. Trade-offs: `environment` has
# to exist, because all sixteen modules take it and infra/modules/network
# declares it required with no default, so main.tf must pass something. But an
# input that accepts either name is the precise mechanism by which this root's
# state comes to manage resources named and tagged for the other environment: the
# operator overrides one value, the plan is clean, and the mistake reads as
# deliberate. Constraining the domain to the single name this directory owns the
# state for keeps the input passable while making that particular accident
# unrepresentable.
#
# Alternatives Considered: declaring no variable at all and hard-coding the
# literal at each module call in main.tf, which is airtight for the same reason.
# Rejected because it breaks the symmetry the two roots depend on -- prod would
# then differ from dev by a missing input rather than by a value, and comparing
# the two input surfaces would no longer be comparing like with like.
#
# Assumptions: infra/modules/network validates its own `environment` against
# exactly `dev` and `prod`. The domain below is a strict subset of that, so every
# value this root accepts the module accepts too; it narrows, and cannot
# contradict.
variable "environment" {
  description = "Environment identity forwarded to all sixteen modules, where it is interpolated into resource names, name tags, log group names and composed secret names so that this environment's resources are distinguishable from the other's. Pinned to `dev`, the environment whose state this Terraform root owns; infra/envs/prod declares the same input pinned to `prod`."
  type        = string
  default     = "dev"

  validation {
    condition     = var.environment == "dev"
    error_message = "environment must be \"dev\" in this root, which owns the dev environment's state; to provision production use the infra/envs/prod root rather than overriding this value."
  }
}

# -----------------------------------------------------------------------------
# Network address space
#
# One input, and deliberately only one. The nine subnet ranges, the availability
# zone count and the interface endpoint set are NOT inputs here: none of them is
# a sizing value, and infra/modules/network declines to expose them for the same
# reason the header gives for the closed input set.
# -----------------------------------------------------------------------------

# WHY the address space is an input when the subnets derived from it are not.
# Alternatives Considered: passing the nine subnet ranges in as three explicit
# lists, one per tier. Rejected -- that is three more inputs which must stay
# mutually non-overlapping, correctly ordered against the zone list and identical
# in shape between the two roots, with nothing checking any of it. The network
# module derives all nine deterministically from this one block instead, so the
# ranges cannot drift apart. The block itself stays an input because two
# environments that may need to be peered cannot both be numbered out of the same
# range, and renumbering a VPC after the fact means replacing it.
#
# Assumptions: infra/modules/network requires a prefix length between /16 and /20
# so that nine usably sized subnets fit inside the block, and it owns the
# arithmetic that proves they do. This root supplies the block and does not
# restate that rule -- one owner for one rule.
variable "vpc_cidr" {
  description = "IPv4 address space this environment's VPC is created with, and the block the network module carves all nine subnets from -- three public, three private-application and three isolated-data. The network module additionally requires a prefix length between /16 and /20 inclusive, the range in which nine usably sized subnets fit."
  type        = string
  default     = "10.0.0.0/16"

  validation {
    # WHY : Trade-offs: this checks only that the value IS a CIDR block, and
    #       leaves the prefix-length bound to the module that owns the subnet
    #       arithmetic. Restating the /16-to-/20 rule here would put the same
    #       rule in two files with nothing keeping them in step, and the copy is
    #       what goes stale. Catching a value that is not a CIDR block at all is
    #       worth doing here because that mistake otherwise surfaces as a
    #       function error inside the module, several frames from this input.
    condition     = can(cidrhost(var.vpc_cidr, 0))
    error_message = "vpc_cidr must be a valid IPv4 CIDR block such as 10.0.0.0/16; the network module additionally requires a prefix length between /16 and /20 so that nine subnets can be derived from it."
  }
}

# -----------------------------------------------------------------------------
# Serverless database
#
# NO CREDENTIAL INPUT APPEARS IN THIS SECTION, AND ITS ABSENCE IS THE DESIGN.
# A reader looking for the database master password belongs exactly here, so the
# reasoning is stated at the point of expectation rather than left in the header
# alone. Alternatives Considered: a `db_master_password` input marked sensitive,
# which is how this is conventionally done. Rejected -- any value handed to it has
# to live somewhere git or a shell history can see it, and no amount of marking
# changes that. infra/modules/secrets generates the credential with the `random`
# provider at apply time and writes it straight to Secrets Manager;
# infra/modules/aurora-postgresql then consumes it as a secret reference wired
# from that module's output in main.tf, and declares no password input of its own
# either. The credential therefore has no representation in source at any point
# in its lifetime.
#
# The three capacity inputs are read together: the floor decides whether the
# cluster may pause at all, the auto-pause interval is meaningful only while that
# floor is zero, and the ceiling has to reach at least one unit once it is.
# -----------------------------------------------------------------------------

# WHY : Assumptions: the capacity invariant is NOT restated in this file.
#       infra/modules/aurora-postgresql owns it, across six validation blocks
#       plus a lifecycle precondition -- the zero-to-256 range, the half-unit
#       granularity, the requirement that the ceiling not fall below the floor,
#       and the two further rules a zero floor arms (auto-pause becomes
#       mandatory, and the ceiling must reach at least one unit). Restating any
#       of it here would put one rule in two files with no mechanism keeping
#       them in step, and the copy is the one that goes stale. Every check in
#       this section is therefore implied by the module's, so any value the
#       module accepts this root accepts as well: they catch a plainly wrong
#       value early without ever being able to refuse a valid one, and the
#       authoritative ruling stays with the owner.

variable "aurora_engine_version" {
  description = "Aurora PostgreSQL engine version for this environment's cluster, forwarded to the database module, which requires the value and supplies no default of its own. Must be a numeric version such as \"16.6\", not an engine name or a parameter-group family. While aurora_min_capacity is 0, the release named here must be one that supports scaling to zero capacity."
  type        = string
  default     = "16.6"

  # WHY this root defaults a value the module deliberately refuses to default.
  # Assumptions: the two are not the same decision. A default in the MODULE would
  # let any caller omit the version, so dev and prod would both silently pin to
  # whatever release was current when that file was written and would drift apart
  # the moment one root overrode it and the other did not. A default in the ROOT
  # is per-environment by construction and is a reviewed, committed value in the
  # single file that owns this environment -- which is what the module's own
  # description asks for when it says the version is supplied by the environment
  # root so that an engine upgrade is an explicit change to one file.
  #
  # WHY this particular release rather than a recent-sounding one. Assumptions:
  # scaling to zero capacity is available only from Aurora PostgreSQL 13.15,
  # 14.12, 15.7 and 16.3 onward, and this environment sets its capacity floor to
  # zero below. An older release in the 16.x line would have the capacity
  # argument rejected during apply, with the error attributed to the capacity
  # input rather than to this one; 16.6 clears that floor.
  # Trade-offs: pinning a minor release means an engine upgrade is an explicit
  # edit that shows up in review, at the cost of not picking newer minors up
  # automatically. For a database engine that is the trade worth making.
  validation {
    condition     = can(regex("^[0-9]+(\\.[0-9]+)*$", var.aurora_engine_version))
    error_message = "aurora_engine_version must be a numeric PostgreSQL version such as \"16.6\" or \"17\", not an engine name and not a parameter-group family such as \"aurora-postgresql16\"."
  }
}

variable "aurora_min_capacity" {
  description = "Floor of the cluster's capacity range, in Aurora Capacity Units, forwarded to the database module which requires it. A floor of 0 lets the cluster pause when idle and is the single largest cost difference between this environment and production; the price is a resume delay on the first connection after a pause. The module owns the full range, granularity and zero-floor rules."
  type        = number
  default     = 0

  # WHY zero here and only here. Trade-offs: this environment's database is idle
  # for most of its existence and a paused cluster bills no compute capacity, so
  # a zero floor removes almost all of its standing cost. What is bought with
  # that is a resume delay of roughly fifteen seconds on the first connection
  # after a pause, which is immaterial for a batch chain and for interactive
  # development and is exactly why infra/envs/prod defaults this above zero
  # instead.
  #
  # Assumptions: a zero floor is what sets the AWS provider floor in versions.tf.
  # The provider accepts a zero minimum only from 5.81.0 onward and the `~> 6.56`
  # constraint clears that comfortably; a reader tempted to relax that constraint
  # downward should know this input is what it protects. It is also what makes
  # aurora_seconds_until_auto_pause mandatory and forces the ceiling to at least
  # one unit -- both enforced by the module.
  validation {
    # WHY : Trade-offs: a bare non-negativity check, deliberately weaker than the
    #       module's rule and chosen so it cannot disagree with it. A negative
    #       capacity is the one value worth catching in the root, because it
    #       reads as an obvious typo rather than as a capacity decision.
    condition     = var.aurora_min_capacity >= 0
    error_message = "aurora_min_capacity must not be negative; the database module additionally requires a value from 0 to 256 in half-unit increments."
  }
}

variable "aurora_max_capacity" {
  description = "Ceiling of the cluster's capacity range, in Aurora Capacity Units, forwarded to the database module which requires it. It bounds what a runaway query or an unexpectedly large batch run can cost, which is why a development environment sets it low rather than leaving headroom it will never use. The module owns the range, the granularity and the rule relating this to the floor."
  type        = number
  default     = 4

  # WHY a low ceiling rather than production's. Trade-offs: the ceiling is a cost
  # bound, not a performance target -- the cluster only scales up to it under
  # load. Setting it low in this environment means a mistake in a query or a
  # fixture large enough to drive real load is expensive in seconds rather than
  # in capacity units. The accepted cost is that a genuinely heavy one-off job
  # here runs slower than it would in production, which is the correct priority
  # for an environment nobody depends on.
  validation {
    # WHY : Trade-offs: as with the floor, this is deliberately weaker than the
    #       module's rule so the two cannot disagree. The module additionally
    #       enforces that this reaches at least one unit when the floor is zero,
    #       which is the case that actually applies in this environment.
    condition     = var.aurora_max_capacity > 0
    error_message = "aurora_max_capacity must be greater than 0; the database module additionally requires a value up to 256 in half-unit increments, at least equal to aurora_min_capacity, and at least 1 whenever aurora_min_capacity is 0."
  }
}

variable "aurora_seconds_until_auto_pause" {
  description = "Idle interval, in seconds, before a cluster whose capacity floor is zero pauses. It has an effect only while that floor is zero, and the database module requires it to be set in exactly that case, so it is declared in both environment roots for interface symmetry and is inert in the root whose floor is above zero. The module owns the accepted range."
  type        = number
  default     = 300

  # WHY this is declared in both roots when it does nothing in one of them.
  # Assumptions: the module makes it mandatory once the floor is zero and permits
  # it to be null otherwise, so production can leave it inert while this
  # environment must set it. That asymmetry is the reason it appears in both: the
  # two roots' input surfaces are required to match, and an input that existed in
  # only one of them would be a structural difference between the environments
  # rather than a sizing one.
  #
  # WHY the shortest interval the service accepts. Trade-offs: the two ends of
  # the range buy different things. A short interval pauses an idle cluster
  # sooner and stops it billing; a long one avoids paying the resume delay
  # repeatedly during intermittent use. This takes the short end because the
  # usage this environment actually sees is a burst of work followed by long
  # idleness, which is the case the short end serves.
  validation {
    # WHY : Trade-offs: a whole-number check only. The module owns the accepted
    #       range and additionally allows null, which this root never passes, so
    #       restating the bounds here would duplicate a rule without being able
    #       to express the null case the module also handles.
    condition     = floor(var.aurora_seconds_until_auto_pause) == var.aurora_seconds_until_auto_pause
    error_message = "aurora_seconds_until_auto_pause must be a whole number of seconds; the database module additionally requires it to fall between 300 and 86400 inclusive."
  }
}

variable "aurora_backup_retention_period" {
  description = "Days of automated backups the cluster retains, forwarded to the database module. Aurora does not permit automated backups to be switched off, so the module accepts 1 to 35 and there is no value here meaning `none`."
  type        = number
  default     = 1

  # WHY the shortest permitted retention rather than none at all. Assumptions:
  # the service has no off switch for automated backups -- one day is the floor,
  # not a choice to keep a token amount. Trade-offs: this environment's data is
  # reproducible by rerunning the ETL against the reference seed datasets under
  # app/data, so backups here protect nothing that cannot be regenerated, and
  # every retained day is storage that bills. Production keeps far more, because
  # there the cluster's contents are not reproducible from anything in this
  # repository. This is a retention value, so the two roots are explicitly
  # permitted to differ on it without changing the stack's shape.
  validation {
    condition     = var.aurora_backup_retention_period >= 1 && var.aurora_backup_retention_period <= 35
    error_message = "aurora_backup_retention_period must be between 1 and 35 days; Aurora does not allow automated backups to be disabled, so 0 is not an option."
  }
}

variable "aurora_preferred_backup_window" {
  description = "Daily UTC window in which automated backups are taken, forwarded to the database module. Must be of the form `hh:mm-hh:mm` with no day-of-week prefix, and must not overlap the window batch_schedule_expression starts the nightly chain in: a backup running against the cluster while the posting and interest jobs are writing to it competes with them for the same capacity, and this environment's capacity ceiling is deliberately low."
  type        = string
  default     = "07:00-08:00"

  # WHY this input is coupled to the batch schedule rather than free to choose.
  # Assumptions: the nightly chain and the backup both act on the same cluster,
  # and the batch chain is the one workload in this environment that drives the
  # database toward its capacity ceiling. Overlapping the two makes each slower
  # and makes the batch chain's duration depend on how much data the backup has
  # to copy, which is the kind of coupling that is invisible until it causes an
  # intermittent timeout. The default sits well clear of the default batch
  # schedule below; whoever moves either value must check the other.
  #
  # Alternatives Considered: leaving this unset and letting the service assign a
  # window, which the module permits by accepting null. Rejected -- an assigned
  # window is chosen without reference to the batch schedule, so the overlap this
  # input exists to prevent would then be a matter of luck.
  validation {
    condition     = can(regex("^([01][0-9]|2[0-3]):[0-5][0-9]-([01][0-9]|2[0-3]):[0-5][0-9]$", var.aurora_preferred_backup_window))
    error_message = "aurora_preferred_backup_window must be a UTC window of the form hh:mm-hh:mm, for example 07:00-08:00, with no day-of-week prefix."
  }
}


# -----------------------------------------------------------------------------
# Container task sizing
#
# One value each, applied to all eight services. The difference between the two
# environments is what these values are, never which services receive them --
# sizing the services differently from one another would be a shape difference
# dressed up as a sizing one.
# -----------------------------------------------------------------------------

variable "ecs_task_cpu" {
  description = "CPU units allocated to each service task, where 1024 units is one virtual CPU. Forwarded to every service this root creates, so all eight are sized alike within an environment. Fargate accepts only certain memory values for a given CPU size, so this value and ecs_task_memory are not independently free -- see ecs_task_memory."
  type        = number
  default     = 512

  # WHY : Assumptions: Fargate offers a fixed set of CPU sizes, and each one
  #       admits only a specific band of memory values. The accepted set here
  #       stops at 2048 rather than covering all seven sizes Fargate offers,
  #       because from 4096 units upward the valid memory band is no longer
  #       simply twice to eight times the CPU value, so admitting those sizes
  #       would require a per-size table here and a matching one on the memory
  #       input. Two virtual CPUs is ample for a service in this stack, and
  #       Trade-offs: widening this set later means widening the memory check in
  #       the same change -- which is the coupling this note exists to record.
  validation {
    condition     = contains([256, 512, 1024, 2048], var.ecs_task_cpu)
    error_message = "ecs_task_cpu must be 256, 512, 1024 or 2048 CPU units; the larger Fargate sizes are excluded deliberately because their valid memory band is not the simple multiple the ecs_task_memory check asserts."
  }
}

variable "ecs_task_memory" {
  description = "Memory in mebibytes allocated to each service task, forwarded to every service this root creates alongside ecs_task_cpu. Fargate accepts only certain memory values for a given CPU size, so this input is validated against ecs_task_cpu rather than on its own; the two must be changed together."
  type        = number
  default     = 1024

  # WHY : Assumptions: for the CPU sizes this root admits, Fargate accepts memory
  #       from twice to eight times the CPU value in whole gibibyte steps, and
  #       nothing else. Asserting that here turns an apply-time task-definition
  #       registration failure -- which names the task definition, not the input
  #       that caused it -- into a plan-time error naming both inputs. The one
  #       exception the band does not describe is the smallest CPU size, whose
  #       lowest memory value is 512 rather than a whole gibibyte, so the
  #       condition admits that pair explicitly.
  #       Trade-offs: this check is coupled to the CPU set above; if that set is
  #       ever widened past 2048 units, this rule has to be replaced with a
  #       per-size table in the same change rather than extended.
  validation {
    condition = (
      var.ecs_task_memory >= var.ecs_task_cpu * 2 &&
      var.ecs_task_memory <= var.ecs_task_cpu * 8 &&
      (var.ecs_task_memory % 1024 == 0 || var.ecs_task_memory == 512)
    )
    error_message = "ecs_task_memory must lie between twice and eight times ecs_task_cpu and be a whole multiple of 1024 MiB, except that 512 MiB is also valid alongside 256 CPU units. Change ecs_task_cpu and ecs_task_memory together."
  }
}

variable "ecs_desired_count" {
  description = "Tasks each service runs. This is the input that decides whether a service survives losing one task, and it is the clearest single sizing difference between this environment and production. Forwarded to all eight services, so the value multiplies by eight across the stack. The service module cross-checks it against its autoscaling floor, so main.tf must keep that floor no higher than this value -- see the note below."
  type        = number
  default     = 1

  # WHY one task here rather than production's count. Trade-offs: a single task
  # means a deployment or a task failure is a brief outage of that service, which
  # is acceptable in an environment whose purpose is development and is why
  # production defaults higher. It also divides the compute cost of the whole
  # stack, because the count applies to every service. The topology is unchanged
  # either way -- the same services, the same load balancer, the same subnets --
  # and only the count differs, which is what keeps this a sizing difference
  # rather than a structural one.
  #
  # WHY zero is refused, when leaving an environment provisioned but idle is an
  # obvious way to stop it billing. Assumptions: infra/modules/ecs-service rejects
  # a count below one whenever it is creating a service, because a service with no
  # running task leaves its load balancer target group empty and every request
  # through the balancer then answers with a gateway error. Refusing zero here
  # rejects that value at the root with a message naming this input, rather than
  # letting it fail at the module boundary. Alternatives Considered: admitting
  # zero and relying on the module to catch it -- rejected, because an input whose
  # documented domain is wider than what the stack accepts invites exactly the
  # value that cannot work.
  #
  # WHY : Assumptions: this value is coupled to the service module's AUTOSCALING
  #       floor, which is a module input this root deliberately does not declare.
  #       infra/modules/ecs-service cross-checks the task count against that
  #       floor whenever it is both creating a service and enabling autoscaling,
  #       and its own floor defaults to 2 -- so passing this count of 1 while
  #       leaving that default in place is rejected at the module boundary, with
  #       the error attributed to the module's input rather than to this one.
  #       main.tf must therefore satisfy one of two conditions for this
  #       environment: pass an autoscaling floor no greater than this count, or
  #       leave autoscaling off here. Recording the coupling at the input that
  #       triggers it is the only place a reader will look for it, because
  #       nothing in the name of either value suggests the other exists.
  #       Alternatives Considered: declaring the autoscaling bounds as two more
  #       root inputs so this root could keep them consistent itself. Rejected --
  #       they are not among the sizing values the two environments are permitted
  #       to differ on, and adding inputs no module call would read from this root
  #       is how an input surface silently stops matching the other root's.
  validation {
    condition     = var.ecs_desired_count >= 1 && var.ecs_desired_count <= 10 && floor(var.ecs_desired_count) == var.ecs_desired_count
    error_message = "ecs_desired_count must be a whole number from 1 to 10 inclusive. Zero is not accepted: a service with no running task leaves its target group empty and every request through the load balancer fails. To stop an environment billing, tear it down with `terraform destroy` instead."
  }
}

# -----------------------------------------------------------------------------
# Retention and edge footprint
# -----------------------------------------------------------------------------

# WHY a week here. Trade-offs: logs in this environment are read while the work
# that produced them is still in progress and have little value afterwards, so a
# short retention keeps storage cost proportional to that. Production keeps them
# far longer, for the audit trail the migrated system inherits from a mainframe
# whose job logs were retained on the spool. This is a retention value, so the
# two roots are explicitly permitted to differ on it without changing the stack's
# shape.
#
# WHY : Assumptions: this one value is forwarded to five different module inputs
#       whose accepted domains are NOT identical, and the domain below is their
#       intersection rather than any one of them. The log group inputs on the
#       service, API, batch and observability modules take the enumerated set of
#       periods the log service accepts; the content distribution module instead
#       reads its retention as the day count of an object lifecycle expiry rule
#       and requires it to be greater than zero. Zero is therefore excluded here
#       even though the log service reads it as `never expire` and two of the five
#       consumers would accept it -- a value legal for four consumers and
#       rejected by the fifth is the kind of mistake that surfaces only once the
#       fifth module is reached. Trade-offs: excluding zero also removes the
#       ability to retain logs indefinitely from this root, which is a cost that
#       would otherwise grow without anyone deciding to accept it.
variable "log_retention_days" {
  description = "Days that log groups and log-derived object lifecycles in this environment retain data. Forwarded to the service, API gateway, batch, observability and content distribution modules, so one value governs the whole environment's retention rather than each module carrying its own. Must be one of the periods the log service accepts, and must be greater than zero because one consumer reads it as an object lifecycle expiry."
  type        = number
  default     = 7

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.log_retention_days)
    error_message = "log_retention_days must be one of the retention periods CloudWatch Logs accepts: 1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288 or 3653. Zero, meaning never expire, is deliberately excluded."
  }
}

# WHY the narrowest class here. Trade-offs: the users of this environment are the
# people building it, so paying for a worldwide edge footprint buys nothing
# measurable. Production takes the widest class. Neither choice changes the
# distribution's configuration, its origin access control or its error routing,
# which is what keeps this a sizing difference between the roots rather than a
# topology one.
variable "cloudfront_price_class" {
  description = "Edge locations the browser application's distribution is served from, forwarded to the content distribution module. Must be one of PriceClass_100, PriceClass_200 or PriceClass_All. A narrower class serves fewer regions at lower cost; it changes latency for distant users and nothing else about how the distribution behaves."
  type        = string
  default     = "PriceClass_100"

  validation {
    # WHY : Assumptions: these three strings are the service's entire
    #       enumeration. A misspelling is otherwise rejected during apply against
    #       the distribution resource rather than against this input, so the
    #       check is here to name the input instead.
    condition     = contains(["PriceClass_100", "PriceClass_200", "PriceClass_All"], var.cloudfront_price_class)
    error_message = "cloudfront_price_class must be one of PriceClass_100, PriceClass_200 or PriceClass_All; the distribution accepts no other tier."
  }
}

# -----------------------------------------------------------------------------
# Batch schedule
# -----------------------------------------------------------------------------

# WHY the schedule is an input at all, when the chain it starts is identical in
# both environments. Trade-offs: the state machine, its states and their order are
# fixed and are not configurable from here -- only the moment the chain is
# triggered is. Making that an input lets this environment run its chain clear of
# production's without either root differing in what the chain DOES, which is the
# distinction the closed input set rests on.
#
# WHY : Assumptions: this value must not place the chain inside the window
#       aurora_preferred_backup_window reserves. Both act on the same cluster and
#       the chain is the workload that drives this environment's database toward
#       its deliberately low capacity ceiling, so an overlap makes each slower and
#       ties the chain's behaviour to how much data the backup has to copy.
#       Whoever moves either value must check the other; the two defaults are set
#       clear of one another.
#       infra/modules/eventbridge-scheduler accepts only the cron form -- the rate
#       and one-shot forms are refused there -- so the check below is a shape
#       check matching that contract rather than a preference.
variable "batch_schedule_expression" {
  description = "Schedule on which the nightly batch chain is started, forwarded to the scheduler module as the trigger for the batch state machine. Must be a `cron(...)` expression; the `rate(...)` and `at(...)` forms are not accepted by that module. Must not overlap aurora_preferred_backup_window, because both act on the same database cluster."
  type        = string
  default     = "cron(0 2 * * ? *)"

  validation {
    condition     = startswith(var.batch_schedule_expression, "cron(") && endswith(var.batch_schedule_expression, ")")
    error_message = "batch_schedule_expression must be a cron(...) expression, for example cron(0 2 * * ? *); the rate(...) and at(...) forms are not accepted by the scheduler module."
  }
}

# -----------------------------------------------------------------------------
# Teardown posture -- the two flags deciding whether this environment can be
# destroyed cleanly
# -----------------------------------------------------------------------------

# WHY this is false here. Trade-offs: one of this project's acceptance criteria is
# that the stack tears down cleanly with `terraform destroy`. Deletion protection
# makes that command fail against the protected resource and require a
# preliminary apply to clear the flag before the destroy can proceed, which is
# precisely the safety net production wants and precisely the friction a
# development environment does not. The risk accepted is real and is stated
# plainly: in this environment a destroy removes the database cluster and the user
# pool without further challenge.
#
# Alternatives Considered: two separate flags, one per protected resource.
# Rejected -- nothing in this architecture wants a protected database beside an
# unprotected user pool, since the two are destroyed together or not at all, and
# two flags would let the two roots drift into exactly that state.
variable "deletion_protection" {
  description = "Whether the stateful resources in this environment refuse deletion until the flag is cleared. Forwarded to both resources that offer the protection -- the database cluster and the user pool -- so one value governs the environment's whole teardown posture."
  type        = bool
  default     = false
}

# WHY this is true here. Trade-offs: a development cluster's contents are
# reproducible by rerunning the ETL against the reference seed datasets under
# app/data, which are read-only and always present, so a final snapshot preserves
# nothing that cannot be regenerated. Skipping it also stops `terraform destroy`
# leaving a billed artifact behind after the environment is supposed to be gone,
# which is what the clean-teardown criterion asks for. Production inverts this,
# because there the cluster's contents are not reproducible from anything in this
# repository.
variable "skip_final_snapshot" {
  description = "Whether destroying the database cluster skips taking a final snapshot first. Skipping makes the destroy fast and complete; taking one leaves a snapshot that survives the cluster and continues to bill until it is deleted by hand."
  type        = bool
  default     = true
}
