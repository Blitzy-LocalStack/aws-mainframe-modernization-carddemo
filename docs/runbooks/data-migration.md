# Data Migration Runbook

> **Purpose.** Validate the CardDemo migration configuration, create database
> schemas and protected reporting views, and stage immutable source extracts.
>
> **Source of truth.** Copybook layouts under `app/cpy/**`,
> `data-migration/sql/**`, `data-migration/src/carddemo_migration/**`, and
> `docs/architecture/data-model-and-schema-mapping.md`.

The current Python package implements configuration/trust validation, the
normative layout catalogue, the twelve fixed-width record readers, the Aurora
bulk loader, all three verification passes and the combined verification gate, and it
exposes them as the `stage-dataset`, `refresh-dataset`, `load-dataset`,
`reconcile-sequences`, `verify-row-counts`, `verify-checksum`, `verify-money-parity`,
`verify-row-count-report`, `verify-money-total-report` and `verify-all` subcommands used
below. Every step in this runbook is executable and fails closed. **Eleven records are
loadable**, covering all eight schemas' seeded tables, and the three conditions a cutover
still turns on are stated at the gate at the end.

Refactoring Rationale: this paragraph twice described a narrower package than the
one that now ships. It first stated that the reader and bulk-loader CLI was absent,
which was true until those modules landed; it then stated that `CUSTOMER` and
`CARD` could not be loaded at all, which was true until the loader gained the
envelope ciphers their `*_encrypted` columns require. It is rewritten rather than
deleted because an operator who had read either earlier version would otherwise
conclude that steps below were aspirational, and skip them.

Assumptions: PostgreSQL is reachable only over TLS with a trusted CA, and the
operator uses a temporary database identity with the privileges required by the
schema bootstrap. No database password is placed in a command argument or
committed file.

## Validate the Package

```bash
# WHAT: install the hash-locked development closure and run the Python gates.
# WHY : Assumptions: the development manifest includes the runtime closure plus
#       Ruff and pytest, so one install reproduces the CI toolchain.
source .venv/bin/activate
python -m pip install --require-hashes -r data-migration/requirements-dev.txt
ruff check data-migration
python -m compileall -q data-migration/src
python -m pytest -v --tb=short data-migration/tests
```

## Stage Source Extracts

The files under `app/data/**` remain reference-only locally; staging copies
their bytes without transcoding.

```bash
# WHAT: copy the baseline source extracts into the dataset bucket's source-extract prefix.
# WHY : Assumptions: EBCDIC sign and packed bytes must remain opaque until a
#       field-aware decoder consumes them; text-mode conversion would corrupt them.
# WHY : Assumptions: sync preserves the SUBDIRECTORY name, so app/data/EBCDIC/ lands at
#       migration/source/EBCDIC/ -- which is exactly the default of the extract prefix
#       the nightly refresh reads. Syncing the parent rather than each child is what
#       keeps that correspondence true without naming it twice.
# WHY : Refactoring Rationale: the bucket name is read from the `datasets` output object
#       rather than from a `dataset_bucket_name` root output. There is no such root
#       output -- each environment root publishes the s3-datasets module whole, under
#       `datasets` -- so `output -raw dataset_bucket_name` fails with "Output
#       \"dataset_bucket_name\" not found" and the sync then ran against `s3:///...`.
ENVIRONMENT=dev
DATASET_BUCKET="$(terraform -chdir="infra/envs/${ENVIRONMENT}" \
  output -json datasets | jq -r '.bucket_name')"
aws s3 sync app/data/ "s3://${DATASET_BUCKET}/migration/source/" \
  --no-follow-symlinks
```

### One prefix, read by the chain and by the operator commands alike

The `aws s3 sync` above is the whole handover, and it populates the **only** landing
prefix there is. The nightly `StageSeedDatasets` branches read each extract from it
because the state machine passes `--extract-prefix=<dataset_source_extract_prefix>` to
every branch, and that input is wired from the `s3-datasets` module's
`source_extract_prefix` output; the operator-invoked `stage-dataset`, `load-dataset`,
`decode-record` and `verify-all` commands read the same place because the module composes
`CARDDEMO_DATASET_STAGING_ROOT` as `s3://<dataset bucket>/<dataset_source_extract_prefix>`
and passes it to every task. Both resolve `migration/source/EBCDIC/` by default, and the
extracts sit **flat** beneath it under the exact file names the seed-dataset registry
records, because every one of those callers joins a dataset's registered source-object
name to the root. Confirm the two agree with where the sync wrote before the first
nightly run:

```bash
# WHAT: print the prefix the orchestrator will read, the root the container receives, and
#       list what is under it.
# WHY : Assumptions: both values are read from the roots' AGGREGATE outputs rather than
#       from scalar ones. Each environment root publishes one output per module -- there is
#       no `dataset_bucket_name` or `dataset_source_prefix` at the root level to read with
#       `output -raw` -- so the URI comes out of `datasets` and the resolved container root
#       out of `batch_orchestration`, which is the module that composes it and is therefore
#       the authority on what the tasks receive. Reading the outputs rather than restating
#       the defaults is what keeps a tfvars override from making this runbook silently
#       wrong.
ENVIRONMENT=dev
EXTRACT_URI="$(terraform -chdir="infra/envs/${ENVIRONMENT}" \
  output -json datasets | jq -r '.source_extract_uri')"
STAGING_ROOT="$(terraform -chdir="infra/envs/${ENVIRONMENT}" \
  output -json batch_orchestration | jq -r '.dataset_staging_root')"
test "${EXTRACT_URI%/}" = "${STAGING_ROOT%/}" \
  || echo "WARNING: the chain reads $EXTRACT_URI but the containers resolve $STAGING_ROOT"
aws s3 ls "$EXTRACT_URI"
```

Refactoring Rationale: this step used to instruct an operator to mount the extracts
on a **filesystem path** inside the data-migration container, supplied as
`CARDDEMO_DATASET_STAGING_ROOT` from a `dataset_staging_root` module input defaulting
to `/mnt/carddemo-extracts`. That instruction had no receiver. The module provisions no
filesystem and no volume, so nothing in the deployable package could satisfy it, and the
nightly chain therefore depended on an out-of-band action that the migration plan's own
end-to-end deployability constraint forbids. The filesystem default is withdrawn:
`dataset_staging_root` survives only as an operator OVERRIDE, both environment roots
leave it null, and the variable the container receives therefore resolves to
`s3://<dataset bucket>/<dataset_source_extract_prefix>` — an object-storage location read
through the same client and credentials the task already uses to write generations. The
single-read-path property the mount was justified by is preserved: a branch downloads
the object once to a temporary path, digests it there, and every later step of the
branch reads that one local copy.

