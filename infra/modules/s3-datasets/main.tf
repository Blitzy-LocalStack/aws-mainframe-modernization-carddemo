# =============================================================================
# infra/modules/s3-datasets/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The substance of the `s3-datasets` module: ONE versioned,
#   customer-managed-key-encrypted, publicly inaccessible S3 bucket carrying a
#   TLS-only bucket policy, plus prefix-scoped lifecycle rules for the TEN
#   generation-dataset families the baseline defines and the TWO non-generation
#   statement artifacts -- twelve prefix-scoped rules, and one further
#   bucket-wide housekeeping rule that carries no retention action.
#
#   What it reproduces, and by what mechanism. The baseline expresses dataset
#   generations through IDCAMS: `DEFINE GENERATIONDATAGROUP ... LIMIT(5)
#   SCRATCH`, ten times over. Each `dt=.../gen=.../` prefix is one LOGICAL
#   generation. The data-migration staging writer enumerates those prefixes and
#   permanently deletes every object version and delete marker under the
#   oldest prefixes once the configured count is exceeded. Bucket versioning
#   separately protects a re-write of the SAME object key inside a generation;
#   the noncurrent-version lifecycle rules bound that recovery history and do
#   not count distinct `gen=` keys.
#
#   Object keys follow one convention, and it is the part consumers depend on:
#
#       s3://<bucket>/<domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/
#
#   This is a reusable MODULE, never applied on its own. It is called as
#   `source = "../../modules/s3-datasets"` from infra/envs/dev and
#   infra/envs/prod, and it inherits the provider configuration -- region and
#   default tags -- from whichever root calls it. That is why no `provider`
#   block appears below and why versions.tf declares only `required_providers`.
#
#   WARNING: the bucket created here is NOT the Terraform remote-state bucket.
#   See the note above the data sources below; infra/bootstrap owns state.
#
# Parameters:
#   All twelve variables declared in variables.tf are read by this file, so the
#   module has no input that reaches nothing. Their types, defaults and
#   constraints are documented on the `variable` blocks themselves rather than
#   restated here, where a second copy could drift from the first:
#     name_prefix, environment ......... composed into local.bucket_name
#     kms_key_arn ...................... the SSE-KMS key
#     dataset_families ................. the ten generation prefixes and rules
#     non_generation_prefixes .......... the two statement prefixes and rules
#     noncurrent_version_retention ..... default logical/version-history count
#     noncurrent_version_transition_days,
#     noncurrent_version_transition_storage_class
#                                      . the optional noncurrent transition
#     abort_incomplete_multipart_upload_days
#                                      . orphaned-part reclamation
#     access_log_bucket_name ........... enables server access logging
#     force_destroy .................... destroy-with-contents behaviour
#     tags ............................. merged onto the bucket
#
# Return values:
#   No `output` is declared here; outputs.tf owns the module's return surface
#   and reads from this file. What it consumes is `aws_s3_bucket.datasets` --
#   its `id`, `arn` and `bucket` attributes -- together with
#   `local.all_dataset_prefixes`, the twelve-entry map of family key to
#   `<domain>/<dataset>/` prefix that a batch state or an ETL loader needs in
#   order to address a generation. Renaming either the resource or that local
#   breaks outputs.tf, and adding a resource makes the generated Resources
#   table in README.md stale until it is regenerated.
#
# Errors:
#   Four failure modes, three of them at apply rather than at plan:
#     1. `kms_key_arn` naming a key that does not exist, sits in another
#        region, or whose key policy does not permit S3 to use it: the bucket
#        is created and the encryption configuration then fails, leaving the
#        bucket present and unencrypted until the apply is corrected.
#     2. A globally taken bucket name. The S3 name namespace spans every AWS
#        account, so creation fails with an ownership conflict; the account id
#        and region in local.bucket_name are what make that improbable rather
#        than merely unlikely.
#     3. A noncurrent-version lifecycle action attached before versioning is
#        enabled is rejected, which is why the lifecycle configuration declares
#        an explicit dependency on the versioning resource.
#     4. `terraform destroy` against a populated bucket fails while
#        `force_destroy` is false -- and a versioned bucket is affected more
#        than an unversioned one, because every noncurrent version and delete
#        marker must be removed before the bucket itself will delete.
#
# WHY (non-obvious design decisions):
#   - Refactoring Rationale: the AAP fixes the distinct-key convention
#     `<domain>/<dataset>/dt=.../gen=.../`, so object versions cannot themselves
#     be the logical generation stack. Cleanup is single-sourced in
#     data-migration/src/carddemo_migration/loaders/s3_stage.py and invoked by
#     every staging path,
#     rather than reimplemented independently by each batch writer.
#   - Alternatives Considered: a days-based noncurrent expiry instead of a
#     count-based one. Rejected -- LIMIT(5) counts generations, it does not age
#     them. Recorded in full on the lifecycle configuration.
#   - Trade-offs: one prefix-scoped rule per family rather than a single
#     bucket-wide rule, accepting twelve rules to gain per-family retention and
#     an auditable prefix filter per baseline generation base.
#   - Assumptions: the prefix TOPOLOGY is identical in every environment and
#     only retention and transition values differ, which is the contract
#     variables.tf states and the reason both inventories arrive as maps with
#     complete defaults rather than as per-environment literals.
# =============================================================================

