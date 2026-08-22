# =============================================================================
# infra/modules/s3-datasets/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The entire public return surface of the `s3-datasets` module -- THIRTEEN
#   outputs and nothing else. Between them they answer the only five questions
#   a caller has about this bucket: what it is called, what it is called to IAM,
#   where inside it each baseline dataset lives, where the allocator keeps its
#   generation bookkeeping, and where its audit trail goes.
#   Assumptions: the count above is maintained against the `output` blocks in
#   this file. A return-surface count is the one figure a caller reads before
#   wiring a module, so an under-count reads as "there is nothing else to wire"
#   and hides the outputs a root actually needs.
#   Refactoring Rationale: this count read TEN and the enumeration below listed
#   SEVEN, while the file declared twelve. Both were restated from a measurement
#   of the `output` blocks; the gap was not one omission but three -- the audit
#   trio was never added to the list, and the two source-extract values landed
#   with the dataset refresh. It now reads THIRTEEN, re-measured again after
#   `generation_claim_prefix` was added to give the generation allocator's
#   bookkeeping prefix a caller. An under-count here is the failure mode the
#   Assumptions paragraph above already names -- a caller reads it as the whole
#   surface and concludes a published value is not published.
#
#   AAP section 0.5.3.5 states the contract these outputs exist to satisfy:
#   "Terraform module outputs are the only source of runtime endpoints and
#   identifiers ... the dataset bucket name ... written to Parameter Store and
#   Secrets Manager, and each service reads them at startup through its Spring
#   profile. No service hard-codes an endpoint." Every value below is therefore
#   read from a resource attribute or composed from a local declared in
#   main.tf. Not one is a literal, so no ARN, no account identifier, no region
#   and no bucket name is written down anywhere in this file.
#
#   ONE-WAY CONTRACT. These thirteen output NAMES are read by four components:
#
#     infra/envs/dev/main.tf ............. writes them into Parameter Store
#     infra/envs/prod/main.tf ............ the same, per environment
#     infra/modules/step-functions-batch . per-state container overrides
#     data-migration/src/carddemo_migration/loaders/s3_stage.py
#                                          stages dataset generations
#
#   Nothing in this module depends on any of those four. The dependency runs
#   one way only, which is what makes a rename here quietly expensive: renaming
#   an output cannot fail this module's own `validate` or its own lint, and it
#   breaks all four callers instead. Add a new output rather than repurpose an
#   existing name.
#
#   THIS IS NOT THE TERRAFORM REMOTE-STATE BUCKET, and the two are genuinely
#   easy to confuse, because both are versioned, encrypted, publicly blocked S3
#   buckets. infra/bootstrap/outputs.tf is the sole owner of that contract --
#   `state_bucket_name`, `state_lock_table_name` and `aws_region`, the three
#   values infra/envs/*/backend.tf transcribes -- and nothing here duplicates
#   or resembles any of them. main.tf carries the same warning above its data
#   sources, together with the sharpest distinction between the two buckets:
#   bootstrap declares no noncurrent-version expiry at all, because every prior
#   version of a state file is recovery material, whereas an expiry retaining
#   exactly five versions is this module's entire purpose.
#
# Parameters:
#   Not applicable, and said rather than left silent: this file declares no
#   `variable` block. Every module input lives in variables.tf and is
#   documented on the `variable` block itself, which is also where tflint's
#   terraform_standard_module_structure requires it -- a `variable` declared
#   here would be reported as being in the wrong file. FOUR inputs are
#   nevertheless READ below: `var.dataset_families` and
#   `var.non_generation_prefixes` indirectly, through the locals main.tf
#   derives from them, and `var.noncurrent_version_retention` and
#   `var.source_extract_prefix` directly. Each is documented at the output that
#   reads it.
#
# Return values:
#   Thirteen, in declaration order. An `output` block IS a return value, so the
#   `description` on each one below is this file's direct discharge of the
#   documentation standard's "Return values" element rather than an analogue of
#   it, and tflint's terraform_documented_outputs rule is what makes a missing
#   one fail the build instead of a review:
#
#     bucket_name .................. string       the created bucket
#     bucket_arn ................... string       IAM resource, bucket form
#     dataset_prefixes ............. map(string)  TEN generation families
#     dataset_uris ................. map(string)  the same TEN as s3:// URIs
#     non_generation_prefixes ...... map(string)  THREE reporting artifacts
#     non_generation_uris .......... map(string)  the same THREE as s3:// URIs
#     source_extract_prefix ........ string       where the refresh READS inputs
#     source_extract_uri ........... string       the same prefix as an s3:// URI
#     generation_claim_prefix ...... string       allocator replay bookkeeping
#     noncurrent_version_retention . number       the LIMIT(5) count
#     audit_bucket_name ............ string       the object-access log bucket
#     audit_bucket_arn ............. string       IAM resource, audit bucket form
#     object_access_trail_arn ...... string       the CloudTrail data-event trail
#
#   NOTHING HERE IS `sensitive`, and that is a decision rather than an
#   oversight. A bucket name, a bucket ARN, a set of key prefixes and a
#   retention count are not credentials: they name a location and a policy,
#   they do not grant access to either. Granting access is what the IAM task
#   roles and the TLS-only bucket policy in main.tf do. Marking any of these
#   would redact it from `terraform output` and from plan output, which defeats
#   the purpose they exist for -- the environment roots have to READ these
#   values in order to write them into Parameter Store, and the deploy and
#   batch-operations runbooks expect an operator to read them off a completed
#   apply. It would not even buy secrecy, because a redacted output is still
#   recorded in plaintext in state.
#
# Errors:
#   Two, neither of them a fault in the module:
#     1. EIGHT of the thirteen reference a resource attribute and are therefore
#        UNKNOWN UNTIL APPLY: both bucket names, both bucket ARNs, the trail
#        ARN, and the three URI-shaped values, each of which interpolates
#        `aws_s3_bucket.datasets.bucket`. `terraform output` run against a root
#        that has only planned returns nothing at all for those, and a plan
#        renders them as "(known after apply)". The remaining FIVE are known at
#        plan time because no term in them touches a resource:
#        `dataset_prefixes` and `non_generation_prefixes`, which are locals
#        composed from their respective maps, `generation_claim_prefix`, a local
#        composed from a literal, plus `source_extract_prefix` and
#        `noncurrent_version_retention` read straight through. That
#        `generation_claim_prefix` falls on the plan side is load-bearing rather
#        than incidental: its consumer is an IAM policy document in an
#        environment root, and an apply-time-unknown prefix there would make the
#        policy depend on the bucket it authorises access to.
#        Refactoring Rationale: this said "every value except
#        `noncurrent_version_retention` ... so those six are unknown", which was
#        wrong in both directions -- it named six of what was then ten, and it
#        put the two prefix MAPS on the apply side when neither touches a
#        resource. The split is now stated as the property that decides it,
#        whether the value reads a resource attribute, so a reader can re-derive
#        it from the file rather than trusting a tally.
#     2. A rename is caught by no gate in this module; see the one-way contract
#        above.
#
# WHY (non-obvious design decisions):
#   - Trade-offs: the ten generation prefixes are published as ONE map rather
#     than as ten separately named scalar outputs, and the three reporting-artifact
#     prefixes as a SECOND map rather than being merged into the first. Both
#     shapes are argued at the outputs themselves; the shared reason is that
#     the count TEN is the most error-prone fact in this module, and each of the
#     two shapes makes a miscount structurally visible instead of silent.
#   - Assumptions: the bare-prefix form and the s3:// URI form are BOTH
#     published, over the same keys, because they have different named
#     consumers -- an IAM resource pattern and a boto3 `Prefix=` argument need
#     the prefix, while a Step Functions container override carries a URI.
#     Argued in full on `dataset_uris`.
#   - Alternatives Considered: publishing `local.all_dataset_prefixes`, the
#     merged thirteen-entry map the per-family and per-artifact lifecycle
#     filters in main.tf read, as a further output. Rejected: the whole content
#     of that map is already published here as its two component maps, so a
#     consumer that genuinely wants the union can `merge()` the two outputs,
#     whereas a single thirteen-entry output would erase the generation /
#     non-generation distinction the separation exists to keep visible -- and
#     erasing it is the specific way the family count stops being ten.
#     Assumptions: that merged map is thirteen entries and the bucket carries
#     FIFTEEN prefixed lifecycle rules; the fourteenth is `src-source-extracts`,
#     which filters on `var.source_extract_prefix` directly rather than through
#     the map, because that prefix is an input the module reads rather than a
#     dataset it owns, and the fifteenth is `claim-generation-allocations`,
#     which filters on `local.generation_claim_prefix` and governs bookkeeping
#     rather than dataset bytes.
# =============================================================================