⚠ Refactoring Rationale — **there was briefly a SECOND landing prefix here, and it is
withdrawn.** This section carried two syncs: the archival one above, and a flat copy of
`app/data/EBCDIC/` into a separate prefix read only by the operator commands, on the
grounds that those commands compose one key per dataset as `<prefix>/<source object>` and
so need the extracts flat. The first half of that is true and is why the sync above lands
`app/data/EBCDIC/` at `migration/source/EBCDIC/` — a prefix under which the extracts
already are flat. The second half was not: two inputs for the same location,
`dataset_source_prefix` (defaulting to `source-extracts`, refusing a trailing slash) and
`dataset_inbox_prefix` (defaulting to `inbox`), were authored beside
`dataset_source_extract_prefix` and neither was passed by any environment root, so a
second sync addressed a prefix the chain never reads while the chain read one the second
sync never filled. Four spellings of "where the seed extracts are" cannot be kept honest,
so the duplicates went and the consumer stayed. One sync, one prefix, one root.

⚠ The object-storage form is not a convenience. The staging task runs on Fargate from an
image that ships no extract -- its Dockerfile copies only `src/` and `sql/`, so no
baseline data is baked into a published layer -- and its task definition mounts no
volume, so a filesystem path cannot be satisfied by the step that runs it. The task role
already reads this bucket for the generations it writes, so the landing prefix needs no
additional grant.

Assumptions: populating the prefix is an **operator action** and this is the step that
owns it. Refreshing an extract is therefore not an image rebuild.

Assumptions: the source-extract prefix is deliberately NOT one of the ten generation
prefixes the `s3-datasets` module provisions, and not one of its three reporting-artifact
prefixes either. Every prefix in that inventory carries a five-noncurrent-version
lifecycle rule, which is the `LIMIT(5) SCRATCH` analogue for output this package writes;
this one holds input the operator writes, whose retention is the operator's decision and
which is read once per execution. Attaching a generation-retention rule to it would delete
the source before a rerun could read it, and would raise a prefix count three sibling
documents publish.

```bash
# WHAT: confirm the eleven registered seed extracts are present at the configured
#       root, named as the registry expects, and each a whole number of records.
# WHY : Assumptions: the registry is the authority for BOTH the file name and the
#       record length, so this check derives every expectation from it rather than
#       restating a table that could drift. A missing object or a non-zero remainder
#       here is the same failure the staging branch would report, found before the
#       nightly window rather than during it.
# WHY : Assumptions: the root is read through the package's own two-form resolver, so
#       this check accepts exactly what the commands accept -- an `s3://` prefix for the
#       deployment, a directory for an operator over a checkout -- and cannot pass
#       against a layout they would reject. Use the `aws s3 ls` above when what is in
#       question is the prefix rather than the objects under it.
export CARDDEMO_DATASET_STAGING_ROOT="${STAGING_ROOT}"
python <<'PY'
import os
import sys
from pathlib import Path

import boto3

from carddemo_migration import seed_datasets

setting = os.environ["CARDDEMO_DATASET_STAGING_ROOT"]
client = boto3.client("s3") if seed_datasets.object_store_location(setting) else None
problems = 0
for token in seed_datasets.seed_dataset_tokens():
    descriptor = seed_datasets.seed_dataset(token)
    located = seed_datasets.extract_location(descriptor, setting)
    length = seed_datasets.record_length(descriptor)
    if isinstance(located, Path):
        path = Path(setting) / located
        if not path.is_file():
            print(f"MISSING  {token}: {path}")
            problems += 1
            continue
        size = path.stat().st_size
    else:
        try:
            size = client.head_object(Bucket=located.bucket, Key=located.key)["ContentLength"]
        except client.exceptions.ClientError:
            print(f"MISSING  {token}: {located.describe()}")
            problems += 1
            continue
    remainder = size % length
    status = "ok" if remainder == 0 else f"PARTIAL RECORD ({remainder} trailing bytes)"
    problems += 1 if remainder else 0
    print(f"{status:<32} {token} -> {descriptor.source_object} ({length}-byte records)")
sys.exit(1 if problems else 0)
PY
```

## Create Schemas, Reporting Views, Runtime Delete Grants and Verification Surfaces

Set `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, and `PGPASSWORD` through an
approved secret-delivery channel. Point `PGSSLROOTCERT` at the pinned CA bundle.

Four SQL artifacts ship here and their order is **not interchangeable**.
`V0__schemas_and_roles.sql` runs before any table exists, so it can only express
privileges schema-wide or as default privileges; `V2__runtime_delete_grants.sql`
names individual tables and `V3__verification_surfaces.sql` creates views over
them, so neither can run until the owning services' Flyway migrations have created
those tables. The full sequence is the one recorded in
[data-model-and-schema-mapping.md](../architecture/data-model-and-schema-mapping.md),
extended by one step:
`V0` -> each owning service's Flyway migration -> `V1__reporting_views.sql` ->
`V2__runtime_delete_grants.sql` -> `V3__verification_surfaces.sql` -> `V0` once
more.

### The one migration that constrains this order in the other direction

`transaction-service`'s `V3__ledger_bytewise_collation.sql` recollates
`ledger.transactions.transaction_id` to `"C"`, and PostgreSQL refuses to alter the
type or collation of a column a view selects. Two of the views created by
`V1__reporting_views.sql` select that column — `reporting.v_report_transactions` and
`reporting.v_statement_transactions` — so the sequence above is a **precondition**
for that migration, not merely a convention: on a first deployment the service's
Flyway history runs before any view exists and the migration applies cleanly.

On an environment that is already past the view step and has **not** yet applied
that migration, the service will refuse to start and the migration will report the
remedy rather than the engine's own message. The remedy is three steps, in this
order:

```bash
# WHAT: drop only the two views that select the ledger key, apply the migration by
#       starting the service, then recreate the views WITH their grants.
# WHY : the views are recreated by re-running the script that owns them rather than
#       by hand, because CREATE VIEW does not restore the grants that script issues
#       to carddemo_reporting_owner and carddemo_reporting -- a hand-recreated view
#       would exist and be readable by nobody.
psql -v ON_ERROR_STOP=1 -c 'DROP VIEW reporting.v_report_transactions, reporting.v_statement_transactions;'

# Start transaction-service so its Flyway history advances; the migration rewrites
# ledger.transactions and rebuilds pk_transactions under the new collation.

psql -v ON_ERROR_STOP=1 -f data-migration/sql/V1__reporting_views.sql
```

Verify the outcome from the catalogue rather than from the absence of an error,
because the ordering guarantee is a property of the column and not of the run:

```bash
# WHAT: confirm the ledger key is collated "C" and that its neighbour is untouched.
# WHY : pg_attribute is read and NOT pg_indexes -- once a column carries a
#       collation, an index over it stops printing a COLLATE clause of its own, so an
#       index-text check passes on the wrong schema and fails on the right one.
psql -v ON_ERROR_STOP=1 -c "SELECT att.attname, coalesce(coll.collname,'default') AS collation
  FROM pg_attribute att
  JOIN pg_class rel ON rel.oid = att.attrelid
  JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
  LEFT JOIN pg_collation coll ON coll.oid = att.attcollation
 WHERE nsp.nspname = 'ledger' AND rel.relname = 'transactions'
   AND att.attname IN ('transaction_id','card_num') ORDER BY 1;"
# Expect: card_num | default   and   transaction_id | C
```

### An applied migration whose file has changed: `flyway repair`

A service refuses to start with
`FlywayValidateException: Migration checksum mismatch for migration version <n>` when
the bytes of a migration it already applied are not the bytes it resolves now. Flyway
checksums the **whole file**, so this happens for a change to comment text alone —
which is how it happened here: `services/reference-service/.../V1__reference.sql` was
rewritten for comment style after it had been applied, with byte-identical executable
SQL, and every database holding the earlier bytes then refused startup while the
schema itself was entirely correct.

The standing rule is therefore that **an applied migration file is immutable** and a
further change goes into a new migration; each service's test tree pins the checksum
of every script it ships so that an edit fails a build rather than a deployment.
`repair` is the remedy for the environments that already hold superseded bytes, and it
has one precondition that must be checked first, because `repair` realigns the stored
checksum **without re-running anything**:

```bash
# WHAT: prove the executable SQL is unchanged between the applied revision and the
#       resolved one BEFORE realigning any checksum.
# WHY : Assumptions: repair rewrites flyway_schema_history to accept the current file
#       and applies no statement, so if the SQL did change, repair records a schema the
#       database does not have and every later migration builds on a false premise.
#       Comments are stripped from both sides because a comment-only difference is the
#       one case repair is the right answer to; a non-empty diff here means the change
#       belongs in a NEW migration instead.
git show "<applied-revision>:<path-to-migration>" | grep -vE '^\s*--' | grep -v '^$' > /tmp/applied.sql
grep -vE '^\s*--' "<path-to-migration>" | grep -v '^$' > /tmp/resolved.sql
diff /tmp/applied.sql /tmp/resolved.sql && echo "SQL identical - repair is safe"
```

```bash
# WHAT: realign the stored checksums for one context's history, as that context's
#       migrator principal, then start the service so it validates and proceeds.
# WHY : Assumptions: the migrator credential is used and not the runtime one -- the
#       runtime role holds no privilege on flyway_schema_history at all, which is
#       deliberate and is why repair is an operator step rather than something the
#       service can do for itself at startup. Trade-offs: an automatic
#       repair-before-migrate in the service would remove this step and is NOT adopted:
#       it would accept a genuine SQL change as silently as a comment change, and the
#       diff above is the only thing that tells the two apart.
export PGSSLMODE=verify-full
export PGSSLROOTCERT=/opt/carddemo-pg-certs/ca.pem
flyway -url="jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}" \
       -user="carddemo_<context>_migrator" -password="${MIGRATOR_PASSWORD}" \
       -schemas=<context> -defaultSchema=<context> \
       -locations="filesystem:services/<context>-service/src/main/resources/db/migration" \
       repair
```

```bash
# WHAT: confirm the realignment from the history table, not from the absence of an
#       error, then start the service.
# WHY : Assumptions: the stored checksum is what startup compares, so it is the value
#       to read back; a successful repair run says nothing about which rows it touched.
psql -v ON_ERROR_STOP=1 -c "SELECT version, script, checksum, success
  FROM <context>.flyway_schema_history ORDER BY installed_rank;"
```

`V3` is what makes the two whole-schema verification queries below runnable by the
least-privilege read-only role. It publishes the row counts and the money totals as
aggregate-only views owned by the schema owners, and grants `SELECT` on those views
alone to `carddemo_reporting`. Refactoring Rationale: before it existed, those two
queries read eleven base tables directly, so running them required a principal
holding row-level read access to every balance, card number and identity record in
the system — and `money_totals.sql` named the **write-capable** `carddemo_batch` as
the role to use. A verification step must not be able to modify what it verifies,
and its execution must not itself be a disclosure.

```bash
# WHAT: apply the role/schema bootstrap, then -- only after every owning service has
#       run its Flyway migration -- the masked reporting views and the table-specific
#       runtime DELETE grants.
# WHY : Assumptions: ON_ERROR_STOP prevents a partially applied security model
#       from being mistaken for a successful load boundary.
export PGSSLMODE=verify-full
export PGSSLROOTCERT=/opt/carddemo-pg-certs/ca.pem
psql -v ON_ERROR_STOP=1 -f data-migration/sql/V0__schemas_and_roles.sql

# Each owning service now applies its own Flyway migration under its
# carddemo_<context>_migrator credential -- see Step 4 of deploy.md. Both files
# below resolve object names at execution time and fail loudly, not silently, if
# that step has not happened.
psql -v ON_ERROR_STOP=1 -f data-migration/sql/V1__reporting_views.sql
psql -v ON_ERROR_STOP=1 -f data-migration/sql/V2__runtime_delete_grants.sql

# WHY : Assumptions: V3 must run as a principal able to SET ROLE to BOTH
#       carddemo_auth_owner and carddemo_reporting_owner -- it creates one view under
#       each, and each half asserts its own owner before creating anything. A view
#       created under the wrong owner reads its base tables with that owner's
#       privileges, which would silently widen the boundary the views exist to narrow.
psql -v ON_ERROR_STOP=1 -f data-migration/sql/V3__verification_surfaces.sql

# WHY : Assumptions: V0 is idempotent by construction, so the closing pass is part of
#       the documented sequence rather than a workaround -- its to_regclass-guarded
#       conditional grants take their IF branch once the tables exist.
psql -v ON_ERROR_STOP=1 -f data-migration/sql/V0__schemas_and_roles.sql
```

## Verify Security Contracts

```bash
# WHAT: verify alternate loader identities, masked reporting-view privileges, and
#       that DELETE is held on exactly the three tables that have a contracted
#       delete operation and on no other table in any of the eight schemas.
# WHY : Assumptions: successful connection by name is insufficient; the checks
#       prove role attributes, membership, source-table denial, and view masking.
psql -v ON_ERROR_STOP=1 \
  -f data-migration/sql/verify/alternate_database_users.sql
psql -v ON_ERROR_STOP=1 \
  -f data-migration/sql/verify/reporting_view_privileges.sql
# WHY : Trade-offs: this check returns rows ONLY on failure, matching the two
#       sibling checks, so an empty result set is the pass and a non-empty one names
#       both the role and the table it either cannot reach or should not reach. The
#       alternative -- a boolean pass/fail -- was rejected because it would not say
#       WHICH grant drifted, which is the only thing an operator can act on.
psql -v ON_ERROR_STOP=1 \
  -f data-migration/sql/verify/runtime_delete_grants.sql
```

## Load Source Records

