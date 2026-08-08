# Authorization test fixtures

Every file in this directory is consumed by
[`AuthorizationFixtureContractTest`](../../java/com/carddemo/authorization/fixtures/AuthorizationFixtureContractTest.java).
That test loads each resource by name and asserts its width, its fields, its final record and its
failure path, so a byte in this directory cannot change while the suite stays green.

Assumptions: this inventory is written per file rather than per family, because two files of one
family here carry deliberately different contracts — the two request payloads differ by one
character in one field, and that character is the difference between a correct amount and one
wrong by a factor of ten in the cents. A family-level description could not state that.

Assumptions: every value in every fixture is synthetic. No file here is copied from, derived from
or reduced from production data. The account identifiers are in the `10000000001` range the
reference seed extracts do not use, the primary account number `4000123456789010` is a
test-range number, and the customer identifier, merchant identity and postal codes are invented.
No card verification value appears in any fixture, in any field, because the segment these
fixtures describe declares none.

---

## 1. The request wire, and why two widths exist

The authorization request is a comma-delimited payload of **eighteen** fields. Its money field has
**two** observable widths, and both are represented here because they are not interchangeable.

| Source | Money width | Payload width | Role |
|---|---|---|---|
| `app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy` line 27, `PIC +9(10).99` | 14 | 170 | the published contract, emitted by `CsvAuthCodec.encodeRequest` |
| `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl` line 63, `WS-TRANSACTION-AMT-AN PIC X(13)` | 13 | 169 | the reference consumer's own intermediate, accepted on decode only |

Assumptions: the contract is the copybook's fourteen and one hundred seventy, because a copybook
field's picture is normative for this wire. The reference consumer's `UNSTRING` at
`COPAUA0C.cbl` line 354 copies the ordinal-nine token into a thirteen-character receiver before
`FUNCTION NUMVAL` converts it, so it keeps thirteen of the fourteen characters it is sent -- a
property of one consumer's working storage rather than of the payload, registered as a
reference-side divergence in `docs/architecture/cobol-to-service-traceability.md`.

Trade-offs: `CsvAuthCodec` emits fourteen and accepts both widths. Refusing the narrower form on
decode would reject a payload a producer built against that intermediate would send, which is why
the narrower vector below is named `decode-only`.

| File | Bytes | Records | Contract |
|---|---|---|---|
| `auth-request-canonical-wire170.csv` | 171 | 1 | The **canonical** request wire: 170 characters, eighteen fields, the 14-character money token `+0000000250.00`. This is byte-for-byte what `CsvAuthCodec.encodeRequest` emits for that amount, so it is the encode oracle. |
| `auth-request-receiver-wire169-decode-only.csv` | 170 | 1 | The reference receiver's narrower form: 169 characters with the 13-character token `0000000250.00`. `decodeRequest` accepts it and yields the same 250.00; `encodeRequest` re-emits it at the declared width. It differs from the canonical vector in the money token and in the transaction identifier -- the identifier because every committed row is deliberately distinguishable by it -- so the pair is an A/B on the money width across the other seventeen positions. |
| `auth-request-short-numeric-display-oracle-170.bin` | 170 | 1 | The canonical row with the two NUMERIC-DISPLAY fields carrying values SHORTER than their declared widths, left-zero filled as a `PIC 9(n)` field is when a shorter value is moved into it -- `000001` in `PA-RQ-PROCESSING-CODE` (`PIC 9(06)`, `cpy/CCPAURQY.cpy` L26) and `01` in `PA-RQ-POS-ENTRY-MODE` (`PIC 9(02)`, L30). It is the encode oracle for the one crossing where the payload's published constraints admit a short digit string and the wire cannot: the schema declares `^[0-9]{1,6}$` and `^[0-9]{1,2}$`, while the encoder pads every field on the RIGHT with blanks because that is correct for the sixteen `PIC X` fields beside these two. Compared byte for byte, so a value emitted as `1` followed by five blanks cannot pass. |
| `auth-request-amount-variants.csv` | 513 | 3 | Three money boundaries at the canonical 14-character width: the negative form `-0000000250.00`, the widest positive `+9999999999.99` and zero `+0000000000.00`. |
| `auth-request-encode-oracle-170.bin` | 170 | 1 | The canonical row with **no terminator at all** -- the same 170 characters as `auth-request-canonical-wire170.csv` minus that file's single LF, so `wc -l` reports 0 and the wire length and the file length coincide. It is the only request fixture read through `bytesOf` rather than `linesOf`, because a `\n`-splitting reader cannot express the ABSENCE of a terminator, and absence is this file's whole contract: a byte comparison against it needs no trimming and therefore cannot pass for output carrying a trailing comma, a trailing pad or one byte too many. |

