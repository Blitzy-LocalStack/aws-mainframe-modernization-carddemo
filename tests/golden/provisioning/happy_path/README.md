# provisioning / happy_path — golden-master expected outputs

Byte-deterministic **expected outputs** (the "data-out" side of the
golden-master pattern) for the four AWS CardDemo master-data **read/print**
COBOL programs, run against the paired 5-record `happy_path` input fixtures.
This is the full-round-trip *happy path*: every seeded record reads cleanly and
is printed — there are **no rejects and no error paths** in this scenario.

> **Why this file exists (Explainability).** The `*.expected` goldens are pure
> data: they cannot carry docstrings of their own. This README therefore *is*
> their docstring — it records, for a maintainer or auditor, what the scenario
> is, how each golden was produced, how to regenerate and verify it, and every
> non-obvious **Assumption / Decision / Trade-off / Alternative** baked into the
> bytes. Providing it is mandatory: the sibling `tests/fixtures/README.md` §9
> requires a `README.md` in *every* scenario folder, and AAP §0.10.1
> (Explainability) makes documentation a review gate.

---

## 1. Intent

The goldens in this folder pin the exact stdout (and the read-count / return-code
observables) of the master-data provisioning **round-trip** for the happy path:
five well-formed records per master file are loaded, read back in key order, and
printed. Because the input is clean, the expected behavior is simply *"read all
five, print all five, finish with condition code 0."* Any deviation — a missing
record, a reformatted money field, a changed banner — is a regression that the
golden comparison must surface.

## 2. Pairing & lockstep rule

- **Paired input ("data-in"):** `tests/fixtures/provisioning/happy_path/`,
  supplying four fixtures, **5 records each**:

  | Fixture | Master | Copybook | Bytes/record |
  |---|---|---|---|
  | `acctdata.txt` | ACCOUNT | `CVACT01Y` | 300 |
  | `carddata.txt` | CARD | `CVACT02Y` | 150 |
  | `cardxref.txt` | CARD-XREF | `CVACT03Y` | 36 B seed → 50 B loaded |
  | `custdata.txt` | CUSTOMER | `CVCUS01Y` | 500 |

- **The five entities are account IDs `{2, 12, 20, 27, 50}`**, cross-consistent
  across all four masters (the same five accounts appear in the account, card,
  cross-reference, and customer files).

- **Lockstep rule (do NOT rename).** This folder's name (`happy_path`) is
  **byte-identical** to the fixtures scenario name by mandate, so that
  `tests/helpers/golden_compare.py` can auto-pair input ↔ output *purely by
  path*. If the two trees drift, the test can no longer locate its expected
  output. Keep the domain and scenario directory names identical — same
  spelling, same case, same underscores — on **both** sides.

- **Comparator & codec.** The goldens are diffed by
  `tests/helpers/golden_compare.py` and asserted by the integration test
  `tests/integration/test_provisioning.py`. The primary assertion entry point is:

  ```python
  assert_matches_golden(actual, golden_path, *, layout=None,
                        encoding="utf-8", update=None, extra_patterns=None)
  ```

  Field-level extracts (when a test asserts specific columns rather than the
  whole stream) use `tests/helpers/record_codec.py`.

## 3. Programs under test (REFERENCE ONLY — never modified)

These four programs are consumed as **REFERENCE only** and are **never
modified** by the test suite (AAP §0.8.2 / §0.10.2). The goldens *encode* their
observed behavior; they do not attempt to change or "fix" it.

| Program | Path | Master file | Prints each record |
|---|---|---|---|
| `CBACT01C` | `app/cbl/CBACT01C.cbl` | ACCOUNT | 15 lines / record (see §5) |
| `CBACT02C` | `app/cbl/CBACT02C.cbl` | CARD | **once** (150 B line) |
| `CBACT03C` | `app/cbl/CBACT03C.cbl` | CARD-XREF | **twice** (50 B line) |
| `CBCUS01C` | `app/cbl/CBCUS01C.cbl` | CUSTOMER | **twice** (500 B line) |

## 4. Files in this folder (10 committed + 2 optional)

**Record-count goldens** — the number of records **READ**, *not* display lines
(see §5):

