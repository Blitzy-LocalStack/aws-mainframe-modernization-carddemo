# =============================================================================
# infra/modules/step-functions-batch/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input contract of the `step-functions-batch` module -- the
#   module that replaces the mainframe JCL/JES2 nightly job stream with the
#   twelve-work-state `carddemo-daily-batch` state machine, a second and much
#   smaller state machine for on-demand reports, a third for the operator-invoked
#   dataset export/import round trip, one shared least-privilege execution role
#   and one encrypted log group per machine. Anything absent from this file is a
#   property of the three state machines fixed in main.tf rather than an
#   environment choice.
#
#   Reusable MODULE, never a Terraform root. Nothing is read from the ambient
#   environment and nothing is generated inside the module: every input arrives
#   from infra/envs/dev/main.tf or infra/envs/prod/main.tf, which is what keeps
#   the only differences between the two environments visible in their own
#   terraform.tfvars files instead of hidden in this module body.
#
# Parameters:
#   Ten groups, in declaration order: naming and tagging; ECS wiring; IAM;
#   networking; data and notification; observability; function-backed states;
#   seed-dataset staging; timing and resilience; and the two ceilings of the
#   operator-invoked dataset round trip. Each block carries the `type`
#   and `description` that tflint's terraform_typed_variables and
#   terraform_documented_variables rules require, and those descriptions are
#   the text terraform-docs injects into README.md.
#
#   Every input that names a resource another module owns is REQUIRED with no
#   default; every defaulted input is a name, a policy value or a sizing value
#   this module can pick a defensible starting point for.
#
#   Assumptions: no ARN, account identifier, region, endpoint, bucket name or
#   credential has a default, and none ever may -- an ARN embeds an AWS account
#   identifier, and the project constraint that nothing of the sort is
#   committed to this repository admits no exception. The expected shape is
#   carried by the `type`, the `validation` pattern and the description instead
#   of by a specimen default.
#
#   Provenance: every identifier is another module's published output, wired by
#   the environment root -- the cluster from infra/modules/ecs-cluster; each
#   task definition, container name and role from an infra/modules/ecs-service
#   instance; the subnets and security group from infra/modules/network; the
#   dataset bucket from infra/modules/s3-datasets; the notification topic from
#   infra/modules/observability; the log-group key from infra/modules/kms. The
#   three function ARNs and the read-only flag parameter name are owned by the
#   environment root itself. Nothing is fetched here by a `data` lookup or a
#   remote-state read, because a module that resolves its own dependencies
#   cannot be composed differently by a different caller.
#
#   Assumptions: the twelve states are NOT inputs. Their order, their catch
#   handlers and the inverted condition predicates that replace the baseline's
#   `COND=` parameters are this module's substance and live in main.tf.
#   Exposing the state list would let one environment run a different chain
#   from the other, which is the topology divergence the two roots are required
#   not to have. For the same reason the posting condition-code contract (0 and
#   4, the only codes app/cbl/CBTRN02C.cbl:230 can produce) is a local beside
#   the predicate it decides, not a configurable ceiling.
#
#   Assumptions: the business date is NOT an input. app/jcl/INTCALC.jcl:22 runs
#   `PGM=CBACT04C,PARM='2022071800'`, injecting the processing date rather than
#   letting the program read the clock, and that is what makes a rerun
#   reproducible. It is preserved by passing the date as a container command
#   argument at execution time; a Terraform variable would freeze one date into
#   the infrastructure.
#
#   Trade-offs: per-state timing is ONE `map(number)` carrying all twelve state
#   names rather than a floor plus a sparse override map. A state left out of
#   sparse overrides silently inherits a ceiling nobody chose for it, and a
#   mistyped key does the same while the operator believes a limit was raised;
#   requiring every key turns both into a plan-time error naming the variable.
#   The accepted cost is that adding a state means extending the default and
#   its validation together.
#
#   Refactoring Rationale: the round trip's two states carry their ceilings in a
#   SECOND map, `dataset_state_timeout_seconds`, rather than as two more keys in
#   the twelve-name map above. Merging them would have widened that map's
#   exactness check from twelve names to fourteen, and that check is what catches
#   a missing or misspelled NIGHTLY ceiling -- the failure it exists to prevent.
#   Two maps, each exact over its own machine's states, keeps both checks as
#   strong as they were.
#
#   Trade-offs: `log_group_kms_key_arn` is nullable with a null default, where
#   the encryption posture argues for requiring it. Customer-managed encryption
#   at rest is a property this migration adds rather than preserves (every CICS
#   file in the baseline was `RECOVERY(NONE) JOURNAL(NO)`,
#   app/csd/CARDDEMO.CSD:1-89), so a module with no log-group key yet is still
#   usable rather than merely less encrypted; both roots do supply the key.
#
# Return values:
#   None. A variables.tf declares no output. outputs.tf publishes the daily,
#   ad-hoc and dataset round-trip state-machine identities separately, because
#   infra/modules/eventbridge-scheduler starts an execution of the first,
#   services/reporting-service starts an execution of the second, and an operator
#   or automation starts an execution of the third.
#
# Exceptions or errors:
#   Omitting a required input stops the calling root with a
#   missing-required-argument error before anything is created, rather than
#   provisioning a chain that fails on its first invocation. Every input except
#   `tags`, a free-form map whose contents are the caller's to choose, and
#   `log_include_execution_data`, a boolean whose type is already its whole
#   domain, carries a `validation` block. Every input naming an AWS resource
#   has its service and resource-type fields asserted, because a
#   wrong-but-well-formed identifier produces a valid plan, a clean apply, and
#   a failure that first appears in the schedule's dead-letter queue detached
#   from the change that caused it.
# =============================================================================

# -----------------------------------------------------------------------------
# Naming and tagging
# -----------------------------------------------------------------------------

# Assumptions: every directory under infra/ takes its prefix through a
#   variable of this name with this same default, which is what lets a root
#   pass one value to every module it calls instead of each module
#   negotiating its own. Fifteen of the sixteen modules in this package
#   share it, so the default is the tree's convention rather than a
#   preference expressed here; it is also a project name, not an account
#   identifier or an endpoint, so it carries none of the disclosure risk
#   that forbids a default on the identifier inputs below.
variable "name_prefix" {
  description = "Prefix concatenated into the state machine, log group and execution role names ahead of the environment suffix, giving the nightly chain one greppable identity shared with the rest of the stack's resource names. Passed in by the environment root, which hands the same value to every module it calls; lowercase letters, digits and hyphens only, at most 32 characters."
  type        = string
  default     = "carddemo"

  validation {
    # Trade-offs: a state machine name, a log group name and an IAM role
    #   name accept different character sets. Restricting the prefix to
    #   lowercase letters, digits and hyphens keeps it legal in all three at
    #   once and catches a space, a dot or an uppercase letter at plan time
    #   instead of letting whichever resource is created first reject it
    #   partway through an apply. The cost accepted is that a legitimately
    #   unusual prefix has to be permitted here before it can be used.
    condition     = can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", var.name_prefix)) && length(var.name_prefix) <= 32
    error_message = "name_prefix must be 1 to 32 characters of lowercase letters, digits and hyphens, beginning and ending with a letter or digit -- for example \"carddemo\"."
  }
}

# Trade-offs: this input is deliberately required with no default. A
#   defaulted environment name is the exact mechanism by which a production
#   schedule ends up starting a development state machine: the caller omits
#   the argument, the module names the machine anyway, and the mistake stays
#   invisible because the plan is clean and the name reads as deliberate.
#   Requiring the value costs each root one line and converts that class of
#   error into a missing-required-argument failure before anything exists.
variable "environment" {
  description = "Environment name suffixed onto the state machine, its log group and its execution role, so one environment's nightly chain is distinguishable from the other's in the console and in every IAM policy that names it; must be `dev` or `prod`, the two environments that have a Terraform root under infra/envs/."
  type        = string

  validation {
    # Assumptions: two environment roots exist, infra/envs/dev and
    #   infra/envs/prod, and this module is called only from those two. They
    #   are required to differ in sizing and retention and never in
    #   topology, so a third value would name a state machine whose state no
    #   root tracks. The check therefore asserts the shape of the tree
    #   rather than a naming preference.
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment must be exactly \"dev\" or \"prod\", matching the environment root under infra/envs/ that calls this module."
  }
}

# Trade-offs: an empty default reads as complete here rather than as
#   missing tagging, and without this note it would read as the latter. The
#   baseline tag set is not this module's to supply: each calling root
#   configures `default_tags` on its own `provider "aws"` block, and the
#   provider merges that map into every taggable resource it creates, so
#   these resources carry the root's common tags whether or not this input
#   is passed. What this input adds is the layer above that, for a tag that
#   applies to the batch orchestration alone.
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
# Alternatives Considered: implementing the job states as functions was
#   evaluated and rejected on a hard limit rather than a preference. A
#   function's execution is capped at fifteen minutes, and the posting,
#   interest and statement steps process a whole day's volume in one pass; a
#   step that exceeds the cap fails with no partial result and no way to
#   resume mid-record. The three states that DO use functions -- the
#   read-only flag bracket and the statistics refresh -- are one API call
#   each and are declared in their own section below.
# -----------------------------------------------------------------------------

# Assumptions: this input is used twice and both uses need the full ARN.
#   It is the `Cluster` parameter of every synchronous run-task state, and
#   it is also the value of the `ecs:cluster` condition that scopes
#   `ecs:StopTask` and `ecs:DescribeTasks` -- neither of which can be
#   scoped by resource, because a task ARN does not exist until RunTask
#   creates it. That second use is why an ARN is required here rather than a
#   bare cluster name: a name cannot appear in an ARN-comparison condition.
variable "ecs_cluster_arn" {
  description = "ARN of the ECS cluster every task state runs its task in. Published as an output by infra/modules/ecs-cluster and passed in by the environment root; it is also the value of the `ecs:cluster` condition that scopes the execution role's task-stopping and task-describing grants, which is why the full ARN is required rather than a cluster name."

  type = string

  validation {
    # Assumptions: a wrong-but-well-formed ARN yields a valid IAM policy and a
    #   state machine that applies without complaint, then fails at the first
    #   invocation with a permissions error naming a resource the reader did not
    #   know was involved. Asserting the `ecs:` service field and the
    #   `:cluster/` resource type converts that into a plan-time error naming
    #   this input; the pattern stops after the resource type because a cluster
    #   name is otherwise unconstrained.
    condition     = can(regex("^arn:[a-z0-9-]+:ecs:[a-z0-9-]+:[0-9]{12}:cluster/", var.ecs_cluster_arn))
    error_message = "ecs_cluster_arn must be an ECS cluster ARN of the form arn:<partition>:ecs:<region>:<account-id>:cluster/<name>."
  }
}

# Assumptions: one definition serves all five jobs because each state
#   overrides only the container command, so the per-step arguments stay in
#   the state machine where the step order is also expressed rather than
#   being spread across five near-identical task definitions.
variable "batch_task_definition_arn" {
  description = "ARN of the task definition the five batch job states run, which is the batch-service image. Published by the batch infra/modules/ecs-service instance and wired by the environment root. Each state overrides only that task's container command, so one task definition serves all five jobs and the per-step arguments stay in the state machine where the step order is also expressed."

  type = string

  validation {
    # Assumptions: the resource type is asserted and the revision
    #   deliberately is not. An ARN without a trailing revision names the
    #   family and resolves to its ACTIVE revision at invocation, which is
    #   what lets a deployment publish a new revision without editing this
    #   infrastructure; an ARN with a revision pins the chain to one build.
    #   Both are legitimate, so both are accepted and the choice belongs to
    #   the caller.
    condition     = can(regex("^arn:[a-z0-9-]+:ecs:[a-z0-9-]+:[0-9]{12}:task-definition/", var.batch_task_definition_arn))
    error_message = "batch_task_definition_arn must be an ECS task-definition ARN of the form arn:<partition>:ecs:<region>:<account-id>:task-definition/<family>[:<revision>]."
  }
}

