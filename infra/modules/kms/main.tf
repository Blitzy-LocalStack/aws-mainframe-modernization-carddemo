# =============================================================================
# infra/modules/kms/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Declares the four customer-managed KMS keys the migrated stack encrypts
#   itself with -- one per data class -- each with automatic rotation, its own
#   alias and its own key policy:
#
#     aws_kms_key.aurora   The Aurora PostgreSQL cluster and its automated
#                          backups: the relational records the VSAM masters
#                          become, including account balances
#                          [app/cpy/CVACT01Y.cpy:L7], card numbers and card
#                          verification values [app/cpy/CVACT02Y.cpy:L5,L7] and
#                          customer national and government identifiers
#                          [app/cpy/CVCUS01Y.cpy:L17-L18].
#     aws_kms_key.s3       The versioned dataset bucket, where the batch chain
#                          and the ETL stage dataset generations.
#     aws_kms_key.secrets  The Secrets Manager entries holding the database
#                          credential and the seed-user passwords the stack
#                          generates rather than commits -- the mechanism that
#                          replaces the fixed-width password field the user
#                          record carries [app/cpy/CSUSR01Y.cpy:L21], which is
#                          deliberately not carried forward into any target
#                          schema.
#     aws_kms_key.sqs      The request, reply and error queues and their
#                          dead-letter queues, whose payloads carry card
#                          numbers.
#
#   Four keys, four aliases and four policy documents, and nothing else. No key
#   material, no credential, no AWS account identifier and no ARN literal
#   appears in this file: account identity is read at plan time from the
#   caller's own session, trusted principals arrive as module inputs, and the
#   key identifiers and ARNs this module produces travel outward as outputs.
#
# Parameters / Return values:
#   None are declared here -- this file holds only resources and data sources.
#   The module's nine inputs, each carrying its own `type` and `description`,
#   are declared in infra/modules/kms/variables.tf, and the identifiers, ARNs
#   and alias names the module publishes to its caller are declared in
#   infra/modules/kms/outputs.tf. All nine inputs are consumed below: an input
#   this file stopped reading would be a contract still published to callers
#   and to the module's generated documentation but no longer honoured, which is
#   the case tflint's unused-declaration rule is enabled to catch.
#
# Errors / Exceptions:
#   Two failure modes are worth naming, because both are moved earlier by the
#   way this file is written rather than being absent:
#     - A malformed trusted-principal ARN, or a reference that cannot be
#       resolved, fails while the four policy documents are evaluated during
#       `terraform plan`, not part-way through a run that has already created
#       keys. That is a consequence of composing each policy with
#       `aws_iam_policy_document` rather than with a JSON string.
#     - A key policy that names no principal able to administer the key is
#       rejected by the service's own lockout safety check. Every policy below
#       therefore opens with an administration statement; that check is relied
#       on, never bypassed.
#   The module's three input `validation` blocks -- on `environment`,
#   `name_prefix` and `deletion_window_in_days` -- reject a bad value at plan
#   time before any of this file is reached; they are documented at the
#   declarations themselves in variables.tf.
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: four separate keys rather than one shared key for
#     the whole stack, accepting four monthly key charges to bound a key-policy
#     mistake or a key compromise to a single data class.
#   - Refactoring Rationale: encryption at rest is a property the migrated tier
#     adds rather than one it inherits, and the reason is measured -- the eight
#     CICS FILE resources of the VSAM tier are each defined with
#     `RECOVERY(NONE)` and `JOURNAL(NO)`.
#   - Assumptions: the calling root owns the `provider "aws"` block, so the
#     region and the baseline `default_tags` these keys carry come from the
#     caller; and each policy's administration statement is what keeps its key
#     manageable and deletable afterwards.
#   - Trade-offs: no `prevent_destroy`, and the deletion window is an input, so
#     that `terraform destroy` can release these keys cleanly.
#   Each bullet is expanded, with its mechanism, at the argument it applies to.
#
#   Where a comment below reasons about `terraform apply` or `terraform destroy`
#   it is describing what an argument means at those points, not reporting on a
#   provisioned stack. No key declared here has been created, rotated or used:
#   this tree is authored and statically checked, and applying it to a live
#   account is an operator action outside this scope.
#
#   What "statically checked" admits for THIS directory, stated exactly, because
#   the tree-wide table in docs/architecture/service-catalog.md was written at a
#   checkpoint before any module had a composition file and therefore records
#   `terraform validate` as not runnable: with this file present the directory
#   parses, `terraform fmt -check` is clean, and `terraform validate` succeeds --
#   both on its own and through a root that calls it as
#   `source = "../../modules/kms"`. What is still outside that boundary is a
#   `plan` or an `apply` against a live account, which needs credentials no part
#   of this repository holds. One finding remains against this directory and it
#   is not in this file: the module-structure rule reports the absent
#   outputs.tf, which is authored at a later index of the same plan. It is left
#   unannotated deliberately -- a `tflint-ignore` for a condition that resolves
#   itself would outlive the condition and quietly narrow the rule.
# =============================================================================

