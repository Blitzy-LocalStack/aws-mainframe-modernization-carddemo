# `reference_list/happy_path` - the populated transaction-type and category browse

> **Why a documentation file sits in a fixtures directory.** The two siblings here are
> fixed-width record files, and neither can carry a comment: every byte position is
> meaningful, so a `#` character would be data rather than an annotation, and a comment
> line would be a physical row of the wrong length that the loaders reject outright.
> User-specified Rule 1 (Explainability) requires at its line 27 that a comment sit
> adjacent to what it explains, and this file is the nearest carrier that principle
> permits. The tree contract makes the same requirement independently at its section 8.4,
> worded `MUST`.
>
> **This document does not restate the byte contract.** Offset base, exact-length
> enforcement, padding and the two `FILLER` regimes, line endings, the single trailing
> newline and the acceptance arithmetic are all owned by
> [`../../README.md`](../../README.md) and are cited here by section, never re-derived and
> never contradicted. What follows is this scenario's own semantics.

The four elements user-specified Rule 1 requires of a docstring map onto the sections
below, so that none of them can be dropped without the omission being visible:

| Rule 1 element | Section |
|---|---|
| Purpose (line 18) | 1 and 2 |
| Parameters (line 19) | 4, with offset, `PICTURE`, fill and copybook line per field |
| Return values (line 20) | 3 |
| Exceptions or errors (line 21) | 7 |

---

## 1. Scenario intent

This is the **populated** state of the transaction-type list flow: a `trantype` dataset
holding every seeded type, together with the `trancatg` dataset whose rows hang off it.
It is the scenario against which the *contents* of a browse page are asserted, as
distinct from the sibling `reference_list/empty_input`, which holds the zero-byte form of
the same two datasets. The two scenarios are independent and neither directory reads the
other.

Both datasets are governed here rather than only the one the list screen browses.

> Assumptions: the category dataset is part of this scenario's state rather than an
> extra. The list path browses the type table alone, but a category row names the type it
> belongs to and the target declares that reference with `ON DELETE RESTRICT`, so a
> category can never name an absent type. The rejected alternative is carrying the seven
> types by themselves: that fixture could show that a page of types is produced and could
> not show that any of them resolves to a category, so the referential half of the
> relationship the schema states in full would go unexercised.

---

## 2. The exact business rule it exercises

The browse is `app/app-transaction-type-db2/cbl/COTRTLIC.cbl`, 2098 lines, and it is
**keyset-paginated** - which is precisely the shape the migration carries into
`PageResponse{firstKey, lastKey, hasNext}`.

**The rule that matters here is an asymmetry between the two cursors.** They are not
mirror images, and the difference is one character:

| Direction | Declared at | Predicate | Ordering | Start key |
|---|---|---|---|---|
| Forward | L339-L351 | `WHERE TR_TYPE >= :WS-START-KEY` | `ORDER BY TR_TYPE` | **inclusive** |
| Backward | L355-L367 | `WHERE TR_TYPE < :WS-START-KEY` | `ORDER BY TR_TYPE DESC` | **exclusive** |

Forward paging re-reads the row it resumes from; backward paging does not. A fixture
whose rows are already in cursor order is what makes that distinction observable, and
these rows are: the types ascend `01` through `07`, and the categories ascend by the
composite `type` then `category` key with no duplicate, so no sort step stands between
the bytes and the assertion.

Page state is carried in the COMMAREA at L404-L410 - `88 CA-FIRST-PAGE VALUE 1`,
`WS-CA-LAST-PAGE-DISPLAYED PIC 9(1)`, and the next-page indicator whose two condition
names are `88 CA-NEXT-PAGE-EXISTS VALUE 'Y'` and
`88 CA-NEXT-PAGE-NOT-EXISTS VALUE LOW-VALUES`. The keys that drive it are documented at
L571-L572 as `F8 - Page down` and `F7 - Page up`. When no further page exists the flow
surfaces the message declared at L256, verbatim:

```text
No more pages for these search conditions
```

Both cursors additionally carry an optional exact type-code filter and an optional
description `LIKE` filter, gated by `WS-EDIT-TYPE-FLAG` and `WS-EDIT-DESC-FLAG`; these
ordered rows support the unfiltered browse path, where both gates are off.

Seven type rows is not an arbitrary sample size.

> Assumptions: the program's page is exactly seven rows deep.
> `COTRTLIC.cbl` L60 declares `WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7`, and every row
> table in the program is `OCCURS 7 TIMES`. Seven seeded types therefore fill one page
> exactly with no page after it, which is the one row count that pins both halves of the
> paging contract at once - a full page, and an absent next page. Trimming to a smaller
> sample would leave the full-page case unexercised, and padding past seven would leave the
> absent-next-page case unexercised, so either direction silently drops half the contract.

