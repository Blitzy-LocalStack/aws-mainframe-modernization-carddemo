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
#                          and the ETL stage dataset generations, and the private
#                          bucket holding the single-page application bundle,
#                          which CloudFront reads through an origin access
#                          control -- so this key's policy grants that service
#                          principal a decrypt, narrowed to this account's
#                          distributions. The same key also covers the
#                          CloudWatch log groups and the encrypted alert topic,
#                          whose service grants are scoped to the regional
#                          logging and notification paths.
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
#   There is deliberately no fifth key for the values the migrated code
#   enciphers ITSELF - the card verification value [app/cpy/CVACT02Y.cpy:L7] and
#   the two customer identifiers [app/cpy/CVCUS01Y.cpy:L17-L18]. Those are
#   columns in the Aurora cluster, so their envelope data keys come from
#   aws_kms_key.aurora, whose policy carries a SECOND statement with an
#   encryption-context condition instead of `kms:ViaService` because a workload
#   role calls it DIRECTLY rather than through RDS.
#
#   Four keys, four aliases and four policy documents, and nothing else. No key
#   material, no credential, no AWS account identifier and no ARN literal
#   appears in this file: account identity is read at plan time from the
#   caller's own session, trusted principals arrive as module inputs, and the
#   key identifiers and ARNs this module produces travel outward as outputs.
#
# Parameters / Return values:
#   None are declared here -- this file holds only resources and data sources.
#   The module's TWENTY inputs, each carrying its own `type` and `description`,
#   are declared in infra/modules/kms/variables.tf, and the identifiers, ARNs
#   and alias names the module publishes to its caller are declared in
#   infra/modules/kms/outputs.tf. All twenty inputs are consumed below: an input
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
#   NINETEEN of the twenty inputs carry a `validation` block -- every one except
#   `tags`, whose keys and values are opaque to this module -- and each rejects a
#   bad value at plan time before any of this file is reached; they are
#   documented at the declarations themselves in variables.tf.
#   Refactoring Rationale: this paragraph previously named four validated inputs
#   and the paragraph above claimed ten inputs in total. Both were stale rather
#   than approximate, and both are now stated as measurements (`grep -c
#   '^variable "'` and `grep -c '  validation {'` over variables.tf) so a reader
#   can re-derive them. The pair was corrected together with the arrival of
#   `envelope_encryption_role_arns` and
#   `envelope_encryption_context_purposes`, which would otherwise have widened
#   an already-wrong count.
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: a separate key per data class rather than one
#     shared key for the whole stack, accepting one monthly key charge per key to
#     bound a key-policy mistake or a key compromise to a single data class.
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
#   of this repository holds. The sibling outputs.tf is now authored and
#   publishes the four key/alias contracts, so this directory no longer carries
#   a module-structure finding. No `tflint-ignore` is needed: the earlier
#   missing-file condition was fixed at its source rather than suppressed.
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
# Values composed once and reused by every key
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

  # Refactoring Rationale: integrated AWS services ask KMS for data keys and
  # decrypt ciphertext; they do not need a workload role to call Encrypt,
  # ReEncrypt, change grants or administer the key directly. Keeping only these
  # three operations prevents a role trusted for one storage capability from
  # turning that trust into a general-purpose cryptographic oracle.
  service_data_key_actions = [
    "kms:Decrypt",
    "kms:GenerateDataKey*",
    "kms:DescribeKey",
  ]

  # Refactoring Rationale: the envelope grant is TWO actions and not the
  # three above, and the difference is the point rather than an oversight. The
  # three above are for integrated services, which describe a key to discover its
  # properties before using it; the envelope grant is exercised by our own code, which
  # already holds the key identifier from its configuration and never asks the
  # service what the key is. Granting DescribeKey as well would let a workload
  # enumerate a key's metadata for no capability it needs.
  #
  # Assumptions: Encrypt is deliberately ABSENT even though this key protects data
  # this system writes. Envelope encryption never calls it: the workload asks for a
  # data key, receives it in plaintext and enciphered form together, and enciphers
  # locally -- so GenerateDataKey covers the whole write path and Decrypt the whole
  # read path. A role holding Encrypt on this key could turn it into a
  # general-purpose oracle for arbitrary plaintext, which is a capability no card
  # verification value needs.
  workload_envelope_actions = [
    "kms:Decrypt",
    "kms:GenerateDataKey*",
  ]

  # Assumptions: the S3 key's CloudFront grant is conditioned on a source ARN,
  # and this expression is the whole of how that ARN is decided -- it is composed
  # here rather than inline so the fallback and the caller-supplied form are
  # visibly the same value used by one condition, not two branches that could
  # drift. When the caller names distributions, the condition matches exactly
  # those; when it names none, the pattern admits distributions of THIS account
  # in THIS partition and nothing else.
  #
  # Trade-offs: the fallback contains a wildcard, and a wildcard in a condition
  # value is normally the thing to avoid. It is accepted here because the
  # alternative is worse in both available directions: omitting the statement
  # makes the front end return 403 for every asset, and omitting the condition
  # makes the grant usable by any account's distribution. The wildcard sits
  # inside the account and partition segments of the ARN, so what it widens is
  # WHICH of this account's distributions may decrypt -- never whose.
  #
  # Assumptions: the account identifier and the partition are resolved from the
  # caller's session for the reasons recorded at those two data sources; no part
  # of this ARN is a literal.
  # Assumptions: ALL THREE module inputs narrow the same grant and are read
  # together -- s3_cloudfront_distribution_arns and cloudfront_distribution_arns
  # take lists, cloudfront_distribution_arn takes the single SPA distribution an
  # environment root wires straight from the cloudfront-spa module. Reading only
  # one of them would leave a root that used another silently falling back to the
  # account-wide pattern below, which is exactly the widening the condition exists
  # to prevent.
  #
  # Trade-offs: three spellings for one concept is more surface than one, and the
  # alternative considered was to delete two of them. It is rejected because each
  # spelling is already consumed by a caller -- the list form by the exact-ARN
  # narrowing statement and its precondition, the scalar by roots that wire the
  # distribution output directly -- so removing either would silently widen the
  # grant for that caller rather than fail its plan.
  cloudfront_distribution_narrowing_arns = compact(concat(
    var.s3_cloudfront_distribution_arns,
    var.cloudfront_distribution_arns,
    [var.cloudfront_distribution_arn],
  ))

  cloudfront_distribution_source_arns = length(local.cloudfront_distribution_narrowing_arns) > 0 ? local.cloudfront_distribution_narrowing_arns : ["arn:${data.aws_partition.current.partition}:cloudfront::${data.aws_caller_identity.current.account_id}:distribution/*"]

  # Assumptions: CloudWatch Logs uses a REGIONAL service principal, and the
  # DNS suffix changes with the AWS partition. Composing both values from the
  # provider context keeps the key policy aligned with the region that owns the
  # log groups and avoids a commercial-partition literal in reusable source.
  cloudwatch_logs_service_principal                   = "logs.${data.aws_region.current.region}.${data.aws_partition.current.dns_suffix}"
  cloudwatch_log_group_arn_pattern                    = "arn:${data.aws_partition.current.partition}:logs:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:log-group:*"
  cloudwatch_alarm_arn_pattern                        = "arn:${data.aws_partition.current.partition}:cloudwatch:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:alarm:${var.name_prefix}-${var.environment}-*"
  current_account_role_arn_pattern                    = "^arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:role/[A-Za-z0-9+=,.@_-]+(/[A-Za-z0-9+=,.@_-]+)*$"
  current_account_cloudfront_distribution_arn_pattern = "^arn:${data.aws_partition.current.partition}:cloudfront::${data.aws_caller_identity.current.account_id}:distribution/[A-Z0-9]+$"
  current_account_secret_arn_pattern                  = "^arn:${data.aws_partition.current.partition}:secretsmanager:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:secret:[A-Za-z0-9/_+=.@-]+-[A-Za-z0-9]{6}$"
  current_account_log_delivery_source_arn_pattern     = "^arn:${data.aws_partition.current.partition}:logs:us-east-1:${data.aws_caller_identity.current.account_id}:delivery-source:[A-Za-z0-9._-]+$"
  current_account_log_group_arn_pattern               = "^arn:${data.aws_partition.current.partition}:logs:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:log-group:[A-Za-z0-9_./#-]+$"
  current_account_sns_topic_arn_pattern               = "^arn:${data.aws_partition.current.partition}:sns:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:[A-Za-z0-9_-]+$"

  # Assumptions: every service grant on the S3 key exists in exactly two shapes --
  # an EXACT-ARN shape used when the environment root can name the distribution,
  # log group, delivery source or topic, and a PATTERN shape used when it cannot.
  # The pair is mutually exclusive by construction: each statement's for_each
  # inverts the other's emptiness test, so precisely one of the two renders for a
  # given purpose.
  #
  # WHY : Assumptions: two independent hazards have to be avoided at once, and
  #       neither shape avoids both. Emitting only the exact-ARN shape leaves a
  #       root that supplies no ARNs with NO service grant at all, and CloudWatch
  #       Logs, SNS and CloudFront then fail closed at create time. Emitting both
  #       shapes unconditionally makes the broader pattern dominate the narrow
  #       grant, so the narrowing becomes decorative, and duplicate statement
  #       identifiers are rejected outright by the key-policy API -- something
  #       `terraform validate` does not detect. Inverting one for_each against the
  #       other is the only arrangement that keeps the module deployable from a
  #       minimal root while still collapsing to least privilege as soon as the
  #       root can name its resources.
  emit_exact_cloudfront_grant = length(local.cloudfront_distribution_narrowing_arns) > 0
  emit_exact_log_group_grant  = length(var.cloudwatch_log_group_arns) > 0
  emit_exact_topic_grant      = length(var.sns_topic_arns) > 0

  # S3 Bucket Keys use the bucket ARN as their encryption context, while direct
  # object data keys use the object ARN. Deriving both forms from exact bucket
  # ARNs keeps the caller from supplying a wildcard broad enough to cover a
  # different bucket.
  s3_encryption_context_arns = distinct(flatten([
    for bucket_arn in var.s3_encryption_context_bucket_arns :
    [bucket_arn, "${bucket_arn}/*"]
  ]))
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
# The arithmetic here is FOUR KEYS SERVING FIVE PURPOSES, and the mismatch is
# deliberate rather than untidy. The frozen design fixes four customer-managed
# keys -- Aurora, S3, Secrets Manager and SQS. The fifth purpose, the values the
# application enciphers itself, has no key of its own: its grant is a second
# statement on the Aurora key, because the columns it protects are Aurora column
# data. That is why this file declares four keys, four aliases and four policies
# while variables.tf declares FIVE trust lists -- one per purpose, not one per
# key. A reader who expects those two counts to agree should read this paragraph
# rather than assume one of them is stale.
#
# Alternatives Considered: A KEY PER DATA CLASS RATHER THAN ONE SHARED KEY. This
# is the defining decision of the module and the alternative is real: a single
# customer-managed key encrypting the database, the dataset bucket, the secrets
# and the queues would work, and it
# would cost less, because KMS is billed per key per month on top of per-request
# charges -- one monthly key charge instead of four. It is rejected on blast
# radius, and the mechanism is concrete. One
# key means exactly one key policy and one rotation schedule, so a principal
# mistakenly added to that single policy, or a compromise of that single key,
# reaches all four data classes at once: the relational records - including the
# values the application enciphers itself, which are columns among them - the
# staged dataset generations, the stored credentials and the queue payloads. With
# four keys each carries its own policy and rotates independently, so the same
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
# see exactly which tier these keys answer for, and so that "encryption at
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
# four keys. The infrastructure pipeline's explicit material-security baseline
# includes the CMK-rotation check, and the variable validation additionally
# accepts only `true`; wiring the argument on all four keys therefore satisfies
# that gate by construction. No inline suppression is needed anywhere in this
# module, which is the difference between a gate that is passed and a gate that
# is silenced.
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
# per-domain boundary these keys exist to draw would be erased at creation --
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
# three. A single shared list is the obvious simplification and it would undo the
# key-per-data-class decision above -- every trusted principal would hold use of
# every key, so a role trusted only to read queue payloads could also decrypt a
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
# opposite of what splitting one key per data class is for.
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
      actions   = local.service_data_key_actions
      resources = ["*"]

      principals {
        type        = "AWS"
        identifiers = var.aurora_key_user_role_arns
      }

      condition {
        test     = "StringEquals"
        variable = "kms:CallerAccount"
        values   = [data.aws_caller_identity.current.account_id]
      }

      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = ["rds.${data.aws_region.current.region}.amazonaws.com"]
      }

      condition {
        test     = "StringEquals"
        variable = "kms:EncryptionContext:aws:rds:db-id"
        values   = var.aurora_encryption_context_ids
      }
    }
  }

  # WHY : Refactoring Rationale: this statement was the whole of a FIFTH key's
  #       policy - a separate customer-managed key the application drew envelope
  #       data keys from, for the card verification value and the two customer
  #       identifiers the baseline held in the clear. The specified key model is
  #       four keys, one per data-at-rest domain: Aurora, S3, Secrets Manager and
  #       SQS. A fifth was outside it, so the key was withdrawn and its grant
  #       moved here.
  #
  #       Assumptions: Aurora is the correct domain for it rather than an
  #       arbitrary choice among the four. Every value these envelopes protect is
  #       a column in the Aurora cluster - card.cards.cvv_encrypted,
  #       account.customers.ssn_encrypted and
  #       account.customers.govt_issued_id_encrypted - so the key that protects
  #       the Aurora domain is the key protecting that data, whether the
  #       enciphering happens in the storage layer or one layer above it.
  #       Alternatives Considered: (a) folding it into the secrets key, on the
  #       grounds that both hold confidential material - rejected, that key's
  #       domain is credentials held in Secrets Manager, and the blast radius a
  #       reader infers from its name would then be wrong; (b) keeping the fifth
  #       key and recording a divergence - rejected, the four-key model is
  #       specified rather than advisory, and a divergence is for behaviour that
  #       cannot be delivered as specified, which is not the case here.
  #       Trade-offs: what is given up is separation of key MATERIAL between
  #       storage-level and application-level encryption of the same rows, so a
  #       compromise of this key reaches both. That is a smaller loss than it
  #       first appears, because the two already protected the same records: a
  #       caller able to read the cluster's storage key could read the rows the
  #       envelopes sit in. What is NOT given up is the separation between the two
  #       application purposes - each still carries its own encryption context,
  #       asserted below, so the card role cannot open a customer identifier and
  #       the account role cannot open a card verification value.
  #
  #       Assumptions: this must be a SECOND statement rather than actions added
  #       to the one above. The Aurora statement carries a kms:ViaService
  #       condition pinning it to the RDS service principal, which is correct for
  #       storage-level use and would deny these calls outright - a task calls
  #       KMS directly, not through RDS. Two statements keep each condition set
  #       attached to the calls it belongs to.
  dynamic "statement" {
    for_each = length(var.application_envelope_user_role_arns) > 0 ? [1] : []

    content {
      sid       = "AllowEnvelopeEncryptionByApplicationRoles"
      effect    = "Allow"
      actions   = local.workload_envelope_actions
      resources = ["*"]

      principals {
        type        = "AWS"
        identifiers = var.application_envelope_user_role_arns
      }

      # Assumptions: this condition is what a direct grant has instead of
      # kms:ViaService. It admits only requests whose encryption context names one
      # of the declared purposes, so a role holding this grant cannot use the key
      # for anything but the values it was granted for -- and a ciphertext produced
      # under one purpose cannot be deciphered by a caller asking under another.
      condition {
        test     = "StringEquals"
        variable = "kms:EncryptionContext:carddemo:purpose"
        values   = var.application_envelope_context_purposes
      }

      # Assumptions: the caller-account condition is kept for the same reason it is
      # kept on every other statement in this module -- it confines the grant to
      # requests made on behalf of this account, so the key cannot be used against
      # another account's resource even by a principal this policy names.
      condition {
        test     = "StringEquals"
        variable = "kms:CallerAccount"
        values   = [data.aws_caller_identity.current.account_id]
      }
    }
  }
}

