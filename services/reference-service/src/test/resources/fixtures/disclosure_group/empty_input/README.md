# `disclosure_group/empty_input` -- no rate rows at all

## 1. Scenario intent

The degenerate case: the disclosure-group dataset is empty, so neither
the composed key nor the `DEFAULT` group can be found. It separates *missing key
with a populated file* from *nothing to read*, which are different failures.

---

## 2. The exact business rule it exercises

The same `CBACT04C` lookup, but with the fallback itself unable to
resolve. Both reads return status 23, and the baseline reports the condition rather
than accruing on an unknown rate -- the accrual has no defensible value to compute
when even the `DEFAULT` group is absent.

---

## 3. Expected outcome

Both the composed-key read and the `DEFAULT` retry find nothing. No
rate is available, and the condition is reported rather than silently treated as a
zero rate.

---

## 4. Fixture bytes and governance

### 4.1 Per-file record width, record count and line ending

| File | Record width | Records | Line ending |
|---|---|---|---|
| `discgrp.txt` | n/a -- empty | 0 | none -- 0 bytes |

Every file in this directory uses **LF** line endings and carries no carriage return; a CRLF file would add a byte to every record and shift every offset after the first.

### 4.1.1 Derivation, per file

- **`discgrp.txt`** -- A **genuinely zero-byte file**, which is the
only correct encoding of an empty fixed-width input (tree README section 5.2). It
is 0 bytes with no carriage return and no line feed.

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

- Zero bytes is the only encoding of "empty" this tree accepts; a single blank line is a one-byte file holding one zero-length record and fails record parsing instead of exercising the empty path. ../../README.md section 6.5 records the decision.
- An empty disclosure-group dataset is NOT a quiet zero-rate run. It is the input that drives the baseline's abend chain, so this scenario asserts a failure and says so explicitly - ../../README.md section 6.3 requires a fixture that omits the DEFAULT group to state that it is asserting an abend.

---

## 8. Hazards and choices the second derivation recorded

Assumptions: these bytes were derived TWICE, independently, and both records are kept
because each names hazards the other does not. The section above states why the bytes
are what they are; the paragraphs below state what a later edit of them would break.
Their labels are given unemphasised, as
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md)
requires -- the second derivation wrote them in bold, which that document lists among the
four ways the label has actually been written wrongly.

- Assumptions: a missing `DEFAULT` group presents as an ABEND in the batch flow. A fixture that omits the group is therefore asserting a crash, and the charter requires the scenario README to say so - which this section does.
- Assumptions: the tables above list the fields **this scenario gives a value to**, each with its offset, its `PICTURE` and the copybook or program line that declares it. The complete record layout, including any field this scenario does not vary and the fill regime for each, is tabled once in the tree charter section 3.4 and is cited rather than restated in full. Copying a whole layout table into each scenario directory would create as many places for the geometry to drift as there are scenarios, which the charter forbids at its section 5.8; citing a line per field costs nothing and points a reader at the normative source rather than at a copy of it.
- Alternatives Considered: a file holding only the `ZEROAPR` group, whose seventeen rows are all `0.00`. Rejected because it is a different scenario: the lookup would still miss and still fall back, and the fallback would still fail, but the file would no longer be empty and the charter's definition of empty would no longer apply to it. `ZEROAPR` is the natural zero-RATE control group; it is not a substitute for an absent table.