---

## 3. Expected outcome

**The browse.** Seven transaction types list in ascending key order, `01`/`Purchase`
first and `07`/`Adjustment` last. Eighteen categories list in ascending composite-key
order. No row is rejected and no error is raised. A page depth of seven returns all seven
types with the next-page indicator unset, because `hasNext` is discovered by reading one
record more than fits and there is no eighth record to read.

Measured distribution of the eighteen categories across their parent types:

| Type | `01` | `02` | `03` | `04` | `05` | `06` | `07` | Total |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Categories | 5 | 3 | 3 | 3 | 1 | 2 | 1 | **18** |

**Referential closure.** The set of type codes named by the eighteen category rows minus
the set of the seven declared type codes is the **empty set**. That measured emptiness is
what makes the `ON DELETE RESTRICT` foreign key satisfiable by this pair as it stands: the
categories can be loaded after the types with no orphan to reject.

**The delete-refused outcome.** Because every one of the seven types is still referenced
by at least one category, a delete of any type is refused rather than cascaded, and the
refusal travels a concrete chain:

```text
PostgreSQL SQLSTATE 23503 (foreign_key_violation)
  -> org.springframework.dao.DataIntegrityViolationException
  -> com.carddemo.common.error.GlobalExceptionHandler
  -> HTTP 409 Conflict
```

The foreign key is declared in `V1__reference.sql`, which names SQLSTATE 23503 at its
line 57 as the state it raises. The baseline reaches the same refusal through Db2 SQLCODE
-532; the target reaches it through 23503 and surfaces it as 409. That correspondence is
recorded in the service's own package documentation and is not restated here.

**The seed cross-check.** `V2__seed_reference.sql` inserts exactly seven
`transaction_types` rows and exactly eighteen `transaction_categories` rows, types before
categories so the foreign key is satisfied at insert time, and `application-test.yml`
leaves Flyway enabled with `schemas: reference` and `create-schemas: true` so both
migrations run against a bare container. These two files are therefore the **byte-level
wire-format vectors that cross-check that SQL seed**: the same seven and eighteen rows,
reached by a different route. That is why byte-for-byte agreement with `app/data/ASCII/`
matters here rather than merely holding plausible rows.

---

## 4. Fixture bytes: the record fields

Offsets below are **0-based**, the base the tree contract declares at its section 3.1 and
never mixes. The normative layout tables are that document's sections 3.2 and 3.3; the
two tables here are the scenario-scoped index those sections require of a scenario README,
citing the copybook line for every field. Where either disagrees with the copybook, the
copybook wins - that is what AAP Rule T1 (Copybook is normative) means in practice.

### 4.1 `trantype.txt` - `TRAN-TYPE-RECORD`

Source layout: `app/cpy/CVTRA03Y.cpy`, 10 lines. The banner at L2 declares `RECLN = 60`;
L4 declares `01 TRAN-TYPE-RECORD.`

| Copybook line | Field | `PICTURE` | Offset | Length | Fill |
|---|---|---|---:|---:|---|
| L5 | `TRAN-TYPE` | `X(02)` | 0 | 2 | right-pad space |
| L6 | `TRAN-TYPE-DESC` | `X(50)` | 2 | 50 | right-pad space |
| L7 | `FILLER` | `X(08)` | 52 | 8 | ASCII `'0'` (0x30), copied verbatim |

Byte-count check: 2 + 50 + 8 = **60**. Every field is `PIC X(n)`, so no field in this
record carries a sign and none is overpunched.

### 4.2 `trancatg.txt` - `TRAN-CAT-RECORD`

Source layout: `app/cpy/CVTRA04Y.cpy`, 12 lines. The banner at L2 declares `RECLN = 60`;
L4 declares `01 TRAN-CAT-RECORD.`; **L5 declares `05 TRAN-CAT-KEY.`, a 6-byte group that
occupies no bytes of its own** - its extent is entirely its two level-10 subordinates.
Adding six bytes for the group is the single easiest way to get this record wrong.

| Copybook line | Level | Field | `PICTURE` | Offset | Length | Fill |
|---|---|---|---|---:|---:|---|
| L6 | 10 | `TRAN-TYPE-CD` (in `TRAN-CAT-KEY`) | `X(02)` | 0 | 2 | right-pad space |
| L7 | 10 | `TRAN-CAT-CD` (in `TRAN-CAT-KEY`) | `9(04)` | 2 | 4 | left-pad `'0'` |
| L8 | 05 | `TRAN-CAT-TYPE-DESC` | `X(50)` | 6 | 50 | right-pad space |
| L9 | 05 | `FILLER` | `X(04)` | 56 | 4 | ASCII `'0'` (0x30), copied verbatim |

