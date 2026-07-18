# CBACT04C interest — DEFAULT-group fallback GOLDEN outputs (blank `ACCT-GROUP-ID` → DISCGRP status `23` → `DEFAULT`)

This folder holds the **byte-deterministic expected outputs** (`*.expected`) that
[`tests/helpers/golden_compare.py`](../../../../tests/helpers/golden_compare.py)
(`assert_matches_golden`) diffs the compiled
[`app/cbl/CBACT04C.cbl`](../../../../app/cbl/CBACT04C.cbl) output against, for the
**interest run, DEFAULT-group fallback scenario**. It is the **output mirror**,
paired 1:1, of the input scenario at
[`tests/fixtures/interest/default_fallback/`](../../../fixtures/interest/default_fallback/README.md).

The comparison is driven by
[`tests/integration/test_cbact04c_interest.py`](../../../integration/test_cbact04c_interest.py),
which loads the paired fixtures into GnuCOBOL indexed files, runs `CBACT04C`
(via a tiny in-test driver `DRV04C` that injects the fixed date
`PARMDATE = 2024-01-15` — the [`app/jcl/INTCALC.jcl`](../../../../app/jcl) `PARM=`
analogue, because `CBACT04C` reads its run date through
`PROCEDURE DIVISION USING EXTERNAL-PARMS`, not the command line), then diffs the
`TRANSACT` and `ACCOUNT` outputs and the process return code against the three
`*.expected` files here.

> **Why this README exists (Explainability, AAP §0.10.1).** The three `.expected`
> files are fixed-width, byte-exact records (and one bare return code) that
> **cannot carry inline comments**, so this document is their mandated *why*: the
> business rule they encode, the arithmetic that produces each number, the
> determinism rules that make the comparison stable, and the run-and-capture
> provenance of the bytes. The **authoritative byte-encoding contract** — field
> widths, offsets, the zoned-decimal sign-overpunch table, the implied decimal,
> and the LF / trailing-newline rules — lives in the master
> **[`tests/fixtures/README.md`](../../../fixtures/README.md)**. This README
> **references** that contract (by section) and **must not restate or contradict
> it**; it documents only the *output semantics* of this scenario.

---

## 1. Scenario intent & the DEFAULT-group fallback (`CBACT04C` `1200-GET-INTEREST-RATE`)

The point of this scenario is to exercise the **mandatory status-23 disclosure-group
fallback** (AAP §0.7.1) — the branch a financial-grade suite must cover so an
account whose own disclosure group is absent still accrues interest at the
`DEFAULT` rate rather than silently accruing nothing.

Each account under test carries a **blank `ACCT-GROUP-ID` (10 spaces)**. For every
`TCATBAL` row, `CBACT04C` builds the disclosure-group key from that account group id
plus the transaction type/category and reads the `DISCGRP` file:

1. **`1200-GET-INTEREST-RATE` (`app/cbl/CBACT04C.cbl` L415–440).** With a blank
   group id, the keyed read finds no matching `DISCGRP` record and returns **file
   status `23` (INVALID KEY / key not found)**. Status `23` is **explicitly
   accepted, not treated as an error** — the paragraph tests `DISCGRP-STATUS = '00' OR '23'`
   as the "OK" condition and (on `23`) emits the diagnostic displays
   `DISCLOSURE GROUP RECORD MISSING` / `TRY WITH DEFAULT GROUP CODE`, then
   `MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID` and `PERFORM 1200-A-GET-DEFAULT-INT-RATE`.
2. **`1200-A-GET-DEFAULT-INT-RATE` (L443–460).** Re-reads `DISCGRP` on the now-`DEFAULT`
   key, which resolves against the `DEFAULT` rows and yields the disclosure rate.
3. Because the resolved `DIS-INT-RATE` is **non-zero (`15.00`)**, the outer loop
   runs `1300-COMPUTE-INTEREST` and one interest transaction is written per account.

`DISCGRP`'s layout is [`app/cpy/CVTRA02Y.cpy`](../../../../app/cpy/CVTRA02Y.cpy)
(RECLN 50: `DIS-ACCT-GROUP-ID X(10)` + `DIS-TRAN-TYPE-CD X(2)` + `DIS-TRAN-CAT-CD 9(4)`
+ `DIS-INT-RATE S9(04)V99` + `FILLER X(28)`). The paired fixture's `discgrp.txt`
contains **`DEFAULT` rows only**, so the blank-group key is genuinely absent — the
`DEFAULT` / type `01` / category `0001` row carries `DIS-INT-RATE` = **`15.00`**
(encoded `00150{`), master §5.7.

