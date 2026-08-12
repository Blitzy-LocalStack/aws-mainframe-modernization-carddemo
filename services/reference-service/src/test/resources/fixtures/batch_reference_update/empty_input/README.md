# `batch_reference_update/empty_input` - a zero-byte 53-byte-record maintenance input

`trtype-update.txt` in this directory is **deliberately zero bytes**. It holds no
record, no byte and no newline, and that is the whole of the fixture.

**This document is the only carrier of explanation this scenario could have.** A
fixed-width record file cannot hold a comment, because a commentary line would be a
wrong-length row and a hard failure at load; and a file of zero bytes cannot hold one
character of anything. Every other scenario directory in this tree at least has bytes
a reader can inspect and reason about. Here there are none, so a reader with no
document in front of them cannot distinguish a deliberate empty fixture from a file
somebody meant to populate and did not. That distinction exists only here.

Two authorities require this file independently, and they converge on the same
obligation:

- **User-specified Rule 1 (Explainability), L27**, which requires a comment to sit
  adjacent to what it explains. The justification for these particular bytes is about
  this scenario's bytes, so it belongs in this scenario's own directory rather than
  aggregated upward into a parent document.
- **The house mandate at `tests/fixtures/README.md` section 9.1 (L695 to L719)**,
  worded `MUST` rather than "should". That document records at L714 to L719 that the
  stronger wording replaced a weaker one precisely because scenario directories had
  shipped with no Explainability carrier at all.

**There is no mechanical gate behind either of them**, and both halves of that were
checked in the gate files themselves rather than taken from a description of them.
`config/checkstyle/checkstyle.xml` sets `<property name="fileExtensions" value="java"/>`
on its `Checker` at L216, with its own adjacent note at L207 recording that only Java
carries Javadoc, so **no file in this directory is ever scanned.** The
`config/checkstyle/suppressions.xml` entry matching `src/test/resources/fixtures/` at
L182 describes itself at L167 as **defensive** and as one that may never fire, precisely
because that extension scoping has already put these files outside the audit set; what
it covers is the narrow case of a `.java` file co-located with a fixtures directory. A
suppression permits a path; it authors no content and discharges no obligation. Rule 1's
Validation Gate at L43 is therefore satisfied here by review alone, and a zero-byte file
is the one artifact in this tree that no automated check could ever tell apart from an
omission. [`../../README.md`](../../README.md) section 2.3 reaches the same conclusion at
tree scope.

The byte-level encoding contract this file works inside - the record layouts, the
0-based offset declaration, the two opposing `FILLER` regimes, the zoned-decimal
overpunch tables, the line-ending rules, the label canon and the ten validation gates
- is single-sourced in [`../../README.md`](../../README.md). **Those tree-wide tables
are deliberately not reproduced here.** Restating them would create a second place for
the same geometry to drift, which that document forbids at its section 5.8; where this
file and that one appear to disagree, that one governs, and where either appears to
disagree with the program source, the program source governs.

---

## 1. Scenario intent - Purpose

This is the **zero-record boundary** of the `COBTUPDT` batch reference-update flow: an
input file that is **present and openable but genuinely empty**.

An empty input drives the reader to end-of-file on its very first read, so the
dispatch paragraph is never entered and **no action branch runs at all**. Nothing is
inserted, nothing is updated, nothing is deleted, and nothing is refused. The run
opens its file, reports the open, reads nothing, closes the file and ends clean.

What the scenario pins is that this outcome is an **ordinary terminal condition and
not an error**. A nightly maintenance stream with nothing to carry is a normal night,
and the program treats it as one: the empty file is a valid file, so the open reports
success rather than failure, and the absence of records produces no refusal and no
condition code. It is also the one outcome in this domain that is identifiable by
**the complete absence of any branch report** - every other path through the dispatch
says something about itself.

---

## 2. The exact business rule it exercises - Purpose

Rule source: [`COBTUPDT.cbl`](../../../../../../../../app/app-transaction-type-db2/cbl/COBTUPDT.cbl),
237 lines. Every line cited below was read in that file rather than inferred.

### 2.1 The priming read, and the loop body that never runs

The read driver is `1001-READ-NEXT-RECORDS` at L91 to L99, and its shape is the point
of this scenario:

- **L92 performs `1002-READ-RECORDS` once, as a priming read, before the loop.**
- L93 opens `PERFORM UNTIL LASTREC = 'Y'`, whose body is `1003-TREAT-RECORD` at L94
  and a further `1002-READ-RECORDS` at L95, closing at L96.
- L97 performs `2001-CLOSE-STOP`, whose body at L235 is `CLOSE TR-RECORD.` alone.

`1002-READ-RECORDS` at L100 to L107 is the read itself. L101 is
`READ TR-RECORD NEXT RECORD INTO WS-INPUT-REC`, L102 is `AT END MOVE 'Y' TO LASTREC`,
and L104 to L106 guard the echo: `IF LASTREC NOT EQUAL TO 'Y' THEN`
`DISPLAY 'PROCESSING   ' WS-INPUT-REC`.

On a zero-byte file, in order:

