# `date_conversion/happy_path` -- the well-formed request, decoded field for field

This directory holds one fixed-width record and this file. Every byte of
`date-request.txt` is data: a `#` in it would occupy a field position rather than
introduce a comment, and a comment line would be a wrong-length row that the
decoder refuses. So this README is the only Explainability carrier the scenario
can have, and it carries the whole justification for those 1000 bytes.

Two authorities require it, and they agree. User Rule 1 (Explainability) L15
requires a docstring on every module entry point, and its L27 adjacency clause puts
the carrier beside the bytes it explains rather than in a directory above them. The
house mandate at
[`tests/fixtures/README.md`](../../../../../../../../tests/fixtures/README.md)
section 9.1, L695 to L719, states the same obligation as `MUST` and records at L714
to L719 that an earlier "should" let scenario directories ship with no carrier at
all.

**The byte-encoding contract is not restated here.** Offsets in this file are
0-based because [`../../README.md`](../../README.md) section 3.1 declares that base
once for the whole tree; the exact-length rule, the definition of "empty", the two
opposing `FILLER` fill regimes, the zoned-decimal overpunch tables, the line-ending
ruling, the acceptance arithmetic and the label canon all live in that charter and
are cited below rather than duplicated. Its section 5.8 forbids re-implementing a
layout that the charter tables at tree scope. What follows is only what is specific
to *this* scenario.

---

## 1. Scenario intent

The foundational well-formed request vector for the `date_conversion` domain: the
one 1000-byte `REQUEST-MSG-COPY` message whose three fields are each populated in
the shape their declarations call for, so that a decoder reads all three without
raising.

It is the control case for the domain's other three scenarios.
[`../request_payload_ignored`](../request_payload_ignored/README.md) varies the
envelope to show the reply does not depend on it,
[`../invalid_date_rejected`](../invalid_date_rejected/README.md) carries the
envelope beside a refused calendar value, and
[`../empty_input`](../empty_input/README.md) is a genuinely zero-byte file. This
scenario is the one against which those three are differences.

---

## 2. The exact business rule it exercises

The rule is the **decode of the request envelope**, and the boundary of that claim
matters more than the claim itself.

The reference program is
[`app/app-vsam-mq/cbl/CODATE01.cbl`](../../../../../../../../app/app-vsam-mq/cbl/CODATE01.cbl),
524 lines. Its request handler `4000-PROCESS-REQUEST-REPLY` spans lines 339 to 364:
it clears the reply, issues `EXEC CICS ASKTIME` at lines 343 to 345, formats the
result with `EXEC CICS FORMATTIME` using `MMDDYYYY` and `DATESEP('-')` at lines 347
to 353, and `STRING`s the two literals around the two formatted values into the
reply at lines 355 to 360.

**No field of the request participates in that.** Measured across all 524 lines,
`WS-FUNC`, `WS-KEY` and `WS-FILLER` appear at exactly five places: their three
declarations at lines 110 to 112, the numeric re-initialisation at line 294
(`INITIALIZE REQUEST-MSG-COPY REPLACING NUMERIC BY ZEROES`), and the group `MOVE`
at line 322 that *fills* the record. The last two name the group, not any field. No
`IF`, no `EVALUATE` and no `MOVE` ever takes a value **out** of one of the three.
The reply is a function of the clock alone.

So the rule this scenario exercises is the one an envelope fixture can actually
exercise: that three declared intervals at three declared widths decode to three
declared values. It is not a validation rule, because the reference program
contains no branch on the request and therefore no path that could refuse one.

---

## 3. Expected outcome

The outcome is a set of specific decoded values, each asserted by name:

| Field | Decodes to | Type of the decoded value |
|---|---|---|
| `WS-FUNC` | `DATE` | four-character string, no padding needed |
| `WS-KEY` | `1` | the numeric value carried by the eleven digits `00000000001` |
| `WS-FILLER` | 985 space characters | string of exactly 985 `0x20` bytes |

Under AAP Rule T1 the `FILLER` is dropped in the target schema, and this record's
drop is **985 bytes** -- the figure the charter records for `REQUEST-MSG-COPY` at
its section 5.3, L678 to L680.

Those three values are asserted, not merely intended.
`ReferenceFixtureTest.theDateRequestCarriesAFunctionCodeAnElevenDigitKeyAndSpaceFiller`
decodes this file through the registered layout and asserts `WS-FUNC` equals
`DATE`, `WS-KEY` equals the numeric `1`, and `WS-FILLER` equals 985 spaces.
`ReferenceFixtureContractTest` separately asserts the geometry: this path, at the
request record length, holding one record. Section 6 names both.

