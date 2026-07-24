# Golden masters — interest / zero_balance (`CBACT04C`)

Byte-exact **expected outputs** ("data-out") of the interest & fee calculator
[`app/cbl/CBACT04C.cbl`](../../../../app/cbl/CBACT04C.cbl) when it is run on the paired input
fixtures in
[`tests/fixtures/interest/zero_balance/`](../../../fixtures/interest/zero_balance/README.md).
This folder is diffed against the program's actual output by
[`tests/helpers/golden_compare.py`](../../../helpers/golden_compare.py) (`assert_matches_golden`),
which pairs a scenario's **inputs** with its **expected** outputs purely by matching directory
path. The driver
[`tests/integration/test_cbact04c_interest.py`](../../../integration/test_cbact04c_interest.py)
loads the fixtures into GnuCOBOL indexed files, runs `CBACT04C` with a **fixed `PARM-DATE` of
`2024-01-15`** (the JCL `PARM=` date analogue; the shipped `app/jcl/INTCALC.jcl` supplies it in
production), then compares the produced `TRANSACT` and `ACCOUNT` files and the process
`RETURN-CODE` against the three `.expected` files committed here.

> **Why this README exists (mandatory Explainability artifact — AAP §0.10.1).**
> The three data siblings in this folder (`transact.expected`, `acctdat.expected`,
> `return_code.expected`) are static, fixed-width `.expected` files whose every byte is
> load-bearing — a stray comment character would shift a positional field and corrupt the
> comparison — so they **cannot carry inline docstrings**. Per the project Explainability rule,
> **this document is the mandated *why* for their bytes**: it records Purpose, the business-rule
> semantics, and the labelled *Assumption* / *Trade-off* / *Alternatives Considered* /
> *Refactoring Rationale* notes behind the captured values.
>
> The authoritative byte-encoding contract (field widths, offsets, the zoned-decimal
> sign-overpunch table, implied decimal, line endings, the `decimal.Decimal` / no-float rule)
> lives in the master [`tests/fixtures/README.md`](../../../fixtures/README.md); this file
> **references it and never restates or contradicts it**. It records only what is specific to the
> *captured output* of this scenario.
>
> **Program under test is REFERENCE-only.** `app/cbl/CBACT04C.cbl` and every copybook/seed are
> consumed as REFERENCE and are **never modified** (AAP §0.8.2, §0.10.2). If a fresh run disagrees
> with these goldens, the fixtures or the load step are wrong — not the program, and not these
> files.

---

## 1. Scenario intent — ZERO BALANCE → ZERO INTEREST (the compute path RUNS and asserts `0.00`)

This scenario encodes a specific, easily-confused distinction that is the whole point of the
folder: **a zero category balance still produces an interest transaction (amount `0.00`) because
the rate is non-zero, so the interest compute branch is *not* skipped.**

- **Zero balance → zero interest (THIS scenario).** Each account's group id is `A000000000`, a
  **DIRECT `DISCGRP` hit** (VSAM file status `00`) carrying a **non-zero** rate
  `DIS-INT-RATE = 15.00`. The intentional edge is `TRAN-CAT-BAL = 0.00`. Because the rate is
  non-zero, `CBACT04C`'s guard `IF DIS-INT-RATE NOT = 0` is **TRUE**, so `1300-COMPUTE-INTEREST`
  executes and `1300-B-WRITE-TX` **writes** an interest transaction — with `TRAN-AMT = 0.00`.
- **Zero rate → no computation (NOT this scenario).** If the rate were `0`, the guard would be
  FALSE, `CBACT04C` would skip `1300-COMPUTE-INTEREST` entirely, and it would write **no**
  transaction. That is a different edge case and is not what this folder tests.

| Case | `DIS-INT-RATE` | `IF DIS-INT-RATE NOT = 0` | `1300-COMPUTE-INTEREST` | Result |
|---|---|---|---|---|
| **this scenario** — zero *balance* | `15.00` | TRUE | **runs** | interest transaction written, `TRAN-AMT = 0.00` |
| zero *rate* (elsewhere) | `0.00` | FALSE | **skipped** | no interest transaction |

