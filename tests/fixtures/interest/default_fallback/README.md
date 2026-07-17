# Interest fixtures — DEFAULT-group fallback (CBACT04C, VSAM status 23)

Deterministic, fixed-width **input** fixtures that drive `app/cbl/CBACT04C.cbl`
down the **DEFAULT-group interest fallback** path. The account under test carries
a **blank** `ACCT-GROUP-ID`, so the keyed disclosure-group read returns
**VSAM file status 23 (INVALID KEY)**. That drives the program through
`1200-GET-INTEREST-RATE` → `MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID` →
`1200-A-GET-DEFAULT-INT-RATE`, which succeeds against the `DEFAULT` rows and
yields the interest rate. These files are the **"data-in"** side of the
golden-master model; the expected **"data-out"** lives in the mirror tree
`tests/golden/interest/default_fallback/`.

> **Byte-encoding contract — read this FIRST:**
> [`../../README.md`](../../README.md) is the authoritative, byte-level contract
> for **every** fixture (fixed width with **no delimiters**, zoned-decimal **sign
> overpunch on the last byte** of signed fields, **LF** for the master files, and
> the documented **CRLF exception** for `tcatbal`). This scenario README does
> **not** restate that contract — it only records *what this scenario does* and
> *why*, per the Explainability mandate (AAP §0.10.1) for static `.txt` files that
> cannot carry docstrings. These fixtures comply with the parent contract exactly.

## 1. Worked expectation (the numbers)

- `TRAN-CAT-BAL = 1000.00` (the transaction-category balance being accrued).
- The account's **own** disclosure group is **not found**, so the **`DEFAULT`**
  group is used instead. The `DEFAULT` / type `01` / category `0001` row carries
  rate **`15.00`** (i.e. 15%).
- Interest is therefore `(TRAN-CAT-BAL × rate) / 1200 = (1000.00 × 15.00) / 1200`
  = **`12.50`**, asserted **exactly** in fixed point (no floating-point tolerance),
  matching `CBACT04C` `1300-COMPUTE-INTEREST` (see §3).

> **Trade-off — same number as `happy_path`, different rate *path*.** This
> scenario deliberately produces the **identical** `12.50` result as the sibling
> `interest/happy_path`, but reaches it via the **DEFAULT fallback** (mandatory
> status-23 branch, AAP §0.7.1) rather than `happy_path`'s **DIRECT** DISCGRP hit.
> Holding the arithmetic constant isolates the *branch under test*: any diff in
> the golden mirror is attributable to the fallback path, not to a different
> amount. `happy_path` sets `ACCT-GROUP-ID = A000000000` (a group present in its
> DISCGRP) for the direct hit; this scenario leaves the group blank for the
> fallback.

**Overpunch reading (self-check against the parent §3.4 table):**

- Balance `0000010000{` decodes to **`+1000.00`** — `S9(09)V99`, so the final
  byte `{` is digit `0` with a positive sign.
- Rate `00150{` decodes to **`+15.00`** — `S9(04)V99`, final byte `{` is digit `0`
  positive.

## 2. Mechanism — WHY the fallback triggers (core explanation)

The account's `ACCT-GROUP-ID` occupies **bytes 113–122** of the 300-byte
`ACCOUNT-RECORD` (`CVACT01Y`) and is **BLANK (10 spaces)**, exactly as shipped in
the raw seed `app/data/ASCII/acctdata.txt`. (The `A000000000` visible earlier in
the record is the adjacent `ACCT-ADDR-ZIP` at bytes 103–112 — *not* the group id.)

At run time `CBACT04C` (key build at lines 210–212) forms the DISCGRP key from
`ACCT-GROUP-ID + TRANCAT-TYPE-CD + TRANCAT-CD`, then reads and, on `INVALID KEY`,
falls back to the `DEFAULT` group:

```
key parts   = ACCT-GROUP-ID(10)  +  TRAN-TYPE(2)  +  TRAN-CAT(4)
first read  = "          010001"   (10 blanks + 010001)
              READ DISCGRP-FILE  ->  INVALID KEY  ->  DISCGRP-STATUS = '23'
fallback    = MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID   (1200-GET-INTEREST-RATE)
second read = "DEFAULT   010001"   (DEFAULT + 3 blanks + 010001)
              READ DISCGRP-FILE  ->  status '00'  ->  DIS-INT-RATE = 15.00
```