Byte-count check: 2 + 4 + 50 + 4 = **60**.

`TRAN-CAT-CD` is `PIC 9(04)` **unsigned display** - plain ASCII digits with **no sign
overpunch**. The measured distinct values across all eighteen rows are exactly `0001`,
`0002`, `0003`, `0004` and `0005`, every one digits-only with zero overpunch characters.
Its target column is `CHAR(4)` rather than an integer type, so the literal `0001` form
keeps its leading zeros end to end: they are contract, not formatting.

> Assumptions: the group name `TRAN-CAT-KEY` is not unique across the copybook corpus.
> `CVTRA04Y` L5 brackets a 6-byte group under that name; the transaction-category-balance
> record brackets a **17-byte** group under the same name. `CopybookLayout` records the
> collision as the reason field lookup is scoped per layout rather than globally: resolving
> a field by name across layouts would read this record's key as 17 bytes instead of 6 and
> shift every field after it, so the decode succeeds and returns wrong values. Section 6
> below names the concrete instance of that trap.

### 4.3 Geometry, line endings and acceptance arithmetic

| File | Record width | Records | Line ending | Bytes |
|---|---:|---:|---|---:|
| `trantype.txt` | 60 | 7 | LF only, one trailing newline | `7 x 60 + 7` = **427** |
| `trancatg.txt` | 60 | 18 | LF only, one trailing newline | `18 x 60 + 18` = **1098** |

Both figures are measured, and both satisfy the acceptance arithmetic the tree contract
states at its section 5.7. `wc -l` therefore equals the record count for each file.

### 4.4 What these two layouts do not carry

Stated positively, because silence is indistinguishable from oversight:

- **Neither layout contains a money field.** There is no `PIC S9(n)V99` and no `COMP-3`
  in either record, so **no zoned-decimal sign convention arises in this directory at
  all** and the overpunch rules at the tree contract's section 5.4 have nothing to act on
  here. A reader should not go looking for a sign byte that does not exist.
- **No synthetic-provenance attestation is owed.** The house obligation is scoped to
  fixtures holding a primary account number or identity-shaped data - customer name,
  address, national identifier, government-issued id, date of birth, phone or credit
  score. `TRAN-TYPE-RECORD` is a 2-byte code plus a description; `TRAN-CAT-RECORD` is a
  type plus a category plus a description. Neither carries any such field, so the
  obligation does not attach, and the tree contract's section 8.6 records
  `reference_list` as not requiring it.
- **The 60-byte record regime is new to this repository.** The house record-length
  enumeration lists 350, 300, 150, 50, 500 and 80 bytes; **60 is absent from it.** These
  two records are 60 bytes, so no pre-existing house fixture shares their width and none
  can be used as a worked precedent for it.

Under AAP Rule T1 the `FILLER` is dropped in the target schema, and the drop is recorded
per record: **8 bytes** for `TRAN-TYPE-RECORD`, **4 bytes** for `TRAN-CAT-RECORD`.

---

## 5. Derivation decisions

Each decision below is one a later author could reverse without any length check
failing, which is why the reason sits beside it rather than in an appendix.

### 5.1 Both files are derived to LF only

The two seeds do not agree with each other on line endings, so this is a choice rather
than a copy. Measured: `app/data/ASCII/trantype.txt` is 433 bytes with **6 CR and 7 LF** -
CRLF on rows 1 through 6 and a bare LF on row 7; `app/data/ASCII/trancatg.txt` is 1116
bytes with **18 CR and 18 LF**, uniformly CRLF. Both fixtures here carry **zero** carriage
returns.

> Trade-offs: preserving each seed's original endings was the alternative, and it was
> rejected. It would reproduce an inconsistency that carries no behavioural meaning, and
> it would leave `trantype` row 7 framed one byte differently from the other six rows of
> its own file. What is given up is byte-identity with the seed's framing; what is bought
> is one framing rule for the whole tree. The tree contract's section 6.1 records LF as
> the ruling and its section 5.5 the measurements; a fixture that intentionally preserved
> CRLF would have to say so here, so recording the opposite decision explicitly is what
> keeps that requirement meaningful.

### 5.2 `FILLER` is copied byte for byte, not synthesised

The trailing `FILLER` in both records is ASCII `'0'` (0x30) - eight of them at offset 52
in `trantype`, four at offset 56 in `trancatg` - uniform across all seven and all eighteen
rows respectively.

