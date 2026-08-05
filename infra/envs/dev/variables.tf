# =============================================================================
# infra/envs/dev/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input surface of the CardDemo `dev` Terraform environment root.
#   Two inputs configure the root provider in versions.tf; the environment
#   composition must forward the remainder into the reusable modules. Optional
#   non-secret overrides may be supplied at invocation time without making a
#   missing main.tf or terraform.tfvars file an authority for this contract.
#
#   The set of names declared here is deliberately CLOSED, and that closure is
#   the point of the file. The prod root is completed in a later implementation
#   index and must mirror this same surface; until then, this file is the
#   authoritative environment-facing contract. The two environments remain
#   identical in topology and differ only in sizing and retention.
#
# Parameters:
#   Thirty-five inputs, nine of them required, in eight groups -- the two values
#   the provider reads; naming and environment identity; the VPC address space;
#   the serverless database's version, capacity, backup and durability settings;
#   container task sizing; log retention, edge footprint and the batch schedule;
#   the TLS identities and imported service key material the load balancer,
#   distribution and tasks each need; and the deployment artifact and the OIDC
#   identity permitted to publish it. The nine with no default are
#   `alb_certificate_arn`, `internal_service_domain_name`,
#   `cloudfront_acm_certificate_arn`, `cloudfront_aliases`,
#   `service_tls_certificate`, `service_tls_private_key`, `image_tag`,
#   `github_repository` and `github_oidc_provider_arn`: a certificate, a key or a
#   deployable artifact has no defensible default, and defaulting one would make a
#   root that cannot serve TLS look complete.
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
# Non-obvious design decisions:
#   - Alternatives Considered: accepting the database and seed-user credentials
#     as inputs, the way a great many Terraform roots do -- a `db_master_password`
#     variable behind a `sensitive = true` marker. Rejected outright, and that
#     rejection is why no credential input of that shape appears below. Any value
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
#   - Assumptions: sizing and retention inputs are defaulted, while the six TLS
#     inputs are deliberately required. Certificate ARNs, DNS names and imported
#     PEM material are deployment identities that cannot be guessed without
#     creating a stack that either fails TLS validation or presents the wrong
#     identity. `terraform validate` remains non-interactive with required
#     variables; a plan or apply must receive them explicitly.
#   - Assumptions: no default here holds a credential, an account identifier, an
#     ARN, a bucket name or a table name. Identifiers reach this root only as
#     module outputs or required operator inputs wired together in main.tf. The
#     two sensitive PEM inputs have no defaults and must arrive through an
#     operator secret channel, never the tracked terraform.tfvars.
#   - Where a comment below reasons about `terraform apply` or `terraform
#     destroy`, it describes what an input MEANS at that point; it is not a
#     report on a provisioned stack. This tree is authored and statically
#     validated -- formatted, validated, planned, linted and policy-scanned --
#     and applying it to a live account is an operator action outside this scope.
# =============================================================================

# -----------------------------------------------------------------------------
# Provider inputs -- the two values versions.tf reads
# -----------------------------------------------------------------------------