resource "aws_kms_key" "aurora" {
  description             = "CardDemo ${var.environment}: customer-managed key for the Aurora PostgreSQL cluster and its automated backups -- the account, customer, card, ledger, reference and authorization records."
  enable_key_rotation     = var.enable_key_rotation
  deletion_window_in_days = var.deletion_window_in_days

  tags = merge(var.tags, {
    Name      = "${local.alias_prefix}aurora-${var.environment}"
    DataClass = "aurora-postgresql"
  })
}

resource "aws_kms_key_policy" "aurora" {
  key_id = aws_kms_key.aurora.key_id
  policy = data.aws_iam_policy_document.aurora.json

  lifecycle {
    precondition {
      condition = alltrue([
        for arn in var.aurora_key_user_role_arns :
        can(regex(local.current_account_role_arn_pattern, arn))
      ])
      error_message = "Every aurora_key_user_role_arns value must be an exact IAM role ARN in the account applying this module."
    }

    precondition {
      condition     = length(var.aurora_key_user_role_arns) == 0 || length(var.aurora_encryption_context_ids) > 0
      error_message = "aurora_encryption_context_ids must name at least one exact Aurora cluster resource identifier when Aurora key users are configured."
    }

    # WHY : Refactoring Rationale: this precondition moved here with the envelope
    #       statement it guards, from the withdrawn fifth key's policy resource.
    #       It is kept rather than merged into the one above because the two
    #       trust lists are independent: storage-level Aurora principals and
    #       application envelope principals are configured separately, and a
    #       malformed ARN in either should name which list it was found in.
    precondition {
      condition = alltrue([
        for arn in var.application_envelope_user_role_arns :
        can(regex(local.current_account_role_arn_pattern, arn))
      ])
      error_message = "Every application_envelope_user_role_arns value must be an exact IAM role ARN in the account applying this module."
    }
  }
}

