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
> overpunch on the last byte** of signed fields, and **LF** line endings — this
> scenario normalizes its `tcatbal.txt` to **LF** (§5, MI-04)). This scenario README does
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

## 1a. Two accounts and the final-flush defect (MA-22)

This scenario ships **two** accounts — `00000000001` (non-final) and
`00000000002` (final) — not one. `CBACT04C` writes an account back
(`1050-UPDATE-ACCOUNT`) **only when the sequentially-keyed account id changes**, so
a single-account fixture can never demonstrate a persisted account update (the
trailing `ELSE PERFORM 1050-UPDATE-ACCOUNT` after the read loop is dead code under
`PERFORM UNTIL END-OF-FILE = 'Y'`). The second account makes `00000000001` a
**non-final** account whose `REWRITE` genuinely fires, while `00000000002` documents
the production **final-flush defect** (its record is never rewritten). Both accounts
carry a **blank** `ACCT-GROUP-ID`, so **both** take the status-23 → `DEFAULT`
fallback that is the point of this scenario.

> **See [`../happy_path/README.md`](../happy_path/README.md) §2 and §7 for the full
> MA-22 treatment**, including the empirically-verified, **out-of-scope** divergence
> between `CBACT04C`'s *integrated* multi-account interest arithmetic and the
> isolated `(1000 × 15)/1200 = 12.50` formula. As there, **do not assert a persisted
> `194.00 → 206.50` balance** — that is not what the compiled program does, and
> `CBACT04C` is immutable REFERENCE source (AAP §0.8.2).

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
| `1050-UPDATE-ACCOUNT` | Runs at the account-id control break: `ADD WS-TOTAL-INT TO ACCT-CURR-BAL`, zeroes `ACCT-CURR-CYC-CREDIT` / `ACCT-CURR-CYC-DEBIT`, and `REWRITE`s the account. It fires for the **non-final** account `00000000001` (whose record therefore changes) but **not** for the final account `00000000002` (final-flush defect, §1a). The exact persisted balance is **not** asserted here — see `../happy_path/README.md` §7 for the out-of-scope integrated-arithmetic divergence. |
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
| `acctdata.txt` | `CVACT01Y` | 300 | LF | 2 | Accounts `00000000001` (`ACCT-CURR-BAL = 194.00`, non-final) and `00000000002` (`158.00`, final). **Both** carry a **blank `ACCT-GROUP-ID` (bytes 113–122)** — the fallback trigger for each. |
| `tcatbal.txt` | `CVTRA01Y` | 50 | LF | 2 | One row per account: `TRAN-CAT-BAL = 1000.00`, type `01`, category `0001` — the balance interest is accrued on. |
| `discgrp.txt` | `CVTRA02Y` | 50 | LF | 17 | **`DEFAULT` rows only**; `DEFAULT   010001` rate = `15.00`. **No** blank-group, `A000000000`, or `ZEROAPR` rows (those seed groups are omitted so only the `DEFAULT` fallback can resolve). |
| `cardxref.txt` | `CVACT03Y` | 50 | LF | 2 | card `9680294154603697` → cust `000000001` → acct `00000000001`; card `0923877193247330` → cust `000000002` → acct `00000000002` (each +14-space `FILLER`). |

> **LF normalization for `tcatbal.txt` (MI-04, documented per parent §3.2/§9.1).**
> The seed `app/data/ASCII/tcatbal.txt` ships as **CRLF**, but this fixture
> **normalizes `tcatbal.txt` to LF** so all four files in this scenario share one
> line ending. **Why LF (Trade-off):** the loader treats one physical line as one
> fixed-length record, and a stray `\r` risks being absorbed into the trailing
> `FILLER` and pushing the record one byte past `RECLN`; LF removes that hazard.
> The record *content* bytes are unchanged, so the one-byte line-ending divergence
> from the seed is immaterial. Verified: all four fixtures here are **LF only** (no
> `\r`), each `tcatbal`/`discgrp`/`cardxref` row 50 bytes and each `acctdata` row
> 300 bytes.

## 6. `ASSIGN`-name mapping

GnuCOBOL binds each COBOL `SELECT … ASSIGN TO <NAME>` to a same-named runtime
environment variable (wired by `scripts/test_env.sh`). All four inputs are
`ORGANIZATION IS INDEXED`, so the harness performs a **flat → indexed load** (via
`tests/helpers/vsam_loader.py` today; `load_indexed.sh` once it wraps it — master
§1) — an `IDCAMS REPRO` analog — **before** invoking `CBACT04C`.