# -----------------------------------------------------------------------------
# Caller context
# -----------------------------------------------------------------------------

# Assumptions: every key policy below has to name an account-root principal,
# and a principal ARN embeds the AWS account identifier. Reading it from the
# caller's own session is what keeps that identifier out of the repository --
# the project admits no committed secret, credential or account identifier, and
# a "just the account number" exemption is the one that would make the
# constraint unverifiable by grep. The value is resolved at plan time from
# whichever credentials the operator runs with, so one unmodified module serves
# every account rather than being edited per account.
data "aws_caller_identity" "current" {}

# Assumptions: the region is a property of the `provider "aws"` block the
# calling root configures, not of this module, and it is needed here for one
# reason only -- a `kms:ViaService` condition value is service-and-region
# specific (`sqs.<region>.amazonaws.com`), so the condition cannot be written
# without it. Reading the region rather than accepting it as an input keeps the
# condition in step with the provider automatically: a root that changes its
# region cannot leave behind a condition naming the old one, which is precisely
# the failure a hardcoded region string produces -- the grant silently stops
# matching and every request it was meant to permit is denied.
data "aws_region" "current" {}

# Assumptions: an ARN's second segment is the partition, and it is not always
# `aws`. Reading it rather than spelling it out means the root principal ARN
# below is assembled entirely from resolved values, so no part of an ARN is a
# literal in this file and the module stays correct in a partition other than
# the commercial one -- where a hardcoded `aws` segment would produce a
# syntactically valid ARN that names no principal, and therefore a key policy
# the service rejects for naming no administrator.
data "aws_partition" "current" {}

# -----------------------------------------------------------------------------
# Values composed once and reused by all four keys
# -----------------------------------------------------------------------------

locals {
  # Assumptions: the four alias names differ only in the data class they name,
  # so the part they share is composed once here and interpolated four times
  # below. Composing it at each alias instead would let the four spellings drift
  # apart -- a prefix corrected in three places and missed in the fourth
  # produces one alias that no longer sorts or greps with its siblings, and
  # nothing in the plan output marks it as the odd one.
  #
  # Assumptions: each finished alias is `alias/<name_prefix>-<class>-<environment>`,
  # and the environment segment is load-bearing rather than decorative. Both
  # environment roots call this same module source, so two sets of four keys can
  # exist in one account; without the segment an operator listing aliases there
  # would see two identically-named aliases and have no way to tell which
  # environment's ciphertext each key opens.
  alias_prefix = "${var.name_prefix}-"

  # Assumptions: the account-root principal is the administrative principal in
  # all four key policies, so its ARN is assembled once here from the resolved
  # partition and the resolved account identifier. Two properties follow from
  # composing it rather than writing it: no account identifier is committed to
  # the repository, and the four policies cannot end up naming two different
  # principals because there is only one expression to get wrong.
  account_root_arn = "arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:root"

  # Assumptions: these five actions are the whole of cryptographic USE of a key
  # -- encrypt, decrypt, re-encrypt, obtain a data key, and read the key's
  # metadata so a client can tell which key a ciphertext belongs to. They are
  # deliberately the same five for all four keys while the PRINCIPALS differ per
  # key, because the per-domain boundary this module exists to draw is a boundary
  # over who may use which key, not over which operations exist. Note what the
  # set excludes: no `kms:PutKeyPolicy`, no `kms:CreateGrant`, no
  # `kms:ScheduleKeyDeletion` and no `kms:*`. A trusted workload that could
  # rewrite the policy of the key it uses could grant itself, or anything else,
  # access to that key -- which would make the key policy describe a boundary it
  # no longer enforces. Administration stays with the account root.
  key_user_actions = [
    "kms:Encrypt",
    "kms:Decrypt",
    "kms:ReEncrypt*",
    "kms:GenerateDataKey*",
    "kms:DescribeKey",
  ]
}

