# `reference_update/happy_path` -- the clean edit and the clean add of a transaction type

## 0. Why this README exists

Two independent authorities require this file, and both are discharged by the one
document.

**User-specified Rule 1 (Explainability), L27** requires a comment to sit adjacent
to the thing it explains. A record file in this directory **cannot carry a comment
of any kind**: every byte position is meaningful, so a `#` would be data, and a
comment line would be a physical row that is not 60 bytes. The loaders reject an
off-length row outright -- they do not pad it, do not truncate it and do not
silently drop it -- which the tree charter records at its section 5.2 from
[`tests/fixtures/README.md`](../../../../../../../../tests/fixtures/README.md)
L144 to L151, where it is stated as verified enforcement and as a deliberate
financial-integrity stance rather than as guidance. A colocated README is
therefore the only carrier available, and its colocation in this directory is what
satisfies "adjacent". Rule 1 is AAP section 0.8.1.

**The house mandate at `tests/fixtures/README.md` section 9.1 (L695 to L719)** is
worded `MUST`, not "should": every scenario subfolder carries a README, described
there as a mandatory Explainability carrier rather than a suggestion. Its own note
at L714 to L719 records that an earlier revision weakened the wording to "should"
and that the weaker wording let scenario directories ship with no Explainability
carrier at all.

One caution about that same house passage: its L711 attributes the review gate to
an AAP section number that, in this project's AAP, is the attachments section. The
governing citations here are **AAP section 0.8.1** for Rule 1 and **AAP sections
0.2.2 and 0.9.2** for the REFERENCE-only status of everything under `app/**`.

The four contents house section 9.1 requires map onto Rule 1's four docstring
elements, and the tree charter tabulates that mapping at its section 8.4. This
file follows it: section 1 and section 2 are Purpose, section 4 is Parameters,
section 3 is Return values, and section 5 is Exceptions.

The byte-level contract for this whole tree is
[`../../README.md`](../../README.md). It is **cited by section below and not
restated**; where the two could be read as disagreeing, that document governs.

---

## 1. Scenario intent

The two **non-refusing** maintenance paths of the online transaction-type
maintenance screen
[`COTRTUPC`](../../../../../../../../app/app-transaction-type-db2/cbl/COTRTUPC.cbl):

- a **clean edit**, where the submitted type code matches a row that the schema
  was seeded with, and
- a **clean add**, where the submitted type code matches nothing and a row is
  created.

It is the counterpart to
[`../delete_restricted_by_category`](../delete_restricted_by_category/README.md),
which holds the path the restricting foreign key refuses.

---

## 2. The exact business rule it exercises

### 2.1 The branch, statement by statement

`9600-WRITE-PROCESSING` spans **L1531 to L1594**. It moves the trimmed
description into the host variable at **L1539 to L1540**, issues one `UPDATE`
against `CARDDEMO.TRANSACTION_TYPE` at **L1544 to L1548**, and then branches on
`SQLCODE`:

| `SQLCODE` | Line | Arm | What happens |
|---|---|---|---|
| `0` | **L1556 to L1557** | clean **edit** | The `UPDATE` matched a row, and `EXEC CICS SYNCPOINT` commits it |
| `+100` | **L1558 to L1560** | clean **add** | The `UPDATE` matched nothing, so control falls into `9700-INSERT-RECORD` (**L1597 to L1623**), which inserts the row and commits it at **L1605 to L1606** |

Both arms converge. Neither sets a lock error, an update failure or a
data-changed condition, so the final `EVALUATE` falls to its `WHEN OTHER` and sets
`TTUP-CHANGES-OKAYED-AND-DONE` at **L1588**. The message selector at **L1237 to
L1240** turns that state into `CONFIRM-UPDATE-SUCCESS` at **L1239 to L1240**.

The screen reaches the branch by function key. `PF05` on a record that was not
found enters the add path at **L503 to L508**; `PF05` once submitted changes have
passed validation performs the save at **L514 to L520**.

### 2.2 The one verbatim message both arms reach

