# `batch_reference_update/invalid_type_soft_reject` -- an unrecognised action byte is rejected and the run continues

Why this file exists: `trtype-update.txt` is fixed-width, so every byte position is
data and a `#` comment would be a wrong-length row the loader rejects outright. A
README is the only carrier available for the reasoning behind these bytes. The
tree-scope contract -- the byte tables, the 0-based offset convention, the two
`FILLER` regimes, the line-ending and trailing-newline rulings, the label canon and
the validation gates -- is single-sourced in [`../../README.md`](../../README.md) and
is deliberately not reproduced here. This file records only what is specific to this
scenario.

## 1. Scenario intent

The reject path of the batch reader, and specifically a reject that is
**easy to author by accident**: an action byte of the right letter in the wrong
case. It also proves the reject does not stop the run, because a second, entirely
valid record follows the bad one and IS still processed.

---

## 2. The exact business rule it exercises

The `WHEN OTHER` arm of the `EVALUATE` at
[`app/app-transaction-type-db2/cbl/COBTUPDT.cbl`](../../../../../../../../app/app-transaction-type-db2/cbl/COBTUPDT.cbl)
lines 122 to 128: an action byte outside `'A'`, `'U'`, `'D'` and `'*'` builds the
message `'ERROR: TYPE NOT VALID'` and performs `9999-ABEND`. A COBOL `EVALUATE`
compares the bytes of the field against each literal, so lower-case `'a'` does not
satisfy `WHEN 'A'` and falls to `WHEN OTHER`.

That paragraph's NAME is the trap, and reading it is what settles this scenario.
`9999-ABEND` at lines 230 to 233 does three things and no more: it displays the
message, it moves 4 to `RETURN-CODE`, and it exits the paragraph. It issues no
`STOP RUN`, no `GOBACK` and no non-zero-CC abend, so control returns through
`1003-TREAT-RECORD` to the `PERFORM UNTIL LASTREC = 'Y'` loop at lines 93 to 96,
which reads and treats the following record. The observable outcome of an
unrecognised action byte is therefore a displayed message, a warning-level return
code of 4, and a run that keeps going.

### 2.1 This is the reject arm, not the comment arm

The `WHEN '*'` arm one line earlier is the branch this one is most easily confused
with, and the two are opposites. Line 121 is a `DISPLAY` of
`'IGNORING COMMENTED LINE'` with **no `PERFORM` after it**, so a commented row has no
action taken for it and the return code is left untouched. This row is not passed
over that way: it builds a message and performs the paragraph that moves 4 into the
return-code register. Reading this scenario as a skipped row describes line 120 and
not line 122, and it is the one misreading that makes the fixture's second row look
pointless. The consumer keeps the split explicit -- the comment byte produces an
APPLIED outcome carrying `RecordAction.COMMENT`, the unrecognised byte a REJECTED one
carrying `RecordAction.INVALID` -- so a commented row does not raise the aggregate
this scenario is aimed at and this row does.

Assumptions: `RETURN-CODE` is a run-level register and not a tally. Every refusal
moves exactly 4 into it and a later refusal overwrites the earlier value, so it
carries neither a count of what was refused nor the identity of it. That is why the
consumer carries a per-record outcome list beside the aggregate rather than the bare
value, and it is a second, independent source reaching the same reading of the
paragraph as the section above.

Assumptions: a paragraph elsewhere in the corpus shares this one's name at the
opposite severity tier, and conflating them inverts the control flow.
`9999-ABEND-PROGRAM` at
[`app/cbl/CBACT04C.cbl`](../../../../../../../../app/cbl/CBACT04C.cbl) lines 628 to
632 calls the language-environment routine `CEE3ABD`, which does end the run at once.
The paragraph transcribed for this scenario calls nothing and ends nothing.

---

## 3. Expected outcome

Record 1 is REJECTED with the message `ERROR: TYPE NOT VALID`, verbatim, and the
run's return code becomes 4. Record 2 is then read and dispatched normally, so its
valid update IS applied. The run ends through `2001-CLOSE-STOP` at line 234 with
return code 4 -- a warning, not a failure. A consumer that reported a halted run,
an unapplied record 2 or a return code other than 4 would be asserting the opposite
of this scenario.

---

## 4. Fixture bytes and governance

### 4.1 Per-file record width, record count and line ending

| File | Record width | Records | Line ending |
|---|---|---|---|
| `trtype-update.txt` | 53 bytes | 2 | one trailing LF |

