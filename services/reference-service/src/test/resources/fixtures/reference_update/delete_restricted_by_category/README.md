# `reference_update/delete_restricted_by_category` -- the foreign key that refuses a delete

Two 60-byte fixed-width record files that put a **referenced** transaction type
beside an **unreferenced** one, so that one directory reaches both sides of the
restricting foreign key: the delete that must be refused with HTTP 409, and the
delete that must succeed.

This document is the Explainability carrier for those bytes. The four elements
user-specified Rule 1 requires of a module entry point are each findable in one
place, so a reviewer auditing this directory does not have to hunt for them:

| Rule 1 element | Where it lives here | What it covers |
|---|---|---|
| Purpose (L18) | sections 1, 2 and 3 | what this scenario represents, why the document exists, and the exact business rule the bytes exercise |
| Parameters (L19) | section 5 | the fixture bytes, and per field its 0-based offset, `PICTURE`, fill character and copybook line |
| Return values (L20) | section 6 | the specific status and value asserted for each of the two type codes, and which consumer asserts it |
| Exceptions and errors (L21) | section 7 | the failure modes these bytes can produce, each with the symptom it actually gives |

Sections 4, 8 and 9 carry the labelled decision rationales, the derivation and
seed policy, and the boundary against the similarly-named sibling domain.

**The full byte-level contract is not restated here.** Record widths, the
exact-length rule, the two `FILLER` regimes, the acceptance arithmetic, the
line-ending ruling and the label orthography are all owned by the tree-scope
charter at
[`services/reference-service/src/test/resources/fixtures/README.md`](../../README.md),
which this document cites and never contradicts. Where the two ever disagree,
the charter wins.

---

## 1. Scenario intent

The condition this scenario represents is a **delete request that referential
integrity must refuse**, held together in one directory with the **delete that
integrity must permit**, so that a consumer can prove the refusal is selective
rather than blanket.

- Type code `06` is named by both category rows in this directory, so **its
  delete is refused**.
- Type code `99` is named by no category row anywhere, so **its delete
  succeeds**.

Assumptions: the referencing category rows live **in this directory**, in the
2 records of `trancatg.txt`, rather than only being described in prose. Both
halves of the assertion are therefore decided by the 2 records of
`trantype.txt` and the 2 of `trancatg.txt` and by nothing outside them, which is
what lets a consumer load exactly this directory and reach both outcomes. A
directory holding only the type file would leave the referencing side
unreadable, and a consumer would have to borrow the 18 records of
`../happy_path/trancatg.txt` to reach the refusal at all -- describing a
combination that is never loaded together.

The scenario name states the **condition**, not the data and not the program,
following the house naming pattern that gives `reject_102_overlimit` and
`unmatched_account`. Charter section 8.2 records that rule.

---

## 2. Why this README exists at all

No row of the migration plan asks for a Markdown file at this path. The plan
asks for fixture records derived from the copybook layouts (AAP sections 0.2.1.2
and 0.4.1.2) -- records, not documentation. This file is nonetheless mandatory,
on two independent grounds.

**First, user-specified Rule 1 (Explainability) L27 requires a comment to sit
adjacent to the thing it explains, and a 60-byte fixed-width record admits no
comment syntax of any kind.** Every byte position in these files is meaningful,
so a `#` would be *data* rather than an annotation; and a comment line would be
a physical row whose length is not 60, which the loaders reject outright rather
than pad, truncate or drop (`tests/fixtures/README.md` L144 to L151). There is
no in-file carrier available, so the adjacent README is the only one. Rule 1 L22
names the per-language docstring formats and closes with `etc.`, which is what
licenses a language-appropriate analogue for a directory of comment-less data
files.

**Second, the house convention mandates it in `MUST` terms.**
`tests/fixtures/README.md` section 9.1 (L695 to L719) states at **L697 to L699**
that every scenario subfolder **MUST** carry a short README, describing it there
as a mandatory Explainability carrier and not a suggestion. Its **L714 to L719**
record that the `MUST` wording was restored from an earlier, weaker "should"
precisely because the weaker form let scenario directories ship with no carrier
at all. Charter section 8.4 inherits both grounds and maps the required content
onto Rule 1's four elements, which is the mapping the table above follows.

The gate on this obligation is **human review, with no mechanical fallback**.
`config/checkstyle/checkstyle.xml` L185 scopes its `Checker` to
`fileExtensions="java"`, so no file in this directory is ever scanned; the
`config/checkstyle/suppressions.xml` entry at L151 matching
`src/test/resources/fixtures/` is defensive, covering the narrow case of a
`.java` file co-located with a fixtures directory, and it discharges no Rule 1
obligation of its own. Authoring discipline is the only protection these bytes
have.

---

## 3. The business rule exercised

### 3.1 The baseline delete path