The scenario-local `discgrp.txt` has **no** row for the blank group, so the first
`READ DISCGRP-FILE` returns **status 23**; the program moves `'DEFAULT'` into the
group id and re-reads key `DEFAULT   010001`, which resolves to rate `15.00`.

> **Assumption + Alternatives Considered — WHY blank, not a bogus non-blank id.**
> **All** seed ACCOUNT rows ship with a blank `ACCT-GROUP-ID`, so a blank group is
> the faithful, byte-for-byte-from-seed trigger for the DEFAULT path. An
> *absent non-blank* group id (e.g. `ZZZZZZZZZZ` with no matching DISCGRP row)
> would be an equivalent alternative that also yields status 23 — but it would
> diverge from the seed and add an unexplained literal. Blank was chosen because
> it matches the seed exactly and needs no justification beyond "this is what real
> accounts look like." (Assumption: the blank-group byte layout matches the
> `app/cpy/CVACT01Y.cpy` copybook contract; verified against the seed.)

> **Fee-stub edge (`1400-COMPUTE-FEES`).** That paragraph is a documented stub
> whose body is the single comment `* To be implemented`, so it produces **no
> fee**. The only transaction emitted for this account is therefore the **interest
> transaction** — the golden mirror asserts the *absence* of any fee record and
> flags the gap; do not fabricate fee output.

## 3. Business rule exercised (from `app/cbl/CBACT04C.cbl`)

| Paragraph | Role in this scenario |
|---|---|
| `1200-GET-INTEREST-RATE` | Reads DISCGRP with the account's (blank) group key; on `INVALID KEY` → status **23** → moves `'DEFAULT'` and performs the fallback read. |
| `1200-A-GET-DEFAULT-INT-RATE` | Re-reads DISCGRP with key `DEFAULT   010001`; resolves `DIS-INT-RATE = 15.00`. |
| `1300-COMPUTE-INTEREST` | `COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200` → `12.50`; accumulates into `WS-TOTAL-INT` and writes the interest transaction. |
| `1050-UPDATE-ACCOUNT` | `ADD WS-TOTAL-INT TO ACCT-CURR-BAL` (194.00 → 206.50), then zeroes `ACCT-CURR-CYC-CREDIT` / `ACCT-CURR-CYC-DEBIT` and rewrites the account. |
| `1400-COMPUTE-FEES` | Stub (`* To be implemented`) → no fee transaction. |

## 4. Coordination with the shared mock `tests/mocks/mock_discgrp.txt`

The suite's **canonical** deliberately-missing-key disclosure-group mock is
`tests/mocks/mock_discgrp.txt`. It keeps the non-blank `A000000000` group but
**deletes exactly one combo row — `A000000000` / type `05` / cat `0001`** — so
that a *non-blank-group* account processing that specific type/category combo
gets status 23 and falls back to `DEFAULT`.

> **WHY this scenario does NOT consume the shared mock (test isolation).** This
> scenario intentionally uses a **self-contained, scenario-local `discgrp.txt`
> containing `DEFAULT` rows only**, paired with a **blank-group** account. Both
> artifacts exercise the **same** mandatory status-23 → `DEFAULT` branch, but via
> **different mechanisms**:
>
> - **here:** blank-group account + `DEFAULT`-only file (every keyed group read
>   misses → fallback);
> - **shared mock:** non-blank `A000000000` account + a file that omits one
>   `A000000000/05/0001` combo (only that combo misses → fallback).
>
> Keeping this scenario's fixtures local avoids coupling its determinism to a
> shared file that other tests may evolve, and keeps the fallback trigger obvious
> from the account row alone.

## 5. Fixture files in this folder

