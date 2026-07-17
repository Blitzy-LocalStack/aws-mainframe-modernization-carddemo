# Golden master — posting / reject_103_expired

Expected outputs of [`app/cbl/CBTRN02C.cbl`](../../../../app/cbl/CBTRN02C.cbl)
(batch transaction posting) when a daily transaction is **rejected with reason
103 — _transaction received after account expiration_**: the card resolves in the
cross-reference and its account is found, the amount is within the credit limit,
but the transaction's origination date is one calendar day **past** the account's
expiry, so the program writes the transaction to the reject stream instead of
posting it.

> **This document is the Explainability artifact** (AAP §0.10.1) for the
> golden-master output folder `tests/golden/posting/reject_103_expired/`. The five
> sibling `*.expected` files are **byte-exact fixtures** that cannot carry in-file
> comments, so the _WHY_ behind every expected value lives here — letting a
> reviewer audit the goldens without reverse-engineering them. (This title +
> summary is Section 1; Sections 2–9 follow, and a closing Explainability block
> consolidates the §0.10.1 rationale.)
>
> **REFERENCE-ONLY inputs — never modified** (AAP §0.8.2, §0.10.2): the program
> under test [`CBTRN02C.cbl`](../../../../app/cbl/CBTRN02C.cbl), all copybooks
> under `app/cpy/`, and the seed datasets under `app/data/` are consumed as
> references only. The byte-level **encoding contract** (widths, offsets,
> zoned-decimal sign overpunch, line endings) is defined once in
> [`tests/fixtures/README.md`](../../../fixtures/README.md) and the paired input
> scenario in
> [`tests/fixtures/posting/reject_103_expired/README.md`](../../../fixtures/posting/reject_103_expired/README.md);
> this document **links to** those contracts and does **not** restate them.

---

## 2. Scenario intent

- Exercises **reject reason 103** on the **negative side of the expiration
  boundary** — a transaction dated exactly **one day past** the account's expiry.
- This folder is the **"data-out"** half of the golden-master model: the paired
  **"data-in"** fixtures live at
  [`tests/fixtures/posting/reject_103_expired/`](../../../fixtures/posting/reject_103_expired/README.md).
  The comparator [`tests/helpers/golden_compare.py`](../../../helpers/golden_compare.py)
  pairs an input scenario with its expected output **purely by identical
  `<domain>/<scenario>` path** (here `posting/reject_103_expired`).

  > **Lockstep requirement (Assumption / firm rule).** Because pairing is by path
  > alone, the input folder name `tests/fixtures/posting/reject_103_expired/` and
  > this output folder name `tests/golden/posting/reject_103_expired/` **MUST stay
  > byte-identical** — same spelling, same case, same underscores. If the two
  > drift, the comparator cannot locate this golden and the test silently loses its
  > assertion target. (See the mirroring rule in
  > [`tests/fixtures/README.md`](../../../fixtures/README.md) §2.1.)

---

## 3. Business rule (`CBTRN02C` paragraph `1500-B-LOOKUP-ACCT`)

Reached after a successful cross-reference hit (no reject 100) and a successful
account read (no reject 101), `1500-B-LOOKUP-ACCT` runs **two independent `IF`
statements that write the _same_ `WS-VALIDATION-FAIL-REASON` field**, with the
**over-limit (102) check evaluated _first_** and the **expiration (103) check
_second_**:

```cobol
      * 1. Over-limit (reject 102) — evaluated FIRST
        COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
                            - ACCT-CURR-CYC-DEBIT
                            + DALYTRAN-AMT
        IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
            CONTINUE
        ELSE
            MOVE 102 TO WS-VALIDATION-FAIL-REASON
            MOVE 'OVERLIMIT TRANSACTION'
              TO WS-VALIDATION-FAIL-REASON-DESC
        END-IF

      * 2. Expiration (reject 103) — evaluated SECOND
        IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
            CONTINUE
        ELSE
            MOVE 103 TO WS-VALIDATION-FAIL-REASON
            MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
              TO WS-VALIDATION-FAIL-REASON-DESC
        END-IF
```

