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

One driver row of category balance `0.00` is fed to `CBACT04C` against an account
whose disclosure group resolves to a **present, non-zero** rate of `15.00`. The
program computes monthly interest and the test asserts the result is `0.00` — a
zero *balance* multiplied by a live rate. This is the deterministic "money in →
money out" (golden-master) check for the degenerate but valid zero-principal case.

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

| File | Copybook | RECLN | Line ending | Role |
|---|---|---|---|---|
| `tcatbal.txt` | `CVTRA01Y` | 50 | **CRLF** | category balance `= 0.00` (the defining input) |
| `acctdata.txt` | `CVACT01Y` | 300 | LF | account `00000000001`, `GROUP-ID A000000000` (DIRECT hit), `CURR-BAL 194.00` |
| `discgrp.txt` | `CVTRA02Y` | 50 | LF | rate `15.00` for `A000000000 / 01 / 0001` |
| `cardxref.txt` | `CVACT03Y` | 50 | LF | card ↔ customer ↔ account linkage (14-space `FILLER` to full width) |

> **WHY `tcatbal.txt` is CRLF while the others are LF (documented exception).** The
> master contract (`../../README.md` §3.2) defaults every fixture to LF, but names
> `tcatbal` among the seeds that ship as **CRLF** in the repository. This fixture
> preserves that CRLF for **byte-fidelity with its seed** `app/data/ASCII/tcatbal.txt`
> (Assumption: keeping the derived fixture byte-identical to the seed avoids any
> hidden re-encoding). It is harmless at load: `tests/helpers/vsam_loader.py`
> strips the trailing carriage return before fixed-width slicing, so the 50-byte
> record is parsed identically to an LF fixture. The other three fixtures are LF,
> matching their LF seeds.

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
drive the `(0.00 × 15.00) / 1200 = 0.00` result described in §2.

---

## 5. ASSIGN-name mapping (GnuCOBOL runtime bindings)

GnuCOBOL binds each COBOL `SELECT … ASSIGN TO <NAME>` to a same-named runtime
environment variable (wired by `scripts/test_env.sh`). All four fixtures back
`ORGANIZATION IS INDEXED` files, so `tests/helpers/load_indexed.sh` performs the
flat → indexed load (the suite's `IDCAMS REPRO` analog) **before** `CBACT04C` runs.

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

Authoritative for the mirrored `tests/golden/interest/zero_balance/` tree
(authored separately by the golden agent) and for the integration asserts:

- **One interest transaction written** with **amount `0.00`**, `TYPE-CD = 01`,
  `CAT-CD = 05`, `SOURCE = System`, `DESC = Int. for a/c 00000000001`, and
  `CARD-NUM = 9680294154603697`.
- **`ACCT-CURR-BAL` unchanged at `194.00`** — `1050-UPDATE-ACCOUNT` adds the total
  interest (`0.00`) to the current balance, leaving it untouched.
- **Cycle credit and cycle debit zeroed** by `1050-UPDATE-ACCOUNT`.
- **No fee transaction** (the `1400-COMPUTE-FEES` stub is empty).

> **WHY timestamps are normalized downstream, not fixed in the fixtures
> (Trade-off).** The emitted interest transaction carries `ORIG-TS` / `PROC-TS`
> (from the runtime DB2-format timestamp) and a generated `TRAN-ID`
> (`STRING PARM-DATE + suffix`) — both **non-deterministic at run time**. Rather
> than bake a frozen timestamp into these *input* fixtures (which would couple the
> data to a clock and make it lie about what the program produces), the comparison
> normalizes those volatile fields in `tests/helpers/golden_compare.py` at
> assert time. This keeps the **input fixtures byte-stable** and ensures no
> timestamp ever leaks into the fixture tree.

---

## 7. Consumers & sources

- **Consumed by:** `tests/integration/test_cbact04c_interest.py` and the
  end-to-end interest cycle.
- **Derived from (never edited):** the shipped seeds
  `app/data/ASCII/{tcatbal,acctdata,discgrp,cardxref}.txt`.
- **Record layouts:** `app/cpy/{CVTRA01Y,CVACT01Y,CVTRA02Y,CVACT03Y}.cpy`.
- **Business rule:** `app/cbl/CBACT04C.cbl` (`1200-GET-INTEREST-RATE`,
  `1300-COMPUTE-INTEREST`, `1400-COMPUTE-FEES`, `1050-UPDATE-ACCOUNT`).

**Minimal-change principle (mandatory).** The seed datasets under
`app/data/ASCII/` and all production sources under `app/` are **REFERENCE ONLY and
are never modified** (AAP §0.8.2). Fixtures are derived copies reshaped for this
scenario.

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the four
static `.txt` fixtures in this folder: static data files cannot carry docstrings,
so their WHY lives here — chiefly the non-zero-rate rationale (§2), the CRLF
exception for `tcatbal.txt` (§3), and the downstream timestamp/`TRAN-ID`
normalization (§6).*