# Assumptions: the `bucket` attribute is published, not `id`. For
# `aws_s3_bucket` the two carry the same value, the bucket name, so the choice
# is only about which one a reader of this file can trust without going and
# checking: `id` is the generic Terraform resource identifier and says nothing
# about what it holds, while `bucket` is the name of the argument that set it.
# main.tf wires its eight companion resources with `.id` because that is the
# idiomatic form for a reference between resources; an output is read by
# somebody who cannot see the resource at all, so it names the thing instead.
# infra/bootstrap/outputs.tf publishes its own `state_bucket_name` off the same
# `.bucket` attribute, so the two modules read alike.
# Alternatives Considered: publishing both `bucket_id` and `bucket_name`, so
# that a caller expecting either convention finds one. Rejected -- two output
# names for one underlying value put two rows in the generated Outputs table of
# infra/modules/s3-datasets/README.md, and a reader then has to work out which
# of the two to use and whether they can ever differ. They cannot, so the
# second row is a question with no answer.
output "bucket_name" {
  description = "Name of the versioned dataset bucket, for the callers that must be given it rather than hard-code it: infra/envs/dev/main.tf and infra/envs/prod/main.tf write it into Parameter Store, infra/modules/step-functions-batch passes it to each Fargate batch task as a container override, and data-migration/src/carddemo_migration/loaders/s3_stage.py reads it to stage dataset generations. This is the application dataset bucket, not the Terraform state bucket that infra/bootstrap/outputs.tf publishes as state_bucket_name."
  value       = aws_s3_bucket.datasets.bucket
}

