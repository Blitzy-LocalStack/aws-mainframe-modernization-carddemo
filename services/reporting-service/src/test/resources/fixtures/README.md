# Reporting test fixtures

Every file in this directory is consumed by
[`ReportingFixtureContractTest`](../../java/com/carddemo/reporting/fixtures/ReportingFixtureContractTest.java).
That test loads each resource by name, decodes it through the shared fixed-width codec against the
registered copybook descriptor, asserts its record length, its row count, its key domain and the
field values the mappers actually read, and re-encodes every row to prove byte identity. A byte in
this directory therefore cannot change while the suite stays green.

Assumptions: this README exists because four fixture files sat here with **no consumer and no
provenance statement**. A fixture nobody loads is indistinguishable from a fixture nobody needs,
and a fixture whose origin is unstated cannot be told apart from a reduced copy of production
data — which matters far more here than in the other services, because one of these four files is
shaped exactly like a customer master and carries names, street addresses, telephone numbers, a
national identifier, a government-issued identifier and a date of birth in every row.

---

## 1. Synthetic-data attestation

**Every value in every file here is fabricated. No file is copied from, derived from or reduced
from production data, and none is a copy of a reference seed extract.** The attestation is not
merely asserted; each of the four files carries structural evidence for it, and the evidence is
listed so a reader can check the claim rather than take it:

| Evidence | Where | Why it establishes the claim |
|---|---|---|
| National identifiers are `900000007`, `900000050`, `900000101`, `900000102` | `custfile.txt` offset 279 | The `900`–`999` area range has never been issued by the United States Social Security Administration, so no value here can collide with a real number. Each is simply the customer identifier prefixed with `9`. |
| Government-issued identifiers are `GOVTID0000000000NN` | `custfile.txt` offset 288 | A literal prefix followed by the customer identifier. The form is not an identifier scheme any authority issues. |
| Telephone numbers are all `(NNN)555-01NN` | `custfile.txt` offsets 249 and 264 | `555-0100` through `555-0199` is the range reserved for fictional use, so no number here can reach a subscriber. |
| Two of the four account and customer identifiers do not exist in the reference extracts | `acctfile.txt`, `custfile.txt` | `00000000101` and `00000000102` appear in neither `app/data/ASCII/acctdata.txt` nor `app/data/ASCII/custdata.txt`. A reduced copy could not contain rows its source does not have. |
| Every field value of the two identifiers that **do** appear differs from the extract | `acctfile.txt`, `custfile.txt` | For account `00000000007` the reference extract stores the balance `00000001930{`, which is 193.00, an expiration and reissue date of `2024-12-13` and the group `A000000000`; this fixture stores `00000005047G`, which is 504.77, dates of `2028-11-30` and `2021-06-15`, and the group `ZEROAPR`. For customer `000000007` all three name parts differ. Only the key is shared, and it is shared deliberately — see §3. |
| Both description files disagree with the reference extracts row for row | `trantype.txt`, `trancatg.txt` | The reference `trantype.txt` ships the same seven codes with different text, and the reference `trancatg.txt` ships eighteen rows where this fixture holds nine. |

Assumptions: no card number, card verification value or enciphered column appears in any file
here, in any field, because none of the four records declares one. The card master and the
cross-reference are the records that carry a primary account number, and neither is a fixture in
this directory. That is recorded so nobody adds one on the assumption that this directory was
already cleared for it.

---

## 2. Inventory

Each row below is asserted by the contract test. `Record length` and `Key` are read from the
registered descriptor in `com.carddemo.common.codec.CopybookLayout`; `Rows` and `Keys` are read
from the file.

