# `reference_list/empty_input` -- both reference datasets are zero-byte

Both record files in this directory, `trantype.txt` and `trancatg.txt`, are
**exactly 0 bytes**. Neither can carry a comment: a fixed-width record file has no
comment construct at all, because every byte position is data, and a file with no
bytes cannot carry one by definition. **This README is therefore the only possible
Explainability carrier for the whole scenario**, which is why every fact below was
measured against the copybooks, the baseline program and the files themselves
rather than inferred.

Two independent authorities put this file here, and neither is the migration
requirement list:

- **User-specified Rule 1 (Explainability) L27**, which requires a rationale to sit
  adjacent to what it explains. The nearest available location to a comment-less
  0-byte file is the directory that holds it.
- **The house mandate at `tests/fixtures/README.md` section 9.1 (L695 to L719)**,
  worded `MUST` rather than "should", with compliance measured at twenty of twenty
  house scenario directories.

The byte-level encoding contract for this tree -- the 0-based offset declaration,
the exact-length rule, the LF-only derivation ruling, the trailing-newline rule and
the two opposite `FILLER` regimes -- is owned by
[`../../README.md`](../../README.md) and is **cited here, never restated**. A second
copy of a rule is a second thing to drift.

The house section 9.1 content items map onto Rule 1's four docstring elements as
follows, so this document discharges the same obligation a docstring would. The
tree charter states the same mapping at its section 8.4 (L1239 to L1244):

| Rule 1 element | Where it lives here | What it covers |
|---|---|---|
| Purpose (L18) | Sections 1 and 2 | The condition this scenario represents and the exact business rule it exercises |
| Parameters (L19) | Section 4 | Per file: record width, record count and line ending; per field: offset, `PICTURE`, fill character and the copybook line that declares it |
| Return values (L20) | Sections 3 and 6 | The expected outcome as a specific value, plus the consumer and what it asserts |
| Exceptions and errors (L21) | Section 5 | The failure modes these bytes can produce, each with the symptom it actually shows |

Section 7 carries the labelled decision rationales and section 8 names the schema
contracts this fixture must not contradict.

---

## 1. Scenario intent

**The empty-result case for the transaction-type list browse: the reader is handed
a dataset that contains no records at all.**

It exists to separate two conditions that are easy to conflate and behave
differently: *a populated dataset whose browse finds nothing at the requested key*
and *a dataset with nothing in it to find*. This scenario is the second one.

**Both files are zeroed, not one, and the reason is referential rather than
procedural.** The baseline list program itself browses only the transaction-type
table -- section 2.1 shows the two cursors, and both read
`CARDDEMO.TRANSACTION_TYPE` and nothing else. What couples the two datasets is the
key relationship between them: a category row names the type it belongs to, and the
baseline declares that reference with `ON DELETE RESTRICT`, so a category can never
name a type that is not there. Emptying only `trantype.txt` while leaving
`trancatg.txt` populated would therefore describe a state the baseline schema
forbids -- category rows whose parent types are absent from the same scenario -- and
the house rule at `tests/fixtures/README.md` L647 to L652 requires every key
cross-referenced within a scenario to be resolvable inside that scenario's own
files, except where a scenario deliberately omits a key to trigger a reject. This
scenario is not testing a missing-key reject; it is testing nothing to read. Zero
rows on both sides is the only reading of "empty" that stays referentially
consistent.

---

## 2. The exact business rule it exercises

### 2.1 The keyset browse, and what an empty result does to its page state

[`COTRTLIC`](../../../../../../../../app/app-transaction-type-db2/cbl/COTRTLIC.cbl)
pages through the transaction types with two declared cursors, and the paging is
by **key**, not by offset:

| Direction | Cursor | Predicate | Ordering |
|---|---|---|---|
| Forward (PF8, page down) | `C-TR-TYPE-FORWARD`, declared L338 to L352 | `WHERE TR_TYPE >= :WS-START-KEY` (L343) | `ORDER BY TR_TYPE` (L351) |
| Backward (PF7, page up) | `C-TR-TYPE-BACKWARD`, declared L355 to L367 | `WHERE TR_TYPE < :WS-START-KEY` (L359) | `ORDER BY TR_TYPE DESC` (L367) |