`app/app-transaction-type-db2/cbl/COTRTUPC.cbl` performs the delete in
`9800-DELETE-PROCESSING.` at **L1624**. It moves the selected type into the host
variable at **L1625**, issues
`DELETE FROM CARDDEMO.TRANSACTION_TYPE WHERE TR_TYPE = :DCL-TR-TYPE` at **L1627
to L1630**, copies `SQLCODE` into a display field at **L1632** (declared at
**L68** as `10 WS-DISP-SQLCODE PIC ----9.`), and branches on the result in an
`EVALUATE TRUE` at **L1634**:

| Branch | Lines | Behaviour |
|---|---|---|
| `WHEN SQLCODE = ZERO` | L1635 to L1637 | `SET TTUP-DELETE-DONE TO TRUE` then `EXEC CICS SYNCPOINT` -- the unreferenced type deletes cleanly and the unit of work commits |
| `WHEN SQLCODE = -532` | L1638 to L1649 | `SET RECORD-DELETE-FAILED TO TRUE` at L1639, then a `STRING` composing `'Please delete associated child records first:'` (L1641), `'SQLCODE :'` (L1642), `WS-DISP-SQLCODE` (L1643), `':'` (L1644) and `SQLERRM OF SQLCA` **at L1645 and again at L1646** into `WS-RETURN-MSG` -- the referential refusal |
| `WHEN OTHER` | L1650 to L1661 | sets **both** `RECORD-DELETE-FAILED` (L1651) **and** `TTUP-DELETE-FAILED` (L1652), with `'Delete failed with message:'` at L1654 -- a generic failure |

`END-EVALUATE` is at **L1662** and the exit paragraph at **L1664**.

**Db2 `SQLCODE -532` is the referential-constraint violation raised by
`DELETE RESTRICT`** -- the Db2 analogue of the PostgreSQL condition the target
raises.

The delete is reached only through a **two-step F4 confirmation**: **L493 to
L495** turn a first F4 on a displayed record into `TTUP-CONFIRM-DELETE`, and
**L482 to L486** turn a second F4 in that state into `TTUP-START-DELETE` and
`PERFORM 9800-DELETE-PROCESSING THRU 9800-DELETE-PROCESSING-EXIT`. The state
flags sit at **L307 to L313**: `TTUP-DELETE-IN-PROGRESS VALUES '9', '8', '7',
'6'` spanning L307 to L309, then `TTUP-CONFIRM-DELETE VALUE '9'` (L310),
`TTUP-START-DELETE VALUE '8'` (L311), `TTUP-DELETE-DONE VALUE '7'` (L312) and
`TTUP-DELETE-FAILED VALUE '6'` (L313).

### 3.2 The chain from the baseline to the target, link by link

Each link below is cited, because the value of these bytes rests entirely on the
chain being unbroken:

1. **The baseline constraint.** `COTRTUPC.cbl` **L1638** branches on
   `SQLCODE = -532`, which is raised by the foreign key declared at
   `app/app-transaction-type-db2/ddl/TRNTYCAT.ddl` **L6 to L7** as
   `FOREIGN KEY TRC_TYPE_CODE (TRC_TYPE_CODE) REFERENCES
   CARDDEMO.TRANSACTION_TYPE (TR_TYPE) ON DELETE RESTRICT`. The parent key is
   `app/app-transaction-type-db2/ddl/TRNTYPE.ddl` **L4**,
   `PRIMARY KEY(TR_TYPE)`.
2. **The target constraint.**
   [`V1__reference.sql`](../../../../../main/resources/db/migration/V1__reference.sql)
   **L266 to L268** declares
   `FOREIGN KEY (type_cd) REFERENCES reference.transaction_types (type_cd)
   ON DELETE RESTRICT`, and its adjacent note at **L236 to L242** records that
   `RESTRICT` was chosen over `CASCADE`, `SET NULL` and `NO ACTION` precisely
   because it is the observable outcome the baseline asserts, calling the
   constraint the single load-bearing object of that file.
3. **The baseline index order.** The composite key at `V1__reference.sql`
   **L234**, `PRIMARY KEY (type_cd, cat_cd)`, builds a unique B-tree on exactly
   the two columns and exactly the order that
   `app/app-transaction-type-db2/ddl/XTRNTYCAT.ddl` **L3** names as
   `(TRC_TYPE_CODE ASC, TRC_TYPE_CATEGORY ASC)`. Its note at **L225 to L233**
   records that no separate `CREATE INDEX` is issued for that reason.
4. **The database condition.** A delete blocked by that foreign key raises
   PostgreSQL `SQLSTATE` **23503**, named at `V1__reference.sql` **L57** as
   `foreign_key_violation`.
5. **The Java condition.** Spring surfaces that as
   `DataIntegrityViolationException`, which
   [`GlobalExceptionHandler`](../../../../../../../common-lib/src/main/java/com/carddemo/common/error/GlobalExceptionHandler.java)
   recognises by fully-qualified name at **L456** and matches through a
   cause-chain walk rather than a compile-time dependency on the persistence
   abstraction.
6. **The HTTP status.** That handler answers **HTTP 409** at **L1038 to L1041**,
   carrying the message constant declared at **L294 to L295**. `V1__reference.sql`
   **L262 to L265** states the same mapping from the schema side and records that
   `reference-service` declares no advice of its own, so the mapping cannot
   drift per service.

