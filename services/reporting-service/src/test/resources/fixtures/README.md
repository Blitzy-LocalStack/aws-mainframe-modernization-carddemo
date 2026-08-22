# Reporting test fixtures

This directory holds **ten** files, and every one of them has at least **two** consumers.

Assumptions: this README exists because fixture files sat here with **no consumer and no
provenance statement**. A fixture nobody loads is indistinguishable from a fixture nobody needs,
and a fixture whose origin is unstated cannot be told apart from a reduced copy of production
data — which matters far more here than in the other services, because one of these files is
shaped exactly like a customer master and carries names, street addresses, telephone numbers, a
national identifier, a government-issued identifier and a date of birth in every row, and five more
carry full card numbers.

Refactoring Rationale: this document described **four** files and attested that no card number or
card verification value appeared anywhere in the directory. **Ten** files are bound here, and five
of them — `carddata.txt`, `cardxref.txt`, `xreffile.txt`, `tranfile.txt` and `trnxfile.txt` — carry
full 16-digit card numbers, with `carddata.txt`
additionally carrying a card-verification-shaped column in every row. The count and the
attestation were therefore both wrong, and wrong in the worse direction: a reviewer auditing this
directory for sensitive-shaped data was told there was none to audit, and `cardxref.txt` was not
mentioned in this document at all. §1 now enumerates what is actually here rather than certifying
its absence.

Refactoring Rationale: the directory now holds **ten** data files, not the four it held when this
document was written, and every count and list below is stated at ten. The six added since —
`carddata.txt`, `cardxref.txt`, `tcatbal.txt`, `tranfile.txt`, `trnxfile.txt` and `xreffile.txt` — are
each argued for individually in
the contract test's own preamble, which records why a card master, two cross-references, a
category-balance set and the report's and the statement's own driving records are bound by a module
that owns no table.
Five of the six carry columns the earlier text asserted were absent from this directory entirely;
§1.1 replaces that assertion.

Refactoring Rationale: every count in this document previously read **seven**, which was correct until
`tranfile.txt` landed, and then **eight**, which was correct until `trnxfile.txt` and `xreffile.txt`
landed. The counts are restated at ten rather than made vague, because the census is
enforced in code — `ReportingFixtureContractTest.EXPECTED_RESOURCES` admits an exact set and asserts
the directory matches it in both directions — so a number here that disagrees with that list is a
contradiction a reader can detect but not resolve, and the failure mode of an approximate count is
that a file can be added with no row in the tables below and nobody notices.

Refactoring Rationale: that this is the second drift of the same count is the reason the *safety*
claims below no longer carry one. A census is a fact about a directory and this document is the only
place it is written in prose, so it is restated; but which files carry a card number, and which
carry a card verification value, are now **derived** in code from the registered copybook descriptor
of every admitted fixture — `everyPanBearingFixture` and `everyVerificationValueBearingFixture` in
`ReportingFixtureContractTest` — precisely so that the gate cannot go stale the way this count did.
The tables in §1.1 and §2.1 are the prose half of a claim that a build now checks per column rather
than per remembered file name.

---

## 1. Synthetic-data attestation

**No file here is copied from, derived from or reduced from production data.** Every value is
either fabricated outright or — in the **six** named values listed below — deliberately shared
with the repository's own public sample extracts under `app/data/ASCII/` so that a join resolves.
Those six are two account identifiers, two customer identifiers and two card numbers, the last pair
appearing in every file here that carries a card number. Nothing here originates outside this
repository.