Both cursors select `TR_TYPE` and `TR_DESCRIPTION` from
`CARDDEMO.TRANSACTION_TYPE` (L340 to L342). PF8 and PF7 are documented as page
down and page up at L571 to L572. A page holds **seven** rows: the output row
table is `EACH-ROWO OCCURS 7 TIMES` at L459, matched by the saved row table
`WS-CA-SCREEN-ROWS-OUT OCCURS 7 TIMES` at L389.

The page state the browse carries between turns is declared L403 to L410:
`88 CA-FIRST-PAGE VALUE 1` (L404), `88 CA-NEXT-PAGE-NOT-EXISTS VALUE LOW-VALUES`
(L409) and `88 CA-NEXT-PAGE-EXISTS VALUE 'Y'` (L410).

### 2.2 Two distinct messages exist, and this scenario reaches exactly one of them

The fetch loop's end-of-result branch, `WHEN SQLCODE = +100` at L1694, is where
both messages are decided, and it is the reason they must not be conflated. It
sets `CA-NEXT-PAGE-NOT-EXISTS` unconditionally (L1696) and then chooses between
two different conditions:

| Condition name | Verbatim text | Set when |
|---|---|---|
| `WS-MESG-NO-RECORDS-FOUND`, declared L253 to L254 | `No records found for this search condition.` | `WS-CA-SCREEN-NUM = 1 AND WS-ROW-NUMBER = 0` (L1702 to L1705) -- the first page fetched no row at all |
| `WS-MESG-NO-MORE-RECORDS`, declared L255 to L256 | `No more pages for these search conditions` | The message is still off **and PF8 was pressed** (L1698 to L1701) -- paging ran off the end of a result that did have rows |

Both strings are reproduced character-for-character, as AAP Rule T8
(user-visible strings are verbatim) requires. Two details are load-bearing and are
easy to lose in transcription:

- **The trailing period is inside the `NO-RECORDS-FOUND` literal** and is part of
  the message.
- **There is no trailing period inside the `NO-MORE-RECORDS` literal.** The `.`
  that follows it in the source is the COBOL sentence terminator, outside the
  closing quote.

**This scenario reaches `WS-MESG-NO-RECORDS-FOUND`**, because it presents the first
page with zero rows fetched, which is exactly the L1702 guard. It does not reach
`WS-MESG-NO-MORE-RECORDS`, whose guard requires a PF8 keypress.

---

## 3. Expected outcome

Stated as specific values rather than as a description of them:

| Observable | Expected value |
|---|---|
| Records decoded from `trantype.txt` | **exactly 0** |
| Records decoded from `trancatg.txt` | **exactly 0** |
| Page state | `CA-NEXT-PAGE-NOT-EXISTS` -- no further page is available in either direction |
| Message | `No records found for this search condition.` |
| Rows rendered on the first page | 0 of the 7 the page can hold |

**A zero-byte dataset is data, not a fault.** A reader or codec handed one yields an
empty result set: **not** an error, **not** a partially-parsed row, and **not** one
row of blanks.

Two levels are involved and they answer differently. Conflating them is the mistake
this paragraph exists to prevent:

- **At dataset level** -- reading a 0-byte file yields **zero records** and no
  exception. Nothing is written and nothing is rejected.
- **At single-record-image level** -- asking the codec to decode a zero-length
  image against a 60-byte layout is a **length failure**, because 0 is not 60. That
  is the exact-length rule of `../../README.md` section 5.2 doing its job, and it is
  a different question from "how many records does this file hold". The consumer in
  section 6 asserts both, separately.

### 3.1 What `empty_input` does NOT mean here

**It does not mean the database table is empty, and no test should be written on
that belief.**

[`../../../application-test.yml`](../../../application-test.yml) runs Flyway with
`schemas: reference` (L238) and `create-schemas: true` (L251) against a bare
container, so **both migrations always execute**: `V1__reference.sql` creates the
tables and `V2__seed_reference.sql` seeds them. `V2` inserts **7
`transaction_types` rows and 18 `transaction_categories` rows on every single
run**, so the tables are never empty in this module's test context.

`empty_input` names strictly the **zero-byte input dataset** condition -- the
reader and codec level that `tests/fixtures/README.md` L144 to L151 governs. A
document that said "the table is empty" would contradict `V2` outright, and a
reader who believed it would write a test that fails for a reason that has nothing
to do with the scenario.

