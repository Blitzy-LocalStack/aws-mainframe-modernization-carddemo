# `reference_update/delete_restricted_by_category` -- the foreign key that refuses a delete

## 1. Scenario intent

The delete path that must be **refused**. One type code is referred to
by category rows and one is not, so a consumer can assert both outcomes from one
fixture pair: the referenced code cannot be deleted, the unreferenced code can.

---

## 2. The exact business rule it exercises

The `ON DELETE RESTRICT` foreign key from the category table to the type
table, which preserves the baseline `XTRNTYCAT` semantic declared in the extension
tree's Db2 definitions. In the target this surfaces as **HTTP 409 Conflict** rather
than as a raw database error, because a referential refusal is a statement about the
request and not an internal fault.

---

## 3. Expected outcome

Deleting type `06` is refused with a 409, because category rows refer
to it. Deleting type `99` succeeds, because none do. The refusal is the asserted
value; a consumer that received a 500 for the first case would be failing this
scenario even though a delete had also been prevented.

---

## 4. Fixture bytes and governance

### 4.1 Per-file record width, record count and line ending

| File | Record width | Records | Line ending |
|---|---|---|---|
| `trantype.txt` | 60 bytes | 2 | one trailing LF |

Every file in this directory uses **LF** line endings and carries no carriage return; a CRLF file would add a byte to every record and shift every offset after the first.

### 4.1.1 Derivation, per file

- **`trantype.txt`** -- Row 1 is the padded ASCII seed row
`06`/`Reversal` -- chosen because the seed's category file refers to it, which is
what makes the restrict path reachable without authoring a category row. **Row 2 is
authored**: `99Unreferenced type for delete fixture` padded to 60. It is authored
because **every** type code in the seed is referred to by at least one category
row, so the seed cannot supply an unreferenced code and the permitted-delete half
of the assertion would otherwise be unreachable. `99` is outside the seed's `01`
to `07` range and collides with nothing.

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

- The file holds a MATCHED PAIR, and one row alone would not do. A fixture holding only the referenced type proves that a delete was refused but not that the refusal is selective; a delete path that refused EVERY type would pass it. The unreferenced second row is the control that makes the assertion discriminating.
- Type 06 is the referenced row because the seeded categories carry exactly two rows under it, 060001 and 060002, so the restriction has a real parent-child relationship behind it rather than a contrived one. Type 99 is the unreferenced row because no seeded category names it and none may be added.

---

## 8. Hazards and choices the second derivation recorded

Assumptions: these bytes were derived TWICE, independently, and both records are kept
because each names hazards the other does not. The section above states why the bytes
are what they are; the paragraphs below state what a later edit of them would break.
Their labels are given unemphasised, as
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md)
requires -- the second derivation wrote them in bold, which that document lists among the
four ways the label has actually been written wrongly.

- Assumptions: the tables above list the fields **this scenario gives a value to**, each with its offset, its `PICTURE` and the copybook or program line that declares it. The complete record layout, including any field this scenario does not vary and the fill regime for each, is tabled once in the tree charter section 3.2 and is cited rather than restated in full. Copying a whole layout table into each scenario directory would create as many places for the geometry to drift as there are scenarios, which the charter forbids at its section 5.8; citing a line per field costs nothing and points a reader at the normative source rather than at a copy of it.
- Alternatives Considered: choosing a type with exactly one referencing category rather than two. Rejected because `06` is the only type in the seed with more than one, and a single reference cannot distinguish an implementation that refuses on ANY reference from one that happens to check only the first.
- Assumptions: `99` is deliberately outside the seed's range of `01` to `07` and outside the `08` the add path introduces, so no category can reference it by accident now or after a future fixture edit. Its description says what it is for, which is the only description in this tree that is not seed-derived.
- Trade-offs: the file names the referencing categories in prose above but does not contain them, so it is not self-contained. Carrying a copy of the two category rows here would make it so, at the cost of a second copy of rows that `../happy_path/trancatg.txt` already owns - and two copies of a foreign-key parent is precisely how the two would come to disagree.
