# =============================================================================
# infra/modules/kms/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input surface of the `kms` module -- the module that provisions
#   the four customer-managed encryption keys (Aurora, S3, Secrets Manager and
#   SQS), their aliases, their rotation setting and their key policies. Every
#   value a calling environment root can configure is declared here, and
#   nothing else is configurable: anything absent from the list below is a
#   property of the module fixed in main.tf, not a per-environment choice.
#
#   Nothing here is read from the ambient environment and nothing is generated
#   inside the module. Every input arrives from infra/envs/dev/main.tf or
#   infra/envs/prod/main.tf, which is what keeps the only differences between
#   the two environments visible in their own terraform.tfvars files instead of
#   hidden in this module.
#
# Parameters -- one required, eight optional:
#   environment                string       REQUIRED. Selects which
#                                           environment's aliases the keys take.
#   name_prefix                string       Common prefix for the alias names.
#   enable_key_rotation        bool         Automatic rotation of key material.
#   deletion_window_in_days    number       Pending-deletion window a destroyed
#                                           key waits out.
#   tags                       map(string)  Key-specific tags, layered on the
#                                           root's default_tags.
#   aurora_key_user_role_arns  list(string) Principals the Aurora key policy
#                                           grants use to.
#   s3_key_user_role_arns      list(string) The same, for the S3 key.
#   secrets_key_user_role_arns list(string) The same, for the Secrets Manager
#                                           key.
#   sqs_key_user_role_arns     list(string) The same, for the SQS key.
#
#   Each block below carries the full `type` and `description` that TFLint's
#   terraform_typed_variables and terraform_documented_variables rules require;
#   the summary above is a map of the surface, not a second copy of it.
#
# Return values:
#   None. A variables.tf declares no output, so the key identifiers, key ARNs
#   and alias names this module publishes to its caller are declared in
#   infra/modules/kms/outputs.tf.
#
# Errors / Exceptions:
#   Three inputs carry a `validation` block, and each rejects a bad value during
#   `terraform plan`, before any request leaves the machine, rather than letting
#   the service reject it part-way through `terraform apply`: `environment`
#   (not one of the two environments that have a root), `name_prefix`
#   (characters an alias name cannot hold), and `deletion_window_in_days`
#   (outside the range the service accepts, or fractional). `environment` has no
#   default, so omitting it stops the run with a missing-required-argument
#   error instead of provisioning keys under a guessed name.
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: one shared list of trusted principals rather than
#     four. Rejected -- it would grant every trusted principal use of every key,
#     erasing the per-domain boundary that provisioning four keys instead of one
#     exists to create.
#   - Assumption: the four trust lists default to empty so the keys can exist
#     before the task roles that use them do. Those roles come from the
#     `ecs-service` module, which consumes this module's key ARNs, so a
#     non-empty requirement would make the grant a precondition of the key the
#     grant depends on.
#   - Assumption: the calling root owns the baseline tag set through its
#     provider's `default_tags`, so `tags` here is a layer above that set rather
#     than the whole of it.
#   - Trade-off: `environment` has no default at all, and the deletion window
#     defaults to the shortest the service accepts. Both lean toward a caller
#     that states what it wants over a caller that receives a silent guess.
#   Each bullet is expanded, with its mechanism, at the variable it applies to.
#
#   No input here accepts key material, a key policy document, a credential or
#   an account identifier, and no default holds an ARN literal. Key ARNs travel
#   outward from this module as outputs, never inward as source-committed
#   inputs: an ARN embeds an AWS account identifier, and the project's
#   no-secrets-in-source constraint admits no exception. A "sample" ARN default
#   is therefore not a convenience that was overlooked -- it is excluded.
#
#   Where a comment below reasons about `terraform apply` or `terraform destroy`
#   it is describing what an input means at those points, not reporting on a
#   provisioned stack. This tree is authored and statically validated --
#   formatted, validated, planned, linted and policy-scanned; applying it to a
#   live account is an operator action outside this scope.
# =============================================================================

# -----------------------------------------------------------------------------
# Naming and environment
# -----------------------------------------------------------------------------

# WHY this is the one input with no `default` (Trade-off): a defaulted
# environment name is the precise mechanism by which a key meant for one
# environment ends up carrying the other environment's alias -- the caller omits
# the argument, the module supplies a name regardless, and the mistake stays
# invisible because the plan is clean and the alias reads as deliberate.
# Requiring the value costs each root one line and converts that failure into a
# missing-required-argument error before any key is created.
#
# WHY the accepted values are exactly two (Assumption): two environment roots
# exist, infra/envs/dev and infra/envs/prod, and this module is called only from
# those two. A third value would mint aliases for an environment whose state no
# root tracks, so the check asserts the shape of the tree rather than a naming
# preference.
variable "environment" {
  description = "Environment name interpolated into all four KMS alias names, so one environment's keys are distinguishable from the other's in the console and in any alias-based key reference; must be `dev` or `prod`, the two environments that have a Terraform root under infra/envs/."
  type        = string

  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment must be exactly \"dev\" or \"prod\", matching the environment root under infra/envs/ that calls this module."
  }
}