> Alternatives Considered: applying the house generic padding rule, which right-pads a
> text `PIC X(n)` field with spaces. Rejected because both `FILLER` fields **are**
> `PIC X(nn)`, so the generic rule would produce eight spaces and four spaces - a
> different 60 bytes in each record, which no length check would catch because the length
> would still be exactly 60. The measured source bytes win, and the tree contract's
> sections 5.3 and 6.2 make that the tree-wide ruling.

### 5.3 Descriptions come from the ASCII seeds, not the Db2 control cards

> Alternatives Considered: deriving the descriptions from
> `app/app-transaction-type-db2/ctl/DB2LTTYP.ctl` and `DB2LTCAT.ctl`, which is
> superficially the better source because they are the reference-data loader for the very
> extension tree these screens come from. Rejected on measured content: those files are
> **UPPERCASE on all 25 rows**, `DB2LTTYP.ctl` L22 writes Reversal as `'REVERAL'`, and
> `DB2LTCAT.ctl` L36 writes `'NON FRAUD REVERSAL'` where the seed writes
> `Non-fraud reversal`. A fixture derived from them would carry uppercase descriptions
> plus a misspelling plus a differently-spelled category, would no longer match the rows
> `V2__seed_reference.sql` seeds, and would breach AAP Rule T8 (User-visible strings are
> verbatim). This is the derivation mistake most likely to look reasonable, which is why
> the rejected source is named by path.

### 5.4 The descriptions are verbatim, inconsistencies included

> Assumptions: the casing in `trancatg.txt` is internally inconsistent, and that
> inconsistency **is data**. AAP Rule T8 carries user-visible strings across
> character-for-character, so an author who tidies any of the following has changed the
> fixture rather than improved it. Every instance, measured:
>
> - `Cash payment`, `Electronic payment`, `Check payment` - lowercase second word.
> - `Credit to Account` capitalises "Account", while `Credit to Purchase balance` and
>   `Credit to Cash balance` lowercase "balance".
> - `Regular Sales Draft` is title case; `Zero dollar authorization` is not.
> - `Non-fraud reversal` carries an ASCII hyphen-minus (0x2D), and it is the **only
>   hyphen in either file** - `trantype.txt` contains none.

### 5.5 All eighteen category rows, unsubsetted

> Assumptions: two individual rows of `trancatg.txt` are load-bearing for sibling
> scenarios, so the whole extract is carried rather than a sample. Pair `07|0001` is the
> discriminator the tree contract's section 6.3 names for the disclosure-group
> DEFAULT-fallback scenario, and pair `01|0005` (`Interest Amount`) is the one its section
> 6.4 names as the no-rate negative path, being present in `trancatg` and absent from
> `discgrp` in all three groups. Both are rows here. Subsetting to a plausible sample
> could drop either and leave a sibling scenario quietly vacuous while every assertion in
> this directory still passed.

### 5.6 One extract, shared with `reference_update/happy_path`

`trancatg.txt` here is byte-identical to
[`../../reference_update/happy_path/trancatg.txt`](../../reference_update/happy_path/trancatg.txt) -
the same derivation of the same seed, not an independent second derivation.

> Trade-offs: the cost is a second copy of eighteen rows, and two copies of one extract
> are exactly how the two come to disagree - a description reworded in one, a `FILLER`
> blanked in the other. The alternative of deriving the file again here was rejected
> because two derivations can differ in a way nothing would notice unless something
> compared them. What is accepted in exchange is that `ReferenceFixtureContractTest`
> asserts the two copies are byte-equal rather than reading each in isolation, so editing
> one alone fails instead of passing twice.

---

## 6. Oracle coverage for these two layouts

The claim here has to be scoped, because the two oracles differ and overstating it in
either direction misleads.

**The existing Python parity oracle does not cover either layout.**
`tests/helpers/record_codec.py` builds its `LAYOUTS` registry at L1349-L1361 with exactly
eleven keys - `ACCOUNT`, `DALYTRAN`, `DISGROUP`, `XREF`, `TCATBAL`, `CARD`, `CUSTOMER`,
`TRAN`, `TRNX`, `REJECT`, `INTTRAN` - and there is **no `TRANTYPE` key and no `TRANCAT`
key**. A search for `CVTRA03Y` or `CVTRA04Y` across the whole `tests/` tree returns zero
hits, the house layout tables cover neither copybook, and the house derivation table names
a copybook and a length for every seed row except `trancatg.txt` and `trantype.txt`. So
these bytes **cannot** be cross-checked with `record_codec.py`.