# Assumptions: `ContainerOverrides` addresses a container by NAME, and an
#   override whose name matches nothing in the definition is not an error --
#   it is ignored. The task then starts with the command baked into its
#   image, so the wrong job runs, reports success, and the chain continues
#   past it. That silent-substitution failure mode is the whole reason this
#   is a separate declared input rather than a literal assumed to match.
variable "batch_container_name" {
  description = "Name of the container inside the batch task definition whose command each job state overrides. The environment root passes the name published by the batch ecs-service instance. An override addresses its container by name and a name that matches nothing is ignored rather than rejected, so a wrong value here silently runs the image's baked-in command instead of the intended job."
  type        = string
  default     = "batch"

  validation {
    # Trade-offs: only the character set is checked, not the container's
    #   existence. Terraform cannot read a definition's container list from
    #   an ARN that may carry no revision, so asserting existence is not
    #   available at plan time. Asserting the character set still catches
    #   the empty string and stray whitespace, which are the two ways this
    #   input produces an unmatched -- and therefore ignored -- override.
    condition     = can(regex("^[a-zA-Z0-9][a-zA-Z0-9_-]*$", var.batch_container_name))
    error_message = "batch_container_name must be a container name of letters, digits, hyphens and underscores, beginning with a letter or digit."
  }
}

# Assumptions: this is a separate definition from the batch one because
#   the two carry different images, different task roles and different
#   resource sizes. The staging branch reads a flat file and bulk-loads it;
#   the job states do neither.
variable "data_migration_task_definition_arn" {
  description = "ARN of the task definition each seed-staging map branch runs, which is the data-migration ETL image. Published by the data-migration infra/modules/ecs-service instance and wired by the environment root. It is separate from the batch definition because the two carry different images, roles and resource sizes."

  type = string

  validation {
    # Assumptions: same shape and same reasoning as the batch definition
    #   above; the two are validated separately rather than through one
    #   shared check so that an error message names the input the caller got
    #   wrong instead of the pair.
    condition     = can(regex("^arn:[a-z0-9-]+:ecs:[a-z0-9-]+:[0-9]{12}:task-definition/", var.data_migration_task_definition_arn))
    error_message = "data_migration_task_definition_arn must be an ECS task-definition ARN of the form arn:<partition>:ecs:<region>:<account-id>:task-definition/<family>[:<revision>]."
  }
}

# Assumptions: the same name-matching contract as the batch container, and
#   the same silent-substitution consequence if it is wrong -- a staging
#   branch that appears to succeed while loading nothing the caller asked
#   for is harder to notice than one that fails outright.
variable "data_migration_container_name" {
  description = "Name of the container inside the data-migration task definition whose command each staging branch overrides, matched by name exactly as the batch container name is, and published by the data-migration ecs-service instance."
  type        = string
  default     = "data-migration"

  validation {
    # Trade-offs: as for the batch container name, existence cannot be
    #   checked at plan time from an ARN, so the character set is the
    #   strongest available assertion.
    condition     = can(regex("^[a-zA-Z0-9][a-zA-Z0-9_-]*$", var.data_migration_container_name))
    error_message = "data_migration_container_name must be a container name of letters, digits, hyphens and underscores, beginning with a letter or digit."
  }
}

# Assumptions: this is a THIRD definition rather than a reuse of the batch one.
#   services/batch-service's job-name list holds no statement job and no report
#   job; the statement and report writers live in services/reporting-service, so
#   the states that replace app/jcl/CREASTMT.JCL and app/jcl/TRANREPT.jcl must
#   run that image. Pointing them at the batch definition would select a job
#   name that does not exist.
variable "reporting_task_definition_arn" {
  description = "ARN of the reporting-service task definition the two daily output states and the ad-hoc report machine run. Published by the reporting infra/modules/ecs-service instance and wired by the environment root. It is distinct from the batch definition because batch-service's job list contains no statement job and no report job -- those writers live in reporting-service."

  type = string

  validation {
    # Assumptions: validating separately keeps a malformed reporting ARN
    #   attributed to the input that feeds the statement and report states
    #   rather than to a generic collection of task definitions.
    condition     = can(regex("^arn:[a-z0-9-]+:ecs:[a-z0-9-]+:[0-9]{12}:task-definition/", var.reporting_task_definition_arn))
    error_message = "reporting_task_definition_arn must be an ECS task-definition ARN of the form arn:<partition>:ecs:<region>:<account-id>:task-definition/<family>[:<revision>]."
  }
}

# Assumptions: the reporting image serves an HTTP surface as well as these
#   jobs, so its baked-in command is a server start. An unmatched override
#   here does not run the wrong job -- it starts a long-lived server inside
#   a synchronous run-task state, which then holds the state open until the
#   state's own timeout expires. That is the most expensive form the
#   name-matching failure takes anywhere in this module.
variable "reporting_container_name" {
  description = "Name of the container inside the reporting-service task definition whose command the two daily output states and the ad-hoc report state override. The environment root passes the name published by the reporting ecs-service instance rather than relying on an assumed literal, because an unmatched override starts the image's ordinary server command inside a state that waits for the task to stop."
  type        = string
  default     = "reporting"

  validation {
    # Trade-offs: existence cannot be checked from an ARN at plan time,
    #   but the legal character set can. Rejecting blank and
    #   whitespace-bearing names here prevents the server-start outcome
    #   described above.
    condition     = can(regex("^[a-zA-Z0-9][a-zA-Z0-9_-]*$", var.reporting_container_name))
    error_message = "reporting_container_name must be a container name of letters, digits, hyphens and underscores, beginning with a letter or digit."
  }
}

variable "authorization_task_definition_arn" {
  description = "ARN of the authorization-service task definition the operator-invoked authorization-extract machine runs. Published by the authorization infra/modules/ecs-service instance and wired by the environment root. It is a fourth definition rather than a reuse of the batch one because the segment export reads authorization.pending_auth_summary and authorization.pending_auth_detail, and only the authorization context's database role may read them."

  type = string

  validation {
    # Assumptions: validated separately from the other three so a malformed ARN is
    #   attributed to the input that feeds the extract machine rather than to a
    #   generic collection.
    condition     = can(regex("^arn:[a-z0-9-]+:ecs:[a-z0-9-]+:[0-9]{12}:task-definition/", var.authorization_task_definition_arn))
    error_message = "authorization_task_definition_arn must be an ECS task-definition ARN of the form arn:<partition>:ecs:<region>:<account-id>:task-definition/<family>[:<revision>]."
  }
}

# Assumptions: the authorization image serves an HTTP surface as well as these jobs,
#   so its baked-in command is a server start and an unmatched override here does not
#   run the wrong job -- it starts a long-lived server inside a synchronous run-task
#   state, which then holds the state open until the state's own timeout expires. That
#   is the same failure mode the reporting container name records, and it is the reason
#   the name is passed rather than assumed.
variable "authorization_container_name" {
  description = "Name of the container inside the authorization-service task definition whose command the authorization-extract states override. The environment root passes the name published by the authorization ecs-service instance rather than an assumed literal, because an unmatched override starts the image's ordinary server command inside a state that waits for the task to stop."
  type        = string
  default     = "authorization"

  validation {
    # Trade-offs: existence cannot be checked from an ARN at plan time, but the
    #   legal character set can, which is what prevents the server-start outcome.
    condition     = can(regex("^[a-zA-Z0-9][a-zA-Z0-9_-]*$", var.authorization_container_name))
    error_message = "authorization_container_name must be a container name of letters, digits, hyphens and underscores, beginning with a letter or digit."
  }
}

# -----------------------------------------------------------------------------
# IAM -- the roles the execution role may hand to ECS
# -----------------------------------------------------------------------------

# Trade-offs: this is ONE list rather than six named scalars. It keeps the
#   surface small and is consumed directly as the `Resource` of a single
#   `iam:PassRole` statement, at the cost of being less self-describing than
#   named inputs would be; the description enumerates exactly what belongs in it
#   because an incomplete list produces an opaque access-denied error on
#   `iam:PassRole` at the first run-task, naming a role rather than this input.
#   Both halves of each pair are needed -- the execution role pulls the image and
#   writes the log stream, the task role is what the job authenticates as.
variable "pass_role_arns" {
  description = "IAM role ARNs each state machine's execution role may pass to ECS, keyed by machine: `daily` takes the task role AND task execution role of the batch, data-migration and reporting task definitions (six entries), `adhoc` those of reporting, `dataset` those of batch, and `authz` those of authorization (two entries each). The environment root assembles each list from the ecs-service outputs it already holds; the per-machine split is the least-privilege boundary of what THAT machine may run a task as, and omitting an entry fails at run-task with an access-denied error on iam:PassRole."

  type = map(list(string))

  validation {
    # Trade-offs: the grant is enumerated rather than written as a wildcard over
    #   the account's roles. A wildcard is one line shorter and would let a
    #   state machine start a task running as ANY role in the account, which
    #   turns a state-machine definition into a privilege-escalation path.
    #   Enumerating costs the caller a list it already has, because every entry
    #   is another module's output, and the name says PASS because that is the
    #   action authorised -- one `iam:PassRole` statement per machine whose
    #   Resource is exactly that machine's ARNs.
    # Refactoring Rationale: this input was a flat list of exactly eight ARNs when
    #   one union execution role served all four machines. It is a map because the
    #   roles are now per machine, and a flat list would have had to be granted to
    #   all four -- which is the finding this change resolves: the ad-hoc report
    #   machine could pass the batch and authorization task roles it never uses.
    #   The exact-arity check the flat list carried is replaced by a per-machine
    #   minimum of two, because the arity now varies by machine (six for daily, two
    #   for each of the others) and a machine gaining an image should not have to
    #   restate a total here.
    # Assumptions: DUPLICATES across machines are expected, not an error -- the
    #   batch image's two roles appear under both `daily` and `dataset` because both
    #   machines run that image. Within a machine a shared execution role may also
    #   legitimately be listed once per slot it fills, so this is a minimum-arity
    #   check rather than a distinctness check. An under-supplied list does not fail
    #   at apply; it fails at the first run-task with an access-denied error on
    #   iam:PassRole that names a role rather than this input, which is the most
    #   expensive place to discover it.
    condition = (
      length(setsubtract(keys(var.pass_role_arns), ["daily", "adhoc", "dataset", "authz"])) == 0
      && length(setsubtract(["daily", "adhoc", "dataset", "authz"], keys(var.pass_role_arns))) == 0
      && alltrue([for machine, arns in var.pass_role_arns : length(arns) >= 2])
      && alltrue([
        for machine, arns in var.pass_role_arns :
        alltrue([for r in arns : can(regex("^arn:[a-z0-9-]+:iam::[0-9]{12}:role/", r))])
      ])
    )
    error_message = "pass_role_arns must carry exactly the keys daily, adhoc, dataset and authz, each listing at least two IAM role ARNs -- the task role and the task execution role of every task definition that machine runs -- of the form arn:<partition>:iam::<account-id>:role/<name>."
  }
}

# -----------------------------------------------------------------------------
# Networking -- where the tasks are placed
#
# Alternatives Considered: exposing an `assign_public_ip` input was
#   considered and rejected. main.tf sets that field to the literal
#   `DISABLED`, because these subnets reach AWS APIs through the VPC's
#   interface endpoints and reach the internet, where a task needs it,
#   through the NAT gateway. A public address would therefore buy no
#   reachability the tasks lack and would widen their exposure, so it is not
#   a choice worth offering an environment root.
# -----------------------------------------------------------------------------

# Assumptions: these are the PRIVATE-APPLICATION tier specifically -- not
#   the public tier, which carries only the load balancer and the NAT
#   gateways, and not the isolated data tier, which has no internet route at
#   all and carries only the database. A task placed in the public tier
#   would be reachable from outside; one placed in the isolated tier could
#   not pull its own image.
variable "private_app_subnet_ids" {
  description = "Private application subnet identifiers the state machine places each task into -- the application tier, not the public tier that carries the load balancer and NAT gateways and not the isolated data tier that carries the database. Published as an output by infra/modules/network and passed in by the environment root; tasks reach the database through these subnets and reach AWS APIs through that VPC's interface endpoints, so they need no public address."

  type = list(string)

  validation {
    # Assumptions: the surrounding network module provisions three availability
    #   zones, so the three-subnet floor asserts that batch tasks are placed
    #   across all of them rather than concentrated. An empty list is accepted by
    #   the type system and rejected by the service only once a task is started,
    #   which is at the first nightly invocation rather than at apply.
    # Refactoring Rationale: distinctness is asserted alongside the count, which
    #   alone was satisfiable by one subnet repeated three times -- exactly the
    #   concentration the floor exists to prevent, and invisible afterwards
    #   because the plan shows three entries. Unlike the role list above, a
    #   repeat here is never legitimate: two identical subnet identifiers
    #   describe one subnet.
    condition = length(var.private_app_subnet_ids) >= 3 && length(distinct(var.private_app_subnet_ids)) == length(var.private_app_subnet_ids) && alltrue([
      for s in var.private_app_subnet_ids : can(regex("^subnet-[0-9a-f]{8,}$", s))
    ])
    error_message = "private_app_subnet_ids must list at least three DISTINCT subnet identifiers, each of the form subnet-<hex>, one per availability zone."
  }
}

