# `batch_reference_update/update_record` - the `'U'` dispatch branch, replacing the description of a seeded type code

This directory holds exactly two files: `trtype-update.txt`, which is 54 bytes of
fixed-width maintenance input, and this document. Nothing else belongs here.

This document exists because it is the only artifact in the directory that can carry
an explanation. `trtype-update.txt` is a fixed-width record file: every physical line
must be exactly 53 bytes, so a `#`, a `*` in the wrong position or a `--` prefix would
not be a comment but a wrong-length row and a hard failure at decode. User Rule 1
names docstring formats for JavaScript and TypeScript, Java, Python and C# at L22, and
a `.txt` record file can carry none of them. The obligation does not disappear for
want of a syntax to hold it, so it is discharged here, in the directory the bytes live
in, which is what Rule 1 L27 asks for when it requires commentary adjacent to what it
explains.

There is no mechanical gate behind this file. `config/checkstyle/checkstyle.xml`
scopes its `Checker` to `fileExtensions="java"`, so nothing in this directory is ever
scanned, and the `config/checkstyle/suppressions.xml` entry covering
`src/test/resources/fixtures/` is defensive rather than load bearing - it discharges no
Rule 1 obligation at all. Rule 1's Validation Gate at L43 is therefore met by review,
and authoring discipline is the only thing protecting it.

## How this file discharges the two obligations it is under

Two independent obligations land on this directory, and they ask for the same four
things under different names. The house convention at `tests/fixtures/README.md`
section 9.1 (L695 to L719) is worded `MUST` and requires four elements; user Rule 1
requires four docstring elements at L18 to L21. The mapping is one-to-one, and the
section headings below carry both names so that neither has to be inferred:

| House section 9.1 element | Rule 1 element it discharges | Where |
|---|---|---|
| Scenario intent | Purpose (L18) | Section 1 |
| The exact business rule exercised | Purpose (L18) | Section 2 |
| The expected outcome, as a specific result | Return values (L20) | Section 3 |
| Fixture bytes and governance | Parameters (L19) | Section 4 |
| (no house counterpart) | Exceptions or errors (L21) | Section 5 |

Section 6 carries the inline-justification half of Rule 1, at L28 and L31 to L34:
every decision in this directory that a reasonable author could have made differently,
with the rejected alternative named and its concrete consequence stated. **Only three
of the four canonical labels are usable here** - `Alternatives Considered:`,
`Assumptions:` and `Trade-offs:`, in the plural form the rules document itself uses.
The fourth is unavailable on the facts rather than by preference: its precondition is
replacing existing code and saying what was wrong with the old approach, and everything
in this directory is newly authored and replaces nothing, so reaching for it would be a
fabricated claim rather than a justification. [`../../README.md`](../../README.md)
section 2.2 is where the full label set and that same exclusion are recorded for the
tree.

---

## 1. Scenario intent - Purpose

This scenario drives the `'U'` dispatch branch of the migrated `COBTUPDT` batch
reference-update flow: a single maintenance record carrying a transaction-type code
that the migration already seeds, so that the code's description is replaced and the
run reports success.

It is the narrowest possible statement of that branch. One record, one existing key,
one clean replacement, no reject tier and no second branch involved. Its neighbours in
this domain cover the other arms of the same dispatch - the insert arm, the delete arm,
the program-recognised comment arm, the unrecognised-action-byte arm and the
zero-record input - and each of those is a separate directory precisely so that a
failure localises to one arm.

## 2. The exact business rule it exercises - Purpose

### 2.1 The dispatch

The governing paragraph is `1003-TREAT-RECORD` at L109 to L130 of
[`COBTUPDT.cbl`](../../../../../../../../app/app-transaction-type-db2/cbl/COBTUPDT.cbl),
whose L110 is `EVALUATE INPUT-REC-TYPE` - byte 0 of the record and nothing else.

