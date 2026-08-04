# =============================================================================
# infra/modules/step-functions-batch/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input surface of the `step-functions-batch` module -- the
#   module that provisions the `carddemo-daily-batch` state machine, its
#   execution role and its log group. That state machine is the migrated form of
#   the nightly job chain under app/jcl/: eleven states, of which the seven that
#   do record processing each run a Fargate task through the synchronous
#   run-task integration and wait for it, two bracket the window by setting and
#   clearing an online read-only flag, one refreshes table statistics, and one
#   stages the seed datasets. Every value a calling environment root can
#   configure is declared here, and nothing else is configurable: anything
#   absent from the list below is a property of the state machine fixed in
#   main.tf, not a per-environment choice.
#
#   Nothing here is read from the ambient environment and nothing is generated
#   inside the module. Every input arrives from infra/envs/dev/main.tf or
#   infra/envs/prod/main.tf, which is what keeps the only differences between
#   the two environments visible in their own terraform.tfvars files rather than
#   hidden in this module.
#
# Parameters -- thirteen required, fifteen optional:
#   environment                       string       REQUIRED. Names the machine.
#   ecs_cluster_arn                   string       REQUIRED. Cluster every task
#                                                  state runs in.
#   batch_task_definition_arn         string       REQUIRED. The batch-service
#                                                  task the job states run.
#   data_migration_task_definition_arn string      REQUIRED. The ETL task the
#                                                  staging state runs.
#   private_app_subnet_ids            list(string) REQUIRED. Task placement.
#   security_group_ids                list(string) REQUIRED. Task network.
#   task_role_arns                    list(string) REQUIRED. Roles the machine
#                                                  is allowed to pass.
#   quiesce_function_arn              string       REQUIRED. Sets the flag.
#   resume_function_arn               string       REQUIRED. Clears the flag.
#   analyze_tables_function_arn       string       REQUIRED. Refreshes stats.
#   notification_topic_arn            string       REQUIRED. Failure sink.
#   dataset_bucket_name               string       REQUIRED. Generation store.
#   kms_key_arn                       string       REQUIRED. Log encryption.
#   name_prefix                       string       Common resource-name prefix.
#   tags                              map(string)  Module-specific tags.
#   batch_container_name              string       Override target in the batch
#                                                  task definition.
#   data_migration_container_name     string       The same, for the ETL task.
#   seed_dataset_names                list(string) Branches of the staging map.
#   default_state_timeout_seconds     number       Per-state timeout floor.
#   state_timeout_seconds_overrides   map(number)  Per-state exceptions.
#   retry_max_attempts                number       Retries per state.
#   retry_interval_seconds            number       First backoff interval.
#   retry_backoff_rate                number       Backoff multiplier.
#   posting_warn_return_code          number       Highest exit status the
#                                                  posting state may return and
#                                                  still continue.
#   log_retention_days                number       Execution-log retention.
#   log_level                         string       Execution-log verbosity.
#   include_execution_data            bool         Whether state input and
#                                                  output are logged.
#   tracing_enabled                   bool         X-Ray tracing.
#
#   Each block below carries the full `type` and `description` that tflint's
#   terraform_typed_variables and terraform_documented_variables rules require;
#   the summary above is a map of the surface, not a second copy of it.
#
# Return values:
#   None. A variables.tf declares no output, so the state machine ARN this
#   module publishes -- the value infra/modules/eventbridge-scheduler consumes
#   as its own `state_machine_arn` input, and the value
#   services/reporting-service starts an execution against for an on-demand
#   report -- is declared in infra/modules/step-functions-batch/outputs.tf.
#
# Errors / Exceptions:
#   Thirteen inputs have no default, so omitting any one of them stops the
#   calling root at `plan` with a missing-required-argument error rather than
#   provisioning a state machine that would fail on its first invocation. Every
#   input that names an AWS resource additionally carries a `validation` block
#   asserting the service and resource-type fields of the identifier, because a
#   wrong-but-well-formed identifier here produces a valid plan, a clean apply
#   and a failure that first appears in the schedule's dead-letter queue,
#   detached from the change that caused it. The remaining `validation` blocks
#   reject out-of-domain numbers and unknown enumerated values at plan time
#   instead of letting the service reject them partway through an apply.
#
# WHY (non-obvious design decisions):
#   - Assumptions: NO ARN, account identifier or bucket name has a default, and
#     none ever may. An ARN embeds an AWS account identifier, and the project's
#     no-secrets-in-source constraint admits no exception. A specimen default,
#     added so a reader can see the expected shape, is therefore refused rather
#     than overlooked -- the shape is conveyed by the `type`, the `validation`
#     pattern and the description instead. The same reasoning governs the
#     sibling infra/modules/kms and infra/modules/eventbridge-scheduler input
#     surfaces.
#   - Assumptions: the eleven states themselves are NOT inputs. Their order,
#     their catch handlers and the inverted condition predicates that replace
#     the baseline's `COND=` parameters are the module's substance and live in
#     main.tf. Exposing the state list would let one environment run a
#     different chain from the other, which is precisely the topology
#     divergence the two roots are required not to have.
#   - Trade-offs: retry and timeout are exposed as a floor plus a per-state
#     override map rather than as one input per state. Eleven timeout inputs
#     would document themselves but would also have to be kept in step with
#     main.tf every time a state is renamed; a map lets a root override only the
#     states whose duration it actually knows differs, and leaves the rest on
#     one deliberately conservative default.
#   - Assumptions: the business date is NOT an input here. app/jcl/INTCALC.jcl
#     line 22 runs `PGM=CBACT04C,PARM='2022071800'`, injecting the processing
#     date rather than letting the program read the clock, and that property is
#     what makes a rerun reproducible. It is preserved by passing the date as a
#     container command argument at EXECUTION time, from the schedule's input or
#     the operator's `StartExecution` call. A Terraform variable would freeze
#     one date into the infrastructure, which is the opposite of the baseline's
#     behaviour.
#   - Where a comment below reasons about `terraform apply` or an execution, it
#     is describing what an input MEANS at those points, not reporting on a
#     provisioned stack. This tree is authored and statically validated --
#     formatted, validated, planned, linted and policy-scanned; applying it to a
#     live account is an operator action outside this scope.
# =============================================================================