Column types follow the same lineage rather than taste. `TRNTYCAT.ddl` **L2 to
L4** declares `CHAR(2)`, `CHAR(4)` and `VARCHAR(50)`, which is where
`V1__reference.sql` inherits them; its note at **L180 to L204** lists six
independent baseline sources for `cat_cd` being character rather than integer,
because an integer column would turn `0001` into `1`. That is why the literal
four-digit form and the 50-wide blank-padded descriptions in these fixtures are
the correct bytes and not an authoring habit.

AAP section 0.5.1.6 states the target behaviour for this flow directly, in its
`TransactionTypeController` row: **"List, add, edit and delete; the Db2
`RESTRICT` behaviour surfaces as a 409 rather than a database error."**

### 3.3 The refusal stays distinguishable from a generic failure

The baseline separates the two outcomes by how many flags it sets: the `-532`
branch sets **only** `RECORD-DELETE-FAILED` (L1639), while `WHEN OTHER` sets
**both** `RECORD-DELETE-FAILED` and `TTUP-DELETE-FAILED` (L1651, L1652). A
referential refusal and an unclassified failure are therefore not one state in
the baseline, and the target keeps them apart rather than merging them:
`GlobalExceptionHandler` **L1005** documents the contract as HTTP 409 with the
referenced-row message for an integrity violation and **otherwise the same HTTP
500 shape**. So a consumer that received a 500 for type `06` would be failing
this scenario even though a delete had also been prevented.

### 3.4 One documented divergence

The baseline's `-532` message concatenates machine diagnostics into
user-visible prose: the display `SQLCODE` (`WS-DISP-SQLCODE`, `PIC ----9.`) at
L1643 and `SQLERRM OF SQLCA` twice over, at L1645 and again at L1646. The
target's 409 keeps the sentence and the diagnostics apart -- it carries
`'Please delete associated child records first:'` verbatim from L1641 and
appends nothing after the colon, so the response names **no constraint, no
table, no schema and no vendor error code**. `GlobalExceptionHandler` **L284 to
L287** states that reasoning at the declaration site: appending the database's
own diagnostic to a client-facing body would disclose the schema and the
constraint name to whoever reads the response, so the diagnostic goes to the
operational record instead.

The framing is deliberate and narrow. **The baseline does X; the target
implements Y; the divergence is documented.** Nothing under `app/**` is altered
by any of this -- the COBOL baseline is REFERENCE-ONLY and stays byte-identical,
and its `-532` message remains exactly as written. `GlobalExceptionHandler`
**L289 to L292** records that the *condition* itself is unchanged: the
reference-data foreign key still carries `ON DELETE RESTRICT`, so deleting a
transaction type remains impossible while categories reference it.

### 3.5 Verbatim delete-flow strings

AAP transformation rule T8 carries user-visible strings across character for
character. Those belonging to this flow, all declared in `COTRTUPC.cbl`, with
the two easily-tidied details called out:

| Lines | String | Detail worth preserving |
|---|---|---|
| L151 to L152 | `Delete this record ? Press F4 to confirm` | there is a **space before the question mark** |
| L153 to L154 | `Delete successful.` | terminal period present |
| L189 to L190 | `Delete of record failed` | **no terminating period** |
| L191 to L192 | `Delete was cancelled` | no terminal period |
| L1641 | `Please delete associated child records first:` | trailing colon retained, nothing appended after it |
| L1654 | `Delete failed with message:` | belongs to the generic branch, not to the refusal |

---

## 4. The labelled decision register

Rule 1 L40 makes each decision below mandatory to document, because for every
one a reasonable alternative demonstrably exists; Rule 1 L41 requires the
rationale to be specific, so each names the rejected alternative **and** the
concrete consequence of taking it. Every rationale cites a line number, a
declared width or a measured byte count.

Three of Rule 1's four canonical categories are used, written plural, in ASCII,
unemphasised and un-parenthesised, as
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md)
requires and charter section 2.2 restates. The fourth category is unavailable
here on its own terms: Rule 1 L32 scopes it to replacing existing code, and
nothing in this directory replaces anything -- these files are additive beside
the untouched COBOL baseline. Its precondition is never met, so it appears
nowhere below. Where a derived fixture departs from its seed or from a house
default, the correct category is `Alternatives Considered:`.

### 4.1 Line endings are normalised to LF only

Trade-offs: the rejected alternative is preserving each seed's original line
endings, which would be the more literal derivation. It is rejected because the
two seeds disagree with each other **and one disagrees with itself**, and the
disagreement carries no behavioural meaning. Measured directly:
`app/data/ASCII/trantype.txt` is **6 CR / 7 LF / 433 bytes**, which is CRLF on
rows 1 to 6 and a **bare LF on row 7**, so preserving origin would leave that
row framed one byte differently from every other row in its own file;
`app/data/ASCII/trancatg.txt` is **18 CR / 18 LF / 1116 bytes**, uniformly CRLF.
`tests/fixtures/README.md` **L168** makes LF the default for all new fixtures,
and its **L170 to L174** require any *intentional* CRLF preservation to be
documented in the scenario's own README. This scenario does not preserve CRLF,
so the **normalisation** is what is recorded here, and both files carry **zero**
carriage returns. The compromise accepted is that neither file is byte-identical
to its seed as a whole file, which is why section 5.5 publishes the LF-only
arithmetic so the divergence is predicted rather than discovered.

