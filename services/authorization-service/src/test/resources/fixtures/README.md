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

| Source | Money width | Payload width | Emitted by |
|---|---|---|---|
| `app/app-authorization-ims-db2-mq/cpy/CCPAURQY.cpy` line 27, `PIC +9(10).99` | 14 | 170 | nothing in this repository |
| `app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl` line 63, `WS-TRANSACTION-AMT-AN PIC X(13)` | 13 | 169 | `CsvAuthCodec.encodeRequest` |

Assumptions: the **effective** contract is thirteen and sixty-nine, not the copybook's fourteen
and seventy. The reference consumer's `UNSTRING` at `COPAUA0C.cbl` line 354 reads the ordinal-nine
token straight into a thirteen-character receiver, and an `UNSTRING` receiver follows alphanumeric
move rules: a fourteen-character token is left-justified into thirteen positions and the
fourteenth character is **discarded**. An emitted `+0000000250.00` therefore arrives as
`+0000000250.0`, and `FUNCTION NUMVAL` returns 250.0 rather than 250.00. The failure is silent,
arithmetically plausible and wrong by a factor of ten in the cents.

Trade-offs: `CsvAuthCodec` emits thirteen and accepts both. Emitting fourteen would have matched
the copybook's arithmetic and broken the only consumer that exists; refusing fourteen on decode
would have rejected a payload the reference consumer accepts. The asymmetry is deliberate, and it
is why the wider vector below is named `decode-only`.

| File | Bytes | Records | Contract |
|---|---|---|---|
| `auth-request-canonical-wire169.csv` | 170 | 1 | The **canonical** request wire: 169 characters, eighteen fields, a 13-character money token `0000000250.00`. This is byte-for-byte what `CsvAuthCodec.encodeRequest` emits for that amount, so it is the encode oracle. |
| `auth-request-copybook-wire170-decode-only.csv` | 171 | 1 | The copybook-arithmetic form: 170 characters with the 14-character token `+0000000250.00`. `decodeRequest` accepts it and yields the same 250.00; `encodeRequest` never emits it. It differs from the canonical vector in the money token and in the transaction identifier -- the identifier because every committed row is deliberately distinguishable by it -- so the pair is an A/B on the money width across the other seventeen positions. |
| `auth-request-amount-variants.csv` | 510 | 3 | Three money boundaries at the canonical 13-character width: the negative form `-000000250.00` (sign plus nine integer digits), the widest positive `9999999999.99` (ten integer digits, no sign position), and zero `0000000000.00`. |
| `auth-request-encode-oracle-170.bin` | 170 | 1 | The row above it with **no terminator at all** -- the same 170 characters as `auth-request-copybook-wire170-decode-only.csv` minus that file's single LF, so `wc -l` reports 0 and the wire length and the file length coincide. It is the only request fixture read through `bytesOf` rather than `linesOf`, because a `\n`-splitting reader cannot express the ABSENCE of a terminator, and absence is this file's whole contract: a byte comparison against it needs no trimming and therefore cannot pass for output carrying a trailing comma, a trailing pad or one byte too many. Byte 169 is the digit `0`, which is what makes the reply wire's trailing comma an assertable asymmetry rather than an assumption. |

Assumptions: despite its name, this `.bin` is **not** what `CsvAuthCodec.encodeRequestBytes` emits --
that is `auth-request-canonical-wire169.csv`, and `REQUEST_WIRE_LENGTH` is 169 for the truncation
reason given above. It is the byte-level oracle for the **copybook-declared** 170-character form, and
`AuthorizationFixtureContractTest` asserts that boundary in both directions: these bytes decode
exactly, and re-encoding them yields 169 bytes that are not equal to them. Reading the name as the
stronger claim is the one mistake this pairing exists to prevent.

Assumptions: the negative form spends its sign on an integer digit position and the non-negative
form does not, which is why both appear. A vector carrying only non-negative amounts would pass
against an implementation that emitted fourteen characters for a negative one.

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

## 5. The remaining eleven resources, and what asserts each

Assumptions: sections 1 to 3 describe eighteen of the thirty resources in this directory. The eleven
below arrived with the detail-segment work and are recorded here so the inventory in
`AuthorizationFixtureContractTest` has a documented counterpart for every name it lists — that class
states it is the consumer for all thirty, and a name it enumerates with nothing written about it
would make that claim true only in the letter.

Assumptions: every record count below is measured by dividing the file's byte length by the
registered record length, 200 for `PAUTDTL`, rather than by reading a header. The `pautdtl1-` prefix
distinguishes these from the `pautdtl-` family of section 3: both describe the same 200-byte segment,
and the digit marks the later group.

| File | Bytes | Records | What asserts it |
|---|---|---|---|
| `auth-reply-approved-wire63.csv` | 64 | 1 | Inventory only. The 63-character approved reply wire plus the one LF section 3.3 of the master fixture README mandates, carrying the six reply fields and the trailing comma the reference `STRING` emits after the last of them. |
| `pautdtl1-canonical.bin` | 200 | 1 | Inventory, geometry and round trip. |
| `pautdtl1-amount-ten-integer-digits.bin` | 200 | 1 | Inventory, geometry, round trip, and the maximum-amount assertions in the contract test: both money spans hold the widest value `PIC S9(10)V99 COMP-3` admits. |
| `pautdtl1-auth-fraud-domain.bin` | 600 | 3 | Inventory, geometry and round trip — one image per admitted fraud-field state. |
| `pautdtl1-match-status-domain.bin` | 800 | 4 | Inventory, geometry and round trip — one image per admitted match status, the same four-value domain section 3 records for `pautdtl-match-status-domain.bin`. |
| `pautdtl1-merchant-name-notrim.bin` | 200 | 1 | `MerchantNameNoTrimFixtureTest`, which proves the trailing blanks of `PA-MERCHANT-NAME` are stored data and survive the load path rather than being trimmed. |
| `pautdtl1-newyear-pair.bin` | 400 | 2 | `PendingAuthDetailNewYearFixtureTest`, plus geometry and round trip. Julian 23365 at 23:59:59.999 and Julian 24001 at 00:00:01.000 on one card — the year-boundary pair. |
| `pautdtl1-order-same-day-times.bin` | 600 | 3 | `PendingAuthDetailOrderingFixtureTest`. Three same-day images whose file order is deliberately not chronological. |
| `pautdtl1-time-leading-nines.bin` | 200 | 1 | `PendingAuthDetailComplementKeyFixtureTest`, plus geometry and round trip. All eight key bytes fall outside the ASCII digit range, which is the property the round trip is there to defend. |
| `unload-gsam-detail-200.bin` | 800 | 4 | `PendingAuthDetailOrderingFixtureTest`, plus geometry and round trip. The GSAM unload form, whose records are the segment length exactly. |
| `unload-prefixed-detail-206.bin` | 824 | 4 | Inventory only. The prefixed unload form: 206 bytes per record, being the 200-byte segment behind a six-byte record prefix. Trade-offs: it is not enrolled in the generic geometry check, because that check divides by the registered 200 and 824 is a whole number of 206-byte records rather than of 200-byte ones. A 206-byte layout is not registered, and inventing one to satisfy a check would assert a geometry no reader of this directory uses. |

Assumptions: "inventory only" is stated plainly rather than left to be discovered, because it names
the weakest coverage a fixture here has — presence and non-emptiness — and a reader deciding where to
add an assertion should be able to find those rows without reading the test.