1. **L83 `OPEN INPUT TR-RECORD` succeeds**, because an empty sequential file is a
   valid file rather than a missing one. L84 tests `IF WS-INF-STATUS = '00' THEN` and
   the test is true, so **L85 reports `'OPEN FILE OK'`**.
2. **The priming read at L92 reaches `AT END` immediately** and L102 sets `LASTREC` to
   `'Y'`.
3. The echo guard at L104 is therefore false, so **not even the `'PROCESSING   '`
   report appears.** That literal carries exactly three spaces before its closing
   quote, and it is reproduced here character for character under AAP Rule T8.
4. **The `PERFORM UNTIL` at L93 finds its condition already satisfied**, so its body
   never executes: `1003-TREAT-RECORD` at L109 to L129 is never performed, the
   `EVALUATE` at L110 is never entered, and no second read is issued.
5. No `EXEC SQL` statement is reached, so no `SQLCODE` is moved or inspected and
   `9999-ABEND` is never performed.
6. L97 closes the file at L235 and the run ends.

### 2.2 What the run reports, and the five reports it does not

Because the dispatch paragraph is never entered, **none of its five branch reports can
appear.** All five are named so that a reader can check the absence rather than take
it on trust:

| Branch | Selected at | Report it would emit |
|---|---|---|
| add | L111 | `'ADDING RECORD'` at L112 |
| update | L114 | `'UPDATING RECORD'` at L115 |
| delete | L117 | `'DELETING RECORD'` at L118 |
| commentary | L120 | `'IGNORING COMMENTED LINE'` at L121 |
| catch-all | L122 | `'ERROR: TYPE NOT VALID'` composed at L124 |

The dispatch order above is the order the program evaluates in. The driver's own
comment block at
[`MNTTRDB2.jcl`](../../../../../../../../app/app-transaction-type-db2/jcl/MNTTRDB2.jcl)
L11 to L14 documents the same domain in a different order, listing delete second.
Because the subject is a single character no two arms can both match, so neither
listing changes which arm runs and neither is wrong; the discrepancy is a real
property of the baseline, recorded here and in [`../../README.md`](../../README.md)
section 3.6 rather than aligned away. **It is not presented as the dispatch order.**

So the entire observable report sequence for this scenario is one line, `'OPEN FILE
OK'`, and the alternative literal at L87, `'OPEN FILE NOT OK'`, does **not** appear.
Confusing those two would invert the scenario's meaning: this fixture asserts that an
empty file opens cleanly, not that a file failed to open.

### 2.3 The two neighbours that also write nothing, and how this differs from both

Three paths through this program leave the database untouched, and conflating them is
the readiest way to mis-assert this scenario. They are separated by two independent
observations:

| Path | Branch report | Condition code |
|---|---|---|
| **This scenario - no record at all** | **none whatsoever** | stays 0 |
| A `'*'` row, the commentary branch | `'IGNORING COMMENTED LINE'` at L121 | stays 0 |
| An unrecognised action byte, the catch-all | `'ERROR: TYPE NOT VALID'` at L124 | moved to 4 |

Only this scenario produces no branch report. Only the catch-all moves the condition
code.

That last path deserves one clarification, because its paragraph name misleads.
`9999-ABEND` at L230 to L233 does **not** terminate the run: its whole body is
`DISPLAY WS-RETURN-MSG` at L231, `MOVE 4 TO RETURN-CODE` at L232 and `EXIT` at L233,
with no abend call in it. Control returns through `1003-TREAT-RECORD` into the loop at
L93 to L96, the next record is read at L95, and the run ends on the warn-tier code.
So the contrast this scenario draws is against a **soft reject that continues**, not
against a crash. The sibling scenario `../invalid_type_abend/` is the one that
exercises it. [`../../README.md`](../../README.md) section 3.6 carries the full
analysis, and its section 12 lists reading that paragraph as a termination among the
named failure modes of this tree.

### 2.4 Why the scenario is worth having

Stated plainly, because it is a fair question. A loop written the other way round -
test first, read inside the body - would behave identically on an empty file. The
priming-read shape at L92 is therefore not what makes an empty input work; it is what
this scenario **pins as safe on a zero-record input**, so that a later reader cannot
mistake the extra read before the loop for a defect that would consume or skip a
record that is not there.

The scenario's own value is the two facts above it: that an empty input is a **clean
no-work run rather than an error**, and that it is **distinguishable from every
dispatch outcome by the total absence of a branch report**. Neither is observable from
any populated fixture, because every populated fixture reports something.

---

## 3. Expected outcome - Return values

### 3.1 The assertions

Each line below is a specific asserted result rather than a description of one:

- **`'OPEN FILE OK'` (L85) is reported.** `'OPEN FILE NOT OK'` (L87) is **not**.
- **No `'PROCESSING   '` echo appears**, because the guard at L104 is false on the
  priming read.
- **No branch report of any kind appears** - none of the five in the table at section
  2.2.
- **No `EXEC SQL` statement is issued**, so no `SQLCODE` is moved to the diagnostic field
  declared at L65 - its three moves sit at L149, L176 and L205, all of them inside
  paragraphs the dispatch performs - and none is inspected.
