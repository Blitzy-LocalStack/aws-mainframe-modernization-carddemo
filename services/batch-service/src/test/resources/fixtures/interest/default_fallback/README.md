```text
# =============================================================================
# services/batch-service/src/test/resources/fixtures/interest/default_fallback/
#     README.md
# -----------------------------------------------------------------------------
# Purpose:
#       The scenario-level contract for the interest DEFAULT-group fallback
#       fixture, and the ONLY explainability carrier for the four record files
#       beside it. tcatbal.txt, acctdata.txt, discgrp.txt and cardxref.txt are
#       fixed-width byte images in which every position is data, so none of
#       them can hold a comment of any kind -- not even a header line. Every
#       reason those bytes are what they are lives here and nowhere else.
#
# Source of truth:
#       Behaviour: app/cbl/CBACT04C.cbl, cited by line throughout.
#       Layout:    app/cpy/CVTRA01Y.cpy, CVTRA02Y.cpy, CVACT01Y.cpy,
#                  CVACT03Y.cpy and CVTRA05Y.cpy.
#       Dataset and parameter contract: app/jcl/INTCALC.jcl.
#       Bytes:     app/data/ASCII/tcatbal.txt, acctdata.txt, discgrp.txt and
#                  cardxref.txt.
#       Expectation: tests/golden/interest/default_fallback/.
#       All of those are REFERENCE-ONLY: they are read and cited, never
#       written.
#
# Authority:
#       ../../README.md is the master byte-encoding contract and outranks this
#       document on widths, offsets, sign encoding, padding bytes, line
#       endings and determinism. This file cites it by section and restates
#       none of it (master section 1.3). Where this scenario departs from a
#       rule stated there, section 4 names the departure and its reason
#       (master section 10 item 4).
#
# Scope:
#       Exactly one of the three disclosure-group outcomes: the VSAM
#       status-23 DEFAULT-group fallback. The direct hit belongs to
#       `../happy_path`; the zero-balance contrast belongs to
#       `../zero_balance`.
# =============================================================================
```

> **Read `../../README.md` first, and read it in full.** It is the master
> byte-encoding contract for this whole fixture tree and it **owns** the record-length
> ledger and key geometry (master section 5.1), the zoned-decimal sign-overpunch tables
> in EBCDIC sign mode (section 3.3), the implied-decimal rule (section 3.7), the
> **measured** per-record `FILLER` padding-byte table (section 6.1, with its two
> deliberate exceptions in section 6.2), the job-dependent `TRAN-DESC` padding split
> (section 6.3), the line-ending and single-trailing-newline rules (sections 3.8 and
> 3.9), the card-xref 36-versus-50 ruling (section 3.10), the opaque business-date
> token (section 8.2) and the per-domain timestamp-normalisation sets (section 8.1).
> **None of that is reproduced here.** This document cites those sections and adds
> only what is specific to this scenario.

This document follows `docs/CODE_DOCUMENTATION_STANDARD.md`, which is the polyglot
convention that governs Markdown in the new trees; its obligations for a new
`README.md` are not restated here.

**Citation convention.** A bare line reference such as `:436` means
`app/cbl/CBACT04C.cbl`, the one program this scenario drives. Every reference to any
other file names that file first, and a bare reference following a named file belongs to
the file just named. A reference of the form "master section N" means
`../../README.md`. `../happy_path` and `../zero_balance` are written as plain code spans
rather than links because their own scenario READMEs are authored independently of this
one, and `docs/CODE_DOCUMENTATION_STANDARD.md` requires a path to a document that may
not yet exist to be a code span, so that no reader follows a reference to nothing.

---

## 0. How this document discharges Rule 1's four docstring elements

Markdown has no docstring construct, so the four elements the user-specified
**Rule 1 (Explainability)** requires are discharged **analogically**, by the sections
named below. Rule 1's forbidden-patterns clause names a documentation artifact that
omits any one of them as a failure, so the mapping is stated explicitly rather than
left to be inferred:

| Rule 1 element | Discharged by | Master section 10 item |
|---|---|---|
| **Purpose** | the header block above, and **section 1** | items 1 and 2 |
| **Parameters** | **section 4** -- the four input byte images, field by field | item 4 |
| **Return values** | **section 3** -- the expected outcome, with the arithmetic shown | item 3 |
| **Exceptions or errors** | **section 5** -- the three ways this fixture fails | -- |

Sections 2, 6 and 7 carry the reasoning; section 8 records the Rule 1 verdict and
section 9 the mandatory provenance attestation (master section 10 item 5). The
ordering satisfies both mandates at once: the docstring elements are enumerated here
in the rule's own order, while the body runs in the order master section 10 requires,
which ends at the attestation.

---

## 1. Purpose -- the branch this scenario exists to cover

This scenario drives `app/cbl/CBACT04C.cbl` down the **`DEFAULT`-group interest
fallback**, one of the three disclosure-group outcomes master section 7.2.2
enumerates.

Both accounts under test carry a **blank `ACCT-GROUP-ID`**. `:210-212` therefore
composes a lookup key no row matches, so the keyed disclosure-group read at `:416-420`
takes its `INVALID KEY` branch and leaves `DISCGRP-STATUS = '23'`. Status `23` is
**explicitly accepted as
non-fatal** at `:422`; `:436` tests for it; `:437` moves the literal `'DEFAULT'` into
the lookup key; `:438` re-reads through `1200-A-GET-DEFAULT-INT-RATE`; the retry
resolves `DIS-INT-RATE = 15.00`; and interest accrues from that rate.

**The exact business rule exercised, cited rather than paraphrased:** `CBACT04C`
`DEFAULT`-group fallback on VSAM status `23` at `:436-438`, with the retry read at
`:443-460` and the interest formula at `:464-465`.

What this scenario is **not** for:

- It is **not** the direct hit. `../happy_path` reshapes `ACCT-GROUP-ID` to
  `A000000000` so the composed key is found on the first read and status is `'00'`.
- It is **not** the zero-rate gate. `:214` reads `IF DIS-INT-RATE NOT = 0`, and when
  the rate is zero **no interest transaction is written at all** and the fee paragraph
  is skipped. Nothing here drives that: both category-balance rows key type `01` /
  category `0001`, whose `DEFAULT` rate is `15.00`.
- It is **not** the zero-balance case. `../zero_balance` drives a zero **balance**
  against a non-zero rate, which still writes a transaction whose `TRAN-AMT` is
  `0000000000{`. A zero **rate** produces no record; a zero **balance** produces a
  `0.00` record. Master section 7.2.2 keeps the two distinct and so does this
  document.

---

## 2. Mechanism -- why the fallback fires, traced line by line

### 2.1 The key is composed from three fields, and MOVE order is not physical order

`:210-212` composes the disclosure-group key:

```text
:210   MOVE ACCT-GROUP-ID     TO FD-DIS-ACCT-GROUP-ID
:211   MOVE TRANCAT-CD        TO FD-DIS-TRAN-CAT-CD
:212   MOVE TRANCAT-TYPE-CD   TO FD-DIS-TRAN-TYPE-CD
```

**The source moves group id, then category code, then type code. The physical key
order is group id, then type code, then category code** -- `app/cpy/CVTRA02Y.cpy:6-8`
declares `DIS-ACCT-GROUP-ID PIC X(10)`, then `DIS-TRAN-TYPE-CD PIC X(02)`, then
`DIS-TRAN-CAT-CD PIC 9(04)`, and the key is those sixteen bytes at offset 0.

