# Golden Masters — provisioning / empty_input

## 2. Intent / scenario

This directory holds the **byte-deterministic expected outputs** for the four
AWS CardDemo master-data *read/print* programs when each is run against
**empty (0-record) input**. It is the golden (data-out) half of the
`provisioning / empty_input` edge case; the paired data-in half lives at
`tests/fixtures/provisioning/empty_input/`, which supplies four genuinely
**0-byte** fixtures — `acctdata.txt`, `carddata.txt`, `cardxref.txt`,
`custdata.txt`.

These `*.expected` files are diffed by `tests/helpers/golden_compare.py` and
asserted by the integration test `tests/integration/test_provisioning.py`.

> **Lockstep naming (do not rename).** The folder name `empty_input` here is
> **byte-identical** to the fixtures scenario name by mandate (the one-for-one
> fixtures↔golden mirroring rule in `tests/fixtures/README.md` §2.1). The
> comparator pairs an input scenario with its golden **purely by path**, so if
> the two trees drift the test cannot locate its expected output. Keep the
> domain and scenario directory names identical — same spelling, same case,
> same underscores.

**Programs under test** (REFERENCE ONLY — never modified, per AAP §0.8.2/§0.10.2):

| Program | Role | Record layout (copybook / bytes) |
|---|---|---|
| `app/cbl/CBACT01C.cbl` | account master read/print | `CVACT01Y` / 300 |
| `app/cbl/CBACT02C.cbl` | card master read | `CVACT02Y` / 150 |
| `app/cbl/CBACT03C.cbl` | card cross-reference read | `CVACT03Y` / 50 |
| `app/cbl/CBCUS01C.cbl` | customer master read/print | `CVCUS01Y` / 500 |

The exact byte layout of each record is intentionally **not** reproduced here;
it is single-sourced in `tests/fixtures/README.md` §5 (see §10 below). Empty
input never materializes a record anyway, so no layout detail is needed to
audit this scenario.

## 3. Business rule exercised — the EOF mechanism (WHY these outputs are correct)

All four programs share one identical read loop, and it is that loop — not any
record-formatting logic — that this scenario pins down. Each program opens its
`ORGANIZATION IS INDEXED` input file and drives:

```
PERFORM UNTIL END-OF-FILE = 'Y'
    IF END-OF-FILE = 'N'
        PERFORM <pgm>-GET-NEXT      *> issues the READ
        IF END-OF-FILE = 'N'
            <display the just-read record>
        END-IF
    END-IF
END-PERFORM
```

On an empty file the **very first `READ` returns file status `'10'`**
(end-of-file). The get-next paragraph maps that to `MOVE 16 TO APPL-RESULT`,
which satisfies the 88-level condition `APPL-EOF` (`VALUE 16`) and therefore
executes `MOVE 'Y' TO END-OF-FILE`. Because the record-display path is guarded
by the **inner** `IF END-OF-FILE = 'N'`, it is *never entered* on the first
(and only) iteration. The loop then exits immediately.

Net effect on empty input: only the **START** and **END** banner lines are
emitted, **zero record lines** are printed, and the program falls off the clean
EOF path with a **return code of 0**. This is precisely the "empty input files"
edge case called out in AAP §0.4.1.

Verified control-flow line numbers in the shipped sources (for auditors):

| Program | START banner | END banner | `PERFORM UNTIL` | first `READ` | `MOVE 16` (EOF) | `88 APPL-EOF` | `MOVE 'Y'` |
|---|---|---|---|---|---|---|---|
| `CBACT01C` | L141 | L158 | L147 | L166 | L181 | L117 | L190 |
| `CBACT02C` | L71 | L85 | L74 | L93 (get-next) | L99 | L63 | L108 |
| `CBACT03C` | L71 | L85 | L74 | L93 (get-next) | L99 | L63 | L108 |
| `CBCUS01C` | L71 | L85 | L74 | L93 (get-next) | L99 | L63 | L108 |

## 4. Expected-output inventory

Every sibling golden in this directory, and the value it must contain:

| Golden file | Program | Expected content |
|---|---|---|
| `acct_count.expected` | `CBACT01C` | `0` (records read) |
| `card_count.expected` | `CBACT02C` | `0` |
| `xref_count.expected` | `CBACT03C` | `0` |
| `cust_count.expected` | `CBCUS01C` | `0` |
| `acct_print.expected` | `CBACT01C` | START + END banner only |
| `card_print.expected` | `CBACT02C` | START + END banner only |
| `xref_print.expected` | `CBACT03C` | START + END banner only |
| `cust_print.expected` | `CBCUS01C` | START + END banner only |
| `return_code.expected` | all four | `0` (clean EOF exit) |

The four `*_print.expected` files each contain exactly two lines. The literal
banner text (quoted verbatim from the sources, and matched byte-for-byte by the
committed goldens) is:

- `CBACT01C`: `START OF EXECUTION OF PROGRAM CBACT01C` then
  `END OF EXECUTION OF PROGRAM CBACT01C`
- `CBACT02C`: `START OF EXECUTION OF PROGRAM CBACT02C` then
  `END OF EXECUTION OF PROGRAM CBACT02C`
- `CBACT03C`: `START OF EXECUTION OF PROGRAM CBACT03C` then
  `END OF EXECUTION OF PROGRAM CBACT03C`
- `CBCUS01C`: `START OF EXECUTION OF PROGRAM CBCUS01C` then
  `END OF EXECUTION OF PROGRAM CBCUS01C`

The only difference between the four print goldens is the trailing program-id.

## 5. WHY counts = 0, banner-only, RC = 0

The expected values above are not arbitrary; each rests on an explicit,
auditable premise:

- **Assumption (verified).** The paired fixtures are genuine **0-byte /
  0-record** files (confirmed against
  `tests/fixtures/provisioning/empty_input/`), so the first `READ` is an
  immediate EOF. That is why every `*_count.expected` is `0` and every
  `*_print.expected` carries no record lines.
- **Assumption (verified).** The return code is uniformly `0` across all four
  programs. None of them sets `RETURN-CODE` on the clean-EOF path, and COBOL
  leaves it at its default `0`. That is why a **single shared**
  `return_code.expected` suffices for the whole scenario.
- **Trade-off.** For the print outputs we compare the **full normalized stdout
  byte-for-byte** (golden-master) rather than asserting field by field. For a
  banner-only result the whole-output diff is both simpler and stricter — it
  would catch even a stray blank line — and it costs us nothing in locality
  because there are no record fields to localize.
- **Alternatives considered.** A **per-program** return-code golden
  (`acct_rc.expected`, `card_rc.expected`, …) was considered and rejected as
  redundant: all four values are identical (`0`), so four files would only
  create four ways to drift out of sync. One shared `return_code.expected` is
  the deliberate choice.

## 6. CRITICAL — no `COBDATFT` / no assembler dependency in this scenario

Because **no record is ever read**, `CBACT01C` **never calls `COBDATFT`** (its
assembler date-formatting routine) and **writes no `OUTFILE`, `ARRYFILE`, or
`VBRCFILE` records**. Those `WRITE` statements, together with
`1300-POPUL-ACCT-RECORD` (which issues `CALL 'COBDATFT'`), live *exclusively*
inside the file-status-`'00'` branch of `1000-ACCTFILE-GET-NEXT` — the branch
that empty input, arriving at status `'10'`, never reaches. (The output files
are still `OPEN`ed before the loop, but zero records are written to them.)

Consequences that make this scenario fully self-contained and deterministic:

- There are **no `COMP-3` packed-decimal out-file records** to encode or
  compare in `empty_input`.
- **No `COBDATFT` stub or shim is required** to produce these goldens — the
  scenario runs clean without any assembler surrogate on `COB_LIBRARY_PATH`.

This is the folder requirement's CRITICAL note: the absence of the write path
is *why* `empty_input` can be captured and audited without touching the packed
-decimal / assembler machinery that the populated `happy_path` scenario needs.

## 7. `CBACT03C` double-display quirk (awareness note for `happy_path` authors)

On a **populated** read (file status `'00'`), `CBACT03C` `DISPLAY`s
`CARD-XREF-RECORD` **twice** per record — once inside its get-next paragraph
(`1000-XREFFILE-GET-NEXT`) and once again in the main loop after the get-next
returns. This is **MOOT for `empty_input`**, because at status `'10'` neither
`DISPLAY` is reached and the xref print golden is banner-only like the others.

The note is recorded here only so the authors of the sibling **`happy_path`**
golden do not mistake the doubled xref lines for a bug: for a populated xref
fixture the expected output legitimately contains each cross-reference record
line twice.

