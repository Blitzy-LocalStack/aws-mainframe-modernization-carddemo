# Interest — Zero-Balance Scenario (CBACT04C)

This scenario isolates **"zero balance → zero interest"**: the transaction-category
balance is **0.00** but the disclosure-group rate is **non-zero (15.00)**, so the
compiled `app/cbl/CBACT04C.cbl` interest calculator still *enters* its computation
path and is asserted to produce exactly **0.00** interest.

> **Read [`tests/fixtures/README.md`](../../README.md) first.** It is the
> authoritative byte-level encoding contract (fixed-width records, zoned-decimal
> sign overpunch, implied decimals, and line-ending rules). This scenario complies
> with that contract exactly; the overpunch table and full record layouts are
> **not** restated here — consult the master contract for them.

---

## 1. Intent

A `0.00` category balance is fed to `CBACT04C` against an account whose disclosure
group resolves to a **present, non-zero** rate of `15.00`. The program computes
monthly interest and the test asserts the result is `0.00` — a zero *balance*
multiplied by a live rate. This is the deterministic "money in → money out"
(golden-master) check for the degenerate but valid zero-principal case.

> **Two accounts (MA-22).** Like every `interest/*` scenario, this fixture ships
> **two** accounts — `00000000001` (non-final, `194.00`) and `00000000002` (final,
> `158.00`), each with a `0.00` category balance — so that the **non-final**
> account's `1050-UPDATE-ACCOUNT` `REWRITE` actually fires (a single-account fixture
> could never persist an account update; the final account is never flushed). See
> [`../happy_path/README.md`](../happy_path/README.md) §2 for the full final-flush
> rationale. **This zero-balance case is arithmetically the simplest:** because the
> accrued interest is `0.00`, it is free of the non-zero integrated-arithmetic
> divergence documented for `happy_path` — `0.00 × 15.00 / 1200` is unambiguously
> `0.00`.

- **Balance:** `0.00` (the defining input)
- **Rate:** `15.00` (group `A000000000`, type `01`, category `0001`)
- **Rate path:** **DIRECT** — the DISCGRP key resolves with VSAM file status `00`
  (this is **not** the status-`23` → `DEFAULT`-group fallback case).
- **Expected interest:** `(0.00 × 15.00) / 1200 = 0.00`

---

## 2. Scenario intent & the KEY WHY

The business rule under test is the interest gate and formula in `CBACT04C`
(paragraphs `1200-GET-INTEREST-RATE` → `1300-COMPUTE-INTEREST`):

```cobol
PERFORM 1200-GET-INTEREST-RATE
IF DIS-INT-RATE NOT = 0
    PERFORM 1300-COMPUTE-INTEREST
    PERFORM 1400-COMPUTE-FEES
END-IF
```

```cobol
COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
```

Worked for this fixture: `(0.00 × 15.00) / 1200 = 0.00`.

> **WHY a *non-zero* rate is used here (Alternatives Considered / Trade-off — the
> review-gate rationale).** The rate is deliberately `15.00`, **not** `0.00`,
> because the guard `IF DIS-INT-RATE NOT = 0` must evaluate **TRUE** for
> `1300-COMPUTE-INTEREST` to run at all. With a live rate the computation path
> **executes** and we can prove it yields `0.00` from a zero balance. A **zero
> rate** would make that guard **FALSE** and skip the computation entirely — that
> is a *different* scenario ("zero rate → no computation", branch not taken), and
> it would silently destroy the branch coverage this fixture provides. **Do not
> "simplify" the rate to `0.00`.** The two cases are intentionally distinct:
>
> | Case | `DIS-INT-RATE` | Guard `NOT = 0` | `1300-COMPUTE-INTEREST` | Asserts |
> |---|---|---|---|---|
> | **this fixture** — zero balance | `15.00` | TRUE | **runs** | interest `= 0.00` |
> | zero rate (elsewhere) | `0.00` | FALSE | **skipped** | no interest tx |

**Emitted-transaction consequence.** Because the guard is TRUE, `1300-B-WRITE-TX`
runs and **one interest transaction IS written** — but with **amount `0.00`**.
`1400-COMPUTE-FEES` is an empty stub (`* To be implemented`), so **no fee
transaction** is produced.

---

## 3. Files in this folder

