# `batch_reference_update/invalid_type_soft_reject` -- an unrecognised action byte is rejected and the run continues

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

---

## 5. Failure modes these bytes can produce

Assumptions: the failure modes below are the ones a consumer of *these* bytes can hit, which is the Rule 1 exceptions element applied to a fixture rather than to a function.

- A record read at the wrong declared width mis-aligns every field after the first, and the symptom is a plausible-looking wrong value rather than an exception, which is why section 4.1 states the width per file.
- A file rewritten by an editor that appends a final newline to an intentionally zero-byte fixture turns it into a 1-byte file holding one zero-length record, which fails fixed-width parsing instead of exercising the empty path.
- A file rewritten with CRLF endings shifts every offset and every record boundary.

---

## 6. Consumer

The consumer is `services/reference-service/src/test/java/com/carddemo/reference/fixtures/ReferenceFixtureContractTest.java`, which loads every file in this tree from the classpath and asserts its width, its record count, its line ending and the field values named above. **The bytes are the contract**: a consumer's expected value is whatever these bytes decode to, and a disagreement is resolved by reading the bytes rather than by editing them.

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
