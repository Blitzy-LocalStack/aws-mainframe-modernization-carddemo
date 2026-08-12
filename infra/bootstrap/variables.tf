# =============================================================================
# infra/bootstrap/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Declares the complete input surface of the infra/bootstrap root -- the
#   out-of-band step that provisions the remote-state backend (a versioned,
#   encrypted S3 bucket for Terraform state plus a DynamoDB lock table) that
#   every other Terraform root in this repository consumes. That root is
#   applied once per AWS account, ahead of both environment roots under
#   infra/envs/, and torn down after them.
#
#   Every input below is NON-SECRET, and every one either carries a working
#   default or defaults to an explicit `null` override. That is a deliberate
#   property rather than an accident of authoring: it is what lets
#   `terraform init -backend=false`, `terraform validate` and `terraform plan`
#   run against this root with no variable file supplied and no AWS credentials
#   present, which is exactly how the gating validate and lint steps in
#   .github/workflows/infra-ci.yml check it.
#
# Parameters -- the nine inputs, with the type and the consumer of each:
#   aws_region                 string. Region the backend is created in. Read
#                              by `provider "aws"` in versions.tf, composed
#                              into the default bucket name in main.tf, and
#                              echoed by outputs.tf.
#   name_prefix                string. Leading component of both composed
#                              resource names, in main.tf's `locals` block.
#   state_bucket_name          string, nullable. Operator override for the
#                              composed bucket name; resolved by `coalesce`
#                              in main.tf.
#   lock_table_name            string, nullable. The same override for the
#                              lock table; resolved by the matching
#                              `coalesce` in main.tf.
#   state_version_retention_days number. Minimum age for versions beyond the
#                              retained recent-version count.
#   state_noncurrent_versions_to_retain number. Recent versions retained.
#   audit_log_retention_days number. Object-access audit-log horizon.
#   state_bucket_force_destroy bool. Whether `terraform destroy` may delete a
#                              bucket that still holds objects and versions.
#                              Read by `force_destroy` in main.tf.
#   tags                       map(string). Common tag set applied through the
#                              `default_tags` block in versions.tf.
#
# Return values:
#   None. A variables file declares none. This root's outputs -- the resolved
#   bucket name, the resolved table name and the region, which an operator
#   transcribes into each environment root's backend configuration -- are
#   declared in infra/bootstrap/outputs.tf.
#
# Errors / Exceptions:
#   The `error_message` on each `validation` block below is this file's whole
#   error surface, and each one names the shape that IS accepted so an operator
#   can correct the input rather than only learning that it was refused. All of
#   them are raised during `terraform plan`, before any AWS API call, so a
#   malformed input costs nothing but a re-run. The two nullable overrides --
#   state_bucket_name and lock_table_name, the only inputs here declared with
#   `default = null` --
#   validate only when a value is actually supplied: their conditions
#   short-circuit on null, because `regex` applied to null raises a type error
#   instead of returning false and would therefore fail every invocation that
#   relies on the default -- including the credential-free CI validate above.
#
# WHY (non-obvious design decisions):
#   - Assumptions: there is deliberately NO `environment` input here, and a
#     reader arriving from infra/envs/dev or infra/envs/prod -- both of which
#     ARE parameterized by environment -- should expect one and not find it.
#     This root provisions ONE state backend per AWS ACCOUNT, shared by every
#     environment whose state lives in that account. An `environment` input
#     would advertise a per-environment backend that does not exist, and would
#     invite two bootstrap applies in one account contending for the same
#     composed bucket name. For the same reason no sizing, retention or
#     capacity input appears here: those belong to the environment roots, which
#     are the only directories in this tree carrying a terraform.tfvars.
#   - Trade-offs: every input is defaulted rather than required, so this root
#     applies with no variable file at all. The cost is that a defaulted region
#     or prefix is a decision made by silence; outputs.tf reports both back,
#     which is what keeps such a choice visible rather than buried.
#   - Assumptions: no default holds an AWS account identifier, an ARN, a bucket
#     name or a table name. Those identify one specific account's
#     infrastructure, so committing one would publish that account's naming to
#     every reader of this repository and pin the root to it; they are supplied
#     at apply time or derived at plan time instead.
# =============================================================================