**This scenario is the `WHEN 'U'` arm at L114.** That arm displays `'UPDATING RECORD'`
at L115 and performs `10032-UPDATE-DB` at L116. The dispatch domain is exactly
`A`, `D`, `U` and `*`, which the program's own arms establish and which
[`MNTTRDB2.jcl`](../../../../../../../../app/app-transaction-type-db2/jcl/MNTTRDB2.jcl)
L11 to L14 documents independently as the allowed values of column 1.

### 2.2 The update, and why it splits these bytes into a key and a value

`10032-UPDATE-DB` occupies L166 to L195. Its statement at L171 to L175 is:

```text
UPDATE CARDDEMO.TRANSACTION_TYPE
   SET TR_DESCRIPTION = :INPUT-REC-DESC
 WHERE TR_TYPE        = :INPUT-REC-NUMBER
```

**The consequence for these bytes must be read carefully: bytes 1 to 2 are the `WHERE`
key and bytes 3 to 52 are the `SET` value.** On this branch the type code is a
predicate, not a payload - it selects the row and is never written - while the
description is the only thing written. A reader who assumes both fields are payload
will mis-read the fixture, and the assumption is easy to make because the same 53 bytes
mean three different things across the three data branches of the same dispatch:

| Branch | Statement | `INPUT-REC-NUMBER` (bytes 1-2) | `INPUT-REC-DESC` (bytes 3-52) |
|---|---|---|---|
| `'A'` L113 | `INSERT` L138 to L147 | payload, inserted as `TR_TYPE` | payload, inserted as `TR_DESCRIPTION` |
| `'U'` L116 | `UPDATE` L171 to L175 | **predicate only**, in the `WHERE` | **the only value written** |
| `'D'` L119 | `DELETE` L201 to L204 | predicate only, in the `WHERE` | **not referenced at all** |

The delete column is worth keeping in view: a delete row still has to carry 50 bytes at
offset 3 because the record is fixed width, yet the program never looks at them. So
"the record has a description field" and "this branch uses the description" are two
different statements, and only the `'U'` and `'A'` branches make the second one true.

### 2.3 The access mode, and the one house step that does not transfer here

L31 to L34 declare `ORGANIZATION IS SEQUENTIAL` with `ACCESS MODE IS SEQUENTIAL`, and
L101 is `READ TR-RECORD NEXT RECORD INTO WS-INPUT-REC`. A fixture in this domain is
therefore a plain sequence of 53-byte rows, and **there is no key-ordering requirement
on it beyond the scenario's own intent.**

That is stated rather than left out, because an unexplained absence reads as an
oversight. The house procedure at `tests/fixtures/README.md` section 9.2 item 5
requires indexed inputs to be pre-sorted by key so its indexed loader accepts them.
That step does not transfer here: the read above is sequential, and no COBOL loader
consumes these fixtures at all. [`../../README.md`](../../README.md) section 8.5 is
where the full five-step transfer ruling lives, and it reaches the same conclusion for
the same reason.

---

## 3. Expected outcome - Return values

### 3.1 The result

Not "the update succeeds" - the specific result is this. The single record selects the
update arm, the row keyed `03` in `reference.transaction_types` has its description
replaced, and the run takes the zero-`SQLCODE` arm at L178, whose whole body is
`DISPLAY 'RECORD UPDATED SUCCESSFULLY'` at L179. No reject is written, no warn tier is
entered, and the return code stays at its clean value.

Row count does not move: the migration seeds seven rows, one row is updated in place,
and the table still holds **seven** rows afterwards. That is the cleanest single check
that this scenario is an update and not an insert - an insert of a code the seed does
not hold would leave eight, which is the business of
[`../add_record`](../add_record/README.md).

`reference.transaction_categories` is not affected, and that is a property of the
program rather than of these bytes: L54 of `COBTUPDT.cbl` includes the transaction-type
table declaration and nothing else, so the program has no category declaration to write
through. A consumer asserting any change to the category table would be asserting
something this input cannot cause.

### 3.2 The three paths this scenario deliberately does not take