Assumptions: the negative form is the only vector whose forced sign position carries a minus, which
is why it appears. A vector carrying only non-negative amounts would pass against an implementation
that took the sign out of an integer position.

---

## 2. The authorization summary segment, `PAUTSUM0`

100 bytes, six-byte packed key at offset zero, transcribed from
`app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy` lines 19 to 31 and declared by
`SEGM NAME=PAUTSUM0,PARENT=0,BYTES=100` in `app/app-authorization-ims-db2-mq/ims/DBPAUTP0.dbd`.
The Java geometry is registered as `PAUTSUM0` in
`services/common-lib/src/main/java/com/carddemo/common/codec/CopybookLayout.java`.

Assumptions: an **unsigned display** field decodes to a NUMBER, not to its stored characters. The
customer identifier is stored as `000000451` and decodes as 451, and the consumer test asserts the
decoded form. Both are recorded here because a reader comparing a hex dump against a test would
otherwise see two different values for one field and have no way to tell which is wrong.

Assumptions: the character fields are **ASCII**, not EBCDIC, while the packed and binary fields
carry their mainframe byte forms unchanged. That mixture is what these fixtures are for: it is the
shape a delivered extract has once its display fields have been transcoded and its computational
fields have not, and it is the shape `FixedWidthCodec` decodes with its default charset.

| File | Bytes | Records | Contract |
|---|---|---|---|
| `pautsum0-canonical.bin` | 100 | 1 | Account 10000000001, customer `000000451` as stored and 451 once decoded, status `A`, the five-slot account-status table `A1B2C3D4E5`, credit limit 5000.00, cash limit 1000.00, a **negative** credit balance of -100.00, 42 approved and 7 declined authorizations totalling 4200.00 and 700.00. |
| `pautsum0-negative-zero-decode-only.bin` | 100 | 1 | A negatively-signed packed zero in a money span. It is decode-only because no encoder emits one: a zero normalises to the positive sign nibble, registered as `D-SIGNED-ZERO-PACKED`. This vector exercises the reading half of that normalisation, which a round trip cannot reach. |
| `pautsum0-filler-nonblank.bin` | 100 | 1 | Value-bearing bytes in the trailing filler, so a decoder that rebuilds padding as blanks instead of preserving what it read fails byte identity rather than passing on a lucky blank. |
| `pautsum0-line-terminator-bytes.bin` | 200 | 2 | Line-terminator-shaped bytes inside character fields. A reader that split on any of them would produce a different **record count**, which is what makes this vector able to fail. |
| `pautsum0-purge-parent.bin` | 100 | 1 | The parent of `pautdtl-purge-children.bin`: 2 approved totalling 300.00 and 2 declined totalling 150.00. Its counters and amounts are the arithmetic that file's four records reproduce. |
| `unload-gsam-summary-100.bin` | 200 | 2 | Two summary records in **ascending** account order, 10000000001 then 10000000002 — the sequential unload order. |
| `unload-prefixed-summary-100.bin` | 200 | 2 | The same two records in the **opposite** order, 10000000002 then 10000000001. The pair exists so unload ordering is asserted rather than assumed: a reader that sorted its output would make the two files indistinguishable. |

---

## 3. The authorization detail segment, `PAUTDTL`

