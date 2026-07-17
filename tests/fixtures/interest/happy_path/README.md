# Interest — Happy-Path Scenario (`CBACT04C`), direct DISCGRP hit

This scenario drives the compiled `app/cbl/CBACT04C.cbl` interest calculator over a
**deterministic two-account** fixture whose disclosure group resolves on a
**direct** DISCGRP read (VSAM file status `00` — *not* the status-23 `DEFAULT`
fallback). It is the reference "money-in → money-out" interest scenario **and** the
scenario that documents `CBACT04C`'s account-flush behavior (including a real
production defect) empirically, per finding **MA-22**.

> **Read [`tests/fixtures/README.md`](../../README.md) first.** It is the
> authoritative byte-level contract (fixed-width records, zoned-decimal sign
> overpunch, implied decimals, line endings, and the availability status of the
> `tests/golden/**` / `tests/integration/**` trees and the `load_indexed.sh` /
> `cobol_runner.py` helpers). The overpunch table and full record layouts are
> **not** restated here.

---

## 1. Intent

Two accounts, each carrying one category balance of `1000.00`, are processed by
`CBACT04C` against a disclosure group that resolves **directly** to a rate of
`15.00`. The scenario exercises the interest formula on a **non-final** account
whose updated record is written back to disk, and it documents — with empirical
evidence — that the **final** account's record is **not** written back under the
current production flow.

- **Accounts:** `00000000001` (balance `194.00`) and `00000000002` (balance
  `158.00`); both group `A000000000`.
- **Category balances:** `1000.00` for each account (`type 01 / cat 0001`).
- **Rate path:** **DIRECT** — DISCGRP key `A000000000 / 01 / 0001` resolves with
  VSAM status `00`. (Contrast `interest/default_fallback`, which forces status 23.)
- **Documented per-category interest (business rule):**
  `(1000.00 × 15.00) / 1200 = 12.50`.

---

## 2. Why two accounts — the MA-22 rationale (KEY WHY)

`CBACT04C` reads `TCATBAL` (`ORGANIZATION IS INDEXED`, `ACCESS SEQUENTIAL`) in
**key order** and updates an account with this control-break logic (paraphrased
from `1000-*` / `1050-UPDATE-ACCOUNT`):

```cobol
PERFORM UNTIL END-OF-FILE = 'Y'
    read next TCATBAL row
    IF  TRANCAT-ACCT-ID NOT = WS-LAST-ACCT-NUM
        IF WS-FIRST-TIME NOT = 'Y'
            PERFORM 1050-UPDATE-ACCOUNT      *> flush the PREVIOUS account
        END-IF
        ...
    END-IF
    ...
END-PERFORM
```

`1050-UPDATE-ACCOUNT` does `ADD WS-TOTAL-INT TO ACCT-CURR-BAL`, zeroes
`ACCT-CURR-CYC-CREDIT` / `ACCT-CURR-CYC-DEBIT`, and `REWRITE`s the account.

> **The final-flush defect (documented, empirically verified, out of scope to
> fix).** The account is flushed **only when the account id changes**. The loop
> condition is tested **before** the body, so the trailing `ELSE PERFORM
> 1050-UPDATE-ACCOUNT` after the read loop is **dead code** — the **last (or only)
> account is never written back**. Therefore a *single-account* interest fixture
> can never demonstrate a persisted account update: the promised "`194.00 → 206.50`
> / cycle-clear" is **impossible** for a sole account (this is exactly what MA-22
> flagged). Adding a **second** account makes `00000000001` a **non-final** account
> whose `REWRITE` path genuinely fires, while `00000000002` remains the **final**
> account that documents the defect. `CBACT04C` is production source under `app/`
> and is **REFERENCE-only / never modified** (AAP §0.8.2), so the fixture *exposes*
> and *documents* the defect rather than working around it.