# -----------------------------------------------------------------------------
# Naming and environment
# -----------------------------------------------------------------------------

# WHY this is required with no default. Trade-offs: a defaulted environment name
# is the exact mechanism by which a production schedule ends up starting a
# development state machine. The caller omits the argument, the module names the
# machine anyway, and the mistake stays invisible because the plan is clean and
# the name reads as deliberate. Requiring the value costs each root one line and
# converts that class of error into a missing-required-argument failure before
# anything is created.
#
# WHY the accepted values are exactly two. Assumptions: two environment roots
# exist, infra/envs/dev and infra/envs/prod, and this module is called only from
# those two. A third value would name a state machine whose state no root
# tracks, so the check asserts the shape of the tree rather than a naming
# preference.
variable "environment" {
  description = "Environment name suffixed onto the state machine, its log group and its execution role, so one environment's nightly chain is distinguishable from the other's in the console and in every IAM policy that names it; must be `dev` or `prod`, the two environments that have a Terraform root under infra/envs/."
  type        = string

  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment must be exactly \"dev\" or \"prod\", matching the environment root under infra/envs/ that calls this module."
  }
}

# WHY the characters are checked rather than trusted. Trade-offs: this prefix is
# concatenated into a state machine name, a log group name and a role name, and
# the three accept different character sets. Restricting the prefix to lowercase
# letters, digits and hyphens keeps it legal in all three at once, and catches a
# space, a dot or an uppercase letter at plan time instead of letting one of the
# three resources reject it partway through an apply. The cost paid is that a
# legitimately unusual prefix has to be spelled out here before it can be used.
#
# WHY the name and the default match the rest of the tree instead of being chosen
# here (Assumption): every directory under infra/ takes its prefix through a
# variable of this name with this default, which is what lets a root pass one
# value to every module it calls rather than each module negotiating its own.
variable "name_prefix" {
  description = "Prefix concatenated into the state machine, log group and execution role names ahead of the environment suffix, giving the nightly chain one greppable identity shared with the rest of the stack's resource names; lowercase letters, digits and hyphens only, at most 32 characters."
  type        = string
  default     = "carddemo"

  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", var.name_prefix)) && length(var.name_prefix) <= 32
    error_message = "name_prefix must be 1 to 32 characters of lowercase letters, digits and hyphens, beginning and ending with a letter or digit -- for example \"carddemo\"."
  }
}