Each invocation loads **one** dataset into the one schema that owns it, as a
single committed unit of work. `--dataset` accepts either spelling of the dataset --
the record-layout identifier `list-datasets` reports, or the orchestrator token the
Terraform `seed_datasets` list carries -- and this sequence uses the layout identifier
because it names a local extract from `app/data` alongside it. `--encoding` is required
and is never inferred.

Assumptions: `--source` is named EXPLICITLY at every line below, and that is a property
of this procedure rather than of the command. The flag is optional: omitted, it resolves
to the dataset's newest STAGED generation, which is what the nightly chain relies on and
what a cutover from staged extracts should use. It is named here because this sequence
loads from the reference corpus in `app/data`, which has never been staged and carries no
generation to resolve.

Refactoring Rationale: this section and the verification section beneath it each
appeared **three times**, byte-identically, between here and the cutover gate. The
repetition carried no distinction — not a per-environment pass, not a retry, not a
dry run — so the only thing it could tell an operator was that the same commands
were to be issued three times, which is wrong for a load that is not idempotent:
`load-dataset` commits per dataset, and a second run of `ACCOUNT` against a loaded
schema fails on the primary key rather than reloading. One sequence and one gate is
therefore the corrected procedure, not merely the shorter one.

Refactoring Rationale: this sequence loaded FIVE datasets and now loads TEN. It was
written when the loader declared five targets and was not revised as the loader grew
to eleven, so an operator following it verbatim migrated the reference tables, the
cross-reference and the account master and left the card master, the customer master,
the security users, the daily feed and the category balances empty — while every
verification pass below reported green, because a pass compares a source it was pointed
at against a table and cannot know a dataset was never named. The count is stated in the
comment so the list and its description cannot drift apart again.

```bash
# WHAT: load ten of the eleven records, smallest reference data first. The eleventh,
#       TRAN, is handled separately below because no seed extract ships for it.
# WHY : Assumptions: the reference tables are loaded before the account tables
#       because `reference.transaction_categories` carries a foreign key to
#       `reference.transaction_types` with ON DELETE RESTRICT, and
#       `account.card_xref` is what every later lookup joins through. Loading in
#       this order means a referential failure names the row that is missing
#       rather than the constraint that noticed.
# WHY : Trade-offs: `--encoding ascii` is used for the nine with an ASCII twin because
#       that tree is the authoritative form for them; the EBCDIC twin is loadable by
#       naming the other path and encoding, which is why the flag is required
#       rather than defaulted. A sniffed encoding would read an all-ASCII EBCDIC
#       extract as text and decode plausible wrong values.
# WHY : Assumptions: SECUSER is the one master with no ASCII counterpart, so it is the
#       one line here that names the EBCDIC tree. Its password span is read as bytes and
#       discarded — `auth.users` declares no column for it — so no credential is loaded.
python -m carddemo_migration.cli load-dataset \
  --dataset TRANTYPE --source app/data/ASCII/trantype.txt --encoding ascii
python -m carddemo_migration.cli load-dataset \
  --dataset TRANCAT  --source app/data/ASCII/trancatg.txt --encoding ascii
python -m carddemo_migration.cli load-dataset \
  --dataset DISGROUP --source app/data/ASCII/discgrp.txt  --encoding ascii
python -m carddemo_migration.cli load-dataset \
  --dataset CUSTOMER --source app/data/ASCII/custdata.txt --encoding ascii
python -m carddemo_migration.cli load-dataset \
  --dataset ACCOUNT  --source app/data/ASCII/acctdata.txt --encoding ascii
python -m carddemo_migration.cli load-dataset \
  --dataset XREF     --source app/data/ASCII/cardxref.txt --encoding ascii
python -m carddemo_migration.cli load-dataset \
  --dataset CARD     --source app/data/ASCII/carddata.txt --encoding ascii
python -m carddemo_migration.cli load-dataset \
  --dataset DALYTRAN --source app/data/ASCII/dailytran.txt --encoding ascii
python -m carddemo_migration.cli load-dataset \
  --dataset TCATBAL  --source app/data/ASCII/tcatbal.txt  --encoding ascii
python -m carddemo_migration.cli load-dataset \
  --dataset SECUSER  --source app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS --encoding ebcdic
```

```bash
# WHAT: the same ten loads on a deployment, driven by the staged generations.
# WHY : Assumptions: this is the form the nightly chain issues -- one dataset token and
#       an encoding, with no path anywhere -- so an operator reproducing a chain failure
#       by hand issues exactly what the failing state issued. Each load reads the newest
#       generation the staging step wrote for that dataset and verifies it against the
#       digest recorded on that object, so it is provably reading what was staged.
# WHY : Assumptions: the corpus is EBCDIC here where the sequence above is mostly ASCII,
#       because every staged generation holds an `AWS.M2.CARDDEMO.*.PS` extract; the
#       ASCII twins exist only in the checkout.
for dataset in accounts cards customers card_xref daily_transactions \
               disclosure_groups transaction_category_balances \
               transaction_types transaction_categories users; do
  python -m carddemo_migration.cli load-dataset --dataset "$dataset" --encoding ebcdic
done
```

**The eleventh record, `TRAN`, only when a real extract exists.** No `TRANSACT`
dataset ships in either tree, so there is nothing for `--source` to name on a
corpus-only run and the command below is skipped entirely; `ledger.transactions` is
then filled by the posting job from `ledger.daily_transactions`, and
`sql/verify/row_counts.sql` reports it against a NULL baseline rather than a count. On a
cutover from a production extract, run it with the path the extract was staged to.

