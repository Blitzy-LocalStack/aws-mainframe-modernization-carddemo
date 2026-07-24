# Golden masters — posting / zero_balance

Byte-exact **expected outputs** ("data-out") of `app/cbl/CBTRN02C.cbl` when it is run
on the paired input fixtures in
[`tests/fixtures/posting/zero_balance/`](../../../fixtures/posting/zero_balance/README.md).
This folder is diffed against the program's actual output by
[`tests/helpers/golden_compare.py`](../../../helpers/golden_compare.py)
(`assert_matches_golden`), which pairs a scenario's **inputs** with its **expected**
outputs purely by matching directory path.

> **Why this README exists (mandatory Explainability artifact — AAP §0.10.1).**
> The five siblings in this folder (`tranfile.expected`, `acctdat.expected`,
> `tcatbal.expected`, `dalyrejs.expected`, `return_code.expected`) are static,
> fixed-width `.expected` data files that cannot carry inline docstrings, so — per the
> project Explainability rule — **this document is the mandated *why* for their bytes**.
> The authoritative byte-encoding contract (field widths, offsets, the zoned-decimal
> sign-overpunch table, line endings, the `decimal.Decimal` / no-float rule) lives in the
> master [`tests/fixtures/README.md`](../../../fixtures/README.md); this file **references
> it and never restates or contradicts it**. It records only what is specific to the
> *captured output* of this scenario.
>
> **Program under test is REFERENCE-only.** `app/cbl/CBTRN02C.cbl` and all copybooks/seeds
> are consumed as REFERENCE and are **never modified** (AAP §0.8.2, §0.10.2). If a fresh
> run disagrees with these goldens, the fixtures or the load step are wrong — not the
> program, and not these files.

---

## 1. Scenario intent

`zero_balance` posts **one** well-formed, in-limit daily transaction onto an account whose
current balance has been forced to **exactly `+0.00`**, and whose category-balance row
**does not yet exist**. It is the deliberate **create-vs-update counterpart** to
`happy_path`/`boundary_*`: because the paired category-balance fixture is empty, this
scenario exercises the **`TCATBAL` CREATE branch** (`2700-A-CREATE-TCATBAL-REC`, a
`WRITE`), whereas those siblings seed an existing row and take the **update** branch
(`2700-B-UPDATE-TCATBAL-REC`, a `REWRITE`).

The transaction is card `4859452612877065` → **account `00000000007`** (account 7),
`DALYTRAN-AMT = +504.77`, transaction date `2022-06-10`. The card is present in the XREF,
the account is found, the resulting balance is within the `+2065.00` credit limit, and the
date is not past the `2024-12-13` expiration — so the transaction **posts cleanly**:
**`RETURN-CODE = 0`, no rejects**, one row written to the transaction file, and both the
account master and a **new** category-balance row updated to `+504.77`.

**Why zero the starting balance (Assumption / Trade-off).** Starting from `+0.00` makes the
posting arithmetic trivially auditable (`0.00 + 504.77 = 504.77` appears verbatim in three
independent goldens) and isolates the create-branch behaviour from any pre-existing balance
noise, so a reviewer can confirm the money math and the CREATE path in a single scenario.

## 2. Business rule & why the CREATE branch fires

All paragraph/field names below are transcribed from `app/cbl/CBTRN02C.cbl`; the full
reject-code and validation semantics are single-sourced in
[`tests/fixtures/README.md`](../../../fixtures/README.md) §6.1 and are not restated here.

**Validation — `1500-VALIDATE-TRAN` → all four checks pass → POST.**

- **`1500-A-LOOKUP-XREF`:** card `4859452612877065` **is** in the XREF and resolves to
  `XREF-ACCT-ID = 00000000007` → **no reject 100** (`INVALID CARD NUMBER FOUND`).
- **`1500-B-LOOKUP-ACCT`:** account `00000000007` **is** found → **no reject 101**
  (`ACCOUNT RECORD NOT FOUND`).
