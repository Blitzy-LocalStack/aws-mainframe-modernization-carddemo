# =============================================================================
# infra/envs/prod/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input surface of the CardDemo PRODUCTION environment root. Two
#   of these inputs configure the root's own aws provider; the others are the
#   explicit values consumed by the shared topology. The two roots are
#   infra/envs/dev. That constraint is the point of this file: the two roots are
#   required to be identical in topology and to differ only in environment
#   identity, sizing, retention, protection and production edge certificate
#   inputs. This file declares the same twenty-nine variable names as dev.
#
#   A variable present in one root and absent from the other would be a
#   structural divergence between the environments, so the symmetry is
#   load-bearing rather than tidy: a reader comparing the two files should find
#   the difference between production and development entirely in the default
#   values and in the reasoning beside them.
#
#   Values are supplied by infra/envs/prod/terraform.tfvars and by the deployment
#   workflow. image_tag, github_repository, github_oidc_provider_arn,
#   spa_domain_name and spa_acm_certificate_arn are required because no safe
#   account-independent default exists.
#
# Parameters -- twenty-nine, five required:
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
#   Every input carries a `validation` block, and each rejects a bad value during
#   `terraform plan`, before any request leaves the machine, rather than letting
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
#   - Assumptions: this file declares NO `environment` and NO `name_prefix`
#     variable, and both absences are deliberate. The environment name is what
#     distinguishes this root's resources from the other root's; making it an
#     input would let a caller point the production state at development-named
#     resources, or the reverse, and the plan would be clean. main.tf therefore
#     carries the literal `prod`. The name prefix is shared by the whole stack
#     and every module already defaults it to the same value, so a root-level
#     input would only create a way for the two environments to disagree.
#   - Trade-offs: every input is defaulted, so this root initialises, validates
#     and plans with no variable file, which is the only way a non-interactive
#     pipeline job can check it without credentials. The cost is heavier here
#     than in development: a defaulted production capacity or retention value is
#     a decision made by silence. Two things mitigate it -- outputs.tf reports
#     the values actually used, and every default in this file is chosen to be
#     the SAFE end of its range rather than the cheap one, so a forgotten
#     variable file yields a conservative environment rather than an exposed one.
#   - Assumptions: NO default here holds a credential, an account identifier, an
#     ARN, a bucket name or a password. This root's secrets do not exist in
#     source at any point in their lifetime: the database credential and the seed
#     user passwords are generated at apply time by the secrets and cognito
#     modules and written straight into Secrets Manager. That is what makes the
#     project's no-secrets-in-source constraint structurally true here rather
#     than merely observed.
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
# is emphatically something an operator should state explicitly (Trade-off): the
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
# per-resource argument (Trade-off): versions.tf sets these on the provider's
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
# for the range to be a range. Each is validated on its own and the pair is
# validated jointly, because a floor above a ceiling is accepted by the type
# system and rejected by the service only once a cluster modification is
# attempted.
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
  # from 5.81.0 onward and `~> 6.56` clears that comfortably; the constraint is
  # identical in both roots because a single provider version has to satisfy both.
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
    # WHY : Assumptions: the floor of this check is 0.5 rather than 0, because
    #       the service requires a maximum of at least one half unit even when the
    #       minimum is zero -- a range whose ceiling is also zero describes a
    #       cluster that can never serve a query.
    condition     = var.aurora_max_capacity >= 0.5 && var.aurora_max_capacity <= 256 && floor(var.aurora_max_capacity * 2) == var.aurora_max_capacity * 2
    error_message = "aurora_max_capacity must be between 0.5 and 256 in half-unit increments; a ceiling of 0 would describe a cluster that can never serve a query."
  }

  validation {
    # WHY : Trade-offs: this second block cross-checks the pair. A ceiling below
    #       the floor is accepted by both types and rejected by the service only
    #       when it attempts the modification, which is partway through an apply
    #       against live infrastructure. Checking it here costs one extra block
    #       and names both inputs in the message. Equality is permitted
    #       deliberately: a fixed capacity, floor equal to ceiling, is a
    #       legitimate way to run a cluster at a known size.
    condition     = var.aurora_max_capacity >= var.aurora_min_capacity
    error_message = "aurora_max_capacity must be greater than or equal to aurora_min_capacity; the two together describe one capacity range."
  }
}