| File | Copybook | RECLN | Line ending | Records | Role |
|---|---|---|---|---:|---|
| `tcatbal.txt` | `CVTRA01Y` | 50 | LF | 2 | category balance `= 0.00` for **each** account (the defining input) |
| `acctdata.txt` | `CVACT01Y` | 300 | LF | 2 | accounts `00000000001` (`CURR-BAL 194.00`, **non-final**) and `00000000002` (`158.00`, **final**), both `GROUP-ID A000000000` (DIRECT hit) |
| `discgrp.txt` | `CVTRA02Y` | 50 | LF | 1 | rate `15.00` for `A000000000 / 01 / 0001` |
| `cardxref.txt` | `CVACT03Y` | 50 | LF | 2 | card ↔ customer ↔ account linkage for both accounts (each +14-space `FILLER` to full width) |

> **WHY `tcatbal.txt` is LF here (MI-04 normalization, documented exception).** The
> master contract (`../../README.md` §3.2) names `tcatbal` among the seeds that ship
> as **CRLF**, and requires any CRLF choice to be documented per scenario. This
> scenario instead **normalizes `tcatbal.txt` to LF** so all four fixtures share one
> line ending. **Why LF (Trade-off):** the loader treats one physical line as one
> fixed-length record, and a stray `\r` risks being absorbed into the trailing
> `FILLER`, pushing the record one byte past `RECLN`; LF removes that hazard. The
> record *content* bytes are unchanged, so the one-byte divergence from the seed's
> line ending is immaterial. Verified: all four fixtures here are **LF only** (no
> `\r`).

---

## 4. Field values that matter

Operative fields only, with **1-based column ranges** and the **exact encoded
bytes** (verified against the copybooks in `app/cpy/` and the sibling `.txt`
fixtures). Signed money fields use zoned-decimal sign overpunch, where `{` is the
last digit `0` carrying a **positive** sign (so a trailing `{` reads as `…0`,
positive). See `../../README.md` §3.4 for the full overpunch table and §3.5 for the
implied decimal (`V` occupies no byte).

| File | Field | PIC | Cols | Encoded | Decodes to |
|---|---|---|---|---|---|
| `tcatbal.txt` | `TRAN-CAT-BAL` | `S9(09)V99` | 18–28 | `0000000000{` | **+0.00** |
| `acctdata.txt` | `ACCT-CURR-BAL` | `S9(10)V99` | 13–24 | `00000001940{` | **+194.00** |
| `acctdata.txt` | `ACCT-GROUP-ID` | `X(10)` | 113–122 | `A000000000` | group id (DIRECT hit) |
| `discgrp.txt` | `DIS-INT-RATE` | `S9(04)V99` | 17–22 | `00150{` | **+15.00** (non-zero) |
| `cardxref.txt` | `XREF-CARD-NUM` | `X(16)` | 1–16 | `9680294154603697` | card number |
| `cardxref.txt` | `XREF-CUST-ID` | `9(09)` | 17–25 | `000000001` | customer |
| `cardxref.txt` | `XREF-ACCT-ID` | `9(11)` | 26–36 | `00000000001` | account |

The two load-bearing values are `TRAN-CAT-BAL = 0000000000{` (`+0.00`, the zero
balance) and `DIS-INT-RATE = 00150{` (`+15.00`, the non-zero rate); together they
drive the `(0.00 × 15.00) / 1200 = 0.00` result described in §2. The **second**
account `00000000002` (`ACCT-CURR-BAL` `00000001580{` = `+158.00`, same group
`A000000000`, its own `0.00` category-balance row) is present so account 1 is
non-final; its identity/PAN bytes are copied byte-for-byte from the published
synthetic seed (§7, MA-24).

---

## 5. ASSIGN-name mapping (GnuCOBOL runtime bindings)

