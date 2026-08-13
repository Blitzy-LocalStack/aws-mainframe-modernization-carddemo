# =============================================================================
# infra/modules/s3-datasets/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The public input surface of the `s3-datasets` module: FOURTEEN variables,
#   every one explicitly typed and described. The module provisions the single
#   versioned, customer-managed-key-encrypted S3 bucket that replaces the
#   mainframe baseline's generation data groups, carrying one prefix and one
#   noncurrent-version lifecycle rule for each of the TEN generation-dataset
#   families, plus the TWO non-generation statement artifacts and the ONE
#   source-extract prefix the nightly dataset refresh reads its inputs from.
#   Refactoring Rationale: the count read THIRTEEN and the file declared
#   fourteen from the revision `source_extract_prefix` landed. The sentence now
#   also names WHY the fourteenth exists, because a bare increment would leave a
#   reader to infer that the new input was another lifecycle knob.
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
#   The thirteen `variable` blocks below ARE this file's parameters, so the
#   name, type and description obligation is discharged on each block directly
#   rather than duplicated into a list here that could drift from it. In
#   declaration order: name_prefix, environment, kms_key_arn,
#   dataset_families, non_generation_prefixes, noncurrent_version_retention,
#   noncurrent_version_transition_days,
#   noncurrent_version_transition_storage_class,
#   abort_incomplete_multipart_upload_days, access_log_bucket_name,
#   s3_gateway_endpoint_id, force_destroy and tags. Exactly THREE of them --
#   `environment`, `kms_key_arn` and `s3_gateway_endpoint_id` -- have no default
#   and are therefore required of the caller.
#   Refactoring Rationale: the count moved from twelve to thirteen and the
#   required set from two to three when `s3_gateway_endpoint_id` was added. Both
#   figures are stated because the second is the one that breaks a caller: a
#   module whose required set grows is a module every existing instantiation must
#   be revisited for, and both environment roots were updated in the same change.
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
#   `environment` or `kms_key_arn`, because neither has a default. Five
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
#   - Assumptions: TEN generation families, not six. Six is what
#     app/jcl/DEFGDGB.jcl yields on its own, and reading only that file is the
#     trap; three more are defined in app/jcl/DEFGDGD.jcl and one in
#     app/jcl/DALYREJS.jcl. Provisioning six would silently lose four
#     retention policies -- the affected batch steps would still write
#     objects, to a prefix with no lifecycle rule, so nothing would fail and
#     generations would accumulate without limit.
#   - Trade-offs: the family and prefix inventories live in variable DEFAULTS
#     rather than as literals inside main.tf. The cost is a large default
#     block in a file that is otherwise pure contract. What it buys is that
#     the inventory is legible to every caller and is rendered into the
#     generated README, and that per-family retention can be overridden for
#     one environment without altering the topology.
#   - Assumptions: this file is consumed by main.tf and outputs.tf in this same
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

  # Assumptions: an S3 bucket name may not exceed 63 characters, and the
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
  # Assumptions: the twelve-character cap is not cosmetic. It is the other half
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

  # Assumptions: there are TEN generation-dataset bases in the baseline, not six.
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
  # Assumptions: the `domain` values are derived from the owning bounded
  # context's schema rather than invented. transaction-service owns the
  # `ledger` schema, reference-service owns `reference`, and reporting-service
  # owns no tables but produces the 133-column report. The split is therefore
  # SIX ledger + THREE reference + ONE reporting = TEN, written out so a
  # reader can verify the count by adding it up instead of trusting a comment.
  #
  # Trade-offs: this inventory is a variable default rather than a hard-coded
  # `for_each` list inside main.tf. The cost is a long default block in a
  # contract file; what it buys is that the inventory is readable by every
  # caller and appears in the generated README, and that a single family's
  # retention can be overridden for one environment WITHOUT changing the
  # prefix topology, which has to stay identical across environments.
  # Hard-coding it in main.tf was rejected because it would place a
  # behavioural contract in the one file no consumer of the module reads.
  #
  # Assumptions: `noncurrent_versions` is deliberately left UNSET on all ten, so
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

  # Assumptions: the TEN family names are asserted, not merely defaulted, because
  # this map IS the prefix topology and the topology is the one thing that must
  # not vary between environments. Before this check the exact-ten default could
  # be replaced wholesale by any map satisfying the object type, so a root could
  # apply cleanly with six families -- the count a reader gets from
  # app/jcl/DEFGDGB.jcl alone -- and nothing would fail: the four missing
  # families' batch steps would still write their objects, into a prefix carrying
  # no lifecycle rule, and generations would accumulate without limit. Two
  # sibling documents publish the same ten independently
  # (docs/architecture/batch-orchestration.md and data-migration/README.md), so an
  # inventory that drifts here also puts this module out of step with both.
  # Trade-offs: the retention KNOBS stay open while the KEY SET closes. A root may
  # still override noncurrent_version_retention globally or per family through the
  # optional noncurrent_versions member, which is the parameterization the two
  # environments are meant to use; what it may no longer do is add, remove or
  # rename a prefix, because that changes what exists rather than how long it is
  # kept.
  # Assumptions: equality needs both halves of the test. The setunion comparison
  # alone proves only that every key supplied is one of the ten, so a six-family
  # map would satisfy it; pairing it with the count closes that, because map keys
  # are already unique, so ten distinct keys drawn from a set of ten is that set
  # exactly.
  validation {
    condition = length(var.dataset_families) == 10 && setunion(keys(var.dataset_families), [
      "transact-bkup", "transact-daly", "tranrept", "tcatbalf-bkup", "systran",
      "transact-combined", "trantype-bkup", "trancatg-bkup", "discgrp-bkup", "dalyrejs",
      ]) == toset([
      "transact-bkup", "transact-daly", "tranrept", "tcatbalf-bkup", "systran",
      "transact-combined", "trantype-bkup", "trancatg-bkup", "discgrp-bkup", "dalyrejs",
    ])
    error_message = "dataset_families must be keyed by exactly the ten generation-dataset families the baseline defines: transact-bkup, transact-daly, tranrept, tcatbalf-bkup, systran and transact-combined from app/jcl/DEFGDGB.jcl, trantype-bkup, trancatg-bkup and discgrp-bkup from app/jcl/DEFGDGD.jcl, and dalyrejs from app/jcl/DALYREJS.jcl. Six is the count DEFGDGB.jcl alone suggests, and provisioning six silently loses four retention policies."
  }

  # Assumptions: `domain` becomes the LEADING path segment of every object key, so
  # an unrecognised value does not fail -- it writes a working prefix nobody
  # queries and nothing else reads. The three accepted values are the owning
  # bounded contexts' schema names: ledger for transaction-service, reference for
  # reference-service, and reporting for the report output reporting-service
  # produces. Constraining the domain is what keeps the six/three/one split
  # recorded above verifiable by reading the map rather than by trusting the
  # comment.
  # Assumptions: `description` is required non-empty because each one carries the
  # baseline lineage -- the generation-data-group base it replaces and the JCL
  # line defining it -- and that lineage is the only record of why a prefix
  # exists. An empty string satisfies the type and would erase it silently.
  validation {
    condition = alltrue([
      for family in var.dataset_families :
      contains(["ledger", "reference", "reporting"], family.domain) && length(trimspace(family.description)) > 0
    ])
    error_message = "Every dataset_families entry must carry a domain of ledger, reference or reporting -- the schema names of the bounded contexts that own the data -- and a non-empty description recording the baseline generation-data-group base it replaces."
  }

  # Assumptions: a per-family override of at least one is the same correctness
  # bound the module-wide `noncurrent_version_retention` carries, and for the same
  # reason: the batch chain addresses generations relatively, so a family retaining
  # zero noncurrent versions has no preceding generation for a rerun to read.
  # Unset is the normal case and inherits the module-wide value.
  validation {
    condition = alltrue([
      for family in var.dataset_families :
      family.noncurrent_versions == null || try(family.noncurrent_versions >= 1, false)
    ])
    error_message = "A dataset_families noncurrent_versions override must be at least 1 when it is set at all; zero would expire every noncurrent version of that family immediately and destroy the generation history the batch chain reads relatively."
  }
}

