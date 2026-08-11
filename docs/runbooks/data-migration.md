# Data Migration Runbook

> **Purpose.** Validate the CardDemo migration configuration, create database
> schemas and protected reporting views, and stage immutable source extracts.
>
> **Source of truth.** Copybook layouts under `app/cpy/**`,
> `data-migration/sql/**`, `data-migration/src/carddemo_migration/**`, and
> `docs/architecture/data-model-and-schema-mapping.md`.

The current Python package implements configuration/trust validation, the
normative layout catalogue, the twelve fixed-width record readers, the Aurora
bulk loader and all three verification passes, and it exposes them as the
`load-dataset`, `verify-row-counts`, `verify-checksum` and `verify-money-parity`
subcommands used below. Every step in this runbook is executable and fails
closed. Ten records are loadable, covering all eight schemas' seeded tables, and
the two conditions a cutover still turns on are stated at the gate at the end.

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
# WHAT: copy ASCII and EBCDIC source extracts to an environment-specific staging prefix.
# WHY : Assumptions: EBCDIC sign and packed bytes must remain opaque until a
#       field-aware decoder consumes them; text-mode conversion would corrupt them.
ENVIRONMENT=dev
DATASET_BUCKET="$(terraform -chdir="infra/envs/${ENVIRONMENT}" output -raw dataset_bucket_name)"
aws s3 sync app/data/ "s3://${DATASET_BUCKET}/migration/source/" \
  --no-follow-symlinks
```

### Make the extracts readable by the orchestrated staging task

The command above puts the extracts in object storage. The nightly
`StageSeedDatasets` branches read them from a **filesystem path** instead, because
the staging command holds one file descriptor across its digest and its upload and
so has a single read path rather than one per source kind. That path is supplied by
`CARDDEMO_DATASET_STAGING_ROOT`, which the state machine sets from the
`dataset_staging_root` module input; it defaults to `/mnt/carddemo-extracts`.

Assumptions: populating that directory is an **operator action** and this is the
step that owns it. The container image ships no extract on purpose — its Dockerfile
copies only `src/` and `sql/`, so no baseline data is baked into a published layer
and refreshing an extract is not an image rebuild. Mount the extracts there for the
data-migration task definition, or make the same files available at that path by
whatever mechanism the deployment already uses for shared task storage.

```bash
# WHAT: confirm the ten registered seed extracts are present, named as the registry
#       expects, and each a whole number of records.
# WHY : Assumptions: the registry is the authority for BOTH the file name and the
#       record length, so this check derives every expectation from it rather than
#       restating a table that could drift. A missing file or a non-zero remainder
#       here is the same failure the staging branch would report, found before the
#       nightly window rather than during it.
STAGING_ROOT=/mnt/carddemo-extracts
python - "$STAGING_ROOT" <<'PY'
import sys
from pathlib import Path
from carddemo_migration import seed_datasets

root = Path(sys.argv[1])
problems = 0
for token in seed_datasets.seed_dataset_tokens():
    descriptor = seed_datasets.seed_dataset(token)
    path = root / descriptor.source_object
    length = seed_datasets.record_length(descriptor)
    if not path.is_file():
        print(f"MISSING  {token}: {path}")
        problems += 1
        continue
    remainder = path.stat().st_size % length
    status = "ok" if remainder == 0 else f"PARTIAL RECORD ({remainder} trailing bytes)"
    if remainder:
        problems += 1
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
single committed unit of work. `--dataset` is the record-layout identifier
`list-datasets` reports; `--encoding` is required and is never inferred.

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

**The eleventh record, `TRAN`, only when a real extract exists.** No `TRANSACT`
dataset ships in either tree, so there is nothing for `--source` to name on a
corpus-only run and the command below is skipped entirely; `ledger.transactions` is
then filled by the posting job from `ledger.daily_transactions`, and
`sql/verify/row_counts.sql` reports it against a NULL baseline rather than a count. On a
cutover from a production extract, run it with the path the extract was staged to.

