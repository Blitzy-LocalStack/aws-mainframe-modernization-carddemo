# posting / empty_input — CBTRN02C expected outputs (golden master)

> **Consumer test.** These goldens are read and asserted by the pytest integration test [`tests/integration/test_cbtrn02c_posting.py`](../../../integration/test_cbtrn02c_posting.py), which runs the compiled `CBTRN02C` against the paired fixtures and diffs its output against these `.expected` files via `assert_matches_golden`.

## 2. Intent

This directory holds the **expected "data-out"** for the `empty_input` posting scenario — the golden half that `tests/helpers/golden_compare.py` diffs against the actual output of the compiled `app/cbl/CBTRN02C.cbl`. It is paired **1:1** with the input fixtures at `tests/fixtures/posting/empty_input/`. The scenario proves that an **empty `DALYTRAN` daily-transaction file** drives a **clean no-op batch**: nothing is posted, nothing is rejected, `RETURN-CODE = 0`, and the program does not abend — i.e. the runner/harness survive a zero-work cycle and emit no spurious output.

> **Why this README exists.** The siblings here are static `.expected` data files that cannot carry code docstrings, so — per the Explainability rule (AAP §0.10.1) — this document is the mandated *why*. Byte-level encoding rules (widths, offsets, the zoned-decimal overpunch table, line-ending rules) are **single-sourced** in `tests/fixtures/README.md` and are **not** restated here.

## 3. Business rule (from `app/cbl/CBTRN02C.cbl`)

`CBTRN02C` opens `DALYTRAN` **INPUT**, `XREFFILE` **INPUT**, `ACCTFILE`/`TCATBALF` **I-O**, and `TRANFILE`/`DALYREJS` **OUTPUT**, then runs one read loop: `PERFORM UNTIL END-OF-FILE = 'Y'`. Each pass performs `1000-DALYTRAN-GET-NEXT`, whose `READ DALYTRAN-FILE` on a **zero-record** file returns file status `'10'`; that maps to `APPL-EOF` and sets `END-OF-FILE = 'Y'`. The **post-read guard** `IF END-OF-FILE = 'N'` is then false, so `1500-VALIDATE-TRAN`, `2000-POST-TRANSACTION`, and `2500-WRITE-REJECT-REC` **never execute**. `WS-TRANSACTION-COUNT` and `WS-REJECT-COUNT` stay `0`, so the trailing `IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE` never fires and **`RETURN-CODE` remains 0** — no soft-reject escalation, no abend.

```cobol
       1000-DALYTRAN-GET-NEXT.
           READ DALYTRAN-FILE INTO DALYTRAN-RECORD.
           IF  DALYTRAN-STATUS = '10'          *> '10' = end-of-file
               MOVE 16 TO APPL-RESULT          *> APPL-EOF (88-level VALUE 16)
           IF  APPL-EOF
               MOVE 'Y' TO END-OF-FILE         *> loop ends before validation
```

## 4. Expected-output inventory

| Golden file | Produced from | Expected content | WHY |
|---|---|---|---|
| `tranfile.expected` | `TRANSACT` (ASSIGN `TRANFILE`, `OPEN OUTPUT`) | **EMPTY (0 bytes)** | posted rows are `WRITE`n only on the `2000-POST-TRANSACTION` path — never reached |
| `dalyrejs.expected` | `DALYREJS` (`OPEN OUTPUT`) | **EMPTY (0 bytes)** | rejects are `WRITE`n only in `2500-WRITE-REJECT-REC` — no validation runs, so none |
| `acctdat.expected` | `ACCTFILE` (`OPEN I-O`) | **UNCHANGED** — byte-identical to fixture `acctdata.txt` (300-byte `ACCOUNT-RECORD`, `CVACT01Y`, +LF) | masters change only via `REWRITE` in `2800-UPDATE-ACCOUNT-REC` — never reached |
| `tcatbal.expected` | `TCATBALF` (`OPEN I-O`) | **UNCHANGED** — byte-identical to fixture `tcatbal.txt` (50-byte `TRAN-CAT-BAL-RECORD`, `CVTRA01Y`, +LF) | balances change only via `WRITE`/`REWRITE` in `2700-UPDATE-TCATBAL` — never reached |
| `return_code.expected` | process `RETURN-CODE` | literal `0` (1 byte) | `WS-REJECT-COUNT = 0`, so the `MOVE 4 TO RETURN-CODE` escalation never fires |
| `README.md` | — | this document | Explainability artifact for the static data files |