# Trade-offs: defaulting the region keeps credential-free static validation
# non-interactive when no variable file is supplied. An
# input with no default makes that step prompt and then fail in a
# non-interactive shell, so this would be the one directory in the tree that
# could not be checked. A region identifier is configuration and not a
# credential -- it names a public AWS location and confers no access -- so
# publishing one as a default discloses nothing. The cost accepted is that an
# operator who never sets it provisions this environment in us-east-1, which
# outputs.tf reports back so the choice does not stay invisible.
#
# Assumptions: the default matches infra/bootstrap because the state bucket this
# root's backend.tf points at is created by infra/bootstrap, which defaults to
# the same region. A backend in one region with resources in another is legal but
# confusing, and matching the defaults keeps the simplest possible deployment
# together in one place.
variable "aws_region" {
  description = "AWS region this environment is provisioned into. Read by `provider \"aws\"` in versions.tf, so all sixteen modules main.tf calls inherit it instead of configuring a region of their own. Accepts a standard region identifier such as us-east-1 or ap-southeast-4."
  type        = string
  default     = "us-east-1"

  # Trade-offs: this is a shape check on a value the provider would reject
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

# Trade-offs: versions.tf applies the root tag set through provider
# `default_tags`, so every taggable resource created by the modules carries it
# without every module author wiring a tags argument. The cost accepted is
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
    # Assumptions: the service accepts at most 50 tags per resource and
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

# Trade-offs: this prefix is validated because it is
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

# Trade-offs: `environment` remains an input for root symmetry but accepts only
# this root's identity. All sixteen modules require a value, but accepting both
# environment names here would let this state manage resources named and tagged
# for the other environment. An
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
    # Trade-offs: this checks only that the value IS a CIDR block, and
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

# Assumptions: the capacity invariant is NOT restated in this file.
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

  # Assumptions: the two are not the same decision. A default in the MODULE would
  # let any caller omit the version, so dev and prod would both silently pin to
  # whatever release was current when that file was written and would drift apart
  # the moment one root overrode it and the other did not. A default in the ROOT
  # is per-environment by construction and is a reviewed, committed value in the
  # single file that owns this environment -- which is what the module's own
  # description asks for when it says the version is supplied by the environment
  # root so that an engine upgrade is an explicit change to one file.
  #
  # Assumptions: this release supports scaling to zero; that capability is
  # available only from Aurora PostgreSQL 13.15,
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

  # Trade-offs: a zero floor is specific to development because its database is idle
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
    # Trade-offs: a bare non-negativity check, deliberately weaker than the
    #       module's rule and chosen so it cannot disagree with it. A negative
    #       capacity is the one value worth catching in the root, because it
    #       reads as a plain typo rather than as a capacity decision.
    condition     = var.aurora_min_capacity >= 0
    error_message = "aurora_min_capacity must not be negative; the database module additionally requires a value from 0 to 256 in half-unit increments."
  }
}