⚠ Refactoring Rationale: that read "in four named places", counting only the account and customer
identifiers, and was written before the card master and the cross-reference were fixtures here. Six
is stated as a count of VALUES rather than of occurrences, and stating which is being counted matters
more now than it did then: the two card numbers appear in all five card-number columns, and
`0500024453765740` alone is on 600 of `trnxfile.txt`'s 700 rows, so those two strings account for 627
occurrences between them and a count of occurrences would run into the hundreds for the same six
values. A reader
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
by anyone reading this repository. Every other card number anywhere in this directory begins `9900`
and fails the Luhn check, and one further value — the orphan sentinel `9999999999999999` — begins
`9` and fails it too; §2.1 gives the reasoning and the contract test asserts both properties of
every one of them, column by column. Trade-offs: the two published numbers were kept
rather than replaced with Luhn-invalid substitutes, for the reason §3 gives for the shared account
and customer identifiers — a fixture that shares no key with the seed cannot be loaded alongside
it in a future combined test, and the join this module walks is card number to account. The cost
accepted is that a scanner will flag two strings in this directory; the mitigation is this
paragraph, which is what a reviewer reaching them needs.

⚠ Refactoring Rationale: that paragraph named `0500024453765741`, `1010000000000001` and
`3714496353984312` as "the other three". All three were replaced outright by the edit §2.1 records,
so the sentence described a corpus that no longer existed, and it also understated the current one:
`xreffile.txt` and `trnxfile.txt` carry 86 further fabricated numbers between them. It now states
the RULE rather than a roster, because a roster of values is what went stale here and the rule is
what the build actually enforces.

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
| The card verification value is the literal `000` on every row | `carddata.txt` offset 27 | One repeated placeholder stands in no relation to the card it sits beside, so no row carries bytes that can be read as a verification value at all. Both shared card numbers additionally disagree with the reference extract's own value for the same card — `0500024453765740` carries `747` there and `4859452612877065` carries `321`, against `000` here — so the column is fabricated even where the key is not. |
| Every card number other than the two published ones cannot collide with a real card | every card-number column in the directory | Each begins `9900`, inside the major industry identifier ISO/IEC 7812 reserves for national assignment rather than for card issuers, and each additionally fails the Luhn check digit every card network requires. The orphan sentinel `9999999999999999` carries the same two properties under a `9` prefix. Both properties are asserted per value by `ReportingFixtureContractTest.noCommittedCardNumberIsAPlausibleCredential`, over columns it derives from the registry rather than from a file list. |
| Every card number other than the two published ones exists in no reference extract | every card-number column in the directory | `app/data/ASCII/carddata.txt` and `app/data/ASCII/cardxref.txt` contain no number beginning `9900` at all. A reduced copy could not contain rows its source does not have. |
| The two card numbers that DO appear in the extract differ from it in every other field | `carddata.txt` offset 0 | `0500024453765740` and `4859452612877065` are in `app/data/ASCII/carddata.txt` — itself the project's committed synthetic seed rather than production data — and only the key is shared: the extract stores verification value `747` and embossed name `Aniya Von` for the first and `321` with `Cooper Mayert` for the second, where this fixture stores the `000` placeholder and the embossed names its own customer rows carry. The keys are shared deliberately, for the reason §3 gives for the account and customer keys. |

⚠ Refactoring Rationale: the three rows above previously read at five card numbers in two files and
named `0500024453765741`, `1010000000000001`, `3714496353984312`, `901` and `905` — every one of
which had been replaced or removed by a later edit, and none of which described the 90 distinct card
numbers this directory now holds across five files. Each row is restated as the property the build
checks, at the scope the build checks it, because a table of specimen values in a document nobody
runs is what produced five simultaneous false statements about the most sensitive column here.

### 1.1 The sensitive columns this directory does carry

⚠ Refactoring Rationale: this section stated that "no card number, card verification value or
enciphered column appears in any file here, in any field, because none of the four records declares
one", and that "the card master and the cross-reference … neither is a fixture in this directory".
Both are false, and the second is contradicted by the inventory table in §2 immediately below,
which lists `carddata.txt` and `cardxref.txt` by name. The attestation was written when this
directory held four files; it now holds ten, and five of the six added records carry a card number,
three of them under exactly the two record types it claimed were absent. A false negative here is the most costly kind of documentation error in
this repository: it is the sentence a reader would rely on before deciding a fixture needs no
handling care, and it would have told them the opposite of the truth.