# =============================================================================
# The four keys
# -----------------------------------------------------------------------------
# The notes in this banner are SHARED: they govern all four key declarations
# that follow, because the reasoning behind these arguments is identical across
# the four and stating it four times would let the copies drift apart. What is
# genuinely per-key -- the data class, the trust list and whether a service
# principal needs a grant -- is documented at each key instead.
#
# Alternatives Considered: FOUR KEYS RATHER THAN ONE SHARED KEY. This is the
# defining decision of the module and the alternative is real: a single
# customer-managed key encrypting the database, the dataset bucket, the secrets
# and the queues would work, and it would cost less, because KMS is billed per
# key per month on top of per-request charges -- one monthly key charge instead
# of four. It is rejected on blast radius, and the mechanism is concrete. One
# key means exactly one key policy and one rotation schedule, so a principal
# mistakenly added to that single policy, or a compromise of that single key,
# reaches all four data classes at once: the relational records, the staged
# dataset generations, the stored credentials and the queue payloads. With four
# keys each carries its own policy and rotates independently, so the same
# mistake or the same compromise reaches one data class and leaves the other
# three unreadable to it. A queue consumer trusted to open message payloads
# cannot decrypt a database backup, because the grant that would let it do so
# is on a key its policy does not appear in.
#   Trade-offs: the cost is named rather than hidden -- four monthly key charges
#   instead of one, and four key policies to keep correct instead of one, which
#   is four times the surface on which a wrong trust entry can be written. That
#   is accepted because a wrong entry on one of four policies is recoverable by
#   narrowing that policy, whereas the same entry on a single shared policy has
#   already exposed everything the stack stores. The same reasoning is recorded,
#   once, in docs/adr/ADR-008-security-and-identity.md; it is cross-referenced
#   here rather than re-derived so the two cannot diverge.
#
# Refactoring Rationale: WHY THESE KEYS EXIST AT ALL, MEASURED. Encryption at
# rest is a property the migrated tier adds rather than one it inherits, and the
# evidence is countable rather than asserted. The CICS resource definitions
# declare eight FILE resources -- the stanzas at [app/csd/CARDDEMO.CSD:L1],
# [L13], [L25], [L37], [L50], [L63], [L76] and [L88] of a 505-line file, for
# ACCTDAT, CARDAIX, CARDDAT, CCXREF, CUSTDAT, CXACAIX, TRANSACT and USRSEC --
# and each one of the eight carries `RECOVERY(NONE)` [L9, L21, L33, L46, L59,
# L72, L84, L96], `JOURNAL(NO)` [L7, L19, L31, L44, L57, L70, L82, L94] and
# `JNLREAD(NONE) JNLSYNCREAD(NO) JNLUPDATE(NO) JNLADD(NONE)` [L8, L20, L32, L45,
# L58, L71, L83, L95]. Those are configuration properties of a deliberately
# simple demonstration application, cited by path and line so that a reader can
# see exactly which tier the four keys answer for, and so that "encryption at
# rest was added" reads as a documented decision about a measured tier rather
# than as an unexplained addition.
#   The claim is SCOPED TO THE VSAM TIER on purpose, and the scope is the point.
#   The Db2 tier of the authorization extension does declare an image copy, at
#   [app/app-authorization-ims-db2-mq/ddl/XAUTHFRD.ddl:L4], so a broader claim
#   about the baseline as a whole would be wrong and would contradict
#   docs/architecture/security-and-identity.md, which reconciles the same two
#   facts as a checklist item.
#   The baseline is reference-only and is unchanged by this migration: it keeps
#   running exactly as it is, and this migration adds a path rather than
#   removing one. What differs is only that the tier re-expressed here declares
#   a customer-managed key and rotation where the tier it re-expresses declared
#   neither.
#
# Assumptions: ROTATION IS NOT A CALLER PREFERENCE. `enable_key_rotation` is
# wired to the module input on every key, and that input defaults to `true`
# because rotation is the posture the target architecture specifies for all
# four keys. What keeps it true is external to this file: the policy scan in the
# infrastructure pipeline reports at HIGH and CRITICAL severity, and a
# customer-managed key with rotation disabled is exactly the class of finding it
# raises. Wiring the argument on all four keys satisfies that gate by
# construction, so no inline suppression is needed anywhere in this module --
# which is the difference between a gate that is passed and a gate that is
# silenced.
#
# Alternatives Considered: NO `multi_region` ARGUMENT ON ANY KEY, so every key
# below is single-Region. A multi-Region key is the alternative and it is out of
# scope: the target topology is a single region with three availability zones,
# and multi-region and disaster-recovery topology are explicitly excluded from
# this migration. Recorded so the absence reads as a decision rather than an
# oversight, and stated as out of scope rather than as work planned elsewhere.
#
# Assumptions: NO IMPORTED KEY MATERIAL AND NO CUSTOM KEY STORE. Every key
# below has its material generated by the service, so no `custom_key_store_id`
# and no imported-material path appears. Either would move the provenance of the
# material outside this configuration, leaving a key whose origin cannot be read
# from the repository that declares it.
#
# Trade-offs: NO `lifecycle { prevent_destroy = true }` ON ANY KEY. The
# accepted risk is a key destroyed by an unintended `terraform destroy`. It is
# accepted because the acceptance criterion for this tree is that `destroy`
# tears the stack down cleanly, and `prevent_destroy` makes that criterion
# unsatisfiable: the run halts on the guarded resource and leaves the rest of
# the environment half-removed, which has to be finished by hand. The recovery
# mechanism is `var.deletion_window_in_days` instead -- a destroyed key spends
# that window pending deletion and can be cancelled within it -- and it is a
# recovery window rather than a block, so it does not stop the teardown.
#
# Trade-offs: `deletion_window_in_days` IS AN INPUT RATHER THAN A CONSTANT.
# The two ends of the range buy different things, and which one is wanted
# belongs to the environment rather than to the module. A short window lets
# `terraform destroy` release the keys sooner and stops a torn-down environment
# from leaving four keys behind in a pending-deletion state; a longer window
# preserves more time in which a key destroyed by mistake can be recovered,
# because once the window elapses the key is gone and every ciphertext under it
# is permanently unreadable. Both environment roots may legitimately set it
# differently, since it is a retention value rather than a change of topology.
#
# Alternatives Considered: EVERY KEY SETS `policy` EXPLICITLY. Omitting the
# argument is the alternative, and it is not a neutral omission: a key created
# with no policy receives the service's default key policy, which delegates
# authorization for that key to IAM across the whole account. Any principal
# whose IAM permissions allow a KMS action could then use the key, so the
# per-domain boundary the four keys exist to draw would be erased at creation --
# four keys each carrying an account-wide default policy are, in effect, one
# key. Setting the policy on every key is therefore the mechanism that makes the
# rest of this module mean anything.
#
# Alternatives Considered: EACH POLICY IS COMPOSED WITH
# `aws_iam_policy_document` RATHER THAN AS A JSON HEREDOC. The heredoc is
# shorter and is the alternative. It is rejected because a heredoc is an opaque
# string to Terraform: a mistyped action name, an unbalanced brace or an
# unresolvable reference inside it is not detected during `terraform plan` and
# surfaces as a service rejection during `terraform apply`, and editing one
# statement re-diffs the entire blob instead of the statement that changed. The
# data source is parsed and validated at plan time and renders a
# statement-level diff, which is what makes a key policy reviewable before it is
# applied rather than after.
#
# Assumptions: EVERY POLICY OPENS WITH AN ADMINISTRATION STATEMENT FOR THE
# ACCOUNT ROOT. This rests on a documented service behaviour rather than on a
# preference: a key policy is the primary authorization mechanism for its own
# key, so a key whose policy names no principal able to administer it can
# afterwards be neither re-policied nor scheduled for deletion. The service
# guards against precisely that with a lockout safety check that refuses such a
# policy, and this module relies on that check -- it never sets the argument
# that bypasses it. The administration statement is also what lets the operator
# running `terraform destroy` schedule each key for deletion, so the clean
# teardown criterion depends on this statement as much as on the absence of
# `prevent_destroy`.
#
# Assumptions: `resources = ["*"]` WITHIN A KEY POLICY MEANS "THIS KEY". A key
# policy is attached to one key and is evaluated only for that key, so its
# resource element can refer to nothing else and there is no narrower form
# available to write. Recorded because the wildcard reads at a glance like the
# over-broad grant a policy scan exists to catch; what actually scopes each
# statement below is its `principals` block and, where one applies, its
# condition.
#
# Alternatives Considered: PER-KEY TRUST LISTS, NOT ONE SHARED LIST. Each
# policy grants cryptographic use to its OWN input and to no other:
# `var.aurora_key_user_role_arns` appears only in the Aurora policy,
# `var.s3_key_user_role_arns` only in the S3 policy, and so on for the remaining
# two. A single shared list is the obvious simplification and it would undo the
# four-key decision above -- every trusted principal would hold use of every
# key, so a role trusted only to read queue payloads could also decrypt a
# database backup and a stored credential. The four separate lists are what make
# the four keys a boundary rather than four copies of one permission. The same
# reasoning is recorded at the declarations in infra/modules/kms/variables.tf.
#
# Assumptions: A USE STATEMENT IS EMITTED ONLY WHEN ITS LIST IS NON-EMPTY. All
# four lists default to empty, deliberately: the principals they name are task
# roles created by a module that itself consumes these keys' ARNs, so requiring
# a non-empty list would make the grant a precondition of the key the grant
# depends on. An emitted statement with an empty `principals` block is not an
# empty grant but an invalid policy the service rejects, so each use statement
# is generated conditionally on its own list. The keys therefore exist,
# administrable and rotating, before any workload is trusted with them, and each
# grant appears once its caller has roles to name.
#
# Assumptions: `tags` IS MERGED RATHER THAN ASSIGNED. The calling root applies
# the stack's baseline tag set through `default_tags` on its own `provider "aws"`
# block, and the provider merges that set into every taggable resource it
# creates. What is merged at each key below is the layer above that set: the tag
# naming the data class, so the four keys are distinguishable from one another
# and from the root's other resources in a cost report or an inventory. Read
# without this note, a small tag map on a key looks like missing tagging.
#
# Assumptions: EACH `description` NAMES BOTH THE DATA CLASS AND THE
# ENVIRONMENT, which the alias already encodes. The duplication is intentional
# because an alias is a separate object: an operator reading a key in the
# console, an inventory export or a policy-scan finding sees the key and its
# description without necessarily having resolved the aliases pointing at it.
# =============================================================================