### 4.2 `FILLER` is ASCII zero, copied rather than synthesised

Alternatives Considered: applying the house generic padding rule to `FILLER` as
well as to values. `tests/fixtures/README.md` **L153** states that text `X`
fields are padded on the **right with spaces**, and that rule is correct for
values -- but `FILLER` carries no value, and applying it here **yields different
bytes**. Measured in both files, the `FILLER` bytes are ASCII `'0'` (`0x30`):
eight of them at offset 52 in `trantype.txt` and four at offset 56 in
`trancatg.txt`, and across every row of both files the distinct fill values are
exactly `3030303030303030` and `30303030`. Space-padding instead would give
eight spaces where the seed has eight zeros -- a different 60 bytes that passes
every length check and every line-ending check, and fails only much later as a
wrong value. The ruling, which charter section 5.3 states for the whole tree, is
that `FILLER` content is **copied byte for byte from the source row and never
synthesised from the padding rule**.

### 4.3 The Db2 control cards were rejected as a derivation source

Alternatives Considered: deriving the descriptions from
`app/app-transaction-type-db2/ctl/DB2LTTYP.ctl` and `DB2LTCAT.ctl`, which is
superficially the most attractive source available, because they are the
reference-data loader belonging to the very extension tree this screen comes
from. Rejected because they differ from the ASCII seed in **two** ways rather
than one, and both defects land on **exactly the rows this fixture carries**:

- everything in them is **UPPERCASE**;
- `DB2LTTYP.ctl` **L22** is the type `06` row, and it spells the description
  `'REVERAL'`;
- `DB2LTCAT.ctl` **L36** is the `06`/`0002` row, and it writes
  `'NON FRAUD REVERSAL'` with a **space** where the ASCII seed uses the ASCII
  hyphen-minus `0x2D`.

[`V2__seed_reference.sql`](../../../../../main/resources/db/migration/V2__seed_reference.sql)
deliberately seeds from `app/data/ASCII/` instead. The concrete consequence of
taking the rejected source is therefore twofold: a control-card-derived fixture
would carry uppercase descriptions plus a misspelling, so it would no longer
match the rows seeded into the schema, and it would violate AAP transformation
rule T8. Charter section 6.6 records the same rejection at tree scope.

### 4.4 The seed casing is inconsistent, and is carried across anyway

Assumptions: AAP transformation rule T8 requires user-visible strings verbatim,
and the seed's capitalisation is **data rather than an accident to be tidied**.
The inconsistency is real and visible across the wider seed population:
`Cash payment` lower-cases its second word while `Credit to Account`
capitalises it; `Regular Sales Draft` is title case while
`Zero dollar authorization` is not; and `Non-fraud reversal`, which is one of
the two category rows in this directory, carries an ASCII hyphen-minus rather
than a dash of any other kind. An author will instinctively normalise this. Rule
T8 forbids it, and the concrete consequence of normalising is silent: the record
length does not change, so nothing detects the edit until a comparison against
the seeded description fails for a reason that looks unrelated. Charter section
7.2 lists the full seeded population these three files draw from.

### 4.5 The 50-byte category-balance layout is not this record

Alternatives Considered: cross-checking these bytes against the Python parity
oracle at `tests/helpers/record_codec.py`, the way a `discgrp` fixture can be.
Rejected because **neither 60-byte layout is registered there**: its `LAYOUTS`
map at **L1349 to L1361** holds exactly eleven keys -- `ACCOUNT`, `DALYTRAN`,
`DISGROUP`, `XREF`, `TCATBAL`, `CARD`, `CUSTOMER`, `TRAN`, `TRNX`, `REJECT` and
`INTTRAN` -- with **no `TRANTYPE` key and no `TRANCAT` key**, so both files here
were derived by hand from the copybook widths.

**The trap is that searching that file for `TRANCAT` does return hits.** All
four of them -- **L1153, L1163, L1164 and L1165** -- belong to `TCATBAL_LAYOUT`,
declared at **L1157** with `name="TCATBAL"` and **`reclen=50`**, derived from
`CVTRA01Y`, and carrying an 11-digit account id at offset 0. That is the
category **balance** record, a different record from `CVTRA04Y`'s 60-byte
category **type** record. The concrete consequence of trusting those hits is
that a 50-byte geometry with an 11-byte leading key gets applied to a 60-byte
record with a 2-byte leading key, which **corrupts every field past offset 6**.
The name collision runs deeper than the search: charter section 3.3 records that
`TRAN-CAT-KEY` brackets 6 bytes in `CVTRA04Y` and 17 bytes in the balance
record, so a lookup by field name alone silently reads the wrong width.