Assumptions: a field-level assertion in this domain belongs to the **decode step**
and not to a comparison of the reference program's reply. Because no field of the
request is ever read (section 2), a reply-shaped expectation derived from these
bytes would assert a dependency the reference program does not have -- it would pass
for any envelope whatsoever, including a malformed one, and so would prove nothing
about the bytes in this directory. The reply's own two literals and its clock-driven
values are the subject of the sibling scenario that varies the envelope, not of this
one.

Assumptions: the reply rendering and the date-edit rules use **two different masks**
and the two are never merged. The conversion path emits the US
`MM-DD-YYYY` form with an `HH:MM:SS` time, which is what `FORMATTIME`'s `MMDDYYYY`
with `DATESEP('-')` produces at lines 347 to 353 and what the consumer's own
formatters reproduce. `com.carddemo.common.validation.DateEditValidator`
standardises instead on the ten-character ISO `YYYY-MM-DD` form. Any statement
about a date in this domain has to name which of the two it means; conflating them
silently transposes a month and a year.

---

## 4. Fixture bytes and governance

### 4.1 Per-file record width, record count and line ending

| File | Record width | Records | Line ending | Total bytes |
|---|---:|---:|---|---:|
| `date-request.txt` | 1000 | 1 | LF, one trailing | 1001 |

`1 x 1000 + 1 = 1001`, which is the charter's section 5.7 acceptance arithmetic
(`bytes = rows x RECLN + rows`) applied to its tabled `N x 1000 + N` row. The file
carries zero carriage returns.

**The 1000-byte record length is new to this repository.** The house enumeration at
[`tests/fixtures/README.md`](../../../../../../../../tests/fixtures/README.md) L140
to L142 lists the permitted record lengths as 350, 300, 150, 50, 500 or 80 bytes,
and 1000 is not among them. That is stated plainly rather than left to look like
existing coverage: no house fixture establishes a precedent for a record this wide,
so the width rests on the declarations cited in section 4.1.1 and on nothing else.

### 4.1.1 Derivation, per file

The record is `REQUEST-MSG-COPY`, declared at
[`CODATE01.cbl`](../../../../../../../../app/app-vsam-mq/cbl/CODATE01.cbl) line
109. Offsets are 0-based per the charter's section 3.1.

| Source line | Field | `PICTURE` and `VALUE` | Offset | Length | Fill character | Value in this fixture |
|---|---|---|---:|---:|---|---|
| 110 | `WS-FUNC` | `X(04)` `VALUE SPACES` | 0 | 4 | pad right with space | `DATE` |
| 111 | `WS-KEY` | `9(11)` `VALUE ZEROES` | 4 | 11 | pad left with ASCII `'0'` | `00000000001` |
| 112 | `WS-FILLER` | `X(985)` `VALUE SPACES` | 15 | 985 | space, `0x20` | 985 spaces |

Byte-count check: `4 + 11 + 985 = 1000`. The raw inbound buffer the record is copied
from is `01 REQUEST-MESSAGE PIC X(1000)` at line 106, and `MOVE 1000 TO
MQ-BUFFER-LENGTH` governs both directions of the exchange -- line 291 on the
receive and line 372 on the send -- so the width is the same going out as coming
in. The copy into the record is the group `MOVE` at line 322, preceded by the
numeric re-initialisation at line 294, which is why the fixture is one contiguous
1000-byte row rather than a delimited structure.

Three properties of the declaration bear on reading these bytes, and none is
inferable from the record itself:

- `WS-KEY` is **unsigned**. Its eleven bytes are plain ASCII digits with **no sign
  overpunch**, so overpunch decoding must never be applied to them: a trailing
  letter in an unsigned identifier is not a sign byte. There is no money field
  anywhere in this record, so **AAP Rule T3**, which holds money in exact
  fixed point end to end, does not reach these 1000 bytes at all -- which is
  precisely why the charter's overpunch tables are cited rather than reproduced
  here.
- Line 109 is level `01` and its three subordinates jump straight to level **10**
  with no intervening `05`. Harmless to the geometry, and fatal to a derivation tool
  that assumes levels advance by a fixed step.
- All four of lines 109 to 112 carry the identical legacy sequence number `011700`
  in columns 1 to 6 -- four distinct declarations sharing one number, which collapses
  under any tooling keyed on that area. The fixture's 1000 bytes are the record
  alone; no content from the sequence or identification areas appears in them.

### 4.2 Provenance and synthetic-data attestation