200 bytes, eight-byte key at offset zero, transcribed from
`app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy` lines 19 to 54 and declared by
`SEGM NAME=PAUTDTL1,PARENT=((PAUTSUM0,)),BYTES=200` in the same database descriptor. The Java
geometry is registered as `PAUTDTL`.

Assumptions: the segment carries **no account identifier**. The IMS parent-child relation supplies
it, so correspondence between a summary fixture and a detail fixture is asserted through the
counters and amounts rather than through a shared key column. That is why `pautsum0-purge-parent`
and `pautdtl-purge-children` are described as a pair.

Assumptions: the two twelve-digit money fields are **seven** bytes each, and the segment's own
declared length is the proof. At six bytes apiece the field widths sum to 198, two short of 200
with no field left to absorb them; at seven they sum to exactly 200.

Trade-offs: these seven files were generated by the **Python** ETL codec in
`data-migration/src/carddemo_migration/copybook/packed.py`, not by the Java codec that reads them.
A fixture produced by the codec that later reads it proves only that the codec agrees with itself;
one produced by an independently written transcription of the same copybook is a cross-language
oracle, which is the property that catches an offset error in a packed money field rather than
posting it.

| File | Bytes | Records | Contract |
|---|---|---|---|
| `pautdtl-canonical.bin` | 200 | 1 | Card `4000123456789010`, authorization date 24095 and time 91500 packed, response code `00`, transaction and approved amounts both 250.00, merchant category 5411, match status `P`, no fraud mark. |
| `pautdtl-fraud-marked.bin` | 200 | 1 | Match status `M`, fraud flag `F`, report date `20240405` — the state `cbl/COPAUS2C.cbl` writes when it reports fraud. |
| `pautdtl-match-status-domain.bin` | 800 | 4 | One record per admitted match status, in the order the copybook declares its condition names at lines 46 to 49: `P` pending, `D` declined, `E` pending-expired, `M` matched. A fifth character is not an unhandled case but a value the reference field cannot represent. |
| `pautdtl-purge-children.bin` | 800 | 4 | The children of `pautsum0-purge-parent.bin`: two approved records of 100.00 and 200.00 summing to that parent's 300.00, and two declined records of 50.00 and 100.00 summing to its 150.00. A declined record carries an approved amount of 0.00. |
| `pautdtl-negative-zero-decode-only.bin` | 200 | 1 | A negatively-signed packed zero in **both** money spans. Decode-only for the same reason as its summary counterpart. |
| `pautdtl-line-terminator-bytes.bin` | 400 | 2 | Terminator-shaped bytes inside the merchant name and city, in one record mid-field and in the other leading. Two records, so a splitting reader reports a different count. |
| `pautdtl-filler-nonblank.bin` | 200 | 1 | `RESERVED17BYTES!!` in the seventeen-byte trailing filler. |

---

## 4. Regenerating a fixture

Assumptions: the detail fixtures are regenerated by a script rather than edited in place, because
they are binary and a hand edit that changed a length would shift every field after it. The
generator is the Python packed encoder, reached from the repository root:

```bash
# WHAT: re-derive one detail fixture's bytes from the copybook geometry.
# WHY : the encoder is the ETL's, not the Java codec's, so the regenerated file remains an
#       INDEPENDENT witness to the Java transcription rather than a copy of its output. Running
#       the Java encoder instead would make every assertion in the consumer test tautological.
PYTHONPATH=data-migration/src python3 -c "
from decimal import Decimal
from carddemo_migration.copybook.packed import encode_packed
print(encode_packed(Decimal('250.00'), 10, 2, True).hex())
"
```

Review the resulting diff before committing: a change here is a change to the contract the
consumer test asserts, so the two must move together.

---

## 5. The remaining nineteen resources, and what asserts each