> **Trade-off — same number as `happy_path`, different rate *path*.** This scenario
> deliberately reaches the **identical** result as the sibling
> [`../happy_path/`](../happy_path/) via the **DEFAULT fallback** (status-23 branch)
> rather than a **direct** `DISCGRP` hit. Holding the arithmetic constant isolates
> the *branch under test*: any diff between this golden and `happy_path`'s is
> attributable to the fallback path, not to a different amount (see §4).

## 2. Interest arithmetic (pinned verbatim — `1300-COMPUTE-INTEREST`)

The single `TCATBAL` category for each account carries `TRAN-CAT-BAL` = **`1000.00`**
(encoded `0000010000{`). `1300-COMPUTE-INTEREST` (L462–470) computes:

```
WS-MONTHLY-INT = (TRAN-CAT-BAL × DIS-INT-RATE) / 1200
               = (1000.00     × 15.00)        / 1200
               = 12.50
```

- The `COMPUTE` is **without `ROUNDED`** — the result truncates to the `V99` scale;
  `15000.00 / 1200 = 12.50` exactly, so truncation is inert here but is asserted as
  **exact fixed point** (no floating-point tolerance), per financial-enterprise rigor.
- A single non-zero category ⇒ exactly **one** interest transaction per account with
  `TRAN-AMT` = **`12.50`**.
- **Balance update (`1050-UPDATE-ACCOUNT`, L350–370):** `ADD WS-TOTAL-INT TO ACCT-CURR-BAL`
  ⇒ account `00000000001` moves **`194.00 + 12.50 = 206.50`** (see §3 and the flush
  note in §5 for why account `00000000002` does **not** move).

## 3. Files in this folder (authoritative for the golden files + integration asserts)

Amounts are shown as decimals with their encoded zoned-decimal literal in `code`; see
master **§3.4** (sign overpunch) and **§3.5** (implied decimal) for *how* those bytes
encode — **not restated here**. Layouts are named by copybook (master §5.x) rather than
reproduced.

| Golden file | Represents (ASSIGN) | Layout / RECLEN | Size | Expected value |
|---|---|---|---|---|
| `transact.expected` | interest transactions written to `TRANSACT` | `CVTRA05Y` / 350 (compared as `INTTRAN`, §4) | 702 B = **2 records** | One interest `TRAN-RECORD` **per processed account**. Rec 1: `TRAN-ID 2024-01-15000001`, `TRAN-TYPE-CD 01`, `TRAN-CAT-CD 0005`, `TRAN-SOURCE "System"`, `TRAN-DESC "Int. for a/c 00000000001"`, **`TRAN-AMT 0000000125{` = `12.50`**, `TRAN-CARD-NUM 9680294154603697`. Rec 2: `TRAN-ID 2024-01-15000002`, `TRAN-DESC "Int. for a/c 00000000002"`, **`TRAN-AMT 0000000125{` = `12.50`**, `TRAN-CARD-NUM 0923877193247330`. `TRAN-ORIG-TS`/`TRAN-PROC-TS` (cols **279–330**) are runtime stamps → **blanked** (see §4). |
| `acctdat.expected` | whole rewritten `ACCOUNT` master (`ACCTFILE`) | `CVACT01Y` / 300 (`ACCOUNT`) | 602 B = **2 records** | The full master in primary-key order. Acct `00000000001`: `ACCT-CURR-BAL` `194.00 → ` **`206.50`** (`00000002065{`) — **flushed** (see §5); cycle credit/debit **`0.00`** (`00000000000{`); `ACCT-GROUP-ID` (cols **113–122**) = **BLANK (10 spaces)**. Acct `00000000002`: `ACCT-CURR-BAL` **`158.00`** (`00000001580{`) — **unchanged**, the un-flushed final account (§5); cycle credit/debit `0.00`; `ACCT-GROUP-ID` **BLANK**. |
| `return_code.expected` | process `RETURN-CODE` | — (text mode) | 1 B | The single byte **`0`** (no trailing newline) — a normal `GOBACK`; no abend, no soft reject. |

These three values are the single source of truth that the paired input README, these
`.expected` files, and `tests/integration/test_cbact04c_interest.py` all encode; they are
mutually consistent and byte-exact.

