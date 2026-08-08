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
closed. Two records are the exception and are called out at the cutover gate:
`CUSTOMER` and `CARD` cannot be loaded from this package at all.

Refactoring Rationale: this paragraph stated that the reader and bulk-loader CLI
was absent, which was true until those modules landed. It is rewritten rather
than deleted because an operator who had read the old text would otherwise
conclude the load steps below were aspirational, and skip them.

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

## Create Schemas, Reporting Views and Runtime Delete Grants

Set `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, and `PGPASSWORD` through an
approved secret-delivery channel. Point `PGSSLROOTCERT` at the pinned CA bundle.

Three SQL artifacts ship here and their order is **not interchangeable**.
`V0__schemas_and_roles.sql` runs before any table exists, so it can only express
privileges schema-wide or as default privileges; `V2__runtime_delete_grants.sql`
names individual tables and therefore cannot run until the owning services'
Flyway migrations have created them. The full sequence is the one recorded in
[data-model-and-schema-mapping.md](../architecture/data-model-and-schema-mapping.md):
`V0` -> each owning service's Flyway migration -> `V1__reporting_views.sql` ->
`V2__runtime_delete_grants.sql` -> `V0` once more.

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

```bash
# WHAT: load the five records this package can load, smallest reference data first.
# WHY : Assumptions: the reference tables are loaded before the account tables
#       because `reference.transaction_categories` carries a foreign key to
#       `reference.transaction_types` with ON DELETE RESTRICT, and
#       `account.card_xref` is what every later lookup joins through. Loading in
#       this order means a referential failure names the row that is missing
#       rather than the constraint that noticed.
# WHY : Trade-offs: `--encoding ascii` is used for these five because the ASCII
#       tree is the authoritative form for them; the EBCDIC twin is loadable by
#       naming the other path and encoding, which is why the flag is required
#       rather than defaulted. A sniffed encoding would read an all-ASCII EBCDIC
#       extract as text and decode plausible wrong values.
python -m carddemo_migration.cli load-dataset \
  --dataset TRANTYPE --source app/data/ASCII/trantype.txt --encoding ascii
python -m carddemo_migration.cli load-dataset \
  --dataset TRANCAT  --source app/data/ASCII/trancatg.txt --encoding ascii
python -m carddemo_migration.cli load-dataset \
  --dataset DISGROUP --source app/data/ASCII/discgrp.txt  --encoding ascii
python -m carddemo_migration.cli load-dataset \
  --dataset XREF     --source app/data/ASCII/cardxref.txt --encoding ascii
python -m carddemo_migration.cli load-dataset \
  --dataset ACCOUNT  --source app/data/ASCII/acctdata.txt --encoding ascii
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
set -e
for pair in \
  "TRANTYPE app/data/ASCII/trantype.txt" \
  "TRANCAT  app/data/ASCII/trancatg.txt" \
  "DISGROUP app/data/ASCII/discgrp.txt" \
  "XREF     app/data/ASCII/cardxref.txt" \
  "ACCOUNT  app/data/ASCII/acctdata.txt" ; do
  set -- $pair
  python -m carddemo_migration.cli verify-row-counts   --dataset "$1" --source "$2" --encoding ascii
  python -m carddemo_migration.cli verify-checksum     --dataset "$1" --source "$2" --encoding ascii
  python -m carddemo_migration.cli verify-money-parity --dataset "$1" --source "$2" --encoding ascii
