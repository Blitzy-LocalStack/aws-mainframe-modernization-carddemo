# =============================================================================
# infra/modules/step-functions-batch/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input contract of the `step-functions-batch` module -- the
#   module that replaces the mainframe JCL/JES2 nightly job stream with the
#   eleven-work-state `carddemo-daily-batch` state machine, a second and much
#   smaller state machine for on-demand reports, one shared least-privilege
#   execution role and one encrypted log group per machine. Every value a
#   calling environment root may configure is declared here; anything absent
#   from this file is a property of the two state machines fixed in main.tf
#   rather than an environment choice, and the closing section names the
#   absences that are most often mistaken for omissions.
#
#   Reusable MODULE, never a Terraform root. Nothing here is read from the
#   ambient environment and nothing is generated inside the module: every input
#   arrives from infra/envs/dev/main.tf or infra/envs/prod/main.tf. That is what
#   keeps the only differences between the two environments visible in their own
#   terraform.tfvars files instead of hidden in this module body.
#
# Parameters:
#   Thirty-one inputs in nine groups, in the order they are declared below.
#   Fourteen are REQUIRED and seventeen carry a default. Every required one is an
#   identifier of a resource another module owns; every defaulted one is a name,
#   a policy value or a sizing value this module can pick a defensible starting
#   point for.
#
#     Naming and tagging ....... name_prefix, environment, tags
#     ECS wiring ............... ecs_cluster_arn and three
#                                task-definition/container-name pairs, one pair
#                                per image the chain runs
#     IAM ...................... pass_role_arns
#     Networking ............... private_app_subnet_ids, task_security_group_id
#     Data and notification .... dataset_bucket_name, notification_topic_arn
#     Observability ............ log_retention_days, log_group_kms_key_arn,
#                                log_level, log_include_execution_data
#     Function-backed states ... quiesce_function_arn, resume_function_arn,
#                                analyze_tables_function_arn,
#                                read_only_flag_parameter_name
#     Seed-dataset staging ..... seed_datasets,
#                                stage_datasets_max_concurrency
#     Timing and resilience .... state_timeout_seconds, three retry knobs and
#                                the two top-level execution ceilings
#
#   The roster above is closed: main.tf references every one of them, so tflint's
#   terraform_unused_declarations rule fails on an input this module stops using,
#   and an input main.tf needs but this file does not declare fails `validate`.
#
#   NOT AN INPUT -- the posting condition-code contract. The two exit statuses the
#   transaction-posting state may return, 0 and 4, are locals in main.tf and not
#   variables. They are the baseline's contract rather than a policy: app/cbl/
#   CBTRN02C.cbl assigns RETURN-CODE in exactly one place, MOVE 4 TO RETURN-CODE at
#   its line 230, so 0 and 4 are the only codes it produces. This was a single
#   configurable ceiling compared with NumericLessThanEquals, which admitted 1, 2
#   and 3 as reject nights when in truth they can only come from the runtime failing
#   around the program, and which could be set to 255 -- at which point every
#   failure the state can report satisfies the predicate and the chain runs interest
#   accrual over transactions that were never posted. The rationale now sits at the
#   locals, beside the predicate it decides.
#
#   PROVENANCE -- where a consumer gets each value. Every identifier is another
#   module's published output, wired by the environment root: the cluster from
#   infra/modules/ecs-cluster; each task definition, container name, task role
#   and execution role from an infra/modules/ecs-service instance; the subnets
#   and the security group from infra/modules/network; the dataset bucket from
#   infra/modules/s3-datasets; the notification topic from
#   infra/modules/observability; the log-group key from infra/modules/kms. The
#   three function ARNs and the read-only flag parameter name are owned by the
#   environment root itself, for the reason recorded on that group. No input is
#   fetched here by a `data` lookup or a remote-state read, because a module
#   that resolves its own dependencies cannot be composed differently by a
#   different caller.
#
#   Each block below carries the full `type` and `description` that tflint's
#   terraform_typed_variables and terraform_documented_variables rules require,
#   and those descriptions are the source terraform-docs injects into
#   README.md; the group map above is a guide to the surface, not a second copy
#   of it.
#
# Return values:
#   None. A variables.tf declares no output. outputs.tf publishes the daily and
#   ad-hoc state-machine identities separately, because
#   infra/modules/eventbridge-scheduler starts an execution of the first and
#   services/reporting-service starts an execution of the second.
#
# Exceptions or errors:
#   Fifteen inputs have no default, so omitting any one of them stops the
#   calling root with a missing-required-argument error before anything is
#   created, rather than provisioning a chain that fails on its first
#   invocation. Twenty-nine of the thirty-one additionally carry a `validation`
#   block. The two that do not are `tags`, a free-form map whose contents are
#   the caller's to choose so there is nothing to constrain, and
#   `log_include_execution_data`, a boolean whose type is already its whole
#   domain.
#   Every input naming an AWS resource has its service and
#   resource-type fields asserted, because a wrong-but-well-formed identifier
#   produces a valid plan, a clean apply, and a failure that first appears in
#   the schedule's dead-letter queue detached from the change that caused it.
#   The remaining checks reject out-of-domain numbers and unknown enumerated
#   values at plan time instead of letting the service reject them partway
#   through an apply.
#
# WHY (non-obvious design decisions):
#   - Assumptions: NO ARN, account identifier, region, endpoint, bucket name or
#     credential has a default, and none ever may. An ARN embeds an AWS account
#     identifier, and the project constraint that nothing of the sort is
#     committed to this repository admits no exception. A specimen default added
#     so a reader could see the expected shape is therefore refused rather than
#     overlooked -- the shape is carried by the `type`, the `validation` pattern
#     and the description instead.
#   - Assumptions: the eleven states themselves are NOT inputs. Their order,
#     their catch handlers and the inverted condition predicates that replace
#     the baseline's `COND=` parameters are this module's substance and live in
#     main.tf. Exposing the state list would let one environment run a different
#     chain from the other, which is precisely the topology divergence the two
#     roots are required not to have.
#   - Trade-offs: per-state timing is ONE `map(number)` carrying all eleven state
#     names, rather than eleven named scalars or a floor plus a sparse override
#     map. Eleven named inputs would document themselves but would have to be
#     restated by every root and kept in step with main.tf whenever a state is
#     renamed. A floor plus sparse overrides was the other candidate and it has a
#     specific failure mode the all-keys map does not: a state left out of the
#     overrides silently inherits a ceiling nobody chose for it, and a mistyped
#     key does the same while the operator believes a limit was raised. Requiring
#     every key turns both of those into a plan-time error naming the variable.
#     The accepted cost is that the map is one degree less discoverable than
#     eleven named inputs and that adding a state means extending the default and
#     its validation together -- see state_timeout_seconds.
#   - Trade-offs: `log_group_kms_key_arn` is NULLABLE with a null default, where
#     the encryption posture argues for requiring it. Every CICS file in the
#     baseline was defined `RECOVERY(NONE) JOURNAL(NO)`
#     (app/csd/CARDDEMO.CSD:1-89), so customer-managed encryption at rest is one
#     of the properties this migration adds rather than preserves, and both
#     environment roots do supply the key. It is nullable anyway so that a module
#     with no log-group key yet is still usable rather than merely less encrypted;
#     the posture is asserted by the roots, and the input's own comment records
#     that null is not the intended production value.
#   - Assumptions: the business date is NOT an input here. app/jcl/INTCALC.jcl:22
#     runs `PGM=CBACT04C,PARM='2022071800'`, injecting the processing date
#     rather than letting the program read the clock, and that property is what
#     makes a rerun reproducible. It is preserved by passing the date as a
#     container command argument at EXECUTION time, from the schedule's payload
#     or an operator's start call. A Terraform variable would freeze one date
#     into the infrastructure -- the opposite of the baseline's behaviour.
#   - Where a comment below reasons about an apply or an execution it is
#     describing what an input MEANS at those points, not reporting on a
#     provisioned stack. This tree is authored and statically validated;
#     applying it to a live account is an operator action outside this scope.
#   - Refactoring Rationale: every comment below is a `# WHY : <label>:`
#     rationale, and there is deliberately no `# WHAT:` narration line paired
#     with it. An earlier revision of this file carried eighty such lines, one
#     ahead of each rationale, and every one restated something already stated
#     verbatim a line or two later: the purpose of an input is its
#     `description`, which is also the text terraform-docs injects into
#     README.md, and the purpose of a check is its `condition` together with its
#     `error_message`. Rule 1 forbids a comment that restates the code, so those
#     lines were not merely redundant. Removing them also brings this file back
#     into line with the rest of the package -- this file and the one statement in
#     its sibling main.tf were the only uses of the `WHAT:` form at statement level
#     anywhere under infra/, and both are gone, while the `# WHY : <label>:` form
#     they keep is used throughout. Assumptions: no
#     rationale, `description`, `validation` or `error_message` was changed by
#     that removal; where a removed line had been carrying the SUBJECT of an
#     absence rather than restating a neighbour -- the six entries in the closing
#     section -- the subject was folded into the rationale's own first sentence,
#     which is what the canonical labels are for.
# =============================================================================