Assumptions: sections 1 to 3 describe eighteen of the thirty-seven fixture files in this directory. The
nineteen below arrived with the detail-segment, decline-reason, fraud-domain and wire-frame work and are
recorded here so the inventory in
`AuthorizationFixtureContractTest` has a documented counterpart for every name it lists — that class
enrols thirty-eight names, being those thirty-seven files plus this README, and a name it enumerates with
nothing written about it would leave the enrolment true only in the letter. Assumptions: enrolment and
assertion are separate claims, and the `What asserts it` column above is where the difference is recorded
per file: enrolment establishes that a resource resolves and is non-empty, while the assertion named in
each row is what holds that resource to a contract.

Refactoring Rationale: both counts in this paragraph were stale and the closure they described was
weaker than it read. It said "eighteen of the thirty-three resources" and "enrols all thirty-three as a
closed set" while five files — the two wire frames and three `pautdtl1-` segments now at the foot of the
table — were enrolled nowhere and described nowhere, so the directory held thirty-seven files against a
thirty-three-name inventory and nothing failed. The counts were only the symptom: the closure was
asserted as `hasSize(33)` against a hand-maintained list, which is a claim about the list rather than
about the directory and which therefore cannot notice a file the list omits. `EVERY_FIXTURE` is now
compared set-for-set against a real enumeration of the directory, so adding, renaming or deleting any
file here fails that test until this table and that list are both updated. Trade-offs: the enumeration
reads the fixtures through the classpath root rather than a source path, so it sees what the tests
actually load rather than what the working tree happens to hold; the cost is that the README must be
counted among the enrolled names, which is why the arithmetic above distinguishes thirty-seven files
from thirty-eight enrolled entries instead of quoting one number.

Assumptions: every record count below is measured by dividing the file's byte length by the
registered record length, 200 for `PAUTDTL`, rather than by reading a header. The `pautdtl1-` prefix
distinguishes these from the `pautdtl-` family of section 3: both describe the same 200-byte segment,
and the digit marks the later group.

