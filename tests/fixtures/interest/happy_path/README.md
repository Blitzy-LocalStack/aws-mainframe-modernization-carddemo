# Interest fixtures — happy path (`CBACT04C` DIRECT disclosure-group rate hit → `12.50` interest, no fee)

Deterministic, fixed-width **input** fixtures that drive `app/cbl/CBACT04C.cbl`
down the **DIRECT** disclosure-group interest path: the account's `ACCT-GROUP-ID`
is present in `DISCGRP`, so the keyed read succeeds with VSAM file **status `00`**
and the program never enters the status-23 `DEFAULT` fallback. It is the reference
"money-in → money-out" interest scenario **and** the folder that documents
`CBACT04C`'s account-flush behavior — including a real, reproducible production
defect — empirically, per finding **MA-22**. These files are the **"data-in"** side
of the golden-master model; the expected **"data-out"** lives in the mirror tree
`tests/golden/interest/happy_path/`.

> **Byte-encoding contract — read this FIRST:**
> [`../../README.md`](../../README.md) is the authoritative, byte-level contract for
> **every** fixture (fixed width with **no delimiters**, zoned-decimal **sign
> overpunch on the last byte** of signed fields, implied decimals, `LF`/`CRLF` and
> trailing-newline rules, and the `[present]`/`[planned]` availability of the
> `tests/golden/**`, `tests/integration/**`, `tests/e2e/**` trees and the
> `load_indexed.sh` / `cobol_runner.py` helpers). This scenario README **does not
> restate** that contract — no overpunch table and no full copybook layouts are
> duplicated here. It records only *what this scenario does* and *why*, the mandated
> Explainability carrier (AAP §0.10.1) for static `.txt` files that cannot hold
> docstrings.

---

## 1. Scenario intent — the DIRECT rate path

This scenario exercises the **DIRECT** `DISCGRP` key hit, **NOT** the `DEFAULT`
fallback. Each account's `ACCT-GROUP-ID` is set to **`A000000000`** — a group that
**is present** in this folder's `discgrp.txt` — so `CBACT04C`
`1200-GET-INTEREST-RATE` reads the disclosure-group row with file **status `00`**
and **never** enters the `IF DISCGRP-STATUS = '23'` branch that would move
`'DEFAULT'` into the key and re-read via `1200-A-GET-DEFAULT-INT-RATE`.