- The two dates are compared as **`X(10)` ISO `YYYY-MM-DD` strings**:
  `ACCT-EXPIRAION-DATE` against the **first 10 bytes** of `DALYTRAN-ORIG-TS`
  (`DALYTRAN-ORIG-TS (1:10)`). The copybook field-name misspelling
  **`ACCT-EXPIRAION-DATE`** (missing the second `T` of "EXPIRATION") is **part of
  the contract** and is preserved verbatim.
- On the reject branch, paragraph `2500-WRITE-REJECT-REC` writes the composite
  reject record to `DALYREJS` (layout in §6), and because at least one reject
  occurred the program sets **`RETURN-CODE = 4`** at end-of-run
  (`IF WS-REJECT-COUNT > 0 → MOVE 4 TO RETURN-CODE`). The post path
  (`2000-POST-TRANSACTION`, `2700-UPDATE-TCATBAL`, `2800-UPDATE-ACCOUNT-REC`,
  `2900-WRITE-TRANSACTION-FILE`) is **never reached** on this scenario.

---

## 4. Paired-fixture facts & WHY these values were chosen

The scenario is pinned by the paired input fixtures (authoritative details in
[`tests/fixtures/posting/reject_103_expired/README.md`](../../../fixtures/posting/reject_103_expired/README.md)):

| Fact | Value | Where |
|---|---|---|
| Account id | `7` (`00000000007`) | `acctdata.txt` (key), reached via `cardxref.txt` |
| Account expiry `ACCT-EXPIRAION-DATE` | `2024-12-13` | `acctdata.txt` |
| Account credit limit `ACCT-CREDIT-LIMIT` | `+2065.00` | `acctdata.txt` |
| Transaction amount `DALYTRAN-AMT` | `+504.77` (`0000005047G`) — **within** limit | `dailytran.txt` |
| Transaction `DALYTRAN-ORIG-TS` date | `2024-12-14` (= expiry **+ 1 day**) | `dailytran.txt` |

- **WHY the amount is kept within the limit (Rationale — the load-bearing
  invariant).** Because the over-limit check (102) is evaluated **before** the
  expiration check (103) and **both `MOVE` into the same
  `WS-VALIDATION-FAIL-REASON` field**, a 103 would silently overwrite a 102 if
  both fired. Holding the amount well within the limit guarantees 102 **never
  fires**, so **103 is the sole, clean reject reason**. Concretely:
  `2065.00 >= 504.77` is **TRUE** (no 102; cycle credit/debit are both `0`, so
  `WS-TEMP-BAL == DALYTRAN-AMT`), then `"2024-12-13" >= "2024-12-14"` is **FALSE**
  → reject **103**. Getting this wrong would let the test pass for the wrong
  reason.
- **WHY the date is expiry + 1 day (Assumption — ISO lexicographic compare).**
  Because `DALYTRAN-ORIG-TS (1:10)` is compared as an ISO `X(10)` string, a
  lexicographic compare of `YYYY-MM-DD` is identical to a chronological one — so
  shifting the date by a single calendar day (`2024-12-13` → `2024-12-14`)
  reliably flips the `>=` test to FALSE, with no real date arithmetic needed.
- **WHY this pairs with `boundary_expiry_equal` (Alternatives / boundary
  coverage).** This scenario is the **negative twin** of the posting scenario
  **`boundary_expiry_equal`**
  ([input fixtures](../../../fixtures/posting/boundary_expiry_equal/README.md)),
  where the transaction date **equals** the expiry date so `>=` is **TRUE** and
  the transaction **POSTS**. Together the two scenarios pin both sides of the `>=`
  expiration boundary.

---

## 5. Expected outputs — the six files in this folder