> **Why `…001` is guaranteed to be the non-final account (Assumption verified).**
> Because `TCATBAL` is indexed, its rows are read in **key order** regardless of
> the flat fixture's physical line order, so account `00000000001` is always read
> before `00000000002`. The non-final account under test is therefore
> deterministic — it does not depend on how the fixture happens to be sorted on
> disk.

---

## 3. Files in this folder

| File | Copybook | `RECLN` | Line ending | Records | Purpose |
|---|---|---:|---|---:|---|
| `acctdata.txt` | `CVACT01Y` | 300 | LF | 2 | Accounts `00000000001` (`CURR-BAL 194.00`) and `00000000002` (`CURR-BAL 158.00`), both group `A000000000` (direct DISCGRP hit). |
| `tcatbal.txt` | `CVTRA01Y` | 50 | LF | 2 | One category-balance row per account: `type 01 / cat 0001`, `TRAN-CAT-BAL = 1000.00`. |
| `discgrp.txt` | `CVTRA02Y` | 50 | LF | 1 | Single direct-hit row `A000000000 / 01 / 0001`, `DIS-INT-RATE = 15.00`. |
| `cardxref.txt` | `CVACT03Y` | 50 | LF | 2 | card `9680294154603697` → cust `000000001` → acct `00000000001`; card `0923877193247330` → cust `000000002` → acct `00000000002` (each + 14-space `FILLER` to the full 50-byte width). |

> **Line endings — all LF, including `tcatbal.txt` (MI-04, documented exception).**
> The master contract (§3.2) notes the `tcatbal` **seed** ships as **CRLF** and
> requires any CRLF choice to be documented per scenario. This scenario instead
> **normalizes `tcatbal.txt` to LF**, matching the other three fixtures and the LF
> default for new fixtures. **Why LF (Trade-off):** the loader treats one physical
> line as one fixed-length record; a stray `\r` risks being absorbed into the
> trailing `FILLER` and pushing the record one byte past `RECLN`. Normalizing to LF
> removes that hazard and keeps the whole scenario byte-uniform; the cost — a
> one-byte divergence from the seed's line ending — is immaterial because the
> record *content* bytes are unchanged. Verified: every file here is LF only (no
> `\r`), `acctdata` 300-byte records, the others 50-byte records.

---

## 4. Field values that matter

Operative fields with **1-based column ranges** and the **exact encoded bytes**
(verified against `app/cpy/` and re-parsed with `tests/helpers/record_codec.py`).
Signed money uses zoned-decimal sign overpunch — `{` is a trailing digit `0` with a
**positive** sign; see `../../README.md` §3.4 for the full table and §3.5 for the
implied decimal (`V` occupies no byte).

| File | Row | Field | PIC | Cols | Encoded | Decodes to |
|---|---|---|---|---|---|---|
| `acctdata.txt` | 1 | `ACCT-ID` | `9(11)` | 1–11 | `00000000001` | account 1 (non-final) |
| `acctdata.txt` | 1 | `ACCT-CURR-BAL` | `S9(10)V99` | 13–24 | `00000001940{` | **+194.00** |
| `acctdata.txt` | 1 | `ACCT-GROUP-ID` | `X(10)` | 113–122 | `A000000000` | group (direct hit) |
| `acctdata.txt` | 2 | `ACCT-ID` | `9(11)` | 1–11 | `00000000002` | account 2 (final) |
| `acctdata.txt` | 2 | `ACCT-CURR-BAL` | `S9(10)V99` | 13–24 | `00000001580{` | **+158.00** |
| `tcatbal.txt` | 1–2 | `TRAN-CAT-BAL` | `S9(09)V99` | 18–28 | `0000010000{` | **+1000.00** each |
| `discgrp.txt` | 1 | `DIS-INT-RATE` | `S9(04)V99` | 17–22 | `00150{` | **+15.00** |
| `cardxref.txt` | 1 | `XREF-ACCT-ID` | `9(11)` | 26–36 | `00000000001` | links card 1 → acct 1 |
| `cardxref.txt` | 2 | `XREF-ACCT-ID` | `9(11)` | 26–36 | `00000000002` | links card 2 → acct 2 |

