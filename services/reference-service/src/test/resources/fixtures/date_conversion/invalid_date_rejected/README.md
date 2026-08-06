# `date_conversion/invalid_date_rejected` -- an impossible calendar date is refused

## 1. Scenario intent

This scenario pairs a well-formed 1000-byte `CODATE01` request envelope with a date
the date-edit rules refuse: `2023-02-29`, February 29 in a year that has no
February 29. The envelope is the carrier. The refusal happens to the **date**, and
the date travels as a parameter beside these bytes rather than as a field inside
them -- the record has no date field at all.

Assumptions: the refusal asserted here is a **validation** outcome, not a malformed
record. All 1000 bytes are well formed and load cleanly, and that is deliberate. A
fixture that were itself off-length would be rejected at load (section 5) and would
never reach the validator, so it would assert nothing whatever about a date.

Assumptions: the date string is supplied by the consumer as a `DateEditValidator`
parameter -- `LS-DATE`, ten characters -- and is **not** read out of this file. The
tree charter states the same scope boundary at its section 8.3: the `date_conversion`
domain carries the `CODATE01` request envelope and not `CSUTLDTC`'s date-edit rules,
because those rules take a date and a ten-byte mask as parameters rather than a
1000-byte message. This directory holds the envelope; the date arrives next to it.

**What this scenario does not claim.** `CODATE01` validates nothing and has no reject
branch to exercise; its reply is a function of the system clock alone. That is
recorded in the tree charter at its section 3.7, and the sibling scenario
`request_payload_ignored` exists precisely to pin it. Nothing here contradicts
either. The directory name describes the outcome of the **date-edit rule** this
fixture is used with, and section 4.3 states that boundary again at the point where a
reader is looking at the bytes.

---

## 2. The exact business rule it exercises

### 2.1 The asserted branch

[`app/cbl/CSUTLDTC.cbl`](../../../../../../../../app/cbl/CSUTLDTC.cbl) wraps the
`CEEDAYS` Language Environment date service and turns the feedback token it returns
into a verdict. Lines 128 to 149 are the `EVALUATE TRUE` catalog over
`WS-RESULT PIC X(15)`, declared at line 49; the program's own comment at lines 126
and 127 states that width. The arm this scenario asserts is **lines 133 and 134**:

```text
WHEN FC-BAD-DATE-VALUE
   MOVE 'Datevalue error'    TO WS-RESULT
```

`FC-BAD-DATE-VALUE` is declared at line 64 as
`VALUE X'000309CC59C3C5C5'`. A day-of-month that the month cannot hold reaches exactly
this arm: the COBOL-unit oracle asserts it for this scenario's date and for the
neighbouring century-year case at its scenarios 4 and 5, and the migrated
`DateEditValidator` selects the same outcome from a day-against-days-in-month test.

### 2.2 The severity contract

The program publishes two numbers and one text. Line 123 moves the token's severity
halfword into `WS-SEVERITY-N` and line 124 moves its message-number halfword into
`WS-MSG-NO-N`; line 97 then moves the assembled `WS-MESSAGE` into
`LS-RESULT PIC X(80)`, and line 98 moves the severity into `RETURN-CODE`. The
component widths of `WS-MESSAGE` at lines 42 to 57 sum to exactly 80, which is why
that group fits `LS-RESULT` without truncation.

The token's first two bytes are its severity and its next two are its message
number, both declared as signed binary halfwords at lines 72 and 73. Decoding the
nine declared tokens at lines 62 to 70 on that rule gives:

| Line | Condition | Severity | Message number | Verdict text |
|---|---|---:|---:|---|
| 62 | `FC-INVALID-DATE` | 0 | 0 | `'Date is valid'` |
| 63 | `FC-INSUFFICIENT-DATA` | 3 | 2507 | `'Insufficient'` |
| **64** | **`FC-BAD-DATE-VALUE`** | **3** | **2508** | **`'Datevalue error'`** |
| 65 | `FC-INVALID-ERA` | 3 | 2509 | `'Invalid Era    '` |
| 66 | `FC-UNSUPP-RANGE` | 3 | 2513 | `'Unsupp. Range  '` |
| 67 | `FC-INVALID-MONTH` | 3 | 2517 | `'Invalid month  '` |
| 68 | `FC-BAD-PIC-STRING` | 3 | 2518 | `'Bad Pic String '` |
| 69 | `FC-NON-NUMERIC-DATA` | 3 | 2520 | `'Nonnumeric data'` |
| 70 | `FC-YEAR-IN-ERA-ZERO` | 3 | 2521 | `'YearInEra is 0 '` |