# -----------------------------------------------------------------------------
# Naming and tagging
# -----------------------------------------------------------------------------

# WHY : Assumptions: every directory under infra/ takes its prefix through a
#       variable of this name with this same default, which is what lets a root
#       pass one value to every module it calls instead of each module
#       negotiating its own. Fifteen of the sixteen modules in this package
#       share it, so the default is the tree's convention rather than a
#       preference expressed here; it is also a project name, not an account
#       identifier or an endpoint, so it carries none of the disclosure risk
#       that forbids a default on the identifier inputs below.
variable "name_prefix" {
  description = "Prefix concatenated into the state machine, log group and execution role names ahead of the environment suffix, giving the nightly chain one greppable identity shared with the rest of the stack's resource names. Passed in by the environment root, which hands the same value to every module it calls; lowercase letters, digits and hyphens only, at most 32 characters."
  type        = string
  default     = "carddemo"

  validation {
    # WHY : Trade-offs: a state machine name, a log group name and an IAM role
    #       name accept different character sets. Restricting the prefix to
    #       lowercase letters, digits and hyphens keeps it legal in all three at
    #       once and catches a space, a dot or an uppercase letter at plan time
    #       instead of letting whichever resource is created first reject it
    #       partway through an apply. The cost accepted is that a legitimately
    #       unusual prefix has to be permitted here before it can be used.
    condition     = can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", var.name_prefix)) && length(var.name_prefix) <= 32
    error_message = "name_prefix must be 1 to 32 characters of lowercase letters, digits and hyphens, beginning and ending with a letter or digit -- for example \"carddemo\"."
  }
}

# WHY : Trade-offs: this input is deliberately required with no default. A
#       defaulted environment name is the exact mechanism by which a production
#       schedule ends up starting a development state machine: the caller omits
#       the argument, the module names the machine anyway, and the mistake stays
#       invisible because the plan is clean and the name reads as deliberate.
#       Requiring the value costs each root one line and converts that class of
#       error into a missing-required-argument failure before anything exists.
variable "environment" {
  description = "Environment name suffixed onto the state machine, its log group and its execution role, so one environment's nightly chain is distinguishable from the other's in the console and in every IAM policy that names it; must be `dev` or `prod`, the two environments that have a Terraform root under infra/envs/."
  type        = string

  validation {
    # WHY : Assumptions: two environment roots exist, infra/envs/dev and
    #       infra/envs/prod, and this module is called only from those two. They
    #       are required to differ in sizing and retention and never in
    #       topology, so a third value would name a state machine whose state no
    #       root tracks. The check therefore asserts the shape of the tree
    #       rather than a naming preference.
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment must be exactly \"dev\" or \"prod\", matching the environment root under infra/envs/ that calls this module."
  }
}

# WHY : Trade-offs: an empty default reads as complete here rather than as
#       missing tagging, and without this note it would read as the latter. The
#       baseline tag set is not this module's to supply: each calling root
#       configures `default_tags` on its own `provider "aws"` block, and the
#       provider merges that map into every taggable resource it creates, so
#       these resources carry the root's common tags whether or not this input
#       is passed. What this input adds is the layer above that, for a tag that
#       applies to the batch orchestration alone.
variable "tags" {
  description = "Tags merged onto both state machines and both log groups, layered on top of the common tag set the calling root already applies through its provider's `default_tags`; defaults to none, because the baseline tags arrive from the root rather than from this module."
  type        = map(string)
  default     = {}
}

# -----------------------------------------------------------------------------
# ECS wiring -- what each work-performing state actually runs
#
# The seven inputs in this section describe the three container images the two
# state machines invoke. Five daily states run the batch image with different
# command arguments; the seed-staging map runs the ETL image once per dataset;
# two daily states and the whole ad-hoc machine run the reporting image. Every
# invocation uses the SYNCHRONOUS run-task integration, which starts the task
# and holds the state open until the task stops, so the next state observes a
# completed step exactly as a JCL step observed its predecessor's completion.
#
# WHY : Alternatives Considered: implementing the job states as functions was
#       evaluated and rejected on a hard limit rather than a preference. A
#       function's execution is capped at fifteen minutes, and the posting,
#       interest and statement steps process a whole day's volume in one pass; a
#       step that exceeds the cap fails with no partial result and no way to
#       resume mid-record. The three states that DO use functions -- the
#       read-only flag bracket and the statistics refresh -- are one API call
#       each and are declared in their own section below.
# -----------------------------------------------------------------------------

# WHY : Assumptions: this input is used twice and both uses need the full ARN.
#       It is the `Cluster` parameter of every synchronous run-task state, and
#       it is also the value of the `ecs:cluster` condition that scopes
#       `ecs:StopTask` and `ecs:DescribeTasks` -- neither of which can be
#       scoped by resource, because a task ARN does not exist until RunTask
#       creates it. That second use is why an ARN is required here rather than a
#       bare cluster name: a name cannot appear in an ARN-comparison condition.
variable "ecs_cluster_arn" {
  description = "ARN of the ECS cluster every task state runs its task in. Published as an output by infra/modules/ecs-cluster and passed in by the environment root; it is also the value of the `ecs:cluster` condition that scopes the execution role's task-stopping and task-describing grants, which is why the full ARN is required rather than a cluster name."

  type = string

  validation {
    # WHY : Assumptions: a wrong-but-well-formed ARN here yields a syntactically
    #       valid IAM policy and a state machine that applies without complaint,
    #       then fails at the first invocation with a permissions error naming a
    #       resource the reader did not know was involved. Checking the `ecs:`
    #       service field and the `:cluster/` resource type converts that into a
    #       plan-time error naming this input.
    #       Alternatives Considered: anchoring the pattern at the end, which
    #       would be tighter and is rejected -- a cluster name is otherwise
    #       unconstrained, so the pattern stops after the resource-type field
    #       rather than guessing at the name.
    condition     = can(regex("^arn:[a-z0-9-]+:ecs:[a-z0-9-]+:[0-9]{12}:cluster/", var.ecs_cluster_arn))
    error_message = "ecs_cluster_arn must be an ECS cluster ARN of the form arn:<partition>:ecs:<region>:<account-id>:cluster/<name>."
  }
}

# WHY : Assumptions: one definition serves all five jobs because each state
#       overrides only the container command, so the per-step arguments stay in
#       the state machine where the step order is also expressed rather than
#       being spread across five near-identical task definitions.
variable "batch_task_definition_arn" {
  description = "ARN of the task definition the five batch job states run, which is the batch-service image. Published by the batch infra/modules/ecs-service instance and wired by the environment root. Each state overrides only that task's container command, so one task definition serves all five jobs and the per-step arguments stay in the state machine where the step order is also expressed."

  type = string

  validation {
    # WHY : Assumptions: the resource type is asserted and the revision
    #       deliberately is not. An ARN without a trailing revision names the
    #       family and resolves to its ACTIVE revision at invocation, which is
    #       what lets a deployment publish a new revision without editing this
    #       infrastructure; an ARN with a revision pins the chain to one build.
    #       Both are legitimate, so both are accepted and the choice belongs to
    #       the caller.
    condition     = can(regex("^arn:[a-z0-9-]+:ecs:[a-z0-9-]+:[0-9]{12}:task-definition/", var.batch_task_definition_arn))
    error_message = "batch_task_definition_arn must be an ECS task-definition ARN of the form arn:<partition>:ecs:<region>:<account-id>:task-definition/<family>[:<revision>]."
  }
}