---

## 4. Fixture bytes and governance

### 4.1 Per-file record width, record count and line ending

| File | Declared record width | Records | Line ending | Size |
|---|---|---:|---|---:|
| `trantype.txt` | 60 bytes (`TRAN-TYPE-RECORD`) | **0** | **none at all** | **0 bytes** |
| `trancatg.txt` | 60 bytes (`TRAN-CAT-RECORD`) | **0** | **none at all** | **0 bytes** |

Measured with `wc -c` and `od -c`: each file is 0 bytes, with **0 carriage returns
and 0 line feeds**. No trailing newline, no blank line, no space, no tab, no byte
order mark.

**The absence of a line ending is deliberate, and it is the one thing about this
directory a well-intentioned edit is most likely to undo.** The tree's
trailing-newline rule ends every record file with a single newline *after the last
record*; a file with no records has no last record, so the rule has nothing to
attach to. `../../README.md` section 5.6 states that exception directly, and
`tests/fixtures/README.md` L176 to L179 is the house rule it inherits. Adding a
newline here would not produce an empty file with tidy punctuation: it would produce
a **1-byte file holding one zero-length record**, which is off-length against a
declared width of 60 and fails parsing instead of exercising the empty path.

That is the byte-level contrast with the populated sibling scenario in this domain,
and it is worth stating explicitly because "empty" and "one blank line" look
identical in an editor: a populated `trantype.txt` in this tree is a whole number of
60-byte records each closed by one LF, and the size arithmetic of
`../../README.md` section 5.7 fixes its length; the same arithmetic applied to zero
records gives exactly 0 bytes, which is what these two files are.

### 4.2 The two layouts these files carry zero rows of

The widths and offsets are recorded even though no row is present, because they are
what makes "exactly zero records" a meaningful assertion rather than a statement
about an unknown shape: the consumer decodes against a **declared** 60-byte layout
and finds no record in it.

These tables are a **derived index that cites a copybook line per field, not a
competing declaration**. Where a table here and a copybook ever disagree, the
copybook wins and this document is wrong -- that is AAP Rule T1 (the copybook is
normative) in practice, and `../../README.md` section 5.8 is the tree's statement of
it. Offsets are **0-based**, as declared once for this tree at
`../../README.md` section 3.1; the base is never mixed here.

**`TRAN-TYPE-RECORD`** -- source
[`app/cpy/CVTRA03Y.cpy`](../../../../../../../../app/cpy/CVTRA03Y.cpy), 10 lines.
The banner at L2 declares `RECLN = 60`; L4 declares `01 TRAN-TYPE-RECORD.`:

| Copybook line | Field | `PICTURE` | Offset | Length | Fill |
|---|---|---|---:|---:|---|
| L5 | `TRAN-TYPE` | `X(02)` | 0 | 2 | text, right-padded with spaces |
| L6 | `TRAN-TYPE-DESC` | `X(50)` | 2 | 50 | text, right-padded with spaces |
| L7 | `FILLER` | `X(08)` | 52 | 8 | ASCII `0` (0x30) x8 |

Byte-count check: 2 + 50 + 8 = **60**. `FILLER` dropped in the target: **8 bytes**,
recorded per record as AAP Rule T1 requires.

**`TRAN-CAT-RECORD`** -- source
[`app/cpy/CVTRA04Y.cpy`](../../../../../../../../app/cpy/CVTRA04Y.cpy), 12 lines.
The banner at L2 declares `RECLN = 60`; L4 declares `01 TRAN-CAT-RECORD.`; **L5
declares `05 TRAN-CAT-KEY.`, a 6-byte group item that occupies no bytes of its
own** -- its two subordinates are the bytes, which is why the group contributes no
row of its own to the arithmetic below:

| Copybook line | Level | Field | `PICTURE` | Offset | Length | Fill |
|---|---|---|---|---:|---:|---|
| L6 | 10 | `TRAN-TYPE-CD` (in `TRAN-CAT-KEY`) | `X(02)` | 0 | 2 | text, right-padded with spaces |
| L7 | 10 | `TRAN-CAT-CD` (in `TRAN-CAT-KEY`) | `9(04)` | 2 | 4 | unsigned display, left-padded with `0` |
| L8 | 05 | `TRAN-CAT-TYPE-DESC` | `X(50)` | 6 | 50 | text, right-padded with spaces |
| L9 | 05 | `FILLER` | `X(04)` | 56 | 4 | ASCII `0` (0x30) x4 |

