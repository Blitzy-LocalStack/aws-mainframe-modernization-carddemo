# Posting golden — zero_balance (TCATBAL **CREATE** branch, run-and-captured)

This folder holds the golden-master expected output for the `posting/zero_balance`
scenario. Its single artifact, **`tcatbal.expected`**, is the one 50-byte
`TRAN-CAT-BAL-RECORD` (`app/cpy/CVTRA01Y.cpy`) that `app/cbl/CBTRN02C.cbl`
**newly `WRITE`s** for account `00000000007` when it posts the scenario's daily
transaction. It is the direct counterpart to sibling `posting/happy_path`, whose
golden is produced by the **update** arm (`2700-B-UPDATE-TCATBAL-REC`, a
`REWRITE`); this scenario exercises the **create** arm
(`2700-A-CREATE-TCATBAL-REC`, a `WRITE`).

> **Why this README exists (mandatory Explainability artifact — AAP §0.10.1).**
> `tcatbal.expected` is raw fixed-width binary data that cannot carry inline
> docstrings, so this document is the mandated *why* for its bytes. It exists
> above all to record **how the compiler-dependent trailing `FILLER` bytes were
> obtained** (run-and-capture, not hand-authored) and **what they actually turned
> out to be**. The authoritative byte-encoding contract (field widths, offsets,
> the zoned-decimal overpunch table, `-fsign=EBCDIC`) lives in the master
> **[`tests/fixtures/README.md`](../../../fixtures/README.md)** §5.6 and the input
> fixtures' scenario note
> **[`tests/fixtures/posting/zero_balance/README.md`](../../../fixtures/posting/zero_balance/README.md)**;
> this file never restates that contract, only references it and records what is
> specific to the *captured output*.

## 1. Record layout & the deterministic prefix (bytes 1–28)

The layout is `CVTRA01Y` (RECLN 50) — see master §5.6 for the field table; it is
**not** restated here. Only the values this scenario pins are listed:

| Field | PIC | Bytes | Captured value | Origin |
|---|---|---|---|---|
| `TRANCAT-ACCT-ID` | `9(11)` | 1–11 | `00000000007` | `XREF-ACCT-ID` (from `cardxref.txt`) |
| `TRANCAT-TYPE-CD` | `X(02)` | 12–13 | `01` | `DALYTRAN-TYPE-CD` |
| `TRANCAT-CD` | `9(04)` | 14–17 | `0001` | `DALYTRAN-CAT-CD` |
| `TRAN-CAT-BAL` | `S9(09)V99` | 18–28 | `0000005047G` (**+504.77**) | `0.00 + DALYTRAN-AMT` |
| `FILLER` | `X(22)` | 29–50 | **22 × `0x00`** (see §3) | `INITIALIZE` + GnuCOBOL WS default |

The three key fields form the composite `TRAN-CAT-KEY` `00000000007` + `01` +
`0001` (= `00000000007010001`). The **deterministic prefix, bytes 1–28, is
exactly**:

```
000000000070100010000005047G
```

`TRAN-CAT-BAL` is `+504.77` because the CREATE path `INITIALIZE`s the balance to
`0` and then `ADD DALYTRAN-AMT` (`+504.77`); the `G` overpunch is `+7` on the last
digit (`…4 7`), so the 11-byte `S9(09)V99` field is `0000005047G`. (Overpunch
table: master §3.4 — not restated.)

## 2. Why the CREATE branch fires (business rule — verified in `CBTRN02C.cbl`)

- `1500-VALIDATE-TRAN` passes all four checks (card in XREF → no reject 100;
  account found → no reject 101; `2065.00 ≥ 504.77` → no reject 102;
  `2024-12-13 ≥ 2022-06-10` → no reject 103), so the transaction **posts** with
  `RETURN-CODE = 0`. See the input fixtures' scenario README for the per-check
  derivation.