The boundary is only visible if the neighbours are named. All three live in the same
`EVALUATE TRUE` at L177 to L194 or in the same dispatch, and all three are one edited
byte away.

- **`WHEN SQLCODE = +100` at L180 to L184 - the key is absent.** L181 builds the
  literal `'No records found.'` into `WS-RETURN-MSG` (`PIC X(80)`, L61) and L184
  performs `9999-ABEND`. The trailing period is inside the literal and is reproduced
  here as it stands. **A `'U'` row aimed at a code the table does not hold is reported;
  it does not silently no-op.** It is worth being precise about what `9999-ABEND` does,
  because its name overstates it: L230 to L233 are `DISPLAY WS-RETURN-MSG`,
  `MOVE 4 TO RETURN-CODE` and `EXIT`, with no `STOP RUN` and no abend call in the
  paragraph, so control returns through `1003-TREAT-RECORD` to the read loop at L93 to
  L96 and the run ends on the warn tier rather than terminating.
  [`../../README.md`](../../README.md) section 3.6 records the same reading.
- **`WHEN SQLCODE < 0` at L185 to L193 - the statement itself failed.** L187 and L188
  concatenate `'Error accessing:'` with `' TRANSACTION_TYPE table. SQLCODE:'` and then
  `WS-VAR-SQLCODE` (`PIC ----9`, L65) into the same message, and L193 performs the same
  paragraph. The leading space is inside the second literal, which is what separates
  the two words when they are concatenated; quoting it without that space would
  misrepresent the message.
- **`WHEN '*'` at L120 to L121 - no database action at all.** The comment arm displays
  that the line is being ignored and ends there. It reaches no statement, no message
  and no return-code change, which makes it the only arm that is genuinely silent.
  That arm is the business of [`../commented_line`](../commented_line), and byte 0 is
  the whole difference between it and this file.

Every literal above is reproduced character for character under AAP Rule T8, which
requires user-visible strings to be carried across verbatim. Where a string could not
be quoted exactly it is not quoted at all: the two sibling online programs each declare
an `88`-level no-change-detected message - `COTRTLIC.cbl` L261 to L262 and
`COTRTUPC.cbl` L179 to L180 - and their wordings differ from each other, so they are
cited by line and left unquoted rather than merged into one approximate rendering.

### 3.3 The consumer, with availability stated rather than implied

- **The declared consumer is present in the tree.**
  `ReferenceBatchUpdateService`, at
  `services/reference-service/src/main/java/com/carddemo/reference/service/ReferenceBatchUpdateService.java`,
  is the transcription of `1003-TREAT-RECORD`. Its entry points are
  `BatchUpdateResult apply(java.io.InputStream)`, which reads the stream as contiguous
  records of its own `RECORD_LENGTH` of 53, and `RecordOutcome applyRecord(byte[])`,
  whose dispatch order is add, update, delete, comment, then the catch-all - the same
  order and the same domain as L111 to L122. The member these bytes select is
  `RecordAction.UPDATE`, and the clean arm returns the constant carrying
  `RECORD UPDATED SUCCESSFULLY`, spelled exactly as L179 spells it. The absent-key arm
  returns a refusal carrying `No records found.`, again exactly as L181 spells it, so
  the two outcomes this section distinguishes are distinguishable in the migrated code
  as well.
- **What that consumer is not.** It is a plain service method over a record stream.
  There is no batch job, no step, no job repository and no run ledger anywhere in this
  flow, and describing one would attribute machinery to a paragraph whose baseline is a
  single sequential read loop at L93 to L96. The service imports nothing from Spring
  Batch and holds no reference to the category table, which is what makes the two
  statements above and in section 3.1 checkable rather than asserted.
- **No claim is made here about any test class.** This document describes the bytes and
  their declared consumer. Which classes resolve this file, and what they assert, is
  the business of the code that does it, and asserting it from a fixture README would
  be claiming knowledge this file does not have.

