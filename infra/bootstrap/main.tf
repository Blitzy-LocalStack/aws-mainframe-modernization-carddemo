# =============================================================================
# infra/bootstrap/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Provisions the remote-state backend that every other Terraform root in this
#   repository consumes, and nothing else: a versioned, encrypted S3 bucket
#   holding Terraform state, and a DynamoDB table serialising concurrent state
#   writes against it.
#
#   This root is applied ONCE PER AWS ACCOUNT, ahead of both environment roots
#   under infra/envs/, and torn down after them -- first thing created, last
#   thing destroyed. An operator runs it by hand, because applying it from a CI
#   workflow would make that workflow depend on the very backend it is running
#   in order to create.
#
# Parameters:
#   This file declares no variable. It reads six of the seven that
#   infra/bootstrap/variables.tf declares, each exactly once:
#     var.aws_region                 string. Region component of the composed
#                                    bucket name, in the `locals` block below.
#     var.name_prefix                string. Leading component of both composed
#                                    names.
#     var.state_bucket_name          string, nullable. Operator override for
#                                    the composed bucket name.
#     var.lock_table_name            string, nullable. Operator override for
#                                    the composed table name.
#     var.state_kms_key_arn          string, nullable. Customer-managed key
#                                    encrypting both halves of the backend.
#     var.state_bucket_force_destroy bool. Whether `destroy` may delete the
#                                    bucket while it still holds objects.
#   The seventh, var.tags (map(string)), is deliberately not read here:
#   versions.tf hands it to the provider's `default_tags` block, which merges it
#   into every taggable resource below, so each resource carries only its own
#   `Name` tag.
#
# Return values:
#   This file declares no output. infra/bootstrap/outputs.tf reads the resolved
#   bucket name and the resolved table name off the resources below, so an
#   operator can transcribe them into each environment root's backend
#   configuration.
#
# Errors / Exceptions -- the three failure modes an operator actually meets:
#
#   1. BucketAlreadyExists on the composed bucket name. The S3 bucket namespace
#      is GLOBAL across every AWS account rather than scoped to this one, so a
#      name another account already holds cannot be created here no matter how
#      little of it that account uses, and the holder is invisible from here.
#      The composed name embeds this account's identifier to make the collision
#      improbable rather than impossible; var.state_bucket_name is the escape
#      hatch for when it happens anyway.
#
#   2. BucketNotEmpty on `terraform destroy`. The bucket is versioned, so
#      deleting it requires first deleting every object version AND every
#      delete marker in it. That is an operator step, documented in
#      docs/runbooks/teardown.md, and var.state_bucket_force_destroy waives it
#      -- see the note on `force_destroy` below for why the default is the
#      refusal rather than the waiver.
#
#   3. State is written LOCALLY on the first apply, as terraform.tfstate beside
#      these files, because this root declares no `backend` block -- it is
#      creating the backend, so it cannot yet use one. That local file is the
#      only record that this root made these resources; losing it leaves the
#      bucket and table in place while Terraform no longer knows it created
#      them, so a re-apply reports the collision in (1). Both are recoverable
#      with `terraform import` precisely because the names below are
#      deterministic rather than random.
#
# WHY (non-obvious design decisions):
#   - Refactoring Rationale: the closest baseline analogue to this root is
#     app/jcl/DEFGDGB.jcl, the one-shot out-of-band job defining the
#     generation-data-group bases that each batch job writes into. Because
#     IDCAMS fails when a definition already exists, that deck must guard EVERY
#     define with `IF LASTCC=12 THEN SET MAXCC=0` -- at L29, L35, L41, L47, L53
#     and L59, once per base. A declarative apply needs no such guard and no
#     edit to re-run: a second `terraform apply` reads what exists and
#     converges to a no-op. That matters here specifically because this root is
#     applied by hand and so may well be applied more than once.
#   - Alternatives Considered: configuring versioning, encryption and access
#     control as inline blocks on `aws_s3_bucket`, which is how they were
#     written before AWS provider v4 removed them. versions.tf constrains the
#     provider to `~> 6.56`, where those arguments do not exist at all, so the
#     separate `aws_s3_bucket_*` resources below are the only form available.
#     Every one of them is named `state` so the set reads as one logical
#     object rather than seven unrelated resources.
#   - Assumption: exactly two data sources are declared and both are read.
#     infra/.tflint.hcl enables terraform_unused_declarations with
#     `force = false`, so an unreferenced third would fail the build rather
#     than merely read as untidy.
#   - Trade-off: this root calls no module. A module's own state would live in
#     the bucket being created here, so depending on one would make this root
#     require the backend it exists to produce. The accepted cost is that the
#     bucket hardening below is written out in full rather than shared with
#     infra/modules/s3-datasets, which hardens a bucket for a different purpose
#     and under different retention rules.
# =============================================================================

