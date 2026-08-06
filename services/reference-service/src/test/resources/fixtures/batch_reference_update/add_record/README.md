# `batch_reference_update/add_record` -- two add actions applied in sequence

## 1. Scenario intent

The ordinary batch maintenance case: a sequential input file whose every
record carries a valid action code, applied in file order. It is the control against
which [`../invalid_type_soft_reject`](../invalid_type_soft_reject/README.md) is meaningful.

---

## 2. The exact business rule it exercises

`COBTUPDT` reads `WS-INPUT-REC`, declared at
[`app/app-transaction-type-db2/cbl/COBTUPDT.cbl`](../../../../../../../../app/app-transaction-type-db2/cbl/COBTUPDT.cbl)
lines 71 to 77 as `INPUT-REC-TYPE PIC X(1)` plus `INPUT-REC-NUMBER PIC X(2)` plus
`INPUT-REC-DESC PIC X(50)`, which is **53 bytes** and is a completely different
record from the 60-byte `TRANTYPE` row the online screens maintain. The action byte
is dispatched by the `EVALUATE` at lines 110 to 129, whose four accepted values are
`'A'` add, `'U'` update, `'D'` delete and `'*'` comment-ignored.

---

## 3. Expected outcome

Both records are added. Type `08` and type `09` are inserted with the
descriptions given, the run completes and no abend occurs.

---

## 4. Fixture bytes and governance

### 4.1 Per-file record width, record count and line ending

| File | Record width | Records | Line ending |
|---|---|---|---|
| `trtype-update.txt` | 53 bytes | 2 | one trailing LF |

Every file in this directory uses **LF** line endings and carries no carriage return; a CRLF file would add a byte to every record and shift every offset after the first.

### 4.1.1 Derivation, per file

- **`trtype-update.txt`** -- Both records are **authored**, and they
must be: `app/data/ASCII/` holds no 53-byte action-coded input file, so there is no
seed to derive from and the tree README's blanket derivation statement does not
reach this domain. The action byte is upper-case `'A'` on both rows, the type codes
`08` and `09` are outside the seed's `01` to `07` range so they cannot collide with
an existing row, and the descriptions are ASCII letters and spaces occupying part of
the declared 50-character field.

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

- This is a 53-byte program working-storage record, NOT a 60-byte copybook record. COBTUPDT reads its input straight into WS-INPUT-REC (COBTUPDT.cbl L71-L77), so the record has no FILLER at all and no copybook to cite. Reusing the 60-byte TRAN-TYPE-RECORD layout here is the obvious mistake and would make every row seven bytes too long.
- Both type codes are NEW to the seeded table, 08 and 09. Insert rows that duplicated an existing type would exercise a key collision instead of the insert branch, which is a different scenario.
- Two rows rather than one, because L101 is a sequential READ inside a loop: a single row cannot show that the loop advances and dispatches again rather than stopping after the first record.

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
- Alternatives Considered: reusing a type number the seed already holds, for instance `01`. Rejected because the seed `app/data/ASCII/trantype.txt` holds exactly seven rows, `01` through `07`, so any of them would make the insert collide with an existing key and turn this scenario into a duplicate-key scenario wearing the add path's name. `08` and `09` are absent from the seed, so the insert can only succeed.
- Trade-offs: two rows rather than one. A single row would exercise the branch just as well and would be shorter; two are used so that the file also demonstrates the sequential loop iterating, which a one-row file cannot distinguish from a program that reads once and stops.