A tenth arm, `WHEN OTHER` at lines 147 and 148, catches a token matching none of the
nine above and selects `'Date is invalid'`. It is not reachable from the nine declared
tokens, and it is the arm a coarser feedback source falls into -- which is why the two
oracles disagree about this scenario's date (section 3).

Every named failure token carries severity 3, so the severity alone does not identify
which failure occurred -- the message number does. Two of the ten verdict texts are
**shorter** than the declared fifteen characters and are right-space-padded by the
`MOVE` into `WS-RESULT`: `'Date is valid'` is thirteen characters at line 130 and
`'Insufficient'` is twelve at line 132. Five others carry explicit trailing spaces in
the source literal to reach fifteen, at lines 136, 138, 140, 142 and 146. The literal
this scenario asserts needs neither treatment: `'Datevalue error'` is exactly fifteen
characters as written. Quote these texts as they stand; trimming a trailing space or
padding one that is already full length changes the asserted bytes.

### 2.3 The condition name that reads backwards

Assumptions: the condition **names** in this program cannot be trusted as a guide to
meaning, and only the token **values** can. Line 62 declares
`88 FC-INVALID-DATE VALUE X'0000000000000000'`, and lines 129 and 130 make that
condition the arm that selects `'Date is valid'`. The all-zero feedback token means
acceptance while its condition name reads as a failure. A reader who trusts the name
inverts every date-validation outcome in the system, which is why this scenario states
the pairing rather than relying on it being noticed. This is a property of the
baseline and it is recorded here as one; the baseline is reference material and stays
byte-identical, and the migrated Java carries the same token-to-verdict pairing so the
outcomes agree.

The zero-means-acceptance convention appears three independent times in this one call
chain, which is worth naming once rather than meeting as a surprise three times: the
all-zero token at `CSUTLDTC.cbl` line 62; the severity test
`IF WS-SEVERITY-N = 0` at
[`app/cpy/CSUTLDPY.cpy`](../../../../../../../../app/cpy/CSUTLDPY.cpy) line 298; and
`88 WS-EDIT-DATE-IS-VALID VALUE LOW-VALUES` at
[`app/cpy/CSUTLDWY.cpy`](../../../../../../../../app/cpy/CSUTLDWY.cpy) line 44.

### 2.4 Why message number 2508 is what makes this scenario decidable

Two callers of `CSUTLDTC` forgive one specific message number.
[`app/cbl/COTRN02C.cbl`](../../../../../../../../app/cbl/COTRN02C.cbl) lines 397 to
402 accept a severity of `'0000'` outright and, on the failure arm, raise
`'Orig Date - Not a valid date...'` **only when the message number is not `'2513'`**;
[`app/cbl/CORPT00C.cbl`](../../../../../../../../app/cbl/CORPT00C.cbl) lines 416 to
421 have the identical shape with `'End Date - Not a valid date...'`. The
screen-path copybook `CSUTLDPY.cpy` line 298 tests the severity only and forgives
nothing.

A non-zero severity carrying message number 2513 is therefore accepted by two callers
and refused by the third. This scenario asserts message number **2508**, which no
caller forgives, so its outcome is the same at every call site. Section 7 records the
rejected alternative that would not have had that property.

---

## 3. Expected outcome

A **specific** refusal, not a description of one:

| Element | Asserted value |
|---|---|
| Feedback outcome | `FC-BAD-DATE-VALUE` (`CSUTLDTC.cbl` lines 133 and 134) |
| Verdict text | `'Datevalue error'` -- exactly fifteen characters, no padding added |
| Severity | **3** -- non-zero, so `RETURN-CODE` is 3 by line 98 |
| Message number | **2508** -- not 2513, so no caller forgives it |
| Result envelope | the 80-byte `LS-RESULT` group whose widths sum to 80 at lines 42 to 57 |
| Submitted date | `2023-02-29` |
| Submitted mask | `YYYY-MM-DD`, ten characters |

