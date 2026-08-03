# =============================================================================
# infra/modules/s3-datasets/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The public input surface of the `s3-datasets` module: twelve variables,
#   every one explicitly typed and described. The module provisions the single
#   versioned, customer-managed-key-encrypted S3 bucket that replaces the
#   mainframe baseline's generation data groups, carrying one prefix and one
#   noncurrent-version lifecycle rule for each of the TEN generation-dataset
#   families, plus the TWO non-generation statement artifacts.
#
#   The environment axis is deliberately narrow. Only retention and
#   lifecycle-transition values differ between the dev and prod roots; the
#   prefix topology NEVER varies between them. A prefix present in one
#   environment and absent in the other would make a batch step that succeeds
#   in dev fail in prod for a reason invisible in the job definition, so
#   `dataset_families` and `non_generation_prefixes` carry complete defaults
#   and are not expected to be overridden per environment, while the four
#   lifecycle knobs below exist precisely so they can be.
#
#   WARNING: the bucket configured from here is NOT the Terraform remote-state
#   bucket. Remote state and its lock table belong solely to infra/bootstrap,
#   which is applied once per AWS account out of band. Both are versioned,
#   encrypted S3 buckets, which is exactly why they are easy to confuse -- and
#   why no variable below names a state bucket or a lock table, and why none
#   should be added.
#
# Parameters:
#   The twelve `variable` blocks below ARE this file's parameters, so the
#   name, type and description obligation is discharged on each block directly
#   rather than duplicated into a list here that could drift from it. In
#   declaration order: name_prefix, environment, kms_key_arn,
#   dataset_families, non_generation_prefixes, noncurrent_version_retention,
#   noncurrent_version_transition_days,
#   noncurrent_version_transition_storage_class,
#   abort_incomplete_multipart_upload_days, access_log_bucket_name,
#   force_destroy and tags. Exactly two of them -- `environment` and
#   `kms_key_arn` -- have no default and are therefore required of the caller.
#
# Return values:
#   None. This file returns nothing: it IS the module's input contract. The
#   module's return surface is published by outputs.tf -- the bucket name and
#   ARN and the resolved prefix map that a Step Functions state or an ETL
#   loader needs in order to address a dataset generation. Nothing declared
#   below may be read as an output by a caller.
#
# Errors:
#   `terraform validate` fails before any plan is produced when a caller omits
#   `environment` or `kms_key_arn`, because neither has a default. Four
#   variables carry `validation` blocks that reject a value at plan time with
#   a stated message: `name_prefix` and `environment` on charset and length,
#   which together guarantee the composed bucket name cannot exceed the S3
#   63-character limit; `noncurrent_version_retention` and
#   `abort_incomplete_multipart_upload_days` on being at least one, because
#   zero is destructive rather than neutral in both cases.
#   A misspelled key in `dataset_families` is deliberately NOT an error here:
#   it is accepted and becomes a prefix that nothing ever writes to, which is
#   why every entry carries its baseline lineage inside its own `description`
#   where a reviewer will see it.
#
# WHY (non-obvious design decisions):
#   - Assumption: TEN generation families, not six. Six is what
#     app/jcl/DEFGDGB.jcl yields on its own, and reading only that file is the
#     trap; three more are defined in app/jcl/DEFGDGD.jcl and one in
#     app/jcl/DALYREJS.jcl. Provisioning six would silently lose four
#     retention policies -- the affected batch steps would still write
#     objects, to a prefix with no lifecycle rule, so nothing would fail and
#     generations would accumulate without limit.
#   - Trade-off: the family and prefix inventories live in variable DEFAULTS
#     rather than as literals inside main.tf. The cost is a large default
#     block in a file that is otherwise pure contract. What it buys is that
#     the inventory is legible to every caller and is rendered into the
#     generated README, and that per-family retention can be overridden for
#     one environment without altering the topology.
#   - Assumption: this file is consumed by main.tf and outputs.tf in this same
#     directory and by nothing else. Every variable below is read by one of
#     them, because `terraform_unused_declarations` in infra/.tflint.hcl is
#     gating: an input added here speculatively would fail the build rather
#     than sit harmlessly idle.
# =============================================================================

# -----------------------------------------------------------------------------
# Naming and identity.
# -----------------------------------------------------------------------------

