# Golden Master — Posting Scenario: `boundary_exact_limit`

> **Consumer test.** These goldens are read and asserted by the pytest integration test [`tests/integration/test_cbtrn02c_posting.py`](../../../integration/test_cbtrn02c_posting.py), which runs the compiled `CBTRN02C` against the paired fixtures and diffs its output against these `.expected` files via `assert_matches_golden`.

These are the **byte-deterministic expected outputs** that
[`tests/helpers/golden_compare.py`](../../../helpers/golden_compare.py)
(`assert_matches_golden`) diffs the compiled `app/cbl/CBTRN02C.cbl` output against.
They are paired **1:1, in lockstep** with the input fixtures at
[`../../../fixtures/posting/boundary_exact_limit/README.md`](../../../fixtures/posting/boundary_exact_limit/README.md)
(the folder name is byte-identical on both sides, which is how the comparator maps
inputs → expected outputs).

> **Path-mirroring & Explainability contract.** Per the Explainability mandate
> (AAP §0.10.1), this README is the mandated *why* for the otherwise-opaque
> `.expected` bytes in this folder, because a static fixed-width data file cannot
> carry a docstring. The **byte-encoding contract** — field widths, offsets, the
> zoned-decimal sign-overpunch table, ISO `X(10)` dates, and the exact fixed-point
> money rule (`decimal.Decimal`, **no** float tolerance) — lives in the master
> [`../../../fixtures/README.md`](../../../fixtures/README.md) (§3.4, §5); this
> document **references** it and never restates or contradicts it.
>
> **Program under test is REFERENCE-only.** `app/cbl/CBTRN02C.cbl`, the copybooks
> under `app/cpy/`, and the seeds under `app/data/` are the rule source and are
> **never modified** (AAP §0.8.2, §0.10.2). This folder is **data-only** — no code.
> If a fresh run disagrees with these goldens, the fixtures or the load step are
> wrong — not the program, and not these files.

## 1. Scenario intent

This scenario exercises the **positive (inclusive) side of the credit-limit
boundary**: a transaction whose amount makes the account's running balance land
**exactly on** the credit limit. Because `CBTRN02C` uses `>=` (not `>`), that
transaction **must POST**, not reject.

It is the deliberate **twin** of the sibling scenario
[`reject_102_overlimit`](../reject_102_overlimit/) — the *exclusive* side, one cent
over the limit (`+2065.01`), which rejects with reason **102**. Documenting both
sides pins the operator down to **`>=` (inclusive to the cent)**, the
financial-correctness edge this pair exists to lock in.

## 2. Business rule exercised — `CBTRN02C`, paragraph `1500-B-LOOKUP-ACCT`

Transcribed verbatim from `app/cbl/CBTRN02C.cbl` (the over-limit test whose result
the main post/reject loop then acts on):

```cobol
COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
                    - ACCT-CURR-CYC-DEBIT
                    + DALYTRAN-AMT

IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
  CONTINUE
ELSE
  MOVE 102 TO WS-VALIDATION-FAIL-REASON
  MOVE 'OVERLIMIT TRANSACTION'
    TO WS-VALIDATION-FAIL-REASON-DESC
END-IF
IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
  CONTINUE
ELSE
  MOVE 103 TO WS-VALIDATION-FAIL-REASON
  MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
    TO WS-VALIDATION-FAIL-REASON-DESC
END-IF
```

On a clean validation, the loop performs `2000-POST-TRANSACTION`, which copies
`DALYTRAN-*` → `TRAN-*` (including `DALYTRAN-ORIG-TS` → `TRAN-ORIG-TS`), stamps
`TRAN-PROC-TS` from `Z-GET-DB2-FORMAT-TIMESTAMP`, and then performs
`2700-UPDATE-TCATBAL`, `2800-UPDATE-ACCOUNT-REC`, and `2900-WRITE-TRANSACTION-FILE`.