For the same reason the house derivation table at `tests/fixtures/README.md`
**L666 to L680** names a copybook and a record length for every seed row
**except** these two, which read only "transaction type reference" and
"transaction category reference". The Java side does cover them --
`com.carddemo.common.codec.CopybookLayout` registers `TRANTYPE` at 60 bytes with
a 2-byte key at offset 0 and `TRANCAT` at 60 bytes with a 6-byte key at offset 0
-- so the geometry is verifiable; it is only the Python cross-check that is
absent.

### 4.6 No `.sql` file belongs in this directory

Assumptions: the `ON DELETE RESTRICT` constraint this scenario exercises is
owned **solely** by `V1__reference.sql` **L266 to L268**, and
[`application-test.yml`](../../../application-test.yml) sets
`spring.jpa.hibernate.ddl-auto: validate` at its **L182** while enabling Flyway
from `classpath:db/migration` at its **L214** to **L228**. The concrete
consequence of adding a duplicate definition here is that a second, divergent
declaration of the same table would be validated against the one Flyway
actually created, so the `validate` check would fail and take every
container-backed integration test in the module with it. Charter section 14
states the same prohibition for the whole tree, alongside the rest of the
do-not-create list this directory observes: no `.java`, no copy or extract of
`data-migration/sql/V0__schemas_and_roles.sql`, no `.env` or credential of any
kind, no `.gitignore`, no EBCDIC `.PS`, and no golden-master, `.expected` file
or `tests/golden` mirror.

### 4.7 An authored type row was unavoidable

Assumptions: the seed **cannot supply an unreferenced type**, so the
clean-delete branch would be unreachable from seed rows alone. Measured across
`app/data/ASCII/trancatg.txt`, the per-type category counts are `01`=5, `02`=3,
`03`=3, `04`=3, `05`=1, `06`=2 and `07`=1, summing to **18**; and every one of
those 18 type codes is one of the seven declared in
`app/data/ASCII/trantype.txt`, so **no category is orphaned and every seeded
type is referenced by at least one category**. Charter section 7.2 records the
same set comparison. A fixture built only from seed rows would therefore reach
`WHEN SQLCODE = -532` for any type it named and would never reach
`WHEN SQLCODE = ZERO`, leaving the permitted half of the delete contract
untested. One type row with no category rows had to be added, using a code
clearly distinguishable from the seven seeded values.

### 4.8 The authored code is `99`

Assumptions: `V2__seed_reference.sql` seeds all seven type rows at its **L112 to
L119** and closes that statement with `ON CONFLICT (type_cd) DO NOTHING` at
**L139**, in FK-satisfying order -- types at L112 before categories at L181,
whose own statement closes with `ON CONFLICT (type_cd, cat_cd) DO NOTHING` at
**L201**. Because `application-test.yml` runs both migrations against a bare
container, a colliding code would be **silently absorbed by that conflict
clause**: the insert would report success, the pre-existing seeded row would
survive unchanged, and the clean-delete branch would then exercise a row this
fixture did not author. `99` avoids that outright. It is `CHAR(2)`-valid, it
stays inside the digit domain the seeded population uses, and it is
unmistakably out of band against `01` through `07` -- and against the `08` that
the sibling add-path fixtures introduce, so no category can come to reference it
through a later fixture edit either. Its description,
`Unreferenced type for delete fixture`, states what the row is for, and it is
the only description in this directory that is not seed-derived.

### 4.9 Both of type `06`'s category rows are carried

Assumptions: the second category row is not padding. Two distinct `cat_cd`
values exercise the composite primary key `(type_cd CHAR(2), cat_cd CHAR(4))` at
`V1__reference.sql` **L234** and the `(type_cd ASC, cat_cd ASC)` ordering
inherited from `XTRNTYCAT.ddl` **L3**, which one row cannot. `06` is also the
only type in the seed carrying more than one category, so it is the only
available code for which a refusal on *any* reference can be distinguished from
an implementation that happens to check only the first. And `Non-fraud reversal`
is precisely the row whose ASCII hyphen-minus `0x2D` differs from the rejected
control card's space at `DB2LTCAT.ctl` **L36**, so the fixture itself carries
the evidence for the derivation-source decision in section 4.3 rather than
merely asserting it.

Alternatives Considered: copying the full 18-record category extract here, all
1098 bytes of it, so that this directory mirrored the sibling `happy_path`.
Rejected because those 18 records name all 7 seeded types, which would make
every one of them refusable and would leave `99` as the only permitted delete
for a reason no longer specific to this scenario. 2 records under one type is
the smallest set that makes exactly one delete refusable and exactly one
permitted, and both consumers assert that the referring set is **exactly** `06`,
so a later widening of this file fails a test rather than quietly generalising
the scenario.

---

## 5. Fixture bytes

This section is the Parameters element of Rule 1 L19: the bytes, and per field
its offset, `PICTURE`, fill character and copybook line.