Assumptions: the mask is the ten-character ISO form and **not** the US form the
conversion reply uses. `CSUTLDTC.cbl` lines 84 and 85 declare both linkage fields as
`PIC X(10)`; the integration oracle passes `"YYYY-MM-DD"` at
[`tests/integration/test_csutldtc_date.py`](../../../../../../../../tests/integration/test_csutldtc_date.py)
line 405 and records at lines 402 to 404 that this is the mask the online callers
actually use; and `DateEditValidator.DATE_FORMAT_MASK` in `common-lib` holds the same
string. The `MM-DD-YYYY` plus `HH:MM:SS` form belongs to the conversion **reply**
path, built by `FORMATTIME ... MMDDYYYY DATESEP('-') TIME TIMESEP` at
[`app/app-vsam-mq/cbl/CODATE01.cbl`](../../../../../../../../app/app-vsam-mq/cbl/CODATE01.cbl)
lines 347 to 353. The two masks coexist in this domain and must never be merged: one
is what a date is validated under, the other is how a clock reading is rendered.

Assumptions: where the two oracles disagree about this same date, this scenario
follows the COBOL-unit one. For `2023-02-29`,
[`tests/cobol-unit/CSUTLDTC_test.cbl`](../../../../../../../../tests/cobol-unit/CSUTLDTC_test.cbl)
scenario 4 at lines 131 to 134 asserts severity 3 with `"Datevalue error"`, because it
models the real Language Environment token set. The integration oracle lists the same
date first in its `_INVALID_DATES` tuple at lines 390 to 399 but asserts severity 12
with `'Date is invalid'`, because its `CEEDAYS` stand-in is a coarse valid-or-invalid
substitute that maps every out-of-domain date onto one token -- and that file says so
itself at lines 384 to 389, deferring the finer feedback to the COBOL-unit layer. The
migrated `DateEditValidator` transcribes the real token set, so the COBOL-unit
expectation is the one that matches it. Both oracles are reference material and
neither is modified.

---

## 4. Fixture bytes and governance

### 4.1 Per-file record width, record count and line ending

| File | Record width | Records | Line ending |
|---|---:|---:|---|
| `date-request.txt` | 1000 bytes | 1 | one trailing LF |

So `wc -l` is 1 and `wc -c` is 1001. That follows the tree charter's acceptance
arithmetic, `bytes = rows x RECLN + rows`, which for a one-row 1000-byte request gives
`1 x 1000 + 1 = 1001`. The file carries **zero** carriage-return bytes and no blank
line.

The **1000-byte record regime is new to this repository.** The house enumeration of
record lengths at
[`tests/fixtures/README.md`](../../../../../../../../tests/fixtures/README.md) lines
140 to 142 names 350, 300, 150, 50, 500 and 80, and does not include 1000. This is
stated rather than left implicit, so that nobody assumes an existing loader or fixture
already covers this width.

### 4.1.1 Derivation, per file

The record is `REQUEST-MSG-COPY`, declared at
[`app/app-vsam-mq/cbl/CODATE01.cbl`](../../../../../../../../app/app-vsam-mq/cbl/CODATE01.cbl)
line 109. Offsets below are **0-based**, which is the base the tree charter declares
once at its section 3.1 and never mixes.

| Source line | Field | `PICTURE` and `VALUE` | Offset | Length | Fill | Value here |
|---|---|---|---:|---:|---|---|
| 110 | `WS-FUNC` | `X(04)` `VALUE SPACES` | 0 | 4 | pad right with space | `DTE` plus one space |
| 111 | `WS-KEY` | `9(11)` `VALUE ZEROES` | 4 | 11 | pad left with ASCII `'0'` | `00000000002` |
| 112 | `WS-FILLER` | `X(985)` `VALUE SPACES` | 15 | 985 | space, `0x20` | 985 spaces |

