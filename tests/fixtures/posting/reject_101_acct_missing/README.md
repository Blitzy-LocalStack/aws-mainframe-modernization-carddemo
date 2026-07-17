# Posting fixture — reject_101_acct_missing

Deterministic, fixed-width **input** fixtures that drive `app/cbl/CBTRN02C.cbl`
down the **reject reason 101** path: the transaction's card *resolves* in the
card cross-reference, but the account it points at is **absent from the ACCOUNT
master**, so the transaction is rejected as `ACCOUNT RECORD NOT FOUND`.

> **Byte-encoding contract:** see [`../../README.md`](../../README.md)
> (authoritative — this file does **not** restate it). Field widths/offsets, the
> zoned-decimal sign-overpunch table, and the line-ending/`PROC-TS` determinism
> rules all live there. This scenario README only records *what this scenario
> does* and *why*, per the Explainability mandate (AAP §0.10.1) for static
> `.txt` files that cannot carry docstrings.

## 1. Business rule exercised (from `CBTRN02C` `1500-VALIDATE-TRAN`)

1. **`1500-A-LOOKUP-XREF` succeeds** — card `4859452612877065` is found in
   `XREFFILE`, resolving `XREF-ACCT-ID = 00000000007`.
2. **`1500-B-LOOKUP-ACCT` fails** — `MOVE XREF-ACCT-ID TO FD-ACCT-ID`, then
   `READ ACCTFILE`. Key `00000000007` is **absent** → `INVALID KEY` →
   `MOVE 101 TO WS-VALIDATION-FAIL-REASON` with description
   **`ACCOUNT RECORD NOT FOUND`**.
3. The rejected transaction is written to `DALYREJS` by `2500-WRITE-REJECT-REC`;
   at end-of-job `WS-REJECT-COUNT > 0` sets **`RETURN-CODE = 4`**.

> **Why the limit/expiry checks (102/103) do not apply:** in the source they
> live *only* inside the `NOT INVALID KEY` branch of `1500-B-LOOKUP-ACCT`, which
> is never entered here because the account read fails first. No `TRANSACT`
> record is written and no balances change — this fixes reject-101 as the single
> observable outcome (Assumption: matches `CBTRN02C` line-for-line).

## 2. Fixture files in this folder

| File | `ASSIGN` | Copybook | `RECLN` | Organization | Role |
|---|---|---|---:|---|---|
| `dailytran.txt` | `DALYTRAN` | `CVTRA06Y` | 350 | SEQUENTIAL | The 1 transaction to reject (card `4859452612877065`, `+504.77`). |
| `cardxref.txt` | `XREFFILE` | `CVACT03Y` | 50 | INDEXED (key 16 @0) | 1 row that **resolves** the card → account `00000000007`. |
| `acctdata.txt` | `ACCTFILE` | `CVACT01Y` | 300 | INDEXED (key 11 @0) | 1 **decoy** account `00000000020`; **deliberately omits `00000000007`** (this omission triggers the reject). |
| `tcatbal.txt` | `TCATBALF` | `CVTRA01Y` | 50 | INDEXED (key 17 @0) | 1 row so `OPEN I-O` succeeds; **never read** on this path. |

## 3. Determinism & encoding notes (specific to this scenario)

- All four fixtures are **LF (`\n`) terminated with a single trailing newline**
  and each record is the **exact** `RECLN` (350 / 50 / 300 / 50 bytes). Encoding
  details (overpunch, offsets) are governed by the parent contract, not here.
- **CRLF→LF normalization (mandated by the parent contract, §3.2).** The seed
  `app/data/ASCII/tcatbal.txt` ships as **CRLF**; this scenario's `tcatbal.txt`
  is authored **LF-only** (trailing `\r` stripped). **Why:** one deterministic
  line-ending convention across the scenario avoids a stray `\r` being absorbed
  into the trailing `FILLER`, which would push the record one byte over `RECLN`
  and corrupt golden comparison. The other three seeds are already LF.
- `DALYTRAN-PROC-TS` is **blank (26 spaces)** and `DALYTRAN-ORIG-TS` is the fixed
  seed literal `2022-06-10 19:27:53.000000`. **Why:** no wall-clock values means
  reruns are byte-identical (see parent §6.3).
- **Self-containment exception (parent §7).** This scenario intentionally omits
  exactly one key — account `00000000007` from `acctdata.txt` — to trigger the
  reject; the *card* key still resolves in `cardxref.txt`, so the failure is
  precisely the missing-account path and nothing else.

## 4. Expected outcome (authoritative for the golden mirror & integration asserts)

- `RETURN-CODE = 4`; **1** reject record in `DALYREJS`; **0** records in
  `TRANSACT`; decoy account `00000000020` and the `tcatbal` row **unchanged**.
- The `DALYREJS` record is the 350-byte `DALYTRAN-RECORD` **verbatim** followed
  by an 80-byte `VALIDATION-TRAILER` whose reason `9(04)` = **`0101`** and
  description `X(76)` = **`ACCOUNT RECORD NOT FOUND`** (right-space-padded).
- Companion golden mirror: `tests/golden/posting/reject_101_acct_missing/`
  (authored by the golden agent; kept path-identical per parent §2.1).

## 5. Sources & scope

- Derived from `app/data/ASCII/{dailytran,cardxref,acctdata,tcatbal}.txt`; rule
  source `app/cbl/CBTRN02C.cbl`; layouts
  `app/cpy/{CVTRA06Y,CVACT03Y,CVACT01Y,CVTRA01Y}.cpy`.
- **Seeds and production sources are REFERENCE-only and are never edited**
  (AAP §0.8.2). Fixtures are derived copies/subsets reshaped for this scenario.
