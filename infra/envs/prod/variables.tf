# =============================================================================
# infra/envs/prod/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input surface of the CardDemo PRODUCTION environment root. Two
#   of these inputs configure the root's own aws provider; the others are the
#   explicit values consumed by the shared topology. The two roots are this one
#   and infra/envs/dev. That pairing is the point of this file: the two roots are
#   required to be identical in topology and to differ only in environment
#   identity, sizing, retention, protection and production edge certificate
#   inputs. This file declares the same thirty-six variable names as dev.
#
#   A variable present in one root and absent from the other would be a
#   structural divergence between the environments, so the symmetry is
#   load-bearing rather than tidy: a reader comparing the two files should find
#   the difference between production and development entirely in the default
#   values and in the reasoning beside them.
#
#   Values are supplied by infra/envs/prod/terraform.tfvars and by the deployment
#   workflow. image_tag, github_repository, github_oidc_provider_arn,
#   cloudfront_aliases and cloudfront_acm_certificate_arn are required because no
#   safe account-independent default exists; the full required set is enumerated
#   under Parameters below.
#
# Parameters -- THIRTY-SIX, TWELVE required. The twelve with no default are
#   `alb_certificate_arn`, `internal_service_domain_name`,
#   `cloudfront_acm_certificate_arn`, `cloudfront_aliases`,
#   `cloudfront_api_connect_src_origins`, `image_tag`, `github_repository`,
#   `github_oidc_provider_arn`, `mask_hmac_secret_arn`,
#   `mask_hmac_secret_kms_key_arn`, `permissions_boundary_arn` and
#   `alarm_email_endpoints`. None of the twelve appears in this root's
#   terraform.tfvars: each names an account-specific credential holder, edge
#   identity or operator address, so all twelve are supplied out of band by the
#   deployment workflow or the operator following docs/runbooks/deploy.md.
#   Assumptions: `alarm_email_endpoints` is required rather than defaulted
#   because an alarm destination is operator-supplied and no value held in this
#   repository is a defensible one. The count is stated here rather than left to
#   be counted from the table below, because the table is what a reader consults
#   for one input and this line is what they consult before a first apply.
#   The table that follows is a reading aid for the same declarations and is not
#   exhaustive; each `variable` block below carries the authoritative type,
#   description and validation for its own input.
#   aws_region                      string       Region this environment
#                                                deploys into.
#   tags                            map(string)  Common tag set applied through
#                                                the provider's default_tags.
#   aurora_min_capacity             number       Floor of the serverless
#                                                database's capacity range.
#   aurora_max_capacity             number       Ceiling of that range.
#   aurora_seconds_until_auto_pause number       Idle interval before a
#                                                zero-floor cluster pauses;
#                                                inert while the floor is above
#                                                zero, as it is here.
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
#   after an apply -- is declared in infra/envs/prod/outputs.tf.
#
# Errors / Exceptions:
#   Twenty-eight of the thirty-six inputs carry a `validation` block, and the eight
#   that do not are two deliberate groups rather than eight oversights. A root-level
#   block is written here when, and only when, this file can see something the
#   module receiving the value cannot: an environment policy such as production's
#   capacity floor, a cross-input rule such as the task memory band, the
#   INTERSECTION of constraints several modules each impose separately on one
#   forwarded value as `name_prefix` does, or a value this root PARSES ITSELF, as
#   `batch_schedule_expression` is parsed to prove the batch window disjoint. The
#   eight without are, first, `deletion_protection` and `skip_final_snapshot`,
#   where `bool` admits exactly the two values that mean anything so a block could
#   only restate the type; and second, the six whose every rule is already enforced
#   once by the module they are forwarded to --
#   `aurora_seconds_until_auto_pause`, `aurora_engine_version`,
#   `aurora_parameter_group_family`, `aurora_backup_retention_period`,
#   `aurora_preferred_backup_window` and `aurora_preferred_maintenance_window`.
#   Each of those six carries a comment saying which module holds its rule, because
#   the reasonable-looking alternative is to restate the rule here and a second
#   statement of one rule is what drifts. Each block that IS written rejects a bad
#   value during `terraform plan`, before any
#   request leaves the machine, rather than letting
#   the service reject it partway through an apply that has already created other
#   resources. That matters more in this root than in the other: an apply here
#   modifies live infrastructure, so a value rejected halfway through leaves the
#   environment in a state neither the previous nor the intended one. The two
#   inputs whose absence would otherwise be silent are `aws_region`, which the
#   provider in versions.tf reads directly, and `tags`, which that provider's
#   `default_tags` block reads; before this file existed both were referenced by
#   that provider and declared nowhere.
#
# WHY (non-obvious design decisions):
#   - Assumptions: `environment` and `name_prefix` ARE declared here, because
#     main.tf composes almost every resource name from them -- it reads
#     `var.environment` and `var.name_prefix` throughout -- and the two roots are
#     required to declare the same names. The hazard that argues against making
#     the environment name an input is real, though: a caller who passed `dev`
#     here would point the production state at development-named resources and
#     the plan would look clean. `environment` therefore carries a `validation`
#     that admits the single value `prod`, which keeps the input surface
#     symmetrical with dev while making the wrong value unrepresentable rather
#     than merely unlikely. `name_prefix` needs no such pin: it is deliberately
#     the SAME in both roots, since the environment name is what separates the
#     two stacks and the prefix is what identifies them jointly as CardDemo.
#   - Trade-offs: every SIZING, RETENTION and PROTECTION input is defaulted --
#     twenty-four of the thirty-six -- so this root initialises and validates with
#     no variable file and no credentials, which is the only way a non-interactive
#     pipeline job can check it. The remaining twelve carry no default on purpose
#     and a `plan` therefore refuses to proceed until each is supplied; that is
#     the intended asymmetry, because those twelve name account-specific material
#     no committed value could stand in for. The cost of defaulting the other
#     twenty-four is heavier here than in development: a defaulted production
#     capacity or retention value is a decision made by silence. Two things
#     mitigate it -- outputs.tf reports the values actually used, and every
#     default in this file is chosen to be the SAFE end of its range rather than
#     the cheap one, so a forgotten variable file yields a conservative
#     environment rather than an exposed one.
#   - Assumptions: NO default here holds a credential, an account identifier, an
#     ARN, a bucket name or a password. This root's secrets do not exist in
#     source at any point in their lifetime: the database credential and the seed
#     user passwords are generated at apply time by the secrets and cognito
#     modules and written straight into Secrets Manager. That is what makes the
#     project's no-secrets-in-source constraint structurally true here rather
#     than merely observed.
#   - Alternatives Considered: a `db_password` input -- or any sibling of it, a
#     master credential, an access key, a credentialed connection string -- so
#     that an operator could supply the database password the way they supply the
#     certificate ARNs. REJECTED, and the absence is recorded here rather than
#     left silent precisely because it is the input a reader most expects to
#     find. Three things are wrong with it. The value would have to be typed
#     somewhere to be passed, and the two places available are this root's
#     terraform.tfvars, which is committed, and a shell history or CI variable,
#     neither of which the project's constraint admits. A variable's value is
#     also written to state in clear, so the input would defeat the constraint
#     even when the file holding it was never committed. And it would duplicate
#     an authority: `manage_master_user_password` already has RDS create and
#     rotate the credential into Secrets Manager, so a supplied password would
#     be the one an operator believed in while the cluster authenticated against
#     the generated one. infra/modules/aurora-postgresql and infra/modules/secrets
#     decline the same input for the same reason, so the prohibition is
#     consistent from this root down to every module it calls.
#   - Assumptions: this environment does NOT scale its database to zero, so the
#     auto-pause input below is inert here. It is declared anyway, because the
#     two roots' input surfaces must match; the AWS provider floor in versions.tf
#     is likewise set by a capability only the development root exercises.
#   - Where a comment below reasons about `terraform apply` or `terraform
#     destroy`, it is describing what an input MEANS at those points, not
#     reporting on a provisioned stack. This tree is authored and statically
#     validated -- formatted, validated, planned, linted and policy-scanned;
#     applying it to a live account is an operator action outside this scope.
# =============================================================================

# -----------------------------------------------------------------------------
# Provider inputs -- the two values versions.tf reads
# -----------------------------------------------------------------------------