variable "non_generation_prefixes" {
  description = "Prefixes for reporting artifacts that are NOT generation data groups, keyed by the S3-safe artifact name. Each value carries the literal key prefix the reporting service writes under, the domain, the owning bounded context, and a description recording the baseline dataset and the JCL line that writes it. Held separately from dataset_families so these can never be counted as additional generation families."

  type = map(object({
    prefix      = string
    domain      = string
    description = string
  }))

  # Assumptions: not one of these three artifacts has a GENERATIONDATAGROUP base
  # anywhere in the baseline. An exhaustive search for DEFINE
  # GENERATIONDATAGROUP matches only four files -- app/jcl/DEFGDGB.jcl,
  # app/jcl/DEFGDGD.jcl, app/jcl/DALYREJS.jcl and app/jcl/REPTFILE.jcl -- and
  # not one of them defines a base for a statement or for
  # AWS.M2.CARDDEMO.TCATBALF.REPT. All three are plain sequential datasets,
  # fixed-key rather than numbered. They live in their own variable so they cannot be
  # miscounted as an eleventh generation family, which is the mistake the
  # separation exists to prevent: the ten-family count is asserted in the AAP
  # and published by two sibling documents, so folding these into that map
  # would put this module out of step with all of them. The BUCKET carries
  # fourteen prefixes in total -- these three, the ten families, and
  # `source_extract_prefix` -- and that total is deliberately not a single
  # inventory: only ten of the fourteen are generation families, and the count
  # that has to stay checkable is the ten.
  # They still need prefixes, because the GenerateStatements and GenerateReports
  # batch states write all three artifacts to S3. Bucket versioning is
  # bucket-wide and cannot be enabled per prefix, so these objects acquire
  # noncurrent versions too and get a noncurrent-version rule as a consequence
  # -- but that rule is ordinary version hygiene and is NOT the LIMIT(5)
  # SCRATCH analogue described on `noncurrent_version_retention` below. Reading
  # it as a generation limit would invent a generation contract the baseline
  # never had for these three datasets.
  #
  # Trade-offs: the baseline's own pattern here was delete-then-recreate -- an
  # IEFBR14 step at app/jcl/CREASTMT.JCL:L66-L75 deletes both statements before
  # CBSTM03A writes them fresh at L87-L96, and app/jcl/PRTCATBL.jcl:L21-L25
  # deletes AWS.M2.CARDDEMO.TCATBALF.REPT before its sort rewrites it. Object
  # versioning expresses that as a new current version with the previous one
  # becoming noncurrent, so the target needs no delete step at all: the old
  # artifact is retained rather than scratched, which is strictly more
  # recoverable than the baseline and costs only the retained versions.
  # Refactoring Rationale: each entry now carries its prefix LITERALLY, and the
  # prefix used to be derived as "<domain>/<key>/". The derivation produced
  # `reporting/statement-text/` and `reporting/statement-html/`, and the
  # reporting service writes neither: it publishes both statements under
  # `statements/` -- declared as `statement-prefix` in
  # services/reporting-service/src/main/resources/application.yml -- and its two
  # reports under `reports/transaction-detail/` and
  # `reports/category-balance/`, declared in the same document. So this module
  # attached noncurrent-version rules to two prefixes nothing writes while the
  # three prefixes that ARE written carried no rule at all, and the outputs
  # published locations no consumer could use. A prefix a service chooses is not
  # derivable from a key this module invents, so it is transcribed instead.
  # Alternatives Considered: changing the SERVICE to write under the derived
  # prefixes. Rejected because the service's prefixes are published contracts --
  # runbooks address artifacts by them, and the IAM scoping in both environment
  # roots is expressed in them -- whereas these keys are internal to this module
  # and consumed by nothing outside it, so moving the module is the change with
  # no blast radius.
  # Assumptions: the two statement artifacts share ONE prefix and are
  # distinguished by object name, `statements.txt` and `statements.html`, which
  # is why they are one entry here and were two before. Two entries resolving to
  # one prefix would emit two lifecycle rules with identical filters, which is a
  # rule that reads as governing two locations while governing one.
  default = {
    "statements" = {
      prefix      = "statements/"
      domain      = "reporting"
      description = "Plain-text and HTML customer statements, written as statements.txt and statements.html under one prefix. Replaces sequential datasets AWS.M2.CARDDEMO.STATEMNT.PS and AWS.M2.CARDDEMO.STATEMNT.HTML, deleted by the IEFBR14 steps at app/jcl/CREASTMT.JCL:L71 and L75 and rewritten by CBSTM03A at L91 with DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB) and at L96 with DCB=(LRECL=100,BLKSIZE=800,RECFM=FB). Neither is a generation data group: no GENERATIONDATAGROUP base for either exists in the baseline."
    }
    "transaction-detail-report" = {
      prefix      = "reports/transaction-detail/"
      domain      = "reporting"
      description = "The request-scoped transaction detail report, keyed by run date, report type and both range bounds. Corresponds to the 133-column output CBTRN03C writes at app/jcl/TRANREPT.jcl:L80. That output also has a generation base -- provisioned as the tranrept generation family -- and the nightly run publishes to BOTH: this prefix is what a range-addressed request and the runbooks resolve, the generation prefix is what carries the LIMIT(5) analogue."
    }
    "category-balance-report" = {
      prefix      = "reports/category-balance/"
      domain      = "reporting"
      description = "The category-balance report, written as category-balance.txt. Corresponds to the sorted 40-byte output the DFSORT step at app/jcl/PRTCATBL.jcl:L52-L63 writes to AWS.M2.CARDDEMO.TCATBALF.REPT. Not a generation data group: an exhaustive search of the baseline for DEFINE GENERATIONDATAGROUP matches four files and none of them defines a base for it, so it is a fixed-key artifact like the statements."
    }
  }

  # Assumptions: these THREE keys are asserted for the same reason the ten
  # families above are, and the assertion matters more here rather than less. The
  # whole purpose of holding these prefixes in a separate variable is that they can
  # never be counted as generation families; an unconstrained map defeats that,
  # because a root could move a generation family into this variable, or add a
  # fourth prefix here, and the fourteen-prefix total would still plan cleanly while
  # the ten-family count the AAP asserts and two sibling documents publish
  # quietly stopped being true of the deployed bucket.
  # Assumptions: the domain is fixed to reporting because all three artifacts are
  # written by the two orchestrated states reporting-service owns -- GenerateStatements
  # and GenerateReports. The set is complete for that workload: those two states
  # publish the statements, the transaction detail report and the category-balance
  # report, and every other object either service writes to this bucket lands in a
  # generation family or in the source-extract prefix below.
  validation {
    condition = length(var.non_generation_prefixes) == 3 && setunion(keys(var.non_generation_prefixes), [
      "statements", "transaction-detail-report", "category-balance-report",
      ]) == toset([
      "statements", "transaction-detail-report", "category-balance-report",
    ])
    error_message = "non_generation_prefixes must be keyed by exactly statements, transaction-detail-report and category-balance-report, the three fixed-key reporting artifacts the baseline writes. Adding a key here would raise the bucket's prefix count above the fourteen the architecture documents publish, and moving a generation family into this variable would drop its LIMIT(5) analogue."
  }

  # Assumptions: the prefix is required to END in a slash and to carry no leading
  #   one. Every filter and IAM pattern in this stack appends a wildcard to it, so
  #   `reports/transaction-detail` without the slash would also match a sibling
  #   prefix such as `reports/transaction-detail-archive/`, and a leading slash
  #   would produce a key no S3 caller composes.
  validation {
    condition = alltrue([
      for prefix in var.non_generation_prefixes :
      prefix.domain == "reporting" &&
      length(trimspace(prefix.description)) > 0 &&
      length(trimspace(prefix.prefix)) > 1 &&
      endswith(prefix.prefix, "/") &&
      !startswith(prefix.prefix, "/")
    ])
    error_message = "Every non_generation_prefixes entry must carry the reporting domain -- all three artifacts are written by the states reporting-service owns -- a non-empty description recording the baseline dataset it replaces, and a non-empty prefix that ends in a slash and does not begin with one."
  }
}