variable "name_prefix" {
  description = "Leading token of the bucket name, which main.tf composes as <name_prefix>-datasets-<environment>-<account-id>-<region>. This is what distinguishes the CardDemo dataset bucket from every other bucket in the account, and it is also the stem the module derives its resource names and tags from."
  type        = string
  default     = "carddemo"

  # Assumption: an S3 bucket name may not exceed 63 characters, and the
  # composed form above spends 38 of them before this prefix is added -- ten
  # for the literal `-datasets-`, twelve for the account id, up to fourteen for
  # a region name, and two further hyphens. That leaves 25 characters to divide
  # between this prefix and `environment`, so capping each at twelve makes the
  # worst case 62 and therefore provably legal. A sixteen-character cap on
  # either one reaches 66 and would produce a name S3 rejects during apply,
  # long after review; constraining the input turns that into a plan-time
  # failure with a message that names the cause.
  # This mirrors the identical constraint on the `name_prefix` in
  # infra/bootstrap/variables.tf, which bounds its own prefix for exactly the
  # same budget reason -- the symmetry is intentional, so the two files reject
  # the same inputs.
  # Alternatives Considered: truncating an over-long prefix inside main.tf
  # instead of rejecting it. Rejected -- truncation silently renames the
  # bucket, and two prefixes differing only after the cut would collide on a
  # single name, which for a versioned dataset bucket means two environments
  # writing generations over each other.
  validation {
    condition     = can(regex("^[a-z]([a-z0-9-]{0,10}[a-z0-9])?$", var.name_prefix))
    error_message = "name_prefix must be 1-12 characters, start with a lowercase letter, end with a lowercase letter or digit, and contain only lowercase letters, digits and hyphens."
  }
}

variable "environment" {
  description = "Deployment environment that owns this bucket, supplied by the calling root: infra/envs/dev passes dev and infra/envs/prod passes prod. It appears verbatim in the composed bucket name, which is what stops two environments in one account resolving to the same bucket, and it is the only axis along which this module's inputs are expected to differ."
  type        = string

  # Alternatives Considered: restricting this to exactly ["dev", "prod"] with a
  # `contains` condition, which is the obvious way to make the two supported
  # roots the only legal values. Rejected -- the requirement is that the
  # package be parameterized by environment "at least dev and prod", so the set
  # is explicitly open, and a two-value enum would reject a third root such as
  # a staging or ephemeral review environment that the plan permits. The
  # charset and length check below is therefore the strongest constraint
  # available that does not forbid something the plan allows.
  # Assumption: the twelve-character cap is not cosmetic. It is the other half
  # of the 63-character bucket-name budget computed on `name_prefix` above, so
  # relaxing one cap without recomputing the other reintroduces the overflow
  # that budget exists to rule out.
  validation {
    condition     = can(regex("^[a-z]([a-z0-9-]{0,10}[a-z0-9])?$", var.environment))
    error_message = "environment must be 1-12 characters, start with a lowercase letter, end with a lowercase letter or digit, and contain only lowercase letters, digits and hyphens."
  }
}

variable "kms_key_arn" {
  description = "ARN of the S3 customer-managed KMS key produced by infra/modules/kms, used as the SSE-KMS key for every object written to this bucket. Required, because the module offers no unencrypted mode."
  type        = string

  # Refactoring Rationale: the storage this bucket replaces had neither
  # encryption nor recovery. All eight file resources in app/csd/CARDDEMO.CSD
  # are defined RECOVERY(NONE) with JOURNAL(NO), so a dataset generation on the
  # mainframe was a plain unencrypted VSAM cluster with no journal to recover
  # it from. A rotating customer-managed key plus bucket versioning answers
  # both halves of that, and making this input REQUIRED is what makes the
  # encryption half unavoidable: there is no value a caller can leave out that
  # yields an unencrypted bucket.
  # Alternatives Considered: making this nullable and falling back to SSE-S3
  # when null. Rejected here, although it is the correct choice one directory
  # over -- infra/bootstrap cannot require a customer-managed key because it
  # runs BEFORE the kms module exists, so requiring one there would be
  # circular. By the time this module is called that key is already
  # provisioned, so a silent fallback would downgrade encryption from a
  # customer-managed key to an AWS-managed one with nothing in the plan output
  # drawing attention to the change. The asymmetry between the two files is
  # deliberate, not an oversight in either one.
  # No default is set, and in particular no key ARN literal appears here: a
  # key identifier is account-specific, so committing one would both break
  # every other account and put an infrastructure identifier in source.
}

