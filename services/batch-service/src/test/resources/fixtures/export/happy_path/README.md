```text
# =============================================================================
# services/batch-service/src/test/resources/fixtures/export/happy_path/README.md
# -----------------------------------------------------------------------------
# Purpose:
#       The docstring for the five sibling fixed-width byte images in this
#       directory -- acctdata.txt, carddata.txt, cardxref.txt, custdata.txt and
#       trandata.txt. Those five files are the module; this file is the only
#       place their intent, the rule they exercise, their expected outcome,
#       their byte geometry and their provenance can be written down. It states
#       what is specific to THIS scenario and nothing that the tree-level
#       contract already owns.
#
# Source of truth:
#       The COBOL baseline, read and cited by path and line, never modified:
#       app/cbl/CBEXPORT.cbl and app/cbl/CBIMPORT.cbl for behaviour,
#       app/cpy/CVEXPORT.cpy for the export record, and app/cpy/CVACT01Y.cpy,
#       CVACT02Y.cpy, CVACT03Y.cpy, CVCUS01Y.cpy and CVTRA05Y.cpy for the five
#       input layouts. Byte values were measured from the committed fixtures and
#       from their seeds under app/data/ASCII/ rather than inferred.
#
# Authority above this file:
#       services/batch-service/src/test/resources/fixtures/README.md is the
#       MASTER byte-encoding contract for this tree. This file cites it BY
#       SECTION and neither restates nor contradicts it (master section 1.3).
#       Where this file gives a byte range, that is this scenario's geometry;
#       where it needs a padding byte, an overpunch table or a normalisation
#       set, it points at the master section that owns it.
#
# Why this file exists:
#       user-specified Rule 1 (Explainability), not the migration plan. See
#       section 9.
# =============================================================================
```

---

## 1. Scenario intent

This is the deterministic five-master input corpus for the `CBEXPORT` / `CBIMPORT`
**500-byte packed-record round trip**. Five typed master files go in; one 500-byte
export record is assembled per input record; each type is then re-materialised from
that record independently. The scenario represents the unexceptional case -- every
input record is well formed, every width is exact, every key resolves in ascending
order, and nothing in the corpus is shaped to provoke a reject, a fallback or an
overflow.

**This is the only scenario under `fixtures/export`, and it is the only one
authorized.** That is a property of the domain rather than an omission: the export
contract is byte-exact round-trip identity, and a round trip either reproduces its
input bytes or it does not. Sibling domains carry many scenarios because they encode
graded business rules with boundaries on either side; this domain has one outcome to
assert, so it has one scenario.

### 1.1 Where the five record files come from in the plan, and where this file does not

The five `.txt` files are called for by the migration plan. This README is not.

| Artifact | Authority |
|---|---|
| The five `.txt` records | AAP section 0.2.1.2 -- `services/*/src/test/resources/**` is CREATE, for *"fixture records derived from the copybook layouts"* |
| Their location in this tree | AAP section 0.4.1.2 -- the uniform per-service shape includes `src/test/resources/fixtures/**` |
| The programs they exercise | AAP section 0.5.1.7 -- the `{ExportJob,ImportJob}.java` row, whose declared sources are `app/cbl/CBEXPORT.cbl`, `app/cbl/CBIMPORT.cbl` and `app/cpy/CVEXPORT.cpy` |
| **This README** | **user-specified Rule 1 (Explainability)** -- no row of the plan asks for documentation at this path. Section 0.2.1.2 asks for *records*. Section 9 records the reasoning in full |

The master contract reaches the same conclusion for the whole tree at its section 10,
so the obligation is not local to this directory.

---

## 2. The rule under test, cited by program and line

### 2.1 The defect the round trip is written around

Both programs declare an indexed file keyed on a field that their own file record does
not contain:

| Program | `ORGANIZATION IS INDEXED` | `RECORD KEY IS EXPORT-SEQUENCE-NUM` | The FD record it actually declares |
|---|---|---|---|
| `app/cbl/CBEXPORT.cbl` | `:66` | **`:68`** | `:92` -- `01 EXPORT-OUTPUT-RECORD PIC X(500).` |
| `app/cbl/CBIMPORT.cbl` | `:38` | **`:40`** | `:79` -- `01 EXPORT-INPUT-RECORD PIC X(500).` |

The FD record in each case is a **flat `PIC X(500)`** with no subordinate fields at
all. `EXPORT-SEQUENCE-NUM` is defined by `CVEXPORT.cpy`, and that copybook is copied
into `WORKING-STORAGE`, not into the file section:

| Program | `WORKING-STORAGE SECTION.` | `COPY CVEXPORT.` |
|---|---|---|
| `app/cbl/CBEXPORT.cbl` | `:94` | `:96` |
| `app/cbl/CBIMPORT.cbl` | `:111` | `:113` |

So the key field exists **only in working storage and not in the file record**. The
consequence is recorded in `tests/README.md` section 1.1, lines 48-69: the pair
**does not compile under the open-source compiler**, only **ten of the twelve** batch
programs build and run, and the export/import integration test
`tests/integration/test_export_import.py` is **skipped** with exactly that reason.

That is why this corpus exists on the Java side at all. The parity oracle cannot
execute this round trip, so the migrated implementation is the only place the
behaviour can be exercised, and these five files are its input.

### 2.2 What the round trip actually exercises

`CBEXPORT` tags each input type with a one-character discriminator and writes it into
the shared prefix of the export record. The five values, with the line that moves
each:

| Type | Discriminator | Set at | Re-materialised at |
|---|:-:|---|---|
| Customer | `'C'` | `CBEXPORT.cbl:274` | `CBIMPORT.cbl:273` |
| Account | `'A'` | `CBEXPORT.cbl:343` | `CBIMPORT.cbl:275` |
| Card cross-reference | `'X'` | `CBEXPORT.cbl:407` | `CBIMPORT.cbl:277` |
| Transaction | `'T'` | `CBEXPORT.cbl:462` | `CBIMPORT.cbl:279` |
| Card | `'D'` | `CBEXPORT.cbl:527` | `CBIMPORT.cbl:281` |

`CBIMPORT.cbl:272` is the `EVALUATE EXPORT-REC-TYPE` that dispatches on them. **Five
discriminators are why there are five input files**: the corpus is one file per type
so that every branch of that `EVALUATE` is reachable from a single scenario.

`CVEXPORT`'s `EXPORT-RECORD` is a 40-byte shared prefix followed by a 460-byte typed
data area:

| Field | `PICTURE` / `USAGE` | Width | Offset | Line |
|---|---|---:|---:|---|
| `EXPORT-REC-TYPE` | `X(1)` | 1 | 0 | `CVEXPORT.cpy:10` |
| `EXPORT-TIMESTAMP` | `X(26)` | 26 | 1 | `:11` |
| `EXPORT-SEQUENCE-NUM` | `9(9) COMP` | 4 | 27 | `:16` |
| `EXPORT-BRANCH-ID` | `X(4)` | 4 | 31 | `:17` |
| `EXPORT-REGION-CODE` | `X(5)` | 5 | 35 | `:18` |
| `EXPORT-RECORD-DATA` | `X(460)` | 460 | 40 | `:19` |

`1 + 26 + 4 + 4 + 5 = 40`, and **`40 + 460 = 500`**.

`EXPORT-TIMESTAMP-R REDEFINES EXPORT-TIMESTAMP` at `CVEXPORT.cpy:12` is an **overlay
that does not advance the offset** and contributes no bytes; the master's authoring-
hazard section 9.2 states what counting it would do to every later field.

### 2.3 All five typed branches close at exactly 460

Each of the five `REDEFINES` branches of `EXPORT-RECORD-DATA` re-reads the same 460
bytes. Summing the declared widths of each:

| Branch | Field widths | Sum |
|---|---|---:|
| CUSTOMER | `4+25+25+25+150+2+3+10+30+9+20+10+10+1+2+134` | **460** |
| ACCOUNT | `11+1+7+12+7+10+10+10+12+8+10+10+352` | **460** |
| TRANSACTION | `16+2+4+10+100+6+4+50+50+10+16+26+26+140` | **460** |
| CARD-XREF | `16+9+8+427` | **460** |
| CARD | `16+8+2+50+10+1+373` | **460** |

### 2.4 Correction C-WIDTH: `PIC S9(10)V99 COMP-3` is SEVEN bytes

`S9(10)V99` is twelve digit positions, and a packed field occupies
`ceil((digits + 1) / 2)` bytes, so `ceil(13 / 2) = 7`.

**At six bytes the ACCOUNT branch sums to 458 and fails to close.** The branch carries
two `S9(10)V99 COMP-3` fields, so the error is doubled and the shortfall is exactly two
bytes. **Any statement that `PIC S9(10)V99 COMP-3` is six bytes is defective and must
not appear anywhere.** The master's section 5.6 is the authority for the width rules;
this section records the arithmetic consequence for this scenario's account phase.

### 2.5 Physical width comes from `USAGE`, never from the `PICTURE`

One copybook proves it three times over with a single `PICTURE`:

| Line | Declaration | Physical width |
|---|---|---:|
| `CVEXPORT.cpy:50` | `EXP-ACCT-CURR-BAL PIC S9(10)V99 COMP-3` | **7** |
| `CVEXPORT.cpy:57` | `EXP-ACCT-CURR-CYC-DEBIT PIC S9(10)V99 COMP` | **8** |
| `CVEXPORT.cpy:51` | `EXP-ACCT-CREDIT-LIMIT PIC S9(10)V99` | **12** |

Three widths, one `PICTURE`. The same copybook also holds `S9(09)V99 COMP-3` at six
bytes in its transaction branch beside `S9(10)V99 COMP-3` at seven in its account
branch -- the same picture family, a different digit count, a different physical width.
Reading a width off the `PICTURE` alone is therefore never sufficient.

### 2.6 There is no 500-byte file among the inputs

**The 500-byte `CVEXPORT` `EXPORT-RECORD` is the job's OUTPUT.** It is assembled from
the five typed inputs; it is not read from disk here. The transaction input is a
`CVTRA05Y` `TRAN-RECORD` at **350** bytes, bound at `app/cbl/CBEXPORT.cbl:83-84`.
No file in this directory is 500 bytes per record for export reasons.

One coincidence is worth disarming before it misleads anyone: `custdata.txt` **is** 500
bytes per record, because `CVCUS01Y` is a 500-byte customer layout. That is an
unrelated artifact of the customer record's own width and has nothing to do with the
500-byte export record. The two numbers are equal and mean different things.

---

## 3. Expected outcome

A **byte-identical 500-byte round trip**. `CBEXPORT` assembles the export record from
the five typed inputs; `CBIMPORT` re-materialises each type independently from that
record; the re-materialised bytes equal the input bytes, per file and per record.

The migrated implementation decodes the record with the shared codecs only:

| Concern | Type | Location |
|---|---|---|
| `COMP-3` packed and `COMP` binary fields | `PackedDecimalCodec` | `services/common-lib/src/main/java/com/carddemo/common/codec/` |
| Zoned-decimal sign overpunch | `ZonedDecimalCodec` | same package |
| Offsets, widths and per-field flags | `CopybookLayout` | same package |

Per **AAP Rule T2**, shared codecs come **only** from `com.carddemo.common.*` and are
never re-declared per service. A locally re-declared overpunch or packed table would be
a second source of truth whose disagreement with the first would surface as a wrong
cent rather than as a compile error.

