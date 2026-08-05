# CardDemo Data Migration Support

> **Purpose.** Validate migration configuration and database security contracts,
> define copybook layouts, and package the schema/view bootstrap artifacts.
>
> **Source of truth.** The record layouts under `app/cpy/**`, the source extracts
> under `app/data/**`, and `data-migration/sql/**`.

The current package includes the configuration trust boundary, the normative
layout catalogue, schema/role DDL, masked reporting views, and tests for
parameter-name, CA, database-role, and reporting-view contracts. It does not
currently include the fixed-width readers, bulk loaders, or CLI described by
the target architecture; a complete source-record cutover must not be claimed
from this checkout.

## Install

```bash
# WHAT: install the hash-verified runtime plus development/test closure.
# WHY : Assumptions: requirements-dev.txt includes requirements.txt and pins the
#       exact Ruff, pytest, and coverage versions used by CI.
source .venv/bin/activate
python -m pip install --require-hashes -r data-migration/requirements-dev.txt
```

## Validate

```bash
# WHAT: run docstring/code lint, bytecode compilation, and the migration tests.
# WHY : Assumptions: configuration import success alone cannot prove CA
#       integrity, least-privilege alternate roles, or masked-view denial.
ruff check data-migration
python -m compileall -q data-migration/src
python -m pytest -v --tb=short data-migration/tests
```

## Build the Package and Image

```bash
# WHAT: build the wheel without resolving another project with the same name.
# WHY : Assumptions: the explicit ./ path selects this repository directory;
#       a bare data-migration token is a package-index name.
python -m pip wheel --no-deps ./data-migration
docker build -t carddemo/data-migration:local data-migration
```

## Database Contracts

Apply `sql/V0__schemas_and_roles.sql` before service migrations, then apply
`sql/V1__reporting_views.sql`. Connections must use `sslmode=verify-full` and
the pinned CA path/digest enforced by `carddemo_migration.config`.

Run both verification scripts under `sql/verify/`; they prove role attributes,
membership, masked-view behavior, and source-table denial.

## Dataset Contract

The generation catalogue contains **ten** families. Plain-text and HTML
statements are separate non-generation artifacts and must not be counted as an
eleventh or twelfth generation family. EBCDIC files are copied in binary mode
and decoded per field only.

See [the operational runbook](../docs/runbooks/data-migration.md) for staging,
schema application, verification, and the closed cutover gate.