| File | Bytes | Records | What asserts it |
|---|---|---|---|
| `auth-reply-declined-reasons.csv` | 448 | 7 | The DECLINE ENCODE ORACLE and the closed decline-reason set, asserted by five consumers. `theDeclinedReplyFixtureIsTheDeclineEncodeOracle` walks the six declared widths on every record, asserts the delimiter after each including the sixth at offset 62, compares every decoded member against the span it was read from, reads each reason a second time at its absolute file offset, and requires `encodeReply` and `encodeReplyBytes` to reproduce each 63-character payload byte for byte. `theDeclinedReplyFixtureCarriesLineFeedTerminatorsOnly` asserts the seven line feeds at 63/127/191/255/319/383/447 with zero carriage returns and zero NULs, deliberately apart from any length assertion because a carriage return absorbed at the trailing-comma position is length-indistinguishable from correct content. `theDeclinedReasonSetIsClosedAndMatchesTheDecisionEnum` asserts the seven reasons distinct and set-equal to `AuthorizationDecisionService.DeclineReason`, that enumeration's own arity of seven, `9000` as its last constant matching the `WHEN OTHER` position, and `0000` absent here and present in the approved sibling. `theDeclinedReplyToleratesTheZeroPaddedMoneyFormsWithoutEmittingThem` shows `+0000000000.00`, `-0000000000.00`, the bare `0.00`, the superseded 62-character form and the transmitted 64-byte form all decoding and all re-emitting as the mask, and a seventh token carrying content refused. `everyDeclinedReplyPayloadSurvivesAnOutboxRowUnchanged` carries each payload through an `auth_reply_outbox` row unchanged and asserts seven distinct 31-character correlation composites and seven distinct tokenised deduplication identities. One record per decline reason of the selection at `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl` lines 700 to 717 — `3100` not found, `4100` insufficient funds, `4200` card not active, `4300` account closed, `5100` card fraud, `5200` merchant fraud, `9000` other — on the invariant card `4000123456789010` with the transaction identifiers `TXN000000000300` to `…306`, so the reason is the only meaningful variable and a failure isolates to it. Assumptions: the money token is `          0.00`, ten spaces then the forced digit, because `WS-APPROVED-AMT-DIS PIC -zzzzzzzzz9.99` at line 66 is what line 720 moves the amount into and line 727 joins into the buffer — not `PA-RL-APPROVED-AMT PIC +9(10).99`, whose `+0000000000.00` rendering belongs to the REQUEST and appears in `auth-request-amount-variants.csv` above. Assumptions: the response code `05` is synthetic, since `CIPAUDTY.cpy` line 31 enumerates only `'00'`, so the decline test is inequality with `'00'` and never equality with `05`. Assumptions: these records are self-describing and their order is not a contract, the deliberate opposite of `unload-gsam-detail-200.bin` below. Provenance: CONTRACT-DERIVED from `CCPAURLY.cpy`, `CIPAUDTY.cpy` and that selection, not a recorded output — `COPAUA0C` is a `CO*` program that cannot run without a CICS runtime, and the requester that would elicit a decline is not supplied by the baseline at all, so there is no golden to regenerate. |
| `auth-reply-approved-wire63.csv` | 64 | 1 | Inventory, wire width, field-by-field decode and re-encode, and the reply ENCODE ORACLE, asserted by three consumers. `replyWireFixtureIsTheReplyEncodeOracle` walks the six declared field widths, asserts the delimiter after each one including the sixth, compares every decoded member against the span it was read from, and requires `CsvAuthCodec.encodeReply` to reproduce the payload byte for byte. `approvedReplyFixtureDecodesFieldByField` asserts the 63-character width, the six separators, each decoded field and the scale-two amount. `theApprovedReplyFixtureDecodesAndReEmitsIdentically` adds the value-level half: it asserts all six field values and the eight blanks that right-justify the edited money, re-emits through both `encodeReply` and `encodeReplyBytes` and requires byte identity from each, and pins the trailing comma by requiring the six-field split to yield a seventh empty token. The file is the 63-character approved reply wire plus the one LF section 3.3 of the master fixture README mandates, carrying the six reply fields and the trailing comma the reference `STRING` emits after the last of them. Refactoring Rationale: this row read "Inventory only" while field order and the delimiter are the whole of the reply contract, so the one property that could not be recovered from anything else in this directory was the one with no oracle. |
| `auth-reply-encode-oracle-63.bin` | 63 | 1 | Inventory, plus the two assertions no other reply fixture can make: the reply's **payload length** and the fact that the encoder emits **no terminator**. `replyEncodeOracleIsTheTerminatorFreeByteImage` asserts 63 bytes as the computed arithmetic `57 + 6` rather than against a bare constant, the six delimiter offsets `16, 32, 39, 42, 47, 62`, a final byte of `0x2C` that is asserted to be neither an LF nor a blank nor a digit, the absence of `0x0A`, `0x0D` and `0x00`, printable ASCII throughout, and byte identity with `auth-reply-approved-wire63.csv` truncated to 63 — which pins the two files together so they cannot drift. `replyEncodeOracleBytesEncodeDecodeAndReEmitByteForByte` is the encode oracle proper: it requires `CsvAuthCodec.encodeReplyBytes` to reproduce these bytes exactly, with no trimming and no whitespace-tolerant matcher anywhere, then decodes and re-emits, and separately proves that a decode of the 64-byte transmitted frame re-emits at 63 and never at 64. Assumptions: this is the ONLY reply fixture whose byte count equals its content length, so it is the only one where `wc -c` legitimately asserts the content, and `wc -l` reports 0 as the positive proof of terminator absence. It deliberately diverges from section 3.3 of the master fixture README; section 3.2's escape hatch of documenting a divergence in a scenario README is closed for a Java fixture directory, so the divergence and the rest of the contract are recorded in the Javadoc of the two cases above, which Checkstyle polices. Trade-offs: the `.csv` above and the transmitted frame are BOTH 64 bytes and differ only in byte 63, `0x0A` against `0x20`, so length cannot tell them apart and the tests discriminate on byte 63. Consolidating this file into either would cost exactly the two assertions named at the head of this row: comparing the encoder against the 64-byte text form either fails spuriously on a terminator the encoder must never emit, or has to be loosened into a trimming matcher that would then also pass for the transmitted frame, a different artifact. |

