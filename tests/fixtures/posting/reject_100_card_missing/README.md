# Posting fixture — reject reason 100 (INVALID CARD NUMBER FOUND)

These fixtures drive `app/cbl/CBTRN02C.cbl` so that a daily transaction whose card
number is **absent from the cross-reference** is rejected with **reason 100 —
`INVALID CARD NUMBER FOUND`**, pinning the program's `1500-A-LOOKUP-XREF`
`INVALID KEY` path.

> **Why this README exists.** These static, fixed-width `.txt` fixtures cannot carry
> docstrings, so — per the Explainability rule (AAP §0.10.1) — this document is the
> mandated *why* for their bytes. The authoritative byte-encoding contract lives in
> the master **[`tests/fixtures/README.md`](../../README.md)**; this README only
> summarizes what is specific to reject 100 and never contradicts the master.

## 1. Business rule exercised — `CBTRN02C` `1500-VALIDATE-TRAN` / `1500-A-LOOKUP-XREF`

The **first** validation step is the cross-reference lookup:

```
read XREF by DALYTRAN-CARD-NUM
  INVALID KEY  ->  reject reason 100, message "INVALID CARD NUMBER FOUND"
```

- The transaction's `DALYTRAN-CARD-NUM = 4859452612877065`.
- `cardxref.txt` contains **only** card `0927987108636232` — so the read for
  `4859452612877065` returns **`INVALID KEY`**, and validation stops at the very
  first check with reason **100**.

> **WHY the card is deliberately absent (Assumption / test-design).** Reject 100 is
> the *card-not-found* branch, which must fire **before** any account, limit, or
> expiration logic. The fixture guarantees this by giving the cross-reference a
> **different** card than the transaction carries, so the `INVALID KEY` on the XREF
> read is the first and only failure — no later check can mask or reorder it.

## 2. Fixture files in this folder

| File | Copybook | `RECLN` | Line ending | Records | Role / key values |
|---|---|---:|---|---:|---|
| `dailytran.txt` | `CVTRA06Y` (DALYTRAN) | 350 | LF | 1 | Transaction with `CARD 4859452612877065` — the card that is **not** in the cross-reference. `AMT +504.77`, `ORIG-TS` date `2022-06-10`, `PROC-TS` blank. |
| `cardxref.txt` | `CVACT03Y` (XREF) | 50 | LF | 1 | Holds a **different** card `0927987108636232` → cust `000000020` → acct `00000000020`, so the transaction's card misses. |
| `acctdata.txt` | `CVACT01Y` (ACCOUNT) | 300 | LF | 1 | account `00000000007` (present but never reached — the XREF lookup fails first). |
| `tcatbal.txt` | `CVTRA01Y` (TCATBAL) | 50 | LF | 1 | category row `00000000007 / 01 / 0001`, `TRAN-CAT-BAL 0.00` (present but never reached). |

> **WHY `PROC-TS` is blank.** `DALYTRAN-PROC-TS` is a runtime timestamp; leaving it
> blank keeps the fixture byte-reproducible for golden comparison (master §6.3).

## 3. Expected outcome (authoritative intent; golden is [planned])

- **`RETURN-CODE = 4`** — a soft reject (master §6.1: reject sets RC=4).
- **One record in `DALYREJS`** carrying reason **100** and message
  **`INVALID CARD NUMBER FOUND`**.
- **No posting side effects** — `TCATBAL`, `ACCOUNT`, and `TRANSACT` are unchanged
  because validation stops at the XREF lookup.

## 4. Data governance / synthetic provenance (MA-24)

Both card numbers (`4859452612877065`, `0927987108636232`) and the account/customer
ids are **synthetic, seed-derived** values from the published AWS CardDemo sample
datasets (`app/data/ASCII/`), representing **no real person or account**. See master
§10.

## 5. Sources & scope

- **Derived from (never edited):** `app/data/ASCII/{dailytran,cardxref,acctdata,tcatbal}.txt`.
- **Record layouts:** `app/cpy/{CVTRA06Y,CVACT03Y,CVACT01Y,CVTRA01Y}.cpy`.
- **Business rule:** `app/cbl/CBTRN02C.cbl` (`1500-VALIDATE-TRAN`,
  `1500-A-LOOKUP-XREF`, `2500-WRITE-REJECT-REC`).
- **Consumed by (when present):** `tests/integration/test_cbtrn02c_posting.py` —
  **[planned]** (master §1).

**Minimal-change principle (mandatory).** Seeds under `app/data/ASCII/` and all
production sources under `app/` are **REFERENCE ONLY and are never modified**
(AAP §0.8.2).

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the four
static `.txt` fixtures in this folder.*
