# `disclosure_group/happy_path` -- a rate found on the exact key

## 1. Scenario intent

The direct hit: the disclosure-group row whose key the accrual program
composes is present, so the rate is read from it and no fallback occurs. It is the
control case against which
[`../default_fallback`](../default_fallback/README.md) is meaningful.

---

## 2. The exact business rule it exercises

The rate lookup of `CBACT04C`. The record is `DIS-GROUP-RECORD` at
[`app/cpy/CVTRA02Y.cpy`](../../../../../../../../app/cpy/CVTRA02Y.cpy) lines 4 to 10:
a 10-character group classifier, a 2-character type code, a 4-digit category code
and the interest rate `PIC S9(04)V99`, padded to the declared 50-byte length. The
program composes the key from the account's group, the transaction type and the
category, and reads it directly.

---

## 3. Expected outcome

One row, on key `A000000000` + `01` + `0001`, carrying rate span
`0150{`. The lookup succeeds on the composed key, so the `DEFAULT` group is never
consulted and no VSAM status 23 arises.

---

## 4. Fixture bytes and governance

### 4.1 Per-file record width, record count and line ending

| File | Record width | Records | Line ending |
|---|---|---|---|
| `discgrp.txt` | 50 bytes | 1 | one trailing LF |

Every file in this directory uses **LF** line endings and carries no carriage return; a CRLF file would add a byte to every record and shift every offset after the first.

### 4.1.1 Derivation, per file

- **`discgrp.txt`** -- The row is a verbatim ASCII seed row of
[`app/data/ASCII/discgrp.txt`](../../../../../../../../app/data/ASCII/discgrp.txt).
**Zero rows are authored**, and no padding normalisation was needed either: the seed
rows are already 50 characters. The trailing `{` of the rate span is a positive
sign overpunch, not a brace character -- see tree README section 3.

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

- ONE row, and one row on purpose. This scenario asserts a DIRECT hit, so the fixture must contain the key being looked up and nothing that could satisfy the lookup a second way. A file that also carried the DEFAULT group would leave a passing assertion unable to say which of the two rows answered.
- The rate is 00150{ rather than a round zero. A zero rate multiplies to zero interest whatever else is wrong, so it cannot distinguish a correctly decoded rate from a silently mis-decoded one; 15.00 yields an exact, checkable 12.50 on a 1000.00 balance.
- The trailing '{' is an overpunched sign byte, NOT a stray character. ../../README.md section 5.4 gives the two overpunch tables; '{' is positive zero, so the field's low-order digit is 0 and the value is positive.

---

## 8. Hazards and choices the second derivation recorded

Assumptions: these bytes were derived TWICE, independently, and both records are kept
because each names hazards the other does not. The section above states why the bytes
are what they are; the paragraphs below state what a later edit of them would break.
Their labels are given unemphasised, as
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md)
requires -- the second derivation wrote them in bold, which that document lists among the
four ways the label has actually been written wrongly.

- Assumptions: the tables above list the fields **this scenario gives a value to**, each with its offset, its `PICTURE` and the copybook or program line that declares it. The complete record layout, including any field this scenario does not vary and the fill regime for each, is tabled once in the tree charter section 3.4 and is cited rather than restated in full. Copying a whole layout table into each scenario directory would create as many places for the geometry to drift as there are scenarios, which the charter forbids at its section 5.8; citing a line per field costs nothing and points a reader at the normative source rather than at a copy of it.
- Assumptions: the group id is `A000000000` and not blank. The seed's own account rows carry a blank group id, which is what makes the fallback the seed's natural path; setting a group that this fixture actually contains is what forces the direct hit instead.
- Trade-offs: one row rather than the seed's full seventeen per group. A single row cannot demonstrate a lookup choosing between candidates, which is why the fallback scenario carries thirty-four; it is used here because a direct hit is proved by the row being found, and every additional row would be inert.
