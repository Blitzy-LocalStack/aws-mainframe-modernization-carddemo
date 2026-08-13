# =============================================================================
# infra/modules/s3-datasets/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The substance of the `s3-datasets` module: ONE versioned,
#   customer-managed-key-encrypted, publicly inaccessible S3 bucket carrying a
#   TLS-only bucket policy, plus prefix-scoped lifecycle rules for the TEN
#   generation-dataset families the baseline defines, the THREE non-generation
#   reporting artifacts and the ONE source-extract input prefix -- fourteen
#   prefix-scoped rules, and one further bucket-wide housekeeping rule that
#   carries no retention action.
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
#   Every variable declared in variables.tf is read by this file, so the module has
#   no input that reaches nothing. Their types, defaults and constraints are
#   documented on the `variable` blocks themselves and tabulated in the generated
#   region of README.md, rather than restated here where a second copy could drift
#   from the first.
#
# Return values:
#   No `output` is declared here; outputs.tf owns the module's return surface
#   and reads from this file. What it consumes is `aws_s3_bucket.datasets` --
#   its `id`, `arn` and `bucket` attributes -- together with
#   `local.all_dataset_prefixes`, the thirteen-entry map of family key to
#   `<domain>/<dataset>/` prefix that a batch state or an ETL loader needs in
#   order to address a generation. Renaming either the resource or that local
#   breaks outputs.tf, and adding a resource makes the generated Resources
#   table in README.md stale until it is regenerated.
#
# Errors:
#   Four failure modes, three of them at apply rather than at plan: a
#   `kms_key_arn` naming a key that does not exist, sits in another region or whose
#   policy does not permit S3 to use it, which leaves the bucket created and
#   unencrypted until the apply is corrected; a globally taken bucket name, which
#   the account id and region in local.bucket_name make improbable rather than
#   merely unlikely; a noncurrent-version lifecycle action attached before
#   versioning is enabled, which is why the lifecycle configuration declares an
#   explicit dependency on the versioning resource; and `terraform destroy` against
#   a populated bucket while `force_destroy` is false, which affects a versioned
#   bucket more because every noncurrent version and delete marker must be removed
#   before the bucket will delete.
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
#     bucket-wide rule, accepting fourteen rules to gain per-family retention and
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
# Note the deliberate ASYMMETRY on retention, which is the sharpest distinction
# between the two and the one most likely to be "helpfully" consolidated away.
# Both retentions are FINITE; what differs is how wide, how it is bounded, and
# how many layers there are.
#
#   infra/bootstrap  ONE layer. Its lifecycle rule declares
#                    `noncurrent_version_expiration` with
#                    `newer_noncurrent_versions = var.state_noncurrent_versions_to_retain`
#                    (default 20) and `noncurrent_days = var.state_version_retention_days`
#                    (default 365) -- so the newest twenty versions of a state
#                    file are retained regardless of age, and a version beyond
#                    that count still survives until it is at least a year old.
#                    Wide and age-gated, because every prior version of a state
#                    file is recovery material and is the only record of the
#                    infrastructure that version described.
#
#   this module      TWO layers, and they operate on different things. Logical
#                    generation cleanup -- performed by data-migration's staging
#                    writer, not by anything declared here -- keeps the newest
#                    five dt=/gen= PREFIXES and physically removes older
#                    prefixes, reproducing LIMIT(5) SCRATCH. Separately, the
#                    lifecycle rules below bound repeat writes of the SAME key
#                    inside a retained prefix at `newer_noncurrent_versions = 5`
#                    with `noncurrent_days = 1`, the smallest age the service
#                    accepts, so that rule behaves as a count cap rather than as
#                    an age policy.
#
# Refactoring Rationale: this paragraph previously read "infra/bootstrap declares
# NO noncurrent-version expiration at all". That was measurably false --
# infra/bootstrap/main.tf declares the rule quoted above, and bootstrap's own
# comment beside it already says "state history is finite but deliberately wider
# than dataset generation history". The false version was the more dangerous
# reading of the two: an operator who believed state versions never expired would
# not think to check the horizon before relying on a year-old version, and would
# not notice that raising the dataset retention here has no bearing on it.
# Identical resource types, deliberately different retention policies, both
# correct for what they hold.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# How the baseline addresses these datasets, and how consumers address them
# here. Recorded once, because every prefix and every lifecycle filter below
# depends on it and none of it is inferable from the HCL alone.
#
#   s3://<bucket>/<domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/
#
# The two relative-generation forms map onto that convention as LOGICAL PREFIX
# operations, and object versioning is a separate mechanism that plays no part in
# either:
#
#   `(+1)`  a NEW generation. The staging writer ALLOCATES a new gen=NNNN logical
#           prefix -- `reserve_generation` in data-migration's
#           loaders/s3_stage.py reserves the next number as a conditional create
#           keyed by execution, family and business date -- and the object is
#           written under it.
#   `(0)`   the CURRENT generation. The reader RESOLVES the newest existing
#           logical prefix for that family -- `latest_generation` /
#           `current_generation` in the same module -- and reads the object under
#           it.
#
# WHY : Assumptions: object versioning is stated here as what it is NOT, because
#       the two are easy to conflate and the conflation is load-bearing. Each
#       generation is written under a DISTINCT key, so S3 cannot see generation
#       six as a version of generation five, and no lifecycle rule can express
#       LIMIT(5) over generations. Versioning protects a REWRITE of one key --
#       the same generation staged twice by a retry -- and that is the only thing
#       the `noncurrent_version_expiration` rules below bound. Generation
#       identity is carried entirely by the prefix.
#
# Refactoring Rationale: this paragraph previously said `(+1)` "becomes a new
# gen=NNNN prefix and a new current object version" and `(0)` "becomes the
# current object version", and read the COMBTRAN.jcl sequence as "a read of two
# current versions, a write creating a third, and a read of what that write just
# made current". That described object-version creation and selection, which is a
# different mechanism from the one that implements generations, and it implied
# generation retention could be expressed as version retention -- the exact
# consolidation the lifecycle rules below and the staging writer exist as two
# separate layers to prevent.
#
# The per-job citations are tabulated in README.md. app/jcl/COMBTRAN.jcl is the
# cleanest single proof that both forms are one mechanism rather than two: in ONE
# job it reads two backups as `(0)`, sorts them into a `(+1)`, then reads that
# same new generation back. In prefix terms that is two resolutions of the newest
# prefix, one allocation of a new prefix, and one read under the prefix just
# allocated -- three prefix operations, and no object rewritten, so nothing in
# the sequence creates a noncurrent version at all.
#
# Alternatives Considered: creating one zero-byte `aws_s3_object` per prefix so
# the "directories" visibly exist, which is what an operator used to catalogued
# datasets will expect. Rejected, and NO SUCH RESOURCE IS DECLARED ANYWHERE BELOW.
# S3 has no directories -- a prefix comes into existence when the first object is
# written under it and ceases to exist when the last is removed -- so a marker
# provisions nothing that was missing, and it would be actively harmful three ways
# here: a marker is itself a versioned object, so the retention rules below would
# accrue noncurrent versions of an empty file; it would appear in every listing, so
# the ETL readers and batch tasks that enumerate a prefix would have to learn to
# skip a spurious zero-length record; and a marker under `<domain>/<dataset>/` does
# not match the keys consumers use, which include the `dt=` and `gen=` segments, so
# it would document a path nothing writes to.
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
# Record formats matter here because they are what makes these objects large
# enough for the multipart rule below to matter and small enough for the
# transition minimum to matter: the fixed record lengths across the ten families
# run from 50 to 430 bytes. The per-family lineage table, with each family's
# record format and its originating JCL, lives in README.md and in each family's
# own `description` in variables.tf.
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
  # the bucket name unreproducible, so a lost state file could not be reconciled
  # with the bucket it described.
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

  # Assumptions: these three are held apart from the ten deliberately and are
  # not an eleventh, twelfth and thirteenth generation family. Not one of them
  # has a GENERATIONDATAGROUP base anywhere in the baseline -- an exhaustive
  # search for that keyword matches only app/jcl/DEFGDGB.jcl,
  # app/jcl/DEFGDGD.jcl, app/jcl/DALYREJS.jcl and app/jcl/REPTFILE.jcl, and none
  # of them defines a base for a statement or for the category-balance report.
  # All three are plain sequential datasets, deleted and rewritten each run:
  # app/jcl/CREASTMT.JCL:L71 deletes STATEMNT.HTML and L75 deletes STATEMNT.PS
  # before CBSTM03A writes both fresh, and app/jcl/PRTCATBL.jcl:L21-L25 deletes
  # TCATBALF.REPT before its sort rewrites it. Merging them into
  # dataset_families would raise the generation count to thirteen and put this
  # module out of step with the ten that variables.tf asserts and that
  # docs/architecture/batch-orchestration.md and data-migration/README.md
  # publish independently.
  #
  # Refactoring Rationale: each prefix is READ from its entry and used to be
  # composed as "<domain>/<key>/". That composition produced
  # `reporting/statement-text/` and `reporting/statement-html/`, which the
  # reporting service does not write -- it publishes under `statements/`,
  # `reports/transaction-detail/` and `reports/category-balance/` -- so every
  # `seq-` rule below governed a prefix that held no objects while the prefixes
  # that did hold objects had no rule, and `non_generation_uris` published
  # locations no consumer could resolve. The prefix a service chooses is not
  # derivable from a key this module invents, so variables.tf transcribes it and
  # this expression reads it.
  non_generation_dataset_prefixes = {
    for key, entry in var.non_generation_prefixes :
    key => entry.prefix
  }

  # Assumptions: this merged thirteen-entry map is THE single source of truth for
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
  # three are ordinary version hygiene -- and that distinction is what the `gdg-`
  # and `seq-` rule identifiers preserve. They are joined only here, where it no
  # longer matters which inventory a key came from.
  # Assumptions: the two key sets are disjoint, so the merge cannot lose an
  # entry to a collision. variables.tf pins both exactly -- ten named
  # generation families and the three named reporting artifacts -- and no name
  # appears in both, so the thirteen keys here are always thirteen.
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
# rather than left to be rediscovered. NEITHER OF THESE TWO is expressed as a
# scanner exemption -- each is declined in prose, with the check left visible so
# it keeps reporting. That is a statement about these two absences and not about
# the file: `aws_s3_bucket.audit` and `aws_cloudtrail.dataset_object_access` each
# carry one justified Checkov skip, both counted by the bounded-exceptions step
# in .github/workflows/infra-ci.yml.
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
    # writes and re-reads many objects per generation across fourteen prefixes,
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
    # access past whatever the policy says, and answering "who may read a given
    # object" means consulting the policy, the bucket ACL and every object ACL.
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
# WHY : Refactoring Rationale: this document was named `tls_only` while it carried
#       one transport statement. It now carries two Deny statements -- one about how
#       a request arrives and one about where it arrives from -- so the name is
#       changed to describe the boundary rather than one of its halves. A document
#       called tls_only holding a network-path deny is the kind of stale name a
#       reader trusts instead of reading, and it would make the second statement
#       look accidental. Renaming a data source is state-safe: it is refreshed on
#       every run and nothing outside this module referenced it.
data "aws_iam_policy_document" "dataset_access_boundary" {
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

  # WHY : Refactoring Rationale: this second statement is NEW. With the transport
  #       deny alone, a correctly-formed HTTPS request carrying any principal that
  #       held an IAM grant could read a dataset generation from anywhere on the
  #       internet, and those generations carry records derived from the cardholder
  #       masters. The transport statement governs HOW a request arrives; nothing
  #       governed WHERE FROM, and the gateway endpoint the batch and ETL tasks
  #       already use gives that second boundary a name to be enforced against.
  statement {
    sid    = "DenyObjectDataOutsideTheVpcEndpoint"
    effect = "Deny"

    # WHY : Assumptions: exactly the three actions that move object CONTENT, and not
    #       s3:* . Deletes and listings are deliberately excluded: Terraform runs
    #       from outside the VPC and both roots derive force_destroy from
    #       !deletion_protection, so a dev teardown legitimately issues version-aware
    #       deletes and bucket-configuration reads from there. Denying those would
    #       break `terraform destroy` -- a criterion this package is accepted against
    #       -- while adding nothing to confidentiality, since bucket metadata is not
    #       the dataset. GetObjectVersion is listed beside GetObject because a
    #       versioned bucket serves a noncurrent version through its own action, so a
    #       policy naming only GetObject would leave every prior generation readable.
    #       Assumptions: excluding the delete and list actions has a second, concrete
    #       beneficiary beyond Terraform. Both roots declare a dataset-retention
    #       Lambda that enforces the generation-retention window with s3:ListBucket
    #       and s3:DeleteObject, and it carries no vpc_config -- it runs outside the
    #       VPC, so its requests carry no aws:SourceVpce and a blanket deny would
    #       silently stop generation pruning, letting the LIMIT(5) SCRATCH analogue
    #       fail open into unbounded retention. The three principals that DO move
    #       object content -- the batch task role, the reporting task role and the
    #       ETL task -- all run on Fargate in the private application subnets, so
    #       their traffic takes the gateway endpoint route and is not caught here.
    actions = [
      "s3:GetObject",
      "s3:GetObjectVersion",
      "s3:PutObject",
    ]

    # WHY : Assumptions: the OBJECT ARN pattern alone, where the transport statement
    #       above needs both forms. The three actions here are authorised against the
    #       object resource, so adding the bucket ARN would name a resource on which
    #       none of them can be evaluated -- a statement that reads as broader
    #       protection while matching nothing extra.
    resources = ["${aws_s3_bucket.datasets.arn}/*"]

    # WHY : Assumptions: a Deny has to reach every principal to be a boundary. This
    #       one is about the network path a request took, not about who sent it, so
    #       scoping it to named principals would leave it silent for exactly the
    #       identity nobody anticipated.
    principals {
      type        = "*"
      identifiers = ["*"]
    }

    # WHY : Assumptions: StringNotEquals is chosen over a negated test for a reason
    #       that is the whole mechanism. aws:SourceVpce is present only on a request
    #       that traversed a VPC endpoint; on a request from outside a VPC the key is
    #       ABSENT, and IAM evaluates StringNotEquals on an absent key as TRUE -- so
    #       this one condition catches both "came through the wrong endpoint" and
    #       "came from outside the VPC entirely". The ...IfExists form would NOT: it
    #       treats an absent key as a non-match and would let every request from
    #       outside the VPC through, which is the opposite of the intent and is the
    #       error this note exists to prevent being introduced as a simplification.
    #       Assumptions: in-VPC traffic cannot miss the endpoint and be caught here by
    #       accident. The gateway endpoint installs a route to the Region's S3
    #       managed prefix list in the private-application route tables, and a
    #       prefix-list route is more specific than the 0.0.0.0/0 NAT route, so
    #       S3-destined packets take the endpoint even though the application
    #       security group also permits broad 443 egress.
    condition {
      test     = "StringNotEquals"
      variable = "aws:SourceVpce"
      values   = [var.s3_gateway_endpoint_id]
    }
  }
}