Every file in this directory uses **LF** line endings and carries no carriage return; a CRLF file would add a byte to every record and shift every offset after the first.

### 4.1.1 Derivation, per file

- **`trtype-update.txt`** -- Both records are **authored** -- there is
no seed for this 53-byte record. Row 1 is `a01Purchase` padded to 53: the action
byte is lower-case `'a'`, which is the whole point, and the rest of the row is
deliberately well-formed so that nothing but the case of one byte can explain the
reject. Row 2 is `U02Payment` padded to 53 -- a valid update action that exists to
be applied AFTER the reject, which is how the fixture demonstrates that the reject
advances the loop instead of ending the run.

Assumptions: the two type codes and their descriptions are lifted **verbatim** from
[`app/data/ASCII/trantype.txt`](../../../../../../../../app/data/ASCII/trantype.txt)
rows 1 and 2 -- `01` `Purchase` and `02` `Payment`. That seed is the 60-byte
`CVTRA03Y` record, so its description field already occupies exactly 50 bytes
right-space-padded, the same width as `INPUT-REC-DESC PIC X(50)`, and a description
lifts across with no re-padding at all. Mind the bases when checking it: in the seed
the description is 0-based [2..51], in this record it is [3..52] -- same content,
different position. The lift also cannot carry the seed's carriage return, because
rows 1 to 6 of that file end CRLF and the CR sits at byte 60, past the `FILLER`
region and well past the description.

Alternatives Considered: taking the descriptions from
[`DB2LTTYP.ctl`](../../../../../../../../app/app-transaction-type-db2/ctl/DB2LTTYP.ctl),
rejected. That control card carries the same seven values in UPPERCASE and misspells
one of them, `'REVERAL'` at its line 22, so a value taken from it would not match the
rows `V2__seed_reference.sql` seeds and would breach AAP Rule T8 on verbatim
user-visible strings; charter section 6.6 rules the same way for the tree as a whole.
The trap is sharper in this directory than elsewhere: the subject of this scenario is
the case of a single byte, so an UPPERCASE description beside a lower-case action byte
would put two unrelated case concerns in one row and leave a reader unable to tell
which of them the row pins.

### 4.2 Acceptance arithmetic, and the layout this file does not repeat

Charter section 5.7 gives `bytes = rows x RECLN + rows`, which here is `2 x 53 + 2` =
**108 bytes**. The file measures exactly that, two rows of exactly 53, zero carriage
returns and no blank line. The normative 53-byte layout -- every field with its
0-based offset, `PICTURE`, fill character and declaring line -- is tabled once at
charter section 3.6 and is deliberately not repeated here, for the reason the charter
gives at section 5.8: a layout copied per scenario creates as many places for the
geometry to drift as there are scenarios. What is restated here is only the minimum a
reader needs to check a row of THIS fixture without leaving the directory -- offsets
0-based, as charter section 3.1 declares once for the whole tree:

| Declared at | Field | `PICTURE` | Offset | Length | Fill |
|---|---|---|---:|---:|---|
| line 72 (`VALUE SPACES` on line 73) | `INPUT-REC-TYPE` | `X(1)` | 0 | 1 | none -- a single byte |
| line 74 (`VALUE SPACES` on line 75) | `INPUT-REC-NUMBER` | `X(2)` | 1 | 2 | right-space-pad |
| line 76 (`VALUE SPACES` on line 77) | `INPUT-REC-DESC` | `X(50)` | 3 | 50 | right-space-pad |

`1 + 2 + 50` = 53, so the record ends at offset 52 and byte 53 of a physical row is
already the LF.

Worth stating positively, because charter section 5.3 warns of two opposite `FILLER`
regimes and a reader will arrive asking which applies: **this record has no `FILLER`
at all**, so neither does. All three fields are `PIC X(n)`, the right-space-pad rule
governs the record completely, and the zoned-decimal sign overpunch of charter
section 5.4 arises nowhere in this domain.

Assumptions: `PIC` and `VALUE SPACES` sit on **separate physical lines** for all three
fields -- 72 and 73, 74 and 75, 76 and 77 -- so a layout extractor assuming one
declaration per line reads three fields with no pictures. The program also carries
legacy sequence numbers in source columns 73 to 80, `00592033` beside line 72 among
them; those columns are source identification, and bytes 0 through 52 of a fixture row
are the record and nothing else.

### 4.3 A second, unrelated artifact agrees on the width and on the allowed set

