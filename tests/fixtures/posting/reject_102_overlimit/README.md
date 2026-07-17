# Posting fixture — reject reason 102 (OVERLIMIT TRANSACTION)

These fixtures drive `app/cbl/CBTRN02C.cbl` so that a single daily transaction
**one cent over the account credit limit** is rejected with **reason 102 —
`OVERLIMIT TRANSACTION`**, pinning the program's exact `>=` credit-limit boundary.

> **Why this README exists.** The fixtures in this folder are static, fixed-width
> `.txt` files that cannot carry docstrings, so — per the project Explainability
> rule — this document is the mandated *why* for their otherwise-opaque bytes. The
> authoritative byte-encoding contract (field widths, offsets, the zoned-decimal
> sign-overpunch table, and the line-ending rules) lives in the master
> **[`tests/fixtures/README.md`](../../README.md)**. This scenario README only
> summarizes what is specific to reject 102; it **references** the master and never
> restates or contradicts it.

## Business rule exercised

Transcribed verbatim from `app/cbl/CBTRN02C.cbl` (paragraph `1500-VALIDATE-TRAN`,
after `1500-B-LOOKUP-ACCT` has resolved the account and the main post/reject loop
inspects the result):

```
COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
    (POST)
ELSE
    reject reason 102, message "OVERLIMIT TRANSACTION"
```

Plugging in this scenario's values — `ACCT-CURR-CYC-CREDIT = 0.00`,
`ACCT-CURR-CYC-DEBIT = 0.00`, `DALYTRAN-AMT = +2065.01`, and
`ACCT-CREDIT-LIMIT = +2065.00`:

- `WS-TEMP-BAL = 0.00 - 0.00 + 2065.01 = 2065.01`
- the test `2065.00 >= 2065.01` is **FALSE**, so the `ELSE` branch fires
- `2500-WRITE-REJECT-REC` writes one record to `DALYREJS` carrying reason **102** /
  `OVERLIMIT TRANSACTION`, and the program sets **`RETURN-CODE = 4`**.

**WHY the amount is exactly `+0.01` over the limit — Trade-off vs.
`boundary_exact_limit`.** The comparison is `>=`, not `>`. A balance *exactly at* the
limit therefore **posts** (the sibling `boundary_exact_limit` scenario, whose
`DALYTRAN-AMT = +2065.00`), while *one cent over* **rejects** (this scenario). The pair
pins the operator to `>=`: a "well over" amount would also reject but would not prove
that the single-cent edge is handled correctly, so the amount here is deliberately the
limit **plus exactly `0.01`** and nothing larger.

**WHY the transaction date is kept inside the account's validity window.** `CBTRN02C`
evaluates the expiration check (reason 103) *after* the over-limit check, and **both**
branches move into the *same* `WS-VALIDATION-FAIL-REASON` field — so a triggered reason
103 would overwrite reason 102. This fixture's `DALYTRAN-ORIG-TS` date (`2022-06-10`) is
earlier than the account's `ACCT-EXPIRAION-DATE` (`2024-12-13`), so the expiration check
stays on its `CONTINUE` path and **102 remains the sole, deterministic reject reason**.
(The account field uses the copybook's preserved misspelling `ACCT-EXPIRAION-DATE`; see
the master contract §5.2.)

**WHY `DALYTRAN-PROC-TS` is left blank.** `PROC-TS` (bytes 305–330) is a *runtime*
processing timestamp. Leaving it blank — 26 spaces, exactly as in the seed — keeps the
fixture free of any run-varying value so golden-master comparison stays byte-reproducible.

## Fixture files in this folder

The three INDEXED files below are loaded flat→indexed by
`tests/helpers/load_indexed.sh` (equivalently `tests/helpers/vsam_loader.load_indexed(...)`)
— the suite's `IDCAMS REPRO` analog — **before** the program runs; `dailytran.txt` is a
sequential input fed to the program directly.

| File | ASSIGN | Copybook | Width | Organization | Role |
|---|---|---|---:|---|---|
| `dailytran.txt` | `DALYTRAN` | `CVTRA06Y` | 350 | SEQUENTIAL | Driver transaction. `DALYTRAN-AMT = +2065.01` (`0000020650A`) — the **only** field changed from seed record 1. |
| `cardxref.txt` | `XREFFILE` | `CVACT03Y` | 50 | INDEXED (key 16@0) | Card `4859452612877065` → customer `000000007` → account `00000000007`, so the XREF lookup resolves (avoids reject 100). |
| `acctdata.txt` | `ACCTFILE` | `CVACT01Y` | 300 | INDEXED (key 11@0) | Account 7 seed verbatim: `ACCT-CREDIT-LIMIT = +2065.00`, cycle credit/debit `0.00` (avoids reject 101; sets the boundary). |
| `tcatbal.txt` | `TCATBALF` | `CVTRA01Y` | 50 | INDEXED (key 17@0) | Seed category-balance row for account 7 (type `01`, category `0001`) so `OPEN I-O` succeeds; never updated on the reject path. |

## Expected outcome

This is the authoritative *summary* of the expected result; the byte-exact expected
datasets are encoded in the golden mirror `tests/golden/posting/reject_102_overlimit/`
(authored separately). This README documents intent, not the golden bytes.

- **`RETURN-CODE = 4`** — the program sets RC 4 whenever at least one record is rejected.
- Exactly **1** reject record is written to `DALYREJS`.
- **0** records are written to `TRANSACT` / `TRANFILE` — posting
  (`2000-POST-TRANSACTION`) is never reached on the reject path.
- `ACCTFILE` account 7 and the `TCATBALF` row are **unchanged** — no balance or account
  update occurs when the transaction is rejected.

The single `DALYREJS` record is **430 bytes**: the 350-byte `DALYTRAN-RECORD` verbatim,
followed by the 80-byte `VALIDATION-TRAILER` = reason `9(04)` = **`0102`** immediately
followed by description `X(76)` = **`OVERLIMIT TRANSACTION`** right-space-padded to 76.

## Determinism & encoding

Every fixture here is fixed-width, LF-terminated, and encodes signed money fields with
zoned-decimal sign overpunch. `tcatbal.txt` is derived from a CRLF seed but is stored
**LF-only** (the seed's trailing carriage return is stripped) so all four files share one
line-ending convention. **For the full byte-level contract — field widths, offsets, the
overpunch table, and the LF rule — see the master
[`tests/fixtures/README.md`](../../README.md).** Those tables are single-sourced there and
are deliberately **not** reproduced here.

## Derivation & seed policy

Each fixture is derived 1:1 from the same-named ASCII seed in `app/data/ASCII/`
(`dailytran.txt`, `cardxref.txt`, `acctdata.txt`, `tcatbal.txt`): the relevant seed rows
are copied into this folder and reshaped for the scenario — here, only `DALYTRAN-AMT` is
adjusted to `+2065.01` to cross the limit by exactly one cent. The seed datasets are
**REFERENCE ONLY and are never modified** (AAP §0.10.2); all edits live in the fixture
copies under `tests/fixtures/…`.