**The bytes are the contract.** A consumer's expected value is whatever these 54 bytes
decode to, so a fixture that is wrong does not fail - it produces a passing assertion
that proves nothing. A disagreement between a consumer and this file is settled by
reading the bytes, not by editing them.

---

## 4. Fixture bytes and governance - Parameters

### 4.1 The record, field by field

Normative source: `01 WS-INPUT-REC.` at L71 of `COBTUPDT.cbl`, and nothing else.
Offsets below are **0-based**, matching [`../../README.md`](../../README.md)
section 3.1.

| Declared at | Field | `PICTURE` | Offset | Length | Fill |
|---|---|---|---:|---:|---|
| L72, with L73 `VALUE SPACES.` | `INPUT-REC-TYPE` | `X(1)` | 0 | 1 | single byte, nothing to pad |
| L74, with L75 `VALUE SPACES.` | `INPUT-REC-NUMBER` | `X(2)` | 1 | 2 | right-pad with spaces |
| L76, with L77 `VALUE SPACES.` | `INPUT-REC-DESC` | `X(50)` | 3 | 50 | right-pad with spaces |

`1 + 2 + 50 = 53`. The same layout is single-sourced at
[`../../README.md`](../../README.md) section 3.6; it is restated here only because these
three offsets are what make the file in this directory readable, and it is derived from
the same L71 to L77 rather than copied from a second-hand table.

Two reading hazards in that declaration are worth naming, because a reader who misses
either derives the wrong record:

- **`PIC` and `VALUE` sit on separate physical lines for all three fields** - L72 with
  L73, L74 with L75, L76 with L77. A layout extractor that assumes one declaration per
  line mis-reads this record, and the failure is silent because each half is
  individually well formed.
- **The record is bytes 0 through 52 and nothing else.** `COBTUPDT.cbl` carries legacy
  sequence numbers in source columns 73 to 80 - `00592033` sits on L72, for example.
  That is an artifact of the source file's own format, and identification-area content
  must never leak into a derived record.

`WS-INPUT-REC` is the normative group because L101 reads `INTO WS-INPUT-REC` and the
dispatch at L110 inspects that copy. A byte-identical 53-byte group is also declared on
the file side, as `01 WS-INPUT-VARS.` at L40 to L46 under
`FD TR-RECORD RECORDING MODE F.` at L39; it is mentioned only because it independently
confirms fixed-length 53-byte records, and it is not the group the dispatch reads.

### 4.2 The file, and arithmetic that makes the check reproducible

| File | Record width | Records | Line ending |
|---|---|---|---|
| `trtype-update.txt` | 53 bytes | 1 | LF, exactly one trailing newline, zero carriage returns |

```text
bytes = rows x 53 + rows  =  1 x 53 + 1  =  54
```

So `wc -c` reads 54 and `wc -l` reads 1, and the two together are a complete geometry
check that needs no decoder. The one row decodes as:

| Offset | Length | Bytes | Meaning |
|---:|---:|---|---|
| 0 | 1 | `U` | the action code, selecting the update arm at L114 |
| 1 | 2 | `03` | the `WHERE` key, a code the migration seeds |
| 3 | 50 | `Credit memo posted to cardholder` then 18 spaces | the `SET` value |

The description text is 32 characters, occupying offsets 3 to 34, and offsets 35 to 52
are the 18 spaces that pad the field to its declared 50. Those trailing spaces are
padding to the fixed record length rather than data, which is why the column that
receives them is `VARCHAR(50)` and not `CHAR(50)` in `V1__reference.sql` and why the
migrated code trims the field before storing it.

### 4.3 The width is corroborated twice, from mutually independent artifacts

The 53 above is not asserted from a single source:

1. **The `PICTURE` arithmetic** at `COBTUPDT.cbl` L71 to L77, summed in section 4.1.
2. **The JCL driver's own comment block** at `MNTTRDB2.jcl` L11 to L18, which documents
   column 1 as the action code over the domain `A`, `D`, `U`, `*`; columns 2 to 3 as the
   transaction type, described there as a numeric value; and columns 4 to 53 as the
   description. Column 53 is the last column documented, so the record is 53 bytes -
   reached without reading a `PICTURE` clause at all.

