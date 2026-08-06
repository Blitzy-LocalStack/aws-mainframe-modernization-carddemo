# `date_conversion/happy_path` -- a well-formed request receives the system date

## 1. Scenario intent

The ordinary request to the date-conversion service: a request whose
function code and key are both populated in the shape the layout declares. It is the
control case for
[`../request_payload_ignored`](../request_payload_ignored/README.md).

---

## 2. The exact business rule it exercises

`CODATE01`'s single request handler,
`4000-PROCESS-REQUEST-REPLY` at
[`app/app-vsam-mq/cbl/CODATE01.cbl`](../../../../../../../../app/app-vsam-mq/cbl/CODATE01.cbl)
lines 339 to 364. It clears the reply, issues `EXEC CICS ASKTIME`, formats the
result with `EXEC CICS FORMATTIME` using `MMDDYYYY` and `DATESEP('-')`, and
`STRING`s the literals `'SYSTEM DATE : '` and `'SYSTEM TIME : '` around the two
formatted values into the reply.

---

## 3. Expected outcome

A reply of the exact form
`SYSTEM DATE : MM-DD-YYYY` followed by `SYSTEM TIME : HH:MM:SS`, with the two
literals verbatim. The date and time come from the **system clock**, so a consumer
must assert the reply's *shape* and its two literals and must not assert a
particular date -- an asserted date would fail on every day but one.

---

## 4. Fixture bytes and governance

### 4.1 Per-file record width, record count and line ending

| File | Record width | Records | Line ending |
|---|---|---|---|
| `date-request.txt` | 1000 bytes | 1 | one trailing LF |

Every file in this directory uses **LF** line endings and carries no carriage return; a CRLF file would add a byte to every record and shift every offset after the first.

### 4.1.1 Derivation, per file

- **`date-request.txt`** -- One 1000-byte record: function code
`DATE` in the 4 characters the layout declares, key `00000000001` in the following
11, and the remaining 985 characters blank. The width comes from
`REQUEST-MESSAGE PIC X(1000)` at line 106 and the field breakdown from
`REQUEST-MSG-COPY` at lines 109 to 112. The single trailing line feed is the record
separator and is not part of the 1000 bytes.

### 4.2 Provenance and synthetic-data attestation

Section 8 of the tree README makes this attestation **required** for the
`date_conversion` domain alone, because `WS-KEY PIC 9(11)` is an eleven-digit
`ACCTDAT`-shaped key and is therefore identity-shaped. The other four domains carry
no identity-shaped field and their scenario READMEs deliberately omit it.

1. **Where the bytes come from.** There is **no ASCII seed dataset for a
   1000-byte MQ request** -- `app/data/ASCII/` holds nine files and none of them is
   a request payload -- so these bytes are authored against the
   `REQUEST-MSG-COPY` layout declared at
   [`app/app-vsam-mq/cbl/CODATE01.cbl`](../../../../../../../../app/app-vsam-mq/cbl/CODATE01.cbl)
   lines 109 to 112. They are synthetic in the strict sense, and that is stated
   plainly rather than described as derived.
2. **No real person and no real account.** The eleven-digit key is a demonstration
   value in the range the published CardDemo seed uses. It is not a credential, it
   identifies no real person and no real account, and no name, address or card
   number appears anywhere in this file.
3. **Why an identity-shaped field is present at all.** The layout declares the
   field, so a fixture that omitted it would not be a request. Its value is chosen
   low and unremarkable precisely so that no reader mistakes it for production data.

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

- The record is 1000 bytes because CODATE01 moves a fixed 1000-byte buffer into REQUEST-MSG-COPY (CODATE01.cbl L109-L112, L322) and sets MQ-BUFFER-LENGTH to 1000 at L291. 985 of those bytes are FILLER, so a fixture that omitted the padding would be a wrong-length request rather than a shorter one.
- The FILLER is SPACES, not ASCII '0'. WS-FILLER is declared PIC X(985) VALUE SPACES at L112, which is the opposite of the three 60-and-50-byte reference records in this tree, whose FILLER carries '0' digits. ../../README.md section 5.3 sets out the two opposite FILLER regimes; this record is governed by the VALUE clause.
- WS-KEY is left-padded with '0' and unsigned. It is 11 digits and identity-shaped, which is what makes this the one domain in the tree that carries the section 8.6 attestation -- see section 4.2 above.

---

## 8. Hazards and choices the second derivation recorded

Assumptions: these bytes were derived TWICE, independently, and both records are kept
because each names hazards the other does not. The section above states why the bytes
are what they are; the paragraphs below state what a later edit of them would break.
Their labels are given unemphasised, as
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md)
requires -- the second derivation wrote them in bold, which that document lists among the
four ways the label has actually been written wrongly.

- Assumptions: the baseline program never INSPECTS either field. `WS-FUNC` and `WS-KEY` each appear exactly once in all 524 lines - at their declarations, L110 and L111 - so the reference program replies with the system date and time to any message it receives. The validation this domain exercises belongs to the MIGRATED endpoint, which composes this request shape with the date-edit rules of `app/cbl/CSUTLDTC.cbl` per the plan's reference-service mapping. This is stated because it is the one fact about this domain a reader cannot re-derive without reading the whole program, and getting it wrong would mean expecting a rejection the baseline never issues.
- Assumptions: the filler is space-filled, not zero-filled, because `CODATE01.cbl` L112 declares `VALUE SPACES`. The three seed-derived datasets in this tree carry ASCII `'0'` padding copied from their seeds; this record does not, and a fixture that zeroed it would still be exactly 1000 bytes.
- Assumptions: the tables above list the fields **this scenario gives a value to**, each with its offset, its `PICTURE` and the copybook or program line that declares it. The complete record layout, including any field this scenario does not vary and the fill regime for each, is tabled once in the tree charter section 3.7 and is cited rather than restated in full. Copying a whole layout table into each scenario directory would create as many places for the geometry to drift as there are scenarios, which the charter forbids at its section 5.8; citing a line per field costs nothing and points a reader at the normative source rather than at a copy of it.