```bash
# WHAT: load the transaction master, ONLY on a cutover that supplies a real extract.
# WHY : Assumptions: this is the one load that is safe to re-run as well as to skip.
#       `ledger.transactions` has a second writer -- the posting job inserts into it --
#       so the loader merges on `transaction_id` instead of failing on the primary key,
#       which is what lets a redriven staging step re-enter without duplicating rows.
#       Every other master above is single-writer and a second run there is expected to
#       fail on its key rather than silently do nothing.
python -m carddemo_migration.cli load-dataset \
  --dataset TRAN --source "$STAGING_ROOT/AWS.M2.CARDDEMO.TRANSACT.PS" --encoding ebcdic
```

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
# WHY : Refactoring Rationale: this loop covered the same FIVE datasets the load
#       sequence did, and ran all three passes over each. Both halves were wrong. It
#       verified five of the ten loaded datasets, so five arrived unverified; and it ran
#       the checksum pass over XREF and ACCOUNT, which that pass cannot digest -- it
#       fails on a `BIGINT`, `DATE` or `SMALLINT` column rather than reporting a
#       difference, as the section above measures. A mandatory pass that aborts on a
#       dataset it was never able to serve stops the run before the datasets after it are
#       checked at all, which is why the checksum is now scoped to the three records it
#       serves and the other two passes run over all ten.
set -e

# All three passes for the three reference records, which the checksum pass can digest.
for pair in \
  "TRANTYPE app/data/ASCII/trantype.txt" \
  "TRANCAT  app/data/ASCII/trancatg.txt" \
  "DISGROUP app/data/ASCII/discgrp.txt" ; do
  set -- $pair
  python -m carddemo_migration.cli verify-row-counts   --dataset "$1" --source "$2" --encoding ascii
  python -m carddemo_migration.cli verify-checksum     --dataset "$1" --source "$2" --encoding ascii
  python -m carddemo_migration.cli verify-money-parity --dataset "$1" --source "$2" --encoding ascii
done

# Row counts and money parity for the remaining seven, which those two passes do serve.
for pair in \
  "CUSTOMER app/data/ASCII/custdata.txt" \
  "ACCOUNT  app/data/ASCII/acctdata.txt" \
  "XREF     app/data/ASCII/cardxref.txt" \
  "CARD     app/data/ASCII/carddata.txt" \
  "DALYTRAN app/data/ASCII/dailytran.txt" \
  "TCATBAL  app/data/ASCII/tcatbal.txt" ; do
  set -- $pair
  python -m carddemo_migration.cli verify-row-counts   --dataset "$1" --source "$2" --encoding ascii
  python -m carddemo_migration.cli verify-money-parity --dataset "$1" --source "$2" --encoding ascii
done

# WHY : Assumptions: SECUSER is verified from the EBCDIC tree because that is its only
#       form, so it cannot join either ASCII loop above.
python -m carddemo_migration.cli verify-row-counts \
  --dataset SECUSER --source app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS --encoding ebcdic
python -m carddemo_migration.cli verify-money-parity \
  --dataset SECUSER --source app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS --encoding ebcdic
```

Finally, run the two whole-schema SQL reports, which read every table including
`ledger.transactions` and so are the only check that covers the eleventh record:

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

## Cutover Gate

Do not switch application traffic based only on schema success. A production
cutover requires every command above to have succeeded, plus an approved rollback
snapshot, plus a deliberate decision on the two items below.

Refactoring Rationale: this gate previously stood on `CUSTOMER` and `CARD` being
unloadable — `account.customers` declares `ssn_encrypted` and
`govt_issued_id_encrypted`, `card.cards` declares `cvv_encrypted`, and this package
held no way to produce the ciphertext they require, so the gate stayed closed by
construction. It produces that ciphertext now, under the same envelope framing the
owning service reads and the same key the owning service resolves, so that
exception is withdrawn and the gate rests on the two real conditions instead.

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

**2. The checksum pass covers three of the eleven records.**
Row counts and money parity cover all eleven; the checksum pass covers the three
reference records, for the measured reason given in the section above. Decide
explicitly whether that is acceptable evidence for the eight master records, or
whether the read-back should first be extended to render non-character columns back
into the reader's published shape.

Assumptions: the alternative to stating this is to let a reader infer from "all
passes green" that all three passes ran for all eleven records, which they did not. A
gate that overstates its own coverage is worse than one that names the gap.

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