| File | Content | Why |
|---|---|---|
| [`tranfile.expected`](tranfile.expected) | **EMPTY (0 bytes)** | Rejected → `2000-POST-TRANSACTION` skipped → nothing is written to `TRANSACT`/`TRANFILE`. |
| [`acctdat.expected`](acctdat.expected) | 300-byte `ACCOUNT-RECORD`, **unchanged** | No `REWRITE`: `2800-UPDATE-ACCOUNT-REC` is unreached on the reject path, so the account equals the paired input fixture `acctdata.txt` byte-for-byte. |
| [`tcatbal.expected`](tcatbal.expected) | 50-byte `TRAN-CAT-BAL-RECORD`, **unchanged** | `2700-UPDATE-TCATBAL` is unreached, so the category balance equals the paired input fixture `tcatbal.txt` byte-for-byte. |
| [`dalyrejs.expected`](dalyrejs.expected) | Exactly **ONE 430-byte reject record** | The reason-103 reject written by `2500-WRITE-REJECT-REC` (layout in §6). |
| [`return_code.expected`](return_code.expected) | Literal **`4`** | `WS-REJECT-COUNT = 1 > 0` → `RETURN-CODE = 4`. RC rubric: **0** pass / **4** warn-reject / **≥ 8** fail. |
| `README.md` | This document | The Explainability artifact for the folder. |

> **WHY `acctdat`/`tcatbal` are asserted "unchanged" rather than omitted
> (Trade-off).** Asserting that the masters are byte-identical to their inputs is
> a stronger, auditable statement than not checking them at all: it proves the
> reject path had **no side effect** on account state or category balances, which
> is exactly the financial-correctness guarantee a reject must honour.

---

## 6. Exact 430-byte reject-record layout (`dalyrejs.expected`)

`2500-WRITE-REJECT-REC` writes `FD-REJS-RECORD` (`= REJECT-RECORD`), which is the
350-byte transaction image followed by the 80-byte validation trailer
(`WS-VALIDATION-TRAILER`), for a total of **430 bytes**:

| Bytes | Field | PIC | Value in this golden |
|---|---|---|---|
| **1–350** | `REJECT-TRAN-DATA` | `X(350)` | The full `DALYTRAN-RECORD` **as read** (see the embedded sub-layout below). |
| **351–354** | `WS-VALIDATION-FAIL-REASON` | `9(04)` | **`0103`** (numeric `103`, zero-padded to 4 digits). |
| **355–430** | `WS-VALIDATION-FAIL-REASON-DESC` | `X(76)` | **`TRANSACTION RECEIVED AFTER ACCT EXPIRATION`** (42 chars) + **34 trailing spaces**. |

Key fields **inside** the 350-byte `REJECT-TRAN-DATA` image (offsets per the
`DALYTRAN-RECORD` copybook [`CVTRA06Y`](../../../../app/cpy/CVTRA06Y.cpy)):

| Bytes (within 1–350) | Field | PIC | Value in this golden |
|---|---|---|---|
| **133–143** | `DALYTRAN-AMT` | `S9(09)V99` | `0000005047G` (= `+504.77`; overpunch `G` = +7). |
| **279–304** | `DALYTRAN-ORIG-TS` | `X(26)` | `2024-12-14 19:27:53.000000` — the post-expiry origination stamp; its **date** is the assertion target. |
| **305–330** | `DALYTRAN-PROC-TS` | `X(26)` | **26 spaces (blank)** — see §7. |
| **331–350** | `FILLER` | `X(20)` | Spaces. |

> **The `0103`/message trailer is the primary assertion.** These three trailer
> facts — record count = **1**, reason = **`0103`**, description =
> **`TRANSACTION RECEIVED AFTER ACCT EXPIRATION`** — are what make this the
> reason-103 golden. The message string is pinned **verbatim** (byte-for-byte,
> including its right-space padding to 76 bytes).

---

## 7. Determinism & normalization