# WHY this input has a default at all, when the region a deployment lands in is
# normally something an operator must state explicitly -- Trade-offs: the infra CI
# workflow checks this root with `terraform -chdir=infra/bootstrap init
# -backend=false` followed by `terraform validate`, supplying no variable file
# and holding no AWS credentials. An input with no default makes that step
# prompt for a value and then fail in a non-interactive shell, so this would be
# the one directory in the tree CI could not check. A region identifier is
# configuration and not a credential -- it names a public AWS location and
# confers no access -- so publishing one as a default discloses nothing. The
# accepted cost is that an operator who never sets it provisions the backend in
# us-east-1; outputs.tf reports the region back, which is what stops that
# outcome from being invisible.
variable "aws_region" {
  description = "AWS region the state bucket and lock table are created in. Read by the aws provider in versions.tf, composed into the default bucket name in main.tf, and echoed by outputs.tf so it can be transcribed into each environment root's backend configuration. Accepts a standard region identifier, for example us-east-1 or ap-southeast-4."
  type        = string
  default     = "us-east-1"

  # WHY a shape check on a value the provider would reject anyway -- Trade-offs: a
  # malformed region fails TWICE over, and neither failure names this input. The
  # provider reports it as an endpoint-resolution failure naming a hostname, and
  # the same string is a component of the composed bucket name in main.tf, where
  # an illegal character surfaces as an S3 bucket-naming violation attributed to
  # the bucket resource. Validating here attributes the fault to the input that
  # caused it, at plan time, before either API call is attempted.
  # Alternatives Considered: an allow-list of the regions that currently exist.
  # Rejected -- it would refuse every region AWS adds after this file is
  # written, and falsely refusing a valid region is a worse outcome than
  # accepting a well-formed identifier that happens to resolve to nothing. The
  # shape is two lowercase letters, one or more hyphenated words, then a
  # trailing ordinal, which admits us-east-1, ap-southeast-4 and the three-part
  # us-gov-east-1. The ordinal is `[0-9]+` rather than a single digit for the
  # same reason the allow-list was rejected: a two-digit ordinal would otherwise
  # make this check the reason a real region was refused.
  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]+$", var.aws_region))
    error_message = "aws_region must be a lowercase AWS region identifier shaped <two letters>-<word>-<ordinal>, for example us-east-1, eu-west-3, ap-southeast-4 or us-gov-east-1."
  }
}

# WHY the bound is 3 to 20 characters rather than an unexplained "keep it short"
# Assumptions: main.tf composes the default bucket name as
# <prefix>-tfstate-<12-digit-account-id>-<region>. The fixed middle segment is
# nine characters (`-tfstate-`), an AWS account identifier is always twelve
# digits, one further hyphen separates it from the region, and the longest
# region identifier in use is fourteen characters (ap-southeast-4). A
# twenty-character prefix therefore yields 20 + 9 + 12 + 1 + 14 = 56 characters,
# which sits inside S3's 63-character bucket-name limit with seven characters of
# headroom for a region identifier longer than any that exists today. Twenty-one
# would spend that headroom, and the resulting failure would present as a
# rejected bucket name during apply rather than as a prefix that was too long.
# The lower bound of three is S3's own minimum bucket-name length, which the
# composed name inherits.
variable "name_prefix" {
  description = "Leading component of the composed names for both halves of the backend. Read by the locals block in main.tf, which appends the fixed -tfstate- segment, the caller's account identifier and aws_region to build the bucket name, and derives the lock table name from the same prefix."
  type        = string
  default     = "carddemo"

  # WHY the charset is narrower than Terraform itself would accept -- Assumptions:
  # this value becomes the leading component of an S3 bucket name, and S3 bucket
  # names must be lowercase and may not contain an underscore -- so `CardDemo`
  # and `card_demo` are both refused by the S3 API rather than by Terraform,
  # which is a failure two steps removed from its cause. Requiring a leading
  # letter additionally keeps the composed name clear of S3's restriction on
  # names shaped like an IP address. A DynamoDB table name is far more
  # permissive, but it is composed from this same prefix, so the stricter of the
  # two services sets the rule for both.
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,19}$", var.name_prefix))
    error_message = "name_prefix must be 3 to 20 characters, start with a lowercase letter, and contain only lowercase letters, digits and hyphens -- it becomes the leading component of an S3 bucket name, which forbids uppercase letters and underscores."
  }
}