# -----------------------------------------------------------------------------
# The source-extract prefix -- where the exported baseline datasets are READ from.
#
# Refactoring Rationale: this prefix exists because the nightly seed-refresh state
#   had no readable source at all. It told the data-migration container to read the
#   extracts from a filesystem path -- /mnt/carddemo-extracts -- and nothing in this
#   stack provisions a filesystem, so every branch of that state failed on an absent
#   file while its own input documentation described populating the path as an
#   "operator action". The extracts were nevertheless already going to S3:
#   docs/runbooks/data-migration.md tells an operator to
#   `aws s3 sync app/data/ s3://<dataset bucket>/migration/source/`. Naming that
#   destination here, and granting the refresh a read of it, is what makes the state
#   runnable from what the runbook already produces.
# Alternatives Considered: adding a key to `non_generation_prefixes` instead of a
#   variable of its own. Rejected because that map is CLOSED by validation at exactly
#   the two statement artifacts, deliberately, so that no third prefix can be
#   miscounted as a generation family -- and because this prefix is categorically
#   different from both inventories: it is the only prefix in the bucket the stack
#   READS as an input rather than WRITES as an output, so it carries no generation
#   convention, no dt=/gen= structure and no LIMIT(5) analogue. Folding it in would
#   have required loosening the assertion that protects the ten-family count.
# Alternatives Considered: a separate bucket for the extracts. Rejected on two
#   grounds: the data-migration task already holds a scoped read on this bucket and a
#   second bucket would need a second grant, a second key policy and a second
#   lifecycle configuration for one read-only prefix; and the runbook's existing
#   sync destination is this bucket, so a second one would make the documented
#   operator step wrong rather than the code right.
# Assumptions: the prefix defaults to the EBCDIC subdirectory of the runbook's sync
#   destination, because the exported extracts the registry names are the
#   mainframe-character-set .PS files -- the ASCII twins are a developer convenience
#   and are not the cutover input. A deployment holding them elsewhere overrides this
#   one value and the refresh reads there instead.
# -----------------------------------------------------------------------------