# -----------------------------------------------------------------------------
# Dataset inventory. These two maps ARE the prefix topology, and the topology is
# the one thing that must not vary between environments.
# -----------------------------------------------------------------------------

variable "dataset_families" {
  description = "Generation-dataset families to provision a prefix and a noncurrent-version lifecycle rule for, keyed by the S3-safe family name main.tf uses as the dataset path segment. Each value carries: domain, the bounded context owning the data, which becomes the leading path segment; description, recording the baseline generation-data-group base the family replaces and the JCL line defining it; and noncurrent_versions, an optional per-family override of noncurrent_version_retention that is left unset on every entry in the default."

  type = map(object({
    domain              = string
    description         = string
    noncurrent_versions = optional(number)
  }))

  # Assumption: there are TEN generation-dataset bases in the baseline, not six.
  # This is the highest-risk value in the module, because six is what a reader
  # gets from app/jcl/DEFGDGB.jcl alone and that file looks complete -- it is
  # headed "DEFINE GDG BASES NEEDED BY CARDDEMO PROJECT" and defines six bases
  # in a single IDCAMS step. The other four sit elsewhere: three in
  # app/jcl/DEFGDGD.jcl (L28, L51, L74) and one in app/jcl/DALYREJS.jcl (L25).
  # An exhaustive search of the baseline for DEFINE GENERATIONDATAGROUP matches
  # only four files -- those three plus app/jcl/REPTFILE.jcl -- and yields
  # eleven DEFINE statements over TEN DISTINCT base names, because TRANREPT is
  # defined twice (see the override note below). Provisioning six would
  # silently lose four retention policies: the four affected batch steps would
  # still write their objects, into a prefix carrying no lifecycle rule, so
  # nothing would fail and generations would accumulate without limit.
  # The count is cross-checked by two sibling artifacts that publish the same
  # ten independently -- docs/architecture/batch-orchestration.md and
  # data-migration/README.md -- so all three must agree, and a change made here
  # but not there leaves whichever is out of step defective.
  #
  # Assumption: the `domain` values are derived from the owning bounded
  # context's schema rather than invented. transaction-service owns the
  # `ledger` schema, reference-service owns `reference`, and reporting-service
  # owns no tables but produces the 133-column report. The split is therefore
  # SIX ledger + THREE reference + ONE reporting = TEN, written out so a
  # reader can verify the count by adding it up instead of trusting a comment.
  #
  # Trade-off: this inventory is a variable default rather than a hard-coded
  # `for_each` list inside main.tf. The cost is a long default block in a
  # contract file; what it buys is that the inventory is readable by every
  # caller and appears in the generated README, and that a single family's
  # retention can be overridden for one environment WITHOUT changing the
  # prefix topology, which has to stay identical across environments.
  # Hard-coding it in main.tf was rejected because it would place a
  # behavioural contract in the one file no consumer of the module reads.
  #
  # Assumption: `noncurrent_versions` is deliberately left UNSET on all ten, so
  # every family inherits `noncurrent_version_retention` below. The field
  # exists because the baseline is not self-consistent with itself:
  # app/jcl/REPTFILE.jcl:L25-L28 holds a SECOND, standalone DEFINE
  # GENERATIONDATAGROUP for the SAME AWS.M2.CARDDEMO.TRANREPT base already
  # defined at app/jcl/DEFGDGB.jcl:L37, but with LIMIT(10) and no SCRATCH. The
  # distinct base NAMES are therefore still ten; TRANREPT simply has two
  # competing definitions. The three sources treated as authoritative --
  # DEFGDGB.jcl L25-L57, DEFGDGD.jcl L28-L76 and DALYREJS.jcl L24-L26 --
  # define all ten at LIMIT(5), so five is applied uniformly and `tranrept` is
  # NOT set to ten here. The option exists so that variant stays expressible
  # from a caller if the discrepancy is ever resolved the other way, without a
  # topology change; recording the conflict beats silently adopting one of two
  # contradictory baseline definitions.
  default = {
    # Six families from app/jcl/DEFGDGB.jcl: five ledger and one reporting.
    "transact-bkup" = {
      domain      = "ledger"
      description = "Transaction master backup generations. Replaces GDG base AWS.M2.CARDDEMO.TRANSACT.BKUP defined at app/jcl/DEFGDGB.jcl:L25 with LIMIT(5) at L26 and SCRATCH at L27; written as (+1) by app/jcl/TRANBKP.jcl:L33 at LRECL=350 and read back as (0) by app/jcl/COMBTRAN.jcl:L24."
    }
    "transact-daly" = {
      domain      = "ledger"
      description = "Daily transaction generations staged for posting. Replaces GDG base AWS.M2.CARDDEMO.TRANSACT.DALY defined at app/jcl/DEFGDGB.jcl:L31 with LIMIT(5) at L32 and SCRATCH at L33; written as (+1) by app/jcl/TRANREPT.jcl:L55."
    }
    "tranrept" = {
      domain      = "reporting"
      description = "Transaction report generations, the 133-column fixed-width output. Replaces GDG base AWS.M2.CARDDEMO.TRANREPT defined at app/jcl/DEFGDGB.jcl:L37 with LIMIT(5) at L38 and SCRATCH at L39; written as (+1) by app/jcl/TRANREPT.jcl:L80 at LRECL=133. A second, conflicting definition of the same base exists at app/jcl/REPTFILE.jcl:L25-L28 with LIMIT(10) and no SCRATCH; the LIMIT(5) definition is the one applied."
    }
    "tcatbalf-bkup" = {
      domain      = "ledger"
      description = "Transaction-category-balance backup generations. Replaces GDG base AWS.M2.CARDDEMO.TCATBALF.BKUP defined at app/jcl/DEFGDGB.jcl:L43 with LIMIT(5) at L44 and SCRATCH at L45."
    }
    "systran" = {
      domain      = "ledger"
      description = "System-generated transaction generations, the interest and fee transactions the interest run emits. Replaces GDG base AWS.M2.CARDDEMO.SYSTRAN defined at app/jcl/DEFGDGB.jcl:L49 with LIMIT(5) at L50 and SCRATCH at L51; read back as (0) by app/jcl/COMBTRAN.jcl:L26."
    }
    "transact-combined" = {
      domain      = "ledger"
      description = "Combined transaction generations, the merge of the transaction backup and the system transactions. Replaces GDG base AWS.M2.CARDDEMO.TRANSACT.COMBINED defined at app/jcl/DEFGDGB.jcl:L55 with LIMIT(5) at L56 and SCRATCH at L57; written as (+1) by app/jcl/COMBTRAN.jcl:L37."
    }

    # Three reference families from app/jcl/DEFGDGD.jcl.
    "trantype-bkup" = {
      domain      = "reference"
      description = "Transaction-type reference backup generations. Replaces GDG base AWS.M2.CARDDEMO.TRANTYPE.BKUP defined at app/jcl/DEFGDGD.jcl:L28 with LIMIT(5) at L29 and SCRATCH at L30; first generation loaded as (+1) at app/jcl/DEFGDGD.jcl:L40 at LRECL=60."
    }
    "trancatg-bkup" = {
      domain      = "reference"
      description = "Transaction-category reference backup generations. Replaces GDG base AWS.M2.CARDDEMO.TRANCATG.PS.BKUP defined at app/jcl/DEFGDGD.jcl:L51 with LIMIT(5) at L52 and SCRATCH at L53; first generation loaded as (+1) at app/jcl/DEFGDGD.jcl:L63 at LRECL=60."
    }
    "discgrp-bkup" = {
      domain      = "reference"
      description = "Disclosure-group reference backup generations, the interest-rate table the interest run reads. Replaces GDG base AWS.M2.CARDDEMO.DISCGRP.BKUP defined at app/jcl/DEFGDGD.jcl:L74 with LIMIT(5) at L75 and SCRATCH at L76; first generation loaded as (+1) at app/jcl/DEFGDGD.jcl:L86 at LRECL=50."
    }

    # One ledger family from app/jcl/DALYREJS.jcl -- the tenth, and the one
    # most easily missed, because it is defined in a job named for the reject
    # dataset itself rather than in either of the two DEFGDG* jobs.
    "dalyrejs" = {
      domain      = "ledger"
      description = "Daily transaction reject-stream generations, carrying the reject record the posting run writes for each of the four documented reject reasons. Replaces GDG base AWS.M2.CARDDEMO.DALYREJS named at app/jcl/DALYREJS.jcl:L25 inside the DEFINE opened at L24, with LIMIT(5) at L26 and SCRATCH at L27."
    }
  }
}

