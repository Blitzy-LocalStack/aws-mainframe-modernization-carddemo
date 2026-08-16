# =============================================================================
# infra/modules/kms/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input surface of the `kms` module -- the module that provisions
#   the four customer-managed encryption keys (Aurora, S3, Secrets Manager and
#   SQS), their aliases, their rotation setting and their key
#   policies. Every
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
# Parameters -- TWENTY-ONE inputs, two required and nineteen optional, in three
# groups. The count and the grouping are measurements over this file
# (`grep -c '^variable "'`), not a description written once and left behind:
#
#   Shape of every key (5):
#     environment              string       REQUIRED. Selects which
#                                           environment's aliases the keys take.
#     name_prefix              string       Common prefix for the alias names.
#     enable_key_rotation      bool         Automatic rotation of key material.
#     deletion_window_in_days  number       Pending-deletion window a destroyed
#                                           key waits out.
#     tags                     map(string)  Key-specific tags, layered on the
#                                           root's default_tags.
#
#   Trusted principals, one list per key (5):
#     aurora_key_user_role_arns       list(string) Principals the Aurora key
#                                                  policy grants use to.
#     s3_key_user_role_arns           list(string) The same, for the S3 key.
#     secrets_key_user_role_arns      list(string) The same, for the Secrets
#                                                  Manager key.
#     sqs_key_user_role_arns          list(string) The same, for the SQS key.
#     application_envelope_user_role_arns  list(string) The same, for the
#                                                  application-data key -- the
#                                                  one key a workload role calls
#                                                  directly.
#
#   Conditions that narrow those grants, and the service-principal grants (11):
#     aurora_encryption_context_ids           list(string) aws:rds:db-id values.
#     s3_encryption_context_bucket_arns       list(string) aws:s3:arn values.
#     secrets_encryption_context_arns         list(string) SecretARN values.
#     application_envelope_context_purposes list(string) carddemo:purpose
#                                                          values -- the
#                                                          envelope grant's
#                                                          narrowing condition,
#                                                          in place of the
#                                                          kms:ViaService the
#                                                          other four use.
#     cloudfront_distribution_arn             string       REQUIRED. Exact SPA
#                                                          distribution allowed
#                                                          to decrypt the
#                                                          SSE-KMS origin.
#     cloudfront_distribution_arns            list(string) Additional exact
#                                                          distributions.
#     s3_cloudfront_distribution_arns         list(string) The same; main.tf
#                                                          reads all three
#                                                          spellings together.
#     cloudwatch_log_delivery_source_arns     list(string) Log-delivery sources
#                                                          granted a data key.
#     cloudwatch_log_group_arns               list(string) Exact log groups.
#     sns_topic_arns                          list(string) Exact alert topics.
#     sqs_key_eventbridge_rule_source_arn_patterns
#                                             list(string) EventBridge RULE ARN
#                                                          patterns whose
#                                                          dead-letter writes may
#                                                          use the queue key. A
#                                                          pattern, not exact
#                                                          ARNs, because the rules
#                                                          are created by a module
#                                                          that consumes this key.
#
#   Each block below carries the full `type` and `description` that TFLint's
#   terraform_typed_variables and terraform_documented_variables rules require;
#   the summary above is a map of the surface, not a second copy of it.
#
#   Assumptions: the summary is COMPLETE -- every declared input appears above --
#   and is grouped by what an input does rather than by whether it is required.
#   Trade-offs: an abridged summary is shorter, but a reader consulting a partial
#   one concludes that whatever it omits is not configurable from a calling root,
#   which is the opposite of what a map of the surface is for. Grouping with a
#   count per group is what makes a future omission visible: a group whose count no
#   longer matches its entries is a one-line discrepancy rather than an invisible
#   absence.
#
# Return values:
#   None. A variables.tf declares no output, so the key identifiers, key ARNs
#   and alias names this module publishes to its caller are declared in
#   infra/modules/kms/outputs.tf.
#
# Errors / Exceptions:
#   TWENTY of the twenty-one inputs carry a `validation` block -- every one except
#   `tags`, whose keys and values are opaque to this module -- and each rejects a
#   bad value during `terraform plan`, before any request leaves the machine,
#   rather than letting the service reject it part-way through
#   `terraform apply`. The four scalars reject a value the service itself would
#   reject later: `environment` (not one of the two environments that have a
#   root), `name_prefix` (characters an alias name cannot hold),
#   `enable_key_rotation` (anything but `true`), and `deletion_window_in_days`
#   (outside the range the service accepts, or fractional). The sixteen list and
#   ARN inputs reject the shapes that would silently WIDEN a grant rather than
#   break it -- a wildcard, an assumed-role session ARN, a user, a root, a
#   service principal, or a duplicate entry -- which is the class of mistake a
#   plan-time failure is worth the most against, because the applied policy would
#   otherwise be valid and permissive.
#   `environment` and `cloudfront_distribution_arn` have no default, so omitting
#   either stops the run with a missing-required-argument error.
#   Assumptions: the twenty is a MEASUREMENT, reproducible with
#   `grep -c '^  validation {' infra/modules/kms/variables.tf`, and it covers the
#   list inputs as well as the scalars. An understated count would invite a
#   contributor to add an unvalidated list input believing that is the established
#   shape, so the figure is stated against the file rather than from memory.
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: one shared list of trusted principals rather than
#     four. Rejected -- it would grant every trusted principal use of every key,
#     erasing the per-domain boundary that provisioning a key per data class
#     instead of one exists to create.
#   - Assumptions: the four trust lists default to empty so the keys can exist
#     before the task roles that use them do. Those roles come from the
#     `ecs-service` module, which consumes this module's key ARNs, so a
#     non-empty requirement would make the grant a precondition of the key the
#     grant depends on.
#   - Assumptions: the calling root owns the baseline tag set through its
#     provider's `default_tags`, so `tags` here is a layer above that set rather
#     than the whole of it.
#   - Trade-offs: `environment` has no default at all, and the deletion window
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
#   provisioned stack. This tree is authored to a foundation state and checked to
#   the extent that state admits: HCL parse and `terraform fmt -check -recursive
#   infra/` pass today, and every variable carries a type and a description.
#   `terraform validate` and `terraform plan` are not runnable until the
#   composition files land and `terraform init` can populate a provider cache; the
#   recursive lint and the generated-documentation drift check likewise report
#   findings until each directory has its main.tf, outputs.tf and README.md. The
#   per-check state is tabulated in docs/architecture/service-catalog.md under its
#   deployment boundary. Applying any of it to a live account is an operator action
#   outside this scope.
# =============================================================================