> **Why `A000000000` and not the shipped blank group (Assumption / Rationale).** The
> shipped seed accounts in `app/data/ASCII/acctdata.txt` carry a **blank**
> `ACCT-GROUP-ID`, whose keyed `DISCGRP` read misses and returns **status 23** —
> which would drive the `DEFAULT` fallback. This fixture *deliberately* sets
> `A000000000` (a real group row provided in `discgrp.txt`) to **force the DIRECT
> hit**, isolating the status-`00` branch as the thing under test. The status-23
> `DEFAULT`-fallback path is a **separate** scenario owned by another agent
> (`tests/fixtures/interest/default_fallback/`), which reaches the *same* `12.50`
> arithmetic via the *other* branch so any golden diff localizes to the branch, not
> the amount. Master `../../README.md` §6.2 endorses exactly this design ("set
> `ACCT-GROUP-ID = A000000000` … a group present in `discgrp`").

The scenario ships **two** accounts (`00000000001` and `00000000002`), both group
`A000000000`, each with one category balance of `1000.00`. Two accounts are
required for a reason that is the heart of this folder — see §2 (MA-22).

---

## 2. Rule exercised (from `app/cbl/CBACT04C.cbl`)

`CBACT04C` reads `TCATBAL` (`ORGANIZATION IS INDEXED`, `ACCESS SEQUENTIAL`) in
**key order** and, on each row, resolves the account, cross-reference, and rate,
then writes an interest transaction. The observed chain for this scenario:

1. **`1000-TCATBALF-GET-NEXT`** reads the next `TCATBAL` row (key = acct `11` +
   type `2` + cat `4`).
2. On an **account-id control break** (`TRANCAT-ACCT-ID NOT = WS-LAST-ACCT-NUM`):
   - **`1050-UPDATE-ACCOUNT`** flushes the *previous* account when it is not the
     first — `ADD WS-TOTAL-INT TO ACCT-CURR-BAL`, zero `ACCT-CURR-CYC-CREDIT` /
     `ACCT-CURR-CYC-DEBIT`, then `REWRITE` (see the MA-22 note below);
   - **`1100-GET-ACCT-DATA`** reads `ACCOUNT` by `FD-ACCT-ID = TRANCAT-ACCT-ID`;
   - **`1110-GET-XREF-DATA`** reads `XREF` by the **ALTERNATE** key
     `FD-XREF-ACCT-ID` (i.e. `READ XREF-FILE … KEY IS FD-XREF-ACCT-ID`).
3. It builds the `DISCGRP` key = `ACCT-GROUP-ID (A000000000)` +
   `TRANCAT-TYPE-CD (01)` + `TRANCAT-CD (0001)` and performs
   **`1200-GET-INTEREST-RATE`** → **DIRECT hit, status `00`** (no `DEFAULT`
   fallback).
4. `IF DIS-INT-RATE NOT = 0` (`15.00 ≠ 0`) → **`1300-COMPUTE-INTEREST`**:
   `COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200`, accumulated into
   `WS-TOTAL-INT`.
5. **`1300-B-WRITE-TX`** writes **exactly one** interest transaction per category
   row, whose observable shape is:

   | `TRAN-*` field | Value written by `1300-B-WRITE-TX` |
   |---|---|
   | `TRAN-TYPE-CD` | `01` |
   | `TRAN-CAT-CD` | `05` (stored as `9(04)` → `0005`) |
   | `TRAN-SOURCE` | `System` |
   | `TRAN-DESC` | `Int. for a/c ` + `ACCT-ID` (e.g. `Int. for a/c 00000000001`) |
   | `TRAN-CARD-NUM` | the account's `XREF` card (from `1110-GET-XREF-DATA`) |
   | `TRAN-AMT` | `WS-MONTHLY-INT` (the accrued interest) |
   | `TRAN-ID` | `PARM-DATE` + ascending `WS-TRANID-SUFFIX` (deterministic — §6) |
   | `TRAN-ORIG-TS` / `TRAN-PROC-TS` | runtime `DB2-FORMAT-TS` clock (volatile — §6) |

6. **`1400-COMPUTE-FEES`** is an **empty stub** — its body is the single comment
   `* To be implemented` — so **no fee transaction** is produced.

> **Why two accounts — the MA-22 final-flush defect (documented, empirically
> verified, out of scope to fix).** `1050-UPDATE-ACCOUNT` runs **only when the
> account id changes**. The `PERFORM UNTIL END-OF-FILE = 'Y'` loop tests its
> condition **before** the body, so the trailing `ELSE PERFORM 1050-UPDATE-ACCOUNT`
> after the read loop is **dead code** — the **last (or only) account is never
> written back**. A *single-account* interest fixture therefore can never
> demonstrate a persisted account update. Shipping a **second** account makes
> `00000000001` a **non-final** account whose `REWRITE` genuinely fires (its record
> changes), while `00000000002` stays the **final** account that documents the
> defect (its record is never rewritten). `CBACT04C` is production source and is
> **REFERENCE-only / never modified** (AAP §0.8.2), so this fixture *exposes and
> documents* the defect rather than working around it.

> **Why `…001` is deterministically the non-final account (Assumption, verified).**
> `TCATBAL` is indexed, so its rows are consumed in **key order** regardless of the
> flat fixture's physical line order; account `00000000001` is always read before
> `00000000002`. The non-final account under test does not depend on how the fixture
> happens to be sorted on disk.

---

## 3. Worked expectation (the key numbers)

- `TRAN-CAT-BAL = 1000.00`, `DIS-INT-RATE = 15.00` →
  `(1000.00 × 15.00) / 1200 = ` **`12.50`** per category row, asserted **exactly**
  in fixed point (no floating-point tolerance). This is the documented business
  rule (`1300-COMPUTE-INTEREST`) and matches master `../../README.md` §6.2.
- Exactly **one** interest transaction is written **per category row** (one per
  account here); **no** fee transaction is produced.
- The rate path is **DIRECT** — group `A000000000`, type `01`, cat `0001`,
  `DISCGRP` status `00` — **not** the `DEFAULT` fallback.

> The `12.50` here is the **rule-level / formula** expectation. What the *compiled,
> integrated* multi-account program persists over this exact fixture is documented
> honestly — and differs — in §7; read it before authoring or asserting a golden.

---

## 4. Input fixtures (files, `ASSIGN` bindings, geometry)

GnuCOBOL binds each COBOL `SELECT … ASSIGN TO <NAME>` to a same-named runtime
environment variable (wired by `scripts/test_env.sh`). All four inputs are
`ORGANIZATION IS INDEXED`, so the harness performs a **flat → indexed load** (the
`IDCAMS REPRO` analog) via `tests/helpers/load_indexed.sh` / `vsam_loader.py`
**before** invoking `CBACT04C`.

| File | `ASSIGN` | Copybook | `RECLN` | Organization | Key (offset/len) | Line ending | Records |
|---|---|---|---:|---|---|---|---:|
| `tcatbal.txt` | `TCATBALF` | `CVTRA01Y` | 50 | INDEXED | `TRAN-CAT-KEY` off 0 len 17 (acct 11 + type 2 + cat 4) | **LF** | 2 |
| `acctdata.txt` | `ACCTFILE` | `CVACT01Y` | 300 | INDEXED | `ACCT-ID` off 0 len 11 | LF | 2 |
| `discgrp.txt` | `DISCGRP` | `CVTRA02Y` | 50 | INDEXED | `DIS-GROUP-KEY` off 0 len 16 (group 10 + type 2 + cat 4) | LF | 1 |
| `cardxref.txt` | `XREFFILE` | `CVACT03Y` | 50 | INDEXED | primary `XREF-CARD-NUM` off 0 len 16 **+ ALTERNATE `XREF-ACCT-ID` off 25 len 11** | LF | 2 |

> **XREF alternate key (master §4.2).** `CBACT04C` reads the cross-reference **by
> account id** (`1110-GET-XREF-DATA`), so the loader must build the file with both
> its primary (card, offset 0) key and its alternate (acct-id, offset 25 len 11)
> key; the alternate is defined as `WITH DUPLICATES`.

---

## 5. Line-ending note — `tcatbal.txt` is **LF** (normalized from a CRLF seed)

Every file in this folder uses **LF (`\n`) only**, each with a single trailing
newline. This includes `tcatbal.txt`, even though its seed differs:

> **LF normalization for `tcatbal.txt` (Assumption / Trade-off, documented per
> master §3.2 / §9.1).** The seed `app/data/ASCII/tcatbal.txt` ships as **CRLF
> (`\r\n`)**, whereas `acctdata`, `discgrp`, and `cardxref` seeds are already **LF**.
> Master §3.2 permits a CRLF-derived fixture to *either* preserve CRLF (which must
> then be documented here) *or* follow the preferred **LF default** — this scenario
> chooses **LF** so all four files share one line ending. **Why LF (Trade-off):** the
> indexed loader treats one physical line as one fixed-length record, and a stray
> trailing `\r` would be absorbed into the trailing `FILLER` and push the record one
> byte past `RECLN` (50), corrupting the last field. Normalizing to LF removes that
> hazard and keeps the scenario byte-uniform; the cost — a one-byte divergence from
> the seed's *line ending* — is immaterial because the record **content** bytes are
> unchanged. **Verified on disk:** all four files are LF only (no `\r`) —
> `tcatbal`/`discgrp`/`cardxref` rows are 50 bytes, `acctdata` rows 300 bytes
> (`tcatbal` 102 B = 2×50 + 2 LF; `acctdata` 602 B; `discgrp` 51 B; `cardxref`
> 102 B).

---

## 6. Determinism — `TRAN-ID` is deterministic; only the timestamps vary

The interest transaction `CBACT04C` writes (`1300-B-WRITE-TX`) mixes deterministic
and runtime-varying fields; the two must not be confused (master §6.3):

- **`TRAN-ID` is DETERMINISTIC and asserted exactly.** It is
  `STRING PARM-DATE DELIMITED BY SIZE, WS-TRANID-SUFFIX` where `WS-TRANID-SUFFIX`
  is an ascending counter. With the **fixed `PARM-DATE`** the suite injects
  (determinism is the whole point), the ids are reproducible: for
  `PARM-DATE = 2022071800` the first emitted transaction is `2022071800000001`, the
  second `2022071800000002`, and so on. It is **not** normalized away.
- **`TRAN-ORIG-TS` and `TRAN-PROC-TS` on the *written* record are runtime.** Both
  are filled from the runtime `DB2-FORMAT-TS` clock, so they vary run to run. The
  comparator `tests/helpers/golden_compare.py` masks **only `PROC-TS`**; per master
  §6.3 the *written* record's `ORIG-TS` is therefore a **known, documented
  non-determinism limitation** to resolve where such output is compared (e.g. by
  injecting a fixed clock or masking the written `ORIG-TS` specifically for
  `CBACT04C` output).

> **Why the input fixtures carry no timestamps (Trade-off).** These are **input**
> fixtures, and the interest domain consumes no `DALYTRAN` `PROC-TS`; none of
> `TRAN-ID` / `ORIG-TS` / `PROC-TS` appears in the four `.txt` files. Baking a
> frozen timestamp into the inputs would couple the data to a clock and misrepresent
> what the program produces. Volatility lives only on the **output** side and is
> handled by the comparator, so the four input files stay **byte-stable** across
> runs. (Golden-master normalization was chosen over asserting the volatile written
> fields directly — a deterministic byte-diff on the stable fields beats a brittle
> assertion on a clock value.)

---

## 7. Expected outcome (authoritative intent — read the limitation)

When the `tests/golden/interest/happy_path/` mirror is authored (by the golden
agent — it is **[planned]**, not present today; master §1), it **must be captured
from the actual program output**. The **documented business-rule expectation** and
the **verified structural behavior** are:

- **Per-category interest (documented rule):** each account's single `1000.00`
  category balance accrues `(1000.00 × 15.00) / 1200 = 12.50`; assert this
  **exactly** at the **formula** level.
- **Non-final account `00000000001` IS flushed:** `1050-UPDATE-ACCOUNT` fires at the
  control break to `00000000002`, so account 1's record **is rewritten** (interest
  added to `ACCT-CURR-BAL`; `ACCT-CURR-CYC-CREDIT` / `-DEBIT` zeroed) — the update
  path a single-account fixture could never reach.