# -----------------------------------------------------------------------------
# THIS IS NOT THE TERRAFORM REMOTE-STATE BUCKET.
#
# infra/bootstrap is the sole owner of the state bucket and the DynamoDB state
# lock table, and it is applied once per AWS account out of band before either
# environment root can `init`. Nothing of either is declared, referenced or
# imported here, and nothing of either should be added: the two are easy to
# confuse because both are versioned, encrypted, publicly blocked S3 buckets,
# and aiming one at the other would put application datasets and Terraform
# state in a single blast radius.
#
# Note the deliberate INVERSE on retention, which is the sharpest distinction
# between the two and the one most likely to be "helpfully" consolidated away.
# infra/bootstrap declares NO noncurrent-version expiration at all, because
# every prior version of a state file is recovery material and pruning it
# destroys the only record of what the infrastructure previously was. This
# module is the opposite: logical generation cleanup keeps the newest five
# dt=/gen= prefixes and physically removes older prefixes, while lifecycle
# bounds repeat-write versions within the retained prefixes. Identical resource
# types, opposite retention policies, both correct for what they hold.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# How the baseline addresses these datasets, and how consumers address them
# here. Recorded once, because every prefix and every lifecycle filter below
# depends on it and none of it is inferable from the HCL alone.
#
#   s3://<bucket>/<domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/
#
# The two relative-generation forms map onto that convention directly:
#
#   (+1)  a NEW generation  => a new gen=NNNN prefix and a new current object
#         version. Written by app/jcl/TRANBKP.jcl:L33, app/jcl/POSTTRAN.jcl:L38,
#         app/jcl/INTCALC.jcl:L41, app/jcl/COMBTRAN.jcl:L37,
#         app/jcl/TRANREPT.jcl:L33, L55 and L80, app/jcl/PRTCATBL.jcl:L39, and
#         app/jcl/DEFGDGD.jcl:L40, L63 and L86.
#   (0)   the CURRENT generation => the current object version. Read by
#         app/jcl/COMBTRAN.jcl:L24 and L26.
#
# app/jcl/COMBTRAN.jcl is the cleanest single proof that both forms are one
# mechanism rather than two: in ONE job it reads TRANSACT.BKUP(0) at L24 and
# SYSTRAN(0) at L26 as current, sorts them into TRANSACT.COMBINED(+1) at L37,
# then reads that same new generation back at L44. Under versioning the same
# sequence is a read of two current versions, a write creating a third, and a
# read of what that write just made current -- no bookkeeping in between.
#
# Alternatives Considered: creating one zero-byte `aws_s3_object` per prefix so
# that the "directories" visibly exist, which is the obvious way to make a
# provisioned prefix inspectable and is what an operator used to catalogued
# datasets will expect. Rejected, and NO SUCH RESOURCE IS DECLARED ANYWHERE
# BELOW. S3 has no directories: a prefix is not an entity that is created, it
# comes into existence the moment the first object is written under it and
# ceases to exist when the last one is removed, so a marker object provisions
# nothing that was missing. Worse, it would be actively harmful in three ways
# specific to this bucket. A marker is itself a versioned object, so the
# retention rules below would apply to it and it would accrue noncurrent
# versions of an empty file. It would appear in every listing, so the ETL
# readers and the batch tasks that enumerate a prefix would see a spurious
# zero-length record among real dataset generations and would have to learn to
# skip it. And a marker under `<domain>/<dataset>/` does not even match the keys
# consumers use, which include the `dt=` and `gen=` segments, so it would
# document a path nothing writes to.
# WHAT "PROVISIONS PREFIXES" THEREFORE MEANS HERE: this module declares the
# prefix CONVENTION -- as the lifecycle-rule filters that govern retention and
# as the map outputs.tf publishes -- and never as placeholder objects.
#
# Assumptions: consumers never hard-code these paths. Per AAP section 0.5.3.3
# every baseline `DD DSN=` becomes either a database connection resolved from
# Parameter Store or an S3 URI supplied through container overrides, so the
# Step Functions batch states that stage, back up, combine and report read the
# URI from their task definition, and data-migration's loaders/s3_stage.py
# stages generations into the dt=/gen= convention for all ten families. That is
# why outputs.tf publishes local.all_dataset_prefixes rather than leaving each
# consumer to compose a prefix and risk composing it differently.
#
# The record formats are worth one line each, because they are what makes these
# objects large enough for the multipart rule below to matter and small enough
# for the transition minimum to matter: the reject stream is RECFM=F,LRECL=430
# (app/jcl/POSTTRAN.jcl:L36) written as DALYREJS(+1) at L38; system-generated
# interest transactions are RECFM=F,LRECL=350 (app/jcl/INTCALC.jcl:L39) written
# as SYSTRAN(+1) at L41; transaction backups are LRECL=350,RECFM=FB
# (app/jcl/TRANBKP.jcl:L31); the report is LRECL=133,RECFM=FB
# (app/jcl/TRANREPT.jcl:L78); the category-balance backup is LRECL=50,RECFM=FB
# (app/jcl/PRTCATBL.jcl:L37); and the three reference backups are LRECL 60, 60
# and 50 (app/jcl/DEFGDGD.jcl:L42, L65 and L88). The per-family lineage table
# lives in README.md and in each family's own `description` in variables.tf.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# Refactoring Rationale: what replaces IDCAMS REPRO, and what has no analogue.
#
# A baseline generation is written by a shared procedure rather than by each
# job: app/proc/REPROC.prc:L21-L28 is a single `EXEC PGM=IDCAMS` step named
# PRC001 with FILEIN and FILEOUT data definitions and SYSIN taken from
# `&CNTLLIB(REPROCT)`, and app/ctl/REPROCT.ctl:L15 is that member's one
# operative line, `REPRO INFILE(FILEIN) OUTFILE(FILEOUT)`. app/jcl/TRANBKP.jcl,
# app/jcl/TRANREPT.jcl and app/jcl/PRTCATBL.jcl all reach a generation write by
# overriding PRC001.FILEIN and PRC001.FILEOUT on that procedure. Per AAP rule
# T6 the REPRO copy itself becomes an ETL or export step writing a new S3
# generation, so THIS MODULE SUPPLIES THE DESTINATION AND PERFORMS NO COPY --
# no resource below moves data, and none should be added that does.
#
# Refactoring Rationale: the baseline's own idempotency idiom has no analogue
# here, and the comparison is instructive rather than decorative. Each of the
# six `DEFINE GENERATIONDATAGROUP` statements in app/jcl/DEFGDGB.jcl is
# followed by `IF LASTCC=12 THEN SET MAXCC=0` -- at L29, L35, L41, L47, L53 and
# L59 -- which resets the condition code so that re-running the job over
# already-existing bases does not fail. Terraform is idempotent by
# construction: a second apply over existing resources is a no-op that reports
# no changes, so there is no condition code to reset and no equivalent line to
# write. The re-runnability the baseline had to encode explicitly, once per
# base, is a property of the tool here. (app/jcl/DALYREJS.jcl:L24-L27 defines
# the tenth base without that reset, so even in the baseline the idiom is
# applied unevenly -- another thing a declarative tool removes the chance to
# get wrong.)
# -----------------------------------------------------------------------------

# Assumptions: the account id and the region are read from the caller's own
# session rather than accepted as inputs, so the composed bucket name cannot
# disagree with the account and region the apply is actually running against.
# Taking either as a variable would let a caller pass one value and apply into
# another, producing a name that claims an account it does not occupy.
data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