- **Over-limit check (operator is `>=`):**
  `COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT − ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT`
  `= 0.00 − 0.00 + 504.77 = 504.77`; the test `ACCT-CREDIT-LIMIT (2065.00) >= 504.77` is
  **TRUE** → **no reject 102** (`OVERLIMIT TRANSACTION`).
- **Expiration check (`>=`):** `ACCT-EXPIRAION-DATE (2024-12-13) >= DALYTRAN-ORIG-TS(1:10)
  (2022-06-10)` is **TRUE** → **no reject 103**
  (`TRANSACTION RECEIVED AFTER ACCT EXPIRATION`). The copybook's preserved misspelling
  `ACCT-EXPIRAION-DATE` is intentional (it is the real field name).

**Why the CREATE branch fires — `2700-UPDATE-TCATBAL` → `2700-A-CREATE-TCATBAL-REC`.**
The program builds the category key from `XREF-ACCT-ID + DALYTRAN-TYPE-CD + DALYTRAN-CAT-CD`
= `00000000007` + `01` + `0001` (composite `00000000007010001`) and issues a random
`READ TCATBAL-FILE`. Because the paired input fixture `tcatbal.txt` is **empty (0 bytes)**,
that read on the `OPEN I-O` indexed file returns **`INVALID KEY` (VSAM file status `23`)**,
which the program accepts as normal (`IF TCATBALF-STATUS = '00' OR '23'`) and routes to the
create path by setting `WS-CREATE-TRANCAT-REC = 'Y'`. The CREATE arm then `INITIALIZE`s a
fresh `TRAN-CAT-BAL-RECORD`, moves the three key parts, `ADD DALYTRAN-AMT TO TRAN-CAT-BAL`
(`0.00 + 504.77`), and **`WRITE`s a brand-new row**. (The running program even prints
`TCATBAL record not found for key : 00000000007010001.. Creating.`)

**Why this is the create-vs-update counterpart (Trade-off).** Splitting create and update
into separate scenarios gives clean, single-branch coverage of the
`IF WS-CREATE-TRANCAT-REC = 'Y'` decision: `zero_balance` alone ships a genuinely empty
category file to drive `2700-A` (WRITE), while `happy_path` keeps a populated row to drive
`2700-B` (REWRITE). The account update itself is shared: `2800-UPDATE-ACCOUNT-REC` does
`ADD DALYTRAN-AMT TO ACCT-CURR-BAL` and, because `DALYTRAN-AMT (504.77) >= 0`, adds the same
amount to `ACCT-CURR-CYC-CREDIT` and leaves `ACCT-CURR-CYC-DEBIT` unchanged; then
`2900-WRITE-TRANSACTION-FILE` writes the posted row.

## 3. Expected outputs — the five sibling goldens

One row per file. Values are the **captured** bytes committed in this folder; encoding
mechanics (overpunch, implied decimal, LF framing) are authoritative in
[`tests/fixtures/README.md`](../../../fixtures/README.md) §3 and are only *cited* here. The
"Compared as" column is the exact mode/layout the harness uses (see
[`tests/integration/test_cbtrn02c_posting.py`](../../../integration/test_cbtrn02c_posting.py)
`_GOLDEN_OUTPUTS`).