## 8. Determinism & normalization

Before comparison, both the captured program output and the stored golden are
passed through `tests/helpers/golden_compare.py`'s `normalize()`. For these
plain-stdout captures `normalize()` runs in its **text mode** (no fixed-width
record `layout` is supplied), which:

1. unifies line endings to `\n` (collapses any `\r\n` / `\r`),
2. replaces embedded ISO-8601-like timestamps with a stable sentinel, and
3. right-strips trailing whitespace on each line.

**Assumption (verified).** This scenario emits **no timestamps at all** — the
output is two fixed banner lines — so step 2 (timestamp scrubbing) is
effectively a **no-op** here. The output is inherently deterministic; there is
nothing run-dependent to mask.

**Storage rules for every golden in this directory:** LF (`\n`) line endings, a
single trailing newline, UTF-8 encoding, **no CR**, **no BOM**. The committed
siblings already satisfy this (the count/RC files are `0` + newline = 2 bytes;
each print file is the two banner lines = 76 bytes).

## 9. Provenance — how to regenerate (run-and-capture)

Every sibling golden is **derived from program execution**, never hand-authored
beyond the verified banner/`0` constants. To reproduce or audit them:

1. **Compile** each program with the repository's compiler convention (the same
   one `scripts/local_compile.sh` uses) — `--std=ibm-strict` is required so the
   `COMP-3` money fields in the copybooks are accepted:

   ```
   cobc -x -fixed -I app/cpy --std=ibm-strict -o build/CBACT01C app/cbl/CBACT01C.cbl
   cobc -x -fixed -I app/cpy --std=ibm-strict -o build/CBACT02C app/cbl/CBACT02C.cbl
   cobc -x -fixed -I app/cpy --std=ibm-strict -o build/CBACT03C app/cbl/CBACT03C.cbl
   cobc -x -fixed -I app/cpy --std=ibm-strict -o build/CBCUS01C app/cbl/CBCUS01C.cbl
   ```

2. **Bind** each program's `SELECT ... ASSIGN TO <NAME>` to the paired 0-record
   fixture via the harness environment variables exported by
   `scripts/test_env.sh` (GnuCOBOL resolves each `ASSIGN` name to a same-named
   env var at runtime): `ACCTFILE`→`acctdata.txt`, `CARDFILE`→`carddata.txt`,
   `XREFFILE`→`cardxref.txt`, `CUSTFILE`→`custdata.txt`. Because the programs
   declare `ORGANIZATION IS INDEXED`, the harness first loads each flat 0-byte
   fixture into a GnuCOBOL indexed file via `tests/helpers/load_indexed.sh` (its
   `IDCAMS REPRO` analog) — for empty input this yields an **empty** indexed
   file.

3. **Run** each program; capture its **stdout** and its process **exit code**.
   The record count is the number of record lines in the output (`0` here); the
   print golden is the captured stdout; the return-code golden is the exit code.

4. **Normalize & commit.** Pass the captured stdout through
   `golden_compare.normalize()` (§8) and write the result to the matching
   `*.expected` file, honoring the storage rules above.

## 10. Cross-references

- **Paired fixtures:** `tests/fixtures/provisioning/empty_input/` (and its own
  `README.md`).
- **Encoding / record-layout contract:** **defer to** `tests/fixtures/README.md`
  (§3 encoding rules, §5 layout tables). Those tables are *not* duplicated here
  on purpose — the contract is single-sourced there.
- **Comparator:** `tests/helpers/golden_compare.py` (`normalize()` and the
  golden-diff assertion).
- **Consumer test:** `tests/integration/test_provisioning.py`.
- **Copybooks:** `CVACT01Y`, `CVACT02Y`, `CVACT03Y`, `CVCUS01Y` under `app/cpy/`
  (resolved at compile time via `-I app/cpy`).

## 11. Scope guard

The **provisioning** domain has exactly **two scenarios** — `happy_path` and
`empty_input` — mirroring the fixtures tree one-for-one. Do **not** invent extra
scenarios in this tree. Reject-reason and monetary-boundary scenarios (e.g.
`reject_102_overlimit`, `boundary_exact_limit`) belong to the **posting** and
**interest** domains, not to provisioning. This file documents `empty_input`
only; its sibling scenario is `happy_path`.