**Every offset in this section is 0-based: the first byte of a record is at
offset 0.** The base is declared explicitly because it cannot be inferred and
both conventions are genuinely in use in this repository -- IDCAMS
`KEYS(length offset)` operands are 0-based while DFSORT `SYMNAMES` positions are
1-based -- so leaving it implicit is a guaranteed one-byte error on every field
rather than a stylistic risk. Charter section 3.1 declares the same base for the
whole tree and tables the two conventions side by side.

### 5.1 The rows, exactly as they are

`trantype.txt` -- 2 records, **122 bytes**:

```text
06Reversal                                          00000000
99Unreferenced type for delete fixture              00000000
```

| Row | `TRAN-TYPE` | `TRAN-TYPE-DESC` | Provenance | Delete outcome |
|---|---|---|---|---|
| 1 | `06` | `Reversal` | **copied byte for byte** from `app/data/ASCII/trantype.txt` | **refused** -- both rows of `trancatg.txt` name it, so the `-532` branch applies |
| 2 | `99` | `Unreferenced type for delete fixture` | **authored** (sections 4.7 and 4.8) | **succeeds cleanly** -- no category row names it, so the `SQLCODE = ZERO` branch applies |

`trancatg.txt` -- 2 records, **122 bytes**:

```text
060001Fraud reversal                                    0000
060002Non-fraud reversal                                0000
```

| Row | `TRAN-TYPE-CD` | `TRAN-CAT-CD` | `TRAN-CAT-TYPE-DESC` | Provenance |
|---|---|---|---|---|
| 1 | `06` | `0001` | `Fraud reversal` | **copied byte for byte** from `app/data/ASCII/trancatg.txt` |
| 2 | `06` | `0002` | `Non-fraud reversal` | **copied byte for byte** from the same seed |

Three of the four records are byte-identical to their seed rows; only the `99`
row is authored. Both files name **exactly one** type code between them on the
referencing side, `06`, which is the property both consumers assert.

### 5.2 `TRAN-TYPE-RECORD` -- 60 bytes

Source: `app/cpy/CVTRA03Y.cpy`. The banner at **L2** declares `RECLN = 60`;
**L4** declares `01 TRAN-TYPE-RECORD.`.

| Field | Copybook line | `PICTURE` | Offset | Length | Fill |
|---|---|---|---:|---:|---|
| `TRAN-TYPE` | L5 | `X(02)` | 0 | 2 | right-pad space; both codes here fill the field exactly, so no pad byte occurs |
| `TRAN-TYPE-DESC` | L6 | `X(50)` | 2 | 50 | right-pad space, `0x20` |
| `FILLER` | L7 | `X(08)` | 52 | 8 | eight ASCII `'0'`, `0x30`, copied verbatim (section 4.2) |

Byte-count check: 2 + 50 + 8 = **60**. Every field is `PIC X(n)`, so no field in
this record carries a sign and none is overpunched. Under AAP transformation rule
T1 the `FILLER` is dropped in the target: **8 bytes dropped**, and no column
corresponds to it in `V1__reference.sql`.

### 5.3 `TRAN-CAT-RECORD` -- 60 bytes

Source: `app/cpy/CVTRA04Y.cpy`. The banner at **L2** declares `RECLN = 60`;
**L4** declares `01 TRAN-CAT-RECORD.`; and **L5 declares
`05 TRAN-CAT-KEY.`, a group item that occupies no bytes of its own** -- its two
subordinate level-10 fields account for all six bytes of the key.

| Field | Copybook line | Level | `PICTURE` | Offset | Length | Fill |
|---|---|---|---|---:|---:|---|
| `TRAN-TYPE-CD` | L6 | 10 | `X(02)` | 0 | 2 | right-pad space; `06` fills the field exactly |
| `TRAN-CAT-CD` | L7 | 10 | `9(04)` | 2 | 4 | plain ASCII digits, left-pad `'0'` |
| `TRAN-CAT-TYPE-DESC` | L8 | 05 | `X(50)` | 6 | 50 | right-pad space, `0x20` |
| `FILLER` | L9 | 05 | `X(04)` | 56 | 4 | four ASCII `'0'`, `0x30`, copied verbatim |

Byte-count check: 2 + 4 + 50 + 4 = **60**. Under rule T1 the `FILLER` is
dropped: **4 bytes dropped**, again with no corresponding column.

Assumptions: **`TRAN-CAT-CD` is unsigned display -- plain ASCII digits with no
sign overpunch.** Its target column is `CHAR(4)` rather than an integer type
(`V1__reference.sql` L180 to L204), so the literal `0001` form with its leading
zeros survives end to end. Applying overpunch decoding to it would read the
trailing digit as a sign character and produce a value wrong by orders of
magnitude, and the error would be silent; charter section 5.4 lists the unsigned
fields in this tree for exactly that reason.

### 5.4 What these layouts do not contain

Stated positively, because silence here is indistinguishable from oversight:

- **Neither layout contains a money field**, so no zoned-decimal sign overpunch,
  no implied decimal point and no fixed-point handling arises anywhere in this
  directory. Every field is either `PIC X(n)` or one unsigned `PIC 9(04)`.