# WHY the characters are checked here rather than trusted (Trade-off): this
# prefix is concatenated into each alias name, and an alias name accepts only
# alphanumerics, hyphens, underscores and forward slashes. A prefix carrying a
# space, a dot or an uppercase letter is caught by neither the type system nor
# `terraform plan`; it is caught by the service during `terraform apply`, after
# the run has begun creating keys. Validating locally moves that failure to plan
# time, and the cost paid is that a legitimately unusual prefix has to be
# spelled out in this file before it can be used. The 32-character ceiling is
# the same trade -- it leaves the finished alias well inside the length the
# service accepts once the per-key purpose and the environment name are
# appended.
#
# WHY the name and the default match infra/bootstrap/variables.tf instead of
# being chosen here (Assumption): every directory in this tree takes its prefix
# through a variable of this name with this default, which is what lets a root
# pass one value to every module it calls rather than each module negotiating
# its own. A `prefix` or `resource_prefix` variant would read as a different
# concept and would have to be mapped at each call site.
variable "name_prefix" {
  description = "Prefix concatenated into each KMS alias name ahead of the key's purpose and the environment, giving the four keys one greppable identity shared with the rest of the stack's resource names; lowercase letters, digits and hyphens only, at most 32 characters, matching the characters an alias name accepts."
  type        = string
  default     = "carddemo"

  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", var.name_prefix)) && length(var.name_prefix) <= 32
    error_message = "name_prefix must be 1 to 32 characters of lowercase letters, digits and hyphens, beginning and ending with a letter or digit -- for example \"carddemo\"."
  }
}

# -----------------------------------------------------------------------------
# Key rotation
# -----------------------------------------------------------------------------

# WHY this is an input at all when the answer is already settled (Assumption):
# rotation-on is the module's design intent, not a caller preference. The target
# architecture specifies four customer-managed keys WITH rotation, so `true` is
# the only value either environment root is expected to pass. The variable
# exists so that the setting is legible at the call site and in this module's
# generated documentation -- an operator can see it and reason about it -- and
# not so that it can be turned off quietly. What keeps that honest is external
# to this file: the policy-scan step in the infrastructure pipeline reports at
# HIGH and CRITICAL severity, and a customer-managed key with rotation disabled
# is exactly the class of finding it raises, so `false` reddens the pipeline.
# That is the mechanism, not an opinion about how the key ought to be set.
#
# WHY no companion rotation-interval input (Alternatives Considered): exposing
# the interval as well was considered and rejected. Enabling rotation applies
# the service's own interval, nothing in the target architecture asks for a
# different one, and an input that is never varied is one more knob a reader
# must evaluate before discovering it does not matter.
variable "enable_key_rotation" {
  description = "Whether all four customer-managed keys rotate their key material automatically on the service's own interval; `true` is the posture the target architecture specifies, and the pipeline's policy scan treats a customer-managed key without rotation as a HIGH-or-above finding."
  type        = bool
  default     = true
}

# -----------------------------------------------------------------------------
# Teardown and recovery window
# -----------------------------------------------------------------------------

# WHY the range is asserted locally (Assumption): the service accepts a window
# of 7 to 30 days and nothing outside it, and the `number` type additionally
# admits a fractional value the service does not take. Neither is visible to
# `terraform plan` without this block, so both surface as a service rejection
# during `terraform apply`, part-way through a run that has already created
# other resources. Checking here converts an apply-time rejection into a
# plan-time error.
#
# WHY the default leans to the floor of that range (Trade-off): the two ends of
# the range buy different things. A short window lets `terraform destroy`
# release the keys sooner, which is what the acceptance criterion of a stack
# that tears down cleanly asks for, and it stops a torn-down environment from
# leaving four keys behind in a pending-deletion state that still bills. A long
# window preserves a longer interval in which a key deleted by mistake can be
# recovered -- past its window a key is gone and every ciphertext under it is
# permanently unreadable. The module default takes the short end because the
# case a module default serves is the one exercised most, provisioning and
# tearing down a non-production environment, and because the recovery interval
# is a production concern that infra/envs/prod/terraform.tfvars is expected to
# state outright rather than inherit.
#
# WHY setting it differently per environment does not fork the topology
# (Assumption): the two environment roots are required to be identical in shape
# and to differ only in sizing and retention values. This is a retention value,
# so infra/envs/dev/terraform.tfvars and infra/envs/prod/terraform.tfvars may
# legitimately disagree on it while still describing the same stack.
variable "deletion_window_in_days" {
  description = "Days a destroyed key spends pending deletion before the service removes it and every ciphertext under it becomes permanently unreadable; the service accepts 7 through 30, and this is one of the retention values the dev and prod roots are permitted to set differently without changing the stack's shape."
  type        = number
  default     = 7

  validation {
    condition     = var.deletion_window_in_days >= 7 && var.deletion_window_in_days <= 30 && floor(var.deletion_window_in_days) == var.deletion_window_in_days
    error_message = "deletion_window_in_days must be a whole number from 7 to 30 inclusive -- the range the service accepts when a key deletion is scheduled."
  }
}