This attestation is required here and in no other domain of this tree. The charter's
section 8.6 scopes the house obligation at
[`tests/fixtures/README.md`](../../../../../../../../tests/fixtures/README.md)
section 10.3, L818 to L831, to scenario directories holding PAN or identity data,
and applies it to exactly one of the five domains: `date_conversion`, because
`WS-KEY PIC 9(11)` is eleven digits wide.

What makes it identity-shaped is a width coincidence, and the claim is held to
exactly that. The only file name `CODATE01` declares is at lines 115 and 116,
`LIT-ACCTFILENAME PIC X(8) VALUE 'ACCTDAT '`, and the key of that dataset,
`ACCT-ID`, is `PIC 9(11)` at
[`app/cpy/CVACT01Y.cpy`](../../../../../../../../app/cpy/CVACT01Y.cpy) line 5. The
widths match exactly, so `WS-KEY` is best described as **an 11-digit
ACCTDAT-key-shaped value** -- which is the whole of what the program establishes.
`LIT-ACCTFILENAME` is declared at line 115 and referenced nowhere else in the 524
lines, and `CODATE01` contains no `EXEC CICS READ` at all, so nothing here reads
that dataset. The keyed read using this field lives in the sibling program:
[`COACCT01.cbl`](../../../../../../../../app/app-vsam-mq/cbl/COACCT01.cbl) tests
`IF WS-FUNC = 'INQA' AND WS-KEY > ZEROES` at line 393, moves the key to a record
identifier at line 394, and issues `EXEC CICS READ DATASET(LIT-ACCTFILENAME)` at
lines 396 to 398.

The three statements the obligation requires:

1. **The key bytes are synthetic, and their provenance is a layout rather than a
   seed.** There is no seed dataset for this record. `app/data/ASCII/` holds nine
   files, none of them a queue payload, and the record has no copybook of its own --
   it is declared inline in the `WORKING-STORAGE` of `CODATE01.cbl` and
   `COACCT01.cbl` and nowhere else. The bytes are therefore authored against the
   declaration at `CODATE01.cbl` lines 109 to 112, which is cited here in place of
   the seed file and row key the house wording asks for. No seed citation is
   invented to fill that slot.
2. **They represent no real person and no real account.** The value is a low
   ordinal chosen to be unmistakably a demonstration value. It is not a credential,
   it identifies no real person and no real account, and no name, address, card
   number or other identifier appears anywhere in this directory.
3. **No field is reshaped away from a source value, because there is no source
   value to depart from.** That absence is the disclosure the third item asks for:
   where a seed-derived fixture would have to name the bytes it changed and say
   what they were, this record has no upstream row from which any byte could have
   been altered, so there is nothing of that kind to declare.

Section 10.3 records at L830 to L831 that an attestation colocated with the bytes it
describes is what makes a fixture tree defensible under financial-enterprise
review. That is the intent this section serves.

---

## 5. Failure modes these bytes can produce

This is the exceptions element of user Rule 1 (Explainability) L21, applied to a
record rather than to a function. Each mode below is reachable by an ordinary edit
of this directory.

- **A one-byte miscount shifts every field after it.** The fields are located by
  byte position and nothing else, so widening or narrowing `WS-FUNC` by one byte
  does not produce a wrong `WS-FUNC`; it moves `WS-KEY` and the entire 985-byte
  `FILLER` run off their offsets. The symptom is a plausible-looking wrong value
  rather than an exception, which is why section 4.1.1 states an offset and a length
  for all three fields rather than a width for the record alone.
- **A blank line is a zero-length record, not blank space.** Adding a line between
  records, or a second trailing newline, introduces a record of length zero that
  fails fixed-width parsing. The house note at L183 to L184 states this directly.
- **Only a genuinely zero-byte file counts as empty.** A file reduced to a single
  newline is a 1-byte file holding one zero-length record, which is a parse failure
  rather than the empty case. The empty case has its own scenario directory and its
  file is 0 bytes.

Assumptions: those consequences are enforced rather than merely advisory. The house
loaders **reject** any physical row whose length is not exactly the record length;
they do **not** pad a short row, do **not** truncate a long one, and do **not**
silently drop a blank line, and the only input treated as empty is a zero-byte
dataset. That enforcement and its reasoning are recorded at L144 to L151, whose
stated rationale at L150 to L151 is that a malformed record must never be silently
coerced into a well-formed-looking one. The Java decoder this tree feeds takes the
same stance: a record that is not exactly its declared length raises rather than
returning a partial decode.