The directory carries **synthetic** primary account numbers and **synthetic** card verification
values, at these positions:

| Column | File | Offset | Width | Values present | Copybook |
|---|---|---:|---:|---|---|
| `CARD-NUM` | `carddata.txt` | 0 | 16 | the five keys listed in §2 | [`CVACT02Y.cpy`](../../../../../../app/cpy/CVACT02Y.cpy) line 5 |
| `CARD-CVV-CD` | `carddata.txt` | 27 | 3 | the literal `000`, on all five rows | [`CVACT02Y.cpy`](../../../../../../app/cpy/CVACT02Y.cpy) line 7 |
| `XREF-CARD-NUM` | `cardxref.txt` | 0 | 16 | four of the five above | [`CVACT03Y.cpy`](../../../../../../app/cpy/CVACT03Y.cpy) line 5 |
| `XREF-CARD-NUM` | `xreffile.txt` | 0 | 16 | 88 distinct numbers: the two published ones and 86 beginning `9900` | [`CVACT03Y.cpy`](../../../../../../app/cpy/CVACT03Y.cpy) line 5 |
| `TRAN-CARD-NUM` | `tranfile.txt` | 262 | 16 | the four cross-referenced cards, plus `9999999999999999` on one orphan row | [`CVTRA05Y.cpy`](../../../../../../app/cpy/CVTRA05Y.cpy) line 15 |
| `TRNX-CARD-NUM` | `trnxfile.txt` | 0 | 16 | the same 88 distinct numbers as `xreffile.txt`, over 700 rows | [`COSTM01.CPY`](../../../../../../app/cpy/COSTM01.CPY) line 22 |

Assumptions: two of these columns sit at an offset other than 0, and that is a property of the record
rather than of the fixture. `TRAN-CARD-NUM` is at 262 because the card is not the posted transaction's
key — `TRAN-ID` is, at offset 0 — and that offset is confirmed four times over: field arithmetic
across `CVTRA05Y.cpy`,
[`app/jcl/TRANREPT.jcl`](../../../../../../app/jcl/TRANREPT.jcl) lines 41 and 42 declaring the
one-based DFSORT positions 263 and 305, [`app/jcl/TRANIDX.jcl`](../../../../../../app/jcl/TRANIDX.jcl)
line 27 building an alternate index `KEYS(26 304)` over `RECORDSIZE(350,350)`, and
[`app/cbl/CBTRN03C.cbl`](../../../../../../app/cbl/CBTRN03C.cbl) lines 61 to 65 splitting its own file
description as `X(304)` plus `X(26)` plus `X(20)`. `TRNX-CARD-NUM` is back at 0 because the statement
sort promotes the card to the front of a 32-byte composite key, which is the whole purpose of the
derived record. Neither carries a verification value: neither `CVTRA05Y.cpy` nor `COSTM01.CPY`
declares one, so there is no such column to fabricate.

Assumptions: the verification value is one repeated placeholder rather than a per-row value, and that
is the structural evidence for it, in the same form §1 gives for the national identifiers and
telephone numbers. A real verification value is derived from the account number, the expiry and a key,
so a column that holds the same three characters beside five different cards cannot be one — and
unlike a fabricated-looking per-row value, it does not resemble one either.