The paired fixture ships **two** accounts — `00000000001` (`194.00`) and `00000000002` (`158.00`),
each with a `0.00` category-balance row — so the suite also exercises the multi-account key-break
path (see §5, finding 3). Both accounts sit in the same group `A000000000`, so both resolve the
same `15.00` rate and both emit a `0.00` interest transaction.

## 2. Interest arithmetic (exact fixed-point, NO rounding)

`CBACT04C` `1300-COMPUTE-INTEREST` computes, verbatim from the source:

```
WS-MONTHLY-INT = (TRAN-CAT-BAL × DIS-INT-RATE) / 1200
              = (0.00        × 15.00)         / 1200
              = 0.00
```

and accumulates `WS-TOTAL-INT = 0.00`. Money is exact fixed-point (`decimal.Decimal` in the
harness, zoned decimal on disk), **never** floating point — the master
[`tests/fixtures/README.md`](../../../fixtures/README.md) §3.5 pins the implied-decimal / no-float
contract.

## 3. Common master data (the paired inputs that drive these goldens)

The inputs live in
[`tests/fixtures/interest/zero_balance/`](../../../fixtures/interest/zero_balance/README.md); the
disclosure-group rate is resolved from the shared mock
[`tests/mocks/mock_discgrp.txt`](../../../mocks/mock_discgrp.txt) (its first row is
`A000000000 / 01 / 0001 → 15.00`, a direct hit). Only the load-bearing values are decoded below;
encoding mechanics are cited, not restated.

| Account | Card | Customer | `ACCT-GROUP-ID` | `ACCT-CURR-BAL` | `TRAN-CAT-BAL` (type `01` / cat `0001`) |
|---|---|---|---|---|---|
| `00000000001` | `9680294154603697` | `000000001` | `A000000000` | `194.00` (`00000001940{`) | `0.00` (`0000000000{`) |
| `00000000002` | `0923877193247330` | `000000002` | `A000000000` | `158.00` (`00000001580{`) | `0.00` (`0000000000{`) |

The disclosure-group row `A000000000 / 01 / 0001` encodes `DIS-INT-RATE = 00150{` = **`15.00`**
(`S9(04)V99`). The trailing `{` is the zoned-decimal overpunch for a units digit of `0` carrying a
**positive** sign (`{` = `+0`); the full table is in
[`tests/fixtures/README.md`](../../../fixtures/README.md) §3.4 — **not restated here**.

## 4. Expected outputs — the three sibling goldens

One entry per file. Values are the **captured** bytes committed in this folder; the "Compared as"
column is the exact mode/layout the harness uses (see
[`tests/integration/test_cbact04c_interest.py`](../../../integration/test_cbact04c_interest.py)).
Each record is framed on disk by a single trailing `LF`, so an *N*-record file occupies
*N* × (reclen + 1) bytes.

| File | On-disk bytes | Compared as | Expected content (summary) |
|---|---|---|---|
| `transact.expected` | **702** = 2 × (350 + LF) | record mode, `layout="INTTRAN"` | **Two** 350-byte `CVTRA05Y` `TRAN-RECORD`s — one `0.00` interest transaction per account. Both carry `TRAN-TYPE-CD = 01`, `TRAN-CAT-CD = 0005`, `TRAN-SOURCE = System`, `TRAN-DESC = Int. for a/c <acct-id>`, `TRAN-AMT = 0000000000{` (**+0.00**, 11-byte `S9(09)V99`), and the account's `TRAN-CARD-NUM`. `TRAN-ORIG-TS` (bytes 279–304) and `TRAN-PROC-TS` (bytes 305–330) are the run clock — **stored blank (26 spaces each) and normalized** (see §5, finding 2). Trailing `FILLER X(20)` (bytes 331–350) = **20 × `0x00`** (captured — see §5, finding 1). |
| `acctdat.expected` | **602** = 2 × (300 + LF) | record mode, `layout="ACCOUNT"` (`encoding="latin-1"`) | **Two** 300-byte `CVACT01Y` `ACCOUNT` records after the run — **byte-identical to the paired input fixture `acctdata.txt`** (see §5, finding 3). Account `00000000001` keeps `ACCT-CURR-BAL 00000001940{` (**194.00**) with cycle fields `00000000000{`; account `00000000002` keeps `ACCT-CURR-BAL 00000001580{` (**158.00**). Both keep `ACCT-GROUP-ID A000000000`. |
| `return_code.expected` | **1** = literal `0` (no trailing `LF`) | text mode (no layout) | The process `RETURN-CODE`: `CBACT04C` runs the daily cycle and falls through to a normal `GOBACK`, so the code is **`0`**. Text mode canonicalises the trailing newline, so the 1-byte `0` compares equal to the runner's `0\n`. |
| `README.md` | — | — | This Explainability artifact (not a compared golden). |

