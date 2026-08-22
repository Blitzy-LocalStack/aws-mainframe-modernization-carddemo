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
   mandatory where they apply, and [§10](#10-verification) records the one place a
   pass does not yet apply to every record.
4. **Switch.** Traffic moves only after the verification gate passes. The gate lives
   in [the data-migration runbook](../docs/runbooks/data-migration.md), not here.

Assumptions: the source extracts are files, never a live mainframe connection. The
package reads `.PS` and `.txt` images that already exist; it never dials into CICS,
VSAM, Db2 or IMS. That is what makes the deployment satisfy the migration's
"no manual mainframe dependency" constraint.

> **Note — delivery state.** This checkout delivers the configuration trust
> boundary, the normative layout catalogue, the zoned-decimal codec, the
> packed-decimal and binary codecs, the per-field EBCDIC decoder, the S3
> generation writer, the credential-application bootstrap, the schema/role DDL, the
> masked reporting views, the twelve fixed-width readers, the shared timestamp
> authority, the Aurora bulk loader with its protected-column ciphers, the
> three verification passes, the combined verification gate, and the command-line entry point
> carrying **all fourteen** subcommands — `list-datasets`, `decode-record`, `stage-dataset`,
> `refresh-dataset`, `apply-credentials`, `reconcile-sequences`, `refresh-card-identity`,
> `load-dataset`, `verify-row-counts`, `verify-checksum`, `verify-money-parity`,
> `verify-row-count-report`, `verify-money-total-report` and `verify-all`. **Nothing is contracted-but-unregistered any
> more**, so `--help` and [§5.2](#52-subcommands-and-their-arguments) agree one-for-one.
> Assumptions: `refresh-dataset` registers no new capability of its own; it composes the staging,
> loading, verification and reconciliation commands beside it into the one invocation the nightly
> seed-refresh state makes, so the roster gains a row without the package gaining a module.
>
> ⚠️ Refactoring Rationale: successive revisions of this note recorded a smaller roster and a
> different set of contracted commands, and each left behind its own count. Those counts are
> **removed rather than restated**, because a reader consulting this note wants to know what
> `--help` will list, and a superseded figure sitting beside the current one only makes them
> check. The figure to trust is the one above, and it is the one the parser produces: thirteen
> subcommands, nothing withheld. Trade-offs: the reasoning that closed the last gap is kept,
> because it explains a live interface rather than a past state. `verify-all` was held back on
> the ground that sequencing the three passes needs a dataset-to-source manifest this
> distribution does not carry — a real gap, now closed twice over, which is why the verb accepts
> **two** coverage sources rather than one. An operator may be told in, through `--manifest`;
> and with no manifest the seed registry in
> [`seed_datasets.py`](src/carddemo_migration/seed_datasets.py) IS the manifest — it names every
> dataset, its owning context, its prefix segments and its extract file, and an omitted `--source`
> resolves to the newest STAGED generation of that dataset. What the absence cost was worse than
> an unreachable capability, because the three passes remained individually reachable: a cutover
> could run two of them and read each command it managed to run as green, so "verified" meant
> whatever the operator happened to invoke. The chain's `VerifyMigration` state invokes exactly
> this verb, and it is the only edge into business processing.
>
> Refactoring Rationale: `decode-record` was added once the codec stack landed, because
> the three codecs were otherwise reachable only from a test. A codec with no caller is
> a codec whose offsets have never been walked against a real delivery, and the one
> operation the stack can perform without the readers or the loader is exactly the one
> worth exposing: decode a single record and show its fields. It is also the check that
> belongs BEFORE a load rather than after one -- it proves the delivered bytes match the
> declared geometry while nothing has been written yet.
> [§2](#2-directory-layout) marks each item and [§5.2](#52-subcommands-and-their-arguments)
> marks each subcommand. `python -m carddemo_migration.cli --help` and every one of the
> **thirteen** subcommands run against this checkout; a misspelled verb is refused as a
> usage error rather than failing part-way through.
>
> Assumptions: a source-record cutover from this checkout is now a matter of
> CREDENTIALS AND A CLUSTER rather than of missing code. The load and the three
> verification passes are implemented and tested, and `load-dataset` serves all ELEVEN
> loadable records — including `CUSTOMER` and `CARD`, whose tables declare ciphertext
> columns that this package now produces under the same envelope framing and the same
> key the owning service reads, as [§5.2](#52-subcommands-and-their-arguments)
> records, and `TRAN`, the transaction master, for which the seed corpus ships no extract
> and an absent one is a zero-row success rather than a failure. All three verification
> passes now serve every record they can be asked about — pass 1 and pass 2 all eleven
> loadable records, pass 3 the five that carry a money column
> ([§10](#10-verification)) — so the one thing a cutover claim must still account for is
> that nothing here has been exercised against a provisioned Aurora cluster.
>
> Refactoring Rationale: this paragraph recorded the checksum pass as serving three of
> the eleven records, which was accurate while its canonicaliser admitted only
> characters, an exact decimal and raw bytes. It is now layout-aware: an integer,
> a date, a timestamp, a UUID and a null each canonicalise under their own type tag, and
> an identifier read back as a number is rendered into the declared digit width the
> reader publishes. The eight records the pass could not serve were the eight carrying a
> `BIGINT`, `DATE`, `SMALLINT`, `TIMESTAMP` or `UUID` comparable column, and that is the
> defect that was fixed rather than a limit that was accepted.
>
> Refactoring Rationale: this paragraph recorded `CUSTOMER` and `CARD` as deliberately
> refused, which was accurate while the loader held no cipher for their `*_encrypted`
> columns. Leaving it would have told an integrator that two of the eight schemas
> could not be populated from here, which is the sort of claim that gets a second
> loader written elsewhere rather than checked.
>
> Refactoring Rationale: this note previously listed the packed and EBCDIC codecs as
> undelivered after both had landed, and later listed the readers, the loader and the
> verification passes the same way. A delivery inventory that overstates what is
> missing is as misleading as one that overstates what is present — an integrator
> reading it would have written a second copy of something that already exists, or
> concluded that a path they could see in the tree was not meant to be used. The
> inventory is measured rather than remembered: `src/carddemo_migration/` holds
> **thirty-seven** modules and `ruff check . --show-files` lists **seventy-seven**
> governed files, and [`pyproject.toml`](pyproject.toml) states the same two numbers so a
> disagreement between the two files is visible.
>
> ⚠️ Refactoring Rationale: these two figures have gone stale here, and disagreed with the
> ones in [`pyproject.toml`](pyproject.toml), on several separate occasions, always in the
> same direction — the file that CONFIGURES the gate was re-measured and the file a reader
> consults was not. Each occasion used to leave its own note naming the figures it
> superseded; those notes are **removed rather than stacked**, because a paragraph that
> recites three generations of wrong counts is harder to check against the tree than the two
> commands above, and a reader cannot tell which generation they are in. The asymmetry that
> makes the drift easy to reintroduce is the part worth keeping:
> `data-migration/tests/test_gate_inventory.py` reads the delimited region in
> `pyproject.toml` and compares it against the directory, so that file cannot go stale
> without failing a test, while this sentence can — which is exactly why the two are
> required to state the same numbers, and why the figures above are quoted with the commands
> that produce them.
>
> Assumptions: an unimplemented subcommand would be left OUT of the parser rather than
> registered and made to fail. A registered command that cannot work would be advertised
> by `--help`, an orchestrator author would wire a batch state to it, and the failure
> would then arrive in a deployment instead of at the point where the command was chosen.
> No such command remains — all **thirteen** are implemented — so the rule is recorded here
> for the next one rather than describing anything in this tree.
>
> Assumptions: the `--encoding` selector belongs to `load-dataset` and to the three
> per-dataset verification commands, and it is **derived, not defaulted, and not sniffed**.
> Given a registered dataset identifier — a seed token or the layout name of a registered
> dataset — `--source` and `--encoding` are both filled in after parsing from the seed
> registry, which names an EBCDIC `.PS` object for all eleven. Given a layout the registry
> does not carry, such as `REJECT` or `INTTRAN`, **both** are required and the refusal names
> them. The distinction matters because it is what makes the load reachable from an
> orchestrator holding one token per dataset, and the reason nothing is ever sniffed is the
> one [§6.2](#62-seven-facts-a-reader-would-otherwise-rediscover-the-hard-way) measures: an
> all-ASCII EBCDIC dataset sniffs as text and decodes to plausible wrong values.
>
> ⚠️ Refactoring Rationale: this note read that `--encoding` is "**required** on each of
> them rather than defaulted", which the parser contradicts — its default is `None` on all
> four commands. The claim was not merely imprecise: taken literally it invites an operator
> to pass `--encoding ascii` with no `--source`, which resolves the registered EBCDIC
> extract and declares it ASCII, and that combination either fails to read or loads
> plausible wrong values. Stating the derivation is what removes the invitation.

---

## 2. Directory layout

```text
data-migration/
├── README.md                     this file -- CLI contract, layout contract, WHY ledger
├── pyproject.toml                packaging, ruff and pytest configuration
├── requirements.txt              runtime closure, hash-locked (12 distributions)
├── requirements-dev.txt          the runtime closure plus ruff, pytest and coverage
├── requirements-build.txt        the PEP 517 build backend, installed and discarded
├── Dockerfile                    two-stage image; non-root; digest-pinned base
├── sql/
│   ├── V0__schemas_and_roles.sql       delivered -- 8 schemas, 1 role per context
│   ├── V1__reporting_views.sql         delivered -- masked cross-schema views
│   ├── V2__runtime_delete_grants.sql   delivered -- table-specific DELETE, 3 tables
│   ├── V3__verification_surfaces.sql   delivered -- aggregate-only verification views
│   └── verify/
│       ├── alternate_database_users.sql    delivered -- role-attribute ceiling
│       ├── reporting_view_privileges.sql   delivered -- masked-view privileges
│       ├── runtime_delete_grants.sql       delivered -- pairs with V2, fails on drift
│       ├── row_counts.sql                  delivered -- pairs with verify/row_counts.py
│       └── money_totals.sql                delivered -- pairs with verify/money_parity.py
├── src/carddemo_migration/
│   ├── __init__.py               delivered -- import and layering contract
│   ├── config.py                 delivered -- runtime settings, resolved when a command runs
│   ├── credentials.py            delivered -- applies each generated credential to its role
│   ├── role_credentials.py       delivered -- SCRAM verifier derivation and role bootstrap
│   ├── cli.py                    delivered -- the thirteen registered subcommands in section 5
│   ├── copybook/
│   │   ├── __init__.py           delivered -- makes the subpackage a regular package
│   │   ├── layouts.py            delivered -- offset, length and usage, declared ONCE
│   │   ├── zoned.py              delivered -- sign-overpunch decode and encode
│   │   ├── packed.py             delivered -- COMP-3 and COMP decode and encode
│   │   ├── ebcdic_codec.py       delivered -- cp037 decode, applied PER FIELD
│   │   └── timestamp.py          delivered -- the two admitted 26-character stamp forms
│   ├── readers/
│   │   ├── __init__.py           delivered -- makes the subpackage a regular package
│   │   ├── factory.py            delivered -- one layout descriptor, twelve bound readers
│   │   ├── account.py            delivered -- CVACT01Y, 300 bytes
│   │   ├── card.py               delivered -- CVACT02Y, 150 bytes; SUPPRESSES the CVV
│   │   ├── customer.py           delivered -- CVCUS01Y, 500 bytes
│   │   ├── xref.py               delivered -- CVACT03Y, 50 bytes
│   │   ├── transaction.py        delivered -- CVTRA05Y, 350 bytes
│   │   ├── dalytran.py           delivered -- CVTRA06Y, 350 bytes
│   │   ├── tcatbal.py            delivered -- CVTRA01Y, 50 bytes
│   │   ├── discgrp.py            delivered -- CVTRA02Y, 50 bytes
│   │   ├── trantype.py           delivered -- CVTRA03Y, 60 bytes
│   │   ├── trancatg.py           delivered -- CVTRA04Y, 60 bytes
│   │   ├── usrsec.py             delivered -- CSUSR01Y, 80 bytes; EBCDIC only; NO password
│   │   └── export_record.py      delivered -- CVEXPORT, 500 bytes; NO text form
│   ├── loaders/
│   │   ├── __init__.py           delivered -- makes the subpackage a regular package
│   │   ├── s3_stage.py           delivered -- generation staging and LIMIT/SCRATCH retention
│   │   ├── protected_columns.py  delivered -- the two envelope ciphers, framed as Java reads
│   │   └── aurora.py             delivered -- ten targets; COPY, or stage-and-merge
│   └── verify/
│       ├── __init__.py           delivered -- makes the subpackage a regular package
│       ├── row_counts.py         delivered -- pass 1, exact COUNT(*) against source records
│       ├── checksum.py           delivered -- pass 2, sha256 over canonical field bytes
│       └── money_parity.py       delivered -- pass 3, exact Decimal totals per column
└── tests/
    ├── conftest.py                     delivered -- corpora, record builders, client doubles
    ├── test_aurora_loader.py           delivered -- ten targets, COPY, merge, sealing, privacy
    ├── test_authorization_disclosure.py
    │                                   delivered -- the authorization allow-list
    ├── test_card_protected_value.py    delivered -- the CVV wrapper's every rendering route
    ├── test_cli.py                     delivered
    ├── test_config_name_contract.py    delivered
    ├── test_corpus_disclosure.py       delivered -- the corpus allow-list, fail-closed
    ├── test_database_trust.py          delivered
    ├── test_docstring_gate.py          delivered -- Rule 1 presence gate, all visibilities
    ├── test_doubles.py                 delivered
    ├── test_ebcdic_code_page_allow_list.py
    │                                   delivered -- the measured code-page allow-list
    ├── test_ebcdic_codec.py            delivered
    ├── test_mask_key_material.py       delivered
    ├── test_master_disclosure.py       delivered
    ├── test_online_write_lease.py      delivered
    ├── test_packed.py                  delivered
    ├── test_protected_columns.py       delivered -- both envelope framings, against the Java
    ├── test_reader_factory.py          delivered -- the reader the CLI builds; suppression
    ├── test_readers.py                 delivered -- all twelve readers at declared geometry
    ├── test_reporting_views.py         delivered
    ├── test_s3_stage.py                delivered
    ├── test_seed_datasets.py           delivered
    ├── test_seed_user_subjects.py      delivered
    ├── test_shared_doubles.py          delivered
    ├── test_timestamp.py               delivered -- the two admitted stamp forms, round-tripped
    ├── test_verification.py            delivered -- the three passes and their two queries
    ├── test_verification_authority.py  delivered -- the read-only role the passes run as
    ├── test_verify.py                  delivered -- all three passes, each failing on injected
    │                                   corruption, plus the checksum pass's own mechanism
    └── test_zoned.py                   delivered
```

Assumptions: every path above is now delivered, so none is written as a plain name. The
convention that produced the earlier mixture still stands: a contracted path is written as
a plain name rather than a link, because
[the documentation standard](../docs/CODE_DOCUMENTATION_STANDARD.md) requires that a path
to a file which does not exist yet is never published as a link — a link that resolves to
nothing is a defect a reader finds by clicking.

Refactoring Rationale: this tree is re-measured rather than amended, with
`find data-migration -name '*.py' -o -name '*.sql'`, because an amended inventory drifts in
exactly one direction — an author adding a file remembers to add its row, and an author who
promotes a directory from contracted to delivered edits the one row they were looking at.
Three test modules that already existed were absent from the previous revision of this
tree, and `conftest.py` was marked contracted while being the file every other test module
imports its corpora from.

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
instead of asking the oracle to move. The payoff is diagnostic: those four are the same
builds on both sides, so a discrepancy in verification output cannot be explained away as
a library difference and has to be investigated as a real one.

⚠️ Assumptions: that alignment is **deliberate but not total, and it must not be read as
permission to share one environment.** Ten distributions appear in both this package's
runtime closure and the parity suite's; nine are pinned identically and exactly one is not
— `cryptography`, at **50.0.0** here and **49.0.0** there. `--require-hashes` admits one
version of a distribution per environment, so that single divergence is enough to make the
two closures mutually exclusive. [§4](#4-install) therefore installs this package into its
own `data-migration/.venv` and leaves the repository-root `.venv` to the oracle.

---

## 4. Install

```bash
# WHAT: create THIS package's own environment and install the hash-locked development
#       closure -- the runtime dependencies plus ruff, pytest and coverage.
# WHY : Assumptions: a current system Python is PEP 668 externally managed and refuses
#       a direct install, so an environment is required rather than advisable.
# WHY : ⚠️ Refactoring Rationale: the environment is `data-migration/.venv` and NOT the
#       repository-root `.venv`. This instruction previously created the root
#       environment and installed into it, on the reasoning that the ETL and the COBOL
#       parity oracle should "share one interpreter instead of two that can disagree
#       about a pin they are both supposed to hold". The premise is false by
#       measurement: the two closures do not hold a shared pin, they hold CONFLICTING
#       ones. `tests/requirements-test.txt` pins `cryptography==49.0.0` and
#       `requirements.txt` here pins `cryptography==50.0.0`, and `--require-hashes`
#       admits exactly one version of a distribution per environment. Sharing one
#       environment therefore does not reconcile the two -- it makes whichever closure
#       is installed second either fail outright or move a pin the other depends on,
#       and the one that must never move is the parity oracle's.
# WHY : Trade-offs: two environments cost two installs and one more path for an
#       operator to get right. That is accepted because the alternative is a green
#       parity suite running on a dependency set it did not pin, which is precisely the
#       failure a hash-locked manifest exists to prevent.
python3 -m venv data-migration/.venv
source data-migration/.venv/bin/activate
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
implements all fourteen subcommands tabulated in
[§5.2](#52-subcommands-and-their-arguments) below, and
[`MIGRATION_README.md`](../MIGRATION_README.md) publishes the invocation. Nothing here
describes a flag that should not be implemented, and no two subcommands do the same
work. `python -m carddemo_migration.cli --help` lists exactly these thirteen, so the
table and `--help` agree one-for-one.

Assumptions: **no subcommand is marked contracted.** The table below therefore carries no
unreachable row, which is the property this section's own convention exists to make visible: a
name in it is a name an operator can invoke.

⚠️ Refactoring Rationale: **every subcommand in this section is registered, `verify-all`
included, and nothing here is contracted-but-unregistered.** Successive revisions of this section
held `verify-all` back on the premise that an aggregate over every dataset cannot be told on the
command line where each extract is, and each revision left its own note saying so; the notes
outlived the premise and are removed rather than restated, because a reader who finds one of them
concludes the gate cannot be invoked. The premise was accurate and the conclusion did not follow:
the answer to needing a manifest is to accept one as input. The verb is now backed twice — by a
manifest an operator supplies, and by the seed registry, which names every dataset's context,
prefix segments and extract, so an omitted `--source` resolves without being guessed. Alternatives
Considered: letting `--dataset` mean "every dataset" on the three per-dataset passes. Rejected
because each would then have had to resolve every extract's path for itself, and a verification
that guesses where the bytes are is a verification of whatever it found.

Assumptions: `verify-all` is the ONLY combined verb, and the three per-dataset passes it
runs remain separately invocable. An operator diagnosing one dataset needs the narrow
verb; an orchestrator certifying a migration must not be able to ask for less than all
three, which is why the gate is one invocation rather than three states in a row.

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

| Subcommand | Purpose | Required arguments | Optional arguments |
|---|---|---|---|
| `list-datasets` | Print the record-layout contract — identifier, copybook, record length, key length and provenance — for every registered layout, so a caller can enumerate the set instead of hard-coding it | none | `--format {table,json}` |
| `decode-record` | Decode **one** record of a fixed-length extract through the per-field codec stack and print its fields, so a delivery can be proved against its declared geometry before anything is loaded. Sensitive fields are redacted ([§8.2](#82-sensitive-fields-in-diagnostic-output)) | `--dataset`, `--source` | `--record` (default 1), `--code-page` (default `cp037`) |
| `stage-dataset` | Stage **one** exported extract to object storage under the generation prefix convention, copying bytes verbatim. The extract is read from the staging root ([§5.7](#57-how-connection-details-reach-the-process)) and every other coordinate — owning context, object name, generation — is derived, never passed | `--dataset`, `--business-date` | `--retain` (default and floor 5), `--dataset-segment` (default: the dataset's own generation family) |
| `refresh-dataset` | Perform **one** dataset's whole cutover as a single step: fetch the exported extract from the deployment's provisioned source prefix in the dataset bucket, stage its bytes as a new generation, decode it per field and bulk-load it into the schema that owns it, then run all three verification passes over the load — and then, for the dataset that feeds `ledger.transactions`, advance the identifier allocator, or, for each of the three reference datasets the baseline copies to a backup base, stage that backup generation. This is the migrated form of one `IDCAMS` DELETE/DEFINE/REPRO master-refresh job, and it is what the batch chain's seed-refresh state invokes | `--dataset`, `--business-date` | `--extract-prefix` (default `migration/source/EBCDIC/`), `--encoding {ascii,ebcdic}` (default `ebcdic`), `--source` (use a local extract and skip the fetch), `--retain` (default and floor 5) |
| `apply-credentials` | Give every service login role the credential it authenticates with, then prove each role can log in | none | none |
| `reconcile-sequences` | Advance `ledger.transaction_id_seq` past every sequence-format identifier `ledger.transactions` holds. Run after the last load into that table and **before** writes are enabled; only ever advances, so a repeat run is a no-op | none | none |
| `refresh-card-identity` | Reconcile `reporting.card_identity` with `account.card_xref`. Run after the last load into the cross-reference and **before** any statement or report run: the relation is created and backfilled by [`sql/V1__reporting_views.sql`](sql/V1__reporting_views.sql), which on a cutover runs against an empty cross-reference, and a card absent from it gets no statement and raises nothing. Idempotent, so a repeat run is a no-op | none | none |
| `load-dataset` | Decode **one** dataset per field and bulk-load it into the schema that owns it, as a single committed unit of work | `--dataset` | `--source` (a local path or an `s3://` key), `--encoding {ascii,ebcdic}` -- both DERIVED when `--dataset` names a dataset the seed registry carries, and both required when it names a copybook layout the registry does not |
| `verify-row-counts` | Verification pass 1 — loaded row count against source record count, for one dataset | `--dataset` | `--source`, `--encoding {ascii,ebcdic}` -- derived as for `load-dataset` |
| `verify-checksum` | Verification pass 2 — per-record digest of the loaded rows against the source image, for one dataset, plus an audit that every sealed column holds a well-formed envelope. No field value is printed | `--dataset` | `--source`, `--encoding {ascii,ebcdic}` -- derived as for `load-dataset` |
| `verify-money-parity` | Verification pass 3 — exact money totals from the source bytes against the database's own `SUM` of each column they load into, for one dataset | `--dataset` | `--source`, `--encoding {ascii,ebcdic}` -- derived as for `load-dataset` |
| `verify-row-count-report` | Verification pass 1 for the WHOLE migration at once — executes [`sql/verify/row_counts.sql`](sql/verify/row_counts.sql) on a session for the read-only `carddemo_reporting` role and prints its six-column verdict for every dataset. The session's role is checked against the server before the query runs, so a pass that could write cannot certify the load. Reads no local extract | none | `--sql-root` (omit in a source checkout; pass `.` in the container image, whose working directory holds the copied `sql` tree) |
| `verify-money-total-report` | Verification pass 3 for the WHOLE migration at once — executes [`sql/verify/money_totals.sql`](sql/verify/money_totals.sql) on a session for the read-only `carddemo_reporting` role, recomputes each money column's exact total independently from the named extracts' own bytes, and compares both the totals and the negative-row counts. The negative-row comparison is what catches a compensating sign swap, which leaves every total unchanged and therefore passes `verify-money-parity`. The session's role is checked against the server before the query runs. Every money column whose layout ships a committed extract must be named, or the run is refused | `--extract LAYOUT=PATH[=ENCODING]` (repeatable), `--encoding {ascii,ebcdic}` | `--sql-root` (as for the row-count report) |
| `verify-all` | **The combined gate.** Run all three passes in the fixed order 1, 2, 3 and report one binary verdict, stopping at the first pass that fails. Coverage comes from a `--manifest` when one is supplied and from the seed registry's own datasets when none is — every registered dataset that ships a committed extract. It takes no `--dataset`: it cannot be narrowed to a subset, and no option can skip a pass or continue past a failure. This is the verb the nightly chain's `VerifyMigration` state invokes | none | `--manifest`, `--source-root` (a directory or `s3://` prefix holding the extracts; omitted, the staging root is read), `--sql-root` (as for `verify-row-count-report`) |

⚠ **`--dataset` accepts two vocabularies, and they are disjoint.** A seed-dataset token --
lower-case and plural, such as `accounts` or `card_xref` -- and a copybook layout name -- short
and upper-case, such as `ACCOUNT` or `XREF` -- both resolve, and the parser normalises whichever
arrives to the layout name every registry downstream is keyed by. No value is in both
registries, which `tests/test_seed_datasets.py` asserts at import rather than assumes, so a value
resolves to exactly one dataset. A layout the seed registry does NOT carry derives nothing and
still requires `--source` and `--encoding`, because such a layout is not bound to one extract:
`REJECT` and `INTTRAN` are produced by the pipeline and shipped by nothing.

⚠️ Refactoring Rationale: the `stage-dataset` row once listed `--source`, `--generation`,
`--domain` and `--object-name` as arguments -- first required, then optional overrides -- and all
four are now **withdrawn**. Each let a caller replace a value the seed registry or the durable
generation reservation is the authority for, and each carried a destructive reading rather than
merely a redundant one: a caller-named source has no root it can be contained by, a wrong owning
context writes one bounded context's master under another's prefix past a policy written per
prefix, an out-of-sequence generation makes the retention sweep scratch a generation that is not
the oldest, and a caller-chosen object name is a staged object the verification pass does not
look for. `--retain` survives with a floor, because retaining MORE history than the contract
requires destroys nothing.

⚠ **The extract location may be an `s3://bucket/prefix` URI.**
`CARDDEMO_DATASET_STAGING_ROOT` names either a filesystem directory or an object-storage
prefix; a registered extract under an object-storage prefix is streamed to task-local scratch
storage for the duration of one command and removed afterwards, and is checked against the
object's own declared length, its recorded digest where one exists, and the declared record
width before a record is decoded. The object-storage form is the one a Fargate task can use at
all -- the image ships no extract and the task definition mounts no volume -- and the filesystem
form is the one a checkout uses.

Refactoring Rationale: the four rows above were `contracted` for exactly as long as
`readers/`, `loaders/aurora.py` and `verify/` were absent, and two of them were spelled
`verify-checksums` and `verify-money-totals` before the modules landed. They are spelled
`verify-checksum` and `verify-money-parity` as delivered, one-for-one with the modules
that back them — `verify/checksum.py` and `verify/money_parity.py` — because a command
whose name does not match its implementation is one indirection an operator reading a
traceback has to resolve for no benefit.

Assumptions: all four take `--dataset`, share one option set, and none of them takes a
`--dataset` meaning "every dataset". They are four questions about the same pairing of one extract
and one table, so a caller who has just loaded a dataset verifies it by changing only the verb; and
"every dataset" is a different question with a different answer shape, which is what `verify-all`
is for.

Assumptions: `--source` and `--encoding` are OPTIONAL on all four and are derived for any dataset
the seed registry carries, under either spelling. That is what lets the orchestrator invoke them
with the one value a Step Functions branch holds. They remain required for a layout the registry
does not carry, and the refusal names both.

Assumptions: `verify-all` is the one command that covers many datasets, and it is told where their
bytes are rather than inventing a location. A `--manifest` is a JSON object carrying a non-empty
`datasets` array whose entries each declare `dataset`, `source` and `encoding` — the same three
values the four commands above take on the command line, with `dataset` accepting **either**
spelling exactly as their `--dataset` does, a seed token or a layout name, because a manifest entry
that refused a spelling those commands accept would refuse a value an operator had just used
successfully — and a relative `source` resolves against
the manifest's OWN directory, so one manifest describes a delivery tree wherever that tree is
mounted; the datasets are then verified in the manifest's declared order, because a load runs in a
dependency order and the operator owns it. With no manifest the coverage is the seed registry's own
datasets and the extracts are resolved beneath `--source-root`, or beneath the staging root when
that is omitted.

⚠️ Assumptions: the two coverage sources are **not** symmetric about object storage, and the
asymmetry is worth stating because the option names suggest otherwise. `--source-root` accepts a
directory **or** an `s3://` prefix; a manifest entry's `source` is a filesystem path only. A
manifest `source` spelled `s3://bucket/key` is not rejected — it is read as a *relative* path,
because that string is not absolute, and so resolves to `<manifest-dir>/s3:/bucket/key`, which
does not exist. Verify staged objects through the registry form and reserve the manifest form for
extracts on a filesystem.

Assumptions: the two whole-migration reports take NONE of that option set, and
`verify-money-total-report` names its sources through repeated `--extract LAYOUT=PATH`
pairings rather than through `--dataset`. That is the same reasoning applied to a pass
that genuinely spans every dataset: it needs several extracts at once, so the paths are named
explicitly — one pairing per money-bearing record — and the pass refuses a set that
leaves a column whose layout ships a committed extract unmeasured. `--encoding` on that
command is the form to read a pairing that declares none of its own, which is why a
pairing may carry a third component overriding it.

Refactoring Rationale: `--source` was REQUIRED on all four and is now optional, and the
reason it was required has been answered rather than relaxed. The stated reason was that
this distribution held no dataset-to-path mapping, so the caller had to be told where the
bytes were. It holds one now: the seed registry names each dataset's extract file and its
prefix segments, so an omitted `--source` resolves to the newest STAGED generation of that
dataset — the direct analogue of a JCL step referencing `TRANSACT.BKUP(0)`. That default
is what lets the batch chain's load and verification states pass a dataset token and
nothing else, and an empty family is a named refusal rather than an empty read.

Assumptions: the default reads what was STAGED, not what an operator put in the inbox.
The staged object carries the digest this package recorded when it wrote it, so the fetch
is integrity-checked against its own metadata; the inbox holds bytes this package did not
write and carries no such record. It is also the only reading under which a load is
provably reading what the staging step before it wrote.

Assumptions: the same reasoning is why `verify-money-total-report` takes a repeated
`--extract LAYOUT=PATH[=ENCODING]` rather than discovering the seed tree. It needs **every**
layout that feeds a money column in one call — the pass refuses to certify a load with any
declared money column left unmeasured — so it cannot be driven one dataset at a time, and it
still cannot be told where the bytes are by anything but its caller. One `--encoding` covers the
whole run because all four layouts that ship an extract for a money column (`ACCOUNT`,
`DALYTRAN`, `DISGROUP`, `TCATBAL`) ship in **both** seed forms, so no corpus requires a
mixed-form run; the per-pairing third component exists for a corpus that one day does.

Assumptions: `--dataset` carries exactly one meaning on every verb that takes it — the
dataset, named in either of the two spellings [§5.4](#54-dataset-identifiers--there-are-two-vocabularies) publishes.
It is emphatically **not** `decode-record`'s `--record`, which is a one-based ORDINAL
within an extract; an earlier draft of these four commands spelled the selector `--record`
and so gave one flag two unrelated meanings, which the "change only the verb" path above
would have run straight into.

> **Cipher boundary — three columns are sealed, never written in the clear.**
> `account.customers.ssn_encrypted`, `account.customers.govt_issued_id_encrypted` and
> `card.cards.cvv_encrypted` hold ciphertext, and `load-dataset` produces it. Each is
> sealed under the key the service owning the column resolves at run time, in the
> envelope framing that service's own decipher expects, so a row this package writes is
> a row that service can read and no other representation is admitted.
>
> The key is resolved per record and only when the record asks for it. A target declares
> which of its columns are sealed; the loader resolves exactly the keys those
> declarations name and nothing else. So `load-dataset TRANTYPE` — seven rows of
> reference data with no sealed column — needs no key-management grant at all, and an
> environment that has provisioned none can still load the reference tables that
> everything else joins through.
>
> Alternatives Considered: loading the plaintext into the `*_encrypted` columns, which
> would succeed. It is rejected because it succeeds — the load would report a clean
> result and the row-count and checksum passes would agree, while every national
> identifier in the database sat in cleartext in a column whose name asserted otherwise.
>
> Alternatives Considered: leaving the two records refused, which is what this section
> described until the ciphers landed. Rejected because the refusal closed two of the
> eight schemas to this package permanently, and the missing piece was never the key —
> the key identifier is published at the same parameter path the owning service reads it
> from — but the envelope framing. Both framings were read out of the Java sources
> byte for byte and are asserted against those sources' own declared constants by test,
> so a change on either side fails rather than producing envelopes that store cleanly
> and refuse to decrypt days later.
>
> Assumptions: the encryption context travels to the key-management service on the
> data-key call and binds the WRAPPED KEY. It is deliberately **not** bound into the
> local cipher as additional authenticated data, because neither Java implementation
> does so — measured, not assumed: neither calls `updateAAD`. Binding it here would
> produce envelopes that frame correctly, load cleanly, verify cleanly, and then fail
> authentication in the application. One test asserts the absence on both sides.

Assumptions: `list-datasets` prints the five properties
[`layouts.py`](src/carddemo_migration/copybook/layouts.py) holds authoritatively, and
reports **every** registered layout — the eleven base masters of
[§6.1](#61-the-eleven-datasets) plus the three derived ones, distinguished by the
`provenance` column. The owning schema and the seed-extract encodings that §6.1
tabulates are deliberately **not** printed: they have no representation in this
distribution's code, and giving them one here would create a second source for a table
§6.1 already owns, free to disagree with it. §6.1 remains where those two are read.

Refactoring Rationale: `stage-dataset` took `--domain`, `--generation` and
`--object-name`, and all three are **withdrawn**. `--domain` was required on the stated
grounds that this distribution held no dataset-to-context mapping, so a default would have
had to invent one — and the stated condition for withdrawing it was that the mapping gain
an authoritative home in code. It has: the seed registry names each dataset's bounded
context, its prefix segment and its extract file, so all three coordinates are derived from
the one token the caller already passes. They are withdrawn rather than left as optional
overrides because an override is a second authority: a caller who passes a domain that
disagrees with the registry writes a real extract to a prefix nothing reads, and the write
succeeds. The generation is likewise reserved rather than named ([§5.5](#55-the-business-date-is-a-parameter-never-a-clock-read)).

Alternatives Considered: documenting the three as OPTIONAL arguments carrying derived defaults,
which is how a competing draft of this section described them. Rejected on measurement rather than
on preference: `build_parser()` gives `stage-dataset` exactly `--business-date`, `--dataset`,
`--dataset-segment` and `--retain`, so the three are not accepted at all and a table of their
defaults would describe flags an operator cannot pass. The same draft attributed `--source` to this
subcommand, where it in fact belongs to `refresh-dataset`.

| Subcommand | What it writes | Non-zero exit when |
|---|---|---|
| `list-datasets` | The contract table on standard output. Touches no database, no object store and no credential | the requested format is unknown |
| `decode-record` | One record's field map as JSON on standard output, every value rendered as a string and every sensitive field redacted. Touches no database, no object store and no credential | the layout name is unknown, the ordinal is below one or past the end of the dataset, the source is unreadable, the dataset does not divide into whole records, or a field fails to decode |
| `stage-dataset` | One object at `<domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/<object-name>` in the dataset bucket — every segment derived from the registry and the reserved generation — carrying a service-verified `ChecksumSHA256` plus the digest and byte count as object metadata, then permanently scratches generations that roll off. The bucket, deployment and effective region are logged before the write, and the digest beside the key after it | the source cannot be resolved beneath the staging root, cannot be staged — absent, reached through a symbolic link, not a regular file, unreadable, or modified while it was being transferred — the dataset identifier is unknown, the generation already holds DIFFERENT bytes, or the write or the scratch fails |
| `load-dataset` | Rows in the owning schema's table, inside one transaction; a per-dataset summary on standard output | a record fails the width contract, a field fails to decode, or the load transaction cannot commit |
| `refresh-dataset` | Everything `stage-dataset`, `load-dataset` and the three per-dataset verification passes write, in that order, for one dataset — plus, for the transaction master only, whatever `reconcile-sequences` writes. The fetched extract lives in a scratch directory that is removed when the step ends, whatever the outcome | the token is not registered (2), the dataset bucket cannot be resolved (16), or the **first** step that did not succeed did not — and the exit status is that step's own, so a fetch or verification failure is 8 and a usage fault stays 2 |
| `apply-credentials` | A SCRAM verifier on each of the **sixteen** login roles — eight runtime, seven migration and the read-only `carddemo_verifier` — read from that role's own secret and applied inside one transaction. The plaintext credential never crosses the connection | any role is unrecognised or missing, cannot be given its verifier, or cannot then log in |
| `reconcile-sequences` | At most one `setval` on `ledger.transaction_id_seq`, issued as the schema owner reached by `SET ROLE`; both allocator positions and the largest stored identifier on standard output | the owner cannot be assumed, the sequence or the table cannot be read, or the advance is refused — in which case writes must not be enabled |
| `refresh-card-identity` | A delta insert and a delta delete on `reporting.card_identity`, issued as the reporting schema's owner reached by `SET ROLE` from the cluster master — the one context with no `_migrator` login, because reporting-service ships no migration; the published card count, the identity count either side and the measured gained and removed counts on standard output | the owner cannot be assumed, either relation cannot be counted, or the reconciliation is refused — in which case a statement run must not be started |
| `verify-row-counts` | A per-dataset expected-versus-actual table | any dataset's counts differ |
| `verify-checksum` | The two whole-dataset digests, then one line per located difference giving the differing record's position, its **key** where the two sides are paired by one, and the differing field's name, byte interval and storage regime — never either value; then one line per sealed column reporting how many envelopes were expected, how many are stored and how many are malformed, and nothing of their contents. The lines are bounded and the withheld count is stated, so a wholly-wrong load reports a diagnostic rather than a file; the compared, differing-record and one-sided counts are exact whatever the bound | any record differs, either side holds a record the other does not, or a sealed column is short of a well-formed envelope |
| `verify-money-parity` | A per-column source-versus-loaded total table, naming both the copybook field and the target column | any total differs by any amount |
| `verify-row-count-report` | The whole-migration row-count report — one line per declared dataset and one verdict line — rendered so that two runs over unchanged data diff to nothing. Writes nothing anywhere and cannot: the session it runs on holds `SELECT` on the two aggregate verification views and nothing else | any line's baseline and actual count differ, a dataset that ships no extract holds rows, the session is not the reporting role, the shipped query cannot be located, or the report omits a declared dataset |
| `verify-money-total-report` | The whole-migration money-total report — one line per declared money column giving the source and loaded totals and both negative-row counts, then one verdict line — rendered so that two runs over unchanged data diff to nothing. A column whose layout ships no extract is held to being empty rather than waved through, and every sign discrepancy is additionally logged on its own line. Writes nothing anywhere and cannot, for the same reason as the row-count report | any column's totals or negative-row counts differ, a column whose layout ships no extract holds money, the session is not the reporting role, the shipped query cannot be located or breaches its published result-set contract, an extract cannot be read or decoded, or a money column whose layout ships an extract was left unnamed |
| `verify-all` | One numbered heading per pass, each followed by that pass's own report for every covered dataset, then a single verdict line naming how many of the three ran and how many verified. A pass that did not run because an earlier one failed is stated as not run rather than omitted | any one pass of any covered dataset fails, or a supplied manifest is absent, unreadable, empty or malformed — the last four as a usage error rather than a data failure |

### 5.3 Applying the DDL is deliberately not a subcommand

There is no `bootstrap-schemas` subcommand, and there will not be one.
All four shipped DDL files — [`sql/V0__schemas_and_roles.sql`](sql/V0__schemas_and_roles.sql),
[`sql/V1__reporting_views.sql`](sql/V1__reporting_views.sql),
[`sql/V2__runtime_delete_grants.sql`](sql/V2__runtime_delete_grants.sql) and
[`sql/V3__verification_surfaces.sql`](sql/V3__verification_surfaces.sql) — are applied by
[the data-migration runbook](../docs/runbooks/data-migration.md), with
`psql -v ON_ERROR_STOP=1` under a temporary administrative identity, and
[§11](#11-schema-and-role-bootstrap) describes what they create.

Alternatives Considered: wrapping it as a subcommand, which would put the whole
bootstrap behind one entry point and is the tidier-looking arrangement. Rejected
because the script creates roles, and role creation is cluster-wide authority that no
ETL task role holds or should hold — exposing it here would mean the loader's identity
had to carry role-creation authority for the lifetime of every load, which is the
opposite of the least-privilege boundary the eight per-context roles exist to draw. The
same objection disqualifies `V1` and `V2` for a narrower reason: both issue `GRANT`,
which only an object's owner or a superuser may do, so exposing either would mean the
loader's identity had to hold grant authority over tables it is otherwise only allowed
to read and write rows in.
`apply-credentials` is the one bootstrap step that **is** exposed, because it needs
only the cluster's administrative secret for the duration of a single invocation and
because the module that implements it asks to be reached through the command line
rather than reimplemented.

### 5.4 Dataset identifiers — there are TWO vocabularies

**`--dataset` accepts two spellings of the same set, and every verb that takes the flag
accepts both.** Twenty-five names are accepted in total, and no others:

```text
# the eleven orchestrator tokens -- what a Step Functions Map branch passes
accounts  cards  customers  card_xref  transactions  daily_transactions
disclosure_groups  transaction_category_balances  transaction_types
transaction_categories  users

# the fourteen record-layout names -- what list-datasets prints
ACCOUNT  CARD  CUSTOMER  XREF  TRAN  DALYTRAN  DISGROUP  TCATBAL
TRANTYPE  TRANCAT  SECUSER  TRNX  REJECT  INTTRAN
```

Assumptions: the two vocabularies are provably DISJOINT — the tokens are lower
snake_case and the layout names are upper — so one resolver accepts both with no
ambiguity to arbitrate, and the module that owns the registry asserts the disjointness at
import time rather than trusting it. The eleven tokens resolve onto eleven of the
fourteen layouts, which is why the counts differ: the three derived layouts `TRNX`,
`REJECT` and `INTTRAN` are written BY the batch pipeline, so no extract is ever staged for
them and no token names them.

Assumptions: a name being accepted by the flag is not the same as being loadable, and the
two are deliberately separated. `list-datasets` and `decode-record` serve all fourteen
layouts, because both only read a declared geometry. `load-dataset` and the three
per-dataset verify verbs serve the ELEVEN with a load target, and naming a derived layout
to one of them is refused with the reason — written by the batch chain rather than
migrated — rather than with an unknown-name error, because the name is perfectly well
known and it is the request that does not apply.

Refactoring Rationale: this section published a THIRD vocabulary — `usrsec`, `acctdata`,
`carddata`, `custdata`, `cardxref`, `dalytran`, `transact`, `discgrp`, `trancatg`,
`tcatbalf`, `trantype` — the baseline dataset names lowercased with `.PS` dropped. No verb
ever accepted any of them. The orchestrator's Map branches passed the plural snake_case
tokens while the command validated `--dataset` against the layout registry's upper-case
names, so `accounts` and `card_xref` were each refused; documenting a third spelling on
top made all three disagree. The resolution unified the two that exist in code rather than
adding a translation table: the seed registry owns the token half, `layouts.py` owns the
layout half, and the command resolves either.

Assumptions: the token is the vocabulary that crosses the PROCESS boundary, because it is
what the Terraform `seed_datasets` variable lists and what a container command override
carries. The layout name is the vocabulary that crosses the FILE boundary, because it is
what `list-datasets` prints and what a copybook is named after. Both are published because
an operator reading a `list-datasets` table and an orchestrator author reading a tfvars
file would otherwise be looking at two sets they had no way to relate.

Alternatives Considered: naming the identifiers after the internal reader modules
instead — the singular domain words for account, customer and transaction. Rejected,
because the caller's request, the JCL that produced the extract and the object key all
name the **dataset**, so tying the public vocabulary to an internal module layout
would turn a file rename into a breaking change to the command line.

**The mapping, stated once.** Every staging token names the layout it stages, so the two
vocabularies are joined rather than parallel. The owning context and the registered source object
are the two coordinates `stage-dataset` derives from the token, so they are published beside it:

| Staging token | Layout identifier | Owning context | Registered source object |
|---|---|---|---|
| `accounts` | `ACCOUNT` | `account` | `AWS.M2.CARDDEMO.ACCTDATA.PS` |
| `cards` | `CARD` | `card` | `AWS.M2.CARDDEMO.CARDDATA.PS` |
| `customers` | `CUSTOMER` | `account` | `AWS.M2.CARDDEMO.CUSTDATA.PS` |
| `card_xref` | `XREF` | `account` | `AWS.M2.CARDDEMO.CARDXREF.PS` |
| `daily_transactions` | `DALYTRAN` | `ledger` | `AWS.M2.CARDDEMO.DALYTRAN.PS` |
| `transactions` | `TRAN` | `ledger` | `AWS.M2.CARDDEMO.DALYTRAN.PS.INIT` |
| `disclosure_groups` | `DISGROUP` | `reference` | `AWS.M2.CARDDEMO.DISCGRP.PS` |
| `transaction_types` | `TRANTYPE` | `reference` | `AWS.M2.CARDDEMO.TRANTYPE.PS` |
| `transaction_categories` | `TRANCAT` | `reference` | `AWS.M2.CARDDEMO.TRANCATG.PS` |
| `transaction_category_balances` | `TCATBAL` | `ledger` | `AWS.M2.CARDDEMO.TCATBALF.PS` |
| `users` | `SECUSER` | `auth` | `AWS.M2.CARDDEMO.USRSEC.PS` |

⚠️ Assumptions: one row of that table is deliberately surprising and is printed rather than
described. **`transactions` stages a `DALYTRAN`-named object**, and the suffix is the tell: the
transaction master is pipeline-produced and no `TRANSACT` extract ships, so the registry stages the
baseline's own substitute — the `.INIT` object that
[`app/jcl/TRANFILE.jcl`](../app/jcl/TRANFILE.jcl) REPROs the `TRANSACT` KSDS from. `DALYTRAN` is a
separate token in its own right, staging the unsuffixed object into
`ledger.daily_transactions`, so the two are not two spellings of one delivery.

Refactoring Rationale: a competing draft of this table had TEN rows and stated that `DALYTRAN` has
no staging token at all. `seed_datasets.SEED_DATASETS` holds ELEVEN and `daily_transactions` is one
of them, so the row is restored rather than the claim repeated; a reader who believed it would have
concluded the nightly chain could not stage the daily feed.

### 5.5 The business date is a parameter, never a clock read

`stage-dataset` requires `--business-date YYYY-MM-DD`. Assumptions: the date is
supplied by the caller and is never read from the wall clock, because a rerun must
reproduce the run it is meant to reproduce — a clock read would place the second
attempt under a different `dt=` prefix and leave the first one orphaned. This mirrors
the baseline exactly, where the business date arrives as `PARM='2022071800'` on the
job step rather than from the system time.

`--dataset-segment` names the generation family the bytes are staged **under**, where
`--dataset` names the record layout they are decoded **as**. Assumptions: the two are
separate because one extract can legitimately reach two prefixes. The baseline's
[`DEFGDGD.jcl`](../app/jcl/DEFGDGD.jcl) defines a backup generation base for each of
the three reference datasets and immediately creates its first generation with an
`IEBGENER` verbatim copy of the same sequential file the load reads — `TRANTYPE.BKUP`
from `TRANTYPE.PS` at L36–L43, `TRANCATG.PS.BKUP` from `TRANCATG.PS` at L59–L66 and
`DISCGRP.BKUP` from `DISCGRP.PS` at L82–L89 — so the same bytes are both loaded and
retained. Without the option the segment defaults to the dataset's own, which is
every other case. Alternatives Considered: a separate `stage-backup` subcommand.
Rejected because it would duplicate the whole staging surface — the reservation, the
retention, the checksum and the metadata — to vary one path segment.

Assumptions: this is the one staging coordinate an operator may still state, and the asymmetry
with the withdrawn `--domain` is measured rather than stylistic. A wrong dataset segment lands
inside the SAME bounded context's prefix, so it cannot cross the per-prefix least-privilege
boundary a wrong owning context crosses; and no other argument can express what three baseline
families require, which is one dataset's bytes under another family's segment.

**The generation number is reserved, never passed.** ⚠️ Refactoring Rationale:
`stage-dataset` took `--generation NNNN`, validated into 1–9999, on the reasoning that one
number should be computed once per execution and handed to every step that touches the
family — because two steps each deriving "the next generation" for themselves resolve to
two different prefixes, and a later step then reads an empty location while the earlier
step's output sits elsewhere. That reasoning was right about the hazard and wrong about the
remedy: passing the number moved the derivation to the caller, where the orchestrator had
to compute it in HCL and every branch of a parallel Map had to be given the same value —
and a number supplied out of sequence makes the retention sweep, which keeps the newest five
BY NUMBER, scratch a generation that is not the oldest. Reservation gives the same property
without the parameter, so the flag is withdrawn and `--business-date` is the only
date-shaped input.

`stage_family_file` reserves the number through `reserve_generation`. Trade-offs: that
costs an extra conditional write per staging step and it buys two properties a
computed number cannot have. The reservation is a conditional create keyed by
execution token, family and business date, so two writers racing for the same number
cannot both win it — the service arbitrates rather than each writer's own listing.
And presenting the same execution token again returns the same number, so a retried
step rewrites one key instead of consuming a second generation for a byte-identical
copy. `next_generation` remains available and still answers "what would a baseline
`(+1)` reference resolve to?", but it is a **query, not an allocation**: two callers
asking it concurrently receive the same answer, which is exactly why allocation does
not go through it.

### 5.6 Exit semantics

**The exit status is binary: zero means the command did what it was asked, and any
non-zero value means it did not.** Where a command can usefully classify its failure
it uses distinct non-zero codes — `2` invoked incorrectly, `8` the step could not be
completed, `16` the environment or cluster could not be reached at all — exactly as
the delivered [`credentials.py`](src/carddemo_migration/credentials.py) already does.
No non-zero value is ever a pass.

**Those four are the whole set, and two paths that used to escape it now report inside
it.** A delivered extract that does not decode at its declared geometry is `8` from every
command that reads one — including `decode-record` and all three per-dataset verification
verbs, which previously ended in an interpreter traceback and status `1` when the fault
came from the zoned or packed field codec rather than from the record codec above them.
And **interrupting a running command with `Ctrl-C` is `8`**, not the interpreter's `130`:
the entry point logs one sentence naming the subcommand that stopped, and the database
work it had started is rolled back by the connection close that was already in place.
`16` additionally covers an unusable `CARDDEMO_MASK_HMAC_KEY` (§5.7.1), which is now
refused at the command-line boundary before any handler runs rather than from inside the
first masked value — so a misconfigured key reports as the configuration fault it is
instead of as a decode failure.

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
[`config.py`](src/carddemo_migration/config.py). Ten of the eleven variables below carry
only **names, modes and locations** — never values. **The eleventh,
`CARDDEMO_MASK_HMAC_KEY`, is the one exception and it carries secret VALUE material**;
§5.7.1 states what that obliges.

| Variable | Carries | Meaning | Default |
|---|---|---|---|
| `CARDDEMO_ENVIRONMENT` | name | Selects which environment's parameters and secrets are read | **none — required** |
| `CARDDEMO_PARAMETER_PREFIX` | name | Root of the Parameter Store path the lookups are built from | `/carddemo` |
| `CARDDEMO_DB_SSL_MODE` | mode | TLS verification mode | `verify-full`, and no other value is accepted |
| `CARDDEMO_DB_SSL_ROOT_CERT` | path | Trust anchor for the database connection | the CA bundle the image installs |
| `CARDDEMO_DB_MASTER_SECRET` | name | Name of the cluster's administrative secret, used only by `apply-credentials` | none |
| `CARDDEMO_DB_ALTERNATE_USERS` | names | Additional database user names permitted to act for a schema's role | none |
| `CARDDEMO_MASK_HMAC_KEY` | ⚠ **secret value** | The HMAC-SHA256 **key** that the redaction tag in [`copybook/layouts.py`](src/carddemo_migration/copybook/layouts.py) is derived with | none — **omitting** the variable selects a process-scoped random key; setting it blank is refused (§5.7.1) |
| `CARDDEMO_DATASET_STAGING_ROOT` | location | Where the seed extracts are read from — either a local directory or an `s3://` prefix. `stage-dataset` resolves each dataset's registered source object beneath it, and `verify-all` reads it unless `--source-root` overrides | none — required by `stage-dataset` and `verify-all`, unused by every other verb |
| `CARDDEMO_BATCH_RUN_ID` | name | The execution token a generation reservation is keyed by, so a retried staging step rewrites one key instead of consuming a second generation | none — a staging step run without it reserves unconditionally |
| `CARDDEMO_SECURITY_CVV_KEY_ID` | parameter name | Parameter Store name, beneath the `card` context, holding the key alias the card-verification envelopes are sealed under | the published per-environment path; resolved only when a record with that sealed column is loaded |
| `CARDDEMO_SECURITY_CUSTOMER_IDENTIFIER_KEY_ID` | parameter name | Parameter Store name, beneath the `account` context, holding the key alias the customer-identifier envelopes are sealed under | the published per-environment path; resolved only when a record with those sealed columns is loaded |

Refactoring Rationale: the table listed seven variables while the package reads eleven.
The four added above — the staging root, the execution token and the two key-alias
parameter names — were omitted while the staging loader and the protected-column ciphers
were newer than this section, and an operator provisioning a task definition from this
table alone would have produced one that could not stage an extract and could not load
either record with a sealed column. Neither omission would have surfaced at start-up: both
are resolved at the point of first use, so the task would have started cleanly and failed
mid-load.

Assumptions: none of the four added rows carries a VALUE. Three carry names and the fourth
carries a location, which is why the one-exception statement above still holds with eleven
rows rather than seven.

#### 5.7.1 `CARDDEMO_MASK_HMAC_KEY` is key material, not a name

This variable is described separately because describing it in the table alone would
mislead. Every other row names something the process then looks up; this one **is** the
secret. `layouts.py` reads it and passes the bytes straight into
`hmac.new(key, message, hashlib.sha256)`, so the variable's value is the key, and
disclosing it is disclosing the ability to confirm a redacted field by guessing its
plaintext and re-deriving the tag.

Four obligations follow, and each is enforced somewhere rather than merely advised:

- **It arrives as a secret reference, never as a plain task or environment value.**
  [`infra/envs/dev/main.tf`](../infra/envs/dev/main.tf) and
  [`infra/envs/prod/main.tf`](../infra/envs/prod/main.tf) place it in
  `mask_hmac_secret_sources`, so the task definition carries a Secrets Manager ARN under
  `secrets` and the platform resolves the value into the process at start.
  [`infra/modules/ecs-service/main.tf`](../infra/modules/ecs-service/main.tf) asserts that
  pairing: the `data-migration` workload is the only one permitted to receive this name,
  and it must receive it as a secret. Putting the literal key in `environment`, in a
  `.tfvars` file, in a shell profile, in a `docker run -e`, or in CI variables is
  **prohibited** — all five are readable by anyone who can describe the task or read the
  build, which is a wider audience than the redaction is protecting the data from.
- **It is never logged, echoed or included in a diagnostic.** `layouts.py` renders only
  the derived tag and never the key; no message in this package quotes the variable's
  value; and the same prohibition applies to a shell that sets it — `env`, `set -x` and a
  crash dump each disclose it in full. Redaction tags themselves are safe to log, which is
  the entire point of deriving them.
- **Entropy, and it is now ENFORCED rather than advised.** Supply **at least 32 bytes
  (256 bits)** of cryptographically random data, **encoded as canonical standard base64**.
  Thirty-two is the output width of the hash it keys, which is the point below which the
  key rather than the hash bounds the work of confirming a guessed plaintext; a
  human-chosen string reduces that work much further still. `layouts.py` refuses, with a
  message naming this variable and the generation command but never the value: a value that
  is not valid standard base64 (which covers a passphrase and the URL-safe alphabet), a
  non-canonical base64 spelling, material decoding to fewer than 32 bytes, and material
  that is a single repeated byte. **The refusal now happens ONCE, at the command-line
  boundary, before any subcommand is dispatched**, and it is classified `16` — the
  environment tier — because no step has run at that point. It used to happen lazily,
  from the first value a command masked, which put it in two places nobody would look for
  a configuration fault: while printing an already-decoded record, and while composing the
  message of a decode refusal — where an unusable key was reported as though the delivered
  extract had failed to decode. Generate a conforming value **into the secret store, without
  it ever reaching a terminal**:

  ```bash
  # WHAT: generates 32 random bytes, encodes them as canonical standard base64, and stores
  #       the result as the masking secret without the value being displayed anywhere.
  # WHY : ⚠️ Refactoring Rationale: this block used to be two bare generation commands --
  #       `openssl rand -base64 32` and a `python3 -c` that PRINTED the encoded key -- with
  #       the operator left to move the value into the secret store by hand. That
  #       contradicted the obligation stated two bullets above, in the same section: the
  #       value is the key, and printing it writes it to the terminal, the scrollback, the
  #       shell history of whatever command consumed it next, and any transcript or session
  #       recording. A document that forbids echoing the key must not open with a command
  #       that echoes it. The generation is unchanged; where the bytes GO is what changed.
  # WHY : Trade-offs: the value travels on STDIN through `--secret-string fileb:///dev/stdin`
  #       rather than as an argument, because an argument is visible in the process table for
  #       the life of the call and is retained by the history file. `tr -d '\n'` removes the
  #       newline `print` adds, so the stored secret is the base64 text alone.
  # WHY : Assumptions: `set +o xtrace` is issued explicitly, because a traced shell echoes
  #       the expansion of every pipeline stage and would defeat the whole arrangement. The
  #       caller's tracing state is read into `xtrace_was_enabled` first and restored only if
  #       it was on -- a bare `set -o xtrace` at the end would turn tracing ON in a shell that
  #       never asked for it, so the next command an operator ran would echo whatever it
  #       carried. This is the same discipline as
  #       [deploy.md](../docs/runbooks/deploy.md)'s credential pipeline.
  # WHY : Assumptions: `--kms-key-id` names a CUSTOMER-MANAGED key. Omitting it is not
  #       neutral: Secrets Manager then encrypts under the account's AWS-managed
  #       `aws/secretsmanager` key, whose policy cannot be narrowed to the roles that should
  #       read this one. Creating that key and passing the same ARN here is
  #       [deploy.md](../docs/runbooks/deploy.md), which owns the secret's provisioning.
  # WHY : Assumptions: `--query null` keeps even the new version identifier out of the
  #       transcript, for the same reason the value is kept out of it.
  xtrace_was_enabled=0
  case "$-" in *x*) xtrace_was_enabled=1 ;; esac
  set +o xtrace
  python3 -c 'import base64,secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())' \
    | tr -d '\n' \
    | aws secretsmanager create-secret \
    --name "carddemo/<env>/mask-hmac" \
    --kms-key-id "<mask-hmac-cmk-arn>" \
    --secret-string fileb:///dev/stdin \
    --query "null" --output text
  if [ "$xtrace_was_enabled" = 1 ]; then set -o xtrace; fi
  ```

  Assumptions: there is deliberately no command here that reads the value back. The task
  definition projects it into the process from the secret reference, so nothing in a normal
  workflow needs to see it; a `get-secret-value` in a shell is the same disclosure the
  generation commands used to make, one step later.

  Setting the variable to an **empty or whitespace-only** value is **also refused**, and it
  is refused separately from leaving it out. Absence means "run without a supplied key" and
  takes the 32-byte process-scoped fallback; presence with no content means a key was
  expected and did not arrive, which is exactly what a failed secret projection or an empty
  secret version delivers. Accepting it silently cost the one mode that *needs* a supplied
  key — the cross-run verification pass — because that run then derived per-process tags,
  compared every field unequal, and reported nothing about the missing key. To run without a
  key, **omit the variable** rather than setting it blank.

  One limit of the enforcement is stated so it is not assumed away: a **hexadecimal** key is
  *accepted*. 64 hexadecimal characters are also valid base64 and decode to 48 bytes, so the
  value is used as base64 of bytes you did not intend — harmless, because the result is
  longer than the floor and no more guessable, but it is why exactly one encoding is
  documented here.
- **Rotation invalidates comparability, so rotate deliberately.** The tag is a function of
  the key, so a rotated key re-derives every tag: a verification pass that compares a
  rendering produced before rotation against one produced after will report differences
  that are not differences in the data. Rotate between load campaigns rather than during
  one, and supply the same key to both sides of any comparison. Absent the variable
  entirely, `layouts.py` falls back to a **process-scoped random key**, which makes tags
  comparable within one run and not across runs — the same hazard, permanently.

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
schema the dataset belongs to — the eight runtime roles in
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

**`usrsec` exists only in EBCDIC form, and publishes only an EBCDIC path.** There is no
`app/data/ASCII/usrsec.txt`; the only extract is
`app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS`. The `usrsec` reader therefore publishes no
`decode_ascii_*` / `iter_ascii_*` / `read_ascii_*` trio at all, and `--encoding ascii`
is refused for `SECUSER` by the shared reader factory as well. Refactoring Rationale:
the trio *was* published, on the grounds that a caller might legitimately hold converted
text. That was the wrong trade for this record specifically — see §8.1: a character entry
point can only be fed a transcode, and every transcode of this dataset is a copy of a
plaintext password written somewhere outside the one reference file meant to hold it. The
refusal is stated in both places so the path cannot be reached through one door after
being closed at the other.

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

**The same authority governs the other route into the same table.** `reference.disclosure_groups`
is reachable two ways — this package's loaders and the Flyway migration
[`V2__seed_reference.sql`](../services/reference-service/src/main/resources/db/migration/V2__seed_reference.sql)
— and until this checkpoint the two disagreed: the loaders read the EBCDIC extract and
stored 15.00 while the migration seeded 0.00 from the ASCII twin, so an identical account
on the `DEFAULT` fallback accrued a different amount of money depending on which route had
populated the row, with no error either way. That migration now seeds **15.00** for
`('DEFAULT   ','07','0001')` and states this authority at its own `disclosure_groups`
insert, so both routes agree. Assumptions: `disclosure_groups` is the only table where
the choice has an effect at all — the transaction-type and transaction-category twins are
byte-identical across the encodings, so the six other inserts in that migration are
unaffected by it and continue to cite the ASCII file they were read from. The divergence
between the two extracts is registered as `D-SEED-ENCODING-AUTHORITY` in
[`cobol-to-service-traceability.md`](../docs/architecture/cobol-to-service-traceability.md).

**What settles the authority is the baseline's own load job.**
[`app/jcl/DISCGRP.jcl`](../app/jcl/DISCGRP.jcl) L56-L61 defines the VSAM cluster and then
`REPRO INFILE(DISCGRP)`, and the `DISCGRP` DD at L57 names
`DSN=AWS.M2.CARDDEMO.DISCGRP.PS` -- the `.PS` dataset, which is the EBCDIC extract. No JCL
in the repository loads the `.txt` form at all. So 15.00 is not merely the value in the
better-preserved file; it is the value the reference system itself loads into the file
`CBACT04C` reads.

Assumptions: the parity oracle is unaffected by this choice, and that is worth stating
because three REFERENCE artefacts read 0.00 for this row and a reader who finds them will
ask. Those three are `app/data/ASCII/discgrp.txt` row 34,
[`tests/fixtures/interest/default_fallback/discgrp.txt`](../tests/fixtures/interest/default_fallback/discgrp.txt)
line 17 and [`tests/mocks/mock_discgrp.txt`](../tests/mocks/mock_discgrp.txt) line 33. The
last two are INPUTS the suite supplies to the program under test, so a golden-master
comparison feeds the same 0.00 to the reference program and to its migrated equivalent and
is indifferent to what any seed holds; and the reference-service fixture tree copies the
ASCII form deliberately, because in the EBCDIC form the `DEFAULT` group prices `07|0001`
identically to group `A000000000` and a fallback fixture built from it could not
discriminate a fallback from a direct hit at all.

Trade-offs: the per-dataset `--encoding {ascii,ebcdic}` selector is **required** on
`load-dataset` and on all three `verify-*` passes — see the inventory in
[§5.2](#52-subcommands-and-their-arguments). It is what lets an operator deliberately load
the ASCII twin of a dataset that ships both, and requiring it rather than defaulting it is
what stops the twin being selected by accident: with no default there is no accident
available, and with a sniffed encoding an all-ASCII EBCDIC extract would be read as text
and decode to plausible wrong values. Note that
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

**Only vetted single-byte EBCDIC pages are admitted, and an admitted one is proved.**
The module publishes the set as `SUPPORTED_CODE_PAGES`: `cp037`, the page the extracts
are in and the default; `cp1140`, cp037 with the euro sign, differing from it at exactly
one byte value; `cp500`, the international page, differing from cp037 at seven byte
values, every one a punctuation or symbol character; and `cp1047`, Latin-1 / Open
Systems, differing at eight. Any other page is refused before a byte of a dataset is
read, and an admitted page is proved once per process: all 256 byte values decode without
substitution, the decode consumes every byte and yields exactly one character per byte,
those 256 characters are distinct, re-encoding them returns the original 256 bytes
exactly, and the EBCDIC digit, sign-overpunch, letter, blank and low-value positions
carry the characters this package's own contracts read. Every field decode then repeats
the per-field half of that proof on the span itself — full consumption, one character per
byte, exact re-encode — so a codec whose two tables disagree is caught on the field that
exposes it.

Assumptions: the known-answer half is not redundant, and `latin-1` is the measurement
that shows why. It decodes all 256 byte values, maps them to 256 distinct characters and
re-encodes them byte for byte, so every structural test passes — and it places the digits
at 0x30 and the letters at 0x41, where EBCDIC places neither. A field decoded through it
keeps its declared width, so every later offset still looks valid and only the content is
wrong. Refusing a byte the page leaves undefined is necessary and nowhere near
sufficient. The euro-updated national family `cp1141` through `cp1149` is refused for the
converse reason: it maps **both** 0x15 and 0x25 to one character, so its 256 byte values
decode to 255 distinct characters and no exact byte-for-byte round trip exists for it at
all.

`ebcdic==2.0.1` is in the runtime closure for a reason that looks removable and is
not. Assumptions: nothing imports a symbol from it — it registers codecs as an import
side effect, so both a linter and a person tidying unused imports will read it as
dead. What it registers is the wider EBCDIC code-page family around cp037, which
CPython does not ship. Note what that means precisely: cp037 itself *is* a standard
library codec, so this pin is not what makes today's extracts decode; it is what lets an
extract in a **vetted** sibling page decode through the identical per-field path by
configuration alone, with no edit to the codec module, and `cp1047` is the vetted page
this pin alone provides. Dropping it breaks nothing visible until the first cp1047
dataset arrives.

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

The `usrsec` reader therefore **never slices the password span at all.** The field must
be declared in the layout — omitting it would move every field after it — so it is
declared and flagged sensitive, it is excluded from the reader's published field tuple,
and the loader has no column to map it to. Refactoring Rationale: carrying the field
forward into any column, hash or shadow table would reproduce in the target the exact
defect the migration exists to correct, and would do so in a datastore with a far larger
audience than a mainframe VSAM file. Identity moves to the managed user pool instead,
and credential recovery becomes an identity-provider reset.

Two further routes onto that value were closed after review, and both are worth naming
because each looked harmless in isolation:

- **The whole-record masked rendering** used to hand the record straight to the shared
  masker, which redacts a sensitive field by replacing it with an HMAC **of that field's
  own characters** — so the password was an input to a digest, and two different
  passwords produced two different tags. For an eight-character credential that tag is a
  confirmable oracle to anyone holding the key. The rendering now substitutes a fixed,
  same-width withheld-marker into the span **before** the masker runs, and re-imposes
  that marker on the output, so the span is identical under every key and derived from
  nothing in the record. Every *other* sensitive field still renders as a keyed tag,
  which is what keeps a masked diff useful.
- **The character decode path** was removed outright, in the reader and in the shared
  factory — see §6.2's note on this record for the reasoning.

**No artifact in this package reproduces those credentials in any form — not plaintext,
not masked, not digested, not keyed — and no example security record appears in this
document.**

### 8.2 Sensitive fields in diagnostic output

**Disclosure is decided by an allowlist, so silence means withhold.** Refactoring
Rationale: this section used to describe a *list of flagged fields* — account number, card
verification value, names, national identifier, government-issued identifier, date of
birth, telephone numbers, electronic-funds account identifier — and that description was
accurate about the mechanism and wrong about its consequence. Flagging one field at a time
makes an unflagged field disclosable, so a field transcribed with a plain factory was
printed verbatim. Measured across the twenty records that are not IMS authorization
segments, **116 distinct field names** were disclosable that way, including every account
balance, credit limit and cycle total, every transaction amount, the transaction-category
balance, every merchant name, city, postal code and identifier, the customer credit score
and the free-text transaction description.

`layouts.py` now inverts that. Two allowlists — one for the authorization segments and one
for the rest of the corpus — name every field a diagnostic may render, and every other
field of every record is withheld. A field admitted by name is one of **four** things: a date
or a time, a code from a small closed domain, a count or a sequence rather than a money value,
or the trailing pad. Four consequences are worth knowing before reading a diagnostic:

- an **account identifier is withheld**, in every record that carries one;
- an account's open, expiration and reissue dates are rendered and a **card expiry** date is
  not, because the latter is a credential rather than a lifecycle fact;
- a state and a country code are rendered and a **postal code** is not, because ten
  characters of postal code narrow a household where a two-character state does not;
- the three pure reference records — disclosure group, transaction type, transaction
  category — are rendered **in full**, rate and description included, because every byte of
  them is seeded configuration linked to no customer.

Refactoring Rationale: this section listed a **fifth** admission reason — "an account
identifier the published REST contracts already render in full" — and opened its consequences
with "an account identifier is rendered and a customer identifier is not". Both are withdrawn,
and the reasoning behind them is worth naming because it is easy to reach again. A REST path
and an operator diagnostic are different surfaces read by different populations: what a caller
may be told about its own account says nothing about what may be written into a retained log
store. [`docs/architecture/observability.md`](../docs/architecture/observability.md) settles it
— account identifiers are among the values a diagnostic must **omit**, "not its content, not
its length, and not a digest of it". Seven names were withdrawn from the corpus allowlist
(`ACCT-ID`, `EXP-ACCT-ID`, `CARD-ACCT-ID`, `EXP-CARD-ACCT-ID`, `XREF-ACCT-ID`,
`EXP-XREF-ACCT-ID`, `TRANCAT-ACCT-ID`). Every one of them was **already** marked sensitive at
its declaration site, so no diagnostic output changed; what changed is the stated policy and
the audit's admitted set — which is precisely the pair that had drifted apart.

The policy is enforced twice rather than documented once. `layouts.py` **refuses to import**
if any record it declares leaves a field disclosable that no allowlist names, and
`tests/test_corpus_disclosure.py` re-runs that audit over the fully imported module,
asserts the rendered output field by field, and holds the seven withdrawn names in its
`_PROHIBITED_NAMES` list so a record that ever disclosed one fails a test rather than passing
an audit.

Assumptions: a verification failure has to show *where* two records differ in order to be
actionable, and it must do that without emitting a complete cardholder identity or payment
number — so the masking is field-aware rather than all-or-nothing, and it is keyed by
`CARDDEMO_MASK_HMAC_KEY` — which is itself secret key material and is handled as such
([§5.7.1](#571-carddemo_mask_hmac_key-is-key-material-not-a-name)) — so the same value
masks consistently wherever that key is supplied, and consistently within one run only
where it is not.

**The partial reveal covers card numbers only.** Four field names — `CARD-NUM`,
`XREF-CARD-NUM`, `TRAN-CARD-NUM`, `DALYTRAN-CARD-NUM` — render as asterisks followed by
their real trailing four digits; every other withheld field renders a keyed tag carrying no
part of its value. Refactoring Rationale: `CUST-SSN` was in that set and is removed. "Quoted
by its last four" describes a practice for a card number and not a safe disclosure for a
nine-digit national identifier, whose remaining five digits are the issuing area and group;
and the ETL never matches on the field, so withholding it costs nothing here. This is a
deliberate divergence from the reference codec at `tests/helpers/record_codec.py`, which
still carries five names — that file is the parity oracle and is reference-only, and the
divergence is safe in this direction only because the oracle compares record *bytes* and
never a masked rendering.

`decode-record` ([§5.2](#52-subcommands-and-their-arguments)) is the first command to
print record content, and it applies exactly that discipline. One detail of its output is
worth knowing: a withheld **character** field shows a keyed tag of the field's own width,
while a withheld **numeric** field shows the fixed literal `<withheld>`. A decoded number is
rendered as its value — `194.00` for a twelve-byte zoned balance — so it is not the declared
width, and the only chunk a tag could be computed from there would be a constant. That tag
would be equal for two different balances while looking value-derived, so the literal is
printed instead: it claims nothing, which is the honest rendering.

Alternatives Considered: a `--reveal` flag that printed the cleartext was considered and
rejected. The command exists to prove a delivery decodes at its declared geometry, and
the redactions prove exactly that -- a last-four reveal shows the card number's own
trailing digits, and a keyed tag is stable within a run, so a maintainer can still tell
two records apart field by field. A reveal flag would put a cardholder's name and a
stored password into a container log for a check that never needed either, and a flag
defaulting to safe is still a flag an operator can pass.

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

### 9.0 Transfer integrity: one descriptor, one checksum, one reservation

Three properties hold for every staged object, and each replaces a specific defect.

**The extract is opened exactly once.** The digest pass and the transfer read the
same held descriptor, and its `fstat` identity — inode, device, size and nanosecond
modification time — is recorded at open and compared again after the transfer.
Refactoring Rationale: staging previously opened the pathname three separate times,
once to probe readability, once to digest and once to send, so the bytes measured and
the bytes uploaded were not provably the same file. That is a time-of-check to
time-of-use gap (CWE-367), and it was reachable because the staging directory is not
owned exclusively by the step that reads from it. The final component is opened with
`O_NOFOLLOW`, so a symbolic link planted at the extract's own name is refused rather
than followed. Assumptions: `O_NOFOLLOW` guards the final component only — an
intermediate directory symlink is still traversed — and that narrower scope is stated
here so the flag is not over-trusted.

**The digest is verified by the service, not merely recorded.** The SHA-256 is sent
as `ChecksumSHA256`, so S3 recomputes it over the bytes it received and rejects the
write on a mismatch. Trade-offs: the same digest is *also* stored as object metadata,
and the two are not redundant. Metadata is an opaque string the service never reads,
so on its own it records a *claim* that the transfer was intact; the checksum
parameter is what makes the service enforce it. Metadata is still written because the
later verification pass reads the expected digest from the object itself rather than
from whatever the staging step happened to log.

**Generation numbers are reserved, not computed.** Allocation is a conditional create
keyed by execution token, family and business date, so two writers cannot take the
same number and a retry reuses the number its first attempt took. Refactoring
Rationale: the earlier form listed the prefixes, took the maximum and added one — a
sequence with no atomicity anywhere. Two concurrent allocators read the same maximum
and computed the same successor, and the second write landed on the first one's key.
A retry was worse in the opposite direction: it saw its own completed write and
allocated the *next* number, staging a duplicate generation of identical bytes and
consuming one of the five the family retains.

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
the failure mode the next one catches, which is why the set is three rather than one.
Assumptions: `verify-all` is the interface that makes "verified" mean all three rather than two
of the three, and it is **registered** — [§5.2](#52-subcommands-and-their-arguments) tabulates
it and `--help` lists it. It runs the three passes in the order above and stops at the first
failure, returning that pass's own status. Two forms are available and they resolve extracts
differently: with `--manifest` it covers exactly the entries the manifest declares, whose
`source` values are **filesystem** paths; with no `--manifest` it covers the datasets the seed
registry declares, resolving each beneath `CARDDEMO_DATASET_STAGING_ROOT`, which may be an
`s3://` prefix.

⚠️ Refactoring Rationale: this paragraph asserted that `verify-all` was contracted, that
[§5.2](#52-subcommands-and-their-arguments) marked it "not registered", and that the parser
refused it as a usage error — then delegated the ordering guarantee to the
[data-migration runbook](../docs/runbooks/data-migration.md) "until it is registered". All of
that is now false and none of it is retained as a historical note, because this is the section a
reader consults to find out how to certify a load: told the one combined verb exits `2`, they
would run the three per-dataset passes by hand instead, which is the partial verdict this
section exists to prevent — and, worse, those three authenticate as the writable service role
while `verify-all` certifies on the read-only `carddemo_verifier`.

**Both SQL passes run as `carddemo_reporting`, the least-privilege read-only role, and
name no base table.** They read the two aggregate-only views
[`sql/V3__verification_surfaces.sql`](sql/V3__verification_surfaces.sql) creates —
counts, exact `NUMERIC` sums and strictly-negative row counts, and nothing row-level.
Refactoring Rationale: both files previously read the eleven base tables directly, so
neither could be run by that role at all; `row_counts.sql` documented an operator
principal instead and `money_totals.sql` named the **write-capable** `carddemo_batch`.
Two properties were missing and are now present: a verification pass cannot modify the
data it is verifying, and running one is not itself a row-level disclosure of every
balance, card number and identity record in the system. The output of both files is
byte-identical across the change — same columns, same eleven and nine rows, same exact
totals — because the aggregates were moved rather than rewritten.

**Measured coverage today: passes 1 and 2 serve all eleven loadable records; pass 3
serves the five that carry a money column.** Those five are `ACCOUNT` (five columns),
`DALYTRAN`, `TRAN`, `TCATBAL` and `DISGROUP` (one each) — nine money columns over five
tables, which is every `NUMERIC(p,2)` column the migration writes, so pass 3's reach is
not a subset of the money but the whole of it. The other six records carry no money at
all, so there is nothing for the pass to total and it declines rather than passing
vacuously.

⚠️ Refactoring Rationale: pass 2 served **three** of the eleven, and this paragraph
measured that shortfall rather than hiding it. The three were the reference records
`TRANTYPE`, `TRANCAT` and `DISGROUP`, whose every comparable column is `CHAR`, `VARCHAR`
or `NUMERIC`. Each of the other eight carries at least one `BIGINT`, `DATE`, `SMALLINT`,
`TIMESTAMP` or `UUID` comparable column, which the driver returns as an `int`, a `date`,
a `datetime` or a `UUID` — and the renderer accepted characters, an exact decimal or raw
bytes only, so the pass **raised** on such a column instead of reporting a difference. For
an identifier the two sides disagreed in representation as well, because a reader
publishes the declared full width with its leading zeros where the column holds a number.

What closed it: every persisted type now canonicalises under its own one-byte type tag with a
length prefix, and the comparison canonicalises **both sides by the form each field is
actually stored in**, rather than by the type that happens to carry it. The rule per field
is derived from the copybook's declared regime and then narrowed by the form the column
returned, so an identifier compares as a number against a `BIGINT` — `00000000011` and `11`
digest identically where the layout says the field is `PIC 9(11)` and only there — a date
compares as its ten characters against a `DATE`, a stamp compares as its twenty-six against a
`TIMESTAMP`, a subject compares as a parsed identifier against a `UUID`, and an unset column
compares as a null distinct from an empty string. The narrowing is not a refinement for its own
sake: five fields are declared `PIC 9(04)` and stored as `CHAR(4)` — the transaction category
code, whose leading zeros are part of the value under specification rule T1 — and comparing
those as numbers would have passed a corruption that changed only their padding. Trading a
false failure for a false pass would have been a worse outcome than the defect.

The pass also pairs the two sides by the target's **own key** rather than by position. The
source arrives in physical extract order and the read-back is put in key order in this process,
so a sequential extract such as `DALYTRAN` was previously reported as wholly different for
arriving in a different order — and a single row missing from the target shifted every
later pair by one, reporting one absent row as an absent row plus a tail of differences
that did not exist. A missing row is now reported once, at its own key.

Assumptions: the measurement stays in the section that states the requirement, because a
verification requirement that overstates its own reach is the failure mode this section
exists to prevent. The [data-migration runbook](../docs/runbooks/data-migration.md) now
prescribes all three passes for every dataset, and a test asserts that it does — so the
coverage cannot narrow again without failing the suite. The combined `verify-all` gate runs
them over the ten datasets that ship a committed extract — every registered dataset except
`TRAN`, which no extract seeds because posting is what fills it.

Assumptions: two further properties of pass 2 are worth stating here, because both are
invisible in its output when they hold and both are load-bearing when they do not. Its framing
is **type-and-length prefixed** rather than delimiter-joined, so no field content can forge a
field or record boundary — a value carrying U+001F or U+001E, which both the cp037 and ASCII
decode paths admit, previously could. And the two sides are put in **one order** before they are
walked: a keyed record is ordered on the key window the descriptor declares, in this process
rather than through an `ORDER BY`, so a linguistic server collation cannot sort the two sides by
two different rules; the sequential daily-transaction feed, whose table keys on an identity
column no extract supplies, is ordered by that persisted ingest sequence, which is its insertion
order. A key one side carries and the other does not is reported as an unpaired position at its own
key rather than compared through, so "the two sides do not hold the same records" cannot be
mistaken for a field defect.

**Pass 2 additionally audits the three sealed columns.** For each of `CARD` and `CUSTOMER`
it counts how many source records hold a value the load must seal, reads back only the
`*_encrypted` column, and asserts one well-formed envelope per such value — the framing
marker, the minimum envelope length, and nothing of the contents. Assumptions: this is
part of pass 2 rather than a fourth pass because it answers pass 2's own question about the
three columns pass 2 cannot digest: a sealed column is excluded from the record digest by
construction, since ciphertext differs on every write, so without the audit those three
columns were the only ones no pass looked at. A plaintext value stored in a column whose
name asserts ciphertext would satisfy every count and every total.

**What the reporting role may actually read, stated in full.** Refactoring Rationale: this
package used to describe that role as holding `SELECT` on "the two aggregate verification
views and nothing else", in a schema that "owns no table". Neither half was true, and the
overstatement made the security claim weaker than the one that holds, because a reader who
checked the topology and found it wrong had no way to tell whether the read-only conclusion had
been checked either. The `reporting` schema holds **nine views, one table and one function**:
the seven product views [`sql/V1__reporting_views.sql`](sql/V1__reporting_views.sql) creates for
`reporting-service`, the two aggregate verification views
[`sql/V3__verification_surfaces.sql`](sql/V3__verification_surfaces.sql) creates for the two SQL
passes, the `card_grouping_key` table that holds the statement view's grouping secret, and the
`resolve_card` lookup function. `carddemo_reporting` holds `USAGE` on the schema, `SELECT` on all
nine views and `EXECUTE` on the function — and **nothing on the table**, which `V0` and `V1` each
revoke explicitly. Assumptions: the read-only guarantee rests on the privileges the role does
**not** hold rather than on the schema being empty — no privilege on any base table, no
`INSERT`, `UPDATE`, `DELETE` or `TRUNCATE` anywhere, no `CREATE` even here, and every privilege it
might have inherited on `ledger`, `account`, `card` and `reference`, present and future,
explicitly revoked in [`sql/V0__schemas_and_roles.sql`](sql/V0__schemas_and_roles.sql). That
argument stays true when a tenth view is added; an inventory does not.

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

The mechanism is [`verify/checksum.py`](src/carddemo_migration/verify/checksum.py)'s
`deterministic_field_names`, which resolves each candidate field against the layout and
drops the ones marked; `cli.py` passes the target's comparable field set through it
before either side is digested. Excluding a stamp is not the same as ignoring it:
`validated_timestamp` admits the field out of the compared span only when its value is
one of the two spellings CardDemo writes or is uniformly unwritten — the 26 blanks the
shipped daily-transaction extract carries — and raises on anything else. Assumptions:
blanking by position with no shape check would discard a record sliced at the wrong
offset, or a corrupted one, along with the stamp, which is the class of defect this pass
exists to surface rather than absorb.

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
- The sixteen login roles — eight runtime, seven migration and one read-only
  verification — are created with
  **no** credential clause, because a credential written into a committed SQL file
  is the defect this migration is correcting. The eight `carddemo_<context>_owner`
  roles get no credential clause either, and for them it is permanent: they are
  `NOLOGIN`, so schema ownership is unreachable by authentication. `apply-credentials` ([§5.2](#52-subcommands-and-their-arguments)) is the
  delivered mechanism that makes them able to authenticate, and it must run
  immediately after this script and before any loader.

  The sixteenth role, `carddemo_verifier`, holds `USAGE` and `SELECT` on the five
  loaded schemas and nothing else — no write privilege, no `CREATE`, no sequence
  privilege, no ownership — and carries `default_transaction_read_only = on`.
  Assumptions: the three per-dataset verification passes read loaded rows back and
  compare them against the extract, and they used to obtain that read as the bounded
  context's own runtime role — the credential the load step immediately before them
  writes with. A verification able to alter what it certifies certifies nothing, so
  the passes now connect through
  [`verify/session.py`](src/carddemo_migration/verify/session.py), which resolves this
  role's own credential, asks the server who the session is, and reads
  `transaction_read_only` back before any pass runs.

The same ordering constraint is what makes
[`sql/V2__runtime_delete_grants.sql`](sql/V2__runtime_delete_grants.sql) a separate file
rather than another section of `V0`, and that file must run **AFTER** every per-service
migration — the exact inverse of the rule above. It grants `DELETE` on three tables and
only three: `reference.transaction_types` and `reference.transaction_categories`, behind
the two published delete routes, and `"authorization".auth_reply_outbox`, behind
`OutboxPublisher.purgePublished()`'s scheduled retention sweep.

Assumptions: the two forms of grant available to `V0` before any table exists —
`GRANT ... ON ALL TABLES IN SCHEMA` and `ALTER DEFAULT PRIVILEGES` — are both
schema-wide, and `reference` holds six tables of which only two have a delete operation
at any layer. Expressing this in `V0` would therefore have had to be four tables wider
than the contract. Trade-offs: a second file is one more artifact an operator has to
apply in the right order, which is a real cost, and it is preferred to a grant that is
correct in aggregate and wrong per table. [`sql/verify/runtime_delete_grants.sql`](sql/verify/runtime_delete_grants.sql)
is the paired check; it returns rows only on failure and covers both directions — the
three tables that must be deletable, and every other table in those schemas, which must
not be.

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

The grant is also narrower than "write on two schemas", and it is worth stating privilege
by privilege rather than in aggregate, because three of the seven lines below are
read-only and one is a single named table. This is exactly what
[`sql/V0__schemas_and_roles.sql`](sql/V0__schemas_and_roles.sql) grants `carddemo_batch`:

| Schema | Privilege | Why exactly that |
|:---|:---|:---|
| `ledger`, `account`, `reference`, `card` | `USAGE` on the schema | Schema `USAGE` is the prerequisite for reaching any object inside it, so it is granted on all four and confers nothing by itself |
| `ledger` | `SELECT`, `INSERT`, `UPDATE` on tables | The posting job writes the posted transaction and the reject stream, and both creates and updates the category balance; the interest job writes its generated transaction. Each of insert and update is demanded by a named write site, so neither is speculative headroom |
| `ledger` | `USAGE`, `SELECT` on **sequences** | A separate grant, and the single easiest privilege in the file to omit: an insert into a table with an identity key also consumes the backing sequence, which needs `USAGE` in its own right. Omitting it errors nowhere until a row is inserted inside the nightly window. Only `ledger` needs it, because `ledger` holds the only tables this role inserts into |
| `account` | `SELECT` on tables | The posting and interest jobs read the account master, and the pre-posting step reads the cross-reference |
| `account.accounts` | `UPDATE`, **by name only** | The two write sites in the whole nightly chain against this schema both rewrite an account master that already exists, so one named table is the exact privilege |
| `reference` | `SELECT` on tables | The interest job reads the disclosure-group rate and never writes it. Reference data is maintained through reference-service and seeded by its own migration, never by the nightly chain |
| `card` | `SELECT` on tables | Read-only, for the export job's card phase. `app/cbl/CBEXPORT.cbl` declares the card master `ACCESS MODE IS SEQUENTIAL` at L61, opens it `OPEN INPUT` at L228, reads it at L513 and closes it at L560, and the only `WRITE` in that paragraph (L527–L545) targets the export output record rather than the card file — so `SELECT` is the whole of what the batch chain performs on this schema |

Refactoring Rationale: the `UPDATE` on `account.accounts` is granted **by name**, inside a
guard that checks the table exists, rather than through the `ALTER DEFAULT PRIVILEGES`
form the earlier revision used. The broader form was withdrawn because it covers every
table the account context ever creates, now and in future — including `account.customers`,
which carries the encrypted national and government-issued identifiers and which no batch
step has any reason to modify — and a table added in a later migration would become
writable the moment it was created, with nothing in the script changing to say so.
Trade-offs: a named grant has to be issued after the table exists, so `V0` reports the
grant as outstanding on a first bootstrap run and applies it on the re-run that follows
the per-service migrations. The script is idempotent, so that re-run is the documented
sequence rather than a workaround, and an outstanding grant named in the output is the
right direction to fail in — an over-broad one is invisible.

Refactoring Rationale: the `card` line is read-only and was **removed and then
reinstated**, and both movements are recorded so the second does not read as a silent
reversal of the first. The removal was right about the justification it deleted: that
justification claimed `CBTRN01C` validates the daily feed against the card master, and the
source contradicts it — that program opens `CARD-FILE` and closes it without ever issuing
a read, and the cross-reference it does read maps to `account.card_xref`. The grant is not
reinstated on that reading. What changed is the removal's second premise, that no batch
entity mapped the card schema: `com.carddemo.batch.domain.Card` now maps `card.cards` and
the export job's card phase walks it in key order, so the privilege rests on a mapped
entity and a named read. Trade-offs: it is `SELECT` and nothing more, and the narrowness
is the point — every card row holds a primary account number and an enciphered
verification value, so a write privilege would let a job that only reads the master alter
or destroy it. `CrossSchemaPrivilegeContractTest` in the shared kernel asserts this list
against the batch module's mapped schemas and its session search path **in both
directions**, so a grant with no call site and a call site with no grant each fail the
build rather than passing review.

---

## 12. Tests

```bash
# WHAT: run this package's own tests.
# WHY : Assumptions: pyproject.toml sets testpaths, python_files, --strict-markers,
#       --strict-config and xfail_strict, so the invocation carries no configuration of
#       its own -- passing a marker expression or an extra path here would bypass a
#       setting a reader cannot see in the command. The package must already be
#       installed (section 4); otherwise collection fails at the first import.
# WHY : Assumptions: the environment activated is `data-migration/.venv`, this package's
#       own, and never the repository-root `.venv`. The two hold conflicting
#       `cryptography` pins, so running these tests under the parity oracle's
#       environment is not a shortcut -- it is the configuration section 4 exists to
#       prevent.
source data-migration/.venv/bin/activate
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
# WHY : Assumptions: this runs in the same `data-migration/.venv` activated above, so the
#       pipeline and a local run resolve one dependency closure.
mkdir -p data-migration-reports
python -m pytest data-migration/tests --junitxml=data-migration-reports/pytest.xml
```

All **39** modules are delivered — **38** test modules plus the shared `conftest.py`:

<!-- carddemo:test-module-roster:begin -->
[`conftest.py`](tests/conftest.py),
[`test_aurora_loader.py`](tests/test_aurora_loader.py),
[`test_authorization_disclosure.py`](tests/test_authorization_disclosure.py),
[`test_card_protected_value.py`](tests/test_card_protected_value.py),
[`test_cli.py`](tests/test_cli.py),
[`test_config_name_contract.py`](tests/test_config_name_contract.py),
[`test_corpus_disclosure.py`](tests/test_corpus_disclosure.py),
[`test_credentials.py`](tests/test_credentials.py),
[`test_database_trust.py`](tests/test_database_trust.py),
[`test_declaration_shadowing.py`](tests/test_declaration_shadowing.py),
[`test_docstring_gate.py`](tests/test_docstring_gate.py),
[`test_doubles.py`](tests/test_doubles.py),
[`test_ebcdic_code_page_allow_list.py`](tests/test_ebcdic_code_page_allow_list.py),
[`test_ebcdic_codec.py`](tests/test_ebcdic_codec.py),
[`test_gate_inventory.py`](tests/test_gate_inventory.py),
[`test_loaders.py`](tests/test_loaders.py),
[`test_mask_key_material.py`](tests/test_mask_key_material.py),
[`test_master_disclosure.py`](tests/test_master_disclosure.py),
[`test_online_write_lease.py`](tests/test_online_write_lease.py),
[`test_package_surfaces.py`](tests/test_package_surfaces.py),
[`test_packed.py`](tests/test_packed.py),
[`test_protected_columns.py`](tests/test_protected_columns.py),
[`test_reader_factory.py`](tests/test_reader_factory.py),
[`test_reader_hardening.py`](tests/test_reader_hardening.py),
[`test_readers.py`](tests/test_readers.py),
[`test_readme_inventory.py`](tests/test_readme_inventory.py),
[`test_reporting_views.py`](tests/test_reporting_views.py),
[`test_s3_stage.py`](tests/test_s3_stage.py),
[`test_seed_datasets.py`](tests/test_seed_datasets.py),
[`test_seed_user_subjects.py`](tests/test_seed_user_subjects.py),
[`test_shared_doubles.py`](tests/test_shared_doubles.py),
[`test_source_hardening.py`](tests/test_source_hardening.py),
[`test_step_functions_asl_contract.py`](tests/test_step_functions_asl_contract.py),
[`test_symbol_uniqueness.py`](tests/test_symbol_uniqueness.py),
[`test_timestamp.py`](tests/test_timestamp.py),
[`test_verification.py`](tests/test_verification.py),
[`test_verification_authority.py`](tests/test_verification_authority.py),
[`test_verify.py`](tests/test_verify.py) and
[`test_zoned.py`](tests/test_zoned.py).
<!-- carddemo:test-module-roster:end -->

No test module is contracted.

Refactoring Rationale: **this roster is now machine-checked, and it is machine-checked because
re-measuring by hand did not work.** It previously read "All sixteen modules are delivered",
listed **seventeen**, and omitted **seven** that existed — `test_card_protected_value.py`,
`test_doubles.py`, `test_mask_key_material.py`, `test_master_disclosure.py`,
`test_online_write_lease.py`, `test_seed_datasets.py` and `test_shared_doubles.py` — while the
paragraph beneath it asserted that "the count and the module list are re-measured ... rather
than incremented". A claim of having re-measured is worth less than nothing when it is wrong,
because it stops the next reader checking. It went stale a second time, at **25** stated against
**27** listed and **33** present, which is what the check below then reported rather than a reader
noticing; all three figures are re-measured together from the directory, and none is adjusted by the
number of modules anybody believes was added.
[`tests/test_readme_inventory.py`](tests/test_readme_inventory.py)
reads the fenced region above, parses the file names out of it, equals that set against
`data-migration/tests/*.py` in **both** directions, checks that each entry's link target
resolves to the module its label names, and checks both stated counts against the same walk. A
module added or removed without touching this list fails a test.

Assumptions: the fence comments delimit the roster so the check reads a bounded region rather
than the whole document. Without them a link to a test module anywhere else in this README —
and there are several — would be swept into the roster, and the check would fail on a correct
document.

Trade-offs: the **test total** is deliberately not stated here. It was "**741 tests**", a
figure the suite passed years of edits ago; the current run collects **1599**. A total changes
on every test added, so pinning it in prose guarantees a stale number and pinning it in a test
guarantees a failing build on every legitimate addition. What a reader wanting the number
should do instead is run
`PYTHONPATH=data-migration/src python -m pytest data-migration/tests -q --collect-only | tail -1`,
which reports it from the suite rather than from a memory of it.

Assumptions: `test_ebcdic_code_page_allow_list.py` is a separate module from
`test_ebcdic_codec.py` even though both exercise one source file, because the two ask
different questions. The codec module asserts what a decode PRODUCES from the shipped
extracts; the allow-list module asserts which code pages are admitted to produce it at
all, and it registers and unregisters a deliberately lossy codec to reach the
one-byte-per-character post-condition that no real page can violate. Keeping that
registry manipulation in its own module is what stops a failure there being read as a
failure of the corpus assertions next door.

Assumptions: `test_cli.py` states the registered set as a literal tuple in registration
order rather than reading it back from the parser, and it keeps an empty denial tuple beside
it. A test that read the parser back would have accepted a command added silently and would
equally have accepted one disappearing. The denial tuple is kept empty rather than deleted
because it is the mechanism by which a future documented-but-unbacked verb is refused
explicitly — an unimplemented command reaching `--help` is how an orchestrator comes to be
wired to a state that cannot run. The case that consumed it was replaced rather than left
parameterised over an empty sequence — a parameterised case over nothing collects nothing and
reports green, which is the vacuous pass this suite refuses everywhere else. What replaced it
asserts the emptiness directly and still exercises the refusal path with a name that is not a
command.

Refactoring Rationale: that tuple held `verify-all` for as long as the verb was
unregistered, and this paragraph described asserting its absence. Both changed together:
the verb is registered, the tuple is empty, and the assertion it once carried is now the
positive one in the registered tuple's last position.

Assumptions: the two codec modules assert their vectors as LITERAL BYTES rather than by
round-tripping each codec through its own inverse. A round trip agrees with itself
whatever it does, so it cannot tell a correct nibble order from a reversed one or a
big-endian read from a little-endian one; a literal span can, and the spans used are the
ones a COBOL compiler emits. Where a shipped extract carries the value, the extract is
read directly instead -- `test_ebcdic_codec.py` decodes `app/data/EBCDIC` images, so a
pass is evidence about this corpus and not about an invented one.

Trade-offs: `test_packed.py` asserts the DECLARED digit capacity rather than the
representable range of the underlying halfword or fullword. A four-digit `COMP` field can
physically hold 65535, and the codec refuses it; asserting the physical range instead
would have been laxer and would have let a five-digit quantity flow into a column sized
for four.

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
  in [§6.2](#62-seven-facts-a-reader-would-otherwise-rediscover-the-hard-way) exists
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
an empty `ignore` list. The **`D`** entry is the pydocstyle family, and it is the primary
half of Rule 1's mechanical enforcement for Python in this repository: it checks the
formatting of every docstring it finds, and it requires a docstring on every **public**
module, class and function.

**Presence on private and nested declarations is a second gate, not this one.**
[`tests/test_docstring_gate.py`](tests/test_docstring_gate.py) walks the same two trees
— plus `config/rule1`, the repository-wide Rule 1 lexical gate, as a third root, because
a nested helper there shipped undocumented past every wired Python gate — with the
standard library's `ast`, and asserts a non-blank docstring on every module, class and
function at **every** visibility and **every** nesting depth. It exists
because `D101`, `D102`, `D103` and `D106` are public-declaration checks, so — measured
against the pinned ruff rather than inferred — a declaration is invisible to them when
its own name carries a single leading underscore, when any enclosing class is privately
named, or when it is declared inside a function body at any depth and any visibility. A
module follows the same single-underscore convention, so `_x.py` raises no `D100` while
`__init__.py` is public and does raise `D104`. Measured **when the gate was added**, and
deliberately not restated as a standing figure: **90 of the 221** declarations in
`src/carddemo_migration` were invisible to those checks, and **32 of the 107** in
`tests`. The live figures are whatever the gate reports, which is where they belong — a
count written into prose goes stale on the next authored function and the staleness is
invisible. The formatting rules (`D400`, `D403`, `D205` and the rest) were measured to
reach every docstring regardless of visibility or nesting, so the second gate
deliberately checks presence only and duplicates nothing. Both gates are load-bearing;
removing either leaves an unenforced half.

**What neither gate can decide, stated because the gap is wide.** Assumptions: no `D`
rule cross-checks a docstring against the signature it documents, so a one-line
docstring on a five-parameter function passes every `D` rule while still failing the
obligation, and a docstring that says nothing satisfies the presence gate for the same
reason. Completeness of the purpose, parameter, return and raised-exception content, and
the quality of the `WHY` rationale, are therefore a **required human-review check** on
every change here. A green `ruff check` plus a green `pytest` run is evidence about the
gates' coverage and is not evidence of documentation compliance.

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
> Assumptions: every one of the fourteen subcommands
> [§5.2](#52-subcommands-and-their-arguments) tabulates is reachable from this image,
> including `verify-all` — which is what the nightly chain's `VerifyMigration` state
> invokes as a container command override, so an image that refused it would fail the
> chain at its gate. What `load-dataset`, the three individual `verify-*` passes and the
> aggregate over them still require is a provisioned cluster and a resolvable credential,
> neither of which the image carries — and an aggregate driven by a `--manifest`
> additionally requires the manifest and the extracts it names to be reachable INSIDE the
> container, which is a mount rather than an argument.
>
> Assumptions: `verify-all`, `verify-row-count-report` and `verify-money-total-report`
> additionally need the `sql` tree, which the image copies into its `/opt/carddemo` working
> directory — so each takes an explicit `--sql-root` here, either `.` or the absolute
> `/opt/carddemo` the nightly chain passes, where a source checkout omits the flag entirely.
> The package-relative default resolves the tree beside the package, which is correct in a
> checkout and in an editable install and cannot work from a plain wheel.

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
invocation on a read-only identity, and why the three per-dataset verbs are diagnostics
rather than a substitute for it
([§10](#10-verification), [§5.2](#52-subcommands-and-their-arguments)).

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
([§6.2](#62-seven-facts-a-reader-would-otherwise-rediscover-the-hard-way)).

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
   when a command runs. The environment carries names and modes, with exactly one
   documented exception — `CARDDEMO_MASK_HMAC_KEY` holds the redaction key itself and is
   injected from Secrets Manager as a task secret, never as a plain value
   ([§5.7.1](#571-carddemo_mask_hmac_key-is-key-material-not-a-name)).
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