Two agreeing derivations from unrelated files is what makes the width checkable rather
than merely stated. **The JCL block is 1-based; the tables in sections 4.1 and 4.2, and
the whole of [`../../README.md`](../../README.md), are 0-based.** The bases are called
out because the baseline itself uses both, and leaving one implicit is a guaranteed
one-byte error on every field rather than a stylistic preference. The conversion is
always `zeroBased = oneBased - 1`, which is why the JCL's "columns 4 to 53" and this
document's "offset 3, length 50" describe the same 50 bytes.

Worth recording positively: **the 53-byte regime is new to this repository.** The house
record-length enumeration at `tests/fixtures/README.md` L140 to L142 lists 350, 300,
150, 50, 500 and 80, and 53 is not among them. No existing fixture width can be reused
here, which is exactly why this scenario carries its own derivation instead of pointing
at an established one.

### 4.4 What governs the fill: this record has no `FILLER` at all

Stated positively, because a reader arriving from the two opposing `FILLER` regimes in
[`../../README.md`](../../README.md) section 5.3 - space-padded against zero-filled -
will otherwise ask which of them applies here. **Neither applies, because there is no
`FILLER` to fill.** `1 + 2 + 50 = 53` accounts for the whole record, all three fields
are `PIC X(n)`, and the program's own `VALUE SPACES` at L73, L75 and L77 settles the pad
character, so the generic right-pad-with-spaces rule governs this record completely.
`FILLER` dropped: **none.** This is how AAP Rule T1's per-record `FILLER` obligation is
discharged for this file - by a positive statement of absence rather than by silence.

It follows that **no zoned decimal, no sign overpunch and no packed field arises
anywhere in this domain**, so none of that machinery is in play for these bytes and the
overpunch table in [`../../README.md`](../../README.md) section 5.4 has no target here.

For contrast, and to show the distinction is real rather than pedantic: the 60-byte seed
record `CVTRA03Y` *does* declare `FILLER PIC X(08)` at its L7, and in
`app/data/ASCII/trantype.txt` that region at 0-based bytes 52 to 59 is zero-filled with
the eight characters `00000000`. That is a live instance of the regime that does **not**
apply to this file.

### 4.5 The encoding contract, and why it fails loudly

- **Exactly 53 bytes on every physical line.** The house loaders reject any off-length
  row outright: they do not pad a short row, do not truncate a long one and do not
  silently drop a blank line (`tests/fixtures/README.md` L144 to L151).
  Assumptions: this file depends on that stance rather than choosing it, and the
  reasoning is stated at the source, L150 to L151 - a malformed record must never be
  silently coerced into a well-formed-looking one. The rejected alternative, tolerating
  an off-length row, would accept the row and shift every field after the error,
  producing a fixture that loads cleanly and asserts the wrong values.
- **LF only, exactly one trailing newline, no blank lines.** L168 of the house document
  makes LF the default for all new fixtures. These bytes are constructed rather than
  derived from a CRLF seed, so no carriage return ever enters and there is no
  normalisation compromise to record - but the rule still needs stating, because a blank
  line is a zero-length record that fails fixed-width parsing (house L181 to L184), and
  a second trailing newline would add exactly that. One newline per record is also what
  keeps `wc -l` equal to the record count, which is the whole basis of the arithmetic in
  section 4.2.
- **Right-space-pad every field to its declared width.** `INPUT-REC-DESC` is 50 bytes,
  so the description is followed by spaces out to offset 52 rather than stopping where
  its text stops.

### 4.6 No synthetic-data attestation is owed here

Also stated positively, because an unexplained absence is indistinguishable from an
oversight. The house attestation obligation at `tests/fixtures/README.md` section 10.3
is scoped to scenario directories holding primary account or identity data - card
number, customer name, address, national identifier, government-issued identifier, date
of birth, telephone number or credit score. `WS-INPUT-REC` is an action byte, a
two-character reference code and a description: **it carries none of those eight
categories**, so the obligation does not attach to this directory and the absence is
deliberate rather than overlooked. [`../../README.md`](../../README.md) section 8.6
records the same scoping across every domain of this tree.

