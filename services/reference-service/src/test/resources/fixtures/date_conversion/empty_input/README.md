# `date_conversion/empty_input` -- no request to service

## 1. Scenario intent

The empty request queue. It proves the request loop terminates on an
immediate end-of-input rather than replying to a record that is not there, and it is
the third of the three states a 1000-byte request file can be in: well-formed,
present-but-unexamined, and absent.

---

## 2. The exact business rule it exercises

`CODATE01`'s request loop. `3000-GET-REQUEST` is performed once at line
163 and `4000-MAIN-PROCESS` then iterates at line 164 until the loop condition is
satisfied; with no request available the handler at lines 339 to 364 is never
entered, so no `ASKTIME` is issued and no reply is built.

---

## 3. Expected outcome

Zero requests read and zero replies produced. The loop terminates
cleanly through `8000-TERMINATION` at line 167 and nothing is put to the reply
queue.

---

## 4. Fixture bytes and governance

### 4.1 Per-file record width, record count and line ending

| File | Record width | Records | Line ending |
|---|---|---|---|
| `date-request.txt` | n/a -- empty | 0 | none -- 0 bytes |

Every file in this directory uses **LF** line endings and carries no carriage return; a CRLF file would add a byte to every record and shift every offset after the first.

### 4.1.1 Derivation, per file

- **`date-request.txt`** -- A **genuinely zero-byte file**: 0 bytes,
0 carriage returns, 0 line feeds. Tree README section 5.2 names this file
specifically as the correct form, and it is the one fixture in this tree that
pre-dates the others. It is left byte-for-byte as it was.

### 4.2 Provenance and synthetic-data attestation

Section 8 of the tree README makes this attestation **required** for the
`date_conversion` domain, because the request layout declares an identity-shaped
`WS-KEY PIC 9(11)`. The obligation is discharged here rather than skipped, and the
answer is short for a reason a reader should not have to infer.

1. **Where the bytes come from.** There are **no bytes**. The file is zero-length,
   so nothing was derived from a seed and nothing was authored.
2. **No real person and no real account.** The file contains no field at all, so it
   carries no key, no name, no account number and no card number. The
   identity-shaped field the layout declares is not present in this file because no
   record is present in it.
3. **Why the attestation still appears.** The requirement attaches to the domain,
   not to the file, and stating "not applicable" without saying why would be
   indistinguishable from having overlooked it.

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

- Zero bytes is the only encoding of "empty" this tree accepts, and ../../README.md section 6.5 names THIS file as the correct form of it. A file holding a single blank line is a one-byte file holding one zero-length record and fails record parsing instead of exercising the empty path.
- This is the ONE fixture in the tree that predates the others; it was the first record file present under this directory. Its scenario README is this file, which discharges the section 8.4 obligation that section 10 previously recorded as outstanding for it.

---

## 8. Hazards and choices the second derivation recorded

Assumptions: these bytes were derived TWICE, independently, and both records are kept
because each names hazards the other does not. The section above states why the bytes
are what they are; the paragraphs below state what a later edit of them would break.
Their labels are given unemphasised, as
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md)
requires -- the second derivation wrote them in bold, which that document lists among the
four ways the label has actually been written wrongly.

- Assumptions: the tables above list the fields **this scenario gives a value to**, each with its offset, its `PICTURE` and the copybook or program line that declares it. The complete record layout, including any field this scenario does not vary and the fill regime for each, is tabled once in the tree charter section 3.7 and is cited rather than restated in full. Copying a whole layout table into each scenario directory would create as many places for the geometry to drift as there are scenarios, which the charter forbids at its section 5.8; citing a line per field costs nothing and points a reader at the normative source rather than at a copy of it.
- Assumptions: no attestation applies to this scenario even though its domain requires one, because the file contains no bytes and therefore no key. The obligation attaches to identity-shaped CONTENT, and there is none here. This is stated rather than left to silence, since an absent attestation is otherwise indistinguishable from an overlooked one.
- Alternatives Considered: a 1000-byte all-space record. Rejected for the reason the charter gives at its section 6.5 - empty means zero bytes - and because an all-space record IS a message: it would be moved into `REQUEST-MSG-COPY` and answered, which is the well-formed path with a blank function code, not the absence of a request.