Byte-count check: 2 + 4 + 50 + 4 = **60**. `FILLER` dropped in the target:
**4 bytes**.

Three properties of these two layouts are stated plainly because assuming any of
them would be wrong:

- **The 60-byte regime is new to this repository's fixture contract.** The house
  record-length enumeration at `tests/fixtures/README.md` L140 to L142 lists 350,
  300, 150, 50, 500 and 80, and **60 is not among them**. There is no existing
  60-byte fixture precedent to lean on.
- **`TRAN-CAT-CD` is unsigned display** -- plain ASCII digits with **no sign
  overpunch** -- and its target column is `CHAR(4)` rather than an integer type, so
  the literal leading-zero form survives end to end. Its observed domain in the seed
  is `0001` through `0005`. Applying overpunch decoding to it would read a digit as
  a sign and be wrong by orders of magnitude, silently.
- **Neither layout contains a money field.** Every field is `X(n)` except the
  unsigned `9(04)`, so **no zoned-decimal handling and no sign-overpunch question
  arises anywhere in this scenario**, and the overpunch tables in
  `../../README.md` section 5.4 have no target here.

### 4.3 Provenance, and why no attestation is owed

**Provenance: zero rows were copied from anything.** The two datasets these
filenames stand for are seeded in the baseline from
[`app/data/ASCII/trantype.txt`](../../../../../../../../app/data/ASCII/trantype.txt)
-- measured 433 bytes, 7 records, CRLF on rows 1 to 6 and a bare LF on row 7 -- and
[`app/data/ASCII/trancatg.txt`](../../../../../../../../app/data/ASCII/trancatg.txt)
-- measured 1116 bytes, 18 records, CRLF on all 18. Both are REFERENCE-ONLY and are
never modified. **They are named here solely to establish provenance**: this
scenario copies **no row** from either, and no row is authored either.

**The filenames follow the source dataset names**, `trantype.txt` and
`trancatg.txt`, and never the copybook names `CVTRA03Y.txt` or `CVTRA04Y.txt`. That
is what lets a consumer resolve the same filename in this scenario and in the
populated one, so a scenario can be swapped without renaming anything.

**No synthetic-data attestation is owed by this scenario, and that is a resolved
condition rather than an omission.** The house obligation at
`tests/fixtures/README.md` L707 to L709 is explicitly conditional: it attaches
*whenever the scenario carries PAN or identity-shaped data*. It does not attach
here, for two independent reasons, either of which would be sufficient:

1. **Neither layout is identity-shaped.** `TRAN-TYPE-RECORD` is a 2-byte code plus a
   description, and `TRAN-CAT-RECORD` is a type plus a category plus a description.
   There is no account number, no card number, no name and no national identifier in
   either. The tree charter reaches the same conclusion for this domain at its
   section 8.6.
2. **These files carry no bytes at all**, so there is no value of any kind in them
   to attest about.

This is stated positively because silence would be indistinguishable from having
overlooked the requirement.

### 4.4 Neither layout is registered in the Python parity oracle

A `trantype` or `trancatg` fixture **cannot** be cross-checked against
`tests/helpers/record_codec.py`. Its `LAYOUTS` registry at L1349 to L1361 holds
exactly eleven keys -- `ACCOUNT`, `DALYTRAN`, `DISGROUP`, `XREF`, `TCATBAL`,
`CARD`, `CUSTOMER`, `TRAN`, `TRNX`, `REJECT`, `INTTRAN` -- and there is **no
`TRANTYPE` key and no `TRANCAT` key**. A search of `tests/helpers/` for `CVTRA03Y`
or `CVTRA04Y` returns nothing at all, and the house derivation table at
`tests/fixtures/README.md` L670 to L680 names a copybook and a length for every seed
row *except* these two, which read only "transaction type reference" and
"transaction category reference".