# Assumptions: the `region` attribute is read, NOT the older `name`. Verified
# against the pinned provider rather than assumed: on hashicorp/aws 6.57.1
# `data.aws_region.current.name` makes `terraform validate` emit "name is
# deprecated. Use region instead.", while `region` validates silently. The
# warning is the whole reason for the choice -- both attributes still return
# the same value today, so nothing but the deprecation distinguishes them, and
# a warning left in the output is a warning the next reader has to re-diagnose.
data "aws_region" "current" {}

locals {
  # Assumptions: the S3 bucket name namespace is GLOBAL across every AWS
  # account, not scoped to this one, so a bare `carddemo-datasets-dev` would
  # collide with any account that had already claimed it and the failure would
  # arrive at create time as an ownership conflict. Appending the account id
  # and the region makes the name deterministic and effectively collision-free
  # while staying reproducible: the same inputs in the same account and region
  # always compose the same name, so a state loss and re-import find the bucket
  # they left behind.
  # This is deliberately the same shape infra/bootstrap uses for its own
  # bucket, `<name_prefix>-tfstate-<12-digit-account-id>-<region>`, documented
  # at infra/bootstrap/variables.tf:L123-L124 and composed from the same three
  # ingredients per L135 and L169. The symmetry is intended -- an operator
  # reading either name can tell which account and region it belongs to without
  # consulting state -- and the differing middle segment (`-datasets-` against
  # `-tfstate-`) is what keeps the two from ever resolving to one name.
  # AAP section 0.4.1.7 writes the convention illustratively as
  # `s3://carddemo-datasets-<env>/...`; the account and region suffix satisfies
  # that same convention while guaranteeing global uniqueness, and the
  # `<domain>/<dataset>/dt=.../gen=.../` KEY structure -- the part every
  # consumer actually depends on -- is unchanged by it.
  # Alternatives Considered: a `random_id` suffix, the usual answer to a global
  # namespace. Rejected twice over: it would add the hashicorp/random provider
  # that versions.tf deliberately omits and that
  # terraform_unused_required_providers would then police, and it would make
  # the bucket name unreproducible, so a lost state file could no longer be
  # reconciled with the bucket it described.
  # The 63-character S3 limit is already guaranteed by the twelve-character
  # caps validated on name_prefix and environment in variables.tf; the budget
  # arithmetic lives there, on the inputs that have to satisfy it.
  bucket_name       = "${var.name_prefix}-datasets-${var.environment}-${data.aws_caller_identity.current.account_id}-${data.aws_region.current.region}"
  audit_bucket_name = "${var.name_prefix}-dataset-audit-${var.environment}-${data.aws_caller_identity.current.account_id}-${data.aws_region.current.region}"
  audit_trail_name  = "${var.name_prefix}-${var.environment}-dataset-object-access"
  audit_trail_arn   = "arn:${data.aws_partition.current.partition}:cloudtrail:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:trail/${local.audit_trail_name}"

  # Trade-offs: the prefix is derived ONCE here rather than written as a
  # literal in the lifecycle filters and again in outputs.tf. Two copies of the
  # same string would be free to drift, and the consequence of drift is silent
  # rather than loud: outputs.tf feeds the prefix that consumers WRITE to --
  # the step-functions-batch container overrides and
  # data-migration/src/carddemo_migration/loaders/s3_stage.py -- while the
  # lifecycle filter selects the prefix that retention GOVERNS. Let those two
  # diverge and every write still succeeds, no plan reports anything, and the
  # retention rule quietly applies to a prefix nobody writes to while the
  # prefix everybody writes to keeps every generation forever. Deriving both
  # from this map makes that class of divergence unrepresentable.
  # The key is the map key and the leading segment is the owning bounded
  # context's schema, so the prefix reads `<domain>/<dataset>/` and covers
  # every dt= and gen= child beneath it without enumerating any of them.
  dataset_prefixes = {
    for key, family in var.dataset_families :
    key => "${family.domain}/${key}/"
  }

  # Assumptions: these two are held apart from the ten deliberately and are not
  # an eleventh and twelfth generation family. Neither statement artifact has a
  # GENERATIONDATAGROUP base anywhere in the baseline -- an exhaustive search
  # for that keyword matches only app/jcl/DEFGDGB.jcl, app/jcl/DEFGDGD.jcl,
  # app/jcl/DALYREJS.jcl and app/jcl/REPTFILE.jcl, and none of them defines a
  # statement base. Both are plain sequential datasets, deleted and rewritten
  # each run: app/jcl/CREASTMT.JCL:L71 deletes STATEMNT.HTML and L75 deletes
  # STATEMNT.PS before CBSTM03A writes both fresh. Merging them into
  # dataset_families would raise the generation count to twelve and put this
  # module out of step with the ten that variables.tf asserts and that
  # docs/architecture/batch-orchestration.md and data-migration/README.md
  # publish independently.
  non_generation_dataset_prefixes = {
    for key, entry in var.non_generation_prefixes :
    key => "${entry.domain}/${key}/"
  }

  # Assumptions: this merged twelve-entry map is THE single source of truth for
  # every prefix in the module. outputs.tf publishes it, so a consumer resolves
  # any prefix -- generation or sequential -- through one lookup without needing
  # to know which inventory a dataset came from; and every lifecycle filter
  # below reads its prefix from THIS map rather than from the two component maps
  # directly. That is deliberate and is the structural half of the anti-drift
  # argument made on `dataset_prefixes` above: because the prefix that retention
  # GOVERNS and the prefix that consumers WRITE TO are now literally the same
  # expression, they cannot disagree. Reading the filters from the component
  # maps instead would leave them merely equal by construction today and free to
  # diverge under a later edit.
  # The two component maps stay separate above because the distinction between
  # them governs which retention RATIONALE applies -- ten reproduce LIMIT(5),
  # two are ordinary version hygiene -- and that distinction is what the `gdg-`
  # and `seq-` rule identifiers preserve. They are joined only here, where it no
  # longer matters which inventory a key came from.
  # Assumptions: the two key sets are disjoint, so the merge cannot lose an
  # entry to a collision. variables.tf pins both exactly -- ten named
  # generation families and the two named statement prefixes -- and no name
  # appears in both, so the twelve keys here are always twelve.
  all_dataset_prefixes = merge(local.dataset_prefixes, local.non_generation_dataset_prefixes)

  # Assumptions: S3 REQUIRES an age gate on a noncurrent-version expiry -- on
  # the pinned provider `noncurrent_days` is a required argument of
  # `noncurrent_version_expiration`, and omitting it fails validation outright
  # with "The argument \"noncurrent_days\" is required". A purely count-based
  # rule is therefore not expressible, so this constant is set to the smallest
  # legal value, one day.
  # What that means, and what it does NOT mean: the retention DECISION stays
  # count-based and is carried entirely by `newer_noncurrent_versions` below.
  # This value only gates how soon a version that is ALREADY beyond the
  # retained count becomes eligible for deletion. One day makes the roll-off as
  # prompt as S3 permits, so the rule behaves as a count cap rather than as an
  # age policy -- which is the whole point, because LIMIT(5) counts generations
  # and never ages them. Raising this number would start retaining more than
  # five generations whenever the chain runs faster than the gate, silently
  # converting a count cap into a hybrid.
  noncurrent_expiration_min_age_days = 1
}

