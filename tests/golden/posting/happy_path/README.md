# CBTRN02C posting — happy-path GOLDEN outputs (in-limit, non-expired → POST via `2700-B-UPDATE`)

This folder holds the **expected outputs** (`*.expected`) that
[`tests/helpers/golden_compare.py`](../../../../tests/helpers/golden_compare.py)
diffs the compiled [`app/cbl/CBTRN02C.cbl`](../../../../app/cbl/CBTRN02C.cbl)
output against, for the posting **happy path**: a single well-formed daily
transaction that passes **both** `1500-VALIDATE-TRAN` lookups and **both**
boundary checks, so `CBTRN02C` posts it, writes one `TRANSACT` record, emits
**no** reject, and exits `RETURN-CODE = 0`. It is the **output mirror**, paired
1:1, of the input scenario at
[`tests/fixtures/posting/happy_path/`](../../../fixtures/posting/happy_path/README.md).

> **Why this README exists (Explainability, AAP §0.10.1).** The five `.expected`
> files here are fixed-width, byte-exact records (and one bare return code) that
> **cannot carry inline comments**, so this document is their mandated *why*: the
> business rule they encode, the arithmetic that produces each number, the
> determinism rules that make the comparison stable, and the run-and-capture
> provenance of the bytes. The **authoritative byte-encoding contract** — field
> widths, offsets, the zoned-decimal sign-overpunch table, the implied decimal,
> and the LF / trailing-newline rules — lives in the master
> **[`tests/fixtures/README.md`](../../../fixtures/README.md)**. This README
> **references** that contract (by section) and **must not restate or contradict
> it**; it documents only the *output semantics* of this scenario. Its
> expected-outcome numbers are, by design, **identical** to §4 of the paired
> input README.

---

## 1. Scenario intent & business rule (`CBTRN02C` `1500-VALIDATE-TRAN`)

Validation runs two lookups then two boundary checks (master §6.1 transcribes the
rule verbatim). The happy path passes all four and falls through to posting:

1. **XREF lookup (`1500-A-LOOKUP-XREF`).** Card `4859452612877065` **resolves** in
   the cross-reference to `XREF-ACCT-ID = 00000000007` (account 7). → no reject 100.
2. **ACCOUNT lookup (`1500-B-LOOKUP-ACCT`).** Account `00000000007` **resolves** in
   the account master. → no reject 101.
3. **Over-limit boundary (`>=`).**
   `WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT (0.00) − ACCT-CURR-CYC-DEBIT (0.00) +
   DALYTRAN-AMT (504.77) = 504.77`. Because `ACCT-CREDIT-LIMIT (2065.00) >=
   WS-TEMP-BAL (504.77)`, i.e. **`0 − 0 + 504.77 ≤ 2065.00`**, the transaction is
   **within limit**. → no reject 102.
4. **Expiration boundary (`>=`).** Because
   `ACCT-EXPIRAION-DATE ("2024-12-13") >= DALYTRAN-ORIG-TS(1:10) ("2022-06-10")`,
   i.e. **`2024-12-13 ≥ 2022-06-10`**, the transaction is **not expired**.
   → no reject 103.
   > **Assumption.** The copybook field name is the preserved misspelling
   > `ACCT-EXPIRAION-DATE` (master §5.2) — reproduced exactly, not "corrected",
   > because the misspelled name *is* the contract the program compiles against.

Because `WS-VALIDATION-FAIL-REASON = 0`, `2000-POST-TRANSACTION` runs and:

- **`2700-UPDATE-TCATBAL → 2700-B-UPDATE-TCATBAL-REC`** — the category-balance key
  `00000000007 / 01 / 0001` already exists (seeded, see §2), so the read succeeds
  (`status 00`) and the *update-existing* branch runs `ADD DALYTRAN-AMT TO
  TRAN-CAT-BAL` then `REWRITE` — **not** the create branch `2700-A`.