# -----------------------------------------------------------------------------
# Naming and environment
# -----------------------------------------------------------------------------

# Trade-offs: this is the one input with no `default`. A defaulted environment
# name is the precise mechanism by which a key meant for one environment ends up
# carrying the other environment's alias -- the caller omits the argument, the
# module supplies a name regardless, and the mistake stays invisible because the
# plan is clean and the alias reads as deliberate. Requiring the value costs
# each root one line and converts that failure into a missing-required-argument
# error before any key is created.
# Assumptions: the accepted values are exactly two. Two environment roots exist,
# infra/envs/dev and infra/envs/prod, and this module is called only from those
# two. A third value would mint aliases for an environment whose state no root
# tracks, so the check asserts the shape of the tree rather than a naming
# preference.
variable "environment" {
  description = "Environment name interpolated into all four KMS alias names, so one environment's keys are distinguishable from the other's in the console and in any alias-based key reference; must be `dev` or `prod`, the two environments that have a Terraform root under infra/envs/."
  type        = string

  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment must be exactly \"dev\" or \"prod\", matching the environment root under infra/envs/ that calls this module."
  }
}

# Trade-offs: the characters are checked here rather than trusted. This prefix
# is concatenated into each alias name, and an alias name accepts only
# alphanumerics, hyphens, underscores and forward slashes. A prefix carrying a
# space, a dot or an uppercase letter is caught by neither the type system nor
# `terraform plan`; it is caught by the service during `terraform apply`, after
# the run has begun creating keys. Validating locally moves that failure to plan
# time, and the cost paid is that a legitimately unusual prefix has to be
# spelled out in this file before it can be used. The 32-character ceiling is
# the same trade -- it leaves the finished alias well inside the length the
# service accepts once the per-key purpose and the environment name are
# appended.
# Assumptions: the name and the default match infra/bootstrap/variables.tf
# instead of being chosen here. Every directory in this tree takes its prefix
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

# Assumptions: this is an input at all when the answer is already settled.
# Rotation-on is the module's design intent, not a caller preference. The target
# architecture specifies customer-managed keys WITH rotation on every one, so
# `true` is the only value either environment root is expected to pass. The
# variable exists so that the setting is legible at the call site and in this
# module's generated documentation -- an operator can see it and reason about it
# -- and not so that it can be turned off quietly. The validation below now
# enforces the invariant at plan time, while the policy scan independently
# verifies the rendered resources rather than serving as the only control.
# Alternatives Considered: no companion rotation-interval input. Exposing the
# interval as well was considered and rejected. Enabling rotation applies the
# service's own interval, nothing in the target architecture asks for a
# different one, and an input that is never varied is one more knob a reader
# must evaluate before discovering it does not matter.
variable "enable_key_rotation" {
  description = "Whether all four customer-managed keys rotate their key material automatically on the service's own interval. The module accepts only true because rotation is an architecture invariant rather than an environment preference."
  type        = bool
  default     = true

  # Assumptions: retaining the input keeps the generated module contract
  # explicit while refusing the fail-open value at plan time. Removing the
  # input entirely would also be safe, but it would hide the invariant from
  # callers and from the terraform-docs table that reviewers inspect.
  validation {
    condition     = var.enable_key_rotation
    error_message = "enable_key_rotation must be true. All four customer-managed keys rotate in every environment, and callers cannot lower that invariant."
  }
}

