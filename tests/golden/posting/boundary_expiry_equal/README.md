# Posting golden master — boundary: transaction date == account expiration date

Byte-deterministic **expected outputs** that [`tests/helpers/golden_compare.py`](../../../helpers/golden_compare.py) diffs the compiled `app/cbl/CBTRN02C.cbl` output against, for the case where the daily transaction's origination date **equals** the account's expiration date and therefore **POSTS** with `RETURN-CODE = 0`. It is the positive / inclusive counterpart to the sibling scenario `reject_103_expired`, and the five `.expected` files here are paired 1:1 with the inputs at `tests/fixtures/posting/boundary_expiry_equal/` — the folder name is byte-identical by design so the input and output halves move in lockstep.

> **Why this README exists.** The static fixed-width `.expected` files cannot carry docstrings, so — per the Explainability rule (AAP §0.10.1) — this file is the mandated *why* for the goldens in this folder. The authoritative byte-encoding contract (widths, offsets, the zoned-decimal sign-overpunch table, and full record layouts) lives in [`tests/fixtures/README.md`](../../../fixtures/README.md) and is **not** restated here.

## 1. Business rule exercised (verbatim)

Transcribed verbatim from `CBTRN02C` paragraph `1500-B-LOOKUP-ACCT`, reached only after a successful XREF + ACCOUNT lookup and a within-limit amount:

```cobol
IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
    CONTINUE
ELSE
    MOVE 103 TO WS-VALIDATION-FAIL-REASON
    MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
      TO WS-VALIDATION-FAIL-REASON-DESC
END-IF
```

Concretely: account `7` expiry is `2024-12-13` and this scenario's `DALYTRAN-ORIG-TS` date is `2024-12-13`, so `"2024-12-13" >= "2024-12-13"` is **TRUE** → the transaction posts and reject **103** never fires. The dates are compared as `X(10)` ISO `YYYY-MM-DD` strings (the first 10 bytes of the 26-byte `ORIG-TS`). The copybook field-name misspelling **`ACCT-EXPIRAION-DATE`** (missing the second `T`) is part of the contract and preserved exactly. The other three reject branches are also *not* taken: the amount **+504.77** is within the **+2065.00** credit limit (no reject **102**), the card resolves in XREF (no reject **100**), and the account is found (no reject **101**).

## 2. Why — Explainability notes

- **Trade-off (boundary pairing vs. `reject_103_expired`).** This scenario sets the transaction date **exactly on** the expiry date to prove the `>=` test is *inclusive* (it posts); its sibling `reject_103_expired` sets the date **one day past** expiry to prove the reject-**103** branch. Together they pin the exact boundary — only the `ORIG-TS` date differs between the two, while amount and time-of-day are held constant for determinism.
- **Trade-off (timestamp normalization).** `tranfile.expected`'s `TRAN-PROC-TS` is a runtime `FUNCTION CURRENT-DATE` value in DB2 dash/dot form (`YYYY-MM-DD-HH.MM.SS.MIL0000`), so comparison uses the offset-based `layout="DALYTRAN"` mask (blanks cols 279–330 symmetrically) instead of the default ISO regex — the DB2 dash/dot format does not match that regex. A consequence is that `TRAN-ORIG-TS` is masked at compare time too; this is acceptable because the equal-to-expiry date's business effect is already proven by the successful POST (the record exists and `acctdat` / `tcatbal` were updated), and the golden still stores the literal `2024-12-13 …` bytes for audit.
- **Assumption (ISO string compare == date compare).** A lexicographic (byte) comparison of an ISO `YYYY-MM-DD` string is order-equivalent to a calendar-date comparison — true for zero-padded ISO-8601 dates, which is exactly how the program compares them (a plain COBOL `X(10)` compare).
- **Assumption (copybook contract).** Fixed-width offsets and zoned-decimal sign overpunch follow the `app/cpy/*` copybook contract exactly as documented in [`tests/fixtures/README.md`](../../../fixtures/README.md); they are not restated here.
- **Why the `2700-B-UPDATE` FILLER stays zeros.** The paired `tcatbal.txt` seeds an **existing** row for key `00000000007/01/0001`, so `2700-UPDATE-TCATBAL` takes the `2700-B-UPDATE-TCATBAL-REC` (REWRITE) branch, which updates only `TRAN-CAT-BAL` and preserves the row's 22-zero `FILLER` (it does **not** re-`INITIALIZE`).

## 3. Expected outputs in this folder

Comparison uses trailing-whitespace normalization for `acctdat` / `tcatbal` / `dalyrejs` (text mode) and the offset-based record mode `layout="DALYTRAN"` for `tranfile`.

| File | Source (ASSIGN) | Copybook / width | Expected content |
|---|---|---|---|
| `tranfile.expected` | `TRANFILE` | `CVTRA05Y` / 350 | 1 posted TRAN record; `TRAN-AMT` = `0000005047G` (+504.77); `TRAN-ORIG-TS` = `2024-12-13 19:27:53.000000`; `TRAN-PROC-TS` runtime → **normalized** via `layout="DALYTRAN"` |
| `acctdat.expected` | `ACCTFILE` | `CVACT01Y` / 300 | account 7: `ACCT-CURR-BAL` 193.00 → **697.77** (`00000006977G`); `ACCT-CURR-CYC-CREDIT` 0.00 → **504.77** (`00000005047G`); `ACCT-CURR-CYC-DEBIT` unchanged (`00000000000{`); all else identical |
| `tcatbal.expected` | `TCATBALF` | `CVTRA01Y` / 50 | key `00000000007/01/0001`; `TRAN-CAT-BAL` 0.00 → **504.77** (`0000005047G`); FILLER 22 zeros (`2700-B-UPDATE`) |
| `dalyrejs.expected` | `DALYREJS` | — / 0 bytes | **empty** — the transaction posts, no reject |
| `return_code.expected` | job RC | — | **`0`** (no rejects → RC stays 0) |
| `README.md` | — | — | this Explainability artifact |

## 4. Provenance (run-and-capture — do not hand-assemble)

These goldens are **generated**, never hand-assembled:

1. Build `CBTRN02C` with `scripts/build_test_programs.sh`.
2. Load `tests/fixtures/posting/boundary_expiry_equal/{acctdata,cardxref,tcatbal}.txt` into indexed files via `tests/helpers/load_indexed.sh`, and bind `DALYTRAN` to the sequential `dailytran.txt`.
3. Run through `tests/helpers/cobol_runner.py` using the `scripts/test_env.sh` ASSIGN bindings.
4. Capture `TRANFILE` → `tranfile.expected`, `ACCTFILE` → `acctdat.expected`, `TCATBALF` → `tcatbal.expected`, `DALYREJS` → `dalyrejs.expected` (empty), and the return code → `return_code.expected`.
5. Normalize runtime timestamps and commit via `assert_matches_golden(..., update=True)` (gated by `CARDDEMO_UPDATE_GOLDENS=1`).
6. Validate the captured bytes against the documented values (697.77 / 504.77 / equal-date post). Business-rule source: `app/cbl/CBTRN02C.cbl`.

## 5. Links

- Authoritative byte-encoding contract: [`tests/fixtures/README.md`](../../../fixtures/README.md)
- Paired input fixture folder & README: [`tests/fixtures/posting/boundary_expiry_equal/README.md`](../../../fixtures/posting/boundary_expiry_equal/README.md)
- Comparator: [`tests/helpers/golden_compare.py`](../../../helpers/golden_compare.py)

---

*Explainability artifact (AAP §0.10.1) for the five static `.expected` goldens in this folder.*