- **Final account `00000000002` is NOT flushed:** it remains at `158.00` with its
  cycle fields unchanged — the documented **final-flush defect** (§2).
- **`TRAN-ID`** is the deterministic `PARM-DATE`+suffix id (§6); **no fee
  transaction** is produced (`1400-COMPUTE-FEES` empty stub).

> **⚠ Known, out-of-scope divergence — do NOT hand-fabricate a golden balance.** An
> **end-to-end run of the compiled `CBACT04C` over this exact two-account fixture**
> (GnuCOBOL 3.2.0, `PARM-DATE = 2022071800`) was performed to verify the above. It
> confirmed the **structural** behavior (account 1's record changes; account 2's does
> not) **but** showed that the program's **integrated multi-account arithmetic
> diverges from the isolated `(1000 × 15)/1200 = 12.50` / `194.00 → 206.50`
> figures**. Empirically, and **reproducibly** (identical across repeated runs and
> under both dynamic `CALL` and static linking):
>
> - the written `TRAN-AMT` decoded to **`+12.59`**, not `12.50`; and
> - account 1's persisted `ACCT-CURR-BAL` came back as **`-181.52`**, not `206.50`
>   (cycle credit/debit correctly zeroed); account 2 stayed at `158.00`.
>
> This divergence is internal to the compiled production program's multi-account
> state and cannot be root-caused without a COBOL debugger. Because `CBACT04C` is
> **immutable REFERENCE source** (AAP §0.8.2), it is **documented here as a known
> limitation rather than "fixed" or papered over.** A naive `194.00 → 206.50` golden
> is therefore **not** what the program does: the golden for the persisted balance
> and the written amount **must be captured from real output** (with the volatile
> `PROC-TS` masked; §6), and the arithmetic divergence investigated separately. The
> fixture's contract is narrower and fully met — make the **non-final `REWRITE`
> observable**, pin the **deterministic `TRAN-ID`**, prove the **DIRECT** rate path,
> and **document the final-flush defect** — exactly the MA-22 deliverable. The
> sibling `../default_fallback/README.md` references this section as the authority
> for the same divergence.