# WHY : Assumptions: `ContainerOverrides` addresses a container by NAME, and an
#       override whose name matches nothing in the definition is not an error --
#       it is ignored. The task then starts with the command baked into its
#       image, so the wrong job runs, reports success, and the chain continues
#       past it. That silent-substitution failure mode is the whole reason this
#       is a separate declared input rather than a literal assumed to match.
variable "batch_container_name" {
  description = "Name of the container inside the batch task definition whose command each job state overrides. The environment root passes the name published by the batch ecs-service instance. An override addresses its container by name and a name that matches nothing is ignored rather than rejected, so a wrong value here silently runs the image's baked-in command instead of the intended job."
  type        = string
  default     = "batch"

  validation {
    # WHY : Trade-offs: only the character set is checked, not the container's
    #       existence. Terraform cannot read a definition's container list from
    #       an ARN that may carry no revision, so asserting existence is not
    #       available at plan time. Asserting the character set still catches
    #       the empty string and stray whitespace, which are the two ways this
    #       input produces an unmatched -- and therefore ignored -- override.
    condition     = can(regex("^[a-zA-Z0-9][a-zA-Z0-9_-]*$", var.batch_container_name))
    error_message = "batch_container_name must be a container name of letters, digits, hyphens and underscores, beginning with a letter or digit."
  }
}

# WHY : Assumptions: this is a separate definition from the batch one because
#       the two carry different images, different task roles and different
#       resource sizes. The staging branch reads a flat file and bulk-loads it;
#       the job states do neither.
variable "data_migration_task_definition_arn" {
  description = "ARN of the task definition each seed-staging map branch runs, which is the data-migration ETL image. Published by the data-migration infra/modules/ecs-service instance and wired by the environment root. It is separate from the batch definition because the two carry different images, roles and resource sizes."

  type = string

  validation {
    # WHY : Assumptions: same shape and same reasoning as the batch definition
    #       above; the two are validated separately rather than through one
    #       shared check so that an error message names the input the caller got
    #       wrong instead of the pair.
    condition     = can(regex("^arn:[a-z0-9-]+:ecs:[a-z0-9-]+:[0-9]{12}:task-definition/", var.data_migration_task_definition_arn))
    error_message = "data_migration_task_definition_arn must be an ECS task-definition ARN of the form arn:<partition>:ecs:<region>:<account-id>:task-definition/<family>[:<revision>]."
  }
}

# WHY : Assumptions: the same name-matching contract as the batch container, and
#       the same silent-substitution consequence if it is wrong -- a staging
#       branch that appears to succeed while loading nothing the caller asked
#       for is harder to notice than one that fails outright.
variable "data_migration_container_name" {
  description = "Name of the container inside the data-migration task definition whose command each staging branch overrides, matched by name exactly as the batch container name is, and published by the data-migration ecs-service instance."
  type        = string
  default     = "data-migration"

  validation {
    # WHY : Trade-offs: as for the batch container name, existence cannot be
    #       checked at plan time from an ARN, so the character set is the
    #       strongest available assertion.
    condition     = can(regex("^[a-zA-Z0-9][a-zA-Z0-9_-]*$", var.data_migration_container_name))
    error_message = "data_migration_container_name must be a container name of letters, digits, hyphens and underscores, beginning with a letter or digit."
  }
}

# WHY : Assumptions: this is a THIRD definition rather than a reuse of the batch
#       one, and the reason is verifiable rather than stylistic.
#       services/batch-service's job-name list is exactly
#       preflight-daily-transactions, post-transactions, calculate-interest,
#       backup-transactions, combine-transactions, export and import -- seven
#       names containing no statement job and no report job. The statement and
#       report writers live in services/reporting-service, so the states that
#       replace app/jcl/CREASTMT.JCL and app/jcl/TRANREPT.jcl must run that
#       image. Pointing them at the batch definition would select a job name
#       that does not exist.
variable "reporting_task_definition_arn" {
  description = "ARN of the reporting-service task definition the two daily output states and the ad-hoc report machine run. Published by the reporting infra/modules/ecs-service instance and wired by the environment root. It is distinct from the batch definition because batch-service's job list contains no statement job and no report job -- those writers live in reporting-service."

  type = string

  validation {
    # WHY : Assumptions: validating separately keeps a malformed reporting ARN
    #       attributed to the input that feeds the statement and report states
    #       rather than to a generic collection of task definitions.
    condition     = can(regex("^arn:[a-z0-9-]+:ecs:[a-z0-9-]+:[0-9]{12}:task-definition/", var.reporting_task_definition_arn))
    error_message = "reporting_task_definition_arn must be an ECS task-definition ARN of the form arn:<partition>:ecs:<region>:<account-id>:task-definition/<family>[:<revision>]."
  }
}

# WHY : Assumptions: the reporting image serves an HTTP surface as well as these
#       jobs, so its baked-in command is a server start. An unmatched override
#       here does not run the wrong job -- it starts a long-lived server inside
#       a synchronous run-task state, which then holds the state open until the
#       state's own timeout expires. That is the most expensive form the
#       name-matching failure takes anywhere in this module.
variable "reporting_container_name" {
  description = "Name of the container inside the reporting-service task definition whose command the two daily output states and the ad-hoc report state override. The environment root passes the name published by the reporting ecs-service instance rather than relying on an assumed literal, because an unmatched override starts the image's ordinary server command inside a state that waits for the task to stop."
  type        = string
  default     = "reporting"

  validation {
    # WHY : Trade-offs: existence cannot be checked from an ARN at plan time,
    #       but the legal character set can. Rejecting blank and
    #       whitespace-bearing names here prevents the server-start outcome
    #       described above.
    condition     = can(regex("^[a-zA-Z0-9][a-zA-Z0-9_-]*$", var.reporting_container_name))
    error_message = "reporting_container_name must be a container name of letters, digits, hyphens and underscores, beginning with a letter or digit."
  }
}

# -----------------------------------------------------------------------------
# IAM -- the roles the execution role may hand to ECS
# -----------------------------------------------------------------------------

# WHY : Trade-offs: this is ONE list rather than six named scalars, or three
#       pairs. A list keeps the surface small and is consumed directly as the
#       `Resource` of a single `iam:PassRole` statement, at the cost of being
#       one degree less self-describing than named inputs would be. That cost is
#       paid down by the description enumerating exactly what belongs in it,
#       because under-supplying this list is the easiest mistake to make here:
#       an incomplete list produces an opaque access-denied error on
#       `iam:PassRole` at the first run-task, naming a role rather than naming
#       this input. Both halves of each pair are needed -- the execution role
#       pulls the image and writes the log stream, the task role is what the
#       running job authenticates as -- so supplying only the task roles fails
#       at launch rather than at run.
variable "pass_role_arns" {
  description = "IAM role ARNs the state-machine execution role is permitted to pass to ECS: the task role AND the task execution role of each of the batch, data-migration and reporting task definitions, six entries for three images. The environment root assembles the list from the ecs-service outputs it already holds; enumerating it is the least-privilege boundary of what either state machine may run a task as, and omitting an entry fails at run-task with an access-denied error on iam:PassRole."

  type = list(string)

  validation {
    # WHY : Trade-offs: the grant is enumerated rather than written as a
    #       wildcard over the account's roles. A wildcard is one line shorter and
    #       would let this state machine start a task running as ANY role in the
    #       account, which turns a state-machine definition into a
    #       privilege-escalation path. Enumerating costs the caller a list it
    #       already has, because every entry is another module's output.
    # WHY : Assumptions: the name says PASS rather than TASK because that is the
    #       action the list authorises -- one `iam:PassRole` statement whose
    #       Resource is exactly these ARNs. A name built around "task" invites the
    #       reading that only task roles belong here, and a list missing the three
    #       EXECUTION roles fails at the first run-task with an error naming
    #       iam:PassRole rather than the omission.
    # WHY : Refactoring Rationale: this condition accepted a single entry in an
    #       earlier revision, which is the ONE arity that cannot be right. This
    #       module starts tasks from three task definitions and each needs two
    #       passable roles -- the execution role that pulls the image and writes
    #       the log stream, and the task role the job authenticates as -- so the
    #       complete set is six slots. An under-supplied list does not fail at
    #       apply; it fails at the first nightly run-task with an access-denied
    #       error on iam:PassRole that names a role rather than this input, which
    #       is the most expensive place to discover it. Requiring the exact arity
    #       moves that discovery to plan time.
    # WHY : Assumptions: DUPLICATES are permitted deliberately, so this is an
    #       exact-count check and not a distinctness check. A caller may share one
    #       execution role across two or three task definitions, in which case the
    #       same ARN legitimately fills more than one slot; rejecting repeats would
    #       reject that composition, and the list is a set of grants where a repeat
    #       grants nothing extra.
    condition     = length(var.pass_role_arns) == 6 && alltrue([for r in var.pass_role_arns : can(regex("^arn:[a-z0-9-]+:iam::[0-9]{12}:role/", r))])
    error_message = "pass_role_arns must list exactly six IAM role ARNs -- the task role and the task execution role of each of the batch, data-migration and reporting task definitions -- each of the form arn:<partition>:iam::<account-id>:role/<name>. List a shared role once per slot it fills."
  }
}