- **Neither layout carries a primary account number or any identity-shaped
  data.** `TRAN-TYPE-RECORD` is a 2-byte code plus a description;
  `TRAN-CAT-RECORD` is a type plus a category plus a description. The house
  synthetic-data attestation obligation is conditional on a folder holding PAN or
  identity-shaped data, so **no attestation is owed by this directory**, and its
  absence is deliberate rather than an omission. Charter section 8.6 records the
  same scoping for the whole `reference_update` domain.

Assumptions: the **60-byte record regime is new to this repository**. The house
record-length enumeration at `tests/fixtures/README.md` **L141** reads
"(350, 300, 150, 50, 500, or 80 bytes)" and **60 is not among them**; every
`.txt` under `tests/fixtures` measures 50, 150, 300, 350 or 500. An author
arriving from the house tree must not assume any tooling has seen a 60-byte
record before -- section 4.5 gives the cross-checks that do exist.

### 5.5 Acceptance arithmetic

Because each file is LF only with exactly one trailing newline, its size is
fully determined by `bytes = rows x 60 + rows`:

| File | Arithmetic | Bytes | Records | CR bytes |
|---|---|---:|---:|---:|
| `trantype.txt` | `2 x 60 + 2` | **122** | 2 | 0 |
| `trancatg.txt` | `2 x 60 + 2` | **122** | 2 | 0 |

Both figures are measured, not predicted. For scale, the full seeds would come
to 427 bytes for `trantype` (7 rows) and 1098 for `trancatg` (18 rows) once
normalised to LF; **a subset is legitimate** where the assertion is against the
fixture rather than against the seeded schema, and the arithmetic stated must
match the rows actually present. Charter section 5.7 owns the general rule and
its verified vectors.

---

## 6. Expected outcome

This section is the Return-values element of Rule 1 L20: the specific status and
value, not a description of one.

### 6.1 The two asserted outcomes

| Request | Status | Effect on the data | Response body |
|---|---|---|---|
| delete transaction type `06` | **HTTP 409** | **nothing is deleted** -- the `transaction_types` row for `06` **survives**, and so do both of its `transaction_categories` rows | carries `Please delete associated child records first:` verbatim, and **no constraint name, no table name, no schema name and no vendor error code** |
| delete transaction type `99` | success | the `transaction_types` row for `99` is **gone**, and the unit of work commits | -- |

Two properties of that table are load-bearing rather than incidental:

- **The refused delete leaves the row in place.** A 409 whose row had also been
  removed would satisfy a status assertion while contradicting the constraint,
  so the surviving row is asserted as well as the status.
- **The 409 stays distinguishable from a generic delete failure.** Section 3.3
  gives the baseline basis (one flag versus two) and the target contract
  (`GlobalExceptionHandler` L1005: integrity violation gives 409, otherwise the
  same HTTP 500 shape). A 500 for type `06` fails this scenario even though a
  delete was also prevented.

### 6.2 Who reads these bytes, and what they assert against them

Two classes currently read **both** files in this directory, and both run under
Surefire:

- `com.carddemo.reference.fixtures.ReferenceFixtureContractTest` enrols each file
  by its classpath path in a closed geometry inventory that asserts the 60-byte
  record width and the record count of 2, resolving each name with
  `getResourceAsStream` and raising rather than skipping if one is absent. It
  then asserts from these two files alone that the first type code is referred
  to, that the second is referred to by nothing, and that the referring set is
  **exactly** the first code.
- `com.carddemo.reference.fixtures.ReferenceFixtureTest` enrols both files in its
  geometry and keyed-fixture inventories, decodes them through
  `CopybookLayout.layout("TRANTYPE")` and `layout("TRANCAT")`, and makes the same
  referencing assertion from this directory's own bytes: row 1 is `06` and is
  present in the referencing set, row 2 is `99` and is absent from it.

**Availability status of the other consumers, verified against the branch rather
than assumed:** these fixtures are also intended for the reference-service
controller, service and repository tests under
`services/reference-service/src/test/java`, and **no `*ControllerTest`,
`*ServiceTest` or `*RepositoryIT` exists in that tree yet**. Whichever such test
lands is the one that will assert the 409-and-row-survives outcome for `06` and
the clean committed delete for `99`; that outcome is stated in section 6.1 as the
contract it is to be written against, not as behaviour already exercised.
Charter section 9.1 names the same consumers under the same status, and its
section 10 records which of them are present.

Fixtures reach every consumer from the **test classpath**, not by filesystem
path: Maven copies `src/test/resources/` into `target/test-classes/`, so each
file resolves as
`fixtures/reference_update/delete_restricted_by_category/<file>.txt`.

**The bytes are the contract.** A consumer's expected value is whatever these
files encode, so a fixture that is wrong does not fail -- it produces a green
test that proves nothing. A disagreement between a consumer and these bytes is
resolved by reading the bytes, never by editing them to suit the consumer.

---

## 7. Determinism, encoding, and the failure modes these bytes can produce

Per file: record width **60**, record count **2**, byte count **122**, line
ending **LF only** with **zero** carriage returns, **exactly one** trailing
newline, and **no blank lines** anywhere.

