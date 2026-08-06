# CardDemo data migration (ETL)

> **Purpose.** Carry CardDemo's data off the mainframe. This package decodes the
> fixed-width VSAM, Db2 and IMS extract files the COBOL baseline writes, bulk-loads
> each record set into the Aurora PostgreSQL schema that owns it, stages the
> generation-dataset families into versioned object storage, and then verifies the
> load three independent ways. This file is also the **authoritative contract** for
> three things it alone owns: the command-line vocabulary, the eleven-row record
> layout contract, and the register of non-obvious rulings in
> [§15](#15-design-decisions-why).
>
> **Source of truth.** The record layouts under `app/cpy/**` and the dataset
> definitions under `app/jcl/**`, both reference-only; the seed extracts under
> `app/data/**`, read and never written; the sibling manifests
> [`pyproject.toml`](pyproject.toml), [`requirements.txt`](requirements.txt) and
> [`Dockerfile`](Dockerfile); and
> [`docs/architecture/data-model-and-schema-mapping.md`](../docs/architecture/data-model-and-schema-mapping.md)
> for the column-level mapping this package loads into.

Two sibling documents defer to this one. [`MIGRATION_README.md`](../MIGRATION_README.md)
publishes `python -m carddemo_migration.cli` as the ETL entry point and points here
for the canonical subcommand list; the repository [`README.md`](../README.md) points
here for the ETL as a whole. Anything stated in [§5](#5-command-line-interface),
[§6](#6-the-record-layout-contract) or [§15](#15-design-decisions-why) is therefore
the contract those documents and the Python modules under `src/` are written
against.

| Section | What it settles |
|---|---|
| [1. Overview](#1-overview) | What this package does and where it sits in the cutover |
| [2. Directory layout](#2-directory-layout) | What is delivered and what is contracted |
| [3. Prerequisites](#3-prerequisites) | Toolchain and versions |
| [4. Install](#4-install) | The exact install commands |
| [5. Command-line interface](#5-command-line-interface) | The authoritative subcommand contract |
| [6. The record layout contract](#6-the-record-layout-contract) | Eleven datasets, two numeric regimes |
| [7. EBCDIC handling](#7-ebcdic-handling) | Decode per field, never per record |
| [8. Loading](#8-loading) | The `IDCAMS REPRO` equivalent |
| [9. Staging dataset generations to S3](#9-staging-dataset-generations-to-s3) | Ten generation families |
| [10. Verification](#10-verification) | Three mandatory passes |
| [11. Schema and role bootstrap](#11-schema-and-role-bootstrap) | Eight schemas, one role per context |
| [12. Tests](#12-tests) | Reused vectors, binary exit status |
| [13. Lint and the documentation gate](#13-lint-and-the-documentation-gate) | The pydocstyle `D` family |
| [14. Container image](#14-container-image) | Interpreter parity and the entry point |
| [15. Design decisions (WHY)](#15-design-decisions-why) | Eleven rulings, each with its rejected alternative |
| [16. Prohibitions and boundaries](#16-prohibitions-and-boundaries) | What a contributor must not do |
| [17. Further reading](#17-further-reading) | Where each neighbouring concern is owned |

---

## 1. Overview

The migration is additive: the COBOL baseline keeps running, and this package
produces a second, relational copy of the same data. It is the **data half** of the
migration; the Java services under `services/**` are the behaviour half, and the two
meet at the copybook record layouts described in [§6](#6-the-record-layout-contract).

Cutover is **read, then verify, then switch** — never a single swap:

1. **Read.** Exported flat files are staged to object storage byte-for-byte, with no
   transcoding, so the staged copy is provably the extract the mainframe produced.
2. **Decode and load.** Each dataset is decoded **per field** and bulk-loaded into
   the one schema that owns it.
3. **Verify.** Three independent passes run: row counts per dataset, record
   checksums, and money-total parity against the source files. All three are
   mandatory ([§10](#10-verification)).
4. **Switch.** Traffic moves only after the verification gate passes. The gate lives
   in [the data-migration runbook](../docs/runbooks/data-migration.md), not here.

Assumptions: the source extracts are files, never a live mainframe connection. The
package reads `.PS` and `.txt` images that already exist; it never dials into CICS,
VSAM, Db2 or IMS. That is what makes the deployment satisfy the migration's
"no manual mainframe dependency" constraint.

> **Note — delivery state.** This checkout delivers the configuration trust
> boundary, the normative layout catalogue, the zoned-decimal codec, the S3
> generation writer, the credential-application bootstrap, the schema/role DDL, the
> masked reporting views, and the command-line entry point carrying the three
> subcommands whose backing modules are present — `list-datasets`, `stage-dataset`
> and `apply-credentials`. It does **not** yet deliver the packed and EBCDIC codecs,
> the readers, the Aurora bulk loader or the three verification passes, so
> `load-dataset` and the `verify-*` subcommands are **not registered** by the parser.
> [§2](#2-directory-layout) marks each item and [§5.2](#52-subcommands-and-their-arguments)
> marks each subcommand. `python -m carddemo_migration.cli --help` and every
> registered subcommand run against this checkout; an unregistered one is refused as
> a usage error rather than failing part-way through. A source-record cutover must
> not be claimed from this checkout, because loading and verifying records is
> precisely what it does not yet do.
>
> Assumptions: an unimplemented subcommand is left OUT of the parser rather than
> registered and made to fail. A registered command that cannot work would be
> advertised by `--help`, an orchestrator author would wire a batch state to it, and
> the failure would then arrive in a deployment instead of at the point where the
> command was chosen.

---

## 2. Directory layout

```text
data-migration/
├── README.md                     this file -- CLI contract, layout contract, WHY ledger
├── pyproject.toml                packaging, ruff and pytest configuration
├── requirements.txt              runtime closure, hash-locked (9 distributions)
├── requirements-dev.txt          the runtime closure plus ruff, pytest and coverage
├── requirements-build.txt        the PEP 517 build backend, installed and discarded
├── Dockerfile                    two-stage image; non-root; digest-pinned base
├── sql/
│   ├── V0__schemas_and_roles.sql       delivered -- 8 schemas, 1 role per context
│   ├── V1__reporting_views.sql         delivered -- masked cross-schema views
│   └── verify/
│       ├── alternate_database_users.sql    delivered -- role-attribute ceiling
│       ├── reporting_view_privileges.sql   delivered -- masked-view privileges
│       ├── row_counts.sql                  contracted -- pairs with verify/row_counts.py
│       └── money_totals.sql                contracted -- pairs with verify/money_parity.py
├── src/carddemo_migration/
│   ├── __init__.py               delivered -- import and layering contract
│   ├── config.py                 delivered -- runtime settings, resolved when a command runs
│   ├── credentials.py            delivered -- applies each generated credential to its role
│   ├── role_credentials.py       delivered -- SCRAM verifier derivation and role bootstrap
│   ├── cli.py                    delivered -- the three registered subcommands in section 5
│   ├── copybook/
│   │   ├── __init__.py           delivered -- makes the subpackage a regular package
│   │   ├── layouts.py            delivered -- offset, length and usage, declared ONCE
│   │   ├── zoned.py              delivered -- sign-overpunch decode and encode
│   │   ├── packed.py             contracted -- COMP-3 decode and encode
│   │   └── ebcdic_codec.py       contracted -- cp037 decode, applied PER FIELD
│   ├── readers/                  contracted -- one reader per record layout
│   ├── loaders/
│   │   ├── s3_stage.py           delivered -- generation staging and LIMIT/SCRATCH retention
│   │   └── aurora.py             contracted -- bulk COPY into one owning schema
│   └── verify/                   contracted -- row_counts.py, checksum.py, money_parity.py
└── tests/
    ├── test_cli.py                     delivered
    ├── test_config_name_contract.py    delivered
    ├── test_database_trust.py          delivered
    ├── test_reporting_views.py         delivered
    ├── test_s3_stage.py                delivered
    └── conftest.py, test_zoned.py, test_readers.py, test_loaders.py, test_verify.py
                                        contracted
```

Assumptions: a contracted path above is written as a plain name rather than a link,
because [the documentation standard](../docs/CODE_DOCUMENTATION_STANDARD.md) requires
that a path to a file which does not exist yet is never published as a link — a link
that resolves to nothing is a defect a reader finds by clicking.

`src` itself is a layout container and is deliberately **not** a package. The
distribution is discovered through `where = ["src"]` in
[`pyproject.toml`](pyproject.toml).

---

## 3. Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| CPython | **3.13** (3.13.7 locally, 3.13.14 in the image) | Everything in this package |
| pip | any release supporting `--require-hashes` | Installing the hash-locked manifests |
| ruff | **0.16.0** | Lint and the docstring gate ([§13](#13-lint-and-the-documentation-gate)) |
| pytest | **9.1.1** | The package's own tests ([§12](#12-tests)) |
| coverage | **7.15.2** | Coverage measurement |
| psycopg | **3.3.4** | Server-side `COPY` for the Aurora loaders |
| boto3 / botocore | **1.43.50** | Object-store staging, Parameter Store, Secrets Manager |
| ebcdic | **2.0.1** | Registers the wider EBCDIC code-page family ([§7](#7-ebcdic-handling)) |
| libpq | 5 | `psycopg` resolves the client library at run time, not from the wheel |
| Docker | any current release | Building the ETL image ([§14](#14-container-image)) |
| PostgreSQL client | 17 | Applying the DDL in [§11](#11-schema-and-role-bootstrap) |

Trade-offs: `boto3`, `botocore`, `pytest` and `coverage` are pinned to the exact
versions the existing COBOL suite already locks in `tests/requirements-test.txt`
rather than to newer releases. That suite is this migration's functional-parity
oracle and no version drift may be introduced into it, so the ETL matches the oracle
instead of asking the oracle to move. The payoff is diagnostic: both sides run
identical library code, so a discrepancy in verification output cannot be explained
away as a library difference and has to be investigated as a real one.

---

## 4. Install

```bash
# WHAT: create the repository-root environment and install the hash-locked
#       development closure -- the runtime dependencies plus ruff, pytest and coverage.
# WHY : Assumptions: a current system Python is PEP 668 externally managed and refuses
#       a direct install, so an environment is required rather than advisable. The
#       environment lives at the REPOSITORY ROOT, not inside this directory, so the ETL
#       and the COBOL parity oracle share one interpreter instead of two that can
#       disagree about a pin they are both supposed to hold.
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --require-hashes -r data-migration/requirements-dev.txt
```

```bash
# WHAT: install the build backend, then install this package itself in editable mode.
# WHY : Assumptions: `carddemo_migration` is a src-layout distribution and
#       pyproject.toml sets no pytest import path, so the package must be INSTALLED
#       before `pytest` can import it -- without this step collection fails with
#       "No module named 'carddemo_migration'". `--no-build-isolation` reuses the
#       hash-locked backend just installed instead of resolving an unpinned one, and
#       `--no-deps` keeps the runtime closure exactly as the manifest above fixed it.
python -m pip install --require-hashes -r data-migration/requirements-build.txt
python -m pip install --no-build-isolation --no-deps -e ./data-migration
```

Assumptions: `./data-migration` is written with an explicit leading `./`. The bare
token `data-migration` is a legal package-index name, so pip would resolve an
unrelated project from PyPI rather than this directory.

---

## 5. Command-line interface

**This section is the contract.** [`src/carddemo_migration/cli.py`](src/carddemo_migration/cli.py)
implements the three subcommands marked **registered** in
[§5.2](#52-subcommands-and-their-arguments) below, and
[`MIGRATION_README.md`](../MIGRATION_README.md) publishes the invocation. Nothing here
describes a flag that should not be implemented, and no two subcommands do the same
work.

Assumptions: the five subcommands marked **contracted** are stated here but are
deliberately **not registered** by the parser, because their backing modules —
`readers/`, `loaders/aurora.py` and `verify/` — are not in this distribution. So
`python -m carddemo_migration.cli --help` lists three subcommands, not eight, and
naming a contracted one is refused as a usage error (exit 2). They remain documented
because the batch state machine and this package's `Dockerfile` are written against
the whole set, and filling an interface in later is a smaller change than renaming one.

Trade-offs: the alternative was to register all eight and have the five unbacked ones
fail when invoked. That was rejected: `--help` would advertise a command that cannot
run, an orchestrator author would wire a batch state to it on that evidence, and the
failure would surface in a deployment rather than at the point where the command was
chosen. The accepted cost is that `--help` and this table do not match one-for-one,
which is why the table marks each row.

### 5.1 Invocation

```bash
# WHAT: the canonical invocation form.
# WHY : Assumptions: this is the form the container ENTRYPOINT issues
#       (`data-migration/Dockerfile`) and the form the package entry point publishes,
#       so it is the one that must always work. The batch orchestrator passes each
#       subcommand and its options as a container command override, which is an
#       argument list rather than a shell string -- a console-script name would add a
#       resolution step inside the image for no gain.
python -m carddemo_migration.cli <subcommand> [options]
```

```bash
# WHAT: the equivalent console-script form.
# WHY : Trade-offs: `[project.scripts]` in pyproject.toml installs `carddemo-migrate`
#       as a convenience for interactive use. It is a second spelling of the same
#       entry point, not a second entry point, and it exists only where the
#       distribution has been installed; the module form above works from a source
#       tree as well, which is why the module form is the canonical one.
carddemo-migrate <subcommand> [options]
```

### 5.2 Subcommands and their arguments

| Subcommand | State | Purpose | Required arguments | Optional arguments |
|---|---|---|---|---|
| `list-datasets` | **registered** | Print the record-layout contract — identifier, copybook, record length, key length and provenance — for every registered layout, so a caller can enumerate the set instead of hard-coding it | none | `--format {table,json}` |
| `stage-dataset` | **registered** | Stage **one** exported extract to object storage under the generation prefix convention, copying bytes verbatim | `--dataset`, `--source`, `--business-date`, `--generation`, `--domain` | `--object-name`, `--retain` (default 5) |
| `apply-credentials` | **registered** | Give every service login role the credential it authenticates with, then prove each role can log in | none | none |
| `load-dataset` | contracted | Decode **one** dataset per field and bulk-load it into the schema that owns it | `--dataset`, `--source` | `--encoding {ascii,ebcdic}`, `--dry-run` |
| `verify-row-counts` | contracted | Verification pass 1 — loaded row count against source record count, per dataset | none | `--dataset` |
| `verify-checksums` | contracted | Verification pass 2 — per-record checksum of loaded rows against the source image | none | `--dataset` |
| `verify-money-totals` | contracted | Verification pass 3 — money-column totals against the source files | none | `--dataset` |
| `verify-all` | contracted | Run all three passes in the fixed order 1, 2, 3 and stop at the first failure | none | none |

Assumptions: `list-datasets` prints the five properties
[`layouts.py`](src/carddemo_migration/copybook/layouts.py) holds authoritatively, and
reports **every** registered layout — the eleven base masters of
[§6.1](#61-the-eleven-datasets) plus the three derived ones, distinguished by the
`provenance` column. The owning schema and the seed-extract encodings that §6.1
tabulates are deliberately **not** printed: they have no representation in this
distribution's code, and giving them one here would create a second source for a table
§6.1 already owns, free to disagree with it. §6.1 remains where those two are read.

Assumptions: `--domain` is **required**, not optional. It is the bounded-context
segment of the object prefix, and this distribution holds no dataset-to-context mapping
— the owning schema is tabulated in §6.1 and nowhere in code — so a default would have
to invent that mapping. An invented default that is wrong writes a real extract to a
prefix nothing reads, which is worse than requiring the caller to be explicit. It
becomes optional when the mapping has an authoritative home in code.

| Subcommand | What it writes | Non-zero exit when |
|---|---|---|
| `list-datasets` | The contract table on standard output. Touches no database, no object store and no credential | the requested format is unknown |
| `stage-dataset` | One object at `<domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/<object-name>` in the dataset bucket, then permanently scratches generations that roll off. The bucket, deployment and effective region are logged before the write | the source is unreadable, the dataset identifier is unknown, the generation is outside 1–9999, or the write or the scratch fails |
| `load-dataset` | Rows in the owning schema's table, inside one transaction; a per-dataset summary on standard output | a record fails the width contract, a field fails to decode, or the load transaction cannot commit |
| `apply-credentials` | A SCRAM verifier on each of the service login roles. The plaintext credential never crosses the connection | any role is missing, cannot be given its verifier, or cannot then log in |
| `verify-row-counts` | A per-dataset expected-versus-actual table | any dataset's counts differ |
| `verify-checksums` | The identifier of every record whose checksum differs, with sensitive fields masked | any record differs |
| `verify-money-totals` | A per-column source-versus-loaded total table | any total differs by any amount |
| `verify-all` | The three tables above, in order | any one pass fails |

### 5.3 Applying the DDL is deliberately not a subcommand

There is no `bootstrap-schemas` subcommand, and there will not be one.
[`sql/V0__schemas_and_roles.sql`](sql/V0__schemas_and_roles.sql) is applied by
[the data-migration runbook](../docs/runbooks/data-migration.md), with
`psql -v ON_ERROR_STOP=1` under a temporary administrative identity, and
[§11](#11-schema-and-role-bootstrap) describes what it creates.

Alternatives Considered: wrapping it as a subcommand, which would put the whole
bootstrap behind one entry point and is the tidier-looking arrangement. Rejected
because the script creates roles, and role creation is cluster-wide authority that no
ETL task role holds or should hold — exposing it here would mean the loader's identity
had to carry role-creation authority for the lifetime of every load, which is the
opposite of the least-privilege boundary the eight per-context roles exist to draw.
`apply-credentials` is the one bootstrap step that **is** exposed, because it needs
only the cluster's administrative secret for the duration of a single invocation and
because the module that implements it asks to be reached through the command line
rather than reimplemented.

### 5.4 Dataset identifiers

`--dataset` accepts exactly these eleven values, and no others:

```text
usrsec  acctdata  carddata  custdata  cardxref  dalytran
transact  discgrp  trancatg  tcatbalf  trantype
```

Each identifier is the baseline dataset name from
[§6.1](#61-the-eleven-datasets), lowercased with the `.PS` suffix dropped, so the
accepted set is derivable from that one table and there is no second list to keep in
step. `list-datasets` prints the same set at run time, which is what the batch chain's
per-dataset staging step enumerates its branches from.

Alternatives Considered: naming the identifiers after the internal reader modules
instead — the singular domain words for account, customer and transaction. Rejected,
because the caller's request, the JCL that produced the extract and the object key all
name the **dataset**, so tying the public vocabulary to an internal module layout
would turn a file rename into a breaking change to the command line, and would leave
two vocabularies for one set of eleven things.

### 5.5 The business date is a parameter, never a clock read

`stage-dataset` requires `--business-date YYYY-MM-DD`. Assumptions: the date is
supplied by the caller and is never read from the wall clock, because a rerun must
reproduce the run it is meant to reproduce — a clock read would place the second
attempt under a different `dt=` prefix and leave the first one orphaned. This mirrors
the baseline exactly, where the business date arrives as `PARM='2022071800'` on the
job step rather than from the system time.

`--generation NNNN` is likewise explicit and is validated into 1–9999, the four-digit
range the prefix convention encodes. Assumptions: one generation number is computed
once per execution and passed to every step that touches the family. Two steps each
deriving "the next generation" for themselves resolve to two different prefixes, and
a later step then reads an empty location while the earlier step's output sits
elsewhere.

### 5.6 Exit semantics

**The exit status is binary: zero means the command did what it was asked, and any
non-zero value means it did not.** Where a command can usefully classify its failure
it uses distinct non-zero codes — `2` invoked incorrectly, `8` the step could not be
completed, `16` the environment or cluster could not be reached at all — exactly as
the delivered [`credentials.py`](src/carddemo_migration/credentials.py) already does.
No non-zero value is ever a pass.

> **Note — the graded rubric belongs to the parity oracle and to nothing else.** The
> repository also contains a graded aggregate return-code rubric — `0` pass, `2`
> usage, `4` warn, `8` fail, `16` fatal, aggregating the **worst** code across
> layers — and under `tests/**` a warn-level aggregate of `4` is the documented
> **green** state. That is correct there and only there: it records the immutable,
> out-of-scope `CBEXPORT`/`CBIMPORT` FD `RECORD KEY` defect, which no compiler flag
> can fix and which the reference-only rule forbids editing. **That tolerance must
> never be imported into this CLI, into `pytest`, or into `ruff`.** This package has
> no warn tier: a load that half worked is a failed load, and a lint or test run that
> reports anything other than success is a failure.

### 5.7 How connection details reach the process

No connection string, endpoint, account identifier or credential appears anywhere in
this repository. Every runtime value is resolved when a command runs, from AWS
Systems Manager Parameter Store and AWS Secrets Manager, by
[`config.py`](src/carddemo_migration/config.py). The environment supplies only the
**names** below — never values.

| Variable | Meaning | Default |
|---|---|---|
| `CARDDEMO_ENVIRONMENT` | Selects which environment's parameters and secrets are read | **none — required** |
| `CARDDEMO_PARAMETER_PREFIX` | Root of the Parameter Store path the lookups are built from | `/carddemo` |
| `CARDDEMO_DB_SSL_MODE` | TLS verification mode | `verify-full`, and no other value is accepted |
| `CARDDEMO_DB_SSL_ROOT_CERT` | Trust anchor for the database connection | the CA bundle the image installs |
| `CARDDEMO_DB_MASTER_SECRET` | Name of the cluster's administrative secret, used only by `apply-credentials` | none |
| `CARDDEMO_DB_ALTERNATE_USERS` | Additional database user names permitted to act for a schema's role | none |
| `CARDDEMO_MASK_HMAC_KEY` | Keys the masking of sensitive fields in diagnostic output | none |

Assumptions: `CARDDEMO_ENVIRONMENT` deliberately has **no** default. A default would
let a command intended for one environment resolve successfully against another,
which is the most damaging failure available to a data-migration tool — it succeeds,
and it succeeds in the wrong place. The endpoint, port, database name and dataset
bucket are read from `<prefix>/<environment>/...` paths, and each role's credential
from a per-role secret under the same root;
[`config.py`](src/carddemo_migration/config.py) owns the exact path and member names.

---

## 6. The record layout contract

### 6.1 The eleven datasets

Every reader offset in this package derives from the table below. **These values are
verified, not asserted** — three independent sources agree on each one:

1. hand-summing the declared field widths in the copybook;
2. exact division of the corresponding EBCDIC dataset's byte size by the record
   length, which leaves no remainder for any of the twelve `.PS` datasets present
   under `app/data/EBCDIC`; and
3. the `RECORDSIZE` and `KEYS` operands of the `IDCAMS DEFINE CLUSTER` that creates
   the file, which [`layouts.py`](src/carddemo_migration/copybook/layouts.py) asserts
   at import time against a table it keeps deliberately separate from the layouts it
   is checking.

| Dataset | Copybook | Record length | Key length | Owning schema | Seed extract present |
|---|---|---|---|---|---|
| `USRSEC.PS` | [`CSUSR01Y`](../app/cpy/CSUSR01Y.cpy) | 80 | 8 | `auth` | EBCDIC only |
| `ACCTDATA.PS` | [`CVACT01Y`](../app/cpy/CVACT01Y.cpy) | 300 | 11 | `account` | ASCII and EBCDIC |
| `CARDDATA.PS` | [`CVACT02Y`](../app/cpy/CVACT02Y.cpy) | 150 | 16 | `card` | ASCII and EBCDIC |
| `CUSTDATA.PS` | [`CVCUS01Y`](../app/cpy/CVCUS01Y.cpy) | 500 | 9 | `account` | ASCII and EBCDIC |
| `CARDXREF.PS` | [`CVACT03Y`](../app/cpy/CVACT03Y.cpy) | 50 | 16 | `account` | ASCII and EBCDIC |
| `DALYTRAN.PS` | [`CVTRA06Y`](../app/cpy/CVTRA06Y.cpy) | 350 | 16 | `ledger` | ASCII and EBCDIC |
| `TRANSACT` | [`CVTRA05Y`](../app/cpy/CVTRA05Y.cpy) | 350 | 16 | `ledger` | none — see below |
| `DISCGRP.PS` | [`CVTRA02Y`](../app/cpy/CVTRA02Y.cpy) | 50 | 16 | `reference` | ASCII and EBCDIC |
| `TRANCATG.PS` | [`CVTRA04Y`](../app/cpy/CVTRA04Y.cpy) | 60 | 6 | `reference` | ASCII and EBCDIC |
| `TRANTYPE.PS` | [`CVTRA03Y`](../app/cpy/CVTRA03Y.cpy) | 60 | 2 | `reference` | ASCII and EBCDIC |
| `TCATBALF.PS` | [`CVTRA01Y`](../app/cpy/CVTRA01Y.cpy) | 50 | 17 | `ledger` | ASCII and EBCDIC |

Assumptions: the owning schema is the bounded context that owns the target table, and
it is what `load-dataset` resolves `--dataset` to. `load-dataset` connects as that
schema's own login role and no other, so a load can only ever write inside the one
schema the dataset belongs to — the eight roles in
[§11](#11-schema-and-role-bootstrap) are what make that a boundary rather than a
convention.

```bash
# WHAT: reproduce the second of the three verifications -- divide each EBCDIC dataset's
#       byte size by its declared record length and confirm the remainder is zero.
# WHY : Assumptions: an EBCDIC dataset is a fixed-length blocked image with no line
#       terminators, so its size is exactly records x reclen. A non-zero remainder means
#       either the declared length is wrong or the file is truncated, and either way no
#       field offset in this package can be trusted until it is resolved.
stat -c%s app/data/EBCDIC/AWS.M2.CARDDEMO.CARDXREF.PS     # 2500   = 50  x 50
stat -c%s app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS       # 800    = 10  x 80
stat -c%s app/data/EBCDIC/AWS.M2.CARDDEMO.DALYTRAN.PS     # 105000 = 300 x 350
stat -c%s app/data/EBCDIC/AWS.M2.CARDDEMO.EXPORT.DATA.PS  # 250000 = 500 x 500
```

Three layouts beyond the eleven are also declared, because the pipeline reads records
that no seed extract contains: the 430-byte reject record the posting run writes, the
statement-ordered transaction view, and the interest-generated transaction. A twelfth
reader covers the 500-byte packed export record described in
[`CVEXPORT`](../app/cpy/CVEXPORT.cpy), which likewise has no base master of its own.

### 6.2 Seven facts a reader would otherwise rediscover the hard way

Each of these was measured against the repository, and each one silently breaks a
reader that does not know it.

**`usrsec` exists only in EBCDIC form.** There is no `app/data/ASCII/usrsec.txt`; the
only extract is `app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS`. The `usrsec` reader
therefore goes through the EBCDIC path unconditionally rather than choosing a path
from the file it was handed.

**`TRANSACT` has no seed extract at all.** The 350-byte transaction master is produced
by the posting and backup pipeline, not shipped, so there is nothing under `app/data`
for `--source transact` to point at and no default path to fall back on. Its reader is
exercised against `tests/fixtures/**` and against pipeline-produced data instead, which
is why the table above marks it separately from the ten that do ship an extract.

**The ASCII seeds are not uniformly full width.** `app/data/ASCII/cardxref.txt`
carries **36** data bytes per line — the 14-byte trailing `FILLER` that
[`CVACT03Y`](../app/cpy/CVACT03Y.cpy) declares is simply omitted — while the EBCDIC
form and every `tests/fixtures/**` copy carry the full 50. The resolution: a **short**
line is right-padded with spaces to the declared length before any field is
extracted, and an **over-long** line is still refused. Alternatives Considered: strict
rejection of any row that is not exactly the declared width, which is precisely what
the reference codec's own `_validated_record` does. Rejected here because it would
make the shipped seed unloadable, and because the missing bytes are `FILLER`, which is
dropped anyway — padding on the right with spaces cannot move a field that exists,
whereas truncating an over-long line would move every field after the cut, so the
asymmetry between the two directions is the whole point.

**Three ASCII seeds use CRLF line endings** — `tcatbal.txt`, `trancatg.txt` and
`trantype.txt`. The other six use LF. A reader therefore strips **at most one**
terminator, matching `\r\n`, `\n` or `\r`, exactly as the reference implementation
does. Assumptions: a reader that strips only `\n` leaves the `\r` in the row, making
every row in those three files one character longer than its declared length — which
the over-long rule then refuses, so the whole dataset fails to load rather than loading
wrongly. Trade-offs: that loud failure is the reason the over-long case is refused
instead of being truncated to fit. Truncating would silently succeed here, because all
three of those layouts happen to end in `FILLER` and the stray `\r` would land in
padding that is dropped anyway — and it would then fail invisibly on the first layout
whose last declared field carries data.

```bash
# WHAT: reproduce the two seed-shape findings -- the short cross-reference line and the
#       three CRLF files.
# WHY : Assumptions: `tr -d` removes the terminator before counting, so the number
#       printed is the DATA width rather than the line length; without it every file
#       reads one or two bytes wider and the 36-versus-50 finding disappears.
head -1 app/data/ASCII/cardxref.txt | tr -d '\n\r' | wc -c   # 36, against a declared 50
for f in tcatbal trancatg trantype; do grep -c $'\r' "app/data/ASCII/$f.txt"; done
```

**`TRAN-CAT-KEY` is declared twice, with different children.** In
[`CVTRA01Y`](../app/cpy/CVTRA01Y.cpy) it is `TRANCAT-ACCT-ID` + `TRANCAT-TYPE-CD` +
`TRANCAT-CD` and spans **17** bytes; in [`CVTRA04Y`](../app/cpy/CVTRA04Y.cpy) it is
`TRAN-TYPE-CD` + `TRAN-CAT-CD` and spans **6**. `TRAN-TYPE-CD` and `TRAN-CAT-CD`
appear again in [`CVTRA05Y`](../app/cpy/CVTRA05Y.cpy). Layout descriptors are
therefore **scoped per copybook**. Alternatives Considered: a flat registry keyed on
bare field names, which is the obvious shape and would collide on all three of those
names — resolving `TRAN-CAT-KEY` to the wrong arity mis-aligns every field after it
while still returning well-formed values.

**Two unrelated sources corroborate the transaction offsets.** Hand-summing
`CVTRA05Y`'s field widths puts `TRAN-CARD-NUM` at zero-based offset **262**,
`TRAN-ORIG-TS` at **278** and `TRAN-PROC-TS` at **304**. Independently,
[`TRANREPT.jcl`](../app/jcl/TRANREPT.jcl) lines 41–42 declare
`TRAN-CARD-NUM,263,16,ZD` and `TRAN-PROC-DT,305,10,CH` in one-based positions. The two
agree exactly, which removes the doubt from every offset-dependent decision in this
package. Two subtleties are worth carrying: the sort control types the card number as
zoned decimal although the copybook declares `PIC X(16)`, and `TRAN-PROC-DT` covers
only the **first 10 characters** of the 26-character `TRAN-PROC-TS` — its date prefix,
not the whole stamp.

**The EBCDIC and ASCII twins are not identical, and one divergence changes money.**
Nine of the eleven datasets ship in both encodings, and for every one of them the two
forms agree field for field — except at exactly **two** field values, both measured
against the raw bytes:

| Dataset | Record | Field | EBCDIC form | ASCII form |
|---|---|---|---|---|
| `DISCGRP` | 34 (`DEFAULT`/type `07`/cat `0001`) | `DIS-INT-RATE` | `00150{` → **15.00** | `00000{` → **0.00** |
| `ACCTDATA` | 49 (`ACCT-ID 00000000049`) | `ACCT-ADDR-ZIP` | `ZEROAPR   ` | `A000000000` |

**The first one decides interest.** Record 34 is the `DEFAULT` disclosure-group row,
and [`CBACT04C`](../app/cbl/CBACT04C.cbl) falls back to that row's rate whenever an
account's own group key is not found — VSAM status 23, the fallback the reference suite
asserts by name. So a `DEFAULT`-fallback account accrues **15.00%** if the EBCDIC
extract was loaded and **nothing at all** if the ASCII one was, on identical inputs,
with no error either way. The second divergence is cosmetic by comparison: 290 of the
record's 300 characters are identical and the field is an address ZIP that no
calculation reads.

**This package therefore treats EBCDIC as authoritative wherever both forms exist.**
Assumptions: the EBCDIC `.PS` files are the mainframe extracts — fixed-length blocked
records carrying sign overpunch and packed fields, in the encoding the baseline
programs actually read — whereas the ASCII `.txt` files are convenience conversions of
them, which is already visible from the two shape findings above: `cardxref.txt` has
lost its trailing `FILLER` and three of the nine have acquired CRLF terminators.
Neither is a property of the source data; both are artefacts of the conversion. A
conversion that dropped fourteen bytes from one file is not the form to trust when it
disagrees with the original about a rate. Alternatives Considered: loading the ASCII
form by default because it is the easier path and needs no codec, which was rejected on
exactly that reasoning; and reconciling the two by editing one file, which is
forbidden outright — `app/**` is REFERENCE-only, so the divergence is recorded here and
resolved by the choice of source, never by changing a byte of either extract.
Trade-offs: `load-dataset`'s optional `--encoding {ascii,ebcdic}` still lets an
operator deliberately load the ASCII twin of any dataset that ships both, so the
capability is not removed; what this fact buys is that using it is an informed decision
with a known consequence rather than an accident. Note that
neither divergence is a codec defect: the per-field cp037 path re-encodes all 626
EBCDIC seed records byte-identically, so both differences are genuinely present in the
shipped files.

```bash
# WHAT: reproduce both twin divergences straight from the raw bytes.
# WHY : Assumptions: the EBCDIC file is opened in BINARY mode and sliced by record
#       length before any decode, because it has no line terminators at all and a text
#       read would both mis-frame it and corrupt the sign bytes -- the hazard section 7
#       exists for. The ASCII twin is split on newlines because it does have them.
python3 - <<'PY'
eb = open("app/data/EBCDIC/AWS.M2.CARDDEMO.DISCGRP.PS", "rb").read()
asc = open("app/data/ASCII/discgrp.txt", "rb").read().split(b"\n")
rec = 34
e = eb[(rec - 1) * 50:rec * 50].decode("cp037")
a = asc[rec - 1].decode("latin-1")
print("group        ", repr(e[0:10]))
print("EBCDIC rate  ", repr(e[16:22]), eb[(rec - 1) * 50 + 16:(rec - 1) * 50 + 22].hex())
print("ASCII  rate  ", repr(a[16:22]))
PY
```

### 6.3 Two numeric regimes, and they are two

**Zoned decimal with sign overpunch** carries every money and rate field across the
base master records. `ACCT-CURR-BAL PIC S9(10)V99` in
[`CVACT01Y`](../app/cpy/CVACT01Y.cpy) is twelve bytes of display text in which the
sign rides the final digit:

```text
positive:  { A B C D E F G H I   ->  +0 +1 +2 ... +9      ('{' is +0)
negative:  } J K L M N O P Q R   ->  -0 -1 -2 ... -9      ('}' is -0)
```

**Unsigned `PIC 9(n)` fields — no `S` in the picture — carry plain digits with no
overpunch at all.** Signed and unsigned display fields are handled distinctly.
Assumptions: over-eager sign parsing corrupts unsigned keys such as
`ACCT-ID PIC 9(11)` and `XREF-CUST-ID PIC 9(09)`, and it corrupts them into values
that still look like account numbers.

**Packed decimal (`COMP-3`)** appears in only two places: the export record in
[`CVEXPORT`](../app/cpy/CVEXPORT.cpy), and — heavily — the two authorization IMS
segment layouts under `app/app-authorization-ims-db2-mq/cpy/`. The authorization
context is the only place packed decimal reaches persisted target data.

**`USAGE` determines physical width; the picture clause alone never does.** Inside
`EXPORT-ACCOUNT-DATA` the *same* `PIC S9(10)V99` clause appears with three different
usages and therefore three different widths — `COMP-3` at 7 bytes (line 50), no
`USAGE` clause, meaning zoned display, at 12 bytes (line 51), and `COMP` at 8 bytes of
binary (line 57). Assumptions: a layout that keys width off the picture clause
mis-aligns the export record from the first packed field onwards, and every field
after it decodes to a plausible wrong number rather than raising.

> **Note — one word, two unrelated meanings.** `tests/README.md` §5.2 mandates the
> compiler flag `-fsign=EBCDIC` and warns that the default `-fsign=ASCII` "misreads
> the zoned-decimal sign overpunch and silently corrupts negative balances". There,
> **`EBCDIC` names a sign convention, not a character encoding.** It is a completely
> different concern from the cp037 **character** decode in
> [§7](#7-ebcdic-handling): one decides which byte values carry a trailing sign, the
> other decides which byte values are which characters. A reader who conflates them
> concludes that the ASCII seeds need transcoding, or that the EBCDIC datasets do not
> need a sign table. Both conclusions are wrong.

### 6.4 Money is exact fixed point at every hop

`NUMERIC(p,2)` in SQL, `BigDecimal` at scale 2 in Java, **`Decimal` in Python**, and a
JSON string on any wire. **`float` is forbidden in the money path here exactly as
`double` is in the Java tree.** Alternatives Considered: `float` is the obvious Python
numeric type and is rejected outright — a binary float cannot represent a value such
as ten cents exactly, so a total accumulated in one drifts from the total the COBOL
computed, silently and by an amount that grows with the row count. The reference codec
takes the same position and refuses `float` inputs rather than converting them.

Known-answer vectors, taken from the reference codec's own doctests over the real
seeds, so any implementation can be sanity-checked against them:

| Encoded field | Picture | Decodes to | Note |
|---|---|---|---|
| `00000001940{` | `S9(10)V99` | `194.00` | `{` is `+0` |
| `00000020650{` | `S9(10)V99` | `2065.00` | present in `app/data/ASCII/acctdata.txt` |
| `0000005047G` | `S9(09)V99` | `504.77` | `G` is `+7` |
| `0000005047J` | `S9(09)V99` | `-504.71` | `J` is `-1` |
| `0000009190}` | `S9(09)V99` | `-919.00` | present in `tests/fixtures/export/happy_path/trandata.txt` |

**A trailing letter is not evidence of a sign.** The byte run `3580010001P` occurs at
zero-based offset 12 of the first line of `app/data/ASCII/dailytran.txt` and looks
exactly like an eleven-character negative overpunch. It is not one: it spans the tail
of `DALYTRAN-ID` (`3580`), the whole of `DALYTRAN-TYPE-CD` (`01`), the whole of
`DALYTRAN-CAT-CD` (`0001`) and the first character of `DALYTRAN-SOURCE` (the `P` of
`POS TERM`). Assumptions: decoding is anchored on a field's declared offset and
length, never on a pattern match across the record. A pattern-matching decoder finds
this run in the very first record of the very first seed file.

### 6.5 Offsets are single-sourced, in one module, across two languages

Record offsets, lengths and usages are declared **once**, in
[`layouts.py`](src/carddemo_migration/copybook/layouts.py), and imported from there by
every reader. Alternatives Considered: declaring each reader's own offsets beside its
own parsing code, which reads more locally and is how two readers of the same layout
drift apart — there would be two places to change and two places to review, and the
divergence is invisible until a field lands one byte over. This is the Python analogue
of compiling every COBOL program against a single copybook include path, which is
exactly how the baseline guaranteed the same property, and of the discipline
`tests/README.md` §12 states for the COBOL suite: never duplicate a layout, keep it
single-sourced from `app/cpy/`.

The contract also reaches across languages. `copybook/zoned.py`, `copybook/packed.py`
and the descriptors in `layouts.py` must produce results identical to the Java
shared kernel's
[`ZonedDecimalCodec`](../services/common-lib/src/main/java/com/carddemo/common/codec/ZonedDecimalCodec.java),
[`PackedDecimalCodec`](../services/common-lib/src/main/java/com/carddemo/common/codec/PackedDecimalCodec.java),
[`FixedWidthCodec`](../services/common-lib/src/main/java/com/carddemo/common/codec/FixedWidthCodec.java)
and
[`CopybookLayout`](../services/common-lib/src/main/java/com/carddemo/common/codec/CopybookLayout.java),
which transcribe the same copybooks. Assumptions: a divergence between the two sides
is not a style difference but a silent data defect, because both sides would keep
returning well-formed values. That is why the vectors in
[§6.4](#64-money-is-exact-fixed-point-at-every-hop) are reproduced on both sides
rather than trusted to one.

### 6.6 Imports are absolute

```python
from carddemo_migration.copybook.layouts import ACCOUNT_LAYOUT
```

Never relative, and never reached by manipulating `sys.path`. Assumptions: this is
mechanically enforced rather than agreed — [`pyproject.toml`](pyproject.toml) selects
`TID252` with `ban-relative-imports = "all"`, so a relative import fails the build
instead of waiting for a reviewer to notice it.

---

## 7. EBCDIC handling

**Decode per fixed-width field, never per record, from a file opened in binary mode.**
This is the single most likely implementation mistake in the whole package, and the
reason it is worth stating this bluntly is that getting it wrong does not raise.

The evidence is not an opinion. The existing suite deliberately treats the EBCDIC
extracts as **opaque binary and never transcodes them**, and its own helper comments
say why: at [`localstack_setup.py`](../tests/helpers/localstack_setup.py) around line
727 a file-referenced payload is uploaded from its on-disk path because its "raw bytes
-- including binary EBCDIC with NULs and overpunch sign bytes -- are uploaded
verbatim"; around line 741 the reason no temporary copy is made is that
"round-tripping a 15 KB binary EBCDIC dataset through a UTF-8 temp write would corrupt
it"; and around line 1044 the read-back helper is documented as the binary-safe
counterpart precisely because NUL bytes, sign overpunch and the absence of a trailing
newline are "something a text decode would corrupt".

Assumptions: decoding a whole record through a text codec routes sign bytes, packed
nibbles and embedded low values through a character decoder. The output is not
garbage — it is data that looks almost right, which is the worst available failure mode
because nothing raises and no test that only checks row counts will see it.

**EBCDIC datasets have no line terminators.** They are fixed-length blocked records,
so a reader slices by record length and must never split on newlines. This is what
makes the exact-division check in [§6.1](#61-the-eleven-datasets) meaningful: the byte
size *is* the record count times the record length, with nothing in between. The
`app/data/EBCDIC` directory holds thirteen binary files — twelve `.PS` datasets and one
single-record `.PS.INIT` primer for the daily transaction file — and every one of the
twelve divides exactly.

**The boundary is exactly one module: `copybook/ebcdic_codec.py`.** Nothing else in
the package decodes EBCDIC. Trade-offs: routing every decode through one module means
a reader cannot take a shortcut for a field it believes is plain text, and the cost is
one extra call per field. What it buys is that there is a single place to review, a
single place to test against the known-answer vectors, and no second implementation to
drift.

`ebcdic==2.0.1` is in the runtime closure for a reason that looks removable and is
not. Assumptions: nothing imports a symbol from it — it registers codecs as an import
side effect, so both a linter and a person tidying unused imports will read it as
dead. What it registers is the wider EBCDIC code-page family around cp037, which
CPython does not ship. Note what that means precisely: cp037 itself *is* a standard
library codec, so this pin is not what makes today's extracts decode; it is what lets
an extract in a sibling code page decode through the identical per-field path by
configuration alone, with no edit to the codec module. Dropping it breaks nothing
visible until the first non-cp037 dataset arrives.

---

## 8. Loading

`loaders/aurora.py` is the `IDCAMS REPRO` equivalent: it bulk-loads one decoded
record set into the single schema that owns it, through `psycopg`'s server-side
`COPY`. Ten jobs in the baseline are its ancestors, one per dataset —
[`ACCTFILE.jcl`](../app/jcl/ACCTFILE.jcl),
[`CARDFILE.jcl`](../app/jcl/CARDFILE.jcl),
[`XREFFILE.jcl`](../app/jcl/XREFFILE.jcl),
[`CUSTFILE.jcl`](../app/jcl/CUSTFILE.jcl),
[`DISCGRP.jcl`](../app/jcl/DISCGRP.jcl),
[`TCATBALF.jcl`](../app/jcl/TCATBALF.jcl),
[`TRANTYPE.jcl`](../app/jcl/TRANTYPE.jcl),
[`TRANCATG.jcl`](../app/jcl/TRANCATG.jcl),
[`DUSRSECJ.jcl`](../app/jcl/DUSRSECJ.jcl) and
[`TRANFILE.jcl`](../app/jcl/TRANFILE.jcl).

All ten follow one three-step shape, verified in `ACCTFILE.jcl`, and step 1 comes in
two variants that are counted rather than generalised:

1. `DELETE ... CLUSTER`, followed by a condition-code reset so a first run against an
   empty catalogue is not a failure. **Five jobs guard the reset** —
   `ACCTFILE`, `CARDFILE`, `CUSTFILE`, `XREFFILE` and `TRANFILE` write
   `IF MAXCC LE 08 THEN SET MAXCC = 0`, which forgives a not-found delete but lets a
   severe failure of 12 or higher propagate. **The other five reset unconditionally** —
   `DISCGRP`, `TCATBALF`, `TRANTYPE`, `TRANCATG` and `DUSRSECJ` write a bare
   `SET MAXCC = 0`, which clears *any* delete failure including a severe one. The
   distinction is recorded because it is a real difference in error tolerance, not a
   formatting variation, and because the ETL reproduces neither form: see the note on
   step 1 below;
2. `DEFINE CLUSTER` with `KEYS(11 0) RECORDSIZE(300 300) INDEXED` — the operands that
   are this package's third independent source for the record and key lengths in
   [§6.1](#61-the-eleven-datasets); and
3. `REPRO INFILE(ACCTDATA) OUTFILE(ACCTVSAM)` — the flat `.PS` image copied into the
   key-sequenced dataset.

Step 2 becomes the table and index definitions in each owning service's
`V1__<schema>.sql`, and step 3 becomes the bulk copy. **Step 1 has no equivalent, and
the reason is worth stating rather than leaving as a gap:** the delete-and-reset
existed — in both of its variants — because a VSAM load had no transaction to roll
back to, so destroying and redefining the cluster was the only way to guarantee the
target was clean. That is also why the difference in error tolerance between the two
variants does not have to be resolved here: neither is reproduced. The loader writes
inside one transaction instead, so a load that fails leaves the previous contents
exactly as they were — without any destructive step to get wrong.

**`IDCAMS BLDINDEX` is retired, not ported.** Assumptions: PostgreSQL maintains an
index transactionally as rows are inserted, so a separate index-build step has no
target equivalent and its absence is a documented retirement rather than an omission.
Alternatives Considered: emitting a post-load `REINDEX` as a stand-in was considered
and rejected — it would rebuild an index that is already correct, take a lock the load
does not need, and imply to a reader that the index was somehow incomplete after the
copy.

### 8.1 The `usrsec` security correction

[`DUSRSECJ.jcl`](../app/jcl/DUSRSECJ.jcl) builds the security file from in-stream
data, and the ten user records are literally present in that job — each one carrying a
plaintext password, matching `SEC-USR-PWD PIC X(08)` at line 21 of
[`CSUSR01Y`](../app/cpy/CSUSR01Y.cpy). The target `auth.users` table has **no password
column** at all.

The `usrsec` reader therefore **reads the whole record and drops the password field on
the way out.** The field must be declared in the layout — omitting it would move every
field after it — so it is declared and flagged sensitive, and the loader simply never
maps it to a column. Refactoring Rationale: carrying the field forward into any
column, hash or shadow table would reproduce in the target the exact defect the
migration exists to correct, and would do so in a datastore with a far larger
audience than a mainframe VSAM file. Identity moves to the managed user pool instead,
and credential recovery becomes an identity-provider reset. **No artifact in this
package reproduces those credentials, and no example security record appears in this
document.**

### 8.2 Sensitive fields in diagnostic output

The layout descriptors flag the fields that must never appear whole in output:
primary account number, card verification value, embossed and cardholder names,
national identifier, government-issued identifier, date of birth, telephone numbers
and the electronic-funds account identifier. Assumptions: a verification failure has
to show *where* two records differ in order to be actionable, and it must do that
without emitting a complete cardholder identity or payment number — so the masking is
field-aware rather than all-or-nothing, and it is keyed by `CARDDEMO_MASK_HMAC_KEY`
so the same value masks consistently within a run and is not reversible across runs.
The ETL's own output follows the same discipline the reference codec already models.

---

## 9. Staging dataset generations to S3

A staged generation is written beneath a fixed prefix shape, and only this shape:

```text
s3://carddemo-datasets-<env>/<domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/
```

`dt=` carries the injected business date and `gen=` a four-digit logical generation
number. A baseline `(+1)` reference becomes a new prefix; a `(0)` reference resolves
to the newest valid prefix in the family. The bucket is versioned and lifecycle keeps
five newer noncurrent versions of any one key.

Trade-offs: bucket versioning and the writer's retention are two different layers and
are not interchangeable. Lifecycle counts *versions of one key*; it cannot count
distinct current keys beneath `gen=0001/`, `gen=0002/` and so on, because separate
generation prefixes are separate keys rather than revisions of one. So
[`s3_stage.py`](src/carddemo_migration/loaders/s3_stage.py) enforces the logical
generation count itself — ordering prefixes by business date then generation, keeping
the newest configured number and permanently deleting every object version and delete
marker beneath the prefixes that roll off. With the count at five, staging a sixth
logical generation scratches the oldest complete prefix, which is the direct analogue
of `LIMIT(5) SCRATCH`.

### 9.1 There are ten generation families, not six

This was confirmed by reading all three authoritative `DEFINE` blocks. Six sit in one
job that looks complete on its own — it is headed as the definitions needed by the
project and defines six bases in a single step — and the remaining four sit elsewhere.

| # | Generation base | Defined at | Domain |
|---|---|---|---|
| 1 | `TRANSACT.BKUP` | [`DEFGDGB.jcl`](../app/jcl/DEFGDGB.jcl) L25 | `ledger` |
| 2 | `TRANSACT.DALY` | [`DEFGDGB.jcl`](../app/jcl/DEFGDGB.jcl) L31 | `ledger` |
| 3 | `TRANREPT` | [`DEFGDGB.jcl`](../app/jcl/DEFGDGB.jcl) L37 | `reporting` |
| 4 | `TCATBALF.BKUP` | [`DEFGDGB.jcl`](../app/jcl/DEFGDGB.jcl) L43 | `ledger` |
| 5 | `SYSTRAN` | [`DEFGDGB.jcl`](../app/jcl/DEFGDGB.jcl) L49 | `ledger` |
| 6 | `TRANSACT.COMBINED` | [`DEFGDGB.jcl`](../app/jcl/DEFGDGB.jcl) L55 | `ledger` |
| 7 | `TRANTYPE.BKUP` | [`DEFGDGD.jcl`](../app/jcl/DEFGDGD.jcl) L28 | `reference` |
| 8 | `TRANCATG.PS.BKUP` | [`DEFGDGD.jcl`](../app/jcl/DEFGDGD.jcl) L51 | `reference` |
| 9 | `DISCGRP.BKUP` | [`DEFGDGD.jcl`](../app/jcl/DEFGDGD.jcl) L74 | `reference` |
| 10 | `DALYREJS` | [`DALYREJS.jcl`](../app/jcl/DALYREJS.jcl) L25 | `ledger` |

Six `ledger` plus three `reference` plus one `reporting` is ten, written out so the
count can be added up rather than trusted. Every one of the ten is declared with
`LIMIT(5)` **and** `SCRATCH` on the two lines following its `NAME` operand.

```bash
# WHAT: count the retention declarations in the three authoritative definition blocks.
# WHY : Assumptions: IDCAMS continues a statement with a trailing hyphen, so the DEFINE
#       verb and its NAME operand sit on different lines -- a single-line grep for the
#       verb returns the verb without the name. Counting LIMIT(5) instead pairs one hit
#       with one base, and the three counts must read 6, 3 and 1.
grep -c 'LIMIT(5)' app/jcl/DEFGDGB.jcl app/jcl/DEFGDGD.jcl app/jcl/DALYREJS.jcl
```

Two further findings keep the count honest. An exhaustive search for
`DEFINE GENERATIONDATAGROUP` matches **four** files and yields **eleven** statements
over **ten distinct base names**, because [`REPTFILE.jcl`](../app/jcl/REPTFILE.jcl)
L25–L28 defines `AWS.M2.CARDDEMO.TRANREPT` a second time with `LIMIT(10)` and no
`SCRATCH` — the base names are still ten, `TRANREPT` simply has two competing
definitions, and the three blocks in the table are the ones treated as authoritative
so five is applied uniformly. Separately, the plain-text and HTML customer statements
are **non-generation** artifacts: no generation base exists for either, so neither may
be counted as an eleventh or twelfth family.

Assumptions: [`infra/modules/s3-datasets`](../infra/modules/s3-datasets/README.md)
provisions prefixes and lifecycle configuration for exactly these ten, and validates
its own key set against them, so the ETL and the infrastructure cannot disagree about
the inventory. Provisioning six would not fail anything — the four affected steps would
still write their objects, into prefixes carrying no retention contract, and
generations would accumulate without limit. The reject stream is one of the four, and
it is the audit trail of every transaction the chain declined to post.

---

## 10. Verification

The governing requirement is that a load is not evidence of anything until it has been
verified three ways: **row counts per dataset, record checksums, and money-total parity
against the source files.** Verification is a first-class deliverable of this package
rather than an afterthought in a script, precisely because a load that reported success
without a money-total check proves nothing about the money.

| Pass | Module | Paired SQL | What it proves | What it cannot prove |
|---|---|---|---|---|
| 1. Row counts | `verify/row_counts.py` | `sql/verify/row_counts.sql` | Every source record produced exactly one row, and none was dropped or duplicated | Nothing about field content. A sign-overpunch or packed-nibble decode defect passes this pass untouched, because the row is present and well formed |
| 2. Record checksums | `verify/checksum.py` | — | Byte-level fidelity of the reconstructed record against the source image, field by field, so drift is localised to a named record | Nothing about a defect that is symmetric between decode and re-encode; and nothing about a field the layout omits |
| 3. Money-total parity | `verify/money_parity.py` | `sql/verify/money_totals.sql` | That the money agrees. **This is the only pass that catches a systematically mis-decoded sign** — a whole column of negatives read as positives changes no row count and can survive a symmetric round trip, but it cannot survive a total | Nothing about which individual rows are wrong when a total differs; pass 2 localises that |

**None of the three may be skipped or weakened.** Assumptions: each one is blind to
the failure mode the next one catches, which is why the set is three rather than one,
and why `verify-all` exists as a single indivisible invocation — so that "verified"
cannot come to mean "two of the three passed".

The two existing verification scripts,
[`alternate_database_users.sql`](sql/verify/alternate_database_users.sql) and
[`reporting_view_privileges.sql`](sql/verify/reporting_view_privileges.sql), are a
different kind of check and are not substitutes for these three: they prove the
security boundary rather than the data. Both follow the convention that a query
returns rows **only** when a property is broken, so a run whose every result set is
empty is a pass and no result needs interpreting.

### 10.1 Checksums and the wall clock

A checksum computed over whole records including the processing timestamp is not
deterministic, and the reason is a two-timestamp asymmetry that is easy to miss.
`TRAN-PROC-TS` / `DALYTRAN-PROC-TS` sits at offset 304 and is 26 characters wide, and
it is stamped with the **wall-clock** posting time. `ORIG-TS` sits at offset 278, is
the same width, and carries the **deterministic** originating timestamp copied from
the input transaction — the reference codec normalises only the former and deliberately
preserves the latter, citing a seed value to show the originating stamp does not vary
between runs.

The checksum pass therefore **excludes the processing timestamp from the checksummed
span** — the layout descriptors already mark exactly that field, so the pass reads the
mark rather than carrying its own list of offsets. Alternatives Considered: leaving
the field in and comparing checksums only within a single run. Rejected because it
makes the pass unable to compare a load against a source image captured at a different
time, which is the entire use it was built for. Assumptions: `ORIG-TS` is **not**
excluded — blanking it would discard business data the comparison has to verify and
would shorten the effectively compared record. One layout is the exception and is
named separately for it: the interest-generated transaction, where the batch program
writes the run clock into **both** stamps, so both are marked there.

---

## 11. Schema and role bootstrap

[`sql/V0__schemas_and_roles.sql`](sql/V0__schemas_and_roles.sql) creates the objects
every other database artifact presumes already exist: the **eight** bounded-context
schemas — `auth`, `account`, `card`, `ledger`, `reference`, `batch`, `authorization`
and `reporting` — one login role per context, and the complete cross-schema privilege
graph. Seven of the eight schemas are owned by their own login role; `reporting` owns
no table of its own and its schema is owned by a dedicated role that holds no
credential, so the reporting context reads through cross-schema views and holds
`SELECT` and nothing else.

**It must run BEFORE any per-service migration.** Each service's `V1__<schema>.sql`
creates only tables inside a schema this script already made, owned by a role this
script already made, and issues no schema, role or grant statement of its own.
Assumptions: a grant that is missing here is missing from the whole system and shows
up as a permission error inside a running service rather than as a build failure —
which is why the grants have to name the exact schema and table names those migrations
define, and why the script is idempotent and documented as safe to re-run.

Two consequences of that ordering are worth stating because they look like defects:

- The one grant that names a table rather than a schema can only be issued **after**
  that table exists, so on a first run the script reports it as outstanding and
  applies it on a later run. Trade-offs: reporting an outstanding grant is the right
  direction to fail in, because an outstanding grant is named in the output whereas an
  over-broad one is invisible.
- The eight login roles are created with **no** credential clause, because a
  credential written into a committed SQL file is the defect this migration is
  correcting. `apply-credentials` ([§5.2](#52-subcommands-and-their-arguments)) is the
  delivered mechanism that makes them able to authenticate, and it must run
  immediately after this script and before any loader.

### 11.1 The batch role's cross-schema grant

This is the migration's **one documented exception** to database-per-service purity,
and it carries its justification at the grant site in the DDL as well as here. The
posting program commits three writes — the transaction, the transaction category
balance and the account — as a **single unit of work**. The grant is what keeps that
commit atomic in the target.

Alternatives Considered: a transactional-outbox-plus-compensating-reversal design, or
a saga across the two services. Both were rejected for the same specific reason —
they would introduce observable partial-posting states that do not exist in the
baseline, such as a posted transaction with an unposted balance, and the golden
masters would correctly flag those as a parity failure. Splitting an atomic commit is
not a neutral refactor when the commit's atomicity is itself observable behaviour.

The grant is also narrower than "write on two schemas". The delivered script gives the
batch role `SELECT`, `INSERT` and `UPDATE` on the `ledger` tables and their sequences;
`SELECT` on `card` and `reference`; `SELECT` across `account`; and `UPDATE` on the
single named table `account.accounts` and no other. Refactoring Rationale: granting
update through default privileges instead would cover every table the account context
ever creates, now and in future — including the customer row that carries the
encrypted national and government-issued identifiers, which no batch step has any
reason to modify — and a ninth table added later would become writable the moment it
was created, with nothing in the script changing to say so. Two write sites exist in
the whole nightly chain against that schema, both rewriting an account master that
already exists, so one named table is the exact privilege.

---

## 12. Tests

```bash
# WHAT: run this package's own tests.
# WHY : Assumptions: pyproject.toml sets testpaths, python_files, --strict-markers,
#       --strict-config and xfail_strict, so the invocation carries no configuration of
#       its own -- passing a marker expression or an extra path here would bypass a
#       setting a reader cannot see in the command. The package must already be
#       installed (section 4); otherwise collection fails at the first import.
source .venv/bin/activate
python -m pytest -v --tb=short data-migration/tests
```

The same run in the form continuous integration uses, which additionally emits a
JUnit report:

```bash
# WHAT: the continuous-integration invocation, from the repository root.
# WHY : Trade-offs: the report path is outside data-migration so the upload step
#       collects it without walking a package directory. The two invocations select the
#       same tests; only the reporting differs, so a local pass and a pipeline pass mean
#       the same thing.
mkdir -p data-migration-reports
python -m pytest data-migration/tests --junitxml=data-migration-reports/pytest.xml
```

The delivered modules are [`test_cli.py`](tests/test_cli.py),
[`test_config_name_contract.py`](tests/test_config_name_contract.py),
[`test_database_trust.py`](tests/test_database_trust.py),
[`test_reporting_views.py`](tests/test_reporting_views.py) and
[`test_s3_stage.py`](tests/test_s3_stage.py) — ninety-nine tests in total. The
contracted modules are `conftest.py`, `test_zoned.py`, `test_readers.py`,
`test_loaders.py` and `test_verify.py`.

Assumptions: `test_cli.py` asserts the ABSENCE of each contracted subcommand as well as
the presence of each registered one. A test that only checked the three that work would
pass equally well if a fourth were added that could not, which is the regression the
absence assertions exist to catch.

### 12.1 Test vectors are reused, not authored

The codec and reader tests take their vectors from the existing suite's fixture
corpus. Alternatives Considered: authoring fresh fixtures for this package, which
would have been quicker to write and is rejected — a fresh vector proves only that this
package agrees with itself. Reusing the corpus is what proves the Python codecs agree
with the COBOL programs that produced the bytes **and** with the Java shared-kernel
codecs that read the same layouts, which is the property that matters, because a
cross-language disagreement returns well-formed wrong numbers rather than an error.

The corpus is **99 files** under `tests/fixtures/**` — 78 fixed-width data files and 21
scenario READMEs — across six domains and twenty scenario directories:

| Domain | Scenarios |
|---|---|
| `export/` | `happy_path` |
| `interest/` | `happy_path`, `default_fallback`, `zero_balance` |
| `posting/` | `happy_path`, `zero_balance`, `empty_input`, `boundary_exact_limit`, `boundary_expiry_equal`, `reject_100_card_missing`, `reject_101_acct_missing`, `reject_102_overlimit`, `reject_103_expired` |
| `prepost/` | `happy_path`, `unmatched_account`, `unmatched_card` |
| `provisioning/` | `happy_path`, `empty_input` |
| `statement/` | `happy_path`, `empty_input` |

**These are read as vectors and are never written to.** Three details of the corpus
matter to a reader:

- **Filenames differ from the `app/data` naming in two domains.** `export/` uses
  `trandata.txt`; `statement/` uses `acctfile.txt`, `custfile.txt`, `trnxfile.txt` and
  `xreffile.txt`. A reader that resolves a fixture by the seed's name finds nothing in
  those two domains.
- **Seven fixtures are genuinely zero-byte.** They express a legitimately empty
  dataset that yields zero rows — not a malformed record, and not a blank line.
  Assumptions: an empty file iterates zero records, so a zero-byte fixture never
  reaches the width check; an empty *line* inside a non-empty file is corrupt input and
  is still rejected.
- **Every fixture copy of the cross-reference record is the full 50 bytes**, unlike the
  36-byte shipped seed. Both shapes must therefore load, which is why the padding rule
  in [§6.2](#62-six-facts-a-reader-would-otherwise-rediscover-the-hard-way) exists
  rather than one shape being declared canonical.

Three layouts have **no** ready fixture round-trip vector — `CVTRA03Y`, `CVTRA04Y` and
`CSUSR01Y` — and the layout registry reports that explicitly rather than leaving it to
be discovered. Their tests are built from the copybook declarations directly. Trade-offs:
a hand-built vector is weaker evidence than a byte taken from a real run, so those three
carry an extra obligation to state the picture clause and offset the vector was derived
from, at the point the vector is declared.

The test suite's exit status is **binary**, on the same terms as
[§5.6](#56-exit-semantics): `pytest` either reports success or the run failed. The
graded warn tier belongs to the COBOL parity oracle and is not inherited here.

---

## 13. Lint and the documentation gate

```bash
# WHAT: run the lint and docstring gate over this package.
# WHY : Assumptions: ruff reads data-migration/pyproject.toml, so the rule selection,
#       the line length and the relative-import ban all come from the committed
#       configuration rather than from this command line. Both spellings below select
#       the same files; the second is the form continuous integration uses, from inside
#       this directory.
ruff check data-migration
(cd data-migration && ruff check .)
```

[`pyproject.toml`](pyproject.toml) selects `["D", "E", "W", "F", "I", "TID252"]` with
an empty `ignore` list. The **`D`** entry is the pydocstyle family, and it is Rule 1's
mechanical enforcement for Python in this repository: it requires that every module,
class and function carries a docstring, and it checks that docstring's formatting.

**What the gate cannot decide, stated because the gap is wide.** Assumptions: no `D`
rule cross-checks a docstring against the signature it documents, so a one-line
docstring on a five-parameter function passes every `D` rule while still failing the
obligation. Completeness of the purpose, parameter, return and raised-exception
content is therefore a **required human-review check** on every change here, not
something the gate covers. A green `ruff check` is evidence about the gate's coverage
and is not evidence of documentation compliance.

The authoritative, per-language convention with a worked example for each language is
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../docs/CODE_DOCUMENTATION_STANDARD.md), and
[`CONTRIBUTING.md`](../CONTRIBUTING.md) states it as a contribution requirement. It is
deliberately **not** restated here. Trade-offs: a second copy of a convention drifts
from the first, and the drift stays invisible until two reviewers cite different
versions of it at the same change.

The shape the delivered modules use, so an author matching an existing file has
something concrete to match: a module docstring opening with a `Purpose` section and
closing with a `Design decisions (WHY)` section; class and function docstrings stating
the purpose, then every parameter with its type, then the return value, then anything
raised. Rationale comments sit adjacent to the code they explain and are labelled with
one of the four categories the rule names.

---

## 14. Container image

```bash
# WHAT: build the ETL image from this directory as the build context.
# WHY : Assumptions: the build context is data-migration, not the repository root, so
#       the image physically cannot copy anything from app/, tests/, scripts/ or
#       samples/ -- the boundary is enforced by the context rather than by a
#       .dockerignore a future edit could relax.
docker build -t carddemo/data-migration:local data-migration
```

```bash
# WHAT: show the image's command-line help.
# WHY : Assumptions: ENTRYPOINT is `python -m carddemo_migration.cli` and CMD is
#       `--help`, so a bare run prints usage and any argument list appended here is
#       read as a subcommand and its options -- which is exactly how the batch
#       orchestrator invokes it, as a container command override.
docker run --rm carddemo/data-migration:local
docker run --rm carddemo/data-migration:local list-datasets
```

The base image is `python:3.13.14-slim-trixie`, pinned by digest in
[`Dockerfile`](Dockerfile). Assumptions: the tag is chosen for interpreter parity with
the parity oracle, so the ETL and the harness that validates it run the same decimal
and codec implementations rather than two that could round differently. **The parity
claim is at the 3.13 minor-series level only** — the existing suite was validated on
3.13.7 and this image runs 3.13.14 — and it must not be read as patch-level equality,
nor "corrected" in either direction to make the two numbers agree.

The image runs as an unprivileged user, uid and gid `10001`, declared after every file
is in place so nothing in the image is owned by the account that executes it. Its
health check imports the package rather than calling a subcommand, so it verifies the
installation without needing a credential or a reachable database.

**Seed extracts are read at run time from object storage and are not baked into the
image.** Trade-offs: reading at run time means a staging step must have run first, and
the image cannot be exercised end to end in isolation. What it buys is that the image
carries no data — so it needs no rebuild when an extract changes, it cannot go stale
against the source, and a published layer can never contain cardholder data.

> **Note.** Both `docker run` commands above work in this checkout. The bare run
> resolves `ENTRYPOINT` plus `CMD` to `python -m carddemo_migration.cli --help`, which
> prints usage and exits `0`; the second appends `list-datasets` as a command override
> and prints the record-layout contract. Refactoring Rationale: this note previously
> stated that both commands exit non-zero with `No module named
> carddemo_migration.cli`, which was true while `cli.py` was absent and is now false.
> It is restated rather than deleted because a reader who had learned the old behaviour
> needs to be told it changed — and because the help path exiting `0` is a requirement,
> not an accident: a container whose default command exits non-zero looks like a broken
> image to every platform that runs it once as a check.
>
> Assumptions: a subcommand [§5.2](#52-subcommands-and-their-arguments) marks
> **contracted** is refused by the parser as a usage error (exit `2`), so
> `docker run ... load-dataset` reports an unrecognised subcommand rather than starting
> a load it cannot finish. A source-record cutover therefore still cannot be claimed
> from this image.

---

## 15. Design decisions (WHY)

Eleven rulings in this package are not self-evident from the code, and a reasonable
alternative exists for every one of them. Each is recorded here with the specific thing
that goes wrong under the alternative, rather than with a general preference.

**1. EBCDIC is decoded per fixed-width field, never per record.** Rejected
alternative: opening the dataset as text and decoding the whole record in one call,
which is shorter and is what a reader reaches for first. A record contains sign
overpunch bytes, packed-decimal nibbles and embedded low values, and a character
decoder rewrites each of them into whatever character it maps to — so a negative
balance loses the sign that rode its last digit and comes back positive, a packed
nibble pair comes back as one substituted character instead of two digits, and the
record still parses to the declared width either way. The failure is therefore silent:
no exception, no width error, and a row count that agrees. That is why the existing
suite treats these extracts as opaque binary and transcodes none of them
([§7](#7-ebcdic-handling)).

**2. Money is `Decimal`, never `float`.** Rejected alternative: `float`, the default
Python numeric type, which the reference codec refuses outright rather than converting.
A binary float cannot represent ten cents exactly, so a column total accumulated in one
diverges from the total the COBOL computed — silently, in the last cents, and by an
amount that grows with the row count. Since money-total parity
([§10](#10-verification)) is one of the three mandatory passes, using `float` would
also make the verification pass unable to distinguish its own arithmetic error from a
real load defect.

**3. Record offsets are single-sourced in `layouts.py`.** Rejected alternative: each
reader declaring the offsets it needs beside its own parsing code, which reads more
locally. With offsets in two places there are two things to change and two things to
review, so the two copies of one layout drift — and a field that lands one byte over
still returns a value. Single-sourcing is the Python analogue of compiling every COBOL
program against one copybook include path, which is how the baseline had this property
already ([§6.5](#65-offsets-are-single-sourced-in-one-module-across-two-languages)).

**4. Verification is three passes, not one.** Rejected alternative: row counts alone,
which is the cheapest check and the one most often mistaken for sufficient. A row count
proves only that a row arrived: a sign-overpunch or packed-nibble decode defect leaves
the count identical, and so does a whole column of negatives read as positives.
Checksums localise byte-level drift to a named record; money-total parity is the only
one of the three that catches a systematically mis-decoded sign. Each pass is blind to
what the next one catches, which is why `verify-all` runs them as one indivisible
invocation.

**5. Test vectors are reused from the existing suite's fixtures.** Rejected
alternative: authoring fresh fixtures for this package. A fresh vector proves only
self-consistency. Reusing bytes the COBOL programs actually produced is what proves
that this package, the COBOL baseline and the Java shared-kernel codecs all decode the
same layout the same way — and a cross-language disagreement returns well-formed wrong
numbers rather than an error, so it has to be caught by shared vectors or not at all
([§12.1](#121-test-vectors-are-reused-not-authored)).

**6. The image base is `python:3.13.14-slim-trixie`.** Rejected alternative: a newer
minor series. The interpreter has to match the one the parity oracle runs on at the
**3.13 minor** level, because the oracle is what validates this package's codecs, and a
different minor series moves decimal and codec behaviour that the comparison depends
on. The claim is deliberately minor-level and not patch-level: the suite was validated
on 3.13.7, this image runs 3.13.14, and neither number is adjusted to make them agree.
Also rejected: an Alpine variant of the same interpreter, which does exist and is
smaller. It is not a drop-in — the runtime stage installs the `libpq5` package with
`apt-get`, and `psycopg` resolves that client library at run time, so an Alpine base
would need a different package manager, a different package name and a musl-linked
client library that is not the one the rest of the migration talks to the database with
([§14](#14-container-image)).

**7. `boto3`, `botocore`, `pytest` and `coverage` are pinned to the oracle's exact
versions.** Rejected alternative: a second, newer dependency set for this package.
Zero version drift may be introduced into `tests/**`, because that suite is the
functional-parity oracle; if the oracle cannot move, matching it is what makes a
verification discrepancy attributable. With two different sets, every disagreement
between the ETL's output and the harness's reading of it has a library-version
explanation available, and that explanation is almost always wrong and always
expensive to eliminate ([§3](#3-prerequisites)).

**8. The batch role holds a narrow cross-schema grant.** Rejected alternative: a saga,
or a transactional outbox with compensating reversals, which is what database-per-service
purity would require. The posting run commits the transaction, the category balance and
the account as one unit of work; splitting it introduces observable partial-posting
states — a posted transaction with an unposted balance — that do not exist in the
baseline, and the golden masters would correctly report those as a parity failure. The
grant is also narrower than the alternative implies: write on the `ledger` tables, and
`UPDATE` on the single named table `account.accounts`
([§11.1](#111-the-batch-roles-cross-schema-grant)).

**9. `IDCAMS BLDINDEX` is retired rather than ported.** Rejected alternative: a
post-load index-build or `REINDEX` step standing in for it. PostgreSQL maintains an
index transactionally as rows are inserted, so the index is already correct when the
load commits; a stand-in step would take a lock the load does not need and would tell a
reader that the index was somehow incomplete after the copy. Recording the retirement
is what stops the absence reading as an oversight ([§8](#8-loading)).

**10. A short seed row is right-padded; an over-long row is refused.** Rejected
alternative: strict rejection of any row that is not exactly the declared width, which
is what the reference codec's `_validated_record` does and which would make the shipped
36-byte cross-reference seed unloadable. The two directions are not symmetric: the
bytes a short ASCII row omits are exactly the trailing `FILLER`, which is dropped
anyway, so padding on the right cannot move a field that exists — whereas an over-long
row means the offsets have already moved and no amount of trimming puts them back
([§6.2](#62-six-facts-a-reader-would-otherwise-rediscover-the-hard-way)).

**11. The processing timestamp is excluded from record checksums; the originating
timestamp is not.** Rejected alternative: checksumming the whole record unmodified.
`PROC-TS` at offset 304 is stamped from the wall clock, so an unmodified checksum
changes on every run and the pass can never compare a load against a source image
captured at a different time — which is the only comparison it was built to make.
`ORIG-TS` at offset 278 is deterministic business data copied from the input
transaction, so excluding it too would discard content the comparison has to verify and
would shorten the effectively compared record
([§10.1](#101-checksums-and-the-wall-clock)).

---

## 16. Prohibitions and boundaries

These are boundaries, not preferences, and each carries its reason in one line here.
Trade-offs: the reasons are compressed in this list and stated in full where each
boundary is introduced above, because a contributor who needs to check a boundary is
rarely the reader who has just read the section that established it — and repeating each
argument twice at full length is how two copies of one rule come to disagree.

1. **Nothing under `app/**` is modified — `app/data/**` included.** The whole COBOL,
   copybook, JCL, CSD and seed-extract baseline is reference-only. The extracts are read
   as input; they are never rewritten in place, re-encoded, or normalised on disk. The
   baseline is the behavioural oracle, and it has to stay byte-identical for the
   comparison to mean anything.
2. **Nothing under `tests/**` or `scripts/**` is modified, re-pinned or extended.**
   `tests/helpers/record_codec.py` is read as reference and `tests/fixtures/**` as test
   vectors, and that is the whole of the relationship. Zero version drift into that
   suite is what allows it to keep serving as the functional-parity oracle.
3. **EBCDIC is never decoded per record.** One module owns the decode, and it decodes
   per fixed-width field from a binary-mode read.
4. **No `float` anywhere in the money path.** `Decimal` in Python, `NUMERIC(p,2)` in
   SQL, a JSON string on any wire.
5. **No relative imports and no `sys.path` manipulation.** Absolute, rooted at
   `carddemo_migration`, and enforced by `TID252` with `ban-relative-imports = "all"`.
6. **No invented dataset, copybook or record-length value.** The eleven-row contract in
   [§6.1](#61-the-eleven-datasets) is fixed by the baseline and verified three ways; a
   new value has to come from the copybook, the dataset size and the `IDCAMS` operands
   agreeing.
7. **Ten generation families, not six.** Six is what one job suggests on its own, and
   provisioning six loses four retention contracts without failing anything.
8. **No secret, credential, account identifier, endpoint or connection string in
   source.** Every runtime value is resolved from Parameter Store and Secrets Manager
   when a command runs; the environment carries names only.
9. **No temporal estimate and no schedule language.** This document states what is
   delivered and what is contracted, and nothing about when.
10. **None of the three verification passes may be skipped or weakened.** A load
    reported as verified without all three is not verified.

---

## 17. Further reading

Each neighbouring concern is owned elsewhere, and is linked rather than summarised.
Trade-offs: a link costs the reader a hop, whereas a summary here would become a second
account of something this file does not own — and the second account is the one that
goes stale, because nothing updates it when its owner changes. Every entry below is
therefore a pointer, not a précis:

| Topic | Owner |
|---|---|
| Build, deploy, run, migrate, validate, roll back | [`MIGRATION_README.md`](../MIGRATION_README.md) |
| Operating the migration, with the cutover gate | [`docs/runbooks/data-migration.md`](../docs/runbooks/data-migration.md) |
| Column-level copybook-to-table mapping | [`docs/architecture/data-model-and-schema-mapping.md`](../docs/architecture/data-model-and-schema-mapping.md) |
| The nightly chain, condition codes and generation semantics | [`docs/architecture/batch-orchestration.md`](../docs/architecture/batch-orchestration.md) |
| Why the datastore targets are what they are | [`docs/adr/ADR-003-datastore-targets.md`](../docs/adr/ADR-003-datastore-targets.md) |
| Why the batch chain is orchestrated the way it is | [`docs/adr/ADR-005-batch-orchestration.md`](../docs/adr/ADR-005-batch-orchestration.md) |
| Identity, encryption and the privilege boundaries | [`docs/architecture/security-and-identity.md`](../docs/architecture/security-and-identity.md) |
| Documented divergences from baseline behaviour | [`docs/architecture/cobol-to-service-traceability.md`](../docs/architecture/cobol-to-service-traceability.md) |
| The per-language documentation convention | [`docs/CODE_DOCUMENTATION_STANDARD.md`](../docs/CODE_DOCUMENTATION_STANDARD.md) |
| Contribution requirements | [`CONTRIBUTING.md`](../CONTRIBUTING.md) |
| The parity oracle: layers, markers, return codes, business rules | [`tests/README.md`](../tests/README.md) |
| The dataset bucket, its prefixes and its lifecycle | [`infra/modules/s3-datasets/README.md`](../infra/modules/s3-datasets/README.md) |

<sub>Apache-2.0 · This package is additive. The COBOL baseline under `app/**` is
reference-only and is never modified; it remains the behavioural oracle against which
this migration's functional parity is verified.</sub>