- **`RETURN-CODE` remains `0`.** The mechanism matters more than the value: the only
  statement in this program that assigns `RETURN-CODE` is `MOVE 4 TO RETURN-CODE` at
  **L232**, inside `9999-ABEND`. That paragraph is performed from six sites - L128, L162,
  L184, L193, L214 and L224 - and **every one of them lies inside `1003-TREAT-RECORD` or
  inside a paragraph that `1003-TREAT-RECORD` performs**: L128 is its own catch-all arm,
  and the other five are the diagnostic paths of the insert, update and delete paragraphs
  it reaches at L113, L116 and L119. Since the dispatch is never entered, no site is
  reachable, **L232 is never reached, and `RETURN-CODE` is never assigned at all.** The
  code is `0` because nothing moved it, not because something computed it.
- **The seeded reference tables are unchanged.** This is a real assertion rather than a
  vacuous one - see section 3.4, which records that the rows demonstrably exist before
  the run.
- **The file is closed** at L235, so the run ends having released its input.

### 3.2 The migrated result, member by member

The migrated form of this flow is
[`ReferenceBatchUpdateService`](../../../../../main/java/com/carddemo/reference/service/ReferenceBatchUpdateService.java),
whose own documentation records that it transcribes L91 to L129 with the priming read
and the loop read written as one expression. Four constraints on how this scenario
reaches it:

1. **This scenario targets `apply(InputStream)` and not `applyRecord(byte[])`.** There
   is no record to hand to the per-record method, so the per-record method has no form
   this scenario could take. `apply` primes with a read of `RECORD_LENGTH` bytes and
   loops while that read returns any; on an empty stream the first read returns nothing
   and the loop body never executes, which is the same shape as the priming read at L92
   reaching `AT END`. The expected result is a `BatchUpdateResult` whose `outcomes` list
   is **empty**, whose `anyRejected` is **false**, whose `processedCount` is **0** and
   whose `returnCode()` is therefore `RETURN_CODE_CLEAN`, which is `0`.
2. **The `RecordAction` enum has no member that describes this case.** Its five
   constants - `ADD`, `UPDATE`, `DELETE`, `COMMENT`, `INVALID` - each name a dispatch
   branch and each carry that branch's verbatim report text. An empty stream selects no
   branch, so it is the one input the enum cannot describe, and **that is exactly why
   this scenario exists beside the dispatch scenarios rather than being folded into
   one of them.** An empty run is not an `INVALID` run: `INVALID` is a record that was
   read and refused.
3. **This is a plain service method and not a Spring Batch job.** There is no job, no
   step, no `JobRepository` and no `batch_run` ledger anywhere on this path, so an
   empty run is **not a skipped step** and must not be described as one. It is a method
   call that read to the end of its stream and found nothing there.
4. **Nothing touches `reference.transaction_categories`.** `COBTUPDT` L54 includes only
   `DCLTRTYP`, so the program carries no category declaration to write through even
   when it does have a record. With no record it touches no table at all, so this
   scenario claims no effect on any table in any schema.

The two failures this entry point declares are both unreachable here. A stream-read
failure needs a stream that fails, and a record-length failure needs a partial record;
a stream of zero bytes is a whole number of records - zero of them - so it is well
formed by the same arithmetic that governs every populated file in this tree.

### 3.3 Consumers, with availability stated rather than implied

**Availability was verified against this branch by direct inspection rather than
assumed**, following the practice [`../../README.md`](../../README.md) section 10 sets
at tree scope and `tests/fixtures/README.md` section 9.3 sets in the house tree, where
every referenced artifact is annotated and none is claimed to exist on the strength of
a plan.

| Consumer | Status | What it asserts about this file |
|---|---|---|
| `com.carddemo.reference.fixtures.ReferenceFixtureContractTest` | present, tracked | Enrols this path among the files that **must be exactly zero bytes** |
| `com.carddemo.reference.fixtures.ReferenceFixtureTest` | present, tracked | Asserts the file is **zero bytes**, that **no record can be read from it**, and that decoding a zero-length image against the 53-byte layout raises a record-length failure rather than yielding an empty field map |
| `com.carddemo.common.codec.FixedWidthCodec` | present, tracked | Supplies the exact-length enforcement that makes 0 records the only possible reading of 0 bytes |
| `ReferenceBatchUpdateService` | present, tracked | The production consumer of the 53-byte record shape, entered through `apply(InputStream)` as section 3.2 records |

Two further facts about that second row are worth separating, because they are two
levels of the same contract rather than one. **Zero records** is what the file yields
when it is read as a stream of whole records. **A record-length failure** is what
happens if a zero-length image is handed to the decoder as though it were a record.
Both hold, they do not conflict, and a consumer of an empty file has to handle the
first while never producing the second.

The file reaches those consumers from the **test classpath** rather than by filesystem
path: Maven copies `src/test/resources/` into `target/test-classes/`, so it resolves as
`fixtures/batch_reference_update/empty_input/trtype-update.txt`. A zero-byte resource
is the one a copy step is most likely to drop, which is why the consumer resolves it
and raises rather than skipping when it is absent - a dropped file fails a test instead
of quietly reading as the empty case it is supposed to be testing.

