# Reporting test fixtures

This directory holds **eight** files, and every one of them has **two** consumers.

Assumptions: this README exists because fixture files sat here with **no consumer and no
provenance statement**. A fixture nobody loads is indistinguishable from a fixture nobody needs,
and a fixture whose origin is unstated cannot be told apart from a reduced copy of production
data — which matters far more here than in the other services, because one of these files is
shaped exactly like a customer master and carries names, street addresses, telephone numbers, a
national identifier, a government-issued identifier and a date of birth in every row, and two more
carry full card numbers.

Refactoring Rationale: this document described **four** files and attested that no card number or
card verification value appeared anywhere in the directory. **Eight** files are bound here, and three
of them — `carddata.txt`, `cardxref.txt` and `tranfile.txt` — carry full 16-digit card numbers, with `carddata.txt`
additionally carrying a card-verification-shaped value in the clear in every row. The count and the
attestation were therefore both wrong, and wrong in the worse direction: a reviewer auditing this
directory for sensitive-shaped data was told there was none to audit, and `cardxref.txt` was not
mentioned in this document at all. §1 now enumerates what is actually here rather than certifying
its absence.

Refactoring Rationale: the directory now holds **eight** data files, not the four it held when this
document was written, and every count and list below is stated at eight. The four added since —
`carddata.txt`, `cardxref.txt`, `tcatbal.txt` and `tranfile.txt` — are each argued for individually in
the contract test's own preamble, which records why a card master, a cross-reference, a
category-balance set and the report's own driving record are bound by a module that owns no table.
Three of the four carry columns the earlier text asserted were absent from this directory entirely;
§1.1 replaces that assertion.

Refactoring Rationale: every count in this document previously read **seven**, which was correct until
`tranfile.txt` landed. The counts are restated at eight rather than made vague, because the census is
enforced in code — `ReportingFixtureContractTest.EXPECTED_RESOURCES` admits an exact set and asserts
the directory matches it in both directions — so a number here that disagrees with that list is a
contradiction a reader can detect but not resolve, and the failure mode of an approximate count is
that a file can be added with no row in the tables below and nobody notices.

---

## 1. Synthetic-data attestation

**No file here is copied from, derived from or reduced from production data.** Every value is
either fabricated outright or — in the **six** named values listed below — deliberately shared
with the repository's own public sample extracts under `app/data/ASCII/` so that a join resolves.
Those six are two account identifiers, two customer identifiers and two card numbers, the last pair
appearing in both `carddata.txt` and `cardxref.txt`. Nothing here originates outside this
repository.

⚠ Refactoring Rationale: that read "in four named places", counting only the account and customer
identifiers, and was written before the card master and the cross-reference were fixtures here. Six
is stated as a count of VALUES rather than of occurrences, because the two card numbers appear in
two files each and a count of occurrences would read eight for the same six strings — a reader
reconciling this figure against §3 and §1.1 needs to know which of the two is being counted.

⚠ Refactoring Rationale: this attestation read "**Every value in every file here is fabricated
… and none is a copy of a reference seed extract**", and the second half of that was false in two
places while the first half was too strong in four. Two of the five card numbers in
`carddata.txt` — `0500024453765740` and `4859452612877065` — are present verbatim in
[`app/data/ASCII/carddata.txt`](../../../../../../app/data/ASCII/carddata.txt), and the same two
appear in `cardxref.txt`. §3 already documented the identical arrangement for two of the four
account and customer identifiers and gave the reason for it, so the attestation was contradicted
by a later section of its own document. It is restated as the narrower claim that is actually
true, because an attestation a reader can disprove in one `grep` is worse than a narrower one they
can check: it withdraws confidence from the evidence table below, which is sound.