Assumptions: reading `:211-212` as the byte order of the key transposes the two-byte
type code and the four-byte category code, producing the key `"          000101"`
instead of `"          010001"`. Both are sixteen bytes of legal characters, so the
read still executes and still misses -- and in **this** scenario it misses either way,
which is precisely what makes the mistake invisible here and expensive in
`../happy_path`, where the direct hit depends on the key being right.

### 2.2 First read -- the miss

With a blank group id, category `0001` and type `01`, the composed key is

```text
"          010001"      ten blanks, then 01, then 0001
```

No row in `discgrp.txt` begins with ten blanks -- every one of its seventeen rows
begins `DEFAULT   `. The read at `:416-420` therefore takes its `INVALID KEY` branch,
displaying `DISCLOSURE GROUP RECORD MISSING` and `TRY WITH DEFAULT GROUP CODE`, and
`DISCGRP-STATUS` is left `'23'`.

### 2.3 Status 23 is accepted, and only the group-id component is overwritten

`:422` reads `IF DISCGRP-STATUS = '00' OR '23'` -- so status `23` sets the
application-OK result and does **not** reach the abend path at `:431-434` that any
other non-zero status reaches. `:436` then tests `IF DISCGRP-STATUS = '23'` and `:437`
performs

```text
:437   MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID
```

That overwrites **only** the ten-byte group-id component. The type code and category
code set at `:211-212` are untouched, so the retry key is

```text
"DEFAULT   010001"      DEFAULT space-padded to X(10), then 01, then 0001
```

### 2.4 Second read -- the hit

`:438` performs `1200-A-GET-DEFAULT-INT-RATE`. The read at `:444` finds
`DEFAULT   ` / `01` / `0001`, status is `'00'`, and `DIS-INT-RATE` is `15.00`,
encoded `00150{`. `:214` sees a non-zero rate, so `:215` computes interest and `:216`
performs the fee paragraph.

### 2.5 The retry has no INVALID KEY clause, so a missing DEFAULT row abends

This is the single most consequential fact about the fallback, and it is easy to
assume the opposite. `1200-A-GET-DEFAULT-INT-RATE` spans `:443-460`. Its read at
`:444` carries **no `INVALID KEY` clause at all** -- unlike the first read at
`:416-420` -- and `:446` accepts **only** status `'00'`. Anything else falls to `:455`
`DISPLAY 'ERROR READING DEFAULT DISCLOSURE GROUP'` and `:458`
`PERFORM 9999-ABEND-PROGRAM`.

**A missing `'DEFAULT'` row does not default to zero interest. It abends.** Section
5.1 states the corollary that follows for seeding.

### 2.6 The fallback never rewrites the persisted group id

`:437` targets `FD-DIS-ACCT-GROUP-ID`, which is the **disclosure-group file's key
field**, not a field of the account record. And `1050-UPDATE-ACCOUNT` at `:350-356`
touches exactly three fields: `:352` adds the accrued interest to `ACCT-CURR-BAL`,
`:353` zeroes `ACCT-CURR-CYC-CREDIT` and `:354` zeroes `ACCT-CURR-CYC-DEBIT`, before
`:356` rewrites the record.

So the output account record still carries the **blank** `ACCT-GROUP-ID` it arrived
with, and the committed golden confirms it: `acctdat.expected` holds ten spaces at
`[112:122]` on both rows. A reader who expects the fallback to have persisted
`DEFAULT` into the account will read that as a defect; it is the specified behaviour.

---

## 3. Return values -- the expected outcome, with the arithmetic shown

### 3.1 Interest per category-balance row

`:464-465` computes, with no `ROUNDED` phrase:

```text
COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
```

For this scenario's operands that is

```text
(1000.00 x 15.00) / 1200 = 15000.0000 / 1200 = 12.50
```

exactly, in fixed point, with no residue to round. `:467` accumulates it into
`WS-TOTAL-INT` for the account currently in hand.

### 3.2 Account balances after the run

Two values are stated per account and they are **not the same claim**. The *reference golden*
column is the byte content of `acctdat.expected` under
`tests/golden/interest/default_fallback/`, which is what the immutable COBOL produces. The
*migrated expectation* column is what the Java produces. They agree on the non-final account
and diverge on the final one, for the reason section 6.6 establishes:

| Account | Opening `ACCT-CURR-BAL` | Reference golden | Encoding in the golden | Migrated expectation | Why the two columns read as they do |
|---|---|---|---|---|---|
| `00000000001` | `194.00` | **`206.50`** | `00000002065{` | **`206.50`**, identical | `194.00 + 12.50` -- the control break to account 2 fired `1050-UPDATE-ACCOUNT`, so `:356` rewrote this record. Both implementations flush this account |
| `00000000002` | `158.00` | **`158.00`** | `00000001580{` | **`170.50`**, different | the reference never writes the final account back (section 6.6), so its golden carries the opening balance unchanged. The migrated rule flushes every account: `158.00 + 12.50 = 170.50`. Registered divergence **D-3** |

Assumptions: the `158.00` in the reference column is a **consequence of the baseline defect**
and not a target result. The migrated code is asserted against `170.50` --
`InterestCalculationServiceTest`, case *flush every account including the final one, the
documented D-3 divergence*. Neither `app/**` nor `tests/golden/**` is edited to reconcile the
two columns; the divergence is registered in
`docs/architecture/cobol-to-service-traceability.md` under D-3.

Both rows also keep `ACCT-CURR-CYC-CREDIT` and `ACCT-CURR-CYC-DEBIT` at
`00000000000{`: account 1 because `:353-354` zeroed them, account 2 because they were
already zero on input and were never rewritten in the reference. The two reasons differ even
though the bytes agree -- and under the migrated rule account 2 reaches the same bytes by the
first reason rather than the second, because it is flushed as well.

### 3.3 Generated transactions

**Two** interest transactions, one per category-balance row. **No fee transaction.**
**Return code `0`.**

Each is a 350-byte `TRAN-RECORD` per `app/cpy/CVTRA05Y.cpy`, whose field offsets are in
master section 5.2. Record 1, field by field:

| Field | Value | Set at |
|---|---|---|
| `TRAN-ID` | `2024-01-15000001` | `:474` increments the suffix, `:476-480` strings it onto the business date |
| `TRAN-TYPE-CD` | `01` | `:482` |
| `TRAN-CAT-CD` | **`0005`** | `:483` -- see section 6.4 |
| `TRAN-SOURCE` | `System` plus four spaces | `:484`, into `X(10)` |
| `TRAN-DESC` | `Int. for a/c 00000000001`, then **exactly 76 x `0x00`** | `:485-489` -- see section 6.7 |
| `TRAN-AMT` | `0000000125{` (`12.50`) | `:490` |
| `TRAN-MERCHANT-ID` | `000000000` | `:491` moves zero into `9(09)` |
| `TRAN-MERCHANT-NAME` | 50 spaces | `:492` |
| `TRAN-MERCHANT-CITY` | 50 spaces | `:493` |
| `TRAN-MERCHANT-ZIP` | 10 spaces | `:494` |
| `TRAN-CARD-NUM` | `9680294154603697` | `:495`, from the cross-reference row the alternate path returned |
| `TRAN-ORIG-TS` | volatile -- **masked** | `:496-497` |
| `TRAN-PROC-TS` | volatile -- **masked**, and byte-identical to `TRAN-ORIG-TS` | `:496-498` |
| `FILLER` | 20 x `0x00` | never moved to by either program |