# The caller's own account identifier, used solely to make the composed bucket
# name unique in a namespace that is not scoped to this account.
#
# WHY this is safe in a credential-free CI check -- Assumption: `terraform
# validate` neither contacts a provider nor evaluates a data source, and the
# gating check over this root is `terraform init -backend=false` followed by
# `terraform validate`. It never plans this root, because planning it would
# need the backend this root has not created yet. So this data source resolves
# only when an operator plans or applies with real credentials, and CI needs
# none. Recorded here because the fix a future reader reaches for -- hard-coding
# an account identifier to "make CI work" -- would commit one account's identity
# to this repository, which the no-secrets constraint in AAP 0.9.1 forbids.
data "aws_caller_identity" "current" {}

locals {
  # Both names are composed here, once, and referenced as `local.*` everywhere
  # below, so neither can drift between the resource that creates it and the
  # resource that refers to it.
  #
  # WHY `coalesce` rather than a required input or a conditional -- Assumption:
  # var.state_bucket_name defaults to null in variables.tf, and `coalesce`
  # returns its first non-null argument, so null is precisely the signal that
  # means "compose it". An operator override therefore wins when supplied and
  # costs nothing when it is absent, which is what keeps this root applyable
  # with no variable file at all.
  #
  # WHY the account identifier is IN this name but NOT in the table name below
  # Assumption: the two services scope names differently, and the asymmetry
  # follows from that rather than being an oversight in either. An S3 bucket
  # name is unique across ALL AWS accounts globally, so `<prefix>-tfstate`
  # alone would collide with any other account that chose the same prefix, and
  # collide unrecoverably. A DynamoDB table name is unique only within one
  # account and one region, so the account identifier would lengthen that name
  # while adding no uniqueness it does not already have.
  #
  # WHY not a `random_id` suffix -- Alternatives Considered: a random suffix
  # would also make the name unique, and was rejected because it makes the name
  # unreproducible. This root keeps LOCAL state, so an operator who loses that
  # file can still derive a deterministic name and `terraform import` the bucket
  # back, whereas a random suffix is knowable only from the state that was lost
  # and the re-apply would try to create a second bucket. It would also require
  # the hashicorp/random provider, which versions.tf omits deliberately.
  #
  # WHY var.aws_region and not `data.aws_region` -- Assumption: versions.tf
  # configures the provider from this same variable and outputs.tf echoes it,
  # so resolving all three from one input makes it impossible for the region
  # embedded in this name, the region the bucket is created in, and the region
  # reported to the environment backends to disagree. `data.aws_region` would
  # instead report whatever the ambient credentials resolved to, which is
  # exactly the value that can differ from the operator's intent in silence.
  state_bucket_name = coalesce(
    var.state_bucket_name,
    "${var.name_prefix}-tfstate-${data.aws_caller_identity.current.account_id}-${var.aws_region}",
  )

  # Composed from the prefix alone, for the namespace reason recorded above.
  lock_table_name = coalesce(var.lock_table_name, "${var.name_prefix}-tfstate-lock")
}