⚠ Assumptions: the two shared card numbers are **Luhn-valid**, and that is worth stating
plainly because it is the property that makes a sixteen-digit string look like a real card number
to a reviewer, a scanner or a leak detector. They are not: they are values the upstream AWS
CardDemo sample application publishes under Apache-2.0 in its own committed sample data, reachable
by anyone reading this repository. The other three — `0500024453765741`, `1010000000000001` and
`3714496353984312` — fail the Luhn check and appear in no extract. Trade-offs: the two were kept
rather than replaced with Luhn-invalid substitutes, for the reason §3 gives for the shared account
and customer identifiers — a fixture that shares no key with the seed cannot be loaded alongside
it in a future combined test, and the join this module walks is card number to account. The cost
accepted is that a scanner will flag two strings in this directory; the mitigation is this
paragraph, which is what a reviewer reaching them needs.

The remaining evidence is not merely asserted; each file carries structural evidence for it, and
the evidence is listed so a reader can check the claim rather than take it:

| Evidence | Where | Why it establishes the claim |
|---|---|---|
| National identifiers are `900000007`, `900000050`, `900000101`, `900000102` | `custfile.txt` offset 279 | The `900`–`999` area range has never been issued by the United States Social Security Administration, so no value here can collide with a real number. Each is simply the customer identifier prefixed with `9`. |
| Government-issued identifiers are `GOVTID0000000000NN` | `custfile.txt` offset 288 | A literal prefix followed by the customer identifier. The form is not an identifier scheme any authority issues. |
| Telephone numbers are all `(NNN)555-01NN` | `custfile.txt` offsets 249 and 264 | `555-0100` through `555-0199` is the range reserved for fictional use, so no number here can reach a subscriber. |
| Two of the four account and customer identifiers do not exist in the reference extracts | `acctfile.txt`, `custfile.txt` | `00000000101` and `00000000102` appear in neither `app/data/ASCII/acctdata.txt` nor `app/data/ASCII/custdata.txt`. A reduced copy could not contain rows its source does not have. |
| Every field value of the two identifiers that **do** appear differs from the extract | `acctfile.txt`, `custfile.txt` | For account `00000000007` the reference extract stores the balance `00000001930{`, which is 193.00, an expiration and reissue date of `2024-12-13` and the group `A000000000`; this fixture stores `00000005047G`, which is 504.77, dates of `2028-11-30` and `2021-06-15`, and the group `ZEROAPR`. For customer `000000007` all three name parts differ. Only the key is shared, and it is shared deliberately — see §3. |
| Both description files disagree with the reference extracts row for row | `trantype.txt`, `trancatg.txt` | The reference `trantype.txt` ships the same seven codes with different text, and the reference `trancatg.txt` ships eighteen rows where this fixture holds nine. |
| Card verification values are the literal run `901`–`905`, one per row in file order | `carddata.txt` offset 27 | The value is the row's position plus 900, not a figure computed from the card number, so no value here stands in any relation to the card it sits beside and none can authenticate anything. Both shared card numbers additionally disagree with the reference extract's own value for the same card — `0500024453765740` carries `747` there and `901` here, and `4859452612877065` carries `321` there and `905` here — so the column is fabricated even where the key is not. |
| Two of the five card numbers cannot collide with a real card | `carddata.txt`, `cardxref.txt` offset 0 | `0500024453765740` and `0500024453765741` begin with `0`, which no card scheme issues as a major industry identifier. |
| Three of the five card numbers exist in no reference extract | `carddata.txt` offset 0 | `0500024453765741`, `1010000000000001` and `3714496353984312` appear in neither `app/data/ASCII/carddata.txt` nor `app/data/ASCII/cardxref.txt`. A reduced copy could not contain rows its source does not have. |
| The two card numbers that DO appear in the extract differ from it in every other field | `carddata.txt` offset 0 | `0500024453765740` and `4859452612877065` are in `app/data/ASCII/carddata.txt` — itself the project's committed synthetic seed rather than production data — and only the key is shared: the extract stores verification value `747` and embossed name `Aniya Von` for the first where this fixture stores `901`, and `321` with `Cooper Mayert` for the second where this fixture stores `905` with `Marcus Whitfield`. The keys are shared deliberately, for the reason §3 gives for the account and customer keys. |