# Assumptions: the ARN is published in its BARE, bucket-level form, and each
# consuming policy appends `/*` itself where it needs the object-level form. S3
# authorises bucket-level operations such as ListBucket and GetBucketLocation
# against the bucket ARN and object-level operations such as GetObject and
# PutObject against the object ARN pattern, and IAM treats those as two
# distinct resources. main.tf's TLS-only bucket policy depends on exactly that
# fact and states it in full at the `resources` argument of
# data.aws_iam_policy_document.dataset_access_boundary, where it lists both forms; the
# reasoning is cross-referenced here rather than argued twice.
# Alternatives Considered: publishing the object pattern instead of the bucket
# ARN, or as well as it. Rejected -- a pre-suffixed string forces every
# consumer that needs the bucket form to strip the suffix back off, and string
# surgery on an ARN inside a caller is the class of composition this whole file
# exists to remove. The bare form is the one both kinds of consumer can derive
# what they need from.
output "bucket_arn" {
  description = "ARN of the dataset bucket in its bucket-level form, for the IAM task-role policies that authorise access to it: the batch-service and data-migration ETL roles scope object permissions to this value with /* appended, and scope bucket-level operations such as a prefix listing to this value unsuffixed."
  value       = aws_s3_bucket.datasets.arn
}

# Trade-offs: ONE map keyed by family rather than ten separately named scalar
# outputs. A map is iterated, so a consumer that walks it cannot silently miss a
# family; ten scalar outputs would let a caller wire nine and never learn that a
# tenth existed. That is worth more than ten convenient names here, because TEN
# is the single most error-prone fact in this module: six bases are defined
# together in app/jcl/DEFGDGB.jcl under a heading that reads as the complete
# inventory, three more sit in app/jcl/DEFGDGD.jcl, and the tenth is in
# app/jcl/DALYREJS.jcl, a job named for the reject dataset rather than for
# generation definitions. AAP section 0.4.1.7 states "There are ten
# generation-dataset bases, not six" and section 0.7.5 repeats it. A shape that
# makes an omission structural beats a shape that leaves it to a reviewer to
# count.
#
# Assumptions: this map is THE single source of truth for the prefix
# convention. main.tf composes local.dataset_prefixes once, its lifecycle rules
# filter on that same expression (by way of local.all_dataset_prefixes, which
# merges it with the statement prefixes), and this output republishes it. Having
# each consumer compose `<domain>/<dataset>/` for itself was the alternative and
# is rejected because the drift would be silent rather than loud: the retention
# rule would govern a prefix nobody writes to while the prefix everybody writes
# to kept every generation forever. No plan would report it and no write would
# fail; it would surface as a storage bill.
#
# Assumptions: the full key convention is
# `s3://<bucket>/<domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/` (AAP section
# 0.4.1.7), and this output supplies the `<domain>/<dataset>/` PORTION ONLY. The
# `dt=` and `gen=` segments are chosen per run by whichever writer creates the
# generation, because only the writer knows its business date and its generation
# number -- the same reason app/jcl/INTCALC.jcl:L22 passes the business date in
# as a PARM instead of reading a clock. A consumer that mistook a value here for
# a fully-qualified generation prefix would build a key two segments short and
# write every run over the same one.
output "dataset_prefixes" {
  description = "Key prefix per generation-dataset family: one entry for each of the ten families, keyed exactly as var.dataset_families is keyed and valued as the <domain>/<dataset>/ prefix that family's generations live under. Read by infra/modules/step-functions-batch for its per-state container overrides, by data-migration/src/carddemo_migration/loaders/s3_stage.py, and by the IAM policies that scope a task role to one family's prefix. Supplies the <domain>/<dataset>/ portion only; the dt= and gen= segments of a generation key are chosen per run by the writer."
  value       = local.dataset_prefixes
}

