# `reference_update/happy_path` -- add, edit and the populated category set

## 1. Scenario intent

The successful maintenance paths of the transaction-type screen: a type
that can be added because its code is free, alongside the full category set the
foreign key refers to. It is the counterpart to
[`../delete_restricted_by_category`](../delete_restricted_by_category/README.md),
which holds the path that must fail.

---

## 2. The exact business rule it exercises

`COTRTUPC` add and edit against `TRAN-TYPE-RECORD`
([`app/cpy/CVTRA03Y.cpy`](../../../../../../../../app/cpy/CVTRA03Y.cpy) lines 4 to 7)
and `TRAN-CAT-RECORD`
([`app/cpy/CVTRA04Y.cpy`](../../../../../../../../app/cpy/CVTRA04Y.cpy) lines 4 to 9).
The category record's key is the type code followed by the 4-digit category code,
which is why the category rows here all begin with a type code that exists in the
type file.

---

## 3. Expected outcome

The type file lists eight rows. Type `08` is present and is the row a
consumer uses to assert that an added code is readable back at its declared width;
types `01` to `07` are unchanged. The category file lists eighteen rows, every one
of whose leading type code resolves to a row in the type file, so no referential
check fails.

---

## 4. Fixture bytes and governance

### 4.1 Per-file record width, record count and line ending

| File | Record width | Records | Line ending |
|---|---|---|---|
| `trantype.txt` | 60 bytes | 8 | one trailing LF |
| `trancatg.txt` | 60 bytes | 18 | one trailing LF |

Every file in this directory uses **LF** line endings and carries no carriage return; a CRLF file would add a byte to every record and shift every offset after the first.

### 4.1.1 Derivation, per file

- **`trantype.txt`** -- Seven rows are the padded ASCII seed rows.
**One row is authored** and is identified here as section 8 requires:
`08Fixture Add Path Type` padded to 60. It is authored because the seed's highest
type code is `07`, so no free code exists in the seed to demonstrate an add
against; `08` is the next code and it collides with nothing. Its description is
ASCII letters and spaces only, so it exercises the 50-character description width
without introducing any character class the copybook does not admit.

- **`trancatg.txt`** -- All eighteen rows are padded ASCII seed rows
of
[`app/data/ASCII/trancatg.txt`](../../../../../../../../app/data/ASCII/trancatg.txt).
**Zero rows are authored.** The seed rows are 61 characters and are normalised to
the declared 60.

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

- The type file holds EIGHT rows, one more than COTRTLIC's seven-row page (COTRTLIC.cbl L60). That is deliberate: eight rows make the added row land on a SECOND page, so the fixture proves the add is reachable through paging and not only through a single-page read.
- Type 08 is the added row and NO category references it. That is what makes it the type this scenario may also delete: the restrict rule in the sibling delete_restricted_by_category scenario refuses a type a category still references, so a success-path delete needs a type that nothing references.
- The eighteen category rows are the seed's own eighteen pairs, which is one more pair than discgrp carries. ../../README.md section 6.4 records that asymmetry as an observed baseline property that fixtures depend on, not as a gap to fill in.

---

## 8. Hazards and choices the second derivation recorded

Assumptions: these bytes were derived TWICE, independently, and both records are kept
because each names hazards the other does not. The section above states why the bytes
are what they are; the paragraphs below state what a later edit of them would break.
Their labels are given unemphasised, as
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md)
requires -- the second derivation wrote them in bold, which that document lists among the
four ways the label has actually been written wrongly.

- Assumptions: `TRAN-CAT-CD` is unsigned display with **no** overpunch, so `0001` is four plain ASCII digits and its leading zeros are part of the value. Its target column is `CHAR(4)` rather than an integer type for exactly that reason.
- Assumptions: the tables above list the fields **this scenario gives a value to**, each with its offset, its `PICTURE` and the copybook or program line that declares it. The complete record layout, including any field this scenario does not vary and the fill regime for each, is tabled once in the tree charter section 3.3 and is cited rather than restated in full. Copying a whole layout table into each scenario directory would create as many places for the geometry to drift as there are scenarios, which the charter forbids at its section 5.8; citing a line per field costs nothing and points a reader at the normative source rather than at a copy of it.
- Alternatives Considered: holding only the seven seeded types, matching the seed exactly. Rejected because the add path's result would then be unrepresentable in this tree: no fixture would show a type that the seed does not have, and the post-add state could only be described in prose.
- Assumptions: the category table holds eighteen pairs while the rate table holds seventeen per group, and the asymmetry is an observed property of the baseline data rather than a gap. Pair `01|0005` - the interest program's own generated category - is priced by no disclosure group, which is the tree's only lookup miss on both the account's group and `DEFAULT`. Adding the missing rate row would make the two tables symmetric and delete that negative path.