### 1.1 The sensitive columns this directory does carry

⚠ Refactoring Rationale: this section stated that "no card number, card verification value or
enciphered column appears in any file here, in any field, because none of the four records declares
one", and that "the card master and the cross-reference … neither is a fixture in this directory".
Both are false, and the second is contradicted by the inventory table in §2 immediately below,
which lists `carddata.txt` and `cardxref.txt` by name. The attestation was written when this
directory held four files; it now holds eight, and two of the four added records are exactly the
two it claimed were absent. A false negative here is the most costly kind of documentation error in
this repository: it is the sentence a reader would rely on before deciding a fixture needs no
handling care, and it would have told them the opposite of the truth.

The directory carries **synthetic** primary account numbers and **synthetic** card verification
values, at these positions:

| Column | File | Offset | Width | Values present | Copybook |
|---|---|---:|---:|---|---|
| `CARD-NUM` | `carddata.txt` | 0 | 16 | the five keys listed in §2 | [`CVACT02Y.cpy`](../../../../../../app/cpy/CVACT02Y.cpy) line 5 |
| `CARD-CVV-CD` | `carddata.txt` | 27 | 3 | `901`, `902`, `903`, `904`, `905` | [`CVACT02Y.cpy`](../../../../../../app/cpy/CVACT02Y.cpy) line 7 |
| `XREF-CARD-NUM` | `cardxref.txt` | 0 | 16 | four of the five above | [`CVACT03Y.cpy`](../../../../../../app/cpy/CVACT03Y.cpy) line 5 |
| `TRAN-CARD-NUM` | `tranfile.txt` | 262 | 16 | the four cross-referenced cards, plus `9999999999999999` on one orphan row | [`CVTRA05Y.cpy`](../../../../../../app/cpy/CVTRA05Y.cpy) line 15 |

Assumptions: the transaction master is the **third** file here carrying a full card number, and its
offset is 262 rather than 0 because the card is not this record's key — `TRAN-ID` is, at offset 0.
That offset is confirmed four times over: field arithmetic across `CVTRA05Y.cpy`,
[`app/jcl/TRANREPT.jcl`](../../../../../../app/jcl/TRANREPT.jcl) lines 41 and 42 declaring the
one-based DFSORT positions 263 and 305, [`app/jcl/TRANIDX.jcl`](../../../../../../app/jcl/TRANIDX.jcl)
line 27 building an alternate index `KEYS(26 304)` over `RECORDSIZE(350,350)`, and
[`app/cbl/CBTRN03C.cbl`](../../../../../../app/cbl/CBTRN03C.cbl) lines 61 to 65 splitting its own file
description as `X(304)` plus `X(26)` plus `X(20)`. It carries **no** verification value: `CVTRA05Y.cpy`
declares none, so there is no such column to fabricate. The numbers are the same synthetic keys §2.1
records, so this file adds no new card value to the directory — only a fourth position at which the
existing ones appear.

Assumptions: the five verification values are `901` through `905`, one per row in key order, which
is a counter rather than a value any issuer would compute — a real verification value is derived
from the account number, the expiry and a key, so a sequence ascending with row position cannot be
one. That is the structural evidence for these three columns, in the same form §1 gives for the
national identifiers and telephone numbers.

Assumptions: **no enciphered column appears here**, and that part of the withdrawn sentence was
true. The target stores the verification value as an encrypted `BYTEA` and these fixtures are
fixed-width baseline records decoded through the shared codec, so the ciphertext form has no
representation in this directory at all.

Handling guidance, which follows from the columns above rather than from a policy stated elsewhere:

- **A fixture card number or verification value must never reach a log, an assertion message or a
  report fixture.** The contract test asserts these columns by offset and width and by their
  synthetic markers; it does not print them. `com.carddemo.common.security.CardNumberMasker` is what
  a diagnostic uses if one is ever needed.