# -----------------------------------------------------------------------------
# Tagging
# -----------------------------------------------------------------------------

# WHY an empty default here reads as complete rather than as an oversight
# (Assumption): the baseline tag set is not this module's to supply. Each
# calling root configures `default_tags` on its own `provider "aws"` block, and
# the provider merges that map into every taggable resource it creates, so the
# four keys carry the root's common tags whether or not this variable is passed.
# What this input adds is the layer above that -- tags that distinguish these
# keys from the rest of the root's resources. Without this note an empty default
# looks like missing tagging, and it is not.
variable "tags" {
  description = "Key-specific tags merged onto each of the four keys, layered on top of the common tag set the calling root already applies through its provider's `default_tags`; defaults to none, because the baseline tags arrive from the root rather than from this module."
  type        = map(string)
  default     = {}
}

# -----------------------------------------------------------------------------
# Trusted key users -- one list per key
# -----------------------------------------------------------------------------
#
# The four inputs below are the least-privilege half of each key policy: the
# policy main.tf composes for a key grants cryptographic use of that key to the
# principals its own list names, and to no others. The three notes in this
# section are shared, and they govern all four declarations that follow -- which
# are the last four in this file -- because the reasoning is identical across
# them and repeating it four times would let the copies drift apart.
#
# WHY four lists rather than one (Alternatives Considered): a single
# `key_user_role_arns` applied to all four policies is the obvious
# simplification, and it is rejected. It would grant every trusted principal use
# of every key, so a service trusted only to read queue payloads could also
# decrypt database ciphertext -- which erases the per-domain boundary that
# provisioning four keys instead of one exists to create in the first place.
# Keeping the lists separate confines a trust entry added to the wrong list to a
# single data class instead of letting it reach all four.
#
# WHY each defaults to empty (Assumption): the principals these lists name are
# the ECS task roles, and those roles are created by the `ecs-service` module --
# which itself consumes this module's key ARNs. Requiring a non-empty list would
# therefore make the grant a precondition of the key that the grant depends on.
# An empty default separates the two concerns: the keys and their aliases are
# created, each policy still reserves administrative control to the account
# root, and the caller supplies the use grants from the roles once those roles
# exist. An empty default that looks like an oversight is worse than none, so it
# is recorded here as deliberate.
#
# WHY no default may ever carry an example value (Alternatives Considered,
# Trade-off): seeding one of these defaults with a specimen ARN, so a reader can
# see the shape the input expects, is the alternative and it is refused. An ARN
# embeds an AWS account identifier, and no account identifier belongs in this
# repository -- the project's no-secrets-in-source constraint admits no
# exception. These four lists are inputs the caller supplies from its own state,
# while the key ARNs this module produces travel the other way, as outputs. The
# trade accepted is that the expected shape is conveyed in prose, by the `type`
# and the description, instead of by a specimen value; the empty defaults are
# therefore load-bearing rather than placeholders awaiting one.

variable "aurora_key_user_role_arns" {
  description = "IAM role ARNs the Aurora key's policy grants cryptographic use of that key to -- the principals that read or write the encrypted database cluster and its automated backups; empty by default, since those roles are created by the module that consumes this key's ARN."
  type        = list(string)
  default     = []
}

variable "s3_key_user_role_arns" {
  description = "IAM role ARNs the S3 key's policy grants cryptographic use of that key to -- the principals that read or write objects in the encrypted dataset bucket, which is where the batch and ETL steps stage dataset generations; empty by default, for the reason this section's shared notes record."
  type        = list(string)
  default     = []
}

variable "secrets_key_user_role_arns" {
  description = "IAM role ARNs the Secrets Manager key's policy grants cryptographic use of that key to -- the principals that read the generated database and seed-user credentials the stack stores rather than commits; empty by default, for the reason this section's shared notes record."
  type        = list(string)
  default     = []
}

variable "sqs_key_user_role_arns" {
  description = "IAM role ARNs the SQS key's policy grants cryptographic use of that key to -- the principals that send or receive on the encrypted request, reply and error queues, including their dead-letter queues; empty by default, for the reason this section's shared notes record."
  type        = list(string)
  default     = []
}