# WHY an empty default reads as complete here rather than as an oversight
# (Assumption): the baseline tag set is not this module's to supply. Each calling
# root configures `default_tags` on its own `provider "aws"` block and the
# provider merges that map into every taggable resource it creates, so the state
# machine and its log group carry the root's common tags whether or not this
# variable is passed. What this input adds is the layer above that. Without this
# note an empty default looks like missing tagging, and it is not.
variable "tags" {
  description = "Tags merged onto the state machine and its log group, layered on top of the common tag set the calling root already applies through its provider's `default_tags`; defaults to none, because the baseline tags arrive from the root rather than from this module."
  type        = map(string)
  default     = {}
}

# -----------------------------------------------------------------------------
# Container task wiring -- what each work-performing state actually runs
#
# The four inputs in this section describe the two container tasks the state
# machine invokes. Seven states run the batch task with different command
# arguments; one state, the seed-dataset staging map, runs the ETL task. Each
# invocation uses the SYNCHRONOUS run-task integration, which starts the task
# and holds the state open until the task stops, so that the next state observes
# a completed step exactly as a JCL step observed its predecessor's completion.
#
# WHY tasks rather than functions. Alternatives Considered: implementing the
# job states as functions was evaluated and rejected on a hard limit rather than
# a preference. A function's execution is capped at fifteen minutes, and the
# posting, interest and statement steps process the whole daily volume in one
# pass; a step that exceeds the cap fails with no partial result and no way to
# resume mid-record. The two states that DO use functions in this module -- the
# read-only flag bracket and the statistics refresh -- are each a single API call
# and are named as such below.
# -----------------------------------------------------------------------------

variable "ecs_cluster_arn" {
  description = "ARN of the ECS cluster every task state runs its task in. Published as an output by infra/modules/ecs-cluster and passed in by the environment root; the execution role's ecs:RunTask grant is scoped so that tasks may be started only in this cluster."

  type = string

  validation {
    # WHY : Assumptions: the shape is asserted because a wrong-but-well-formed
    #       ARN here yields a syntactically valid IAM policy and a state machine
    #       that applies without complaint, then fails at the first invocation
    #       with a permissions error naming a resource the reader did not know
    #       was involved. Checking the `ecs:` service field and the `:cluster/`
    #       resource type converts that into a plan-time error naming the input.
    #       Alternatives Considered: anchoring the pattern at the end, which
    #       would be tighter and is rejected -- a cluster name is otherwise
    #       unconstrained, so the pattern stops after the resource-type field
    #       rather than guessing at the name.
    condition     = can(regex("^arn:[a-z0-9-]+:ecs:[a-z0-9-]+:[0-9]{12}:cluster/", var.ecs_cluster_arn))
    error_message = "ecs_cluster_arn must be an ECS cluster ARN of the form arn:<partition>:ecs:<region>:<account-id>:cluster/<name>."
  }
}

variable "batch_task_definition_arn" {
  description = "ARN of the task definition the seven job states run, which is the batch-service image. Each state overrides only that task's container command, so one task definition serves the whole chain and the per-step arguments stay in the state machine where the step order is also expressed."

  type = string

  validation {
    # WHY : Assumptions: the resource type is asserted, and the revision
    #       deliberately is not. An ARN without a trailing revision names the
    #       family and resolves to its ACTIVE revision at invocation, which is
    #       what lets a deployment publish a new revision without editing this
    #       infrastructure; an ARN with a revision pins the chain to one build.
    #       Both forms are therefore accepted, and the choice belongs to the
    #       caller.
    condition     = can(regex("^arn:[a-z0-9-]+:ecs:[a-z0-9-]+:[0-9]{12}:task-definition/", var.batch_task_definition_arn))
    error_message = "batch_task_definition_arn must be an ECS task-definition ARN of the form arn:<partition>:ecs:<region>:<account-id>:task-definition/<family>[:<revision>]."
  }
}