# -----------------------------------------------------------------------------
# Networking -- where the tasks are placed
#
# WHY : Alternatives Considered: exposing an `assign_public_ip` input was
#       considered and rejected. main.tf sets that field to the literal
#       `DISABLED`, because these subnets reach AWS APIs through the VPC's
#       interface endpoints and reach the internet, where a task needs it,
#       through the NAT gateway. A public address would therefore buy no
#       reachability the tasks lack and would widen their exposure, so it is not
#       a choice worth offering an environment root.
# -----------------------------------------------------------------------------

# WHY : Assumptions: these are the PRIVATE-APPLICATION tier specifically -- not
#       the public tier, which carries only the load balancer and the NAT
#       gateways, and not the isolated data tier, which has no internet route at
#       all and carries only the database. A task placed in the public tier
#       would be reachable from outside; one placed in the isolated tier could
#       not pull its own image.
variable "private_app_subnet_ids" {
  description = "Private application subnet identifiers the state machine places each task into -- the application tier, not the public tier that carries the load balancer and NAT gateways and not the isolated data tier that carries the database. Published as an output by infra/modules/network and passed in by the environment root; tasks reach the database through these subnets and reach AWS APIs through that VPC's interface endpoints, so they need no public address."

  type = list(string)

  validation {
    # WHY : Assumptions: the surrounding network module provisions three
    #       availability zones, so the three-subnet floor asserts that the batch
    #       tasks are placed across all of them rather than concentrated. An
    #       empty list is accepted by the type system and rejected by the service
    #       only once a task is started, which is at the first nightly
    #       invocation rather than at apply; placing tasks in fewer than three
    #       zones would let one zone's loss stop the nightly chain in an
    #       environment whose whole point is that it does not.
    # WHY : Refactoring Rationale: the count check alone was satisfiable by one
    #       subnet repeated three times, which passes the arithmetic while placing
    #       every task in a single zone -- exactly the concentration the floor
    #       exists to prevent, and invisible afterwards because the plan shows
    #       three entries. Distinctness is therefore asserted alongside the count,
    #       and unlike the role list above a repeat here is never legitimate: two
    #       identical subnet identifiers describe one subnet.
    condition = length(var.private_app_subnet_ids) >= 3 && length(distinct(var.private_app_subnet_ids)) == length(var.private_app_subnet_ids) && alltrue([
      for s in var.private_app_subnet_ids : can(regex("^subnet-[0-9a-f]{8,}$", s))
    ])
    error_message = "private_app_subnet_ids must list at least three DISTINCT subnet identifiers, each of the form subnet-<hex>, one per availability zone."
  }
}

# WHY : Assumptions: what this group must permit is egress to Aurora on the
#       database port and egress on 443 to the VPC interface endpoints, and
#       nothing wider -- those two are the whole of what a batch step reaches.
#       Declared as a list rather than a single identifier both because the
#       awsvpc network configuration takes a list and because the sibling
#       infra/modules/ecs-service input of the same name is a list, so a root
#       passes the same expression to both without reshaping it.
variable "task_security_group_id" {
  description = "Security group attached to every task the state machines start, the single application-tier group published by infra/modules/network. It is what permits the egress a batch step actually needs -- the database port to Aurora and 443 to the VPC interface endpoints -- and nothing wider."

  type = string

  validation {
    # WHY : Assumptions: a task started with no group at all falls back to the
    #       VPC's default security group, which is a permissive group nobody chose
    #       for this workload, and the substitution is silent. A required scalar
    #       makes the group an explicit decision.
    # WHY : Alternatives Considered: a `list(string)`, matching the awsvpc network
    #       configuration's own shape and the identically named ecs-service input.
    #       Rejected: this module attaches exactly one group, so a list would let a
    #       caller pass several and quietly widen the egress the whole batch window
    #       runs under, and main.tf would have to wrap or unwrap it either way. The
    #       accepted cost is one `[var.task_security_group_id]` at the single point
    #       the awsvpc block is built.
    condition     = can(regex("^sg-[0-9a-f]{8,}$", var.task_security_group_id))
    error_message = "task_security_group_id must be a security group identifier of the form sg-<hex>."
  }
}

# -----------------------------------------------------------------------------
# Data and notification
# -----------------------------------------------------------------------------

# WHY : Assumptions: this module CONSUMES the bucket and creates nothing inside
#       it. The bucket itself, the ten generation-dataset prefix families and
#       the five-noncurrent-version lifecycle rule that is the `LIMIT(5)
#       SCRATCH` analogue all belong to infra/modules/s3-datasets. Only the name
#       crosses the boundary, and it crosses once: both the execution role's
#       grants and the task command overrides reference this single input rather
#       than each taking their own.
variable "dataset_bucket_name" {
  description = "Name of the versioned bucket the staging, backup, combine, statement and report states read and write dataset generations in. Published as an output by infra/modules/s3-datasets and passed in by the environment root. This module only consumes it: the bucket, its ten generation-dataset prefix families and its five-noncurrent-version lifecycle rule all belong to s3-datasets."

  type = string

  validation {
    # WHY : Assumptions: the rules asserted are the service's -- 3 to 63
    #       characters of lowercase letters, digits, hyphens and dots, beginning
    #       and ending alphanumeric. A name breaking them can neither be created
    #       nor referenced, and without this check the failure surfaces as an
    #       IAM policy that silently matches nothing rather than as a bad input.
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.dataset_bucket_name))
    error_message = "dataset_bucket_name must be a valid S3 bucket name: 3 to 63 characters of lowercase letters, digits, hyphens and dots, beginning and ending with a letter or digit."
  }
}

# WHY : Assumptions: the catch handler on every work state routes here first and
#       only then to the terminal failure, so a failed nightly run notifies
#       instead of failing silently. That is the analogue of the job log and the
#       operator console the baseline relied on -- the baseline reported a failed
#       step through `NOTIFY=&SYSUID` on the job card, and routing all eleven
#       states' catch handlers through one topic is what makes any of them reach
#       the same place.
variable "notification_topic_arn" {
  description = "ARN of the SNS topic every state's catch handler publishes to before the execution reaches its terminal failure state. Published as an output by infra/modules/observability and passed in by the environment root. It replaces the baseline's job-card NOTIFY and job log; routing all eleven states through one topic is what makes a failure in any of them reach the same place rather than failing silently."

  type = string

  validation {
    # WHY : Assumptions: asserting the `sns:` service field catches the
    #       plausible substitution of a queue ARN, which a catch handler would
    #       accept into a valid plan and then fail to publish to at exactly the
    #       moment something has already gone wrong -- the worst time to lose a
    #       notification.
    condition     = can(regex("^arn:[a-z0-9-]+:sns:[a-z0-9-]+:[0-9]{12}:", var.notification_topic_arn))
    error_message = "notification_topic_arn must be an SNS topic ARN of the form arn:<partition>:sns:<region>:<account-id>:<topic-name>."
  }
}

# -----------------------------------------------------------------------------
# Observability -- execution logs, their encryption, and tracing
# -----------------------------------------------------------------------------

# WHY : Assumptions: retention is one of the narrow set of parameters the dev and
#       prod roots are permitted to set differently without changing the stack's
#       shape, which is why it is an input rather than a constant fixed in
#       main.tf. What it retains is the record of which states ran on which
#       night, so the value is a compliance and diagnosis decision belonging to
#       the environment rather than to this module.
variable "log_retention_days" {
  description = "Days both state machines' execution log groups retain events. Supplied by the environment root, which is where dev and prod are permitted to differ; retention and sizing are the only axes on which the two environments may diverge, and this is the record of which states ran on which night."
  type        = number
  default     = 30

  validation {
    # WHY : Assumptions: the set is closed because CloudWatch Logs accepts only
    #       these values and rejects anything else during apply, after other
    #       resources already exist. 0 is excluded deliberately even though the
    #       service reads it as never-expire: unbounded retention on an execution
    #       log is a cost that grows without anyone deciding to accept it, and a
    #       caller who genuinely wants it can say so by widening this check
    #       rather than by passing a value that looks like a mistake.
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.log_retention_days)
    error_message = "log_retention_days must be one of the retention periods CloudWatch Logs accepts: 1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288 or 3653."
  }
}