variable "aurora_max_capacity" {
  description = "Ceiling of the cluster's capacity range, in Aurora Capacity Units, forwarded to the database module which requires it. It bounds what a runaway query or an unexpectedly large batch run can cost, which is why a development environment sets it low rather than leaving headroom it will never use. The module owns the range, the granularity and the rule relating this to the floor."
  type        = number
  default     = 4

  # Trade-offs: the low ceiling is a cost
  # bound, not a performance target -- the cluster only scales up to it under
  # load. Setting it low in this environment means a mistake in a query or a
  # fixture large enough to drive real load is expensive in seconds rather than
  # in capacity units. The accepted cost is that a genuinely heavy one-off job
  # here runs slower than it would in production, which is the correct priority
  # for an environment nobody depends on.
  validation {
    # Trade-offs: as with the floor, this is deliberately weaker than the
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

  # Assumptions: the module makes it mandatory once the floor is zero and permits
  # it to be null otherwise, so production can leave it inert while this
  # environment must set it. That asymmetry is the reason it appears in both: the
  # two roots' input surfaces are required to match, and an input that existed in
  # only one of them would be a structural difference between the environments
  # rather than a sizing one.
  #
  # Trade-offs: the shortest accepted interval favors this environment's bursty
  # usage. The two ends of the range buy different things. A short interval pauses an idle cluster
  # sooner and stops it billing; a long one avoids paying the resume delay
  # repeatedly during intermittent use. This takes the short end because the
  # usage this environment actually sees is a burst of work followed by long
  # idleness, which is the case the short end serves.
  validation {
    # Trade-offs: a whole-number check only. The module owns the accepted
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
  default     = 7

  # Assumptions: the service has no off switch for automated backups, so one day
  # is the floor rather than a token retention choice.
  # Trade-offs: this environment's data is
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

  # Assumptions: Fargate offers a fixed set of CPU sizes, and each one
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

  # Assumptions: for the CPU sizes this root admits, Fargate accepts memory
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

  # Trade-offs: a single development task
  # means a deployment or a task failure is a brief outage of that service, which
  # is acceptable in an environment whose purpose is development and is why
  # production defaults higher. It also divides the compute cost of the whole
  # stack, because the count applies to every service. The topology is unchanged
  # either way -- the same services, the same load balancer, the same subnets --
  # and only the count differs, which is what keeps this a sizing difference
  # rather than a structural one.
  #
  # Assumptions: zero is refused because infra/modules/ecs-service rejects a
  # count below one whenever it is creating a service. A service with no
  # running task leaves its load balancer target group empty and every request
  # through the balancer then answers with a gateway error. Refusing zero here
  # rejects that value at the root with a message naming this input, rather than
  # letting it fail at the module boundary. Alternatives Considered: admitting
  # zero and relying on the module to catch it -- rejected, because an input whose
  # documented domain is wider than what the stack accepts invites exactly the
  # value that cannot work.
  #
  # Assumptions: this value is coupled to the service module's AUTOSCALING
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

# Trade-offs: one week keeps log storage proportional to development use because
# logs are read while the work that produced them is still in progress and have
# little value afterwards, so a
# short retention keeps storage cost proportional to that. Production keeps them
# far longer, for the audit trail the migrated system inherits from a mainframe
# whose job logs were retained on the spool. This is a retention value, so the
# two roots are explicitly permitted to differ on it without changing the stack's
# shape.
#
# Assumptions: this one value is forwarded to five different module inputs
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

# Trade-offs: the narrowest class serves the developers who use this environment;
# paying for a worldwide edge footprint buys nothing measurable. Production takes
# the widest class. Neither choice changes the
# distribution's configuration, its origin access control or its error routing,
# which is what keeps this a sizing difference between the roots rather than a
# topology one.
variable "cloudfront_price_class" {
  description = "Edge locations the browser application's distribution is served from, forwarded to the content distribution module. Must be one of PriceClass_100, PriceClass_200 or PriceClass_All. A narrower class serves fewer regions at lower cost; it changes latency for distant users and nothing else about how the distribution behaves."
  type        = string
  default     = "PriceClass_100"

  validation {
    # Assumptions: these three strings are the service's entire
    #       enumeration. A misspelling is otherwise rejected during apply against
    #       the distribution resource rather than against this input, so the
    #       check is here to name the input instead.
    condition     = contains(["PriceClass_100", "PriceClass_200", "PriceClass_All"], var.cloudfront_price_class)
    error_message = "cloudfront_price_class must be one of PriceClass_100, PriceClass_200 or PriceClass_All; the distribution accepts no other tier."
  }
}

# -----------------------------------------------------------------------------
# TLS identities and imported internal-service material
#
# Four non-secret values identify the certificates and DNS names that ALB,
# API Gateway and CloudFront must agree on. Two sensitive values carry the
# internal Spring Boot listeners' certificate chain and private key into the
# secrets module, which stores each as a scalar Secrets Manager value for ECS
# injection.
# -----------------------------------------------------------------------------

# WHY : Assumptions: an ACM certificate ARN identifies the listener credential
#       but does not encode the DNS name API Gateway verifies. Both values are
#       required independently so the root can pass the same identity to the
#       ALB listener and the VPC Link integration without trying to parse one
#       from the other.
variable "alb_certificate_arn" {
  description = "Regional ACM certificate ARN presented by the internal ALB HTTPS listener. Forwarded to the alb module; the certificate must be issued in this environment's region and cover internal_service_domain_name."
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^arn:[a-z0-9-]+:acm:[a-z0-9-]+:[0-9]{12}:certificate/[0-9a-f-]+$", var.alb_certificate_arn))
    error_message = "alb_certificate_arn must be a complete regional ACM certificate ARN shaped arn:<partition>:acm:<region>:<account-id>:certificate/<id>."
  }
}

variable "internal_service_domain_name" {
  description = "Bare DNS name covered by alb_certificate_arn. Forwarded to the alb module as its certificate identity and to api-gateway-http as the private integration server name to verify."
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$", var.internal_service_domain_name))
    error_message = "internal_service_domain_name must be a bare DNS hostname covered by the ALB certificate, with no scheme, port, wildcard or path."
  }
}

# WHY : Assumptions: CloudFront reads viewer certificates only from us-east-1,
#       even when the rest of the environment is deployed elsewhere. Keeping a
#       separate required ARN makes that global-service prerequisite explicit
#       rather than encouraging reuse of the regional ALB certificate.
variable "cloudfront_acm_certificate_arn" {
  description = "ACM certificate ARN issued in us-east-1 for the SPA distribution. Forwarded to cloudfront-spa and required to cover every entry in cloudfront_aliases."
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^arn:[a-z0-9-]+:acm:us-east-1:[0-9]{12}:certificate/[0-9a-f-]+$", var.cloudfront_acm_certificate_arn))
    error_message = "cloudfront_acm_certificate_arn must be an ACM certificate ARN issued in us-east-1, shaped arn:<partition>:acm:us-east-1:<account-id>:certificate/<id>."
  }
}