```bash
# WHAT: load the transaction master, ONLY on a cutover that supplies a real extract.
# WHY : Assumptions: this load is safe to re-run as well as to skip. `ledger.transactions`
#       has a second writer -- the posting job inserts into it -- so the loader merges on
#       `transaction_id` instead of failing on the primary key, which is what lets a
#       redriven staging step re-enter without duplicating rows.
# WHY : Refactoring Rationale: this note used to end "every other master above is
#       single-writer and a second run there is expected to FAIL on its key rather than
#       silently do nothing", and that is no longer what happens. The seven single-writer
#       masters are now guarded by a row count taken in the same transaction as the COPY: a
#       second run finds the table populated, DECLINES, prints `declined <DATASET> ...`
#       naming the count already there, and exits 0. The change was made because a commit
#       can be AMBIGUOUS -- committed on the server, unacknowledged to the client -- so a
#       retry is the normal case rather than an operator error, and two of the seven
#       behaved badly on it: a primary-key violation reads as a decode fault, and
#       `ledger.daily_transactions`, whose key is generated and whose source carries no
#       natural key, would have accepted the rows and DOUBLED the daily feed. `declined` is
#       therefore a success to read as "already loaded", never as "loaded now".
# WHY : Assumptions: the source is named explicitly here for two independent reasons, and
#       both are measured. Omitting it resolves the newest staged generation of the
#       `transactions` family, whose registered extract is
#       AWS.M2.CARDDEMO.DALYTRAN.PS.INIT -- the single 350-byte record
#       app/jcl/TRANFILE.jcl primes the cluster from, whose unpopulated category code is
#       four NUL bytes and which therefore does not decode as a whole transaction. And
#       `AWS.M2.CARDDEMO.TRANSACT.PS` is NOT one of the thirteen extracts in
#       `app/data/EBCDIC` -- the repository ships the daily feed and the masters, not a
#       populated transaction master -- which is why this step is conditional at all.
# WHY : Assumptions: `EXTRACT_LOCATION` accepts either form `--source` accepts, so one
#       command serves both callers: a directory for an operator over a checkout, or an
#       `s3://` URI for a cutover extract delivered into the dataset bucket, which the
#       command fetches and checks against the object's own recorded length and digest
#       before decoding a record. It defaults to the repository's own EBCDIC directory,
#       which every other command in this section reads by its literal path, so the two
#       cannot disagree about where the corpus is.
EXTRACT_LOCATION="${EXTRACT_LOCATION:-app/data/EBCDIC}"
python -m carddemo_migration.cli load-dataset \
  --dataset TRAN --source "${EXTRACT_LOCATION%/}/AWS.M2.CARDDEMO.TRANSACT.PS" --encoding ebcdic
```

## Reconcile the Transaction-Identifier Allocator

Run this after the **last** load into `ledger.transactions` and **before** writes are
enabled. It is not optional on a cutover, and it is a no-op on a corpus-only
deployment, so it belongs in the sequence unconditionally rather than in a
decision.

```bash
# WHAT: advance ledger.transaction_id_seq past every sequence-format identifier the
#       transaction master now holds, and report both allocator positions.
# WHY : Assumptions: the allocator's STARTING position is derived by its own migration,
#       services/transaction-service/src/main/resources/db/migration/
#       V2__ledger_transaction_id_allocator.sql, from max(transaction_id) over
#       ledger.transactions -- and on a cutover that migration runs BEFORE this runbook
#       loads the extract, against an empty table, so it positions the allocator at 1.
#       The load then writes the real master with its own identifiers. The first
#       interactive transaction add or bill payment after writes are enabled therefore
#       allocates an identifier the table already holds and fails on pk_transactions --
#       and so does the next, for as many allocations as the loaded range is wide.
# WHY : Trade-offs: this runs as the ledger MIGRATION login, not the service login. V0
#       grants each service role USAGE, SELECT on its schema's sequences, which is
#       nextval and currval; setval needs UPDATE, which only the NOLOGIN owner holds.
#       Granting the service role UPDATE was rejected: it is a permanent privilege on a
#       long-lived principal for a one-time step, and it is the dangerous direction --
#       a role that can setval can REWIND the allocator and make the service reissue
#       identifiers it has already stored.
# WHY : Assumptions: the step only ever ADVANCES the allocator, so it is safe to re-run
#       and safe to leave in a script. If writes have already been enabled, allocations
#       have happened, and a rewind would reissue every identifier allocated since; a
#       run against an allocator already past the data prints "nothing to reconcile" and
#       issues no setval at all.
python -m carddemo_migration.cli reconcile-sequences
```

Read the printed line before enabling writes. `advanced from 1 to 683581 past a
largest stored identifier of 683580` means the hazard was present and is now closed;
`already issues 900001 ... nothing to reconcile` means it was not present. A non-zero
exit means the allocator could **not** be reconciled — do not enable writes, because
the first interactive write will fail on the primary key.

## Run All Three Verification Passes

```bash
# WHAT: run every pass for every loaded dataset. All three are mandatory.
# WHY : Assumptions: the three catch different defects and none subsumes another.
#       Row counts catch a load that stopped early or ran twice; the checksum
#       catches a corrupted field where the counts agree; money parity catches a
#       sign overpunch or a misplaced decimal point where both the counts and the
#       field bytes agree. A load reported as verified on fewer than three is not
#       verified.
# WHY : Assumptions: a non-zero exit is the gate. Each pass exits 8 on a
#       difference and prints the comparison line, so `set -e` stops at the first
#       failing dataset with the evidence on standard output.
# WHY : Refactoring Rationale: ⚠️ this step is now ONE invocation of `verify-all`, and it
#       has been wrong twice before. It first covered the same FIVE datasets the load
#       sequence did, so five arrived unverified. It was then corrected into loops that ran
#       all three passes over the three reference records and only two passes over the
#       other seven, because the checksum pass could not digest a `BIGINT`, `DATE`,
#       `SMALLINT`, `TIMESTAMP` or `UUID` column -- it raised rather than reporting a
#       difference. That gap is closed: the pass canonicalises by value class, so all three
#       passes now serve every seeded record, and the scoping that existed only to route
#       around it is withdrawn with it.
# WHY : Assumptions: the pass also pairs the two sides by the target's own key rather than by
#       position, so a sequential extract such as DALYTRAN is no longer reported as wholly
#       different for arriving in a different order from the read-back. That is what makes a
#       single aggregate invocation safe over a population mixing keyed and sequential
#       targets.
# WHY : Trade-offs: the aggregate command is used rather than a shell loop over the three
#       verbs. A loop is what this step was, and its failure mode is that "verified" comes
#       to mean whatever the loop happened to contain -- which is exactly how both earlier
#       forms of this step went wrong. A `set -e` that stops mid-group leaves a subset
#       verified and no record of which subset. `verify-all` runs the three in the fixed
#       order 1, 2, 3 per dataset, stops at the first failure, and has no option that can
#       skip a pass or continue past one, so a zero exit means every covered dataset was
#       verified three ways.
# WHY : Assumptions: the checksum pass over CUSTOMER and CARD needs the SAME key-management
#       grant their loads needed, and nothing more. The source side is projected through the
#       loader's own `prepare_record`, which projects every mapped column including the two
#       sealed ones, so preparing either record without a cipher is refused -- even though the
#       sealed columns are excluded from the digest and no ciphertext is ever compared. The
#       grant is already exported at this point in the runbook because those two datasets were
#       loaded above; if the loads ran in a different session, re-export
#       CARDDEMO_SECURITY_CUSTOMER_IDENTIFIER_KEY_ID and CARDDEMO_SECURITY_CVV_KEY_ID before
#       this command. ⚠️ Those are the two names `config.py` reads; a draft of this note named
#       them CARDDEMO_CUSTOMER_IDENTIFIER_KEY_ID and CARDDEMO_CARD_VERIFICATION_VALUE_KEY_ID,
#       which nothing reads, so exporting those two leaves the refusal in place and reads as a
#       package defect rather than a missing grant.
set -e
python -m carddemo_migration.cli verify-all --source-root app/data/EBCDIC
```

`verify-all` takes its coverage from one of two sources, and which one was used is
what an operator has to be able to state afterwards.

- **The registry form**, above. Given no `--manifest`, the gate covers the whole
  seed-dataset registry minus the layouts that ship no committed extract — ten of the
  eleven records, every one except `TRAN`. It takes no `--dataset`, so it **cannot** be
  narrowed. This is the **cutover** gate rather than a nightly one, and the distinction
  matters when reproducing a failure. The nightly chain no longer runs `verify-all`: it
  verifies **per dataset**, inside each `StageSeedDatasets` branch, where
  `refresh-dataset` runs the same three passes over the one dataset it just loaded. So an
  operator reproducing a *chain* failure narrows to the dataset the failing branch names,
  whereas this whole-corpus form is what proves a cutover before the first nightly run.
  Assumptions: a whole-corpus gate belongs here and not mid-chain because its two
  committed queries compare **across** datasets, and the chain refreshes datasets
  independently and concurrently — a cross-dataset assertion inside the `Map` would
  depend on branch ordering. `--source-root` is named here and omitted in the deployment, where the task
  definition already carries `CARDDEMO_DATASET_STAGING_ROOT`; pointing it at the
  checked-out corpus is what lets a source-tree operator run the gate at all, and the
  extracts it reads are the EBCDIC images, because those are the ones whose names the
  registry knows.
- **The manifest form**, below. A delivery whose bytes are not laid out under one root,
  or which mixes the two corpora, is declared explicitly instead. Coverage is then
  exactly what the manifest declares — which is why gate condition 2 asks an operator who
  used this form to confirm the manifest carried every dataset the cutover loaded.

```bash
# WHAT: the same gate, over an explicitly declared population.
# WHY : Assumptions: the manifest is written HERE rather than shipped in the distribution,
#       because it declares where a delivery's bytes are and that is an operator fact --
#       the package holds no dataset-to-path mapping, deliberately. A relative source
#       resolves against the manifest's own directory, so this one is written beside the
#       repository root it names paths from.
# WHY : Assumptions: SECUSER is declared from the EBCDIC tree because that is its only
#       form -- `app/data/ASCII/usrsec.txt` does not exist -- and the manifest carries the
#       seed form per entry precisely so one invocation can span both trees, which the
#       loops this replaces could not.
# WHY : Assumptions: DISGROUP and ACCOUNT are ALSO declared from the EBCDIC tree, and those
#       two specifically. `data-migration/README.md` records that the nine datasets shipping
#       in both encodings agree field for field at every value except exactly two -- DISCGRP
#       record 34's `DIS-INT-RATE` (15.00 against 0.00, the `DEFAULT` fallback rate that
#       decides interest) and ACCTDATA record 49's `ACCT-ADDR-ZIP` -- and that this package
#       treats EBCDIC as authoritative wherever both forms exist. The load above reads
#       `app/data/EBCDIC`, so declaring those two from the ASCII twins made this example
#       report a checksum DIFFER on a corpus divergence, which reads as a failed load rather
#       than as the difference between two conversions of one extract. The other eight stay
#       ASCII, so the example still demonstrates one manifest spanning both trees.
# WHY : Trade-offs: the checksum pass over SECUSER digests the loaded columns only, and the
#       credential is not among them -- the target schema has no column for it, by design. So
#       this verifies the identity rows and says nothing about a secret, which is the intended
#       reach rather than a shortfall.
set -e