Refactoring Rationale: this table listed three card columns and gave the verification values as the
run `901`–`905`. Both were stale: `xreffile.txt` and `trnxfile.txt` carry a card column each and
neither was listed, and the verification column had been replaced by the `000` placeholder that §2.1
records. The omission was the more serious half, because this table is what a reader consults before
deciding whether a fixture needs handling care, and because the contract test's own card-number gate
read `carddata.txt` alone for the same reason this table named it alone. That gate now derives its
columns from the registered descriptor of every admitted fixture, so the enforcement no longer depends
on either list being remembered, and this table is its prose half rather than its source.

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
- **A new fixture carrying either column is gated by its admission, and registers here as well.** The
  contract test enforces the closed resource set, so a file cannot appear unnoticed, and it derives
  the card-number and verification-value columns from each admitted fixture's registered descriptor —
  so a PAN-bearing arrival is held to the provenance rules of §2.1 without anyone extending a list.
  This table is what keeps a reader from having to re-derive that sensitivity from the copybook.

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
| `trancatg.txt` | `TRANCAT` | [`app/cpy/CVTRA04Y.cpy`](../../../../../../app/cpy/CVTRA04Y.cpy) | 60 | 6 bytes at offset 0 | 9 | `010001`, `010002`, `010005`, `020001`, `030001`, `040001`, `050001`, `060001`, `070001` |
| `tranfile.txt` | `TRAN` | [`app/cpy/CVTRA05Y.cpy`](../../../../../../app/cpy/CVTRA05Y.cpy) | 350 | 16 bytes at offset 0 | 31 | `0000000000000001` through `0000000000000031` |
| `trantype.txt` | `TRANTYPE` | [`app/cpy/CVTRA03Y.cpy`](../../../../../../app/cpy/CVTRA03Y.cpy) | 60 | 2 bytes at offset 0 | 7 | `01` through `07` |
| `trnxfile.txt` | `TRNX` | [`app/cpy/COSTM01.CPY`](../../../../../../app/cpy/COSTM01.CPY) | 350 | 32 bytes at offset 0 | 700 | the 32-byte composite of card number then transaction identifier, over 88 distinct cards |
| `xreffile.txt` | `XREF` | [`app/cpy/CVACT03Y.cpy`](../../../../../../app/cpy/CVACT03Y.cpy) | 50 | 16 bytes at offset 0 | 88 | the same 88 card numbers `trnxfile.txt` uses, 84 of them on account `00000000101` |

Assumptions: `tranfile.txt` is the driving record of the transaction report rather than one of the
tables the report joins to, and it is named for a data definition instead
of a seed dataset — as `trnxfile.txt` and `xreffile.txt` are, after the DD names
[`app/jcl/CREASTMT.JCL`](../../../../../../app/jcl/CREASTMT.JCL) supplies at lines 83 and 84. The
posted transaction master is batch OUTPUT, so no seeded extract of it exists
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

Assumptions: all TEN files are listed, in the order `ReportingFixtureContractTest.EXPECTED_RESOURCES`
names them -- which is the sorted order a directory listing produces, because that is what the closed-set
assertion compares against, so this table and that list can be read side by side line for line.
The table previously listed five, omitting `cardxref.txt` and `tcatbal.txt` while the contract test
bound both -- so a reader reconciling the directory against this section found two files it did not
admit, and the available conclusions were that the directory held something unauthorised or that
this document was stale. The census is checkable in one place rather than two: that test's own
`EXPECTED_RESOURCES` list names all ten fixtures plus this README and asserts the directory holds
exactly those eleven entries in both directions, so a file added without a row here still fails the
build, and this table is the prose half of a claim the test already enforces.

Refactoring Rationale: `trnxfile.txt` and `xreffile.txt` were missing from this table for the same
reason the earlier two were, one iteration later, and the two rows are added. Both are 88-card
fixtures and each carries the property the other cannot: `xreffile.txt` is the statement path's
cross-reference under the DD name `XREFFILE`, which is what makes the 88 distinct cards resolvable
at all, and `trnxfile.txt` is the statement path's driving record, whose 700 rows put 600
transactions on one card and 88 distinct cards into one run -- past both fixed table bounds of
`app/cbl/CBSTM03A.CBL`, which is the property they exist for.

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
`ReportingFixtureContractTest.EXPECTED_RESOURCES`, which admits exactly these ten names plus this
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

**Five** files here declare a primary account number — `carddata.txt`, `cardxref.txt`,
`xreffile.txt`, `tranfile.txt` and `trnxfile.txt` — and `carddata.txt` alone declares a card
verification value. Neither of those two lists is maintained by hand:
`ReportingFixtureContractTest.everyPanBearingFixture` and its
`everyVerificationValueBearingFixture` derive them from the registered copybook descriptor of every
fixture the directory admits, selecting a card number by its declared 16-byte width and its
`*-CARD-NUM` name and a verification value by its `*-CVV-CD` name. A fixture that arrives carrying
either column is therefore held to the rules below by its admission rather than by an editor
remembering to extend a list, and the list above is this document's copy of what that derivation
currently yields.