`CONFIRM-UPDATE-SUCCESS` is declared at **L162 to L163** with the value

```text
Changes committed to database
```

reproduced here character for character under **AAP Rule T8** (user-visible
strings are verbatim). The pre-save prompt that precedes it is declared at **L160
to L161** as `Changes validated.Press F5 to save`, which carries **no space after
the full stop**; it is quoted only in that exact form.

The target side agrees rather than paraphrasing: the published contract
[`reference-api.yaml`](../../../../../main/resources/openapi/reference-api.yaml)
carries the message as the `changesCommitted` constant at **L4504 to L4506**, and
that constant's own description cites `COTRTUPC` line 163 as its origin. The
prompt is carried the same way at **L4497 to L4499**, where the description
records the missing space explicitly.

### 2.3 What this scenario is not

`COTRTUPC` also deletes, and its delete is a **two-step F4 confirmation** in the
region spanning **L480 to L495**: a first `PF04` on a shown record sets
confirm-delete at **L493 to L498**, and a second `PF04` performs the delete at
**L482 to L489**. Neither step is exercised here. The **refused** delete is the
sibling scenario's subject, not this one's.

---

## 3. Expected outcome

Stated as a specific status **and** a specific value, because house section 9.1
item 3 asks for the asserted value rather than a description of one.

### 3.1 The clean edit

**HTTP 200**, the message `Changes committed to database`, and the addressed row's
`description` column in `reference.transaction_types` holding the **submitted**
value rather than the seeded one. The published contract's replace operation
(summary at **L523**) answers **200** at **L549**.

For that arm to be observable rather than vacuous, the description submitted for
an existing code **must differ from the seeded description**. If it does not, the
stored value is identical whichever arm ran, and an assertion on it passes without
establishing that the `UPDATE` matched -- the failure mode the tree charter's
section 12 calls vacuous, and the worst kind, because it reads as coverage.

### 3.2 The clean add

**HTTP 201** and a new row for code `08`. The contract answers **201** at **L439**
and requires a `Location` header, whose published example at **L455** is
`/api/v1/reference/transaction-types/08` -- the same code row 8 of `trantype.txt`
carries.

One divergence is recorded rather than smoothed over. The baseline reaches a single
success state for both arms and therefore shows one message for both; the target
distinguishes them by status, answering 201 with a `Location` header and the stored
representation for a create and 200 with `changesCommitted` for a replace. The
baseline behaviour is unchanged, the target behaviour is as the contract states,
and the difference is written down here.

### 3.3 What neither path reaches

Neither arm touches the restricting foreign key. A **refused delete** raises
PostgreSQL SQLSTATE **23503**, which Spring surfaces as
`DataIntegrityViolationException`, which
`com.carddemo.common.error.GlobalExceptionHandler` maps to **HTTP 409** -- the
chain the tree charter tabulates at its section 7.4. That outcome belongs to
[`../delete_restricted_by_category`](../delete_restricted_by_category/README.md).
It is named here only so that a reader who arrives looking for it is sent one
directory sideways instead of concluding it is undocumented.

---

## 4. Fixture bytes and governance

### 4.1 Per-file record width, record count and line ending

| File | Record | Width | Records | Bytes | Line ending |
|---|---|---:|---:|---:|---|
| `trantype.txt` | `TRAN-TYPE-RECORD` | 60 | 8 | 488 | LF, one trailing newline, zero CR |
| `trancatg.txt` | `TRAN-CAT-RECORD` | 60 | 18 | 1098 | LF, one trailing newline, zero CR |

Both byte counts follow the charter's section 5.7 acceptance arithmetic,
`bytes = rows x RECLN + rows`:

```text
trantype.txt   8 x 60 + 8  =  488
trancatg.txt  18 x 60 + 18 = 1098
```

The trailing newline is what makes `wc -l` equal the record count, so the row
count is checkable without decoding anything (charter section 5.6).

### 4.2 `trantype.txt` fields

