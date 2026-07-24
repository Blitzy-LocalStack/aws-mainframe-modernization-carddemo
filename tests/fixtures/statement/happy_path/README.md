# Statement — Happy-Path Scenario (`CBSTM03A` / `CBSTM03B`)

Deterministic fixed-width **INPUT** fixtures for a **small, internally consistent,
single-customer** statement run. Loaded into GnuCOBOL indexed files and fed to the
compiled statement driver `CBSTM03A` (with its I/O subprogram `CBSTM03B`), they
produce **one plain-text statement** (`STMTFILE`, LRECL 80) and **one HTML statement**
(`HTMLFILE`, LRECL 100). The integration test
`tests/integration/test_cbstm03a_statement.py` **[planned]** compares those two
outputs against the goldens mirrored at `tests/golden/statement/happy_path/`
**[planned]** — the byte-identical scenario name means **this fixture folder and that
golden folder are a matched pair**.

> **Read [`tests/fixtures/README.md`](../../README.md) first.** It is the single
> source of truth for the byte-level encoding contract — fixed-width positional
> fields (§3.1), LF line endings (§3.2), trailing newline (§3.3), zoned-decimal sign
> overpunch (§3.4), implied decimals (§3.5), the record-layout tables (§5), the
> flat→indexed loader convention (§4.2), the UPPERCASE `.CBL` casing quirk of the
> statement programs (§4.1), and the synthetic-data attestation (§10). This README
> **describes** the records and their **decoded** values so a reviewer can verify the
> fixtures and goldens by hand; it does **not** restate that contract and does **not**
> embed the raw fixed-width bytes.

## 1. Purpose of this folder

The statement is built for exactly **one** cross-reference row → **one** customer →
**one** account, all reachable from a single card, carrying **three** transactions.
This is the reference "transactions in → statement out" scenario for the statement
domain: the program reads the transactions, resolves each to its account via the
cross-reference, and emits the plain-text and HTML statements listing all three and
their net effect.

## 2. Files in this folder

Each fixture is a flat fixed-width text file whose base name equals the runtime
`ASSIGN`/DD name the program binds to (via `scripts/test_env.sh`). All four are
loaded from flat text into GnuCOBOL **indexed** files — **every key at offset 0** —
by `tests/helpers/vsam_loader.load_indexed(...)` / `tests/helpers/load_indexed.sh`
(an `IDCAMS REPRO` analog) **before** the program runs. Each flat file is pre-sorted
ascending by its key and every key is unique, so the indexed load is clean and
order-independent.

| File (= ASSIGN/DD name) | Record layout (copybook) | RECLEN | Indexed key @ offset 0 | ACCESS |
|---|---|---:|---|---|
| `trnxfile.txt` | `COSTM01.CPY` `TRNX-RECORD` | 350 | card `X(16)` + id `X(16)` = 32 | SEQUENTIAL |
| `xreffile.txt` | `CVACT03Y` `CARD-XREF-RECORD` | 50 | `XREF-CARD-NUM` `X(16)` = 16 | SEQUENTIAL |
| `custfile.txt` | `CVCUS01Y` `CUSTOMER-RECORD` | 500 | `CUST-ID` `X(09)` = 9 | RANDOM |
| `acctfile.txt` | `CVACT01Y` `ACCOUNT-RECORD` | 300 | `ACCT-ID` `9(11)` = 11 | RANDOM |

> The `RECLEN`, key, and `ACCESS` columns are taken verbatim from the `SELECT … ORGANIZATION
> IS INDEXED … ACCESS MODE …` clauses in `app/cbl/CBSTM03B.CBL`, the I/O subprogram that
> actually opens these files: `TRNX-FILE` and `XREF-FILE` are read `ACCESS SEQUENTIAL`
> (browse), while `CUST-FILE` and `ACCT-FILE` are read `ACCESS RANDOM` (keyed lookup per
> transaction card). See parent README §5.3 (XREF), §5.8 (CUSTOMER), §5.2 (ACCOUNT) for the
> full field maps.

## 3. The single linked triple