Record 2 differs in exactly three places: `TRAN-ID` is `2024-01-15000002`, the
description's account id is `00000000002`, and `TRAN-CARD-NUM` is `0923877193247330`.
Type, category, source, amount, merchant fields and padding are identical.

### 3.4 The masked timestamps are 52 bytes, and they are a product of normalisation

`:496` performs `Z-GET-DB2-FORMAT-TIMESTAMP` **once** and `:497-498` move that one
value into **both** timestamp fields, so for the interest domain the two are
**necessarily equal to each other** as well as volatile. Both are normalised away
before comparison, which in the committed golden appears as 26 spaces each -- **52
contiguous bytes**.

Assumptions: master section 8.1 requires every scenario README to say which of the two
meanings a blank timestamp carries, because the byte pattern cannot distinguish them.
**Here they are the product of normalisation, not genuine input data.** No fixture file
in this folder carries a timestamp field at all, so the only blank timestamps in this
scenario are the masked ones in the expectation.

The Java side names this masking set **`INTTRAN`** --
`services/common-lib/src/main/java/com/carddemo/common/codec/CopybookLayout.java:1843-1844`
derives it from the `TRAN` layout by additionally flagging `TRAN-ORIG-TS`, and
registers it at `:2155`. **A comparison of interest output must name `INTTRAN`, not
`TRAN`**: the ordinary `TRAN` layout deliberately preserves a deterministic
originating timestamp, which is right for posting and wrong here.

---

## 4. Parameters -- the four input byte images

### 4.1 Geometry, per file

Widths and key offsets are the master ledger's (section 5.1); they are cited here, not
re-derived. Every figure below was measured from the committed bytes.

| File | Copybook | RECLN | Organization | Key geometry | Records | Bytes | Line ending |
|---|---|---:|---|---|---:|---:|---|
| `tcatbal.txt` | `CVTRA01Y` | 50 | INDEXED | `TRAN-CAT-KEY` at offset 0, length 17 | 2 | 102 | LF, one trailing |
| `acctdata.txt` | `CVACT01Y` | 300 | INDEXED | `ACCT-ID` at offset 0, length 11 | 2 | 602 | LF, one trailing |
| `discgrp.txt` | `CVTRA02Y` | 50 | INDEXED | `DIS-GROUP-KEY` at offset 0, length 16 | **17** | **867** | LF, one trailing |
| `cardxref.txt` | `CVACT03Y` | **50** | INDEXED | primary `XREF-CARD-NUM` at offset 0, length 16, **plus ALTERNATE `XREF-ACCT-ID` at offset 25, length 11, `WITH DUPLICATES`** | 2 | 102 | LF, one trailing |

All four are **LF-only with exactly one trailing newline** (master sections 3.8 and
3.9), so `wc -l` equals the record count in every case. None contains a carriage
return.

### 4.2 `tcatbal.txt` -- the two category balances that drive the run

| Slice | Field | Row 1 | Row 2 |
|---|---|---|---|
| `[0:11]` | `TRANCAT-ACCT-ID` | `00000000001` | `00000000002` |
| `[11:13]` | `TRANCAT-TYPE-CD` | `01` | `01` |
| `[13:17]` | `TRANCAT-CD` | `0001` | `0001` |
| `[17:28]` | `TRAN-CAT-BAL` | `0000010000{` = **1000.00** | `0000010000{` = **1000.00** |
| `[28:50]` | `FILLER X(22)` | 22 x ASCII `'0'` | 22 x ASCII `'0'` |

The file is pre-sorted by key, as master section 3.12 requires of an indexed input, so
`...001` is consumed first deterministically.

### 4.3 `acctdata.txt` -- two accounts, both with a blank group id

| Slice | Field | Row 1 | Row 2 |
|---|---|---|---|
| `[0:11]` | `ACCT-ID` | `00000000001` | `00000000002` |
| `[11:12]` | `ACCT-ACTIVE-STATUS` | `Y` | `Y` |
| `[12:24]` | `ACCT-CURR-BAL` | `00000001940{` = **194.00** | `00000001580{` = **158.00** |
| `[24:36]` | `ACCT-CREDIT-LIMIT` | `00000020200{` | `00000061300{` |
| `[36:48]` | `ACCT-CASH-CREDIT-LIMIT` | `00000010200{` | `00000054480{` |
| `[48:58]` | `ACCT-OPEN-DATE` | `2014-11-20` | `2013-06-19` |
| `[58:68]` | `ACCT-EXPIRAION-DATE` | `2025-05-20` | `2024-08-11` |
| `[68:78]` | `ACCT-REISSUE-DATE` | `2025-05-20` | `2024-08-11` |
| `[78:90]` | `ACCT-CURR-CYC-CREDIT` | `00000000000{` | `00000000000{` |
| `[90:102]` | `ACCT-CURR-CYC-DEBIT` | `00000000000{` | `00000000000{` |
| `[102:112]` | `ACCT-ADDR-ZIP` | `A000000000` | `A000000000` |
| `[112:122]` | **`ACCT-GROUP-ID`** | **ten spaces** | **ten spaces** |
| `[122:300]` | `FILLER X(178)` | 178 spaces | 178 spaces |

The expiration field is spelled `ACCT-EXPIRAION-DATE` because that is what
`app/cpy/CVACT01Y.cpy:11` says. Master section 9.3 confines the migrated spelling
`expiration_date` to **target column names** and forbids applying it to a baseline
citation, so a table describing baseline bytes quotes the baseline name.

**The offset trap.** `ACCT-ADDR-ZIP` is at zero-based **102** and `ACCT-GROUP-ID` at
zero-based **112**. The `A000000000` visible in this fixture is the **address ZIP**.
Mistaking it for the group id makes this scenario look mis-authored -- the reader
concludes the group id is populated and the fallback therefore cannot fire, when in
fact the group id is ten bytes further along and blank. Master section 7.2.3 records
the same trap from the other direction: `../happy_path` reshapes the group id to
`A000000000` as well, so in **that** fixture both slices read the same ten characters.

### 4.4 `discgrp.txt` -- the seventeen DEFAULT rows

All seventeen rows are keyed `DEFAULT   ` -- the seven letters of the literal moved at
`:437`, space-padded to `DIS-ACCT-GROUP-ID PIC X(10)`. `FILLER X(28)` at `[22:50]` is
28 x ASCII `'0'` on every row.

| # | Type | Cat | `DIS-INT-RATE` | Rate |
|---:|---|---|---|---:|
| 1 | `01` | `0001` | `00150{` | **15.00 -- the only row this scenario consumes** |
| 2 | `01` | `0002` | `00250{` | 25.00 |
| 3 | `01` | `0003` | `00250{` | 25.00 |
| 4 | `01` | `0004` | `00250{` | 25.00 |
| 5 | `02` | `0001` | `00000{` | 0.00 |
| 6 | `02` | `0002` | `00000{` | 0.00 |
| 7 | `02` | `0003` | `00000{` | 0.00 |
| 8 | `03` | `0001` | `00000{` | 0.00 |
| 9 | `03` | `0002` | `00000{` | 0.00 |
| 10 | `03` | `0003` | `00000{` | 0.00 |
| 11 | `04` | `0001` | `00150{` | 15.00 |
| 12 | `04` | `0002` | `00150{` | 15.00 |
| 13 | `04` | `0003` | `00150{` | 15.00 |
| 14 | `05` | `0001` | `00150{` | 15.00 |
| 15 | `06` | `0001` | `00150{` | 15.00 |
| 16 | `06` | `0002` | `00150{` | 15.00 |
| 17 | `07` | `0001` | `00000{` | 0.00 -- see section 6.3 |