**The packed negative-zero carve-out.** A `0xD`-signed packed zero re-encodes as `0xC`,
because the target types have no negative zero to preserve. It is the packed analogue of
the zoned `}`-to-`{` normalisation and it is **the one documented non-byte-identical
round trip**; the master's section 3.6 is the authority for both halves. **No field in
this corpus carries a negative zero**, so the carve-out does not fire here -- it is
recorded because a reader verifying "byte-identical" needs to know the single exception
exists and that this scenario is not it.

**Packed bytes are binary and must never be routed through a character decoder.** A
packed field's nibbles and sign half-byte are not text in any encoding, so decoding a
record as a string before slicing it replaces unmapped bytes with a substitute
character and destroys the value while leaving the field the right width.

---

## 4. Fixture bytes and governance

### 4.1 Contents

Every figure below was measured from the committed bytes.

| File | Copybook / layout | RECLN | Bytes | Records | Provenance |
|---|---|---:|---:|---:|---|
| `custdata.txt` | `CVCUS01Y` / CUSTOMER | 500 | 2505 | 5 | verbatim copy of `tests/fixtures/provisioning/happy_path/custdata.txt` |
| `acctdata.txt` | `CVACT01Y` / ACCOUNT | 300 | 1505 | 5 | verbatim copy of `tests/fixtures/provisioning/happy_path/acctdata.txt` |
| `cardxref.txt` | `CVACT03Y` / XREF | 50 | 255 | 5 | verbatim copy of `tests/fixtures/provisioning/happy_path/cardxref.txt` |
| `carddata.txt` | `CVACT02Y` / CARD | 150 | 755 | 5 | verbatim copy of `tests/fixtures/provisioning/happy_path/carddata.txt` |
| `trandata.txt` | `CVTRA05Y` / TRAN | 350 | 1755 | 5 | first five records of `app/data/ASCII/dailytran.txt` |

Each byte count is `records x (RECLN + 1)`: the `+ 1` is the single LF terminating every
line, so `5 x 301 = 1505` for the account file and so on for the rest.

### 4.2 Governance that holds for all five files

- **LF line endings** throughout; **zero carriage returns** measured in every file.
- **Exactly one trailing newline**, so `wc -l` equals the record count. The last byte of
  every file is `0x0a`, and there is no second blank line.
- **Every line exactly its record width** -- one distinct line length per file, measured.
- **Zero NUL bytes** anywhere in the corpus.
- **No byte-order mark.**
- **Records pre-sorted ascending on a primary key that begins at byte 0.**

The master's sections 3.8, 3.9 and 3.12 are the authority for line endings, the trailing
newline and the pre-sorted requirement respectively.

The five keys, each at offset 0 of its own record, confirmed from the `SELECT` clauses at
`app/cbl/CBEXPORT.cbl:35-62`:

| File | Key | `RECORD KEY IS` at |
|---|---|---|
| `custdata.txt` | `CUST-ID` | `:38` |
| `acctdata.txt` | `ACCT-ID` | `:44` |
| `cardxref.txt` | `XREF-CARD-NUM` | `:50` |
| `trandata.txt` | `TRAN-ID` | `:56` |
| `carddata.txt` | `CARD-NUM` | `:62` |

### 4.3 The widths are confirmed twice, from unrelated artifacts

The record lengths above are not a judgement call. They are the sum of the declared
field widths in each copybook (section 4.5), and they are **independently** declared by
`CBIMPORT`'s own output file descriptions:

| Output FD | `RECORD CONTAINS` | Line | Matches |
|---|---:|---|---:|
| `CUSTOMER-OUTPUT` | 500 | `app/cbl/CBIMPORT.cbl:83` | `CVCUS01Y` |
| `ACCOUNT-OUTPUT` | 300 | `:88` | `CVACT01Y` |
| `XREF-OUTPUT` | **50** | **`:93`** | `CVACT03Y` |
| `TRANSACTION-OUTPUT` | 350 | `:98` | `CVTRA05Y` |
| `CARD-OUTPUT` | 150 | `:103` | `CVACT02Y` |

Two independent sources agree on all five. **`XREF-OUTPUT` declared at 50 is what
settles the card-xref width question** discussed in section 6.3.

### 4.4 `FILLER` byte ranges

Each file's trailing `FILLER` is copybook geometry, and this scenario's ranges are:

| File | `FILLER` | Byte range | Padding byte authority |
|---|---|---|---|
| `acctdata.txt` | `X(178)` | `[122:300]` | master section 6.1, ACCOUNT `CVACT01Y` row |
| `carddata.txt` | `X(59)` | `[91:150]` | **no row exists** -- see below |
| `cardxref.txt` | `X(14)` | `[36:50]` | master section 6.1, CARD-XREF `CVACT03Y` row |
| `custdata.txt` | `X(168)` | `[332:500]` | **no row exists** -- see below |
| `trandata.txt` | `X(20)` | `[330:350]` | master section 6.1, **DALYTRAN `CVTRA06Y` input row** |

**The `trandata.txt` row is the DALYTRAN input row, not the TRAN output row.** The
master's table carries both, and they disagree. The TRAN `CVTRA05Y` **output** row
describes the record the posting and interest jobs *write*; `trandata.txt` here is a
**DALYTRAN-derived input** that no job has written yet. Reading the output row for this
file would predict the wrong padding byte for all twenty bytes of every record. Input
and output share a 350-byte geometry and do not share a padding byte.

**Two records have no row in the master's table at all**: CARD (`CVACT02Y`) and CUSTOMER
(`CVCUS01Y`). Their byte ranges are given above, and their padding bytes are **preserved
verbatim by the byte-identical copy** recorded in **this file's** section 6.1 and
**validated by the round-trip assertion** rather than by a constant stated here. No
competing constant is invented for them.