| File | Value |
|---|---|
| `acct_count.expected` | `5` |
| `card_count.expected` | `5` |
| `xref_count.expected` | `5` |
| `cust_count.expected` | `5` |

**Print / round-trip goldens** — the full stdout stream, framing banners
included:

| File | Program | Lines | Bytes | md5 |
|---|---|---|---|---|
| `card_print.expected` | CBACT02C | 7 | 831 | `e4e0199d09e6afefb6162d97d660b612` |
| `xref_print.expected` | CBACT03C | 12 | 586 | `7941c490256d05e8bdce173dabd2ee0f` |
| `cust_print.expected` | CBCUS01C | 12 | 5086 | `5404c30056b83c26060e6609ccc7a192` |
| `acct_print.expected` | CBACT01C | 77 | 3221 | `9eb8dd44b40769a06d3119f61d145bbd` |

**Return code:**

- `return_code.expected` → `0`. All four programs complete cleanly; none sets
  `RETURN-CODE` on the success path, so GnuCOBOL leaves the default `0`.
  Per-program RC goldens, if the harness prefers them, are all `0` as well.

**OPTIONAL secondary goldens** *(only if the integration test additionally
captures `CBACT01C`'s output* files*; these are **OPTIONAL** and are not among
the 10 committed goldens above):*

- `acct_outfile.expected`, `acct_arryfile.expected` — these contain
  `USAGE COMP-3` **packed-decimal** fields, so they must be stored
  **binary-safe / byte-exact** (never as normalized text), and
  `OUT-ACCT-REISSUE-DATE` is COBDATFT-derived (see §6). Because the primary,
  fully-deterministic observables are the DISPLAY stream, the read counts, and
  the return code, these file-capture goldens are **OPTIONAL**.

## 5. WHY the count is 5 — not the display-line total (critical distinction)

The `*_count.expected` value is the number of **successful `READ`s** (file
status `'00'`) issued before end-of-file (`'10'`) — i.e. **records read**. It is
completely **independent** of how many `DISPLAY` lines each program emits. The
two numbers differ per program because of a real quirk in the source (below), so
they must never be conflated.

Display-line geometry for the five records (banners excluded from the per-record
count, included in the totals):

| Program | Records read | Display lines (5 records) | Why |
|---|---|---|---|
| `CBACT01C` (acct) | 5 | **75** (15 / record) | 11 labeled field lines + 1 dashed separator (exactly 49 hyphens) + `VBRC-REC1:` + `VBRC-REC2:` + 1 raw 300-byte record line |
| `CBACT02C` (card) | 5 | **5** (1 / record) | the in-paragraph `DISPLAY CARD-RECORD` (~line 96) is **commented out**; only the main-loop `DISPLAY` fires |
| `CBACT03C` (xref) | 5 | **10** (2 / record) | **both** the in-paragraph `DISPLAY CARD-XREF-RECORD` (~line 96) **and** the main-loop `DISPLAY` are active |
| `CBCUS01C` (cust) | 5 | **10** (2 / record) | **both** the in-paragraph `DISPLAY CUSTOMER-RECORD` (~line 96) **and** the main-loop `DISPLAY` are active |

**The rule is 75 / 5 / 10 / 10** display lines for acct / card / xref / cust
respectively. Adding the two framing banners gives the committed file line
counts: `acct_print` = 75 + 2 = **77**; `card_print` = 5 + 2 = **7**;
`xref_print` = 10 + 2 = **12**; `cust_print` = 10 + 2 = **12**.

> **This double-`DISPLAY` in `CBACT03C` / `CBCUS01C` is a genuine quirk of the
> source, not a bug in the golden.** The goldens **encode** it; they do not
> "fix" it. Tests encode the specification — they do not redefine it (AAP
> §0.10.2). Each print golden is framed by
> `START OF EXECUTION OF PROGRAM <name>` as its **first** line and
> `END OF EXECUTION OF PROGRAM <name>` as its **last** line.

## 6. Assumption — the COBDATFT harness dependency (acct only)

**Assumption.** `CBACT01C` paragraph `1300-POPUL-ACCT-RECORD` executes
`CALL 'COBDATFT'` once per record. `COBDATFT` is `app/asm/COBDATFT.asm` — an
**HLASM assembler** routine that is **not GnuCOBOL-compilable**. Two
consequences follow, and both are load-bearing for reading these goldens
correctly:

1. **stdout is COBDATFT-independent.** `COBDATFT`'s result feeds only the
   `OUTFILE` field `OUT-ACCT-REISSUE-DATE`; it is **never** written to a
   `DISPLAY`. Therefore `acct_print.expected` is fully derivable from the inputs
   alone, and that DISPLAY stream is exactly what this golden captures.

2. **Clean completion (RC = 0, all 5 records) requires the integration harness
   to resolve the `CALL` with a no-op COBOL stub** — a trivial
   `PROGRAM-ID. COBDATFT.` that simply `GOBACK`s, linked in at build time. This
   is the **integration harness's** responsibility, **not** the golden's. It is
   flagged here so that a link/abend failure is diagnosed as a *harness* problem
   rather than mistaken for a golden mismatch.

## 7. Decision — compile dialect `--std=ibm-strict` (money format is dialect-sensitive)

**Decision / Alternatives considered.** The goldens were captured with GnuCOBOL

```
cobc -x -std=ibm-strict -fixed -I app/cpy -o build/<PROG> app/cbl/<PROG>.cbl
```

— the repository convention (per `scripts/local_compile.sh` and the AAP
`build_test_programs.sh`). The alternative `-std=cobol85` was **rejected**
because it does not accept the `COMP-3` packed-decimal money fields these records
carry.

This matters because the `DISPLAY` of an elementary `S9(10)V99` **zoned** field
is **dialect-sensitive**:

- under `--std=ibm-strict`: **12 digits followed by a trailing sign**, e.g.

  ```
  ACCT-CURR-BAL           :000000015800+
  ```

- under the **default** dialect: `+0000000158.00` — a different byte string
  entirely.

Therefore the integration harness **must** compile and run `CBACT01C` under
`--std=ibm-strict` for `acct_print.expected` to match. This affects **only**
`acct_print`; the card, xref, and customer records contain no signed elementary
money fields displayed this way.

## 8. Trade-off — byte-level fidelity vs. `normalize()`'s trailing-whitespace default

**Trade-off.** A `DISPLAY` of a fixed-width record emits the **full width**,
including trailing `FILLER` spaces (card lines 150 B, xref 50 B, cust 500 B, the
acct raw record 300 B; the acct dashed separator is exactly 49 hyphens). But the
comparator's `golden_compare.normalize()` **defaults `strip_trailing_ws=True`**
in text mode — which would strip those trailing spaces on *both* sides. The
comparison would still pass, but it would **no longer verify exact record
width**, which is precisely the property financial-enterprise byte fidelity
requires.

We therefore chose to **store the exact full-width bytes (trailing spaces
preserved)** in the `*_print.expected` files, and the integration test **must
compare with trailing-whitespace stripping DISABLED**. Concretely, achieve that
by any one of:

- driving the text-mode comparison through `normalize(actual, strip_trailing_ws=False)`
  before diffing (this is the parameter that governs stripping); **or**
- using **byte-exact record mode** — pass the record `layout=` to
  `assert_matches_golden`, which compares byte-for-byte and never strips trailing
  whitespace (in fact `strip_trailing_ws=True` is *rejected* in record mode); **or**
- asserting field extracts at explicit columns via `record_codec.py`.

> **API note (avoid a broken call).** `strip_trailing_ws` is a parameter of
> `normalize()`, **not** of `assert_matches_golden(...)` — the latter's keyword
> arguments are `layout`, `encoding`, `update`, and `extra_patterns` only.
> Preserve trailing whitespace via one of the three mechanisms above rather than
> by passing an unsupported keyword.

**Storage format:** **LF** line endings, a **single trailing newline**, UTF-8,
**no CR**.

## 9. Assumption — exact monetary precision, no floating point

**Assumption.** ACCOUNT money fields are `S9(10)V99` **zoned decimal with sign
overpunch** (`{` = +0 … `I` = +9, `}` = −0 … `R` = −9). Round-trip and preserve
the overpunch byte **exactly** via `tests/helpers/record_codec.py`; **never**
reformat a money field as a floating-point number — floating point cannot
represent fixed-point cents exactly and would silently corrupt the golden.