**The bytes are the contract.** A consumer's expected value is whatever this file
encodes, so a fixture that is wrong does not fail; it produces a green test that proves
nothing. A disagreement between this document and the file is settled by measuring the
file, and a disagreement between this document and the program source is settled by the
program source.

### 3.4 The seeded state this run leaves alone

`V1__reference.sql` and `V2__seed_reference.sql` both execute against a bare
PostgreSQL container under
[`application-test.yml`](../../../application-test.yml), which enables Flyway, names
`reference` as both the managed and the default schema, and permits schema creation. So
the schema exists and is populated before the run: **7 rows in
`reference.transaction_types`** and **18 rows in `reference.transaction_categories`**,
seeded from the mixed-case ASCII datasets.

That is what makes "the seeded tables are unchanged" an assertion with content. The
rows are demonstrably there, a populated sibling fixture demonstrably alters them, and
this scenario demonstrably does not.

---

## 4. Fixture bytes and governance - Parameters

### 4.1 The record this file holds zero instances of, field by field

Recording the layout of a record that is absent is not idle: **53 is the width this file
is empty of**, and it is the width `ReferenceFixtureTest` names alongside the record
count of zero when it asserts that no record can be read from this file. A reader cannot
check "zero records of 53 bytes" without knowing what the 53 bytes are.

Normative source: `01 WS-INPUT-REC.` at L71 of `COBTUPDT.cbl`. Offsets are **0-based**,
matching [`../../README.md`](../../README.md) section 3.1. Each row cites the line that
declares the field, which is what makes this a derived index rather than a competing
declaration of the geometry.

| Declared at | Field | `PICTURE` | Offset | Length | Fill |
|---|---|---|---:|---:|---|
| L72, with L73 `VALUE SPACES.` | `INPUT-REC-TYPE` | `X(1)` | 0 | 1 | single byte, nothing to pad |
| L74, with L75 `VALUE SPACES.` | `INPUT-REC-NUMBER` | `X(2)` | 1 | 2 | right-pad with spaces |
| L76, with L77 `VALUE SPACES.` | `INPUT-REC-DESC` | `X(50)` | 3 | 50 | right-pad with spaces |

`1 + 2 + 50 = 53`.

Two reading hazards in that declaration are named because a reader who misses either
derives the wrong record:

- **`PIC` and `VALUE` sit on separate physical lines for all three fields** - L72 with
  L73, L74 with L75, L76 with L77. A layout extractor that assumes one declaration per
  line mis-reads this record.
- **The record is bytes 0 through 52 and nothing else.** `COBTUPDT.cbl` carries legacy
  sequence numbers in source columns 73 to 80, `00592033` on L72 for instance.
  Identification-area content is an artifact of the source file's own format and must
  never leak into a derived record.

`WS-INPUT-REC` is the normative group because L101 reads `INTO WS-INPUT-REC` and the
dispatch at L110 inspects that copy. A byte-identical 53-byte group is also declared on
the file side as `01 WS-INPUT-VARS.` at L40 to L46, under
`FD TR-RECORD RECORDING MODE F.` at L39; it is mentioned only because fixed-length
recording independently confirms 53-byte records, and it is not the group the dispatch
reads.

**Sequential access, so nothing to order.** L31 to L34 declare
`ORGANIZATION IS SEQUENTIAL` and `ACCESS MODE IS SEQUENTIAL`, and L101 is a
`READ ... NEXT RECORD`. A fixture in this domain is a plain sequence of 53-byte rows
with no key-ordering requirement, and the house add-a-scenario step about pre-sorting
indexed inputs - `tests/fixtures/README.md` section 9.2 item 5, which exists for
`tests/helpers/load_indexed.sh` - **does not transfer to this domain at all.** That
non-transfer is stated rather than left silent, because an unexplained absence reads as
an oversight; [`../../README.md`](../../README.md) section 8.5 records the same
non-transfer for the same reason. With zero rows the point holds twice over: there is
neither a key to sort on nor a row to sort.

### 4.2 The file, and the arithmetic at zero rows

| File | Record width | Records | Line ending |
|---|---|---:|---|
| `trtype-update.txt` | 53 bytes | 0 | **none - the file is 0 bytes** |

[`../../README.md`](../../README.md) section 5.7 determines a record file's size
completely as `bytes = rows x RECLN + rows`. Here it degenerates, and it is written out
in full so that the general rule is visibly satisfied rather than looking waived:

```text
bytes = rows x 53 + rows   ->   0 = 0 x 53 + 0
```

**Measured, not asserted:** `wc -c` reads `0`, and `od -c` emits only the `0000000`
terminator - not one byte, and in particular not a newline. As a confidence check on
the arithmetic itself, both populated siblings in this domain measure exactly 108 bytes
over 2 rows, and `2 x 53 + 2 = 108`, so the same formula that yields 0 here yields their
measured size there.

**"Empty" here means a zero-byte file, categorically, and nothing else.** Recording
that resolution is a contractual obligation rather than a courtesy:
`tests/fixtures/README.md` section 7 (L657 to L661) requires each `empty_input`
scenario to **document** whether its emptiness is a 0-byte file or a file containing no
lines, because both read as "empty" to a person while the loader has to be told which.
This directory resolves it to 0 bytes, and three independent supports converge on that:

- **The house enforcement at L144 to L151 is categorical.** The loaders reject any
  physical row whose length is not exactly the record length; they do not pad short
  rows, do not truncate long ones and do not silently drop blank lines, and **the only
  input treated as empty is a genuinely zero-byte dataset**.
- **The Java side enforces the same contract.**
  `com.carddemo.common.codec.FixedWidthCodec` raises a record-length failure for
  exactly this case, so a wrong-length record fails on decode rather than decoding into
  shifted fields.
- **The measured precedent.** `tests/fixtures/posting/empty_input/dailytran.txt`
  measures 0 bytes with `od -c` emitting only its terminator; the four files in
  `tests/fixtures/provisioning/empty_input/` are each 0 bytes; and
  `tests/fixtures/statement/empty_input/trnxfile.txt` is 0 bytes. Inside this tree,
  [`../../README.md`](../../README.md) section 5.2 records five of its eighteen record
  files as genuinely zero-byte, this file among them, and section 6.5 states the rule as
  "a zero-byte file and nothing else".

### 4.3 The trailing newline that is absent, and why adding one breaks the scenario

`tests/fixtures/README.md` section 3.3 (L176 to L184) requires a **single trailing
newline after the last record**, so that `wc -l` equals the record count and CI has a
cheap integrity check. Its own warning at L181 to L184 is that a blank line is a
zero-length record that will fail fixed-width parsing.

**This is the one case in the tree where the expected trailing-newline count is zero
rather than one, and the exception is stated explicitly rather than left to be
inferred.** There is no last record, so there is nothing to terminate. A reader who
applies the general rule mechanically produces a 1-byte file, and the consequence chain
is worth following to its end: 1 byte is one **zero-length record**, a zero-length
record **fails fixed-width parsing** by the exact-length contract in section 4.2, and
the run then fails at load - so the scenario would report a **parsing defect instead of
exercising the empty-input path it exists for**. The general rule's own purpose survives
intact regardless: `wc -l` returns `0`, which still equals the record count of `0`.
[`../../README.md`](../../README.md) section 5.6 names the zero-byte file as the sole
case carrying no trailing newline.

Both line-ending measurements are zero and both were taken: zero line-feed bytes and
zero carriage-return bytes. The tree's LF-only rule is honoured by there being no line
to end.

### 4.4 The width is corroborated twice, from mutually independent artifacts

The 53 above is not asserted from a single source:

1. **The `PICTURE` arithmetic** at `COBTUPDT.cbl` L71 to L77, summed in section 4.1.
2. **The JCL driver's own comment block** at `MNTTRDB2.jcl` L11 to L18, which documents
   column 1 as the action code, columns 2 to 3 as the transaction type described as a
   numeric value (L16), and columns 4 to 53 as the description (L18). Column 53 is the
   last column documented, so the record is 53 bytes - reached without touching a
   `PICTURE` clause.

Two agreeing derivations from unrelated files is what makes the width checkable rather
than merely stated. **The JCL block is 1-based; the table in section 4.1 and the whole
of [`../../README.md`](../../README.md) are 0-based.** The bases are called out because
the baseline itself uses both, and leaving either implicit is a guaranteed one-byte
error on every field rather than a matter of taste.

Worth recording positively: **the 53-byte regime is new to this repository.** The house
record-length enumeration at `tests/fixtures/README.md` L140 to L142 lists 350, 300,
150, 50, 500 and 80, and **53 is not among them.** No existing fixture width can be
copied here, so the derivation above is the only source for it and is carried in this
domain rather than assumed from house precedent.

### 4.5 What governs the fill: this record has no `FILLER` at all

Stated positively, because a reader arriving from the two opposing `FILLER` regimes in
[`../../README.md`](../../README.md) section 5.3 will otherwise ask which one applies
here. **Neither applies, because there is no `FILLER` to fill.** `1 + 2 + 50 = 53`
accounts for the whole record with nothing left over, all three fields are `PIC X(n)`,
and the program's own `VALUE SPACES` on each of them at L73, L75 and L77 settles the pad
character. The generic right-pad-with-spaces rule therefore governs this record
completely. `FILLER` dropped: **none.**

It follows that **no zoned decimal, no sign overpunch and no packed field arises
anywhere in this domain**, so none of that machinery is in play for this fixture even
when it is populated. For a zero-byte file the point holds twice: there is no field to
pad and no byte to pad it with.

### 4.6 Nothing to source verbatim here, and where a value would have come from

**No value in this scenario is sourced from anywhere, because the file carries no
bytes.** No action code, no type code and no description is present, so the
verbatim-sourcing rule has nothing to bind to. That is stated positively rather than by
omitting the topic, so that a reviewer comparing this directory against its siblings
sees a deliberate non-application rather than a missing section.

Recorded for completeness, because it is the derivation mistake most likely to look
reasonable to whoever populates a fixture in this domain next:

- **A description would come verbatim from `app/data/ASCII/trantype.txt`**, whose seven
  rows are mixed case - `01` `Purchase`, `02` `Payment`, `03` `Credit`, `04`
  `Authorization`, `05` `Refund`, `06` `Reversal`, `07` `Adjustment`.
- **It would never come from
  `app/app-transaction-type-db2/ctl/DB2LTTYP.ctl` or `DB2LTCAT.ctl`**, which carry the
  same reference data in UPPERCASE and additionally misspell one value, `'REVERAL'` at
  `DB2LTTYP.ctl` L22. `V2__seed_reference.sql` seeds from the ASCII datasets, so a
  control-card-derived value would not match the rows seeded into the schema and would
  breach **AAP Rule T8**, under which user-visible strings are carried across character
  for character. [`../../README.md`](../../README.md) section 6.6 records the same
  rejected source by path.

### 4.7 No synthetic-data attestation is owed here

Also stated positively, because an unexplained absence is indistinguishable from an
oversight. The house attestation obligation at `tests/fixtures/README.md` section 10.3
is scoped to scenario directories holding primary account or identity data - card
number, customer name, address, national identifier, government-issued identifier, date
of birth, telephone number or credit score.

`WS-INPUT-REC` is an action byte, a two-character reference code and a description: it
carries **none of those eight categories**. So the obligation does not attach to this
domain, and with a zero-byte file the point holds doubly - there are no bytes here to
attest to at all. [`../../README.md`](../../README.md) section 8.6 reaches the same
conclusion for this domain and records that the obligation triggers in exactly one of
the tree's five domains, which is not this one.

### 4.8 The audits that do not apply to a zero-record file

Several of the tree's byte-level audits have **no row to inspect** here. They are named
and marked inapplicable rather than omitted or claimed as passing:

| Audit | What it would require of a row | Status here |
|---|---|---|
| Length | Every physical line exactly 53 bytes | **Not applicable** - zero lines. The file-level arithmetic in section 4.2 still holds and was measured |
| Fill | `FILLER` bytes matching the source regime | **Not applicable** - this record declares no `FILLER` at all (section 4.5) |
| Overpunch | A signed zoned-decimal field decoding through the sign tables | **Not applicable** - all three fields are `PIC X(n)`; this domain has no signed numeric field |
| Verbatim | Every description matching its seeded value character for character | **Not applicable** - no description byte is present (section 4.6) |
| Discriminator | A scenario claiming a rate-fallback proof to use the documented pair | **Not applicable** - that gate belongs to the `disclosure_group` domain, not to this one |
| Dispatch coverage | The action byte at offset 0 to be one of the declared values | **Holds vacuously, by design** - zero rows means zero byte-0 values, and the absence of any dispatch is the entire subject of this scenario |

Assumptions: silently omitting these would read as an oversight, and recording any of
them as *passing* would assert something about bytes that do not exist. Naming them and
marking them inapplicable is the only honest third option, and it is what lets a later
reader tell "this was considered and does not apply" apart from "this was never looked
at". The dispatch-coverage row is called out separately because it is the one that could
be mistaken for a coverage gap: the consuming test that inspects byte 0 across this
domain enrols the two populated siblings and deliberately does not enrol this file,
which is correct rather than an omission.

---

## 5. Failure modes these bytes can produce - Exceptions

Exceptions: the failures below are the ones a consumer of *these* bytes can actually
hit, which is Rule 1's L21 element applied to a fixture rather than to a function. That
label names the element and not a fourth rationale category - the rationale labels
available in this tree remain the three of section 6. Each row pairs a mistake with the
symptom it actually produces, because the dangerous ones here are the quiet ones and the
loud ones are misattributed.

| Failure | Symptom |
|---|---|
| A newline appended to this file by an editor that insists on terminating text | The file becomes **1 byte**: one zero-length record, which fails fixed-width parsing. The scenario then reports a parsing failure instead of exercising the empty path. **Loud, but misattributed** - the symptom names the decoder, not the edit |
| A blank 53-byte row added "to make the file valid" | The file is no longer empty and no longer this scenario. Byte 0 holds a space, which is none of the four declared action values, so the row reaches the catch-all at L122 and the run ends on the warn-tier code. **The scenario would assert a soft reject under the name of the quiet case** |
| The file dropped by a copy step rather than packaged | The consumer resolves it from the classpath and raises rather than skipping, so this fails a test instead of silently reading as the empty case. **Loud - this is the good case** |
| A carriage return introduced by a CRLF-normalising tool | The file becomes 1 byte or 2, which is not a whole number of 53-byte records. Rejected at load |
| The zero-byte file read as though a zero-length image were a record | A record-length failure rather than an empty field map. Correct behaviour, and asserted deliberately by the consumer named in section 3.3 - it is not the same claim as "zero records", and section 3.3 separates the two |
| `9999-ABEND` read as a termination when comparing this scenario against its neighbour | The neighbour is mis-documented as a crash when it is a soft reject that continues to the next record. **Silent** - it corrupts the comparison rather than the bytes, which is why section 2.3 states the paragraph's actual body |
| A one-byte miscount in any populated sibling of this domain | Every field after the miscount shifts, so a plausible-looking wrong value is decoded rather than an exception raised. **Silent**, which is why section 4.1 states the width and the offset of every field |