> The copybook `CVACT01Y` preserves the original misspelling `ACCT-EXPIRAION-DATE`;
> it is reproduced here verbatim so the citation matches the source byte-for-byte.
> The reject-code semantics (100–103) and the inclusive `>=` limit test belong to
> the specification and are asserted, not redefined, by this scenario. (The interest
> formula `(TRAN-CAT-BAL × rate) / 1200` is a *different* program, `CBACT04C`, and is
> **not** exercised here.)

## 3. Paired fixture facts (account `7`)

The input side is authoritative in
[`../../../fixtures/posting/boundary_exact_limit/README.md`](../../../fixtures/posting/boundary_exact_limit/README.md);
the values that drive these goldens are:

| Fixture field | Value | Encoded |
|---|---|---|
| `ACCT-CREDIT-LIMIT` | `+2065.00` | `00000020650{` |
| `ACCT-CURR-BAL` | `+193.00` | `00000001930{` |
| `ACCT-CURR-CYC-CREDIT` | `0.00` | `00000000000{` |
| `ACCT-CURR-CYC-DEBIT` | `0.00` | `00000000000{` |
| `ACCT-EXPIRAION-DATE` | `2024-12-13` (non-expired) | — |
| `DALYTRAN-AMT` | `+2065.00` | `0000020650{` |
| `DALYTRAN-ORIG-TS` | `2022-06-10 19:27:53.000000` | — |
| Pre-seeded `TCATBAL` row `00000000007`/`01`/`0001` | `+0.00` | `0000000000{`, FILLER = 22 zeros |

The card (`4859452612877065`) resolves to account `7` through `cardxref.txt`, so
there is no reject 100; the account exists, so there is no reject 101.

## 4. Why it posts (the arithmetic)

```
WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
            = 0.00 - 0.00 + 2065.00
            = 2065.00
```

- `ACCT-CREDIT-LIMIT >= WS-TEMP-BAL` ⇒ `2065.00 >= 2065.00` is **TRUE → POST**;
  `RETURN-CODE = 0`.
- The expiration guard also passes: `ACCT-EXPIRAION-DATE (2024-12-13) >=
  DALYTRAN-ORIG-TS(1:10) (2022-06-10)` is TRUE, so there is no reject 103.

**WHY `+2065.00` *exactly* (Assumption / Trade-off).** This is the single amount
that makes `WS-TEMP-BAL` equal the credit limit to the cent, isolating the inclusive
`>=` edge from every other behavior. The seed cycle balances are deliberately zero
(`ACCT-CURR-CYC-CREDIT = ACCT-CURR-CYC-DEBIT = 0.00`), so `WS-TEMP-BAL` equals the
transaction amount outright — the cleanest way to land the running balance on the
limit. Its exclusive twin [`reject_102_overlimit`](../reject_102_overlimit/) uses
`+2065.01` (`0000020650A`; the overpunch `A` = `+1` on the final digit — see the
master contract §3.4), where `WS-TEMP-BAL = 2065.01 > 2065.00 →` reject 102. A
"well under" amount would also post but would never prove the single-cent edge, so
the amount here is the limit **exactly** and nothing less.

## 5. Expected outputs (all five files, exact bytes)

Each `.expected` file is what a correct run must reproduce after normalization
(§6). Byte columns are 1-indexed; widths and offsets are single-sourced in the
master contract §5 and are **not** restated here.

### `tranfile.expected` — `TRANFILE` posted transaction (`CVTRA05Y`, 350 B + trailing `LF`)

The daily-transaction record copied field-by-field into `TRAN-*` by
`2000-POST-TRANSACTION`:

| Field | Cols | Bytes |
|---|---|---|
| `TRAN-ID` | 1–16 | `0000000000683580` |
| `TRAN-TYPE-CD` | 17–18 | `01` |
| `TRAN-CAT-CD` | 19–22 | `0001` |
| `TRAN-SOURCE` | 23–32 | `POS TERM` + 2 trailing spaces |
| `TRAN-DESC` | 33–132 | `Purchase at Abshire-Lowe` + trailing spaces |
| `TRAN-AMT` | 133–143 | `0000020650{` (**+2065.00**) |
| `TRAN-MERCHANT-ID` | 144–152 | `800000000` |
| `TRAN-MERCHANT-NAME` | 153–202 | `Abshire-Lowe` + trailing spaces |
| `TRAN-MERCHANT-CITY` | 203–252 | `North Enoshaven` + trailing spaces |
| `TRAN-MERCHANT-ZIP` | 253–262 | `72112` + 5 spaces |
| `TRAN-CARD-NUM` | 263–278 | `4859452612877065` |
| `TRAN-ORIG-TS` | 279–304 | `2022-06-10 19:27:53.000000` (deterministic passthrough — **preserved & asserted**) |
| `TRAN-PROC-TS` | 305–330 | **26 spaces** — the run-generated stamp, masked in place (§6) |
| `FILLER` | 331–350 | **20 bytes of binary zeros** — low-values, hex `0x00` (**not** spaces; see §7) |

### `acctdat.expected` — `ACCTFILE` account 7 after `2800-UPDATE-ACCOUNT-REC` (`CVACT01Y`, 300 B + `LF`)

| Field | Cols | Bytes | Note |
|---|---|---|---|
| `ACCT-ID` | 1–11 | `00000000007` | key, unchanged |
| `ACCT-ACTIVE-STATUS` | 12 | `Y` | unchanged |
| `ACCT-CURR-BAL` | 13–24 | `00000022580{` (**+2258.00**) | `193.00 + 2065.00` |
| `ACCT-CREDIT-LIMIT` | 25–36 | `00000020650{` (+2065.00) | unchanged |
| `ACCT-CASH-CREDIT-LIMIT` | 37–48 | `00000002640{` (+264.00) | unchanged |
| `ACCT-OPEN-DATE` | 49–58 | `2012-10-12` | unchanged |
| `ACCT-EXPIRAION-DATE` | 59–68 | `2024-12-13` | unchanged |
| `ACCT-REISSUE-DATE` | 69–78 | `2024-12-13` | unchanged |
| `ACCT-CURR-CYC-CREDIT` | 79–90 | `00000020650{` (**+2065.00**) | `0.00 → 2065.00` (amount ≥ 0) |
| `ACCT-CURR-CYC-DEBIT` | 91–102 | `00000000000{` (+0.00) | **unchanged** (amount ≥ 0) |
| `ACCT-ADDR-ZIP` | 103–112 | `A000000000` | unchanged |
| `ACCT-GROUP-ID` | 113–122 | 10 spaces | unchanged |
| `FILLER` | 123–300 | 178 spaces | unchanged |

### `tcatbal.expected` — `TCATBALF` category-balance row after `2700-B-UPDATE-TCATBAL-REC` (`CVTRA01Y`, 50 B + `LF`)

| Field | Cols | Bytes | Note |
|---|---|---|---|
| key (`TRANCAT-ACCT-ID`+`TRANCAT-TYPE-CD`+`TRANCAT-CD`) | 1–17 | `00000000007010001` | unchanged |
| `TRAN-CAT-BAL` | 18–28 | `0000020650{` (**+2065.00**) | `0.00 → 2065.00` |
| `FILLER` | 29–50 | **22 ASCII `0` characters** (zeros) | preserved from the seed row (see §7) |

### `dalyrejs.expected` — `DALYREJS` reject stream

**Empty (0 bytes).** The transaction posts, so nothing is written to the reject
stream.

### `return_code.expected` — process `RETURN-CODE`

The single byte `0` (no trailing newline). A clean post leaves `RETURN-CODE = 0`
(no soft reject, no abend).

## 6. Timestamp normalization (determinism)

`tranfile.expected` is compared in **record mode** with `layout="DALYTRAN"` — the
350-byte transaction layout, byte-for-byte identical in width and field offsets to
the `TRAN` / `CVTRA05Y` layout — so the comparator can mask the one run-varying
field in place:

- **Only `TRAN-PROC-TS` (cols 305–330) is blanked**, symmetrically on the actual
  output and on this golden, so it never participates in the diff. Everything else
  — cols 1–304 (including `TRAN-ORIG-TS`) **and** cols 331–350 — is asserted
  byte-for-byte.