resource "aws_s3_bucket" "state" {
  bucket = local.state_bucket_name

  # WHY this is operator-controlled and defaults to false -- Trade-off: with
  # false a `terraform destroy` here fails with BucketNotEmpty while any state
  # remains, and that failure is the feature rather than a rough edge. Emptying
  # a versioned bucket means deleting every object version and delete marker,
  # which is the deliberate operator step docs/runbooks/teardown.md documents,
  # alongside its standing advice to leave this root in place if the account may
  # be redeployed. Defaulting to true would let one command in this directory
  # discard the state history of every environment in the account with no prompt
  # naming what went. Discarding it stays an affirmative act.
  force_destroy = var.state_bucket_force_destroy

  # WHY only `Name`, when this bucket carries the project's common tags too
  # Trade-off: versions.tf passes var.tags to the provider's `default_tags`
  # block and the provider merges that map into every taggable resource it
  # creates, so repeating it here would be exactly the duplication default_tags
  # exists to remove, and the two copies could drift apart. The accepted cost is
  # locality: the full tag set this bucket ends up carrying is not visible here.
  tags = {
    Name = local.state_bucket_name
  }
}

# WHY versioning is enabled -- the single most consequential argument in this
# file. Refactoring Rationale: it is the recovery mechanism the baseline this
# migration replaces never had. Every `DEFINE FILE` stanza in
# app/csd/CARDDEMO.CSD declares RECOVERY(NONE) with JOURNAL(NO) and
# FWDRECOVLOG(NO) -- at L7/L9, L19/L21, L31/L33, L44/L46, L57/L59 and L70/L72,
# once per file -- so a truncated or wrongly-overwritten VSAM record had no
# prior image to return to and no log to roll forward from. Terraform state is
# the artifact where that gap costs the most: a partial write or a mistaken
# overwrite leaves Terraform's record of the account disagreeing with the
# account itself, and a retained prior version is what turns reconciling that by
# hand into rolling back one object.
# Trade-off, stated because it has a real cost: a versioned bucket cannot be
# deleted until every version and delete marker in it is removed, which is
# precisely the BucketNotEmpty obstacle named in the header and the reason
# docs/runbooks/teardown.md carries a bucket-emptying step at all.
resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  versioning_configuration {
    status = "Enabled"
  }
}

# WHY a customer-managed key is OPTIONAL here, when every datastore at the
# environment level receives one -- Assumption: inside this root the dependency
# runs the wrong way around. Key management belongs to infra/modules/kms, and
# that module's own state lives in the bucket being created here, so a key
# created in this root could never be managed by the module that owns key
# management, and a key from that module cannot encrypt a bucket that has to
# exist before the module can run at all. The circularity has no resolution
# within one apply, so the key becomes an input: an operator who already holds a
# suitable key supplies its ARN, and an account with none still gets an
# encrypted backend on the first apply.
# Trade-off: what SSE-S3 gives up is specific rather than notional -- there is
# no per-key CloudTrail record of decrypt calls against the state objects and no
# key policy restricting which principals may read them, so the bucket policy
# and IAM carry the whole access decision. That gap is confined to this backend;
# environment-level data at rest is encrypted with customer-managed keys through
# infra/modules/kms.
resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      # WHY the algorithm is derived from whether the key is present rather than
      # fixed -- Assumption: the two arguments are not independent. S3 rejects
      # `kms_master_key_id` alongside AES256, and `aws:kms` with no key silently
      # falls back to the AWS-managed aws/s3 key, which is a third posture that
      # neither branch here intends. Deriving the algorithm from the same
      # variable that supplies the key keeps the pair consistent by
      # construction rather than by an operator remembering to set both.
      sse_algorithm = var.state_kms_key_arn == null ? "AES256" : "aws:kms"

      # Assumption: passing null omits the argument entirely rather than
      # sending an empty value, which is exactly what the AES256 branch above
      # requires -- so one unconditional assignment covers both branches and no
      # `dynamic` block or second `rule` is needed to express the choice.
      kms_master_key_id = var.state_kms_key_arn
    }

    # WHY this follows the key rather than being set unconditionally
    # Trade-off: an S3 Bucket Key makes S3 derive one data key per bucket
    # instead of calling KMS once per object, so it reduces KMS request volume
    # and therefore KMS request charges on a bucket that is written on every
    # plan and every apply. Under SSE-S3 no KMS request is made at all, so the
    # setting has nothing to reduce there.
    bucket_key_enabled = var.state_kms_key_arn != null
  }
}