Trade-offs: the file is LF only, with the cost accepted that a stray carriage
return is undetectable by eye here. A CR would be absorbed into `WS-FILLER`, push
the row to 1001 content bytes and be rejected at decode -- but inside a run of 985
identical space characters it is invisible on inspection, so it has to be checked
programmatically with `grep -c $'\r'` rather than read. The alternative, tolerating
CRLF, was declined because the house ruling at L168 makes LF the default for all
new fixtures and a mixed-ending tree gives every future author two arithmetics to
choose between for the same record. The charter states the same ruling at its
sections 5.5 and 5.6.

---

## 6. Consumer

This is the return-values element of user Rule 1 (Explainability) L20: who reads
these bytes and what they assert with them. Availability below was checked against
the branch rather than assumed, which is the stance the charter's section 10 takes
for the tree as a whole.

**The decoding consumer** is
[`DateConversionMessageListener`](../../../../../main/java/com/carddemo/reference/service/DateConversionMessageListener.java),
and it is present. Its `@SqsListener`-annotated `onDateConversionRequest` receives
the payload and decodes it through a `CopybookLayout.RecordSpec` that the class
builds locally, at offsets 0, 4 and 15 with widths 4, 11 and 985 -- the same three
intervals as the table in section 4.1.1, each of its field-name constants citing
the physical line it came from. Its declared buffer length is 1000, and its key
offset and key length are declared as 4 and 11. Its `convert` method is shared with
the synchronous path, so the queue transport and the HTTP transport render the same
reply from the same code.

**The asserting consumers** are both present, both under
`com.carddemo.reference.fixtures`, and both run by Surefire:

- `ReferenceFixtureContractTest` enrols this file in its geometry inventory and
  asserts it holds one record at the request record length with the line-ending and
  trailing-newline contract of section 4.1.
- `ReferenceFixtureTest` decodes it through the registered layout and asserts the
  three field values tabled in section 3.

`DateInquiryMessageListenerTest` resolves this domain's fixtures from the test
classpath by building the path from the scenario name, which is what makes the
constant filename in section 7 load-bearing rather than cosmetic. There is no test
class named for the decoding consumer itself; the listener-level coverage sits in
that class.

Fixtures reach all of them from the **test classpath** rather than by filesystem
path: Maven copies `src/test/resources/` into `target/test-classes/`, so this file
resolves as `fixtures/date_conversion/happy_path/date-request.txt`.

**The direction of the contract runs from the bytes outward.** A consumer's
expected value is whatever this file encodes, so a fixture that is wrong does not
fail -- it produces a passing test that proves nothing. A disagreement between this
file and a consumer is settled by reading the bytes and the declarations they came
from, never by editing the bytes to match an assertion.

---

## 7. Why these bytes and not others

Each choice below had a reasonable alternative, so user Rule 1 (Explainability) L40
requires it to be recorded rather than left silent, and L41 requires the reason to
name a specific consequence rather than a preference. Labels follow the one
permitted written form of
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md):
plural, unparenthesised, colon retained, no emphasis markup.

Alternatives Considered: filling `WS-FILLER` with ASCII `'0'`, which is what the
three seed-derived reference datasets in the sibling domains do with their padding.
Declined because it would put **985 zeros where this record has 985 spaces -- a
different 1000 bytes**, identical in length and undetectable by any size check.
Line 112 declares `VALUE SPACES`, and the charter's section 5.3 tables this record's
fill byte as space for that reason. The sharpest form of the hazard is that **both
fill regimes occur inside this one record, one byte apart**: `WS-KEY` is
`'0'`-left-padded across bytes 4 to 14, being unsigned numeric with `VALUE ZEROES`
at line 111 and re-initialised numerically at line 294, while `WS-FILLER` is
space-filled across bytes 15 to 999. In this fixture byte 14 is `'1'` (`0x31`) and
byte 15 is a space (`0x20`). The distinction is not between files; it is across a
single byte boundary in this one.

Alternatives Considered: the unhyphenated house filename form. The house convention
at L88 to L90 names a fixture after the logical dataset it stands in for --
`dailytran.txt`, `acctdata.txt`, `cardxref.txt`, `tcatbal.txt`, `discgrp.txt` -- and
no hyphenated fixture filename exists anywhere in the house tree. It is declined
here because **this record stands in for no dataset**: it is a queue message
payload, not an image of a VSAM file record, so there is no dataset name to derive
one from. The name is instead held identical across all four `date_conversion`
scenario directories, which is what lets a test build the resource path from the
scenario name alone -- as `DateInquiryMessageListenerTest` does. Diverging the
filename per scenario would turn that one parameterised lookup into four literals.