Observed nuance worth recording:

- In the labeled `1100-DISPLAY` lines the value renders as **digits + a trailing
  sign** (the dialect rule from §7), e.g. `ACCT-CURR-BAL           :000000015800+`.
- In the **raw** main-loop `DISPLAY ACCOUNT-RECORD` line the value shows the
  stored form, where a trailing `{` (= +0) normalizes to `0`.

For all five seeded accounts, `ACCT-CURR-CYC-CREDIT` and `ACCT-CURR-CYC-DEBIT`
are zero (`000000000000+`).

## 10. Assumption — determinism (no timestamp normalization needed)

**Verified Assumption.** None of these four read/print programs emit a
**run-timestamp** to stdout (verified by scanning the captured output), so **no
timestamp normalization is required** for this scenario — unlike the posting and
interest goldens, `normalize()`'s timestamp-blanking is a **no-op** here.

This was checked against the comparator's actual scrubber, whose pattern
requires a **time** component
(`\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:\.\d+)?`). The only date-shaped bytes
in these goldens are **data dates** such as `2013-06-19` and `2024-08-11`
(account open / expiration / reissue dates carried in the records); with no
`HH:MM:SS` component they **do not match** the scrubber and are preserved
verbatim. The result is that these goldens are fully reproducible
**byte-for-byte across runs and machines**.

## 11. How to regenerate & verify

**Regenerate.** The full suite is produced by the runner scripts. The underlying
per-program form is:

```
cobc -x -std=ibm-strict -fixed -I app/cpy -o build/<PROG> app/cbl/<PROG>.cbl
```

For `CBACT01C`, additionally link a **no-op `COBDATFT` object** (see §6). Then
load the paired flat fixture into a GnuCOBOL indexed file via
`tests/helpers/load_indexed.sh` / `tests/helpers/vsam_loader.py` (an
`IDCAMS REPRO` analog). Record keys and widths:

| Master | Record bytes | Key length | `ASSIGN` name |
|---|---|---|---|
| ACCOUNT | 300 | 11 | `ACCTFILE` |
| CARD | 150 | 16 | `CARDFILE` |
| CARD-XREF | 50 | 16 | `XREFFILE` |
| CUSTOMER | 500 | 9 | `CUSTFILE` |

The `ASSIGN` names are bound to workspace files by `scripts/test_env.sh`. Run
the program capturing stdout, then apply `golden_compare.normalize()` (with
trailing-whitespace stripping disabled, per §8).

**Verify** against the committed digests:

```
md5sum *.expected
# acct_print.expected  9eb8dd44b40769a06d3119f61d145bbd   (3221 bytes, 77 lines)
# card_print.expected  e4e0199d09e6afefb6162d97d660b612   ( 831 bytes,  7 lines)
# xref_print.expected  7941c490256d05e8bdce173dabd2ee0f   ( 586 bytes, 12 lines)
# cust_print.expected  5404c30056b83c26060e6609ccc7a192   (5086 bytes, 12 lines)
# *_count.expected     -> "5"   ;  return_code.expected -> "0"
```

**Updating goldens.** If the reference output legitimately changes, regenerate
with the comparator's guarded update switch — set the environment variable
`CARDDEMO_UPDATE_GOLDENS=1` (or call `assert_matches_golden(..., update=True)`),
which rewrites the `*.expected` files — then **review the diff before
committing**. Do **not** hand-edit golden bytes.

## 12. Provenance & cross-consistency

- These goldens were produced by running the **REFERENCE** programs
  (`CBACT01C`, `CBACT02C`, `CBACT03C`, `CBCUS01C`) on fixtures **derived from the
  shipped seeds** in `app/data/ASCII/` (`acctdata`, `carddata`, `cardxref`,
  `custdata`).
- Record layouts are **single-sourced** from the copybooks `CVACT01Y`,
  `CVACT02Y`, `CVACT03Y`, and `CVCUS01Y` in `app/cpy/`, resolved at compile time
  via `-I app/cpy` — the goldens and the programs that generate them therefore
  reference identical field offsets.
- There are **no sibling-folder dependencies within `tests/golden`**: this
  scenario stands alone and can be regenerated and verified in isolation.