GnuCOBOL binds each COBOL `SELECT … ASSIGN TO <NAME>` to a same-named runtime
environment variable (wired by `scripts/test_env.sh`). All four fixtures back
`ORGANIZATION IS INDEXED` files, so the harness performs the flat → indexed load
(the suite's `IDCAMS REPRO` analog) — via `tests/helpers/vsam_loader.py` today
(`load_indexed.sh` once it wraps it — master §1) — **before** `CBACT04C` runs.

| Fixture | ASSIGN name | Organization | RECORD KEY (length) |
|---|---|---|---|
| `tcatbal.txt` | `TCATBALF` | INDEXED | acct 11 + type 2 + cat 4 = **17** |
| `acctdata.txt` | `ACCTFILE` | INDEXED | `ACCT-ID` = **11** |
| `discgrp.txt` | `DISCGRP` | INDEXED | group 10 + type 2 + cat 4 = **16** |
| `cardxref.txt` | `XREFFILE` | INDEXED | card number = **16** (primary) **+ ALTERNATE key on account id** |

> **Note (XREF alternate key).** `CBACT04C` reads the cross-reference **by account
> id**, not by card number: `1110-GET-XREF-DATA` issues `READ XREF-FILE … KEY IS
> FD-XREF-ACCT-ID`. The fixture must therefore load cleanly under both the primary
> (card) key and the alternate (account-id) key.

---

## 6. Expected end-state (golden coordination)

For the **[planned]** mirror `tests/golden/interest/zero_balance/` (not present
today — master §1), captured from actual output, and the integration asserts. With
two accounts both carrying a `0.00` category balance:

- **Two interest transactions written** (one per account), **each amount `0.00`**,
  `TYPE-CD = 01`, `SOURCE = System`, `DESC = Int. for a/c …`, and the account's
  `CARD-NUM` (`9680294154603697` for account 1, `0923877193247330` for account 2).
  The interest transaction is emitted per category (`1300-B-WRITE-TX`) regardless of
  the account flush.
- **Non-final account `00000000001`: record IS rewritten.** `1050-UPDATE-ACCOUNT`
  fires at the key break to account 2, adding the total interest (`0.00`, so the
  balance stays `194.00`) and **zeroing** `ACCT-CURR-CYC-CREDIT` / `-DEBIT` — the
  record physically changes even though the balance value is unchanged.
- **Final account `00000000002`: record is NOT rewritten** (final-flush defect,
  §1) — it remains `158.00` with its cycle fields unchanged.
- **No fee transaction** (the `1400-COMPUTE-FEES` stub is empty).

> **Why the zero-balance case is safe to state exactly (unlike `happy_path`).** The
> accrued interest is `0.00` (`0.00 × 15.00 / 1200`), so `WS-TOTAL-INT` is `0.00`
> and `ADD WS-TOTAL-INT TO ACCT-CURR-BAL` is a no-op on the balance value. There is
> no non-zero arithmetic to diverge, so account 1's persisted balance (`194.00`,
> cycles zeroed) and account 2's (`158.00`, untouched) follow directly from the
> documented rule — this scenario does **not** inherit the `happy_path` out-of-scope
> divergence (see `../happy_path/README.md` §7).

> **`TRAN-ID` is DETERMINISTIC; only timestamps vary (MI-03 — Trade-off).** The
> emitted interest transaction's `TRAN-ID` is `PARM-DATE` + an ascending suffix
> (e.g. `2022071800000001`, `2022071800000002` for a fixed `PARM-DATE`) and is
> **asserted exactly** — it is *not* non-deterministic. Only the record's `ORIG-TS`
> / `PROC-TS` (from the runtime `DB2-FORMAT-TS` clock) vary run to run and are the
> sole fields normalized by `tests/helpers/golden_compare.py` at assert time (master
> §6.3). Baking a frozen timestamp into these *input* fixtures would couple the data
> to a clock and misrepresent what the program produces, so volatility is handled on
> the output side only — the input fixtures stay byte-stable.

---

## 7. Consumers & sources

- **Consumed by:** `tests/integration/test_cbact04c_interest.py` and the
  end-to-end interest cycle — both **[planned]**, not present on the branch today
  (master §1).
- **Derived from (never edited):** the shipped seeds
  `app/data/ASCII/{tcatbal,acctdata,discgrp,cardxref}.txt`.
- **Record layouts:** `app/cpy/{CVTRA01Y,CVACT01Y,CVTRA02Y,CVACT03Y}.cpy`.
- **Business rule:** `app/cbl/CBACT04C.cbl` (`1200-GET-INTEREST-RATE`,
  `1300-COMPUTE-INTEREST`, `1400-COMPUTE-FEES`, `1050-UPDATE-ACCOUNT`).
- **Synthetic-data provenance (MA-24).** All card numbers, account ids, and
  customer ids in `cardxref.txt` (and account 2, added byte-for-byte from the
  published seed) are **synthetic, seed-derived** values representing **no real
  person or account**; only non-identity business-rule fields (category balances)
  were reshaped. See master §10.

**Minimal-change principle (mandatory).** The seed datasets under
`app/data/ASCII/` and all production sources under `app/` are **REFERENCE ONLY and
are never modified** (AAP §0.8.2). Fixtures are derived copies reshaped for this
scenario.

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the four
static `.txt` fixtures in this folder: static data files cannot carry docstrings,
so their WHY lives here — chiefly the non-zero-rate rationale (§2), the two-account
/ final-flush design (§1, MA-22), the LF normalization of `tcatbal.txt` (§3, MI-04),
the deterministic-`TRAN-ID` correction (§6, MI-03), and the synthetic-data
provenance (§7, MA-24).*
