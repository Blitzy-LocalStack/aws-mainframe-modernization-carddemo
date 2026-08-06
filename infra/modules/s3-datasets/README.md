# `infra/modules/s3-datasets/` — versioned batch datasets and object-access audit

> **Purpose.** This called Terraform module provisions one application dataset
> bucket that is versioned, encrypted with the caller's S3 customer-managed KMS
> key, fully blocked from public access, and denied plaintext transport. It
> declares the prefix and lifecycle contracts for ten generation-dataset
> families plus two non-generation statement artifacts, retains five same-key
> noncurrent versions per prefix by default, invokes a caller-owned function to
> retain the five newest logical generation prefixes, and records dataset object
> reads and writes in a separate versioned CloudTrail audit bucket. Together the
> two retention layers replace the ten baseline
> `DEFINE GENERATIONDATAGROUP ... LIMIT(5) SCRATCH` contracts.
>
> **Source of truth.** The generation inventory comes from the REFERENCE-only
> definitions at `app/jcl/DEFGDGB.jcl:L25-L57`,
> `app/jcl/DEFGDGD.jcl:L28-L76`, and
> `app/jcl/DALYREJS.jcl:L24-L27`; the writer and reader DD statements cited in
> the table below; and the shared copy tier at
> `app/proc/REPROC.prc:L21-L28` and `app/ctl/REPROCT.ctl:L15`. The target
> storage decision is [ADR-003, decision D3](../../../docs/adr/ADR-003-datastore-targets.md):
> relational records remain relational while generation-style artifacts use
> versioned S3 prefixes. The four `.tf` files beside this README are
> authoritative for the module's implemented resources, inputs, and outputs.
>
> **Parameters.** The generated Inputs table is the typed input contract. The
> two required values are `environment` and `kms_key_arn`; the two inventory maps
> have closed default key sets, and the retention, transition, logging,
> destruction, naming and tag controls have validated defaults. Twelve inputs in
> total.
>
> **Return values.** The module publishes ten outputs: seven data-path values
> for the dataset bucket, generation and statement locations, and effective
> same-key retention count; plus the audit bucket name, audit bucket ARN, and
> object-access trail ARN. [Outputs and consumers](#outputs-and-consumers)
> records the dependency direction and the named readers.
>
> **Exceptions / errors.** This document describes a statically validated
> module, not evidence of a live deployment. Apply-time failures include a
> globally occupied bucket name, a KMS key the caller cannot use, an
> unreachable server-access-log target bucket, and destruction of a populated
> bucket while
> `force_destroy` is false. Logical-generation cleanup — pruning all but the
> newest five `dt=`/`gen=` prefixes — is NOT performed by this module. It is the
> calling root's to wire, over the bucket name this module publishes; see
> [What this module deliberately does not own](#what-this-module-deliberately-does-not-own).

> [!WARNING]
> **This is not the Terraform remote-state bucket.** `infra/bootstrap` alone
> owns the state bucket and DynamoDB lock table and is applied separately before
> an environment root uses its backend. Both buckets are versioned, encrypted,
> and publicly blocked, but their retention policies are deliberately inverse:
> the bootstrap bucket declares no noncurrent-version expiration because prior
> state is recovery material, while this module bounds recoverable dataset
> revisions and logical generations.

This README is the prose half of Rule 1's Explainability obligation for HCL.
Terraform has no docstring construct, so the typed variables, output
descriptions, and adjacent rationale in the module provide the mechanical half,
while this document provides the module-level purpose, lineage, alternatives,
trade-offs, and honest boundaries. See the
[code documentation standard](../../../docs/CODE_DOCUMENTATION_STANDARD.md)
and the REFERENCE-only precedent in `tests/README.md` §12.

## Authoritative ten-family inventory

The table is keyed exactly as `var.dataset_families`. A family is a distinct
baseline GDG base name, not a count of `DEFINE` statements: `TRANREPT` is
defined twice but remains one base.

| Map key | Baseline GDG base | `domain` | Record format | Written by / read by | Baseline definition |
|---|---|---|---|---|---|
| `transact-bkup` | `AWS.M2.CARDDEMO.TRANSACT.BKUP` | `ledger` | `LRECL=350`, `RECFM=FB` | Written at `app/jcl/TRANBKP.jcl:L33` and `app/jcl/TRANREPT.jcl:L33`; read at `TRANREPT.jcl:L39` and as `(0)` at `app/jcl/COMBTRAN.jcl:L24` | `app/jcl/DEFGDGB.jcl:L25`; `LIMIT(5)` L26; `SCRATCH` L27 |
| `transact-daly` | `AWS.M2.CARDDEMO.TRANSACT.DALY` | `ledger` | Inherits SORTIN, `LRECL=350` | Written at `app/jcl/TRANREPT.jcl:L55`; read at L66 | `app/jcl/DEFGDGB.jcl:L31`; `LIMIT(5)` L32; `SCRATCH` L33 |
| `tranrept` | `AWS.M2.CARDDEMO.TRANREPT` | `reporting` | `LRECL=133`, `RECFM=FB`; the 133-column report | Written at `app/jcl/TRANREPT.jcl:L80`, with DCB at L78 | `app/jcl/DEFGDGB.jcl:L37`; `LIMIT(5)` L38; `SCRATCH` L39 |
| `tcatbalf-bkup` | `AWS.M2.CARDDEMO.TCATBALF.BKUP` | `ledger` | `LRECL=50`, `RECFM=FB` | Written at `app/jcl/PRTCATBL.jcl:L39`, with DCB at L37; read at L45 | `app/jcl/DEFGDGB.jcl:L43`; `LIMIT(5)` L44; `SCRATCH` L45 |
| `systran` | `AWS.M2.CARDDEMO.SYSTRAN` | `ledger` | `RECFM=F`, `LRECL=350` | Written at `app/jcl/INTCALC.jcl:L41`, with DCB at L39; read as `(0)` at `app/jcl/COMBTRAN.jcl:L26` | `app/jcl/DEFGDGB.jcl:L49`; `LIMIT(5)` L50; `SCRATCH` L51 |
| `transact-combined` | `AWS.M2.CARDDEMO.TRANSACT.COMBINED` | `ledger` | Inherits concatenated SORTIN | Written at `app/jcl/COMBTRAN.jcl:L37`; read back in the same job at L44 | `app/jcl/DEFGDGB.jcl:L55`; `LIMIT(5)` L56; `SCRATCH` L57 |
| `trantype-bkup` | `AWS.M2.CARDDEMO.TRANTYPE.BKUP` | `reference` | `LRECL=60`, `RECFM=FB` | First generation written at `app/jcl/DEFGDGD.jcl:L40`, with DCB at L42 | `app/jcl/DEFGDGD.jcl:L28`; `LIMIT(5)` L29; `SCRATCH` L30 |
| `trancatg-bkup` | `AWS.M2.CARDDEMO.TRANCATG.PS.BKUP` | `reference` | `LRECL=60`, `RECFM=FB` | First generation written at `app/jcl/DEFGDGD.jcl:L63`, with DCB at L65 | `app/jcl/DEFGDGD.jcl:L51`; `LIMIT(5)` L52; `SCRATCH` L53 |
| `discgrp-bkup` | `AWS.M2.CARDDEMO.DISCGRP.BKUP` | `reference` | `LRECL=50`, `RECFM=FB` | First generation written at `app/jcl/DEFGDGD.jcl:L86`, with DCB at L88 | `app/jcl/DEFGDGD.jcl:L74`; `LIMIT(5)` L75; `SCRATCH` L76 |
| `dalyrejs` | `AWS.M2.CARDDEMO.DALYREJS` | `ledger` | `RECFM=F`, `LRECL=430`; the reject-record contract | Written at `app/jcl/POSTTRAN.jcl:L38`, with DCB at L36 | `app/jcl/DALYREJS.jcl:L25`, inside the `DEFINE` opened at L24; `LIMIT(5)` L26; `SCRATCH` L27 |

The count is checkable in two independent ways:

- **By source file:** six from `DEFGDGB.jcl` + three from `DEFGDGD.jcl`
  + one from `DALYREJS.jcl` = **TEN**.
- **By owning domain:** six `ledger` + three `reference` + one `reporting`
  = **TEN**.

**Six is the trap.** `DEFGDGB.jcl` reads like a complete inventory but contains
only six bases; all three definition jobs must be read to find the other four.
Provisioning six would not stop those four families from being written, so it
would fail silently by leaving their logical cleanup contracts absent.

Assumptions: the `domain` values are derived from the bounded-context schemas,
not invented as storage categories. Transaction processing owns `ledger`,
reference data owns `reference`, and reporting produces the 133-column
`reporting` artifact.

## Generation and object-key contract

The complete S3 convention is
`s3://<bucket>/<domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/`.
`dataset_prefixes` supplies the `<domain>/<dataset>/` portion; a writer appends
the business date, generation number, and object leaf.

- **`(+1)` means a new logical generation.** The target creates a new
  `gen=NNNN` prefix and writes a current object version under the new key.
- **`(0)` means the current logical generation.** The target resolves the newest
  valid family prefix and reads the current object version under that key.

`app/jcl/COMBTRAN.jcl` is the clearest proof that the two forms belong to one
contract: it reads `TRANSACT.BKUP(0)` at L24 and `SYSTRAN(0)` at L26, writes
`TRANSACT.COMBINED(+1)` at L37, and reads that same new generation at L44 in
the same job. A target execution must therefore compute or resolve one
generation identity and pass it consistently to every state that touches it.

The complete relative-reference audit is:

- `app/jcl/COMBTRAN.jcl:L24/L26/L37/L44`
- `app/jcl/INTCALC.jcl:L41`
- `app/jcl/POSTTRAN.jcl:L38`
- `app/jcl/TRANBKP.jcl:L33`
- `app/jcl/TRANREPT.jcl:L33/L39/L55/L66/L80`
- `app/jcl/PRTCATBL.jcl:L39/L45`
- `app/jcl/DEFGDGD.jcl:L40/L63/L86`

Alternatives Considered: zero-byte marker objects under each prefix would make
the paths visible before any dataset arrives, but S3 has no directory entity to
provision. A marker would itself become a versioned object governed by
lifecycle, appear in listings as a spurious record, and still would not create
the `dt=` and `gen=` children consumers use. The module therefore declares
prefixes through lifecycle filters and outputs only; a prefix appears when its
first real object is written.

## Retention: two mechanisms, one configured count

### Why bucket versioning is recovery inside the generation mechanism

Alternatives Considered: treating bucket versioning alone as the generation
catalogue would require each family to overwrite one stable object key. That
would discard the required `dt=/gen=` address, make relative generation
resolution opaque, and contradict the ten distinct-key families published to
batch and migration consumers. The other reasonable alternative—writing
distinct generation keys with no common cleanup hook—would make every writer
enumerate, sort, and delete the sixth-oldest prefix, reimplementing `SCRATCH`
semantics at every call site.

The implemented split keeps those concerns separate:

1. The caller-supplied function, invoked by `s3:ObjectCreated:*`, owns
   **logical-prefix retention** across distinct `dt=/gen=` keys. Its contract is
   to enumerate one family, retain the newest configured count, and permanently
   remove every object version and delete marker beneath prefixes that roll
   off. This is the `LIMIT(5) SCRATCH` analogue.
2. S3 bucket versioning plus the prefix-scoped lifecycle rules own
   **same-key recovery**. A retry that writes the same key creates a noncurrent
   version, and `newer_noncurrent_versions` bounds those recoverable revisions.

One central hook avoids seven or more writer-specific copies of deletion logic
while still preserving the explicit object-key convention. The hook is wired in
the calling root, not here: this module publishes `bucket_name`, and each root
declares its own `aws_lambda_permission` and `aws_s3_bucket_notification` over
that bucket. Refactoring Rationale: an `aws_s3_bucket_notification` claims a
bucket's single notification configuration, so declaring one inside a reusable
module would make the module the sole permitted event publisher for every
consumer; the function, its permission, its failure handling and its retention
count are therefore all caller-owned. See
[What this module deliberately does not own](#what-this-module-deliberately-does-not-own).

### Why the logical rule is count-based rather than age-based

Alternatives Considered: an age threshold looks simpler but cannot reproduce
`LIMIT(5)`, which counts generations rather than elapsed days. It fails in both
directions: it **over-deletes when the batch chain pauses**, because five valid
generations can age past the threshold while remaining the five most recent,
and it **under-deletes when the chain runs hot**, because more than five can
arrive inside the window. Only newest-N ordering by business date and generation
number preserves the baseline count.

The lifecycle block also uses `newer_noncurrent_versions`, but only for versions
of one object key. The AWS provider requires `noncurrent_days` as an eligibility
gate, so the module pins it to the smallest legal value, one day; that gate does
not turn logical generation retention into an age policy. The caller must keep
its retention-function configuration aligned with
`noncurrent_version_retention`; both environment roots supply five, and the
output republishes the module value for verification.

Trade-offs: the dataset bucket has one prefix-scoped lifecycle rule for each of
the ten generation families and each of the two statement artifacts, plus one
bucket-wide multipart housekeeping rule. This is more configuration than one
bucket-wide retention rule, but it preserves an auditable link from every
`gdg-<family>` rule to its baseline base and permits a family-specific
`noncurrent_versions` override without changing prefix topology. All ten
defaults leave that override unset and inherit five.

## What this module deliberately does not own

**No object-created notification, and no invoke permission.** Refactoring
Rationale: an `object_created_lambda_arn` input, an `aws_lambda_permission` and an
`aws_s3_bucket_notification` stood here, invoking a caller-supplied function on
every completed object write so it could prune all but the newest five generation
prefixes. All three were removed. An `aws_s3_bucket_notification` is a
WHOLE-BUCKET resource, so a reusable module that declares one claims the bucket's
only notification slot for every consumer of the module; and the function being
wired up belongs to whichever root owns it, not here. Both environment roots now
declare the permission and the notification themselves, over
`module.s3_datasets.bucket_name`, so the behaviour is unchanged and the ownership
sits with the function. Assumptions: that hook and this module's
noncurrent-version lifecycle rule remain complementary rather than alternative — a
lifecycle rule bounds the VERSIONS of one object key, while each generation is
written under a distinct key, so S3 cannot see generation six as a version of
generation five. Alternatives Considered: keeping the input and making it
nullable, which was rejected because a conditionally claimed notification slot is
harder to reason about than an unclaimed one, and the input would still not be one
this module's contract admits.

**No audit-log retention horizon.** Refactoring Rationale: an
`audit_log_retention_days` input defaulted to seven years and drove an expiration
and a noncurrent-version expiration on the audit bucket's lifecycle rule. It was
removed. A compliance retention horizon is an organisational policy decision
rather than a property of a dataset bucket module, and this module had no basis for
the figure it defaulted to. Trade-offs: the audit bucket's lifecycle rule now
expires nothing, so objects accumulate until an owner sets a horizon. That is the
safe direction for an audit trail — the objects are versioned, encrypted and
public-access blocked — and it is preferable to asserting a horizon the module
cannot justify. The rule keeps its `abort_incomplete_multipart_upload` action,
which is storage hygiene rather than retention, and its identifier was renamed to
say so. Note that [`infra/bootstrap`](../../bootstrap/) declares a separate
variable of the same name for its own state-access audit bucket; that one is
untouched and is a different bucket with a different owner.

## Two non-generation statement artifacts

`app/jcl/CREASTMT.JCL` produces two plain sequential datasets that receive S3
locations but do not join the ten-family inventory:

| Map key | Baseline dataset | Delete and write evidence | Record format |
|---|---|---|---|
| `statement-text` | `AWS.M2.CARDDEMO.STATEMNT.PS` | Deleted at `CREASTMT.JCL:L72-L75`; written at L87-L91 | `LRECL=80`, `BLKSIZE=8000`, `RECFM=FB` at L89 |
| `statement-html` | `AWS.M2.CARDDEMO.STATEMNT.HTML` | Deleted at `CREASTMT.JCL:L67-L71`; written at L92-L96 | `LRECL=100`, `BLKSIZE=800`, `RECFM=FB` at L94 |

These are **not generation families**. An exhaustive repository search for
`DEFINE GENERATIONDATAGROUP` finds exactly four files:
`app/jcl/DEFGDGB.jcl`, `app/jcl/DEFGDGD.jcl`,
`app/jcl/DALYREJS.jcl`, and `app/jcl/REPTFILE.jcl`. Those files contain eleven
definitions over ten distinct bases, and none defines either statement
dataset. The separate `non_generation_prefixes` and `non_generation_uris`
outputs preserve that distinction for the statement-producing batch state and
for any consumer that counts generation families.

Trade-offs: the baseline deletes each statement dataset and writes it fresh.
Bucket versioning expresses a rewrite as a new current version while retaining
the replaced version for recovery, so no separate target delete step is needed.
Because versioning is bucket-wide, the two prefixes still need
noncurrent-version rules to prevent unbounded same-key history; those `seq-`
rules are ordinary version hygiene, not the `LIMIT(5) SCRATCH` analogue.

## Observed `REPTFILE.jcl` variant

`app/jcl/REPTFILE.jcl:L25-L28` contains a second standalone definition of the
same `AWS.M2.CARDDEMO.TRANREPT` base defined at
`app/jcl/DEFGDGB.jcl:L37-L39`. The standalone definition specifies
`LIMIT(10)` at L27 and has no `SCRATCH` operand. This is an observed baseline
artifact, not an asserted defect, and nothing in the REFERENCE-only JCL was
changed.

The distinct-base count remains ten because both statements name `TRANREPT`.
The three authoritative inventory sources used by this module define all ten
bases with `LIMIT(5)` and `SCRATCH`, so the default applies five uniformly.
`var.dataset_families[*].noncurrent_versions` can represent a reviewed
per-family variant without changing topology, but every default entry
deliberately leaves it unset.

Assumptions: recording the second definition is necessary even though it does
not change the chosen default. Without this note, the eleventh grep hit and its
different limit could be read either as an eleventh family or as evidence that
the table omitted a contract.

## Shared `IDCAMS REPRO` tier

Refactoring Rationale: `app/proc/REPROC.prc:L21-L28` defines the shared
`PRC001` step as `EXEC PGM=IDCAMS`, with `FILEIN`, `FILEOUT`, and `SYSIN` read
from `&CNTLLIB(REPROCT)`. The control member's operative line is
`REPRO INFILE(FILEIN) OUTFILE(FILEOUT)` at
`app/ctl/REPROCT.ctl:L15`. `TRANBKP.jcl`, `TRANREPT.jcl`, and `PRTCATBL.jcl`
override those DD names to write generation data. In the target, ETL or export
work performs the copy and writes the new S3 generation; this module supplies
the governed destination and performs no data copy.

Refactoring Rationale: each of the six definitions in
`app/jcl/DEFGDGB.jcl` is followed by
`IF LASTCC=12 THEN SET MAXCC=0` at L29, L35, L41, L47, L53, and L59 so a rerun
does not fail when the base already exists. Terraform records managed resources
in state and converges a repeated apply to no change, so there is no
condition-code reset to translate. The baseline made rerun handling explicit
per definition; the target tool provides declarative idempotency.

## Module boundary and usage

This directory is a reusable module, not a Terraform root. The `dev` and `prod`
roots own backend and provider configuration and call the same module source:

```hcl
# WHAT: call the dataset module from an environment root with identities and
#       controls resolved from sibling resources and root-owned variables.
# WHY : Assumptions: keeping provider, backend, KMS, logging, and Lambda wiring in
#       the root preserves one dependency direction and prevents sibling modules
#       from calling one another.
module "s3_datasets" {
  source = "../../modules/s3-datasets"

  name_prefix            = var.name_prefix
  environment            = var.environment
  kms_key_arn            = module.kms.s3_key_arn
  access_log_bucket_name = module.observability.access_log_bucket_name
  force_destroy          = !var.deletion_protection
}
```

The module is never applied or destroyed directly. It is initialized and
validated through an environment root, and deployment or teardown procedures
belong to the [deployment runbook](../../../docs/runbooks/deploy.md) and
[teardown runbook](../../../docs/runbooks/teardown.md).

## Environment parameterization

The `dev` and `prod` roots use identical prefix topology. Environment-owned
values may tune retention and lifecycle behavior without adding or removing a
family:

- `noncurrent_version_retention`
- `noncurrent_version_transition_days`
- `noncurrent_version_transition_storage_class`
- `force_destroy`, where the root's deletion-protection policy permits it

The roots also supply environment-specific resource identities through
`environment`, `kms_key_arn` and `access_log_bucket_name`; those values connect
the same topology to resources owned by that environment. `name_prefix` remains
the shared naming stem.

Assumptions: `dataset_families` and `non_generation_prefixes` are topology
contracts and do not vary by environment. If one root changed either key set,
the batch chain, lifecycle filters, retention function, IAM paths, and migration
staging code would disagree only in that environment. The variable validations
therefore pin the default family set to exactly ten and the statement set to
exactly two.

Trade-offs: the transition day is nullable. Leaving it null keeps noncurrent
versions in their existing storage class until expiry; setting it moves eligible
versions to the configured class. This avoids creating an empty transition
block that the provider rejects, at the cost of one conditional dynamic block
per prefix rule.

## Outputs and consumers

The first seven rows are the data-path return contract required by the batch and
migration design. The final three rows document the audit resources added by
the authored module.

| Output | Contract | Named consumers |
|---|---|---|
| `bucket_name` | Created dataset-bucket name | Environment roots publish it to Parameter Store, pass it to `infra/modules/step-functions-batch`, expose it to reporting, and return the complete module contract |
| `bucket_arn` | Bucket-level IAM resource ARN | Environment-root policies for retention Lambda, reporting, batch, and migration tasks; the KMS module's exact S3 encryption context |
| `dataset_prefixes` | Ten bare `<domain>/<dataset>/` prefixes | Batch and ETL IAM scoping plus `data-migration/src/carddemo_migration/loaders/s3_stage.py` prefix operations |
| `dataset_uris` | Ten fully qualified family URIs | Batch container overrides replacing generation-oriented `DD DSN=` locations |
| `non_generation_prefixes` | Two bare statement prefixes | Statement-generation IAM and prefix operations |
| `non_generation_uris` | Two fully qualified statement URIs | The statement-producing batch state's text and HTML output overrides |
| `noncurrent_version_retention` | Module-level same-key retention count | Retention-function configuration checks, runbooks, and compliance review; per-family overrides remain visible in `dataset_families` |
| `audit_bucket_name` | CloudTrail delivery-bucket name | Environment aggregate outputs, evidence export, and teardown operations |
| `audit_bucket_arn` | Audit bucket's IAM resource ARN | The environment root supplies it to the KMS module's exact bucket-context policy wiring |
| `object_access_trail_arn` | Dataset object-access CloudTrail ARN | Environment aggregate outputs and audit integrations that identify the data-event trail |

Assumptions: both prefix and URI forms are published because they are not
interchangeable at their call sites. IAM resource construction and boto3
`Prefix=` operations need the bare prefix, while a task container override
replacing a JCL `DD DSN=` location carries an S3 URI. Reconstructing either form
inside every consumer would reintroduce hard-coded path composition.

The dependency is one-way: roots, task definitions, IAM policies, runbooks, and
migration code read these names, while this module imports nothing from those
consumers. Renaming or repurposing an output can therefore leave this module's
own validation green while breaking its callers; add a new output instead of
changing an established contract.

## Gating validation

All five checks below are **GATING** in
`.github/workflows/infra-ci.yml`; a nonzero result stops the infrastructure job.

1. **GATING — Formatting:** `terraform fmt -check -recursive infra/` verifies
   canonical HCL formatting without rewriting the reviewed checkout.
2. **GATING — Initialization and validation:** each of `infra/bootstrap`,
   `infra/envs/dev`, and `infra/envs/prod` runs
   `terraform init -backend=false -lockfile=readonly -input=false` followed by
   `terraform validate`. This module is validated transitively through both
   environment roots and is never applied directly.
3. **GATING — TFLint:** the shared configuration enables documented and typed
   variables, documented outputs, required version/provider declarations,
   unused declaration checks, `#` comment syntax, naming convention, and
   standard module structure. The AWS ruleset is initialized before recursive
   lint.
4. **GATING — terraform-docs drift:** terraform-docs v0.20.0 runs with
   `--output-check` over all module and root directories. A stale generated
   region fails; CI does not regenerate or commit documentation.
5. **GATING — Material-security policy:** Checkov evaluates the explicit
   material control set covering encryption, versioning, public-access blocks,
   TLS-only policy, lifecycle, IAM, audit, and logging. The explicit set
   preserves the intended HIGH/CRITICAL security boundary without relying on a
   severity selector that the installed scanner distribution cannot evaluate.

The dataset bucket's controls are satisfied **by construction, not by
suppression**: versioning, CMK encryption, ownership enforcement, all four
public-access flags, the transport deny, lifecycle, optional server logging,
and data-event audit are explicit resources. There is no `tflint-ignore` in
this module. The separate audit-delivery path has exactly two bounded Checkov
annotations, both asserted by a workflow guard before Checkov runs:

- `CKV_AWS_145` records why the dedicated CloudTrail bucket uses SSE-S3 instead
  of widening the financial dataset CMK to the delivery service principal.
- `CKV_AWS_35` records the same key-boundary decision on the CloudTrail trail;
  log-file validation and the exact-source bucket policy remain enabled.

These are reviewed architecture contracts, not a blanket scanner exemption. If
the dataset bucket's access-logging check fires, the resolution is to supply
`access_log_bucket_name` from the environment root, never to add a suppression.

## Caveats and honest boundaries

- **Not the state backend.** `infra/bootstrap` owns the remote-state bucket and
  lock table. Its state-history retention is intentionally unbounded by a
  noncurrent-version lifecycle rule.
- **Static validation only.** No claim is made that either bucket has been
  provisioned, populated, load-tested, or measured in a live account. Applying
  an environment root is an operator action outside this module's scope.
- **Protected destruction.** `force_destroy` defaults to `false`, so deletion
  of a populated dataset bucket fails. A versioned bucket must be purged of
  current objects, noncurrent versions, and delete markers through the
  version-aware procedure in the
  [teardown runbook](../../../docs/runbooks/teardown.md).
- **Caller-owned logical cleanup.** This module declares neither the retention
  function, nor its invoke permission, nor the bucket notification that triggers
  it; all three belong to the calling root. Object creation succeeds before any
  asynchronous retention function finishes. A root-owned function must be able to
  list family prefixes, delete every retained version and marker beneath a
  rolled-off prefix, surface partial failures, and use the same count this module
  publishes as `noncurrent_version_retention`. Notification or function failure
  does not make the original object write transactional.
- **Optional server access logging.** A null `access_log_bucket_name` omits the
  server-logging resource so the module can be instantiated without a
  pre-existing log bucket. Both environment roots are expected to supply a
  different target bucket; targeting the dataset bucket itself would create
  recursive log writes.
- **Audit scope.** CloudTrail records data events for objects under the dataset
  bucket. The audit bucket is versioned, publicly blocked, TLS-only, and subject
  to its own finite evidence lifecycle; it is not another dataset-generation
  store.
- **Topology boundary.** Cross-region replication, multi-region or
  disaster-recovery topology, S3 Object Lock, and compliance-retention mode are
  outside this module. The target remains one region across three availability
  zones.
- **Additive migration.** The JCL, GDG definitions, procedures, control member,
  and datasets remain intact and REFERENCE-only. The migration adds an S3 path;
  it does not remove the mainframe path.

## Design decisions

- Assumptions: ten is a closed lineage contract, not a configurable deployment
  size. The validation names all ten keys because a length-only check could
  accept nine correct families plus one invented family while still reporting
  ten.
- Trade-offs: logical generation retention depends on a caller-owned
  ObjectCreated function, accepting asynchronous cleanup in exchange for one
  path that covers batch, retry, migration, and ad-hoc writers. Versioning alone
  cannot count distinct `gen=` keys.
- Alternatives Considered: a days-based logical expiry was rejected because it
  over-deletes after a pause and under-deletes during a dense run; newest-N
  ordering is the only policy equivalent to `LIMIT(5)`.
- Refactoring Rationale: the target replaces catalogued relative names and the
  shared `IDCAMS REPRO` copy tier with explicit S3 prefixes and ETL/export
  writes. Prefixes and URIs are published rather than recomposed in each task so
  the location governed by lifecycle cannot drift from the location consumers
  write.

## Related documents

- [Infrastructure guide](../../README.md) — module index, root/module boundary,
  parameterization, and package-wide gates.
- [ADR-003: Datastore Targets](../../../docs/adr/ADR-003-datastore-targets.md) —
  accepted D3 choice for relational data and generation artifacts.
- [Batch orchestration](../../../docs/architecture/batch-orchestration.md) —
  independently publishes the same ten families, relative references, and
  `dt=/gen=` convention; its inventory and this one must agree.
- [Batch operations runbook](../../../docs/runbooks/batch-operations.md) —
  execution inspection and redrive procedures.
- [Teardown runbook](../../../docs/runbooks/teardown.md) — guarded,
  version-aware bucket purge.
- [Code documentation standard](../../../docs/CODE_DOCUMENTATION_STANDARD.md) —
  Rule 1's polyglot documentation contract.
- [Data migration README](../../../data-migration/README.md) — independently
  confirms ten generation families and keeps the two statement artifacts
  separate.

## Generated Terraform reference

The block below is generated by terraform-docs v0.20.0 from `versions.tf`,
`main.tf`, `variables.tf`, and `outputs.tf`. The tracked module lock file makes
the Providers row show the resolved AWS provider release. Do not edit between
the markers; use terraform-docs to regenerate after an HCL contract change.

<!-- BEGIN_TF_DOCS -->
### Requirements

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 1.15.0 |
| <a name="requirement_aws"></a> [aws](#requirement\_aws) | ~> 6.56 |

### Providers

| Name | Version |
|------|---------|
| <a name="provider_aws"></a> [aws](#provider\_aws) | 6.57.1 |

### Resources

| Name | Type |
|------|------|
| [aws_cloudtrail.dataset_object_access](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudtrail) | resource |
| [aws_s3_bucket.audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket) | resource |
| [aws_s3_bucket.datasets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket) | resource |
| [aws_s3_bucket_lifecycle_configuration.audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_lifecycle_configuration) | resource |
| [aws_s3_bucket_lifecycle_configuration.datasets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_lifecycle_configuration) | resource |
| [aws_s3_bucket_logging.datasets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_logging) | resource |
| [aws_s3_bucket_ownership_controls.audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_ownership_controls) | resource |
| [aws_s3_bucket_ownership_controls.datasets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_ownership_controls) | resource |
| [aws_s3_bucket_policy.audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_policy) | resource |
| [aws_s3_bucket_policy.datasets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_policy) | resource |
| [aws_s3_bucket_public_access_block.audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_public_access_block) | resource |
| [aws_s3_bucket_public_access_block.datasets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_public_access_block) | resource |
| [aws_s3_bucket_server_side_encryption_configuration.audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_server_side_encryption_configuration) | resource |
| [aws_s3_bucket_server_side_encryption_configuration.datasets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_server_side_encryption_configuration) | resource |
| [aws_s3_bucket_versioning.audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_versioning) | resource |
| [aws_s3_bucket_versioning.datasets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_versioning) | resource |
| [aws_caller_identity.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_iam_policy_document.audit_bucket](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.tls_only](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_partition.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/partition) | data source |
| [aws_region.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/region) | data source |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_environment"></a> [environment](#input\_environment) | Deployment environment that owns this bucket, supplied by the calling root: infra/envs/dev passes dev and infra/envs/prod passes prod. It appears verbatim in the composed bucket name, which is what stops two environments in one account resolving to the same bucket, and it is the only axis along which this module's inputs are expected to differ. | `string` | n/a | yes |
| <a name="input_kms_key_arn"></a> [kms\_key\_arn](#input\_kms\_key\_arn) | ARN of the S3 customer-managed KMS key produced by infra/modules/kms, used as the SSE-KMS key for every object written to this bucket. Required, because the module offers no unencrypted mode. | `string` | n/a | yes |
| <a name="input_abort_incomplete_multipart_upload_days"></a> [abort\_incomplete\_multipart\_upload\_days](#input\_abort\_incomplete\_multipart\_upload\_days) | Age in days after which an incomplete multipart upload is aborted and its already-uploaded parts deleted. Applies to the whole bucket rather than to one prefix. | `number` | `7` | no |
| <a name="input_access_log_bucket_name"></a> [access\_log\_bucket\_name](#input\_access\_log\_bucket\_name) | Name of an existing bucket that receives S3 server access logs for this bucket. Null disables access logging, which is the module default so that the module can be instantiated without a logging bucket already in place. | `string` | `null` | no |
| <a name="input_dataset_families"></a> [dataset\_families](#input\_dataset\_families) | Generation-dataset families to provision a prefix and a noncurrent-version lifecycle rule for, keyed by the S3-safe family name main.tf uses as the dataset path segment. Each value carries: domain, the bounded context owning the data, which becomes the leading path segment; description, recording the baseline generation-data-group base the family replaces and the JCL line defining it; and noncurrent\_versions, an optional per-family override of noncurrent\_version\_retention that is left unset on every entry in the default. | <pre>map(object({<br/>    domain              = string<br/>    description         = string<br/>    noncurrent_versions = optional(number)<br/>  }))</pre> | <pre>{<br/>  "dalyrejs": {<br/>    "description": "Daily transaction reject-stream generations, carrying the reject record the posting run writes for each of the four documented reject reasons. Replaces GDG base AWS.M2.CARDDEMO.DALYREJS named at app/jcl/DALYREJS.jcl:L25 inside the DEFINE opened at L24, with LIMIT(5) at L26 and SCRATCH at L27.",<br/>    "domain": "ledger"<br/>  },<br/>  "discgrp-bkup": {<br/>    "description": "Disclosure-group reference backup generations, the interest-rate table the interest run reads. Replaces GDG base AWS.M2.CARDDEMO.DISCGRP.BKUP defined at app/jcl/DEFGDGD.jcl:L74 with LIMIT(5) at L75 and SCRATCH at L76; first generation loaded as (+1) at app/jcl/DEFGDGD.jcl:L86 at LRECL=50.",<br/>    "domain": "reference"<br/>  },<br/>  "systran": {<br/>    "description": "System-generated transaction generations, the interest and fee transactions the interest run emits. Replaces GDG base AWS.M2.CARDDEMO.SYSTRAN defined at app/jcl/DEFGDGB.jcl:L49 with LIMIT(5) at L50 and SCRATCH at L51; read back as (0) by app/jcl/COMBTRAN.jcl:L26.",<br/>    "domain": "ledger"<br/>  },<br/>  "tcatbalf-bkup": {<br/>    "description": "Transaction-category-balance backup generations. Replaces GDG base AWS.M2.CARDDEMO.TCATBALF.BKUP defined at app/jcl/DEFGDGB.jcl:L43 with LIMIT(5) at L44 and SCRATCH at L45.",<br/>    "domain": "ledger"<br/>  },<br/>  "trancatg-bkup": {<br/>    "description": "Transaction-category reference backup generations. Replaces GDG base AWS.M2.CARDDEMO.TRANCATG.PS.BKUP defined at app/jcl/DEFGDGD.jcl:L51 with LIMIT(5) at L52 and SCRATCH at L53; first generation loaded as (+1) at app/jcl/DEFGDGD.jcl:L63 at LRECL=60.",<br/>    "domain": "reference"<br/>  },<br/>  "tranrept": {<br/>    "description": "Transaction report generations, the 133-column fixed-width output. Replaces GDG base AWS.M2.CARDDEMO.TRANREPT defined at app/jcl/DEFGDGB.jcl:L37 with LIMIT(5) at L38 and SCRATCH at L39; written as (+1) by app/jcl/TRANREPT.jcl:L80 at LRECL=133. A second, conflicting definition of the same base exists at app/jcl/REPTFILE.jcl:L25-L28 with LIMIT(10) and no SCRATCH; the LIMIT(5) definition is the one applied.",<br/>    "domain": "reporting"<br/>  },<br/>  "transact-bkup": {<br/>    "description": "Transaction master backup generations. Replaces GDG base AWS.M2.CARDDEMO.TRANSACT.BKUP defined at app/jcl/DEFGDGB.jcl:L25 with LIMIT(5) at L26 and SCRATCH at L27; written as (+1) by app/jcl/TRANBKP.jcl:L33 at LRECL=350 and read back as (0) by app/jcl/COMBTRAN.jcl:L24.",<br/>    "domain": "ledger"<br/>  },<br/>  "transact-combined": {<br/>    "description": "Combined transaction generations, the merge of the transaction backup and the system transactions. Replaces GDG base AWS.M2.CARDDEMO.TRANSACT.COMBINED defined at app/jcl/DEFGDGB.jcl:L55 with LIMIT(5) at L56 and SCRATCH at L57; written as (+1) by app/jcl/COMBTRAN.jcl:L37.",<br/>    "domain": "ledger"<br/>  },<br/>  "transact-daly": {<br/>    "description": "Daily transaction generations staged for posting. Replaces GDG base AWS.M2.CARDDEMO.TRANSACT.DALY defined at app/jcl/DEFGDGB.jcl:L31 with LIMIT(5) at L32 and SCRATCH at L33; written as (+1) by app/jcl/TRANREPT.jcl:L55.",<br/>    "domain": "ledger"<br/>  },<br/>  "trantype-bkup": {<br/>    "description": "Transaction-type reference backup generations. Replaces GDG base AWS.M2.CARDDEMO.TRANTYPE.BKUP defined at app/jcl/DEFGDGD.jcl:L28 with LIMIT(5) at L29 and SCRATCH at L30; first generation loaded as (+1) at app/jcl/DEFGDGD.jcl:L40 at LRECL=60.",<br/>    "domain": "reference"<br/>  }<br/>}</pre> | no |
| <a name="input_force_destroy"></a> [force\_destroy](#input\_force\_destroy) | Whether Terraform may delete this bucket while it still holds objects, including noncurrent versions. False makes a destroy of a non-empty bucket fail rather than discard its contents. | `bool` | `false` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Leading token of the bucket name, which main.tf composes as <name\_prefix>-datasets-<environment>-<account-id>-<region>. This is what distinguishes the CardDemo dataset bucket from every other bucket in the account, and it is also the stem the module derives its resource names and tags from. | `string` | `"carddemo"` | no |
| <a name="input_non_generation_prefixes"></a> [non\_generation\_prefixes](#input\_non\_generation\_prefixes) | Prefixes for baseline datasets that are NOT generation data groups, keyed by the S3-safe name main.tf uses as the dataset path segment. Each value carries a domain, the owning bounded context, and a description recording the baseline dataset and the JCL line that writes it. Held separately from dataset\_families so these can never be counted as additional generation families. | <pre>map(object({<br/>    domain      = string<br/>    description = string<br/>  }))</pre> | <pre>{<br/>  "statement-html": {<br/>    "description": "HTML customer statements. Replaces sequential dataset AWS.M2.CARDDEMO.STATEMNT.HTML, deleted by the IEFBR14 step at app/jcl/CREASTMT.JCL:L71 and rewritten by CBSTM03A at L96 with DCB=(LRECL=100,BLKSIZE=800,RECFM=FB) declared at L94. Not a generation data group: no GENERATIONDATAGROUP base for it exists in the baseline.",<br/>    "domain": "reporting"<br/>  },<br/>  "statement-text": {<br/>    "description": "Plain-text customer statements. Replaces sequential dataset AWS.M2.CARDDEMO.STATEMNT.PS, deleted by the IEFBR14 step at app/jcl/CREASTMT.JCL:L75 and rewritten by CBSTM03A at L91 with DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB) declared at L89. Not a generation data group: no GENERATIONDATAGROUP base for it exists in the baseline.",<br/>    "domain": "reporting"<br/>  }<br/>}</pre> | no |
| <a name="input_noncurrent_version_retention"></a> [noncurrent\_version\_retention](#input\_noncurrent\_version\_retention) | Default retention count shared by two mechanisms: data-migration's staging writer keeps this many logical dt=/gen= generation prefixes per family, reproducing LIMIT(5) SCRATCH; S3 lifecycle also keeps this many newer noncurrent versions of any one object key as repeat-write recovery. Distinct gen= prefixes are not noncurrent versions of each other. | `number` | `5` | no |
| <a name="input_noncurrent_version_transition_days"></a> [noncurrent\_version\_transition\_days](#input\_noncurrent\_version\_transition\_days) | Age in days at which a noncurrent version moves to the storage class named by noncurrent\_version\_transition\_storage\_class. Null disables the transition entirely, leaving noncurrent versions in the class they were written to until they expire. | `number` | `null` | no |
| <a name="input_noncurrent_version_transition_storage_class"></a> [noncurrent\_version\_transition\_storage\_class](#input\_noncurrent\_version\_transition\_storage\_class) | Storage class a noncurrent version transitions into, read only when noncurrent\_version\_transition\_days is non-null. Ignored entirely while that value is null. | `string` | `"STANDARD_IA"` | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Additional tags merged onto the resources this module creates, over and above whatever the calling root's provider-level default\_tags already applies. Empty by default, so the module contributes no tags of its own unless a caller asks for them. | `map(string)` | `{}` | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_audit_bucket_arn"></a> [audit\_bucket\_arn](#output\_audit\_bucket\_arn) | ARN of the versioned dataset object-access audit bucket, consumed by exact KMS encryption-context policy wiring in the environment root. |
| <a name="output_audit_bucket_name"></a> [audit\_bucket\_name](#output\_audit\_bucket\_name) | Name of the versioned bucket receiving validated CloudTrail data-event logs for dataset object reads and writes. |
| <a name="output_bucket_arn"></a> [bucket\_arn](#output\_bucket\_arn) | ARN of the dataset bucket in its bucket-level form, for the IAM task-role policies that authorise access to it: the batch-service and data-migration ETL roles scope object permissions to this value with /* appended, and scope bucket-level operations such as a prefix listing to this value unsuffixed. |
| <a name="output_bucket_name"></a> [bucket\_name](#output\_bucket\_name) | Name of the versioned dataset bucket, for the callers that must be given it rather than hard-code it: infra/envs/dev/main.tf and infra/envs/prod/main.tf write it into Parameter Store, infra/modules/step-functions-batch passes it to each Fargate batch task as a container override, and data-migration/src/carddemo\_migration/loaders/s3\_stage.py reads it to stage dataset generations. This is the application dataset bucket, not the Terraform state bucket that infra/bootstrap/outputs.tf publishes as state\_bucket\_name. |
| <a name="output_dataset_prefixes"></a> [dataset\_prefixes](#output\_dataset\_prefixes) | Key prefix per generation-dataset family: one entry for each of the ten families, keyed exactly as var.dataset\_families is keyed and valued as the <domain>/<dataset>/ prefix that family's generations live under. Read by infra/modules/step-functions-batch for its per-state container overrides, by data-migration/src/carddemo\_migration/loaders/s3\_stage.py, and by the IAM policies that scope a task role to one family's prefix. Supplies the <domain>/<dataset>/ portion only; the dt= and gen= segments of a generation key are chosen per run by the writer. |
| <a name="output_dataset_uris"></a> [dataset\_uris](#output\_dataset\_uris) | Fully-qualified s3:// URI per generation-dataset family: the same ten keys as dataset\_prefixes, each resolved against the created bucket. This is the form infra/modules/step-functions-batch puts in a Fargate container override in place of a JCL DD DSN= statement, whereas dataset\_prefixes carries the bare-prefix form that an IAM resource pattern and a boto3 Prefix= argument need. Addresses the family, not a generation: a writer appends its own dt= and gen= segments. |
| <a name="output_non_generation_prefixes"></a> [non\_generation\_prefixes](#output\_non\_generation\_prefixes) | Key prefix per non-generation dataset -- the two sequential statement artifacts, plain text and HTML -- keyed exactly as var.non\_generation\_prefixes is keyed. Read by the GenerateStatements batch state, which writes both statements to S3. Deliberately separate from dataset\_prefixes: neither artifact has a generation-data-group base in the baseline, so counting them among the generation families would report twelve where variables.tf, docs/architecture/batch-orchestration.md and data-migration/README.md all publish ten. |
| <a name="output_non_generation_uris"></a> [non\_generation\_uris](#output\_non\_generation\_uris) | Fully-qualified s3:// URI per non-generation dataset: the same two keys as non\_generation\_prefixes, each resolved against the created bucket, so the GenerateStatements batch state receives its plain-text and HTML output locations as container overrides in the same form the generation-writing states receive theirs. |
| <a name="output_noncurrent_version_retention"></a> [noncurrent\_version\_retention](#output\_noncurrent\_version\_retention) | Effective number of noncurrent object versions the module retains per prefix, republished so a runbook, a verification query or a compliance review can confirm the baseline's LIMIT(5) SCRATCH generation limit is still being reproduced without reading the module's HCL. Reflects the module-level value only: a per-family override supplied through a dataset\_families entry's noncurrent\_versions member is not folded in. |
| <a name="output_object_access_trail_arn"></a> [object\_access\_trail\_arn](#output\_object\_access\_trail\_arn) | ARN of the CloudTrail trail whose advanced selector audits object-level access to the CardDemo dataset bucket. |
<!-- END_TF_DOCS -->