variable "data_migration_task_definition_arn" {
  description = "ARN of the task definition the seed-dataset staging state runs, which is the data-migration ETL image. It is a separate definition from the batch one because the two carry different images, different roles and different resource sizes; the staging step reads flat files and bulk-loads them, the job steps do not."

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

variable "batch_container_name" {
  description = "Name of the container inside the batch task definition whose command each job state overrides. The synchronous run-task integration matches an override to a container by name, so a value that does not appear in the definition is rejected by the service at invocation rather than at apply."
  type        = string
  default     = "batch"

  validation {
    # WHY : Trade-offs: only the character set is checked, not the existence of
    #       the container. Terraform cannot read the task definition's container
    #       list from an ARN that may not carry a revision, so asserting
    #       existence is not available at plan time; asserting the character set
    #       still catches the empty string and stray whitespace, which are the
    #       two ways this input silently produces an unmatched override.
    condition     = can(regex("^[a-zA-Z0-9][a-zA-Z0-9_-]*$", var.batch_container_name))
    error_message = "batch_container_name must be a container name of letters, digits, hyphens and underscores, beginning with a letter or digit."
  }
}

variable "data_migration_container_name" {
  description = "Name of the container inside the data-migration task definition whose command the staging state overrides, matched by the run-task integration exactly as the batch container name is."
  type        = string
  default     = "data-migration"

  validation {
    condition     = can(regex("^[a-zA-Z0-9][a-zA-Z0-9_-]*$", var.data_migration_container_name))
    error_message = "data_migration_container_name must be a container name of letters, digits, hyphens and underscores, beginning with a letter or digit."
  }
}

# -----------------------------------------------------------------------------
# Task network placement
# -----------------------------------------------------------------------------

variable "private_app_subnet_ids" {
  description = "Private application subnet identifiers the state machine places each task into. Published as an output by infra/modules/network and passed in by the environment root; batch tasks reach the database through these subnets and reach AWS APIs through that VPC's interface endpoints, so they need no public address."

  type = list(string)

  validation {
    # WHY : Assumptions: both the count and the prefix are checked. An empty
    #       list is accepted by the type system and rejected by the service only
    #       once a task is started, which is at the first nightly invocation
    #       rather than at apply. The three-subnet floor is not a style
    #       preference either: the surrounding architecture is three
    #       availability zones, and placing batch tasks in fewer would make one
    #       zone's loss stop the nightly chain in an environment whose whole
    #       point is that it does not.
    condition     = length(var.private_app_subnet_ids) >= 3 && alltrue([for s in var.private_app_subnet_ids : can(regex("^subnet-[0-9a-f]{8,}$", s))])
    error_message = "private_app_subnet_ids must list at least three subnet identifiers, each of the form subnet-<hex>, one per availability zone."
  }
}

variable "security_group_ids" {
  description = "Security groups attached to every task the state machine starts. These are what permit the egress a batch step actually needs -- the database port to Aurora and 443 to the VPC interface endpoints -- and nothing wider."

  type = list(string)

  validation {
    # WHY : Assumptions: an empty list would leave the service to apply the
    #       VPC's default security group, which is a permissive group nobody
    #       chose for this workload, and that substitution is silent. Requiring
    #       at least one entry makes the group an explicit decision.
    condition     = length(var.security_group_ids) >= 1 && alltrue([for g in var.security_group_ids : can(regex("^sg-[0-9a-f]{8,}$", g))])
    error_message = "security_group_ids must list at least one security group identifier, each of the form sg-<hex>."
  }
}

variable "task_role_arns" {
  description = "IAM role ARNs the state machine's execution role is permitted to pass to a task it starts, which is the task role and the task execution role of both task definitions. Starting a task requires iam:PassRole on exactly the roles the definition names, so this list is the least-privilege boundary of what the nightly chain can run as."

  type = list(string)

  validation {
    # WHY : Trade-offs: the grant is enumerated rather than written as a
    #       wildcard over the account's roles. A wildcard is one line shorter and
    #       would let this state machine start a task running as ANY role in the
    #       account, which converts a state-machine definition into a privilege
    #       escalation path. Enumerating costs the caller a list it already has,
    #       since every entry is another module's output.
    condition     = length(var.task_role_arns) >= 1 && alltrue([for r in var.task_role_arns : can(regex("^arn:[a-z0-9-]+:iam::[0-9]{12}:role/", r))])
    error_message = "task_role_arns must list at least one IAM role ARN, each of the form arn:<partition>:iam::<account-id>:role/<name>."
  }
}

# -----------------------------------------------------------------------------
# Function-backed states -- the operator bracket and the statistics refresh
#
# Three of the eleven states are a single API call each and are therefore backed
# by functions rather than tasks. They are declared as separate inputs, not one
# list, because each is invoked at a specific position in the chain and a list
# would lose which is which.
#
# WHY these three are functions while the rest are tasks (Alternatives
# Considered): a fifteen-minute execution ceiling rules a function out for a
# record-processing step, and rules nothing out for a step that writes one
# parameter or issues one statement. Running them as Fargate tasks for
# uniformity was considered and rejected: it would pay a task cold start and a
# task definition's worth of configuration for a call that completes in
# milliseconds.
# -----------------------------------------------------------------------------

variable "quiesce_function_arn" {
  description = "ARN of the function the first state invokes to set the online read-only flag, opening the batch window. This is the migrated form of app/jcl/CLOSEFIL.jcl, which closed the CICS files with an operator command; the flag is a parameter the services read, so the mechanism changes while the bracket around the window does not."

  type = string

  validation {
    # WHY : Assumptions: the `lambda:` service field and the `:function:`
    #       resource type are asserted because an ARN of any other service is
    #       accepted into the plan and rejected only when the state runs, which
    #       is in the middle of the night at the head of the chain. A version or
    #       alias qualifier adds a further colon-delimited segment, so the
    #       pattern deliberately does not anchor at the end.
    condition     = can(regex("^arn:[a-z0-9-]+:lambda:[a-z0-9-]+:[0-9]{12}:function:", var.quiesce_function_arn))
    error_message = "quiesce_function_arn must be a Lambda function ARN of the form arn:<partition>:lambda:<region>:<account-id>:function:<name>[:<qualifier>]."
  }
}

variable "resume_function_arn" {
  description = "ARN of the function the last state invokes to clear the online read-only flag, closing the batch window. This is the migrated form of app/jcl/OPENFIL.jcl, and it is the counterpart of the quiesce state: the chain must clear the flag it set, on the success path and on the failure path alike, or the online services stay read-only after the window ends."

  type = string

  validation {
    condition     = can(regex("^arn:[a-z0-9-]+:lambda:[a-z0-9-]+:[0-9]{12}:function:", var.resume_function_arn))
    error_message = "resume_function_arn must be a Lambda function ARN of the form arn:<partition>:lambda:<region>:<account-id>:function:<name>[:<qualifier>]."
  }
}

variable "analyze_tables_function_arn" {
  description = "ARN of the function the penultimate state invokes to refresh table statistics after the night's writes. It replaces app/jcl/TRANIDX.jcl only in part: that job REBUILT an alternate index, and index building is retired because PostgreSQL maintains indexes inside the same transaction as the write, leaving statistics as the only part of the step with a target."

  type = string

  validation {
    condition     = can(regex("^arn:[a-z0-9-]+:lambda:[a-z0-9-]+:[0-9]{12}:function:", var.analyze_tables_function_arn))
    error_message = "analyze_tables_function_arn must be a Lambda function ARN of the form arn:<partition>:lambda:<region>:<account-id>:function:<name>[:<qualifier>]."
  }
}

# -----------------------------------------------------------------------------
# Failure notification and dataset location
# -----------------------------------------------------------------------------

variable "notification_topic_arn" {
  description = "ARN of the topic every state's catch handler publishes to before the execution fails. The baseline reported a failed step through `NOTIFY=&SYSUID` on the job card and the job log; this is that path's replacement, and routing every catch through one topic is what makes a failure in any of the eleven states reach the same place."

  type = string

  validation {
    # WHY : Assumptions: asserting the `sns:` service field catches the
    #       plausible substitution of a queue ARN, which the catch handler would
    #       accept into a valid plan and then fail to publish to at exactly the
    #       moment something has already gone wrong -- the worst time to lose a
    #       notification.
    condition     = can(regex("^arn:[a-z0-9-]+:sns:[a-z0-9-]+:[0-9]{12}:", var.notification_topic_arn))
    error_message = "notification_topic_arn must be an SNS topic ARN of the form arn:<partition>:sns:<region>:<account-id>:<topic-name>."
  }
}

variable "dataset_bucket_name" {
  description = "Name of the versioned bucket the staging, backup, combine, statement and report states read and write dataset generations in. Published as an output by infra/modules/s3-datasets and passed in by the environment root; the execution role and the task overrides both reference it, so it is supplied once rather than twice."

  type = string

  validation {
    # WHY : Assumptions: the rules asserted are the service's own naming rules
    #       for a bucket -- 3 to 63 characters, lowercase letters, digits,
    #       hyphens and dots, beginning and ending alphanumeric. A name breaking
    #       them cannot be created and cannot be referenced, and without this
    #       check the failure surfaces as a policy that silently matches nothing.
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.dataset_bucket_name))
    error_message = "dataset_bucket_name must be a valid S3 bucket name: 3 to 63 characters of lowercase letters, digits, hyphens and dots, beginning and ending with a letter or digit."
  }
}