---

## 6. Why these bytes and not others

The rationale labels below are **plural, ASCII and unemphasised**, with the trailing
colon part of the label, as [`../../README.md`](../../README.md) section 2.2 fixes for
this tree and as
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md)
states for every language in it. Rule 1 L31 to L34 names four categories and **three of
them are available here** - the three used below. **The fourth is factually unavailable
and is therefore used nowhere in this file.** L32 scopes that one by its own definition
to replacing existing code, and nothing in this directory replaces anything: this tree
is additive beside an untouched baseline and beside the untouched house oracle tree,
which stays exactly where it is. Reaching for it would mean claiming a predecessor that
does not exist, which would itself breach Rule 1's ban on unsupported rationale.
[`../../README.md`](../../README.md) section 2.2 rules the same way for the same reason
at tree scope.

### 6.1 Alternatives Considered: the file name

`trtype-update.txt` is the name used by **every scenario in this domain**, so the
consumer resolves one name per scenario directory. Consistency matters more here than
anywhere else in the tree: **an empty file has no content to identify it, so the name is
the only signal of what it stands in for.** Four alternatives were weighed and each was
rejected for a stated consequence:

- **`inpfile.txt`** - rejected. It would follow the baseline literally, from
  `//INPFILE  DD  DSN=INPFILE,DISP=SHR` at `MNTTRDB2.jcl` L27 and `ASSIGN TO INPFILE` at
  `COBTUPDT.cbl` L31. But the DSN there is literally the string `INPFILE`: it is a
  generic data-definition placeholder that **identifies no particular stream**, so the
  name would carry no information about which reference dataset the file maintains.
- **`mnttrdb2.txt`** - rejected. It names the **job** that runs the program, not the
  stream the job supplies.
- **`COBTUPDT.txt`** - rejected. A fixture is never named for a **program**.
- **`WS-INPUT-REC.txt`** - rejected. A fixture is never named for a
  **working-storage group**, for the same reason [`../../README.md`](../../README.md)
  section 8.2 rule 3 forbids naming one for a copybook: no file anywhere in the house
  tree is named that way, and a layout-derived name invites the reader to treat the
  fixture as a second declaration of the layout.

The hyphenated form departs deliberately from the eight-character DSN-shaped names used
elsewhere in the house tree, and the reason is factual rather than aesthetic: **no
eight-character DSN exists for this stream.** The only dataset name the baseline supplies
is the generic `INPFILE`, so there is nothing DSN-shaped to copy, and the name describes
the stream's purpose instead - the transaction-type reference data it maintains.

### 6.2 Alternatives Considered: a file containing one blank line

The rejected alternative, and the one that reads as "empty" to a person: a file holding
a single newline, or a single all-blank 53-byte row.

**Rejected, and the consequence chain is the reason rather than convention.** A lone
newline makes the file **1 byte**, which is **one zero-length record**, which **fails
fixed-width parsing** under the exact-length contract in section 4.2 - so the run would
end in **an outcome that looks like a parsing defect rather than an empty-input test**,
and the behaviour this scenario exists to pin would never be reached. An all-blank
53-byte row fails differently and worse: it is a well-formed record, so it loads
cleanly, and its space at offset 0 is none of the four declared action values, so it
reaches the catch-all at L122 and moves the condition code to 4. That variant would
assert a **soft reject** while wearing the name of the quiet case, and nothing about it
would look wrong.

Two independent authorities converge on the same resolution:
`tests/fixtures/README.md` L147 to L148, where the only input treated as empty is a
genuinely zero-byte dataset, and [`../../README.md`](../../README.md) section 6.5, which
states it as "a zero-byte file and nothing else".

### 6.3 The external contracts this fixture depends on

Assumptions: four contracts outside this directory have to hold for these bytes to mean
what section 3 says they mean, and each is named with the consequence of its failing.

- **The exact-length contract holds, and its rationale is a deliberate stance rather
  than an implementation detail.** `tests/fixtures/README.md` L144 to L151 records that
  the loaders reject any off-length row without padding, truncating or dropping blank
  lines, and at L150 to L151 states the reason as a deliberate **financial-integrity
  stance**: a malformed record must never be silently coerced into a well-formed-looking
  one. The rejected alternative there - tolerating an off-length row - would accept the
  row and shift every field after the error, producing a fixture that loads cleanly and
  asserts the wrong values. A loud failure at load is the only outcome that surfaces the
  mistake, and it is what makes 0 records the only possible reading of 0 bytes here.
  Worth noting so the stance is not read too narrowly: the house states it of a malformed
  *monetary* record, and this domain carries no monetary field at all - but the failure it
  guards against is a silent field shift, which is not specific to money, so the stance
  governs a record of an action byte, a code and a description exactly as it governs a
  balance.
- **`OPEN INPUT` on an empty sequential file succeeds.** This scenario depends on it:
  the file status at L84 is `'00'`, so L85 reports `'OPEN FILE OK'` and the run proceeds
  to its first read. An empty file and a missing dataset are different conditions, and
  only the first is being exercised - a missing dataset would be the other branch at L87
  and a different scenario.