Byte-count check: 4 + 11 + 985 = **1000**. The raw inbound buffer this record is
copied from is `01 REQUEST-MESSAGE PIC X(1000)` at line 106, and the copy is a group
`MOVE` at line 322 preceded by a numeric re-initialisation at line 294
(`INITIALIZE REQUEST-MSG-COPY REPLACING NUMERIC BY ZEROES`) -- which is why the fixture
is one contiguous 1000-byte row and not a delimited structure.

Under migration rule T1 the `FILLER` is dropped in the target schema and the drop is
recorded: **985 bytes** for this record.

**Both fill regimes appear inside this one record, one byte apart.** `WS-KEY` is
`'0'`-left-padded across bytes 4 to 14 because it is unsigned numeric, and `WS-FILLER`
is space-filled across bytes 15 to 999 because line 112 declares `VALUE SPACES`. In
this fixture byte 14 is `'2'` (`0x32`) and byte 15 is a space (`0x20`). The tree
charter's section 5.3 is the normative statement of the two regimes; it is cited here
rather than reproduced.

`WS-KEY` is **unsigned**, so its eleven bytes are plain ASCII digits with **no sign
overpunch**. Overpunch decoding must never be applied to a `PIC 9(n)` field: a
trailing letter in an unsigned identifier is not a sign byte. There is no money field
anywhere in this record, so the charter's zoned-decimal overpunch tables do not apply
to these bytes at all -- which is the whole reason they are not restated here.

### 4.1.2 The rule's parameters, which are not record fields

The rule this scenario exercises has its own parameters, and they are declared in a
different program from the record above. From
[`app/cbl/CSUTLDTC.cbl`](../../../../../../../../app/cbl/CSUTLDTC.cbl), whose
`LINKAGE SECTION` begins at line 83 and whose line 88 reads
`PROCEDURE DIVISION USING LS-DATE, LS-DATE-FORMAT, LS-RESULT`:

| Source line | Parameter | `PICTURE` | Length | Role and value here |
|---|---|---|---:|---|
| 84 | `LS-DATE` | `X(10)` | 10 | the date under test: `2023-02-29` |
| 85 | `LS-DATE-FORMAT` | `X(10)` | 10 | the mask: `YYYY-MM-DD` |
| 86 | `LS-RESULT` | `X(80)` | 80 | the returned verdict envelope (section 2.2) |

`CSUTLDTC` is a dynamically-called subprogram that **owns no dataset**: a search of
all 157 of its lines for `FILE-CONTROL`, `SELECT` and `FD` returns nothing. There is
therefore no `CSUTLDTC` input file to author, and none exists in this directory or
anywhere else in this tree.

### 4.2 Provenance and synthetic-data attestation

The tree charter's section 8.6 makes this attestation **required** for the
`date_conversion` domain and for that domain alone, because `WS-KEY PIC 9(11)` is
identity-shaped: it is eleven digits, and the only file name this program declares is
`ACCTDAT` -- lines 115 and 116 declare
`05 LIT-ACCTFILENAME PIC X(8) VALUE 'ACCTDAT '` -- whose key `ACCT-ID` is
`PIC 9(11)` at
[`app/cpy/CVACT01Y.cpy`](../../../../../../../../app/cpy/CVACT01Y.cpy) line 5. The
widths match exactly, so the honest description is **an eleven-digit
`ACCTDAT`-key-shaped value**, and nothing beyond that.

1. **The bytes are synthetic.** They are authored directly against the
   `REQUEST-MSG-COPY` declaration at `CODATE01.cbl` lines 109 to 112. They are not a
   capture, not an extract and not a transformation of any production payload.
2. **They represent no real person and no real account.** The key is a low,
   unremarkable demonstration value. No name, address, card number, national
   identifier, credential, endpoint or resource name appears anywhere in this
   directory. The refused date `2023-02-29` is a calendar impossibility, so it cannot
   coincide with any real person's date of birth either.
3. **No field is reshaped away from a source value, because there is no source row.**
   No ASCII seed dataset exists for a 1000-byte request: `app/data/ASCII/` holds nine
   files and the widest measures 500 bytes. The disclosure required by the third
   attestation item is therefore this fact itself -- the value is authored, not copied
   from a seed and then altered.