# -----------------------------------------------------------------------------
# Teardown and recovery window
# -----------------------------------------------------------------------------

# Assumptions: the range is asserted locally. The service accepts a window of 7
# to 30 days and nothing outside it, and the `number` type additionally admits a
# fractional value the service does not take. Neither is visible to `terraform
# plan` without this block, so both surface as a service rejection during
# `terraform apply`, part-way through a run that has already created other
# resources. Checking here converts an apply-time rejection into a plan-time
# error.
# Trade-offs: the default leans to the floor of that range. The two ends of the
# range buy different things. A short window lets `terraform destroy` release
# the keys sooner, which is what the acceptance criterion of a stack that tears
# down cleanly asks for, and it stops a torn-down environment from leaving four
# keys behind in a pending-deletion state that still bills. A long window
# preserves a longer interval in which a key deleted by mistake can be recovered
# -- past its window a key is gone and every ciphertext under it is permanently
# unreadable. The module default takes the short end because the case a module
# default serves is the one exercised most, provisioning and tearing down a
# non-production environment, and because the recovery interval is a production
# concern that infra/envs/prod/terraform.tfvars is expected to state outright
# rather than inherit.
# Assumptions: setting it differently per environment does not fork the
# topology. The two environment roots are required to be identical in shape and
# to differ only in sizing and retention values. This is a retention value, so
# infra/envs/dev/terraform.tfvars and infra/envs/prod/terraform.tfvars may
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

# Assumptions: an empty default here reads as complete rather than as an
# oversight. The baseline tag set is not this module's to supply. Each calling
# root configures `default_tags` on its own `provider "aws"` block, and the
# provider merges that map into every taggable resource it creates, so the four
# keys carry the root's common tags whether or not this variable is passed. What
# this input adds is the layer above that -- tags that distinguish these keys
# from the rest of the root's resources. Without this note an empty default
# looks like missing tagging, and it is not.
variable "tags" {
  description = "Key-specific tags merged onto each of the four keys, layered on top of the common tag set the calling root already applies through its provider's `default_tags`; defaults to none, because the baseline tags arrive from the root rather than from this module."
  type        = map(string)
  default     = {}
}

# -----------------------------------------------------------------------------
# CloudFront origin access -- narrowing the S3 key's service grant
# -----------------------------------------------------------------------------