# Assumptions: what this group must permit is egress to Aurora on the
#   database port and egress on 443 to the VPC interface endpoints, and
#   nothing wider -- those two are the whole of what a batch step reaches.
#   Declared as a list rather than a single identifier both because the
#   awsvpc network configuration takes a list and because the sibling
#   infra/modules/ecs-service input of the same name is a list, so a root
#   passes the same expression to both without reshaping it.
variable "task_security_group_id" {
  description = "Security group attached to every task the state machines start, the single application-tier group published by infra/modules/network. It is what permits the egress a batch step actually needs -- the database port to Aurora and 443 to the VPC interface endpoints -- and nothing wider."

  type = string

  validation {
    # Assumptions: a task started with no group at all falls back to the VPC's
    #   default security group -- a permissive group nobody chose for this
    #   workload, and the substitution is silent -- so a required scalar makes
    #   the group an explicit decision.
    # Alternatives Considered: a `list(string)`, matching the awsvpc network
    #   configuration's own shape and the identically named ecs-service input.
    #   Rejected: this module attaches exactly one group, so a list would let a
    #   caller pass several and quietly widen the egress the whole batch window
    #   runs under. The accepted cost is one `[var.task_security_group_id]` at
    #   the single point the awsvpc block is built.
    condition     = can(regex("^sg-[0-9a-f]{8,}$", var.task_security_group_id))
    error_message = "task_security_group_id must be a security group identifier of the form sg-<hex>."
  }
}

# -----------------------------------------------------------------------------
# Data and notification
# -----------------------------------------------------------------------------

# Assumptions: this module CONSUMES the bucket and creates nothing inside
#   it. The bucket itself, the ten generation-dataset prefix families and
#   the five-noncurrent-version lifecycle rule that is the `LIMIT(5)
#   SCRATCH` analogue all belong to infra/modules/s3-datasets. Only the name
#   crosses the boundary, and it crosses once: both the execution role's
#   grants and the task command overrides reference this single input rather
#   than each taking their own.
variable "dataset_bucket_name" {
  description = "Name of the versioned bucket the staging, backup, combine, statement and report states read and write dataset generations in. Published as an output by infra/modules/s3-datasets and passed in by the environment root. This module only consumes it: the bucket, its ten generation-dataset prefix families and its five-noncurrent-version lifecycle rule all belong to s3-datasets."

  type = string

  validation {
    # Assumptions: the rules asserted are the service's -- 3 to 63
    #   characters of lowercase letters, digits, hyphens and dots, beginning
    #   and ending alphanumeric. A name breaking them can neither be created
    #   nor referenced, and without this check the failure surfaces as an
    #   IAM policy that silently matches nothing rather than as a bad input.
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.dataset_bucket_name))
    error_message = "dataset_bucket_name must be a valid S3 bucket name: 3 to 63 characters of lowercase letters, digits, hyphens and dots, beginning and ending with a letter or digit."
  }
}

# Assumptions: the catch handler on every work state routes here first and
#   only then to the terminal failure, so a failed nightly run notifies
#   instead of failing silently. That is the analogue of the job log and the
#   operator console the baseline relied on -- the baseline reported a failed
#   step through `NOTIFY=&SYSUID` on the job card, and routing all twelve
#   states' catch handlers through one topic is what makes any of them reach
#   the same place.
variable "notification_topic_arn" {
  description = "ARN of the SNS topic every state's catch handler publishes to before the execution reaches its terminal failure state. Published as an output by infra/modules/observability and passed in by the environment root. It replaces the baseline's job-card NOTIFY and job log; routing all twelve states through one topic is what makes a failure in any of them reach the same place rather than failing silently."

  type = string

  validation {
    # Assumptions: asserting the `sns:` service field catches the
    #   plausible substitution of a queue ARN, which a catch handler would
    #   accept into a valid plan and then fail to publish to at exactly the
    #   moment something has already gone wrong -- the worst time to lose a
    #   notification.
    condition     = can(regex("^arn:[a-z0-9-]+:sns:[a-z0-9-]+:[0-9]{12}:", var.notification_topic_arn))
    error_message = "notification_topic_arn must be an SNS topic ARN of the form arn:<partition>:sns:<region>:<account-id>:<topic-name>."
  }
}

# -----------------------------------------------------------------------------
# Observability -- execution logs, their encryption, and tracing
# -----------------------------------------------------------------------------

# Assumptions: retention is one of the narrow set of parameters the dev and
#   prod roots are permitted to set differently without changing the stack's
#   shape, which is why it is an input rather than a constant fixed in
#   main.tf. What it retains is the record of which states ran on which
#   night, so the value is a compliance and diagnosis decision belonging to
#   the environment rather than to this module.
variable "log_retention_days" {
  description = "Days both state machines' execution log groups retain events. Supplied by the environment root, which is where dev and prod are permitted to differ; retention and sizing are the only axes on which the two environments may diverge, and this is the record of which states ran on which night."
  type        = number
  default     = 30

  validation {
    # Assumptions: the set is closed because CloudWatch Logs accepts only
    #   these values and rejects anything else during apply, after other
    #   resources already exist. 0 is excluded deliberately even though the
    #   service reads it as never-expire: unbounded retention on an execution
    #   log is a cost that grows without anyone deciding to accept it, and a
    #   caller who genuinely wants it can say so by widening this check
    #   rather than by passing a value that looks like a mistake.
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.log_retention_days)
    error_message = "log_retention_days must be one of the retention periods CloudWatch Logs accepts: 1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288 or 3653."
  }
}

# Trade-offs: this is OPTIONAL -- nullable, defaulting to null, meaning
#       CloudWatch's own service-managed encryption -- and the guarantee is moved
#       to the environment roots rather than to this declaration. Making it
#       REQUIRED was the alternative and was rejected because it makes a module
#       that has no customer-managed log key yet unusable rather than merely less
#       encrypted, which blocks a standalone smoke apply for a property that
#       smoke apply does not exercise. What is given up is that a root can fall
#       back to service-managed encryption by omitting one argument. That is
#       bought back where it can be checked against a real key: both
#       infra/envs/dev/main.tf and infra/envs/prod/main.tf pass
#       `module.kms.s3_key_arn` on their `step_functions` call, each with its own
#       comment recording why, and the validation below still refuses a
#       malformed or alias ARN so the only accepted omission is a deliberate
#       null. The reason a key is wanted at all is unchanged and worth keeping in
#       view: every file resource in the baseline was defined
#       `RECOVERY(NONE) JOURNAL(NO)` (app/csd/CARDDEMO.CSD:1-89), so encryption
#       at rest is a property this migration ADDS rather than preserves.
#       Refactoring Rationale: this paragraph formerly asserted the input was
#       REQUIRED and that a nullable key had been "rejected on the merits" --
#       directly contradicting the three lines beneath it, which declare
#       `nullable = true` and `default = null`, the description that documents
#       what null means, and the validation's own Trade-offs note that reaches
#       the opposite conclusion for the same reason. The declaration was never
#       wrong; this rationale was, and it was the more misleading of the two,
#       because a reader who trusted it would conclude the module enforced a
#       guarantee that in fact rests entirely on both roots continuing to pass
#       the key.
variable "log_group_kms_key_arn" {
  description = "ARN of the customer-managed key both execution log groups are encrypted with, published as an output by infra/modules/kms and passed in by the environment root. Null leaves the log groups on CloudWatch's own service-managed encryption."

  type     = string
  nullable = true
  default  = null

  validation {
    # Assumptions: both the `kms:` service field and the `:key/` resource type
    #   are asserted, because an alias ARN is the plausible wrong value -- an
    #   alias is what a human reads in the console -- and it carries resource
    #   type `alias/`, which a log group does not accept. Catching it here names
    #   this input; letting it through produces an apply-time failure attributed
    #   to the log group instead. Null passes the check because null is the
    #   documented service-managed-encryption fallback, not an omission.
    condition     = var.log_group_kms_key_arn == null || can(regex("^arn:[a-z0-9-]+:kms:[a-z0-9-]+:[0-9]{12}:key/", var.log_group_kms_key_arn))
    error_message = "log_group_kms_key_arn must be null, or a KMS key ARN of the form arn:<partition>:kms:<region>:<account-id>:key/<key-id>, not an alias ARN."
  }
}

# Assumptions: this is the literal every RunTask integration stamps as StartedBy, and
#   it is also the ListTasks filter both the graph's cancellation states and the resume
#   function's reconciling release match on. All three have to agree exactly, which is
#   why it is one input rather than a value each side composes.
# Refactoring Rationale: it was `substr("${local.name_stem}-sfn", 0, 36)` inside the
#   module. It became an input when the resume function started needing the same
#   literal: the module already takes that function's ARN, so publishing the marker as
#   an output would have made the function depend on the module and the module depend
#   on the function -- a cycle Terraform refuses. The caller owns the value and passes
#   it to both.
# Trade-offs: the ceiling is 36 characters, the SMALLEST length any published surface
#   states for this field -- the run-task CLI reference and the StartTask API reference
#   both say 36 while the current RunTask API reference says 128 -- so a value inside 36
#   is valid under all of them and remains usable verbatim as a ListTasks filter. The
#   bound is REFUSED rather than truncated, unlike the module-internal version that
#   composed and then shortened it: a caller whose marker is silently shortened has a
#   Lambda environment variable that no longer matches what the tasks carry, and a
#   filter that matches nothing reads as "no tasks are running".
variable "task_started_by" {
  description = "Literal stamped as StartedBy on every task these state machines launch, and used as the ListTasks filter that finds a residual task after the state that started it gave up. The environment root owns the value because the resume function's environment must carry the identical string."

  type = string

  validation {
    # Assumptions: the ECS-reserved `ecs-svc/` prefix is refused. A service sets its
    #   own startedBy of that shape, so a marker imitating it would make the
    #   cancellation path able to stop tasks an ECS SERVICE is running -- which is a
    #   production outage rather than a cleanup. Blank is refused because an empty
    #   filter matches every task in the cluster.
    condition     = length(trimspace(var.task_started_by)) > 0 && length(var.task_started_by) <= 36 && !startswith(var.task_started_by, "ecs-svc/")
    error_message = "task_started_by must be a non-blank string of at most 36 characters and must not begin with the ECS-reserved ecs-svc/ prefix."
  }
}

# Assumptions: nullable for the same reason log_group_kms_key_arn is -- the module
#   stays applicable without a key, and both roots pass one. The fallback is never
#   "unencrypted": main.tf asserts sqs_managed_sse_enabled when this is null, so the
#   only choice this input expresses is WHOSE key, not whether.
# Trade-offs: the SQS key is the right one to pass rather than the S3 key the log
#   groups take, because both roots already create a queue key for
#   infra/modules/sqs and the scheduler's dead-letter target uses it. Reusing it
#   keeps every queue in the deployment under one key.
variable "dead_letter_kms_key_arn" {
  description = "ARN of the customer-managed key encrypting the bracket-release dead-letter queue, published as an output by infra/modules/kms and passed in by the environment root. Null leaves the queue on SQS-managed encryption."

  type     = string
  nullable = true
  default  = null

  validation {
    # Assumptions: the same alias-ARN trap the log-group key documents applies here,
    #   with one difference worth naming: SQS accepts an ALIAS for
    #   kms_master_key_id, so an alias would apply cleanly and then be impossible to
    #   audit against the key the rest of the deployment uses. Requiring a key ARN
    #   keeps the queue's key comparable with every other resource's.
    condition     = var.dead_letter_kms_key_arn == null || can(regex("^arn:[a-z0-9-]+:kms:[a-z0-9-]+:[0-9]{12}:key/", var.dead_letter_kms_key_arn))
    error_message = "dead_letter_kms_key_arn must be null, or a KMS key ARN of the form arn:<partition>:kms:<region>:<account-id>:key/<key-id>, not an alias ARN."
  }
}