variable "non_generation_prefixes" {
  description = "Prefixes for baseline datasets that are NOT generation data groups, keyed by the S3-safe name main.tf uses as the dataset path segment. Each value carries a domain, the owning bounded context, and a description recording the baseline dataset and the JCL line that writes it. Held separately from dataset_families so these can never be counted as additional generation families."

  type = map(object({
    domain      = string
    description = string
  }))

  # Assumption: neither statement artifact has a GENERATIONDATAGROUP base
  # anywhere in the baseline. An exhaustive search for DEFINE
  # GENERATIONDATAGROUP matches only four files -- app/jcl/DEFGDGB.jcl,
  # app/jcl/DEFGDGD.jcl, app/jcl/DALYREJS.jcl and app/jcl/REPTFILE.jcl -- and
  # not one of them defines a statement base. Both are plain sequential
  # datasets. They live in their own variable precisely so they cannot be
  # miscounted as an eleventh generation family, which is the mistake the
  # separation exists to prevent: the ten-family count is asserted in the AAP
  # and published by two sibling documents, so an inventory of twelve prefixes
  # in one map would put this module out of step with all of them.
  # They still need prefixes, because the GenerateStatements batch state writes
  # both the plain-text and the HTML statement to S3. Bucket versioning is
  # bucket-wide and cannot be enabled per prefix, so these objects acquire
  # noncurrent versions too and get a noncurrent-version rule as a consequence
  # -- but that rule is ordinary version hygiene and is NOT the LIMIT(5)
  # SCRATCH analogue described on `noncurrent_version_retention` below. Reading
  # it as a generation limit would invent a generation contract the baseline
  # never had for these two datasets.
  #
  # Trade-off: the baseline's own pattern here was delete-then-recreate -- an
  # IEFBR14 step at app/jcl/CREASTMT.JCL:L66-L75 deletes both datasets before
  # CBSTM03A writes them fresh at L87-L96. Object versioning expresses that as
  # a new current version with the previous one becoming noncurrent, so the
  # target needs no delete step at all: the old statement is retained rather
  # than scratched, which is strictly more recoverable than the baseline and
  # costs only the retained versions.
  default = {
    "statement-text" = {
      domain      = "reporting"
      description = "Plain-text customer statements. Replaces sequential dataset AWS.M2.CARDDEMO.STATEMNT.PS, deleted by the IEFBR14 step at app/jcl/CREASTMT.JCL:L75 and rewritten by CBSTM03A at L91 with DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB) declared at L89. Not a generation data group: no GENERATIONDATAGROUP base for it exists in the baseline."
    }
    "statement-html" = {
      domain      = "reporting"
      description = "HTML customer statements. Replaces sequential dataset AWS.M2.CARDDEMO.STATEMNT.HTML, deleted by the IEFBR14 step at app/jcl/CREASTMT.JCL:L71 and rewritten by CBSTM03A at L96 with DCB=(LRECL=100,BLKSIZE=800,RECFM=FB) declared at L94. Not a generation data group: no GENERATIONDATAGROUP base for it exists in the baseline."
    }
  }
}