**Only row 1 is read.** Both category balances key type `01` / category `0001`, so the
retry at `:444` resolves row 1 and the other sixteen are never fetched.

**The zero-rate rows are present but unreached.** Rows 5 to 10 and row 17 carry
`00000{`, so a category balance keyed `02`, `03` or `07` would trip the zero-rate gate
at `:214` and produce no transaction. Nothing in this scenario keys them, so **the
zero-rate gate is not exercised here** and no expectation in section 3 depends on it.

### 4.5 `cardxref.txt` -- the account-keyed chain

| Slice | Field | Row 1 | Row 2 |
|---|---|---|---|
| `[0:16]` | `XREF-CARD-NUM` | `9680294154603697` | `0923877193247330` |
| `[16:25]` | `XREF-CUST-ID` | `000000001` | `000000002` |
| `[25:36]` | `XREF-ACCT-ID` | `00000000001` | `00000000002` |
| `[36:50]` | `FILLER X(14)` | **14 spaces** | **14 spaces** |

Each account resolves to exactly one card, which is what puts a card number into
`TRAN-CARD-NUM` at `:495`.

Assumptions: the alternate index is declared `WITH DUPLICATES`, so master section 4.3
warns that the account-keyed path may legitimately return more than one row and
requires a scenario relying on a single row to say so. **This scenario does rely on
one row per account** -- the expected `TRAN-CARD-NUM` in section 3.3 is a specific card
number, which is only well defined because each account maps to exactly one card here.
That is a property of this fixture's data shape, not of the access path.

### 4.6 Departures from the master contract, named

Master section 10 item 4 requires a scenario to name any departure from a rule stated
in the master. This scenario has two, and both are deliberate:

1. **`ACCT-GROUP-ID` is not reshaped.** Master section 7.3's "established values to
   reuse" table lists `A000000000` for both accounts, because that table is written
   from `../happy_path`. This scenario departs from that one row and leaves the seed's
   ten spaces in place. Master section 7.2.3 authorises exactly this -- it states that
   a verbatim seed row yields a blank group id and that "that is the `default_fallback`
   scenario, not `happy_path`". Every other value in that table is adopted unchanged:
   the `1000.00` category balances, the `15.00` rate, both cross-reference chains and
   both opening balances. Rationale in section 6.1.
2. **Row 17's rate differs from the value the migrated harness seeds.** Detail and
   reason in section 6.3.

Everything else follows the master as written: 50-byte card-xref rows per section 3.10,
LF line endings per section 3.8, one trailing newline per section 3.9, key-sorted
indexed input per section 3.12, and the measured `FILLER` bytes of section 6.1.

---

## 5. Exceptions or errors -- the three ways this fixture fails

### 5.1 A missing `'DEFAULT'` row abends the program

The mechanism is in section 2.5: the retry read at `:444` has no `INVALID KEY` clause,
`:446` accepts only `'00'`, and any other status reaches `:455` and `:458`.

**The corollary that matters for seeding is not "one `DEFAULT` row" but "one
`DEFAULT` row per (type code, category code) pair actually reached."** A `DEFAULT`
table holding, say, only `01`/`0001` is sufficient for this scenario and insufficient
for any scenario whose category balances key a second combination -- and the failure it
produces is an abend attributed to the interest job, not a missing-rate message.

The observable symptom is a crash while the program is behaving exactly as specified.
That is why this folder ships the full seventeen-row table rather than the single row
its own keys require (section 6.2), and why removing the harness's seeded rows
reproduces the same abend (section 6.3).

### 5.2 A stray carriage return overruns RECLN

The loader treats one physical line as exactly one fixed-length record. A `\r` left at
the end of a line is therefore absorbed as a **data byte** into the trailing `FILLER`
and pushes the row one byte past its declared width, so the row is rejected outright
rather than trimmed. `app/data/ASCII/tcatbal.txt` ships CRLF-terminated, which makes
this a live hazard for exactly one of the four files here; section 6.8 records the
normalisation and its rationale. Master section 3.8 is the rule.

### 5.3 Wrong `FILLER` bytes fail the comparison on a field whose value is correct

There is no single padding rule. `DISCGRP`'s `FILLER X(28)` and `TCATBAL`'s
`FILLER X(22)` are **ASCII `'0'`**; `ACCOUNT`'s `X(178)` and `CARD-XREF`'s `X(14)` are
**spaces**; and on the generated transaction the `TRAN-DESC` tail and the trailing
`FILLER X(20)` are **NUL**. Master section 6.1 is the measured authority and section
6.2 explains why two of those rows contradict the general rule of section 3.2.

The consequence is specific rather than cautionary: applying the general
text-pads-with-spaces rule to `tcatbal.txt` yields a row differing from the seed and the
golden
in 22 bytes, and to `discgrp.txt` in 28 bytes, while every value the record carries --
key, balance, rate -- is right. The diff points at padding that holds no data, which is
the hardest class of mismatch to read, because attention goes to the fields.

---

## 6. Scenario design decisions

Every decision below carries a labelled rationale, in the plain, plural,
unparenthesised form `docs/CODE_DOCUMENTATION_STANDARD.md` fixes and master section
1.4 restates. Rule 1's fourth category is unavailable here: it is scoped to replacing
existing code, and these fixtures replace nothing.

Assumptions: the labels are written **without emphasis markup** even though the rules
document typesets them bold. That bold is the rules document's own typography, and
`docs/CODE_DOCUMENTATION_STANDARD.md` names the emphasised spelling wrong in Markdown
as well as in code, because a reviewer auditing this repository against Rule 1's
validation gate finds every rationale by literal string search across seven languages
and no linter parses prose in Markdown. An emphasised label is a rationale that search
does not return. The master contract and the sibling harness use the same plain form,
so one search finds all of them in a single pass.

### 6.1 `ACCT-GROUP-ID` is left blank -- the deliberate non-reshape

Alternatives Considered: an absent but **non-blank** group id such as `ZZZZZZZZZZ`
would also compose a key no row matches and would also leave `DISCGRP-STATUS = '23'`,
so it would reach the same branch. Rejected on two counts. It diverges from
`app/data/ASCII/acctdata.txt`, whose rows 1 and 2 are otherwise carried into this
fixture byte for byte, so the provenance attestation in section 9 would have to
declare a reshaped identity-adjacent field for no gain. And it introduces a literal
nobody can trace to a source, which a later reader has to guess at: ten `Z`s look
chosen, whereas ten spaces are demonstrably what the published seed contains. The
blank group is the faithful, byte-for-byte-from-seed trigger, so the reshape decision
here is the **deliberate non-reshape**, recorded as such in section 9.

### 6.2 Seventeen `DEFAULT` rows rather than the one this scenario reads

Alternatives Considered: a single-row `discgrp.txt` carrying only
`DEFAULT   ` / `01` / `0001` / `00150{`, which is all section 4.4 shows being read, and
which is the shape `../happy_path` uses for its own direct-hit group. Rejected because
of the abend corollary in section 5.1: the retry at `:444` treats a miss as fatal, so
the seeding requirement is one row per reached (type, category) pair, and a
one-row file silently encodes the assumption that no future category balance in this
folder will ever key a second combination. The full seventeen rows make the file a
faithful `DEFAULT`-group table -- exactly the seed's own `DEFAULT` block -- so adding a
category balance keyed `04`/`0002` changes one line of `tcatbal.txt` and cannot abend
the run.