### Per-record byte breakdown (auditor reference)

`transact.expected`, record 1 → record 2 (the only run-varying bytes are the two timestamps):

- `TRAN-ID` = `2024-01-15000001` → `2024-01-15000002`  *(deterministic — see §5, finding 2)*
- `TRAN-TYPE-CD` = `01`; `TRAN-CAT-CD` = `0005`; `TRAN-SOURCE` = `System    `
- `TRAN-DESC` = `Int. for a/c 00000000001` → `Int. for a/c 00000000002` (then `0x00` padding to 100 bytes)
- `TRAN-AMT` = `0000000000{` (both records — **+0.00**)
- `TRAN-MERCHANT-ID` = `000000000`; merchant name / city / zip = spaces
- `TRAN-CARD-NUM` = `9680294154603697` → `0923877193247330`
- `TRAN-ORIG-TS` = 26 spaces; `TRAN-PROC-TS` = 26 spaces; `FILLER` = 20 × `0x00`

## 5. Key empirical findings (these goldens were CAPTURED from a real run, not hand-derived)

The goldens were produced by running `CBACT04C` (built with the repository convention
`cobc -fixed -I app/cpy --std=ibm-strict`) against the paired fixtures and capturing its output
through the suite's fixed-width codec, then normalizing the run-varying fields. Four findings a
purely hand-derived golden gets wrong:

1. **`TRAN-AMT` for `+0.00` is `0000000000{` — ten `0`s plus the overpunch `{`, NOT eleven plain
   zeros.** Signed money is written as zoned decimal, where the sign rides the **last** byte: a
   units digit of `0` carrying a **positive** sign is the overpunch `{` (`{` = `+0`), per
   [`tests/fixtures/README.md`](../../../fixtures/README.md) §3.4. The same `{` appears on **every**
   positive money field in this scenario — e.g. `ACCT-CURR-BAL 00000001940{`, the cycle fields
   `00000000000{`, and the rate `00150{` — so the encoding is uniform and internally consistent.
   The trailing `FILLER X(20)` is a separate matter: it is captured as **20 × `0x00`** (`LOW-VALUES`),
   the WORKING-STORAGE default the toolchain leaves in the un-`VALUE`d record area.
   *Trade-off / Refactoring Rationale:* we adopt the **empirically observed** byte string as the
   contract, because a golden must equal what the toolchain and codec actually emit — a hand-guessed
   plain-zero `TRAN-AMT` (or a "spaces" FILLER) would guarantee a false failure on the very first
   run. This is exactly the regression the byte-exact comparison guards against.

2. **The transaction timestamps are non-deterministic; `TRAN-ID` is not.** `CBACT04C`
   (`1300-B-WRITE-TX`) stamps **both** `TRAN-ORIG-TS` (bytes 279–304) and `TRAN-PROC-TS`
   (bytes 305–330) from the runtime `FUNCTION CURRENT-DATE` clock, so both drift run to run. The
   harness therefore compares `transact.expected` in record mode with **`layout="INTTRAN"`** — a
   [`record_codec`](../../../helpers/record_codec.py) layout purpose-built for the interest stream
   that flags **both** timestamp fields `normalize_ts=True`. In record mode the comparator blanks
   each flagged field **to spaces, in place, at its fixed byte offset**, **symmetrically on the
   actual output and the golden**, before diffing — preserving the 350-byte record width and every
   other byte. The committed golden stores each timestamp as **26 spaces**; because both sides are
   blanked by offset, the stored content is irrelevant to pass/fail as long as each field is exactly
   26 bytes wide. *Alternatives Considered:* (a) committing the live captured timestamp — rejected,
   it bakes a non-reproducible wall-clock value into a committed artifact; (b) reusing the base
   `TRAN` layout — rejected, `TRAN` normalizes only `PROC-TS`, so the run-generated `ORIG-TS` would
   still flake. By contrast, **`TRAN-ID` is deterministic** — `2024-01-15000001` and
   `2024-01-15000002` — because `1300-B-WRITE-TX` builds it as `PARM-DATE` (the injected
   `2024-01-15`) followed by a 6-digit ascending suffix, so it is asserted **verbatim**.