# Assumptions: this is a CADENCE, not a staleness threshold. The reconciler releases
#   on evidence -- a terminal owning execution and no task short of STOPPED -- so this
#   value decides only how quickly a stranded bracket is noticed, never whether one
#   is. That is why it can be shortened without any risk of re-enabling writes inside
#   a legitimate long night.
# Trade-offs: fifteen minutes, so a stranded bracket costs at most a quarter of an
#   hour of read-only online service. Every cycle costs one Lambda invocation, one
#   strongly-consistent DynamoDB read and -- only when a lease is actually stranded --
#   one DescribeExecution and two ListTasks calls, so the cadence is bounded by
#   attention rather than by cost. A minute would be cheap too and was rejected as
#   noise: the finalizer already covers the fast path, so this rule's job is to
#   converge, not to race.
variable "reconcile_interval_minutes" {
  description = "How often the bracket reconciler asks the resume function to look for a quiesce bracket that no event released. It is a cadence and not a staleness threshold: the release still requires the owning execution and its tasks to be terminal."

  type    = number
  default = 15

  validation {
    # Assumptions: the floor is one minute because EventBridge's rate expression
    #   accepts no smaller unit, and the ceiling is 720 -- twelve hours -- because a
    #   cadence longer than half a day could let a stranded bracket outlast a full
    #   business day, which is the outage this rule exists to bound. An integer is
    #   required because rate() takes no fraction.
    condition     = var.reconcile_interval_minutes == floor(var.reconcile_interval_minutes) && var.reconcile_interval_minutes >= 1 && var.reconcile_interval_minutes <= 720
    error_message = "reconcile_interval_minutes must be a whole number of minutes between 1 and 720."
  }
}

# Trade-offs: the default is the most verbose setting rather than the
#   cheapest. A nightly chain runs once, so volume is bounded by twelve
#   states rather than by a request rate, and the first question asked after
#   a failure -- which state failed and what did it receive -- is answered
#   only by the full transition history.
variable "log_level" {
  description = "Which execution events reach both state-machine log groups: ERROR records failures, FATAL only terminal failures, and ALL every transition. Logging cannot be disabled, because execution history is the target analogue of the baseline job log."
  type        = string
  default     = "ALL"

  validation {
    # Alternatives Considered: accepting the service's fourth level, OFF,
    #   and leaving it to the CI policy scan to reject -- that scan requires
    #   logging to be enabled on a state machine, so OFF would be caught
    #   there. Rejected because catching it here is strictly earlier and
    #   strictly clearer: the error names this input at plan time, whereas
    #   the scan reports a finding against a generated resource. A
    #   caller who disables execution logging removes the only cross-state
    #   record of a failed chain while still producing a valid plan, so the
    #   narrower domain is the point rather than an oversight.
    condition     = contains(["ALL", "ERROR", "FATAL"], var.log_level)
    error_message = "log_level must be one of ALL, ERROR or FATAL; execution logging cannot be disabled, and OFF is rejected here rather than by the CI policy scan."
  }
}

# Assumptions: the default is on, and what makes that safe is a checkable claim
#   about what this chain's payloads contain -- a business date, dataset names,
#   job names, execution identities and task metadata. NO cardholder data, NO
#   primary account number and NO credential enters either state machine's
#   payload; record data is read by the tasks from the database and the dataset
#   bucket, never threaded through the state machine. This stays an INPUT rather
#   than becoming a constant precisely because the claim is about the current
#   chain: a future change that threaded record-level data through an execution
#   input must revisit this default.
variable "log_include_execution_data" {
  description = "Whether each logged event of the daily, ad-hoc report and dataset round-trip machines carries the state's input and output payload as well as the transition itself. Safe to leave on because those three chains' payloads are business dates, dataset names, job names and execution identities -- no cardholder data, primary account number or credential enters them. It remains an input so that a future change threading record-level data through an execution can turn it off. The authorization-extract machine is governed separately by log_include_authorization_execution_data, because its payload carries operator-supplied extract locations."
  type        = bool
  default     = true
}

# WHY : ⚠️ Refactoring Rationale: the authorization-extract machine used to share the input
#   above, and the claim that made sharing safe -- that no payload carries anything but business
#   dates, dataset names, job names and execution identities -- was untrue of that one machine.
#   Its load mode takes two extract LOCATIONS from the operator's request, and with execution
#   data on, each is written verbatim into the execution log at every transition. An operator's
#   own path is not cardholder data, but it is operator-supplied text this module neither
#   composed nor bounded, and the honest response to a claim that has acquired an exception is to
#   split the input rather than to widen the claim.
# WHY : Assumptions: the default is ON, matching the shared input, and what makes that safe is
#   not the same argument. The shared claim is about what the payload CANNOT contain; here it is
#   about what the graph will ACCEPT. ValidateAuthorizationExtractRequest now requires both
#   locations to match s3://<dataset bucket>/authorization/extract/*, the same space the unload
#   mode computes its own destinations in, so the only value that can reach the log is an object
#   key inside this deployment's own bucket under a fixed prefix. A foreign bucket, another
#   scheme and a container-local path are refused before the first transition, which is where the
#   sensitivity was -- not in the fact that a location is logged at all.
# WHY : Trade-offs: setting this to false is what the code review's suggested resolution asked
#   for, and it is available deliberately -- but it FAILS the curated policy gate. Checkov's
#   CKV_AWS_285, which .github/workflows/infra-ci.yml gates on, reads exactly
#   logging_configuration[0].include_execution_data and requires it true, so a root that turns
#   this off trades a gated logging control for a narrowing the input constraint above has
#   already achieved. The input exists for the case that changes that balance -- a future mode
#   accepting an operand this deployment does not bound -- and turning it off then would be a
#   deliberate, reviewable act with a policy exception attached, rather than the default.
# WHY : Alternatives Considered: redacting the two locations instead of withholding the whole
#   payload. Step Functions offers no field-level redaction in a log destination, so the only
#   two settings available are all of the payload or none of it; there is no third option to
#   prefer.
variable "log_include_authorization_execution_data" {
  description = "Whether the authorization-extract machine's logged events carry state input and output as well as the transition. Governed separately from log_include_execution_data because this machine's load mode accepts two extract locations from the operator's request; those locations are constrained by the graph to objects under the deployment's own authorization/extract/ prefix before any transition, which is what makes logging them safe. Setting this to false withholds the payload and fails Checkov CKV_AWS_285, which requires execution-data logging on a state machine."
  type        = bool
  default     = true
}
# Trade-offs: tracing costs per recorded trace, and a chain that runs once a
#   night records one. Against that cost, the question this module's failures
#   raise is almost always where in the chain time went or which call failed, and
#   a per-execution trace answers it without reconstructing a timeline from log
#   timestamps across three services.
# -----------------------------------------------------------------------------
# Function-backed states -- the operator bracket and the statistics refresh
#
# Three of the twelve states are a single API call each and are therefore backed
# by functions rather than tasks. They are declared as three separate inputs
# rather than one list, because each is invoked at a specific position in the
# chain and a list would lose which is which.
#
# Alternatives Considered: running them as Fargate tasks for uniformity.
#   Rejected -- it would pay a task cold start and a task definition's worth of
#   configuration for a call that writes one parameter or issues one statement.
#   The fifteen-minute execution ceiling that rules a function out for a
#   record-processing step rules nothing out for these three.
#
# Alternatives Considered: making the three ARNs nullable and degrading their
#   states to `Pass` states when a caller omitted them, so the module could plan
#   without them. Rejected: a state machine that silently no-ops the quiesce
#   bracket would run the entire batch window against a live online write path,
#   with concurrent writes landing in the masters the chain is posting against.
#   All three are therefore required. Function ownership itself sits with the
#   environment root -- this module's remit is two state machines, one execution
#   role and two log groups, and there is no Lambda module in this package.
# -----------------------------------------------------------------------------

# Assumptions: this is the migrated form of app/jcl/CLOSEFIL.jcl:26-30,
#   which issued five `CEMT SET FIL(...) CLO` operator commands over
#   TRANSACT, CCXREF, ACCTDAT, CXACAIX and USRSEC. The target sets one
#   parameter that the online services read, so the MECHANISM changes while
#   the bracket around the window does not.
variable "quiesce_function_arn" {
  description = "ARN of the function the first state invokes to set the online read-only flag, opening the batch window. Declared by the environment root, which owns these functions and the parameter they toggle. This is the migrated form of app/jcl/CLOSEFIL.jcl, which closed five CICS files with an operator command; the target sets a parameter the online services read, so the mechanism changes while the bracket does not."

  type = string

  validation {
    # Assumptions: an ARN of any other service is accepted into the plan
    #   and rejected only when the state runs, which is at the head of the
    #   nightly chain. A version or alias qualifier adds a further
    #   colon-delimited segment, so the pattern deliberately does not anchor
    #   at the end and both qualified and unqualified ARNs are accepted.
    condition     = can(regex("^arn:[a-z0-9-]+:lambda:[a-z0-9-]+:[0-9]{12}:function:", var.quiesce_function_arn))
    error_message = "quiesce_function_arn must be a Lambda function ARN of the form arn:<partition>:lambda:<region>:<account-id>:function:<name>[:<qualifier>]."
  }
}

# Assumptions: this is app/jcl/OPENFIL.jcl:26-30, the five matching `CEMT SET
#   FIL(...) OPE` commands, and it is the counterpart the chain cannot omit. The
#   flag must be cleared on the success path AND on the failure path alike,
#   which is why main.tf invokes this function from both, and from a THIRD place
#   outside the execution -- an EventBridge rule on the daily machine's terminal
#   status, since an execution timed out at the top level or aborted by an
#   operator runs no further state. This ARN is therefore granted to
#   events.amazonaws.com as well as to the execution role.
#   Refactoring Rationale: this note previously said the handler "overwrites the
#   parameter unconditionally, so being called twice for one night costs one
#   idempotent write". The release is no longer unconditional -- it is a conditional
#   delete of a lease item that succeeds only for the recorded owner or an expired
#   lease -- and unconditional is precisely what made the out-of-graph rule able to
#   re-enable online writes underneath an execution that still held the bracket.
#   Being called twice for one night is still cheap, but for a different reason: the
#   second call finds no lease to delete, reports that, and writes no parameter.
variable "resume_function_arn" {
  description = "ARN of the function invoked to clear the online read-only flag, closing the batch window. Declared by the environment root. This is the migrated form of app/jcl/OPENFIL.jcl and the counterpart of the quiesce state: it is invoked on the success path and on the failure path alike, because a chain that failed without clearing the flag it set would leave the online services read-only after the window ended. It is additionally invoked from outside the execution, by an EventBridge rule on the daily machine's terminal status, so a timed-out or operator-aborted execution -- which runs no further state and so reaches neither in-execution path -- still releases the flag."

  type = string

  validation {
    # Assumptions: validated separately from the quiesce ARN so that an
    #   error message names the input the caller got wrong rather than the
    #   bracket as a whole.
    condition     = can(regex("^arn:[a-z0-9-]+:lambda:[a-z0-9-]+:[0-9]{12}:function:", var.resume_function_arn))
    error_message = "resume_function_arn must be a Lambda function ARN of the form arn:<partition>:lambda:<region>:<account-id>:function:<name>[:<qualifier>]."
  }
}

# Assumptions: this replaces app/jcl/TRANIDX.jcl only IN PART, and the
#   missing part is deliberate. That job REBUILT an alternate index with
#   `IDCAMS BLDINDEX`; index building is retired outright because PostgreSQL
#   maintains an index inside the same transaction as the write, which leaves
#   statistics as the only portion of the step with a target at all.
variable "analyze_tables_function_arn" {
  description = "ARN of the function the penultimate state invokes to refresh table statistics after the night's writes. Declared by the environment root. It replaces app/jcl/TRANIDX.jcl only in part: that job rebuilt an alternate index, and index building is retired because PostgreSQL maintains indexes inside the same transaction as the write, leaving statistics as the only part of the step with a target."

  type = string

  validation {
    # Assumptions: validated separately for the same attribution reason as
    #   the resume function above.
    condition     = can(regex("^arn:[a-z0-9-]+:lambda:[a-z0-9-]+:[0-9]{12}:function:", var.analyze_tables_function_arn))
    error_message = "analyze_tables_function_arn must be a Lambda function ARN of the form arn:<partition>:lambda:<region>:<account-id>:function:<name>[:<qualifier>]."
  }
}