[`MNTTRDB2.jcl`](../../../../../../../../app/app-transaction-type-db2/jcl/MNTTRDB2.jcl)
documents the same record without reference to the copybook, in **1-based** columns:
`COLUMN 1` at lines 11 to 14, `COLUMNS 2-3` at line 16 and `COLUMNS 4-53` at line 18.
Column 53 is the last named, so the record is 53 bytes. Those same lines 11 to 14 are
the independent statement of what makes a byte invalid, enumerating the allowed
column-1 values as exactly `A`, `D`, `U` and `*`.

Two cautions on reading it. Its columns are 1-based where this tree is 0-based
throughout (charter section 3.1), so its `COLUMNS 4-53` is this document's [3..52], and
mixing the bases is a one-byte error on every field. And it lists the set as `A`, `D`,
`U`, `*` while the `EVALUATE` dispatches `A` at 111, `U` at 114, `D` at 117 and `*` at
120 -- the SET is identical and the ORDER is not, so no dispatch order may be inferred
from either listing.

Assumptions: 53 bytes is a width new to this repository -- the house record-length
enumeration at `tests/fixtures/README.md` line 141 lists 350, 300, 150, 50, 500 and 80
and does not include 53, so no existing house fixture or loader covers it. Lines 31 to
34 declare `ORGANIZATION IS SEQUENTIAL` with `ACCESS MODE IS SEQUENTIAL` and line 101
reads `NEXT RECORD`, so these rows carry no key-ordering requirement and the house
add-a-scenario step about pre-sorting an indexed input does not apply here; the only
ordering that matters is the one section 7 records.

---

## 5. Failure modes these bytes can produce

Assumptions: the failure modes below are the ones a consumer of *these* bytes can hit, which is the Rule 1 exceptions element applied to a fixture rather than to a function.

- A record read at the wrong declared width mis-aligns every field after the first, and the symptom is a plausible-looking wrong value rather than an exception, which is why section 4.1 states the width per file.
- A file rewritten by an editor that appends a final newline to an intentionally zero-byte fixture turns it into a 1-byte file holding one zero-length record, which fails fixed-width parsing instead of exercising the empty path.
- A file rewritten with CRLF endings shifts every offset and every record boundary.
- A one-byte miscount in any field shifts every field after it, and because all three fields are character fields the result decodes without complaint into plausible wrong values rather than raising anything.
- A row of the wrong LENGTH fails somewhere else entirely, and the two failures must not be read as one. `com.carddemo.common.codec.FixedWidthCodec` validates the record length against the layout's `reclen` **before it reads any field** and throws `RecordLengthException` naming the layout, the width expected and the width received, so an off-length row never reaches the `EVALUATE` at all. Row 1 here is invalid in its **content** and exactly right in its **length**; a consumer reporting a length failure for it would be describing a broken fixture rather than the dispatch this scenario exercises.
- Only a genuinely zero-byte file counts as empty, per charter section 5.2. That is the `empty_input` sibling's contract and not this one's -- this file is 108 bytes, and a consumer finding it empty has resolved the wrong path.

---

## 6. Consumer

The consumer is `services/reference-service/src/test/java/com/carddemo/reference/fixtures/ReferenceFixtureContractTest.java`, which loads every file in this tree from the classpath and asserts its width, its record count, its line ending and the field values named above. **The bytes are the contract**: a consumer's expected value is whatever these bytes decode to, and a disagreement is resolved by reading the bytes rather than by editing them.

The production consumer is
[`ReferenceBatchUpdateService`](../../../../../main/java/com/carddemo/reference/service/ReferenceBatchUpdateService.java):
`BatchUpdateResult apply(java.io.InputStream)` reads contiguous 53-byte records, and
`RecordOutcome applyRecord(byte[])` transcribes `1003-TREAT-RECORD` in the dispatch
order `A` / `U` / `D` / `*` / OTHER. For this fixture row 1 yields
`RecordAction.INVALID` with `RejectReason.INVALID_ACTION_CODE` and the message
constant holding `ERROR: TYPE NOT VALID`, and row 2 yields an applied
`RecordAction.UPDATE`. The member this scenario is aimed at most directly is the
aggregate `anyRejected` on `BatchUpdateResult`, whose derived `returnCode()` reports
the 4.

Assumptions: it is a plain service method and not a Spring Batch job -- there is no
job, no step, no job repository and no `batch_run` ledger on this path, and none is to
be described as though there were. This path also issues no SQL and touches no table
at all, so there is no database effect to assert: `COBTUPDT.cbl` line 54 includes only
`DCLTRTYP`, so `reference.transaction_categories` is not involved in this domain even
on the arms that do write.