## 4. Numeric identity to `happy_path`, and why `ACCT-GROUP-ID` stays BLANK

**Numeric identity (verified byte-for-byte).** These goldens are numerically identical to
[`../happy_path/`](../happy_path/): both drive `1000.00 × 15.00 / 1200 = 12.50`, differing
**only** in the rate-resolution *path* (DEFAULT fallback here vs. a direct `DISCGRP` hit in
`happy_path`). Consequences:

- `transact.expected` is **byte-identical** to `happy_path`'s — the `TRAN-RECORD` has no
  group-id field, so a different rate *path* leaves the transaction bytes unchanged.
- `acctdat.expected` differs from `happy_path`'s **only** in the `ACCT-GROUP-ID` columns
  (bytes **113–122** of each 300-byte record — here **blank**, in `happy_path` `A000000000`);
  every other byte is identical.
- `return_code.expected` is **identical** (`0`).

> **Assumption — the blank group id is *preserved*, not rewritten.** `acctdat.expected`
> keeps `ACCT-GROUP-ID` blank because `1050-UPDATE-ACCOUNT` (L350–370) touches **only**
> `ACCT-CURR-BAL` and the two cycle counters before `REWRITE` — it **never** writes
> `ACCT-GROUP-ID`. The `'DEFAULT'` substitution in `1200-GET-INTEREST-RATE` targets the
> **`DISCGRP` file key** (`FD-DIS-ACCT-GROUP-ID`), **not** the account record, so the
> account's own group id is untouched. Therefore the output group id equals the (blank)
> **input** group id — and, symmetrically, `happy_path`'s non-blank `A000000000` is likewise
> preserved. (The `A000000000` visible in this scenario's *fixture* at cols 103–112 is the
> `ACCT-ADDR-ZIP`, a different field; the group id at 113–122 is blank on both input and output.)

> **Alternatives Considered — how the shared `tests/mocks/mock_discgrp.txt` differs.** This
> scenario triggers the fallback via a **blank group id** (the *whole* group key is absent,
> because `discgrp.txt` holds `DEFAULT` rows only). The shared mock
> [`tests/mocks/mock_discgrp.txt`](../../../mocks/mock_discgrp.txt) (AAP §0.4.4 / §0.5.5)
> reaches the same `1200-A-GET-DEFAULT-INT-RATE` fallback through a **different mechanism** —
> a **non-blank** group (`A000000000`) that is present for some type/category combinations
> but **missing a specific one** (e.g. it has no type-`05` row), so that one lookup returns
> status `23`. Both converge on the `DEFAULT` re-read; this folder uses the blank-group path
> for a **minimal, self-contained** fixture.

## 5. Two accounts, the final-flush dependency, and the absent fee record

> **⚠️ Fixture flush dependency (documented for the fixture/test agents).** `CBACT04C`'s main
> loop (L188–222) flushes an account (`1050-UPDATE-ACCOUNT`) **only when the sequentially-keyed
> `TCATBAL` account id CHANGES**; the trailing `ELSE PERFORM 1050-UPDATE-ACCOUNT` (L219–220) is
> **unreachable dead code** under `PERFORM UNTIL END-OF-FILE = 'Y'`, so the **last** account in
> `TCATBAL` is **never flushed**. The paired fixture therefore ships **two** accounts:
> `00000000001` (non-final) and `00000000002` (final). Reading account `002`'s `TCATBAL` row is
> what triggers the `REWRITE` (flush) of account `001`, so `001`'s balance `206.50` genuinely
> persists to `acctdat.expected`. Account `002` is the final account: its interest transaction
> **is** written (rec 2 of `transact.expected`, `12.50`), but its `REWRITE` never fires, so its
> balance stays at the seed **`158.00`** — `002` therefore documents the production
> **final-flush defect** in `acctdat.expected` (it is *not* the missing `170.50`). Both accounts
> carry a blank group id, so both take the status-23 → `DEFAULT` fallback that is the point of
> this scenario.

> **No fee record (`1400-COMPUTE-FEES` is a stub).** `1400-COMPUTE-FEES` (L518–520) is an
> `EXIT`-only stub marked *"To be implemented"*, so **no fee transaction is written**. Each
> processed account thus contributes exactly **one** record to `transact.expected` (the interest
> transaction); the two-record total is two accounts × one interest transaction, with **no** fee
> line among them.

## 6. Determinism & timestamp normalization (`layout="INTTRAN"`)