---

## 5. Failure modes these bytes can produce - Exceptions

Assumptions: these are the failure modes of the *bytes*, which is what Rule 1 L21 asks
for when its Exceptions element is applied to a record file rather than to a function.
Every one of them is a way this file can be edited into something that still looks
plausible.

- **A one-byte miscount shifts every field after the miscount.** An off-length row is
  rejected outright, so the immediate symptom is a hard failure at decode. The dangerous
  variant is a *compensated* miscount that holds the row at 53 bytes while moving a
  boundary - a description one character longer with one trailing space fewer, say, or a
  single-digit type code padded to two. That one loads cleanly and updates the wrong row
  or writes the wrong value. Sections 4.1 and 4.2 state the offsets and the decoded row
  so the boundary can be checked rather than assumed.
- **A blank line is a zero-length record, not a spacer.** It fails fixed-width parsing
  (`tests/fixtures/README.md` L181 to L184). Because this file holds a single record, a
  stray newline is also the single easiest edit to make by accident and the hardest to
  see, and it takes `wc -l` out of agreement with the record count.
- **Only a genuinely zero-byte file counts as empty** (`tests/fixtures/README.md` L144
  to L151). A file of whitespace is not an empty file, and it is not inert either: 53
  spaces followed by a newline is a well-formed one-record file whose action byte is a
  space, and a space matches none of `A`, `U`, `D` or `*`, so it reaches the catch-all
  arm at L122 and is reported rather than ignored. The genuinely-empty case is
  [`../empty_input`](../empty_input/README.md), whose file is 0 bytes.
- **A rewrite to CRLF adds a byte to the record.** The carriage return would be absorbed
  into the trailing field, pushing the row to 54 bytes and breaking the arithmetic in
  section 4.2. This is the failure most likely to arrive from an editor's default rather
  than from an intentional edit.
- **Moving the type code out of the seeded range inverts the scenario.** Section 6.1 is
  the whole reason `03` is used; nothing in the file name or the record's shape would
  reveal the change, and the run would take the refusal arm while the directory still
  read `update_record`.

---

## 6. Why these bytes and not others

Each item below is a decision that could be reversed without any of the geometry in
section 4 failing, which is precisely why Rule 1 L40 requires the reason to be recorded
beside the bytes rather than left to inference, and why L41 rules out settling any of
them with an unspecific appeal to being clearer or safer.

### 6.1 Assumptions: the seeded state is what makes `03` a clean update

`application-test.yml` runs Flyway over both migrations against a bare PostgreSQL
container, with `locations: classpath:db/migration`, `schemas: reference` and
`create-schemas: true`. `V1__reference.sql` declares
`reference.transaction_types.type_cd` as `CHAR(2) NOT NULL` under
`CONSTRAINT pk_transaction_types PRIMARY KEY (type_cd)`, with
`description VARCHAR(50) NOT NULL`. `V2__seed_reference.sql` then seeds exactly seven
rows, `01` through `07`, from `app/data/ASCII/trantype.txt`.

The consequence is direct: **a `'U'` row is a clean update only for a code inside `01`
through `07`.** `03` is inside it, so the `WHERE` at L174 matches one row and the run
reaches the zero-`SQLCODE` arm. A code outside that range - `08`, say, or `99` - would
match nothing and take the `+100` refusal arm at L180 to L184 instead, which is a
different scenario's business and is described in section 3.2 so that the boundary is
visible from here. If an edit moves this code out of the seeded range, this scenario
silently becomes a not-found test while still being named `update_record`, and the
seven-row outcome in section 3.1 becomes unreachable.

### 6.2 Assumptions: this program has no unchanged-description guard, so the change is deliberate