# -----------------------------------------------------------------------------
# Aurora PostgreSQL -- the relational records
#
# Assumptions: this key protects the one datastore that holds every field the
# baseline kept in its VSAM masters -- money as exact fixed point
# [app/cpy/CVACT01Y.cpy:L7], card numbers and card verification values
# [app/cpy/CVACT02Y.cpy:L5,L7], and customer national and government identifiers
# [app/cpy/CVCUS01Y.cpy:L17-L18] -- together with the cluster's automated
# backups, which are encrypted with the same key as the cluster. It is therefore
# the key whose trust list is the narrowest of the four in intent.
#
# Assumptions: NO service-principal statement appears in this policy, and the
# absence is reasoned rather than overlooked. The database service reaches a
# customer-managed key through a grant issued by the principal that creates the
# encrypted cluster, not through a statement naming the service in the key
# policy, and the administration statement below is what permits that principal
# to issue the grant. Adding a service-principal statement as well would widen
# the policy by a principal that nothing in this stack needs, which is the
# opposite of what splitting one key into four is for.
# -----------------------------------------------------------------------------

data "aws_iam_policy_document" "aurora" {
  statement {
    sid       = "AllowKeyAdministrationByAccountRoot"
    effect    = "Allow"
    actions   = ["kms:*"]
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = [local.account_root_arn]
    }
  }

  dynamic "statement" {
    for_each = length(var.aurora_key_user_role_arns) > 0 ? [1] : []

    content {
      sid       = "AllowCryptographicUseByAuroraPrincipals"
      effect    = "Allow"
      actions   = local.key_user_actions
      resources = ["*"]

      principals {
        type        = "AWS"
        identifiers = var.aurora_key_user_role_arns
      }
    }
  }
}