variable "source_extract_prefix" {
  description = "Key prefix inside the dataset bucket holding the exported baseline extracts the data-migration refresh READS. Populating it is an operator action -- docs/runbooks/data-migration.md syncs app/data/ here -- and the refresh joins each dataset's registered source file name to this prefix. This is the only prefix in the bucket that is an input rather than an output, so it carries no generation convention and no LIMIT(5) analogue; its lifecycle rule is ordinary version hygiene for a re-synced corrected extract."

  type    = string
  default = "migration/source/EBCDIC/"

  validation {
    # Assumptions: a LEADING separator is refused rather than stripped. An S3 key has
    #   no root, so "/migration/..." is a different, working prefix whose first
    #   segment is empty -- a value copied from a filesystem path would otherwise plan
    #   cleanly and then read a prefix nothing was ever synced to. The ETL's own
    #   `extract_source_key` refuses the same spelling for the same reason, so the two
    #   sides agree about what a prefix is.
    condition     = length(trimspace(var.source_extract_prefix)) > 0 && !startswith(var.source_extract_prefix, "/")
    error_message = "source_extract_prefix must be a non-empty S3 key prefix and must not begin with \"/\"; an object key has no root, so a leading separator names a different prefix rather than the same one."
  }

  validation {
    # Assumptions: a TRAILING separator is required, the opposite of the rule on
    #   dataset_staging paths elsewhere, because this value is used as a prefix FILTER
    #   in a lifecycle rule and in an IAM resource pattern. Without the separator,
    #   "migration/source/EBCDIC" also matches "migration/source/EBCDICOLD/", so a
    #   sibling prefix would inherit this prefix's retention rule and its read grant.
    condition     = endswith(var.source_extract_prefix, "/")
    error_message = "source_extract_prefix must end with \"/\" so that it matches only keys inside it; without the separator the prefix also matches sibling prefixes that merely start with the same characters."
  }

  validation {
    # Assumptions: the character set is restricted to what the exported extract names
    #   and the runbook's own sync destination use. It excludes the wildcard and
    #   policy-variable characters an IAM resource pattern interprets, because this
    #   value is interpolated into one.
    condition     = can(regex("^[a-zA-Z0-9!_.*'()/-]+$", var.source_extract_prefix)) && !strcontains(var.source_extract_prefix, "//") && !strcontains(var.source_extract_prefix, "..")
    error_message = "source_extract_prefix must consist of S3-safe key characters, must not contain an empty segment (\"//\") and must not contain \"..\"."
  }
}