This attestation is recorded here, beside the bytes it describes, rather than in a
central document, because the house rule at `tests/fixtures/README.md` lines 830 and
831 states that colocation is what makes the fixture tree audit-defensible for
financial-enterprise review. A reader auditing these 1000 bytes finds the provenance
of the identity-shaped field in the same directory as the field.

### 4.3 Why no date lives in these bytes

Measured across all 524 lines of `CODATE01.cbl`, `WS-FUNC`, `WS-KEY` and `WS-FILLER`
occur at their declarations on lines 110, 111 and 112 and at the two group operations
on lines 294 and 322, and nowhere else. No `IF`, no `EVALUATE` and no `MOVE` ever
takes a value **out** of any of them, and the program contains zero
`EXEC CICS READ`.

Assumptions: the baseline reply is therefore not driven by any request field. It is
built from `EXEC CICS ASKTIME` at lines 343 to 345 and `FORMATTIME` at lines 347 to
353, then assembled by the `STRING` at lines 355 to 360 as the two verbatim
fourteen-character prefixes `'SYSTEM DATE : '` and `'SYSTEM TIME : '` wrapped around a
`MM-DD-YYYY` date from `WS-MMDDYYYY PIC X(10)` (line 37) and an `HH:MM:SS` time from
`WS-TIME PIC X(8)` (line 38) -- 14 + 10 + 14 + 8 = 46 characters of content. Two
consequences follow, and they are the whole shape of this scenario: a field-level
assertion about these bytes belongs to the target's **decode** step, and the date-edit
refusal belongs to the **validator**. Neither belongs to a `CODATE01` branch, because
there is no such branch.

---

## 5. Failure modes these bytes can produce

This is the exceptions-and-errors element of Rule 1 applied to a fixture rather than to
a function: the mistakes a consumer of *these* bytes can hit, paired with the symptom
each actually produces.

- **A one-byte miscount in any field shifts every field after it.** The loaders reject
  a physical row whose length is not exactly 1000; they do not pad a short row and do
  not truncate a long one, so the mistake surfaces at load rather than as a plausible
  wrong value further on. On the Java side
  `com.carddemo.common.codec.FixedWidthCodec` raises for the same case.
- **A blank line is not an empty record.** It is a zero-length row that fails
  fixed-width parsing, and the loaders do not silently drop it.
- **Only a genuinely zero-byte dataset counts as empty.** This file is 1001 bytes, so
  it is emphatically **not** the empty case; the sibling scenario
  `date_conversion/empty_input` owns that case with a zero-byte file that has no
  trailing newline at all.
- **A stray carriage return matters more here than in a narrower record.** It is
  absorbed into `WS-FILLER`, pushing the row to 1001 content bytes, which the loader
  rejects. Inside a run of 985 spaces a carriage return is invisible on inspection, so
  it must be checked byte-wise -- `grep -c $'\r'` returns 0 for this file -- and never
  by eye.

Assumptions: the loaders reject rather than coerce, and that is a deliberate stance
rather than an implementation limitation. The house rule at
[`tests/fixtures/README.md`](../../../../../../../../tests/fixtures/README.md) lines
150 and 151 states it directly: a malformed record must never be silently coerced into
a well-formed-looking one. The rejected alternative -- tolerating an off-length row and
treating a blank line as no record -- accepts the row and shifts every field after the
error, producing a fixture that loads cleanly and asserts the wrong values. A loud
failure at load is the only outcome that surfaces the mistake.

---

## 6. Consumer

**Present in this module and reading this tree.**
`services/reference-service/src/test/java/com/carddemo/reference/fixtures/ReferenceFixtureContractTest.java`
and `ReferenceFixtureTest.java` resolve each record file as
`fixtures/<domain>/<scenario>/<file>.txt` from the test classpath and assert its width,
its record count, its line ending and its field values, raising rather than skipping on
a name that is not there. **The bytes are the contract**: an expected value is whatever
these bytes decode to, and a disagreement between this document and the file is
resolved by re-reading the file and correcting this document.

