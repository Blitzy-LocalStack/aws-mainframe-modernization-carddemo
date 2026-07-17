# Posting fixture — boundary: transaction date EQUALS expiration (POSTS)

These fixtures drive `app/cbl/CBTRN02C.cbl` with a transaction whose date is
**exactly equal to** the account's expiration date. Because the program's
comparison is **`>=`**, the transaction **POSTS** (it is *not* rejected). This is
the "on the line" half of the `>=` expiration boundary pair; its sibling
`reject_103_expired` is dated one day later and rejects with reason 103.

> **Why this README exists.** Static fixed-width `.txt` fixtures cannot carry
> docstrings, so — per the Explainability rule (AAP §0.10.1) — this is the mandated
> *why*. The byte-encoding contract lives in the master
> **[`tests/fixtures/README.md`](../../README.md)**; this README only covers what is
> specific to the expiry-equal boundary.

## 1. Business rule exercised — `CBTRN02C` `1500-VALIDATE-TRAN` (expiration check)

```
IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
    (POST)
ELSE
    reject reason 103 "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"
```

The transaction date is the first 10 characters of `DALYTRAN-ORIG-TS`
(`YYYY-MM-DD`). With this scenario's values:

- `ACCT-EXPIRAION-DATE = 2024-12-13`, `DALYTRAN-ORIG-TS(1:10) = 2024-12-13`
- the test `2024-12-13 >= 2024-12-13` is **TRUE**, so the transaction **POSTS**.

(The account field uses the copybook's preserved misspelling `ACCT-EXPIRAION-DATE`;
master §5.2.)

> **WHY the date is exactly the expiration — Trade-off vs. `reject_103_expired`.** The
> comparison is `>=`, not `>`. A transaction dated **equal to** the expiration date
> therefore **posts** (this scenario), while **one day past** **rejects**
> (`reject_103_expired`). The pair pins the operator to `>=`: a date "well before"
> expiration would also post but would not prove the single-day edge is handled
> correctly, so the date here is deliberately **equal to** the expiration and no
> earlier.

> **WHY the amount stays within the limit.** The over-limit check (reason 102) runs
> before the expiration check and shares the same reason field, so a triggered 102
> would mask this scenario's expiry-boundary result. `DALYTRAN-AMT = +504.77` is
> well under `ACCT-CREDIT-LIMIT = 2065.00`, so the over-limit check stays on its
> POST path and the expiry-equal POST is the sole, deterministic outcome.

## 2. Fixture files in this folder

| File | Copybook | `RECLN` | Line ending | Records | Role / key values |
|---|---|---:|---|---:|---|
| `dailytran.txt` | `CVTRA06Y` (DALYTRAN) | 350 | LF | 1 | Transaction `ORIG-TS` date **`2024-12-13`** (= expiration), `AMT +504.77`, `CARD 4859452612877065`, `PROC-TS` blank. |
| `cardxref.txt` | `CVACT03Y` (XREF) | 50 | LF | 1 | card `4859452612877065` → cust `000000007` → acct `00000000007`. |
| `acctdata.txt` | `CVACT01Y` (ACCOUNT) | 300 | LF | 1 | account `00000000007`: `EXPIRAION-DATE 2024-12-13`, `CREDIT-LIMIT 2065.00`, cycle credit/debit `0.00`. |
| `tcatbal.txt` | `CVTRA01Y` (TCATBAL) | 50 | LF | 1 | category row `00000000007 / 01 / 0001`, `TRAN-CAT-BAL 0.00` (update-branch target). |

> **WHY `PROC-TS` is blank, `ORIG-TS` is not.** `PROC-TS` is a runtime timestamp and
> is blanked for determinism; `ORIG-TS` carries the transaction **date** the
> expiration check reads, so it is deterministic author-supplied data (master §6.3).

## 3. Expected outcome (authoritative intent; golden is [planned])

- **`RETURN-CODE = 0`** — POST, no reject.
- **`DALYREJS` empty.**
- **`TCATBAL` updated (`2700-B-UPDATE`):** `0.00 + 504.77 = 504.77`.
- **ACCOUNT updated** and the posted transaction **written to `TRANSACT`**.

## 4. Data governance / synthetic provenance (MA-24)

Card `4859452612877065`, account `00000000007`, and customer `000000007` are
**synthetic, seed-derived** values from the published AWS CardDemo sample datasets,
representing **no real person or account**; only the transaction date is tuned to
equal the expiration. See master §10.

## 5. Sources & scope

- **Derived from (never edited):** `app/data/ASCII/{dailytran,cardxref,acctdata,tcatbal}.txt`.
- **Record layouts:** `app/cpy/{CVTRA06Y,CVACT03Y,CVACT01Y,CVTRA01Y}.cpy`.
- **Business rule:** `app/cbl/CBTRN02C.cbl` (`1500-VALIDATE-TRAN`, expiration `>=`).
- **Consumed by (when present):** `tests/integration/test_cbtrn02c_posting.py` —
  **[planned]** (master §1).

**Minimal-change principle (mandatory).** Seeds under `app/data/ASCII/` and all
production sources under `app/` are **REFERENCE ONLY and are never modified**
(AAP §0.8.2).

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the four
static `.txt` fixtures in this folder.*