This is the behavioural trap in the directory, and it is genuinely surprising because
the sibling online programs behave differently. `COTRTLIC.cbl` L261 to L262 and
`COTRTUPC.cbl` L179 to L180 each declare an `88`-level no-change-detected message, and
each program carries a `WS-DATACHANGED-FLAG` alongside it. **`COBTUPDT` carries neither**
- a case-insensitive scan of the program for change-detection wording returns nothing at
all. The batch program issues the `UPDATE` unconditionally, and PostgreSQL and Db2 alike
report a matched row whether or not the value differs.

Two things follow, and both matter for reading this fixture:

- **The description here differs from the seeded one on purpose.** Code `03` is seeded
  with `Credit`, and this record replaces it with `Credit memo posted to cardholder`, so
  the update is observable in the stored row rather than being a write that changes
  nothing.
- **An identical description would still have reported success.** Had this record
  carried `Credit` unchanged, L178 would still have been the arm taken and L179 would
  still have displayed its message. So the differing value is what makes the scenario
  *observable*, not what makes it *pass*. Recording this is the point: without it a
  reader may assume a no-change guard exists and mis-read this fixture as testing one,
  which would attribute a behaviour to the baseline that it does not have.

### 6.3 Assumptions: the code is verbatim-bound and the description is not

Two values, two different rules, and conflating them is the likeliest sourcing mistake
here.

**The type code is bound.** `03` appears in the reference data, so AAP Rule T8 requires
it character for character, and it is exactly the two bytes `03` - not `3`, not `3 `.
The `CHAR(2)` key in `V1__reference.sql` is the reason a single-digit spelling would not
locate its own row.

**The description is not bound, and that is correct rather than a lapse.**
`Credit memo posted to cardholder` is not one of the seven seeded descriptions, so there
is no reference-data value being paraphrased and AAP Rule T8 has nothing to bind - that
rule governs values which *do* appear in the reference data. This scenario is the one
most likely to need such a value, because replacing a description is its entire subject:
the seven seeded descriptions are the *old* values here, not candidate new ones. The
composed value follows the seed's mixed-case shape for a concrete reason rather than for
tidiness: `description` is `VARCHAR(50)`, so an all-uppercase spelling would sit in the
same column as seven mixed-case neighbours and any comparison of a composed row against
a seeded one would be comparing two conventions.

Where a scenario in this tree *does* carry a code the seed holds and needs that code's
own description, the value is lifted verbatim - `01` `Purchase`, `02` `Payment`, `03`
`Credit`, `04` `Authorization`, `05` `Refund`, `06` `Reversal`, `07` `Adjustment`, mixed
case included. Such a lift needs no re-padding and cannot pick up the seed's line
ending: `trantype.txt` is the 60-byte `CVTRA03Y` record, so a seed row's description
already occupies exactly 50 right-space-padded bytes at 0-based 2 to 51, identical in
width to `INPUT-REC-DESC`, while the carriage return on its first six rows sits at
0-based byte 60, well past the description's last byte. One off-by-one is worth flagging
for anyone doing that lift: the description sits at 0-based 2 to 51 in the 60-byte seed
record but at 0-based 3 to 52 in this 53-byte fixture record, because the fixture
prepends the one-byte action code.

### 6.4 Alternatives Considered: where a description must never be taken from

Taking a description from the Db2 control card
`app/app-transaction-type-db2/ctl/DB2LTTYP.ctl`, or from its category counterpart
`DB2LTCAT.ctl`. Superficially attractive, because those files are the reference-data
loader for the very extension tree this flow belongs to, and a reader hunting for a
description has nowhere else obvious to reach. **Rejected on two concrete grounds:**
their values are UPPERCASE on every row, and `DB2LTTYP.ctl` L22 carries `'REVERAL'`
where the seed carries `Reversal`. `V2__seed_reference.sql` deliberately seeds from the
ASCII files instead, so a control-card-derived value would not match the row seeded into
the schema and would violate AAP Rule T8. The rejected source is named by path rather
than left unmentioned precisely because it looks authoritative.
[`../../README.md`](../../README.md) section 6.6 records the same ruling for the tree.