# -----------------------------------------------------------------------------
# Lifecycle and retention. These four values, and only these four, are what the
# dev and prod roots are expected to set differently.
# -----------------------------------------------------------------------------

variable "noncurrent_version_retention" {
  description = "Number of noncurrent object versions retained per prefix before the oldest is expired. This is the S3 expression of the baseline's LIMIT(5) SCRATCH generation limit, so the default of five reproduces mainframe generation retention exactly; a root may raise it to keep more history."
  type        = number
  default     = 5

  # Assumption: all ten baseline bases are defined LIMIT(5) with SCRATCH --
  # app/jcl/DEFGDGB.jcl:L26, L32, L38, L44, L50 and L56; app/jcl/DEFGDGD.jcl:L29,
  # L52 and L75; and app/jcl/DALYREJS.jcl:L26. The two keywords carry separate
  # meanings and both are needed to justify this default. LIMIT(5) caps the
  # group at five generations, and SCRATCH makes the generation that rolls off
  # physically deleted rather than merely uncatalogued -- without SCRATCH the
  # rolled-off dataset would survive on disk unnamed, which is not what the
  # baseline does. Bucket versioning plus a noncurrent-version expiry that
  # keeps exactly five is the pair's joint equivalent: versioning supplies the
  # generation stack, and the expiry supplies both the cap and the deletion.
  #
  # Assumption: a value of zero would expire every version the moment it
  # stopped being current, collapsing a five-generation group to a single
  # object and destroying the retained history that LIMIT(5) exists to provide.
  # That history is load-bearing rather than decorative, because the batch
  # chain addresses generations RELATIVELY rather than by absolute name --
  # app/jcl/COMBTRAN.jcl:L24 and L26 read (0), the current generation, while
  # app/jcl/TRANREPT.jcl:L39 reads back the (+1) written earlier in the same
  # job at L33 -- so a rerun or an investigation that needs the preceding
  # generation would find nothing to read. The floor of one is therefore a
  # correctness bound, not a style preference.
  validation {
    condition     = var.noncurrent_version_retention >= 1
    error_message = "noncurrent_version_retention must be at least 1; zero would expire every noncurrent version immediately and destroy the generation history the batch chain reads."
  }
}