# Assumptions: this output exists BECAUSE the prefix form above cannot serve
# both consumers. AAP section 0.5.3.3 fixes the mapping out of JCL: every
# `DD DSN=` becomes either a database connection resolved from Parameter Store
# or an S3 URI supplied via container overrides, never a hard-coded path in a
# service. A container override carries the URI form, while an IAM resource
# pattern and a boto3 `Prefix=` argument take the bare prefix. Two named
# consumers needing two forms of one location is the reason both are published
# instead of one being recomposed at every call site -- recomposition at the
# call site is precisely how a hard-coded `s3://` host gets introduced.
#
# The DD statements this replaces, named so the mapping is concrete rather than
# asserted:
#   app/jcl/POSTTRAN.jcl:L34-L38  the DALYREJS DD, writing DALYREJS(+1) at L38
#                                 with DCB=(RECFM=F,LRECL=430) at L36.
#   app/jcl/INTCALC.jcl:L37-L41   the TRANSACT DD, writing SYSTRAN(+1) at L41
#                                 with DCB=(RECFM=F,LRECL=350) at L39.
#   app/jcl/COMBTRAN.jcl:L24,L26  the concatenated SORTIN, reading
#                                 TRANSACT.BKUP(0) and SYSTRAN(0).
#
# Assumptions: the map is built by iterating local.dataset_prefixes rather than
# var.dataset_families, so `dataset_prefixes` and `dataset_uris` cannot disagree
# about where a family lives: one expression composes the prefix and this one
# only prepends a scheme and a host to it. Recomposing `<domain>/<dataset>/`
# here from the variable would reintroduce the second copy main.tf deliberately
# eliminated, and the two outputs could then drift against each other while
# both continued to look correct in isolation.
#
# Assumptions: the host is read from aws_s3_bucket.datasets.bucket -- the same
# attribute `bucket_name` publishes -- rather than from local.bucket_name. The
# local is the name that was REQUESTED of S3 and the attribute is the name S3
# CONFIRMED; binding the URI to the attribute is also what keeps this output
# unknown until apply, so a URI naming a bucket that was never created is never
# handed to a caller at plan time.
output "dataset_uris" {
  description = "Fully-qualified s3:// URI per generation-dataset family: the same ten keys as dataset_prefixes, each resolved against the created bucket. This is the form infra/modules/step-functions-batch puts in a Fargate container override in place of a JCL DD DSN= statement, whereas dataset_prefixes carries the bare-prefix form that an IAM resource pattern and a boto3 Prefix= argument need. Addresses the family, not a generation: a writer appends its own dt= and gen= segments."
  value = {
    for family_key, family_prefix in local.dataset_prefixes :
    family_key => "s3://${aws_s3_bucket.datasets.bucket}/${family_prefix}"
  }
}