Availability: the service and the fixture test classes named above are **present on
this branch**. No test class asserting this fixture's run-level outcome is named here,
because naming a planned artifact would be a contract for when it lands rather than a
claim that it exists.

---

## 7. Why these bytes and not others

Assumptions: the choices below are properties of the FIXTURE rather than of the
rule it exercises, so they are recorded here rather than in section 2. Each one is a
decision a later author could reverse without any test failing, which is why the
reason is written down beside it.

- The invalid row is FIRST, and the order is the assertion. The read at line 101 is a sequential read inside the loop at lines 93 to 96, so a reject on record 1 followed by a processed record 2 is the evidence that the loop advances. Putting the invalid row second would leave the continuation unobservable, because there would be no record after it to process.
- The second row is a VALID 'U' row on purpose. It is the evidence that the run continued: had the reject ended the run, a legitimate update would have gone unapplied and the difference would be observable.
- The invalid byte is a LOWERCASE 'a' rather than an obviously foreign character. COBOL's EVALUATE compares bytes, so 'a' is as invalid as '?' - and a lowercase letter is the realistic form of this mistake, which is the case worth pinning.

---

## 8. Refactoring Rationale: why this directory was renamed

This scenario was named `invalid_type_abend` and this document previously stated
that the run "abends on record 1", that "record 2 is never processed", and that the
fixture's row order proved "processing stops at the offending record". All three were
read from the NAME of the `9999-ABEND` paragraph rather than from its body, and the
body -- three statements at lines 230 to 233 -- does not stop anything. The
directory is renamed to `invalid_type_soft_reject` and the outcome restated, because
a fixture whose directory name asserts a halt teaches every later reader the wrong
control flow, and the consuming test would have been written to assert it.

The former name is not present in this tree and is not to be re-created. Both fixture
test classes resolve this scenario as
`batch_reference_update/invalid_type_soft_reject/trtype-update.txt` by that path, and
the charter's section 10 inventory counts three scenarios in this domain, so a
directory under the old name would be read by nothing and would put that inventory out
of step with the directory it is measured from. A reader who arrives here from an
earlier reference to the old name is in the right place.

Assumptions: the reject is not silent either, which is why "soft" and not "ignored"
is the word. The return code becomes 4, and the repository's own suite treats a
warning-level aggregate return code as its green state, so a run of this fixture is
expected to end at 4 rather than at 0. A consumer asserting 0 would be asserting
that the invalid record was accepted.

---

## 9. Hazards and choices the second derivation recorded

Assumptions: these bytes were derived TWICE, independently, and both records are kept
because each names hazards the other does not. The section above states why the bytes
are what they are; the paragraphs below state what a later edit of them would break.
Their labels are given unemphasised, as
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md)
requires -- the second derivation wrote them in bold, which that document lists among the
four ways the label has actually been written wrongly.

- Assumptions: the tables above list the fields **this scenario gives a value to**, each with its offset, its `PICTURE` and the copybook or program line that declares it. The complete record layout, including any field this scenario does not vary and the fill regime for each, is tabled once in the tree charter section 3.6 and is cited rather than restated in full. Copying a whole layout table into each scenario directory would create as many places for the geometry to drift as there are scenarios, which the charter forbids at its section 5.8; citing a line per field costs nothing and points a reader at the normative source rather than at a copy of it.
- Assumptions: the invalid row is placed FIRST and a valid row follows it. With the order reversed the file could not distinguish an abend from a skip, because either behaviour would leave the same single update applied.
- Trade-offs: the invalid byte differs from a valid one only in case, which is less obvious on sight than, say, a `Z` would be. It is chosen anyway because case sensitivity is the property most likely to be assumed away by a reimplementation, and a `Z` would exercise the same branch while proving less.
- Alternatives Considered: a SPACE in byte 0, which also falls to `WHEN OTHER` and is the shape a mis-padded row takes rather than a mis-typed one. Rejected as this scenario's subject because a space in byte 0 is indistinguishable on sight from a formatting slip, so the fixture's intent would stop being readable from the fixture.

---

## 10. Data governance

No synthetic-provenance attestation is owed for this directory. `WS-INPUT-REC` is an
action byte, a two-character type number and a description, with no primary account
number, no account or customer identifier, no national or government identifier, no
date of birth, no telephone number and no credit score, so the house obligation at
`tests/fixtures/README.md` section 10.3 (line 818) does not attach -- which is the
scoping the tree charter already records for this domain at its section 8.6.