variable "seed_dataset_names" {
  description = "Dataset names the seed-staging state iterates over, one parallel branch per name, each branch running the ETL task for that dataset. The default lists the eleven datasets the migration's readers are written against, so a caller that stages the standard set supplies nothing."

  type = list(string)

  # Assumptions: the eleven default entries are not an arbitrary selection. They
  # are the datasets for which a fixed-width reader exists, each pinned to one
  # copybook layout and one record length: usrsec at 80 bytes, acctdata at 300,
  # carddata at 150, custdata at 500, cardxref at 50, dalytran at 350, transact
  # at 350, discgrp at 50, trancatg at 60, trantype at 60 and tcatbalf at 50.
  # Staging a dataset with no reader would start a branch that fails on an
  # unknown layout; omitting one that HAS a reader would leave a master table
  # empty and the failure would surface much later, as a posting reject rather
  # than as a load error.
  #
  # WHY the list is an input at all when the readers fix it. Trade-offs: a caller
  # occasionally needs to stage a subset -- reloading one master after a
  # correction, rather than the whole set. Exposing the list makes that a tfvars
  # change instead of a module edit. The accepted cost is that a caller CAN pass
  # a name no reader supports, which the validation below cannot detect because
  # the reader set lives in the ETL package rather than in Terraform.
  default = [
    "usrsec",
    "acctdata",
    "carddata",
    "custdata",
    "cardxref",
    "dalytran",
    "transact",
    "discgrp",
    "trancatg",
    "trantype",
    "tcatbalf",
  ]

  validation {
    # WHY : Assumptions: each name becomes a path segment in the dataset
    #       prefix and a branch name in the map state, so it is restricted to
    #       characters legal in both. An empty list is rejected because a map
    #       state with no branches succeeds instantly, which would report a
    #       staging step as complete having loaded nothing.
    condition     = length(var.seed_dataset_names) >= 1 && alltrue([for d in var.seed_dataset_names : can(regex("^[a-z0-9][a-z0-9-]*$", d))])
    error_message = "seed_dataset_names must list at least one name, each of lowercase letters, digits and hyphens, beginning with a letter or digit."
  }
}