- `2700-UPDATE-TCATBAL` builds the key from `XREF-ACCT-ID` + `DALYTRAN-TYPE-CD` +
  `DALYTRAN-CAT-CD`, then `READ TCATBAL-FILE`. Because the paired input fixture
  `tests/fixtures/posting/zero_balance/tcatbal.txt` is **empty (0 bytes)**, the
  random `READ` on the `OPEN I-O` indexed file returns **`INVALID KEY` (file
  status 23)** → `MOVE 'Y' TO WS-CREATE-TRANCAT-REC`.
- `2700-A-CREATE-TCATBAL-REC` then `INITIALIZE`s a fresh `TRAN-CAT-BAL-RECORD`,
  moves the three key parts, `ADD DALYTRAN-AMT TO TRAN-CAT-BAL`, and **`WRITE`s a
  brand-new row** — the record captured here. (The running program even prints
  `TCATBAL record not found for key : 00000000007010001.. Creating.`)

## 3. CRITICAL — the trailing `FILLER` (bytes 29–50) is **`0x00`**, captured, not assumed

`2700-A-CREATE-TCATBAL-REC` builds the record with `INITIALIZE
TRAN-CAT-BAL-RECORD`. Per the COBOL standard, `INITIALIZE` **without `REPLACING`**
sets elementary numeric items to zero and alphanumeric items to spaces **but does
not touch `FILLER` items**, so bytes 29–50 keep whatever the record's
WORKING-STORAGE default is under this compiler — a value that is *not reliably
hand-predictable*.

**Run-and-capture result (authoritative).** Compiling the real `CBTRN02C` with
this repo's pinned flags (**GnuCOBOL 3.2.0**, `-fixed -fsign=EBCDIC
--std=ibm-strict -I app/cpy`) and running the scenario end-to-end, the emitted
`FILLER` is **22 bytes of `0x00` (`LOW-VALUES` / NUL)** — verified byte-for-byte
both through `tests/helpers/cobol_runner.py::unload_output` **and** by inspecting
the raw GnuCOBOL indexed data file on disk (the record is stored with a
`3030…4700000000…00` tail). The full captured record is:

```
hex:  30 30 30 30 30 30 30 30 30 30 37 30 31 30 30 30    "00000000007" "01" "000"
      31 30 30 30 30 30 30 35 30 34 37 47 00 00 00 00    "1" "0000005047" "G" 0x00…
      00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
      00 00 0a                                           …0x00 (22 total) + LF