### 6.5 Alternatives Considered: the fixture file name

House fixtures are named for the logical dataset they stand in for - `dailytran.txt`,
`acctdata.txt` - never for a program and never for a record layout.
`trtype-update.txt` names the stream: transaction-type maintenance records. The rejected
candidates, each with the consequence that rules it out:

- **`inpfile.txt`**, the obvious candidate, after `//INPFILE DD DSN=INPFILE,DISP=SHR` at
  `MNTTRDB2.jcl` L27 and `ASSIGN TO INPFILE` at `COBTUPDT.cbl` L31. Rejected because
  `INPFILE` is a generic data-definition placeholder that identifies no particular
  stream; the name would not distinguish this input from any other program's input.
- **`mnttrdb2.txt`**, rejected because it names the job rather than the stream.
- **`COBTUPDT.txt`** and **`WS-INPUT-REC.txt`**, rejected outright: a fixture is named
  for neither a program nor a working-storage group.

The hyphenated form departs from the eight-character dataset-shaped house names on
purpose, and the reason is that **no eight-character dataset name exists for this
stream** - the only name the baseline supplies is the generic `INPFILE` above. The
departure is recorded so that it is not read as carelessness. Every scenario directory
in this domain uses this same file name, so a consumer resolves one name per scenario
and the directory carries the distinction.

### 6.6 Trade-offs: one record rather than a two-record set-up pair

This file holds a single row, where the delete scenario next door needs two - an insert
of an unseeded code followed by the delete of it. The compromise is deliberate and it
cuts both ways.

What it buys: the update target already exists, because `03` is one of the seven rows
`V2__seed_reference.sql` seeds, so no set-up row is needed. A prepended `'A'` row would
make this scenario depend on the insert arm as well, and a failure could then originate
in either arm while the directory named only one of them. Isolating the `'U'` arm is the
whole reason the directory exists.

What it gives up: a single-row file cannot demonstrate that the read loop at L93 to L96
advances past its first record - a one-row input looks the same whether the loop
iterates or the program reads once and stops. That property is covered by the multi-row
scenarios in this domain, so it is not lost from the tree, only from this file.

---

## 7. The byte-level contract lives one directory up

The byte tables, the offset base, the overpunch table, both `FILLER` regimes, the
line-ending ruling, the label set, the seeded-schema facts and the review gates are
single-sourced in the tree contract at [`../../README.md`](../../README.md). **Those
tables are deliberately not reproduced here.** Restating them invites drift, and
contradicting them would be a defect in this file rather than a disagreement: where this
document names a contract, it names where the contract lives.

The sections of that document this scenario leans on directly are 2.1 to 2.3 for Rule 1
and the label set, 3.1 for the offset base, 3.6 for the `WS-INPUT-REC` layout and the
dispatch table, 5.2 for exact-length enforcement and the only definition of "empty", 5.3
for the `FILLER` regimes, 5.5 to 5.7 for line endings and the acceptance arithmetic, 6.6
for description sourcing, 6.7 for the golden-master question, 6.8 for the offset base
against the house 1-based tables, 7.1 and 7.4 for the seeded state, 8.4 for the
per-scenario README obligation, 8.5 for which house steps transfer, 8.6 for attestation
scoping, and 9.1 and 9.2 for consumers and the direction of the contract.

Two further pointers, so that nothing above has to be inferred: the house document those
sections defer to is `tests/fixtures/README.md`, and the baseline sources every line
anchor in this file refers to are
[`COBTUPDT.cbl`](../../../../../../../../app/app-transaction-type-db2/cbl/COBTUPDT.cbl)
and
[`MNTTRDB2.jcl`](../../../../../../../../app/app-transaction-type-db2/jcl/MNTTRDB2.jcl).
Both are reference-only under AAP sections 0.2.2 and 0.9.2: this directory reads them
and never writes to them.