| File | Descriptor | Copybook | Record length | Key | Rows | Keys |
|---|---|---|---|---|---|---|
| `acctfile.txt` | `ACCOUNT` | [`app/cpy/CVACT01Y.cpy`](../../../../../../app/cpy/CVACT01Y.cpy) | 300 | 11 bytes at offset 0 | 4 | `00000000007`, `00000000050`, `00000000101`, `00000000102` |
| `carddata.txt` | `CARD` | [`app/cpy/CVACT02Y.cpy`](../../../../../../app/cpy/CVACT02Y.cpy) | 150 | 16 bytes at offset 0 | 5 | `0500024453765740`, `0500024453765741`, `1010000000000001`, `3714496353984312`, `4859452612877065` |
| `custfile.txt` | `CUSTOMER` | [`app/cpy/CUSTREC.cpy`](../../../../../../app/cpy/CUSTREC.cpy) | 500 | 9 bytes at offset 0 | 4 | `000000007`, `000000050`, `000000101`, `000000102` |
| `trantype.txt` | `TRANTYPE` | [`app/cpy/CVTRA03Y.cpy`](../../../../../../app/cpy/CVTRA03Y.cpy) | 60 | 2 bytes at offset 0 | 7 | `01` through `07` |
| `trancatg.txt` | `TRANCAT` | [`app/cpy/CVTRA04Y.cpy`](../../../../../../app/cpy/CVTRA04Y.cpy) | 60 | 6 bytes at offset 0 | 9 | `010001`, `010002`, `010005`, `020001`, `030001`, `040001`, `050001`, `060001`, `070001` |

Assumptions: every file is line-oriented with one fixed-width record per line and a terminating
newline on the last line, which is the same shape the reference ASCII extracts under
`app/data/ASCII/` use. The newline is a **file** convention and is not part of any record: the
declared record length excludes it, and the contract test strips it before decoding. Reading the
files as one continuous byte stream would therefore mis-align every record after the first.

Assumptions: the customer record is transcribed from `CUSTREC.cpy` rather than from
`CVCUS01Y.cpy`. The two books describe the same 500-byte record and differ in field-name
punctuation and in whitespace, and the reporting mappers cite `CUSTREC.cpy` throughout, so this
directory follows them rather than introducing a second reading.

---

## 3. Why the key domains overlap the reference extracts

Two of the four account and customer identifiers are also in the reference extracts and two are
not, and both halves are deliberate.

Sharing `00000000007` and `00000000050` keeps a fixture usable alongside the reference seed data
in any future test that loads both, because the join between an account and its customer resolves
on identifiers the reference already knows. Adding `00000000101` and `00000000102` proves the
consumer does not depend on that overlap — a reader who saw only shared identifiers could not tell
whether the fixture was independent data or a filtered copy.

Assumptions: the identifier is the **only** thing shared. Every other field is fabricated, as §1
sets out, so an overlapping identifier carries no information from the extract into this
directory.

---

## 4. Field values chosen to exercise a narrowing

The description text in `trantype.txt` and `trancatg.txt` is not filler. The report and statement
mappers narrow descriptions to columns much shorter than the 50 characters their source declares,
and a fixture whose descriptions were all short would exercise none of that.

| Column | Declared source | Target column | Where |
|---|---|---|---|
| Transaction type description | 50 | **15** | `app/cpy/CVTRA07Y.cpy` line 22, narrowed at `app/cbl/CBTRN03C.cbl` line 366 |
| Transaction category description | 50 | **29** | `app/cpy/CVTRA07Y.cpy` line 26, narrowed at `app/cbl/CBTRN03C.cbl` line 368 |
| Transaction description (statement) | 100 | **49** | `app/cbl/CBSTM03A.CBL` line 135, narrowed at line 677 |

Four of the seven type descriptions and six of the nine category descriptions occupy the **full
50** characters, so narrowing them to 15 and to 29 discards text rather than blanks. The
remainder are shorter than their target columns, so they exercise the blank-fill path instead.
Both populations are needed: a narrowing that returned its input unchanged would pass on short
values alone.