variable "s3_cloudfront_distribution_arns" {
  description = "ADDITIONAL exact CloudFront distribution ARNs admitted by the S3 key's `cloudfront.amazonaws.com` decrypt grant, beyond the mandatory `cloudfront_distribution_arn`. Empty -- the default -- confines the grant to that one distribution. Wildcards are refused: the grant's condition is `ArnEquals`, which performs no wildcard expansion, so a pattern entry would match nothing and every asset request under it would answer 403."
  type        = list(string)
  default     = []
  nullable    = false

  # WHY : Assumptions: the single-page bundle is served from a private bucket
  #       encrypted with the S3 key and read by CloudFront through an origin
  #       access control, so CloudFront -- not a task role -- is the principal
  #       that decrypts those objects. Without a service-principal grant to it
  #       every asset request answers 403 while the bucket, the distribution, the
  #       origin access control and the key each look correct in isolation, which
  #       is why the grant in main.tf is UNCONDITIONAL.
  #
  # WHY : Assumptions: a grant to a service principal is otherwise usable by
  #       that service on behalf of ANY account it serves, so an unconditioned
  #       CloudFront grant would let a stranger's distribution decrypt this
  #       bucket's objects -- the confused-deputy problem. main.tf therefore
  #       always applies two conditions: the request must be made on behalf of
  #       THIS account, and its source ARN must EQUAL one of the distribution ARNs
  #       composed from this input, `cloudfront_distribution_arns` and the
  #       mandatory `cloudfront_distribution_arn`.
  #
  # WHY : Refactoring Rationale: this input previously described itself as
  #       "empty narrows the grant to every distribution in THIS account and
  #       partition", and the comment beside it explained an account-wide
  #       `arn:<partition>:cloudfront::<account>:distribution/*` fallback and
  #       rejected an exact-ARN requirement as "a module-level dependency cycle
  #       that Terraform refuses to graph". All three claims were false of this
  #       module. `cloudfront_distribution_arn` IS a required exact-ARN input, so
  #       the composed list is never empty and the fallback statement could never
  #       render -- it has been deleted. And the cycle does not exist: both
  #       environment roots pass `module.cloudfront_spa.distribution_arn` into this
  #       module and both graph cleanly, because the distribution depends on the
  #       KEY while this list feeds the key POLICY, which is a separate resource.
  #       The claims mattered because together they told a reader the grant was
  #       account-wide by default and could not be tightened, when in fact it is
  #       already confined to one exact distribution and this list only widens it.
  #
  # WHY : Trade-offs: no `kms:ViaService` condition accompanies the two above,
  #       unlike the queue key's scheduler grant. CloudFront calls KMS itself when
  #       it retrieves an encrypted object, so a condition asserting the request
  #       arrived through the storage service would match nothing and would deny
  #       the very path the grant exists to permit.
  #
  # WHY : Assumptions: a CloudFront ARN carries no region -- the service is global,
  #       so the region segment is empty -- and the identifier segment admits only
  #       upper-case letters and digits, with NO wildcard.
  #       Refactoring Rationale: the identifier character class was `[A-Z0-9*]+`
  #       and is now `[A-Z0-9]+`. The old class admitted a trailing `*` and the
  #       error message advertised it as "a prefix rather than one distribution",
  #       but the consuming condition is `ArnEquals`, which compares ARNs without
  #       expanding wildcards -- so a pattern entry silently admitted nothing and
  #       produced a 403 with nothing in the plan to explain it. Refusing the value
  #       at plan time is the only place that failure is diagnosable. A caller who
  #       genuinely wants prefix semantics needs an `ArnLike` condition, which this
  #       module does not offer for this grant; adding one would widen a grant on
  #       cardholder-facing objects and is not a change a validation relaxation
  #       should be able to smuggle in.
  #       Assumptions: duplicates are refused too, matching the sibling
  #       `cloudfront_distribution_arns`, because a duplicate entry reaches the
  #       policy document as a repeated condition value -- accepted by the API and
  #       silently meaningless, so it reads as narrowing that is not there.
  validation {
    condition = length(distinct(var.s3_cloudfront_distribution_arns)) == length(var.s3_cloudfront_distribution_arns) && alltrue([
      for arn in var.s3_cloudfront_distribution_arns :
      can(regex("^arn:[a-z0-9-]+:cloudfront::[0-9]{12}:distribution/[A-Z0-9]+$", arn))
    ])
    error_message = "s3_cloudfront_distribution_arns must contain unique, exact CloudFront distribution ARNs of the form arn:<partition>:cloudfront::<account-id>:distribution/<id>. CloudFront is a global service, so the region segment is empty; wildcard distribution identifiers are refused because the consuming condition is ArnEquals and would match nothing."
  }
}

# -----------------------------------------------------------------------------
# Trusted key users -- one list per key
# -----------------------------------------------------------------------------
#
# The four inputs of this shape are the least-privilege half of each key policy:
# the policy main.tf composes for a key grants cryptographic use of that key to
# the principals its own list names, and to no others.
#
# Assumptions: there are FIVE lists here and FOUR keys in main.tf, because the
# lists are one per PURPOSE rather than one per key. The frozen design fixes four
# customer-managed keys -- Aurora, S3, Secrets Manager and SQS -- and the fifth
# purpose, the field-level envelopes the application produces for itself, is a
# second statement on the AURORA key rather than a key of its own, because the
# columns it protects are Aurora column data.
# `application_envelope_user_role_arns` therefore names principals trusted on the
# Aurora key under an encryption-context condition, while
# `aurora_key_user_role_arns` names principals trusted on the same key under a
# kms:ViaService condition. Keeping them as two lists is what keeps those two
# grants independently auditable: collapsing them would let a principal trusted
# for one path silently acquire the other.
#
# The three notes in this
# section are shared, and they govern all five declarations of that shape --
# `aurora_key_user_role_arns`, `s3_key_user_role_arns`,
# `secrets_key_user_role_arns`, `application_envelope_user_role_arns` and
# `sqs_key_user_role_arns` -- because the reasoning is identical across them and
# repeating it in each of them would let the copies drift apart.
# Assumptions: the note locates the declarations it governs by NAME rather than by
# position. A positional form ("the last four in this file") reads more briefly but
# stops being true the moment another input is declared among them, and the
# encryption-context and application-data inputs are declared among them.
#
# WHY five lists rather than one -- Alternatives Considered: a single
# `key_user_role_arns` applied to all four policies is the obvious
# simplification, and it is rejected. It would grant every trusted principal use
# of every key, so a service trusted only to read queue payloads could also
# decrypt database ciphertext -- which erases the per-domain boundary that
# provisioning a key per data class instead of one exists to create in the first
# place. Keeping the lists separate confines a trust entry added to the wrong
# list to a single data class instead of letting it reach all four.
# Assumptions: each defaults to empty. The principals these lists name are the
# ECS task roles, and those roles are created by the `ecs-service` module --
# which itself consumes this module's key ARNs. Requiring a non-empty list would
# therefore make the grant a precondition of the key that the grant depends on.
# An empty default separates the two concerns: the keys and their aliases are
# created, each policy still reserves administrative control to the account
# root, and the caller supplies the use grants from the roles once those roles
# exist. An empty default that looks like an oversight is worse than none, so it
# is recorded here as deliberate.
# Alternatives Considered: no default may ever carry an example value. Seeding
# one of these defaults with a specimen ARN, so that a reader can see the shape
# the input expects, is the alternative and it is refused. An ARN embeds an AWS
# account identifier, and no account identifier belongs in this repository --
# the project's no-secrets-in-source constraint admits no exception. These four
# lists are inputs the caller supplies from its own state, while the key ARNs
# this module produces travel the other way, as outputs. Trade-offs: the
# expected shape is therefore conveyed in prose, by the `type` and the
# description, instead of by a specimen value. The empty defaults are
# load-bearing rather than placeholders awaiting one.