variable "noncurrent_version_transition_days" {
  description = "Age in days at which a noncurrent version moves to the storage class named by noncurrent_version_transition_storage_class. Null disables the transition entirely, leaving noncurrent versions in the class they were written to until they expire."
  type        = number
  default     = null

  # Trade-off: this is one of the two lifecycle knobs the environment roots are
  # expected to set differently, the other being the retention count above. It
  # defaults to null -- no transition -- because dev recreates these datasets
  # constantly and every transitioned object incurs a per-object transition
  # request plus a minimum-duration charge that is billed even if the version
  # is expired first, so in dev a transition can cost more than the storage it
  # saves. A prod root with a slower generation turnover can set a value and
  # come out ahead.
  # Alternatives Considered: giving this a numeric default so a transition
  # applies everywhere unless disabled. Rejected -- a numeric default would
  # silently move objects to another storage class in every environment that
  # never asked for it, and the first symptom would be a retrieval-cost line
  # item rather than anything visible in the plan. Nullable with a null default
  # inverts that: the transition exists only where a root opted in.
}

variable "noncurrent_version_transition_storage_class" {
  description = "Storage class a noncurrent version transitions into, read only when noncurrent_version_transition_days is non-null. Ignored entirely while that value is null."
  type        = string
  default     = "STANDARD_IA"

  # Assumption: a rolled-off dataset generation is read only during an
  # investigation or a rerun, so it is genuinely infrequent-access data, but
  # when it IS read the read is interactive and someone is waiting on it.
  # Infrequent Access is the cheapest class that still serves a first byte in
  # milliseconds, which makes it the one class matching both halves of that
  # profile.
  # Alternatives Considered: an archival class, which stores the same object
  # for materially less. Rejected because retrieval is no longer immediate --
  # an archived generation must be restored before it can be read at all, which
  # turns "read yesterday's backup" from an object GET into a restore request
  # with a wait, exactly when someone is diagnosing a failed batch run. The
  # saving is real and is declined deliberately, in favour of keeping a restore
  # as fast as reading the current generation.
}

variable "abort_incomplete_multipart_upload_days" {
  description = "Age in days after which an incomplete multipart upload is aborted and its already-uploaded parts deleted. Applies to the whole bucket rather than to one prefix."
  type        = number
  default     = 7

  # Assumption: the batch exports write whole generations in one object, large
  # enough that the SDK is expected to choose a multipart upload -- the
  # transaction backup carries 350-byte records (app/jcl/TRANBKP.jcl:L31) over
  # the full master, and the report is 133 columns per line for every selected
  # transaction (app/jcl/TRANREPT.jcl:L78). A Fargate batch task killed
  # part-way through such an upload leaves its uploaded parts behind, and those
  # parts are STORED AND BILLED WHILE BEING INVISIBLE AS OBJECTS: they appear
  # in no bucket listing, so neither the noncurrent-version expiry above nor
  # any object-level rule can reach them, and nothing but this rule ever
  # removes them. That is the specific failure this value exists to bound -- a
  # cost that grows with every interrupted run and that no listing reveals.
  # Trade-off: seven days rather than one. A shorter window would reclaim the
  # parts sooner, but it would also abort a legitimately slow or retried upload
  # that is still in progress across a restart, and losing a real export costs
  # more than a few days of orphaned parts.
  validation {
    condition     = var.abort_incomplete_multipart_upload_days >= 1
    error_message = "abort_incomplete_multipart_upload_days must be at least 1; S3 measures this rule in whole days, so zero would express no rule at all and orphaned parts would accumulate unbounded."
  }
}

