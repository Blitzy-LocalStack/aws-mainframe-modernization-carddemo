# posting / empty_input — CBTRN02C empty daily-transaction fixture

## 1. Intent

An **empty `DALYTRAN`** input drives `app/cbl/CBTRN02C.cbl` straight to **immediate
EOF** on its first read, so **no transaction is posted, none is rejected,
`RETURN-CODE` stays `0`, and the program does not abend**. This scenario pins the
program's *"no-work" happy path*: the surrounding cross-reference, account, and
category-balance datasets are present and valid (so every `OPEN` succeeds) but are
never looked up, because there is no transaction to drive a lookup.

> **Why this README exists.** The fixtures here are static fixed-width `.txt` files
> that cannot carry docstrings, so — per the Explainability rule (AAP §0.10.1) — this
> document is the mandated *why*. The authoritative byte-encoding contract (widths,
> offsets, the zoned-decimal overpunch table, line-ending rules) and the general
> `empty_input` semantics (§7) live in the master
> **[`tests/fixtures/README.md`](../../README.md)**; this file **references** that
> contract and never restates or contradicts it.

## 2. Business rule exercised (verbatim from `app/cbl/CBTRN02C.cbl`)

`CBTRN02C` opens `DALYTRAN` **INPUT**, `XREFFILE` **INPUT**, `ACCTFILE` and `TCATBALF`
**I-O**, and `TRANFILE` and `DALYREJS` **OUTPUT**, then runs a single read loop
(`PERFORM UNTIL END-OF-FILE = 'Y'`). On an empty `DALYTRAN` the first `READ`
(`1000-DALYTRAN-GET-NEXT`) returns file status **`'10'`** (end-of-file), which sets
the `APPL-EOF` flag and ends the loop **before** `1500-VALIDATE-TRAN` (validation,
posting, and reject logic) is ever performed. `WS-REJECT-COUNT` therefore stays `0`,
so the trailing `IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE` never fires and
`RETURN-CODE` remains `0`:

```cobol
       1000-DALYTRAN-GET-NEXT.
           READ DALYTRAN-FILE INTO DALYTRAN-RECORD.
           IF  DALYTRAN-STATUS = '00'
               MOVE 0 TO APPL-RESULT
           ELSE
               IF  DALYTRAN-STATUS = '10'
                   MOVE 16 TO APPL-RESULT
               ELSE
                   MOVE 12 TO APPL-RESULT
               END-IF
           END-IF
           IF  APPL-AOK
               CONTINUE
           ELSE
               IF  APPL-EOF
                   MOVE 'Y' TO END-OF-FILE
```

(`88 APPL-EOF VALUE 16` — a `'10'` status maps to `APPL-EOF`, so a `'10'` on the very
first read exits the loop with zero records processed.)

## 3. Fixture inventory

| File | ASSIGN | Copybook / RECLN | Organization | Role |
|---|---|---|---|---|
| `dailytran.txt` | `DALYTRAN` | `CVTRA06Y` / 350 | SEQUENTIAL | **The input under test — a truly empty 0-byte file** |
| `cardxref.txt` | `XREFFILE` | `CVACT03Y` / 50 | INDEXED (key 16 @ 0) | 1 row; present so `OPEN INPUT` succeeds (never read) |
| `acctdata.txt` | `ACCTFILE` | `CVACT01Y` / 300 | INDEXED (key 11 @ 0) | 1 row (account 7 seed); present so `OPEN I-O` succeeds (never read/updated) |
| `tcatbal.txt` | `TCATBALF` | `CVTRA01Y` / 50 | INDEXED (key 17 @ 0) | 1 seed row; present so `OPEN I-O` succeeds (never read/updated) |

The three populated fixtures form one coherent account-`7` chain: card
`4859452612877065` → account `00000000007` (`cardxref`), account `00000000007`
(`acctdata`), and category row `00000000007 / 01 / 0001` with `TRAN-CAT-BAL +0.00`
(`tcatbal`).

## 4. Expected outcome