3. **`acctdat.expected` is byte-identical to the paired input fixture `acctdata.txt`** (verified).
   *Assumption / observation:* the accrued interest is `0.00`, so no account balance ever changes
   value, and the two accounts exercise both key-break paths without altering the bytes:
   - Account `00000000001` (non-final) **is** rewritten — `1050-UPDATE-ACCOUNT` fires at the
     key break to account 2 — but it adds `0.00` to the `194.00` balance and re-zeros
     already-zero cycle counters, so the `REWRITE` writes back **identical** bytes
     (`00000001940{`, `00000000000{`).
   - Account `00000000002` (final) is **not** rewritten at all: `CBACT04C`'s end-of-file
     account-flush branch is never reached for the last account (a documented CardDemo control-flow
     quirk), so the final record is left untouched at `158.00`.

   Either way the persisted bytes equal the input, so the golden equals the fixture exactly. Note
   that both fixture and golden carry the identical `{` overpunch on every positive money field —
   consistent with the single byte-encoding contract in the master fixtures README — which is why a
   byte-for-byte comparison, not a decode-and-compare, is the right proof here.

4. **No fee transaction — exactly two records, both interest.** `CBACT04C`'s
   `1400-COMPUTE-FEES` is an `EXIT`-only "To be implemented" stub, so it posts **no** fee record.
   `transact.expected` therefore contains exactly the **two** `0.00` interest transactions (one per
   account) and nothing else. *Refactoring Rationale:* the golden encodes the program's *current*
   no-fee behaviour verbatim; when the fee path is one day implemented, this golden is the tripwire
   that forces the change to be reviewed rather than silently absorbed.

## 6. Timestamp determinism & normalization (detail)

`transact.expected` is the only golden with run-varying bytes; `acctdat.expected` and
`return_code.expected` have none.

- **Record mode is symmetric.** For `layout="INTTRAN"` the comparator applies the *same*
  offset-based space-blanking to both the live run output and the committed golden, then compares.
  No ISO-timestamp regex runs in record mode and trailing whitespace is never stripped — doing
  either would clobber real fixed-width bytes. See
  [`tests/helpers/golden_compare.py`](../../../helpers/golden_compare.py) for the exact rules and
  [`tests/helpers/record_codec.py`](../../../helpers/record_codec.py) for the `INTTRAN` field flags.
- **`acctdat.expected` (`layout="ACCOUNT"`, `encoding="latin-1"`)** is compared **byte-exactly** —
  it has no processing timestamp. `latin-1` is the identity byte↔codepoint map, so the record's
  zoned-decimal digits and any `0x00`/space filler round-trip with no re-encoding.
- **`return_code.expected` (text mode)** canonicalises the trailing newline, so the committed
  1-byte `0` and the runner's `0\n` reduce to the same value.

## 7. How to (re)generate — provenance

These goldens are **program output**, not hand-assembled. To reproduce:

1. **Build** the unit under test: `../../../../scripts/build_test_programs.sh` (repository
   convention `cobc -fixed -I app/cpy --std=ibm-strict`, which — unlike `-std=cobol85` — accepts the
   `COMP-3`/zoned money fields).