# Alternatives Considered: relying solely on the parameter name the environment
#   root already bakes into each function's own environment variables, which
#   would make this input unnecessary. Rejected because it leaves the state
#   machine unable to say WHICH flag its bracket toggles: the definition would
#   show a quiesce call with no subject, and the answer would live in a different
#   resource in a different file. Passing the name in the payload makes the
#   bracket self-describing in the execution history, and it lets one function
#   serve more than one flag. What it replaces is the operator-command mechanism
#   of app/jcl/CLOSEFIL.jcl and app/jcl/OPENFIL.jcl, scoped to the WRITE path
#   only, so a flag the services consult is the faithful analogue rather than a
#   hard lock that would also block reads the baseline allowed.
variable "read_only_flag_parameter_name" {
  description = "Name of the SSM Parameter Store parameter the first and last states toggle, passed to the quiesce and resume functions in their invocation payload so the execution history records which flag the bracket controls. Declared by the environment root alongside the functions. It replaces the operator-command mechanism of app/jcl/CLOSEFIL.jcl and app/jcl/OPENFIL.jcl, which issued five CEMT SET FIL commands each; the bracket is scoped to the write path, because the baseline's read-only unload jobs opened their files shared."

  type = string

  validation {
    # Assumptions: the plausible wrong value here is the parameter's ARN, because
    #   every other identifier this module takes is one. The API the functions
    #   call takes a name, so an ARN would be accepted into a valid plan and fail
    #   inside the function at the head of the chain; rejecting a value
    #   containing `arn:` names this input instead.
    # Refactoring Rationale: the leading slash is REQUIRED even though Parameter
    #   Store accepts a hierarchical name either way, because the consumer does
    #   not: infra/lambda/online_write_flag.py raises `PARAMETER_NAME must be an
    #   absolute SSM path` at IMPORT time unless the name begins with a slash.
    #   Terraform now rejects what the function rejects, so a clean plan can no
    #   longer produce a first state that crashes on cold start.
    condition     = can(regex("^/[a-zA-Z0-9_.\\-/]+$", var.read_only_flag_parameter_name)) && !can(regex("^arn:", var.read_only_flag_parameter_name)) && length(var.read_only_flag_parameter_name) <= 2048
    error_message = "read_only_flag_parameter_name must be an ABSOLUTE SSM parameter name beginning with \"/\", of letters, digits, underscores, dots, hyphens and slashes -- for example \"/carddemo/dev/batch/online-writes-enabled\" -- and not a parameter ARN."
  }
}

# -----------------------------------------------------------------------------
# Refactoring Rationale: this input is the OPERATOR OVERRIDE only. The composed default it
#   overrides is built in main.tf from var.dataset_source_extract_prefix, which is the prefix
#   the s3-datasets module owns, provisions a lifecycle rule for and publishes, and which the
#   environment roots already wire into this module.
# Refactoring Rationale: two further inputs for the same location were authored here and are
#   WITHDRAWN, because four spellings of "where the seed extracts are" cannot be kept honest.
#   The first was `dataset_source_prefix`, a bare key segment defaulting to "source-extracts"
#   whose validation REFUSED a trailing slash. The second was `dataset_inbox_prefix`,
#   defaulting to "inbox". Neither was passed by either environment root, and the measurement
#   that decided it is this: var.dataset_source_extract_prefix defaults to
#   "migration/source/EBCDIC/" and REQUIRES a trailing slash, and the s3-datasets output the
#   roots wire into it carries the same default -- so a location composed from either withdrawn
#   input addressed a prefix nothing is ever synced to, while the refresh command read the
#   other one. That divergence was latent only because the environment variable carrying the
#   composed value had lost its consumer; restoring the consumer makes it reachable, so the
#   duplicate inputs go rather than the consumer.
# Assumptions: the withdrawn inbox input carried two arguments worth keeping, and they are kept
#   here rather than lost with it. First, this prefix is deliberately NOT a member of
#   infra/modules/s3-datasets's family inventory: every prefix in that inventory carries a
#   lifecycle rule reproducing the baseline's LIMIT(5) SCRATCH generation limit, and this one
#   holds the operator's delivered export, whose retention is the operator's decision and which
#   is read once per execution. Adding it would attach a generation-retention rule to bytes
#   that are not generations and would raise a prefix count the architecture documents publish.
#   Second, provisioning an Elastic File System volume, populating it and mounting it on the
#   task was rejected on two counts: it adds a file system, a mount target per availability
#   zone, a security group and an access point to operate and pay for, purely to hold bytes S3
#   already holds; and populating it still requires an out-of-band copy, so the operator action
#   does not go away, it moves behind two more resources.
# Assumptions: populating the prefix remains an OPERATOR action and is documented as one in
#   docs/runbooks/data-migration.md, as a single `aws s3 sync` of app/data/EBCDIC/. This module
#   cannot perform it: the AAP places a live deployment outside this scope, and the extracts are
#   baseline data no Terraform resource should carry. What the module owes is that the container
#   is told where to look.
# Trade-offs: this override is nullable and defaults to null, meaning "use the composed bucket
#   prefix". It is kept rather than removed because the same commands are run by hand in a
#   checkout, where a local directory is the natural source and the CLI's filesystem branch
#   reads the operator's own file without copying it. Null rather than an empty string, so that
#   "not overridden" is a distinct value from "overridden with nothing" and `coalesce` in
#   main.tf can decide between them.
variable "dataset_staging_root" {
  description = "Optional override for the location the data-migration container resolves seed extracts from, replacing the composed `s3://dataset_bucket_name/dataset_source_extract_prefix` value. Accepts an absolute filesystem path for a mounted or local source, or an `s3://bucket/prefix` URI to read a different bucket. Leave null in both environment roots."

  type    = string
  default = null

  validation {
    # Assumptions: an absolute path or an s3:// URI, and nothing else. A relative path
    #   would resolve against the container's working directory, which the image sets and
    #   this module does not control, so the same configuration would read different
    #   directories if that working directory ever changed; any other scheme names a
    #   transport the CLI has no branch for.
    condition = (
      var.dataset_staging_root == null
      || startswith(var.dataset_staging_root, "/")
      || can(regex("^s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9](/.+)?$", var.dataset_staging_root))
    )
    error_message = "dataset_staging_root must be null, an absolute path beginning with /, or an s3://bucket/prefix URI."
  }

  validation {
    # Assumptions: a trailing slash is refused rather than tolerated so the value has one
    #   spelling. Both forms address the same prefix once joined, but permitting both means
    #   two deployments can differ in a way that shows up in logs and diffs while meaning
    #   the same thing.
    condition = (
      var.dataset_staging_root == null
      || var.dataset_staging_root == "/"
      || !endswith(var.dataset_staging_root, "/")
    )
    error_message = "dataset_staging_root must not end with a trailing slash."
  }

  validation {
    # Assumptions: the alphabet is restricted to what an S3 key and a POSIX path both
    #   handle without escaping, and `..` is refused outright. The value is composed into a
    #   location the container parses, so a segment that traversed upward would address a
    #   prefix outside the one the task role's own statements are scoped to -- the request
    #   would be denied rather than misrouted, but the failure would surface as an
    #   authorization error against a prefix nobody configured.
    condition     = var.dataset_staging_root == null || (can(regex("^[A-Za-z0-9/][A-Za-z0-9._:/-]*$", var.dataset_staging_root)) && !strcontains(var.dataset_staging_root, ".."))
    error_message = "dataset_staging_root must contain only letters, digits, dot, colon, underscore, hyphen and forward slash, and must not contain '..'."
  }
}

# Assumptions: this is the directory holding the `sql` tree inside the data-migration image, and
#   it is stated here because the container cannot derive it. The two committed verification
#   queries are read from <root>/sql/verify/, and the package resolves that root from its own
#   location only in a source checkout or an editable install -- in the image the package is
#   installed into a virtual environment while data-migration/Dockerfile's `COPY sql/ ./sql/`
#   places the queries under its WORKDIR, so the two are not in the same tree and the default
#   cannot resolve.
# Alternatives Considered: passing `--sql-root .` and relying on the image's WORKDIR. Rejected
#   for the reason the staging-root override above gives for requiring an absolute path: a
#   relative value resolves against a working directory this module does not control, so the
#   same configuration would read a different tree if the image ever changed it.
variable "data_migration_sql_root" {
  description = "Absolute path inside the data-migration container of the directory holding the sql tree, passed to the verification state as --sql-root. Matches the WORKDIR that data-migration/Dockerfile copies sql/ beneath, so the two committed verification queries -- whose digests the passes pin -- are found where the image actually places them."

  type    = string
  default = "/opt/carddemo"

  validation {
    condition     = startswith(var.data_migration_sql_root, "/")
    error_message = "data_migration_sql_root must be an absolute path beginning with /."
  }

  validation {
    condition     = var.data_migration_sql_root == "/" || !endswith(var.data_migration_sql_root, "/")
    error_message = "data_migration_sql_root must not end with a trailing slash."
  }
}

# -----------------------------------------------------------------------------
# Refactoring Rationale: this input REPLACES `dataset_staging_root`, which named an
#   absolute FILESYSTEM path (defaulting to /mnt/carddemo-extracts) inside the
#   data-migration task. Nothing in this stack provisions a filesystem -- the tasks are
#   Fargate with no volume, and the container image deliberately ships no extract -- so
#   the second state's ten Map branches each read an absent file and failed. The old
#   input's own documentation said as much, calling population of the path "an OPERATOR
#   action" that "this module cannot perform"; a state whose only source is a path no
#   deployment creates is a state that cannot run, so the input was describing a gap
#   rather than closing one.
# Refactoring Rationale: the replacement is an S3 KEY PREFIX because the extracts were
#   already going to S3. docs/runbooks/data-migration.md has always told an operator to
#   `aws s3 sync app/data/ s3://<dataset bucket>/migration/source/`, so the bytes the
#   state needed existed in the bucket the task already reads while the state looked for
#   them on a disk that did not. Reading where the runbook writes is what makes the
#   branch runnable from the documented procedure instead of from an undocumented mount.
# Alternatives Considered: mounting an EFS access point into the task and keeping the
#   filesystem contract. Rejected on cost and on blast radius: it adds a file system, a
#   mount target per availability zone and a security group to the stack for one
#   read-only directory that is consumed once per nightly execution, and it gives the
#   batch tasks a writable shared filesystem they otherwise have no reason to hold.
# Alternatives Considered: baking the extracts into the container image. Rejected for
#   the reason the withdrawn input already recorded: it would put a copy of the baseline
#   data -- including the security file, which carries a plaintext credential in the
#   baseline -- into every published image layer, and would make correcting one extract
#   an image rebuild and a redeploy.
# Assumptions: the value is supplied by the s3-datasets module, which owns the prefix,
#   provisions its lifecycle rule and publishes it as `source_extract_prefix`. The
#   default here matches that module's default so the module remains usable standalone,
#   but a composed root passes the owner's value so the two cannot disagree.
variable "dataset_source_extract_prefix" {
  description = "S3 key prefix inside the dataset bucket holding the exported baseline extracts the seed-refresh state READS. Passed to the data-migration container as --extract-prefix; the container joins each dataset's registered source file name to it. Owned and provisioned by the s3-datasets module, which publishes it as source_extract_prefix. Populating it is an operator action documented in docs/runbooks/data-migration.md."

  type    = string
  default = "migration/source/EBCDIC/"

  validation {
    # Assumptions: a LEADING separator is refused rather than stripped, because an S3
    #   key has no root: "/migration/source/" is a prefix whose first segment is empty,
    #   which is a different working prefix. A value copied from a filesystem path would
    #   otherwise plan cleanly and then read a prefix nothing was synced to. The ETL's
    #   own `extract_source_key` refuses the same spelling, so both sides agree.
    condition     = length(trimspace(var.dataset_source_extract_prefix)) > 0 && !startswith(var.dataset_source_extract_prefix, "/")
    error_message = "dataset_source_extract_prefix must be a non-empty S3 key prefix and must not begin with \"/\"; an object key has no root, so a leading separator names a different prefix rather than the same one."
  }

  validation {
    # Assumptions: a TRAILING separator is REQUIRED -- the opposite of the rule the
    #   withdrawn filesystem input carried -- because the container joins the registered
    #   file name to this value directly. Without the separator the join would produce
    #   "migration/source/EBCDICAWS.M2..." and read a key that does not exist.
    condition     = endswith(var.dataset_source_extract_prefix, "/")
    error_message = "dataset_source_extract_prefix must end with \"/\" so that the registered source file name joins to it as a child key rather than being concatenated onto the last segment."
  }
}

