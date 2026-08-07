# `date_conversion/request_payload_ignored` -- the payload is never examined

## 1. Scenario intent

The scenario that pins a **surprising** property of `CODATE01`: it
examines no field of the request at all. The function code here is `DTE ` rather
than `DATE` and the key differs from the control case, and the reply is nonetheless
the same system-date reply. A reader who assumed a malformed function code produces
an error, or that the key selects anything, is corrected by this fixture.

Assumptions: this directory's record is **byte-identical** to the one under the sibling
scenario `invalid_date_rejected`, and the duplication is deliberate rather than an
oversight. Both scenarios need the same well-formed envelope, and they are separate
directories because they assert different things about it: this one asserts that
`CODATE01` produces the control reply from a payload it never examines -- see item 5 --
while the sibling pairs the same envelope with a date the date-edit rules refuse, a
refusal that reaches the rule as a parameter beside the bytes rather than as a field
inside them. Sharing one directory would have forced one name to describe both
properties, and a scenario name is what a future author trusts when choosing where to
add a case. `ReferenceFixtureContractTest` resolves both paths and asserts the equality,
so the pair cannot silently drift apart.

Refactoring Rationale: the rename left the old directory standing beside this one, holding a
README that documented a 1000-byte `date-request.txt` it did not contain -- so the old name still
resolved for a reader, still described an executable fixture, and still pointed at a branch this
program has no path to. The directory has now been removed rather than completed. Adding the
record it described was considered and rejected on two grounds: the bytes would have been
identical to this scenario's, because both READMEs specify the same function code and the same
key, so the tree would carry two names for one fixture; and the name itself is the false claim,
since the refusal it promises is not a branch `CODATE01.cbl` contains. The contract test pins the
rename by asserting THIS name resolves rather than that the old one does not, which is the
direction that cannot pass or fail for reasons of build hygiene.

---

## 2. The exact business rule it exercises

`CODATE01`'s only consumer of the request is
`4000-PROCESS-REQUEST-REPLY` at
[`app/app-vsam-mq/cbl/CODATE01.cbl`](../../../../../../../../app/app-vsam-mq/cbl/CODATE01.cbl)
lines 339 to 364, and it reads **no** field of it. The payload is copied into
`REQUEST-MSG-COPY` at line 322 and then never referenced: `WS-FUNC` and `WS-KEY`
occur in that program at lines 110 and 111 only, which are their declarations. There
is no `IF`, no `EVALUATE` and no `MOVE` anywhere in the program that reads either
field.

---

## 3. Expected outcome

The reply is the **same** system-date reply the control case receives:
`SYSTEM DATE : MM-DD-YYYY` then `SYSTEM TIME : HH:MM:SS`, with both literals
verbatim. No error, no rejection and no distinguishable difference from
`happy_path` other than the request bytes. That indistinguishability is the asserted
value.

---

## 4. Fixture bytes and governance

### 4.1 Per-file record width, record count and line ending

| File | Record width | Records | Line ending |
|---|---|---|---|
| `date-request.txt` | 1000 bytes | 1 | one trailing LF |

Every file in this directory uses **LF** line endings and carries no carriage return; a CRLF file would add a byte to every record and shift every offset after the first.

### 4.1.1 Derivation, per file

- **`date-request.txt`** -- One 1000-byte record: `DTE ` in the
4-character function field -- three letters and a trailing blank, so the field is
filled to its declared width -- key `00000000002` in the next 11, and 985 blanks.
The bytes are **unchanged** from those this directory held under its former name;
only the directory name and this document changed, so the fixture's own history is
not disturbed.

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

### 4.3 Where invalid calendar vectors actually live, and why not here