cat > carddemo-verification-manifest.json <<'MANIFEST'
{
  "datasets": [
    {"dataset": "TRANTYPE", "source": "app/data/ASCII/trantype.txt",  "encoding": "ascii"},
    {"dataset": "TRANCAT",  "source": "app/data/ASCII/trancatg.txt",  "encoding": "ascii"},
    {"dataset": "DISGROUP", "source": "app/data/EBCDIC/AWS.M2.CARDDEMO.DISCGRP.PS",
     "encoding": "ebcdic"},
    {"dataset": "CUSTOMER", "source": "app/data/ASCII/custdata.txt",  "encoding": "ascii"},
    {"dataset": "ACCOUNT",  "source": "app/data/EBCDIC/AWS.M2.CARDDEMO.ACCTDATA.PS",
     "encoding": "ebcdic"},
    {"dataset": "XREF",     "source": "app/data/ASCII/cardxref.txt",  "encoding": "ascii"},
    {"dataset": "CARD",     "source": "app/data/ASCII/carddata.txt",  "encoding": "ascii"},
    {"dataset": "DALYTRAN", "source": "app/data/ASCII/dailytran.txt", "encoding": "ascii"},
    {"dataset": "TCATBAL",  "source": "app/data/ASCII/tcatbal.txt",   "encoding": "ascii"},
    {"dataset": "SECUSER",  "source": "app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS",
     "encoding": "ebcdic"}
  ]
}
MANIFEST

python -m carddemo_migration.cli verify-all \
  --manifest carddemo-verification-manifest.json