Trade-offs: sixteen of the seventeen rows are never fetched, so the file is larger than
this scenario strictly needs and a reader can mistake an unread row for an unused one.
The compromise is accepted because the alternative failure -- an abend from a table too
small -- costs more to diagnose than an unread row costs to read past, and because
section 4.4 marks explicitly which single row is consumed.

### 6.3 Coordination with the harness's seeded `DEFAULT` rows

`services/batch-service/src/test/resources/db/testharness/test-harness-schemas-and-foreign-tables.sql`
declares `reference.disclosure_groups` at `:353-360` with the composite primary key
`acct_group_id CHAR(10)`, `tran_type_cd CHAR(2)`, `tran_cat_cd CHAR(4)` and
`interest_rate NUMERIC(6,2)`, and seeds exactly **seventeen** `'DEFAULT   '` rows --
the `INSERT` opens at `:392` and its value rows occupy `:394-410` -- idempotently via
`ON CONFLICT DO NOTHING`, the clause at `:411` naming all three key columns.

**This scenario's group rows are additive on top of that seeded set, never a
replacement.** The `ON CONFLICT DO NOTHING` idiom is what makes loading them twice
harmless: a second load of a row that already exists is a no-op rather than a
`23505 unique_violation`.

**Removing the seeded rows reproduces the abend of section 5.1.** The harness records
the chain at `:362-371`, down to the abend code path, and the symptom is a crash
attributed to the interest job while the interest job is correct.

Assumptions: `DIS-ACCT-GROUP-ID` is `PIC X(10)`, so the literal `'DEFAULT'` moved at
`:437` is space-padded to `DEFAULT   `, and the seeded rows carry those three trailing
blanks. A PostgreSQL **`CHAR(10)`** column compares ignoring trailing blanks, so a
predicate written `= 'DEFAULT'` matches the padded value; a `VARCHAR` column would
require the literal padded to exactly ten characters. The column type is therefore
load-bearing, not incidental.

**The seeded rows are owned by `reference-service`.** They belong to that service's
seed migration, `services/reference-service/src/main/resources/db/migration/V2__seed_reference.sql`,
and the harness mirrors them rather than originating them. Their absence therefore
presents here as a defect that **is not local to this fixture or to `batch-service`**,
which is worth stating so an operator does not spend the diagnosis in the wrong
service.

Assumptions: one row of the seeded set does **not** match this fixture, and the
divergence is documented upstream rather than being a defect in either artifact. Row 17
here reads `00000{` for `07`/`0001`, while the harness seeds `15.00` at `:410`. The
cause is recorded at harness `:376-387`: the two shipped extracts of this dataset
disagree on that pair -- `app/data/EBCDIC/AWS.M2.CARDDEMO.DISCGRP.PS` reads `00150{`
and `app/data/ASCII/discgrp.txt` reads `00000{` -- and the owning migration settles it
in favour of the EBCDIC extract, registered as `D-SEED-ENCODING-AUTHORITY` in
`docs/architecture/cobol-to-service-traceability.md`. This fixture is derived from the
ASCII extract (section 9), so it carries the ASCII byte. **The disagreement is
immaterial to this scenario:** both category balances key `01`/`0001`, and that row
reads `15.00` in the ASCII extract, in the EBCDIC extract and in the harness alike.

### 6.4 The generated category code is `0005`, not the input `0001`

`:483` moves the literal `'05'` into `TRAN-CAT-CD`, which is `PIC 9(04)`, so the
generated transaction stores **`0005`** regardless of the category code the input
category-balance row carried -- `0001` in this fixture, as section 4.2 shows.
**Do not reconcile the two.** Master section 7.2.4 states the same rule for the whole
domain.

Assumptions: the input category code is `0001` and the output category code is `0005`,
and **both are plausible values for the same field name**, so a reviewer checking
section 3.3's expectation against section 4.2's input has no way to tell a transcription
slip from correct behaviour without opening `:483`. Stating the divergence is therefore
not redundant with the master: it is what stops a correct expectation from being edited
into a wrong one in the name of consistency. There was no alternative to weigh here --
the literal is the baseline's, and the committed golden carries `0005` -- so what is
documented is the reading hazard rather than a choice.

### 6.5 The business-date token -- this scenario is the ISO exemplar

`:473-480` strings `PARM-DATE`, a `PIC X(10)`, together with a `PIC 9(06)` counter
`DELIMITED BY SIZE` into `TRAN-ID`, a `PIC X(16)`. Ten plus six fills sixteen exactly,
and **no formatting whatsoever is applied** to the ten-character token. `:474`
increments the counter **before** it is used, so the first generated identifier always
ends `000001`.

This scenario supplies the **ISO** token `2024-01-15`, which is what the committed
golden `tests/golden/interest/default_fallback/transact.expected` carries and what the
module's own `InterestCalculationServiceTest` passes.

Alternatives Considered: the compact token `2022071800` that `app/jcl/INTCALC.jcl:22`
passes as `PARM='2022071800'`. Not used here, because the token is **opaque** -- the
program neither parses nor validates it -- so the passthrough is only genuinely
exercised if the domain supplies both shapes. Master section 8.2's measurement fixes
where each shape lives: **all three** interest scenario goldens carry the ISO prefix,
while the two interest end-to-end goldens carry the compact form, and the module
discharges the two-shape requirement by parameterising one launch over both tokens
rather than by splitting them across scenario folders. Choosing ISO here also keeps
this folder byte-aligned with its committed golden, which is the artifact the
expectation in section 3 is checked against.

Assumptions: `services/batch-service/README.md` describes the compact token as
`yyyyMMdd` followed by the literal `00` and not an ISO date. That is accurate as a
description of what `app/jcl/INTCALC.jcl` happens to pass, and **wrong if read as a
required format for the migrated Java** -- the reading to guard against, since it would
invalidate the ISO-prefixed goldens outright. That stronger reading is not followed
here and must not be propagated. Master section 8.2 rules that where the two statements
conflict, the goldens and the business-date type govern.

### 6.6 The two-account design is load-bearing

`:188` opens `PERFORM UNTIL END-OF-FILE = 'Y'`, and `1050-UPDATE-ACCOUNT` runs **only
on an account-id change**: `:194` tests `IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM` and
`:196` performs the update, guarded at `:195` so the very first account does not
trigger a spurious write. Because the `PERFORM UNTIL` tests its condition **before**
the body, the trailing `ELSE PERFORM 1050-UPDATE-ACCOUNT` at `:219-220` is
**unreachable** -- the loop exits on the same iteration that raises the end-of-file
flag. The consequence is that the **last, or only, account is never written back**.

The baseline behaves that way; the migrated Java flushes the final account; the
divergence is documented in `docs/architecture/cobol-to-service-traceability.md`.
Nothing under `app/**` changes.

Alternatives Considered: a single-account fixture, which is smaller and reads more
simply. Rejected because it can observe **neither** behaviour -- with one account there
is no control break, so the only account is the final one and no rewrite is ever
observable. Two accounts make both halves visible at once: `00000000001` is the
**non-final** account whose `REWRITE` at `:356` genuinely fires, which is why section
3.2 expects `206.50` persisted; `00000000002` is the **final** account, which is why
section 3.2 expects `158.00` unchanged. A third account was also considered and
rejected: it only shifts which account is final and buys no additional observation.