Across those five files the directory holds **90 distinct card numbers**, of exactly three kinds:

| Kind | Count | Prefix | Luhn | Where |
|---|---:|---|---|---|
| Published in this repository's own Apache-2.0 reference extract | 2 | `0500`, `4859` | valid | every one of the five files |
| Fabricated here | 87 | `9900` | **invalid** | card master, both cross-references, both transaction records |
| The orphan sentinel `9999999999999999` | 1 | `9` | **invalid** | one row of `tranfile.txt` only |

The card master's own five rows are the specimen set, and they are what the relationships in §2.2 are
built from:

| Card number | Provenance | Luhn | Where it appears |
|---|---|---|---|
| `0500024453765740` | Row 1 of [`app/data/ASCII/carddata.txt`](../../../../../../app/data/ASCII/carddata.txt), this repository's own Apache-2.0 reference extract | valid | all five card-number columns; 600 of `trnxfile.txt`'s 700 rows |
| `4859452612877065` | Also present in that same published extract | valid | all five card-number columns |
| `9900000000000502` | Fabricated here | **invalid** | all five card-number columns |
| `9900001010000001` | Fabricated here | **invalid** | card master only — the unreferenced card §2.2 relies on |
| `9900001020000001` | Fabricated here | **invalid** | all five card-number columns |

Assumptions: the two published numbers are carried across rather than replaced because their
provenance is checkable inside this repository — `ReportingFixtureContractTest` reads
`app/data/ASCII/carddata.txt` and requires each of them to be in it, and requires each to be carried
by some committed column, so an exemption that no fixture uses is withdrawn rather than left
standing. A number whose origin can be
demonstrated from a committed, openly licensed file is a stronger attestation than a fabricated one,
which can only ever be attested by assertion.

Assumptions: every fabricated number is constructed so that no scheme can issue it, and **both**
grounds are required of it rather than either being taken as sufficient. Each fails the Luhn check
digit that every card network requires, and each begins `9900`, inside the major industry identifier
ISO/IEC 7812 reserves for national assignment rather than for card issuers. Neither alone would do:
a Luhn-invalid number inside an allocated issuer range still names a real issuer, and the checksum is
the last thing a reviewer or a secret scanner looks at, while a number in the `9` range that happened
to satisfy Luhn reads to that same scanner as a live credential.
`ReportingFixtureContractTest.noCommittedCardNumberIsAPlausibleCredential` asserts both grounds, per
value, over every card-number column it derives — so a future edit cannot quietly introduce a
checksum-valid number, and cannot introduce one into a file the gate had not been told about.

Assumptions: the orphan sentinel is held inside that gate under the weaker prefix rule rather than
exempted from it. `9999999999999999` cannot carry the four-digit `9900` prefix and still read at a
glance as the deliberately unresolvable key it is, but the property that makes `9900` safe — the
national-assignment major industry identifier — it does carry, and the Luhn failure is required of it
identically. Trade-offs: this costs one branch in the gate. An outright exemption for the string
would have admitted any sixteen-digit value an editor chose to spell that way, including one that
happened to satisfy Luhn.

Assumptions: the verification value is the literal `000` on every row, and it is a placeholder
rather than a value. The column exists because `CVACT02Y.cpy` declares it at offset 27 and the
record is 150 bytes whether or not this directory has any use for it — dropping it would change the
geometry the descriptor asserts. Nothing here reads it: no reporting projection selects it, the
target column holds ciphertext, and `ReportingDeployedRelationIT` writes a fabricated constant into
that column rather than enciphering the placeholder, because it exercises no cipher.
`ReportingFixtureContractTest.everyCommittedVerificationValueIsTheSamePlaceholder` asserts the whole
column equals it, which rejects a row that varied its value even harmlessly — a column holding one
repeated placeholder cannot be mistaken for a verification value, and a column holding five different
fabricated-looking triples can.