# Assumptions: THESE THREE ARE NOT GENERATION FAMILIES and must never be counted
# as an eleventh, a twelfth and a thirteenth. They are the two statement
# artifacts app/jcl/CREASTMT.JCL writes -- STATEMNT.PS, deleted by the IEFBR14
# step at L75 and rewritten at L91 with DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)
# declared at L89, and STATEMNT.HTML, deleted at L71 and rewritten at L96 with
# DCB=(LRECL=100,BLKSIZE=800,RECFM=FB) declared at L94, both under ONE prefix and
# distinguished by object name -- plus the request-scoped transaction detail
# report and the category-balance report TCATBALF.REPT, which
# app/jcl/PRTCATBL.jcl deletes at L21-L25 and rewrites at L59-L63 with
# DCB=(LRECL=40,RECFM=FB). NOT ONE OF THEM HAS A `DEFINE GENERATIONDATAGROUP`
# BASE ANYWHERE IN THE BASELINE. That is measured rather than assumed: an
# exhaustive search for the keyword matches four files only,
# app/jcl/DEFGDGB.jcl, app/jcl/DEFGDGD.jcl, app/jcl/DALYREJS.jcl and
# app/jcl/REPTFILE.jcl, and not one of them defines a base for any of these.
# All three are plain sequential datasets the job deletes and rewrites each run.
# They are published under their own output name for exactly one reason: a
# consumer counting generation families reads `dataset_prefixes` and gets TEN.
# Folding these three in would make that number thirteen and put the module out
# of step with variables.tf's `length == 10` assertion, with
# docs/architecture/batch-orchestration.md and with data-migration/README.md,
# all three of which publish the same ten independently.
#
# Assumptions: the transaction detail report appears HERE as well as among the
# generation families, and that is not a double count. The reference writes that
# output to a numbered base -- TRANREPT(+1) at app/jcl/TRANREPT.jcl:L80 -- and
# the target publishes the same bytes to two keys: the generation coordinate
# under `dataset_prefixes["tranrept"]`, which carries the LIMIT(5) analogue, and
# the request-scoped key here, which is what a range-addressed request and the
# runbooks resolve. Two keys for one artifact is argued at
# ReportArtifactPublisher.publishDaily; what matters here is that the family
# count reads ten either way.
#
# Assumptions: they need prefixes all the same, because the GenerateStatements
# and GenerateReports batch states -- states 8 and 9 of the eleven in AAP
# section 0.4.1.7 -- write all three artifacts to S3 and have to write them
# somewhere. The noncurrent-version rule main.tf gives them is ordinary version
# hygiene and NOT the LIMIT(5) SCRATCH analogue, which is why main.tf identifies
# their lifecycle rules `seq-` and the ten generation rules `gdg-`: the retention
# numbers coincide, the contracts behind them do not.
output "non_generation_prefixes" {
  description = "Key prefix per non-generation reporting artifact -- the shared statements prefix carrying the plain-text and HTML statements, the request-scoped transaction detail report and the category-balance report -- keyed exactly as var.non_generation_prefixes is keyed and valued as the literal prefix the reporting service writes under. Read by the GenerateStatements and GenerateReports batch states and by the IAM policies that scope the reporting task role. Deliberately separate from dataset_prefixes: not one artifact has a generation-data-group base in the baseline, so counting them among the generation families would report thirteen where variables.tf, docs/architecture/batch-orchestration.md and data-migration/README.md all publish ten."
  value       = local.non_generation_dataset_prefixes
}