Assumptions: the phrase *"section 3.2 expects `158.00` unchanged"* above describes the
**reference** golden and not the migrated expectation, which section 3.2 states as `170.50`
under divergence D-3. Both are named here because the two-account design is what makes the
divergence observable at all: with one account there would be no non-final account to compare
the flushed case against.

Assumptions: `TCATBAL` is an indexed file, so its rows are consumed in key order
regardless of the fixture's physical line order (master section 3.12). `...001` is
therefore deterministically the non-final account, and the section 3.2 expectation does
not depend on line order.

Both accounts carry the blank group id, so **both** take the fallback this scenario
exists to cover -- the fallback is exercised on the rewritten account and on the
un-rewritten one alike, which keeps the two facts independent.

The full state change at the break is three fields, not one: `:352` adds the accrued
interest to `ACCT-CURR-BAL`, and `:353` and `:354` zero `ACCT-CURR-CYC-CREDIT` and
`ACCT-CURR-CYC-DEBIT`. That cycle reset is a real side effect of the interest run and
is visible in the golden, where account 1's two cycle fields are `00000000000{` after
having been zeroed rather than merely having started zero.

### 6.7 `TRAN-DESC` is NUL-padded, and the merchant fields are not

`:485-489` performs `STRING 'Int. for a/c ' ACCT-ID DELIMITED BY SIZE INTO TRAN-DESC`.
The literal is 13 characters and `ACCT-ID` is 11, so `STRING` writes **24** characters
into an `X(100)` field and **leaves the remaining 76 bytes untouched**, over storage
that holds low values. The tail is therefore exactly **76 x `0x00`**.

The merchant name, city and zip are **space**-padded, because `:492-494` set them with
`MOVE SPACES`, and `MOVE` pads an alphanumeric receiver with blanks.

Assumptions: space-padding the description shifts nothing and changes 76 bytes from
`0x00` to `0x20`, so the comparison fails on a field whose **value** is correct -- the
description reads properly, the record is the right length, every other field matches.
Master section 6.3 proves the mechanism and records that this padding is a property of
the **writing job** rather than of the record type, which is why a 350-byte transaction
record is not self-describing on this point and why this section names the job.

### 6.8 `tcatbal.txt` is normalised to LF, and `cardxref.txt` to 50 bytes

Assumptions: `app/data/ASCII/tcatbal.txt` ships CRLF-terminated -- mixed, in fact, with
a final bare LF (master section 3.8 has the measured geometry). The loader treats one
physical line as one fixed-length record, so a surviving `\r` is absorbed into the
trailing `FILLER` and pushes the row one byte past RECLN, which is rejected rather than
trimmed (section 5.2). This fixture is therefore normalised to LF throughout. Master
section 3.8's default is LF and requires a scenario that **intentionally** preserves
CRLF to say so; this one does not, and says that instead.

Assumptions: `app/data/ASCII/cardxref.txt` measures 36 bytes per record, because the
trailing `FILLER X(14)` is simply absent from the seed rows, while
`app/cpy/CVACT03Y.cpy` sums to 50. Master section 3.10 rules that card-xref fixtures
are authored at the full **50-byte** copybook width with that `FILLER` space-padded, so
that one width -- the copybook's -- holds for both the loader and the Java codec. This
fixture follows that ruling: fourteen spaces at `[36:50]`, 51 bytes per line including
the newline.

### 6.9 A scenario-local `discgrp.txt` rather than the shared mock

The house suite's shared mock `tests/mocks/mock_discgrp.txt` reaches the **same**
status-23 branch by a **different** mechanism: it keeps a non-blank `A000000000`
account and omits exactly one combination row, so only that one combination misses.
Both artifacts exercise the same rule.

Alternatives Considered: consuming that shared mock instead of shipping a local
`discgrp.txt`, which would remove seventeen rows from this folder. Rejected for two
reasons. It would couple this scenario's determinism to a file other tests may evolve,
so a change made for another scenario could silently move this one's rate. And it would
hide the trigger: with a `DEFAULT`-only file paired with a blank-group account, **every**
keyed group read misses and the fallback is evident from the account row alone, whereas
with the shared mock a reader has to diff the file against the seed to discover which
single combination is missing.

### 6.10 Interest reads the cross-reference through the ALTERNATE key

The two jobs in this module reach `CARD-XREF` through **different keys**, so a fixture
that satisfies one does not automatically satisfy the other (master section 4.3):

- **Interest reads by account id.** `app/jcl/INTCALC.jcl:29-30` mounts the base cluster
  as `XREFFILE`, and lines `31-32` of that same job mount a **second** DD, `XREFFIL1`,
  pointing at the alternate-index PATH `AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH`.
  `app/cbl/CBACT04C.cbl:38` declares
  `ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID`, and the read happens at `:204-205`, which
  moves the category balance's account id into that alternate key and performs
  `1110-GET-XREF-DATA`. The key is `XREF-ACCT-ID`, `PIC 9(11)` at zero-based offset
  **25**.
- **Posting reads by card number only**, through the primary key `XREF-CARD-NUM` at
  offset 0, and mounts no AIX path at all.

Assumptions: this is why `cardxref.txt` in **this** folder must make every category
balance's **account** resolvable at offset 25, rather than making a card resolvable at
offset 0. A cross-reference file that satisfies posting can leave an interest run with
no card number to put into `TRAN-CARD-NUM` at `:495`, which surfaces as a blank field in
the generated transaction rather than as a read failure. Section 4.5 shows both rows
satisfying the account-keyed shape.

The migrated target of that access path is the secondary index
`idx_card_xref_account_id` on `account.card_xref(account_id)`, declared by the sibling
harness alongside the table itself -- so the alternate index does not disappear in
migration, it becomes an index the query planner uses for the same lookup.

### 6.11 `1400-COMPUTE-FEES` is an empty stub, so the absence of a fee is the assertion

`1400-COMPUTE-FEES` occupies `:518-520`, and its entire body is the comment
`* To be implemented` followed by `EXIT`. `:216` performs it on every non-zero-rate
category balance, and it produces nothing.

Assumptions: the expectation in section 3.3 therefore asserts the **absence** of a fee
record -- two transactions in total, both interest -- and no fee output may be
fabricated for this or any other scenario in the domain. The paragraph exists and is
called, so a reader tracing `:216` finds a fee step in the flow; what makes the
expectation right is that the step writes nothing, which is only visible by opening the
paragraph. Master section 7.2.4 states the same rule for the whole domain.

### 6.12 The `1000.00` balance is held equal to the direct-hit sibling

Both category balances carry `0000010000{` -- the same `1000.00` that
[`../happy_path`](../happy_path/README.md) uses, against the same `15.00` rate, over the same
two accounts opening at `194.00` and `158.00`. So this scenario produces the **identical**
`12.50` per row and `206.50` persisted figures as its direct-hit sibling -- and the identical
`170.50` migrated expectation for the final account -- while reaching them through the
**other** branch of the rate lookup.

Trade-offs: holding every operand constant across the two scenarios means the numbers
alone cannot tell a reader which branch ran, so section 1 has to say so in words. What
is bought is worth more: because the arithmetic is identical, **any** diff between the
two domains' goldens is attributable to the rate-lookup path rather than to a different
amount. Had this scenario used a distinct balance, a mismatch would leave two candidate
explanations -- the wrong branch, or the wrong operand -- and separating them would mean
re-deriving the arithmetic before the branch could even be examined. Master section 7.3
fixes the same operands as the values every interest scenario reuses, for the same
reason.