- **The verification value is read by nothing in this module.** It is present because
  `CVACT02Y.cpy` declares it inside the 150-byte record and the contract test proves byte-identical
  round-tripping, which a record with a hole in it cannot do. No reporting mapper reads offset 27,
  and none may: the target returns the verification value from no endpoint at all.
- **A new fixture carrying either column registers here as well as in the contract test.** The test
  enforces the closed resource set, so a file cannot appear unnoticed; this table is what keeps a
  reader from having to re-derive its sensitivity from the copybook.

---

## 2. Inventory

Each row below is asserted by the contract test. `Record length` and `Key` are read from the
registered descriptor in `com.carddemo.common.codec.CopybookLayout`; `Rows` and `Keys` are read
from the file.

| File | Descriptor | Copybook | Record length | Key | Rows | Keys |
|---|---|---|---|---|---|---|
| `acctfile.txt` | `ACCOUNT` | [`app/cpy/CVACT01Y.cpy`](../../../../../../app/cpy/CVACT01Y.cpy) | 300 | 11 bytes at offset 0 | 4 | `00000000007`, `00000000050`, `00000000101`, `00000000102` |
| `carddata.txt` | `CARD` | [`app/cpy/CVACT02Y.cpy`](../../../../../../app/cpy/CVACT02Y.cpy) | 150 | 16 bytes at offset 0 | 5 | `0500024453765740`, `4859452612877065`, `9900000000000502`, `9900001010000001`, `9900001020000001` |
| `cardxref.txt` | `XREF` | [`app/cpy/CVACT03Y.cpy`](../../../../../../app/cpy/CVACT03Y.cpy) | 50 | 16 bytes at offset 0 | 4 | `0500024453765740`, `4859452612877065`, `9900000000000502`, `9900001020000001` |
| `custfile.txt` | `CUSTOMER` | [`app/cpy/CUSTREC.cpy`](../../../../../../app/cpy/CUSTREC.cpy) | 500 | 9 bytes at offset 0 | 4 | `000000007`, `000000050`, `000000101`, `000000102` |
| `tcatbal.txt` | `TCATBAL` | [`app/cpy/CVTRA01Y.cpy`](../../../../../../app/cpy/CVTRA01Y.cpy) | 50 | 17 bytes at offset 0 | 8 | `00000000007010001`, `00000000007030001`, `00000000050010001`, `00000000050010002`, `00000000050030001`, `00000000101010001`, `00000000101040001`, `00000000102070001` |
| `trantype.txt` | `TRANTYPE` | [`app/cpy/CVTRA03Y.cpy`](../../../../../../app/cpy/CVTRA03Y.cpy) | 60 | 2 bytes at offset 0 | 7 | `01` through `07` |
| `trancatg.txt` | `TRANCAT` | [`app/cpy/CVTRA04Y.cpy`](../../../../../../app/cpy/CVTRA04Y.cpy) | 60 | 6 bytes at offset 0 | 9 | `010001`, `010002`, `010005`, `020001`, `030001`, `040001`, `050001`, `060001`, `070001` |
| `tranfile.txt` | `TRAN` | [`app/cpy/CVTRA05Y.cpy`](../../../../../../app/cpy/CVTRA05Y.cpy) | 350 | 16 bytes at offset 0 | 31 | `0000000000000001` through `0000000000000031` |

Assumptions: `tranfile.txt` is the driving record of the transaction report rather than one of the
tables the report joins to, which is why it is the only file here named for a data definition instead
of a seed dataset. The posted transaction master is batch OUTPUT, so no seeded extract of it exists
to take a name from: [`app/cbl/CBTRN03C.cbl`](../../../../../../app/cbl/CBTRN03C.cbl) names its input
`TRANFILE` and [`app/jcl/TRANREPT.jcl`](../../../../../../app/jcl/TRANREPT.jcl) supplies it at lines
65 and 66. Its 31 rows are 26 inside the `2022-01-01` to `2022-07-06` window that job states at lines
43 and 44, two fully resolvable rows one day outside each end, and three rows whose card, type code
and type-and-category pair respectively resolve in none of the three lookup fixtures.