# WHY an override that defaults to null rather than a required name
# Alternatives Considered: a required input was rejected on two counts. It
# would force every invocation to state a bucket name even when the composed
# default is exactly what is wanted, and it would break the credential-free CI
# validate described in the header. `default = null` is not decorative here, it
# is the mechanism: main.tf resolves the effective name with
# `coalesce(var.state_bucket_name, <composed name>)`, and `coalesce` returns its
# first non-null argument, so null is precisely the signal that means "compose
# it". For the same reason `nullable = false` is deliberately NOT set on this
# input -- it would reject the null the default supplies and make the composed
# fallback unreachable.
# Assumptions: the default holds no real bucket name. A committed bucket name
# would publish one account's naming standard to every reader of this repository
# and tie the root to that account.
variable "state_bucket_name" {
  description = "Explicit name for the Terraform state bucket, overriding the name main.tf composes from name_prefix, the caller's account identifier and aws_region. Resolved by coalesce in main.tf, so leaving it null selects the composed name. Set it to adopt an existing bucket or to satisfy an account naming standard."
  type        = string
  default     = null

  # WHY the condition short-circuits on null before reaching the pattern
  # Assumptions: `regex` applied to null raises a type error rather than
  # returning false, so a condition that tested the pattern alone would fail
  # every invocation that relies on the default -- which is every invocation
  # that does not override the name, the CI validate among them. The
  # `== null ||` guard is therefore load-bearing rather than defensive style.
  # The pattern is S3's general-purpose naming rule reduced to what can be
  # checked without an API call: 3 to 63 characters of lowercase letters,
  # digits, hyphens and periods, beginning and ending alphanumeric. Global
  # uniqueness and S3's reserved prefixes cannot be checked here and remain
  # apply-time failures.
  validation {
    condition     = var.state_bucket_name == null || can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.state_bucket_name))
    error_message = "state_bucket_name must be null to accept the composed name, or a legal S3 bucket name: 3 to 63 characters of lowercase letters, digits, hyphens and periods, starting and ending with a letter or digit."
  }
}

# WHY the lock table carries its own override instead of reusing the bucket's
# Assumptions: the two names answer to different service rules. A bucket name
# is unique across all of S3 and limited to 63 lowercase characters, while a
# DynamoDB table name is unique only within one account and region and admits
# uppercase letters, underscores and up to 255 characters -- so an account
# naming standard that fits one need not fit the other. A single shared override
# would impose the stricter rule on the looser resource, and an operator whose
# standard uses mixed case for tables could not express it at all.
# Assumptions: as with the bucket, no literal table name is committed; null is
# what tells main.tf to compose one from name_prefix.
variable "lock_table_name" {
  description = "Explicit name for the DynamoDB state-lock table, overriding the name main.tf composes from name_prefix. Resolved by the matching coalesce in main.tf, so leaving it null selects the composed name. Set it to adopt an existing table or to satisfy an account naming standard."
  type        = string
  default     = null

  # WHY the same null short-circuit as the bucket override, for the same reason
  # Assumptions: `regex` on null is a type error and not a false result, so the
  # guard is what keeps every defaulted invocation valid. The pattern is
  # DynamoDB's own table-name rule -- 3 to 255 characters of letters, digits,
  # underscores, hyphens and periods -- and it is deliberately laxer than the S3
  # pattern above because the service is, which is the whole reason this input
  # is separate from state_bucket_name.
  validation {
    condition     = var.lock_table_name == null || can(regex("^[A-Za-z0-9_.-]{3,255}$", var.lock_table_name))
    error_message = "lock_table_name must be null to accept the composed name, or a legal DynamoDB table name: 3 to 255 characters of letters, digits, underscores, hyphens and periods."
  }
}

variable "state_version_retention_days" {
  description = "Minimum age in days before a state object version beyond the retained recent-version count may expire."
  type        = number
  nullable    = false
  default     = 365

  validation {
    condition     = floor(var.state_version_retention_days) == var.state_version_retention_days && var.state_version_retention_days >= 90 && var.state_version_retention_days <= 3653
    error_message = "state_version_retention_days must be a whole number from 90 through 3653."
  }
}

variable "state_noncurrent_versions_to_retain" {
  description = "Number of newest noncurrent Terraform state versions retained regardless of age."
  type        = number
  nullable    = false
  default     = 20

  validation {
    condition     = floor(var.state_noncurrent_versions_to_retain) == var.state_noncurrent_versions_to_retain && var.state_noncurrent_versions_to_retain >= 5 && var.state_noncurrent_versions_to_retain <= 100
    error_message = "state_noncurrent_versions_to_retain must be a whole number from 5 through 100."
  }
}