# Trade-offs: published for SYMMETRY with `dataset_uris`, accepting one more
# output than the minimum. The GenerateStatements state consumes a container
# override in exactly the way the generation-writing states do, so an
# asymmetric return surface -- prefixes and URIs for the ten, prefixes only for
# the three -- would leave those states as the sole consumers obliged to compose
# their own `s3://` string from a bucket name and a prefix. A caller made to
# compose one location is a caller that eventually hard-codes one, which is the
# outcome AAP section 0.5.3.5 exists to prevent. The cost is one extra output
# and one extra row in the generated Outputs table; the cost of the asymmetry
# would be one bespoke path in the statements state.
output "non_generation_uris" {
  description = "Fully-qualified s3:// URI per non-generation reporting artifact: the same three keys as non_generation_prefixes, each resolved against the created bucket, so the GenerateStatements and GenerateReports batch states receive their output locations as container overrides in the same form the generation-writing states receive theirs."
  value = {
    for artifact_key, artifact_prefix in local.non_generation_dataset_prefixes :
    artifact_key => "s3://${aws_s3_bucket.datasets.bucket}/${artifact_prefix}"
  }
}

# Assumptions: this is the only prefix this module publishes that a consumer READS
# rather than writes, and it is published so that the batch chain's seed-refresh
# state receives it instead of composing it. The step-functions-batch module passes
# it to the data-migration container as --extract-prefix, and the container joins
# each dataset's registered source file name to it; if the two ever composed the
# prefix independently they could disagree, and the failure mode is a refresh that
# reads a prefix nothing was synced to and reports an absent object per dataset.
# Assumptions: it is deliberately NOT folded into dataset_prefixes or
# non_generation_prefixes. Both of those inventories are closed by validation --
# ten generation families and three reporting artifacts -- and this prefix belongs to
# neither: it carries no generation convention, no dt=/gen= structure and no
# LIMIT(5) analogue, so counting it in either would make the ten-family count that
# variables.tf asserts and two sibling documents publish stop being true.
output "source_extract_prefix" {
  description = "Key prefix inside the dataset bucket that the exported baseline extracts are read FROM. Passed to the batch chain's seed-refresh state, which gives it to the data-migration container as --extract-prefix. Populating it is an operator action documented in docs/runbooks/data-migration.md. Not one of the ten generation families and not one of the three reporting-artifact prefixes: it is an input, so it has no generation convention and no LIMIT(5) analogue."
  value       = var.source_extract_prefix
}

# Trade-offs: published for symmetry with dataset_uris and non_generation_uris, so
# that an operator following the runbook copies one value rather than composing a
# URI from a bucket name and a prefix. A caller made to compose a location is a
# caller that eventually hard-codes one, which is what AAP section 0.5.3.5 exists to
# prevent.
output "source_extract_uri" {
  description = "Fully-qualified s3:// URI of the source-extract prefix, for the operator sync documented in docs/runbooks/data-migration.md."
  value       = "s3://${aws_s3_bucket.datasets.bucket}/${var.source_extract_prefix}"
}