| Fixture | `ASSIGN` name | Key length | Organization |
|---|---|---|---|
| `tcatbal.txt` | `TCATBALF` | 17 (acct 11 + type 2 + cat 4) | INDEXED |
| `acctdata.txt` | `ACCTFILE` | 11 (acct-id) | INDEXED |
| `discgrp.txt` | `DISCGRP` | 16 (group 10 + type 2 + cat 4) | INDEXED |
| `cardxref.txt` | `XREFFILE` | 16 (card num), + alternate index on acct-id | INDEXED |

## 7. Determinism

These are **input** fixtures and contain **no runtime timestamps** (the interest
domain consumes no `DALYTRAN` `PROC-TS`), so the four files are inherently
**byte-stable** across runs. On the **output** side, the emitted interest
transaction's fields split into deterministic and run-varying (MI-03):

- **`TRAN-ID` is DETERMINISTIC** — built as `PARM-DATE` + an ascending
  `WS-TRANID-SUFFIX` (e.g. for `PARM-DATE = 2022071800`: `2022071800000001`,
  `2022071800000002`, …). With a fixed injected `PARM-DATE` it is reproducible and
  is **asserted exactly**; it is **not** normalized away. (An earlier revision
  wrongly grouped `TRAN-ID` with the volatile timestamps — corrected here.)
- **`ORIG-TS` / `PROC-TS` on the written record are runtime** (from the
  `DB2-FORMAT-TS` clock) and are the only fields normalized by
  `tests/helpers/golden_compare.py` at compare time (master §6.3).

Nothing in the input fixtures must be regenerated between runs.

## 8. Expected outcome (authoritative for the golden mirror & asserts)

The **[planned]** golden mirror `tests/golden/interest/default_fallback/` (not
present today — master §1) must be captured from **actual** program output. The
documented business-rule expectation and the verified structural behavior:

- **Rate path:** the interest rate is resolved via the **`DEFAULT`** group, reached
  through the **status-23 fallback** — the branch this scenario exists to cover.
- **Per-category interest (documented rule):** each account's `1000.00` category
  balance accrues `(1000.00 × 15.00) / 1200 = 12.50`; assert this **exactly** at
  the formula level.
- **Non-final account `00000000001` IS flushed** (`1050-UPDATE-ACCOUNT`: interest
  added, cycle credit/debit zeroed, `REWRITE`); **final account `00000000002` is
  NOT flushed** (final-flush defect, §1a).
- **`TRAN-ID`** is the deterministic `PARM-DATE`+suffix id (§7); **no fee
  transaction** (`1400-COMPUTE-FEES` stub).

> **⚠ Do not assert a persisted `194.00 → 206.50` balance.** As documented in
> `../happy_path/README.md` §7, an end-to-end run of the compiled `CBACT04C` over
> the two-account fixture confirmed the *structural* behavior above but showed its
> **integrated multi-account interest arithmetic diverges** from the isolated
> `12.50`/`206.50` figures. That divergence is internal to the **immutable**
> production program (AAP §0.8.2) and is a documented out-of-scope limitation, not a
> value to fabricate into a golden.

This scenario reaches the interest computation via the **status-23 → `DEFAULT`
fallback** branch, whereas `interest/happy_path` uses a **direct** DISCGRP hit;
that branch difference is the reason both scenarios exist.

## 9. Sources & scope

- Derived from the seeds `app/data/ASCII/{acctdata,discgrp}.txt` (and the
  `tcatbal` / `cardxref` seed rows); record layouts
  `app/cpy/{CVACT01Y,CVTRA01Y,CVTRA02Y,CVACT03Y}.cpy`; business-rule source
  `app/cbl/CBACT04C.cbl`. Coordinates with `tests/mocks/mock_discgrp.txt` (§4).
- **Seeds and production sources are REFERENCE-only and are never edited**
  (AAP §0.8.2). Fixtures are derived copies/subsets reshaped for this scenario.
- **Synthetic-data provenance (MA-24).** The card numbers, account ids, and
  customer ids in `cardxref.txt` are **synthetic, seed-derived** values copied from
  `app/data/ASCII/{cardxref,acctdata}.txt` (account `00000000002` / card
  `0923877193247330` byte-for-byte from the published seed); they represent **no
  real person or account**. Only non-identity business-rule fields (group id,
  balances) were reshaped. See master §10 for the full attestation.
- Consumed by `tests/integration/test_cbact04c_interest.py` (and the end-to-end
  interest cycle) through the shared helpers in `tests/helpers/*` — both
  **[planned]**, not present on the branch today (master §1).