# -----------------------------------------------------------------------------
# Timeout, retry and the graded return code
#
# These are the inputs that carry the baseline's step-gating semantics into the
# target. Two of them deserve to be read together with app/jcl, because the
# translation is where this module is easiest to get backwards.
# -----------------------------------------------------------------------------

variable "default_state_timeout_seconds" {
  description = "Timeout applied to every state that has no entry in the override map. A state without a timeout waits indefinitely, so a task that hangs holds the whole chain open and the online read-only flag stays set until an operator intervenes; a conservative default is therefore safer than none."
  type        = number
  default     = 3600

  validation {
    # WHY : Trade-offs: the floor is one minute and the ceiling six hours. The
    #       floor rejects a value so small that a healthy task is killed during
    #       its own startup, which presents as an intermittent failure rather
    #       than as a misconfiguration. The ceiling is the point past which a
    #       timeout stops being a safety net: a chain allowed to sit for longer
    #       than a night has already missed the window the schedule exists to
    #       fit inside.
    condition     = var.default_state_timeout_seconds >= 60 && var.default_state_timeout_seconds <= 21600 && floor(var.default_state_timeout_seconds) == var.default_state_timeout_seconds
    error_message = "default_state_timeout_seconds must be a whole number of seconds from 60 to 21600 inclusive."
  }
}

variable "state_timeout_seconds_overrides" {
  description = "Per-state timeout exceptions, keyed by the state name exactly as main.tf spells it, for the states whose duration is known to differ from the default. Empty by default, because a timeout that has not been measured is better left at the module's conservative floor than guessed at per state."
  type        = map(number)
  default     = {}

  validation {
    # WHY : Assumptions: the same bounds as the default are asserted for every
    #       entry, because an override is the same kind of value and an
    #       unbounded one reintroduces exactly the hang this section guards
    #       against. A key naming a state that does not exist is NOT detectable
    #       here -- the state names live in main.tf and Terraform cannot check a
    #       map key against them -- so main.tf reads this map with a lookup
    #       against the default rather than indexing it, and a stale key is
    #       inert rather than fatal.
    condition     = alltrue([for t in values(var.state_timeout_seconds_overrides) : t >= 60 && t <= 21600 && floor(t) == t])
    error_message = "Every value in state_timeout_seconds_overrides must be a whole number of seconds from 60 to 21600 inclusive."
  }
}

