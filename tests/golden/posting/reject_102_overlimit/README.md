# Posting golden-master — reject reason 102 (OVERLIMIT TRANSACTION)

These are the byte-exact expected outputs of `app/cbl/CBTRN02C.cbl` when the paired
`reject_102_overlimit` fixtures drive a single transaction **one cent over the account
credit limit**, so it is rejected with **reason 102** and **nothing is posted**.

> **Path-mirroring & Explainability contract.** This directory mirrors
> [`../../../fixtures/posting/reject_102_overlimit/README.md`](../../../fixtures/posting/reject_102_overlimit/README.md)
> one-for-one (identical domain/scenario spelling and case), because
> `tests/helpers/golden_compare.py` (`assert_matches_golden`) pairs a scenario's
> **input** fixtures with its **expected** outputs purely by path — see
> [`../../../fixtures/README.md`](../../../fixtures/README.md) §2.1 (authoritative).
> Per the Explainability mandate (AAP §0.10.1), this README is the mandated *why* for
> the otherwise-opaque `.expected` bytes in this folder, since a static data file cannot
> carry a docstring. The **byte-encoding contract** — field widths, offsets, the
> zoned-decimal sign-overpunch table, and the LF rule — lives in the master
> [`../../../fixtures/README.md`](../../../fixtures/README.md); this document
> **references** it and never restates or contradicts it.
>
> **Program under test is REFERENCE-only.** `app/cbl/CBTRN02C.cbl` is the rule source and
> is **never modified** (AAP §0.8.2, §0.10.2). If a fresh run disagrees with these
> goldens, the fixtures or the load step are wrong — not the program, and not these files.

## Business rule exercised

Transcribed from `app/cbl/CBTRN02C.cbl` (paragraph `1500-B-LOOKUP-ACCT`, whose result the
main post/reject loop then acts on):

```
COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
    (POST)
ELSE
    reject reason 102, message "OVERLIMIT TRANSACTION"
```

Plugging in this scenario's values — `ACCT-CURR-CYC-CREDIT = 0.00`,
`ACCT-CURR-CYC-DEBIT = 0.00`, `DALYTRAN-AMT = +2065.01`, `ACCT-CREDIT-LIMIT = +2065.00`:

- `WS-TEMP-BAL = 0.00 - 0.00 + 2065.01 = 2065.01`
- the test `2065.00 >= 2065.01` is **FALSE**, so the `ELSE` branch fires →
  `MOVE 102 TO WS-VALIDATION-FAIL-REASON` / `MOVE 'OVERLIMIT TRANSACTION' TO WS-VALIDATION-FAIL-REASON-DESC`
- the main loop takes its `ELSE` branch → `ADD 1 TO WS-REJECT-COUNT` and
  `PERFORM 2500-WRITE-REJECT-REC` (one record written to `DALYREJS`)
- at end-of-job `IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE`.

**WHY the amount is exactly `+0.01` over the limit — Trade-off vs. `boundary_exact_limit`.**
The operator is `>=`, not `>`. A balance *exactly at* the limit therefore **posts** — that
is the sibling scenario
[`boundary_exact_limit`](../../../fixtures/posting/boundary_exact_limit/README.md)
(`DALYTRAN-AMT = +2065.00`, whose golden `return_code.expected = 0`) — while *one cent
over* **rejects** (this scenario). The pair pins the operator to the cent: a "well over"
amount would also reject but would not prove that the single-cent edge is handled
correctly, so the amount here is the limit **plus exactly `0.01`** and nothing larger.

**WHY the transaction date is kept inside the account's validity window.** The expiration
check (reason 103) is an independent `IF` evaluated *after* the over-limit check **in the
same paragraph**, and both branches move into the *same* `WS-VALIDATION-FAIL-REASON` field
— so a triggered 103 would **overwrite** 102. This scenario's `DALYTRAN-ORIG-TS` date
(`2022-06-10`) is earlier than the account's `ACCT-EXPIRAION-DATE` (`2024-12-13`), so the
expiration check stays on its `CONTINUE` path and **102 remains the sole, deterministic
reject reason**. (The copybook `CVACT01Y` preserves the misspelling `ACCT-EXPIRAION-DATE`.)