resource "aws_s3_bucket_policy" "datasets" {
  bucket = aws_s3_bucket.datasets.id
  policy = data.aws_iam_policy_document.dataset_access_boundary.json

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
#   - Assumptions: TEN generation families, not six. This is the single most
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
#   - Alternatives Considered: `newer_noncurrent_versions` -- a COUNT -- rather
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
#   - Trade-offs: one prefix-scoped rule per family rather than a single
#    bucket-wide rule. The cost is fourteen rules where one would have compiled,
#    and it is accepted deliberately for two reasons. Per-family retention
#    becomes overridable for one environment through the optional
#    `noncurrent_versions` member without touching the prefix topology -- which
#    variables.tf requires stay identical across environments -- and each rule's
#    `prefix` filter is the AUDITABLE LINK back to the generation base it
#    replaces, so a reviewer can match a rule to a line of JCL. A single
#    bucket-wide rule would retain five versions of everything with no way to
#    tell which baseline contract each retention was serving.
#
#   - Assumptions: the noncurrent-version actions require versioning enabled, so
#    this configuration takes an explicit dependency on the versioning resource.
#    Both reference the same bucket but neither references the other, so without
#    the dependency Terraform may attach a noncurrent-version rule to a
#    still-unversioned bucket on a first apply, which S3 rejects.
#
#   - Assumptions: every rule carries a `filter`, and every rule that EXPIRES or
#    TRANSITIONS a version is prefix-scoped. A retention rule left filterless
#    would apply to the whole bucket, imposing one retention policy on every
#    dataset at once and erasing the per-family control the third item above
#    exists to provide. The single bucket-wide rule at the end of this resource is
#    deliberately exempt from that scoping because it carries no retention
#    action at all; its reasoning is recorded there.
resource "aws_s3_bucket_lifecycle_configuration" "datasets" {
  bucket = aws_s3_bucket.datasets.id

  # Trade-offs: set explicitly, and matching the service's current default, so
  # the value is pinned and reviewable rather than inherited. The argument is
  # `computed`, meaning an unset value silently adopts whatever the API returns
  # -- and AWS has already changed this default once, introducing a 128 KB floor
  # on transitions that had none before it. Pinning it means a future
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

  # The three non-generation reporting artifacts.
  #
  # Assumptions: THIS RETENTION IS ORDINARY VERSION HYGIENE AND IS NOT THE
  # LIMIT(5) SCRATCH ANALOGUE. Not one of these three datasets has a
  # GENERATIONDATAGROUP base anywhere in the baseline -- all three are plain
  # sequential datasets their job deletes and rewrites each run:
  # app/jcl/CREASTMT.JCL deletes the HTML statement at L71 and the plain-text
  # one at L75, and app/jcl/PRTCATBL.jcl deletes TCATBALF.REPT at L21-L25. They
  # acquire noncurrent versions only because versioning is a BUCKET-WIDE setting
  # that cannot be scoped to a prefix, so a rule is needed to stop those
  # versions accumulating. Reading these three as generation families would
  # invent a generation contract the baseline never had for them, and would make
  # the family count thirteen.
  # Trade-offs: they share the module-wide retention count rather than carrying
  # a separate variable, so the bucket has one retention story instead of two.
  # The accepted imprecision is that a number chosen to mean "five generations"
  # also governs three datasets that have no generations; the alternative -- a
  # retention variable of their own, read by exactly three rules -- buys nothing, because the
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

  # The source-extract prefix -- the one prefix in this bucket that is an INPUT.
  #
  # Refactoring Rationale: this rule exists because the prefix it governs did not.
  # The nightly seed-refresh state read its extracts from a filesystem path
  # (/mnt/carddemo-extracts) that nothing in this stack provisions, so every one of
  # its ten branches failed on an absent file. The extracts were already being put
  # in S3 -- docs/runbooks/data-migration.md syncs app/data/ into this bucket -- so
  # the fix names that destination here and the refresh reads it. Once the prefix is
  # part of the module's inventory it needs the same version hygiene every other
  # prefix has, for the same reason: versioning is bucket-wide and cannot be scoped,
  # so re-syncing a corrected extract leaves the previous one as a noncurrent
  # version that would otherwise accumulate without bound.
  #
  # Assumptions: THIS IS NOT A GENERATION FAMILY and its retention is NOT the
  # LIMIT(5) SCRATCH analogue. The prefix holds no dt=/gen= structure at all: it is
  # a flat directory of exported datasets, read once per refresh and written only by
  # an operator sync. The rule is identified `src-` rather than `gdg-` or `seq-` so
  # that the three different retention CONTRACTS in this configuration remain
  # distinguishable in a plan diff and in the console -- ten reproduce a baseline
  # generation limit, two are hygiene over rewritten outputs, and this one is
  # hygiene over a re-uploaded input.
  #
  # Trade-offs: it shares var.noncurrent_version_retention rather than carrying its
  # own count, on the same reasoning the two statement rules do: one retention story
  # per bucket. The imprecision accepted is that a number chosen to mean "five
  # generations" also bounds how many superseded uploads of one extract are kept.
  # The baseline kept ZERO -- an operator re-transmitting a dataset overwrote it --
  # so any positive number is already strictly more recoverable, and no particular
  # number is more faithful than another.
  rule {
    id     = "src-source-extracts"
    status = "Enabled"

    filter {
      prefix = var.source_extract_prefix
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

  # One bucket-wide housekeeping rule, and the ONLY rule here that is not scoped
  # to a dataset prefix.
  #
  # Assumptions: an incomplete multipart upload can be initiated against ANY
  # key, including one that matches none of the thirteen dataset prefixes above --
  # a mistyped prefix, an ad-hoc staging path used during an investigation, or a
  # key written by a future consumer before its prefix is added to the
  # inventory. A prefix-scoped abort rule only reclaims parts whose key matches
  # its prefix, so parts left anywhere else would be stored and billed
  # indefinitely while remaining invisible as objects. The fourteen rules above
  # therefore cannot on their own make the guarantee their own abort comment
  # claims; this rule is what completes it.
  #
  # Trade-offs: this deliberately overlaps the per-family abort blocks rather
  # than replacing them. Both specify the same number of days, so for a key
  # under one of the fourteen prefixes the two agree and the outcome is identical
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
  # control the fourteen rules exist to provide. Aborting an incomplete upload
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

# WHY : Assumptions: this module declares no aws_s3_bucket_notification and no
#       aws_lambda_permission, and neither belongs here. An
#       aws_s3_bucket_notification is a WHOLE-BUCKET resource, so claiming it in a
#       reusable module takes the bucket's only notification slot away from every
#       consumer of that module -- for an event integration that belongs to
#       whichever root owns the function. Generation pruning is therefore driven by
#       the staging writer and the lifecycle rules above rather than by an
#       object-created hook; variables.tf records the same reasoning on the input
#       surface.

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
  # ACCESS LOGGING specifically carries no exemption anywhere in this module, and
  # a suppression comment here would convert a real finding into a permanent
  # blind spot: after the suppression, a bucket with no log target is
  # indistinguishable from one whose root forgot to supply one.
  # Refactoring Rationale: this note previously generalised to "the gates in this
  # tree are satisfied by construction rather than by exemption", which the same
  # file contradicts twice. `aws_s3_bucket.audit` carries
  # `#checkov:skip=CKV_AWS_145` because the dedicated CloudTrail delivery bucket
  # uses SSE-S3 rather than the financial dataset CMK, and
  # `aws_cloudtrail.dataset_object_access` carries `#checkov:skip=CKV_AWS_35` for
  # the same reason -- both justified at the resource, and both COUNTED by the
  # assertion step in .github/workflows/infra-ci.yml, so neither can be added or
  # removed silently. The claim is therefore scoped to the check it is actually
  # about, because an overstated "no exemptions anywhere" invites a reader to
  # treat the two real ones as undocumented drift.
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
    # WHY : Assumptions: the identifier names exactly what the rule does --
    #       abandoning incomplete multipart uploads -- and names no retention
    #       horizon. An identifier naming an expiration this rule does not configure
    #       is worse than an imprecise one, because an operator reads the identifier
    #       in the console and would infer a compliance horizon that is not there.
    id     = "abort-incomplete-multipart-uploads"
    status = "Enabled"

    filter {}

    # WHY : Assumptions: this rule configures no `expiration` and no
    #       `noncurrent_version_expiration`, so the audit trail's objects are kept
    #       until an account-level retention decision removes them rather than being
    #       aged out by this module. A module cannot know the compliance horizon its
    #       caller is subject to, and an object-access audit trail deleted on a
    #       module default is exactly the record an investigation needs; variables.tf
    #       records the same reasoning on the input surface. What remains is the
    #       multipart cleanup below, which is storage hygiene rather than a retention
    #       policy -- and it is also what keeps this rule VALID, because a lifecycle
    #       rule needs at least one action and the API rejects an empty one.
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