# WHY this has a default at all, when the region a production deployment lands in
# is emphatically something an operator should state explicitly. Trade-offs: the
# infrastructure pipeline checks this root without a variable file and without AWS
# credentials. An input with no default makes that step prompt for a value and
# then fail in a non-interactive shell, so this would be the one directory in the
# tree that could not be checked. A region identifier is configuration rather
# than a credential -- it names a public AWS location and confers no access -- so
# publishing one as a default discloses nothing. The accepted cost is that an
# operator who never sets it provisions production in us-east-1; outputs.tf
# reports the region back, and this root's terraform.tfvars is expected to state
# it outright rather than inherit it.
#
# WHY the default matches infra/envs/dev and infra/bootstrap. Assumptions: the
# state bucket this root's backend configuration points at is created by
# infra/bootstrap, which defaults to the same region. A backend in one region and
# resources in another is legal but confusing, and matching the defaults keeps the
# simplest deployment coherent.
variable "aws_region" {
  description = "AWS region this environment is provisioned into. Read by the aws provider in versions.tf and therefore inherited by every one of the sixteen modules this root calls. Accepts a standard region identifier, for example us-east-1 or ap-southeast-4."
  type        = string
  default     = "us-east-1"

  # WHY a shape check on a value the provider would reject anyway. Trade-offs: a
  # malformed region does not fail as a bad region. It fails as an
  # endpoint-resolution error naming a hostname, from whichever provider call runs
  # first, and nothing in that message names this input. Validating here
  # attributes the fault to the input that caused it, at plan time, before any API
  # call is attempted.
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
# per-resource argument. Trade-offs: versions.tf sets these on the provider's
# `default_tags` block, so every taggable resource this root creates -- including
# every resource created inside all sixteen modules -- carries them without any
# module author remembering to wire a tags argument through. The accepted cost is
# locality: reading a module's main.tf does not reveal the tags its resources will
# carry. The alternative, threading a tag map into sixteen module call sites,
# fails the first time one call site is edited and the others are not, and an
# untagged production resource is one no cost report attributes to anything.
#
# Alternatives Considered: a fixed `object({...})` type naming these three keys.
# Rejected -- the AWS tagging API is itself an arbitrary string-to-string map, so
# an object type would refuse a caller's own cost-allocation or ownership tags
# while adding nothing this root relies on.
#
# Assumptions: the default holds only non-secret descriptive values. Tags are
# readable by any principal that can describe the resource and are surfaced in
# billing and cost-allocation exports, so nothing sensitive belongs in one. The
# `Environment` value is the key difference from the development root's default,
# and it is what makes a cost report separate production spend from development's.
variable "tags" {
  description = "Common tag set merged into every taggable resource in this root, and in every module it calls, through the provider default_tags block in versions.tf. Values must be non-secret: tags are visible to any principal that can describe the resource and appear in cost-allocation exports."
  type        = map(string)
  default = {
    Project     = "carddemo"
    Environment = "prod"
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
# Database capacity
#
# These three are read together. The floor of the capacity range decides whether
# the cluster may pause at all; the auto-pause interval is meaningful only when
# that floor is zero, which here it is not; and the ceiling must exceed the floor
# for the range to be a range.
#
# WHY : Assumptions: the division of labour between this root and
#       infra/modules/aurora-postgresql is deliberate, and it is why these three
#       blocks carry fewer checks than the sizing inputs below. Every rule the
#       SERVICE imposes -- the 0-to-256 range, the half-unit granularity, the
#       ceiling not falling below the floor, the auto-pause interval's own range,
#       and the two conditional rules a zero floor triggers -- is enforced once by
#       that module, on behalf of every caller, and its main.tf records that
#       decision from the other side. What this root asserts is only what the
#       ENVIRONMENT adds: that production's floor is above zero, and that its
#       ceiling leaves the nightly batch chain room to scale into. The two files
#       read together give the whole contract, and neither restates a rule the
#       other could then contradict.
# -----------------------------------------------------------------------------

variable "aurora_min_capacity" {
  description = "Floor of the serverless database's capacity range, in Aurora Capacity Units. Held above zero in this environment, so the cluster never pauses and no query ever pays a resume delay."
  type        = number
  default     = 2

  # WHY this is above zero here and zero in the development root. Trade-offs: a
  # zero floor lets an idle cluster pause and stop billing capacity, at the price
  # of roughly fifteen seconds of resume delay on the first query afterwards. In
  # development that delay is immaterial and the saving is the largest single one
  # available. In production the same delay would land on a cardholder-facing
  # request -- an account view or a sign-on -- and the pause would additionally
  # discard the warm connection pools every service holds. Two capacity units is
  # the smallest floor that keeps the cluster continuously resident.
  #
  # Assumptions: a zero floor is what sets the AWS provider floor in versions.tf,
  # even though THIS root never uses one. The provider accepts a zero minimum only
  # from 5.80.0 onward, and the auto-pause argument a zero minimum makes mandatory
  # only from 5.81.0, so the pair floors at 5.81.0 and `~> 6.56` clears that
  # comfortably; the constraint is
  # identical in both roots because a single provider version has to satisfy both.
  validation {
    # WHY : ⚠️ Refactoring Rationale: this restated the service's own 0-to-256
    #       range and half-unit granularity. Both are already asserted on
    #       `min_capacity` by infra/modules/aurora-postgresql, whose main.tf
    #       records that the capacity rules "are enforced once, as `validation`
    #       blocks in variables.tf, and are deliberately not restated here" -- so
    #       the copy made this the second place one rule lived, and it had ALREADY
    #       DRIFTED: the companion ceiling check below floored at 0.5 where the
    #       module floors at 0. A divergent copy is worse than no copy, because a
    #       reader cannot tell which of the two is the contract. The range and the
    #       increment are therefore left to the module, which enforces them for
    #       every caller and reports them at plan time exactly as this did.
    #       Assumptions: what remains is the one rule the module CANNOT hold --
    #       that THIS environment's floor is above zero. The module has to keep
    #       accepting zero, because infra/envs/dev sets it and depends on the
    #       pause; the same value is correct there and wrong here, which makes
    #       this an environment policy rather than a service constraint.
    condition     = var.aurora_min_capacity > 0
    error_message = "aurora_min_capacity must be above 0 in the production root, because a zero floor lets the cluster pause and the roughly fifteen-second resume delay on the next query would land on a cardholder-facing request. Use 2 unless a larger floor is wanted, and leave 0 to infra/envs/dev. The 0-to-256 range and the half-unit granularity are enforced by infra/modules/aurora-postgresql rather than here."
  }
}

variable "aurora_max_capacity" {
  description = "Ceiling of the serverless database's capacity range, in Aurora Capacity Units. It has to leave room for the nightly batch chain, which is the heaviest thing this cluster does and which runs against the same writer the online services use."
  type        = number
  default     = 32

  # WHY the ceiling is well above the floor here. Assumptions: the load on this
  # cluster is not flat. The online services need a small, steady capacity, and
  # then the nightly chain posts the day's transactions, accrues interest and
  # generates statements and reports against the same writer, because the posting
  # unit of work is a single ACID commit across two schemas and is deliberately
  # not a saga. A ceiling sized for the online day alone would throttle exactly
  # the window that has the least tolerance for running long.
  validation {
    # WHY : ⚠️ Refactoring Rationale: two blocks stood here and between them
    #       restated three rules infra/modules/aurora-postgresql already owns --
    #       the 0-to-256 range, the half-unit granularity, and the pair rule that
    #       the ceiling is not below the floor. The first had drifted from the
    #       module it copied, flooring at 0.5 against the module's 0, which is
    #       exactly how a duplicated invariant fails: silently, and in the copy,
    #       where a reader cannot tell which statement is the contract. Both are
    #       withdrawn in favour of the module's single enforcement, which runs at
    #       plan time and names the offending value just as these did.
    #       Assumptions: what replaces them is a rule the module deliberately does
    #       NOT impose. The module permits the ceiling to EQUAL the floor, because
    #       a fixed-size cluster is a legitimate shape for some caller to ask for.
    #       This environment cannot use it: the nightly chain posts the day's
    #       transactions, accrues interest and generates statements against the
    #       same writer the online services are serving from, so a range with no
    #       headroom leaves the heaviest workload of the day nothing to scale into.
    #       Requiring the ceiling to exceed the floor is therefore an environment
    #       policy the shared module has no way to express, and it is what the
    #       section note above means by the range needing to be a range.
    condition     = var.aurora_max_capacity > var.aurora_min_capacity
    error_message = "aurora_max_capacity must be strictly greater than aurora_min_capacity in the production root, because the nightly batch chain runs against the same writer as the online services and a range with no headroom leaves it nothing to scale into. The 0-to-256 range, the half-unit granularity and the not-below-the-floor rule are enforced by infra/modules/aurora-postgresql rather than here."
  }
}

variable "aurora_seconds_until_auto_pause" {
  description = "Idle interval, in seconds, before a cluster whose capacity floor is zero pauses. INERT in this environment, because this root's floor is held above zero and Aurora only pauses a cluster that can scale to zero. main.tf forwards it to the database module unconditionally, so the value must still be one the service accepts; it is declared here so the two environment roots' input surfaces match exactly."
  type        = number
  default     = 300

  # WHY a variable that does nothing in this root is declared anyway.
  # Assumptions: the two environment roots are required to have the same input
  # surface, so that the difference between the environments is entirely in the
  # values. A variable existing in one root and not the other would be a
  # structural difference, and it would also mean a single tfvars template could
  # not serve both. The value is kept at the service's shortest accepted interval
  # rather than at something conspicuous, so that if this root's capacity floor is
  # ever lowered to zero the setting it then requires is already valid.
  #
  # WHY : ⚠️ Refactoring Rationale: a `validation` block stood here asserting the
  #       300-to-86400 whole-second range. That range is the service's, and
  #       infra/modules/aurora-postgresql already asserts it on
  #       `seconds_until_auto_pause` -- so the copy made this the second place one
  #       rule lived, which is the drift risk the module's own main.tf calls out
  #       when it records that the capacity rules are enforced once and
  #       deliberately not restated. It is withdrawn rather than reworded, and
  #       nothing is lost: main.tf forwards this input to the module
  #       unconditionally, so the module's block sees every value this root can
  #       pass and rejects a bad one at plan time.
  #       Assumptions: unlike the two capacity inputs, this one has NO production
  #       policy left to assert once the service range is the module's. Auto-pause
  #       is reachable only from a zero capacity floor, and this root forbids one,
  #       so there is no value of this input that changes what production does. A
  #       block invented to keep the count symmetrical would assert nothing.
}

# -----------------------------------------------------------------------------
# Container task sizing
# -----------------------------------------------------------------------------

variable "ecs_task_cpu" {
  description = "CPU units allocated to each service task, where 1024 units is one virtual CPU. Passed to every service this root creates, so all eight are sized alike within an environment; the difference between the environments is what this value is, not which services get it."
  type        = number
  default     = 1024

  # WHY one virtual CPU here rather than the half a development task gets.
  # Trade-offs: the services are JVM applications, and a task with less than a
  # full virtual CPU spends a visible share of its first requests on
  # just-in-time compilation while also serving them. In development that shows
  # up as a slow first call and nothing worse; under production concurrency it
  # shows up as latency on real requests. The cost is double the compute per task,
  # multiplied by the task count below.
  validation {
    # WHY : Trade-offs: the accepted set stops at 2048, three sizes rather than
    #       the seven Fargate offers. The reason is the memory rule below: from
    #       4096 units upward the valid memory band is no longer simply twice to
    #       eight times the CPU value, so admitting those sizes would require a
    #       per-size table here and a matching one there. Two virtual CPUs is
    #       ample for a Java service in this stack, and widening the set later
    #       means widening both checks in the same change -- which is the coupling
    #       this note exists to record.
    condition     = contains([512, 1024, 2048], var.ecs_task_cpu)
    error_message = "ecs_task_cpu must be 512, 1024 or 2048 CPU units; larger Fargate sizes are excluded deliberately because their valid memory band is not the simple multiple the memory check below asserts."
  }
}

variable "ecs_task_memory" {
  description = "Memory in mebibytes allocated to each service task. Fargate accepts only certain memory values for a given CPU size, so this input is validated against ecs_task_cpu rather than on its own."
  type        = number
  default     = 2048

  validation {
    # WHY : Assumptions: for CPU sizes of 512, 1024 and 2048 units, Fargate
    #       accepts memory from twice to eight times the CPU value in whole
    #       gibibyte steps, and nothing else. Asserting that here converts an
    #       apply-time task-definition registration failure -- which names the
    #       task definition rather than the input -- into a plan-time error naming
    #       both inputs. This check is coupled to the CPU set above: if that set
    #       is widened past 2048, this rule must be replaced with a per-size table
    #       in the same change.
    condition     = var.ecs_task_memory >= var.ecs_task_cpu * 2 && var.ecs_task_memory <= var.ecs_task_cpu * 8 && var.ecs_task_memory % 1024 == 0
    error_message = "ecs_task_memory must be a whole multiple of 1024 MiB between twice and eight times ecs_task_cpu, which is the band Fargate accepts for the CPU sizes this root permits."
  }
}

variable "ecs_desired_count" {
  description = "Tasks each service runs. This is the input that decides whether a service survives losing one task, and it is the clearest single difference between a production environment and a development one."
  type        = number
  default     = 2

  # WHY two tasks here rather than one. Trade-offs: with a single task, a rolling
  # deployment and an unexpected task failure are both a brief outage of that
  # service. Two tasks, placed by the service in different availability zones,
  # mean the load balancer always has a healthy target during a deployment and
  # survives losing one zone -- which is the reason the network has three zones in
  # the first place. The cost is double the compute across all eight services.
  # The topology is unchanged either way: the same services, the same load
  # balancer, the same subnets, so this stays a sizing difference between the
  # roots rather than a structural one.
  validation {
    # WHY : Assumptions: zero is permitted, and the permission is inherited from
    #       the shared input surface rather than intended for use here: the two
    #       roots must validate identically. A production root passing zero would
    #       leave every service provisioned and serving nothing, which no plan
    #       review would miss, whereas refusing zero would make the two roots'
    #       contracts differ. The ceiling of 10 bounds an accidental extra digit,
    #       which across eight services is an eightfold cost error.
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
  default     = 365

  # WHY a year here against a week in development. Assumptions: these logs are
  # the migrated system's operational record of which batch steps ran on which
  # night and which requests failed, and they replace a mainframe job log that was
  # retained on the spool and archived. A week would be shorter than the interval
  # at which a monthly statement discrepancy is noticed, so the log needed to
  # explain it would already be gone. This is a retention value, so the two roots
  # are explicitly permitted to differ on it without changing the stack's shape.
  validation {
    # WHY : Assumptions: the set is closed because the service accepts only
    #       these values -- an arbitrary number is rejected during apply, after
    #       other resources exist. 0 is excluded even though the service reads it
    #       as never expire: unbounded retention is a cost that grows without
    #       anyone deciding to accept it, and in this environment it would grow
    #       fastest.
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.log_retention_days)
    error_message = "log_retention_days must be one of the retention periods CloudWatch Logs accepts: 1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288 or 3653."
  }
}

variable "cloudfront_price_class" {
  description = "Edge locations the browser application's distribution is served from. A wider class serves more regions at higher cost; it changes latency for distant users and nothing else about how the distribution behaves."
  type        = string
  default     = "PriceClass_All"

  # WHY the widest class here. Trade-offs: the browser application replaces
  # twenty-one 3270 screens whose users were wherever the terminals were, and
  # nothing in this architecture constrains them to one continent. Serving the
  # application from every edge location costs more per request and removes the
  # transcontinental round trip on each asset. Development takes the narrowest
  # class instead, since its only users are the people building it. Neither choice
  # changes the distribution's configuration, its origin access control or its
  # error routing, which keeps this a sizing difference rather than a topology one.
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
# Teardown posture -- the two flags whose defaults are inverted relative to the
# development root, and the reason the inversion exists
# -----------------------------------------------------------------------------

variable "deletion_protection" {
  description = "Whether the stateful resources in this environment refuse deletion until the flag is cleared. It is forwarded to both resources that offer the protection, the database cluster and the user pool, so one value governs the environment's whole teardown posture."
  type        = bool
  default     = true

  # WHY this is true here and false in development. Trade-offs: with protection
  # on, `terraform destroy` FAILS against the database cluster and the user pool
  # rather than removing them, and an operator who genuinely intends to destroy
  # production must first apply a change clearing this flag. That is friction, and
  # it is the entire point: it makes destroying cardholder data and every user
  # identity a two-step, separately reviewed act rather than a consequence of
  # running one command in the wrong directory. The project's clean-teardown
  # acceptance criterion is still satisfiable here -- the runbook records the
  # clear-then-destroy sequence -- and it is satisfied in one step in development,
  # where the flag is off.
  #
  # Alternatives Considered: two separate flags, one per resource. Rejected
  # because nothing in this architecture wants a protected database beside an
  # unprotected user pool -- losing the pool loses every identity that can reach
  # the protected database -- and two flags would let the roots drift into exactly
  # that state.
}

variable "skip_final_snapshot" {
  description = "Whether destroying the database cluster skips taking a final snapshot first. Taking one leaves a snapshot that survives the cluster and continues to bill until it is deleted; skipping it makes the destroy fast and irreversible."
  type        = bool
  default     = false

  # WHY this is false here and true in development. Trade-offs: a development
  # cluster's contents are reproducible by rerunning the ETL against the seed
  # datasets under app/data, which are reference-only and always present, so a
  # final snapshot there preserves nothing. This cluster's contents are NOT
  # reproducible from anything in this repository: they are the posted
  # transactions, accrued interest and updated masters of every night since
  # cutover. A snapshot is therefore taken even though it outlives the cluster and
  # keeps billing, and even though it makes the destroy slower -- the cost of
  # storing it is not comparable to the cost of not having it.
  #
  # Assumptions: this pairs with the deletion-protection flag above rather than
  # duplicating it. Protection decides whether a destroy is permitted at all;
  # this decides what is preserved once it is.
}

# -----------------------------------------------------------------------------
# Shared topology inputs. These match development; production differs only in
# the sizing, retention and protection variables declared above.
# -----------------------------------------------------------------------------

# WHY : Assumptions: this value is deliberately the SAME in both roots, which is
#       what distinguishes it from `environment` directly below. The environment
#       name is what separates the production stack from the development one; this
#       prefix is what identifies both of them jointly as CardDemo, so a reader
#       grepping either account for the deployment has one string to grep. Making
#       the two roots differ here would produce two stacks that no single search
#       finds, which is the opposite of what a name prefix is for.
# WHY : ⚠️ Refactoring Rationale: this carried no `validation`, while its own
#       description promised a lower-case value -- a constraint asserted in prose
#       and enforced by nothing.
#       Assumptions: a rule here is NOT a duplicate of the fifteen modules that
#       validate this same input, and that is the whole reason it is worth adding.
#       Each of those modules checks the prefix against ITS OWN namespace and
#       cannot see the others, so each accepts values the deployment as a whole
#       cannot use. The binding constraint is their INTERSECTION, and only this
#       root -- which forwards one value to all sixteen -- is in a position to
#       state it: `s3-datasets` caps the prefix at twelve characters, `cognito`
#       requires at least two, and `cognito`, `s3-datasets`, `aurora-postgresql`
#       and `secrets` all require it to begin with a letter rather than a digit.
#       Checked only per module, a thirteen-character prefix passes fourteen
#       module calls and fails the fifteenth partway through an apply, reporting a
#       bucket-name constraint rather than naming this input.
#       Trade-offs: the intersection is stricter than any one module's rule, so a
#       prefix that would satisfy the module a reader happens to be looking at can
#       be refused here. That is the intended direction -- refusing it at plan time
#       costs a re-edit, while accepting it costs a half-created environment. This
#       is deliberately NOT a production-only policy: both roots forward one prefix
#       to the same sixteen modules, so the same intersection binds development.
variable "name_prefix" {
  description = "Prefix concatenated into the name of every resource this root creates, ahead of the component and the environment, giving the whole deployment one greppable identity. Two to twelve characters of lower-case letters, digits and hyphens, beginning with a letter and ending with a letter or digit -- the intersection of the naming rules the sixteen modules this root calls each impose on their own namespace."
  type        = string
  default     = "carddemo"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]*[a-z0-9]$", var.name_prefix)) && length(var.name_prefix) >= 2 && length(var.name_prefix) <= 12
    error_message = "name_prefix must be 2 to 12 characters of lower-case letters, digits and hyphens, must begin with a letter and must not end with a hyphen -- for example carddemo. The twelve-character ceiling is the dataset bucket namespace's, the two-character floor and the leading-letter rule are the user pool's, and this one prefix is composed into names in both."
  }
}

# WHY : Assumptions: this is the one input that decides WHICH environment the whole
#       stack is, so it is both declared and pinned. It is composed into the name of
#       almost every resource the sixteen modules create, and it is what separates
#       this deployment's resources from the development root's inside an account
#       that could hold both.
#       Alternatives Considered: (1) not declaring it at all and having main.tf carry
#       the literal `prod`, which removes the wrong-value hazard completely. Rejected
#       because the two roots are required to declare the same input names and
#       .github/workflows/infra-ci.yml asserts that equality, so an input dev
#       declares and this root does not would fail the build. (2) Declaring it and
#       leaving it free, as dev effectively does. Rejected because a caller who
#       passed `dev` here would have THIS root's state -- the production state --
#       adopt development-named resources, and the plan would look entirely clean
#       while doing it. Pinning keeps the input surface symmetrical and makes the
#       one dangerous value unrepresentable rather than merely unlikely, which is
#       the combination neither alternative gives.
variable "environment" {
  description = "Environment identity forwarded to every module and composed into the name of nearly every resource this root creates. Pinned to prod: this root's state and resource names are production's, so no other value is admissible here."
  type        = string
  default     = "prod"

  validation {
    condition     = var.environment == "prod"
    error_message = "environment must be prod in this root. Any other value would have the production state adopt another environment's resource names, and the plan would look clean while doing it -- deploy a different environment from its own root under infra/envs instead."
  }
}

variable "vpc_cidr" {
  description = "IPv4 CIDR allocated to the production VPC. Must use a /16 prefix: the reporting service's trusted-proxy pattern is derived from the first two octets of this block, which is exact only for a /16."
  type        = string
  default     = "10.1.0.0/16"

  # WHY : Refactoring Rationale: this validation is NEW and it narrows THIS ROOT's
  #       contract without touching the module's. The network module accepts /16 to
  #       /20, and that remains true for any other caller. This root additionally
  #       requires exactly /16, because one value derived here depends on it for
  #       correctness rather than for convenience: the reporting service's trusted
  #       proxy pattern in main.tf is composed from the FIRST TWO OCTETS of this block,
  #       which describes exactly a /16. Given a /20 the same expression would produce
  #       a pattern matching the whole containing /16 -- sixteen times the address
  #       space actually allocated -- so Tomcat would honour an X-Forwarded-For header
  #       from any address in that range, not merely from this VPC's own load balancer.
  #       Since a client-supplied header is what that setting decides whether to
  #       trust, over-matching there is a trust widening rather than an untidiness.
  #       Alternatives Considered: deriving an exact regex from an arbitrary prefix
  #       length. Rejected because it reimplements CIDR arithmetic as string
  #       manipulation -- the third octet of a /20 is a bounded range, not a free
  #       wildcard -- and a subtle error there fails OPEN, silently trusting more
  #       than intended, which is the same failure mode by a longer route.
  #       Assumptions: both environments already use a /16 (dev 10.0.0.0/16, prod
  #       10.1.0.0/16), so this refuses nothing either root does today and refuses
  #       only the configurations under which the derived pattern would over-match.
  validation {
    condition     = can(regex("/16$", var.vpc_cidr))
    error_message = "vpc_cidr must use a /16 prefix in this environment root. The reporting service's trusted-proxy pattern is derived from the first two octets of this block, which is exact only for a /16; with any longer prefix that pattern would trust the whole containing /16 rather than this VPC alone."
  }
}

# WHY : Assumptions: the version is pinned to a MINOR release rather than to a
#       major line, and the database module supplies no default of its own, so this
#       is the single file an engine upgrade is an edit to. That is the property
#       worth having in a production root: the upgrade appears in a plan and in a
#       review as a named version change, rather than arriving because a default
#       elsewhere moved.
#       Trade-offs: a pinned minor is not picked up automatically, so a release
#       carrying a fix this deployment wants requires an explicit edit here. For a
#       database engine holding cardholder data that is the trade worth making --
#       an unreviewed engine change during a nightly batch window is a worse
#       outcome than a deliberate one made a release later.
# WHY : ⚠️ Assumptions: the development root's reasoning about this input does NOT
#       apply here, and the difference is easy to carry over by mistake. There, the
#       release named has to be one that supports scaling to zero capacity, because
#       that root sets its capacity floor to zero. This root forbids a zero floor,
#       so NO scale-to-zero release floor constrains the value chosen here; what
#       constrains it is that the pinned provider accepts it and that
#       aurora_parameter_group_family below names the matching major line.
#       Assumptions: this input carries NO `validation`, and the omission is a
#       decision rather than an oversight -- the reasonable-looking alternative is
#       a numeric-shape check, and it is declined because
#       infra/modules/aurora-postgresql already asserts exactly that shape on the
#       `engine_version` it receives, and additionally cross-checks that this
#       version's major line matches the parameter-group family below. Adding the
#       same regex here would be the second statement of one rule, which is the
#       drift the capacity inputs above were just repaired for. The two remaining
#       questions -- which releases exist, and which are still supported -- are
#       ones no regex can answer, and .github/workflows/infra-ci.yml asserts this
#       pin against its support review horizon separately.
# WHY : ⚠️ Refactoring Rationale: this default was "16.6" while terraform.tfvars
#       pinned "16.8" and this description already offered 16.8 as its example. The
#       tfvars value wins whenever it is passed, so the divergence was invisible in
#       every ordinary plan -- and 16.6's Aurora STANDARD SUPPORT ended on
#       2026-05-31, so the one path that reached the default reached an unsupported
#       release, in the production root. That path is not hypothetical: a
#       `-var-file` omission, a plan run from a scratch copy of the root, or a
#       `terraform console` session all resolve the default, and the outcome is a
#       cluster force-upgraded on Aurora's schedule or attracting Extended Support
#       charges. Both values now name the reviewed long-term-support pin, and
#       .github/workflows/infra-ci.yml's "Verify the Aurora engine pin against its
#       support review horizon" gate asserts the DEFAULT against the tfvars pin as
#       well as the marker date, so the two cannot part again without failing the
#       build.
#       Alternatives Considered: removing the default so the value is required from
#       the caller, which makes divergence structurally impossible. Rejected because
#       every other input in this file carries a reviewed default, and a required
#       input here would make the root unplannable without a var-file -- which the
#       documentation-generation and lint gates exercise.
variable "aurora_engine_version" {
  description = "Aurora PostgreSQL engine version for the production cluster, forwarded to the database module, which requires the value and supplies no default. Must be a numeric version such as 16.8 -- not an engine name and not a parameter-group family -- and its major line must match aurora_parameter_group_family, which that module verifies. Defaults to the reviewed long-term-support pin this root's terraform.tfvars sets, so an omitted tfvars cannot select an unsupported release."
  type        = string
  default     = "16.8"
}

# WHY : Assumptions: this and aurora_engine_version above are ONE decision made in
#       two values, and an engine upgrade has to move both together. The database
#       module refuses a pair whose major lines disagree, comparing the leading
#       component of the version against this family's trailing digits, so a
#       half-finished upgrade is rejected at plan time rather than producing a
#       cluster running one major line under a parameter group written for another.
#       That cross-check is the reason no `validation` is repeated here: the rule
#       needs BOTH values to evaluate, the module already holds it, and a copy in
#       this file could only restate it less completely.
variable "aurora_parameter_group_family" {
  description = "Aurora PostgreSQL cluster parameter-group family whose trailing major must match aurora_engine_version's leading component, for example aurora-postgresql16 against 16.8. Forwarded to the database module, which rejects a mismatched pair."
  type        = string
  default     = "aurora-postgresql16"
}

# WHY : Assumptions: 35 is the SERVICE MAXIMUM rather than a round number, and
#       Aurora offers no way to switch automated backups off, so the floor is one
#       day rather than none. The maximum is taken here for the reason recorded on
#       skip_final_snapshot above: this cluster's contents are the posted
#       transactions, accrued interest and updated masters of every night since
#       cutover, and nothing in this repository can regenerate them.
#       Trade-offs: retaining the maximum bills for backup storage proportional to
#       the cluster's size for five weeks. Accepted because the alternative is a
#       recovery window shorter than the interval at which a statement discrepancy
#       is noticed and reported, which would make the backups present but useless
#       for the failure they exist to cover.
#       ⚠️ Assumptions: this value MATCHES development deliberately and is not an
#       axis the two roots may differ on. Specification section 0.4.1.6 enumerates a
#       closed set of per-environment axes and its only retention entry is log
#       retention days; reading "sizing and retention" as an open licence is what
#       let this value drift apart from development's once already. No `validation`
#       is repeated here because the database module already bounds the input it
#       receives to the service's 1-to-35 range.
variable "aurora_backup_retention_period" {
  description = "Days of automated backups the production cluster retains, forwarded to the database module. Aurora cannot disable automated backups, so 1 is the floor and 35 the service maximum; there is no value here meaning none."
  type        = number
  default     = 35
}

# WHY : Assumptions: the backup window and the nightly chain act on the SAME
#       writer, and the chain is what drives this cluster toward its capacity
#       ceiling. Overlapping them makes the chain's duration depend on how much
#       data the backup is copying, which is the kind of coupling that stays
#       invisible until it surfaces as an intermittent timeout on the one workload
#       with the least room to run long. 07:00 sits clear of the 02:00 chain and of
#       the window a retried trigger could still start it in.
#       Alternatives Considered: leaving this unset and letting the service assign
#       a window, which the database module permits by accepting null. Rejected --
#       an assigned window is chosen with no reference to the batch schedule, so
#       the overlap this value exists to prevent would become a matter of luck, and
#       the disjointness main.tf asserts could not be asserted at all.
#       Assumptions: no `validation` is repeated here because the database module
#       already requires the hh:mm-hh:mm shape, with no day-of-week prefix, on the
#       input this root forwards. main.tf's own window-disjointness check parses
#       the same string and depends on that shape, so the module's rule is what
#       keeps that parse well defined.
variable "aurora_preferred_backup_window" {
  description = "Daily UTC window in which the production cluster's automated backups are taken, of the form hh:mm-hh:mm with no day-of-week prefix. Must stay clear of the window batch_schedule_expression can start the nightly chain in, which terraform_data.batch_window_disjoint in main.tf asserts."
  type        = string
  default     = "07:00-08:00"
}

# WHY : Assumptions: maintenance is the more consequential of the two windows to
#       place, because it may fail the cluster over rather than merely competing
#       for capacity. A failover during the chain aborts in-flight loader tasks and
#       leaves the online-write bracket engaged until the finalizer clears it, so
#       this window is kept clear of both the chain's start window and the backup
#       window, and main.tf asserts the first of those.
#       Assumptions: no `validation` is repeated here because the database module
#       already requires the day-prefixed ddd:hh:mm-ddd:hh:mm shape. That prefix is
#       load-bearing rather than cosmetic: main.tf reads the hour out of this value
#       at a different index than it uses for the backup window above, precisely
#       because this form carries a day and that one does not.
variable "aurora_preferred_maintenance_window" {
  description = "Weekly UTC maintenance window for the production cluster, of the form ddd:hh:mm-ddd:hh:mm such as sun:09:00-sun:10:00. Kept outside both the nightly batch start window and aurora_preferred_backup_window, because maintenance may fail the cluster over and abort in-flight batch tasks."
  type        = string
  default     = "sun:09:00-sun:10:00"
}

# WHY : Assumptions: 02:00 is chosen so the chain runs after the business day it
#       posts and still finishes clear of the 07:00 backup window, and the value is
#       IDENTICAL to development's on purpose. A trigger that fired at a different
#       hour in one environment would make the two environments' batch windows
#       differ, and the disjointness main.tf asserts against the backup and
#       maintenance windows would then be asserting a different arrangement in each
#       root -- which is a topology difference rather than the sizing difference the
#       two roots are permitted.
#       ⚠️ Refactoring Rationale: this input DOES carry a `validation`, and it is the
#       one member of this group that needs one even though the scheduler module
#       already requires the cron form. The reason is that this root parses the
#       value ITSELF: main.tf recovers the schedule's fields by trimming the cron
#       wrapper off this string, splitting what remains, and calling `tonumber` on
#       the hour field to assert the start window is disjoint from the backup and
#       maintenance windows. Given `rate(1 day)` the trim matches nothing, the
#       split yields a non-numeric token, and the failure that reaches the operator
#       is a conversion error naming a `tonumber` parameter -- not the module's
#       clear message, because the module's block never gets to run. It surfaces
#       during the graph walk, after the ephemeral password resources have already
#       been opened, and it names neither this input nor this file.
#       Assumptions: the guard therefore belongs here rather than being left to the
#       module, because what it protects is this root's own arithmetic rather than
#       the module's argument. It is deliberately the WEAKEST check that makes that
#       parse well defined -- the wrapper and a numeric hour field -- so the cron
#       grammar itself stays the scheduler's business and this file does not become
#       a second, partial statement of it.
variable "batch_schedule_expression" {
  description = "EventBridge Scheduler cron expression that starts the nightly batch chain, of the form cron(...) in UTC; the rate(...) and at(...) forms are rejected. main.tf trims the wrapper and reads the hour field to assert the start window is disjoint from the Aurora backup and maintenance windows, so the wrapper and a numeric hour are required for that check to be evaluable at all."
  type        = string
  default     = "cron(0 2 * * ? *)"

  validation {
    condition = (
      startswith(var.batch_schedule_expression, "cron(") &&
      endswith(var.batch_schedule_expression, ")") &&
      can(tonumber(split(" ", trimsuffix(trimprefix(var.batch_schedule_expression, "cron("), ")"))[1]))
    )
    error_message = "batch_schedule_expression must be a cron(...) expression whose second field is a numeric hour, for example cron(0 2 * * ? *). The rate(...) and at(...) forms are not accepted: main.tf reads the hour out of this expression to prove the batch start window does not overlap the Aurora backup and maintenance windows, and it cannot do that for a schedule with no hour field."
  }
}

# WHY : Refactoring Rationale: this input exists so the root can NARROW the scheduler
#       module's delivery-age default, which is 86400 -- the top of the accepted range.
#       That default is well argued in the module: it keeps maximum_retry_attempts, and
#       not the age bound, as the setting that governs how many delivery attempts are
#       made. But it also means a delivery that keeps failing may succeed at any point in
#       the following TWENTY-FOUR HOURS, and a retried delivery starts the chain whenever
#       it lands. That silently discards the entire reason 02:00 was chosen: the chain
#       could begin inside the backup window, or inside the Sunday maintenance window, on
#       exactly the nights when something is already wrong.
# WHY : Assumptions: 3600 is chosen to preserve the module's five attempts while keeping
#       the start bounded. EventBridge Scheduler's retries are exponentially backed off in
#       the seconds-to-minutes range, so five attempts complete far inside an hour; the age
#       bound therefore still does not govern the attempt count, which is the property the
#       module's default was protecting. What changes is only the worst-case START time,
#       from 02:00-plus-24h to 02:00-plus-1h -- which terraform_data.batch_window_disjoint
#       in main.tf then asserts against both windows. This value is identical to the dev root's because
#       the two roots are required to differ only in sizing and retention and never in topology,
#       and a trigger that can start at a different hour in one environment is a topology difference.
# WHY : Trade-offs: a delivery failure lasting longer than an hour now goes to the
#       dead-letter queue instead of continuing to retry into the next day. That is the
#       better outcome and not a loss of coverage: the queue is monitored, whereas a
#       chain that starts at 09:00 because delivery recovered late looks like a successful
#       run while colliding with maintenance. Missing one night loudly beats running it at
#       the wrong hour quietly.
variable "batch_schedule_maximum_event_age_seconds" {
  description = "Outer bound, in seconds, on how long a failed delivery of the nightly batch trigger may keep being retried before it is sent to the dead-letter queue. Narrows the scheduler module's 86400 default so that a retried delivery cannot start the chain outside its intended window; must keep the start window clear of aurora_preferred_backup_window and aurora_preferred_maintenance_window, which terraform_data.batch_window_disjoint asserts."
  type        = number
  default     = 3600

  validation {
    # Assumptions: the 60-86400 range is the pinned provider's, and it is restated here
    #   rather than deferred to the module so a bad value fails naming THIS variable, in
    #   the file an operator edited. The whole-hour requirement is this root's own: the
    #   window gate in main.tf reasons in whole hours, so a value that is not a whole
    #   number of hours would make its arithmetic silently approximate.
    condition     = var.batch_schedule_maximum_event_age_seconds >= 60 && var.batch_schedule_maximum_event_age_seconds <= 86400 && var.batch_schedule_maximum_event_age_seconds % 3600 == 0
    error_message = "batch_schedule_maximum_event_age_seconds must be a whole number of hours in seconds, from 3600 to 86400 inclusive, because the batch start-window check in main.tf reasons in whole hours."
  }
}

# WHY : Trade-offs: the check below refuses the tag `latest` outright, and refusing a
#       tag that every registry accepts is the non-obvious half of this input. A
#       mutable tag makes a registered task definition mean something different
#       tomorrow than it meant when it was reviewed, and it leaves a rollback unable
#       to name what it is rolling back TO -- both revisions would say `latest`. The
#       cost is that a hand-run deployment must supply a real tag rather than the
#       convenient one; the seven-character floor is set so an abbreviated commit SHA
#       clears it.
#       Assumptions: the deployment workflow supplies the commit SHA, so the tag is
#       already unique per revision without anyone choosing one. In this environment
#       image_digests below is populated as well, and the ECS service module then
#       deploys by digest -- this input remains the tag those images are PUSHED under.
variable "image_tag" {
  description = "Immutable image tag applied to the ten deployable ECR repositories this deployment builds, normally the source commit SHA supplied by the OIDC deployment workflow. The tag latest is rejected: it cannot identify a revision to roll back to. The mirrored telemetry collector is not one of them: it is a cached third-party image and carries its own upstream version tag."
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[A-Za-z0-9][A-Za-z0-9._-]{6,127}$", var.image_tag)) && lower(var.image_tag) != "latest"
    error_message = "image_tag must be a 7-128 character explicit tag and must not be latest; the deployment workflow supplies the commit SHA."
  }
}

# WHY : Assumptions: this string is not descriptive metadata -- it becomes a subject
#       condition in the publication role's trust policy, so it is the value that
#       decides WHICH repository may assume that role. The owner/name shape is checked
#       for that reason: a bare repository name would build a condition matching
#       nothing and fail closed, which is merely confusing, but a value carrying a
#       wildcard would build one matching more repositories than intended, which is a
#       trust widening that an apply reports as success.
variable "github_repository" {
  description = "GitHub repository in owner/name form whose protected prod environment may assume the SPA publication role. Becomes a subject condition in that role's trust policy, so it is what decides which repository can publish to this environment."
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must be an owner/name pair such as example/carddemo."
  }
}

