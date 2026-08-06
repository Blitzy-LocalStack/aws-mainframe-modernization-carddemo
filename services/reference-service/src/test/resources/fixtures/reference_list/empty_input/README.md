# `reference_list/empty_input` -- both reference datasets empty

## 1. Scenario intent

The empty-set case for the list screens. It exists to prove that an
empty dataset produces an empty page rather than an error, and it carries **both**
reference files because the list screen reads types and categories through the same
code path and either one being non-empty would mask the other's behaviour.

---

## 2. The exact business rule it exercises

`COTRTLIC` reading a dataset positioned at end-of-file on its first
read. There is no reject reason and no abend for an empty reference dataset: the
browse simply returns nothing, so the correct target behaviour is an empty page,
not a 404 and not an exception.

---

## 3. Expected outcome

Zero rows. A consumer receives an empty item list, a
further-page indicator of false, and no error. Nothing is written and nothing is
rejected.

---

## 4. Fixture bytes and governance

### 4.1 Per-file record width, record count and line ending

| File | Record width | Records | Line ending |
|---|---|---|---|
| `trantype.txt` | n/a -- empty | 0 | none -- 0 bytes |
| `trancatg.txt` | n/a -- empty | 0 | none -- 0 bytes |

Every file in this directory uses **LF** line endings and carries no carriage return; a CRLF file would add a byte to every record and shift every offset after the first.

### 4.1.1 Derivation, per file

- **`trantype.txt`** -- A **genuinely zero-byte file**: 0 bytes,
0 carriage returns, 0 line feeds. Tree README section 5.2 is explicit that this is
the only correct encoding -- a file holding a single blank line is a 1-byte file
carrying one zero-length record, which fails fixed-width parsing instead of
exercising the empty path.

- **`trancatg.txt`** -- The same, and for the same reason. Both
files are zero bytes so that neither dataset can supply rows the other's absence
was meant to demonstrate.

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

- Both files are zero bytes, and that is the ONLY encoding of "empty" this tree accepts. A file holding a single blank line reads as empty to a person and is a one-byte file holding one zero-length record to a length-checking loader, so it fails record parsing instead of exercising the empty path. ../../README.md section 6.5 records the decision; section 5.2 states the length contract it rests on.
- BOTH tables are emptied, not just the type table. The list screen reads types and categories together, so leaving categories populated would test a half-empty read and leave the empty-page assertion ambiguous about which read produced it.

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
- Assumptions: both files are present and both are empty, rather than the directory simply omitting them. A missing file and an empty file are different conditions to a consumer - one is a class-path resolution failure, the other is data - and the charter's definition of empty is a committed zero-byte file.
- Alternatives Considered: emptying only the category table and leaving the seven types in place. Rejected because that is a partially-populated list, which would exercise the join between the two tables rather than the empty case, and would deserve its own name if it were wanted.
