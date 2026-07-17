# Posting fixture — boundary: exactly at credit limit (POST)

This scenario proves the credit-limit test in `CBTRN02C` is **inclusive** (`>=`):
a transaction whose amount makes the running balance land **exactly on** the
account credit limit must **POST**, not reject.

> **Why this README exists.** Static fixed-width `.txt` fixtures cannot carry
> docstrings, so — per the Explainability rule (AAP §0.10.1) and the master
> contract's §9.1/§10.3 mandate — this file carries the "why." All byte-encoding
> rules live in the master **[`tests/fixtures/README.md`](../../README.md)**; this
> README points to them rather than duplicating them.

## 1. Business rule exercised — `CBTRN02C`, paragraph `1500-B-LOOKUP-ACCT`

Quoted verbatim from `app/cbl/CBTRN02C.cbl`:

```cobol
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
```

Arithmetic for account `7` (cycle credit = cycle debit = `0.00`, credit limit
`+2065.00`, transaction amount `+2065.00`):

- `WS-TEMP-BAL = 0.00 - 0.00 + 2065.00 = 2065.00`
- `2065.00 >= 2065.00` is **TRUE → POST** (reason stays `0`; no reject 102).

The expiration guard in the same paragraph also passes, so there is no reject
103: `ACCT-EXPIRAION-DATE (2024-12-13) >= DALYTRAN-ORIG-TS(1:10) (2022-06-10)`
is TRUE.

## 2. Trade-off — why the amount is *exactly* the limit (vs. `reject_102_overlimit`)

This fixture is the **positive** counterpart to the sibling scenario
[`reject_102_overlimit`](../reject_102_overlimit/). Same account, card, and
category; the **only** difference is the transaction amount:

| Scenario | `DALYTRAN-AMT` | `WS-TEMP-BAL` | `>=` limit? | Result |
|---|---|---|---|---|
| `boundary_exact_limit` (here) | `0000020650{` (+2065.00) | 2065.00 | TRUE | **POST**, `RC 0` |
| `reject_102_overlimit` | `0000020650A` (+2065.01) | 2065.01 | FALSE | reject **102** |

Documenting both sides pins the operator down to **`>=` (inclusive)**, not `>`.
A "well under" amount would also post, but would never prove the single-cent
edge; the amount here is therefore deliberately the limit **exactly**. That
inclusive boundary is the financial-correctness edge this scenario pair exists
to lock in.

## 3. Fixture files in this folder

| File | ASSIGN name | Copybook | RECLN | Role |
|---|---|---|---:|---|
| `dailytran.txt` | `DALYTRAN` (SEQUENTIAL) | `CVTRA06Y` | 350 | Driving transaction; `DALYTRAN-AMT = 0000020650{` (+2065.00); card `4859452612877065`. |
| `cardxref.txt` | `XREFFILE` (INDEXED, key 16@0) | `CVACT03Y` | 50 | Maps card → account `7` (avoids reject 100). |
| `acctdata.txt` | `ACCTFILE` (INDEXED, key 11@0) | `CVACT01Y` | 300 | Account `7`; `ACCT-CREDIT-LIMIT = 00000020650{` (+2065.00); cycle credit/debit = 0. |
| `tcatbal.txt` | `TCATBALF` (INDEXED, key 17@0) | `CVTRA01Y` | 50 | Seed row acct7/01/0001 at `+0.00` → forces the `2700-B-UPDATE` (REWRITE) branch. |

## 4. Expected outcome

Authoritative summary; the byte-level golden mirror lives at
[`tests/golden/posting/boundary_exact_limit/`](../../../golden/posting/boundary_exact_limit/).

- `RETURN-CODE = 0`.
- Exactly **1** record written to `TRANSACT` (transaction id
  `0000000000683580`); `DALYREJS` **empty** (no rejects).
- `TCATBAL` row `00000000007/01/0001`: balance `0.00 → 2065.00` (`0000020650{`)
  via `2700-B-UPDATE`.
- `ACCOUNT 7`: `ACCT-CURR-BAL` `193.00 → 2258.00` (`00000022580{`);
  `ACCT-CURR-CYC-CREDIT` `0.00 → 2065.00` (`00000020650{`);
  `ACCT-CURR-CYC-DEBIT` unchanged.

> **Determinism caveat.** The emitted `TRANSACT` record's `TRAN-PROC-TS` is a
> runtime timestamp; it is **normalized** before golden comparison by
> [`tests/helpers/golden_compare.py`](../../../helpers/golden_compare.py).

## 5. Encoding & determinism

All fixtures follow the byte-encoding contract in
[`tests/fixtures/README.md`](../../README.md) — fixed widths (350 / 50 / 300 /
50), zoned-decimal sign overpunch on signed money fields, ISO `X(10)` dates, LF
line endings, and derivation from the `app/data/ASCII/*` seeds (which are never
edited). Those tables are **not** repeated here.

> **CRLF → LF choice for `tcatbal.txt` (documented per master §3.2).** The
> `tcatbal` seed ships as CRLF; this fixture is authored with **LF** to match
> the folder-wide LF mandate. This is safe because the loader strips `\r`/`\n`,
> and uniform LF prevents a stray `\r` from being absorbed into the trailing
> 22-byte `FILLER` and pushing the record one byte past `RECLN`.

## 6. Assumptions

- `DALYTRAN-ORIG-TS` and `DALYTRAN-PROC-TS` are fixed literals (ORIG-TS =
  `2022-06-10 19:27:53.000000`; PROC-TS = blank), so reruns are byte-identical.
- Seed cycle balances are zero (`ACCT-CURR-CYC-CREDIT = ACCT-CURR-CYC-DEBIT =
  0.00`), so `WS-TEMP-BAL` equals the transaction amount exactly — the cleanest
  way to land the running balance on the limit.
- The scenario is self-contained: the card resolves via `cardxref.txt`, the
  account via `acctdata.txt`, and the category-balance row via `tcatbal.txt`.

## 7. Data governance (synthetic provenance, per master §10)

Card `4859452612877065`, account `00000000007`, and customer `000000007` are
**synthetic, seed-derived** values from the published AWS CardDemo sample
datasets (`app/data/ASCII/*`); they represent **no real person or account**.
Only the business-rule field — `DALYTRAN-AMT` — is reshaped (to exactly the
credit limit); all identity bytes are carried unchanged from the seeds.

---

*Mandatory Explainability artifact (AAP §0.10.1) for the four static `.txt`
fixtures in this folder. Production sources under `app/` and seeds under
`app/data/` are REFERENCE ONLY and are never modified (AAP §0.8.2).*