Layout source
[`app/cpy/CVTRA03Y.cpy`](../../../../../../../../app/cpy/CVTRA03Y.cpy). Its banner
comment at **L2** declares `RECLN = 60` and **L4** declares
`01 TRAN-TYPE-RECORD.`. **Offsets are 0-based**, as the charter declares once at
its section 3.1.

| Field | Copybook line | `PICTURE` | Offset | Length | Fill |
|---|---|---|---:|---:|---|
| `TRAN-TYPE` | L5 | `X(02)` | 0 | 2 | right-pad space by regime; all 8 rows hold two ASCII digits and need none |
| `TRAN-TYPE-DESC` | L6 | `X(50)` | 2 | 50 | right-pad space, used -- `Purchase` is followed by 42 spaces |
| `FILLER` | L7 | `X(08)` | 52 | 8 | eight ASCII `'0'`, copied byte for byte |

Byte-count check: `2 + 50 + 8 = 60`.

### 4.3 `trancatg.txt` fields

Layout source
[`app/cpy/CVTRA04Y.cpy`](../../../../../../../../app/cpy/CVTRA04Y.cpy). Its banner
comment at **L2** declares `RECLN = 60`, **L4** declares `01 TRAN-CAT-RECORD.`,
and **L5** declares `05 TRAN-CAT-KEY.` -- a group that brackets the two key fields
and occupies no bytes of its own.

| Field | Copybook line | Level | `PICTURE` | Offset | Length | Fill |
|---|---|---:|---|---:|---:|---|
| `TRAN-CAT-KEY` | L5 | 05 | group | 0 | 0 | brackets the two fields below; contributes no bytes itself |
| `TRAN-TYPE-CD` | L6 | 10 | `X(02)` | 0 | 2 | right-pad space by regime; all 18 rows hold two ASCII digits |
| `TRAN-CAT-CD` | L7 | 10 | `9(04)` | 2 | 4 | left-pad `'0'` by regime; **unsigned, no overpunch** |
| `TRAN-CAT-TYPE-DESC` | L8 | 05 | `X(50)` | 6 | 50 | right-pad space, used |
| `FILLER` | L9 | 05 | `X(04)` | 56 | 4 | four ASCII `'0'`, copied byte for byte |

Byte-count check: `2 + 4 + 50 + 4 = 60`.

These two tables **cite** a copybook line for every field; they are not a competing
declaration of the geometry. Where a table and the copybook could be read as
disagreeing, the copybook governs, which is what **AAP Rule T1** (the copybook is
normative) means in practice. They are reproduced here rather than replaced by a
bare cross-reference for the reason the charter gives at its section 11.2: the
Python parity oracle registers neither layout, so a reader checking these bytes has
no second machine-readable source to fall back on within that oracle. Consuming
**code** is under the opposite instruction -- charter section 5.8 -- and obtains
geometry from `com.carddemo.common.codec.CopybookLayout` rather than from any table.

### 4.4 What the target drops, and what it never carries

- **`FILLER` is dropped, and AAP Rule T1 requires the drop be recorded per record:
  8 bytes** from `TRAN-TYPE-RECORD` and **4 bytes** from `TRAN-CAT-RECORD`. The
  target tables have no column for either, because the bytes pad the record to its
  declared length and carry no value.
- **Trailing blanks in a description are padding, not data.** The field is 50 bytes
  wide on the wire and its target column is `description VARCHAR(50)`, so the
  padding does not survive. The baseline itself takes that position: `COTRTUPC`
  applies `FUNCTION TRIM` to the description at **L1539 to L1540** before binding
  it to the host variable, so the padding is already gone before the SQL sees it.
- **Neither layout carries a money field.** Every field in both records is `X(n)`
  or unsigned `9(n)`, so **no zoned-decimal sign overpunch arises anywhere in this
  directory**. This is stated positively so that a reader does not go looking for
  an overpunch table that would have nothing to describe. `TRAN-CAT-CD` is the
  field most likely to be mistaken for a signed one: it is unsigned display, and
  its target column is `cat_cd CHAR(4)`, a character type, so the literal `0001`
  form keeps its leading zeros end to end.