One supporting measurement about **these** files, which this scenario owns: the corpus is
entirely **NUL-free**, and `TRAN-DESC` at `[32:132]` of `trandata.txt` is space-padded.
The NUL-free result is the cleanest single discriminator between this DALYTRAN-derived
input and the NUL-padded TRAN output record -- it distinguishes the two artifacts in one
measurement, without restating either table row.

### 4.5 Copybook geometry, zero-based

Reproduced so that any offset in this scenario can be verified without leaving this file.
Every layout was re-summed against its copybook.

`CVACT01Y` -- **300** bytes:

| Field | `PICTURE` | Offset |
|---|---|---:|
| `ACCT-ID` | `9(11)` | 0 |
| `ACCT-ACTIVE-STATUS` | `X(01)` | 11 |
| `ACCT-CURR-BAL` | `S9(10)V99` | 12 |
| `ACCT-CREDIT-LIMIT` | `S9(10)V99` | 24 |
| `ACCT-CASH-CREDIT-LIMIT` | `S9(10)V99` | 36 |
| `ACCT-OPEN-DATE` | `X(10)` | 48 |
| **`ACCT-EXPIRAION-DATE`** | `X(10)` | 58 |
| `ACCT-REISSUE-DATE` | `X(10)` | 68 |
| `ACCT-CURR-CYC-CREDIT` | `S9(10)V99` | 78 |
| `ACCT-CURR-CYC-DEBIT` | `S9(10)V99` | 90 |
| `ACCT-ADDR-ZIP` | `X(10)` | 102 |
| `ACCT-GROUP-ID` | `X(10)` | 112 |
| `FILLER` | `X(178)` | 122 |

`CVACT02Y` -- **150** bytes:

| Field | `PICTURE` | Offset |
|---|---|---:|
| `CARD-NUM` | `X(16)` | 0 |
| `CARD-ACCT-ID` | `9(11)` | 16 |
| `CARD-CVV-CD` | `9(03)` | 27 |
| `CARD-EMBOSSED-NAME` | `X(50)` | 30 |
| **`CARD-EXPIRAION-DATE`** | `X(10)` | 80 |
| `CARD-ACTIVE-STATUS` | `X(01)` | 90 |
| `FILLER` | `X(59)` | 91 |

`CVACT03Y` -- **50** bytes:

| Field | `PICTURE` | Offset |
|---|---|---:|
| `XREF-CARD-NUM` | `X(16)` | 0 |
| `XREF-CUST-ID` | `9(09)` | 16 |
| `XREF-ACCT-ID` | `9(11)` | 25 |
| `FILLER` | `X(14)` | 36 |

`CVCUS01Y` -- **500** bytes:

| Field | `PICTURE` | Offset |
|---|---|---:|
| `CUST-ID` | `9(09)` | 0 |
| `CUST-FIRST-NAME` | `X(25)` | 9 |
| `CUST-MIDDLE-NAME` | `X(25)` | 34 |
| `CUST-LAST-NAME` | `X(25)` | 59 |
| `CUST-ADDR-LINE-1` | `X(50)` | 84 |
| `CUST-ADDR-LINE-2` | `X(50)` | 134 |
| `CUST-ADDR-LINE-3` | `X(50)` | 184 |
| `CUST-ADDR-STATE-CD` | `X(02)` | 234 |
| `CUST-ADDR-COUNTRY-CD` | `X(03)` | 236 |
| `CUST-ADDR-ZIP` | `X(10)` | 239 |
| `CUST-PHONE-NUM-1` | `X(15)` | 249 |
| `CUST-PHONE-NUM-2` | `X(15)` | 264 |
| **`CUST-SSN`** | `9(09)` | **279** |
| **`CUST-GOVT-ISSUED-ID`** | `X(20)` | **288** |
| `CUST-DOB-YYYY-MM-DD` | `X(10)` | 308 |
| `CUST-EFT-ACCOUNT-ID` | `X(10)` | 318 |
| `CUST-PRI-CARD-HOLDER-IND` | `X(01)` | 328 |
| `CUST-FICO-CREDIT-SCORE` | `9(03)` | 329 |
| `FILLER` | `X(168)` | 332 |

332 declared bytes plus the 168-byte `FILLER` is 500.

`CVTRA05Y` -- **350** bytes, fourteen fields:

| Field | `PICTURE` | Offset |
|---|---|---:|
| `TRAN-ID` | `X(16)` | 0 |
| `TRAN-TYPE-CD` | `X(02)` | 16 |
| `TRAN-CAT-CD` | `9(04)` | 18 |
| `TRAN-SOURCE` | `X(10)` | 22 |
| `TRAN-DESC` | `X(100)` | 32 |
| `TRAN-AMT` | `S9(09)V99` | 132 |
| `TRAN-MERCHANT-ID` | `9(09)` | 143 |
| `TRAN-MERCHANT-NAME` | `X(50)` | 152 |
| `TRAN-MERCHANT-CITY` | `X(50)` | 202 |
| `TRAN-MERCHANT-ZIP` | `X(10)` | 252 |
| **`TRAN-CARD-NUM`** | `X(16)` | **262** |
| `TRAN-ORIG-TS` | `X(26)` | 278 |
| **`TRAN-PROC-TS`** | `X(26)` | **304** |
| `FILLER` | `X(20)` | 330 |

### 4.6 Two-source offset corroboration

Offsets 262 and 304 are confirmed a second time by an artifact with no connection to the
copybook. `app/jcl/TRANREPT.jcl` declares sort symbols in **one-based** positions:

| Line | Declaration | One-based | Zero-based |
|---|---|---:|---:|
| `app/jcl/TRANREPT.jcl:41` | `TRAN-CARD-NUM,263,16,ZD` | 263 | **262** |
| `app/jcl/TRANREPT.jcl:42` | `TRAN-PROC-DT,305,10,CH` | 305 | **304** |