**WHY `PROC-TS` is blank in the embedded reject record.** `2500-WRITE-REJECT-REC` writes
the daily-transaction record **verbatim, as read** (`MOVE DALYTRAN-RECORD TO
REJECT-TRAN-DATA`) — the reject path applies **no** processing-timestamp stamp — and the
paired fixture's `DALYTRAN-PROC-TS` is blank, so the embedded reject bytes carry no
run-varying value and stay byte-reproducible.

## Golden files in this folder

Six artifacts mirror this scenario's outputs one-for-one; each `.expected` file is what a
correct run must reproduce after normalization.

| File | What it is | Expected | WHY |
|---|---|---|---|
| `dalyrejs.expected` | `DALYREJS` reject stream | exactly **one** 430-byte reject record (layout below) | the sole reject; the primary assertion of the scenario |
| `acctdat.expected` | `ACCTFILE` account 7 after the run | **unchanged** — byte-identical to the input fixture (300 B) | the reject path issues no `REWRITE`; `2800-UPDATE-ACCOUNT-REC` is never reached |
| `tcatbal.expected` | `TCATBALF` row acct 7 / type `01` / cat `0001` after the run | **unchanged** (50 B) | `2700-UPDATE-TCATBAL` is never reached on the reject path |
| `tranfile.expected` | posted-transaction output (`TRANFILE`) | **empty (0 bytes)** | `2000-POST-TRANSACTION` is never reached ⇒ nothing is posted |
| `return_code.expected` | process `RETURN-CODE` | `4` | `WS-REJECT-COUNT > 0` ⇒ `MOVE 4 TO RETURN-CODE` (soft reject, **not** an abend) |
| `README.md` | this document | — | the Explainability artifact for the folder |

**WHY a reject has zero side effects.** Because validation fails, the loop bypasses
`2000-POST-TRANSACTION` entirely; `2700-UPDATE-TCATBAL`, `2800-UPDATE-ACCOUNT-REC`, and
`2900-WRITE-TRANSACTION-FILE` all sit under it and never run. That is precisely why both
masters are unchanged and `tranfile.expected` is empty — a money-critical invariant this
scenario locks down.

## The 430-byte reject record layout

The single `DALYREJS` record is **430 bytes** = the 350-byte `DALYTRAN-RECORD` **verbatim**
(as read from `dailytran.txt`) followed by an 80-byte `VALIDATION-TRAILER`:

| Bytes | Field | Content |
|---|---|---|
| 1–350 | `DALYTRAN-RECORD` (`REJECT-TRAN-DATA`) | the driver transaction, copied **unchanged** as read |
| 351–354 | reason `PIC 9(04)` | `0102` |
| 355–430 | description `PIC X(76)` | `OVERLIMIT TRANSACTION` + trailing spaces |

- The trailer's reason field is `PIC 9(04)`, so reject reason **102** is stored
  zero-padded as **`0102`**.
- The description is `PIC X(76)`; `OVERLIMIT TRANSACTION` is **21 characters**, right-space
  padded to 76 ⇒ **21 characters + 55 trailing spaces**.
- Within the embedded transaction, `DALYTRAN-AMT` (cols 133–143) = `0000020650A`
  (= +2065.01; the trailing overpunch `A` encodes `+` on the final digit `1` — see the
  master [`../../../fixtures/README.md`](../../../fixtures/README.md) §3.4 for the
  overpunch table, **not reproduced here**), `DALYTRAN-ORIG-TS` (cols 279–304) =
  `2022-06-10 19:27:53.000000`, and `DALYTRAN-PROC-TS` (cols 305–330) = **blank**.