| File | Copybook | `RECLN` | Line ending | Records | Purpose |
|---|---|---:|---|---:|---|
| `acctdata.txt` | `CVACT01Y` | 300 | LF | 1 | Verbatim seed account `00000000001`; **blank `ACCT-GROUP-ID` (bytes 113–122)** is the fallback trigger; `ACCT-CURR-BAL = 194.00`. |
| `tcatbal.txt` | `CVTRA01Y` | 50 | **CRLF** | 1 | `TRAN-CAT-BAL = 1000.00`, type `01`, category `0001` — the balance interest is accrued on. |
| `discgrp.txt` | `CVTRA02Y` | 50 | LF | 17 | **`DEFAULT` rows only**; `DEFAULT   010001` rate = `15.00`. **No** blank-group, `A000000000`, or `ZEROAPR` rows (those seed groups are omitted so only the `DEFAULT` fallback can resolve). |
| `cardxref.txt` | `CVACT03Y` | 50 | LF | 1 | card `9680294154603697` → cust `000000001` → acct `00000000001` (+14-space `FILLER`); identical to `happy_path`. |

> **CRLF choice for `tcatbal.txt` (documented per parent §3.2/§9.1).** The seed
> `app/data/ASCII/tcatbal.txt` ships as **CRLF**, and this fixture **preserves
> that CRLF** to mirror the seed byte-for-byte. `tests/helpers/load_indexed.sh`
> strips the trailing `\r` so the record still loads as exactly **50 bytes**. The
> other three fixtures (`acctdata`, `discgrp`, `cardxref`) are **LF** — they mirror
> LF seeds, per the parent contract's default.

## 6. `ASSIGN`-name mapping

GnuCOBOL binds each COBOL `SELECT … ASSIGN TO <NAME>` to a same-named runtime
environment variable (wired by `scripts/test_env.sh`). All four inputs are
`ORGANIZATION IS INDEXED`, so the harness performs a **flat → indexed load** via
`tests/helpers/load_indexed.sh` (an `IDCAMS REPRO` analog) **before** invoking
`CBACT04C`.

| Fixture | `ASSIGN` name | Key length | Organization |
|---|---|---|---|
| `tcatbal.txt` | `TCATBALF` | 17 (acct 11 + type 2 + cat 4) | INDEXED |
| `acctdata.txt` | `ACCTFILE` | 11 (acct-id) | INDEXED |
| `discgrp.txt` | `DISCGRP` | 16 (group 10 + type 2 + cat 4) | INDEXED |
| `cardxref.txt` | `XREFFILE` | 16 (card num), + alternate index on acct-id | INDEXED |

## 7. Determinism

These are **input** fixtures and contain **no runtime timestamps** (the interest
domain consumes no `DALYTRAN` `PROC-TS`), so the four files are inherently
**byte-stable** across runs. Any run-varying values live only on the **output**
side — the emitted interest transaction's `TRAN-ID` and processing timestamps —
and are normalized by `tests/helpers/golden_compare.py` when the produced
datasets are compared against the golden mirror. Nothing here must be regenerated
between runs.

## 8. Expected outcome (authoritative for the golden mirror & asserts)

The golden agent creates the byte-identically-named mirror
`tests/golden/interest/default_fallback/`. Expected end state:

- One **interest transaction** with amount **`12.50`**, computed via the
  **`DEFAULT`** group (reached through the status-23 fallback).
- `ACCT-CURR-BAL` updated **`194.00` → `206.50`** (`+12.50`).
- `ACCT-CURR-CYC-CREDIT` and `ACCT-CURR-CYC-DEBIT` **zeroed** by
  `1050-UPDATE-ACCOUNT`.
- **No fee transaction** (`1400-COMPUTE-FEES` stub).

Numerically identical to `interest/happy_path`, but this scenario exercises the
**status-23 → `DEFAULT` fallback** branch rather than the direct DISCGRP hit.

## 9. Sources & scope

- Derived from the seeds `app/data/ASCII/{acctdata,discgrp}.txt` (and the
  `tcatbal` / `cardxref` seed rows); record layouts
  `app/cpy/{CVACT01Y,CVTRA01Y,CVTRA02Y,CVACT03Y}.cpy`; business-rule source
  `app/cbl/CBACT04C.cbl`. Coordinates with `tests/mocks/mock_discgrp.txt` (§4).
- **Seeds and production sources are REFERENCE-only and are never edited**
  (AAP §0.8.2). Fixtures are derived copies/subsets reshaped for this scenario.
- Consumed by `tests/integration/test_cbact04c_interest.py` (and the end-to-end
  interest cycle) through the shared helpers in `tests/helpers/*`.