Refactoring Rationale: this section opened by naming `carddata.txt` and `cardxref.txt` as "the only
two files here that declare a primary account number", and that was the documentation half of a real
gap rather than only a stale sentence. Three PAN-bearing fixtures had been admitted since it was
written — `xreffile.txt`, `tranfile.txt` and `trnxfile.txt`, adding 85 further card numbers between
them — and the contract test's own card-number gate read `carddata.txt` alone for exactly the same
reason this section named it alone, taking the cross-reference on the argument that every card it
names is in the master. That argument held for `cardxref.txt`, whose four cards are all in the
master, and does not survive the three: 84 of `xreffile.txt`'s cards are in no card master here, and
`tranfile.txt` deliberately carries a card that is in neither lookup file. A primary account number
could therefore have been added to any of the three with nothing objecting. The gate now derives its
columns from each admitted fixture's registered descriptor, and this section states the scope that
derivation covers rather than a roster of file names that has now gone stale twice.

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

The card master's five rows and `cardxref.txt`'s four are more than a consumer needs to decode a
record. The
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
are in `app/data/ASCII/carddata.txt` and the other 88 — the card master's remaining three, the 84
further cards the statement cross-reference adds and the orphan sentinel — are not.

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
`FILLER` is made of differs by record type**. For six of the ten it is taken from the record's own
reference extract under `app/data/ASCII/`, measured rather than assumed. Four have no usable extract
of their own and are treated separately below: the two cross-reference fixtures, whose extract
settles nothing, and the two 350-byte transaction records, which are job output and so have no
extract at all. All ten rows are
listed, because a fixture absent from this table is a fixture whose pad nobody measured:

| Fixture | Descriptor | `FILLER` offset | Length | Pad character | Reference extract |
|---|---|---:|---:|---|---|
| `acctfile.txt` | `ACCOUNT` | 122 | 178 | **blank** | `acctdata.txt` pads with blanks |
| `carddata.txt` | `CARD` | 91 | 59 | **blank** | `carddata.txt` pads with blanks |
| `cardxref.txt` | `XREF` | 36 | 14 | **blank** | `cardxref.txt` pads **nothing** — see below |
| `xreffile.txt` | `XREF` | 36 | 14 | **blank** | same record type, so the same derivation — see below |
| `custfile.txt` | `CUSTOMER` | 332 | 168 | **blank** | `custdata.txt` pads with blanks |
| `tcatbal.txt` | `TCATBAL` | 28 | 22 | **ASCII zero** | `tcatbal.txt` pads with zeroes |
| `tranfile.txt` | `TRAN` | 330 | 20 | **blank** | no extract of its own — measured from `dailytran.txt`, see below |
| `trnxfile.txt` | `TRNX` | 330 | 20 | **blank** | no extract of its own — same substitution, see below |
| `trantype.txt` | `TRANTYPE` | 52 | 8 | **ASCII zero** | `trantype.txt` pads with zeroes |
| `trancatg.txt` | `TRANCAT` | 56 | 4 | **ASCII zero** | `trancatg.txt` pads with zeroes |

Refactoring Rationale: the span column previously gave a range, and its two ends were counted from
different bases — every row read as a zero-based offset through a one-based end except
`tranfile.txt`, which read `331–350` for a `FILLER` the descriptor declares at 330. The column is now
the descriptor's own declared offset and length, verbatim, so a reader can put each row beside
`CopybookLayout` and compare two integers instead of re-deriving a convention. A mixed-base range is
the kind of claim that cannot be checked at a glance and so goes unchecked.