**One near-miss is worth naming, because a search for the string finds it and it is
not what it looks like.** `record_codec.py` L1163 to L1165 does contain
`TRANCAT-ACCT-ID`, `TRANCAT-TYPE-CD` and `TRANCAT-CD` -- but those are *field names
inside the `TCATBAL` layout*, which is `CVTRA01Y`, a **different 50-byte record**
declared at L1152. They are not support for `CVTRA04Y` and must not be cited as
such.

**The claim is scoped to the Python oracle and must not be widened.** The Java side
does register both: `com.carddemo.common.codec.CopybookLayout` declares `TRANTYPE`
at 60 bytes with a 2-byte key at offset 0 and `TRANCAT` at 60 bytes with a 6-byte
key at offset 0, and its per-field offsets agree with section 4.2 exactly. So the
missing Python coverage removes one cross-check; it does not leave this geometry
unverifiable, and it says nothing about the target stack.

---

## 5. Failure modes these bytes can produce

The Exceptions-and-errors element of Rule 1 L21, applied to a fixture rather than to
a function. Each row pairs a mistake with the symptom it actually shows, because the
dangerous ones are the quiet ones:

| Mistake | Symptom |
|---|---|
| A trailing newline is appended to either file | The file becomes a 1-byte file holding **one zero-length record**, not an empty dataset. It is off-length against 60, so it is rejected at load. **Loud** -- but the scenario has silently become a different one, and the record count assertion is what catches it |
| A space, tab or byte order mark is left in the file | Same as above: a short record rather than no record. The file still looks empty in an editor |
| Either file is deleted rather than emptied | A missing dataset instead of an empty one. Resolution from the test classpath fails, which is a different failure from the one this scenario is about -- the house rule at `tests/fixtures/README.md` L657 to L661 requires a **present but empty** input precisely so the reader opens a valid dataset instead of failing on an absent one |
| Only one of the two files is emptied | If `trantype.txt` is the one emptied, the remaining category rows name types that are no longer present in the scenario, which is the referential state section 1 rules out. The zero-record assertion on the emptied file still passes, so nothing fails. **Vacuous**, which is worse than a failure because it looks like coverage |
| A record row is added to either file | The scenario becomes a duplicate of the populated one and stops testing anything unique. **Silent** |
| Either file is rewritten with CRLF endings | Irrelevant while the files are empty, and immediately relevant if a row is ever added: a stray carriage return is absorbed into the trailing `FILLER` and pushes the record one byte past 60 |
| A width other than 60 is assumed by a reader | Every field after the first is misaligned, and the symptom is a plausible-looking wrong value rather than an exception. That is why section 4.2 states the width and every offset even though no row is present |
| A zero-length image is expected to decode to an empty field map | It does not. It is a **length** failure against a 60-byte layout, as section 3 distinguishes |

---

## 6. Consumer, and its verified availability status

**Availability status, verified against this branch rather than assumed.** Both
executable consumers of this tree are present and tracked, confirmed with
`git ls-files`:

| Consumer | Status | What it asserts about this scenario |
|---|---|---|
| `com.carddemo.reference.fixtures.ReferenceFixtureContractTest` | present, tracked | Lists both files among those that **must be exactly zero bytes** |
| `com.carddemo.reference.fixtures.ReferenceFixtureTest` | present, tracked | Asserts each file is zero bytes, that **no record can be read from it**, and that decoding a zero-length image against the 60-byte layout raises a record-length failure -- the two levels section 3 separates |
| `com.carddemo.common.codec.FixedWidthCodec` | present, tracked | Supplies the exact-length enforcement that makes 0 records the only reading of 0 bytes |
| `com.carddemo.common.codec.CopybookLayout` | present, tracked | Supplies the `TRANTYPE` and `TRANCAT` geometry a test decodes against, so no test hard-codes an offset copied out of section 4.2 |

Both files reach those consumers from the **test classpath**, not by filesystem
path: Maven copies `src/test/resources/` into `target/test-classes/`, so each
resolves as `fixtures/reference_list/empty_input/<file>.txt`. A zero-byte resource
is the one a copy step is most likely to drop, which is why the consumer resolves it
and raises rather than skipping when it is absent.