variable "aurora_key_user_role_arns" {
  description = "Exact IAM role ARNs the Aurora key policy permits to use the key through Amazon RDS for the named Aurora encryption contexts. Wildcards, assumed-role session ARNs, users, roots and service principals are refused."
  type        = list(string)
  default     = []

  validation {
    condition = length(distinct(var.aurora_key_user_role_arns)) == length(var.aurora_key_user_role_arns) && alltrue([
      for arn in var.aurora_key_user_role_arns :
      can(regex("^arn:(aws|aws-us-gov|aws-cn):iam::[0-9]{12}:role/[A-Za-z0-9+=,.@_-]+(/[A-Za-z0-9+=,.@_-]+)*$", arn))
    ])
    error_message = "aurora_key_user_role_arns must contain unique, exact IAM role ARNs only. Wildcards and principals other than roles are not accepted; same-account enforcement is applied by the key-policy resource."
  }
}

variable "s3_key_user_role_arns" {
  description = "Exact IAM role ARNs the S3 key policy permits to use the key through Amazon S3 for the named bucket encryption contexts. Wildcards, assumed-role session ARNs, users, roots and service principals are refused."
  type        = list(string)
  default     = []

  validation {
    condition = length(distinct(var.s3_key_user_role_arns)) == length(var.s3_key_user_role_arns) && alltrue([
      for arn in var.s3_key_user_role_arns :
      can(regex("^arn:(aws|aws-us-gov|aws-cn):iam::[0-9]{12}:role/[A-Za-z0-9+=,.@_-]+(/[A-Za-z0-9+=,.@_-]+)*$", arn))
    ])
    error_message = "s3_key_user_role_arns must contain unique, exact IAM role ARNs only. Wildcards and principals other than roles are not accepted; same-account enforcement is applied by the key-policy resource."
  }
}

variable "cloudfront_distribution_arn" {
  description = "ARN of the CloudFront distribution allowed to decrypt the SPA origin under the S3 data-domain key. Required so the service-principal grant is always scoped to the exact distribution rather than omitted or widened."
  type        = string
  nullable    = false

  # WHY : Refactoring Rationale: OAC authorizes the S3 GetObject request but
  #       does not authorize KMS decryption. Accepting the exact distribution
  #       ARN prevents a broad CloudFront service-principal grant covering every
  #       distribution in the account.
  validation {
    condition     = can(regex("^arn:[a-z0-9-]+:cloudfront::[0-9]{12}:distribution/[A-Z0-9]+$", var.cloudfront_distribution_arn))
    error_message = "cloudfront_distribution_arn must be a CloudFront distribution ARN of the form arn:<partition>:cloudfront::<account-id>:distribution/<id>."
  }
}

variable "secrets_key_user_role_arns" {
  description = "Exact IAM role ARNs the Secrets Manager key policy permits to use the key through Secrets Manager for the named secret encryption contexts. Wildcards, assumed-role session ARNs, users, roots and service principals are refused."
  type        = list(string)
  default     = []

  validation {
    condition = length(distinct(var.secrets_key_user_role_arns)) == length(var.secrets_key_user_role_arns) && alltrue([
      for arn in var.secrets_key_user_role_arns :
      can(regex("^arn:(aws|aws-us-gov|aws-cn):iam::[0-9]{12}:role/[A-Za-z0-9+=,.@_-]+(/[A-Za-z0-9+=,.@_-]+)*$", arn))
    ])
    error_message = "secrets_key_user_role_arns must contain unique, exact IAM role ARNs only. Wildcards and principals other than roles are not accepted; same-account enforcement is applied by the key-policy resource."
  }
}