resource "aws_s3_bucket" "datasets" {
  bucket = local.bucket_name

  # Trade-offs: false by default, which means `terraform destroy` FAILS against
  # a populated bucket instead of emptying it, and teardown therefore requires
  # an explicit, documented purge first. The alternative default would let a
  # destroy aimed at any other resource in the root take every retained dataset
  # generation with it, silently and without a second confirmation -- and these
  # generations are the only copy, because the baseline's own datasets are not
  # written by this stack. A failed destroy is recoverable; a deleted backup
  # history is not.
  # A versioned bucket is affected more than an unversioned one: deleting the
  # current version of every object is not sufficient, because each noncurrent
  # version and each delete marker must also be removed before the bucket
  # itself will delete, so the purge is a version-aware operation rather than a
  # recursive object delete.
  # infra/bootstrap/variables.tf:L271-L272 makes the same choice for the state bucket
  # and documents the matching manual purge in docs/runbooks/teardown.md
  # (referenced at L272 of that file), so an operator learns one convention
  # rather than one per bucket.
  force_destroy = var.force_destroy

  # Assumptions: the calling root configures `default_tags` on its aws
  # provider, so every resource here is already carrying the account-wide tag
  # set before this argument is read. `var.tags` therefore adds only tags
  # meaningful to this module or to one environment, and on a key collision the
  # resource-level tag wins over the provider default -- which is what makes a
  # targeted override possible at all.
  tags = var.tags
}

# Two capabilities a reviewer may expect on a bucket holding the only copy of a
# backup history are deliberately absent, and both absences are recorded here
# rather than left to be rediscovered. Neither is expressed as a scanner
# exemption: the gates in this tree are satisfied by construction, so where a
# check is declined the reason is written as prose and the check stays visible.
#
# Trade-offs: NO CROSS-REGION REPLICATION. It is the obvious protection for
# data whose loss is unrecoverable, and it is declined because AAP section 0.2.2
# places multi-region and disaster-recovery topology out of scope for this
# migration -- the target is a single region across three availability zones,
# and S3 already replicates across availability zones within one region. Adding
# a replica would also need a second bucket, a replication role and a key in the
# destination region, none of which this module's inputs describe, so it would
# be a topology change rather than a setting. The accepted exposure is a
# region-level loss event; the mitigation, if that ever comes into scope, is a
# replication configuration added alongside this bucket rather than a change to
# any rule below.
#
# Trade-offs: NO EVENT NOTIFICATIONS. A bucket that receives batch output is a
# natural place to trigger downstream work from an object-created event, and
# that is precisely why it is declined: the batch chain is orchestrated
# explicitly, by EventBridge Scheduler driving a Step Functions state machine
# whose states run in a defined order with retry, catch and timeout per state.
# Emitting an event per object would introduce a SECOND, implicit trigger path
# alongside that state machine, so a step could begin because an object landed
# rather than because the orchestrator advanced -- which is exactly the
# ordering guarantee the JCL step sequence provides and the migration has to
# preserve. There is also no target to notify: no topic, queue or function
# appears in this module's input contract.