Assumptions: the failure modes below are those a consumer of *these* bytes can
hit, which is the Rule 1 L21 exceptions element applied to a data file rather
than to a function. The dangerous ones are the silent ones.

| Failure | Symptom it actually produces |
|---|---|
| A record is one byte short or long | Every field after the miscount **shifts**, so the record silently decodes into wrong values unless the length is checked. It is checked: the loaders reject any row whose length is not exactly 60, and `FixedWidthCodec` raises `RecordLengthException`. **Loud** -- this is the good case |
| A blank line is added between or after the records | A blank line is a **zero-length record**, not an absence of one. It fails fixed-width parsing rather than being skipped |
| A file is emptied by deleting its rows but leaving a newline | That is a 1-byte file holding one zero-length record. **Only a genuinely zero-byte file counts as empty** (`tests/fixtures/README.md` L148), and neither file here is or should be empty |
| A carriage return survives an edit | The record becomes 61 bytes, one over its length, and is rejected at load. **Loud**, and section 4.1 is what prevents it |
| `FILLER` rewritten as spaces instead of the copied `'0'` | Correct length, correct line endings, **wrong bytes**. **Silent** until an assertion on a later field or a comparison against the seeded row fails for an unrelated-looking reason |
| A description tidied for consistent capitalisation | No length change at all, so nothing detects it. **Silent**, and forbidden by rule T8 (section 4.4) |
| The 50-byte category-balance geometry applied to `trancatg.txt` | Every field past offset 6 is misread. **Silent** (section 4.5) |
| Overpunch decoding applied to `TRAN-CAT-CD` | A digit is read as a sign and the value is wrong by orders of magnitude. **Silent** (section 5.3) |
| A third type code added to `trancatg.txt` | That type's delete becomes refusable too, so the fixture stops isolating exactly one refused delete and one permitted one. Both consumers assert the referring set is exactly `06`, so this one **fails a test** rather than going unnoticed |

The loaders' refusal to pad, truncate or silently drop is deliberate: the house
calls it a financial-integrity stance at `tests/fixtures/README.md` L150 to
L151, on the ground that a malformed record must never be coerced into a
well-formed-looking one. **Defer to the tree-scope charter, sections 5.1 through
5.7, for the full byte contract**; it is single-sourced there and is deliberately
not reproduced here.

---

## 8. Derivation and seed policy

Three of the four records are byte-identical copies of rows from
`app/data/ASCII/trantype.txt` and `app/data/ASCII/trancatg.txt`, reshaped only by
the LF normalisation of section 4.1; the fourth, the `99` type row, is authored
for the reason in section 4.7.

**The seeds are inputs to derivation only and are never modified**
(`tests/fixtures/README.md` L686 to L689): the rows needed are copied into the
fixture and reshaped there, never edited in place. That is the house
minimal-change principle, and it applies with full force -- `app/**`, `tests/**`,
`scripts/**` and `samples/**` are REFERENCE-ONLY, and the COBOL baseline, the
copybooks and the parity-oracle suite all keep running exactly as they do today.

Load order is **types before categories**, matching the FK-satisfying order
`V2__seed_reference.sql` uses at its L112 and L181. Loading categories first
would violate the foreign key on insert, which is the same constraint this
scenario exercises on delete.

Test-side derivation follows the same import discipline the migration applies to
code (AAP section 0.5.3.4): a test consumes fixtures from its **own** module's
test resources, and the geometry it decodes against comes from
`CopybookLayout` in `common-lib` rather than from an offset copied out of the
tables in section 5.

---

## 9. The similarly-named sibling, and what is independent of what

**`batch_reference_update` is a different flow, and the shared name prefix is
not redundancy.** That domain covers `COBTUPDT`, a sequential batch reader whose
input is a completely different **53-byte** record: `WS-INPUT-REC`, declared at
`app/app-transaction-type-db2/cbl/COBTUPDT.cbl` **L71 to L77** as a 1-byte action
type, a 2-byte number and a 50-byte description, summing to 53 with no `FILLER`
at all. **This** directory covers the online screen over the two **60-byte**
reference records. Neither domain may be folded into the other, and charter
section 8.3 records the distinction.

**The sibling `reference_update/happy_path` is independent of this directory.**
Both derive from the same two source seeds plus the same copybook widths, and
neither includes nor requires the other: that scenario carries 8 type records
(488 bytes) and 18 category records (1098 bytes), where this one carries 2 and 2.
This directory's category file is a **subset** of the eighteen-row extract rather
than a second copy of it, so it cannot contradict that extract on any row it does
not carry, and the one disagreement it could still hold -- a reference added or
removed -- is asserted against directly by both consumers.

---

<sub>Apache-2.0 - This fixture directory is additive and test-only. It holds
exactly three files: `trantype.txt`, `trancatg.txt` and this `README.md`, with no
subfolder. Production `app/**` is never modified. See the tree-scope
[`fixtures/README.md`](../../README.md) for the derivation contract this scenario
inherits.</sub>