variable "cloudfront_aliases" {
  description = "Non-empty list of bare DNS names the SPA distribution serves. Every entry must be covered by cloudfront_acm_certificate_arn and is forwarded unchanged to cloudfront-spa."
  type        = list(string)
  nullable    = false

  validation {
    condition = length(var.cloudfront_aliases) > 0 && alltrue([
      for alias in var.cloudfront_aliases :
      can(regex("^(\\*\\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$", lower(alias)))
    ]) && length(distinct([for alias in var.cloudfront_aliases : lower(alias)])) == length(var.cloudfront_aliases)
    error_message = "cloudfront_aliases must contain at least one unique bare DNS name, optionally with a leading wildcard label; schemes, ports, paths and duplicate names are not accepted."
  }
}

# WHY : Alternatives Considered: generating a self-signed key pair in Terraform.
#       Rejected because the service clients would not trust a new authority and
#       the AAP provider set does not include a TLS provider. The imported pair
#       must come from the environment's existing trust authority.
#       Assumptions: `sensitive` redacts these values from ordinary CLI output but
#       does not remove them from state. They must be supplied through an
#       operator secret channel, and the encrypted remote-state backend remains
#       part of the security boundary.
variable "service_tls_certificate" {
  description = "PEM certificate or certificate chain presented by the CardDemo services' internal HTTPS listeners. Forwarded to the secrets module for scalar storage and ECS injection; supply it through an operator secret channel, never terraform.tfvars."
  type        = string
  sensitive   = true
  nullable    = false

  validation {
    condition = (
      strcontains(var.service_tls_certificate, "-----BEGIN CERTIFICATE-----") &&
      strcontains(var.service_tls_certificate, "-----END CERTIFICATE-----") &&
      !strcontains(var.service_tls_certificate, "PRIVATE KEY")
    )
    error_message = "service_tls_certificate must contain PEM BEGIN/END CERTIFICATE markers and must not contain private-key material."
  }
}

variable "service_tls_private_key" {
  description = "PEM private key paired with service_tls_certificate. Forwarded to the secrets module for scalar storage and ECS injection; supply it through an operator secret channel, never terraform.tfvars."
  type        = string
  sensitive   = true
  nullable    = false

  validation {
    condition = anytrue([
      strcontains(var.service_tls_private_key, "-----BEGIN PRIVATE KEY-----") &&
      strcontains(var.service_tls_private_key, "-----END PRIVATE KEY-----"),
      strcontains(var.service_tls_private_key, "-----BEGIN RSA PRIVATE KEY-----") &&
      strcontains(var.service_tls_private_key, "-----END RSA PRIVATE KEY-----"),
      strcontains(var.service_tls_private_key, "-----BEGIN EC PRIVATE KEY-----") &&
      strcontains(var.service_tls_private_key, "-----END EC PRIVATE KEY-----"),
    ])
    error_message = "service_tls_private_key must contain matching PEM private-key markers such as BEGIN/END PRIVATE KEY, RSA PRIVATE KEY or EC PRIVATE KEY."
  }
}

# -----------------------------------------------------------------------------
# Batch schedule
# -----------------------------------------------------------------------------

# Trade-offs: the schedule remains an input so dev and prod can trigger the same
# state machine at different times; its states and their order are
# fixed and are not configurable from here -- only the moment the chain is
# triggered is. Making that an input lets this environment run its chain clear of
# production's without either root differing in what the chain DOES, which is the
# distinction the closed input set rests on.
#
# Assumptions: this value must not place the chain inside the window
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

# Trade-offs: deletion protection is false here because an acceptance criterion is
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

# Trade-offs: skipping the final snapshot is safe here because development data is
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

# -----------------------------------------------------------------------------
# Deployment artifact, operational rotation and optional notification inputs.
# -----------------------------------------------------------------------------

variable "image_tag" {
  description = "Immutable image tag applied to all ten ECR repositories for this deployment, normally the source commit SHA supplied by the OIDC deployment workflow."
  type        = string
  nullable    = false

  # WHY : Assumptions: every ECS task definition must name an explicit,
  #       immutable artifact. `latest` would let two tasks launched from one
  #       revision run different bytes and would make rollback non-deterministic.
  validation {
    condition     = can(regex("^[A-Za-z0-9][A-Za-z0-9._-]{6,127}$", var.image_tag)) && lower(var.image_tag) != "latest"
    error_message = "image_tag must be a 7-128 character explicit tag and must not be latest; the deployment workflow supplies the commit SHA."
  }
}