## 5. WHY every output is empty / unchanged / RC 0

- **Assumption (verified).** The paired input `dailytran.txt` is a **truly 0-byte file** (confirmed on disk), yielding a clean EOF on the *first* `READ` — not a blank line that could slip into validation. This is the precondition for the whole no-op path.
- **Trade-off.** `acctdat.expected`/`tcatbal.expected` are stored **byte-identical to their input fixtures** (full fixed-width fidelity) rather than as stripped forms, so a reviewer can `diff` golden-vs-fixture to directly confirm "master unchanged". The comparator normalizes trailing whitespace / line-endings **symmetrically** on both sides, so this fidelity choice can never cause a false mismatch.
- **Assumption.** `XREFFILE`/`ACCTFILE`/`TCATBALF` are opened regardless of `DALYTRAN` content, so the paired fixtures keep **one valid row each**; every `OPEN` succeeds and the test isolates *purely* the empty-`DALYTRAN` behaviour (no empty-index OPEN edge case leaks in).
- **Synthetic provenance (AAP §0.10.1 / fixtures §10.3).** The account and category-balance bytes in `acctdat.expected`/`tcatbal.expected` are **synthetic and seed-derived** (byte-identical to the paired fixtures) and represent **no real person or account**; the full attestation lives in the paired fixture README §8.

## 6. Determinism & normalization

These outputs are **trivially deterministic**. `tranfile.expected`/`dalyrejs.expected` are empty, so there is **no `TRAN-PROC-TS` processing timestamp to normalize** — the one non-determinism source in posting output does not arise here. `acctdat.expected`/`tcatbal.expected` are unchanged masters. Comparison through `tests/helpers/golden_compare.py` applies only **line-ending unification and trailing-whitespace normalization**, symmetrically to golden and actual. Reruns are byte-identical.

## 7. Provenance — how these goldens were produced

Derived from program execution (never hand-assembled beyond deriving the unchanged masters from the seed):

1. **Build** the program via `scripts/build_test_programs.sh` (repository convention `cobc -fixed -I app/cpy --std=ibm-strict`).
2. **Provision** the paired fixture: 0-byte `dailytran.txt`, plus `acctdata`/`cardxref`/`tcatbal` loaded into GnuCOBOL indexed files by `tests/helpers/load_indexed.sh` (its `IDCAMS REPRO` analog).
3. **Run** the program through `tests/helpers/cobol_runner.py` (binds each `SELECT ... ASSIGN` name to the workspace file via an environment variable).
4. **Capture** `TRANFILE` (empty), `ACCTFILE`→`acctdat` (unchanged), `TCATBALF`→`tcatbal` (unchanged), `DALYREJS` (empty), and `RETURN-CODE` (`0`).
5. **Normalize & commit** via `assert_matches_golden(..., update=True)` with `CARDDEMO_UPDATE_GOLDENS=1` (the two-signal opt-in the comparator requires before it will (re)write a golden).
6. **Validate**: `tranfile`/`dalyrejs` empty, `RETURN-CODE` 0, masters byte-identical to the fixtures, no abend.

## 8. Cross-references

- **Encoding / record-layout contract (authoritative):** [`tests/fixtures/README.md`](../../../fixtures/README.md) — widths, zoned-decimal overpunch, LF rules. *Not restated here.*
- **Paired input scenario:** [`tests/fixtures/posting/empty_input/README.md`](../../../fixtures/posting/empty_input/README.md) and its fixtures.
- **Comparator:** [`tests/helpers/golden_compare.py`](../../../helpers/golden_compare.py).
- **Program under test:** [`app/cbl/CBTRN02C.cbl`](../../../../app/cbl/CBTRN02C.cbl) — **REFERENCE ONLY, never modified** (AAP §0.8.2/§0.10.2).