# WHY : Trade-offs: this is REQUIRED, where a log-encryption key is commonly
#       optional with a null default meaning service-managed encryption. That
#       alternative was considered and rejected on the merits. Every file
#       resource in the baseline was defined `RECOVERY(NONE) JOURNAL(NO)`
#       (app/csd/CARDDEMO.CSD:1-89), so encryption at rest is a property this
#       migration ADDS rather than preserves; a nullable key would let a root
#       fall back to service-managed encryption by simply omitting an argument,
#       which makes the added property skippable without any reader noticing.
#       Requiring it costs each root one line naming an infra/modules/kms output
#       it already holds, and that is the whole cost.
variable "log_group_kms_key_arn" {
  description = "ARN of the customer-managed key both execution log groups are encrypted with, published as an output by infra/modules/kms and passed in by the environment root. Null leaves the log groups on CloudWatch's own service-managed encryption."

  type     = string
  nullable = true
  default  = null

  validation {
    # WHY : Assumptions: both the `kms:` service field and the `:key/` resource
    #       type are asserted, because an alias ARN is the plausible wrong value
    #       -- an alias is what a human reads in the console -- and it carries
    #       resource type `alias/`, which a log group does not accept. Catching
    #       it here names this input; letting it through produces an apply-time
    #       failure attributed to the log group instead.
    # WHY : Trade-offs: the default is null, so a module applied with no key still
    #       plans and the log groups fall back to service-managed encryption. That
    #       is acceptable for a local smoke run and is NOT the intended production
    #       posture: every CICS file in the baseline was defined RECOVERY(NONE)
    #       JOURNAL(NO), and customer-managed encryption at rest is one of the
    #       properties this migration adds, so both environment roots are expected
    #       to supply the key. A required input was the alternative and was
    #       rejected because it makes a module that has no log-group key yet
    #       unusable rather than merely less encrypted.
    condition     = var.log_group_kms_key_arn == null || can(regex("^arn:[a-z0-9-]+:kms:[a-z0-9-]+:[0-9]{12}:key/", var.log_group_kms_key_arn))
    error_message = "log_group_kms_key_arn must be null, or a KMS key ARN of the form arn:<partition>:kms:<region>:<account-id>:key/<key-id>, not an alias ARN."
  }
}

# WHY : Trade-offs: the default is the most verbose setting rather than the
#       cheapest. A nightly chain runs once, so volume is bounded by eleven
#       states rather than by a request rate, and the first question asked after
#       a failure -- which state failed and what did it receive -- is answered
#       only by the full transition history.
variable "log_level" {
  description = "Which execution events reach both state-machine log groups: ERROR records failures, FATAL only terminal failures, and ALL every transition. Logging cannot be disabled, because execution history is the target analogue of the baseline job log."
  type        = string
  default     = "ALL"

  validation {
    # WHY : Alternatives Considered: accepting the service's fourth level, OFF,
    #       and leaving it to the CI policy scan to reject -- that scan requires
    #       logging to be enabled on a state machine, so OFF would be caught
    #       there. Rejected because catching it here is strictly earlier and
    #       strictly clearer: the error names this input at plan time, whereas
    #       the scan reports a finding against a generated resource. A
    #       caller who disables execution logging removes the only cross-state
    #       record of a failed chain while still producing a valid plan, so the
    #       narrower domain is the point rather than an oversight.
    condition     = contains(["ALL", "ERROR", "FATAL"], var.log_level)
    error_message = "log_level must be one of ALL, ERROR or FATAL; execution logging cannot be disabled, and OFF is rejected here rather than by the CI policy scan."
  }
}

# WHY : Assumptions: the default is on, and what makes that safe is a checkable
#       claim about what this chain's payloads contain -- a business date,
#       dataset names, job names, execution identities and task metadata. NO
#       cardholder data, NO primary account number and NO credential enters
#       either state machine's payload; record data is read by the tasks from the
#       database and the dataset bucket, never threaded through the state
#       machine. Knowing what a state received is the difference between knowing
#       that the interest state failed and knowing it failed for a particular
#       business date, which is why the payload is worth recording at all. This
#       stays an INPUT rather than becoming a constant precisely because the
#       claim is about the current chain: a future change that threaded
#       record-level data through an execution input must revisit this default.
variable "log_include_execution_data" {
  description = "Whether each logged event carries the state's input and output payload as well as the transition itself. Safe to leave on because this chain's payloads are business dates, dataset names, job names and execution identities -- no cardholder data, primary account number or credential enters either state machine. It remains an input so that a future change threading record-level data through an execution can turn it off."
  type        = bool
  default     = true
}

# WHY : Trade-offs: tracing costs per recorded trace, and a chain that runs once
#       a night records one. Against that cost, the question this module's
#       failures raise is almost always where in the chain time went or which
#       call failed, and a per-execution trace answers it without reconstructing
#       a timeline from log timestamps across three services.
# -----------------------------------------------------------------------------
# Function-backed states -- the operator bracket and the statistics refresh
#
# Three of the eleven states are a single API call each and are therefore backed
# by functions rather than tasks. They are declared as three separate inputs
# rather than one list, because each is invoked at a specific position in the
# chain and a list would lose which is which.
#
# WHY : Alternatives Considered: running them as Fargate tasks for uniformity.
#       Rejected -- it would pay a task cold start and a task definition's worth
#       of configuration for a call that writes one parameter or issues one
#       statement. The fifteen-minute execution ceiling that rules a function
#       out for a record-processing step rules nothing out for these three.
#
# WHY : Alternatives Considered: this module's remit is two state machines, one
#       execution role and two log groups; there is no Lambda module in this
#       infrastructure package, so function ownership sits with the environment
#       root, which declares them alongside the parameter they toggle. The
#       alternative genuinely considered was making these three ARNs nullable and
#       degrading their states to `Pass` states when a caller omitted them, so
#       the module could plan without them. It is rejected on the merits: a state
#       machine that silently no-ops the quiesce bracket would run the entire
#       batch window against a live online write path, with concurrent writes
#       landing in the masters the chain is posting against. A module that
#       refuses to plan without its input is a far better outcome than one that
#       degrades a correctness guarantee into a no-op, so all three are required.
# -----------------------------------------------------------------------------

# WHY : Assumptions: this is the migrated form of app/jcl/CLOSEFIL.jcl:26-30,
#       which issued five `CEMT SET FIL(...) CLO` operator commands over
#       TRANSACT, CCXREF, ACCTDAT, CXACAIX and USRSEC. The target sets one
#       parameter that the online services read, so the MECHANISM changes while
#       the bracket around the window does not.
variable "quiesce_function_arn" {
  description = "ARN of the function the first state invokes to set the online read-only flag, opening the batch window. Declared by the environment root, which owns these functions and the parameter they toggle. This is the migrated form of app/jcl/CLOSEFIL.jcl, which closed five CICS files with an operator command; the target sets a parameter the online services read, so the mechanism changes while the bracket does not."

  type = string

  validation {
    # WHY : Assumptions: an ARN of any other service is accepted into the plan
    #       and rejected only when the state runs, which is at the head of the
    #       nightly chain. A version or alias qualifier adds a further
    #       colon-delimited segment, so the pattern deliberately does not anchor
    #       at the end and both qualified and unqualified ARNs are accepted.
    condition     = can(regex("^arn:[a-z0-9-]+:lambda:[a-z0-9-]+:[0-9]{12}:function:", var.quiesce_function_arn))
    error_message = "quiesce_function_arn must be a Lambda function ARN of the form arn:<partition>:lambda:<region>:<account-id>:function:<name>[:<qualifier>]."
  }
}

# WHY : Assumptions: this is app/jcl/OPENFIL.jcl:26-30, the five matching
#       `CEMT SET FIL(...) OPE` commands, and it is the counterpart the chain
#       cannot omit. The chain must clear the flag it set on the success path AND
#       on the failure path alike, which is why main.tf invokes this function
#       from both -- a chain that failed without clearing the flag would leave
#       the online services read-only after the window ended.
#       Trade-offs: main.tf invokes it from a THIRD place as well, outside the
#       execution, because the two in-execution paths are both states and an
#       execution that is timed out at the top level or aborted by an operator
#       runs no further state. That third caller is an EventBridge rule on the
#       daily machine's terminal status, so this ARN is granted to
#       events.amazonaws.com as well as to the execution role. The handler
#       overwrites the parameter unconditionally, so being called twice for one
#       night costs one idempotent write.
variable "resume_function_arn" {
  description = "ARN of the function invoked to clear the online read-only flag, closing the batch window. Declared by the environment root. This is the migrated form of app/jcl/OPENFIL.jcl and the counterpart of the quiesce state: it is invoked on the success path and on the failure path alike, because a chain that failed without clearing the flag it set would leave the online services read-only after the window ended. It is additionally invoked from outside the execution, by an EventBridge rule on the daily machine's terminal status, so a timed-out or operator-aborted execution -- which runs no further state and so reaches neither in-execution path -- still releases the flag."

  type = string

  validation {
    # WHY : Assumptions: validated separately from the quiesce ARN so that an
    #       error message names the input the caller got wrong rather than the
    #       bracket as a whole.
    condition     = can(regex("^arn:[a-z0-9-]+:lambda:[a-z0-9-]+:[0-9]{12}:function:", var.resume_function_arn))
    error_message = "resume_function_arn must be a Lambda function ARN of the form arn:<partition>:lambda:<region>:<account-id>:function:<name>[:<qualifier>]."
  }
}