Golden comparison is deterministic and byte-oriented — there is **no float tolerance**;
monetary fields are asserted as **exact fixed-point** values. The only run-varying bytes are
the two timestamps: `CBACT04C` `1300-B-WRITE-TX` (L496–498) moves the runtime
`DB2-FORMAT-TS` (from `FUNCTION CURRENT-DATE`, dash/dot format
`YYYY-MM-DD-HH.MM.SS.NNNNNN`) into **both** `TRAN-ORIG-TS` (cols 279–304) **and**
`TRAN-PROC-TS` (cols 305–330), so **both** are non-deterministic.

The paired test compares `transact` in `golden_compare` **record mode** with
**`layout="INTTRAN"`** — `record_codec`'s purpose-built clone of the `TRAN` (`CVTRA05Y`)
layout that flags **both** timestamps `normalize_ts=True` — so record mode blanks
**cols 279–330** on each side. Because both sides are blanked identically, the stored golden
keeps those 52 columns as **spaces**.

> **Trade-off — offset blanking via `INTTRAN`, not the default ISO regex, and not base `TRAN`.**
> `CBACT04C`'s dash/dot timestamp format does **not** match `golden_compare.py`'s default
> (text-mode) ISO-timestamp regex, so the paired test must use **record-mode, layout-driven
> offset blanking**, i.e. pass a record layout to `assert_matches_golden`. The base `TRAN`/
> `DALYTRAN` layouts normalize **only** `PROC-TS` (they deliberately *preserve* the
> deterministic `ORIG-TS` of an ordinary posted transaction); an interest transaction has **no**
> deterministic `ORIG-TS` (it too is the wall clock), so a dedicated **`INTTRAN`** layout that
> blanks **both** timestamps is required — comparing under base `TRAN` would leave the
> run-generated `ORIG-TS` unmasked and make every interest golden non-deterministic. `INTTRAN`
> shares the 350-byte `CVTRA05Y` geometry exactly; only the two `normalize_ts` flags differ.

`acctdat` is compared record mode with `layout="ACCOUNT"` (whole 300-byte records, width-framed)
and `return_code` in **text mode** (no layout — the bare integer, with trailing-newline
canonicalization so `0` and `0\n` compare equal). Both record-mode diffs read/write the goldens
as `latin-1` for exact byte identity (including any `0x00` `FILLER`). Line-ending and
trailing-newline rules are the master's (§3.2–§3.3).

## 7. How these goldens were generated (provenance) & regeneration

The golden bytes are **produced by running the program on the paired fixtures**, not
hand-assembled:

1. **Build** `CBACT04C` via
   [`scripts/build_test_programs.sh`](../../../../scripts/build_test_programs.sh) — the shared
   flags are `-fixed -fsign=EBCDIC --std=ibm-strict -I app/cpy` (§8).
2. **Load** the paired fixtures from
   [`tests/fixtures/interest/default_fallback/`](../../../fixtures/interest/default_fallback/README.md)
   (`acctdata.txt`, `cardxref.txt`, `discgrp.txt`, `tcatbal.txt`) into GnuCOBOL indexed files
   via [`tests/helpers/load_indexed.sh`](../../../../tests/helpers/load_indexed.sh) /
   `vsam_loader.load_indexed`, and bind the sequential `TRANSACT` output (master §4.2).
3. **Run** via [`tests/helpers/cobol_runner.py`](../../../../tests/helpers/cobol_runner.py)
   with the `ASSIGN`-name env bindings (`TCATBALF`, `XREFFILE`, `ACCTFILE`, `DISCGRP`,
   `TRANSACT`) and `PARMDATE=2024-01-15` fed to the in-test driver `DRV04C`.
4. **Capture** `TRANSACT` → `transact.expected`, the unloaded `ACCTFILE` → `acctdat.expected`,
   and the process return code → `return_code.expected`.
5. **Regenerate** only under the guarded two-step opt-in — set `CARDDEMO_UPDATE_GOLDENS=1`
   **and** have the test pass `update=True` (e.g.
   `CARDDEMO_UPDATE_GOLDENS=1 pytest tests/integration/test_cbact04c_interest.py`), which writes
   the **normalized** actual output back to these files. Neither signal alone writes, and CI never
   sets the flag, so goldens change only deliberately. The checked-in bytes were pinned from an
   empirical run and a bootstrap reproduces them **exactly**.