variable "retry_max_attempts" {
  description = "Retry attempts each state makes after its first failure, before its catch handler runs. This is the per-state half of the durable retry tier; the other half is that a failed execution can be redriven from the state that failed, and that the batch run ledger makes a step which already completed a no-op when it is retried."
  type        = number
  default     = 3

  validation {
    # WHY : Trade-offs: zero is permitted and is a legitimate choice for a
    #       caller that wants a failure to surface immediately, so the floor is
    #       0 rather than 1. The ceiling of 10 exists because retrying a
    #       record-processing step is not free: each attempt reruns the step from
    #       its beginning, and a step whose failure is deterministic will fail
    #       ten times and then fail once more, having spent the entire batch
    #       window doing it.
    condition     = var.retry_max_attempts >= 0 && var.retry_max_attempts <= 10 && floor(var.retry_max_attempts) == var.retry_max_attempts
    error_message = "retry_max_attempts must be a whole number from 0 to 10 inclusive."
  }
}

variable "retry_interval_seconds" {
  description = "Seconds a state waits before its first retry. Subsequent waits are this interval multiplied by the backoff rate, compounding per attempt, so this value and the rate together set how long a retrying state can occupy the batch window."
  type        = number
  default     = 30

  validation {
    condition     = var.retry_interval_seconds >= 1 && var.retry_interval_seconds <= 600 && floor(var.retry_interval_seconds) == var.retry_interval_seconds
    error_message = "retry_interval_seconds must be a whole number of seconds from 1 to 600 inclusive."
  }
}

variable "retry_backoff_rate" {
  description = "Multiplier applied to the retry interval on each successive attempt. A value of 1 makes every wait equal to the interval, which is a flat retry rather than a backoff; higher values grow the wait geometrically."
  type        = number
  default     = 2.0

  validation {
    # WHY : Assumptions: the floor is 1.0 because a rate below one SHRINKS each
    #       successive wait, which turns a backoff into an accelerating retry
    #       against a dependency that is already failing -- the opposite of what
    #       the mechanism is for, and accepted silently without this check.
    condition     = var.retry_backoff_rate >= 1.0 && var.retry_backoff_rate <= 10.0
    error_message = "retry_backoff_rate must be between 1.0 and 10.0 inclusive; a value below 1.0 would shorten each successive wait instead of lengthening it."
  }
}

variable "posting_warn_return_code" {
  description = "Highest process exit status the transaction-posting state may return and still let the chain continue. This is the migrated form of the baseline's graded condition code, and it is the reason the posting state is followed by a choice rather than by a plain success edge."

  type    = number
  default = 4

  # Assumptions: the default of 4 is recovered from the baseline and is not a
  # convention. app/cbl/CBTRN02C.cbl line 229 to line 230 sets RETURN-CODE to 4
  # when its reject count is greater than zero, and the downstream step is gated
  # by `COND=(4,LT)` at app/jcl/TRANBKP.jcl line 51 -- a JCL COND is a SKIP
  # predicate, so "4 is less than the return code" means the step is skipped
  # only when the code EXCEEDS 4, and therefore runs when the code is 4 or
  # lower. A posting run that correctly rejected some transactions is a warn, not
  # a failure, and the chain continues.
  #
  # WHY this must be a predicate on a choice and not a catch (Refactoring
  # Rationale): the naive translation of `COND=` is a retry or a catch handler,
  # and both are wrong in the same way. A catch treats exit status 4 as an error
  # and aborts the night, losing the backup, the statements and the reports over
  # rejects the baseline tolerated by design. Inverting the predicate into a
  # choice -- continue while the status is at or below this value, notify and
  # fail above it -- preserves the graded tier instead of collapsing it into
  # pass-or-fail.
  #
  # WHY the tolerance stops here. Trade-offs: this graded rubric belongs to the
  # batch chain alone. It is deliberately NOT applied to any build or lint gate
  # in this repository, where a check either passes or fails the build; letting
  # an `rc <= 4` tolerance leak into a gate would turn a real failure into a
  # tolerated warning.
  validation {
    condition     = var.posting_warn_return_code >= 0 && var.posting_warn_return_code <= 255 && floor(var.posting_warn_return_code) == var.posting_warn_return_code
    error_message = "posting_warn_return_code must be a whole number from 0 to 255 inclusive, the range a process exit status occupies."
  }
}