variable "application_envelope_user_role_arns" {
  description = "Exact IAM role ARNs the AURORA key policy permits to generate envelope data keys and decrypt them for values the application enciphers itself, confined to the encryption-context purposes declared below. This input is named for the caller rather than for a key because there is no separate application key: the key model is four keys, one per data-at-rest domain, and every value these envelopes protect is an Aurora column. Unlike aurora_key_user_role_arns the resulting statement carries no kms:ViaService condition, because a workload calls KMS directly rather than through RDS. Wildcards, assumed-role session ARNs, users, roots and service principals are refused."
  type        = list(string)
  default     = []

  validation {
    condition = length(distinct(var.application_envelope_user_role_arns)) == length(var.application_envelope_user_role_arns) && alltrue([
      for arn in var.application_envelope_user_role_arns :
      can(regex("^arn:(aws|aws-us-gov|aws-cn):iam::[0-9]{12}:role/[A-Za-z0-9+=,.@_-]+(/[A-Za-z0-9+=,.@_-]+)*$", arn))
    ])
    error_message = "application_envelope_user_role_arns must contain unique, exact IAM role ARNs only. Wildcards and principals other than roles are not accepted; same-account enforcement is applied by the key-policy resource."
  }
}

variable "application_envelope_context_purposes" {
  description = "The kms:EncryptionContext:carddemo:purpose values the Aurora key's application-envelope grant admits. This is the narrowing condition a directly-called grant has in place of kms:ViaService, so a role holding it can work only with ciphertext produced for one of these purposes. The default names the two purposes the migration enciphers: card-cvv, the card verification value produced by com.carddemo.card.service.CardVerificationValueCipher, and customer-identifier, the national and government-issued identifiers produced by com.carddemo.account.service.CustomerIdentifierCipher."
  type        = list(string)

  # WHY : Assumptions: the default is an exact inventory of the purposes enciphered
  #       under this grant, and BOTH have to be named. card-service's
  #       CardVerificationValueCipher enciphers under card-cvv, and
  #       account-service's CustomerIdentifierCipher protects CUST-SSN
  #       (app/cpy/CVCUS01Y.cpy L17) and CUST-GOVT-ISSUED-ID (L18) under
  #       customer-identifier -- so a list carrying only card-cvv would deny every
  #       account write the moment application_envelope_user_role_arns is wired,
  #       and the denial would surface at run time rather than at plan time.
  # WHY : Trade-offs: two purposes under ONE grant rather than a key each. The two
  #       workloads are granted separately from the identity side -- the card task
  #       role's condition names card-cvv and the account task role's names
  #       customer-identifier -- so neither role can reach the other's ciphertext
  #       even though both grants resolve to the same key material. A fifth key would
  #       buy separation of the key material as well, at the cost of another key,
  #       another alias, another rotation schedule and another monthly charge, for
  #       two values written by two roles that are already condition-separated.
  default = ["card-cvv", "customer-identifier"]

  validation {
    condition = length(var.application_envelope_context_purposes) > 0 && length(distinct(var.application_envelope_context_purposes)) == length(var.application_envelope_context_purposes) && alltrue([
      for purpose in var.application_envelope_context_purposes :
      can(regex("^[a-z0-9]+(-[a-z0-9]+)*$", purpose))
    ])
    error_message = "application_envelope_context_purposes must be a non-empty list of unique lower-case hyphenated purpose names. An empty list would produce a condition matching nothing, which denies every request the grant exists for."
  }
}

variable "sqs_key_user_role_arns" {
  description = "Exact IAM role ARNs the SQS key policy permits to use the key through Amazon SQS. Wildcards, assumed-role session ARNs, users, roots and service principals are refused."
  type        = list(string)
  default     = []

  validation {
    condition = length(distinct(var.sqs_key_user_role_arns)) == length(var.sqs_key_user_role_arns) && alltrue([
      for arn in var.sqs_key_user_role_arns :
      can(regex("^arn:(aws|aws-us-gov|aws-cn):iam::[0-9]{12}:role/[A-Za-z0-9+=,.@_-]+(/[A-Za-z0-9+=,.@_-]+)*$", arn))
    ])
    error_message = "sqs_key_user_role_arns must contain unique, exact IAM role ARNs only. Wildcards and principals other than roles are not accepted; same-account enforcement is applied by the key-policy resource."
  }
}