# WHY all four flags are set, with the exposure they close named concretely
# Assumption: a Terraform state file is an inventory. It enumerates every
# resource this account contains together with its identifiers, and it stores
# attribute values verbatim, including values marked sensitive in the
# configuration that produced them. A publicly readable object in this bucket is
# therefore a complete map of the account's infrastructure and a directory of
# where its credentials are kept -- not the disclosure of one value. The four
# flags close both routes to that outcome: two refuse public ACLs and public
# bucket policies at the moment they are written, and two neutralise any that
# are already in place. The same reasoning is why `.terraform/` and `*.tfstate*`
# are excluded from version control at the repository root.
resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# WHY ACLs are switched off rather than merely left unused
# Alternatives Considered: `ObjectWriter`, the other setting this argument
# accepts, keeps ACLs live and lets an uploading principal own the object it
# wrote. That leaves two access-control mechanisms in force on the most
# sensitive bucket in the account, and an object whose ACL disagrees with the
# bucket policy is resolved by whichever grants more. `BucketOwnerEnforced`
# disables ACLs outright, so access is decided by IAM and the bucket policy
# alone -- one mechanism to read, and no per-object exception waiting to be
# discovered.
resource "aws_s3_bucket_ownership_controls" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# WHY this configuration holds exactly one rule, and what must never be added to
# it -- Alternatives Considered: a noncurrent-version expiration rule belongs in
# infra/modules/s3-datasets, where retaining five noncurrent versions reproduces
# the LIMIT(5) SCRATCH generation limit the baseline's generation-data-group
# bases carry (app/jcl/DEFGDGB.jcl L26, L32, L38, L44, L50 and L56). Copying
# that rule into this bucket would look like consistency and would be its
# opposite: dataset generations are a rolling window by design, whereas the
# state history here is the only record of what was provisioned and when, so
# expiring it would delete the audit trail the versioning above exists to keep.
# No rule in this resource may expire, transition or otherwise remove a
# noncurrent object version.
# WHY the one rule that IS present -- Trade-off: an incomplete multipart upload
# leaves parts that are billed as storage but do not appear in an object
# listing, so they accumulate unnoticed and are found only by asking for them.
# Terraform's S3 backend writes state in a single request, so a stranded upload
# here comes from an interrupted operator copy rather than from normal
# operation. That is why the window is a week rather than a day: it leaves time
# to notice and inspect a stuck upload before its parts are reclaimed.
resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    id     = "abort-incomplete-multipart-upload"
    status = "Enabled"

    # Assumption: this resource requires a filter or a prefix on every rule, and
    # an empty filter is the form that scopes one to every object. Omitting the
    # block is not the same thing -- it leaves the rule unscoped and rejected --
    # and no narrower scope is meaningful in a bucket holding nothing but state.
    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}


# WHY the policy is built with this data source rather than `jsonencode` or a
# heredoc -- Alternatives Considered: a hand-built JSON string was rejected
# because it defers every structural mistake to the AWS API at apply time, where
# a misspelled condition operator or a missing wrapper element surfaces as a
# malformed-policy error naming the bucket rather than as the line that caused
# it. This data source renders the document at plan time, refuses a statement it
# cannot construct, and keeps the statement legible as HCL.
data "aws_iam_policy_document" "state_bucket" {
  # WHY TLS is enforced by the bucket rather than trusted from the client
  # Assumption: state crosses the network on every plan and every apply, from
  # developer workstations and from CI runners alike, and the bucket cannot know
  # how any of those clients was configured -- an endpoint override, an outdated
  # SDK or an intercepting proxy could each produce a plaintext request. A Deny
  # attached to the resource is the only control that holds whatever the caller
  # does, because it is evaluated after the request arrives rather than trusting
  # a setting on the machine that sent it.
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]

    # WHY both ARNs and not the bucket alone -- Assumption: the bucket ARN
    # matches bucket-level actions only. An object-level action such as
    # GetObject or PutObject is authorised against the object's own ARN, so a
    # Deny listing the bucket by itself would leave every state read and write
    # unmatched -- which is the entire traffic this statement exists to cover.
    resources = [
      aws_s3_bucket.state.arn,
      "${aws_s3_bucket.state.arn}/*",
    ]

    # WHY a wildcard principal here is not a public policy, which it resembles
    # Assumption: `block_public_policy = true` above rejects a policy that
    # GRANTS access to everyone, and this statement grants nothing -- it is an
    # explicit Deny. That is also what makes the wildcard the correct scope: the
    # condition below is the thing being matched, and it has to be matched for
    # every caller without exception. The two settings therefore coexist rather
    # than contradicting one another, which is worth stating because they read
    # as if one of them must be broken.
    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  # WHY there is deliberately no second statement denying an unencrypted PUT
  # Alternatives Considered: a Deny on `s3:PutObject` when the
  # `s3:x-amz-server-side-encryption` header is absent is the obvious companion
  # to the statement above, and it would brick this backend. Terraform's S3
  # backend does not send that header -- it relies on the bucket's DEFAULT
  # encryption, configured above -- so the statement would reject every state
  # write the backend attempts, on a bucket whose objects are encrypted anyway.
  # Recorded because the mistake looks precisely like hardening.
}

