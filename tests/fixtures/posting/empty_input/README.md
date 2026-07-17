# Posting fixture — empty input (no daily transactions)

These fixtures drive `app/cbl/CBTRN02C.cbl` with a **genuinely empty (0-record)**
daily-transaction input. The program opens a valid but empty `DALYTRAN`, reads
straight to end-of-file, and completes with **no posts and no rejects**
(`RETURN-CODE = 0`). The cross-reference, account, and category-balance inputs are
present and valid but are never looked up, because there is no transaction to drive
a lookup.

> **Why this README exists.** Static fixed-width `.txt` fixtures cannot carry
> docstrings, so — per the Explainability rule (AAP §0.10.1) — this is the mandated
> *why*. The byte-encoding contract and the `empty_input` semantics (§7) live in the
> master **[`tests/fixtures/README.md`](../../README.md)**.

## 1. Intent & the empty-input form

`dailytran.txt` is a **0-byte file (zero records)** — the unambiguous form the
loader/codec accept as empty (master §3.1, §7). This exercises the program's
"nothing to process" path: `CBTRN02C` opens `DALYTRAN`, its read hits EOF
immediately, and it exits cleanly without writing to `TRANSACT` or `DALYREJS`.

> **WHY a 0-byte file rather than a blank line (Assumption / Trade-off).** The
> master contract rejects blank lines as zero-length "records" that fail fixed-width
> parsing (§3.3), so the correct representation of "no records" is an **empty file**,
> not a file containing an empty line. A 0-byte `DALYTRAN` also matches the rule that
> the *only* input treated as empty is a **genuinely zero-byte dataset** (§3.1) — so
> the program still opens a valid (if empty) dataset rather than failing on a missing
> one.

## 2. Fixture files in this folder

| File | Copybook | `RECLN` | Line ending | Records | Role / key values |
|---|---|---:|---|---:|---|
| `dailytran.txt` | `CVTRA06Y` (DALYTRAN) | 350 | — (0-byte) | **0** | **Genuinely empty** transaction input — the defining condition of this scenario. |
| `cardxref.txt` | `CVACT03Y` (XREF) | 50 | LF | 1 | card `4859452612877065` → cust `000000007` → acct `00000000007` (present, unused). |
| `acctdata.txt` | `CVACT01Y` (ACCOUNT) | 300 | LF | 1 | account `00000000007` (present, unused). |
| `tcatbal.txt` | `CVTRA01Y` (TCATBAL) | 50 | LF | 1 | category row `00000000007 / 01 / 0001`, `TRAN-CAT-BAL 0.00` (present, unused). |

> **WHY the master/xref/tcatbal files are still present.** An empty *transaction*
> input is the scenario; the surrounding datasets are kept valid and non-empty so
> the program opens all its files normally and the "empty transaction stream" path
> is the *only* thing under test (a missing master file would be a different,
> error-path scenario).

## 3. Expected outcome (authoritative intent; golden is [planned])

- **`RETURN-CODE = 0`** — clean completion.
- **`DALYREJS` empty** and **`TRANSACT` unchanged** — no transaction was read, so
  none was posted or rejected.
- **`TCATBAL` and `ACCOUNT` unchanged** — no updates occur.

## 4. Data governance / synthetic provenance (MA-24)

Card `4859452612877065`, account `00000000007`, and customer `000000007` are
**synthetic, seed-derived** values from the published AWS CardDemo sample datasets,
representing **no real person or account**. See master §10.

## 5. Sources & scope

- **Derived from (never edited):** `app/data/ASCII/{cardxref,acctdata,tcatbal}.txt`
  (`dailytran` is intentionally empty).
- **Record layouts:** `app/cpy/{CVTRA06Y,CVACT03Y,CVACT01Y,CVTRA01Y}.cpy`.
- **Business rule:** `app/cbl/CBTRN02C.cbl` (main read loop / EOF handling).
- **Consumed by (when present):** `tests/integration/test_cbtrn02c_posting.py` —
  **[planned]** (master §1).

**Minimal-change principle (mandatory).** Seeds under `app/data/ASCII/` and all
production sources under `app/` are **REFERENCE ONLY and are never modified**
(AAP §0.8.2).

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the static
fixtures in this folder.*