- **The width this file is empty of is 53 bytes**, derived twice over in section 4.4.
  The consuming assertions name that width alongside the record count of zero, so the
  width is part of this fixture's contract even though no byte of it is present.
- **The seeded schema exists before the run**, from `V1__reference.sql` and
  `V2__seed_reference.sql` executing under the test profile as section 3.4 records.
  Without that, "the seeded tables are unchanged" would be a claim about tables that
  were never populated, and would prove nothing.

### 6.4 What this scenario gives up, and what it buys

Trade-offs: three compromises were accepted here, and none of them is cost-free.

- **This scenario deliberately exercises no dispatch branch at all, and that is what it
  is for.** The trade accepted is that it adds no coverage of any action code - the
  populated siblings carry that - in exchange for pinning two facts nothing else can
  reach: that the priming read at L92, standing before the loop at L93, is safe on a
  zero-record input rather than consuming or skipping a record that is not there; and
  that an empty input is a **clean no-work run** rather than an error, distinguishable
  from every other quiet path by the absence of any report.
- **The layout of an absent record is tabled here rather than only cited.**
  [`../../README.md`](../../README.md) section 5.8 warns that a layout restated in two
  places drifts in two places, and section 3.6 already carries this record at tree
  scope. The trade accepted is one derived index in each of two documents, taken because
  section 8.4 makes per-field offset, `PICTURE`, fill and declaring line part of the
  Parameters element every scenario README owes; the drift risk is contained by citing a
  COBOL line for every row, so a disagreement is settled by opening the program rather
  than by choosing between two documents.
- **The file name is hyphenated and not DSN-shaped**, which departs from house naming
  as section 6.1 records. Accepted because the only dataset name the baseline offers is
  a generic placeholder, and a name that identifies the stream is worth more here than
  one that matches a shape.

---

## 7. Validation gates, and how this file discharges Rule 1

[`../../README.md`](../../README.md) section 13 states ten gates. Their outcomes for
this directory, each run rather than assumed:

| Gate | Outcome here |
|---|---|
| 1. Length | **Not applicable** - zero lines (section 4.8). The file-level arithmetic `0 = 0 x 53 + 0` was measured and holds |
| 2. Line-ending | **Pass** - zero carriage returns and zero line feeds; no trailing newline, which section 4.3 records as the zero-byte exception rather than an omission |
| 3. Fill | **Not applicable** - this record declares no `FILLER` at all (section 4.5) |
| 4. Overpunch | **Not applicable** - no signed numeric field exists in this record (section 4.5) |
| 5. Verbatim | **Not applicable** to the bytes, since no description is present (section 4.6). Every program literal quoted in this document was checked character for character against the source, `'PROCESSING   '` included, with its three spaces |
| 6. Discriminator | **Not applicable** - that gate belongs to the `disclosure_group` domain |
| 7. README completeness | **Pass** - this file, carrying all four house scenario items and all four Rule 1 elements as tabled below |
| 8. Prose | **Pass** - zero non-ASCII bytes, verified mechanically with `LC_ALL=C grep -nP '[^\x00-\x7F]'` rather than by eye; every rationale names a concrete consequence; the fourth rationale label absent for the reason in section 6 |
| 9. Reference-integrity | **Pass** - nothing under `app/**`, `tests/**`, `scripts/**` or `samples/**` is modified. Those trees are REFERENCE-ONLY, and the seeds named in section 4.6 are inputs to derivation, never edited |
| 10. Live check | Delegated to the reactor build [`../../README.md`](../../README.md) section 10 commands, which resolves this file from the test classpath through the consumers of section 3.3 |

The emptiness audit is the load-bearing one here, and its measurements are in section
4.2.

**How this file discharges user-specified Rule 1.** [`../../README.md`](../../README.md)
section 8.4 maps the four house scenario items onto Rule 1's four docstring elements,
and this document satisfies both readings at once:

| Rule 1 element | Where |
|---|---|
| Purpose (L18) | Sections 1 and 2, including the control flow at 2.1 and the reason the scenario is worth having at 2.4 |
| Parameters (L19) | Section 4 - width 53, 0 records, 0 bytes, no trailing newline, plus offset, `PICTURE`, fill character and declaring line per field at 4.1 |
| Return values (L20) | Section 3 - the enumerated assertions at 3.1 with the `RETURN-CODE` mechanism named, the migrated result member by member at 3.2, and the consumers and what they assert at 3.3 |
| Exceptions (L21) | Section 5, and the two failure paths this scenario is distinguished from at 2.3 |

Rule 1 L43 is the operative gate and it is conjunctive: code missing either the
documentation or the decision rationale fails review. Its named triad is purpose,
parameters and return values, so the exceptions element rests on L21's "where
applicable", and for a fixture whose whole subject is an absence it is plainly
applicable. L43 is also what makes the labelled justifications in section 6 obligations
rather than courtesies. **There is no linter behind any of it** - the header block
records why - so this document is the whole of this scenario's defence, which is the
reason it states the byte count it was measured at rather than the byte count it was
expected to have.
