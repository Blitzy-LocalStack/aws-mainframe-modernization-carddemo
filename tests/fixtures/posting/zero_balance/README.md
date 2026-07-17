# Posting fixture — zero-balance account, category-row CREATE branch

These fixtures drive `app/cbl/CBTRN02C.cbl` so that a valid transaction posts to an
account that **starts at a zero balance** and has **no existing category-balance
row**. The post therefore exercises the **create** arm of the category-balance
update (`2700-A-CREATE`) — the complement to `happy_path`, which exercises the
**update** arm (`2700-B-UPDATE`).

> **Why this README exists.** Static fixed-width `.txt` fixtures cannot carry
> docstrings, so — per the Explainability rule (AAP §0.10.1) — this is the mandated
> *why*. The byte-encoding contract lives in the master
> **[`tests/fixtures/README.md`](../../README.md)**.

## 1. What this scenario pins (two things)

1. **Zero starting balance.** `ACCT-CURR-BAL = 0.00` — a valid, degenerate account
   balance that must post normally.
2. **Category-row CREATE branch.** `tcatbal.txt` is **genuinely empty (0 bytes)**,
   so there is **no** `TCATBAL` row for `00000000007 / 01 / 0001`. When the
   transaction posts, `2700-UPDATE-TCATBAL` takes its **create** branch
   (`2700-A-CREATE`) and writes a **new** category-balance row rather than adding to
   an existing one.

> **WHY an empty `tcatbal` (Trade-off vs. `happy_path`).** `happy_path` seeds an
> existing category row (`100.00`) to exercise `2700-B-UPDATE`; this scenario omits
> the row to exercise `2700-A-CREATE`. Together the two scenarios cover **both**
> arms of the create-vs-update decision (master §6.1). A 0-byte `tcatbal` is the
> master-contract form for "no records" (§3.1, §7), so the file is present and valid
> but empty.

## 2. Business rule exercised — `CBTRN02C` `1500-VALIDATE-TRAN` (all checks pass)

- **XREF lookup:** card `4859452612877065` → acct `00000000007` (present).
- **ACCOUNT lookup:** account `00000000007` present.
- **Over-limit (`>=`):** `WS-TEMP-BAL = 0.00 - 0.00 + 504.77 = 504.77`;
  `2065.00 >= 504.77` **TRUE** → within limit.
- **Expiration (`>=`):** `2024-12-13 >= 2022-06-10` **TRUE** → not expired.

All four checks pass, so the transaction **POSTS**.

## 3. Fixture files in this folder

| File | Copybook | `RECLN` | Line ending | Records | Role / key values |
|---|---|---:|---|---:|---|
| `dailytran.txt` | `CVTRA06Y` (DALYTRAN) | 350 | LF | 1 | Transaction `AMT +504.77`, `CARD 4859452612877065`, `ORIG-TS` date `2022-06-10`, `PROC-TS` blank. |
| `cardxref.txt` | `CVACT03Y` (XREF) | 50 | LF | 1 | card `4859452612877065` → cust `000000007` → acct `00000000007`. |
| `acctdata.txt` | `CVACT01Y` (ACCOUNT) | 300 | LF | 1 | account `00000000007`: **`CURR-BAL 0.00`** (the zero balance), `CREDIT-LIMIT 2065.00`, `EXPIRAION-DATE 2024-12-13`, cycle credit/debit `0.00`. |
| `tcatbal.txt` | `CVTRA01Y` (TCATBAL) | 50 | — (0-byte) | **0** | **Empty** — no existing category row, so the post takes the CREATE branch. |

## 4. Expected outcome (authoritative intent; golden is [planned])

- **`RETURN-CODE = 0`** — POST, no reject; `DALYREJS` empty.
- **`TCATBAL` CREATE (`2700-A-CREATE`):** a **new** row `00000000007 / 01 / 0001`
  is written with `TRAN-CAT-BAL = 504.77` (created from zero + the posted amount).
- **ACCOUNT `00000000007` updated** (from its zero starting balance) and the posted
  transaction **written to `TRANSACT`**.

## 5. Data governance / synthetic provenance (MA-24)

Card `4859452612877065`, account `00000000007`, and customer `000000007` are
**synthetic, seed-derived** values from the published AWS CardDemo sample datasets,
representing **no real person or account**; only the starting balance and the
presence/absence of the category row are tuned. See master §10.

## 6. Sources & scope

- **Derived from (never edited):** `app/data/ASCII/{dailytran,cardxref,acctdata}.txt`
  (`tcatbal` is intentionally empty).
- **Record layouts:** `app/cpy/{CVTRA06Y,CVACT03Y,CVACT01Y,CVTRA01Y}.cpy`.
- **Business rule:** `app/cbl/CBTRN02C.cbl` (`1500-VALIDATE-TRAN`,
  `2700-UPDATE-TCATBAL` create branch `2700-A-CREATE`).
- **Consumed by (when present):** `tests/integration/test_cbtrn02c_posting.py` —
  **[planned]** (master §1).

**Minimal-change principle (mandatory).** Seeds under `app/data/ASCII/` and all
production sources under `app/` are **REFERENCE ONLY and are never modified**
(AAP §0.8.2).

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the static
fixtures in this folder.*