- **`2800-UPDATE-ACCOUNT-REC`** — `ADD DALYTRAN-AMT TO ACCT-CURR-BAL`; because
  `DALYTRAN-AMT (504.77) >= 0` it also `ADD`s to `ACCT-CURR-CYC-CREDIT` (the debit
  accumulator is left untouched), then `REWRITE`s the account.
- **`2900-WRITE-TRANSACTION-FILE`** — `WRITE`s exactly **one** posted `TRAN-RECORD`
  to `TRANSACT`.

No reject record is written, so `WS-REJECT-COUNT = 0` and the run ends with
`RETURN-CODE = 0`.

## 2. Why the `2700-B` UPDATE branch runs — and why the `TCATBAL` FILLER stays 22 zeros

The paired input `tcatbal.txt` deliberately seeds an **existing** category-balance
row (`00000000007 / 01 / 0001`) at **`+100.00`** (paired input README §2).

> **Assumption / Trade-off.** A key-matching, *non-zero* seed forces
> `2700-UPDATE-TCATBAL` down the **update-existing** arm (`2700-B-UPDATE`) instead
> of the create arm (`2700-A-CREATE`), and makes the arithmetic **observable**:
> `100.00 + 504.77 = 604.77` is provably distinct from a create-from-zero result
> (`0.00 + 504.77 = 504.77`), so a test cannot pass by silently taking the wrong
> branch. Consequently the golden `tcatbal.expected` **preserves the fixture's
> 22-zero `FILLER`**: `2700-B` does `REWRITE FROM` the *read-into* record and
> **never `INITIALIZE`s** it, so whatever `FILLER` bytes the fixture carried
> survive verbatim. This is deliberately the *opposite* of the sibling
> `posting/zero_balance` scenario, whose `2700-A-CREATE` path **does** `INITIALIZE`
> the record (spacing the `FILLER`). Documenting the branch here is what lets a
> reviewer confirm the 22 zeros are a *correct consequence of the rule*, not an
> encoding slip.

## 3. Expected outputs (authoritative for the golden files + integration asserts)

One row per golden file. Amounts are shown as decimals with their encoded
zoned-decimal literal in `code`; see master **§3.4** (sign overpunch) and **§3.5**
(implied decimal) for *how* those bytes encode — **not restated here**. Layouts
are named by copybook (master §5.x) rather than reproduced.

| Golden file | Represents (ASSIGN) | Layout / RECLEN | Expected value |
|---|---|---|---|
| `tranfile.expected` | posted `TRANSACT` record (`TRANFILE`) | `CVTRA05Y` / 350 | Bytes **1–304** are a verbatim pass-through of the input `dailytran.txt` named fields (`TRAN-ID 0000000000683580`, type `01`, cat `0001`, `TRAN-AMT 0000005047G` = **+504.77**, card `4859452612877065`, `TRAN-ORIG-TS "2022-06-10 19:27:53.000000"`). `TRAN-PROC-TS` (305–330) is a **runtime** stamp → **masked** (see §4). `FILLER` (331–350, `PIC X(20)`) = **`LOW-VALUES`** (see §4 note). Exactly **one** record. |
| `acctdat.expected` | updated `ACCOUNT` 7 (`ACCTFILE`) | `CVACT01Y` / 300 | `ACCT-CURR-BAL` `193.00 → ` **`697.77`** (`00000006977G`); `ACCT-CURR-CYC-CREDIT` `0.00 → ` **`504.77`** (`00000005047G`); `ACCT-CURR-CYC-DEBIT` **unchanged `0.00`** (`00000000000{`); every other field byte-identical to the input fixture (`ACCT-CREDIT-LIMIT 00000020650{` = 2065.00, `ACCT-EXPIRAION-DATE 2024-12-13`, `ACCT-ADDR-ZIP A000000000`, then `ACCT-GROUP-ID` + trailing `FILLER` = **188 spaces**). |
| `tcatbal.expected` | updated `TCATBAL` row (`TCATBALF`) | `CVTRA01Y` / 50 | Key `00000000007010001`; `TRAN-CAT-BAL` `100.00 → ` **`604.77`** (`0000006047G`); `FILLER` (`PIC X(22)`) = **22 zeros** (preserved from the fixture, see §2). |
| `dalyrejs.expected` | reject stream (`DALYREJS`) | — | **Empty — 0 bytes.** No reject on a clean post. |
| `return_code.expected` | process `RETURN-CODE` | — | The single byte **`0`** (no trailing newline). All posted; `WS-REJECT-COUNT = 0`. |