An invalid **calendar** date cannot be exercised through this request at all,
because no date travels in it -- `CODATE01` takes its date from the system clock.
The date-edit rules that reject an impossible date belong to `CSUTLDTC` and its
copybooks, and in the target they are implemented by
`com.carddemo.common.validation.DateEditValidator` and asserted by
`DateEditValidatorTest` in `common-lib`, which covers the century rule, the
leap-year rule, the date-of-birth rule, invalid month-and-day combinations, the
result envelope and the Language-Environment path across eight nested groups.

Assumptions: no divergence is being recorded here, because nothing diverges. The
baseline has two separate facilities -- a clock-reading reply service and a
date-edit validator -- and the target keeps them separate in the same way. The
earlier scenario name conflated them, which is a documentation error rather than a
behavioural one.

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

- The marker is `DTE ` -- three letters and a trailing space -- rather than something
  obviously foreign. It is the realistic form of the mistake: right length, plausible
  spelling, wrong value. A marker of the wrong LENGTH would be caught by the record
  geometry instead and would test a different thing.
- The key is `00000000002`, a DIFFERENT seed key from `happy_path`'s `00000000001`, so
  a consumer reading both fixtures cannot confuse one request with the other in a
  failure message.
- Neither difference is claimed as a REJECTION basis, and the distinction is the whole
  point of this scenario's name. `WS-FUNC` is declared at line 110 of `CODATE01.cbl`
  and referenced at no other line in the program, so the baseline performs no
  function-code check and would not itself distinguish this request from the control
  one. An earlier revision of this directory was named for a rejection and cited a
  baseline branch that does not exist; a citation a reader cannot follow is worse than
  no citation, because it is indistinguishable from one they simply failed to find.

---

## 8. Hazards and choices the second derivation recorded

Assumptions: these bytes were derived TWICE, independently, and both records are kept
because each names hazards the other does not. The section above states why the bytes
are what they are; the paragraphs below state what a later edit of them would break.
Their labels are given unemphasised, as
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md)
requires -- the second derivation wrote them in bold, which that document lists among the
four ways the label has actually been written wrongly.

- Assumptions: the deviation this fixture carries is in the FUNCTION code, not in a date. The record has no date field at all - it is a function code, an account key and padding - so a scenario in this domain can only deviate in its function code or its key.
- Refactoring Rationale: this bullet previously ended by stating that "the directory name describes the outcome the migrated endpoint produces, which is a rejected date-conversion request." That sentence survived the rename recorded in section 1 and contradicted both this directory's name and its own section 3, which state that the reply is indistinguishable from `happy_path`. It is corrected rather than left standing: the migrated consumer, `services/reference-service/src/main/java/com/carddemo/reference/service/DateInquiryMessageListener.java`, now exists and applies **no** guard, exactly as `CODATE01` applies none, and `DateInquiryMessageListenerTest` asserts that these bytes and `happy_path`'s produce byte-identical replies. A sentence describing a rejection that neither the baseline nor the target performs would have sent a reader looking for a branch that does not exist.
- Assumptions: the tables above list the fields **this scenario gives a value to**, each with its offset, its `PICTURE` and the copybook or program line that declares it. The complete record layout, including any field this scenario does not vary and the fill regime for each, is tabled once in the tree charter section 3.7 and is cited rather than restated in full. Copying a whole layout table into each scenario directory would create as many places for the geometry to drift as there are scenarios, which the charter forbids at its section 5.8; citing a line per field costs nothing and points a reader at the normative source rather than at a copy of it.
- Alternatives Considered: making the request invalid by its KEY instead - an account number absent from the seed, or a non-numeric one. Rejected because the key is `PIC 9(11)` and the accepted sibling already fixes its shape, so a key deviation would exercise a lookup miss rather than a request-validation refusal, which is a different scenario and would need its own name.
- Trade-offs: the two scenarios differ in their keys as well as their function codes, `00000000002` against `00000000001`. Holding the key constant would isolate the variable more tightly; distinct keys are used instead so that a consumer reading both files can tell which one it has from the key alone, which is worth more here than the isolation, since neither field affects the other's validation.
