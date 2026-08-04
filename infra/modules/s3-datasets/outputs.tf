# =============================================================================
# infra/modules/s3-datasets/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The entire public return surface of the `s3-datasets` module -- SEVEN
#   outputs and nothing else. Between them they answer the only three questions
#   a caller has about this bucket: what it is called, what it is called to IAM,
#   and where inside it each baseline dataset lives.
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
#   ONE-WAY CONTRACT. These seven output NAMES are read by four components:
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
#   here would be reported as being in the wrong file. Two inputs are
#   nevertheless READ below: `var.dataset_families` and
#   `var.non_generation_prefixes` indirectly, through the locals main.tf
#   derives from them, and `var.noncurrent_version_retention` directly. Each is
#   documented at the output that reads it.
#
# Return values:
#   Seven, in declaration order. An `output` block IS a return value, so the
#   `description` on each one below is this file's direct discharge of the
#   documentation standard's "Return values" element rather than an analogue of
#   it, and tflint's terraform_documented_outputs rule is what makes a missing
#   one fail the build instead of a review:
#
#     bucket_name .................. string       the created bucket
#     bucket_arn ................... string       IAM resource, bucket form
#     dataset_prefixes ............. map(string)  TEN generation families
#     dataset_uris ................. map(string)  the same TEN as s3:// URIs
#     non_generation_prefixes ...... map(string)  TWO statement artifacts
#     non_generation_uris .......... map(string)  the same TWO as s3:// URIs
#     noncurrent_version_retention . number       the LIMIT(5) count
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
#     1. Every value except `noncurrent_version_retention` derives from a
#        resource attribute, so those six are UNKNOWN UNTIL APPLY. `terraform
#        output` run against a root that has only planned returns nothing at
#        all, and a plan renders them as "(known after apply)". Only the
#        retention count is known at plan time, because it is an input rather
#        than an attribute.
#     2. A rename is caught by no gate in this module; see the one-way contract
#        above.
#
# WHY (non-obvious design decisions):
#   - Trade-offs: the ten generation prefixes are published as ONE map rather
#     than as ten separately named scalar outputs, and the two statement
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
#     merged twelve-entry map every lifecycle filter in main.tf reads, as an
#     eighth output. Rejected: the whole content of that map is already
#     published here as its two component maps, so a consumer that genuinely
#     wants the union can `merge()` the two outputs, whereas a single
#     twelve-entry output would erase the generation / non-generation
#     distinction the separation exists to keep visible -- and erasing it is
#     the specific way the family count stops being ten.
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
# data.aws_iam_policy_document.tls_only, where it lists both forms; the
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

# Assumptions: THESE TWO ARE NOT GENERATION FAMILIES and must never be counted
# as an eleventh and a twelfth. They are the two statement artifacts
# app/jcl/CREASTMT.JCL writes -- STATEMNT.PS, deleted by the IEFBR14 step at
# L75 and rewritten at L91 with DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB) declared at
# L89, and STATEMNT.HTML, deleted at L71 and rewritten at L96 with
# DCB=(LRECL=100,BLKSIZE=800,RECFM=FB) declared at L94 -- and NEITHER HAS A
# `DEFINE GENERATIONDATAGROUP` BASE ANYWHERE IN THE BASELINE. That is measured
# rather than assumed: an exhaustive search for the keyword matches four files
# only, app/jcl/DEFGDGB.jcl, app/jcl/DEFGDGD.jcl, app/jcl/DALYREJS.jcl and
# app/jcl/REPTFILE.jcl, and not one of them defines a statement base. Both are
# plain sequential datasets the job deletes and rewrites each run.
# They are published under their own output name for exactly one reason: a
# consumer counting generation families reads `dataset_prefixes` and gets TEN.
# Folding these two in would make that number twelve and put the module out of
# step with variables.tf's `length == 10` assertion, with
# docs/architecture/batch-orchestration.md and with data-migration/README.md,
# all three of which publish the same ten independently.
#
# Assumptions: they need prefixes all the same, because the GenerateStatements
# batch state -- state 8 of the eleven in AAP section 0.4.1.7 -- writes both the
# plain-text and the HTML statement to S3 and has to write them somewhere. The
# noncurrent-version rule main.tf gives them is ordinary version hygiene and NOT
# the LIMIT(5) SCRATCH analogue, which is why main.tf identifies their lifecycle
# rules `seq-` and the ten generation rules `gdg-`: the retention numbers
# coincide, the contracts behind them do not.
output "non_generation_prefixes" {
  description = "Key prefix per non-generation dataset -- the two sequential statement artifacts, plain text and HTML -- keyed exactly as var.non_generation_prefixes is keyed. Read by the GenerateStatements batch state, which writes both statements to S3. Deliberately separate from dataset_prefixes: neither artifact has a generation-data-group base in the baseline, so counting them among the generation families would report twelve where variables.tf, docs/architecture/batch-orchestration.md and data-migration/README.md all publish ten."
  value       = local.non_generation_dataset_prefixes
}

# Trade-offs: published for SYMMETRY with `dataset_uris`, accepting one more
# output than the minimum. The GenerateStatements state consumes a container
# override in exactly the way the generation-writing states do, so an
# asymmetric return surface -- prefixes and URIs for the ten, prefixes only for
# the two -- would leave that one state as the sole consumer obliged to compose
# its own `s3://` string from a bucket name and a prefix. A caller made to
# compose one location is a caller that eventually hard-codes one, which is the
# outcome AAP section 0.5.3.5 exists to prevent. The cost is one extra output
# and one extra row in the generated Outputs table; the cost of the asymmetry
# would be one bespoke path in the statements state.
output "non_generation_uris" {
  description = "Fully-qualified s3:// URI per non-generation dataset: the same two keys as non_generation_prefixes, each resolved against the created bucket, so the GenerateStatements batch state receives its plain-text and HTML output locations as container overrides in the same form the generation-writing states receive theirs."
  value = {
    for statement_key, statement_prefix in local.non_generation_dataset_prefixes :
    statement_key => "s3://${aws_s3_bucket.datasets.bucket}/${statement_prefix}"
  }
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