Refactoring Rationale: this table listed `tcatbal.txt` TWICE, on consecutive rows with identical
contents, which made the census sentence below unverifiable -- a reader counting rows reached eight
where the sentence claimed seven, and the surplus row was a duplicate rather than a file. The
duplicate is removed. The card-number columns of `carddata.txt` and `cardxref.txt` were also stale:
they still named `0500024453765741`, `1010000000000001` and `3714496353984312`, the three numbers
§2.1 records as REPLACED outright, so the inventory contradicted both §2.1 and the committed bytes.
They now name what the files hold.

Assumptions: all EIGHT files are listed, in the order `ReportingFixtureContractTest` declares them.
The table previously listed five, omitting `cardxref.txt` and `tcatbal.txt` while the contract test
bound both -- so a reader reconciling the directory against this section found two files it did not
admit, and the available conclusions were that the directory held something unauthorised or that
this document was stale. The census is checkable in one place rather than two: that test's own
`EXPECTED_RESOURCES` list names all eight fixtures plus this README and asserts the directory holds
exactly those nine entries in both directions, so a file added without a row here still fails the
build, and this table is the prose half of a claim the test already enforces.

Refactoring Rationale: `cardxref.txt` and `tcatbal.txt` were absent from this table while
`ReportingFixtureContractTest` bound both, so the document that claims to be the inventory listed
five of the seven files the test asserted at that point. The two additions are the cross-reference —
`XREF`, whose registry name deliberately differs from the file name, because `XREF` is the
descriptor for `app/cpy/CVACT03Y.cpy` while `cardxref.txt` is named for the dataset — and the
transaction-category balance, whose 17-byte key is the composite `TRAN-CAT-KEY` group rather than a
single field: 11 digits of account, 2 characters of type and 4 digits of category.

Refactoring Rationale: the table previously listed **five** rows. It omitted `cardxref.txt` and
`tcatbal.txt` entirely, and its card keys were the five the file carried before §2.1's replacement.
An inventory that omits a file is worse than no inventory, because a reader checking the directory
against it concludes the two extra files are strays. The table is now closed against
`ReportingFixtureContractTest.EXPECTED_RESOURCES`, which admits exactly these eight names plus this
README, so a file added without a row here fails that case.

Assumptions: every file is line-oriented with one fixed-width record per line and a terminating
newline on the last line, which is the same shape the reference ASCII extracts under
`app/data/ASCII/` use. The newline is a **file** convention and is not part of any record: the
declared record length excludes it, and the contract test strips it before decoding. Reading the
files as one continuous byte stream would therefore mis-align every record after the first.

Assumptions: the customer record is transcribed from `CUSTREC.cpy` rather than from
`CVCUS01Y.cpy`. The two books describe the same 500-byte record and differ in field-name
punctuation and in whitespace, and the reporting mappers cite `CUSTREC.cpy` throughout, so this
directory follows them rather than introducing a second reading.

### 2.1 The card numbers and the verification value

`carddata.txt` and `cardxref.txt` are the only two files here that declare a primary account
number, and `carddata.txt` is the only one that declares a card verification value. Both columns are
accounted for individually:

| Card number | Provenance | Luhn | Where it appears |
|---|---|---|---|
| `0500024453765740` | Row 1 of [`app/data/ASCII/carddata.txt`](../../../../../../app/data/ASCII/carddata.txt), this repository's own Apache-2.0 reference extract | valid | card master and cross-reference |
| `4859452612877065` | Also present in that same published extract | valid | card master and cross-reference |
| `9900000000000502` | Fabricated here | **invalid** | card master and cross-reference |
| `9900001010000001` | Fabricated here | **invalid** | card master only |
| `9900001020000001` | Fabricated here | **invalid** | card master and cross-reference |