# Seed-dataset staging -- the branches of the second state's Map
# -----------------------------------------------------------------------------

# Assumptions: ten of the eleven defaults correspond one-to-one to the ten IDCAMS
#   master-refresh load jobs in app/jcl/ -- ACCTFILE, CARDFILE, CUSTFILE,
#   XREFFILE, TRANFILE, DISCGRP, TCATBALF, TRANTYPE, TRANCATG and DUSRSECJ --
#   each of which performs exactly one `REPRO`, which is what makes the mapping
#   one-to-one rather than approximate.
# Refactoring Rationale: `daily_transactions` is the eleventh, and it was ADDED
#   after being reasoned out of the list. The earlier reasoning was that
#   app/jcl/POSTTRAN.jcl:30 reads DALYTRAN.PS directly with `DISP=SHR`, so it is
#   flat sequential input to posting rather than a loaded master -- true of the
#   BASELINE and false of the target. Here posting reads `ledger.daily_transactions`,
#   a real table with a declared Aurora load target, and its `amount` column is one
#   of the nine the committed money-total query totals. So omitting it did not avoid
#   inventing a load step; it left a declared target unloaded while the verification
#   query totalled it, and verification pass 3 refuses the entire run when a layout
#   that ships a committed extract contributes no source total. The load step is the
#   target's own contract, not an invention.
# Trade-offs: the list is exposed because a caller occasionally needs to stage a
#   SUBSET -- reloading one master after a correction rather than the whole set
#   -- which is then a tfvars change instead of a module edit.
# Assumptions: these eleven names are a CROSS-LANGUAGE CONTRACT, not a local label set.
#   Each is passed verbatim as `--dataset` to the data-migration CLI, which resolves
#   it through `carddemo_migration.seed_datasets` to obtain the bounded-context
#   domain, the prefix segment, the source extract and the declared record length.
#   The registry there is the single authority for that binding; this list is the
#   orchestrator's half of the same vocabulary.
# Refactoring Rationale: the two halves used to disagree completely, and every
#   staging branch failed because of it. This variable sent plural snake-case tokens
#   while the CLI validated `--dataset` against the copybook LAYOUT registry, whose
#   keys are short upper-case names -- `accounts` against `ACCOUNT`, `card_xref`
#   against `XREF` -- so no name here could ever be accepted. The orchestrator also
#   supplied only `--dataset` and `--business-date` while `--source`, `--generation`
#   and `--domain` were each required, so the task exited in argument parsing with
#   status 2 before reaching any staging code. The registry closed both gaps: it
#   accepts these tokens and derives the rest.
# Assumptions: the agreement is ENFORCED rather than trusted. HCL and Python share
#   no build and cannot import one another, so
#   `data-migration/tests/test_seed_datasets.py` reads this variable's default list
#   and asserts it names exactly the registry's tokens. A name added on either side
#   without the other fails that test rather than a nightly execution.
variable "seed_datasets" {
  description = "Dataset names the seed-staging state iterates over, one Map branch and one data-migration task per name. The default is the eleven loaded masters, one per declared Aurora load target. daily_transactions is included because ledger.daily_transactions IS a declared load target whose amount column the committed money-total query totals, so verification pass 3 refuses the whole run without a DALYTRAN source total. An environment may pass a subset to restage one master without a module edit."

  type = list(string)

  default = [
    "accounts",
    "cards",
    "customers",
    "card_xref",
    "transactions",
    "daily_transactions",
    "disclosure_groups",
    "transaction_category_balances",
    "transaction_types",
    "transaction_categories",
    "users",
  ]

  validation {
    # Assumptions: each name becomes a path segment in the dataset prefix and an
    #   argument on the ETL container's command, so it is restricted to
    #   characters both accept. An empty list is rejected because a Map with no
    #   items SUCCEEDS while loading nothing, which is the one failure here that
    #   reads as a clean run. Distinctness is asserted too: a repeated name
    #   stages one master twice in the same run -- two Map branches loading the
    #   same table concurrently, a write conflict rather than a slower load.
    # Refactoring Rationale: membership in the closed eleven-name set is asserted
    #   rather than a character-shape pattern, which admitted any lowercase token
    #   so that `accounts_v2` or a misspelled `custommers` passed validation and
    #   failed later inside a Map branch as a container usage error naming an
    #   argument. Every legal value is one of the eleven defaults, so the set is
    #   knowable here even though the readers themselves live in the ETL package.
    condition = length(var.seed_datasets) >= 1 && length(distinct(var.seed_datasets)) == length(var.seed_datasets) && alltrue([
      for d in var.seed_datasets : contains([
        "accounts",
        "cards",
        "customers",
        "card_xref",
        "transactions",
        "daily_transactions",
        "disclosure_groups",
        "transaction_category_balances",
        "transaction_types",
        "transaction_categories",
        "users",
      ], d)
    ])
    error_message = "seed_datasets must list at least one name, each DISTINCT and each one of the eleven loaded masters: accounts, cards, customers, card_xref, transactions, daily_transactions, disclosure_groups, transaction_category_balances, transaction_types, transaction_categories, users."
  }
}