Authoritative for the golden mirror `tests/golden/posting/empty_input/` (owned by the
golden agent) and for the integration asserts:

- **`RETURN-CODE = 0`**.
- **0** records written to `TRANSACT`.
- **0** records written to `DALYREJS`.
- `ACCOUNT` (account `7`) and `TCATBAL` are **byte-identical to the inputs**
  (unchanged — no update path runs).
- **No abend** and no error diagnostics.

## 5. Assumptions & Trade-offs (WHY — mandatory per AAP §0.10.1)

- **Assumption — 0-byte, not a blank line.** `dailytran.txt` is a **genuinely 0-byte
  file** so the first `READ` yields a clean EOF (`'10'`). *WHY 0-byte and not a blank
  line:* a blank line is a zero-length "record" that would be parsed as a malformed
  short record and could enter validation, whereas a truly empty file gives the
  clean-EOF no-work path this scenario is meant to exercise (master §3.1, §7).
- **Trade-off — `tcatbal.txt` normalized to LF.** `tcatbal.txt` is written **LF-only**
  even though its ASCII seed (`app/data/ASCII/tcatbal.txt`) is **CRLF**; the trailing
  `\r` is stripped so each record is exactly the copybook width (50 B) for the
  flat→indexed loader. *WHY:* a stray `\r` absorbed into the trailing `FILLER` would
  push the record one byte over `RECLN` and corrupt the fixed-width record (master §3.2).
- **Assumption — indexes kept populated.** `XREF`/`ACCOUNT`/`TCATBAL` each keep one
  valid row because `CBTRN02C` opens them regardless of `DALYTRAN` content; a non-empty
  valid index avoids empty-index `OPEN` edge cases and isolates purely the
  empty-`DALYTRAN` behavior (a missing master would be a different, error-path scenario).
- **Assumption — layout matches the copybooks.** Fixed-width offsets and zoned-decimal
  sign overpunch match the `app/cpy/` copybook contract — see
  [`tests/fixtures/README.md`](../../README.md) (§3, §5); this file does not restate them.

## 6. Derivation & determinism

The three populated fixtures are derived from the seeds
`app/data/ASCII/{cardxref,acctdata,tcatbal}.txt` (seeds are **REFERENCE-only and never
edited**, AAP §0.8.2); `dailytran.txt` is intentionally empty. The rule source is
`app/cbl/CBTRN02C.cbl`. All non-empty fixtures are LF-terminated with a **single**
trailing newline, and these records contain **no runtime timestamps**, so every run is
byte-deterministic and the `RC = 0` + empty-output golden holds reproducibly.

## 7. Cross-reference

- **Master contract:** [`tests/fixtures/README.md`](../../README.md) — the byte-level
  encoding rules, record layouts, and general `empty_input` semantics this file defers
  to.
- **Golden mirror:** `tests/golden/posting/empty_input/` — the parallel expected-output
  tree (owned by the golden agent; **not** created in this folder).
- At run time the indexed fixtures (`cardxref`, `acctdata`, `tcatbal`) are loaded
  flat → indexed by `tests/helpers/load_indexed.sh` / `tests/helpers/vsam_loader.py`
  (an `IDCAMS REPRO` analog); `dailytran.txt` is sequential and is consumed as-is.

## 8. Data governance / synthetic provenance (MA-24)

Card `4859452612877065` and account `00000000007` in these fixtures are **synthetic,
seed-derived** test data copied from the published AWS CardDemo sample datasets
`app/data/ASCII/{cardxref,acctdata,tcatbal}.txt`, and represent **no real person or
account**. `dailytran.txt` is empty and carries no data at all. See master
[`tests/fixtures/README.md`](../../README.md) §10 for the full attestation.

> **WHY recorded even here (compliance completeness).** A PAN that passes a Luhn check
> is indistinguishable, by inspection, from a live card number, so a financial-grade
> suite must *attest* provenance per scenario rather than leave a reviewer to infer it.

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the static
fixtures in this folder.*