> **Alternatives Considered (golden-master byte-diff vs. field-by-field asserts).** A whole-record
> byte-diff (with the timestamps masked) was chosen over per-field assertions because it is
> deterministic, catches width/offset drift and stray bytes a field check would miss, and mirrors
> CardDemo's own reject-stream / `LISTCAT` verification style. The cost is coarser failure
> locality, mitigated by the field-aware diff `golden_compare` emits on mismatch.

## 8. Compile requirement — `-fsign=EBCDIC` (required)

The money fields above use **EBCDIC-style zoned-decimal sign overpunch** (`{` = +0 … `I` = +9,
`}` = −0 … `R` = −9), matching the `app/data/ASCII/` seeds and the
[`tests/helpers/record_codec.py`](../../../../tests/helpers/record_codec.py) codec contract.
GnuCOBOL emits and reads that overpunch **only** when compiled with **`-fsign=EBCDIC`**; under the
default `-fsign=ASCII` it mis-handles the trailing sign byte (e.g. it would write plain trailing
digits for values it modifies), which would **not** match these goldens. Therefore
`scripts/build_test_programs.sh` **must** compile the programs under test with `-fsign=EBCDIC`
(in addition to `-fixed --std=ibm-strict -I app/cpy`). This is finding **MA-02**, established
empirically on `cobc` 3.2.0.

> **Assumption + Alternatives Considered.** The required flag syntax is **`-fsign=EBCDIC`** (with
> the `=`); the bare `-fsign-ebcdic` spelling is silently ignored by this `cobc`, which would
> reintroduce the ASCII default. The default ASCII sign was **rejected**: it breaks byte-identity
> with the seed data and the `record_codec` contract and yields inconsistent, mixed-encoding sign
> bytes in rewritten records.

## 9. Consumers

- [`tests/integration/test_cbact04c_interest.py`](../../../integration/test_cbact04c_interest.py)
  loads the paired fixtures into an isolated workspace, runs the compiled `CBACT04C` (via `DRV04C`),
  and asserts the produced `TRANSACT` / `ACCOUNT` and `RETURN-CODE` against these goldens.
- The interest-cycle and full-batch end-to-end tests under
  [`tests/e2e/`](../../../e2e/) reuse the same expected outputs.

## 10. Provenance, references, and byte-encoding deferral

Outputs derive from running [`app/cbl/CBACT04C.cbl`](../../../../app/cbl/CBACT04C.cbl) on the
[`tests/fixtures/interest/default_fallback/`](../../../fixtures/interest/default_fallback/README.md)
fixtures, which are themselves derived from the `app/data/ASCII/` seeds (master §8; the seeds and
all `app/cbl/*.cbl` / `app/cpy/*.cpy` are **REFERENCE-only** and never modified — AAP §0.8.2 /
§0.10.2). Record layouts: `CVTRA05Y` (TRAN 350, master §5.5), `CVACT01Y` (ACCOUNT 300, §5.2);
inputs shaped by `CVTRA01Y` (TCATBAL 50, §5.6), `CVTRA02Y` (DISCGRP 50, §5.7), and `CVACT03Y`
(XREF 50, §5.3).

**Byte-encoding deferral.** Low-level byte-encoding rules — the zoned-decimal overpunch table,
fixed-width field offsets, EBCDIC/ASCII notes, line endings, and the trailing-newline rule — are
documented centrally in the master
**[`tests/fixtures/README.md`](../../../fixtures/README.md)** (encoding rules §3, overpunch §3.4,
implied decimal §3.5, layouts §5, interest rule semantics §6.2). This README covers only what is
scenario-specific. One-line overpunch reminder for convenience (widths follow each field's PIC):
`12.50` → `0000000125{` (11 B, `TRAN-AMT S9(9)V99`); `206.50` → `00000002065{` (12 B,
`ACCT-CURR-BAL S9(10)V99`); `0.00` → `00000000000{` (12 B, a cycle counter).

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the three `.expected`
outputs in this folder, and the output-side counterpart of the per-scenario documentation required
by master `tests/fixtures/README.md` §9.1 / §10.3. Every non-obvious value above is tied to a
`CBACT04C` paragraph or a business rule and carries at least one of an Assumption, a Trade-off, or
an Alternatives-Considered rationale, so a reviewer can confirm the goldens encode the
**specification** (DEFAULT fallback, exact `12.50` interest, blank group preserved) rather than
accidental output.*