done
```

```bash
# WHAT: the two whole-schema queries, run once after every dataset is loaded.
# WHY : Assumptions: these cover tables no single dataset load touches -- the
#       ledger tables the batch jobs populate, and `auth.users` -- so they are the
#       only check that the database as a whole is in the state a cutover assumes.
psql -v ON_ERROR_STOP=1 -f data-migration/sql/verify/row_counts.sql
psql -v ON_ERROR_STOP=1 -f data-migration/sql/verify/money_totals.sql
```

## Load Source Records

Each invocation loads **one** dataset into the one schema that owns it, as a
single committed unit of work. `--dataset` is the record-layout identifier
`list-datasets` reports; `--encoding` is required and is never inferred.

```bash
# WHAT: load the five records this package can load, smallest reference data first.
# WHY : Assumptions: the reference tables are loaded before the account tables
#       because `reference.transaction_categories` carries a foreign key to
#       `reference.transaction_types` with ON DELETE RESTRICT, and
#       `account.card_xref` is what every later lookup joins through. Loading in
#       this order means a referential failure names the row that is missing
#       rather than the constraint that noticed.
# WHY : Trade-offs: `--encoding ascii` is used for these five because the ASCII
#       tree is the authoritative form for them; the EBCDIC twin is loadable by
#       naming the other path and encoding, which is why the flag is required
#       rather than defaulted. A sniffed encoding would read an all-ASCII EBCDIC
#       extract as text and decode plausible wrong values.
python -m carddemo_migration.cli load-dataset \
  --dataset TRANTYPE --source app/data/ASCII/trantype.txt --encoding ascii
python -m carddemo_migration.cli load-dataset \
  --dataset TRANCAT  --source app/data/ASCII/trancatg.txt --encoding ascii
python -m carddemo_migration.cli load-dataset \
  --dataset DISGROUP --source app/data/ASCII/discgrp.txt  --encoding ascii
python -m carddemo_migration.cli load-dataset \
  --dataset XREF     --source app/data/ASCII/cardxref.txt --encoding ascii
python -m carddemo_migration.cli load-dataset \
  --dataset ACCOUNT  --source app/data/ASCII/acctdata.txt --encoding ascii
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
set -e
for pair in \
  "TRANTYPE app/data/ASCII/trantype.txt" \
  "TRANCAT  app/data/ASCII/trancatg.txt" \
  "DISGROUP app/data/ASCII/discgrp.txt" \
  "XREF     app/data/ASCII/cardxref.txt" \
  "ACCOUNT  app/data/ASCII/acctdata.txt" ; do
  set -- $pair
  python -m carddemo_migration.cli verify-row-counts   --dataset "$1" --source "$2" --encoding ascii
  python -m carddemo_migration.cli verify-checksum     --dataset "$1" --source "$2" --encoding ascii
  python -m carddemo_migration.cli verify-money-parity --dataset "$1" --source "$2" --encoding ascii
done
```

```bash
# WHAT: the two whole-schema queries, run once after every dataset is loaded.
# WHY : Assumptions: these cover tables no single dataset load touches -- the
#       ledger tables the batch jobs populate, and `auth.users` -- so they are the
#       only check that the database as a whole is in the state a cutover assumes.
psql -v ON_ERROR_STOP=1 -f data-migration/sql/verify/row_counts.sql
psql -v ON_ERROR_STOP=1 -f data-migration/sql/verify/money_totals.sql
```

## Cutover Gate

Do not switch application traffic based only on schema success. A production
cutover requires every command above to have succeeded, plus an approved rollback
snapshot, plus a resolution for the two records this package cannot load.

**`CUSTOMER` and `CARD` are refused by `load-dataset`, by name.**
`account.customers` declares `ssn_encrypted` and `govt_issued_id_encrypted`, and
`card.cards` declares `cvv_encrypted`, as `BYTEA NOT NULL` holding ciphertext
produced by the owning service's cipher under a key this package does not hold.
Both records have working readers and both decode correctly; what is absent is
any way for this package to produce the ciphertext those columns require. Loading
them is therefore work for `account-service` and `card-service`, and the cutover
gate stays **closed** until that path exists.

Assumptions: the refusal is preferred to the alternative, which would succeed.
Writing the decoded plaintext into the `*_encrypted` columns would load cleanly
and both the row-count and checksum passes would agree, while every national
identifier and card verification value in the database sat in cleartext in a
column whose name asserted otherwise. A refusal naming the reason is the only
outcome that cannot be mistaken for a completed load.