# Trade-offs: three is chosen between two bad endpoints. Unbounded
#   concurrency would start all eleven branches at once, and eleven simultaneous
#   bulk loads can exhaust both the Aurora connection budget and the Fargate
#   task quota -- a failure that presents as an unrelated capacity error
#   rather than as a concurrency choice. A value of 1 serialises eleven loads
#   that have no ordering requirement between them, spending the window for
#   no correctness gain. Three keeps useful parallelism while bounding
#   connection pressure, and the input lets a root tune that sizing without
#   changing the state-machine topology.
variable "stage_datasets_max_concurrency" {
  description = "Maximum number of seed-staging Map branches allowed to run at once. The environment root may lower it to fit Aurora connection and Fargate task quotas; the default permits parallel loads without starting all eleven branches simultaneously. A sizing value, so it is one of the few a root may legitimately differ on."
  type        = number
  default     = 3

  validation {
    # Assumptions: the floor of 1 is what the Map itself requires -- 0 is
    #   the service's spelling of UNBOUNDED, so accepting it would silently
    #   turn the deliberate bound above into no bound at all. The ceiling of
    #   10 is the number of default branches, past which the value cannot
    #   increase parallelism and only obscures the intent.
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

# Assumptions: a state with NO timeout waits indefinitely. Because the
#   resume state runs only after the chain finishes or fails, a task that
#   hangs holds the whole chain open AND leaves the online read-only flag set
#   until an operator intervenes -- so the absence of a timeout is not a
#   neutral default but an outage the flag makes worse. A conservative
#   ceiling on every state is therefore safer than none.
variable "state_timeout_seconds" {
  description = "Ceiling on each of the twelve work states, keyed by the state name exactly as main.tf spells it. The default puts the migration verification gate at the top ceiling because it re-reads every staged record and runs both committed whole-migration queries, then the four next-longest states -- seed staging, posting, interest and statements -- below it, the three dataset-writing states in the middle, and the three states that only toggle a flag or refresh statistics at the bottom. Every key must be present, so a state can never be left without a timeout: a state with no ceiling waits indefinitely, which holds the whole chain open and leaves the online read-only flag set until an operator intervenes."

  type = map(number)

  # Assumptions: StageSeedDatasets carries the second-largest ceiling, and the reason is
  #   what one branch of it does. It copies bytes from one prefix of a bucket to another,
  #   AND decodes every record of the extract, seals three protected columns through the
  #   key-management service, bulk-copies into a session-temporary table and then merges,
  #   AND runs three verification passes over that one dataset. The largest family alone
  #   -- the daily-transaction extract against ledger.daily_transactions -- costs a hash
  #   of the destination table per load.
  # Assumptions: VerifyMigration is sized above it. It is three passes over the whole
  #   migration, not one dataset: a server-side row-count report, a per-record digest
  #   comparison for each of the ten seeded datasets with the target rows streamed back,
  #   and an exact money-total report. The digest pass reads both sides of every record.
  default = {
    # Refactoring Rationale: VerifyMigration is the entry added to the eleven this map
    #   used to carry, and it carries the LARGEST ceiling of any state. It re-reads every
    #   staged record and runs both committed whole-migration queries on a SELECT-only
    #   session, which is the most work any single state does; 10800 is three hours, the
    #   same order as the two seven-two-hundred states, with headroom because a gate that
    #   times out leaves the online-write bracket engaged.
    # Refactoring Rationale: two further entries -- LoadSeedDatasets and
    #   ReconcileTransactionSequence -- were authored alongside it and are NOT here,
    #   because the states they timed are withdrawn in main.tf. See the withdrawal
    #   recorded there: the refresh state already loads each dataset and already advances
    #   the identifier allocator, so both would have timed a second pass over work that
    #   had been done.
    # Assumptions: StageSeedDatasets keeps its 7200 rather than being lowered to 3600.
    #   That lower figure belonged to the split chain, where the state only staged; here
    #   one branch fetches, stages, loads, runs three verification passes, may stage a
    #   backup generation and may advance the allocator, so the higher ceiling is the one
    #   that matches the work.
    QuiesceOnlineWrites        = 300
    StageSeedDatasets          = 7200
    VerifyMigration            = 10800
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
    # Assumptions: the twelve names are fixed by main.tf's own state list and are
    #   spelled here character for character; the error message carries the
    #   required set. Both directions matter -- a MISSING key would leave that
    #   state without a ceiling, and a key such as "PostTransaction" would apply
    #   cleanly while posting ran unbounded and the operator believed a limit had
    #   been set. Exactness needs both halves of the condition: `setsubtract`
    #   proves every required name is present but says nothing about an extra
    #   one, and a map holds no duplicate keys, so twelve keys that include all
    #   twelve required names are exactly those names. Terraform has no
    #   symmetric-difference function, which is why the count carries the second
    #   half.
    # Refactoring Rationale: the count moved from eleven to twelve when the chain
    #   gained the state that stands between staging an extract and posting against it,
    #   VerifyMigration. It was briefly written as fourteen, alongside two further
    #   states that are withdrawn in main.tf; this check is what makes the count a
    #   measured property of the graph rather than a tally, because a name here with no
    #   state and a state with no name here both fail the plan naming this variable.
    condition = length(var.state_timeout_seconds) == 12 && length(setsubtract([
      "QuiesceOnlineWrites",
      "StageSeedDatasets",
      "VerifyMigration",
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
    error_message = "state_timeout_seconds must hold exactly one entry for each of the twelve work states, named as main.tf spells them: QuiesceOnlineWrites, StageSeedDatasets, VerifyMigration, PreflightDailyTransactions, PostTransactions, CalculateInterest, BackupTransactions, CombineTransactions, GenerateStatements, GenerateReports, AnalyzeTables, ResumeOnlineWrites."
  }

  validation {
    # Trade-offs: the floor of one minute rejects a value so small that a
    #   healthy task is killed during its own startup, which presents as an
    #   intermittent failure rather than as a misconfiguration. The ceiling of
    #   six hours is the point past which a timeout stops acting as a safety
    #   net at all, since a chain permitted to sit that long has already lost
    #   the property the bound exists to protect.
    condition     = alltrue([for t in values(var.state_timeout_seconds) : t >= 60 && t <= 21600 && floor(t) == t])
    error_message = "Every value in state_timeout_seconds must be a whole number of seconds from 60 to 21600 inclusive."
  }
}

# Assumptions: these three knobs express EXPONENTIAL BACKOFF together, and they
#   are the TRANSIENT-FAULT tier only. A task that exits with a hard failure
#   status has produced a deterministic outcome that retrying cannot change, so
#   main.tf retries the service-level errors -- a launch, observation or timeout
#   fault -- and lets a business-level failure fall through to the state's
#   `Catch` instead. Reading these as a general failure remedy is the misreading
#   they are documented against.
variable "retry_max_attempts" {
  description = "Retry attempts each state makes after its first failure, before its catch handler runs. This is the per-state half of the durable retry tier; the other half is that a failed execution can be redriven from the state that failed, and that the batch run ledger makes a step which already completed a no-op when it is retried."
  type        = number
  default     = 3

  validation {
    # Trade-offs: zero is permitted and is legitimate for a caller that
    #   wants a failure to surface immediately, so the floor is 0 rather than
    #   1. The ceiling of 10 exists because retrying a record-processing step
    #   is not free -- each attempt reruns the step from its beginning, so a
    #   step whose failure is deterministic will fail ten times and then fail
    #   once more, having spent the entire window doing it.
    condition     = var.retry_max_attempts >= 0 && var.retry_max_attempts <= 10 && floor(var.retry_max_attempts) == var.retry_max_attempts
    error_message = "retry_max_attempts must be a whole number from 0 to 10 inclusive."
  }
}

# Assumptions: this value and the rate together bound how long a retrying
#   state can occupy the window, so neither is meaningful read alone; the
#   transient-fault reasoning above governs both.
variable "retry_interval_seconds" {
  description = "Seconds a state waits before its first retry. Subsequent waits are this interval multiplied by the backoff rate, compounding per attempt, so this value and the rate together bound how long a retrying state can occupy the batch window."
  type        = number
  default     = 30

  validation {
    # Trade-offs: the floor of 1 second is the smallest the service
    #   accepts, and the ceiling of 600 keeps a single first wait from
    #   exceeding the shortest permitted state timeout, which would otherwise
    #   let a state be killed by its own timeout while still waiting to make
    #   its first retry.
    condition     = var.retry_interval_seconds >= 1 && var.retry_interval_seconds <= 600 && floor(var.retry_interval_seconds) == var.retry_interval_seconds
    error_message = "retry_interval_seconds must be a whole number of seconds from 1 to 600 inclusive."
  }
}

# Assumptions: this is the third of the three backoff knobs and completes
#   the transient-fault tier described above; a rate of exactly 1 makes every
#   wait equal to the interval, which is a flat retry rather than a backoff
#   and is a legitimate choice a caller may want.
variable "retry_backoff_rate" {
  description = "Multiplier applied to the retry interval on each successive attempt. A value of 1 makes every wait equal to the interval, which is a flat retry rather than a backoff; higher values grow the wait geometrically."
  type        = number
  default     = 2.0

  validation {
    # Assumptions: the floor is 1.0 because a rate below one SHORTENS each
    #   successive wait, turning a backoff into an accelerating retry against
    #   a dependency that is already failing -- the opposite of what the
    #   mechanism is for, and accepted silently without this check.
    condition     = var.retry_backoff_rate >= 1.0 && var.retry_backoff_rate <= 10.0
    error_message = "retry_backoff_rate must be between 1.0 and 10.0 inclusive; a value below 1.0 would shorten each successive wait instead of lengthening it."
  }
}

# Refactoring Rationale: the three inputs below configure the cancellation
#   sub-chain the daily failure path now runs before it releases the online-write
#   bracket, and they are separate from the retry inputs above because they
#   measure a different thing. The retry inputs govern how many times a failing
#   API call is repeated; these govern how long the graph is prepared to wait for
#   a container that has been asked to stop to actually exit. Sharing one set
#   would tie the number of DescribeTasks polls to the number of SNS publish
#   retries, which have nothing to do with each other.
# Assumptions: all three carry defaults, so neither environment root has to
#   declare them and the cancellation behaviour is identical in dev and prod.
#   Nothing about draining a task differs by environment.
variable "cancellation_poll_seconds" {
  description = "Seconds the daily failure path waits between passes of the residual-task cancellation loop. The default matches the Amazon ECS container stop timeout, which is the shortest interval after which a task asked to stop can plausibly have exited, so a smaller value spends DescribeTasks calls observing a container that cannot yet be gone."
  type        = number
  default     = 30

  validation {
    # Assumptions: the floor is 5 seconds because the loop's whole purpose is to
    #   let a SIGTERM be honoured, and a poll faster than that observes nothing
    #   new while still counting against the attempt budget -- it converts the
    #   budget from a drain allowance into a burst of API calls.
    condition     = var.cancellation_poll_seconds >= 5 && var.cancellation_poll_seconds <= 300 && floor(var.cancellation_poll_seconds) == var.cancellation_poll_seconds
    error_message = "cancellation_poll_seconds must be a whole number of seconds between 5 and 300 inclusive."
  }
}

variable "cancellation_max_attempts" {
  description = "How many passes of the residual-task cancellation loop the daily failure path will make before it gives up and fails WITHOUT releasing the online-write bracket. The product of this value and cancellation_poll_seconds is the total drain allowance; exhausting it is treated as an operator-visible failure rather than an excuse to release, because releasing while a batch task may still be writing is the corruption the bracket exists to prevent."
  type        = number
  default     = 10

  validation {
    # Assumptions: the floor is 1 rather than 0. At zero the loop would fail on
    #   its first pass without ever waiting, which makes every failing execution
    #   that started a task end in the unconfirmed terminal state and require an
    #   operator -- a bound so tight it is indistinguishable from no cleanup at
    #   all. The ceiling of 100 keeps the worst-case drain inside the whole-machine
    #   ceiling, whose validation adds this budget to its own floor.
    condition     = var.cancellation_max_attempts >= 1 && var.cancellation_max_attempts <= 100 && floor(var.cancellation_max_attempts) == var.cancellation_max_attempts
    error_message = "cancellation_max_attempts must be a whole number between 1 and 100 inclusive."
  }
}

variable "cancellation_state_timeout_seconds" {
  description = "Ceiling on each individual state of the residual-task cancellation sub-chain: the ListTasks discovery call and the two Map states that stop and then confirm the tasks. Held separately from state_timeout_seconds because that map's validation asserts exactly the eleven names of the nightly work chain, and these states are failure-path recovery rather than work."
  type        = number
  default     = 60

  validation {
    condition     = var.cancellation_state_timeout_seconds >= 30 && var.cancellation_state_timeout_seconds <= 900 && floor(var.cancellation_state_timeout_seconds) == var.cancellation_state_timeout_seconds
    error_message = "cancellation_state_timeout_seconds must be a whole number of seconds between 30 and 900 inclusive."
  }
}

variable "cancellation_max_concurrency" {
  description = "How many residual tasks the cancellation sub-chain stops and confirms in parallel. Bounded rather than unlimited so that a failure which left many tasks running cannot answer with a burst of StopTask and DescribeTasks calls large enough to be throttled, which would turn a cleanup into a second failure."
  type        = number
  default     = 5

  validation {
    condition     = var.cancellation_max_concurrency >= 1 && var.cancellation_max_concurrency <= 20 && floor(var.cancellation_max_concurrency) == var.cancellation_max_concurrency
    error_message = "cancellation_max_concurrency must be a whole number between 1 and 20 inclusive."
  }
}

# Assumptions: this is a CEILING, not an expectation of how long a run takes, and
#   per-state timeouts do not make it redundant -- an execution can stall between
#   states, or in a Map's own bookkeeping, where no single state's timeout
#   applies, and because the resume state runs only once the chain finishes or
#   fails, an indefinite wait holds the online write path quiesced for as long as
#   it lasts.
# Trade-offs: the bound caps how long a RUNNING execution can hold the bracket;
#   it does not itself release it, because a TIMED_OUT execution runs no further
#   state, so the release on that path comes from the out-of-execution watchdog
#   rule in main.tf. This same value is published to the quiesce call as the
#   bracket's lease length, so the advertised expiry and the enforced ceiling
#   agree by construction.
variable "state_machine_timeout_seconds" {
  description = "Ceiling on a single daily-batch execution, applied at the top level of the state machine definition rather than to any one state. It bounds the whole chain: an execution that stalls where no individual state's timeout applies would otherwise wait indefinitely, holding the online read-only flag set, because the resume state runs only after the chain finishes or fails. The ceiling caps how long the flag can be held rather than releasing it -- a timed-out execution runs no further state -- so release on that path comes from the out-of-execution watchdog rule, and this same value is published to the quiesce call as the bracket's lease length. The default is validated against the aggregate SEQUENTIAL budget of the twelve work states rather than against the largest single one, because the chain runs them one after another."
  type        = number
  # Refactoring Rationale: the default is 61200 where it read 46800, and the raise is a
  #   MEASURED correction rather than headroom taken for comfort. The floor below is the
  #   SUM of the per-state ceilings plus two further allowances, and at the defaults it
  #   evaluates to 58920 -- 54600 for the twelve sequential ceilings, 2520 for the retry
  #   waits at twelve states times 210 seconds each, 1500 for the cancellation drain and
  #   300 for the second ResumeOnlineWrites. 46800 was below that, so the module's own
  #   validation refused its own default, and a caller who accepted the default got a
  #   plan-time error naming this variable. It was already below the floor at eleven
  #   states, where the same formula returns 47910; adding the verification gate widened
  #   the gap rather than opening it. 61200 is seventeen hours, which clears the floor
  #   with 2280 seconds of margin and stays well inside the service's own 86400 ceiling.
  # Assumptions: raising this value lengthens the quiesce LEASE as well, because the same
  #   number is published to the quiesce call as the bracket's lease length. That is the
  #   intended coupling and not a side effect: the lease must outlast the execution it
  #   brackets, or the watchdog releases the read-only flag while the chain is still
  #   posting.
  default = 61200

  validation {
    # Refactoring Rationale: the floor was `max` over the per-state ceilings and
    #   is now their SUM plus two further allowances, because `max` was the wrong
    #   comparison for a chain whose states run SEQUENTIALLY. The eleven default
    #   ceilings totalled 43800 seconds against a whole-machine default of 28800, so
    #   a run in which several long states each used most of its own allowance was
    #   aborted at the top level while every state was still inside its budget --
    #   and a top-level expiry runs no further state, so it left the online-write
    #   bracket engaged and produced no notification. The old floor could not
    #   detect that: 28800 is comfortably above the largest single ceiling of 7200.
    # Assumptions: the floor is the sum of three named terms, each of which is a
    #   real thing the ceiling has to cover.
    #     1. sequential work -- every one of the twelve states running to its own
    #        ceiling, in order, which is the shape of the chain.
    #     2. retry waits -- the geometric backoff each state may spend before its
    #        final attempt. This is the WAITS only, not additional whole attempts;
    #        see the trade-off below.
    #     3. the failure path -- the residual-task cancellation loop's full drain
    #        allowance plus a second ResumeOnlineWrites, because a chain that fails
    #        late still has to cancel its tasks and clear the bracket, and the
    #        top-level ceiling aborts that cleanup too if it does not fit.
    # Trade-offs: term 2 counts the retry WAITS but not the extra whole attempts a
    #   retried state may consume. Counting those would multiply term 1 by one plus
    #   retry_max_attempts, which at the defaults is 218400 seconds -- above the
    #   86400 the service itself permits, so no value could satisfy the floor and
    #   the check would refuse every configuration. The line drawn here is that the
    #   ceiling must not abort a HEALTHY sequential run; a run that additionally
    #   burned several full state timeouts on retries is exactly the run this
    #   ceiling exists to abort.
    # Assumptions: the geometric sum is written with an explicit guard for a
    #   backoff rate of exactly 1, where the series degenerates to a flat interval
    #   and the closed form would divide by zero. retry_backoff_rate's own
    #   validation admits 1.0, so the guard is reachable rather than defensive.
    condition = var.state_machine_timeout_seconds <= 86400 && floor(var.state_machine_timeout_seconds) == var.state_machine_timeout_seconds && var.state_machine_timeout_seconds >= (
      sum(values(var.state_timeout_seconds))
      + length(var.state_timeout_seconds) * (
        var.retry_backoff_rate == 1
        ? var.retry_interval_seconds * var.retry_max_attempts
        : var.retry_interval_seconds * (pow(var.retry_backoff_rate, var.retry_max_attempts) - 1) / (var.retry_backoff_rate - 1)
      )
      + var.cancellation_max_attempts * (var.cancellation_poll_seconds + 2 * var.cancellation_state_timeout_seconds)
      + var.state_timeout_seconds["ResumeOnlineWrites"]
      # Refactoring Rationale: a `max` over the map's values stood here in a second
      #   authoring of this same floor, spread with `...` and justified by the map
      #   holding a fixed number of entries. It is superseded rather than merged: the
      #   sum above is the strictly stronger bound for the same reason recorded at the
      #   top of this block -- the states run SEQUENTIALLY, so the largest single
      #   ceiling is not a bound on the chain, and a floor set from it admits a
      #   whole-machine ceiling that expires while every state is still inside its own
      #   allowance. The entry-count assertion that authoring relied on is kept, at the
      #   exactness check on var.state_timeout_seconds itself.
    )
    error_message = "state_machine_timeout_seconds must be a whole number of seconds, at most 86400, and at least the aggregate sequential budget of the chain: the sum of all twelve state_timeout_seconds values, plus each state's geometric retry waits, plus the failure path's cancellation drain allowance and a second ResumeOnlineWrites. A ceiling below that aborts a healthy sequential run at the top level, which runs no further state and therefore leaves the online-write bracket engaged with no notification."
  }
}

# Trade-offs: this is a SEPARATE ceiling from the daily one rather than a
#   shared value, and the reason is that one on-demand report is a much
#   smaller unit of work than the whole nightly chain. A single shared value
#   would have to be sized for the larger of the two, which would leave a
#   stuck ad-hoc report holding a task for the length of a full batch window
#   before anything noticed. The cost of the separation is one more input.
variable "adhoc_report_timeout_seconds" {
  description = "Ceiling on a single ad-hoc report execution, applied at the top level of the ad-hoc state machine definition. Separate from the daily ceiling because one on-demand report is a far smaller unit of work than the nightly chain, and a shared value would have to be sized for the larger of the two."
  type        = number
  default     = 7200

  validation {
    # Assumptions: the ad-hoc machine's single work state draws its timeout from
    #   the SAME map as the daily report state, under the key `GenerateReports`,
    #   so the floor is derived from that entry rather than from a literal that
    #   would silently become too low when a caller raised the report state's own
    #   ceiling. The key is indexed rather than looked up with a fallback because
    #   state_timeout_seconds is validated to hold every one of the twelve names.
    condition     = var.adhoc_report_timeout_seconds <= 86400 && floor(var.adhoc_report_timeout_seconds) == var.adhoc_report_timeout_seconds && var.adhoc_report_timeout_seconds >= var.state_timeout_seconds["GenerateReports"]
    error_message = "adhoc_report_timeout_seconds must be a whole number of seconds, at most 86400, and at least as large as the ceiling the report state itself receives -- the GenerateReports entry in state_timeout_seconds."
  }
}

# -----------------------------------------------------------------------------
# Deliberate absences. Everything below is an input this module does NOT take,
# recorded so that none of them reads as an oversight to be repaired. Each names
# the owner the value belongs to instead.
# -----------------------------------------------------------------------------

# Assumptions: NO `business_date` input, and its absence is the decision. The
#   daily machine takes `businessDate` from its EXECUTION INPUT -- supplied by
#   infra/modules/eventbridge-scheduler in the schedule payload, or by an
#   operator starting an execution directly -- and threads it into every task's
#   command override. A Terraform variable would bake one date into the
#   infrastructure and every night would post the same day. app/jcl/INTCALC.jcl:22
#   reads `//STEP15 EXEC PGM=CBACT04C,PARM='2022071800'`, injecting the
#   processing date as a step parameter rather than letting the program read the
#   clock, and that injection is what makes a rerun reproducible.

# Alternatives Considered: NO `state_machine_type` input. Exposing the
#   workflow type was considered and
#   rejected on two hard limits rather than a preference. main.tf hard-codes
#   STANDARD because an Express workflow caps its execution at five minutes,
#   which every task state here exceeds, and because Express does not support
#   the synchronous `.sync` service integrations at all -- the very mechanism
#   that makes a state wait for its task to finish. An input would therefore
#   offer a value that cannot work.

# Assumptions: NO `schedule_expression` input. The nightly cron belongs to
#   infra/modules/eventbridge-scheduler, which TARGETS the state-machine ARN
#   this module publishes. Accepting a schedule here would invert that
#   dependency and give one chain two possible triggers.

# Alternatives Considered: NO `assign_public_ip` input; recorded in full on
#   the networking section
#   above -- main.tf sets the literal DISABLED because these tasks reach AWS
#   APIs through interface endpoints and the internet through the NAT
#   gateway, so a public address would add exposure without adding
#   reachability.

# Assumptions: NO bucket, prefix or lifecycle input beyond
#   `dataset_bucket_name`. The bucket itself, the ten generation-dataset prefix
#   families derived from app/jcl/DEFGDGB.jcl, app/jcl/DEFGDGD.jcl and
#   app/jcl/DALYREJS.jcl, and the five-noncurrent-version retention rule that
#   is the `LIMIT(5) SCRATCH` analogue all belong to
#   infra/modules/s3-datasets. This module passes a name through to the tasks
#   and creates nothing inside the bucket, so a prefix or lifecycle input
#   here would duplicate a contract that has one owner.

# Assumptions: NO alarm, dashboard or subscription input. Those belong to
#   infra/modules/observability, which CONSUMES
#   the state-machine ARN this module publishes. This module's only
#   observability surface is the two log groups it owns and the topic it
#   publishes a failure to; alarming on an execution is the consumer's
#   concern, and splitting it across both modules would leave neither owning
#   it.
# =============================================================================

# -----------------------------------------------------------------------------
# Operator-invoked dataset round trip
# -----------------------------------------------------------------------------

variable "dataset_state_timeout_seconds" {
  description = "Per-state ceiling for the two work states of the operator-invoked dataset round trip, keyed by state name: ExportDataset and ImportDataset. Held in its own map rather than merged into state_timeout_seconds because that variable's validation asserts exactly the twelve names of the nightly chain, and widening it would weaken the check that catches a missing or misspelled nightly ceiling."
  type        = map(number)

  default = {
    # Assumptions: the export reads five whole masters and the import re-reads
    #   every record it wrote, so both are sized above the nightly backup rather
    #   than at a per-record cost. They are equal because the two traverse the
    #   same dataset once each -- the export writing it and the import splitting
    #   it into six artefacts.
    ExportDataset = 3600
    ImportDataset = 3600
  }

  validation {
    # Assumptions: the two names are fixed by main.tf's own definition and are
    #   spelled here character for character, and BOTH directions are checked.
    #   A missing key leaves that state without a ceiling; a key such as
    #   "ExportDatasets" would apply cleanly while the export ran unbounded and
    #   the operator believed a limit had been set. A map holds no duplicate
    #   keys, so two keys that include both required names are exactly those
    #   names -- which is why the count carries the second half of the test, as
    #   Terraform has no symmetric-difference function.
    condition = length(var.dataset_state_timeout_seconds) == 2 && length(setsubtract([
      "ExportDataset",
      "ImportDataset",
    ], keys(var.dataset_state_timeout_seconds))) == 0
    error_message = "dataset_state_timeout_seconds must hold exactly one entry for each of the two round-trip work states, named as main.tf spells them: ExportDataset, ImportDataset."
  }

  validation {
    # Trade-offs: the same floor and ceiling the nightly work states carry, and
    #   for the same reasons. A minute is the point below which a healthy task is
    #   killed during its own start-up, which presents as an intermittent failure
    #   rather than as a misconfiguration; six hours is the point past which a
    #   timeout has stopped acting as a safety net.
    condition     = alltrue([for t in values(var.dataset_state_timeout_seconds) : t >= 60 && t <= 21600 && floor(t) == t])
    error_message = "Every value in dataset_state_timeout_seconds must be a whole number of seconds from 60 to 21600 inclusive."
  }
}

variable "dataset_roundtrip_timeout_seconds" {
  description = "Ceiling on a single dataset round-trip execution, applied at the top level of that state machine's definition. Separate from the two per-state ceilings because a per-state TimeoutSeconds does not bound an execution that stalls between states or inside the service's own bookkeeping."
  type        = number
  default     = 7800

  validation {
    # Assumptions: the floor is the SUM of the two per-state ceilings rather than
    #   the larger of them, because the export and the import run in sequence --
    #   a machine ceiling below their sum could expire while the import was still
    #   inside its own allowance, which is the one failure a ceiling must not
    #   cause. It is derived from the map rather than written as a literal so that
    #   raising a state's own ceiling cannot leave this bound silently too low.
    # Trade-offs: the ceiling of one day matches the ad-hoc report machine. An
    #   execution permitted to sit longer than that has already lost the property
    #   the bound exists to protect, and an operator round trip is not a workload
    #   anyone waits a day for.
    condition     = var.dataset_roundtrip_timeout_seconds <= 86400 && floor(var.dataset_roundtrip_timeout_seconds) == var.dataset_roundtrip_timeout_seconds && var.dataset_roundtrip_timeout_seconds >= var.dataset_state_timeout_seconds["ExportDataset"] + var.dataset_state_timeout_seconds["ImportDataset"]
    error_message = "dataset_roundtrip_timeout_seconds must be a whole number of seconds, at most 86400, and at least the SUM of the ExportDataset and ImportDataset entries in dataset_state_timeout_seconds, because those two states run in sequence."
  }
}

variable "authorization_state_timeout_seconds" {
  description = "Per-state ceiling for the two work states of the operator-invoked authorization extract, keyed by state name: UnloadAuthorizations and LoadAuthorizations. Held in its own map for the reason dataset_state_timeout_seconds is, so that widening either map cannot weaken the other's exact-name check."
  type        = map(number)

  default = {
    # Assumptions: the export walks every summary row and every authorization under
    #   it, and the load re-reads every record it wrote, so the two traverse the same
    #   segments once each and are sized equally. They sit below the dataset pair
    #   because the authorization segments are a fraction of the transaction masters.
    UnloadAuthorizations = 1800
    LoadAuthorizations   = 1800
  }

  validation {
    # Assumptions: BOTH directions are checked, as the dataset map records. A missing
    #   key leaves that state unbounded; a misspelled one applies cleanly while the
    #   state runs unbounded and the operator believes a limit was set. A map holds no
    #   duplicate keys, so the count carries the second half of the test.
    condition = length(var.authorization_state_timeout_seconds) == 2 && length(setsubtract([
      "UnloadAuthorizations",
      "LoadAuthorizations",
    ], keys(var.authorization_state_timeout_seconds))) == 0
    error_message = "authorization_state_timeout_seconds must hold exactly one entry for each of the two extract work states, named as main.tf spells them: UnloadAuthorizations, LoadAuthorizations."
  }

  validation {
    # Trade-offs: the same floor and ceiling every other work state in this module
    #   carries, and for the same reasons.
    condition     = alltrue([for t in values(var.authorization_state_timeout_seconds) : t >= 60 && t <= 21600 && floor(t) == t])
    error_message = "Every value in authorization_state_timeout_seconds must be a whole number of seconds from 60 to 21600 inclusive."
  }
}

variable "authorization_extract_timeout_seconds" {
  description = "Ceiling on a single authorization-extract execution, applied at the top level of that state machine's definition. Separate from the two per-state ceilings because a per-state TimeoutSeconds does not bound an execution that stalls between states."
  type        = number
  default     = 2100

  validation {
    # Assumptions: the floor is the LARGER of the two per-state ceilings rather than
    #   their sum, which is where this bound differs from the dataset round trip's and
    #   why it is not simply a copy of it. The export and the load are ALTERNATIVES
    #   selected by the request's mode, never a sequence, so no execution can run both
    #   and a floor at their sum would only permit an idle execution to sit twice as
    #   long as any real one. It is derived from the map so that raising a state's own
    #   ceiling cannot leave this bound silently too low.
    # Trade-offs: the ceiling of one day matches the other two operator-facing
    #   machines in this module.
    condition     = var.authorization_extract_timeout_seconds <= 86400 && floor(var.authorization_extract_timeout_seconds) == var.authorization_extract_timeout_seconds && var.authorization_extract_timeout_seconds >= max(var.authorization_state_timeout_seconds["UnloadAuthorizations"], var.authorization_state_timeout_seconds["LoadAuthorizations"])
    error_message = "authorization_extract_timeout_seconds must be a whole number of seconds, at most 86400, and at least the LARGER of the two entries in authorization_state_timeout_seconds, because the export and the load are alternatives rather than a sequence."
  }
}
