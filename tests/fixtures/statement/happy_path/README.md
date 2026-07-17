# Statement — Happy-Path Scenario (`CBSTM03A` / `CBSTM03B`)

These fixtures drive the statement generator over **one fully linked customer /
account / card** with **three matched transactions**, so the program produces a
complete statement listing every transaction for the account holder. This is the
reference "transactions in → statement out" scenario for the statement domain.

> **Read [`tests/fixtures/README.md`](../../README.md) first** for the byte-level
> encoding contract and the availability status of the `tests/golden/**` /
> `tests/integration/**` trees. This README does not restate that contract.

## 1. Intent

A single customer (`000000050`) and account (`00000000050`), reachable through the
cross-reference card `0500024453765740`, own **three** transactions. The statement
program reads the transactions, matches each to the account via the cross-reference,
and emits a statement (plain-text, and the program's HTML form) that lists all three
and reflects their net effect.

- **Net of the three amounts:** `+183.88 + 14.00 - 47.88 = +150.00`.

> **WHY three transactions incl. a credit (Assumption / coverage).** The set mixes
> two debits (`TYPE 01`) and one credit (`TYPE 03`, `-47.88`) so the statement
> exercises both signs and a realistic multi-line body — not just a single-line
> degenerate case. The credit uses the negative zoned-overpunch encoding (master
> §3.4), so it also verifies signed-field handling on the statement path.

## 2. Fixture files in this folder

| File | Copybook / layout | `RECLN` | Line ending | Records | Role / key values |
|---|---|---:|---|---:|---|
| `trnxfile.txt` | `COSTM01` (TRNX) | 350 | LF | 3 | Three transactions, all `CARD 0500024453765740`, distinct ids; `+183.88` / `+14.00` / `-47.88`. Key is `CARD-NUM(16) + TRAN-ID(16)`. |
| `xreffile.txt` | `CVACT03Y` (XREF) | 50 | LF | 1 | card `0500024453765740` → cust `000000050` → acct `00000000050`. |
| `acctfile.txt` | `CVACT01Y` (ACCOUNT) | 300 | LF | 1 | account `00000000050`, `ACCT-CURR-BAL 492.00`. |
| `custfile.txt` | `CVCUS01Y` (CUSTOMER) | 500 | LF | 1 | customer `000000050` — the statement recipient (name/address/etc.). |

> **WHY all three transactions carry the same card (linkage).** The statement is
> assembled per account; each transaction must resolve to the account via the
> cross-reference. Sharing `CARD 0500024453765740` (the card in `xreffile.txt`)
> guarantees all three match account `00000000050`, so the happy-path statement body
> has exactly three lines and no orphaned transactions.

## 3. Programs under test & expected outcome

- **Programs:** `app/cbl/CBSTM03A.CBL` (driver) with the I/O subprogram
  `app/cbl/CBSTM03B.CBL` (UPPERCASE `.CBL`; master §4.1).
- **Expected outcome** (authoritative intent for the **[planned]** golden mirror
  `tests/golden/statement/happy_path/`, captured from actual output — master §1): a
  statement for customer `000000050` / account `00000000050` listing all **three**
  transactions with their amounts and net effect (`+150.00`), in both the plain-text
  and HTML forms the program emits.

> **Determinism (Trade-off — golden-master over field-by-field).** Statement output
> is compared as a **byte-deterministic golden master** rather than field-by-field,
> because the statement layout (headers, spacing, HTML markup) is easier to pin as a
> whole. Any run-varying timestamp on the produced statement must be normalized by
> the comparator (master §6.3); the fixture inputs contain no run-varying values.

## 4. Data governance / synthetic provenance (MA-24)

The customer, account, and card values are **synthetic, seed-derived** test data
representing **no real person or account**:

- `custfile.txt` (customer `000000050`) is copied **byte-for-byte** from the
  published synthetic seed `app/data/ASCII/custdata.txt` (verified equal for cust
  `000000050`).
- `acctfile.txt` and `xreffile.txt` derive from `acctdata.txt` / `cardxref.txt`
  (account/card `…050` / `0500024453765740`); the three `trnxfile.txt` rows are
  synthetic transactions on that same synthetic card.

These seeds ship with the upstream open-source AWS CardDemo project as fabricated
demonstration data. See master §10 for the full attestation.

## 5. Sources & scope

- **Derived from (never edited):** `app/data/ASCII/{custdata,acctdata,cardxref}.txt`.
- **Record layouts:** `app/cpy/{CVCUS01Y,CVACT01Y,CVACT03Y}.cpy` and the statement
  transaction layout `COSTM01`.
- **Programs:** `app/cbl/CBSTM03A.CBL`, `app/cbl/CBSTM03B.CBL`.
- **Consumed by (when present):** the statement integration test and end-to-end
  statement cycle — **[planned]** (master §1).

**Minimal-change principle (mandatory).** Seeds under `app/data/ASCII/` and all
production sources under `app/` are **REFERENCE ONLY and are never modified**
(AAP §0.8.2).

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the static
fixtures in this folder.*