**The conversion is always `zeroBased = oneBased - 1`.** Applying it in the wrong
direction shifts a card-number slice by one byte, which yields sixteen legal digits that
are the wrong card. The master's section 5.3 records this cross-check for the tree.

### 4.7 The two misspellings, and the rename discipline

Two baseline field names are misspelled, and both appear in this corpus:

| Baseline field, as declared | Declared at | Target column |
|---|---|---|
| `ACCT-EXPIRAION-DATE` | `app/cpy/CVACT01Y.cpy:11` | `expiration_date` |
| `CARD-EXPIRAION-DATE` | `app/cpy/CVACT02Y.cpy:9` | `expiration_date` |

**A COBOL citation keeps the baseline spelling**, because that is what the line says --
the geometry tables in section 4.5 therefore write `EXPIRAION`. Only the **migrated
column name** carries the conventional spelling. Per **AAP Rule T1 (Copybook is
normative)**, **no other field is renamed**, and the baseline copybooks are not edited.
The master's section 9.3 is the authority for the rename set.

### 4.8 Zoned sign overpunch, as measured in this corpus

The sign is folded into the last byte of a signed field together with that byte's digit.
The master's sections 3.3 and 3.4 own the overpunch table and the tree's worked vectors;
the vectors below were measured in **these** files:

| File | Field | Bytes | Value |
|---|---|---|---:|
| `acctdata.txt` | `ACCT-CURR-BAL` `[12:24]` | `00000001580{` | **+158.00** |
| `trandata.txt` | `TRAN-AMT` `[132:143]` | `0000005047G` | **+504.77** |
| `trandata.txt` | `TRAN-AMT` `[132:143]` | `0000009190}` | **-919.00** |
| `trandata.txt` | `TRAN-AMT` `[132:143]` | `0000000678H` | **+67.88** |

**The final byte is the low-order digit carrying the sign, never a bare sign marker.**
`0000005047G` is `+504.77`: `S9(09)V99` is eleven digit positions, `504.77` fills them as
`00000050477`, and the final `7` takes a positive overpunch to become `G`. Reading the
`G` as a sign appended to a ten-digit body gives `+50.47` -- **a factor of ten out**, at
the correct field width and with every character legal, so nothing downstream objects.

### 4.9 Timestamps in this corpus

| Field | Offset | Value in all five records |
|---|---:|---|
| `TRAN-ORIG-TS` | 278 | `2022-06-10 19:27:53.000000` |
| `TRAN-PROC-TS` | 304 | 26 spaces |

**The 26 blanks are genuine input data, not the product of normalisation.** These are
unposted daily transactions: nothing has processed them, so the processing stamp has
never been written. **A 26-blank timestamp is a legitimate value, not an error.** The
master's section 8.1 requires every scenario to say which of the two meanings applies
whenever it shows a blank timestamp, precisely because the bytes are identical either
way; this scenario's answer is *genuine input data*. For which fields a given domain
normalises before comparison, that same section 8.1 holds the per-domain sets.

---

## 5. Synthetic-provenance attestation

This attestation is mandatory here because **every file in this corpus carries card-number
or identity-shaped data.** The master's section 11.1 states the requirements; this section
satisfies them for these five files.

**The data is synthetic and seed-derived.** Every card number, account id, customer id,
transaction id, name, address, national identifier, government-issued identifier, date of
birth, telephone number and credit score in this directory comes from the published AWS
CardDemo sample seed datasets, which ship with the upstream open-source project as
fabricated demonstration data.

Seed files and row keys:

| Files | Seed | Row keys |
|---|---|---|
| `custdata.txt`, `acctdata.txt`, `cardxref.txt`, `carddata.txt` | `tests/fixtures/provisioning/happy_path/` | `CUST-ID` `000000002`, `000000012`, `000000020`, `000000027`, `000000050`; the same five as `ACCT-ID` in eleven-digit form, `00000000002` through `00000000050`; `CARD-NUM` and `XREF-CARD-NUM` `0500024453765740`, `0683586198171516`, `0923877193247330`, `0927987108636232`, `0982496213629795` |
| `trandata.txt` | `app/data/ASCII/dailytran.txt` | `TRAN-ID` `0000000000683580`, `0000000001774260`, `0000000006292564`, `0000000009101861`, `0000000010142252` |

**These values represent no real person, no real account and no real card.**

**Business-rule fields reshaped away from the seed: NONE.** The list is empty, and it is
stated as empty rather than omitted, because the master's section 10 identifies an omitted
list as the failure most likely to pass unnoticed. The four masters are **verbatim
byte-identical copies** and `trandata.txt` is a **verbatim five-record slice**; no monetary
amount, date, group id, balance, status or identifier in this directory was altered. A
reader diffing any row here against its seed row will find no difference to explain.

### 5.1 Sensitive fields, noted without exposing them

| Field | `PICTURE` | Byte range | Record |
|---|---|---|---|
| `CUST-SSN` | `9(09)` | `[279:288]` | `CVCUS01Y` |
| `CUST-GOVT-ISSUED-ID` | `X(20)` | `[288:308]` | `CVCUS01Y` |
| `CARD-CVV-CD` | `9(03)` | `[27:30]` | `CVACT02Y` |
| `CARD-NUM` / primary account number | `X(16)` | `[0:16]` | `CVACT02Y`, and `[262:278]` in `CVTRA05Y` |

**Masking and encryption belong to the mapper layer, not to a fixture.** A fixture that
masked its own bytes could not round-trip: the assertion in this domain is that the bytes
that go in come back out, and a masked byte does not come back. The migrated mapper is
where the account number is masked to its last four digits, where the card verification
value is never serialised, and where the national and government-issued identifiers are
stored encrypted and returned masked. Those transformations are asserted against the
mapper, on data these files supply in the clear.