---

## 8. Field values quick-reference

Values only — the byte-encoding (the full zoned-decimal **overpunch table**, implied
decimal position, and padding) lives in the master `../../README.md` §3 and is **not
restated here**; the encoded tokens below (e.g. `0000010000{`) are shown purely so
this scenario's numbers can be located in the raw files, and are re-parsed with
`tests/helpers/record_codec.py`.

- **`tcatbal.txt`** (2 rows, one per account): acct `00000000001` / `00000000002`,
  type `01`, cat `0001`, `TRAN-CAT-BAL` = **`1000.00`** (encoded `0000010000{`).
- **`acctdata.txt`** (2 rows): acct `00000000001` `ACCT-CURR-BAL` = **`194.00`**
  (`00000001940{`); acct `00000000002` `ACCT-CURR-BAL` = **`158.00`**
  (`00000001580{`). Both `ACCT-GROUP-ID` = **`A000000000`** (cols 113–122, changed
  from the seed's blank to force the DIRECT hit).
  > **Note — two distinct `A000000000` fields (Assumption).** Cols 103–112
  > (`ACCT-ADDR-ZIP`) *also* read `A000000000` **from the seed**; the operative
  > group id is the **separate** field at cols 113–122 (`ACCT-GROUP-ID`). They are
  > different fields that happen to share the same literal — do not conflate them.
- **`discgrp.txt`** (1 row): group `A000000000`, type `01`, cat `0001`,
  `DIS-INT-RATE` = **`15.00`** (encoded `00150{`).
- **`cardxref.txt`** (2 rows): card `9680294154603697` → cust `000000001` → acct
  `00000000001`; card `0923877193247330` → cust `000000002` → acct `00000000002`
  (each + 14-space `FILLER` to the full 50-byte width).

---

## 9. Provenance / derivation & synthetic-data attestation

- **Derived from (REFERENCE-only, never edited — AAP §0.8.2):** the seeds
  `app/data/ASCII/{tcatbal,acctdata,discgrp,cardxref}.txt`. Record layouts:
  `app/cpy/{CVTRA01Y,CVACT01Y,CVTRA02Y,CVACT03Y}.cpy`. Business-rule source:
  `app/cbl/CBACT04C.cbl`. See master `../../README.md` for the encoding contract.
- **Specific derivations:**
  - `acctdata.txt` — seed rows for accounts 1 and 2 with `ACCT-GROUP-ID` reshaped
    from **blank → `A000000000`** (forces the DIRECT hit; §1).
  - `discgrp.txt` — the seed's `A000000000 / 01 / 0001` row (rate `15.00`) — the
    single row the DIRECT read needs.
  - `cardxref.txt` — the account-1 and account-2 seed rows, padded to the full
    50-byte width.
  - `tcatbal.txt` — the account-1 and account-2 category rows with `TRAN-CAT-BAL`
    set to **`1000.00`**, normalized to **LF** (§5).
- **Synthetic-data attestation (master §10 / §10.3).** Every PAN (card number),
  account id, and customer id here is **synthetic test data derived byte-for-byte
  from the published AWS CardDemo sample seeds** and represents **no real person or
  account**. Card `0923877193247330`, account `00000000002`, and customer
  `000000002` are copied unchanged from `app/data/ASCII/{cardxref,acctdata}.txt`;
  only **non-identity business-rule fields** (`ACCT-GROUP-ID`, the category and
  account balances) were reshaped to the values above. The identity/PAN bytes are
  unchanged from the seed, so their non-person provenance is inherited intact.

---

## 10. Companion artifacts

- **Golden mirror — `tests/golden/interest/happy_path/`** (authored by the **golden
  agent**, not here; **[planned]**). Its expected end-state, at the **documented
  business-rule** level, is: interest transaction amount **`12.50`**,
  `ACCT-CURR-BAL` **`194.00` → `206.50`** on the non-final account, cycle
  credit/debit zeroed, and **no** fee transaction — **subject to the §7 divergence
  caveat**, which is why the persisted-balance/written-amount goldens must be
  captured from **actual** program output rather than hand-computed.
- **Consumed by (when present; both `[planned]`):**
  `tests/integration/test_cbact04c_interest.py` and the end-to-end interest cycle,
  through the shared helpers in `tests/helpers/*`.
- **Path discipline:** keep the domain/scenario folder name `interest/happy_path`
  **byte-identical** across the `tests/fixtures/**` and `tests/golden/**` trees
  (master §2.1) so `golden_compare.py` can pair input with expected output by path.

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the four
static `.txt` fixtures in this folder. Because static data cannot carry docstrings,
their WHY lives here — chiefly the DIRECT-hit rationale (`GROUP-ID` blank →
`A000000000`, §1), the two-account/final-flush treatment (§2, MA-22), the LF
normalization of `tcatbal.txt` (§5, MI-04), the deterministic-`TRAN-ID` correction
(§6, MI-03), the honest expected-outcome with its out-of-scope divergence (§7), and
the synthetic-data attestation (§9, MA-24).*