resource "aws_kms_key" "aurora" {
  description             = "CardDemo ${var.environment}: customer-managed key for the Aurora PostgreSQL cluster and its automated backups -- the account, customer, card, ledger, reference and authorization records."
  enable_key_rotation     = var.enable_key_rotation
  deletion_window_in_days = var.deletion_window_in_days

  # Assumptions: this argument names THIS key's own document. Pointing two keys
  # at one document would silently merge their trust lists and reduce the four
  # keys to one boundary expressed four times, which is the failure the
  # per-key-list note in the banner above describes.
  policy = data.aws_iam_policy_document.aurora.json

  tags = merge(var.tags, {
    Name      = "${local.alias_prefix}aurora-${var.environment}"
    DataClass = "aurora-postgresql"
  })
}

# Assumptions: this note governs all four aliases in this module -- this one and
# the three declared further down. An alias exists because a key identifier is an
# opaque generated value that says nothing about what it opens, so it is the
# alias that consuming modules and operators are expected to reference. The
# second reason is durability: an alias name is stable across the key it points
# at being replaced, so a reference written against the alias still resolves
# after a key is re-created, where a reference written against the identifier has
# to be found and edited everywhere it was written.
resource "aws_kms_alias" "aurora" {
  name          = "alias/${local.alias_prefix}aurora-${var.environment}"
  target_key_id = aws_kms_key.aurora.key_id
}