# Bucket versioning is RECOVERY INSIDE a logical generation, not the generation
# mechanism itself. The AAP's distinct dt=/gen= key convention means two
# generations are different object keys and can never become versions of one
# another. data-migration/src/carddemo_migration/loaders/s3_stage.py therefore
# centralises the one
# unavoidable application-side rule: enumerate generation prefixes, sort by
# business date and generation number, keep the newest configured count, and
# delete every version/delete marker beneath the rest. Every writer calls that
# one implementation, so SCRATCH semantics are not copied seven times.
#
# The two IDCAMS keywords justify the two layers jointly. LIMIT(5) is the
# writer's prefix-count boundary. SCRATCH is its permanent version-aware delete
# of prefixes that roll off. Versioning and the lifecycle below protect and
# bound accidental repeat writes to an object key that is still retained.
#
# All TEN baseline bases are defined LIMIT(5) with SCRATCH, and the citation set
# is given in full because the count is the most error-prone fact in this module
# -- six is what app/jcl/DEFGDGB.jcl yields on its own, and that file looks
# complete:
#   app/jcl/DEFGDGB.jcl:L25-L27, L31-L33, L37-L39, L43-L45, L49-L51, L55-L57
#   app/jcl/DEFGDGD.jcl:L28-L30, L51-L53, L74-L76
#   app/jcl/DALYREJS.jcl:L24-L27
#
# Assumptions: a noncurrent-version lifecycle action REQUIRES versioning to be
# enabled -- S3 rejects the rule otherwise, because there are no noncurrent
# versions for it to act on. That is why the lifecycle configuration below
# declares an explicit dependency on this resource rather than relying on
# Terraform to infer an order from the bucket reference the two share.
resource "aws_s3_bucket_versioning" "datasets" {
  bucket = aws_s3_bucket.datasets.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "datasets" {
  bucket = aws_s3_bucket.datasets.id

  rule {
    # Refactoring Rationale: the storage this bucket replaces carried neither
    # encryption nor journalling. All eight file resources in
    # app/csd/CARDDEMO.CSD are defined RECOVERY(NONE) with JOURNAL(NO) -- the
    # pairs appear at L7 and L9, L19 and L21, L31 and L33, L44 and L46, L57 and
    # L59, L70 and L72, L82 and L84, and L94 and L96 -- so a dataset generation
    # was a plain VSAM cluster with no journal behind it. The target's answer is
    # a rotating customer-managed key plus the bucket versioning above,
    # addressing the confidentiality and the recoverability halves separately.
    # A customer-managed key rather than the AWS-managed default because AAP
    # section 0.4.1.9 assigns one of four CMKs to S3 specifically: with a key
    # per data class, a key-policy change or a compromise is scoped to this
    # bucket alone instead of reaching the database, the secrets and the queues
    # at the same time. `var.kms_key_arn` is a required input with no default,
    # so no caller can reach an unencrypted bucket by omission.
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = var.kms_key_arn
    }

    # Trade-offs: an S3 Bucket Key lets S3 derive one data key per bucket and
    # day and reuse it across objects, instead of issuing a KMS Decrypt or
    # GenerateDataKey call for EVERY object read and written. The batch chain
    # writes and re-reads many objects per generation across twelve prefixes,
    # so the per-object call pattern is exactly the shape that multiplies KMS
    # request volume -- and KMS requests are both billed per call and subject
    # to a per-region rate quota that a large export can approach. The accepted
    # cost is coarser granularity: CloudTrail then records key use per bucket
    # and day rather than per object, so per-object cryptographic attribution
    # is lost. Server access logging below, when a root supplies a target,
    # covers per-object access attribution instead.
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "datasets" {
  bucket = aws_s3_bucket.datasets.id

  # Assumptions: NO OBJECT IN THIS BUCKET IS EVER PUBLIC. It holds account,
  # card, customer and transaction extracts derived from the baseline masters --
  # the reject stream alone carries the 430-byte record contract
  # (app/jcl/POSTTRAN.jcl:L36) built from real card and account data, and the
  # statement artifacts carry customer names and addresses.
  # All four flags are set rather than some subset because each one closes a
  # DIFFERENT route to public access, and leaving any one off leaves that route
  # open:
  #   block_public_acls ....... rejects a request that ATTACHES a public ACL,
  #                             including at object-creation time
  #   block_public_policy ..... rejects a BUCKET POLICY that grants public
  #                             access, so the TLS-only policy below cannot
  #                             later be replaced by a permissive one
  #   ignore_public_acls ...... ignores public ACLs ALREADY present, which is
  #                             what the first flag cannot do, since it only
  #                             refuses new ones
  #   restrict_public_buckets . confines access to principals in this account
  #                             and to authorised service principals, cutting
  #                             off anonymous and cross-account public reach
  #                             even where a policy would otherwise allow it
  # The first two refuse new grants and the second two neutralise existing
  # ones, so the pairs are complementary rather than redundant.
  # infra/bootstrap sets the same four on the state bucket for the same reason.
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "datasets" {
  bucket = aws_s3_bucket.datasets.id

  rule {
    # Alternatives Considered: leaving ACLs enabled, which is the historical S3
    # default and would have worked. Rejected because BucketOwnerEnforced
    # DISABLES ACLs ENTIRELY, so access is decided by bucket policy and IAM
    # alone. That collapses two overlapping permission systems into one
    # auditable one: with ACLs live, an object can carry a grant that widens
    # access beyond anything the policy says, and answering "who can read this
    # object" means reading the policy, the bucket ACL and every object ACL.
    # With ACLs disabled the policy and IAM are the complete answer.
    # It also removes a whole class of cross-account confusion, because every
    # object is owned by this account regardless of which principal wrote it --
    # relevant here because batch tasks and the ETL both write these prefixes.
    object_ownership = "BucketOwnerEnforced"
  }
}

# Alternatives Considered: the policy is built with this data source rather than
# as a heredoc JSON string. Rejected the heredoc because this source parses and
# type-checks the document at PLAN time and keeps the bucket-ARN references
# typed, so a misspelled key, a malformed condition or a wrong nesting level is
# reported before anything is created. A heredoc is an opaque string to
# Terraform: every one of those mistakes would survive the plan and surface as
# an API rejection partway through the apply, with the bucket already created.
data "aws_iam_policy_document" "tls_only" {
  statement {
    sid    = "DenyNonTlsRequests"
    effect = "Deny"

    # Assumptions: AAP section 0.7.8 requires encryption in transit end to end,
    # and a bucket policy is the ONLY place S3 can actively REFUSE a plaintext
    # request. Without this statement an `http://` request is served normally --
    # S3 accepts both schemes, and neither the public-access block above nor
    # SSE-KMS (which encrypts at rest, not in flight) declines one.
    actions = ["s3:*"]

    # Assumptions: BOTH ARN forms are required, and this is the non-obvious
    # half. Bucket-level operations such as ListBucket and GetBucketLocation are
    # authorised against the BUCKET ARN, while object-level operations such as
    # GetObject and PutObject are authorised against the OBJECT ARN pattern.
    # They are distinct resources to IAM, so a policy naming only one leaves the
    # other reachable over plain HTTP -- naming only the bucket would still serve
    # every object insecurely, and naming only the objects would still permit an
    # unencrypted listing that discloses every key in the bucket.
    resources = [
      aws_s3_bucket.datasets.arn,
      "${aws_s3_bucket.datasets.arn}/*",
    ]

    # Assumptions: a Deny must apply to every principal to be effective. Scoping
    # it to named principals would leave the deny silent for anyone not listed,
    # which for a transport-security control is the opposite of the intent -- the
    # rule is about HOW a request arrives, not about WHO sends it.
    principals {
      type        = "*"
      identifiers = ["*"]
    }

    # Assumptions: the condition tests SecureTransport being false rather than
    # negating a test for true. The two are not equivalent in IAM: `Bool` with
    # "false" matches only requests that genuinely arrived without TLS, whereas
    # a negated-condition form can also match when the key is absent, which
    # widens a Deny in ways that are hard to reason about.
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "datasets" {
  bucket = aws_s3_bucket.datasets.id
  policy = data.aws_iam_policy_document.tls_only.json

  # Assumptions: the public-access block must be in place BEFORE a policy is
  # attached. Its `block_public_policy` flag is what causes S3 to reject a
  # policy granting public access, so applying it first means the guard is
  # active for this attachment and every later one. Terraform would otherwise be
  # free to order the two either way, since neither references the other -- they
  # only share a reference to the bucket -- so the ordering is stated rather
  # than left to chance.
  depends_on = [aws_s3_bucket_public_access_block.datasets]
}

# THE LIMIT(5) SCRATCH ANALOGUE. Twelve prefix-scoped rules -- ten for the
# generation-dataset families and two for the sequential statement artifacts --
# plus one bucket-wide housekeeping rule documented at the end of the resource.
#
# 1. Assumptions: TEN generation families, not six. This is the single most
#    error-prone fact in the module, because app/jcl/DEFGDGB.jcl defines six
#    bases in one IDCAMS step under the heading "DEFINE GDG BASES NEEDED BY
#    CARDDEMO PROJECT" (L19) and reads as the complete inventory. It is not.
#    Three more are defined in app/jcl/DEFGDGD.jcl (L28, L51, L74) and one in
#    app/jcl/DALYREJS.jcl (L25) -- the last easiest of all to miss, because it
#    sits in a job named for the reject dataset rather than in either DEFGDG*
#    job. Provisioning six would fail silently in the worst way: the four
#    missing families' batch steps would still write their objects, into a
#    prefix carrying no lifecycle rule, so nothing would error and generations
#    would accumulate without limit.
#    The count is why the rules below are driven by `for_each` over
#    `var.dataset_families` rather than by a hand-written list -- the rule count
#    follows the data, and variables.tf asserts that data is exactly ten with a
#    `length == 10` validation, so a short inventory fails at plan time instead
#    of under-provisioning quietly. docs/architecture/batch-orchestration.md and
#    data-migration/README.md publish the same ten independently, so all three
#    must agree.
#    (An exhaustive search for DEFINE GENERATIONDATAGROUP matches four files and
#    yields eleven statements over ten DISTINCT base names: TRANREPT is defined
#    twice, at app/jcl/DEFGDGB.jcl:L37 with LIMIT(5) SCRATCH and again at
#    app/jcl/REPTFILE.jcl:L25-L28 with LIMIT(10) and no SCRATCH. The base NAMES
#    are still ten; the LIMIT(5) definition is the one applied, and the conflict
#    is recorded on `dataset_families` in variables.tf.)
#
# 2. Alternatives Considered: `newer_noncurrent_versions` -- a COUNT -- rather
#    than a days-based expiry. LIMIT(5) is COUNT-BASED, NOT AGE-BASED, and a
#    days-based rule is the trap here because it looks equivalent, passes every
#    gate, and then deletes the wrong generations. It fails in both directions:
#    it OVER-DELETES when the nightly chain pauses, because five perfectly good
#    generations older than the threshold vanish even though they are still the
#    five most recent; and it UNDER-DELETES when the chain runs hot, because
#    more than five survive inside the window. That logic applies to LOGICAL
#    generation prefixes in the staging writer, not to this lifecycle action:
#    `newer_noncurrent_versions` counts versions of ONE object key and cannot
#    compare `gen=0001/...` with `gen=0002/...`. It remains useful as bounded
#    recovery for repeat writes to the same key. `noncurrent_days` is still
#    supplied because the provider requires it, pinned to the one-day minimum
#    in `local.noncurrent_expiration_min_age_days` as an eligibility gate.
#
# 3. Trade-offs: one prefix-scoped rule per family rather than a single
#    bucket-wide rule. The cost is twelve rules where one would have compiled,
#    and it is accepted deliberately for two reasons. Per-family retention
#    becomes overridable for one environment through the optional
#    `noncurrent_versions` member without touching the prefix topology -- which
#    variables.tf requires stay identical across environments -- and each rule's
#    `prefix` filter is the AUDITABLE LINK back to the generation base it
#    replaces, so a reviewer can match a rule to a line of JCL. A single
#    bucket-wide rule would retain five versions of everything with no way to
#    tell which baseline contract each retention was serving.
#
# 4. Assumptions: the noncurrent-version actions require versioning enabled, so
#    this configuration takes an explicit dependency on the versioning resource.
#    Both reference the same bucket but neither references the other, so without
#    the dependency Terraform may attach a noncurrent-version rule to a
#    still-unversioned bucket on a first apply, which S3 rejects.
#
# 5. Assumptions: every rule carries a `filter`, and every rule that EXPIRES or
#    TRANSITIONS a version is prefix-scoped. A retention rule left filterless
#    would apply to the whole bucket, imposing one retention policy on every
#    dataset at once and erasing the per-family control point 3 exists to
#    provide. The single bucket-wide rule at the end of this resource is
#    deliberately exempt from that scoping because it carries no retention
#    action at all; its reasoning is recorded there.
resource "aws_s3_bucket_lifecycle_configuration" "datasets" {
  bucket = aws_s3_bucket.datasets.id

  # Trade-offs: set explicitly, and matching the service's current default, so
  # the value is pinned and reviewable rather than inherited. The argument is
  # `computed`, meaning an unset value silently adopts whatever the API returns
  # -- and AWS has already changed this default once, introducing the 128 KB
  # floor where transitions previously had none. Pinning it means a future
  # service-side change cannot alter this bucket's behaviour without a visible
  # diff. It is stated for a concrete reason rather than for tidiness: the
  # reference-data generations are genuinely small (LRECL 50 and 60 per
  # app/jcl/DEFGDGD.jcl:L42, L65 and L88), so a full reference backup can fall
  # under 128 KB, and with this floor in force such an object never transitions
  # at all even once a root enables the transition below. That is the intended
  # behaviour -- transitioning an object that small bills a per-object
  # transition request plus per-object metadata overhead in the destination
  # class that can exceed the storage it saves -- but it is behaviour a reader
  # would otherwise have to discover from a bill rather than from the code.
  transition_default_minimum_object_size = "all_storage_classes_128K"

  # The ten generation families. These rules govern noncurrent OBJECT VERSIONS
  # inside each family; logical generation-prefix retention is enforced by the
  # staging writer using the same configured counts.
  dynamic "rule" {
    for_each = var.dataset_families

    content {
      # Assumptions: the id is derived from the map key and carries a `gdg-`
      # marker, so a rule found in the console or in a plan diff is traceable
      # to its family and therefore to the GENERATIONDATAGROUP base it
      # replaces. The marker is what distinguishes these ten from the two
      # `seq-` rules below, whose retention means something different.
      id     = "gdg-${rule.key}"
      status = "Enabled"

      filter {
        prefix = local.all_dataset_prefixes[rule.key]
      }

      noncurrent_version_expiration {
        # Assumptions: the per-family override wins when set and the
        # module-wide value applies otherwise, which is the precedence
        # variables.tf documents -- `noncurrent_versions` is left unset on all
        # ten entries in the default, so every family inherits five. That number
        # is shared with the logical-generation cleanup contract, but this block
        # itself retains repeat writes of the SAME key only. `coalesce` is the
        # whole precedence rule: an unset optional member arrives as null and
        # falls through to the module-wide value.
        newer_noncurrent_versions = coalesce(rule.value.noncurrent_versions, var.noncurrent_version_retention)
        noncurrent_days           = local.noncurrent_expiration_min_age_days
      }

      # Assumptions: the transition block is emitted ONLY when a root opted in
      # by setting a non-null day count, which is why it is a nested `dynamic`
      # over a one-or-zero-element list rather than a plain block. Both of the
      # block's own arguments are required by the provider, so a block emitted
      # with a null day count would fail validation -- the conditional is what
      # makes the disabled default (and therefore dev) expressible at all.
      dynamic "noncurrent_version_transition" {
        for_each = var.noncurrent_version_transition_days == null ? [] : [var.noncurrent_version_transition_days]

        content {
          noncurrent_days = noncurrent_version_transition.value
          storage_class   = var.noncurrent_version_transition_storage_class
        }
      }

      # Assumptions: the batch exports write whole generations in one object,
      # large enough that the SDK is expected to choose a multipart upload --
      # the transaction backup carries 350-byte records over the full master
      # (app/jcl/TRANBKP.jcl:L31) and the report is 133 columns per selected
      # transaction (app/jcl/TRANREPT.jcl:L78). A Fargate batch task killed
      # part-way through such an upload leaves its uploaded parts behind, and
      # those parts are STORED AND BILLED WHILE INVISIBLE AS OBJECTS: they
      # appear in no bucket listing, so neither the noncurrent-version expiry
      # above nor any object-level rule can reach them, and NOTHING BUT THIS
      # RULE ever removes them. Left unset, the cost grows with every
      # interrupted run and no listing reveals why.
      abort_incomplete_multipart_upload {
        days_after_initiation = var.abort_incomplete_multipart_upload_days
      }
    }
  }

  # The two non-generation statement artifacts.
  #
  # Assumptions: THIS RETENTION IS ORDINARY VERSION HYGIENE AND IS NOT THE
  # LIMIT(5) SCRATCH ANALOGUE. Neither statement dataset has a
  # GENERATIONDATAGROUP base anywhere in the baseline -- both are plain
  # sequential datasets that app/jcl/CREASTMT.JCL deletes and rewrites each run,
  # HTML at L71 and plain text at L75. They acquire noncurrent versions only
  # because versioning is a BUCKET-WIDE setting that cannot be scoped to a
  # prefix, so a rule is needed to stop those versions accumulating. Reading
  # these two as generation families would invent a generation contract the
  # baseline never had for them, and would make the family count twelve.
  # Trade-offs: they share the module-wide retention count rather than carrying
  # a separate variable, so the bucket has one retention story instead of two.
  # The accepted imprecision is that a number chosen to mean "five generations"
  # also governs two datasets that have no generations; the alternative -- a
  # thirteenth variable read by exactly two rules -- buys nothing, because the
  # baseline retained ZERO previous copies of these files (it deleted them
  # outright), so any positive number here is already strictly more recoverable
  # than the baseline and none is more faithful than another.
  dynamic "rule" {
    for_each = var.non_generation_prefixes

    content {
      # Assumptions: the `seq-` marker distinguishes these from the `gdg-`
      # rules above at a glance, in the console and in a plan diff, so the
      # different meaning of their retention is visible without cross-checking
      # which inventory the key came from.
      id     = "seq-${rule.key}"
      status = "Enabled"

      filter {
        prefix = local.all_dataset_prefixes[rule.key]
      }

      noncurrent_version_expiration {
        newer_noncurrent_versions = var.noncurrent_version_retention
        noncurrent_days           = local.noncurrent_expiration_min_age_days
      }

      dynamic "noncurrent_version_transition" {
        for_each = var.noncurrent_version_transition_days == null ? [] : [var.noncurrent_version_transition_days]

        content {
          noncurrent_days = noncurrent_version_transition.value
          storage_class   = var.noncurrent_version_transition_storage_class
        }
      }

      abort_incomplete_multipart_upload {
        days_after_initiation = var.abort_incomplete_multipart_upload_days
      }
    }
  }

  # One bucket-wide housekeeping rule, and the ONLY rule here that is not scoped
  # to a dataset prefix.
  #
  # Assumptions: an incomplete multipart upload can be initiated against ANY
  # key, including one that matches none of the twelve dataset prefixes above --
  # a mistyped prefix, an ad-hoc staging path used during an investigation, or a
  # key written by a future consumer before its prefix is added to the
  # inventory. A prefix-scoped abort rule only reclaims parts whose key matches
  # its prefix, so parts left anywhere else would be stored and billed
  # indefinitely while remaining invisible as objects. The twelve rules above
  # therefore cannot on their own make the guarantee their own abort comment
  # claims; this rule is what completes it.
  #
  # Trade-offs: this deliberately overlaps the per-family abort blocks rather
  # than replacing them. Both specify the same number of days, so for a key
  # under one of the twelve prefixes the two agree and the outcome is identical
  # -- there is no conflict to resolve. The redundancy is accepted because the
  # per-family block states the policy where the generations it protects are
  # declared, which is where a reader looking at one family will find it, while
  # this rule guarantees that no key in the bucket is left uncovered. Deleting
  # either one would still leave a working configuration; deleting this one
  # would silently reopen the gap described above.
  #
  # Assumptions: this rule carries NO retention action -- no
  # noncurrent_version_expiration and no transition -- and that is what makes a
  # bucket-wide scope safe here. Every rule that expires or transitions a
  # version stays prefix-scoped, because a bucket-wide retention rule would
  # apply one retention policy to every dataset at once and erase the per-family
  # control the twelve rules exist to provide. Aborting an incomplete upload
  # deletes no object version, so it cannot affect generation retention.
  #
  # Assumptions: `prefix = ""` is the documented way to match every object while
  # still declaring a filter, so the rule is scoped bucket-wide without being
  # filterless. Verified against the pinned provider rather than assumed.
  rule {
    id     = "housekeeping-abort-incomplete-uploads"
    status = "Enabled"

    filter {
      prefix = ""
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = var.abort_incomplete_multipart_upload_days
    }
  }

  depends_on = [aws_s3_bucket_versioning.datasets]
}

resource "aws_lambda_permission" "dataset_generation_retention" {
  statement_id   = "AllowDatasetGenerationRetentionFromS3"
  action         = "lambda:InvokeFunction"
  function_name  = var.object_created_lambda_arn
  principal      = "s3.amazonaws.com"
  source_arn     = aws_s3_bucket.datasets.arn
  source_account = data.aws_caller_identity.current.account_id

  # WHY : Assumptions: both SourceArn and SourceAccount are required. The bucket
  #       ARN binds invocation to this bucket, while the account condition blocks
  #       a confused-deputy request from a same-named bucket in another account.
}

resource "aws_s3_bucket_notification" "datasets" {
  bucket = aws_s3_bucket.datasets.id

  lambda_function {
    lambda_function_arn = var.object_created_lambda_arn
    events              = ["s3:ObjectCreated:*"]
  }

  # WHY : Refactoring Rationale: generation writers create distinct
  #       `<family>/dt=.../gen=.../` keys, so S3's noncurrent-version count
  #       cannot see generation six. Object-created notification covers nightly,
  #       retry and ad-hoc writers through one retention path.
  # WHY : Assumptions: the permission must exist before S3 validates and stores
  #       the notification configuration; otherwise first apply fails even
  #       though the function and bucket both exist.
  depends_on = [aws_lambda_permission.dataset_generation_retention]
}

resource "aws_s3_bucket_logging" "datasets" {
  # Trade-offs: conditional rather than mandatory, so the module stays
  # instantiable without a pre-existing log bucket -- requiring one would make a
  # logging bucket a precondition of every consumer, including a throwaway test
  # root, and would invert the dependency between this module and whatever
  # provisions that bucket.
  # The consequence is stated rather than glossed: the complete Checkov scan
  # reports access logging on a bucket holding financial extracts, while the
  # environment roots are expected to supply a target and leaving this null in
  # dev or prod is not the intended end state. If the scanner flags this bucket,
  # THE FIX IS TO PASS A TARGET FROM THE ROOT -- never an inline suppression.
  # The gates in this tree are satisfied by construction rather than by
  # exemption, and a suppression comment here would convert a real finding into
  # a permanent blind spot.
  count = var.access_log_bucket_name == null ? 0 : 1

  bucket = aws_s3_bucket.datasets.id

  # Assumptions: the target must be a DIFFERENT bucket from this one. Aiming a
  # bucket's server access logs at itself makes each delivered log object a
  # loggable write, which generates a further log object, and the bucket grows
  # without bound from its own logging. Nothing in the type system prevents a
  # caller passing this bucket's own name, so the constraint is documented on
  # the input in variables.tf and again here at the point of use.
  target_bucket = var.access_log_bucket_name

  # Assumptions: the delivered logs are namespaced by this bucket's own composed
  # name, so one shared log bucket can receive logs from the dev and prod
  # dataset buckets -- and from other buckets entirely -- without interleaving
  # them into one flat prefix that no query can separate afterwards.
  target_prefix = "s3-access-logs/${local.bucket_name}/"
}

resource "aws_s3_bucket" "audit" {
  #checkov:skip=CKV_AWS_145:This dedicated CloudTrail delivery bucket uses SSE-S3 so audit delivery does not require a service-principal grant on the financial dataset CMK; validation, public-access blocking and the exact-source bucket policy provide the compensating controls.
  bucket        = local.audit_bucket_name
  force_destroy = false

  tags = merge(var.tags, {
    Name        = local.audit_bucket_name
    Environment = var.environment
  })
}

resource "aws_s3_bucket_versioning" "audit" {
  bucket = aws_s3_bucket.audit.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "audit" {
  bucket = aws_s3_bucket.audit.id

  rule {
    apply_server_side_encryption_by_default {
      # WHY : Trade-offs: the financial dataset itself uses the project S3 CMK,
      #       while this destination uses SSE-S3 so CloudTrail delivery does not
      #       depend on broadening that data key's policy to a service principal.
      #       Log integrity is supplied by CloudTrail validation; access remains
      #       bounded by the dedicated bucket policy and public-access block.
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "audit" {
  bucket                  = aws_s3_bucket.audit.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "audit" {
  bucket = aws_s3_bucket.audit.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "audit" {
  bucket = aws_s3_bucket.audit.id

  rule {
    id     = "expire-after-compliance-retention"
    status = "Enabled"

    filter {}

    expiration {
      days = var.audit_log_retention_days + 1
    }

    noncurrent_version_expiration {
      noncurrent_days = var.audit_log_retention_days + 1
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = var.abort_incomplete_multipart_upload_days
    }
  }

  depends_on = [
    aws_s3_bucket_versioning.audit,
  ]
}

data "aws_iam_policy_document" "audit_bucket" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.audit.arn,
      "${aws_s3_bucket.audit.arn}/*",
    ]

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

  statement {
    sid       = "AllowCloudTrailAclCheck"
    effect    = "Allow"
    actions   = ["s3:GetBucketAcl"]
    resources = [aws_s3_bucket.audit.arn]

    principals {
      type        = "Service"
      identifiers = ["cloudtrail.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = [local.audit_trail_arn]
    }
  }

  statement {
    sid       = "AllowCloudTrailWrite"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.audit.arn}/AWSLogs/${data.aws_caller_identity.current.account_id}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudtrail.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = [local.audit_trail_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "s3:x-amz-acl"
      values   = ["bucket-owner-full-control"]
    }
  }
}

resource "aws_s3_bucket_policy" "audit" {
  bucket = aws_s3_bucket.audit.id
  policy = data.aws_iam_policy_document.audit_bucket.json

  depends_on = [aws_s3_bucket_public_access_block.audit]
}

resource "aws_cloudtrail" "dataset_object_access" {
  #checkov:skip=CKV_AWS_35:The trail writes only to the dedicated SSE-S3 audit bucket above; using the financial dataset CMK would widen that key to the CloudTrail service principal, while log-file validation and the exact-source bucket policy preserve integrity and admission.
  name                          = local.audit_trail_name
  s3_bucket_name                = aws_s3_bucket.audit.id
  include_global_service_events = false
  is_multi_region_trail         = false
  enable_log_file_validation    = true
  enable_logging                = true

  advanced_event_selector {
    name = "CardDemo dataset object reads and writes"

    field_selector {
      field  = "eventCategory"
      equals = ["Data"]
    }

    field_selector {
      field  = "resources.type"
      equals = ["AWS::S3::Object"]
    }

    field_selector {
      field       = "resources.ARN"
      starts_with = ["${aws_s3_bucket.datasets.arn}/"]
    }
  }

  depends_on = [aws_s3_bucket_policy.audit]

  tags = merge(var.tags, {
    Name        = local.audit_trail_name
    Environment = var.environment
  })
}