`dalyrejs.expected` is **fully deterministic**: `DALYTRAN-PROC-TS` is blank
(cols 305–330) because the reject path never runs `2000-POST-TRANSACTION` (which
is where `TRAN-PROC-TS` would be stamped with the wall-clock time), and
`DALYTRAN-ORIG-TS` is a **fixed literal** carried straight from the input fixture.
`acctdat`/`tcatbal` are deterministic masters; `tranfile` is empty. All records
use **LF-only** line endings and zoned-decimal sign overpunch **exactly as
specified in [`tests/fixtures/README.md`](../../../fixtures/README.md)** — see
that document (not this one) for the overpunch table and full field layouts.

### 7.1 Comparison-mode guidance for the integration author (CRITICAL)

> **Verified against the comparator on this branch** —
> [`tests/helpers/golden_compare.py`](../../../helpers/golden_compare.py) and
> [`tests/helpers/record_codec.py`](../../../helpers/record_codec.py) — not merely
> asserted, so this guidance is auditable.

**Do NOT pass `layout="DALYTRAN"` to `assert_matches_golden` when comparing the
captured `DALYREJS`.** The reject stream record is the **composite
`FD-REJS-RECORD` = 430 bytes** (350-byte transaction image + 80-byte trailer),
whereas `record_codec`'s `DALYTRAN` layout is registered with **`reclen = 350`**.
Record mode requires **every physical row to equal the layout's record length**,
so `layout="DALYTRAN"` would raise `record_codec.RecordLengthError` on the 430-byte
row. (There is **no** 430-byte reject/`DALYREJS` layout registered, so record mode
is not an option for this artifact at all.)

**Use the default (`layout=None`) text comparison** for the whole-record golden.
That mode applies **no positional/offset blanking**; instead it (1) unifies line
endings to LF, (2) scrubs ISO-8601-shaped timestamps
(`YYYY-MM-DD[ T]HH:MM:SS[.ffffff]`) to a stable `<TS>` sentinel, and (3)
right-strips trailing whitespace — **identically on both the golden and the actual
output**, so the comparison stays byte-deterministic. The reason code **`0103`**
and the description **`TRANSACTION RECEIVED AFTER ACCT EXPIRATION`** are neither
timestamps nor trailing whitespace, so they are **preserved and asserted** by this
whole-record diff.

> **Pin the post-expiry date with a targeted slice (accuracy note).** Because
> text-mode normalization replaces the embedded `DALYTRAN-ORIG-TS`
> (`2024-12-14 19:27:53.000000`) with `<TS>` on both sides, the whole-record diff
> alone does **not** pin the specific date `2024-12-14`. To keep the
> "date = expiry + 1" business fact an explicit assertion target, the integration
> test should additionally check the **ORIG-TS date slice** of the captured
> `DALYREJS` record — bytes **279–288** (`DALYTRAN-ORIG-TS (1:10)`) must equal
> `2024-12-14` — alongside the golden diff. (Optionally also assert the captured
> record length `== 430` and the reason slice bytes 351–354 `== 0103`.) This is
> **why** the earlier, simpler advice to "just preserve ORIG-TS via record mode"
> does not apply here: the 430-byte composite record has no record-mode layout,
> and text mode deliberately scrubs the stamp.

---

## 8. How these goldens were generated (provenance)

These files are captured from a real run of the compiled program — **never
hand-assembled** — so they remain faithful to `CBTRN02C`'s actual byte output:

1. **Build** `CBTRN02C` via
   [`scripts/build_test_programs.sh`](../../../../scripts/build_test_programs.sh)
   (GnuCOBOL `cobc`, `--std=ibm-strict` dialect per AAP §0.3.2; the `ibm-strict`
   dialect is required because it accepts the `COMP-3`/zoned-decimal money fields
   that `-std=cobol85` rejects).
2. **Provision** an isolated workspace: load
   `tests/fixtures/posting/reject_103_expired/{acctdata,cardxref,tcatbal}.txt`
   into indexed files via
   [`tests/helpers/load_indexed.sh`](../../../helpers/load_indexed.sh) (an
   `IDCAMS REPRO` analog), and bind the `DALYTRAN` ASSIGN name to the sequential
   fixture `dailytran.txt`.