# Assumptions: this note governs all four aliases in this module -- this one and
# the four declared further down. An alias exists because a key identifier is an
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
# baseline defines with a five-generation scratch limit -- AND the private bucket
# holding the single-page application bundle, which the `cloudfront-spa` module
# encrypts with this same key. Its trust list names the task roles that read and
# write those objects.
#
# Refactoring Rationale: this policy previously named NO service principal, on
# the stated ground that every writer to the dataset bucket is a task role and
# that object storage encrypts and decrypts on behalf of whichever principal
# called it. That reasoning is sound for the dataset bucket and was incomplete
# for the key: the application bundle is fetched from its bucket by CloudFront
# through an origin access control, and CloudFront -- not a task role, and not
# the storage service acting for one -- is the principal that must decrypt those
# objects. With no grant to it, every asset request answered 403 while the
# bucket, the distribution, the origin access control and the key each looked
# correct in isolation, which is the hardest shape of failure to diagnose. The
# grant below is therefore unconditional: whether the front end can be served
# must not depend on an operator populating an optional list.
#
# Trade-offs: the grant is one action rather than the three in
# `local.service_data_key_actions`. Retrieving an encrypted object needs a decrypt and
# nothing else; `kms:Encrypt` and `kms:GenerateDataKey*` would only be needed if
# objects were uploaded THROUGH the distribution, which this architecture never
# does -- the deployment pipeline publishes the bundle under its own identity.
#
# Assumptions: log delivery, inventory and cross-region replication remain out of
# scope, and each would be its own separately reviewable statement if it were
# ever brought in. This one statement is not a precedent for adding others
# unexamined.
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
      actions   = local.service_data_key_actions
      resources = ["*"]

      principals {
        type        = "AWS"
        identifiers = var.s3_key_user_role_arns
      }

      condition {
        test     = "StringEquals"
        variable = "kms:CallerAccount"
        values   = [data.aws_caller_identity.current.account_id]
      }

      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = ["s3.${data.aws_region.current.region}.amazonaws.com"]
      }

      # Assumptions: the encryption-context condition can only be asserted when the
      # caller named the buckets it applies to. Rendering it from an empty list
      # would emit a condition with no permitted value, which denies every request
      # the surrounding statement exists to allow, so the block is emitted only
      # when a value exists. The alternative considered -- defaulting the context
      # to a wildcard -- was rejected because a wildcard here would let the grant
      # cover a bucket in this account that this key does not protect.
      #
      # Trade-offs: with the block omitted the grant is bounded only by which
      # buckets reference this key, which is broader than an exact context but
      # still narrower than the key's own key-user grant. Accepted because the
      # environment roots that name principals also name their buckets, so the
      # unbound form is reachable only from a deliberately minimal caller.
      dynamic "condition" {
        for_each = length(local.s3_encryption_context_arns) > 0 ? [1] : []

        content {
          test     = "ArnLike"
          variable = "kms:EncryptionContext:aws:s3:arn"
          values   = local.s3_encryption_context_arns
        }
      }
    }
  }

  dynamic "statement" {
    for_each = local.emit_exact_cloudfront_grant ? [1] : []

    content {
      sid       = "AllowCloudFrontOriginAccessControlDecrypt"
      effect    = "Allow"
      actions   = ["kms:Decrypt"]
      resources = ["*"]

      principals {
        type        = "Service"
        identifiers = ["cloudfront.amazonaws.com"]
      }

      condition {
        test     = "StringEquals"
        variable = "AWS:SourceAccount"
        values   = [data.aws_caller_identity.current.account_id]
      }

      condition {
        test     = "ArnEquals"
        variable = "AWS:SourceArn"
        values   = local.cloudfront_distribution_narrowing_arns
      }

      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = ["s3.${data.aws_region.current.region}.amazonaws.com"]
      }

      # Assumptions: the encryption-context condition can only be asserted when the
      # caller named the buckets it applies to. Rendering it from an empty list
      # would emit a condition with no permitted value, which denies every request
      # the surrounding statement exists to allow, so the block is emitted only
      # when a value exists. The alternative considered -- defaulting the context
      # to a wildcard -- was rejected because a wildcard here would let the grant
      # cover a bucket in this account that this key does not protect.
      #
      # Trade-offs: with the block omitted the grant is bounded only by which
      # buckets reference this key, which is broader than an exact context but
      # still narrower than the key's own key-user grant. Accepted because the
      # environment roots that name principals also name their buckets, so the
      # unbound form is reachable only from a deliberately minimal caller.
      dynamic "condition" {
        for_each = length(local.s3_encryption_context_arns) > 0 ? [1] : []

        content {
          test     = "ArnLike"
          variable = "kms:EncryptionContext:aws:s3:arn"
          values   = local.s3_encryption_context_arns
        }
      }
    }
  }

  dynamic "statement" {
    for_each = length(var.cloudwatch_log_delivery_source_arns) > 0 ? [1] : []

    content {
      sid    = "AllowCloudFrontV2LogDeliveryEncryption"
      effect = "Allow"
      actions = [
        "kms:Decrypt",
        "kms:GenerateDataKey",
      ]
      resources = ["*"]

      principals {
        type        = "Service"
        identifiers = ["delivery.logs.amazonaws.com"]
      }

      condition {
        test     = "StringEquals"
        variable = "AWS:SourceAccount"
        values   = [data.aws_caller_identity.current.account_id]
      }

      condition {
        test     = "ArnEquals"
        variable = "AWS:SourceArn"
        values   = var.cloudwatch_log_delivery_source_arns
      }

      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = ["s3.${data.aws_region.current.region}.amazonaws.com"]
      }

      # Assumptions: the encryption-context condition can only be asserted when the
      # caller named the buckets it applies to. Rendering it from an empty list
      # would emit a condition with no permitted value, which denies every request
      # the surrounding statement exists to allow, so the block is emitted only
      # when a value exists. The alternative considered -- defaulting the context
      # to a wildcard -- was rejected because a wildcard here would let the grant
      # cover a bucket in this account that this key does not protect.
      #
      # Trade-offs: with the block omitted the grant is bounded only by which
      # buckets reference this key, which is broader than an exact context but
      # still narrower than the key's own key-user grant. Accepted because the
      # environment roots that name principals also name their buckets, so the
      # unbound form is reachable only from a deliberately minimal caller.
      dynamic "condition" {
        for_each = length(local.s3_encryption_context_arns) > 0 ? [1] : []

        content {
          test     = "ArnLike"
          variable = "kms:EncryptionContext:aws:s3:arn"
          values   = local.s3_encryption_context_arns
        }
      }
    }
  }

  # Assumptions: CloudWatch Logs uses the regional service principal and binds
  # every KMS request to the exact log-group ARN in the encryption context.
  # Exact values keep the grant from covering unrelated application groups.
  dynamic "statement" {
    for_each = local.emit_exact_log_group_grant ? [1] : []

    content {
      sid    = "AllowCloudWatchLogGroupEncryption"
      effect = "Allow"
      actions = [
        "kms:Decrypt",
        "kms:DescribeKey",
        "kms:Encrypt",
        "kms:GenerateDataKey*",
        "kms:ReEncrypt*",
      ]
      resources = ["*"]

      principals {
        type        = "Service"
        identifiers = ["logs.${data.aws_region.current.region}.amazonaws.com"]
      }

      condition {
        test     = "StringEquals"
        variable = "kms:CallerAccount"
        values   = [data.aws_caller_identity.current.account_id]
      }

      condition {
        test     = "ArnEquals"
        variable = "kms:EncryptionContext:aws:logs:arn"
        values   = var.cloudwatch_log_group_arns
      }
    }
  }

  # Assumptions: SNS performs envelope encryption for the exact topic ARN in
  # the encryption context. SourceAccount and SourceArn prevent another account
  # or another topic from reusing the service-principal grant.
  dynamic "statement" {
    for_each = local.emit_exact_topic_grant ? [1] : []

    content {
      sid    = "AllowSnsTopicEncryption"
      effect = "Allow"
      actions = [
        "kms:Decrypt",
        "kms:GenerateDataKey",
      ]
      resources = ["*"]

      principals {
        type        = "Service"
        identifiers = ["sns.amazonaws.com"]
      }

      condition {
        test     = "StringEquals"
        variable = "aws:SourceAccount"
        values   = [data.aws_caller_identity.current.account_id]
      }

      condition {
        test     = "ArnLike"
        variable = "aws:SourceArn"
        values   = var.sns_topic_arns
      }

      condition {
        test     = "ArnEquals"
        variable = "kms:EncryptionContext:aws:sns:topicArn"
        values   = var.sns_topic_arns
      }
    }
  }

  # Assumptions: CloudWatch alarms publish as a service principal. The source
  # ARN is limited to alarms in this account and Region, while the encryption
  # context still binds cryptographic use to the exact alert topic.
  dynamic "statement" {
    for_each = local.emit_exact_topic_grant ? [1] : []

    content {
      sid    = "AllowCloudWatchAlarmEncryption"
      effect = "Allow"
      actions = [
        "kms:Decrypt",
        "kms:GenerateDataKey",
      ]
      resources = ["*"]

      principals {
        type        = "Service"
        identifiers = ["cloudwatch.amazonaws.com"]
      }

      condition {
        test     = "StringEquals"
        variable = "aws:SourceAccount"
        values   = [data.aws_caller_identity.current.account_id]
      }

      condition {
        test     = "ArnLike"
        variable = "aws:SourceArn"
        values   = ["arn:${data.aws_partition.current.partition}:cloudwatch:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:alarm:*"]
      }

      condition {
        test     = "ArnEquals"
        variable = "kms:EncryptionContext:aws:sns:topicArn"
        values   = var.sns_topic_arns
      }
    }
  }

  dynamic "statement" {
    for_each = local.emit_exact_cloudfront_grant ? [] : [1]

    content {
      sid    = "AllowCloudFrontOriginAccessControlDecryptByPattern"
      effect = "Allow"

      actions   = ["kms:Decrypt"]
      resources = ["*"]

      principals {
        type        = "Service"
        identifiers = ["cloudfront.${data.aws_partition.current.dns_suffix}"]
      }

      # WHY : Assumptions: the first confines the grant to requests the service
      #       makes on behalf of THIS account, and the second to requests whose
      #       source resource is a distribution matching the pattern composed
      #       below. Either alone would be insufficient: without the account
      #       condition the grant is usable for another account's resource, and
      #       without the ARN condition it is usable by any CloudFront feature
      #       rather than by a distribution reading this origin.
      #       Assumptions: `ArnLike` rather than `ArnEquals`, because the default
      #       pattern ends in a wildcard and `ArnEquals` performs no wildcard
      #       expansion -- it would match nothing and deny every asset request.
      #       With exact ARNs supplied, `ArnLike` on a pattern containing no
      #       wildcard is equality, so one operator serves both shapes.
      condition {
        test     = "StringEquals"
        variable = "AWS:SourceAccount"
        values   = [data.aws_caller_identity.current.account_id]
      }

      condition {
        test     = "ArnLike"
        variable = "AWS:SourceArn"
        values   = local.cloudfront_distribution_source_arns
      }
    }
  }

  dynamic "statement" {
    for_each = local.emit_exact_log_group_grant ? [] : [1]

    content {
      sid    = "AllowRegionalCloudWatchLogsEncryption"
      effect = "Allow"

      actions = [
        "kms:Encrypt",
        "kms:Decrypt",
        "kms:ReEncrypt*",
        "kms:GenerateDataKey*",
        "kms:DescribeKey",
      ]

      resources = ["*"]

      principals {
        type        = "Service"
        identifiers = [local.cloudwatch_logs_service_principal]
      }

      # WHY : Assumptions: the regional Logs principal serves every account in
      #       that region. CallerAccount and ViaService confine the grant to this
      #       account's Logs path, while the encryption-context ARN limits it to
      #       log groups in this account and region. Without the service-principal
      #       grant a log group can reference the key but cannot encrypt an event.
      condition {
        test     = "StringEquals"
        variable = "kms:CallerAccount"
        values   = [data.aws_caller_identity.current.account_id]
      }

      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = [local.cloudwatch_logs_service_principal]
      }

      condition {
        test     = "ArnLike"
        variable = "kms:EncryptionContext:aws:logs:arn"
        values   = [local.cloudwatch_log_group_arn_pattern]
      }
    }
  }

  dynamic "statement" {
    for_each = local.emit_exact_topic_grant ? [] : [1]

    content {
      sid    = "AllowCloudWatchAlarmEncryptionForAlertTopic"
      effect = "Allow"

      actions = [
        "kms:GenerateDataKey*",
        "kms:Decrypt",
      ]

      resources = ["*"]

      principals {
        type        = "Service"
        identifiers = ["cloudwatch.${data.aws_partition.current.dns_suffix}"]
      }

      condition {
        test     = "StringEquals"
        variable = "aws:SourceAccount"
        values   = [data.aws_caller_identity.current.account_id]
      }

      condition {
        test     = "ArnLike"
        variable = "aws:SourceArn"
        values   = [local.cloudwatch_alarm_arn_pattern]
      }
    }
  }

  dynamic "statement" {
    for_each = local.emit_exact_topic_grant ? [] : [1]

    content {
      sid    = "AllowSnsEnvelopeEncryptionInThisAccount"
      effect = "Allow"

      actions = [
        "kms:GenerateDataKey*",
        "kms:Decrypt",
      ]

      resources = ["*"]

      principals {
        type        = "Service"
        identifiers = ["sns.${data.aws_partition.current.dns_suffix}"]
      }

      condition {
        test     = "StringEquals"
        variable = "kms:CallerAccount"
        values   = [data.aws_caller_identity.current.account_id]
      }

      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = ["sns.${data.aws_region.current.region}.${data.aws_partition.current.dns_suffix}"]
      }
    }
  }
}