- **The keys are the two the schema declares.** `reference.transaction_types` keys
  on `type_cd`, and `reference.transaction_categories` keys on the composite
  `(type_cd, cat_cd)` in that order, which is the order `TRAN-CAT-KEY` brackets the
  two fields in. No file here repeats a key.

---

## 5. Failure modes these bytes can produce

The Exceptions element of Rule 1 L21, applied to a fixture rather than to a
function. Each row pairs a mistake with the symptom it actually produces, because
the dangerous ones are the quiet ones. The charter's section 12 holds the
tree-wide list; the five below are the ones reachable from *these* two files.

| Mistake | Symptom |
|---|---|
| A row authored one byte short or long | Every field after the error shifts by that byte. Loud: the row is rejected at load and `FixedWidthCodec` raises `RecordLengthException`, so the mistake surfaces instead of decoding into plausible wrong values |
| A carriage return survives an edit | The record becomes 61 bytes and is rejected. Loud, and the reason section 7 derives both files to LF |
| A blank line used to stand for an absent record | An empty line **is** a zero-length record, not the absence of one, so the file fails parsing rather than reading short. **Only a genuinely zero-byte file is empty** -- neither file here is, at 488 and 1098 bytes -- so a truncation to zero would read as the `empty_input` scenario rather than as a loss |
| `FILLER` synthesised as spaces instead of copied `'0'` | Correct length, correct line endings, wrong bytes. Silent until an assertion on the filler or a comparison against the seed fails for an unrelated-looking reason |
| A description tidied for consistent capitalisation | No length change at all, so nothing detects it until a comparison against the seeded description fails. Silent, and forbidden by AAP Rule T8 |

---

## 6. Consumers, and their availability

Stated as availability status rather than as a promise, following the charter's
section 10, which was itself verified against the branch rather than planned.

**Reading these two files, and asserting against them:** the two classes in
`com.carddemo.reference.fixtures` under
`services/reference-service/src/test/java`. `ReferenceFixtureContractTest`
resolves both files from the test classpath as
`fixtures/reference_update/happy_path/<file>.txt` and asserts the geometry section
4 states -- 8 records and 18 records at 60 bytes -- together with the authored
value in row 8 of `trantype.txt` and the fact that a `'0'`-filled `FILLER` decodes
as content rather than being dropped as blank. `ReferenceFixtureTest` decodes both
through the registered layouts and asserts the cross-file claims, including that
every type code in `trancatg.txt` names a type declared in `trantype.txt`.

**A contract for when they land:** the `*ServiceTest`, `*ControllerTest` and
`*RepositoryIT` classes that the charter's section 9.1 names as this tree's
consumers. **None of those three kinds is present in this module.** They are named
so that whoever authors them finds the expected outcome of section 3 already
stated, rather than inferring it from the bytes.

**A neighbouring scenario depends on one of these files.**
`../delete_restricted_by_category` carries only its two type rows and deliberately
carries no category rows, relying on `trancatg.txt` here to supply the referencing
evidence its own README describes: type `06` is referenced by two category rows in
this file, and the unreferenced type it pairs against `06` is referenced by none.
Removing or renumbering a category row here therefore changes what that sibling
scenario proves.

**No golden master.** No `.expected` file, no golden-master output and no
`tests/golden` mirror exists for this tree, and none is to be created -- charter
section 6.7, which records that the mirroring rule is conditioned on a
path-pairing comparator this tree does not use.

The direction of the contract is the charter's section 9.2: **the bytes are the
contract.** A consumer's expected value is whatever these files encode, so a
fixture that is wrong does not fail -- it produces a green test that proves
nothing. Disagreements are settled by reading the bytes, not by editing them.

---

## 7. Why these bytes and not others

Each decision below had a reasonable alternative, so Rule 1 L40 makes it
mandatory to document, and Rule 1 L41 makes a specific consequence mandatory in
each rationale. Labels are plural, unparenthesised, colon-retained and carry no
emphasis markup, per
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md)
and the charter's section 2.2. The fourth category the rule names is unavailable
here on the merits: its own precondition is the replacement of existing code, and
nothing in this directory replaces anything.