---

## 5. `ASSIGN`-name mapping (GnuCOBOL runtime bindings)

GnuCOBOL binds each `SELECT … ASSIGN TO <NAME>` to a same-named environment
variable (wired by `scripts/test_env.sh`). All four inputs are
`ORGANIZATION IS INDEXED`, so the harness performs a **flat → indexed load** (via
`tests/helpers/vsam_loader.py` today; `load_indexed.sh` once it wraps it — see
`../../README.md` §1) **before** invoking `CBACT04C`.

| Fixture | `ASSIGN` name | Organization | RECORD KEY (length) |
|---|---|---|---|
| `tcatbal.txt` | `TCATBALF` | INDEXED | acct 11 + type 2 + cat 4 = **17** |
| `acctdata.txt` | `ACCTFILE` | INDEXED | `ACCT-ID` = **11** |
| `discgrp.txt` | `DISCGRP` | INDEXED | group 10 + type 2 + cat 4 = **16** |
| `cardxref.txt` | `XREFFILE` | INDEXED | card = **16** (primary) **+ ALTERNATE key on acct-id** |

> **XREF alternate key (§4.2 of the master).** `CBACT04C` reads the cross-reference
> **by account id** (`1110-GET-XREF-DATA`: `READ XREF-FILE … KEY IS
> FD-XREF-ACCT-ID`). The fixture must load cleanly under both the primary (card)
> key and the alternate (acct-id at bytes 26–36) key.

---

## 6. Determinism: `TRAN-ID` is deterministic; only timestamps vary (MI-03)

The interest transaction `CBACT04C` writes (`1300-B-WRITE-TX`) has both
deterministic and runtime-varying fields:

- **`TRAN-ID` is DETERMINISTIC.** It is built as `STRING PARM-DATE DELIMITED SIZE,
  WS-TRANID-SUFFIX` where `WS-TRANID-SUFFIX` is an ascending counter starting at 0.
  With a **fixed `PARM-DATE`** (the suite injects one — determinism is the whole
  point), the ids are reproducible: e.g. for `PARM-DATE = 2022071800`, the first
  emitted transaction is `2022071800000001`, the second `2022071800000002`, and so
  on. **This value is asserted exactly** — it is *not* non-deterministic. (An
  earlier revision incorrectly called the fixed-PARM `TRAN-ID` non-deterministic;
  MI-03 corrects that: only the timestamps below vary.)
- **`ORIG-TS` and `PROC-TS` on the _written_ record are runtime.** Both are filled
  from the runtime `DB2-FORMAT-TS` clock, so they vary run to run and must be
  masked/normalized when the written transaction is compared (see the master §6.3
  "Documented limitation — `CBACT04C`-written transactions").

> **Why the input fixtures carry no timestamps (Trade-off).** These are *input*
> fixtures; the interest domain consumes no `DALYTRAN` `PROC-TS`. Baking a frozen
> timestamp into the inputs would couple the data to a clock and misrepresent what
> the program produces. Volatility lives only on the **output** side and is handled
> by the comparator, keeping the four input files byte-stable across runs.

---

## 7. Expected outcome (authoritative intent; read the limitation)

When the `tests/golden/interest/happy_path/` mirror is authored (it is **[planned]**,
not present today — see master §1), it must be captured from the **actual**
program output. The **documented business-rule expectation** and the **verified
structural behavior** are:

- **Per-category interest (documented rule):** each account's single `1000.00`
  category balance accrues `(1000.00 × 15.00) / 1200 = 12.50` of interest. Assert
  this **exactly** (no floating-point tolerance) at the formula level.
- **Non-final account `00000000001` IS flushed:** `1050-UPDATE-ACCOUNT` runs when
  the key breaks to account `00000000002`, so account 1's record **is rewritten**
  (interest added to `ACCT-CURR-BAL`; `ACCT-CURR-CYC-CREDIT` / `-DEBIT` zeroed).
  This is the update path a single-account fixture could never reach.