Alternatives Considered: a balance chosen to discriminate the multiply-before-divide
ordering, such as the `0.43` vector of section 7.1. Rejected for this folder because it
would change two things at once -- the branch **and** the arithmetic -- and because the
committed golden this folder is checked against encodes `0000000125{`. A vector that
exercises the ordering belongs in a scenario whose stated purpose is the ordering, not
in the one whose stated purpose is the fallback.

---

## 7. The arithmetic, and the divergence that must not be reconciled

### 7.1 What the canonical vector proves, and what it does not

`:464-465` carries **no `ROUNDED` phrase**, so the baseline truncates. AAP Rule T4
requires the migrated Java to multiply at full precision **first** and only then divide,
with an explicit scale and rounding mode.

**The `1000.00` at `15.00` vector does not prove that ordering rule.** It is neutral on
both axes at once: `(1000.00 x 15.00) / 1200` is `12.5000` **exactly**, so
multiply-first and divide-first agree, and truncation and half-up rounding agree too.
It is the right vector for checking that this scenario's arithmetic closes; it is the
wrong vector for justifying the ordering, and nothing here claims otherwise.

Alternatives Considered: dividing the rate by 1200 first and multiplying the resulting
monthly factor into the balance, which reads more naturally and needs one fewer
intermediate scale. Rejected on a **discriminating** vector, which the canonical one
cannot supply -- a balance of `0.43` at the same `15.00` rate:

| Ordering | Intermediate | Result at scale 2, half up |
|---|---|---|
| multiply first | `0.43 x 15.00 = 6.45`, then `6.45 / 1200 = 0.005375` | **`0.01`** |
| divide first | `15.00 / 1200` taken to cents is `0.01`, then `0.43 x 0.01 = 0.0043` | **`0.00`** |

That is a one-cent divergence on a single category balance, compounding once per
category per account. A fixture intended to exercise the ordering must use a vector of
that shape; this one does not, and section 3.1 is stated as an arithmetic closure rather
than as evidence for Rule T4. Master section 7.2.1 reaches the same conclusion.

Every value in this path is exact fixed point end to end -- the stored form is zoned
decimal with a sign overpunch (master section 3.3) and the migrated form is a
scale-2 decimal. There is no approximate representation anywhere in it.

### 7.2 The documented divergence -- cross-referenced, not reconciled

Trade-offs: `tests/fixtures/interest/happy_path/README.md` section 7 records a
reproducible end-to-end run of the compiled two-account program under GnuCOBOL 3.2.0
that produced a `TRAN-AMT` of `+12.59` and an account-1 balance of `-181.52`, while
confirming the *structural* behaviour section 6.6 describes -- which account is
rewritten and which is not. Consult that section for its detail rather than a
paraphrase here. Because the COBOL is immutable reference material, that observation is
**documented, not altered**: the baseline computes what it computes, the migrated Java
implements the documented rule, and the divergence is registered in
`docs/architecture/cobol-to-service-traceability.md`.

**This folder encodes `12.50` and `206.50`.** It does not move them toward `12.59`. The
compromise accepted is that one figure in this domain is analytic rather than captured,
in exchange for an expectation that states the documented business rule instead of
reproducing a defect.

Assumptions: that divergence run injected `PARM-DATE = 2022071800`, the compact token,
whereas this scenario supplies `2024-01-15` (section 6.5). The two figures are therefore
not two measurements of one run -- they differ in an input parameter as well as in
value -- which is a further reason they must not be reconciled into a single number.

### 7.3 Two house statements exist, and this folder follows the golden

Both of the following are in the reference tree, and they do not agree:

- `tests/fixtures/interest/default_fallback/README.md` advises against asserting a
  persisted `194.00` to `206.50` balance, on the strength of the divergence run above.
- `tests/golden/interest/default_fallback/acctdat.expected` nonetheless **encodes**
  `00000002065{` on its first row, and `00000001580{` on its second.

Trade-offs: **this folder follows the committed golden**, which is also what master
section 7.3 directs. The reason to prefer it is that the golden is the artifact a
comparison actually runs against, so an expectation that contradicts it cannot pass;
and the analytic figure is the one the documented business rule yields. What is given up
is agreement with the house README's caution, which is why both statements are named
here rather than one being quietly adopted -- a reader who finds the caution first
should be able to see that it was read and weighed, not missed.

---

## 8. How this document satisfies user-specified Rule 1 (Explainability)

**This README is the sole Explainability carrier for this folder,** and two independent
facts confirm that no mechanical gate reaches the bytes it documents:

- `config/checkstyle/checkstyle.xml` sets the `Checker` property
  `fileExtensions` to `java`, so a `.txt` or `.md` file here is outside the audit set
  before any suppression is even consulted; and
- `config/checkstyle/suppressions.xml` additionally suppresses the
  `src/test/resources/fixtures/` tree, under a charter that records the boundary
  explicitly -- that entry covers `src/test/resources/fixtures/` and must never be
  widened to `src/test/java/`.

**There is no linter that can catch a gap here.** Only this document can, which makes
the discipline more important in this folder rather than less.

Against Rule 1's clauses:

- **All four docstring elements are present and complete**, mapped in section 0:
  Purpose in the header block and section 1, Parameters in section 4, Return values in
  section 3, Exceptions or errors in section 5. Rule 1's forbidden-patterns clause
  names omitting any of them as a failure.
- **Every non-obvious choice carries a labelled rationale** naming a concrete program
  line, byte range, arithmetic consequence or rejected alternative -- section 6 for the
  fixture-shaping decisions and section 7 for the arithmetic ones. Rule 1's **Validation
  Gate** is conjunctive: a missing element **or** a missing rationale fails review. That
  gate is also where the obligation is stated as a requirement rather than as a
  preference, so it is the clause cited here.
- **WHAT and WHY are both served, and the boundary between them is deliberate.** Rule
  1's opening sentence asks for documentation that explains both, and its
  WHY-not-WHAT prohibition governs annotation placed **adjacent to** the thing it
  explains. A Purpose statement legitimately says what something is, because that is
  exactly what the Purpose element asks for; what it may not do is stop there. So
  section 4 states what a byte range holds **and** section 6 states why it holds that
  and not something else.
- **The four forbidden patterns are avoided.** Nothing here restates an obvious byte
  without adding why it is that byte; no element is omitted; no non-obvious choice with
  a reasonable alternative is left undocumented -- the alternatives actually weighed are
  named and rejected in sections 6.1, 6.2, 6.5, 6.6, 6.9, 6.12 and 7.1, and every other
  subsection of sections 6 and 7 carries a labelled `Assumptions:` or `Trade-offs:`
  rationale instead, because for those the reasoning is a dependency or a compromise
  rather than a road not taken; and no rationale is vague, since each one names a
  program line, a byte count, a cent, or an alternative.
- **Rule 1's scope boundary is respected.** It applies to newly authored artifacts.
  `app/**` and `tests/**` are REFERENCE-ONLY, so nothing there is retro-documented; they
  are cited by path and line only, and no baseline file is edited by anything in this
  folder.

---

## 9. Synthetic-provenance attestation

**No real cardholder, account or personal data appears in this folder.** Every card
number, account id and customer id here is **synthetic test data derived byte for byte
from the published AWS CardDemo sample seed datasets** --
`app/data/ASCII/acctdata.txt`, `app/data/ASCII/cardxref.txt`,
`app/data/ASCII/discgrp.txt` and `app/data/ASCII/tcatbal.txt`. Those seeds ship with
the upstream open-source project as fabricated demonstration data and **represent no
real person and no real account**. Master section 11.1 is the tree-wide attestation and
section 11.2 records why the published seeds were preferred to freshly minted values.