Assumptions: the two 350-byte transaction records have no extract at all — `TRANSACT` is produced by
the posting job rather than seeded, and the statement path's own input is produced from it by the sort
at [`app/jcl/CREASTMT.JCL`](../../../../../../app/jcl/CREASTMT.JCL) lines 44 to 53. Both are measured
from a DIFFERENT record instead, and the substitution is
exact rather than approximate: `app/data/ASCII/dailytran.txt` is the daily-transaction record of
[`app/cpy/CVTRA06Y.cpy`](../../../../../../app/cpy/CVTRA06Y.cpy), whose fourteen field widths match
[`app/cpy/CVTRA05Y.cpy`](../../../../../../app/cpy/CVTRA05Y.cpy) field for field, so its trailing
`FILLER` occupies the same columns of the same 350-byte record. All 300 of its records are
350 bytes and every one of those spans holds 20 blanks, so the measurement is unanimous over 300
observations rather than derived from one.
[`app/cpy/COSTM01.CPY`](../../../../../../app/cpy/COSTM01.CPY) line 36 declares its own trailing
`FILLER` at `PIC X(20)`, closing the same 350-byte record at the same offset, which is what carries
the measurement across to `trnxfile.txt`.

Alternatives Considered: padding those two with ASCII zeroes to match the three zero-padded records.
Rejected on that measurement, and for a second reason the cross-reference rows do not have: a zero
pad would move them out of the codec-suppliable half of the rule below, turning a pad the
descriptor can rebuild into content the file has to carry, for the records whose geometry is the most
offset-dependent in the directory.

Assumptions: the cross-reference is the one record whose pad **cannot** be measured from its own
extract, and both of this directory's cross-reference fixtures inherit that. `app/data/ASCII/cardxref.txt`
stores only the 36 declared bytes and pads nothing at all
— every one of its fifty records is 36 bytes long, so it truncates exactly where the account and
card extracts pad. Two independent sources settle it as a blank instead, and they agree: the live
house fixture at `tests/fixtures/posting/happy_path/cardxref.txt` writes those 36 bytes followed by
14 blanks, and the descriptor reaches the same 14 bytes by a second route — decode a committed row,
drop its `FILLER` entry and re-encode, and `FixedWidthCodec` restores exactly the bytes the file
carries, which makes the value a codec fact rather than only a typed-in one.
`ReportingFixtureContractTest.everyFixturePadIsSuppliedByTheCodecOnlyWhenItIsBlank` is what performs
that round trip.

Refactoring Rationale: that second route was stated as "this directory's own file ... omits the
`FILLER` key entirely and lets `FixedWidthCodec` rebuild the pad", which is not true of the file. Both
cross-reference fixtures are committed at their full 50 bytes, blanks included, as the contract test's
per-row length assertion requires; what omits the `FILLER` key is the round trip the codec case
performs on a decoded row. The distinction matters to anyone editing a fixture by hand, because the
withdrawn wording says a 36-byte row would be accepted and it would not.

Trade-offs: an ASCII-zero pad was available for the cross-reference and would have matched the three
zero-padded reference records, but it would have contradicted the only other committed `cardxref`
fixture in the repository for a record type whose own extract offers no counter-evidence. The two rows
are therefore documented as a **derived** rather than a measured fact, which is why the sentence above
names both of its sources.

The three zero-padded rows are corrections. They previously padded with blanks, which put the same
record type in two shapes two directories apart: `reference-service`'s `trantype.txt` and
`trancatg.txt` fixtures and `transaction-service`'s `tcatbal.txt` fixtures all pad with zeroes, as
their extracts do, and these did not.

Refactoring Rationale: the cross-reference argument appeared TWICE in this section, in two paragraphs
making the same case in different words, and the two had already diverged -- the earlier one carried
the `FILLER`-key wording withdrawn above while the later one did not. That divergence is the whole
argument for keeping one: two copies of a claim are two things to maintain, and the copy that does not
get updated is the one a reader happens to read. The surviving paragraph is the one that names both of
its sources; the duplicate is removed, for the reason §2 gives for removing its own duplicated
`tcatbal.txt` row.