```

- **WHY `0x00`, not spaces or `0`s (the key finding).** Under these flags
  GnuCOBOL initialises the un-`VALUE`d WORKING-STORAGE record area to
  `LOW-VALUES`, and `INITIALIZE` leaves the `FILLER` untouched, so the written
  bytes are `0x00`. This is **neither** the input-seed convention (master §5.6:
  the *seed* `tcatbal.txt` `FILLER` is 22 `'0'` characters) **nor** the "spaces"
  hypothesis stated as the *fallback* contract in the input fixtures' README —
  it is a third value that only the mandated **run-and-capture** could establish.
  `tests/helpers/golden_compare.py` (record-mode comment) explicitly anticipates
  exactly this: "LOW-VALUES (0x00) FILLER that GnuCOBOL emits for un-populated
  trailing fields … are preserved verbatim."
- **Trade-off / correction, not contradiction.** The input fixtures' scenario
  README wrote "created `FILLER` is spaces" as the *documented fallback for when
  `cobc` is unavailable*, to be confirmed by CI run-and-capture. That
  confirmation has now been performed and the fallback superseded: the committed
  golden carries the captured `0x00` bytes. The **create-vs-update byte
  divergence** the scenario targets still holds — and is in fact sharper: created
  `FILLER` = `0x00` versus a seeded/updated row's `'0'` characters.

## 4. File framing & how it is compared

- **File size = 51 bytes** = the 50-byte record + **one trailing `LF`** (`0x0a`,
  never `CRLF`). This is the repository's committed golden convention (`reclen+1`
  framing; see `golden_compare.py`, which lists `tcatbal=51`).
- **Comparison — record mode (correct/idiomatic).** The consuming test compares
  with `assert_matches_golden("\n".join(records), ".../tcatbal.expected",
  layout="TCATBAL")`. In record mode the comparison is **byte-exact**: the
  trailing `FILLER` (including the `0x00` bytes) is preserved verbatim, and the
  EOF-newline framing is canonicalised so the program's raw 50-byte read-back and
  this 51-byte golden compare **equal**. Verified: the real program output
  matches this golden, and a wrong seed-style row (`FILLER` = 22 `'0'`s) is
  correctly **rejected** — so the golden is exact, not loose.
  - **Assumption:** the consumer uses record mode (`layout="TCATBAL"`) for this
    fixed-width record, as shown in `cobol_runner.unload_output`'s own docstring
    example. Text mode (no `layout`) is for free-form report text and would see
    only a benign trailing-newline framing difference; it is not the intended
    path for a fixed-width record.

## 5. Provenance — how to (re)generate

1. Build: `scripts/build_test_programs.sh` (or directly
   `cobc -x -fixed -fsign=EBCDIC --std=ibm-strict -I app/cpy -o build/CBTRN02C
   app/cbl/CBTRN02C.cbl`). **`-fsign=EBCDIC` is required** — the default
   `-fsign=ASCII` misreads the zoned overpunch and would falsely reject this
   in-limit transaction as over-limit (reject 102). See build script finding
   MA-02.
2. Stage inputs into the workspace via `tests/helpers/cobol_runner.py`
   (`load_input`): `XREFFILE`/`ACCTFILE` from `cardxref.txt`/`acctdata.txt`, an
   **empty `TCATBALF`** from the 0-byte `tcatbal.txt` (forces the CREATE branch),
   and `DALYTRAN` bound to the 350-byte `dailytran.txt` record. `XREFFILE` is
   staged **primary-key-only** (`alternate_keys=()`) because `CBTRN02C`'s `SELECT`
   declares only the card-number `RECORD KEY` (avoids a `FILE STATUS 39`).
3. Run `CBTRN02C`; capture the single new `TCATBALF` row with `unload_output`.
4. Persist as `record_bytes + b"\n"`, or regenerate through
   `assert_matches_golden(actual, ".../tcatbal.expected", update=True)` under the
   opt-in `CARDDEMO_UPDATE_GOLDENS=1` protocol.
5. **Validate**: prefix (1–28) == `000000000070100010000005047G` **and** the
   `FILLER` (29–50) taken verbatim from the run (currently 22 × `0x00`).

> **CI note.** These 22 `FILLER` bytes are compiler/flag-dependent. CI must
> continue to run-and-capture; if a future GnuCOBOL release changes the WS-default
> initialisation, re-capture and update this golden (the deterministic prefix,
> bytes 1–28, must never change).

## 6. Data governance / synthetic provenance (MA-24)

The account id (`00000000007`), card (`4859452612877065`), and amount
(`+504.77`) are **synthetic, seed-derived** values from the published AWS CardDemo
sample datasets and represent **no real person or account**. This golden is
generated output only.

## 7. Sources & scope

- **Program under test (REFERENCE, never modified):** `app/cbl/CBTRN02C.cbl` —
  `2700-UPDATE-TCATBAL` → `2700-A-CREATE-TCATBAL-REC`.
- **Record layout (REFERENCE):** `app/cpy/CVTRA01Y.cpy` (single-sourced via
  `tests/helpers/record_codec.py` `TCATBAL` layout).
- **Inputs:** `tests/fixtures/posting/zero_balance/{dailytran,cardxref,acctdata}.txt`
  and the empty `tcatbal.txt`.
- **Harness:** `tests/helpers/{cobol_runner,vsam_loader,record_codec,golden_compare}.py`.
- **Consumed by (when present):** `tests/integration/test_cbtrn02c_posting.py`
  and the end-to-end posting cycle — **[planned]**.

**Minimal-change principle (mandatory, AAP §0.8.2):** all production sources under
`app/` and the seed data are REFERENCE ONLY and are never modified; this golden is
a newly generated test artifact.