---

## 6. Decisions

Every decision below had a reasonable alternative, so each carries its rationale under one
of Rule 1's category names, written plain, plural and unemphasised as the master's section
1.4 and `docs/CODE_DOCUMENTATION_STANDARD.md` both require.

### 6.1 The four masters are copied verbatim rather than re-derived

Assumptions: `custdata.txt`, `acctdata.txt`, `cardxref.txt` and `carddata.txt` are
byte-identical copies of
`tests/fixtures/provisioning/happy_path/{custdata,acctdata,cardxref,carddata}.txt`, an
already-validated fixture set that the parity oracle exercises today. `cmp` confirms byte
identity on all four, and the widths and record counts in section 4.1 are the measured
result rather than a target. Copying inherits that validation intact; the five customer,
account, cross-reference and card rows already resolve against one another, so the corpus
needs no additional consistency work.

Alternatives Considered: re-deriving the four masters from the ASCII seeds under
`app/data/ASCII/` instead of copying the validated fixtures. Declined for a specific
reason rather than a general preference: the seed `app/data/ASCII/cardxref.txt` measures
**36 bytes per record** -- 1850 bytes over 50 rows, at 36 data bytes plus one LF each --
because it omits the trailing `FILLER X(14)` entirely. A re-derivation that took the seed
rows as they stand would produce 36-byte cross-reference records and silently break the
50-byte contract that `app/cbl/CBIMPORT.cbl:93` declares, and it would break it in the
padding rather than in a value, which is the hardest class of difference to find in a diff.

### 6.2 `trandata.txt` is a five-record slice of the daily-transaction seed

Assumptions: `trandata.txt` is the **first five records of `app/data/ASCII/dailytran.txt`**
(300 records in the seed) and is treated as a `CVTRA05Y` `TRAN-RECORD`. That is valid
because `CVTRA06Y` (`DALYTRAN-RECORD`) and `CVTRA05Y` (`TRAN-RECORD`) are **structurally
identical**: the same **fourteen** fields, in the same order, with the same pictures --

```text
X(16) X(02) 9(04) X(10) X(100) S9(09)V99 9(09) X(50) X(50) X(10) X(16) X(26) X(26) X(20)
```

-- summing to **350** in both, and differing only in the `DALYTRAN-` versus `TRAN-` name
prefix. The binding that matters is `app/cbl/CBEXPORT.cbl:83-84`, where the transaction
input FD copies **`CVTRA05Y`** and not `CVTRA06Y`, so the bytes are read under the
transaction layout by the program itself.

Alternatives Considered: describing the transaction fixture through `CVTRA06Y` field names,
since that is the copybook its seed belongs to. Declined because the two layouts are
byte-identical, so the renaming would be a no-op at byte level while obscuring the fact
that `CBEXPORT` reads these bytes as `CVTRA05Y`. The layout the program declares is the
one this file names; the seed's own copybook is recorded here as provenance in section 5.

### 6.3 `cardxref.txt` is authored at the full 50-byte width

Trade-offs: this file carries `FILLER X(14)` at `[36:50]` space-padded, giving 50 data
bytes per record, while the shipped seed carries 36. The compromise accepted is that a
cross-reference record here is **not** byte-identical to a seed row, so a reader diffing
the two finds fourteen extra bytes and has to consult the ruling. What that buys is one
width -- the copybook's -- holding for the loader, the Java codec and the program alike.
The master's section 3.10 is the authority for the 36-versus-50 ruling, and
`app/cbl/CBIMPORT.cbl:93` `RECORD CONTAINS 50 CHARACTERS` is the independent confirmation
that 50 is the width the baseline itself declares.

### 6.4 The referential-integrity gap, stated rather than hidden

Trade-offs: of the five `TRAN-CARD-NUM` values in `trandata.txt`, **only
`0927987108636232`** appears in this scenario's five-card `carddata.txt`. The other four --
`4859452612877065`, `6009619150674526`, `8040580410348680` and `5656830544981216` -- do
not. That is accepted rather than engineered away, because `CBEXPORT` and `CBIMPORT`
**move whole records file by file with no cross-file join**: each input file is read
sequentially, tagged with its own discriminator and written to the export record on its
own terms. Byte-exact round-trip identity therefore holds for all five transaction records
regardless of whether their card numbers resolve, and the assertion this corpus supports is
unaffected.

Alternatives Considered: substituting card numbers that resolve, or enlarging
`carddata.txt` until every transaction's card is present. Declined on two grounds. It would
break the verbatim provenance recorded in section 5 -- the reshaped-field list would stop
being empty, and every altered row would need its own attestation entry. And it would
assert a join that neither program performs, which would misrepresent the contract under
test as relational when it is positional. The gap is documented here instead, which is the
outcome the master's section 8 asks for when a key deliberately does not resolve inside a
scenario.

### 6.5 The documented divergence

Trade-offs: **the baseline declares `RECORD KEY IS EXPORT-SEQUENCE-NUM` over a file record
that does not contain that field (section 2.1); the migrated Java implements correct keying;
the divergence is documented** in `docs/architecture/cobol-to-service-traceability.md`. The
compromise accepted is that this one behaviour cannot be compared against a COBOL run at
all, because the pair does not compile under the open-source compiler, so this corpus is
verified against the copybook geometry and the round-trip property rather than against a
golden master. Per **AAP section 0.2.2** the baseline stays exactly as it is; nothing under
`app/**` is edited to close the gap.

Three constraints follow from the same defect, and each is easy to get wrong in the
opposite direction:

1. **The existing COBOL suite's aggregate return code of 4 is its documented passing
   state, and must never be reported as a regression.** It is caused solely by this defect:
   `scripts/build_test_programs.sh` classifies the pair as known-unsupported, expects the
   failure, and aggregates a soft warning rather than poisoning the result, while
   `tests/integration/test_export_import.py` is skipped with that exact reason
   (`tests/README.md` section 1.1).
2. **That graded rubric must never reach a Maven, Surefire, Failsafe or JUnit gate.** Those
   gates are **binary** -- a test passes or it fails, and there is no soft-warning tier for
   a return code to occupy. Mapping a 4 onto them would either fail a green build or pass a
   broken one.
3. **No Java build in this module is described in the graded vocabulary of that rubric.**
   The rubric belongs exclusively to `tests/**`, which owns it and is never modified.

---

## 7. Coordination constraints

### 7.1 This corpus is not a schema seed

**This domain's contract is the byte-exact round trip, not a database load.** All five
files are consumed as flat byte images at codec level. Nothing in this directory inserts a
row, and no test in this domain needs one to: the assertion is that bytes survive a
round trip, which is decided entirely in memory against the copybook geometry.

That is reinforced by the privilege boundary the deployed roles establish. Measured from
`data-migration/sql/V0__schemas_and_roles.sql`, the batch role holds:

| Schema | Grant to `carddemo_batch` | Line |
|---|---|---|
| `batch` | `SELECT, INSERT, UPDATE` | `:983` |
| `ledger` | `SELECT, INSERT, UPDATE` | `:1162` |
| `account` | `SELECT` only, plus a narrow `UPDATE` on `account.accounts` alone | `:1215`, `:1226` |
| `card` | **`SELECT` only** | `:1268` |
| `reference` | **`SELECT` only** | `:1277` |

So even if a test in this domain tried, **this module cannot insert a card or a customer
row** -- its grant on `card` is read-only, and on `account` it may update only the accounts
table. The corpus being byte-level input rather than seed data is therefore consistent with
the privilege model rather than merely conventional.

Alternatives Considered: loading these five files into the database and asserting the round
trip through persisted rows, which is the shape the posting and interest domains use.
Declined for two measurable reasons. The privilege table above is the first: three of the
five record types resolve to schemas this module may only read, so two of the five files
have no insertable destination here at all and the scenario would cover three types instead
of five -- losing exactly the `'D'` and `'C'` branches of the `EVALUATE` at
`app/cbl/CBIMPORT.cbl:272`. The second is that a database round trip cannot decide this
domain's assertion: a column stores a decoded value, so re-reading it proves the decode and
the encode agree with each other, not that the 500 bytes are reproduced. Byte identity is
only observable at codec level, which is where section 3 places it.

### 7.2 The sibling harness owns the baseline; this scenario owns nothing

Per the master's section 11.3, **no scenario creates, alters or seeds another service's
schema.** The sibling
`services/batch-service/src/test/resources/db/testharness/test-harness-schemas-and-foreign-tables.sql`
establishes the read-side baseline this module needs, mirroring tables it does not own
across the foreign schemas `ledger`, `account`, `reference` and `card`, while `batch` itself
is created by Flyway rather than by that script. `application-test.yml` sets the ordered
resolution path across those schemas in one statement. Both files are **read here and
modified never** -- as are the master `fixtures/README.md`, every `pom.xml`, and
`config/checkstyle/checkstyle.xml` and `config/checkstyle/suppressions.xml`.

Assumptions: this section deliberately describes the harness by **ownership and privilege**
rather than by reciting its table inventory. A scenario README that copied that inventory
would become a second source of truth for a file it does not own, and would fall out of step
the first time the harness gained or dropped a mirror. That is not hypothetical: an earlier
assumption that this module needed no `card` schema at all has already been superseded and
is recorded as such at `data-migration/sql/V0__schemas_and_roles.sql:1260-1264`, which notes
that the assumption *"was true of the tree it was made against"* and that
`com.carddemo.batch.domain.Card` now maps `card.cards` for the export's card phase. A
recited inventory here would have carried that stale claim forward into this directory. The
two facts this scenario actually depends on are stable: the harness owns the baseline, and
this corpus adds no rows to it.

---

## 8. Consumers

| Consumer | Path |
|---|---|
| `ExportJob` | `services/batch-service/src/main/java/com/carddemo/batch/job/ExportJob.java` |
| `ImportJob` | `services/batch-service/src/main/java/com/carddemo/batch/job/ImportJob.java` |
| Round-trip test | the export/import test under `services/batch-service/src/test/java/com/carddemo/batch/` |

Both jobs are the AAP section 0.5.1.7 `{ExportJob,ImportJob}.java` row.

The decoding path is **exclusively** `common-lib`'s `PackedDecimalCodec`,
`ZonedDecimalCodec` and `CopybookLayout`, plus `batch.mapper.ExportRecordMapper` as the
anti-corruption layer. Per **AAP Rule T2**, never a locally re-declared codec.

For context, `ImportJob` re-materialises five outputs at the widths `CBIMPORT` declares --
customer 500, account 300, card cross-reference 50, transaction 350 and card 150 (section
4.3) -- alongside a **132-byte** error stream at `app/cbl/CBIMPORT.cbl:108`.

---

## 9. How this file satisfies user-specified Rule 1 (Explainability)

### 9.1 Why this file exists at all

Rule 1's line **15** requires a docstring on every function, class and **module entry
point**. The five `.txt` files in this directory are the module, and they can carry no
docstring for two independent reasons:

- Rule 1's line **22** lists the standard formats -- JSDoc, Javadoc, Python docstrings, C#
  XML comments -- and **none of them applies to a fixed-width byte image.**
- A comment inserted into one of these files would be **a physical row of the wrong
  length**, so it would not be a comment at all. It would be a garbage record that the
  fixed-width loaders reject, and every byte position in these files is meaningful, so even
  a leading `#` would be data.