Assumptions: the two published numbers are carried across rather than replaced because their
provenance is checkable inside this repository — `ReportingFixtureContractTest` reads
`app/data/ASCII/carddata.txt` and requires each of them to be in it. A number whose origin can be
demonstrated from a committed, openly licensed file is a stronger attestation than a fabricated one,
which can only ever be attested by assertion.

Assumptions: the three fabricated numbers are constructed so that no scheme can issue them, on two
independent grounds either of which is sufficient. Each fails the Luhn check digit that every card
network requires, and each begins `99`, inside the major industry identifier ISO/IEC 7812 reserves
for national assignment rather than for card issuers. `ReportingFixtureContractTest` asserts the
first ground arithmetically for every committed number that is not one of the two published ones, so
a future edit cannot quietly introduce a checksum-valid number.

Assumptions: the verification value is the literal `000` on every row, and it is a placeholder
rather than a value. The column exists because `CVACT02Y.cpy` declares it at offset 27 and the
record is 150 bytes whether or not this directory has any use for it — dropping it would change the
geometry the descriptor asserts. Nothing here reads it: no reporting projection selects it, the
target column holds ciphertext, and `ReportingDeployedRelationIT` writes a fabricated constant into
that column rather than enciphering the placeholder, because it exercises no cipher.

Refactoring Rationale: three of the five card numbers were replaced outright. The set previously
held `0500024453765741`, which is a published number with its last digit altered;
`1010000000000001`, attributed by a charter to a card series that exists nowhere in this repository;
and `3714496353984312`, which is a widely published fifteen-digit provider test number with a digit
appended. Two of the five passed the Luhn check, one of those was paired with a verification value
of `905`, and none of the three carried any statement of where it came from. Replacing them cost
nothing structural — every relationship §2.2 lists is preserved, at the same widths and row
counts — and it removes the only reading under which this directory contained something that looked
like a credential.

### 2.2 The relationships the card corpus is arranged to discriminate

Five card rows and four cross-reference rows are more than a consumer needs to decode a record. The
surplus is arranged, and each arrangement distinguishes a specific pair of behaviours a smaller
corpus could not tell apart. Every row of the table is an **executable** assertion, not a note:
`ReportingFixtureContractTest.theCardFixturesCarryEveryStatedRelationship` reads it out of the files
and `ReportingDeployedRelationIT` reads it back out of the engine.

| Arrangement | What it distinguishes |
|---|---|
| Account `00000000050` carries **two** cards | A projection keyed on the card from one keyed on the account, which would collapse the pair to one row |
| Card `9900001010000001` is in the master and **not** in the cross-reference | A read that starts from the cross-reference from one that starts from the master |
| `9900001010000001` and `9900001020000001` end in the **same four digits** | A lookup on the whole number from one on the masked rendering, which names a tail rather than a card |
| Card `9900001020000001` is `N` in the master while its cross-reference row remains | Whether a reporting read filters on the master's active flag; no reporting relation carries it, so a statement is produced for a closed card exactly as the batch oracle produces one |
| The four cross-referenced cards span accounts `7`, `50` and `102` | A join that resolves per card from one that resolves per account |
| `9999999999999999` appears in neither the card master nor the cross-reference, and appears in `tranfile.txt` as the card of its first orphan row | A lookup that answers with nothing from one that answers with the nearest row |

---

## 3. Why the key domains overlap the reference extracts

Two of the four account and customer identifiers are also in the reference extracts and two are
not, and both halves are deliberate. The same split holds for the card numbers, as §1 records: two
of the five are in `app/data/ASCII/carddata.txt` and three are not.

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
`FILLER` is made of differs by record type**. For six of the eight it is taken from the record's own
reference extract under `app/data/ASCII/`, measured rather than assumed. Two have no extract of their
own and are treated separately below: the cross-reference, whose extract settles nothing, and the
posted transaction master, which is batch output and so has no extract at all. All eight rows are
listed, because a fixture absent from this table is a fixture whose pad nobody measured:

| Fixture | Descriptor | `FILLER` span | Pad character | Reference extract |
|---|---|---|---|---|
| `acctfile.txt` | `ACCOUNT` | 122–300 (178) | **blank** | `acctdata.txt` pads with blanks |
| `carddata.txt` | `CARD` | 91–150 (59) | **blank** | `carddata.txt` pads with blanks |
| `cardxref.txt` | `XREF` | 36–50 (14) | **blank** | `cardxref.txt` pads **nothing** — see below |
| `custfile.txt` | `CUSTOMER` | 332–500 (168) | **blank** | `custdata.txt` pads with blanks |
| `tcatbal.txt` | `TCATBAL` | 28–50 (22) | **ASCII zero** | `tcatbal.txt` pads with zeroes |
| `tranfile.txt` | `TRAN` | 331–350 (20) | **blank** | no extract of its own — measured from `dailytran.txt`, see below |
| `trantype.txt` | `TRANTYPE` | 52–60 (8) | **ASCII zero** | `trantype.txt` pads with zeroes |
| `trancatg.txt` | `TRANCAT` | 56–60 (4) | **ASCII zero** | `trancatg.txt` pads with zeroes |

Assumptions: the posted transaction master is the second record whose pad cannot be measured from its
own extract, and unlike the cross-reference it has no extract at all — `TRANSACT` is produced by the
posting job rather than seeded. It is measured from a DIFFERENT record instead, and the substitution is
exact rather than approximate: `app/data/ASCII/dailytran.txt` is the daily-transaction record of
[`app/cpy/CVTRA06Y.cpy`](../../../../../../app/cpy/CVTRA06Y.cpy), whose fourteen field widths match
[`app/cpy/CVTRA05Y.cpy`](../../../../../../app/cpy/CVTRA05Y.cpy) field for field, so its trailing
`FILLER` occupies the same columns 331 to 350 of the same 350-byte record. All 300 of its records are
350 bytes and every one of those spans holds 20 blanks, so the measurement is unanimous over 300
observations rather than derived from one.

Alternatives Considered: padding this one with ASCII zeroes to match the three zero-padded records.
Rejected on that measurement, and for a second reason the cross-reference row does not have: a zero
pad would move this fixture out of the codec-suppliable half of the rule below, turning a pad the
descriptor can rebuild into content the file has to carry, for a record whose geometry is the most
offset-dependent in the directory.

Assumptions: the cross-reference is the one record whose pad **cannot** be measured from its own
extract. `app/data/ASCII/cardxref.txt` stores only the 36 declared bytes and pads nothing at all
— every one of its fifty records is 36 bytes long, so it truncates exactly where the account and
card extracts pad. Two independent sources settle it as a blank instead, and they agree: the live
house fixture at `tests/fixtures/posting/happy_path/cardxref.txt` writes those 36 bytes followed by
14 blanks, and this directory's own file reaches the same byte by a second route — it omits the
`FILLER` key entirely and lets `FixedWidthCodec` rebuild the pad, which makes the value a codec fact
rather than a typed-in one.

Trade-offs: an ASCII-zero pad was available for the cross-reference and would have matched the three
zero-padded reference records, but it would have contradicted the only other committed `cardxref`
fixture in the repository for a record type whose own extract offers no counter-evidence. The row is
therefore documented as a **derived** rather than a measured fact, which is why the sentence above
names both of its sources.

The three zero-padded rows are corrections. They previously padded with blanks, which put the same
record type in two shapes two directories apart: `reference-service`'s `trantype.txt` and
`trancatg.txt` fixtures and `transaction-service`'s `tcatbal.txt` fixtures all pad with zeroes, as
their extracts do, and these did not.

Assumptions: the cross-reference is the one record whose pad **cannot** be measured from its
extract, because `app/data/ASCII/cardxref.txt` stores only the 36 declared bytes and truncates where
the account and card extracts pad. Two independent sources settle it as a blank and they agree: the
live house fixture at `tests/fixtures/posting/happy_path/cardxref.txt` writes those 36 bytes followed
by 14 blanks, and rebuilding the record from the descriptor alone reaches the same 14 bytes. A zero
pad was available and would have matched the three zero-padded records, but it would have contradicted
the only other committed cross-reference fixture in the repository for a record type whose own extract
offers no counter-evidence.