# -----------------------------------------------------------------------------
# Auditing, teardown and tagging.
# -----------------------------------------------------------------------------

variable "access_log_bucket_name" {
  description = "Name of an existing bucket that receives S3 server access logs for this bucket. Null disables access logging, which is the module default so that the module can be instantiated without a logging bucket already in place."
  type        = string
  default     = null

  # Trade-off: nullable with a null default rather than a required input. A
  # reusable module that required a log destination could not be instantiated
  # until the caller had provisioned one, which would make a logging bucket a
  # precondition of every consumer including a throwaway test root. The
  # consequence is stated rather than glossed: the policy scan in
  # .github/workflows/infra-ci.yml gates at HIGH and CRITICAL and expects
  # access logging on a bucket holding financial datasets, so the environment
  # roots are expected to supply a target here and leaving it null in dev or
  # prod is not the intended end state. If the scanner flags this bucket, the
  # resolution is to pass a target from the environment root -- never an inline
  # suppression, because the gates in this tree are satisfied by construction
  # rather than by exemption.
  #
  # Assumption: the target must be a DIFFERENT bucket from this one. Aiming a
  # bucket's server access logs at itself makes each delivered log object a
  # loggable write, which generates a further log object, and the bucket grows
  # without bound from its own logging. Nothing in the type system prevents a
  # caller passing this bucket's own name, so the constraint is recorded here
  # where a caller reading the input will see it.
}

variable "force_destroy" {
  description = "Whether Terraform may delete this bucket while it still holds objects, including noncurrent versions. False makes a destroy of a non-empty bucket fail rather than discard its contents."
  type        = bool
  default     = false

  # Trade-off: false by default, accepting that `terraform destroy` will fail
  # on a populated bucket and require an explicit, documented purge first. The
  # alternative default would let a destroy aimed at any other resource in the
  # root take every retained dataset generation with it, silently and without a
  # separate confirmation -- and these generations are the only copy, since the
  # baseline's own datasets are not written by this stack. A failed destroy is
  # recoverable in a way that a deleted backup history is not.
  # infra/bootstrap/variables.tf makes the same choice for the same reason with
  # its `state_bucket_force_destroy`, and documents the matching manual purge
  # in docs/runbooks/teardown.md, so the two behave alike and an operator does
  # not learn one convention per bucket.
  # Assumption: a versioned bucket is affected more than an unversioned one.
  # Deleting the current version of every object is not enough -- each
  # noncurrent version and each delete marker must also be removed before the
  # bucket itself will delete, so the purge is a version-aware operation and
  # not a recursive object delete.
}

variable "tags" {
  description = "Additional tags merged onto the resources this module creates, over and above whatever the calling root's provider-level default_tags already applies. Empty by default, so the module contributes no tags of its own unless a caller asks for them."
  type        = map(string)
  default     = {}

  # Assumption: the calling root configures `default_tags` on its aws provider,
  # the pattern infra/bootstrap/versions.tf establishes, so every resource in
  # this tree is already tagged with the account-wide set before this variable
  # is consulted. This input therefore exists only for tags meaningful to this
  # module or to one environment -- a cost-allocation key for the dataset
  # bucket specifically, say -- and not for the tags every resource shares.
  # On a key collision the resource-level tag wins over the provider default,
  # which is what makes a targeted override here possible at all.
  # Alternatives Considered: declaring individual tag variables such as
  # `cost_centre` or `owner`. Rejected -- each addition would be a breaking
  # change to this module's input contract, whereas a map absorbs a new tag
  # without an edit here, and the provider's default_tags already covers the
  # tags that genuinely apply everywhere.
}

