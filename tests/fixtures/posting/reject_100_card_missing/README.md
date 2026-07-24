# Posting fixture — reject reason 100 (card missing from XREF)

Deterministic, fixed-width **input** fixtures that drive the compiled
`app/cbl/CBTRN02C.cbl` down the **reject-reason-100** path: a daily transaction
whose card number is **absent from the card cross-reference** is rejected as
`INVALID CARD NUMBER FOUND`. They are consumed by
`tests/integration/test_cbtrn02c_posting.py` and by the end-to-end batch layer.

> **Byte-encoding contract:** see [`../../README.md`](../../README.md)
> (`tests/fixtures/README.md`) — it is **authoritative** for record layouts, the
> zoned-decimal sign-overpunch table, field widths/offsets, and the
> line-ending/`PROC-TS` determinism rules. This scenario README does **not**
> restate any of that; it records only *what this scenario does* and *why*, which
> — per the Explainability mandate (AAP §0.10.1) — is the required documentation
> carrier for static `.txt` fixtures that cannot hold docstrings.

## 1. Business rule exercised — `CBTRN02C` `1500-VALIDATE-TRAN` / `1500-A-LOOKUP-XREF`

The **first** validation step is the cross-reference lookup, and it is the only
one this scenario reaches. Quoting the source paragraph verbatim:

```cobol
1500-A-LOOKUP-XREF.
    MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM
    READ XREF-FILE INTO CARD-XREF-RECORD
       INVALID KEY
         MOVE 100 TO WS-VALIDATION-FAIL-REASON
         MOVE 'INVALID CARD NUMBER FOUND'
           TO WS-VALIDATION-FAIL-REASON-DESC
       NOT INVALID KEY
         CONTINUE
    END-READ
    EXIT.
```

- The program moves `DALYTRAN-CARD-NUM` (`4859452612877065`) into
  `FD-XREF-CARD-NUM` and issues `READ XREFFILE`.
- Because that card is **not** a key in `cardxref.txt`, the read raises
  **`INVALID KEY`**, so the program sets reject **reason `100`**, description
  **`INVALID CARD NUMBER FOUND`**, and calls `2500-WRITE-REJECT-REC` to append
  the offending transaction to `DALYREJS`.
- At end-of-job `WS-REJECT-COUNT > 0`, so the program executes
  `MOVE 4 TO RETURN-CODE` → **`RETURN-CODE = 4`**.

> **Why the account / limit / expiration checks never fire (Assumption — matches
> `CBTRN02C` line-for-line).** `1500-VALIDATE-TRAN` performs
> `1500-A-LOOKUP-XREF` first and only calls `1500-B-LOOKUP-ACCT` when
> `WS-VALIDATION-FAIL-REASON = 0`. Reason `100` is non-zero, so the account
> lookup, the credit-limit test (reject 102), and the expiration test
> (reject 103) are **never reached**. Consequently **no `TRANSACT` record is
> written and no balances change** — reject 100 is the single observable outcome.

## 2. Fixture files in this folder

| File | `ASSIGN` | Copybook | `RECLN` | Organization / key | Role in this scenario |
|---|---|---|---:|---|---|
| `dailytran.txt` | `DALYTRAN` | `CVTRA06Y` | 350 | SEQUENTIAL | The one input transaction: card `4859452612877065`, `AMT +504.77`. |
| `cardxref.txt` | `XREFFILE` | `CVACT03Y` | 50 | INDEXED, key 16 @0 | **Deliberately OMITS** card `4859452612877065`; holds ONE decoy row (card `0927987108636232` → cust `000000020` / acct `00000000020`) so the index is valid but the lookup misses → reject 100. |
| `acctdata.txt` | `ACCTFILE` | `CVACT01Y` | 300 | INDEXED, key 11 @0 | Account `00000000007`; present only so `OPEN I-O ACCTFILE` succeeds — **never read** on this path. |
| `tcatbal.txt` | `TCATBALF` | `CVTRA01Y` | 50 | INDEXED, key 17 @0 | Category-balance row for acct `00000000007` / type `01` / cat `0001`; present only so `OPEN I-O TCATBALF` succeeds — **never read or updated** on this path. |

> **Why the single decoy row exists (Trade-off / self-containment exception,
> parent §7).** An empty index risks GnuCOBOL ISAM edge cases at `OPEN`/first
> `READ`. Seeding the cross-reference with exactly **one** *non-matching* row
> keeps the XREF index populated and valid while still guaranteeing the
> transaction's card is absent, so the `INVALID KEY` fires for the right reason.
> This is the parent contract's *self-containment exception* — a scenario may
> deliberately omit **exactly one** key to trigger a reject; here the omitted key
> is the transaction's own card, and every other reference (account, category)
> resolves. The parent README's §7 names this scenario as its worked example.

> **Why `acctdata.txt` and `tcatbal.txt` are present at all (Rationale).**
> `CBTRN02C` opens **all six** files with `OPEN I-O` before the read loop
> (`0400-ACCTFILE-OPEN`, `0500-TCATBALF-OPEN`); a missing dataset would abend in
> `OPEN` rather than reach the reject-100 logic under test. These two files
> therefore exist solely to satisfy `OPEN` — the reject-100 path never reads them.

## 3. Line endings (scenario-specific)

- All four fixtures use **LF (`\n`) line endings with a single trailing newline**
  after the one record they each carry, so `wc -l` equals the record count. The
  general rule and its rationale live in the parent contract (§3.2, §3.3) and are
  not restated here.