These five values are the single source of truth that the paired input README §4,
these `.expected` files, and `tests/integration/test_cbtrn02c_posting.py` all
encode; they are mutually consistent and byte-exact.

## 4. Determinism & timestamp normalization

Golden comparison is deterministic and byte-oriented; there is **no float
tolerance**. Monetary fields are asserted as **exact fixed-point** values — the
byte-exact record comparison admits no rounding or epsilon — and all
record-bearing goldens use **LF** line endings with a single trailing newline
after their one record (`tranfile`/`acctdat`/`tcatbal`); `dalyrejs` is 0 bytes and
`return_code` is the bare byte `0` (master §3.2, §3.3).

The only run-varying field is `TRAN-PROC-TS`: `2000-POST-TRANSACTION` stamps it
from `Z-GET-DB2-FORMAT-TIMESTAMP` (the runtime `DB2-FORMAT-TS` clock), so it
changes on every run. The scenario is compared in `golden_compare` **record mode**
via `assert_matches_golden(actual, golden_path, layout="TRAN")` (the produced
record is a `CVTRA05Y` `TRAN`; the `CVTRA06Y` `DALYTRAN` layout is geometry-
identical for these fields).

> **Trade-off (mask *only* `PROC-TS`, keep `ORIG-TS`).** The comparator blanks
> **only** `TRAN-PROC-TS` (bytes 305–330) *in place*, and **preserves**
> `TRAN-ORIG-TS` (279–304), the trailing `FILLER`, and the full 350-byte width
> **verbatim, byte-exact** (master §6.3; `record_codec` flags only `…-PROC-TS`
> with `normalize_ts=True`). `ORIG-TS` is **not** masked because its first 10
> bytes are the deterministic transaction date the expiration check consumes
> (§1, step 4), so it must be asserted, not discarded. Masking `ORIG-TS` as well
> — which an earlier build did — is a rejected alternative: it would collapse the
> record and stop the suite from verifying the very date the rule depends on.

> **Assumption (`tranfile` `FILLER` = `LOW-VALUES`, not spaces).** The 20 trailing
> `FILLER` bytes (331–350) are **byte-verified as `LOW-VALUES` (`0x00`)** in the
> committed golden, because `2000-POST-TRANSACTION` moves only the *named* fields
> (`TRAN-ID`…`TRAN-ORIG-TS`) plus `TRAN-PROC-TS` and **never writes** the
> working-storage `TRAN-RECORD` `FILLER`, which GnuCOBOL leaves at its initial
> `LOW-VALUES`. Because record mode preserves the `FILLER` byte-exact (it is *not*
> masked), the program must reproduce those 20 `0x00` bytes for the diff to pass;
> this README records them as non-business trailing fill so the bytes are not
> mistaken for meaningful data.

## 5. How these goldens were generated (provenance — the primary path)

The golden bytes are **produced by running the program on the paired fixtures**,
not hand-assembled:

1. **Build** `CBTRN02C` via
   [`scripts/build_test_programs.sh`](../../../../scripts/build_test_programs.sh)
   (`cobc -x -fixed -I app/cpy --std=ibm-strict`).