3. **Run** the program through
   [`tests/helpers/cobol_runner.py`](../../../helpers/cobol_runner.py), which wires
   each `SELECT … ASSIGN TO <NAME>` to its workspace file via environment variables.
4. **Capture** the outputs: `TRANFILE` → `tranfile.expected` (empty),
   `ACCTFILE` → `acctdat.expected` (unchanged), `TCATBALF` → `tcatbal.expected`
   (unchanged), `DALYREJS` → `dalyrejs.expected` (one reject record), and the
   process `RETURN-CODE` → `return_code.expected` (`4`).
5. **Normalize + commit** the baseline through the guarded update protocol —
   `assert_matches_golden(..., update=True)` with `CARDDEMO_UPDATE_GOLDENS=1`
   (never in CI).
6. **Validate** the captured baseline: `dalyrejs` trailer reason `== 0103`,
   message `== TRANSACTION RECEIVED AFTER ACCT EXPIRATION`, embedded `ORIG-TS`
   date `== 2024-12-14`, `RETURN-CODE == 4`, `tranfile` empty, and the `acctdat`
   / `tcatbal` masters unchanged versus their input fixtures.

---

## Explainability rationale (AAP §0.10.1)

A consolidated record of the non-obvious decisions behind this golden set (the
same _WHY_ notes are also woven into §4, §5, and §7), each tagged with the
rationale class it documents:

- **Assumption — encoding is single-sourced.** Every fixed-width offset and the
  zoned-decimal sign overpunch used above follow the `app/cpy/` copybook contract
  exactly as codified in
  [`tests/fixtures/README.md`](../../../fixtures/README.md); this document relies
  on that contract and deliberately does **not** duplicate the overpunch table or
  the record layouts (duplication would risk the two drifting).
- **Trade-off — golden-master byte comparison over field-by-field assertions.**
  The reject stream is asserted as a whole normalized record rather than as dozens
  of individual field checks. A single deterministic byte-diff is easier to
  maintain and gives full-record auditability; the cost is coarser failure
  locality, which the embedded unified diff in the comparator's mismatch error
  recovers when a difference actually occurs.
- **Rationale — reason 103 is deliberately isolated.** The transaction amount is
  held within the credit limit so the earlier-evaluated reject 102 cannot fire and
  overwrite the shared reason field, making **`0103` the sole, final reject
  reason** (see §4).

---

## 9. Sources & links

- **Program under test** (REFERENCE): [`app/cbl/CBTRN02C.cbl`](../../../../app/cbl/CBTRN02C.cbl)
  — paragraphs `1500-B-LOOKUP-ACCT` (reject 103) and `2500-WRITE-REJECT-REC`.
- **Copybooks** (REFERENCE): [`CVTRA06Y`](../../../../app/cpy/CVTRA06Y.cpy)
  (`DALYTRAN-RECORD`, 350 bytes — embedded in the reject record),
  [`CVACT03Y`](../../../../app/cpy/CVACT03Y.cpy) (XREF, 50),
  [`CVACT01Y`](../../../../app/cpy/CVACT01Y.cpy) (`ACCOUNT-RECORD`, 300),
  [`CVTRA01Y`](../../../../app/cpy/CVTRA01Y.cpy) (`TRAN-CAT-BAL-RECORD`, 50).
- **Paired input fixtures:**
  [`tests/fixtures/posting/reject_103_expired/README.md`](../../../fixtures/posting/reject_103_expired/README.md).
- **Master encoding contract** (link; reference only — not restated here):
  [`tests/fixtures/README.md`](../../../fixtures/README.md).
- **Comparator:** [`tests/helpers/golden_compare.py`](../../../helpers/golden_compare.py)
  (`assert_matches_golden`), backed by
  [`tests/helpers/record_codec.py`](../../../helpers/record_codec.py).