- **`tcatbal.txt` is derived from a CRLF seed but stored LF here.** The seed
  `app/data/ASCII/tcatbal.txt` ships as **CRLF (`\r\n`)** (parent §3.2, §8), yet
  this scenario's `tcatbal.txt` is written **LF-only** (the trailing `\r` is
  stripped).

  > **Why (Trade-off).** The flat→indexed loader treats each physical line as
  > exactly one fixed-length record. A stray `\r` would make the `CVTRA01Y`
  > record **51 bytes** instead of 50 and be absorbed into the trailing `FILLER`,
  > corrupting the last field and any byte-diff against the golden. Storing every
  > fixture in this folder as LF keeps them uniform and loader-safe. The parent
  > contract (§3.2) requires this CRLF→LF choice to be documented per scenario —
  > this section satisfies that obligation. The other three fixtures derive from
  > seeds that are already LF.

## 4. Derivation from seeds

Every fixture is derived **read-only** from the published AWS CardDemo sample
datasets under `app/data/ASCII/*` and reshaped into this folder (parent §8):

- `dailytran.txt` ← `dailytran` seed record 1 (the transaction carrying card
  `4859452612877065`, `AMT +504.77`).
- `cardxref.txt` ← the `cardxref` seed row for account `00000000020` (card
  `0927987108636232`), used here as the decoy so the transaction's card misses.
- `acctdata.txt` ← `acctdata` seed record for account `00000000007`.
- `tcatbal.txt` ← `tcatbal` seed record for account `00000000007`.

> **Seeds are never edited (AAP §0.8.2).** The seeds are inputs to derivation
> only; rows are copied into this folder and reshaped here, never modified in
> place. `cardxref.txt` additionally **appends the 14-space `FILLER`** to reach
> the 50-byte `CVACT03Y` copybook width, because the seed rows are only 36 bytes
> (they stop after `XREF-ACCT-ID`). The overpunch/width rules that govern this
> padding are defined in the parent contract (§3.4, §5.3) and are not duplicated
> here.

## 5. Expected outcome (authoritative for the golden mirror & integration asserts)

- **`RETURN-CODE = 4`** — a soft reject (`WS-REJECT-COUNT > 0` at end-of-job).
- Exactly **1** reject record in `DALYREJS`; **0** records in `TRANSACT`;
  account `00000000007` and the `tcatbal` row **unchanged** (never touched on the
  reject-100 path).
- The single `DALYREJS` record is the **350-byte `DALYTRAN-RECORD` copied
  verbatim** followed by an **80-byte `VALIDATION-TRAILER`** whose reason field
  `9(04)` = **`0100`** and whose description `X(76)` =
  **`INVALID CARD NUMBER FOUND`** (right-space-padded to 76 bytes).

  > **Why `0100` and not `100` (Assumption).** `WS-VALIDATION-FAIL-REASON` is
  > `PIC 9(04)`, so the value `100` serializes as the four zoned digits `0100`;
  > the 76-byte description is a `PIC X(76)` field, hence the right-space padding.

- The expected outputs live in the parallel golden tree
  **`tests/golden/posting/reject_100_card_missing/`** (authored by the golden
  agent, kept path-identical per parent §2.1) and are compared with
  `tests/helpers/golden_compare.py`.

## 6. How this scenario is run (brief)

1. `tests/helpers/load_indexed.sh` (or `vsam_loader.load_indexed`) loads the
   three **INDEXED** fixtures — `cardxref.txt`, `acctdata.txt`, `tcatbal.txt` —
   into GnuCOBOL indexed files (the suite's `IDCAMS REPRO` analog) **before** the
   program runs; the **SEQUENTIAL** `DALYTRAN` input is bound straight to
   `dailytran.txt` as-is (parent §4.2).
2. `tests/integration/test_cbtrn02c_posting.py` invokes the compiled `CBTRN02C`
   with the `ASSIGN`-name environment bindings wired by `scripts/test_env.sh`,
   then asserts the §5 outcome against the golden mirror.

> **Why only these helpers/paths are named (Assumption).** This scenario uses
> exactly the harness components defined by the AAP; no ad-hoc commands are
> introduced, so the fixture stays portable across the CI and local runners.

## 7. Data governance / synthetic provenance (MA-24)

The card numbers (`4859452612877065`, `0927987108636232`), the customer id
(`000000020`), and the account ids (`00000000007`, `00000000020`) in these
fixtures are **synthetic, seed-derived** values copied from the published AWS
CardDemo sample datasets `app/data/ASCII/{dailytran,cardxref,acctdata,tcatbal}.txt`,
which ship with the upstream open-source project as fabricated demonstration
data. They represent **no real person, account, or payment instrument**. No
business-rule field was reshaped away from its seed value in this scenario (the
rows are used as copied, only widened to the copybook `RECLN`). See the master
[`tests/fixtures/README.md`](../../README.md) §10 for the full attestation and
derivation-of-record.

> **Why this attestation is recorded (Assumption / compliance, parent §10.3).**
> A 16-digit PAN is, by inspection, indistinguishable from a live card number; a
> financial-enterprise test suite must therefore *attest* provenance rather than
> leave a reviewer to assume it. Colocating this note with the bytes it describes
> makes the "seed-derived, non-person" origin explicit and audit-defensible.

---

*This README is the mandatory Explainability artifact (AAP §0.10.1, parent §9.1)
for the four static `.txt` fixtures in this folder.*
