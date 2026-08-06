# `reference_list/happy_path` -- the populated transaction-type list

## 1. Scenario intent

The ordinary case for the transaction-type list screen: a populated
`TRANTYPE` dataset from which every row is listed in key order. This is the
scenario a consumer asserts the *contents* of a page against.

---

## 2. The exact business rule it exercises

`COTRTLIC` lists the transaction types. Its record is
`TRAN-TYPE-RECORD` at
[`app/cpy/CVTRA03Y.cpy`](../../../../../../../../app/cpy/CVTRA03Y.cpy) lines 4 to 7 --
a 2-character type code followed by a 50-character description, padded to the
declared 60-byte record length. Ordering is by the type code, which is the base
cluster key, so the seven rows list as `01` through `07` with no sort step
anywhere.

---

## 3. Expected outcome

Seven rows list, in ascending type-code order, beginning
`01`/`Purchase` and ending `07`/`Authorization`. No row is rejected and no error is
raised. A consumer asserting a page of ten receives all seven and a
further-page indicator of false, because seven is fewer than ten.

---

## 4. Fixture bytes and governance

### 4.1 Per-file record width, record count and line ending

| File | Record width | Records | Line ending |
|---|---|---|---|
| `trantype.txt` | 60 bytes | 7 | one trailing LF |

Every file in this directory uses **LF** line endings and carries no carriage return; a CRLF file would add a byte to every record and shift every offset after the first.

### 4.1.1 Derivation, per file

- **`trantype.txt`** -- All seven rows are the ASCII seed rows of
[`app/data/ASCII/trantype.txt`](../../../../../../../../app/data/ASCII/trantype.txt),
right-padded from the seed's mixed 60-and-61-character rows to a uniform 60. That
normalisation is the only change: **zero rows are authored.**

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

- Seven rows is not an arbitrary sample. COTRTLIC declares WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7 at app/app-transaction-type-db2/cbl/COTRTLIC.cbl L60, and every row table in that program is OCCURS 7 TIMES. A fixture of exactly seven rows is therefore one FULL page with no page after it, which is the only row count that pins both halves of the paging contract at once.
- The bytes are the seed rows, not invented ones, so the fixture cannot drift away from the data the seeded schema really holds.

---

## 8. Hazards and choices the second derivation recorded

Assumptions: these bytes were derived TWICE, independently, and both records are kept
because each names hazards the other does not. The section above states why the bytes
are what they are; the paragraphs below state what a later edit of them would break.
Their labels are given unemphasised, as
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md)
requires -- the second derivation wrote them in bold, which that document lists among the
four ways the label has actually been written wrongly.

- Assumptions: the seed `app/data/ASCII/trantype.txt` carries **CRLF** on its first six rows and LF on its last. This fixture is LF throughout, per the charter's derive-to-LF rule, so it is not a byte copy of the seed and the difference is exactly one byte per row on six of the seven rows.
- Assumptions: the tables above list the fields **this scenario gives a value to**, each with its offset, its `PICTURE` and the copybook or program line that declares it. The complete record layout, including any field this scenario does not vary and the fill regime for each, is tabled once in the tree charter section 3.2 and is cited rather than restated in full. Copying a whole layout table into each scenario directory would create as many places for the geometry to drift as there are scenarios, which the charter forbids at its section 5.8; citing a line per field costs nothing and points a reader at the normative source rather than at a copy of it.
- Alternatives Considered: synthesising seven plausible type rows instead of deriving the seed's. Rejected because the descriptions are asserted verbatim downstream and a synthesised description would diverge from the seeded reference data the service loads, making a list assertion pass here and fail against a real schema.
- Trade-offs: the eight filler bytes are `'0'` rather than spaces, which looks like a mistake beside the space-padded description. They are copied from the seed unchanged, because the charter's `FILLER` regime for the three seed-derived datasets is copy-verbatim; normalising them to spaces would make the fixture tidier and no longer the seed's bytes.