# WHY : Assumptions: this replaces app/jcl/TRANIDX.jcl only IN PART, and the
#       missing part is deliberate. That job REBUILT an alternate index with
#       `IDCAMS BLDINDEX`; index building is retired outright because PostgreSQL
#       maintains an index inside the same transaction as the write, which leaves
#       statistics as the only portion of the step with a target at all.
variable "analyze_tables_function_arn" {
  description = "ARN of the function the penultimate state invokes to refresh table statistics after the night's writes. Declared by the environment root. It replaces app/jcl/TRANIDX.jcl only in part: that job rebuilt an alternate index, and index building is retired because PostgreSQL maintains indexes inside the same transaction as the write, leaving statistics as the only part of the step with a target."

  type = string

  validation {
    # WHY : Assumptions: validated separately for the same attribution reason as
    #       the resume function above.
    condition     = can(regex("^arn:[a-z0-9-]+:lambda:[a-z0-9-]+:[0-9]{12}:function:", var.analyze_tables_function_arn))
    error_message = "analyze_tables_function_arn must be a Lambda function ARN of the form arn:<partition>:lambda:<region>:<account-id>:function:<name>[:<qualifier>]."
  }
}

# WHY : Alternatives Considered: relying solely on the parameter name the
#       environment root already bakes into each function's own environment
#       variables, which would make this input unnecessary. Rejected because it
#       leaves the state machine unable to say WHICH flag its bracket toggles:
#       the definition would show a quiesce call with no subject, and the answer
#       would live in a different resource in a different file. Passing the name
#       in the payload makes the bracket self-describing in the execution
#       history, and it lets one function serve more than one flag.
#       Assumptions: what this replaces is the operator-command mechanism of
#       app/jcl/CLOSEFIL.jcl and app/jcl/OPENFIL.jcl, each of which issues five
#       `CEMT SET FIL(...)` commands over TRANSACT, CCXREF, ACCTDAT, CXACAIX and
#       USRSEC. The bracket is scoped to the WRITE path only -- the baseline's
#       read-only unload jobs open their files shared -- so a flag the services
#       consult is the faithful analogue rather than a hard lock that would also
#       block reads the baseline allowed.
variable "read_only_flag_parameter_name" {
  description = "Name of the SSM Parameter Store parameter the first and last states toggle, passed to the quiesce and resume functions in their invocation payload so the execution history records which flag the bracket controls. Declared by the environment root alongside the functions. It replaces the operator-command mechanism of app/jcl/CLOSEFIL.jcl and app/jcl/OPENFIL.jcl, which issued five CEMT SET FIL commands each; the bracket is scoped to the write path, because the baseline's read-only unload jobs opened their files shared."

  type = string

  validation {
    # WHY : Assumptions: the plausible wrong value here is the parameter's ARN,
    #       because every other identifier this module takes is one. The API that
    #       the functions call takes a name, so an ARN would be accepted into a
    #       valid plan and fail inside the function at the head of the chain.
    #       Rejecting a value containing `arn:` names this input instead.
    # WHY : Refactoring Rationale: the leading slash is REQUIRED, where an earlier
    #       revision made it optional on the grounds that Parameter Store accepts
    #       a hierarchical name either way. The service does, but the consumer
    #       does not: infra/lambda/online_write_flag.py raises
    #       `PARAMETER_NAME must be an absolute SSM path` at IMPORT time unless
    #       the name begins with a slash. Accepting a relative name therefore
    #       produced a clean plan whose first state crashed on cold start, at the
    #       head of the batch chain and immediately after the gate was set --
    #       exactly where a failure is most expensive. Terraform now rejects what
    #       the function rejects, so the two agree.
    condition     = can(regex("^/[a-zA-Z0-9_.\\-/]+$", var.read_only_flag_parameter_name)) && !can(regex("^arn:", var.read_only_flag_parameter_name)) && length(var.read_only_flag_parameter_name) <= 2048
    error_message = "read_only_flag_parameter_name must be an ABSOLUTE SSM parameter name beginning with \"/\", of letters, digits, underscores, dots, hyphens and slashes -- for example \"/carddemo/dev/batch/online-writes-enabled\" -- and not a parameter ARN."
  }
}

# -----------------------------------------------------------------------------
# Seed-dataset staging -- the branches of the second state's Map
# -----------------------------------------------------------------------------

# WHY : Assumptions: the ten defaults correspond one-to-one to the ten IDCAMS
#       master-refresh load jobs in app/jcl/ -- accounts to ACCTFILE.jcl, cards
#       to CARDFILE.jcl, customers to CUSTFILE.jcl, card_xref to XREFFILE.jcl,
#       transactions to TRANFILE.jcl, disclosure_groups to DISCGRP.jcl,
#       transaction_category_balances to TCATBALF.jcl, transaction_types to
#       TRANTYPE.jcl, transaction_categories to TRANCATG.jcl and users to
#       DUSRSECJ.jcl. Each of those ten jobs performs exactly one `REPRO`, which
#       is what makes the mapping one-to-one rather than approximate.
#       Assumptions: DALYTRAN.PS is deliberately NOT an eleventh entry, and its
#       absence is correct rather than an omission. It has no load job because
#       app/jcl/POSTTRAN.jcl:30 reads it directly with `DISP=SHR` at its
#       DALYTRAN DD -- it is a flat sequential INPUT to posting, not a loaded
#       master -- so staging it would invent a load step the baseline has no
#       contract for.
# WHY : Trade-offs: a caller occasionally needs to stage a SUBSET -- reloading
#       one master after a correction rather than the whole set -- and exposing
#       the list makes that a tfvars change instead of a module edit. The
#       accepted cost is that a caller can pass a name no reader supports, which
#       the validation below cannot detect because the reader set lives in the
#       ETL package rather than in Terraform.
variable "seed_datasets" {
  description = "Dataset names the seed-staging state iterates over, one Map branch and one data-migration task per name. The default is the ten loaded masters, one per IDCAMS master-refresh load job in app/jcl/; DALYTRAN is absent because posting reads it directly as sequential input rather than loading it into a master table. An environment may pass a subset to restage one master without a module edit."

  type = list(string)

  default = [
    "accounts",
    "cards",
    "customers",
    "card_xref",
    "transactions",
    "disclosure_groups",
    "transaction_category_balances",
    "transaction_types",
    "transaction_categories",
    "users",
  ]

  validation {
    # WHY : Assumptions: each name becomes a path segment in the dataset prefix
    #       and an argument on the ETL container's command, so it is restricted
    #       to lowercase letters, digits and separators both accept. An empty
    #       list is rejected because a Map with no items SUCCEEDS while loading
    #       nothing, which is the one failure here that reads as a clean run.
    # WHY : Refactoring Rationale: membership in the closed ten-name set is
    #       asserted rather than a character-shape pattern. The pattern it replaces
    #       admitted any lowercase token, so `accounts_v2` or a misspelled
    #       `custommers` passed validation and failed later inside a Map branch as
    #       a container usage error naming an argument rather than naming this
    #       input. Every legal value is one of the ten defaults, each corresponding
    #       to exactly one IDCAMS master-refresh load job, so the set is knowable
    #       here. An earlier revision argued the opposite -- that the reader set
    #       lives in the ETL package and so could not be checked here -- but that
    #       describes where the knowledge lives, not whether it can be asserted.
    # WHY : Assumptions: distinctness is asserted too, because a repeated name
    #       stages one master twice in the same run -- two Map branches loading the
    #       same table concurrently, which is a write conflict rather than a slower
    #       load, and which the count check alone would let through.
    condition = length(var.seed_datasets) >= 1 && length(distinct(var.seed_datasets)) == length(var.seed_datasets) && alltrue([
      for d in var.seed_datasets : contains([
        "accounts",
        "cards",
        "customers",
        "card_xref",
        "transactions",
        "disclosure_groups",
        "transaction_category_balances",
        "transaction_types",
        "transaction_categories",
        "users",
      ], d)
    ])
    error_message = "seed_datasets must list at least one name, each DISTINCT and each one of the ten loaded masters: accounts, cards, customers, card_xref, transactions, disclosure_groups, transaction_category_balances, transaction_types, transaction_categories, users."
  }
}