Markdown likewise has no docstring construct, so the **analogous obligation** applies and
lands here: **this README is the docstring for the fixture set.** Section 1 is its purpose,
section 4 its parameters, section 3 its return value, and sections 5 through 7 the
conditions and constraints under which it holds. `docs/CODE_DOCUMENTATION_STANDARD.md`
defines that analogous obligation for Markdown, and the master's sections 1.2 and 10 apply
it to this tree.

### 9.2 The validation gate, cited correctly

The operative wording is Rule 1's line **43**, the Validation Gate. It hardens line 29's
*should* into *must* and is **conjunctive**: a contribution missing **either** the
documentation **or** the decision rationale fails review. Line 43 is therefore the citation
for the obligation being mandatory, not line 29.

Line 43's triad is literally *purpose, parameters, and return values*. The exceptions
element is Rule 1's line **21** and is qualified *where applicable*; line 43 does not
require it. This file states it anyway where it is meaningful -- the packed negative-zero
carve-out in section 3 and the referential-integrity gap in section 6.4 are the conditions
under which a naive byte-comparison would report a difference.

Rule 1's four category names are defined at its lines **31-34**. Three are used here.
The fourth, at line **32**, is scoped to *replacing existing code*, and it is factually
unavailable in this file: these fixtures are net-new and replace nothing. The COBOL baseline
they derive from is untouched, and the house fixture tree they parallel is a separate,
reference-only tree that keeps running unchanged. Where a decision here differs from the
house tree's, it is recorded under `Alternatives Considered:` or `Trade-offs:` instead.

### 9.3 Compliance here is by construction, not by linter

**No machine gate validates this file, and none is claimed.**
`config/checkstyle/checkstyle.xml` sets `fileExtensions` to `java`, so a `.md` or `.txt`
file in this tree is outside the audit set before any suppression is consulted, and the
fixtures entry in `config/checkstyle/suppressions.xml` covers
`src/test/resources/fixtures/` as defensive cover for the narrow case of a generated
`.java` file. **Rule 1 binds this file in full regardless** -- it is simply enforced by
review rather than by Checkstyle, ESLint, ruff or a CI step. Neither Checkstyle file is
modified to manufacture a gate that does not belong there.

### 9.4 Scope of applicability

Rule 1 applies **only to newly authored artifacts.** `app/**`, `tests/**` and `scripts/**`
are **reference-only** per **AAP section 0.2.2** -- read and cited by path and line here,
and modified never. **No retro-documentation of the COBOL baseline is required or
permitted**, and none was performed: every citation in this file was read from the baseline
as it stands.

For citation discipline, the baseline counts are **31** COBOL programs under `app/cbl`,
**44** `.cbl` sources across the whole migration scope including the three extension trees,
and exactly **30** copybooks under `app/cpy` (master section 11.4).

---

## 10. Re-verifying this scenario

Both blocks below are runnable from the repository root and re-derive the claims above from
the committed bytes rather than from this prose.

```bash
# WHAT: assert the record geometry of all five fixtures -- one distinct line width
#       per file, the declared record count, no CR, no NUL, and a single final LF.
# WHY : Assumptions: the fixed-width loaders reject a wrong-length row outright
#       rather than padding or truncating it, so this turns a load-time failure
#       into an authoring-time one. Counting widths, rows and bytes together is
#       what catches the two likeliest faults at once -- a stray CR absorbed into
#       the trailing FILLER, and a second blank line that parses as a zero-length
#       record.
cd services/batch-service/src/test/resources/fixtures/export/happy_path
for spec in custdata.txt:500 acctdata.txt:300 cardxref.txt:50 carddata.txt:150 trandata.txt:350; do
  f=${spec%:*}; w=${spec#*:}
  awk -v w="$w" -v f="$f" 'length($0)!=w{bad=1}
       END{ printf "%-14s rows=%d width=%s %s\n", f, NR, w, (bad?"MISMATCH":"OK") }' "$f"
  printf "  CR=%s NUL=%s final=%s\n" \
    "$(tr -dc '\r' < "$f" | wc -c)" "$(tr -dc '\000' < "$f" | wc -c)" "$(tail -c 1 "$f" | od -An -tx1 | tr -d ' ')"
done
```

```bash
# WHAT: re-prove the provenance claims of section 5 -- byte identity of the four
#       masters against the validated provisioning fixtures, and trandata.txt as
#       the first five records of the daily-transaction seed.
# WHY : Assumptions: section 5 attests that NO business-rule field was reshaped,
#       and an empty reshaped-field list is only credible if byte identity is
#       mechanically checkable. cmp exits non-zero on the first differing byte, so
#       a silent drift in any master surfaces here rather than in a round-trip
#       diff where it would read as a codec fault.
D=services/batch-service/src/test/resources/fixtures/export/happy_path
P=tests/fixtures/provisioning/happy_path
for f in custdata.txt acctdata.txt cardxref.txt carddata.txt; do
  cmp -s "$D/$f" "$P/$f" && echo "identical: $f" || echo "DIFFERS: $f"
done
head -c 1755 app/data/ASCII/dailytran.txt | cmp -s - "$D/trandata.txt" \
  && echo "identical: trandata.txt is the first five dailytran records" \
  || echo "DIFFERS: trandata.txt"
```

---

<sub>Apache-2.0 - This scenario is additive and test-only. The COBOL baseline under
`app/**`, the parity oracle under `tests/**` and the runners under `scripts/**` are
reference-only and are never modified. Byte encoding for this tree is contracted by
`services/batch-service/src/test/resources/fixtures/README.md`; documentation form is
contracted by `docs/CODE_DOCUMENTATION_STANDARD.md`.</sub>