- **`TRAN-ORIG-TS` (cols 279–304) is *preserved and asserted*.** It is the
  deterministic originating timestamp copied straight from the driver transaction
  (`2022-06-10 19:27:53.000000`), i.e. business data the golden must verify — **not**
  a run-varying value. This is why the stored golden carries a real `ORIG-TS` but a
  blanked `PROC-TS`. (The masked-field policy is single-sourced in
  `tests/helpers/record_codec.py`: in the `DALYTRAN`/`TRAN` layouts only `PROC-TS`
  is flagged `normalize_ts`; `ORIG-TS` is not.)
- `acctdat.expected`, `tcatbal.expected`, `dalyrejs.expected`, and
  `return_code.expected` contain **no** run-generated timestamp field, so they need
  no in-place masking; their comparison is deterministic (byte-for-byte, modulo the
  symmetric trailing-whitespace / line-ending normalization the comparator applies
  to both sides).

**Trade-off — fixed-offset byte-range blanking over an ISO-timestamp regex.**
`Z-GET-DB2-FORMAT-TIMESTAMP` emits the DB2 timestamp format
`YYYY-MM-DD-HH.MM.SS.NN0000` (a dash between date and time, dots inside the time),
which a conventional ISO regex (`YYYY-MM-DD HH:MM:SS…`) would **not** match. Blanking
`PROC-TS` by its layout offset is therefore both necessary and more robust than a
regex substitution; the offsets live beside the layout in `record_codec.py` so the
comparator and the fixtures stay single-sourced.

## 7. The `2700-B-UPDATE` branch, and WHY two FILLERs differ

**`tcatbal` FILLER stays 22 zeros — because this is the UPDATE branch.** The
pre-seeded `TCATBAL` row for `00000000007`/`01`/`0001` is *found* on READ, so
`2700-UPDATE-TCATBAL` takes **`2700-B-UPDATE-TCATBAL-REC`**:

```cobol
ADD DALYTRAN-AMT TO TRAN-CAT-BAL
REWRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD
```

Only the balance field changes; the 22-byte `FILLER` is rewritten exactly as it was
read, and the seed row carries **22 zeros** — so the golden shows 22 zeros.

**Alternatives Considered — the CREATE branch would show 22 *spaces*.** When the key
is *absent* (e.g. the `zero_balance` scenario), posting instead takes
`2700-A-CREATE-TCATBAL-REC`, which begins with `INITIALIZE TRAN-CAT-BAL-RECORD`.
`INITIALIZE` sets the alphanumeric `FILLER` to **spaces**, so a CREATE golden would
show 22 spaces. Documenting the contrast is exactly why the `TCATBAL` FILLER encoding
legitimately differs between scenarios — it is a signal of which branch ran, not a
defect.

**`tranfile` FILLER is 20 binary zeros — because it is never populated.**
`2000-POST-TRANSACTION` MOVEs only the named `TRAN-*` fields into the working-storage
`TRAN-RECORD` (`COPY CVTRA05Y`); the trailing `FILLER PIC X(20)` is never assigned,
so it keeps GnuCOBOL's default working-storage initialization — **low-values
(`0x00`)** — and `2900-WRITE-TRANSACTION-FILE` writes it verbatim
(`WRITE FD-TRANFILE-REC FROM TRAN-RECORD`). Because `FILLER` is **not** a
`normalize_ts` field, the comparator preserves those bytes exactly; the value is
stable across runs, so the byte-exact assertion is deterministic.

> **Assumption.** All fixed-width offsets and the zoned-decimal sign-overpunch used
> above match the `app/cpy/` copybook contract as tabulated in the master
> [`../../../fixtures/README.md`](../../../fixtures/README.md) §3.4 and §5; this
> document relies on that contract rather than re-deriving it.

## 8. How these goldens were produced (run-and-capture provenance)

The `.expected` bytes are **captured from a real run** and blessed through the same
comparator that later reads them — they are **not** hand-assembled:

1. **Build** `CBTRN02C` via `scripts/build_test_programs.sh`
   (`cobc -x -fixed -I app/cpy --std=ibm-strict`).
2. **Load** the paired fixtures into GnuCOBOL indexed files via
   `tests/helpers/load_indexed.sh` — `ACCTFILE` (key 11 @ 0), `XREFFILE`
   (key 16 @ 0), `TCATBALF` (key 17 @ 0) — and bind the sequential `DALYTRAN` input
   to the fixture `dailytran.txt`.
3. **Run** the program through `tests/helpers/cobol_runner.py` with the ASSIGN-name
   environment bindings (`DALYTRAN`, `XREFFILE`, `ACCTFILE`, `TCATBALF`, `TRANFILE`,
   `DALYREJS`).
4. **Capture** the outputs: `TRANFILE` → `tranfile.expected`, `ACCTFILE` →
   `acctdat.expected`, `TCATBALF` → `tcatbal.expected`, `DALYREJS` →
   `dalyrejs.expected` (empty), and the process return code → `return_code.expected`
   (`0`).
5. **Bless** the goldens through the comparator's guarded update path —
   `assert_matches_golden(..., update=True)` with `CARDDEMO_UPDATE_GOLDENS=1`
   (**both** signals are required) — which normalizes the run-generated `PROC-TS`
   and writes the captured bytes here.
6. **Validate** the captured bytes against the documented values above (post at the
   exact limit; `ACCT-CURR-BAL 2258.00`; `ACCT-CURR-CYC-CREDIT 2065.00`;
   `TRAN-CAT-BAL 2065.00`; empty rejects; `RETURN-CODE 0`) — they **must** match.

If a fresh run yields anything other than the above, the fixtures or the load step
are wrong — **not** these goldens, and **not** the REFERENCE-only program.

## 9. Pairing & seed policy

- These goldens pair **1:1 and in lockstep** (byte-identical folder name) with the
  input fixtures at
  [`../../../fixtures/posting/boundary_exact_limit/README.md`](../../../fixtures/posting/boundary_exact_limit/README.md);
  that README is authoritative for the *input-side* rationale.
- The single-cent boundary is co-defined with the twin
  [`reject_102_overlimit`](../reject_102_overlimit/) (exactly-at-limit **posts**;
  one-cent-over **rejects**); editing either amount breaks a deliberate,
  money-critical boundary assertion.
- The underlying seeds in `app/data/ASCII/` are **REFERENCE-only and never
  modified** (AAP §0.10.2); the fixtures are derived copies. The master
  byte-encoding contract is [`../../../fixtures/README.md`](../../../fixtures/README.md).

## 10. Sources & scope

- **Rule source:** `app/cbl/CBTRN02C.cbl` — paragraphs `1500-B-LOOKUP-ACCT`
  (over-limit / expiration tests), `2000-POST-TRANSACTION`, `2700-UPDATE-TCATBAL`
  (`2700-A-CREATE` / `2700-B-UPDATE`), `2800-UPDATE-ACCOUNT-REC`,
  `2900-WRITE-TRANSACTION-FILE`, `Z-GET-DB2-FORMAT-TIMESTAMP` (REFERENCE-only, never
  modified).
- **Encoding contract:** [`../../../fixtures/README.md`](../../../fixtures/README.md)
  (widths, offsets, overpunch table §3.4, layout tables §5, LF rule).
- **Paired inputs:**
  [`../../../fixtures/posting/boundary_exact_limit/README.md`](../../../fixtures/posting/boundary_exact_limit/README.md).
- **Comparator:** [`tests/helpers/golden_compare.py`](../../../helpers/golden_compare.py)
  (`assert_matches_golden`, record mode, `layout="DALYTRAN"`); masked-field policy in
  `tests/helpers/record_codec.py`.

---

*Mandatory Explainability artifact (AAP §0.10.1) for the five `.expected` golden
files in this folder. Production sources under `app/` and seeds under `app/data/`
are REFERENCE ONLY and are never modified (AAP §0.8.2, §0.10.2).*
