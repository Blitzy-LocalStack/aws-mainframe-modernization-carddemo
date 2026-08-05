# Data Migration Runbook

> **Purpose.** Validate the CardDemo migration configuration, create database
> schemas and protected reporting views, and stage immutable source extracts.
>
> **Source of truth.** Copybook layouts under `app/cpy/**`,
> `data-migration/sql/**`, `data-migration/src/carddemo_migration/**`, and
> `docs/architecture/data-model-and-schema-mapping.md`.

The current Python package implements configuration/trust validation and the
normative layout catalogue. It does **not** expose the fixed-width reader and
bulk-loader CLI described by the target architecture. Consequently this
runbook does not claim that a complete flat-file-to-Aurora cutover is executable
from this checkout. Schema creation, view enforcement, source staging, and
verification below are executable and fail closed.

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

## Create Schemas and Reporting Views

Set `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, and `PGPASSWORD` through an
approved secret-delivery channel. Point `PGSSLROOTCERT` at the pinned CA bundle.

```bash
# WHAT: apply the role/schema bootstrap and the versioned masked reporting views.
# WHY : Assumptions: ON_ERROR_STOP prevents a partially applied security model
#       from being mistaken for a successful load boundary.
export PGSSLMODE=verify-full
export PGSSLROOTCERT=/opt/carddemo-pg-certs/ca.pem
psql -v ON_ERROR_STOP=1 -f data-migration/sql/V0__schemas_and_roles.sql
psql -v ON_ERROR_STOP=1 -f data-migration/sql/V1__reporting_views.sql
```

## Verify Security Contracts

```bash
# WHAT: verify alternate loader identities and masked reporting-view privileges.
# WHY : Assumptions: successful connection by name is insufficient; the checks
#       prove role attributes, membership, source-table denial, and view masking.
psql -v ON_ERROR_STOP=1 \
  -f data-migration/sql/verify/alternate_database_users.sql
psql -v ON_ERROR_STOP=1 \
  -f data-migration/sql/verify/reporting_view_privileges.sql
```

## Cutover Gate

Do not switch application traffic based only on schema success. A production
cutover additionally requires record readers/loaders, per-dataset row counts,
checksums, money-total parity, and an approved rollback snapshot. Those
executables are absent in the current package, so the cutover gate is **closed**
for source-record loading even when every command above passes.