Assumptions: `trancatg.txt` carries three categories under type `01` and one under each of the
other six types. The uneven fan-out is deliberate — a one-to-one fixture could not distinguish a
lookup keyed on the six-byte composite from one keyed on the two-byte type alone, and the
composite is what `CVTRA04Y.cpy` declares.

---

## 5. The trailing pad character is a per-record-type fact

Every record here closes with a `FILLER` that carries no value, and **which character that
`FILLER` is made of differs by record type**. It is taken from the record's own reference extract
under `app/data/ASCII/`, measured rather than assumed:

| Fixture | Descriptor | `FILLER` span | Pad character | Reference extract |
|---|---|---|---|---|
| `acctfile.txt` | `ACCOUNT` | 122–300 (178) | **blank** | `acctdata.txt` pads with blanks |
| `carddata.txt` | `CARD` | 91–150 (59) | **blank** | `carddata.txt` pads with blanks |
| `custfile.txt` | `CUSTOMER` | 332–500 (168) | **blank** | `custdata.txt` pads with blanks |
| `tcatbal.txt` | `TCATBAL` | 28–50 (22) | **ASCII zero** | `tcatbal.txt` pads with zeroes |
| `trantype.txt` | `TRANTYPE` | 52–60 (8) | **ASCII zero** | `trantype.txt` pads with zeroes |
| `trancatg.txt` | `TRANCAT` | 56–60 (4) | **ASCII zero** | `trancatg.txt` pads with zeroes |

The three zero-padded rows are corrections. They previously padded with blanks, which put the same
record type in two shapes two directories apart: `reference-service`'s `trantype.txt` and
`trancatg.txt` fixtures and `transaction-service`'s `tcatbal.txt` fixtures all pad with zeroes, as
their extracts do, and these did not.

Assumptions: nothing a decode does can catch a wrong pad character. `FILLER` is declared as a
character field, so blanks and zeroes both decode, both re-encode and both round-trip byte for
byte — which is exactly why the table above is **asserted** by
`ReportingFixtureContractTest.everyFixturePadsWithItsExtractsCharacter` rather than only written
down here. That case additionally asserts the two pad characters are different, so the directory
cannot be quietly standardised on one of them.

Alternatives Considered: padding everything here with zeroes, which is the simpler rule. Rejected
on the measurement above — it would move `acctfile.txt` and `custfile.txt` away from what their own
extracts do, trading three divergences for two new ones. The pad belongs to the record type, not to
the directory.

---

## 6. Regenerating or extending

There is no generator. Each file is hand-authored at its exact record length, and the contract
test is what proves the hand-authoring is right. To add a row:

```bash
# WHAT: check that every existing row is already exactly its declared length, then run the contract
#       test, which is the only thing that certifies a hand-authored row.
# WHY : the length is verified per ROW rather than per file. A file-level byte count would be
#       satisfied by one row a byte short and the next a byte long, and every field of both rows
#       would then be read from the wrong offsets while the file still measured correctly.
cd services/reporting-service/src/test/resources/fixtures
awk '{ printf "%s row %d: %d bytes\n", FILENAME, FNR, length($0) }' acctfile.txt custfile.txt \
    trantype.txt trancatg.txt

# WHAT: run the executable consumer for this directory.
# WHY : -am is REQUIRED and not merely convenient. Without it Maven resolves an installed
#       common-lib from the local repository rather than building the one in this tree, so a
#       descriptor change made alongside a fixture change would be tested against the stale copy.
cd ../../../../..
mvn -B -f services/pom.xml -pl reporting-service -am -Dtest=ReportingFixtureContractTest test
```

To add a row, write it out at the exact declared length with the field offsets from the copybook
linked in §2, then run the two commands above. The expected row count in
`ReportingFixtureContractTest.everyFixture` must be updated in the same change.

Assumptions: the row-count assertions in the contract test are exact rather than lower bounds, so
adding a row **will** fail the test until the expected count is updated. That is intended: a
fixture directory whose consumer accepted any number of rows could lose a row to a bad merge
without any test noticing.