**Present in `common-lib`.** `com.carddemo.common.validation.DateEditValidator` holds
the date-edit rules this scenario exercises, including the ten-outcome feedback catalog
of section 2.2 and named constants for severity 0, severity 3, the severity 12 that a
token matching none of the nine carries, and message number 2513; its
`evaluateWithLanguageEnvironment(date, mask)` returns the outcome, severity, message
number and verdict text that section 3 asserts, and `DateEditValidatorTest` in the same
module already pins the neighbouring `1582-10-14` outcome.
`com.carddemo.common.codec.CopybookLayout` and `FixedWidthCodec` are present alongside
it and are where record geometry is obtained from, rather than from an offset copied
out of the table in section 4.1.1.

**Not present in this module, and planned in this same change.**
`services/reference-service/src/main/java/com/carddemo/reference/service/DateConversionMessageListener.java`
is the decoding consumer: its queue entry point decodes the 1000-byte request buffer
through a locally built `CopybookLayout.RecordSpec` at offsets 0, 4 and 15 with widths
4, 11 and 985 -- identical to section 4.1.1 -- and shares its conversion method with
`DateConversionController`, also not present in this module, so that the HTTP and queue
transports produce byte-identical replies. It delegates every date-edit rule to
`DateEditValidator` and re-implements none of them, which is precisely why this
scenario's assertion is made at the parameter level.

**Not present in this module.** No `*ServiceTest`, `*ControllerTest` or `*RepositoryIT`
class exists under `services/reference-service/src/test/java`; the count measured on
this branch is zero. They are named here as a contract for whichever of them lands,
not as something that reads these bytes today.

---

## 7. Why these bytes and not others

Each choice below is one a later author could reverse without any test failing, which
is exactly why the reason is written down beside it.

Alternatives Considered: the refused date could have been `1582-10-14`, the day
immediately below the Gregorian floor, and the COBOL-unit oracle does assert that
vector at its scenario 15 (lines 202 to 212) with severity 3 and the unsupported-range
verdict of the line-66 row in section 2.2. It was rejected because it decodes to
`FC-UNSUPP-RANGE`, whose message number is **2513**
-- the one code `COTRN02C` and `CORPT00C` explicitly forgive (section 2.4). The
identical fixture would then be **accepted by two callers and refused by a third**,
making this scenario's expected outcome caller-dependent. `2023-02-29` decodes to 2508,
which no caller forgives, so the outcome is the same wherever the rule is invoked. It
also appears in the invalid set of **both** oracles, and it is a calendar
impossibility, which is what makes it safe to quote in a document (section 4.2).

Alternatives Considered: `WS-FILLER` could have been filled with ASCII `'0'`, which is
the regime the three seed-derived reference datasets in the sibling domains use for
their `FILLER`. It is wrong for this record: line 112 declares `VALUE SPACES`, and
applying the seed regime here would put **985 zeros where this record has 985 spaces**
-- a different 1000 bytes, of the same length, that no length check would catch.

Trade-offs: `WS-FUNC` carries a three-character token padded to its declared width with
one trailing space, `DTE` plus a blank, rather than a token that fills all four bytes.
The compromise accepted is that the value looks like a near-miss; what it buys is that
this fixture **exercises the right-space-pad rule**, which a four-character token
cannot do because there is nothing to pad. The token is deliberately not `'INQA'`:
that is the sibling program's account-inquiry discriminator at
[`app/app-vsam-mq/cbl/COACCT01.cbl`](../../../../../../../../app/app-vsam-mq/cbl/COACCT01.cbl)
line 393, where it gates a keyed read at lines 396 to 401, and borrowing it here would
imply a contract this flow does not have.

Alternatives Considered: the key could have matched the control case. It is
`00000000002`, different from the `happy_path` sibling's `00000000001`, so a consumer
holding two decoded requests can tell them apart from the key alone and no test can
pass by cross-matching one scenario's key against the other's fixture.

Alternatives Considered: the record file could have been named in the unhyphenated
house form. Measured across all twenty house scenario directories, **zero** fixture
filenames contain a hyphen, so `date-request.txt` is a departure and is named as one.
It is the same name in every `date_conversion` scenario directory, which lets a
consumer resolve the file from the scenario name alone rather than carrying a per-
scenario filename table. The name is never `CODATE01.txt` and never
`REQUEST-MSG-COPY.txt`, because a fixture is named for what it is, not for the program
or the record that declares it.