# The context inputs below are feedback edges from resources encrypted by these
# keys. Refactoring Rationale: key creation is intentionally separate from
# policy application in main.tf, so the key ARN can create an Aurora cluster,
# bucket or secret first and the resulting exact resource identity can then
# narrow the final key policy without a dependency cycle.
# WHY : Refactoring Rationale: this input exists because the SQS key policy granted
#       the SCHEDULER service principal and nothing else, while two EventBridge RULES
#       -- the batch bracket finalizer and its reconciler, declared in
#       infra/modules/step-functions-batch -- deliver to a dead-letter queue encrypted
#       under this key. EventBridge Rules call KMS as `events.amazonaws.com`, which the
#       scheduler grant does not cover, so every bracket-release event EventBridge
#       could not deliver was itself undeliverable: the queue that exists to catch a
#       lost release rejected the write with a KMS access denial, leaving the online
#       read-only flag set with no record of why. Rules and schedules are different
#       principals even though both are EventBridge, which is exactly the kind of
#       distinction a key policy has to state rather than imply.
#
# WHY : Assumptions: the value is an ARN PATTERN rather than the exact rule ARNs, and
#       the reason is a module cycle rather than convenience. The rules live in the
#       step-functions-batch module, which consumes this key's ARN; feeding their ARNs
#       back here would make each module depend on the other and no root could resolve
#       it. A pattern built from the naming convention -- `rule/<prefix>-<environment>-*`
#       -- is resolvable at plan time from values the root already holds, and it still
#       binds the grant to this deployment's own rules rather than to every rule in the
#       account.
#       Alternatives Considered: (a) leaving the queue on SQS-managed encryption so no
#       key grant were needed. Rejected because the queue carries which night's release
#       was lost and for which account, and AAP section 0.4.1.9 puts every queue in this
#       deployment under a customer-managed key; a single exception would be the one
#       store whose retention and access an operator could not reason about with the
#       others. (b) Granting `events.amazonaws.com` with no SourceArn condition, which
#       is what most published examples show. Rejected: a service-principal grant with
#       only an account condition is usable by that service on behalf of any rule in the
#       account, so a rule created for an unrelated purpose could encrypt under this
#       key.
#
# WHY : Trade-offs: a pattern cannot assert that the two rules exist, only that nothing
#       outside the naming convention is covered. Accepted because the alternative is
#       the cycle above, and because the pattern is narrowed on three axes at once --
#       the calling account, the source rule ARN and `kms:ViaService` for this region's
#       queue service -- so the grant authorises the enqueue path it was added for and
#       nothing else.
variable "sqs_key_eventbridge_rule_source_arn_patterns" {
  description = "Same-account EventBridge RULE ARN patterns whose dead-letter deliveries may use the SQS key. Each entry becomes an ArnLike condition on an events.amazonaws.com grant that also requires the calling account and kms:ViaService for this region's queue service. A pattern rather than an exact ARN because the rules are created by a module that consumes this key, so exact ARNs would close a dependency cycle. Empty installs no EventBridge service-principal grant."
  type        = list(string)
  default     = []

  validation {
    # WHY : Assumptions: the pattern must be an EventBridge rule ARN in this partition
    #       family with at most a trailing-name wildcard. Admitting a bare `*` for the
    #       rule name is deliberate and admitting one for the ACCOUNT is not: the whole
    #       point of the condition is to keep the grant inside this deployment, and a
    #       wildcard account would silently return the grant to the state this input was
    #       added to fix.
    condition = length(distinct(var.sqs_key_eventbridge_rule_source_arn_patterns)) == length(var.sqs_key_eventbridge_rule_source_arn_patterns) && alltrue([
      for pattern in var.sqs_key_eventbridge_rule_source_arn_patterns :
      can(regex("^arn:(aws|aws-us-gov|aws-cn):events:[a-z0-9-]+:[0-9]{12}:rule/[A-Za-z0-9._-]+\\*?$", pattern))
    ])
    error_message = "sqs_key_eventbridge_rule_source_arn_patterns must contain unique EventBridge rule ARNs for an explicit region and twelve-digit account, optionally ending in a single * to cover a name prefix. A wildcard region or account is refused because the condition exists to confine the grant to this deployment."
  }
}

variable "aurora_encryption_context_ids" {
  description = "Aurora cluster resource identifiers accepted in the `aws:rds:db-id` KMS encryption context. A non-empty Aurora role trust list requires at least one exact identifier."
  type        = list(string)
  default     = []

  validation {
    condition = length(distinct(var.aurora_encryption_context_ids)) == length(var.aurora_encryption_context_ids) && alltrue([
      for id in var.aurora_encryption_context_ids :
      can(regex("^cluster-[A-Za-z0-9-]+$", id))
    ])
    error_message = "aurora_encryption_context_ids must contain unique Aurora cluster resource identifiers beginning with `cluster-`; wildcards and blank identifiers are not accepted."
  }
}