- Assumptions: **the seeded starting state is what selects the arm.**
  `V2__seed_reference.sql` inserts exactly the seven rows `01` through `07` into
  `reference.transaction_types` before a test runs, with the descriptions the ASCII
  seed carries. So a submitted code among `01` to `07` makes the `UPDATE` at L1544
  to L1548 match and takes the edit arm, and a submitted code outside that range
  makes it miss and takes the add arm. **Row 8 of `trantype.txt` carries the
  invented code `08` for exactly that reason**: the seed's highest code is `07`, so
  without an authored row this tree could not express a code that is free, and the
  add arm's outcome would be describable only in prose. Its description contains
  the word `Fixture`, which appears in none of the 25 seed descriptions, so a
  reader can tell the one authored row from the seven derived ones without
  consulting the seed. Rows 1 to 7 are byte-for-byte seed content and are the
  pre-edit image the edit arm changes.

- Assumptions: **these files are not the loading mechanism for the database.**
  Flyway's `V1__reference.sql` creates the schema and `V2__seed_reference.sql`
  seeds it; these two files are the record vectors a test decodes and compares.
  Reading them as the seed loader would lead an author to expect row 8 to appear in
  `reference.transaction_types` before any request is made, and to write an
  assertion that fails for a reason that has nothing to do with the code under
  test.

- Assumptions: **the inconsistent capitalisation in the seed descriptions is
  data.** `Cash payment` lowercases its second word while `Credit to Account`
  capitalises its last; `Regular Sales Draft` is title case while `Zero dollar
  authorization` is not; `Non-fraud reversal` carries an ASCII hyphen-minus.
  AAP Rule T8 forbids tidying any of it. An author will instinctively try, and the
  consequence is the silent failure in section 5: the length does not change, so
  nothing detects the edit until a comparison against the seeded description fails.
  The charter's section 7.2 lists the inconsistencies in full.

- Trade-offs: **both files are derived to LF, so neither is a byte copy of its
  seed.** The two seeds do not even agree with each other:
  `app/data/ASCII/trantype.txt` measures 6 CR against 7 LF in 433 bytes, being CRLF
  on rows 1 to 6 with a **bare LF on row 7**, while `app/data/ASCII/trancatg.txt`
  measures 18 CR against 18 LF in 1116 bytes and is uniformly CRLF. House L168
  makes LF the default for new fixtures, and L170 to L174 require any *intentional*
  preservation of CRLF to be documented in the scenario's own README. **These files
  do not preserve CRLF**, so nothing is being claimed under that clause. The
  concrete consequence of the rejected alternative is twofold: an absorbed CR
  pushes each record to 61 bytes, which the loader rejects rather than trims, and
  preserving origin faithfully would leave row 7 of `trantype` framed one byte
  differently from every other row in the same file for no reason a reader could
  act on. The compromise accepted is that the derived files differ from their seeds
  in exactly the line-ending byte, which is why section 4.1 publishes the arithmetic
  so the difference is predicted rather than discovered. Charter section 6.1 rules
  this tree-wide.

- Alternatives Considered: **`FILLER` was kept as ASCII `'0'` rather than
  space-padded.** The generic padding rule at house L152 to L156 pads a text field
  on the right with spaces, and applying it to `FILLER` needs no measurement of the
  source, which is why it is the tempting choice. It is rejected because `FILLER`
  carries no value for that rule to place: the seed rows hold eight ASCII `'0'` at
  offset 52 and four at offset 56, so space-padding would put spaces where the seed
  has zeros -- a different 60 bytes that passes every length check and every
  line-ending check and fails only much later as a wrong value. The consuming test
  asserts the filler decodes as the literal zeros, so the two regimes cannot be
  interchanged silently here. Charter sections 5.3 and 6.2 rule this tree-wide.