# -----------------------------------------------------------------------------
# S3 -- the versioned dataset bucket
#
# Assumptions: this key protects the dataset generations the batch chain and the
# ETL stage in object storage -- the target form of the generation datasets the
# baseline defines with a five-generation scratch limit. Its trust list names the
# task roles that read and write those objects.
#
# Assumptions: NO service-principal statement appears in this policy either.
# Every writer to the dataset bucket in this architecture is a task role running
# a container -- the staging, backup, statement and report steps -- and object
# storage performs its encryption and decryption on behalf of whichever
# principal made the request, using that principal's permissions on this key.
# Nothing in scope delivers into the bucket as a service principal instead: log
# delivery, inventory and cross-region replication are all outside this
# migration, and each would be the reason to add such a statement if it were in
# it.
# -----------------------------------------------------------------------------

data "aws_iam_policy_document" "s3" {
  statement {
    sid       = "AllowKeyAdministrationByAccountRoot"
    effect    = "Allow"
    actions   = ["kms:*"]
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = [local.account_root_arn]
    }
  }

  dynamic "statement" {
    for_each = length(var.s3_key_user_role_arns) > 0 ? [1] : []

    content {
      sid       = "AllowCryptographicUseByS3Principals"
      effect    = "Allow"
      actions   = local.key_user_actions
      resources = ["*"]

      principals {
        type        = "AWS"
        identifiers = var.s3_key_user_role_arns
      }
    }
  }
}

resource "aws_kms_key" "s3" {
  description             = "CardDemo ${var.environment}: customer-managed key for the versioned S3 dataset bucket holding the staged dataset generations produced by the batch chain and the ETL."
  enable_key_rotation     = var.enable_key_rotation
  deletion_window_in_days = var.deletion_window_in_days
  policy                  = data.aws_iam_policy_document.s3.json

  tags = merge(var.tags, {
    Name      = "${local.alias_prefix}s3-${var.environment}"
    DataClass = "s3-datasets"
  })
}

resource "aws_kms_alias" "s3" {
  name          = "alias/${local.alias_prefix}s3-${var.environment}"
  target_key_id = aws_kms_key.s3.key_id
}

# -----------------------------------------------------------------------------
# Secrets Manager -- the generated credentials
#
# Assumptions: this key protects the entries holding the database credential and
# the seed-user passwords, both generated at provisioning time and written
# straight into the secret store. That mechanism is what allows the migrated
# stack to carry no password field at all, in place of the fixed-width plaintext
# field the user record declares [app/cpy/CSUSR01Y.cpy:L21], which is
# deliberately not carried into any target schema. This key is consequently the
# one whose compromise would be worth the most to an attacker, and the strongest
# reason in the module for not sharing one key across all four data classes.
#
# Assumptions: NO service-principal statement appears in this policy. The secret
# store encrypts and decrypts a secret's value on behalf of whichever principal
# called it, under that principal's own grant on this key, so the trust list is
# where a reader of a secret is authorised. A statement naming the service itself
# would name a principal that never calls the key on its own behalf in this
# stack, and would therefore be a grant with no request to authorise.
# -----------------------------------------------------------------------------

data "aws_iam_policy_document" "secrets" {
  statement {
    sid       = "AllowKeyAdministrationByAccountRoot"
    effect    = "Allow"
    actions   = ["kms:*"]
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = [local.account_root_arn]
    }
  }

  dynamic "statement" {
    for_each = length(var.secrets_key_user_role_arns) > 0 ? [1] : []

    content {
      sid       = "AllowCryptographicUseBySecretsPrincipals"
      effect    = "Allow"
      actions   = local.key_user_actions
      resources = ["*"]

      principals {
        type        = "AWS"
        identifiers = var.secrets_key_user_role_arns
      }
    }
  }
}

