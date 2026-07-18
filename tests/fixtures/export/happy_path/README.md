# Fixture scenario: `export/happy_path`

Deterministic master-file inputs for the **export → import byte-identical
round-trip** integration test
(`tests/integration/test_export_import.py::test_export_import_roundtrip`).

## Purpose

This scenario supplies the **complete** five-master input set that `CBEXPORT`
reads and `CBIMPORT` re-materialises, so the round-trip test can bind to a
dedicated `export` domain rather than falling back to `provisioning/happy_path`
(which ships only four of the five masters — it has **no transaction file**).
Creating this directory closes QA finding **F-EXP-FIXTURE**.

`tests/integration/test_export_import.py` prefers this directory automatically:
its `_SCENARIO_PREFS` lists `export/happy_path` first, ahead of
`provisioning/happy_path`.

## Contents

| File           | Records | Reclen | Layout (copybook / codec) | Provenance |
|----------------|---------|--------|---------------------------|------------|
| `custdata.txt` | 5       | 500    | `CVCUS01Y` / `CUSTOMER`   | copied verbatim from `tests/fixtures/provisioning/happy_path/custdata.txt` |
| `acctdata.txt` | 5       | 300    | `CVACT01Y` / `ACCOUNT`    | copied verbatim from `tests/fixtures/provisioning/happy_path/acctdata.txt` |
| `cardxref.txt` | 5       | 50     | `CVACT03Y` / `XREF`       | copied verbatim from `tests/fixtures/provisioning/happy_path/cardxref.txt` |
| `carddata.txt` | 5       | 150    | `CVACT02Y` / `CARD`       | copied verbatim from `tests/fixtures/provisioning/happy_path/carddata.txt` |
| `trandata.txt` | 5       | 350    | `CVTRA05Y` / `TRAN`       | first 5 records of the shipped seed `app/data/ASCII/dailytran.txt` |

All files are fixed-width, newline-delimited (one `\n`-terminated record per
line), and pre-sorted ascending by their primary key, which begins at byte 0 —
the record geometry the harness's indexed loader (`load_input`, the `IDCAMS
REPRO` analog) requires.

## Why the four master files are copied from `provisioning/happy_path`

**Assumption / Trade-off.** Those four fixtures are an already-validated,
production-shaped master set (the provisioning integration test loads them into
indexed files successfully), so reusing them here gives a known-good, minimal
five-record-per-master input without re-deriving data. They are **copied**, not
referenced in place, precisely so this in-scope `export` scenario is fully
isolated and this work never modifies `provisioning/happy_path`, which
out-of-scope checkpoints depend on.

## Why the transaction fixture is sliced from the daily-transaction seed

**Assumption (verified against the copybooks).** The export round-trip binds the
transaction master through the `TRAN` codec layout (`CVTRA05Y`, 350-byte record,
16-byte key at offset 0). `CVTRA05Y` (`TRAN-RECORD`) and `CVTRA06Y`
(`DALYTRAN-RECORD`, the layout of the shipped `dailytran.txt` seed) are
**structurally identical** — the same 14 fields in the same order with the same
`PIC` clauses (`X(16) X(02) 9(04) X(10) X(100) S9(09)V99 9(09) X(50) X(50) X(10)
X(16) X(26) X(26) X(20)` = 350 bytes); they differ only in the `TRAN-`/`DALYTRAN-`
field-name prefix. A seed daily-transaction record is therefore a byte-compatible
**and** semantically-valid `TRAN` record, so the first five seed records serve as
a correct `TRANSACT` fixture with no transformation.

**Trade-off (referential integrity).** `CBEXPORT`/`CBIMPORT` move whole records
per file with **no cross-file join** — the export dataset tags each record by
type and the import re-materialises each type independently — so the round-trip's
correctness does not depend on the transaction card numbers existing in the
five-record `carddata.txt` subset. Byte-exact round-trip identity (the property
this scenario exists to prove) holds regardless. Full cross-file referential
integrity was therefore not engineered into this minimal fixture; if a future
test needs it, replace `trandata.txt` with transactions whose `TRAN-CARD-NUM`
values are drawn from `carddata.txt`.

## Volatile fields (excluded from byte comparison)

The `TRAN` layout carries a runtime `TRAN-PROC-TS` timestamp at offset 304
(26 bytes) that the comparator blanks via
`tests.helpers.record_codec.normalize_timestamps` before comparing, so the
round-trip stays deterministic. The other four master layouts carry no volatile
field and are compared as-is (after the standard trailing-`FILLER` normalisation
the test applies — see the `_normalize_trailing_filler` helper and QA finding
F-EXP-FILLER).

## Source data is never modified

Both source trees — `tests/fixtures/provisioning/happy_path/` and
`app/data/ASCII/` — are REFERENCE only (AAP Section 0.8.2). This scenario is a
newly-created, self-contained copy; it neither edits nor depends on mutation of
either source.