| File | Record bytes | Compared as | Expected content (summary) |
|---|---|---|---|
| `tranfile.expected` | 350 (+LF ⇒ 351 on disk) | record mode, `layout="TRAN"` | One posted `TRAN-RECORD` (`CVTRA05Y`). Bytes 1–278 are copied from the input daily-tran, incl. `TRAN-AMT = 0000005047G` (**+504.77**, 11-byte `S9(09)V99`) and `TRAN-CARD-NUM = 4859452612877065`. `TRAN-ORIG-TS = 2022-06-10 19:27:53.000000` (bytes 279–304, **preserved verbatim**). `TRAN-PROC-TS` (bytes 305–330) is the **runtime** stamp — stored blank and **normalized** (see §4). Trailing `FILLER X(20)` (bytes 331–350) = **20 × `0x00`** (captured — see §5). |
| `acctdat.expected` | 300 (+LF ⇒ 301) | record mode, `layout="ACCOUNT"` | Account `00000000007` (`CVACT01Y`) after post: `ACCT-CURR-BAL` `0.00 → 504.77` (`00000005047G`), `ACCT-CURR-CYC-CREDIT` `0.00 → 504.77` (`00000005047G`), `ACCT-CURR-CYC-DEBIT` **unchanged** `+0.00` (`00000000000{`). `ACCT-CREDIT-LIMIT +2065.00`, `ACCT-EXPIRAION-DATE 2024-12-13`, and all other fields **unchanged** from the fixture. |
| `tcatbal.expected` | 50 (+LF ⇒ 51) | record mode, `layout="TCATBAL"` | **Newly created** `TRAN-CAT-BAL-RECORD` (`CVTRA01Y`), key `00000000007010001`, `TRAN-CAT-BAL` `0.00 → 504.77` (`0000005047G`, 11-byte `S9(09)V99`). Trailing `FILLER X(22)` (bytes 29–50) = **22 × `0x00`**, **captured from the run** — see §5. |
| `dalyrejs.expected` | 0 | record mode, `layout="REJECT"` | **Empty (0 bytes)** — clean post, so `2500-WRITE-REJECT-REC` never runs and no reject record is written. |
| `return_code.expected` | 1 | text mode (no layout) | Literal **`0`** — the transaction posts, `WS-REJECT-COUNT = 0`, so the `IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE` escalation never fires. |

**Why the same `+504.77` has two spellings (field-width distinction, not a contradiction).**
It encodes as `0000005047G` in the **11-byte** `TCATBAL.TRAN-CAT-BAL` (`S9(09)V99`) but as
`00000005047G` in the **12-byte** `ACCOUNT` money fields (`S9(10)V99`) — the same value,
one extra leading zero for the wider `PIC`; the overpunch `G` (`+7` on the final digit) is
identical. `+0.00` in a 12-byte field is `00000000000{` (`{` = `+0`). The full overpunch
table is in [`tests/fixtures/README.md`](../../../fixtures/README.md) §3.4 — **not restated
here**.

## 4. Timestamp determinism & normalization

`tranfile.expected` is the only golden with a run-varying field. In
`2000-POST-TRANSACTION` the program copies `DALYTRAN-ORIG-TS` into `TRAN-ORIG-TS`
verbatim, then stamps `TRAN-PROC-TS` from `FUNCTION CURRENT-DATE` (via
`Z-GET-DB2-FORMAT-TIMESTAMP`). Therefore:

- **`TRAN-ORIG-TS` (bytes 279–304) is deterministic** business data — the originating
  timestamp copied from the input, whose first 10 bytes (`2022-06-10`) are the very date the
  expiration check consumes. It is **preserved and compared verbatim** (this golden pins it
  to `2022-06-10 19:27:53.000000`).
- **`TRAN-PROC-TS` (bytes 305–330) is non-deterministic** (wall-clock at run time). The
  comparator neutralizes it: in **record mode** (`layout="TRAN"`),
  `assert_matches_golden` blanks **only** the layout's `normalize_ts` field — i.e. only
  `*-PROC-TS` — **in place**, preserving the record's total width and every other byte. The
  committed golden stores that field as 26 blanks; at compare time the actual run's live
  timestamp is blanked the same way on both sides, so the two agree without a flake.

**Why only PROC-TS, and why the posted layout is `TRAN` (not the input `DALYTRAN`).** The
record layouts are single-sourced in
[`tests/helpers/record_codec.py`](../../../helpers/record_codec.py), where **only** the
`*-PROC-TS` field is flagged `normalize_ts`; `*-ORIG-TS` is deliberately **not** flagged,
because blanking the deterministic originating timestamp (as an earlier build did) would
discard real business data that the golden should verify. The *input* daily transaction is
the `DALYTRAN` layout (`CVTRA06Y`) and the *posted output* is the `TRAN` layout
(`CVTRA05Y`); the two are geometrically identical and **both** mark `PROC-TS` (offset
304, i.e. bytes 305–330) as the sole normalized field — so the timestamp handling is the
same regardless of which of the two layout names drives it. The harness compares this
output golden with `layout="TRAN"`.