variable "audit_log_retention_days" {
  description = "Finite lifecycle horizon for validated CloudTrail state-object data-event logs."
  type        = number
  nullable    = false
  default     = 2557

  validation {
    condition     = floor(var.audit_log_retention_days) == var.audit_log_retention_days && var.audit_log_retention_days >= 365 && var.audit_log_retention_days <= 3653
    error_message = "audit_log_retention_days must be a whole number from 365 through 3653."
  }
}

# Alternatives Considered: defaulting this input to true, and omitting the input
# altogether, were both evaluated and rejected outright. The
# state bucket is versioned, so a bucket holding any state history cannot be
# removed until every object version in it is deleted; with force_destroy true a
# single `terraform destroy` in this directory performs that deletion silently,
# and the state history of every environment in the account is gone with no
# prompt naming what was discarded. It would also put this file in direct
# conflict with docs/runbooks/teardown.md, whose bootstrap-removal step
# documents emptying the bucket as an explicit operator action and records the
# standing advice to leave the bootstrap in place if the account may be
# redeployed -- two artifacts in one repository describing incompatible
# behaviour, with the destructive one winning by default.
# Trade-offs: with false, `terraform destroy` against a populated bucket fails
# with a BucketNotEmpty error. That failure is the point rather than a rough
# edge: it is a stop, and the input exists so a genuine account decommission can
# clear it deliberately. Discarding state history should require an affirmative
# act, not inherit one from a default.
variable "state_bucket_force_destroy" {
  description = "Whether terraform destroy may delete the state bucket while it still holds objects and non-current versions. Read by force_destroy on the bucket resource in main.tf. Keep false so destroying a populated bucket fails; set true only for a deliberate account decommission, which permanently discards all state history."
  type        = bool
  default     = false
}

# WHY the common tags are one map applied through the provider rather than
# written onto each resource -- Trade-offs: versions.tf passes this value to the
# provider's `default_tags` block, and the provider merges it into every
# taggable resource it creates -- the bucket, its versioning, encryption and
# public-access sub-resources, and the lock table -- so the common set is stated
# once instead of copied onto each, and cannot drift between copies. Resources
# in main.tf then carry only their own distinguishing `Name` tag. The accepted
# cost is locality: reading a resource in main.tf does not reveal the tags it
# will actually carry, so a reader has to know that versions.tf is where they
# are applied.
# Alternatives Considered: a fixed `object({...})` type naming the three keys
# below. Rejected -- the AWS tagging API is itself an arbitrary
# string-to-string map, so an object type would reject a caller's own
# cost-allocation or ownership tags while adding nothing this root relies on.
# Assumptions: the default holds only non-secret descriptive values. Tags are
# readable by any principal that can describe the resource and are surfaced in
# billing and cost-allocation exports, so nothing sensitive belongs in one.
variable "tags" {
  description = "Common tag set merged into every taggable resource in this root through the provider default_tags block in versions.tf. Values must be non-secret: tags are visible to any principal that can describe the resource and appear in cost-allocation exports."
  type        = map(string)
  default = {
    Project   = "carddemo"
    Component = "tfstate-backend"
    ManagedBy = "terraform"
  }
}

# -----------------------------------------------------------------------------
# Deliberate absences.
#
# NINE inputs is the whole surface. Two things a reader may go looking for are
# not here, and their absence is a decision rather than an omission:
#
#   - No `environment` input, for the reason recorded in the header: this root
#     is scoped to an AWS account, not to an environment. Both roots under
#     infra/envs/ are environment-scoped and carry a terraform.tfvars; this one
#     is neither and carries none.
#   - No TENTH input of any kind. infra/.tflint.hcl enables
#     terraform_unused_declarations, and the lint step in
#     .github/workflows/infra-ci.yml is gating with no tolerated-finding tier,
#     so an input that no expression in this root reads fails the build rather
#     than merely reading as untidy. That is why the header names the consumer
#     of each of the nine above.
#
# Refactoring Rationale: this block said "Seven inputs is the whole surface",
# "No eighth input of any kind" and "each of the seven above", while the header
# twenty lines up correctly said nine and enumerated all nine by name. The two
# halves of one file disagreed, and the closing half was the one a reader would
# quote when deciding whether an input already existed. Both counts are now
# derived the same way and are checkable with
# `grep -c '^variable "' variables.tf`.
# -----------------------------------------------------------------------------