> Assumptions: the literal text `TRANCAT` does appear in `record_codec.py`, and it is not
> support for this layout. At L1163-L1165 the field names `TRANCAT-ACCT-ID` (offset 0,
> length 11), `TRANCAT-TYPE-CD` (offset 11, length 2) and `TRANCAT-CD` (offset 13, length
> 4) sit inside `TCATBAL_LAYOUT` at L1157, which is `CVTRA01Y` - a **different 50-byte
> record carrying an 11-byte account-id prefix**, and the record whose 17-byte
> `TRAN-CAT-KEY` group collides by name with the 6-byte group in section 4.2 above. A reader who
> matches on the name alone reads the wrong widths at the wrong offsets.

**The Java side does register both.** `com.carddemo.common.codec.CopybookLayout` declares
`TRANTYPE` at 60 bytes with a 2-byte key at offset 0 and `TRANCAT` at 60 bytes with a
6-byte key at offset 0, and its per-field offsets match sections 4.1 and 4.2 above exactly. The
gap belongs to the existing Python oracle, not to the target: the absence of Python
support removes one cross-check, and it does not leave this geometry unverifiable. The
tree contract's section 11.2 records the same scoping.

---

## 7. Failure modes these bytes can produce

- **A one-byte miscount shifts every field after it.** The symptom of a shifted record is
  a plausible-looking wrong value rather than an obvious break, which is why section 4 above
  states the width and every offset explicitly.
- **A wrong-length row is rejected, not coerced.** The loaders do **not** pad short rows,
  do **not** truncate long rows, and do **not** silently drop blank lines. A miscount is a
  hard failure at load rather than a quietly corrupted record, and that is a deliberate
  financial-integrity stance rather than strictness for its own sake: a malformed record
  that loads cleanly asserts the wrong values forever. The Java side enforces the same
  contract, raising `RecordLengthException` on decode. Tree contract section 5.2.
- **A blank line is a zero-length record, not a separator.** Inserting one to group rows
  visually adds a row of length 0 and fails parsing.
- **Only a genuinely zero-byte file counts as empty.** A file holding a single newline is
  a 1-byte file holding one zero-length record. Neither file in this directory is empty;
  the zero-byte form of both lives in `reference_list/empty_input`.
- **A stray carriage return corrupts the last field.** It is absorbed into the trailing
  `FILLER` and pushes the record to 61 bytes, one over the declared width - which is
  precisely why section 5.1 above derives both files to LF only.

---

## 8. Consumers, verified against the branch

Following the house precedent of recording availability as measured rather than as
planned, and matching the tree contract's sections 9.1 and 10:

**Present and reading this directory.** Two consumers in
`services/reference-service/src/test/java/com/carddemo/reference/fixtures`, both run by
Surefire:

- `ReferenceFixtureContractTest` enrols `trantype.txt` as 60 bytes by 7 records and
  `trancatg.txt` as 60 bytes by 18 records in a closed geometry inventory, decodes the
  seven types in key order, asserts the eighteen categories are ordered, free of duplicate
  keys and referentially closed over the seven types, asserts byte-equality with the
  `reference_update/happy_path` copy, asserts the nonblank `FILLER` survives decoding as
  content, and asserts that a record fed at the wrong declared width is refused rather
  than mis-read.
- `ReferenceFixtureTest` decodes `trantype.txt` through `CopybookLayout.layout("TRANTYPE")`
  and orphan-checks `trancatg.txt`, enrolling both files in its geometry and keyed-fixture
  inventories so a duplicate key or a changed record count fails there as well.

**Not present in this tree.** No `*ControllerTest`, `*ServiceTest` or `*RepositoryIT`
exists in `services/reference-service/src/test/java`. For those three test kinds this
document is a contract to be met when such classes are added, not a description of
anything reading these bytes today.

Fixtures reach their consumers from the **test classpath** rather than by filesystem path:
Maven copies `src/test/resources/` into `target/test-classes/`, so each file resolves as
`fixtures/reference_list/happy_path/<file>.txt`.

> Trade-offs: the byte contract is written before every one of its eventual consumers
> exists, which is why this section separates what is measured from what is forward-looking
> instead of naming a single class list and implying all of it runs. The alternative -
> deferring this document until the remaining test kinds are authored - was rejected
> because the geometry is what those classes would have to be written against, so it has
> to be settled first. No specific test method is named here, because a method name is the
> detail most likely to change without any of these bytes changing.

**The direction of the contract is one-way.** The bytes are authoritative: a consumer's
expected value is whatever these files decode to, and a disagreement is settled by reading
the bytes rather than by editing them.