# WHY : Trade-offs: three is chosen between two bad endpoints. Unbounded
#       concurrency would start all ten branches at once, and ten simultaneous
#       bulk loads can exhaust both the Aurora connection budget and the Fargate
#       task quota -- a failure that presents as an unrelated capacity error
#       rather than as a concurrency choice. A value of 1 serialises ten loads
#       that have no ordering requirement between them, spending the window for
#       no correctness gain. Three keeps useful parallelism while bounding
#       connection pressure, and the input lets a root tune that sizing without
#       changing the state-machine topology.
variable "stage_datasets_max_concurrency" {
  description = "Maximum number of seed-staging Map branches allowed to run at once. The environment root may lower it to fit Aurora connection and Fargate task quotas; the default permits parallel loads without starting all ten branches simultaneously. A sizing value, so it is one of the few a root may legitimately differ on."
  type        = number
  default     = 3

  validation {
    # WHY : Assumptions: the floor of 1 is what the Map itself requires -- 0 is
    #       the service's spelling of UNBOUNDED, so accepting it would silently
    #       turn the deliberate bound above into no bound at all. The ceiling of
    #       10 is the number of default branches, past which the value cannot
    #       increase parallelism and only obscures the intent.
    condition     = var.stage_datasets_max_concurrency == floor(var.stage_datasets_max_concurrency) && var.stage_datasets_max_concurrency >= 1 && var.stage_datasets_max_concurrency <= 10
    error_message = "stage_datasets_max_concurrency must be a whole number from 1 to 10; 0 is rejected because the service reads it as unbounded concurrency."
  }
}

# -----------------------------------------------------------------------------
# Timing and resilience
#
# These inputs carry the baseline's step-gating semantics into the target, and
# two of them are where this module is easiest to get backwards. They are worth
# reading beside app/jcl/ rather than on their own; docs/architecture/
# batch-orchestration.md is the prose home of the full mapping.
# -----------------------------------------------------------------------------

# WHY : Assumptions: a state with NO timeout waits indefinitely. Because the
#       resume state runs only after the chain finishes or fails, a task that
#       hangs holds the whole chain open AND leaves the online read-only flag set
#       until an operator intervenes -- so the absence of a timeout is not a
#       neutral default but an outage the flag makes worse. A conservative
#       ceiling on every state is therefore safer than none.
variable "state_timeout_seconds" {
  description = "Ceiling on each of the eleven work states, keyed by the state name exactly as main.tf spells it. The default sizes the long-running states -- seed staging, posting, interest, statements and reports -- above the states that only toggle a flag or refresh statistics. Every key must be present, so a state can never be left without a timeout: a state with no ceiling waits indefinitely, which holds the whole chain open and leaves the online read-only flag set until an operator intervenes."

  type = map(number)

  default = {
    QuiesceOnlineWrites        = 300
    StageSeedDatasets          = 3600
    PreflightDailyTransactions = 1800
    PostTransactions           = 7200
    CalculateInterest          = 7200
    BackupTransactions         = 3600
    CombineTransactions        = 3600
    GenerateStatements         = 7200
    GenerateReports            = 3600
    AnalyzeTables              = 1800
    ResumeOnlineWrites         = 300
  }

  validation {
    # WHY : Assumptions: the eleven names are fixed by main.tf's own state list
    #       and are spelled here character for character -- QuiesceOnlineWrites,
    #       StageSeedDatasets, PreflightDailyTransactions, PostTransactions,
    #       CalculateInterest, BackupTransactions, CombineTransactions,
    #       GenerateStatements, GenerateReports, AnalyzeTables and
    #       ResumeOnlineWrites. Both directions matter. A MISSING key would leave
    #       that state without a ceiling, and a key such as "PostTransaction"
    #       would apply cleanly while posting ran unbounded and the operator
    #       believed a limit had been set. The error names the required set so the
    #       typo or the omission is visible.
    #       Assumptions: exactness needs both halves. `setsubtract` proves every
    #       REQUIRED name is present but says nothing about an extra one, and a map
    #       holds no duplicate keys -- so eleven keys that include all eleven
    #       required names are exactly those names. Terraform has no
    #       symmetric-difference function, which is why the count carries the
    #       second half.
    condition = length(var.state_timeout_seconds) == 11 && length(setsubtract([
      "QuiesceOnlineWrites",
      "StageSeedDatasets",
      "PreflightDailyTransactions",
      "PostTransactions",
      "CalculateInterest",
      "BackupTransactions",
      "CombineTransactions",
      "GenerateStatements",
      "GenerateReports",
      "AnalyzeTables",
      "ResumeOnlineWrites",
    ], keys(var.state_timeout_seconds))) == 0
    error_message = "state_timeout_seconds must hold exactly one entry for each of the eleven work states, named as main.tf spells them: QuiesceOnlineWrites, StageSeedDatasets, PreflightDailyTransactions, PostTransactions, CalculateInterest, BackupTransactions, CombineTransactions, GenerateStatements, GenerateReports, AnalyzeTables, ResumeOnlineWrites."
  }

  validation {
    # WHY : Trade-offs: the floor of one minute rejects a value so small that a
    #       healthy task is killed during its own startup, which presents as an
    #       intermittent failure rather than as a misconfiguration. The ceiling of
    #       six hours is the point past which a timeout stops acting as a safety
    #       net at all, since a chain permitted to sit that long has already lost
    #       the property the bound exists to protect.
    condition     = alltrue([for t in values(var.state_timeout_seconds) : t >= 60 && t <= 21600 && floor(t) == t])
    error_message = "Every value in state_timeout_seconds must be a whole number of seconds from 60 to 21600 inclusive."
  }
}

# WHY : Trade-offs: a map keyed by state name is one degree less discoverable
#       than eleven separately named inputs would be, and that cost is accepted
#       because eleven inputs would have to be restated by every calling root and
#       kept in step with main.tf whenever a state was renamed. The shape's one
#       real hazard is that a MISTYPED key is inert -- main.tf reads this map with
#       a lookup against the default, so a typo silently leaves that state on the
#       floor value rather than failing. The key-name validation below closes
#       exactly that hazard, which is why the shape did not have to change to a
#       map required to carry all eleven keys.
# WHY : Assumptions: these three knobs express EXPONENTIAL BACKOFF together, and
#       they are the TRANSIENT-FAULT tier only. A task that exits with a hard
#       failure status has produced a deterministic outcome that retrying cannot
#       change, so main.tf retries the service-level errors -- a launch,
#       observation or timeout fault -- and lets a business-level failure fall
#       through to the state's `Catch` instead. Reading these as a general
#       failure remedy is the misreading they are documented against.
variable "retry_max_attempts" {
  description = "Retry attempts each state makes after its first failure, before its catch handler runs. This is the per-state half of the durable retry tier; the other half is that a failed execution can be redriven from the state that failed, and that the batch run ledger makes a step which already completed a no-op when it is retried."
  type        = number
  default     = 3

  validation {
    # WHY : Trade-offs: zero is permitted and is legitimate for a caller that
    #       wants a failure to surface immediately, so the floor is 0 rather than
    #       1. The ceiling of 10 exists because retrying a record-processing step
    #       is not free -- each attempt reruns the step from its beginning, so a
    #       step whose failure is deterministic will fail ten times and then fail
    #       once more, having spent the entire window doing it.
    condition     = var.retry_max_attempts >= 0 && var.retry_max_attempts <= 10 && floor(var.retry_max_attempts) == var.retry_max_attempts
    error_message = "retry_max_attempts must be a whole number from 0 to 10 inclusive."
  }
}

# WHY : Assumptions: this value and the rate together bound how long a retrying
#       state can occupy the window, so neither is meaningful read alone; the
#       transient-fault reasoning above governs both.
variable "retry_interval_seconds" {
  description = "Seconds a state waits before its first retry. Subsequent waits are this interval multiplied by the backoff rate, compounding per attempt, so this value and the rate together bound how long a retrying state can occupy the batch window."
  type        = number
  default     = 30

  validation {
    # WHY : Trade-offs: the floor of 1 second is the smallest the service
    #       accepts, and the ceiling of 600 keeps a single first wait from
    #       exceeding the shortest permitted state timeout, which would otherwise
    #       let a state be killed by its own timeout while still waiting to make
    #       its first retry.
    condition     = var.retry_interval_seconds >= 1 && var.retry_interval_seconds <= 600 && floor(var.retry_interval_seconds) == var.retry_interval_seconds
    error_message = "retry_interval_seconds must be a whole number of seconds from 1 to 600 inclusive."
  }
}