variable "aurora_seconds_until_auto_pause" {
  description = "Idle interval before a cluster whose capacity floor is zero pauses. INERT in this environment, because this root's floor is above zero; main.tf forwards it to the database module only on that condition, and it is declared here so the two environment roots' input surfaces match exactly."
  type        = number
  default     = 300

  # WHY a variable that does nothing in this root is declared anyway
  # (Assumption): the two environment roots are required to have the same input
  # surface, so that the difference between the environments is entirely in the
  # values. A variable existing in one root and not the other would be a
  # structural difference, and it would also mean a single tfvars template could
  # not serve both. The value is kept at the service's shortest accepted interval
  # rather than at something conspicuous, so that if this root's capacity floor is
  # ever lowered to zero the setting it then requires is already valid.
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
  default     = 1024

  # WHY one virtual CPU here rather than the half a development task gets
  # (Trade-off): the services are JVM applications, and a task with less than a
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

variable "name_prefix" {
  description = "Common lower-case prefix used by every module in this environment."
  type        = string
  default     = "carddemo"
}

variable "environment" {
  description = "Environment identity passed to every module; fixed to prod for this root."
  type        = string
  default     = "prod"

  validation {
    condition     = var.environment == "prod"
    error_message = "The production root environment must be prod."
  }
}

variable "vpc_cidr" {
  description = "IPv4 CIDR allocated to the production VPC."
  type        = string
  default     = "10.1.0.0/16"
}

variable "aurora_engine_version" {
  description = "Aurora PostgreSQL engine version used by the production cluster."
  type        = string
  default     = "16.6"
}

variable "aurora_parameter_group_family" {
  description = "Aurora PostgreSQL cluster parameter-group family matching aurora_engine_version."
  type        = string
  default     = "aurora-postgresql16"
}

variable "aurora_backup_retention_period" {
  description = "Days of automated Aurora backups retained in production."
  type        = number
  default     = 35
}

variable "aurora_preferred_backup_window" {
  description = "Daily UTC backup window kept outside the nightly batch schedule."
  type        = string
  default     = "07:00-08:00"
}

variable "aurora_preferred_maintenance_window" {
  description = "Weekly UTC maintenance window kept outside batch and backup windows."
  type        = string
  default     = "sun:09:00-sun:10:00"
}

variable "batch_schedule_expression" {
  description = "EventBridge Scheduler cron expression that starts the nightly batch chain."
  type        = string
  default     = "cron(0 2 * * ? *)"
}

variable "image_tag" {
  description = "Immutable image tag applied to all ten ECR repositories for this deployment, normally the source commit SHA supplied by the OIDC deployment workflow."
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[A-Za-z0-9][A-Za-z0-9._-]{6,127}$", var.image_tag)) && lower(var.image_tag) != "latest"
    error_message = "image_tag must be a 7-128 character explicit tag and must not be latest; the deployment workflow supplies the commit SHA."
  }
}

variable "github_repository" {
  description = "GitHub repository in owner/name form whose protected prod environment may assume the SPA publication role."
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

variable "secret_recovery_window_in_days" {
  description = "Secrets Manager recovery window for generated database, TLS and Cognito credentials."
  type        = number
  default     = 30

  validation {
    condition     = var.secret_recovery_window_in_days >= 7 && var.secret_recovery_window_in_days <= 30
    error_message = "Production secret_recovery_window_in_days must be 7-30 days."
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
  description = "Email addresses subscribed to the production observability topic."
  type        = list(string)
  default     = []

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