Assumptions: these bytes are **constructed** from the declaration in section 4.1.1
rather than derived from a seed row, and that changes what "correct" means for them.
No seed exists (section 4.2) and the record has no copybook of its own -- a repository
search for `REQUEST-MSG-COPY` returns only `CODATE01.cbl` and `COACCT01.cbl`, where it
is declared inline in `WORKING-STORAGE`, and no file under `app/cpy/` or any extension
copybook directory mentions `WS-FUNC` at all. Correctness therefore rests on the three
field widths, the two fill characters and the 1000-byte total, and **not** on matching
a source file, because there is no source file to diff against.

---

## 8. Hazards a later edit of these bytes would trip

Trade-offs: this document cites the tree charter,
[`../../README.md`](../../README.md), instead of reproducing it. **Consult the charter,
not this file**, for the complete `REQUEST-MSG-COPY` layout table, the 0-based offset
declaration, the exact-length and single-trailing-newline rules, the LF-only ruling,
the two `FILLER` regimes and the zoned-decimal sign-overpunch tables. The compromise
accepted is that a reader needs two files open; what it avoids is as many places for
the geometry to drift as there are scenario directories, which the charter forbids at
its section 5.8. The overpunch tables in particular are not restated because no field
in this record is signed or monetary.

Alternatives Considered: this file is written in **pure ASCII**, which is the settled
convention of this tree -- its three existing scenario READMEs measure zero non-ASCII
characters each -- and a deliberate departure from the house tree, where all twenty
scenario READMEs carry non-ASCII characters, between 5 and 110 of them each. Copying
the house typography would break a byte-oriented audit, and it has a sharper
consequence for rationale labels specifically: a label written with a non-breaking
hyphen is not returned by a literal search for `Trade-offs:`, so a reviewer sweeping
the repository for Rule 1 rationales would miss it. Arrows are written `->` and dashes
are the ASCII hyphen-minus for the same reason.

Assumptions: the three rationale labels used here -- `Alternatives Considered:`,
`Assumptions:` and `Trade-offs:` -- are plural, unemphasised and unparenthesised, as
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md)
requires and as the charter's section 2.2 repeats. The fourth category,
`Refactoring Rationale`, is unavailable here on the merits: it is scoped to replacing
existing code, and this document and the bytes it describes replace nothing. Two drifted
spellings occur in the reference oracle suite -- one drops the plural from the label,
the other wraps it in parentheses behind a `WHY` prefix -- and neither is imitated here,
because the whole value of the label is that a single literal search finds every
rationale in the repository. Neither drifted spelling is reproduced even as an example,
so that a reviewer's sweep for them returns nothing from this directory.

Assumptions: a reader arriving from the sibling `request_payload_ignored` will have
read that a directory of this name once asserted a rejection `CODATE01` cannot perform,
and the fixture contract test carries the same note. That reading remains correct and
this scenario does not reverse it. The distinction is where the refusal lives: not in
`CODATE01`, which examines no field and has no error path, but in the date-edit rule
invoked with the ten-character date parameter that accompanies these bytes. A future
scenario in this domain that claims a refusal **by the envelope** would be describing a
branch that does not exist, and section 4.3 is the check against that.

Nothing else belongs in this directory. It holds exactly two files, `date-request.txt`
and this `README.md`, and no subdirectory. The charter's section 14 is the normative
do-not-create list -- no `.java`, no `.sql`, no `.env` or credential file, no
`.gitignore`, no EBCDIC `.PS`, no golden-master or `.expected` file and no
`tests/golden` mirror -- and one item is worth repeating at the point of temptation:
there is **no `CSUTLDTC` dataset to manufacture**, because that program owns no file
(section 4.1.2). Nothing under `app/`, `tests/`, `scripts/` or `samples/` is modified
by this scenario; those trees are reference material, and the baseline properties named
in sections 2.3 and 4.3 are recorded as they stand.
