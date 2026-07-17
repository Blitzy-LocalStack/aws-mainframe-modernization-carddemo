# Posting fixture — happy path (valid transaction POSTS)

These fixtures drive `app/cbl/CBTRN02C.cbl` so that a single well-formed daily
transaction passes **both** lookups and **both** boundary checks and is **posted**:
the category balance is updated, the account is updated, the transaction is written
to `TRANSACT`, and no reject is emitted (`RETURN-CODE = 0`).

> **Why this README exists.** The fixtures here are static, fixed-width `.txt`
> files that cannot carry docstrings, so — per the project Explainability rule
> (AAP §0.10.1) — this document is the mandated *why* for their bytes. The
> authoritative byte-encoding contract (field widths, offsets, the zoned-decimal
> sign-overpunch table, line endings) lives in the master
> **[`tests/fixtures/README.md`](../../README.md)**; this scenario README only
> summarizes what is specific to the happy path and never restates or contradicts
> it.

## 1. Business rule exercised — `CBTRN02C` `1500-VALIDATE-TRAN`

Validation performs two lookups then two boundary checks; the happy path passes
all four:

- **XREF lookup (`1500-A-LOOKUP-XREF`):** `DALYTRAN-CARD-NUM = 4859452612877065`
  **is present** in `cardxref.txt` and resolves to `XREF-ACCT-ID = 00000000007`.
- **ACCOUNT lookup (`1500-B-LOOKUP-ACCT`):** account `00000000007` **is present**
  in `acctdata.txt`.
- **Over-limit check (`>=`):**
  `WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT`
  `= 0.00 - 0.00 + 504.77 = 504.77`; the test `ACCT-CREDIT-LIMIT (2065.00) >=
  504.77` is **TRUE**, so the transaction is **within limit**.
- **Expiration check (`>=`):** `ACCT-EXPIRAION-DATE (2024-12-13) >=
  DALYTRAN-ORIG-TS(1:10) (2022-06-10)` is **TRUE**, so the transaction is **not
  expired**. (Field name uses the copybook's preserved misspelling
  `ACCT-EXPIRAION-DATE`; master §5.2.)

## 2. Fixture files in this folder

| File | Copybook | `RECLN` | Line ending | Records | Role / key values |
|---|---|---:|---|---:|---|
| `dailytran.txt` | `CVTRA06Y` (DALYTRAN) | 350 | LF | 1 | The transaction to post: `DALYTRAN-ID 0000000000683580`, `TYPE 01`, `CAT 0001`, `AMT +504.77` (`00000050477G`), `CARD 4859452612877065`, `ORIG-TS` date `2022-06-10`, `PROC-TS` blank. |
| `cardxref.txt` | `CVACT03Y` (XREF) | 50 | LF | 1 | card `4859452612877065` → cust `000000007` → acct `00000000007` (+14-space `FILLER`). |
| `acctdata.txt` | `CVACT01Y` (ACCOUNT) | 300 | LF | 1 | account `00000000007`: `CURR-BAL 193.00`, `CREDIT-LIMIT 2065.00`, `EXPIRAION-DATE 2024-12-13`, cycle credit/debit `0.00`, blank group. |
| `tcatbal.txt` | `CVTRA01Y` (TCATBAL) | 50 | LF | 1 | category-balance row `00000000007 / 01 / 0001`, `TRAN-CAT-BAL 100.00` — the **update** branch target (the row already exists). |

> **WHY `PROC-TS` is blank.** `DALYTRAN-PROC-TS` (bytes 305–330) is a *runtime*
> processing timestamp. Leaving it blank (26 spaces, as in the seed) keeps the
> fixture free of any run-varying value so golden-master comparison stays
> byte-reproducible (master §6.3). `DALYTRAN-ORIG-TS` (bytes 279–304) is **not**
> blanked — its first 10 bytes are the transaction date the expiration check
> consumes, so it is deterministic author-supplied data.

## 3. Expected outcome (authoritative intent; golden is [planned])

For the **[planned]** mirror `tests/golden/posting/happy_path/` (master §1),
captured from actual output:

- **`RETURN-CODE = 0`** — no reject.
- **`DALYREJS` reject stream is empty** — the transaction is not rejected.
- **`TCATBAL` updated (update branch, `2700-B-UPDATE`):** the existing category
  row `00000000007 / 01 / 0001` has `DALYTRAN-AMT` added:
  `100.00 + 504.77 = 604.77`.
- **ACCOUNT `00000000007` updated** and the posted transaction **written to
  `TRANSACT`** (`TRAN-*` record, `TRAN-AMT +504.77`).

> **WHY the category row pre-exists (Trade-off vs. `zero_balance`).** This scenario
> seeds an existing `TCATBAL` row (`100.00`) so the **update** branch
> (`2700-B-UPDATE`) is exercised. The sibling `posting/zero_balance` omits the row
> to exercise the **create** branch (`2700-A-CREATE`). Together they cover both
> arms of the create-vs-update decision (master §6.1).

## 4. Data governance / synthetic provenance (MA-24)

The card number (`4859452612877065`), account id (`00000000007`), and customer id
(`000000007`) are **synthetic, seed-derived** values from the published AWS
CardDemo sample datasets (`app/data/ASCII/`), representing **no real person or
account**. Only non-identity business-rule fields (amount, balances, dates) are
tuned for this scenario. See master §10.

## 5. Sources & scope

- **Derived from (never edited):** `app/data/ASCII/{dailytran,cardxref,acctdata,tcatbal}.txt`.
- **Record layouts:** `app/cpy/{CVTRA06Y,CVACT03Y,CVACT01Y,CVTRA01Y}.cpy`.
- **Business rule:** `app/cbl/CBTRN02C.cbl` (`1500-VALIDATE-TRAN`,
  `2000-POST-TRANSACTION`, `2700-UPDATE-TCATBAL`, `2800-UPDATE-ACCOUNT-REC`).
- **Consumed by (when present):** `tests/integration/test_cbtrn02c_posting.py` and
  the end-to-end posting cycle — **[planned]** (master §1).

**Minimal-change principle (mandatory).** Seeds under `app/data/ASCII/` and all
production sources under `app/` are **REFERENCE ONLY and are never modified**
(AAP §0.8.2). Fixtures are derived copies reshaped for this scenario.

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the four
static `.txt` fixtures in this folder.*