Assumptions: nothing a decode does can catch a wrong pad character. `FILLER` is declared as a
character field, so blanks and zeroes both decode, both re-encode and both round-trip byte for
byte — which is exactly why the table above is **asserted** by
`ReportingFixtureContractTest.everyFixturePadsWithItsExtractsCharacter` rather than only written
down here. That case additionally asserts the two pad characters are different, so the directory
cannot be quietly standardised on one of them.

Assumptions: whether the codec can *supply* a pad is a second and narrower fact, asserted separately
by `everyFixturePadIsSuppliedByTheCodecOnlyWhenItIsBlank`. The codec's rule is content-based: a
**blank** padding field is dropped when a record is decoded and restored when one is encoded, while a
**nonblank** one stays content and is carried through. So the five blank-padded fixtures here can be
rebuilt from the descriptor alone, byte for byte, and the three zero-padded ones cannot — their zeroes
are data the file supplies. That case asserts both directions, which is what keeps the table above
honest: if the codec could derive every pad, the table would be redundant, and if it could derive
none, the blank rows would be unverifiable.

Alternatives Considered: padding everything here with zeroes, which is the simpler rule. Rejected
on the measurement above — it would move all five blank-padded fixtures (`acctfile.txt`,
`carddata.txt`, `cardxref.txt`, `custfile.txt` and `tranfile.txt`) away from what their own extracts
do, trading three divergences for five new ones. The pad belongs to the record type, not to the
directory.

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
awk '{ printf "%s row %d: %d bytes\n", FILENAME, FNR, length($0) }' acctfile.txt carddata.txt \
    cardxref.txt custfile.txt tcatbal.txt trancatg.txt trantype.txt tranfile.txt

# WHAT: run both executable consumers for this directory.
# WHY : -am is REQUIRED and not merely convenient. Without it Maven resolves an installed
#       common-lib from the local repository rather than building the one in this tree, so a
#       descriptor change made alongside a fixture change would be tested against the stale copy.
# WHY : Assumptions: the second command is not optional. The contract test settles geometry and the
#       integration test settles what the fixtures MEAN once loaded -- masking, the three-way join and
#       the SELECT-only privilege -- so a change that keeps every row at its declared length while
#       breaking a relationship from §2.2 passes the first command and fails the second. It needs a
#       container runtime; verify runs it under Failsafe after packaging.
cd ../../../../..
mvn -B -f services/pom.xml -pl reporting-service -am -Dtest=ReportingFixtureContractTest test
mvn -B -f services/pom.xml -pl reporting-service -am -Dit.test=ReportingDeployedRelationIT verify
```

Refactoring Rationale: the byte-length command previously named **four** of the seven files then
present, so three of them — including both card files, the two most consequential in the directory —
could be edited to a wrong length and the documented check would report nothing. It now names all
**eight**, so the two can be read against each other against the §2 inventory.

Refactoring Rationale: `tranfile.txt` was appended to that command for the same reason the earlier
three were, and it is the row where an omission would cost most. At 350 bytes it is the longest record
here, its two load-bearing fields sit at offsets 262 and 304 rather than near the front, and its
amount field carries a sign overpunch in its final byte — so a row a single byte short shifts the card
number, the processing timestamp and the sign out of position at once, and every value still parses as
something. A per-row length check is the cheapest way to catch that, and it only catches what it is
told to read.

To add a row, write it out at the exact declared length with the field offsets from the copybook
linked in §2, then run the two commands above. The expected row count in
`ReportingFixtureContractTest.everyFixture` must be updated in the same change.

Assumptions: the row-count assertions in the contract test are exact rather than lower bounds, so
adding a row **will** fail the test until the expected count is updated. That is intended: a
fixture directory whose consumer accepted any number of rows could lose a row to a bad merge
without any test noticing.
