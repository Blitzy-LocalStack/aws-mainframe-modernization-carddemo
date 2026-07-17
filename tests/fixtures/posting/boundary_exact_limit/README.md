# Posting fixture — boundary: amount EXACTLY at credit limit (POSTS)

These fixtures drive `app/cbl/CBTRN02C.cbl` with a transaction whose amount lands
`WS-TEMP-BAL` **exactly on** the account credit limit. Because the program's
comparison is **`>=`**, the transaction **POSTS** (it is *not* rejected). This is
the "on the line" half of the `>=` credit-limit boundary pair; its sibling
`reject_102_overlimit` is one cent over and rejects.

> **Why this README exists.** Static fixed-width `.txt` fixtures cannot carry
> docstrings, so — per the Explainability rule (AAP §0.10.1) — this is the mandated
> *why*. The byte-encoding contract lives in the master
> **[`tests/fixtures/README.md`](../../README.md)**; this README only covers what is
> specific to the exact-limit boundary.

## 1. Business rule exercised — `CBTRN02C` `1500-VALIDATE-TRAN` (over-limit check)

```
COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
    (POST)
ELSE
    reject reason 102 "OVERLIMIT TRANSACTION"
```

With this scenario's values — `ACCT-CURR-CYC-CREDIT = 0.00`,
`ACCT-CURR-CYC-DEBIT = 0.00`, `DALYTRAN-AMT = +2065.00`, `ACCT-CREDIT-LIMIT =
+2065.00`:

- `WS-TEMP-BAL = 0.00 - 0.00 + 2065.00 = 2065.00`
- the test `2065.00 >= 2065.00` is **TRUE**, so the transaction **POSTS**.

> **WHY the amount is exactly the limit — Trade-off vs. `reject_102_overlimit`.** The
> comparison is `>=`, not `>`. A balance **exactly at** the limit therefore **posts**
> (this scenario), while **one cent over** **rejects** (`reject_102_overlimit`,
> `AMT +2065.01`). The pair pins the operator to `>=`: a "well under" amount would
> also post but would not prove the single-cent edge is handled correctly, so the
> amount here is deliberately the limit **exactly** and nothing less.

> **WHY the transaction date stays inside the validity window.** The expiration
> check (reason 103) runs after the over-limit check; both branches move into the
> same reason field, so a triggered 103 would overwrite the POST result. This
> fixture's `DALYTRAN-ORIG-TS` date (`2022-06-10`) is earlier than the account's
> `ACCT-EXPIRAION-DATE` (`2024-12-13`), so the expiration check stays on its
> POST path and the exact-limit POST is the sole, deterministic outcome.

## 2. Fixture files in this folder

| File | Copybook | `RECLN` | Line ending | Records | Role / key values |
|---|---|---:|---|---:|---|
| `dailytran.txt` | `CVTRA06Y` (DALYTRAN) | 350 | LF | 1 | Transaction `AMT +2065.00` (`00000206500{`) = the credit limit exactly; `CARD 4859452612877065`, `ORIG-TS` date `2022-06-10`, `PROC-TS` blank. |
| `cardxref.txt` | `CVACT03Y` (XREF) | 50 | LF | 1 | card `4859452612877065` → cust `000000007` → acct `00000000007`. |
| `acctdata.txt` | `CVACT01Y` (ACCOUNT) | 300 | LF | 1 | account `00000000007`: `CREDIT-LIMIT 2065.00`, cycle credit/debit `0.00`, `EXPIRAION-DATE 2024-12-13`. |
| `tcatbal.txt` | `CVTRA01Y` (TCATBAL) | 50 | LF | 1 | category row `00000000007 / 01 / 0001`, `TRAN-CAT-BAL 0.00` (update-branch target). |

## 3. Expected outcome (authoritative intent; golden is [planned])

- **`RETURN-CODE = 0`** — POST, no reject.
- **`DALYREJS` empty.**
- **`TCATBAL` updated (`2700-B-UPDATE`):** `0.00 + 2065.00 = 2065.00`.
- **ACCOUNT updated** and the posted transaction **written to `TRANSACT`**.

## 4. Data governance / synthetic provenance (MA-24)

Card `4859452612877065`, account `00000000007`, and customer `000000007` are
**synthetic, seed-derived** values from the published AWS CardDemo sample datasets,
representing **no real person or account**; only the amount is tuned to the exact
limit. See master §10.

## 5. Sources & scope

- **Derived from (never edited):** `app/data/ASCII/{dailytran,cardxref,acctdata,tcatbal}.txt`.
- **Record layouts:** `app/cpy/{CVTRA06Y,CVACT03Y,CVACT01Y,CVTRA01Y}.cpy`.
- **Business rule:** `app/cbl/CBTRN02C.cbl` (`1500-VALIDATE-TRAN`, over-limit `>=`).
- **Consumed by (when present):** `tests/integration/test_cbtrn02c_posting.py` —
  **[planned]** (master §1).

**Minimal-change principle (mandatory).** Seeds under `app/data/ASCII/` and all
production sources under `app/` are **REFERENCE ONLY and are never modified**
(AAP §0.8.2).

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the four
static `.txt` fixtures in this folder.*
