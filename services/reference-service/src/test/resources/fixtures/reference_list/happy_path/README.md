# `reference_list/happy_path` -- the populated transaction-type list

## 1. Scenario intent

The ordinary case for the transaction-type list screen: a populated
`TRANTYPE` dataset from which every row is listed in key order, together with the
populated `TRANCATG` dataset whose rows hang off it. This is the
scenario a consumer asserts the *contents* of a page against.

Assumptions: the category dataset is part of the scenario rather than an extra.
A type list screen reaches its categories through the two-character type code,
so a list scenario carrying types alone could show that a page of types is
produced and could not show that any of them resolves to a category. Both files
are therefore governed here and both are read by the consumers named in section 6.

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
`01`/`Purchase` and ending `07`/`Adjustment`. No row is rejected and no error is
raised. A consumer asserting a page of ten receives all seven and a
further-page indicator of false, because seven is fewer than ten.

Refactoring Rationale: the last row is `07`/`Adjustment`, and an earlier revision of
this paragraph named `07`/`Authorization`. `Authorization` is the description of
`04`, so the sentence attributed one row's description to another; the bytes in
`trantype.txt` were always right and only this sentence was wrong.

The eighteen category rows resolve against those seven types with none left over:
every category names a parent in `01` through `07`, and the eighteen composite keys
`type||category` ascend with no duplicate.

---

## 4. Fixture bytes and governance

### 4.1 Per-file record width, record count and line ending

| File | Record width | Records | Line ending |
|---|---|---|---|
| `trantype.txt` | 60 bytes | 7 | one trailing LF |
| `trancatg.txt` | 60 bytes | 18 | one trailing LF |

So `wc -c` is 427 for the type file and 1098 for the category file, each following the
tree charter's acceptance arithmetic `bytes = rows x RECLN + rows`.

Every file in this directory uses **LF** line endings and carries no carriage return; a CRLF file would add a byte to every record and shift every offset after the first.

**Both files are populated, not one, and the reason is referential rather than
procedural** -- the same reason the sibling `empty_input` scenario zeroes both. The
list program browses only the transaction-type table, but a category row names the
type it belongs to and the baseline declares that reference with `ON DELETE RESTRICT`,
so a category can never name a type that is not there. A scenario carrying the seven
seeded types without the eighteen category rows that reference them would describe
half of a relationship the schema states in full. It would also leave this scenario
unable to serve as the referentially complete starting state that the
`reference_update` domain's restrict scenario narrows a two-row subset out of.

### 4.1.1 Derivation, per file

- **`trantype.txt`** -- All seven rows are the ASCII seed rows of
[`app/data/ASCII/trantype.txt`](../../../../../../../../app/data/ASCII/trantype.txt),
right-padded from the seed's mixed 60-and-61-character rows to a uniform 60. That
normalisation is the only change: **zero rows are authored.**
- **`trancatg.txt`** -- All eighteen rows are the ASCII seed rows of
[`app/data/ASCII/trancatg.txt`](../../../../../../../../app/data/ASCII/trancatg.txt),
converted from that seed's CRLF endings to LF per the charter's derive-to-LF rule. That
conversion is the only change: **zero rows are authored** and the four-character `FILLER`
stays `'0'` exactly as the seed writes it. The file is consequently **byte-identical** to
`../../reference_update/happy_path/trancatg.txt`, which is the same derivation of the same
seed. Assumptions: the duplication is deliberate. Every scenario in this tree carries the
inputs its own state needs, so a consumer loads one directory rather than assembling a
state out of two, and the referential reason above is why this scenario's state needs both
files. Trade-offs: the cost is a second copy of eighteen rows, and two copies of the same
rows are how the two come to disagree; the guard accepted in exchange is that
`ReferenceFixtureContractTest` asserts the two copies are byte-equal instead of reading
each in isolation, so an edit to one alone fails rather than passing twice.

---

## 5. Failure modes these bytes can produce

Assumptions: the failure modes below are the ones a consumer of *these* bytes can hit, which is the Rule 1 exceptions element applied to a fixture rather than to a function.

- A record read at the wrong declared width mis-aligns every field after the first, and the symptom is a plausible-looking wrong value rather than an exception, which is why section 4.1 states the width per file.
- A file rewritten by an editor that appends a final newline to an intentionally zero-byte fixture turns it into a 1-byte file holding one zero-length record, which fails fixed-width parsing instead of exercising the empty path.
- A file rewritten with CRLF endings shifts every offset and every record boundary.

---

## 6. Consumer

There are two consumers, and both read **both** files in this directory.

- `services/reference-service/src/test/java/com/carddemo/reference/fixtures/ReferenceFixtureContractTest.java` enrols each file in a closed inventory that asserts its width, record count and line ending, decodes the seven types in key order, and asserts the eighteen categories are ordered, free of duplicate keys, referentially closed over the seven types, and byte-identical to the update scenario's copy.
- `services/reference-service/src/test/java/com/carddemo/reference/fixtures/ReferenceFixtureTest.java` enrols each file in its own geometry and keyed-fixture inventories, so a duplicate key or a changed record count fails there as well.

Refactoring Rationale: an earlier revision of this section named one consumer and said it "loads every file in this tree", which was true of the type file and not of the category file -- that file appeared in no inventory and was read by nothing. Both are now enrolled, and this section names what each consumer actually asserts rather than describing a directory-wide sweep.

**The bytes are the contract**: a consumer's expected value is whatever these bytes decode to, and a disagreement is resolved by reading the bytes rather than by editing them.

---

## 7. Why these bytes and not others

Assumptions: the choices below are properties of the FIXTURE rather than of the
rule it exercises, so they are recorded here rather than in section 2. Each one is a
decision a later author could reverse without any test failing, which is why the
reason is written down beside it.

- Seven rows is not an arbitrary sample. COTRTLIC declares WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7 at app/app-transaction-type-db2/cbl/COTRTLIC.cbl L60, and every row table in that program is OCCURS 7 TIMES. A fixture of exactly seven rows is therefore one FULL page with no page after it, which is the only row count that pins both halves of the paging contract at once.
- The bytes are the seed rows, not invented ones, so the fixture cannot drift away from the data the seeded schema really holds.
- Eighteen category rows is the whole seeded extract rather than a sample, and the whole extract is what makes referential closure assertable: a sample could omit the only category of some type and the list would still look coherent. Eighteen is also what `app/data/ASCII/trancatg.txt` holds, so the count is read rather than chosen.
- The category file is the same bytes as the update scenario's copy rather than an independent derivation of the same seed. Alternatives Considered: deriving it again here. Rejected because two derivations of one extract can differ in a way no test would notice unless something compared them, so one extract shared by two scenarios plus an equality assertion is strictly stronger than two derivations plus none.

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