Assumptions: nothing a decode does can catch a wrong pad character. `FILLER` is declared as a
character field, so blanks and zeroes both decode, both re-encode and both round-trip byte for
byte — which is exactly why the table above is **asserted** by
`ReportingFixtureContractTest.everyFixturePadsWithItsExtractsCharacter` rather than only written
down here. That case additionally asserts the two pad characters are different, so the directory
cannot be quietly standardised on one of them.

Assumptions: whether the codec can *supply* a pad is a second and narrower fact, asserted separately
by `everyFixturePadIsSuppliedByTheCodecOnlyWhenItIsBlank`. The codec's rule is content-based: a
**blank** padding field is dropped when a record is decoded and restored when one is encoded, while a
**nonblank** one stays content and is carried through. So every blank-padded fixture in the table
above can be rebuilt from the descriptor alone, byte for byte, and every zero-padded one cannot — their
zeroes are data the file supplies. That case asserts both directions, which is what keeps the table
above honest: if the codec could derive every pad, the table would be redundant, and if it could derive
none, the blank rows would be unverifiable.

Alternatives Considered: padding everything here with zeroes, which is the simpler rule. Rejected
on the measurement above — it would move all seven blank-padded fixtures (`acctfile.txt`,
`carddata.txt`, `cardxref.txt`, `xreffile.txt`, `custfile.txt`, `tranfile.txt` and `trnxfile.txt`)
away from what their own extracts
do, trading three divergences for seven new ones. The pad belongs to the record type, not to the
directory.

Refactoring Rationale: the two paragraphs above gave the blank half of that split as **five** and the
prose in the contract test gave it as five too. Seven fixtures pad with a blank and have since
`xreffile.txt` and `trnxfile.txt` were registered, so the figure had gone stale twice over — once per
arrival. Neither half is counted in prose any more: the contract test reads the membership of each
half out of its own pad argument source through `fixturesPaddedWith`, and
`theTwoPadPopulationsPartitionTheRegistry` asserts the two halves between them cover every registered
fixture exactly once. That last assertion is what a count was standing in for and never checked — a
fixture registered for decoding but forgotten by the pad source used to have its trailing bytes
examined by nothing, and both pad cases would still have reported a clean run.

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
    cardxref.txt custfile.txt tcatbal.txt trancatg.txt tranfile.txt trantype.txt trnxfile.txt \
    xreffile.txt

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
**ten**, so the command and the §2 inventory can be read against each other.

Refactoring Rationale: `tranfile.txt` was appended to that command for the same reason the earlier
three were, and it is the row where an omission would cost most. At 350 bytes it is the longest record
here, its two load-bearing fields sit at offsets 262 and 304 rather than near the front, and its
amount field carries a sign overpunch in its final byte — so a row a single byte short shifts the card
number, the processing timestamp and the sign out of position at once, and every value still parses as
something. A per-row length check is the cheapest way to catch that, and it only catches what it is
told to read.

Refactoring Rationale: `xreffile.txt` and `trnxfile.txt` were appended for the same reason again, one
iteration later, and this is the third time this one command has had to be extended by hand.
`trnxfile.txt` carries the same 350-byte hazard `tranfile.txt` does, over 700 rows rather than 31, and
its card number sits at offset 0 where a short row corrupts the statement key itself. The command is
kept in step with §2 rather than replaced by a glob because a glob would silently accept a fixture
that no test admits, which is the one thing the closed resource set in
`ReportingFixtureContractTest.EXPECTED_RESOURCES` exists to prevent — and that test is what actually
enforces the per-row length for all ten, this command being the copy a reader can run by hand before
invoking Maven.

To add a row, write it out at the exact declared length with the field offsets from the copybook
linked in §2, then run the two commands above. The expected row count in
`ReportingFixtureContractTest.everyFixture` must be updated in the same change.

Assumptions: the row-count assertions in the contract test are exact rather than lower bounds, so
adding a row **will** fail the test until the expected count is updated. That is intended: a
fixture directory whose consumer accepted any number of rows could lose a row to a bad merge
without any test noticing.