# -----------------------------------------------------------------------------
# Lifecycle and retention. These four values, and only these four, are what the
# dev and prod roots are expected to set differently.
# -----------------------------------------------------------------------------

variable "noncurrent_version_retention" {
  description = "Default retention count shared by two mechanisms: data-migration's staging writer keeps this many logical dt=/gen= generation prefixes per family, reproducing LIMIT(5) SCRATCH; S3 lifecycle also keeps this many newer noncurrent versions of any one object key as repeat-write recovery. Distinct gen= prefixes are not noncurrent versions of each other."
  type        = number
  default     = 5

  # Assumptions: all ten baseline bases are defined LIMIT(5) with SCRATCH --
  # app/jcl/DEFGDGB.jcl:L26, L32, L38, L44, L50 and L56; app/jcl/DEFGDGD.jcl:L29,
  # L52 and L75; and app/jcl/DALYREJS.jcl:L26. The two keywords carry separate
  # meanings and both are needed to justify this default. LIMIT(5) caps the
  # group at five generations, and SCRATCH makes the generation that rolls off
  # physically deleted rather than merely uncatalogued. The staging writer
  # supplies that exact behaviour by sorting the distinct dt=/gen= prefixes and
  # deleting every version and delete marker under prefixes older than the
  # newest five. The lifecycle rule uses the same number for a DIFFERENT layer:
  # repeat writes to one object key inside a retained generation.
  #
  # Assumptions: a value of zero would tell the writer to delete every logical
  # generation after staging and would make noncurrent object history useless.
  # That history is load-bearing rather than decorative, because the batch
  # chain addresses generations RELATIVELY rather than by absolute name --
  # app/jcl/COMBTRAN.jcl:L24 and L26 read (0), the current generation, while
  # app/jcl/TRANREPT.jcl:L39 reads back the (+1) written earlier in the same
  # job at L33 -- so a rerun or an investigation that needs the preceding
  # generation would find nothing to read. The floor of one is therefore a
  # correctness bound, not a style preference.
  validation {
    condition     = var.noncurrent_version_retention >= 1
    error_message = "noncurrent_version_retention must be at least 1; zero would leave no logical generation after staging and no usable repeat-write recovery."
  }
}