The three master fixtures form one fully linked chain; the statement cannot be built
unless all three resolve to one another.

- **XREF** (`xreffile.txt`, one row): card **`0500024453765740`** → cust
  **`000000050`** → acct **`00000000050`**. Derived from the seed
  `app/data/ASCII/cardxref.txt` row `050002445376574000000005000000000050`
  (card 16 + cust 9 + acct 11 = 36 bytes), right-padded with 14 spaces to fill the
  50-byte `CVACT03Y` record. Exactly **one** XREF row.
- **CUSTOMER** (`custfile.txt`, one row): `CUST-ID` **`000000050`** →
  **Aniya Alba Von**; address **`1588 Nienow Cape`** / **`Suite 187`** /
  **`New Aricchester`**, state **`OR`**, country **`USA`**, zip **`04257`**;
  primary-cardholder indicator **`Y`**; **FICO 623**. Full 500-byte seed record.
  Exactly **one** customer row.
- **ACCOUNT** (`acctfile.txt`, one row): `ACCT-ID` **`00000000050`**,
  `ACCT-ACTIVE-STATUS` **`Y`**, **`ACCT-CURR-BAL` = +492.00** (raw zoned field
  `00000004920{`, where trailing `{` is the positive-0 overpunch — parent §3.4),
  open / expire / reissue dates **`2011-04-22`** / **`2023-03-09`** / **`2023-03-09`**.
  Full 300-byte seed record. Exactly **one** account row.

> **Internal-consistency / abend note (critical).** For each card it processes,
> `CBSTM03A` performs **keyed reads** of the customer (`2000-CUSTFILE-GET`, keyed on
> `XREF-CUST-ID`) and the account (`3000-ACCTFILE-GET`, keyed on `XREF-ACCT-ID`), and
> **`PERFORM 9999-ABEND-PROGRAM` if either read fails** (any subprogram return code
> other than `'00'`). Because the XREF's `000000050` / `00000000050` keys resolve
> exactly to the present `custfile.txt` and `acctfile.txt` records, the happy path
> **never abends** — every lookup succeeds and the statement is produced end to end.

## 4. The three chosen transactions (`trnxfile.txt`)

All three rows carry card **`0500024453765740`** (the card in `xreffile.txt`), so all
three resolve to account `00000000050`. They are derived from
`app/data/ASCII/dailytran.txt` via the `app/jcl/CREASTMT.JCL` `SORT` step, which
moves the 16-byte card number to the front of each record and then sorts ascending by
card then transaction id — hence the fixtures are already ordered by `TRNX-ID`:

| # | `TRNX-ID` | Type | Description | Decoded amount |
|---|---|---|---|---:|
| 1 | `0000000058866561` | `01` | `Purchase at Blick-Rippin` | **+183.88** |
| 2 | `0000000329724245` | `01` | `Purchase at Reichel Group` | **+14.00** |
| 3 | `0000000577826814` | `03` | `Return item at DuBuque, Wuckert and Mraz` | **−47.88** |

**Total EXP = 183.88 + 14.00 − 47.88 = 150.00.**

> **WHY exactly these three (Alternatives Considered / coverage).** The set is small
> and hand-auditable — well within the program's 10-transactions-per-card table cap
> (`WS-TRAN-TBL OCCURS 10 TIMES`) — yet it deliberately mixes two purchases
> (`TYPE 01`, positive) with **one negative return** (`TYPE 03`, `−47.88`, negative
> zoned overpunch). That single negative row is what exercises both the running
> `Total EXP` summation (the return must *reduce* the total to `150.00`, not inflate
> it) and the trailing-sign `PIC Z(9).99-` edit on the amount columns. The full seed
> holds **six** transactions for this card
> (`…058866561`, `…329724245`, `…475746885`, `…577826814`, `…685488982`,
> `…838587312`); using all six was rejected in favour of this **3-transaction
> subset** to keep the golden statement short enough to review by eye while still
> covering the sign-edit and total-summation branches.

## 5. Expected statement shape (so a reviewer can eyeball the golden)

The plain-text `STMTFILE` output the golden will contain, in order:

- A **`START OF STATEMENT`** banner line (framed by `*` characters).
- **Customer block:** the recipient name **`Aniya Alba Von`**, then the address lines
  **`1588 Nienow Cape`**, **`Suite 187`**, and **`New Aricchester OR USA 04257`**.
- **Account block:** `Account ID` **`00000000050`**; **Current Balance** rendered via
  `PIC 9(9).99-` (fixed, *not* zero-suppressed) as **`492.00`**; **FICO Score**
  **`623`**.
- **Transaction summary:** a `TRANSACTION SUMMARY` heading and column headers, then
  **three detail lines** — each amount rendered via `PIC Z(9).99-` (zero-suppressed,
  trailing sign) and prefixed with **`$`** — followed by the **`Total EXP:`** line
  showing **`$150.00`** (the negative return reduces the total below the sum of the
  two purchases).
- An **`END OF STATEMENT`** banner line (framed by `*`).

The **HTML** `HTMLFILE` mirrors the same data in the program's HTML layout (LRECL
100).

> **Determinism.** The plain-text statement carries no run-date or timestamp, so it is
> naturally byte-deterministic. Any generated run date/timestamp that a variant might
> introduce is normalized by `tests/helpers/golden_compare.assert_matches_golden`
> before comparison (parent §6.3), and these input fixtures encode no "today" value —
> reruns produce identical output.

## 6. Explainability (AAP §0.10.1)

- **Assumptions.** The fixtures' fixed-width byte offsets and zoned-decimal sign
  overpunch match the `app/cpy/` copybook contract exactly (`COSTM01.CPY`,
  `CVACT03Y`, `CVCUS01Y`, `CVACT01Y`). Every master record is copied **byte-for-byte**
  from the shipped `app/data/ASCII` seeds (never hand-transcribed), so the field-level
  values quoted above — `492.00`, `623`, the card→cust→acct triple, the dates — are
  authoritative by construction.
- **Trade-offs.** The integration test uses **golden-master byte-diff comparison** of
  the whole statement rather than field-by-field assertions. This was chosen for
  deterministic, audit-grade whole-output verification (headers, spacing, and HTML
  markup are pinned as a unit) at the cost of finer failure locality — a byte diff
  says *the statement changed*, not *which field*.
- **Alternatives Considered.** Using all six of the card's seed transactions was
  rejected in favour of the 3-transaction subset (one negative) — see §4 — to keep the
  golden small and hand-auditable while still exercising the sign-edit and
  total-summation branches.

## 7. Data governance & sources

- **Synthetic provenance (MA-24).** The customer, account, and card values are
  **synthetic, seed-derived** test data representing **no real person or account**.
  `custfile.txt`, `acctfile.txt`, and `xreffile.txt` are copied from the published
  synthetic seeds `app/data/ASCII/{custdata,acctdata,cardxref}.txt`; the three
  `trnxfile.txt` rows are a synthetic subset of `app/data/ASCII/dailytran.txt` for the
  same synthetic card. These seeds ship with the upstream open-source AWS CardDemo
  project as fabricated demonstration data — see parent README §10 for the full
  attestation.
- **Minimal-change principle (mandatory).** The seeds under `app/data/ASCII/` and all
  production sources under `app/` (COBOL, copybooks, JCL) are **REFERENCE ONLY and are
  never modified** (AAP §0.8.2). This scenario is derived from them; it does not edit
  them.
- **Derived from (never edited):** `app/data/ASCII/{cardxref,custdata,acctdata,dailytran}.txt`.
- **Record layouts:** `app/cpy/{COSTM01.CPY,CVACT03Y,CVCUS01Y,CVACT01Y}`.
- **Programs under test:** `app/cbl/CBSTM03A.CBL` (driver), `app/cbl/CBSTM03B.CBL`
  (I/O subprogram); statement generation orchestrated by `app/jcl/CREASTMT.JCL`.
- **Consumed by (when present):** `tests/integration/test_cbstm03a_statement.py` and
  the golden mirror `tests/golden/statement/happy_path/` — both **[planned]**.

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the static
fixtures in this folder.*