variable "github_repository" {
  description = "GitHub repository in owner/name form whose protected dev environment may assume the SPA publication role."
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must be an owner/name pair such as example/carddemo."
  }
}

variable "github_oidc_provider_arn" {
  description = "ARN of the account-scoped GitHub Actions OIDC provider created by infra/bootstrap."
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^arn:[a-z0-9-]+:iam::[0-9]{12}:oidc-provider/token\\.actions\\.githubusercontent\\.com$", var.github_oidc_provider_arn))
    error_message = "github_oidc_provider_arn must identify the token.actions.githubusercontent.com IAM OIDC provider."
  }
}

variable "aurora_parameter_group_family" {
  description = "Aurora PostgreSQL cluster parameter-group family matching aurora_engine_version."
  type        = string
  default     = "aurora-postgresql16"
}

variable "aurora_preferred_maintenance_window" {
  description = "Weekly UTC maintenance window for Aurora, kept outside the nightly batch and backup windows."
  type        = string
  default     = "sun:09:00-sun:10:00"
}

variable "secret_recovery_window_in_days" {
  description = "Secrets Manager recovery window for generated database, TLS and Cognito credentials. Development uses immediate deletion so destroy/recreate remains repeatable."
  type        = number
  default     = 0

  validation {
    condition     = var.secret_recovery_window_in_days == 0 || (var.secret_recovery_window_in_days >= 7 && var.secret_recovery_window_in_days <= 30)
    error_message = "secret_recovery_window_in_days must be 0 or 7-30 days."
  }
}

variable "rotation_automatically_after_days" {
  description = "Days between scheduled rotations of each service database credential after its immediate bootstrap rotation."
  type        = number
  default     = 30

  validation {
    condition     = var.rotation_automatically_after_days >= 1 && var.rotation_automatically_after_days <= 1000
    error_message = "rotation_automatically_after_days must be between 1 and 1000."
  }
}

variable "alarm_email_endpoints" {
  description = "Email addresses subscribed to the environment observability topic; empty leaves notifications available for a later subscription without inventing an address."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for address in var.alarm_email_endpoints : can(regex("^[^@[:space:]]+@[^@[:space:]]+\\.[^@[:space:]]+$", address))])
    error_message = "Every alarm_email_endpoints entry must be a syntactically valid email address."
  }
}

# WHY : Assumptions: this is a security control with no safe default, so it is
#       required rather than defaulted. The distribution's content-security
#       policy names the origins the SPA may reach with fetch or XHR, and the
#       API Gateway endpoint is on a different origin from the bundle, so the
#       policy has to name it or the browser blocks every call. The value is
#       supplied per deployment rather than read from module.api_gateway,
#       because the SPA build learns the same address out of band -- the deploy
#       workflow passes it as VITE_API_BASE_URL -- and the two have to agree.
#       Deriving one from Terraform while the other comes from the workflow is
#       how they silently diverge; a single operator-supplied value cannot.
#       Alternatives Considered: wiring api_connect_src_origins directly from
#       module.api_gateway.api_endpoint_url. Rejected because it closes a
#       dependency ring: the response-header policy would depend on the API
#       stage, the stage's access log group depends on the S3 CMK, and that key's
#       policy narrows the CloudFront decrypt grant to this distribution's ARN --
#       which depends on the response-header policy. Terraform reports that as a
#       cycle at plan time, and the fix would be to weaken the key-policy
#       narrowing, which is a real control traded for a convenience.
#       Trade-offs: an operator must supply one more value per environment, and
#       an empty list is accepted -- it yields `connect-src 'self'`, which fails
#       closed by blocking the cross-origin call visibly rather than permitting
#       any origin.
variable "cloudfront_api_connect_src_origins" {
  description = "Origins the SPA is permitted to reach with fetch or XHR, forwarded unchanged to cloudfront-spa as api_connect_src_origins. Scheme and host only, no path and no trailing slash; normally the single API Gateway origin the SPA was built against. Supply it as TF_VAR_cloudfront_api_connect_src_origins, never in terraform.tfvars, so it tracks the deployed endpoint."
  type        = list(string)
  nullable    = false

  validation {
    condition = alltrue([
      for origin in var.cloudfront_api_connect_src_origins :
      can(regex("^https://[a-z0-9][a-z0-9.-]*[a-z0-9](:[0-9]{1,5})?$", lower(origin)))
    ])
    error_message = "Each entry in cloudfront_api_connect_src_origins must be an https:// scheme and host, optionally with a port, and must not contain a path, a trailing slash or a wildcard."
  }
}