variable "noncurrent_version_transition_days" {
  description = "Age in days at which a noncurrent version moves to the storage class named by noncurrent_version_transition_storage_class. Null disables the transition entirely, leaving noncurrent versions in the class they were written to until they expire."
  type        = number
  default     = null

  # Trade-offs: this is one of the two lifecycle knobs the environment roots are
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

  # Assumptions: the null case must stay expressible, so the bound is written as an
  # explicit null test rather than as a bare comparison. A plain `>= 30` condition
  # on a nullable number fails when the value IS null, which would make the
  # disabled path -- the default, and the one dev uses -- unreachable.
  # Assumptions: S3 measures a lifecycle transition in WHOLE days and applies the
  # rule to noncurrent versions once they are at least that old. A fractional or
  # negative value is not corrected: the provider rejects it during apply, after
  # the bucket, its versioning and its policy already exist, and the error names
  # the lifecycle rule rather than this input. A value of zero is the one most
  # likely to be tried to mean "immediately", and it is refused, because
  # transitioning a version the moment it stops being current bills the
  # destination class's minimum duration on an object that a five-generation
  # retention may expire long before that duration elapses -- paying twice to
  # store history the rule is about to delete.
  # Trade-offs: no upper bound is imposed. A transition later than the retention
  # window merely never fires, which costs nothing and is a legitimate way to stage
  # a change ahead of raising retention, so refusing it would remove a usable
  # configuration to prevent a harmless one.
  validation {
    condition     = var.noncurrent_version_transition_days == null || try(var.noncurrent_version_transition_days >= 1 && floor(var.noncurrent_version_transition_days) == var.noncurrent_version_transition_days, false)
    error_message = "noncurrent_version_transition_days must be null to disable the transition, or a whole number of days of at least 1. S3 counts transition age in whole days, and zero would transition a version the moment it stopped being current, billing the destination class's minimum duration on history the retention rule may expire first."
  }
}

variable "noncurrent_version_transition_storage_class" {
  description = "Storage class a noncurrent version transitions into, read only when noncurrent_version_transition_days is non-null. Ignored entirely while that value is null."
  type        = string
  default     = "STANDARD_IA"

  # Assumptions: a rolled-off dataset generation is read only during an
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

  # Assumptions: the accepted set is the storage classes a lifecycle TRANSITION may
  # name, and it is enumerated here because a misspelling or an unsupported class
  # is refused by the API during apply, in an error that quotes the lifecycle rule
  # rather than this variable. STANDARD is deliberately absent from the set: it is
  # the class these objects are written to, so transitioning to it is a rule that
  # can never do anything, and naming it here would read as a way to disable the
  # transition -- which is what a null in noncurrent_version_transition_days is
  # for.
  # Trade-offs: enumerating a set AWS owns means a class added by the service is
  # refused here until this list is extended, which is the objection that led the
  # cloudfront-spa module to leave its TLS policy list to the provider. It is
  # accepted here because this set has been stable for years and is short, and
  # because the failure it prevents -- a typo that surfaces only at apply, partway
  # through creating a bucket's lifecycle configuration -- is the more likely of
  # the two.
  validation {
    condition = contains([
      "STANDARD_IA",
      "ONEZONE_IA",
      "INTELLIGENT_TIERING",
      "GLACIER_IR",
      "GLACIER",
      "DEEP_ARCHIVE",
    ], var.noncurrent_version_transition_storage_class)
    error_message = "noncurrent_version_transition_storage_class must be one of STANDARD_IA, ONEZONE_IA, INTELLIGENT_TIERING, GLACIER_IR, GLACIER or DEEP_ARCHIVE. STANDARD is excluded because it is the class these objects are already written to, so a transition into it can never act; disable the transition with a null noncurrent_version_transition_days instead."
  }
}