**The bytes are the contract.** A consumer's expected value is whatever these files
encode, so a fixture that is wrong does not fail -- it produces a green test that
proves nothing. A disagreement between this document and the files is settled by
measuring the files, and a disagreement between this document and a copybook is
settled by the copybook.

---

## 7. Why these bytes and not others

The decisions below are properties of the **fixture** rather than of the rule it
exercises, and each is one a later author could reverse without any test failing
until much later, which is why the reason is recorded beside it.

Assumptions: the only input this tree treats as empty is a **genuinely zero-byte
dataset**. The loaders and the codec reject any physical row whose length is not
exactly the record length -- they do not pad short rows, do not truncate long ones
and do not silently drop blank lines -- which `tests/fixtures/README.md` L144 to
L151 records as verified enforcement and frames as a deliberate financial-integrity
stance: a malformed record must never be silently coerced into a well-formed-looking
one. Both files here are therefore 0 bytes exactly.

Assumptions: both files are **present and empty** rather than absent, because the
house rule at `tests/fixtures/README.md` L657 to L661 requires the reader to open a
valid, if empty, dataset instead of failing on a missing one. That same passage
leaves an open question -- whether an `empty_input` scenario means a 0-byte file or a
file containing no lines -- and asks each scenario to say which. **This scenario
resolves it as 0-byte**, which is the same resolution the three other zero-byte
fixtures in this tree reached; counting these two, the tree holds five.

Alternatives Considered: a **whitespace-only file** (one space, or a single tab) and
a **single-blank-line file**. Both were rejected for the same concrete consequence.
A blank line is not an absent record; it is a **zero-length record**, and a
whitespace-only file is a one-byte or two-byte record. Measured against a declared
width of 60, each is off-length, and because the loader neither pads nor drops it,
the read ends in a hard failure rather than in the empty result set this scenario
claims to produce. The scenario would then assert the opposite of what its name
says, and the failure would be read as a bug in the reader rather than as a defect in
the fixture.

Trade-offs: **both** files are zeroed rather than one, which costs this scenario the
ability to observe the two datasets independently. The house tree offers both
precedents and they resolve differently: `provisioning/empty_input` zeroes **all
four** of its record files, while `posting/empty_input` and `statement/empty_input`
zero **only** their single driving dataset and leave their supporting masters
populated. The deciding difference is not how many files a program opens but whether
the populated remainder would still be self-consistent. In `posting` and
`statement` it is -- the masters stand alone and the emptied file is what drives the
run. Here it would not be, for the referential reason in section 1: 18 category rows
whose 7 parent types had been removed is a state the baseline's `ON DELETE RESTRICT`
reference forbids. So this scenario follows `provisioning`. Observing the two
datasets separately is a legitimate but different scenario, and it would need its own
name and its own README rather than a reinterpretation of this one.

---

## 8. Schema contracts this fixture must not contradict

Named as forward contracts so a reader can see that the fixture and the schema
agree. **This document authors no SQL and no DDL**; these live in the migrations and
are cited, not copied:

- **`V1__reference.sql`** creates `reference.transaction_types` with primary key
  `type_cd CHAR(2)` and `description VARCHAR(50) NOT NULL`, and
  `reference.transaction_categories` with the composite primary key
  `(type_cd CHAR(2), cat_cd CHAR(4))` and `description VARCHAR(50) NOT NULL`. Its
  foreign key `transaction_categories.type_cd` to `transaction_types(type_cd)` is
  declared `ON DELETE RESTRICT`, carrying across the baseline reference that
  `app/app-transaction-type-db2/ddl/TRNTYCAT.ddl` declares at its L6 to L7: a type
  still named by a category cannot be deleted. The composite primary key is
  separately what satisfies the baseline's unique index in
  `app/app-transaction-type-db2/ddl/XTRNTYCAT.ddl`, since a unique constraint on the
  same two columns in the same order enforces it already. `V1` records both
  attributions itself, and they are two different objects rather than one.
- **`V2__seed_reference.sql`** seeds the 7 type rows and 18 category rows described
  in section 3.1.

The column widths are why section 4.2 matters to a schema reader as well as to a
fixture author: `CHAR(2)`, `CHAR(4)` and `VARCHAR(50)` are the same 2, 4 and 50 the
copybooks declare, so a fixture row that was ever added here at the wrong width
would not fit the column it targets.
