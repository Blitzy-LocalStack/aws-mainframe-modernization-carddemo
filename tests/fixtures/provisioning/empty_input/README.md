# Provisioning fixture — `empty_input` (0-record master files)

These fixtures drive the four CardDemo read/print **provisioning** programs
(`CBACT01C`, `CBACT02C`, `CBACT03C`, `CBCUS01C`) with **present-but-empty**
versions of their master files, so the suite can verify that each program
handles a **completely empty input** cleanly — reading, hitting immediate
end-of-file, and finishing at `RETURN-CODE = 0` without processing (or abending
on) a single record. This is the explicit **empty-file verification** for the
`provisioning` domain.

> **Why this README exists.** The fixtures in this folder are static `.txt`
> files — and, uniquely here, **0-byte** files — so they cannot carry
> docstrings. Per the project Explainability rule (AAP §0.10.1), this document
> is the mandated *why* for their (absent) bytes. The authoritative byte-level
> encoding contract — field widths, offsets, the zoned-decimal sign-overpunch
> table, and the line-ending rules — lives in the master
> **[`tests/fixtures/README.md`](../../README.md)**. This scenario README only
> records what is specific to `empty_input`; it **references** the master and
> never restates or contradicts it. In particular, it resolves the master's §7
> open question for this scenario: these are **0-byte** files, *not* files
> "containing no lines."

## Scenario intent (edge case — empty-file handling)

`provisioning/empty_input` supplies a present-but-**EMPTY (0-record)** version of
each of the four CardDemo master files. The purpose is to prove that the
read/print programs treat "the dataset exists but has nothing in it" as a normal,
successful run — not as an error and not as a record to process.

## Fixture files in this folder

All four files below are **truly empty — 0 bytes / 0 records** (verifiable with
`wc -c` == 0 **and** `wc -l` == 0). Each stands in for one CardDemo master and
is bound at run time to the program's `SELECT … ASSIGN TO <NAME>` via the
environment wiring described in the master README (§4.2).

| Fixture file | ASSIGN / DD name | Consuming program | Copybook (RECLN) | Organization (key) |
|---|---|---|---|---|
| `acctdata.txt` | `ACCTFILE` | `app/cbl/CBACT01C.cbl` | `CVACT01Y` (300) | INDEXED (key 11 @ 0) |
| `carddata.txt` | `CARDFILE` | `app/cbl/CBACT02C.cbl` | `CVACT02Y` (150) | INDEXED (key 16 @ 0) |
| `cardxref.txt` | `XREFFILE` | `app/cbl/CBACT03C.cbl` | `CVACT03Y` (50)  | INDEXED (key 16 @ 0) |
| `custdata.txt` | `CUSTFILE` | `app/cbl/CBCUS01C.cbl` | `CVCUS01Y` (500) | INDEXED (key 9 @ 0)  |

## Expected outcome

For each program the observable behaviour is identical and trivial:

1. DISPLAY `START OF EXECUTION OF PROGRAM <name>`.
2. Read → **immediate EOF**; the main read loop exits without ever entering the
   record-display path (no record blocks are emitted).
3. DISPLAY `END OF EXECUTION OF PROGRAM <name>`.
4. `GOBACK` with **`RETURN-CODE = 0`**.

Summarised, the scenario asserts **record counts `0 / 0 / 0 / 0`** and **`RC = 0`**
across `CBACT01C` / `CBACT02C` / `CBACT03C` / `CBCUS01C`. This expectation is the
"data-out" side of the golden-master model: it is mirrored by the sibling golden
tree `tests/golden/provisioning/empty_input/` (a parallel tree authored
separately, **not** created in this folder) and asserted by the integration
module `tests/integration/test_provisioning.py`.

## Why a 0-byte file was chosen (not one blank record)

- **Alternatives Considered.** A single all-blank record would still be **one**
  record (`wc -l` == 1) and would push each program *into* its record-processing
  path — display, count, and the downstream write/format paragraphs — which is
  the opposite of what this edge case is meant to exercise. A **truly empty
  file** is the only faithful model of "no records," so it was chosen; the
  distinction is material because the loader and codec must be told which kind of
  "empty" this is (master README §7).

- **Assumption / mechanism (verified against source).** The flat→indexed loader
  `tests/helpers/vsam_loader.py` builds an **empty** GnuCOBOL indexed file from
  the 0-byte flat input (its `IDCAMS REPRO` analog writes zero records into a
  valid, openable dataset). On the program's **first `READ`** the file status is
  therefore `'10'` (EOF), which drives the shared read paragraph to
  `MOVE 16 TO APPL-RESULT` → the 88-level **`APPL-EOF`** → `MOVE 'Y' TO
  END-OF-FILE`. The main `PERFORM UNTIL END-OF-FILE = 'Y'` loop then exits
  immediately, so no record is ever displayed. All four provisioning programs
  share this identical read-loop / EOF pattern.

- **Why `RC` stays 0.** The fail-fast abend path (`9999-ABEND-PROGRAM`, which
  calls Language Environment `CEE3ABD` with abend code 999) fires only for a file
  status that is neither `'00'` nor `'10'`. A clean EOF (`'10'`) does **not** take
  that path, so each program returns `RETURN-CODE = 0`.

## Encoding note

Because there are **zero records**, the fixed-width layouts, the zoned-decimal
sign-overpunch rule, and the LF / trailing-newline convention from the master
[`tests/fixtures/README.md`](../../README.md) (§3) do **not** apply here — there
is simply no content to encode.

## Loader & determinism

These masters use the **same INDEXED organization and load parameters as the
sibling `happy_path` scenario** — ACCOUNT reclen 300 / key length 11; CARD 150 /
key 16; XREF 50 / key 16; CUSTOMER 500 / key 9; all with `key_offset = 0` (the
primary key is the leading bytes of each record). The only difference is that the
flat input is empty, so `tests/helpers/vsam_loader.py` produces an empty (but
valid) indexed file. Determinism is trivial for this scenario — there is no
content and no run-varying timestamp to normalise — so every run is byte-identical
and the `0/0/0/0` + `RC=0` golden holds reproducibly.

## Seeds are not edited

The four files here are **new, empty files** — they are **not** derived rows and
**not** partial copies of anything. The non-empty ASCII seeds
`app/data/ASCII/{acctdata,carddata,cardxref,custdata}.txt` (each **50 records**)
are **REFERENCE ONLY and are never modified** (AAP §0.8.2). They are named in the
table above **solely to establish provenance** — i.e. which CardDemo master each
empty fixture stands in for — and no seed content is copied into this folder.

## Data governance / synthetic provenance (MA-24)

This scenario contains **no cardholder data at all**: all four fixtures are
**0-byte** files, so there are no PANs, account ids, names, SSNs, DOBs, or
government ids present. The non-person seed masters these empty files stand in for
(`app/data/ASCII/{acctdata,carddata,cardxref,custdata}.txt`) are themselves
**synthetic** fabricated demonstration data shipped with the upstream open-source
AWS CardDemo project, representing **no real person or account**. See master
[`tests/fixtures/README.md`](../../README.md) §10 for the full attestation.

> **WHY this is recorded even for an empty scenario (compliance completeness).**
> The MA-24 attestation is a per-scenario Explainability carrier requirement; a
> reviewer auditing data governance should be able to open *any* scenario README —
> including the empty ones — and find the provenance statement, rather than having
> to infer that "0 bytes" implies "no sensitive data."