resource "aws_s3_bucket_policy" "state" {
  bucket = aws_s3_bucket.state.id
  policy = data.aws_iam_policy_document.state_bucket.json

  # WHY the ordering is declared rather than left to be inferred -- Assumption:
  # Terraform derives order from references, and this resource references the
  # bucket but not the public-access block, so on a first apply the two are free
  # to run concurrently. `block_public_policy` is evaluated at the moment a
  # policy is written, so a policy that landed first would be assessed under the
  # account's default public-access settings instead of this bucket's. Naming
  # the dependency makes the sequence deterministic rather than relying on the
  # Deny above never tripping that check.
  depends_on = [aws_s3_bucket_public_access_block.state]
}

# WHY a DynamoDB lock table at all, when the S3 backend can now lock without one
# Alternatives Considered: Terraform's S3 backend supports two locking
# mechanisms. The S3-native one is the `use_lockfile` backend argument, which
# defaults to false; the DynamoDB one is the `dynamodb_table` argument, which
# HashiCorp documents as deprecated and slated for removal in a future minor
# release, naming use_lockfile as its replacement. This table is provisioned
# because a DynamoDB lock table is a mandated deliverable of this root -- AAP
# 0.2.1.1, 0.4.1.6 and 0.5.1.12 each name it -- and that mandate decides it.
# Assumption: the choice forecloses nothing, because the two mechanisms may be
# configured simultaneously, which is the documented migration path, so a
# backend configuration that additionally sets use_lockfile requires no change
# to this root.
resource "aws_dynamodb_table" "state_lock" {
  name = local.lock_table_name

  # WHY on-demand rather than provisioned capacity -- Trade-off: this table
  # holds at most one small item per concurrent Terraform operation, for as long
  # as that operation runs, so its traffic is a handful of tiny reads and writes
  # per apply arriving in bursts with no predictable shape. Provisioned capacity
  # would mean paying for units that sit idle between applies and choosing a
  # number with no measurement behind it; per-request billing matches the shape
  # the workload actually has.
  billing_mode = "PAY_PER_REQUEST"

  # WHY the key is named LockID, which looks like a naming choice and is not
  # Assumption: this is an external contract. Terraform's S3 backend requires
  # the lock table to have a partition key named exactly `LockID` of type
  # String, and it writes and reads the lock item under that key. Renaming it to
  # satisfy a naming standard would leave a table that provisions cleanly and
  # locks nothing, and the failure would surface in the environment roots rather
  # than here. It is not this file's to choose.
  hash_key = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }

  # WHY this is on, without overclaiming what it buys -- Trade-off: continuous
  # backups on a table holding one short-lived item cost very little, and they
  # give a restore path if the table is deleted or emptied by accident. They do
  # not protect the state itself, which lives in the bucket above, and a lock
  # item lost on its own is cleared with `terraform force-unlock` rather than
  # restored. It is cheap insurance against a mistake on the table, and the
  # policy scan over this tree checks for it.
  point_in_time_recovery {
    enabled = true
  }

  # WHY the block is emitted only when a key is supplied, rather than always
  # with `enabled = false` -- Assumption: a DynamoDB table is ALWAYS encrypted
  # at rest. This block does not decide whether encryption happens, it decides
  # whose key performs it: omitted, the table uses an AWS-owned key; present
  # with a key ARN, it uses that customer-managed key. Writing `enabled = false`
  # explicitly would read to a human reviewer, and to a policy scanner, as
  # encryption being switched off -- a state this resource does not have.
  dynamic "server_side_encryption" {
    for_each = var.state_kms_key_arn == null ? [] : [1]

    content {
      enabled     = true
      kms_key_arn = var.state_kms_key_arn
    }
  }

  # Only `Name`, for the default_tags reason recorded on the bucket above.
  tags = {
    Name = local.lock_table_name
  }
}