variable "s3_encryption_context_bucket_arns" {
  description = "Exact S3 bucket ARNs accepted by the S3 key policy. The policy derives both bucket and object encryption-context forms so S3 Bucket Keys and direct object keys remain scoped to these buckets."
  type        = list(string)
  default     = []

  validation {
    condition = length(distinct(var.s3_encryption_context_bucket_arns)) == length(var.s3_encryption_context_bucket_arns) && alltrue([
      for arn in var.s3_encryption_context_bucket_arns :
      can(regex("^arn:(aws|aws-us-gov|aws-cn):s3:::[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", arn))
    ])
    error_message = "s3_encryption_context_bucket_arns must contain unique, exact S3 bucket ARNs with no object suffix or wildcard."
  }
}

variable "secrets_encryption_context_arns" {
  description = "Exact Secrets Manager secret ARNs accepted in the `SecretARN` KMS encryption context. A non-empty Secrets Manager role trust list requires at least one exact secret ARN."
  type        = list(string)
  default     = []

  validation {
    condition = length(distinct(var.secrets_encryption_context_arns)) == length(var.secrets_encryption_context_arns) && alltrue([
      for arn in var.secrets_encryption_context_arns :
      can(regex("^arn:(aws|aws-us-gov|aws-cn):secretsmanager:[a-z0-9-]+:[0-9]{12}:secret:[A-Za-z0-9/_+=.@-]+-[A-Za-z0-9]{6}$", arn))
    ])
    error_message = "secrets_encryption_context_arns must contain unique, exact Secrets Manager secret ARNs including the service-generated six-character suffix; wildcards are not accepted."
  }
}

variable "cloudfront_distribution_arns" {
  description = "Exact same-account CloudFront distribution ARNs allowed to decrypt the SSE-KMS SPA origin through an origin access control. Empty means no CloudFront service-principal grant is installed."
  type        = list(string)
  default     = []

  validation {
    condition = length(distinct(var.cloudfront_distribution_arns)) == length(var.cloudfront_distribution_arns) && alltrue([
      for arn in var.cloudfront_distribution_arns :
      can(regex("^arn:(aws|aws-us-gov|aws-cn):cloudfront::[0-9]{12}:distribution/[A-Z0-9]+$", arn))
    ])
    error_message = "cloudfront_distribution_arns must contain unique, exact CloudFront distribution ARNs; wildcard distribution identifiers are not accepted."
  }
}

variable "cloudwatch_log_delivery_source_arns" {
  description = "Exact same-account CloudWatch Logs delivery-source ARNs allowed to generate data keys under this key for a CloudFront standard logging v2 destination. Scoping rather than exercise: the destination cloudfront-spa creates defaults to SSE-S3 because CloudFront delivery cannot write to an SSE-KMS bucket, so the grant encrypts no delivered log object under this configuration and exists so the policy stays narrow and a key-encrypted destination needs no policy change. Empty means no log-delivery service-principal grant is installed."
  type        = list(string)
  default     = []

  validation {
    condition = length(distinct(var.cloudwatch_log_delivery_source_arns)) == length(var.cloudwatch_log_delivery_source_arns) && alltrue([
      for arn in var.cloudwatch_log_delivery_source_arns :
      can(regex("^arn:(aws|aws-us-gov|aws-cn):logs:us-east-1:[0-9]{12}:delivery-source:[A-Za-z0-9._-]+$", arn))
    ])
    error_message = "cloudwatch_log_delivery_source_arns must contain unique, exact CloudWatch Logs delivery-source ARNs in us-east-1; wildcards are not accepted."
  }
}

variable "cloudwatch_log_group_arns" {
  description = "Exact same-account CloudWatch log-group ARNs the regional Logs service may encrypt with the S3/data key. Empty installs no CloudWatch Logs service-principal grant."
  type        = list(string)
  default     = []

  validation {
    condition = length(distinct(var.cloudwatch_log_group_arns)) == length(var.cloudwatch_log_group_arns) && alltrue([
      for arn in var.cloudwatch_log_group_arns :
      can(regex("^arn:(aws|aws-us-gov|aws-cn):logs:[a-z0-9-]+:[0-9]{12}:log-group:[A-Za-z0-9_./#-]+$", arn))
    ])
    error_message = "cloudwatch_log_group_arns must contain unique, exact log-group ARNs without a trailing :* wildcard."
  }
}

variable "sns_topic_arns" {
  description = "Exact same-account SNS topic ARNs that CloudWatch alarms and SNS may encrypt with the S3/data key. Empty installs no alert-topic service-principal grant."
  type        = list(string)
  default     = []

  validation {
    condition = length(distinct(var.sns_topic_arns)) == length(var.sns_topic_arns) && alltrue([
      for arn in var.sns_topic_arns :
      can(regex("^arn:(aws|aws-us-gov|aws-cn):sns:[a-z0-9-]+:[0-9]{12}:[A-Za-z0-9_-]+$", arn))
    ])
    error_message = "sns_topic_arns must contain unique, exact SNS topic ARNs with no wildcard."
  }
}