# -----------------------------------------------------------------------------
# The account's deployment permissions boundary
# -----------------------------------------------------------------------------
# WHY : Assumptions: the boundary is owned by the account, NOT by this deployment.
#       A boundary that a deployment can rewrite bounds nothing, so it is supplied
#       rather than created here and has no default: a guessed ARN would either fail
#       the apply or, worse, attach a boundary that permits everything.
#       Trade-offs: an operator must create the boundary policy before the first
#       apply, which is one more prerequisite. Accepted because the alternative is
#       roles whose maximum permissions are whatever the inline documents in this
#       root happen to say, with nothing above them.
variable "permissions_boundary_arn" {
  description = "ARN of the same-account customer-managed IAM policy used as the permissions boundary on every role this deployment creates. Supplied by the operator or the deploy workflow; never created here."
  type        = string

  validation {
    condition     = can(regex("^arn:[a-z0-9-]+:iam::[0-9]{12}:policy/[A-Za-z0-9+=,.@_/-]+$", var.permissions_boundary_arn))
    error_message = "permissions_boundary_arn must be an anchored customer-managed IAM policy ARN, for example arn:aws:iam::111122223333:policy/CardDemoDeploymentBoundary."
  }
}

# -----------------------------------------------------------------------------
# The protected-field fingerprint key
# -----------------------------------------------------------------------------
# WHY : Assumptions: the key is SUPPLIED, not generated here, and it has no default.
#       A tag is only useful if the same input yields the same tag across runs and
#       across tasks, so a key generated per apply would silently invalidate every
#       tag produced by the previous apply. Keeping it outside this configuration
#       also keeps it outside this state file.
#       Trade-offs: one more prerequisite before the first apply. Accepted for the
#       same reason as the permissions boundary: a value this deployment could
#       rewrite would not be a key it can be held to.
variable "mask_hmac_secret_arn" {
  description = "Secrets Manager ARN of the environment-separated HMAC key the data-migration image uses for protected-field fingerprints. Supplied by the operator; never created or rotated by this configuration."
  type        = string

  validation {
    condition     = can(regex("^arn:[a-z0-9-]+:secretsmanager:[a-z0-9-]+:[0-9]{12}:secret:[A-Za-z0-9/_+=.@-]+-[A-Za-z0-9]{6}$", var.mask_hmac_secret_arn))
    error_message = "mask_hmac_secret_arn must be an anchored Secrets Manager secret ARN including its six-character suffix, for example arn:aws:secretsmanager:eu-west-1:111122223333:secret:carddemo/dev/mask-hmac-AbCdEf."
  }
}

# -----------------------------------------------------------------------------
# Immutable image references
# -----------------------------------------------------------------------------
# WHY : Assumptions: the deploy workflow already knows each digest -- it is what the
#       push returned -- so supplying it costs nothing and removes the one way a
#       registered task definition can silently change what it runs. In production
#       infra/modules/ecs-service refuses anything else, so an incomplete map fails
#       the plan with a message naming the artifact rather than deploying a tag.
#       Trade-offs: the map is OPTIONAL and defaults to empty, which is what keeps a
#       development apply able to run straight from a tag. The enforcement therefore
#       lives in the module, per environment, rather than in this variable.
variable "image_digests" {
  description = "Immutable sha256 digests keyed by ECR artifact name, for example { \"auth-service\" = \"sha256:<64 hex>\" }. Any artifact named here is deployed by digest instead of by image_tag. Required in production, where the ECS service module refuses a mutable tag."
  type        = map(string)
  default     = {}

  validation {
    condition = alltrue([
      for digest in values(var.image_digests) : can(regex("^sha256:[a-f0-9]{64}$", digest))
    ])
    error_message = "Every image_digests value must be sha256: followed by exactly 64 lowercase hexadecimal characters, as returned by an ECR push."
  }

  validation {
    condition = alltrue([
      for artifact in keys(var.image_digests) : contains([
        "auth-service",
        "account-service",
        "card-service",
        "transaction-service",
        "reference-service",
        "batch-service",
        "authorization-service",
        "reporting-service",
        "data-migration",
      ], artifact)
    ])
    error_message = "Every image_digests key must name one of the nine ECR artifacts this deployment builds: the eight services plus data-migration. A key that names no repository would be silently ignored."
  }
}