2. **Load** the three indexed inputs from
   [`tests/fixtures/posting/happy_path/`](../../../fixtures/posting/happy_path/README.md)
   (`acctdata.txt`, `cardxref.txt`, `tcatbal.txt`) into GnuCOBOL indexed files with
   [`tests/helpers/load_indexed.sh`](../../../../tests/helpers/load_indexed.sh) /
   `vsam_loader.load_indexed` (ACCTFILE key len 11 @ 0, XREFFILE key len 16 @ 0,
   TCATBALF key len 17 @ 0) and bind the sequential `dailytran.txt` (master §4.2).
3. **Run** via
   [`tests/helpers/cobol_runner.py`](../../../../tests/helpers/cobol_runner.py)
   (`run_program` / `CobolRunner`) with the env-var `ASSIGN` bindings `DALYTRAN`,
   `XREFFILE`, `ACCTFILE`, `TCATBALF`, `TRANFILE`, `DALYREJS`.
4. **Capture** `TRANFILE` (unloaded to flat → `tranfile.expected`), `ACCTFILE` →
   `acctdat.expected`, `TCATBALF` → `tcatbal.expected`, `DALYREJS` →
   `dalyrejs.expected`, and the process return code → `return_code.expected`.
5. **Regenerate** under the guarded two-step opt-in
   (`assert_matches_golden(..., update=True)` **and** `CARDDEMO_UPDATE_GOLDENS=1`,
   never in CI) so goldens are only ever (re)written deliberately.
6. **Validate** the captured bytes against the documented values (`504.77`,
   `697.77`, `604.77`; RC `0`; empty `DALYREJS`) — they must match §3.

> **Alternatives Considered (golden-master byte-diff vs. field-by-field asserts).**
> A whole-record byte-diff (with `PROC-TS` masked) was chosen over per-field
> assertions because it is deterministic, catches width/offset drift and stray
> bytes that a field-by-field check would miss, and mirrors CardDemo's own
> reject-stream / `LISTCAT` verification style. The cost is coarser failure
> locality, which the field-aware, privacy-masked diff emitted by
> `golden_compare` on mismatch mitigates.

## 6. Consumers

- **[planned]** [`tests/integration/test_cbtrn02c_posting.py`](../../../integration/)
  loads the paired fixtures into an isolated workspace, runs the compiled
  `CBTRN02C`, and asserts the produced `TRANSACT`/`ACCOUNT`/`TCATBAL`/`DALYREJS`
  and `RETURN-CODE` against these goldens.
- **[planned]** the end-to-end posting / full-batch-cycle tests
  (`tests/e2e/`) reuse the same expected outputs.

## 7. Provenance & references

Outputs derive from running
[`app/cbl/CBTRN02C.cbl`](../../../../app/cbl/CBTRN02C.cbl) on the
[`tests/fixtures/posting/happy_path/`](../../../fixtures/posting/happy_path/README.md)
fixtures, which are themselves derived from the `app/data/ASCII/` seeds (master
§8; seeds are REFERENCE-only and never edited, AAP §0.8.2). Record layouts:
`CVTRA05Y` (TRAN 350, master §5.5), `CVACT01Y` (ACCOUNT 300, §5.2), `CVTRA01Y`
(TCATBAL 50, §5.6); inputs are shaped by `CVTRA06Y` (DALYTRAN 350, §5.1) and
`CVACT03Y` (XREF 50, §5.3).

- **Master encoding contract:** [`tests/fixtures/README.md`](../../../fixtures/README.md)
  — overpunch table (§3.4), implied decimal (§3.5), line endings & trailing
  newline (§3.2–§3.3), layouts (§5), rule semantics (§6), determinism (§6.3).
- **Paired input scenario:** [`tests/fixtures/posting/happy_path/README.md`](../../../fixtures/posting/happy_path/README.md)
  — the input fixtures and the §4 expected outcome this README mirrors.

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the five
`.expected` outputs in this folder, and the output-side counterpart of the
per-scenario documentation required by master `tests/fixtures/README.md` §9.1 /
§10.3.*