# WHY : Alternatives Considered: looking the provider up with a data source instead of
#       accepting its ARN, which would remove this input. Rejected because an IAM OIDC
#       provider is account-scoped and single-instance, so a lookup would make this
#       root fail with a not-found error against a data source rather than naming the
#       prerequisite an operator has to run first -- and infra/bootstrap is what
#       creates it, deliberately in a separate root run once per account.
#       Assumptions: the check pins the provider URL to GitHub's own issuer rather than
#       accepting any OIDC provider ARN. Passing a provider for a different identity
#       provider would produce a trust policy that federates a third party into this
#       account, and every individual field of such an ARN would look well formed.
variable "github_oidc_provider_arn" {
  description = "ARN of the account-scoped GitHub Actions OIDC provider created by infra/bootstrap. Must name the token.actions.githubusercontent.com issuer specifically; any other provider ARN would federate a different identity provider into this account."
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^arn:[a-z0-9-]+:iam::[0-9]{12}:oidc-provider/token\\.actions\\.githubusercontent\\.com$", var.github_oidc_provider_arn))
    error_message = "github_oidc_provider_arn must identify the token.actions.githubusercontent.com IAM OIDC provider."
  }
}

# WHY : Assumptions: this one window governs EVERY secret the deployment generates, and
#       the inventory is stated because "generated credentials" is too vague to check.
#       It reaches the FIVE purpose secrets this root creates directly -- the card
#       selector key, the TWO pairwise internal-identity keys (authorization and
#       transaction), the pagination-cursor key and the reporting-artifact key -- plus
#       the per-service database credentials the secrets module creates and the Cognito
#       seed-user secrets.
# WHY : ⚠️ Refactoring Rationale: this inventory said SIX and led with a messaging HMAC
#       key. No such resource exists in this root: the withdrawal note in main.tf above
#       the card-selector resource records that the ephemeral generator, the secret and
#       its write-only version went together with the injection when the one Spring bean
#       that read the property was deleted. Five aws_secretsmanager_secret resources are
#       declared. An inventory stated to be checkable and then not checked is worse than
#       a vague one, because the reader who does check it finds a credential this window
#       supposedly governs and cannot locate the resource -- so the count is now asserted
#       by the "Verify hand-written Terraform prose counts against the declarations" gate
#       in .github/workflows/infra-ci.yml against those declarations.
# WHY : ⚠️ Refactoring Rationale: this description named "database, TLS and Cognito
#       credentials". There is NO TLS secret: the service-certificate feature it referred
#       to is withdrawn, and neither root creates a secret for listener material. The
#       word survived the feature, which is the kind of leftover that has a reader
#       looking for a resource that does not exist.
variable "secret_recovery_window_in_days" {
  description = "Secrets Manager recovery window, in days, applied to every secret this deployment generates: the five purpose secrets this root creates -- card-selector, the two pairwise internal-identity keys, pagination-cursor and reporting-artifact -- plus the per-service database credentials from the secrets module and the Cognito seed-user secrets."
  type        = number
  default     = 30

  validation {
    condition     = var.secret_recovery_window_in_days >= 7 && var.secret_recovery_window_in_days <= 30
    error_message = "Production secret_recovery_window_in_days must be 7-30 days."
  }
}