resource "aws_kms_key" "s3" {
  description             = "CardDemo ${var.environment}: customer-managed key for the versioned S3 dataset bucket holding the staged dataset generations produced by the batch chain and the ETL."
  enable_key_rotation     = var.enable_key_rotation
  deletion_window_in_days = var.deletion_window_in_days

  tags = merge(var.tags, {
    Name      = "${local.alias_prefix}s3-${var.environment}"
    DataClass = "s3-datasets"
  })
}

resource "aws_kms_key_policy" "s3" {
  key_id = aws_kms_key.s3.key_id
  policy = data.aws_iam_policy_document.s3.json

  lifecycle {
    precondition {
      condition = alltrue([
        for arn in var.s3_key_user_role_arns :
        can(regex(local.current_account_role_arn_pattern, arn))
      ])
      error_message = "Every s3_key_user_role_arns value must be an exact IAM role ARN in the account applying this module."
    }

    precondition {
      condition = alltrue([
        for arn in var.cloudfront_distribution_arns :
        can(regex(local.current_account_cloudfront_distribution_arn_pattern, arn))
      ])
      error_message = "Every cloudfront_distribution_arns value must name an exact CloudFront distribution in the account applying this module."
    }

    precondition {
      condition = alltrue([
        for arn in var.cloudwatch_log_delivery_source_arns :
        can(regex(local.current_account_log_delivery_source_arn_pattern, arn))
      ])
      error_message = "Every cloudwatch_log_delivery_source_arns value must name an exact CloudWatch Logs delivery source in us-east-1 in the account applying this module."
    }

    precondition {
      condition = alltrue([
        for arn in var.cloudwatch_log_group_arns :
        can(regex(local.current_account_log_group_arn_pattern, arn))
      ])
      error_message = "Every cloudwatch_log_group_arns value must name an exact CloudWatch log group in the account and region applying this module."
    }

    precondition {
      condition = alltrue([
        for arn in var.sns_topic_arns :
        can(regex(local.current_account_sns_topic_arn_pattern, arn))
      ])
      error_message = "Every sns_topic_arns value must name an exact SNS topic in the account and region applying this module."
    }

    precondition {
      condition = (
        length(var.s3_key_user_role_arns) == 0 &&
        length(var.cloudfront_distribution_arns) == 0 &&
        length(var.cloudwatch_log_delivery_source_arns) == 0
      ) || length(var.s3_encryption_context_bucket_arns) > 0
      error_message = "s3_encryption_context_bucket_arns must name at least one exact bucket whenever an S3 role, CloudFront distribution or log-delivery source is trusted."
    }
  }
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
      actions   = local.service_data_key_actions
      resources = ["*"]

      principals {
        type        = "AWS"
        identifiers = var.secrets_key_user_role_arns
      }

      condition {
        test     = "StringEquals"
        variable = "kms:CallerAccount"
        values   = [data.aws_caller_identity.current.account_id]
      }

      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = ["secretsmanager.${data.aws_region.current.region}.amazonaws.com"]
      }

      condition {
        test     = "ArnEquals"
        variable = "kms:EncryptionContext:SecretARN"
        values   = var.secrets_encryption_context_arns
      }
    }
  }
}