# Refactoring Rationale: this output exists because the prefix it names was reachable
# by no caller. The generation allocator reads and writes one small record per
# orchestrator execution and family under `_generation-claims/`, and it does so on
# EVERY allocation, before any dataset object is touched. The environment roots
# derive the batch task role's object grants from `dataset_prefixes`, which is the ten
# generation families and nothing else, so the very first allocation of a deployment
# was denied and every generation-writing state failed with it. A root cannot fix that
# by restating the literal, because the prefix is a contract shared with two
# application constants that a root has no way to read; publishing it here is what
# lets a root scope a narrow Get and Put to the same value the code uses.
#
# Assumptions: this is deliberately NOT a member of `dataset_prefixes` or of
# `non_generation_prefixes`, and it is not a fourteenth entry of the merged map
# either. Those two maps are the inventory of prefixes that hold DATASET BYTES and
# every one of their entries carries a retention rule expressing a generation or
# reporting contract. This prefix holds bookkeeping, has no `dt=`/`gen=` structure and
# is governed by the `claim-generation-allocations` rule, whose retention means
# something different from all three of the others. Folding it in would give it a
# generation-retention rule it must not have, would present it to consumers as a
# dataset location, and would make the family count stop being ten.
#
# Trade-offs: the value is known at PLAN time -- it is a local composed from a
# literal, with no resource attribute in it -- which is what allows an environment
# root to interpolate it into an IAM policy document without an apply-time cycle.
# Publishing it as an s3:// URI instead, for symmetry with the three URI-shaped
# outputs above, was rejected for exactly that reason: interpolating the bucket name
# would make it apply-time-unknown, and its one consumer is an IAM resource pattern
# that needs the bare prefix rather than a URI.
output "generation_claim_prefix" {
  description = "Key prefix inside the dataset bucket holding the generation allocator's per-execution replay records, each recording which generation one orchestrator execution took for one family so a retried or redriven attempt reuses that number instead of consuming a second generation. Published so an environment root can scope the batch task role's s3:GetObject and s3:PutObject to it narrowly rather than repeating the literal: the same string is declared by DatasetGenerationService.RUN_CLAIM_ROOT in the batch service and by _RUN_CLAIM_ROOT in data-migration's s3_stage loader, and tests on both sides read this module's declaration and assert all three agree. Not one of the ten generation families and not one of the three reporting-artifact prefixes: it holds no dataset bytes, has no dt=/gen= structure, and its retention is the claim-generation-allocations lifecycle rule rather than any LIMIT(5) analogue."
  value       = local.generation_claim_prefix
}

# Assumptions: the effective retention is echoed back so that the LIMIT(5)
# SCRATCH equivalence can be CHECKED rather than trusted -- by an operator
# runbook, by a verification query, or by a compliance review -- without anyone
# having to read the HCL. The baseline defines every one of its ten generation
# bases with LIMIT(5) and SCRATCH: app/jcl/DEFGDGB.jcl:L26, L32, L38, L44, L50
# and L56; app/jcl/DEFGDGD.jcl:L29, L52 and L75; and app/jcl/DALYREJS.jcl:L26.
# A number a reviewer can read straight off `terraform output` turns the parity
# claim into something they can test.
#
# Alternatives Considered: not publishing it at all, on the ground that a caller
# already knows what it passed in. Rejected -- a caller may pass nothing and
# inherit the module DEFAULT of five, which is the normal case for both
# environment roots, and in that case the effective value exists nowhere the
# caller can read it. The one number the whole parity argument rests on would be
# visible only by opening variables.tf.
#
# Assumptions: this reflects the MODULE-LEVEL value only. A per-family override
# supplied through a `dataset_families` entry's `noncurrent_versions` member is
# NOT folded in, because main.tf resolves that override per lifecycle rule with
# `coalesce(rule.value.noncurrent_versions, var.noncurrent_version_retention)`
# and no single number describes a mixed set. Averaging the overrides, or
# reporting the module value as though none existed, would make this output
# misleading in exactly the situation it is consulted in -- a review asking
# whether retention still matches LIMIT(5). A root that overrides per family
# reads its own `dataset_families` for the exceptions.
output "noncurrent_version_retention" {
  description = "Effective number of noncurrent object versions the module retains per prefix, republished so a runbook, a verification query or a compliance review can confirm the baseline's LIMIT(5) SCRATCH generation limit is still being reproduced without reading the module's HCL. Reflects the module-level value only: a per-family override supplied through a dataset_families entry's noncurrent_versions member is not folded in."
  value       = var.noncurrent_version_retention
}

output "audit_bucket_name" {
  description = "Name of the versioned bucket receiving validated CloudTrail data-event logs for dataset object reads and writes."
  value       = aws_s3_bucket.audit.bucket
}

output "audit_bucket_arn" {
  description = "ARN of the versioned dataset object-access audit bucket, consumed by exact KMS encryption-context policy wiring in the environment root."
  value       = aws_s3_bucket.audit.arn
}

output "object_access_trail_arn" {
  description = "ARN of the CloudTrail trail whose advanced selector audits object-level access to the CardDemo dataset bucket."
  value       = aws_cloudtrail.dataset_object_access.arn
}