# WHY : Refactoring Rationale: `rotation_automatically_after_days` used to stand
#       here and was forwarded to infra/modules/secrets. It was removed with the
#       module-owned rotation function it configured: that module implements no
#       rotation and creates no function, and the only rotation this package owns
#       is KMS KEY rotation in the kms module. An interval with no function to
#       run it configures nothing, and tflint's terraform_unused_declarations
#       rule would report the declaration once main.tf stopped passing it. A
#       deployment that brings its own rotation function reintroduces this input
#       alongside a `rotation_lambda_arn`, which the module accepts as a pair.

# WHY : Refactoring Rationale: this input was `default = []` and terraform.tfvars set
#       it to `[]` as well, so PRODUCTION provisioned a notification topic, thirteen
#       alarms and every alarm action pointing at it -- with ZERO SUBSCRIBERS. Every
#       alarm would have fired correctly into nothing. That is the worst shape of
#       monitoring failure, because the dashboards, the alarms and the topic all exist
#       and look complete, so the gap is invisible until an incident is missed. The
#       default is REMOVED, making the input required: a production plan cannot now
#       succeed without a delivery target.
# WHY : Assumptions: the value is supplied OUT OF BAND and never committed. It is not
#       a secret, but an operator or on-call address is personal data and the
#       project's constraint is that the repository carries no environment-specific
#       identity of that kind, so terraform.tfvars no longer names it at all. Being a
#       required variable is what makes that safe rather than fragile: because it has
#       no default, .github/workflows/infra-ci.yml's input-closure guard obliges
#       deploy.yml, infra-ci.yml and docs/runbooks/deploy.md to supply and document it
#       together, so an operator following the runbook exactly reaches a plan that
#       works, and one who forgets is stopped at plan rather than at the first missed
#       page.
# WHY : Alternatives Considered: (1) keeping the default and adding a non-empty
#       validation. Rejected as dishonest bookkeeping -- it produces the same
#       operational requirement while leaving the variable looking optional to the
#       closure guard, so the three sources that must name it would never be checked
#       against each other. (2) Provisioning a chat or pager integration here instead.
#       Rejected because every such target needs an endpoint URL bearing a workspace
#       or service token, which is precisely the material that may not enter this
#       tree; an address subscribed at apply time keeps the secret out. (3) Requiring
#       it in prod ONLY, leaving dev defaulted to an empty list. Rejected because the
#       two roots are required to be identical in SHAPE and to differ only in sizing
#       and retention -- a variable that is required in one root and optional in the
#       other is a shape difference, and .github/workflows/infra-ci.yml asserts the
#       two roots' required sets are equal, so the asymmetry fails the build. Dev
#       therefore supplies an address too; a development environment whose alarms
#       notify nobody is the same defect at lower stakes.
# WHY : Trade-offs: a subscription created this way requires the recipient to confirm
#       it before delivery begins, so provisioning the target is necessary but not by
#       itself sufficient; the runbook step that follows the apply is what closes it.
variable "alarm_email_endpoints" {
  description = "Email addresses subscribed to the production observability topic. REQUIRED and deliberately absent from terraform.tfvars: supply it out of band, because the repository carries no operator identity. At least one address must be given, so a production deployment cannot create alarms that notify nobody."
  type        = list(string)

  validation {
    condition     = length(var.alarm_email_endpoints) > 0
    error_message = "alarm_email_endpoints must name at least one recipient in production. A notification topic with no subscriber means every alarm fires into nothing, which is indistinguishable from working monitoring until an incident is missed. Supply the address out of band, for example TF_VAR_alarm_email_endpoints='[\"oncall@example.com\"]'."
  }

  validation {
    condition     = alltrue([for address in var.alarm_email_endpoints : can(regex("^[^@[:space:]]+@[^@[:space:]]+\\.[^@[:space:]]+$", address))])
    error_message = "Every alarm_email_endpoints entry must be a syntactically valid email address."
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

# WHY : Assumptions: two of the three rules below are worth stating because they are
#       not shape checks. Duplicates are refused because CloudFront rejects a repeated
#       alias when it builds the distribution, and the comparison is done on the
#       lower-cased name since DNS is case-insensitive while a list is not -- so
#       `App.example.com` beside `app.example.com` is one alias twice, and only the
#       normalised comparison sees it. A leading wildcard label IS permitted, because a
#       wildcard certificate legitimately serves one and refusing it would rule out a
#       valid deployment for tidiness.
#       Assumptions: the list must be non-empty. A distribution with no alias is
#       reachable only at its generated CloudFront domain, which no certificate in
#       cloudfront_acm_certificate_arn covers, so the pair would be inconsistent.
variable "cloudfront_aliases" {
  description = "Non-empty list of bare DNS names the SPA distribution serves, optionally with a leading wildcard label. Every entry must be covered by cloudfront_acm_certificate_arn and is forwarded unchanged to cloudfront-spa; entries are compared case-insensitively so one name cannot appear twice."
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

# WHY : Refactoring Rationale: two required inputs -- `service_tls_certificate`
#       and `service_tls_private_key` -- used to stand here, each a PEM scalar
#       this root forwarded to infra/modules/secrets so that module could store
#       it. Both were removed. A Terraform variable can only be given a value
#       from a committed tfvars file, a committed default or a CI-carried
#       environment variable, so a variable that accepts key material is a
#       channel for committing key material however carefully it is used;
#       `sensitive = true` changed only how a plan RENDERED the value, never
#       whether a tfvars or state file could hold it.
#       Refactoring Rationale: this went on to say that main.tf "now GENERATES
#       the pair with tls_private_key/tls_self_signed_cert and writes it straight
#       into two root-owned Secrets Manager entries and into
#       aws_acm_certificate". That intermediate arrangement is withdrawn in full:
#       it moved the key out of a VARIABLE and into STATE, which is the same
#       material in a place that is equally readable and harder to notice. All
#       four resources are deleted and the tls provider requirement with them.
#       Each task now mints its own listener key pair and self-signed certificate
#       before the JVM starts (config/docker/generate-listener-material.sh), so
#       there is no shared key at all -- not in a tfvars file, not in state, and
#       not in a task definition.
#       Alternatives Considered: keeping the inputs nullable and letting a
#       supplied pair win over the generated one. Rejected: an optional channel
#       for key material is still a channel. The operator-issued case is served
#       by var.alb_certificate_arn, which names a certificate the operator has
#       already imported into ACM out of band -- the service built to custody a
#       private key and never re-export it.
#       Trade-offs: an environment that requires a certificate from its own
#       authority now needs that out-of-band ACM import rather than a variable.
#       Accepted, because the listeners this pair serves are internal to the
#       VPC and fronted by the load balancer, so the trust decision is the
#       ALB's -- which is why a generated self-signed leaf is acceptable here
#       and would not be at the edge.

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
# WHY : ⚠️ Refactoring Rationale: this description said the boundary applied to "every
#       role this deployment creates" while SEVEN role resources -- ten effective role
#       instances per environment -- received no boundary at all. Only
#       infra/modules/ecs-service attached one. The description was not corrected to
#       match the narrower reality; the reality was corrected to match the description,
#       because a documented ceiling that is not attached is worse than an absent one:
#       an auditor reading this variable would record the control as present. The
#       measured inventory is now nine role RESOURCES, all nine carrying
#       `permissions_boundary`, and it is reproducible -- every `aws_iam_role` block
#       under infra/ has the argument.
# WHY : Assumptions: the ten effective instances per environment are: this root's
#       `spa_publication` (1) and `lambda` (3 -- online-write, database-admin,
#       dataset-retention); `modules/network` flow-log delivery (1);
#       `modules/eventbridge-scheduler` invocation (1); and
#       `modules/step-functions-batch` execution (4 -- one per machine in
#       `local.machines`: daily, adhoc, dataset, authz). The `modules/ecs-service`
#       execution and task roles are additional and were already bounded.
# WHY : Assumptions: the three modules that create roles now take this same value as
#       their own `permissions_boundary_arn` input, and each asserts the ARN names THIS
#       account in a `lifecycle` precondition. A cross-account boundary ARN is accepted
#       by IAM and then bounds nothing, because the policy it names does not resolve --
#       so without that check the control could be silently inert even once attached.
variable "permissions_boundary_arn" {
  description = "ARN of the same-account customer-managed IAM policy used as the permissions boundary on every role this deployment creates -- all nine aws_iam_role resources under infra/, which is ten effective role instances per environment plus the two per ECS service. Passed into every module that creates a role, each of which asserts the ARN belongs to this account. Supplied by the operator or the deploy workflow; never created here."
  type        = string

  validation {
    condition     = can(regex("^arn:[a-z0-9-]+:iam::[0-9]{12}:policy/[A-Za-z0-9+=,.@_/-]+$", var.permissions_boundary_arn))
    error_message = "permissions_boundary_arn must be an anchored customer-managed IAM policy ARN, shaped arn:<partition>:iam::<account-id>:policy/<policy-name>."
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
  description = "Secrets Manager ARN of the environment-separated HMAC key the data-migration image uses for protected-field fingerprints. Supplied by the operator; never created or rotated by this configuration. The secret VALUE must be canonical standard base64 decoding to at least 32 bytes, which the image enforces by refusing to run on weaker material; docs/runbooks/deploy.md gives the creation command."
  type        = string

  validation {
    condition     = can(regex("^arn:[a-z0-9-]+:secretsmanager:[a-z0-9-]+:[0-9]{12}:secret:[A-Za-z0-9/_+=.@-]+-[A-Za-z0-9]{6}$", var.mask_hmac_secret_arn))
    error_message = "mask_hmac_secret_arn must be an anchored Secrets Manager secret ARN including the six-character suffix the service appends, shaped arn:<partition>:secretsmanager:<region>:<account-id>:secret:<secret-name>-<suffix>. Copying the name without that suffix is the usual cause of this failure."
  }
}

# -----------------------------------------------------------------------------
# The key that encrypts the fingerprint secret
# -----------------------------------------------------------------------------
# WHY : ⚠️ Refactoring Rationale: this input did not exist, and its absence made the
#       secret above unprovable. The runbook's creation command named no key, so the
#       secret landed on the account's AWS-MANAGED secretsmanager key -- whose policy
#       admits any principal in the account that holds the matching Secrets Manager
#       permission, which is a far wider set than the one task that reads it. The
#       root additionally granted decrypt on the CardDemo Secrets CMK only, so it
#       could neither prove which key protected the secret nor grant the one that
#       actually did: the decrypt succeeded through the managed key's own policy
#       rather than through anything written here.
# WHY : Assumptions: the ARN is SUPPLIED rather than defaulted to the CMK this root
#       creates, because the secret it protects is created out of band and may
#       legitimately predate this deployment. Naming the key explicitly is what lets
#       the root grant exactly it, and what lets a reviewer see which key holds the
#       fingerprint material without reading the secret's metadata.
# WHY : Alternatives Considered: creating the secret here under module.kms's secrets
#       key, which would remove the input entirely. Rejected for the reason recorded
#       on mask_hmac_secret_arn above -- a key this configuration could rewrite would
#       invalidate every fingerprint the previous apply produced, and keeping the
#       material outside this configuration keeps it outside this state file.
# WHY : Trade-offs: one more prerequisite, and a customer-managed key costs more than
#       the managed one. Accepted because the managed key cannot be granted narrowly,
#       cannot be given a rotation schedule this deployment states, and leaves no
#       record in this configuration of what protects the material.
variable "mask_hmac_secret_kms_key_arn" {
  description = "ARN of the customer-managed KMS key that encrypts mask_hmac_secret_arn. Supplied by the operator alongside the secret; never created here. The data-migration task role is granted kms:Decrypt on exactly this key, through Secrets Manager, so a secret protected by a different key fails to decrypt rather than succeeding through a wider key policy."
  type        = string

  validation {
    condition     = can(regex("^arn:[a-z0-9-]+:kms:[a-z0-9-]+:[0-9]{12}:key/[a-f0-9-]+$", var.mask_hmac_secret_kms_key_arn))
    error_message = "mask_hmac_secret_kms_key_arn must be an anchored customer-managed KMS key ARN, shaped arn:<partition>:kms:<region>:<account-id>:key/<key-id>. The AWS-managed alias alias/aws/secretsmanager is deliberately not accepted: it cannot be granted to one principal."
  }

  # WHY : Assumptions: a key in another account or another Region cannot decrypt a
  #       secret Secrets Manager holds here, so the two ARNs are compared rather than
  #       each being validated in isolation. The mismatch is the realistic operator
  #       error -- copying a key ARN from a different environment -- and it would
  #       otherwise present at run time as an access denial on the batch path.
  validation {
    condition = (
      split(":", var.mask_hmac_secret_kms_key_arn)[3] == split(":", var.mask_hmac_secret_arn)[3] &&
      split(":", var.mask_hmac_secret_kms_key_arn)[4] == split(":", var.mask_hmac_secret_arn)[4]
    )
    error_message = "mask_hmac_secret_kms_key_arn must name a key in the same Region and account as mask_hmac_secret_arn."
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
        "aws-otel-collector",
      ], artifact)
    ])
    error_message = "Every image_digests key must name one of the ten ECR artifacts this deployment runs inside a task: the eight services, data-migration, and the mirrored telemetry collector every task runs as a sidecar. A key that names no repository would be silently ignored."
  }

  # WHY : Refactoring Rationale: `aws-otel-collector` is an admissible key again,
  #       restored with the collector sidecar infra/modules/ecs-service composes. It
  #       names the mirror repository infra/modules/ecr provisions from
  #       third_party_mirror_repository_names -- a third-party image this deployment
  #       caches rather than builds -- so recording its digest here is what makes the
  #       task definition state WHICH collector bytes ran, which its mirror tag alone
  #       cannot answer after the fact.
  # WHY : Assumptions: production's own posture makes the collector digest effectively
  #       mandatory in practice even though this variable leaves every key optional --
  #       infra/modules/ecs-service refuses a mutable tag for the application image
  #       here, and an operator who records nine digests and omits the tenth is
  #       running a pinned application beside an unpinned sidecar. The runbook's
  #       mirror procedure therefore prints the pushed digest for this key.
  # WHY : Assumptions: `ui` is absent for a different and unchanged reason -- the
  #       browser bundle is published to S3 and its image runs no ECS task, so a
  #       digest for it would configure nothing. That is why ten admissible keys sit
  #       against the eleven repositories the ecr module provisions, ten deployables
  #       plus the one mirror.
}

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

# WHY : Assumptions: this is the second half of the pair the comment above
#       alb_certificate_arn reasons about -- the certificate ARN names the listener
#       credential, and this names the identity API Gateway verifies against it. The
#       value is required to be BARE for that reason: it is used as a TLS server name
#       rather than as a URL, so a scheme, a port or a path would produce a name that
#       can never match the certificate's subject, and the failure would appear as a
#       handshake error on the private integration rather than as a bad input here.
#       A wildcard is refused for the same reason -- a server name is one host.
variable "internal_service_domain_name" {
  description = "Bare DNS name covered by alb_certificate_arn, with no scheme, port, path or wildcard. Forwarded to the alb module as its certificate identity and to api-gateway-http as the TLS server name the private integration verifies, which is why it must be a host and not a URL."
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$", var.internal_service_domain_name))
    error_message = "internal_service_domain_name must be a bare DNS hostname covered by the ALB certificate, with no scheme, port, wildcard or path."
  }
}