variable "abort_incomplete_multipart_upload_days" {
  description = "Age in days after which an incomplete multipart upload is aborted and its already-uploaded parts deleted. Applies to the whole bucket rather than to one prefix."
  type        = number
  default     = 7

  # Assumptions: the batch exports write whole generations in one object, large
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
  # Trade-offs: seven days rather than one. A shorter window would reclaim the
  # parts sooner, but it would also abort a legitimately slow or retried upload
  # that is still in progress across a restart, and losing a real export costs
  # more than a few days of orphaned parts.
  validation {
    condition     = var.abort_incomplete_multipart_upload_days >= 1
    error_message = "abort_incomplete_multipart_upload_days must be at least 1; S3 measures this rule in whole days, so zero would express no rule at all and orphaned parts would accumulate unbounded."
  }
}

# WHY : Refactoring Rationale: an `object_created_lambda_arn` input stood here,
#       and with it an aws_lambda_permission and an aws_s3_bucket_notification in
#       main.tf that invoked a caller-supplied function on every completed object
#       write. All three were removed. The module's remit is the versioned dataset
#       bucket, the ten generation-dataset prefix families and their lifecycle
#       rules; wiring a bucket to an arbitrary caller-supplied function is an
#       event-integration concern that belongs to whichever root owns that
#       function, and an aws_s3_bucket_notification is a WHOLE-BUCKET resource, so
#       declaring one here also took the bucket's only notification slot away from
#       every consumer of this module.
#       Alternatives Considered: keeping the input and making it nullable.
#       Rejected: it would leave the notification slot claimed conditionally, which
#       is harder to reason about than not claiming it, and the input would still
#       not be one this module's contract admits.
#       Trade-offs: the five-generation retention the removed hook enforced across
#       distinct dt=/gen= keys is now the calling root's to wire, over the bucket
#       name this module publishes. The noncurrent-version lifecycle rule this
#       module does own is unaffected -- it bounds versions of one key, which is a
#       different guarantee, and the two were always complementary rather than
#       alternatives.

# -----------------------------------------------------------------------------
# Auditing, teardown and tagging.
# -----------------------------------------------------------------------------

variable "access_log_bucket_name" {
  description = "Name of an existing bucket that receives S3 server access logs for this bucket. Null disables access logging, which is the module default so that the module can be instantiated without a logging bucket already in place."
  type        = string
  default     = null

  # Trade-offs: nullable with a null default rather than a required input. A
  # reusable module that required a log destination could not be instantiated
  # until the caller had provisioned one, which would make a logging bucket a
  # precondition of every consumer including a throwaway test root. The
  # consequence is stated rather than glossed: the complete Checkov scan reports
  # access logging on a bucket holding financial datasets, so the environment
  # roots are expected to supply a target here and leaving it null in dev or
  # prod is not the intended end state. If the scanner flags this bucket, the
  # resolution is to pass a target from the environment root -- never an inline
  # suppression, because ACCESS LOGGING carries no exemption anywhere in this
  # module and a suppression here would make a bucket with no log target
  # indistinguishable from one whose root forgot to supply one. That is scoped
  # deliberately: this module does carry two justified Checkov skips, on
  # `aws_s3_bucket.audit` and `aws_cloudtrail.dataset_object_access`, so a claim
  # that it declines no check at all would be false and would invite a reader to
  # treat those two as undocumented drift.
  #
  # Assumptions: the target must be a DIFFERENT bucket from this one. Aiming a
  # bucket's server access logs at itself makes each delivered log object a
  # loggable write, which generates a further log object, and the bucket grows
  # without bound from its own logging. Nothing in the type system prevents a
  # caller passing this bucket's own name, so the constraint is recorded here
  # where a caller reading the input will see it.
}

# WHY : Refactoring Rationale: an `audit_log_retention_days` input stood here,
#       defaulting to seven years and driving an expiration and a
#       noncurrent-version expiration on the audit bucket's lifecycle rule. It was
#       removed. A compliance retention horizon is an organisational policy
#       decision, not a property of a dataset bucket module, and the module had no
#       basis for the seven-year figure it defaulted to -- which is precisely the
#       kind of non-obvious default this repository's documentation rule requires a
#       named rationale for and which none of the four categories could honestly
#       supply. infra/bootstrap declares its own, separate variable of the same
#       name for its own state-access audit bucket; that one is untouched.
#       Trade-offs: the audit bucket's lifecycle rule now expires nothing, so
#       objects accumulate until an owner sets a horizon. That is the safe
#       direction for an audit trail -- objects are versioned, encrypted and
#       public-access blocked -- and it is preferable to this module asserting a
#       horizon it cannot justify. The rule retains its
#       abort_incomplete_multipart_upload action, which cleans up abandoned
#       multipart uploads and is a storage-hygiene concern rather than a retention
#       policy.