# -----------------------------------------------------------------------------
# Deliberate absences.
#
# Six things a reader would reasonably expect on a pair of resources holding
# Terraform state are not here, and the last three are each raised as a finding
# by the policy scan over this tree. Every one is a decision with a stated
# reason rather than an omission, and none is silenced with a suppression
# annotation -- a scanner finding that the design answers is answered here, in
# prose, where the design is:
#
#   - No `lifecycle { prevent_destroy = true }`, on either the bucket or the
#     table. Alternatives Considered: it would refuse an accidental destroy of
#     the backend, which is genuinely the outcome one wants -- and it would do
#     so by raising an error docs/runbooks/teardown.md does not describe,
#     leaving two artifacts in this repository documenting incompatible
#     behaviour and an operator following the runbook facing an unexplained
#     refusal with nothing to consult. The versioned bucket already supplies a
#     deliberate stop at the same point: destroy fails with BucketNotEmpty
#     until someone empties it on purpose.
#
#   - No `deletion_protection_enabled` on the table. Alternatives Considered:
#     the same reasoning applies, and the outcome is worse here because the
#     table has no versioning obstacle of its own -- the flag would be the only
#     thing refusing the teardown, so it would block the runbook's documented
#     step rather than a mistake, and the operator would have to edit this file
#     to complete a procedure the runbook says is complete.
#
#   - No `ttl` block on the table. Alternatives Considered: a time-to-live
#     attribute would auto-clear a lock orphaned by a crashed apply, and was
#     rejected because it cannot tell an orphan from an apply that is merely
#     slow. Expiring the item under a running operation releases a lock that is
#     still held, converting a visible, diagnosable stall into an invisible
#     concurrent-write hazard on the state the lock protects.
#     `terraform force-unlock`, run by an operator who has checked who holds
#     the lock, keeps that judgement with the person able to make it.
#
#   - No S3 access logging on the bucket. Alternatives Considered: it requires a
#     destination bucket, which would itself need a logging destination -- the
#     same regress this root exists to break -- and that bucket would be created
#     here, outside the reach of the modules that own bucket configuration.
#     CloudTrail S3 data events record the same access at account scope, are
#     configured outside this root, and add nothing to bootstrap.
#
#   - No cross-region replication on the bucket. Alternatives Considered: it
#     would place a second copy of every state version in another region, and it
#     is declined because the target topology for this migration is deliberately
#     single-region across three availability zones, with multi-region and
#     disaster-recovery topology out of scope by AAP 0.2.2. Adding replication
#     to the one bucket that sits outside every module would make the state
#     backend the sole multi-region component of an otherwise single-region
#     system -- a topology inconsistency, and a destination bucket and
#     replication role this root would also have to own.
#
#   - No event notification on the bucket. Alternatives Considered: it would
#     publish object-created events, and nothing in this architecture subscribes
#     to them -- no workflow reacts to a state write, and CloudTrail already
#     records who wrote what. Configuring one means creating a topic, queue or
#     function here to receive it, which is the module dependency this root
#     cannot take. A notification with no consumer is a resource to maintain and
#     a permission to grant in exchange for nothing observable.
# -----------------------------------------------------------------------------