The other four goldens have **no** processing timestamp to normalize: `tcatbal.expected`
and `acctdat.expected` are compared **byte-exactly** in record mode (`layout="TCATBAL"` /
`layout="ACCOUNT"`), `dalyrejs.expected` is empty (record mode `layout="REJECT"`, framed by
width — trivially empty here), and `return_code.expected` is a one-byte text-mode value.
Every comparison is symmetric (the same normalization is applied to golden and actual), and
the reclen+1 EOF-newline framing is canonicalised so the program's raw fixed-width read-back
and the newline-terminated golden reduce to the identical record sequence. See
[`tests/helpers/golden_compare.py`](../../../helpers/golden_compare.py) for the exact rules.

## 5. Why `tcatbal.expected` must be **captured**, not hand-authored

This is the key Explainability point of the scenario: the create-vs-update **FILLER byte
divergence**.

- `2700-A-CREATE-TCATBAL-REC` builds the record with `INITIALIZE TRAN-CAT-BAL-RECORD`. Per
  the COBOL standard, `INITIALIZE` (without `REPLACING`) sets elementary numeric items to
  zero and alphanumeric items to spaces **but does not touch `FILLER` items**. So the
  22-byte trailing `FILLER X(22)` (bytes 29–50) keeps whatever the record's
  WORKING-STORAGE default is under the compiler — a value that is **not reliably
  hand-predictable**.
- **Captured result (authoritative):** under this repository's toolchain (GnuCOBOL,
  `-fixed -I app/cpy --std=ibm-strict`), the emitted `FILLER` is **22 bytes of `0x00`**
  (`LOW-VALUES` / NUL) — the WORKING-STORAGE default GnuCOBOL gives the un-`VALUE`d record
  area, left untouched by `INITIALIZE`. The same phenomenon appears in `tranfile.expected`,
  whose `TRAN` `FILLER X(20)` (bytes 331–350) is likewise **20 × `0x00`**.
- **Assumption / Trade-off — why this must be run-and-captured.** These bytes are
  **compiler/flag-dependent**. The captured `0x00` is a *third* value, distinct from **both**
  the seed convention (an input `tcatbal.txt` row pads `FILLER` with `'0'` characters) **and**
  the "spaces" hypothesis the paired input-fixture README documents as the *fallback for when
  `cobc` is unavailable*. Because it cannot be safely predicted by hand, `tcatbal.expected`
  **MUST** be produced by run-and-capture and committed via the guarded update mode; CI must
  re-run to confirm/correct it. This is exactly the regression the integration test guards
  against — a stale golden that carried spaces where the runtime emits NUL.
- **Contrast with `happy_path` (the update arm).** `2700-B-UPDATE-TCATBAL-REC` `REWRITE`s an
  **existing** row read from a seeded fixture, so it **preserves** that row's pre-existing
  (`'0'`-filled) `FILLER`. The create-vs-update byte divergence this scenario targets is
  therefore real and sharp: created `FILLER` = `0x00` versus a seeded/updated row's `'0'`
  characters.

## 6. How to (re)generate — provenance