Alternatives Considered: `INQA` as the function code. Declined because `INQA` is the
**sibling** program's account-inquiry discriminator -- `COACCT01.cbl` line 393 tests
for it before performing its keyed read -- so putting it here would imply that this
envelope selects an account-inquiry contract, which this flow does not have. `DATE`
names the flow the directory exercises and matches no branch in the sibling. It is
also what makes this the distinctive well-formed vector of the four: the two
scenarios that vary the envelope both carry a different function code, so the value
here is the one that is not a variation of anything.

Assumptions: the key value is eleven digits comprising ten ASCII `'0'` pad bytes and
a single `1`. That shape is deliberate on two counts. It exercises the left-pad
regime maximally -- ten of the eleven bytes are padding, so a decoder that mishandled
the pad would be caught here rather than in a fixture whose digits happen to fill
the field -- and it remains a plainly synthetic low ordinal that no reader can
mistake for a real account. This dovetails with the attestation in section 4.2 and
is the reason the value is not drawn from any realistic-looking range.

Assumptions: the record is constructed from its declaration rather than derived from
a seed, and that changes what "correct bytes" means for this directory. For a
seed-derived fixture, correctness is byte-identity with a source row and is settled
by diffing against it. Here there is no such row, so correctness rests entirely on
the three field widths, the two fill characters and the 1000-byte total -- which is
why section 4.1.1 cites a declaration line per field and why an audit of this
fixture is an arithmetic and a byte-value check rather than a comparison. The
supporting facts are that `app/data/ASCII/` contains no file corresponding to this
record and that the record has no copybook of its own.

---

## 8. Scope, enforcement and what is not here

**Enforcement is human.**
[`config/checkstyle/checkstyle.xml`](../../../../../../../../config/checkstyle/checkstyle.xml)
scopes its `Checker` to `fileExtensions="java"` at L185, with its own adjacent note
recording that only Java carries a Javadoc construct, so **no file in this directory
is ever scanned**.
[`config/checkstyle/suppressions.xml`](../../../../../../../../config/checkstyle/suppressions.xml)
does carry an entry matching `src/test/resources/fixtures/`, at L151, but its own
rationale at L127 to L149 describes that entry as defensive and notes it may never
fire -- non-Java files under `resources/` are outside the audit set regardless. A
suppression permits a path; it authors no content and discharges no obligation under
user Rule 1 (Explainability). Authoring discipline is the only protection this file
has, and the review gate at L43 of that rule is conjunctive: missing either the
documented content or the decision rationale fails review.

**No golden-master mirror exists for this scenario and none is to be created.** The
house mirroring rule at L92 to L109 is worded `MUST`, but its own rationale at L99
to L103 conditions it on `tests/helpers/golden_compare.py` pairing an input scenario
with an expected output **purely by path**. This tree has no such comparator: its
fixtures feed Java decode and assertion, and nothing compares a program's output
against an `.expected` file. A mirror would create directories no comparator reads.
The charter states this at its section 6.7.

**`CSUTLDTC` owns no dataset, so no fixture here belongs to it.** A search of
[`app/cbl/CSUTLDTC.cbl`](../../../../../../../../app/cbl/CSUTLDTC.cbl) for
`FILE-CONTROL`, `SELECT` and `FD` returns nothing. It is a dynamically-called
subprogram whose interface is three parameters, declared at lines 84 to 86 and used
by line 88: `LS-DATE PIC X(10)`, `LS-DATE-FORMAT PIC X(10)` and
`LS-RESULT PIC X(80)`. Any assertion against its rules is therefore parameter-level
and travels beside this envelope rather than inside it. The charter's section 8.3
draws the same boundary for the domain: it exercises the `CODATE01` request
envelope, not `CSUTLDTC`'s date-edit rules.

**The reference material cited above is read and never modified.** Everything under
`app/**`, `tests/**`, `scripts/**` and `samples/**` is reference-only, including
every line of `CODATE01.cbl`, `COACCT01.cbl`, `CVACT01Y.cpy`, `CSUTLDTC.cbl` and
the house fixture README. This file cites them by path and line. The COBOL states
one behaviour and the migrated Java implements another where the two differ, and any
such divergence is recorded in the traceability document rather than resolved by an
edit to the baseline.

Two naming conventions used above are worth stating so neither is misread. User
Rule 1 (Explainability) is the single user-specified rule for this project; there is
no second one. `AAP Rule T1` (copybook is normative) and `AAP Rule T3` (money stays
in exact fixed point) are transformation rules of the migration plan and are written
out in full wherever they appear, so that a bare rule number can never be read as
belonging to the wrong document.
