# Posting scenario — reject 103 (transaction received after account expiration)

## Intent

These fixtures drive the compiled `app/cbl/CBTRN02C.cbl` down its **reject
reason 103** path. The card resolves in the cross-reference and its account is
found, and the transaction amount is **within** the account's credit limit, but
the transaction's origination date is **one calendar day past** the account's
expiration date — so `CBTRN02C` rejects the transaction (writing it to the
reject stream) instead of posting it.

## Business rule exercised

Transcribed verbatim from `CBTRN02C` paragraph `1500-B-LOOKUP-ACCT`, reached
after a successful XREF + ACCOUNT lookup and a within-limit amount:

```cobol
IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
    CONTINUE
ELSE
    MOVE 103 TO WS-VALIDATION-FAIL-REASON
    MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
      TO WS-VALIDATION-FAIL-REASON-DESC
END-IF
```

- The dates are compared as `X(10)` ISO `YYYY-MM-DD` strings: `ACCT-EXPIRAION-DATE`
  against the first 10 bytes of `DALYTRAN-ORIG-TS`. The copybook field-name
  misspelling **`ACCT-EXPIRAION-DATE`** (missing the second `T` of "EXPIRATION")
  is **part of the contract** and is preserved exactly (see
  [`tests/fixtures/README.md`](../../README.md) §5.2).
- **Concrete instance:** account `7` expiry `2024-12-13` vs. `ORIG-TS` date
  `2024-12-14` → `"2024-12-13" >= "2024-12-14"` is **FALSE** → **reject 103**.
  Paragraph `2500-WRITE-REJECT-REC` then writes the record to `DALYREJS`, and
  because a reject occurred the program sets **`RETURN-CODE = 4`**.

## Fixture files

| File | ASSIGN | Copybook | RECLN | Org | Role |
|---|---|---|---|---|---|
| `dailytran.txt` | `DALYTRAN` | `CVTRA06Y` | 350 | SEQUENTIAL | 1 driver record; `ORIG-TS` date = expiry + 1 (`2024-12-14`), `AMT` `+504.77` (in-limit) |
| `cardxref.txt` | `XREFFILE` | `CVACT03Y` | 50 | INDEXED (key 16@0) | card `4859452612877065` → acct `00000000007` (so no reject 100/101) |
| `acctdata.txt` | `ACCTFILE` | `CVACT01Y` | 300 | INDEXED (key 11@0) | account `7`, expiry `2024-12-13`, credit limit `+2065.00` |
| `tcatbal.txt` | `TCATBALF` | `CVTRA01Y` | 50 | INDEXED (key 17@0) | present so `OPEN I-O` succeeds; never updated on this path |

## Expected outcome

Authoritative for the golden mirror `tests/golden/posting/reject_103_expired/`
(authored separately by the golden agent) and for the integration asserts:

- **`RETURN-CODE = 4`**; **1** reject record in `DALYREJS`; **0** records in
  `TRANSACT`; ACCOUNT `7` and TCATBAL **unchanged**.
- The single `DALYREJS` record is the 350-byte `DALYTRAN-RECORD` **verbatim**
  (carrying the `2024-12-14…` `ORIG-TS`) followed by an 80-byte
  `VALIDATION-TRAILER`: reason `9(04)` = **`0103`** and description `X(76)` =
  **`TRANSACTION RECEIVED AFTER ACCT EXPIRATION`**, right-space-padded to 76 bytes.

## Why — Explainability

- **Assumption (ISO-string comparison).** `DALYTRAN-ORIG-TS(1:10)` is compared as
  an ISO `X(10)` string, so a lexicographic compare of `YYYY-MM-DD` is identical
  to a chronological one. That is *why* shifting the date by a single calendar day
  (`2024-12-13` → `2024-12-14`) reliably flips the `>=` test to FALSE — no real
  date arithmetic is needed in the fixture.
- **Trade-off (reject-code ordering — the load-bearing invariant of this
  scenario).** In `1500-B-LOOKUP-ACCT` the over-limit check (reject **102**) is
  evaluated **before** the expiration check (reject **103**), and both `MOVE` into
  the *same* `WS-VALIDATION-FAIL-REASON` field, so 103 would overwrite 102 if both
  fired. To make **103 the sole and final reason**, the amount is deliberately held
  **within** the credit limit (`+504.77` vs. limit `+2065.00`; cycle credit/debit
  are both `0`, so `WS-TEMP-BAL == AMT`) so reject 102 never fires. Getting this
  wrong would let the test pass for the wrong reason.
- **Assumption (tcatbal present-but-unused).** `tcatbal.txt` exists only so that
  `OPEN I-O` on `TCATBALF` succeeds; it is never read or updated on this path
  because validation short-circuits to the reject branch **before**
  `2000-POST-TRANSACTION`.
- **Derivation / minimal-change.** All four fixtures are derived from the shipped
  seeds in `app/data/ASCII/` (`dailytran`/`cardxref`/`acctdata`/`tcatbal`), which
  are **never edited** (AAP §0.8.2). `dailytran.txt` is the *only* file whose
  `ORIG-TS` date is shifted (to expiry + 1); every other field is verbatim from
  the seed row (with the `tcatbal` CRLF line ending normalized to LF).

## Encoding & references

Byte widths (350 / 300 / 50 / 50), the zoned-decimal sign-overpunch on the money
fields (`AMT`, `CREDIT-LIMIT`), ISO `X(10)` dates (`ORIG-TS`, `EXPIRAION-DATE`),
LF-only line endings, and the blank `PROC-TS` all follow the **authoritative
master contract at [`tests/fixtures/README.md`](../../README.md)** — consult it
(not this file) for the overpunch table and full record layouts; they are
intentionally **not** restated here.

At run time the indexed fixtures (`cardxref`, `acctdata`, `tcatbal`) are loaded
flat → indexed by `tests/helpers/load_indexed.sh` /
`tests/helpers/vsam_loader.load_indexed(...)` (an `IDCAMS REPRO` analog);
`dailytran.txt` is sequential and is consumed as-is.