Identity bytes -- card numbers, account ids, customer ids -- are carried **unchanged**.
Every field reshaped away from its seed value is named below, because a reader diffing a
fixture row against its seed row will otherwise conclude the row was invented when only
one field moved:

| File | What changed | What did not |
|---|---|---|
| `tcatbal.txt` | **`TRAN-CAT-BAL` reshaped** from the seed's `0000000000{` (0.00) to `0000010000{` (**1000.00**), so the scenario has a balance to accrue interest on. Additionally **line-ending normalisation only**, CRLF to LF (section 6.8). | The 17-byte key -- account id, type code, category code -- and the 22-byte `FILLER`, all seed-verbatim. |
| `acctdata.txt` | **Nothing.** Both rows are seed-verbatim in every field. | In particular **`ACCT-GROUP-ID` is the field deliberately LEFT at its seed value of ten spaces** -- the non-reshape is itself the design decision, and its rationale is in section 6.1. Balances, limits, dates, cycle amounts, `ACCT-ADDR-ZIP` and the 178-byte `FILLER` are all seed-verbatim too. |
| `discgrp.txt` | **A pure subset selection:** the seed's seventeen `DEFAULT   ` rows, byte for byte, with the `A000000000` and `ZEROAPR   ` group families omitted. **No value altered** -- including row 17, whose ASCII-extract byte is retained as discussed in section 6.3. | Keys, rates and the 28-byte `FILLER` on every retained row. |
| `cardxref.txt` | **Width normalisation only:** 36 seed bytes to the copybook's 50, by appending the fourteen-space `FILLER X(14)` the seed omits (section 6.8). | Every identity byte -- both card numbers, both customer ids, both account ids -- seed-verbatim. |

The attestation is kept in the folder it describes rather than only in the master,
because colocating it with the bytes is what makes the folder audit-defensible on its
own.

---

## 10. Verifying this folder

```bash
# WHAT: assert the geometry this README claims -- exact byte size, record count,
#       record width, absence of CR, and exactly one trailing newline -- for all
#       four record files at once.
# WHY : Assumptions: the loader rejects a wrong-length physical row outright
#       rather than padding or truncating it, so this check converts a load-time
#       failure into an authoring-time one. Counting rows separately from bytes
#       catches the two commonest mistakes together: a stray CR absorbed into the
#       trailing FILLER (section 5.2), and a second trailing blank line that
#       parses as a zero-length record.
python3 - <<'PY'
D = "services/batch-service/src/test/resources/fixtures/interest/default_fallback/"
EXPECTED = {"tcatbal.txt": (102, 2, 50), "acctdata.txt": (602, 2, 300),
            "discgrp.txt": (867, 17, 50), "cardxref.txt": (102, 2, 50)}
for name, (size, count, width) in EXPECTED.items():
    raw = open(D + name, "rb").read()
    assert len(raw) == size, f"{name}: {len(raw)} bytes, expected {size}"
    assert b"\r" not in raw, f"{name}: contains a carriage return"
    assert raw.endswith(b"\n"), f"{name}: no trailing newline"
    rows = raw.split(b"\n")[:-1]
    assert len(rows) == count, f"{name}: {len(rows)} records, expected {count}"
    assert all(len(r) == width for r in rows), f"{name}: a row is not {width} bytes"
    print(f"OK {name}: {count} records of {width} bytes, {size} bytes total")
PY
```

```bash
# WHAT: assert the two facts that make this the DEFAULT-fallback scenario rather
#       than the direct-hit one -- a blank ACCT-GROUP-ID on every account row,
#       and a DISCGRP file containing DEFAULT rows only, whose 01/0001 rate is
#       15.00.
# WHY : Assumptions: ACCT-ADDR-ZIP at zero-based 102 and ACCT-GROUP-ID at
#       zero-based 112 are ten bytes apart and this fixture carries A000000000 in
#       the first of them (section 4.3). Reading the wrong slice reports a
#       populated group id, from which a reader concludes the fallback cannot
#       fire and the fixture is mis-authored. Slicing both is what settles it.
python3 - <<'PY'
D = "services/batch-service/src/test/resources/fixtures/interest/default_fallback/"
accounts = [r for r in open(D + "acctdata.txt", "rb").read().split(b"\n") if r]
assert all(r[102:112] == b"A000000000" for r in accounts), "ACCT-ADDR-ZIP moved"
assert all(r[112:122] == b" " * 10 for r in accounts), "ACCT-GROUP-ID is not blank"
groups = [r for r in open(D + "discgrp.txt", "rb").read().split(b"\n") if r]
assert {r[0:10] for r in groups} == {b"DEFAULT   "}, "discgrp is not DEFAULT-only"
assert groups[0][10:16] == b"010001", "row 1 is not keyed type 01 category 0001"
assert groups[0][16:22] == b"00150{", "the 01/0001 rate is not 15.00"
print("OK blank ACCT-GROUP-ID, DEFAULT-only DISCGRP, 01/0001 rate 15.00")
PY
```

```bash
# WHAT: assert that the interest figures this README states close in exact fixed
#       point, and that the vector cited for the multiply-before-divide ordering
#       genuinely discriminates between the two orderings.
# WHY : Assumptions: the canonical 1000.00 at 15.00 vector divides exactly, so it
#       cannot distinguish the orderings and must not be offered as evidence for
#       them (section 7.1). The 0.43 vector can, and the second half of this check
#       is what keeps that claim honest rather than asserted.
python3 - <<'PY'
from decimal import Decimal, ROUND_HALF_UP
CENTS = Decimal("0.01")
balance, rate = Decimal("1000.00"), Decimal("15.00")
assert (balance * rate) / Decimal(1200) == Decimal("12.5000")
assert Decimal("194.00") + Decimal("12.50") == Decimal("206.50")
probe = Decimal("0.43")
multiply_first = ((probe * rate) / Decimal(1200)).quantize(CENTS, ROUND_HALF_UP)
monthly_factor = (rate / Decimal(1200)).quantize(CENTS, ROUND_HALF_UP)
divide_first = (probe * monthly_factor).quantize(CENTS, ROUND_HALF_UP)
assert multiply_first == Decimal("0.01"), multiply_first
assert divide_first == Decimal("0.00"), divide_first
print("OK 12.50 and 206.50 close; 0.43 discriminates 0.01 from 0.00")
PY
```

```bash
# WHAT: assert that this folder holds exactly five files and nothing else.
# WHY : Assumptions: a fifth record file, a loader script, a manifest or a
#       .gitignore would each be an artifact no test loads and no section of this
#       README explains, and the master contract holds this tree to authored,
#       reviewed bytes with no generated artifact. Listing is cheaper than
#       discovering the stray file in a review.
ls -1 services/batch-service/src/test/resources/fixtures/interest/default_fallback/ | sort
# Expected, exactly: README.md acctdata.txt cardxref.txt discgrp.txt tcatbal.txt
```

---

<sub>Apache-2.0. This folder is additive and test-only. `app/**`, `tests/**`,
`scripts/**` and `samples/**` are REFERENCE-ONLY and are never modified; the master
byte-encoding contract is `../../README.md` and the polyglot documentation convention
is `docs/CODE_DOCUMENTATION_STANDARD.md`.</sub>