resource "aws_kms_key" "secrets" {
  description             = "CardDemo ${var.environment}: customer-managed key for the Secrets Manager entries holding the generated database credential and seed-user passwords, which the stack stores rather than commits."
  enable_key_rotation     = var.enable_key_rotation
  deletion_window_in_days = var.deletion_window_in_days

  tags = merge(var.tags, {
    Name      = "${local.alias_prefix}secrets-${var.environment}"
    DataClass = "secrets-manager"
  })
}

resource "aws_kms_key_policy" "secrets" {
  key_id = aws_kms_key.secrets.key_id
  policy = data.aws_iam_policy_document.secrets.json

  lifecycle {
    precondition {
      condition = alltrue([
        for arn in var.secrets_key_user_role_arns :
        can(regex(local.current_account_role_arn_pattern, arn))
      ])
      error_message = "Every secrets_key_user_role_arns value must be an exact IAM role ARN in the account applying this module."
    }

    precondition {
      condition = alltrue([
        for arn in var.secrets_encryption_context_arns :
        can(regex(local.current_account_secret_arn_pattern, arn))
      ])
      error_message = "Every secrets_encryption_context_arns value must name an exact Secrets Manager secret in the region and account applying this module."
    }

    precondition {
      condition     = length(var.secrets_key_user_role_arns) == 0 || length(var.secrets_encryption_context_arns) > 0
      error_message = "secrets_encryption_context_arns must name at least one exact secret when Secrets Manager key users are configured."
    }
  }
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
# Assumptions: this is one of the two keys whose policy names a service
# principal -- the other being the S3 key, which grants CloudFront a decrypt so
# the application bundle can be served from a private encrypted origin -- and it
# is named here because a specific delivery in this architecture fails silently
# without it. The nightly batch schedule is configured with a
# dead-letter target, which is a queue encrypted with this key; when an
# invocation cannot be delivered, the scheduler service -- not a task role --
# is the principal that must obtain a data key to write the failed invocation
# into that queue. Without this statement the enqueue is denied and the one
# message the dead-letter target exists to preserve is the message that is lost,
# which is the failure mode hardest to notice because it appears only when
# something else has already gone wrong. The grant is deliberately two actions
# rather than the three in `local.service_data_key_actions`: writing a message needs a
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
      actions   = local.service_data_key_actions
      resources = ["*"]

      principals {
        type        = "AWS"
        identifiers = var.sqs_key_user_role_arns
      }

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

  tags = merge(var.tags, {
    Name      = "${local.alias_prefix}sqs-${var.environment}"
    DataClass = "sqs"
  })
}