These goldens are **program output**, not hand-assembled. To reproduce (mirrors the paired
input-fixture README's recipe):

1. **Build** the program: `scripts/build_test_programs.sh` (repository convention
   `cobc -fixed -I app/cpy --std=ibm-strict`).
2. **Provision** the paired fixture into an isolated workspace via
   [`tests/helpers/load_indexed.sh`](../../../helpers/load_indexed.sh) /
   `vsam_loader.py`: load `acctdata.txt` (`ACCTFILE`) and `cardxref.txt` (`XREFFILE`) into
   GnuCOBOL indexed files, provision an **empty `TCATBALF`** from the 0-byte `tcatbal.txt`
   (this is what forces the CREATE branch), and bind `DALYTRAN` to the 350-byte
   `dailytran.txt`.
3. **Run** `CBTRN02C` through
   [`tests/helpers/cobol_runner.py`](../../../helpers/cobol_runner.py), which binds each
   `SELECT ... ASSIGN` external name to its workspace file via an environment variable
   (`DALYTRAN`, `XREFFILE`, `ACCTFILE`, `TCATBALF`, `TRANFILE`, `DALYREJS`).
4. **Capture** `TRANFILE` → `tranfile`, `ACCTFILE` → `acctdat`, the newly written
   `TCATBALF` row → `tcatbal`, `DALYREJS` (empty) → `dalyrejs`, and the process
   `RETURN-CODE` (`0`) → `return_code`.
5. **Normalize & commit** via
   `assert_matches_golden(actual, ".../<name>.expected", layout=..., update=True)` under the
   two-signal opt-in the comparator requires (`CARDDEMO_UPDATE_GOLDENS=1`, and never in CI).
   Record mode blanks only `TRAN-PROC-TS`; every other byte — including the `0x00` FILLER —
   is written verbatim.
6. **Validate** the captured bytes against §3: `RETURN-CODE = 0`, `dalyrejs` empty, the
   `+504.77` postings in all three record goldens, the `00000000007010001` category key, and
   the `0x00` FILLER on the created `tcatbal` row.

## 7. Links & references

- **Encoding / record-layout contract (authoritative — referenced, not restated):**
  [`tests/fixtures/README.md`](../../../fixtures/README.md) — field widths, offsets,
  zoned-decimal overpunch (§3.4), implied decimal (§3.5), LF framing (§3.3), and the
  record-layout tables (§5.2 ACCOUNT, §5.5 TRAN, §5.6 TCATBAL, §5.1 DALYTRAN).
- **Paired input fixtures for this scenario:**
  [`tests/fixtures/posting/zero_balance/README.md`](../../../fixtures/posting/zero_balance/README.md)
  (the 1:1 inputs that produce these outputs).
- **Comparator:**
  [`tests/helpers/golden_compare.py`](../../../helpers/golden_compare.py)
  (`assert_matches_golden`, record vs text mode, `normalize_ts` handling).
- **Consumer:**
  [`tests/integration/test_cbtrn02c_posting.py`](../../../integration/test_cbtrn02c_posting.py)
  (`_GOLDEN_OUTPUTS` drives the five byte-exact comparisons).
- **Program under test (REFERENCE ONLY, never modified — AAP §0.8.2, §0.10.2):**
  [`app/cbl/CBTRN02C.cbl`](../../../../app/cbl/CBTRN02C.cbl) — `1500-VALIDATE-TRAN`,
  `2700-UPDATE-TCATBAL` → `2700-A-CREATE-TCATBAL-REC`, `2800-UPDATE-ACCOUNT-REC`,
  `2900-WRITE-TRANSACTION-FILE`.
- **Record layouts (REFERENCE ONLY, single-sourced):**
  [`app/cpy/CVTRA05Y.cpy`](../../../../app/cpy/CVTRA05Y.cpy) (TRAN),
  [`app/cpy/CVACT01Y.cpy`](../../../../app/cpy/CVACT01Y.cpy) (ACCOUNT),
  [`app/cpy/CVTRA01Y.cpy`](../../../../app/cpy/CVTRA01Y.cpy) (TCATBAL),
  [`app/cpy/CVTRA06Y.cpy`](../../../../app/cpy/CVTRA06Y.cpy) (DALYTRAN),
  [`app/cpy/CVACT03Y.cpy`](../../../../app/cpy/CVACT03Y.cpy) (XREF).

## 8. Data governance / synthetic provenance

The card number (`4859452612877065`), account id (`00000000007`), and amount (`+504.77`)
are **synthetic, seed-derived** values from the published AWS CardDemo sample datasets and
represent **no real person or account**; the full attestation lives in the paired
input-fixture README §4 and [`tests/fixtures/README.md`](../../../fixtures/README.md) §10.
These `.expected` files are generated program output only.

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the five static
`.expected` data files in this folder.*