- Alternatives Considered: **the Db2 control cards were rejected as the derivation
  source.**
  [`DB2LTTYP.ctl`](../../../../../../../../app/app-transaction-type-db2/ctl/DB2LTTYP.ctl)
  and
  [`DB2LTCAT.ctl`](../../../../../../../../app/app-transaction-type-db2/ctl/DB2LTCAT.ctl)
  are superficially the better source, being the reference-data loader of the very
  extension tree these screens come from, and they carry the same 25 rows. They are
  rejected because they differ in three measurable ways. Every description is
  UPPERCASE -- `'PURCHASE'` at `DB2LTTYP.ctl` L17, `'PAYMENT'` at L18 and
  `'AUTHORIZATION'` at L20. `DB2LTTYP.ctl` **L22 spells the type-`06` description
  `'REVERAL'`**, where the ASCII seed reads `Reversal`. And `DB2LTCAT.ctl` **L36
  writes `'NON FRAUD REVERSAL'` with a space** where the ASCII seed uses an ASCII
  hyphen-minus. The consequence is specific rather than stylistic:
  `V2__seed_reference.sql` seeds from `app/data/ASCII/`, so a control-card-derived
  fixture would not match the rows the schema holds, every description comparison
  would fail, and carrying the variant spellings forward would violate AAP Rule T8.
  Charter section 6.6 rules this tree-wide.

- Assumptions: **the Python parity oracle offers no cross-check for either
  layout, and a search of it returns a misleading one.**
  [`tests/helpers/record_codec.py`](../../../../../../../../tests/helpers/record_codec.py)
  registers exactly eleven layouts at **L1349 to L1361**, and there is **no
  `TRANTYPE` key and no `TRANCAT` key** among them. The trap is that searching that
  file for `TRANCAT` does return hits -- at **L1153** and **L1163 to L1165** -- and
  they belong to `TCATBAL_LAYOUT`, which is `reclen=50` over
  `app/cpy/CVTRA01Y.cpy`, the transaction-category **balance** record. That record
  opens with an 11-byte account id, so its type code sits at offset 11 and its
  category code at offset 13, and it carries a zoned money field. Applying it to the
  60-byte record in this directory reads `TRAN-TYPE-CD` at offset 11 instead of 0
  and corrupts **every field past offset 6** while raising nothing. The scope of the
  claim matters and the charter fixes it at section 11.2: the **Python** oracle
  lacks these layouts, whereas the Java `CopybookLayout` registers both at 60 bytes,
  so geometry is verifiable -- one cross-check is missing, not all of them. The
  60-byte regime is genuinely new to the repository: the house enumeration of record
  lengths at L140 to L142 reads `(350, 300, 150, 50, 500, or 80 bytes)` with 60
  absent, and in the house derivation table at L665 to L689 these two seeds are the
  only two rows of nine carrying neither a copybook name nor a length.

---

## 8. Two obligations evaluated and found not to apply

Both are stated positively, because silence is indistinguishable from oversight.

**No synthetic-data attestation is owed for this directory.** The house obligation
at `tests/fixtures/README.md` section 10.3 (**L818 to L831**) is scoped to a
scenario README whose folder holds PAN or identity-shaped data. Neither layout here
holds either: between them the fields are a 2-byte type code, a 4-byte category
code, a 50-byte description and `FILLER`. There is no card number, no account id,
no customer id, no name and no national identifier in any byte of either file. The
charter reaches the same conclusion by the same scoping and lists this domain as
not requiring the attestation at its section 8.6, which also records which one of
the five domains does.

**This directory is distinct from the sibling `batch_reference_update`, and the
shared name is not redundancy.** That domain covers
[`COBTUPDT`](../../../../../../../../app/app-transaction-type-db2/cbl/COBTUPDT.cbl),
a sequential batch reader over an entirely different record: `WS-INPUT-REC`,
declared at **L71 to L77** as a 1-byte type indicator, a 2-byte number and a
50-byte description, summing to **53 bytes** with no `FILLER`. This directory
covers the **online** maintenance screen over the two **60-byte** reference
records. A reader who conflates them applies a 53-byte geometry to a 60-byte file
and every field after the first byte shifts.