# -----------------------------------------------------------------------------
# Execution logging and tracing
# -----------------------------------------------------------------------------

variable "log_retention_days" {
  description = "Days the state machine's execution log group retains events. This is one of the retention values the dev and prod roots are permitted to set differently without changing the stack's shape, and it is the record of which states ran on which night."
  type        = number
  default     = 30

  validation {
    # WHY : Assumptions: the set is closed because the service accepts only
    #       these values and silently nothing else -- an arbitrary number is
    #       rejected during apply, after other resources exist. 0 is excluded
    #       deliberately even though the service reads it as never expire:
    #       unbounded retention on an execution log is a cost that grows without
    #       anyone deciding to accept it, and a caller who wants it can say so by
    #       widening this check rather than by passing a value that looks like a
    #       mistake.
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.log_retention_days)
    error_message = "log_retention_days must be one of the retention periods CloudWatch Logs accepts: 1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288 or 3653."
  }
}

variable "log_level" {
  description = "Which execution events reach the log group. `ERROR` records only failures, `FATAL` only terminal ones, `ALL` records every state transition and `OFF` records nothing."
  type        = string
  default     = "ALL"

  validation {
    # WHY : Trade-offs: the default is the most verbose setting rather than the
    #       cheapest. A nightly chain runs once, so the volume is bounded by
    #       eleven states rather than by request rate, and the first question
    #       asked after a failure is which state failed and what it received --
    #       which only the full transition history answers. `OFF` remains
    #       available and is deliberately left in the accepted set, because
    #       forcing logging on would be a policy this module has no standing to
    #       impose.
    condition     = contains(["ALL", "ERROR", "FATAL", "OFF"], var.log_level)
    error_message = "log_level must be one of ALL, ERROR, FATAL or OFF."
  }
}

variable "include_execution_data" {
  description = "Whether each logged event carries the state's input and output payloads as well as the transition itself. The payloads in this chain are job names, business dates and dataset prefixes rather than record data, which is what makes recording them safe."
  type        = bool
  default     = true

  # WHY the default is on. Assumptions: what a state received is the difference
  # between knowing that the interest state failed and knowing that it failed for
  # a particular business date. The payloads carry no cardholder data -- record
  # data never enters the state machine, only the arguments naming what to
  # process -- so the usual reason to suppress execution data does not apply
  # here. It stays an input because that property is a claim about the current
  # chain, and a future state that passed record data through its payload would
  # have to turn this off.
}

variable "tracing_enabled" {
  description = "Whether executions are traced end to end, so a nightly run appears as one trace spanning its task and function invocations rather than as eleven unrelated ones."
  type        = bool
  default     = true

  # WHY the default is on. Trade-offs: tracing costs per recorded trace, and a
  # chain that runs once a night records one. Against that, the question this
  # module's failures raise is almost always where in the chain time went or
  # where a call failed, and a per-execution trace answers it without
  # reconstructing a timeline from log timestamps across three services.
}

# -----------------------------------------------------------------------------
# Encryption
# -----------------------------------------------------------------------------

variable "kms_key_arn" {
  description = "ARN of the customer-managed key the execution log group is encrypted with. Required rather than optional, because every CICS file in the baseline was defined RECOVERY(NONE) JOURNAL(NO) and encryption at rest is one of the properties this migration adds; making the key mandatory is what keeps that addition from being skippable."

  type = string

  validation {
    # WHY : Assumptions: both the `kms:` service field and the `:key/`
    #       resource type are asserted, because an alias ARN -- which is the
    #       plausible wrong value, since an alias is what a human reads in the
    #       console -- has resource type `alias/` and is not accepted where a
    #       log group expects a key. Catching that here names the input; letting
    #       it through produces an apply-time failure against the log group.
    condition     = can(regex("^arn:[a-z0-9-]+:kms:[a-z0-9-]+:[0-9]{12}:key/", var.kms_key_arn))
    error_message = "kms_key_arn must be a KMS key ARN of the form arn:<partition>:kms:<region>:<account-id>:key/<key-id>, not an alias ARN."
  }
}