resource "aws_kms_key" "secrets" {
  description             = "CardDemo ${var.environment}: customer-managed key for the Secrets Manager entries holding the generated database credential and seed-user passwords, which the stack stores rather than commits."
  enable_key_rotation     = var.enable_key_rotation
  deletion_window_in_days = var.deletion_window_in_days
  policy                  = data.aws_iam_policy_document.secrets.json

  tags = merge(var.tags, {
    Name      = "${local.alias_prefix}secrets-${var.environment}"
    DataClass = "secrets-manager"
  })
}

resource "aws_kms_alias" "secrets" {
  name          = "alias/${local.alias_prefix}secrets-${var.environment}"
  target_key_id = aws_kms_key.secrets.key_id
}

# -----------------------------------------------------------------------------
# SQS -- the queue payloads
#
# Assumptions: this key protects the request, reply and error queues and each of
# their dead-letter queues. Their payloads are not incidental: the authorization
# request and reply are carried as delimited text whose fields include the card
# number, so a message body is cardholder data at rest for as long as it sits in
# a queue.
#
# Assumptions: this is the ONE key of the four whose policy names a service
# principal, and it is named because a specific delivery in this architecture
# fails silently without it. The nightly batch schedule is configured with a
# dead-letter target, which is a queue encrypted with this key; when an
# invocation cannot be delivered, the scheduler service -- not a task role --
# is the principal that must obtain a data key to write the failed invocation
# into that queue. Without this statement the enqueue is denied and the one
# message the dead-letter target exists to preserve is the message that is lost,
# which is the failure mode hardest to notice because it appears only when
# something else has already gone wrong. The grant is deliberately two actions
# rather than the five in `local.key_user_actions`: writing a message needs a
# data key, and reading one back needs a decrypt, and nothing about a
# dead-letter delivery needs re-encryption.
# -----------------------------------------------------------------------------

data "aws_iam_policy_document" "sqs" {
  statement {
    sid       = "AllowKeyAdministrationByAccountRoot"
    effect    = "Allow"
    actions   = ["kms:*"]
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = [local.account_root_arn]
    }
  }

  dynamic "statement" {
    for_each = length(var.sqs_key_user_role_arns) > 0 ? [1] : []

    content {
      sid       = "AllowCryptographicUseBySqsPrincipals"
      effect    = "Allow"
      actions   = local.key_user_actions
      resources = ["*"]

      principals {
        type        = "AWS"
        identifiers = var.sqs_key_user_role_arns
      }
    }
  }

  statement {
    sid    = "AllowSchedulerDeadLetterDeliveryThroughSqs"
    effect = "Allow"

    actions = [
      "kms:GenerateDataKey*",
      "kms:Decrypt",
    ]

    resources = ["*"]

    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }

    # Assumptions: a grant to a service principal is otherwise usable by that
    # service on behalf of ANY account it serves, because the principal is the
    # service rather than one caller. These two conditions are what narrow it to
    # the intended use. The first confines the grant to requests made on behalf
    # of this account, so the service cannot be induced to use this key for
    # someone else's resource. The second confines it to requests that reach KMS
    # THROUGH the queue service in this region, so the grant authorises the
    # enqueue path it was added for and nothing else -- the service principal
    # cannot use the key directly. Both condition values are resolved from the
    # caller's session and the provider's region rather than written out, for the
    # reasons given at those two data sources.
    condition {
      test     = "StringEquals"
      variable = "kms:CallerAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["sqs.${data.aws_region.current.region}.amazonaws.com"]
    }
  }
}

resource "aws_kms_key" "sqs" {
  description             = "CardDemo ${var.environment}: customer-managed key for the SQS request, reply and error queues and their dead-letter queues, whose payloads carry card numbers."
  enable_key_rotation     = var.enable_key_rotation
  deletion_window_in_days = var.deletion_window_in_days
  policy                  = data.aws_iam_policy_document.sqs.json

  tags = merge(var.tags, {
    Name      = "${local.alias_prefix}sqs-${var.environment}"
    DataClass = "sqs"
  })
}

resource "aws_kms_alias" "sqs" {
  name          = "alias/${local.alias_prefix}sqs-${var.environment}"
  target_key_id = aws_kms_key.sqs.key_id
}