2. **Provision** the paired fixtures into an isolated workspace by loading the flat records into
   GnuCOBOL indexed files (the suite's `IDCAMS REPRO` analog) via
   [`tests/helpers/load_indexed.sh`](../../../helpers/load_indexed.sh) /
   [`tests/helpers/vsam_loader.py`](../../../helpers/vsam_loader.py): `tcatbal.txt → TCATBALF`,
   `acctdata.txt → ACCTFILE`, `cardxref.txt → XREFFILE` (which also needs the alternate account-id
   key `CBACT04C` reads by), and the shared
   [`tests/mocks/mock_discgrp.txt`](../../../mocks/mock_discgrp.txt) `→ DISCGRP`.
3. **Run** `CBACT04C` with `PARM-DATE = 2024-01-15` through
   [`tests/helpers/cobol_runner.py`](../../../helpers/cobol_runner.py), which binds each
   `SELECT … ASSIGN` external name (`TCATBALF`, `ACCTFILE`, `XREFFILE`, `DISCGRP`, `TRANSACT`) to
   its workspace file via an environment variable, and capture the `TRANSACT` and `ACCOUNT` outputs
   plus the process `RETURN-CODE`.
4. **Normalize & commit** via
   `assert_matches_golden(actual, ".../<name>.expected", layout=..., update=True)` under the
   two-signal opt-in the comparator requires (`CARDDEMO_UPDATE_GOLDENS=1`, and never in CI). Record
   mode blanks only the `normalize_ts` timestamps; every other byte — including the `0x00` FILLER —
   is written verbatim.
5. **Audit** the deterministic fields against §4/§5: both `TRAN-AMT = 0000000000{`, the two
   `TRAN-ID`s `2024-01-15000001` / `2024-01-15000002`, the two card numbers, `acctdat.expected`
   byte-identical to `acctdata.txt`, and `return_code.expected = 0`.

## 8. Byte-encoding rules (referenced, not restated)

Fixed-width offsets, the zoned-decimal sign-overpunch table (`{` = `+0` … `I` = `+9`; `}` = `−0` …
`R` = `−9`), the implied decimal (`V` occupies no byte), `COMP-3` conventions, and the LF-framing
rule are defined **once** in the master
[`tests/fixtures/README.md`](../../../fixtures/README.md) (§3). This folder cites them and does not
restate or contradict them.

## 9. Links & references

- **Paired input fixtures (the 1:1 inputs that produce these outputs):**
  [`tests/fixtures/interest/zero_balance/README.md`](../../../fixtures/interest/zero_balance/README.md).
- **Encoding / record-layout contract (authoritative — referenced, not restated):**
  [`tests/fixtures/README.md`](../../../fixtures/README.md) (§3.4 overpunch, §3.5 implied decimal,
  §5.5 `TRAN`, §5.2 `ACCOUNT`).
- **Comparator:** [`tests/helpers/golden_compare.py`](../../../helpers/golden_compare.py)
  (`assert_matches_golden`; record vs text mode; `normalize_ts` handling).
- **Record layouts / `INTTRAN`:** [`tests/helpers/record_codec.py`](../../../helpers/record_codec.py).
- **Consumer / driver:**
  [`tests/integration/test_cbact04c_interest.py`](../../../integration/test_cbact04c_interest.py).
- **Program under test (REFERENCE ONLY, never modified — AAP §0.8.2, §0.10.2):**
  [`app/cbl/CBACT04C.cbl`](../../../../app/cbl/CBACT04C.cbl) — `1200-GET-INTEREST-RATE`,
  `1300-COMPUTE-INTEREST` → `1300-B-WRITE-TX`, `1400-COMPUTE-FEES` (stub), `1050-UPDATE-ACCOUNT`.
- **Record copybooks (REFERENCE ONLY, single-sourced):**
  [`app/cpy/CVTRA05Y.cpy`](../../../../app/cpy/CVTRA05Y.cpy) (`TRAN-RECORD`, 350),
  [`app/cpy/CVACT01Y.cpy`](../../../../app/cpy/CVACT01Y.cpy) (`ACCOUNT-RECORD`, 300).

## 10. Data governance / synthetic provenance

The account ids (`00000000001`, `00000000002`), card numbers (`9680294154603697`,
`0923877193247330`), customer ids, and balances (`194.00`, `158.00`) are **synthetic,
seed-derived** values from the published AWS CardDemo sample datasets and represent **no real person
or account**. The full attestation lives in the paired input-fixture README §7 and in
[`tests/fixtures/README.md`](../../../fixtures/README.md) §10. These `.expected` files are generated
program output only.

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the three static
`.expected` data files in this folder. Every byte literal above is transcribed from the committed
siblings; it must never contradict them.*