variable "s3_gateway_endpoint_id" {
  description = "Identifier of the VPC S3 gateway endpoint that dataset object reads and writes must arrive through, shaped vpce-<hex>. The bucket policy denies s3:GetObject, s3:GetObjectVersion and s3:PutObject to any request whose aws:SourceVpce is not this endpoint, so the dataset contents are reachable only from inside the VPC. Required, with no default: an omitted value would leave the deny statement unable to name an endpoint and would silently reduce the control to nothing."
  type        = string

  # WHY : Refactoring Rationale: this input is NEW. The bucket previously carried
  #       one policy statement -- a transport-security deny -- so an object read
  #       from outside the VPC was refused only if it arrived over plain HTTP. A
  #       correctly-formed HTTPS request bearing any principal with an IAM grant
  #       reached the dataset generations from anywhere on the internet, and those
  #       generations hold records derived from the cardholder masters. The network
  #       boundary belongs here rather than on the gateway endpoint itself: the
  #       sibling network module records why an endpoint policy cannot carry it
  #       without breaking ECR image pulls, and a bucket policy can name one bucket
  #       and three actions where an endpoint policy names a whole service.
  #
  # WHY : Assumptions: the input is REQUIRED rather than nullable, and this differs
  #       deliberately from access_log_bucket_name above. That one degrades to a
  #       missing diagnostic; this one degrades to a missing security control, and a
  #       nullable security input is a control that is off by default in exactly the
  #       environments nobody reviews. Both environment roots pass
  #       module.network.s3_gateway_endpoint_id, so requiring it costs a caller
  #       nothing and makes an omission a plan error rather than a silent opening.
  #
  # WHY : Trade-offs: an operator can no longer download a dataset generation or a
  #       generated report from a workstation, and that is the control working
  #       rather than a defect to be worked around. Retrieval has to happen from
  #       inside the VPC -- from a task, or from an instance in the private
  #       application tier. A presigned URL does not evade it either, because the
  #       redemption is what carries aws:SourceVpce and a redemption from outside
  #       carries none. docs/runbooks/batch-operations.md is where that procedure
  #       belongs; naming the constraint here is what stops it being discovered
  #       during an incident.
  #
  # WHY : Alternatives Considered: denying every s3 action rather than the three
  #       object data-plane ones. Rejected on a concrete consequence: Terraform runs
  #       from outside the VPC, and both roots set force_destroy from
  #       !var.deletion_protection, so a dev destroy legitimately issues
  #       version-aware deletes and bucket-configuration reads from there. A blanket
  #       deny would make `terraform destroy` fail -- breaking the teardown
  #       criterion this package is accepted against -- while adding nothing to
  #       confidentiality, because bucket metadata is not the dataset. The three
  #       named actions are the ones that move dataset CONTENT.
  validation {
    condition     = can(regex("^vpce-[0-9a-f]{8,}$", var.s3_gateway_endpoint_id))
    error_message = "s3_gateway_endpoint_id must be a VPC endpoint identifier shaped vpce- followed by at least eight lowercase hexadecimal characters. A bucket name, an ARN or an interface-endpoint identifier for another service would produce a policy that denies every object read, including the batch tasks' own."
  }
}

variable "force_destroy" {
  description = "Whether Terraform may delete this bucket while it still holds objects, including noncurrent versions. False makes a destroy of a non-empty bucket fail rather than discard its contents."
  type        = bool
  default     = false

  # Trade-offs: false by default, accepting that `terraform destroy` will fail
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
  # Assumptions: a versioned bucket is affected more than an unversioned one.
  # Deleting the current version of every object is not enough -- each
  # noncurrent version and each delete marker must also be removed before the
  # bucket itself will delete, so the purge is a version-aware operation and
  # not a recursive object delete.
}

variable "tags" {
  description = "Additional tags merged onto the resources this module creates, over and above whatever the calling root's provider-level default_tags already applies. Empty by default, so the module contributes no tags of its own unless a caller asks for them."
  type        = map(string)
  default     = {}

  # Assumptions: the calling root configures `default_tags` on its aws provider,
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