The exact widths and offsets of the 350-byte `DALYTRAN-RECORD` are single-sourced in the
master contract §5.1; this section summarizes only the 80-byte trailer that is specific to
the reject output.

## Determinism & normalization

These goldens are compared to live output by
`tests/helpers/golden_compare.py::assert_matches_golden`. `dalyrejs.expected` is compared
in **record mode** with `layout="DALYTRAN"`: the comparator masks only the run-generated
`DALYTRAN-PROC-TS` field *in place* — on both sides — while preserving the deterministic
`DALYTRAN-ORIG-TS`, the trailer, and the total record width, and it normalizes trailing
whitespace and line endings symmetrically. The stored literal bytes (a fixed `ORIG-TS` and
a blank `PROC-TS`) are therefore stable across runs. `acctdat.expected` and
`tcatbal.expected` are deterministic masters; `tranfile.expected` is empty; and
`return_code.expected` is the constant `4`.

## How these goldens were produced (run-and-capture provenance)

The `.expected` bytes are **captured from a real run** and blessed through the same
comparator that later reads them — they are **not** hand-assembled:

1. Build `CBTRN02C` via `scripts/build_test_programs.sh`
   (`cobc -x -fixed -I app/cpy --std=ibm-strict`).
2. Load the paired fixtures into GnuCOBOL indexed files via
   `tests/helpers/load_indexed.sh` — `ACCTFILE` (key 11@0), `XREFFILE` (key 16@0),
   `TCATBALF` (key 17@0) — and bind the sequential `DALYTRAN` input to `dailytran.txt`.
3. Run the program through `tests/helpers/cobol_runner.py`.
4. Capture the outputs: `TRANFILE` → `tranfile.expected` (empty), `ACCTFILE` →
   `acctdat.expected` (unchanged), `TCATBALF` → `tcatbal.expected` (unchanged),
   `DALYREJS` → `dalyrejs.expected` (the one 430-byte reject), and the process return code
   → `return_code.expected` (`4`).
5. Bless the goldens through the comparator's guarded update path —
   `assert_matches_golden(..., update=True)` with `CARDDEMO_UPDATE_GOLDENS=1` (**both**
   signals are required) — which writes the normalized captured bytes here.

If a fresh run yields anything other than the above, the fixtures or the load step are
wrong — **not** these goldens.

## Pairing & seed policy

- These goldens pair **1:1** and in lockstep (byte-identical folder name) with the input
  fixtures at
  [`../../../fixtures/posting/reject_102_overlimit/README.md`](../../../fixtures/posting/reject_102_overlimit/README.md);
  that README is authoritative for the *input-side* rationale.
- The one-cent boundary is co-defined with the sibling
  [`boundary_exact_limit`](../../../fixtures/posting/boundary_exact_limit/README.md)
  scenario (exactly-at-limit posts, one-cent-over rejects); editing either amount breaks a
  deliberate money-critical boundary assertion.
- The underlying seeds in `app/data/ASCII/` are **REFERENCE-only and never modified**
  (AAP §0.10.2); fixtures are derived copies. The master byte-encoding contract is
  [`../../../fixtures/README.md`](../../../fixtures/README.md).

## Sources & scope

- **Rule source:** `app/cbl/CBTRN02C.cbl` — paragraphs `1500-B-LOOKUP-ACCT`, the main
  post/reject loop, and `2500-WRITE-REJECT-REC` (REFERENCE-only, never modified).
- **Encoding contract:** [`../../../fixtures/README.md`](../../../fixtures/README.md)
  (widths, offsets, overpunch table, LF rule).
- **Paired inputs:**
  [`../../../fixtures/posting/reject_102_overlimit/README.md`](../../../fixtures/posting/reject_102_overlimit/README.md).
- **Comparator:** `tests/helpers/golden_compare.py` (`assert_matches_golden`,
  `layout="DALYTRAN"`).