- **Final account `00000000002` is NOT flushed:** it remains at `158.00` with its
  cycle fields unchanged — the documented **final-flush defect** (§2).
- **`TRAN-ID`** is the deterministic `PARM-DATE`+suffix id (§6); **no fee
  transaction** is produced because `1400-COMPUTE-FEES` is an empty stub
  (`* To be implemented`).

> **⚠ Known, out-of-scope limitation — do not fabricate a golden balance.** An
> **end-to-end run of the compiled `CBACT04C` over this exact two-account fixture**
> was performed to verify the above. It confirmed the structural behavior (account
> 1's record changes; account 2's does not) **but** revealed that the program's
> **integrated multi-account interest arithmetic diverges from the isolated
> `(1000 × 15)/1200 = 12.50` formula**: the written transaction amount and account
> 1's posted balance did **not** equal `12.50` / `206.50`. The divergence is
> **reproducible under both dynamic (`CALL`) and static linking**, is internal to
> the compiled production program's multi-account state, and **cannot be
> root-caused without a COBOL debugger**. Because `CBACT04C` is **immutable
> REFERENCE source** (AAP §0.8.2), this is documented here as a known limitation
> rather than "fixed" or papered over. **The earlier README claim of `194.00 →
> 206.50` was removed precisely because it is not what the program does** — a golden
> for the persisted balance must be captured from real output and this divergence
> investigated separately. The fixture's contract is narrower and fully met: make
> the **non-final `REWRITE` observable**, pin the **deterministic `TRAN-ID`**, and
> **document the final-flush defect** — exactly the MA-22 deliverable.

---

## 8. Data governance / synthetic provenance (MA-24)

Every PAN, account id, and cross-reference value here is **synthetic, seed-derived
test data** and represents **no real person or account** (master §10). Specifically:

- Account `00000000001`, its category balance, and card `9680294154603697` are the
  original single-account happy-path values, derived from `app/data/ASCII/`.
- Account `00000000002` (balance `158.00`) and card `0923877193247330` are copied
  **byte-for-byte** from the published synthetic seed rows
  (`app/data/ASCII/acctdata.txt` line 2 and the matching `cardxref.txt` row); only
  the group id and category balance were reshaped to the values above so the direct
  DISCGRP hit and the `1000.00` balance under test are exact. The identity/PAN bytes
  are unchanged from the seed, so their non-person provenance is inherited intact.

---

## 9. Consumers & sources

- **Consumed by (when present):** `tests/integration/test_cbact04c_interest.py` and
  the end-to-end interest cycle (both **[planned]** — master §1).
- **Derived from (never edited):** `app/data/ASCII/{acctdata,cardxref,discgrp}.txt`
  (and a `tcatbal` seed row, normalized to LF).
- **Record layouts:** `app/cpy/{CVACT01Y,CVTRA01Y,CVTRA02Y,CVACT03Y}.cpy`.
- **Business rule:** `app/cbl/CBACT04C.cbl` (`1000-*`, `1050-UPDATE-ACCOUNT`,
  `1200-GET-INTEREST-RATE`, `1300-COMPUTE-INTEREST`, `1400-COMPUTE-FEES`).

**Minimal-change principle (mandatory).** Seeds under `app/data/ASCII/` and all
production sources under `app/` are **REFERENCE ONLY and are never modified**
(AAP §0.8.2). Fixtures are derived copies reshaped for this scenario.

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the four
static `.txt` fixtures in this folder. Static data cannot carry docstrings, so their
WHY lives here — chiefly the two-account/final-flush rationale (§2), the LF
normalization (§3), the deterministic-`TRAN-ID` correction (§6), the honest
expected-outcome with its out-of-scope limitation (§7), and the synthetic-data
attestation (§8).*
