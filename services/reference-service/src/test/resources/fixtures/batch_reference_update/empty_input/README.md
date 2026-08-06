# `batch_reference_update/empty_input` -- no maintenance records to apply

## 1. Scenario intent

The empty sequential input. It proves the reader terminates cleanly on
an immediate end-of-file rather than treating an empty file as an error, which is
the behaviour a nightly job with nothing to do depends on.

---

## 2. The exact business rule it exercises

`COBTUPDT` reaching end-of-file on its first `READ` at line 101, before
the `EVALUATE` at line 110 is ever entered. No action byte is examined, so none of
the four accepted values and none of the abend path is reached.

---

## 3. Expected outcome

Zero records processed, no abend, and a clean termination. Nothing is
inserted, updated or deleted.

---

## 4. Fixture bytes and governance

### 4.1 Per-file record width, record count and line ending

| File | Record width | Records | Line ending |
|---|---|---|---|
| `trtype-update.txt` | n/a -- empty | 0 | none -- 0 bytes |

Every file in this directory uses **LF** line endings and carries no carriage return; a CRLF file would add a byte to every record and shift every offset after the first.

### 4.1.1 Derivation, per file

- **`trtype-update.txt`** -- A **genuinely zero-byte file** -- 0
bytes, no carriage return, no line feed -- which is the only correct encoding of an
empty fixed-width input (tree README section 5.2).

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

- Zero bytes is the only encoding of "empty" this tree accepts; a file holding a single blank line is a one-byte file holding one zero-length record and fails record parsing instead of exercising the empty path. ../../README.md section 6.5 records the decision.
- Empty here means a CLEAN end, not a failure, and the distinction is a return code rather than a halt. The `WHEN OTHER` reject at L122-L128 is reached only by a record that exists, so a run with no records cannot reach it and ends at return code 0 - whereas the sibling invalid_type_soft_reject reaches it, displays its message and ends at 4 while still processing the record after the rejected one.

---

## 8. Hazards and choices the second derivation recorded

Assumptions: these bytes were derived TWICE, independently, and both records are kept
because each names hazards the other does not. The section above states why the bytes
are what they are; the paragraphs below state what a later edit of them would break.
Their labels are given unemphasised, as
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md)
requires -- the second derivation wrote them in bold, which that document lists among the
four ways the label has actually been written wrongly.

- Assumptions: the tables above list the fields **this scenario gives a value to**, each with its offset, its `PICTURE` and the copybook or program line that declares it. The complete record layout, including any field this scenario does not vary and the fill regime for each, is tabled once in the tree charter section 3.6 and is cited rather than restated in full. Copying a whole layout table into each scenario directory would create as many places for the geometry to drift as there are scenarios, which the charter forbids at its section 5.8; citing a line per field costs nothing and points a reader at the normative source rather than at a copy of it.
- Alternatives Considered: a file holding one all-blank 53-byte row. Rejected because the tree charter section 6.5 defines "empty" as a zero-byte file and nothing else, and because a blank row is not empty to this program: offset 0 would hold a space, which is none of `'A'`, `'U'`, `'D'` or `'*'`, so it would reach the abend branch at L122 and this scenario would assert a crash under the name of the quiet case.