resource "aws_kms_key_policy" "sqs" {
  key_id = aws_kms_key.sqs.key_id
  policy = data.aws_iam_policy_document.sqs.json

  lifecycle {
    precondition {
      condition = alltrue([
        for arn in var.sqs_key_user_role_arns :
        can(regex(local.current_account_role_arn_pattern, arn))
      ])
      error_message = "Every sqs_key_user_role_arns value must be an exact IAM role ARN in the account applying this module."
    }
  }
}

resource "aws_kms_alias" "sqs" {
  name          = "alias/${local.alias_prefix}sqs-${var.environment}"
  target_key_id = aws_kms_key.sqs.key_id
}


# -----------------------------------------------------------------------------
# Application-enciphered values -- deliberately NOT a fifth key
#
# Refactoring Rationale: a fifth customer-managed key stood here, the one the
# application drew envelope data keys from for the card verification value and
# the two customer identifiers the baseline held in the clear. It has been
# withdrawn. The specified key model is FOUR customer-managed keys with
# rotation, one per data-at-rest domain -- Aurora, S3, Secrets Manager and SQS --
# and that enumeration is exhaustive rather than a starting point. The note that
# stood here argued a fifth key was the smaller deviation; that reasoning is
# withdrawn with the key, because the choice was not between a fifth key and a
# weaker posture. Those envelope operations now draw their data keys from the
# AURORA key, whose policy carries the grant and both encryption-context
# conditions -- see the second dynamic statement in
# data.aws_iam_policy_document.aurora above, where the full rationale for the
# placement, the alternatives weighed and the trade-off accepted are recorded.
#
# Assumptions: nothing about the application-side contract changed except which
# key alias is configured. The values are still enciphered by
# com.carddemo.card.service.CardVerificationValueCipher and
# com.carddemo.account.service.CustomerIdentifierCipher, still under an
# authenticated cipher with a per-value data key, and still separated from each
# other by the carddemo:purpose encryption context. What the environment roots
# publish to those two services is aurora_key_alias_name instead of a fifth
# alias, and the two inputs governing that grant are named
# application_envelope_user_role_arns and application_envelope_context_purposes -
# named for the CALLER rather than for a key, precisely so no reader infers a
# key that does not exist.
# -----------------------------------------------------------------------------