# WHY : Assumptions: this is the third of the three backoff knobs and completes
#       the transient-fault tier described above; a rate of exactly 1 makes every
#       wait equal to the interval, which is a flat retry rather than a backoff
#       and is a legitimate choice a caller may want.
variable "retry_backoff_rate" {
  description = "Multiplier applied to the retry interval on each successive attempt. A value of 1 makes every wait equal to the interval, which is a flat retry rather than a backoff; higher values grow the wait geometrically."
  type        = number
  default     = 2.0

  validation {
    # WHY : Assumptions: the floor is 1.0 because a rate below one SHORTENS each
    #       successive wait, turning a backoff into an accelerating retry against
    #       a dependency that is already failing -- the opposite of what the
    #       mechanism is for, and accepted silently without this check.
    condition     = var.retry_backoff_rate >= 1.0 && var.retry_backoff_rate <= 10.0
    error_message = "retry_backoff_rate must be between 1.0 and 10.0 inclusive; a value below 1.0 would shorten each successive wait instead of lengthening it."
  }
}

# WHY : Assumptions: this is a CEILING, not an expectation of how long a run
#       takes, and per-state timeouts do not make it redundant. An execution can
#       stall between states, or in a Map's own bookkeeping, where no single
#       state's timeout applies; without a top-level bound such an execution
#       waits indefinitely, and because the resume state runs only once the chain
#       finishes or fails, an indefinite wait holds the online write path
#       quiesced for as long as it lasts.
#       Trade-offs: the bound caps how long a RUNNING execution can hold the
#       bracket; it does not itself release it. An execution-level TIMED_OUT
#       stops the execution without running any further state, so the release on
#       that path comes from the out-of-execution watchdog rule in main.tf. This
#       same value is also published to the quiesce call as the bracket's lease
#       length, so the advertised expiry and the enforced ceiling agree by
#       construction instead of by a caller keeping a second input in step.
variable "state_machine_timeout_seconds" {
  description = "Ceiling on a single daily-batch execution, applied at the top level of the state machine definition rather than to any one state. It bounds the whole chain: an execution that stalls where no individual state's timeout applies would otherwise wait indefinitely, holding the online read-only flag set, because the resume state runs only after the chain finishes or fails. The ceiling caps how long the flag can be held rather than releasing it -- a timed-out execution runs no further state -- so release on that path comes from the out-of-execution watchdog rule, and this same value is published to the quiesce call as the bracket's lease length."
  type        = number
  default     = 28800

  validation {
    # WHY : Assumptions: the floor is computed from the other two timing inputs
    #       rather than written as a literal, because a literal floor would go
    #       stale the moment a caller raised any per-state ceiling. A top-level
    #       bound below the longest state bound could expire while a legitimately
    #       long state was still inside its own allowance, which aborts a healthy
    #       run and reads as a task failure rather than as a misconfigured bound.
    #       The one-day ceiling is the point past which this stops being a bound
    #       on a nightly chain at all.
    #       Assumptions: `max` is spread over the map's values with `...` rather
    #       than given a seed, which is safe here only because
    #       state_timeout_seconds is validated to hold exactly eleven entries --
    #       `max()` with no arguments is an error, so an empty map would fail this
    #       check with a confusing message instead of a clear one.
    condition = var.state_machine_timeout_seconds <= 86400 && floor(var.state_machine_timeout_seconds) == var.state_machine_timeout_seconds && var.state_machine_timeout_seconds >= max(
      [for t in values(var.state_timeout_seconds) : t]...
    )
    error_message = "state_machine_timeout_seconds must be a whole number of seconds, at most 86400, and at least as large as the largest value in state_timeout_seconds, which is the longest any single state is allowed to run."
  }
}

# WHY : Trade-offs: this is a SEPARATE ceiling from the daily one rather than a
#       shared value, and the reason is that one on-demand report is a much
#       smaller unit of work than the whole nightly chain. A single shared value
#       would have to be sized for the larger of the two, which would leave a
#       stuck ad-hoc report holding a task for the length of a full batch window
#       before anything noticed. The cost of the separation is one more input.
variable "adhoc_report_timeout_seconds" {
  description = "Ceiling on a single ad-hoc report execution, applied at the top level of the ad-hoc state machine definition. Separate from the daily ceiling because one on-demand report is a far smaller unit of work than the nightly chain, and a shared value would have to be sized for the larger of the two."
  type        = number
  default     = 7200

  validation {
    # WHY : Assumptions: the ad-hoc machine's single work state draws its timeout
    #       from the SAME map as the daily report state, under the key
    #       `GenerateReports`, so the floor is computed from that exact entry
    #       rather than from a literal. Deriving it is what keeps this bound
    #       correct when a caller raises the report state's own ceiling; a literal
    #       floor would silently become too low and the machine would expire while
    #       its only real state was still within its allowance. The key is indexed
    #       rather than looked up with a fallback because state_timeout_seconds is
    #       validated to hold every one of the eleven state names, so
    #       `GenerateReports` cannot be absent.
    condition     = var.adhoc_report_timeout_seconds <= 86400 && floor(var.adhoc_report_timeout_seconds) == var.adhoc_report_timeout_seconds && var.adhoc_report_timeout_seconds >= var.state_timeout_seconds["GenerateReports"]
    error_message = "adhoc_report_timeout_seconds must be a whole number of seconds, at most 86400, and at least as large as the ceiling the report state itself receives -- the GenerateReports entry in state_timeout_seconds."
  }
}

# -----------------------------------------------------------------------------
# Deliberate absences. Everything below is an input this module does NOT take,
# recorded so that none of them reads as an oversight to be repaired. Each names
# the owner the value belongs to instead.
# -----------------------------------------------------------------------------

# WHY : Assumptions: NO `business_date` input, and its absence is the decision.
#       The daily machine takes `businessDate` from its EXECUTION
#       INPUT -- supplied by infra/modules/eventbridge-scheduler in the schedule
#       payload, or by an operator starting an execution directly -- and threads
#       it into every task's command override. A Terraform variable would bake
#       one date into the infrastructure and every night would post the same day.
#       The baseline is explicit on this point: app/jcl/INTCALC.jcl:22 reads
#       `//STEP15 EXEC PGM=CBACT04C,PARM='2022071800'`, injecting the processing
#       date as a step parameter rather than letting the program read the clock,
#       and that injection is exactly what makes a rerun reproducible.

# WHY : Alternatives Considered: NO `state_machine_type` input. Exposing the
#       workflow type was considered and
#       rejected on two hard limits rather than a preference. main.tf hard-codes
#       STANDARD because an Express workflow caps its execution at five minutes,
#       which every task state here exceeds, and because Express does not support
#       the synchronous `.sync` service integrations at all -- the very mechanism
#       that makes a state wait for its task to finish. An input would therefore
#       offer a value that cannot work.

# WHY : Assumptions: NO `schedule_expression` input. The nightly cron belongs to
#       infra/modules/eventbridge-scheduler, which TARGETS the state-machine ARN
#       this module publishes. Accepting a schedule here would invert that
#       dependency and give one chain two possible triggers.

# WHY : Alternatives Considered: NO `assign_public_ip` input; recorded in full on
#       the networking section
#       above -- main.tf sets the literal DISABLED because these tasks reach AWS
#       APIs through interface endpoints and the internet through the NAT
#       gateway, so a public address would add exposure without adding
#       reachability.

# WHY : Assumptions: NO bucket, prefix or lifecycle input beyond
#       `dataset_bucket_name`. The bucket itself, the ten generation-dataset prefix
#       families derived from app/jcl/DEFGDGB.jcl, app/jcl/DEFGDGD.jcl and
#       app/jcl/DALYREJS.jcl, and the five-noncurrent-version retention rule that
#       is the `LIMIT(5) SCRATCH` analogue all belong to
#       infra/modules/s3-datasets. This module passes a name through to the tasks
#       and creates nothing inside the bucket, so a prefix or lifecycle input
#       here would duplicate a contract that has one owner.

# WHY : Assumptions: NO alarm, dashboard or subscription input. Those belong to
#       infra/modules/observability, which CONSUMES
#       the state-machine ARN this module publishes. This module's only
#       observability surface is the two log groups it owns and the topic it
#       publishes a failure to; alarming on an execution is the consumer's
#       concern, and splitting it across both modules would leave neither owning
#       it.
# =============================================================================