```

Read the three numbered pass headings and the verdict line beneath them. The gate exits
0 only when all three ran and all three verified; a pass that never ran because an
earlier one failed is printed as not run rather than omitted, so the report distinguishes
"passed" from "not reached". Pass 2 additionally prints one `sealed columns` line per
sealed column -- how many envelopes were expected, how many are stored, how many are
malformed -- which is the only check that looks at the three ciphertext columns no digest
can compare.

Two properties of the checksum pass are worth reading before its output is
interpreted, because both are deliberate and neither is a gap:

- **A column the loader enciphers is excluded from the digest, not compared.** Three
  are: `account.customers.ssn_encrypted`, `account.customers.govt_issued_id_encrypted`
  and `card.cards.cvv_encrypted`. An envelope draws a fresh initialisation vector per
  value, so the same identifier enciphered twice differs byte for byte — comparing one
  would report a difference on every run of a correct load. Each digest line reports the
  number of fields it covered as `fields=N`, so an exclusion is visible as a field count
  below the record's field total rather than as a silent omission; the `sealed columns`
  line is what certifies those three, and gate condition 1 below is what covers whether
  they can be deciphered at all.
- **Both sides are rendered through the column's declared type, so a difference in
  REPRESENTATION is not reported as a difference in DATA.** `00000000011` in the extract
  and `11` in a `BIGINT` are the same account identifier and compare equal;
  `2022-07-18` in the extract and a `DATE` compare equal; the extract's zoned
  `0000000000{`, which the reader decodes to an exact zero before anything is digested,
  and a `NUMERIC(12,2)` zero compare equal; and a 26-blank processing stamp compares
  equal to the null the load stores for it. What still reports a difference is a value
  that differs — a wrong digit, a dropped sign, a truncated name.

Each pass remains separately invocable for diagnosis, and that is the only thing to reach
for them individually for:

```bash
# WHAT: the three passes for one dataset, for diagnosing a gate failure.
# WHY : Assumptions: these narrow the question to one dataset and claim nothing more,
#       which is why they exist alongside the gate rather than instead of it. `--source`
#       is omitted, so each reads the dataset's newest staged generation -- the same bytes
#       the load read.
python -m carddemo_migration.cli verify-row-counts   --dataset accounts --encoding ebcdic
python -m carddemo_migration.cli verify-checksum     --dataset accounts --encoding ebcdic
python -m carddemo_migration.cli verify-money-parity --dataset accounts --encoding ebcdic
```

Finally, run the two whole-schema SQL reports. They read every declared table
including `ledger.transactions`, which no per-dataset pass and no gate covers because no
extract seeds it:

```bash
# WHAT: the schema-wide row-count and money-total reports.
# WHY : Assumptions: these are run LAST and separately from the per-dataset passes above
#       because they are the only pass that reports a table no dataset was named for.
#       `ledger.transactions` has no `--source` to point a per-dataset pass at, so a
#       corpus-only run reports it here against a NULL baseline and nowhere else.
psql "$CARDDEMO_ADMIN_URL" -v ON_ERROR_STOP=1 \
  -f data-migration/sql/verify/row_counts.sql
psql "$CARDDEMO_ADMIN_URL" -v ON_ERROR_STOP=1 \
  -f data-migration/sql/verify/money_totals.sql
```

```bash
# WHAT: the two whole-schema queries, run once after every dataset is loaded, AS
#       carddemo_reporting.
# WHY : Assumptions: these cover tables no single dataset load touches -- the
#       ledger tables the batch jobs populate, and `auth.users` -- so they are the
#       only check that the database as a whole is in the state a cutover assumes.
# WHY : Assumptions: the role is carddemo_reporting and not the operator principal.
#       Both files read only the aggregate views V3 creates, so this role is
#       sufficient -- and it is the right choice rather than merely a possible one,
#       because it can read nine aggregates and eleven counts and cannot read one
#       base-table row or write anything anywhere. A pass that cannot alter its own
#       subject is the property being bought.
PGUSER=carddemo_reporting psql -v ON_ERROR_STOP=1 \
  -f data-migration/sql/verify/row_counts.sql
PGUSER=carddemo_reporting psql -v ON_ERROR_STOP=1 \
  -f data-migration/sql/verify/money_totals.sql
```

BOTH halves of that pair are ALSO reachable as subcommands, and those are the forms a
batch step should use:

```bash
# WHAT: the whole-migration row-count report, judged rather than printed.
# WHY : Assumptions: this is the same file the psql invocation above runs, executed
#       verbatim -- the package reads the text and refuses one that is not a single
#       pure-SQL statement rather than rewriting it, so the report an operator reads
#       with psql and the report this judges are the same bytes.
# WHY : Assumptions: prefer this form in an orchestrated step and the psql form at a
#       terminal. This one opens its own session for carddemo_reporting, CHECKS with
#       the server that the session really is that role before it runs anything, and
#       reduces the report to a process exit status -- 0 when every line verified, 8
#       when any did not. psql exits 0 for a report full of mismatches, so a state
#       machine branching on it would treat a failed verification as a success.
# WHY : Assumptions: `--sql-root .` is required in the container image and must be
#       omitted in a source checkout. The `sql` tree ships BESIDE the installed
#       package rather than inside it, so the image copies it to the working
#       directory /opt/carddemo, while a checkout resolves it from the package's own
#       location. Passing the wrong one fails closed, naming the path it looked at.
python -m carddemo_migration.cli verify-row-count-report          # source checkout
python -m carddemo_migration.cli verify-row-count-report --sql-root .   # in the image
```

```bash
# WHAT: the whole-migration MONEY-TOTAL report, judged rather than printed.
# WHY : Refactoring Rationale: this command exists because the pass behind it did and its
#       entry point did not. `verify/money_parity.py` shipped `read_source_totals` and
#       `verify_money_totals` -- the whole-migration half of the money pass, the one that
#       reads `money_totals.sql` as carddemo_reporting and judges every table's exact
#       total against the source extracts -- with nothing anywhere invoking either, so the
#       only reachable money check was the per-dataset `verify-money-parity` above and the
#       schema-wide half could be run only by hand with psql, which exits 0 on a report
#       full of mismatches. An orchestrated step branching on that exit status would have
#       treated a money mismatch as a success.
# WHY : Assumptions: one `--extract` per money-bearing record, each `LAYOUT=PATH` and
#       optionally `LAYOUT=PATH=ENCODING` when a record's form differs from `--encoding`.
#       Four records carry money columns that a shipped extract can total -- ACCOUNT's
#       five, DALYTRAN's one, TCATBAL's one and DISGROUP's one. TRAN carries the same
#       amount column and ships NO extract, so its total is reported against no source and
#       reads as unsourced rather than as a mismatch, which is the same situation the
#       row-count report describes for `ledger.transactions`.
# WHY : Assumptions: the session's role is CHECKED with the server before the query runs,
#       exactly as the row-count report checks it, so a pass that could write cannot
#       certify the totals. The exit status is the gate -- 0 when every line verified, 8
#       when any did not.
# WHY : Assumptions: this is the ONLY pass that compares the count of strictly-negative rows
#       as well as the totals, and that comparison is a reason to run it even after
#       `verify-money-parity` has passed every dataset. Exchange the signs of two records and
#       every total is unchanged, so a total-only comparison reports agreement; the signature
#       that remains is the negative-row count, which no other pass reads. Each discrepancy of
#       that kind is logged on its own line, because a count that differs while the total
#       agrees sends an operator to the overpunch convention rather than to one record.
# WHY : Assumptions: a short invocation fails CLOSED and names the column it is missing, so
#       forgetting an `--extract` cannot certify part of the load as though it were all of it.
# WHY : ⚠️ Assumptions: use the form the database was loaded FROM, because the two seed forms of
#       the disclosure-group extract do not agree. Measured with the package's own
#       `read_source_totals` over the shipped files: `app/data/ASCII/discgrp.txt` totals 375.00
#       and `app/data/EBCDIC/AWS.M2.CARDDEMO.DISCGRP.PS` totals 390.00 over the same 51 records.
#       Totalling one against a database loaded from the other reports a real 15.00 difference
#       that is an artefact of the source form and not a load defect.
python -m carddemo_migration.cli verify-money-total-report \
  --encoding ascii \
  --extract ACCOUNT=app/data/ASCII/acctdata.txt \
  --extract DALYTRAN=app/data/ASCII/dailytran.txt \
  --extract TCATBAL=app/data/ASCII/tcatbal.txt \
  --extract DISGROUP=app/data/ASCII/discgrp.txt          # source checkout

python -m carddemo_migration.cli verify-money-total-report \
  --encoding ascii \
  --extract ACCOUNT=app/data/ASCII/acctdata.txt \
  --extract DALYTRAN=app/data/ASCII/dailytran.txt \
  --extract TCATBAL=app/data/ASCII/tcatbal.txt \
  --extract DISGROUP=app/data/ASCII/discgrp.txt \
  --sql-root .                                           # in the image
```

## Cutover Gate

Do not switch application traffic based only on schema success. A production
cutover requires every command above to have succeeded, plus an approved rollback
snapshot, plus a deliberate decision on the three items below.

Refactoring Rationale: this gate previously stood on `CUSTOMER` and `CARD` being
unloadable — `account.customers` declares `ssn_encrypted` and
`govt_issued_id_encrypted`, `card.cards` declares `cvv_encrypted`, and this package
held no way to produce the ciphertext they require, so the gate stayed closed by
construction. It produces that ciphertext now, under the same envelope framing the
owning service reads and the same key the owning service resolves, so that
exception is withdrawn and the gate rests on the three real conditions instead.

**1. The protected columns are enciphered, so confirm the keys were the right ones.**
Three columns are written as ciphertext and never in the clear. Each is sealed under
the key that the service owning the column reads at run time, resolved from the same
parameter the service resolves it from, so a load performed against a different
environment's key produces rows that store and verify cleanly and then fail to
decrypt in the application days later. Confirm before cutover that the environment
whose parameters the load resolved is the environment the application will run in.

Assumptions: this is stated as a gate condition because no verification pass can
catch it. The row counts agree, the money totals agree, and the ciphertext is
well-formed either way — the key identifier is not recoverable from the envelope by
anything in this package, and the authentication failure is deferred to first read.

⚠️ Refactoring Rationale — **a load of `account.customers` performed before the
customer envelope was aligned must be discarded, not topped up.** Until that
alignment, this package framed the two customer identifier columns with no marker
and no version byte, reproducing a second Java writer that `account-service` has
since deleted; the writer that remains frames a four-byte `CDCI` marker and a
version byte first. Rows written under the earlier framing are five bytes short of
what the service parses and will fail before decryption is attempted. **A re-run of
`load-dataset CUSTOMER` does not repair them:** no role this package uses holds
`DELETE` or `TRUNCATE` — [`sql/V0__schemas_and_roles.sql`](../../data-migration/sql/V0__schemas_and_roles.sql)
withholds both from every service role — so a populated table is refused rather
than replaced. Repairing is an operator action on the cluster, taken with the
account owner role: drop and recreate the schema from its Flyway baseline, or
delete the affected rows, then re-run the load. Confirm at the gate either that
`account.customers` has never been loaded by this package, or that it has been
emptied since.

Assumptions: this is a gate condition rather than a code change for the same reason
as the key check above — nothing in the loader can tell a pre-alignment envelope
from a post-alignment one without deciphering it, which this package deliberately
cannot do. Teaching the account service to accept both framings was rejected: it
would keep two formats alive in one column permanently, which is precisely the state
the alignment ended.

**2. The gate covers ten of the eleven records, three columns are covered by condition 1
rather than by a digest, and when a manifest is supplied the manifest is what fixes the
population -- so confirm it declared every record the cutover loaded.**
Row counts and per-record checksums cover all ten datasets the gate reads. Money parity
covers the four of those ten that carry a money column; the declared money inventory is
nine columns over five tables, and the fifth table is `ledger.transactions`, which no
extract seeds and which the whole-schema money-total report covers instead. The eleventh
record, `TRAN`, is outside the gate for that same reason, and condition 3 below is how it
is accounted for.

⚠️ Refactoring Rationale: this condition read "the checksum pass covers three of the
eleven records", and asked for an explicit decision about whether row counts and money
parity were acceptable evidence for the other eight. The measured reason it gave has been
removed rather than accepted: the pass could not digest the `BIGINT`, `DATE`, `SMALLINT`,
`TIMESTAMP` and `UUID` values a driver returns, and it now canonicalises by value class,
so no seeded record is outside its reach and an identifier read back as a number is
rendered into the digit width the reader publishes. It is corrected rather than deleted,
because an operator who had read the earlier version would otherwise still be scoping the
checksum pass by hand.

What remains at the gate is what can still make coverage partial, and one half of it is an
operator fact rather than a package limit. The registry form cannot be narrowed — it takes
no `--dataset`, and its population is the registry minus the layouts that ship no
committed extract — but the manifest form verifies exactly the datasets its manifest
declares. So when the run was manifested, confirm the manifest carried every dataset the
cutover loaded.

Two further boundaries of that coverage are structural, and are stated rather than left to
inference:

- `ledger.transactions` ships no extract, so no per-dataset pass and no gate can be
  pointed at it. It is covered by `verify-row-count-report` and
  `verify-money-total-report`, and condition 3 below is what an operator reads its result
  against.
- The three enciphered columns are excluded from the digest by design, for the
  initialisation-vector reason given above. Pass 2 audits them instead — one
  `sealed columns` line per column, reporting how many envelopes were expected, how many
  are stored and how many are malformed — and condition 1 is what covers whether they can
  be deciphered at all. No digest can.

Assumptions: this stays a gate condition rather than being dropped, because the failure it
guards against survived the fix in a new form. A manifest silently short of a dataset
produces a green run over the datasets it does declare, which reads as "the migration was
verified" -- the same misreading the earlier scoping produced, reached a different way. A
gate that overstates its own coverage is worse than one that names what bounds it, and
naming what the three passes do and do not reach is what makes the alternative -- letting a
reader infer from "all passes green" exactly how much was compared -- unnecessary.

Assumptions: coverage and execution are different questions, and a zero exit answers only the
second. Confirm the run actually emitted its verdicts rather than reading the exit code alone:
**ten** row-count verdicts, **ten** per-record checksum verdicts, **four** money-parity verdicts
-- `accounts`, `daily_transactions`, `disclosure_groups` and `transaction_category_balances` are
the four covered datasets carrying a money column -- and one `sealed columns` line per enciphered
column. The whole-schema money-total report is separate and publishes **nine** columns over
**five** tables, the fifth being the `ledger.transactions` that no per-dataset pass reaches. A
run that fails those two datasets for a missing cipher grant fails them rather than skipping
them, so a short verdict count is a real gap and not a quieter form of success.

**3. `ledger.transactions` loading zero rows is a NORMAL result, not a skipped step.**
The transaction master is the eleventh loadable record and the only one for which no
seed extract ships, so a corpus-only run loads it successfully with zero rows and
`sql/verify/row_counts.sql` reports it against a NULL baseline rather than a count.
Confirm which of the two situations applies before reading the result: on a corpus-only
run zero is correct and the table is filled later by the posting job, whereas on a
cutover from a real extract zero means the extract was not supplied and the largest
table in the system has not moved.

Assumptions: this is a gate condition because the two cases are indistinguishable from
the pass output alone — both report a committed load and a NULL-baseline row. Naming it
here is what stops "row counts green" being read as "every master arrived".
