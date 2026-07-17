# Posting fixture — boundary: transaction date == account expiration date

Deterministic input fixtures that drive the compiled `app/cbl/CBTRN02C.cbl` where the
transaction origination date **equals** the account expiration date and therefore must
**POST** (it is *not* rejected). This is the positive counterpart to the sibling
scenario `reject_103_expired`.

> **Why this README exists.** The static fixed-width `.txt` fixtures cannot carry
> docstrings, so — per the Explainability rule (AAP §0.10.1) — this file is the mandated
> *why* for the four fixtures in this folder. The authoritative byte-encoding contract
> (widths, offsets, the zoned-decimal sign-overpunch table, and full record layouts)
> lives in the master **[`tests/fixtures/README.md`](../../README.md)** and is **not**
> restated here.

## 1. Business rule exercised (verbatim)

Transcribed verbatim from `CBTRN02C` paragraph `1500-B-LOOKUP-ACCT`, reached after a
successful XREF + ACCOUNT lookup and a within-limit amount:

```cobol
IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
    CONTINUE
ELSE
    MOVE 103 TO WS-VALIDATION-FAIL-REASON
    MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
      TO WS-VALIDATION-FAIL-REASON-DESC
END-IF
```

The dates are compared as `X(10)` ISO `YYYY-MM-DD` strings — `ACCT-EXPIRAION-DATE`
against the first 10 bytes of the 26-byte `DALYTRAN-ORIG-TS`. With this scenario's
values (account `7` expiry `2024-12-13` vs. `ORIG-TS` date `2024-12-13`), the compare
`"2024-12-13" >= "2024-12-13"` is **TRUE**, so the transaction **posts** and reject
**103** never fires. The copybook field-name misspelling **`ACCT-EXPIRAION-DATE`**
(missing the second `T` of "EXPIRATION") is part of the contract and preserved exactly.

## 2. Why — Explainability notes

- **Trade-off (boundary pairing vs. `reject_103_expired`).** This scenario sets the
  transaction date **exactly on** the expiry date to prove the `>=` test is *inclusive*
  (it posts); its sibling `reject_103_expired` sets the date **one day past** expiry to
  prove the reject-**103** branch. Together the two pin down the exact boundary. Only the
  `ORIG-TS` date differs between them; amount and time-of-day are held constant for
  determinism (a date "well before" expiry would also post, but would not prove the
  single-day edge is handled correctly).
- **Assumption (ISO string compare == date compare).** A lexicographic (byte) comparison
  of an ISO `YYYY-MM-DD` string is order-equivalent to a calendar-date comparison — true
  for zero-padded ISO-8601 dates, which is exactly how the program compares them (a plain
  COBOL `X(10)` compare). This is *why* a static byte/string fixture faithfully exercises
  the date rule without any date arithmetic.
- **Assumption (copybook contract).** Fixed-width offsets and zoned-decimal sign
  overpunch follow the `app/cpy/*` copybook contract exactly as documented in
  [`../../README.md`](../../README.md); they are not restated here.

## 3. Fixture files in this folder

| File | ASSIGN | Copybook | Width | Org | Role |
|---|---|---|---|---|---|
| `dailytran.txt` | `DALYTRAN` | `CVTRA06Y` | 350 | SEQUENTIAL | 1 daily tran: card `4859452612877065`, amount **+504.77** (`0000005047G`), `ORIG-TS` date **`2024-12-13`** |
| `cardxref.txt` | `XREFFILE` | `CVACT03Y` | 50 | INDEXED (key 16@0) | card `4859452612877065` → cust `000000007` → **acct `00000000007`** |
| `acctdata.txt` | `ACCTFILE` | `CVACT01Y` | 300 | INDEXED (key 11@0) | account 7: expiry **`2024-12-13`**, credit limit +2065.00, balance +193.00, cycle fields 0.00 |
| `tcatbal.txt` | `TCATBALF` | `CVTRA01Y` | 50 | INDEXED (key 17@0) | seed row acct7/`01`/`0001`, balance 0.00 → forces the `2700-B-UPDATE` (rewrite) path |

All fixtures are LF-terminated with a single trailing newline; `tcatbal.txt` mirrors a
CRLF seed but is stored LF (see [`../../README.md`](../../README.md)). `DALYTRAN-PROC-TS`
is left blank (26 spaces) so the input is deterministic. At run time the three indexed
fixtures (`cardxref`, `acctdata`, `tcatbal`) are loaded flat → indexed by
`tests/helpers/load_indexed.sh` (an `IDCAMS REPRO` analog) before `CBTRN02C` runs;
`dailytran.txt` is sequential and consumed as-is.

## 4. Expected outcome (informational — asserted by the golden mirror, not here)

The expected "data-out" is authored separately under
`tests/golden/posting/boundary_expiry_equal/`; the values below state intent:

- `RETURN-CODE = 0`; **1** record written to `TRANSACT`; `DALYREJS` empty.
- `TCATBAL` row `00000000007/01/0001`: 0.00 → **504.77** (`0000005047G`).
- Account 7: `ACCT-CURR-BAL` 193.00 → **697.77** (`00000006977G`);
  `ACCT-CURR-CYC-CREDIT` 0.00 → **504.77** (`00000005047G`);
  `ACCT-CURR-CYC-DEBIT` unchanged.
- **Determinism caveat:** the emitted `TRANSACT` `TRAN-PROC-TS` is a runtime timestamp
  and must be normalized before golden comparison.

## 5. Derivation & provenance

All four fixtures are **derived** from the shipped seeds
`app/data/ASCII/{dailytran,cardxref,acctdata,tcatbal}.txt`, which are **REFERENCE-ONLY
and never modified** (AAP §0.8.2):

- `dailytran.txt` = seed record 1 with only the `ORIG-TS` **date** changed to `2024-12-13`.
- `acctdata.txt` = seed account 7, unmodified.
- `cardxref.txt` = seed card-7 → acct-7 row, padded to 50 bytes.
- `tcatbal.txt` = seed account-7 category row (CRLF → LF).

Business-rule source: `app/cbl/CBTRN02C.cbl`. Byte-encoding contract:
[`../../README.md`](../../README.md). The card, account, and customer identifiers are
synthetic, seed-derived demonstration values representing no real person or account
(full attestation: master `README` §10).

---

*Explainability artifact (AAP §0.10.1) for the four static `.txt` fixtures in this folder.*