| `pautdtl1-canonical.bin` | 200 | 1 | Inventory, geometry and round trip. |
| `pautdtl1-amount-ten-integer-digits.bin` | 200 | 1 | Inventory, geometry, round trip, and the maximum-amount assertions in the contract test: both money spans hold the widest value `PIC S9(10)V99 COMP-3` admits. |
| `pautdtl1-auth-fraud-domain.bin` | 600 | 3 | Inventory, geometry, round trip, and the four-state domain assertions in `PendingAuthDetailFraudDomainFixtureTest` and `PendingAuthFraudDomainRepositoryIT`. One image per admitted fraud-field state, in the order their evidence appears: `F` and `R` from the condition names at `cpy/CIPAUDTY.cpy` lines 51 and 52, then the blank whose only evidence is the copybook declaring no condition name for one while `cbl/COPAUS1C.cbl` lines 344 to 349 handle that state explicitly. The fourth accepted state, SQL null, cannot be imaged by a fixed-width record and is supplied by the engine-tier consumer instead. |
| `pautdtl1-auth-fraud-invalid.bin` | 200 | 1 | `PendingAuthDetailFraudDomainFixtureTest` and `PendingAuthFraudDomainRepositoryIT`, plus geometry and round trip. **This record is INTENTIONALLY INVALID and must not be "corrected".** Its `PA-AUTH-FRAUD` byte at offset 174 is `Y`, which no condition name declares and which `ck_pending_auth_detail_auth_fraud` refuses, so it is the fence around a domain that was deliberately widened once to admit a blank. Every other field is valid and in-domain — the neighbouring `PA-MATCH-STATUS` at offset 173 is a valid `P` and both money pad nibbles are correct — so the record decodes cleanly and the refusal is attributable to the fraud byte alone; the engine-tier case therefore asserts the failing constraint BY NAME rather than merely expecting an exception. Its Julian is 24121 rather than the siblings' 24120 so that a combined load reaches the check instead of the composite key. Trade-offs: changing the byte to `F` would turn a negative test into a duplicate of a positive one, and widening the check to admit `Y` would remove the only bound on the column; both leave the suite green, which is why the intent is recorded in the consumers' Javadoc as well as here. |
| `pautdtl1-match-status-domain.bin` | 800 | 4 | Inventory, geometry and round trip — one image per admitted match status, the same four-value domain section 3 records for `pautdtl-match-status-domain.bin`. |
| `pautdtl1-merchant-name-notrim.bin` | 200 | 1 | `MerchantNameNoTrimFixtureTest`, which proves the trailing blanks of `PA-MERCHANT-NAME` are stored data and survive the load path rather than being trimmed. |
| `pautdtl1-newyear-pair.bin` | 400 | 2 | `PendingAuthDetailNewYearFixtureTest`, plus geometry and round trip. Julian 23365 at 23:59:59.999 and Julian 24001 at 00:00:01.000 on one card — the year-boundary pair. |
| `pautdtl1-order-same-day-times.bin` | 600 | 3 | `PendingAuthDetailOrderingFixtureTest`. Three same-day images whose file order is deliberately not chronological. |
| `pautdtl1-time-leading-nines.bin` | 200 | 1 | `PendingAuthDetailComplementKeyFixtureTest`, plus geometry and round trip. All eight key bytes fall outside the ASCII digit range, which is the property the round trip is there to defend. |
| `unload-gsam-detail-200.bin` | 800 | 4 | `PendingAuthDetailOrderingFixtureTest`, plus geometry and round trip. The GSAM unload form, whose records are the segment length exactly. |
| `auth-reply-transmitted-64.bin` | 64 | 1 | `AuthorizationWireFrameFixtureTest`. The reply as a TRANSPORT frame: the 63-character payload followed by one `0x20` at byte 63. `theTransmittedFrameIsThePayloadPlusOneByte` derives the width as the payload length plus one from `CsvAuthCodec.REPLY_WIRE_LENGTH` rather than against a literal 64. `byteSixtyThreeIsTheTransportPadAlone` pins the trailing byte as a blank and asserts it is neither a line feed nor a carriage return nor a NUL nor a digit, which is the whole of what separates this file from its two siblings. `theDecoderAcceptsTheFrameAndReEmitsThePayload` requires a decode of 64 bytes to re-emit at 63, so the pad is proved to be transport rather than content. `aControlByteInTheTrailingPositionIsRefused` is parameterised over `0x0A`, `0x0D` and `0x00` and shows each refused in that position. `neitherThePayloadNorTheTailCarriesATerminator` asserts no terminator anywhere. Trade-offs: this file and `auth-reply-approved-wire63.csv` are BOTH 64 bytes and differ ONLY at byte 63, `0x20` here against `0x0A` there, so no length or trimming assertion can tell them apart and every case above discriminates on that byte. Keeping both is deliberate and was reconsidered rather than assumed: a prior authority had declined to commit a near-duplicate, and the pad-versus-terminator distinction is the one property neither sibling can express, so the file is retained and made load-bearing instead. |
| `auth-request-buffer500.bin` | 500 | 1 | `AuthorizationWireFrameFixtureTest`. The request as a RECEIVE BUFFER: the 170-character payload followed by 330 blanks, which is what a fixed-length receive area holds rather than what an encoder emits. `theBufferCarriesThePayloadThenABlankTail` asserts the payload prefix and that every one of the remaining bytes is `0x20`. `thePayloadDecodesAtItsDeclaredLength` decodes at the declared 170 and asserts all eighteen fields. `theBlankPaddedBufferIsToleratedAndNeverReEmitted` records a MEASURED tolerance rather than an assumed refusal: the decoder accepts the whole 500 bytes, because the 330 blanks fall inside the eighteenth field and are stripped as trailing pad, and it then re-emits at 170 and never at 500. `aTruncatingLengthAndAnOverLongLengthAreRefused` shows a length of 100, which would cut a field, and a length one past the array both refused — so the tolerance above is bounded and is not general laxity. Assumptions: the tolerance is the interesting property and was verified before being written down; an earlier draft of this row asserted a refusal that does not occur, and stating it would have documented behaviour the code does not have. |
| `pautdtl1-date-formats.bin` | 400 | 2 | `PendingAuthDetailDatePivotFixtureTest`, plus geometry and round trip. The two-digit-year PIVOT pair, and the only fixture that reaches the twentieth-century branch. Record 1 stores `240715` and `09:15:30` with expiry `0826`; record 2 stores `991231` and `235959` with expiry `1299`. `aYearBelowThePivotWidensIntoTheTwentyFirstCentury` asserts 24 resolves to 2024, `aYearAtOrAboveThePivotWidensIntoTheTwentiethCentury` asserts 99 resolves to **1999 and not 2099**, and `theTwoRecordsTakeOppositeBranchesAcrossThePivot` asserts the pair straddles `PendingAuthDetailMapper.CENTURY_PIVOT` by reading that constant rather than restating seventy. Assumptions: record 2 is why the mapper's own Javadoc could not keep claiming every committed year takes the twenty-first-century branch — it falsified that sentence, which was corrected in the same change, and without an oracle a reader could have removed the twentieth-century branch as dead. |
| `pautdtl1-match-status-invalid.bin` | 200 | 1 | `PendingAuthDetailRefusedSegmentFixtureTest`. **This record is INTENTIONALLY INVALID and must not be "corrected".** Its `PA-MATCH-STATUS` byte at offset 173 is `X`, outside the closed `P`/`D`/`E`/`M` domain the condition names at `cpy/CIPAUDTY.cpy` lines 46 to 49 declare. `theMapperRefusesTheSegmentBeforeAnyStatement` asserts `PendingAuthDetailMapper.toEntity` raises `IllegalArgumentException` naming the field and the refused value; `onlyTheMatchStatusIsAtFault` decodes every other field so the refusal is attributable to this byte alone; `theFixtureStoresAnOutOfDomainMatchStatus` locates the byte through the registry's field descriptor and asserts the ADJACENT fraud position holds its legitimate blank, since those two consecutive single-character fields are exactly what an off-by-one offset would confuse. Assumptions: the refusal asserted here is a JAVA one and the row says so deliberately. It would be natural to claim this fixture exercises `ck_pending_auth_detail_match_status`, and through the mapper it cannot — `PendingAuthDetail`'s constructor rejects the value before a statement is prepared, so the database is never consulted and a test claiming otherwise would pass while leaving the constraint unverified. The engine-tier claim belongs to a container-backed consumer, the same split already used for the fraud byte between `PendingAuthDetailFraudDomainFixtureTest` and `PendingAuthFraudDomainRepositoryIT`. |
| `pautdtl1-raw-complement-trap.bin` | 200 | 1 | `PendingAuthDetailRefusedSegmentFixtureTest`. The complement trap: a key that decodes correctly and whose UNINVERTED form is an ordinary-looking nine-digit number. Its stored spans are 75899 and 879999999, inverting to Julian 24100 and a time of 120000000, noon exactly, corroborated against the separate ASCII `PA-AUTH-ORIG-TIME` span holding `120000`. `theTrapsKeyInvertsToARealClockReading` asserts the inverted hour is 12 and the uninverted hour exceeds 23. `theTrapSegmentLoadsWithAnInvertedKey` drives `toEntity` and asserts the KEY COLUMNS receive 24100 and 120000000 — an assertion its near-sibling's consumer does not make, since that one stops at codec level. Trade-offs: this file shares Julian 24100 with `pautdtl1-time-leading-nines.bin`, so on the date alone it would be a duplicate, and `theTrapContributesAWidthIndependentBoundary` is what records the difference and stops the pair being thinned to one. The sibling's decoded time is 1000, four characters against the nine the reference slices, so a pipeline that skipped the inversion there is caught on WIDTH as well as on value; this fixture's decoded time and its stored complement are BOTH nine characters wide, so width detects nothing and the inversion is the only thing under test. |
| `unload-prefixed-detail-206.bin` | 824 | 4 | Inventory, prefixed geometry, per-record decode and round trip, asserted by `AuthorizationFixtureContractTest.thePrefixedUnloadFixtureCarriesFourPackedPrefixedSegments` and `prefixedUnloadFixtureRecordsDecodeAndRoundTrip`. The prefixed unload form: 206 bytes per record, being the 200-byte segment behind a six-byte record prefix. Trade-offs: it is still not enrolled in the *generic* geometry check, because that check divides by the registered 200 and 824 is a whole number of 206-byte records rather than of 200-byte ones; a 206-byte layout is not registered, and inventing one to satisfy a generic check would assert a geometry no reader of this directory uses. Its dedicated cases supply the coverage instead without registering that layout: they derive the prefix width as the difference between the 206-byte stride and the registered 200, check it equals the packed width of the eleven-digit root key, decode each prefix to an account identifier, and apply the ordinary `PAUTDTL` layout to the segment behind it for a byte-identical round trip. The four prefixes are asserted as the measured sequence 10000000001, 10000000002, 10000000001, 10000000002 -- two parents ALTERNATING, not grouped as in the 200-byte GSAM sibling -- and the four segments are asserted distinct. |

Assumptions: no row in this inventory now reads "inventory only", which would name the weakest coverage
a fixture here could have -- presence and non-emptiness -- and which contradicted this file's own
opening guarantee that a byte in this directory cannot change while the suite stays green. The two rows
that once carried the label, `auth-reply-approved-wire63.csv` and `unload-prefixed-detail-206.bin`, are
both asserted semantically as of the same change that recorded this paragraph, so the guarantee and the
inventory now agree. The phrase is described here rather than dropped so that a reader adding a fixture
knows what the weakest acceptable coverage would have been, and knows not to settle for it.
