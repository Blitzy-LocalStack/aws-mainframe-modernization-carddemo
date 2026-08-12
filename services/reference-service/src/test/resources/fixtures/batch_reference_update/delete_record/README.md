# `batch_reference_update/delete_record` - the `'D'` dispatch branch, reached so that the delete succeeds

Two 53-byte maintenance records: the first adds a transaction type the seeded schema
does not hold, the second removes that same type. This is the clean-delete case for the
`COBTUPDT` batch reference-update flow, and the pairing is what lets the delete arm
reach its success outcome rather than a refusal or a not-found.

> **Why this README exists.** `trtype-update.txt` is a fixed-width flat file in which
> every byte position carries meaning, so a `#` or `//` annotation would be *data*
> rather than a comment: the physical line holding it would be the wrong length and
> would be rejected at decode instead of ignored. None of the documentation formats
> Rule 1 L22 names - JSDoc, Javadoc, Python docstrings, XML comments - can be attached
> to such a file, so this document is the only conforming place the justification for
> those bytes can live. Rule 1 L27 requires that justification to sit adjacent to what
> it explains, which is why it is in this scenario's own directory rather than
> aggregated upward.
>
> **The tree-wide byte contract is deliberately not reproduced here.** The offset base,
> the complete layout tables, the two opposite `FILLER` fill regimes, the zoned-decimal
> sign-overpunch tables, the line-ending ruling, the canonical rationale labels and the
> validation gates are all single-sourced in
> [`../../README.md`](../../README.md), whose section 3.6 tables this record and whose
> section 8.4 sets the shape of this document. What follows summarises only what is
> specific to this scenario. Copying those tables down into every scenario directory
> would create as many places for the geometry to drift as there are scenarios; where
> this document and that one ever disagree, that one governs and this one is wrong.

The six sections below are the house four-element scenario contract, and each discharges
one element of the docstring requirement Rule 1 L15 imposes: intent and rule are its
**Purpose** (L18), the byte table is its **Parameters** (L19), the outcome together with
its consumer is its **Return values** (L20), and the byte-level failure modes are its
**Exceptions** (L21).

---

## 1. Scenario intent - Purpose

The clean-delete case: the `'D'` (DELETE) dispatch branch of the migrated `COBTUPDT`
batch reference-update flow, exercised so that the removal **succeeds**. Nothing here is
a boundary, a refusal or a not-found; the point of the scenario is that the delete arm
does exactly what its name says and can be observed doing it.

Why that outcome takes **two** records rather than one is the single fact about this
directory a reader cannot re-derive from the bytes, and it is declared in section 3.2.
Read that section before changing either row.

---

## 2. The exact business rule it exercises - Purpose

### 2.1 The dispatch

`1003-TREAT-RECORD` in
[`COBTUPDT.cbl`](../../../../../../../../app/app-transaction-type-db2/cbl/COBTUPDT.cbl)
spans L109 to L130. Its `EVALUATE INPUT-REC-TYPE` opens at L110 and closes at L129, and
it reads byte 0 of the record and nothing else. The verified arm order is `'A'` at L111,
`'U'` at L114, `'D'` at L117, `'*'` at L120 and `WHEN OTHER` at L122.

This scenario is the `WHEN 'D'` arm at L117, which displays a progress line at L118 and
performs `10033-DELETE-DB` at L119.

Assumptions: the arm order in the program and the order in the job's own column
documentation genuinely differ - the program runs `A`, `U`, `D`, `*` while
`MNTTRDB2.jcl` L11 to L14 lists `A`, `D`, `U`, `*`. Neither ordering is behavioural,
because `EVALUATE` arms over a single byte are mutually exclusive and exactly one can
match. It is recorded because a reader holding both documents will notice the mismatch,
and the reasonable-looking response - editing one to agree with the other - would be a
change to a REFERENCE-ONLY baseline file for no behavioural gain.

### 2.2 The delete, and exactly what it binds of the record

`10033-DELETE-DB` occupies L196 to L226. Its statement at L201 to L204 is
`DELETE FROM CARDDEMO.TRANSACTION_TYPE WHERE TR_TYPE = :INPUT-REC-NUMBER`, and it names
exactly one host variable.

The consequence for these bytes is direct: **only bytes 1 and 2 are bound by the SQL on
this path.** Bytes 3 to 52 are read into `INPUT-REC-DESC` by the `READ ... INTO` at L101
and are then never referenced by the delete arm - the statement does not bind the
description at all.

The corollary belongs in the same breath, because separating the two is how a record
gets shortened. The description field is **still positionally present and must still be
exactly 50 bytes**: "unused" is not "omittable". The record width is a property of the
access method that delivers the record, not of the statement that happens to read part
of it, so a row trimmed to its two bound bytes is a wrong-length row and is rejected at
decode rather than interpreted as a three-byte delete instruction.

### 2.3 The add on row 1 exercises the neighbouring arm

Row 1 selects the `WHEN 'A'` arm at L111, which displays a progress line at L112 and
performs `10031-INSERT-DB` at L113. That paragraph spans L132 to L164, and its insert at
L137 to L148 binds **both** host variables - `:INPUT-REC-NUMBER` at L145 and
`:INPUT-REC-DESC` at L146 - reaching `WHEN SQLCODE = ZERO` at L152 and displaying
`'RECORD INSERTED SUCCESSFULLY'` at L153.

That asymmetry is worth holding on to when reading section 6: the same 50 description
bytes are behaviourally live on row 1 and behaviourally inert on row 2, which is why
their *content* is argued from readability while their *width* is not negotiable.

### 2.4 What the two nearest dispatch neighbours do instead

Stated so that a reader cannot mistake this scenario's outcome for either of them.

- **The no-action comment path.** `WHEN '*'` at L120 displays
  `'IGNORING COMMENTED LINE'` at L121 and performs **no SQL at all**. Such a row is
  still exactly 53 bytes and still data to any length-checking reader; only the program
  interprets byte 0 as a comment marker. That case belongs to
  [`../commented_line`](../commented_line).
- **The invalid-type path.** `WHEN OTHER` at L122 composes `'ERROR: TYPE NOT VALID'`
  (L124) into `WS-RETURN-MSG` (`PIC X(80)`, L61) and performs `9999-ABEND` at L128.
  Despite the paragraph name, `9999-ABEND` at L230 to L233 does not end the run: its
  whole body displays the message, moves 4 to `RETURN-CODE`, and returns through
  `1003-TREAT-RECORD` to the loop at L93 to L96, so every later row is still read. That
  case belongs to
  [`../invalid_type_abend`](../invalid_type_abend/README.md).

Assumptions: the paragraph *name* is the only thing on that second path suggesting
termination, which is exactly why its real effect is written out here rather than left
to the name. [`../../README.md`](../../README.md) section 3.6 records the same finding
at tree scope.

### 2.5 One diagnostic this paragraph does not carry

`10033-DELETE-DB` distinguishes exactly three outcomes in its `EVALUATE` at L207 to
L225: zero, `+100`, and less than zero. It carries **no dedicated arm for a
referential-integrity refusal** - such a refusal collapses into the generic negative arm
at L216 alongside every other negative state.

The dedicated discrimination of Db2 `SQLCODE -532` lives in the **online** programs -
`COTRTLIC.cbl` at L1914, and `COTRTUPC.cbl` at L1638 - and not in `COBTUPDT`. Recorded
because crediting this program with that discrimination would attribute behaviour it
does not have, and because the migrated service's `RejectReason.REFERENTIAL_INTEGRITY`
documents itself as a divergence from the baseline on precisely that ground.

---

## 3. Expected outcome - Return values

### 3.1 The result

Row 2 reaches `WHEN SQLCODE = ZERO` at L208 and displays
`'RECORD DELETED SUCCESSFULLY'` at L209: **one row removed.** That specific result, and
not the category "a delete happened", is what this directory exists to pin. Row 1
reaches the insert's success arm at L152 with `'RECORD INSERTED SUCCESSFULLY'` at L153.
Neither row is refused, neither is not-found, and neither reaches `9999-ABEND`.

In the migrated service the same pair presents as an applied `ADD` outcome followed by
an applied `DELETE` outcome, carrying those two messages, with the aggregate reporting
that nothing was rejected.

### 3.2 Why a clean delete needs two rows

Assumptions: **this directory carries Option A - a clean delete via a two-row fixture,
row 1 adding the unseeded code `08` and row 2 deleting that same code.** It is the only
arrangement that reaches `SQLCODE = 0` and `'RECORD DELETED SUCCESSFULLY'` without
arranging database state outside the fixture, and the seeded schema is what forces it.
`V1__reference.sql` declares a foreign key on `reference.transaction_categories` whose
`type_cd` references `reference.transaction_types (type_cd)` under `ON DELETE RESTRICT`,
carrying forward the legacy semantic that
[`TRNTYCAT.ddl`](../../../../../../../../app/app-transaction-type-db2/ddl/TRNTYCAT.ddl)
states at L6 to L7; a refusal surfaces as PostgreSQL SQLSTATE 23503.
`V2__seed_reference.sql` then seeds 7 types and 18 categories from
`app/data/ASCII/trantype.txt` and `trancatg.txt`, and `application-test.yml` runs both
migrations against a bare PostgreSQL container. Counted directly from the category seed,
those 18 rows distribute over codes `01` to `07` as 5, 3, 3, 3, 1, 2, 1 - so **every
seeded type has at least one child category**, and a `'D'` row aimed at any of them is
refused. Code `08` appears in neither seed file, so it is unseeded and childless: adding
it first makes the row exist, and its having no categories means the foreign key has
nothing to refuse. A `'D'` row on `01` documented as "deletes the row" would simply be
wrong against this schema, and no length check or lint pass would catch the error.

Two consequences of Option A that must not be left to inference:

1. **Row 1's byte 0 is `'A'`, so the dispatch-coverage audit for this folder expects the
   ordered set `{A, D}` and not `{D}` alone.** Stated outright so the audit is
   unambiguous: a reviewer counting one dispatch value per scenario directory would
   otherwise read the `'A'` row as contamination and remove the very row that makes the
   delete succeed.
2. **The ordering, `'A'` before `'D'`, is required by this scenario's own intent and by
   no key rule whatsoever.** Section 6 draws that distinction in full; it matters
   because a reader who infers a sort rule from this pair will apply it where none
   exists.

Alternatives Considered: **Option B, declaring a refusal** - a single `'D'` row on a
seeded code, expecting the `ON DELETE RESTRICT` refusal. Rejected because that outcome is
already owned twice over elsewhere, and encoding it here would duplicate it at byte
level while leaving the `'D'` branch's success path with no fixture anywhere in the
tree. SQLSTATE 23503 is declared in the batch service's own reject-reason contract as
that class's documented divergence from a baseline which does not discriminate it
(section 2.5), and the byte-level case is held by the sibling domain scenario
`reference_update/delete_restricted_by_category`, whose fixture pairs a type the
categories reference with one nothing references so that exactly one refused delete is
isolated.

Alternatives Considered: **Option C, declaring a not-found** - a single `'D'` row on an
unseeded code, expecting `SQLCODE +100`, `'No records found.'` and `9999-ABEND`.
Rejected on two concrete grounds. It is a *not-found* scenario rather than a *delete*
scenario, so a directory named `delete_record` would document a case in which nothing is
deleted. And it is not distinctive to delete at all: `10032-UPDATE-DB` carries an
identical `+100` arm composing the same literal at L180 to L184, so the fixture would
pin a behaviour shared with the update path rather than anything specific to this one.

**Do not resolve either rejected option by adding a refusal or duplicate scenario to
this domain.** SQLSTATE 23503 and 23505 are classified by the service's reject-reason
contract and asserted by its own tests; a byte-level fixture directory is not where that
classification is decided.

### 3.3 The two outcomes of the same paragraph this scenario does not take

Naming them bounds the declaration in section 3.1, so that "the delete succeeds" is read
as one of three arms rather than as the only one.

- **L210 to L214** - `WHEN SQLCODE = +100` composes `'No records found.'` (L211) into
  `WS-RETURN-MSG` and performs `9999-ABEND`. The trailing period is inside the literal.
- **L216 to L224** - `WHEN SQLCODE < 0` composes `'Error accessing:'` (L218),
  `' TRANSACTION_TYPE table. SQLCODE:'` (L219) and `WS-VAR-SQLCODE` (`PIC ----9`, L65)
  into `WS-RETURN-MSG` (`PIC X(80)`, L61) and performs `9999-ABEND`. The leading space is
  inside the second literal.

Assumptions: both literals are reproduced character for character under AAP Rule T8,
which requires user-visible strings to be carried across verbatim. The two easily-lost
characters are called out at their own sites because a message compared against an
approximation fails on a difference a reader will not see, and because an approximate
quotation of a literal is worse than no quotation at all.

### 3.4 Consumers, with availability stated rather than implied

Annotated `[present]` or `[planned]` following [`../../README.md`](../../README.md)
section 9.3, whose own stated reason is to keep a document from describing a future
artifact as if it already exists.

- **`ReferenceBatchUpdateService`** - `[present]`. Its entry point accepts the
  maintenance records as a stream of contiguous 53-byte records and returns one outcome
  per record in stream order; its per-record member transcribes `1003-TREAT-RECORD` with
  the same `A` / `U` / `D` / `*` / `OTHER` dispatch, and its declared record length is
  53. The action codes it matches are the single bytes `A` and `D`, so this fixture's
  two rows select the `ADD` action for row 1 and the `DELETE` action for row 2. Its
  removal member cites the same L196 to L226 span this document cites.
- **A case binding these two rows** - `[present]`. The scenario-parameterised classpath
  loader in `ReferenceBatchUpdateServiceTest` resolves
  `fixtures/batch_reference_update/<scenario>/trtype-update.txt`, which is the mechanism
  that makes one file name per scenario resolvable at all (section 6), and the
  `on the stored scenario fixtures` group feeds *this* scenario through it. It asserts the
  ordered pair section 3.1 states - an applied `ADD` on code `08` carrying
  `RECORD INSERTED SUCCESSFULLY`, then an applied `DELETE` on the same code carrying
  `RECORD DELETED SUCCESSFULLY`, nothing refused, the clean return code - and verifies the
  removal as a real delete of the row the lookup returned, because an outcome reporting
  `DELETE` while nothing was removed is the shape a swallowed foreign-key refusal takes.
  The fixture-geometry cases in `com.carddemo.reference.fixtures` now enumerate this
  scenario alongside its siblings, and `ReferenceFixtureTest` sweeps the packaged tree so
  none can fall out of that enumeration again.
- **This entry read `[planned]` until a review found it.** Its own wording was accurate -
  these bytes were "stated as this file's declared contract, not as an observed assertion"
  - and that is precisely the state a committed fixture should not be left in: nothing
  executed against the file, so an edit that made it wrong would have passed. The
  annotation is kept in this shape rather than deleted so the distinction between a
  declared and an observed contract stays legible to the next reader.
- **What that consumer is not.** A plain service method over a record stream. There is
  no batch job, no step, no job repository and no run ledger behind this input, and
  describing one would attribute machinery to a flow whose baseline is a single
  sequential read loop at L93 to L96.
- **What it does not write.** `reference.transaction_categories`. `COBTUPDT` L54
  includes only `DCLTRTYP`, so the category table is never a target of this flow. It
  bears on this scenario in exactly one way - as the source of the foreign-key refusal
  that Option A avoids - and in no other. The line is drawn explicitly because a delete
  scenario is where a reader is most likely to blur it and read the child table as
  something the flow maintains.

**The bytes are the contract.** A consumer's expected value is whatever these 108 bytes
decode to, so a fixture that is wrong does not fail - it produces a passing assertion
that proves nothing. A disagreement between a consumer and this file is settled by
reading the bytes, not by editing them.

---

## 4. Fixture bytes and governance - Parameters

### 4.1 The record, field by field

Offsets are **0-based**, matching [`../../README.md`](../../README.md) section 3.6.

| COBOL line | Field | `PICTURE` | Offset | Length | Fill |
|---|---|---|---:|---:|---|
| L72, with L73 `VALUE SPACES.` | `INPUT-REC-TYPE` | `X(1)` | 0 | 1 | space, declared but never exercised |
| L74, with L75 `VALUE SPACES.` | `INPUT-REC-NUMBER` | `X(2)` | 1 | 2 | right-pad space |
| L76, with L77 `VALUE SPACES.` | `INPUT-REC-DESC` | `X(50)` | 3 | 50 | right-pad space |

Byte-count check: 1 + 2 + 50 = **53**.

Assumptions: L71 `01 WS-INPUT-REC.` is the **normative** source of this geometry, and it
is normative for one specific reason rather than by preference - L101 reads
`INTO WS-INPUT-REC`, so that group is the one the program actually populates. The
FD-side `01 WS-INPUT-VARS.` at L40 to L46, under `FD TR-RECORD RECORDING MODE F.` at
L39, mirrors the same three widths and is cited here **only** as independent
confirmation that the file is fixed-length; a reader who takes it as the layout instead
will reach the same 53 bytes but from the wrong authority, and the two could in
principle diverge.

Assumptions: `PIC` and `VALUE` sit on **separate physical lines** for all three fields -
L72 with L73, L74 with L75, L76 with L77 - so a layout extractor that assumes one
declaration per line mis-reads this record. The same source lines carry legacy sequence
numbers in columns 73 to 80, `00592033` on L72 among them, and that identification-area
content must never leak into a derived record: bytes 0 through 52 are the record and
nothing else is.

### 4.2 The file, and arithmetic that makes the check reproducible

`trtype-update.txt` carries a record width of **53**, a record count of **2**, a total
of **108 bytes**, and **LF-only line endings with exactly one trailing newline** and no
blank line.

```text
bytes = rows x 53 + rows        ->        2 x 53 + 2 = 108
```

Assumptions: the arithmetic is written out rather than only its total because it is what
lets a later author who adds or removes a row **re-derive** the expected size instead of
measuring the file and then trusting the measurement. Measured against this file, the
byte count is 108, `wc -l` is 2, both physical lines are exactly 53 characters and the
carriage-return count is 0.

Assumptions: the exact-width rule is enforcement rather than guidance. The loaders
reject any physical row whose length is not exactly the record length - they do not pad
a short row, do not truncate a long one and do not drop a blank line
(`tests/fixtures/README.md` L144 to L151), and that document's own rationale at L150 to
L151 is that a malformed record must never be silently coerced into a well-formed-looking
one. LF only with a single trailing newline follows the same tree (L158 to L184), where
L168 makes LF the default for every new fixture. These two rows are **constructed rather
than lifted from a seed**, so no carriage return can enter by derivation and the rule
needs no normalisation argument here - but it still needs stating, because a blank line
added later would be a zero-length record that fails fixed-width parsing (L181 to L184)
rather than a harmless spacer.

### 4.3 The width is corroborated twice, from mutually independent artifacts

The `PICTURE` arithmetic above gives 53. So does
[`MNTTRDB2.jcl`](../../../../../../../../app/app-transaction-type-db2/jcl/MNTTRDB2.jcl),
whose comment block documents the record in **one-based** columns: L11 to L14 give
column 1 as the action - `A - ADD`, `D - DELETE`, `U - UPDATE`, `* - COMMENT` - L16
gives columns 2 to 3 as the numeric transaction type, and L18 gives columns 4 to 53 as
the description. Column 53 is the last column named, so the record is 53 bytes, reached
without reference to any `PICTURE`.

Assumptions: **the offset bases differ and the difference is stated rather than
reconciled.** The JCL is 1-based; this document and [`../../README.md`](../../README.md)
are 0-based. Leaving the base implicit is not a stylistic risk but a guaranteed one-byte
error on every field, which is why the description reads as columns 4 to 53 there and as
offset 3 for length 50 here, and why those two are the same field.

Assumptions: **53 is a width new to this repository, and saying so is more useful than
implying coverage that does not exist.** The house record-length enumeration at
`tests/fixtures/README.md` L140 to L142 lists 350, 300, 150, 50, 500 and 80, and 53 is
not among them. No existing house fixture, helper vector or documented width can be
consulted as precedent for this geometry, so the two independent derivations above are
the whole of the evidence for it.

That same JCL block is also the second, independent statement that the dispatch domain
is exactly `{A, D, U, *}` - the program's `EVALUATE` at L110 to L129 being the first -
which is what makes the `WHEN OTHER` arm a genuine catch-all rather than a fifth member.

### 4.4 What governs the fill: this record has no `FILLER` at all

Stated positively, because a reader arriving from the two opposite `FILLER` regimes in
[`../../README.md`](../../README.md) will otherwise ask which one applies here.

Assumptions: **neither regime applies, because there is no `FILLER` to fill.** The three
declared fields account for the record exactly, 1 + 2 + 50 = 53 with nothing left over,
and all three are `PIC X(n)`, so the generic right-pad-with-spaces rule governs the
whole record. `FILLER` dropped: **none**. For the same reason **no zoned-decimal or
sign-overpunch handling arises anywhere in this domain**: the record declares no signed
numeric field, so the overpunch tables in the tree contract have no target in it, and a
reader looking for a sign byte here is looking for something that does not exist.

Assumptions: every field is right-space-padded to its declared width, `INPUT-REC-DESC`
included, and that holds on the `'D'` row **even though the SQL never reads it** -
26 trailing spaces on both rows. The width obligation comes from the access method, not
from the statement, exactly as section 2.2 sets out.

### 4.5 No synthetic-data attestation is owed here

Also stated positively, because an unexplained absence is indistinguishable from an
oversight. The house attestation obligation at `tests/fixtures/README.md` section 10.3
is scoped to scenario directories holding a primary account number or identity-shaped
data - card number, customer name, address, national identifier, government-issued
identifier, date of birth, telephone number or credit score. `WS-INPUT-REC` is an action
byte, a two-character reference code and a description: **it carries none of those
categories**, so the obligation does not attach to this directory and its absence is
deliberate rather than overlooked. [`../../README.md`](../../README.md) section 8.6
records the same determination for this domain alongside the other four.

---

## 5. Failure modes these bytes can produce - Exceptions

Assumptions: these are the failure modes of the *bytes*, which is the Exceptions element
of Rule 1 L21 applied to a fixture rather than to a function. Each one is a way this file
can be edited into something that still looks plausible.

- **A one-byte miscount shifts every field after the miscount.** An off-length row is
  rejected outright at decode, so that variant announces itself. The dangerous variant is
  a *compensated* miscount, which holds the row at 53 bytes while moving the boundary
  between two fields: drop one byte from the code and add one to the description, and the
  row still measures 53, still loads cleanly, and the delete then keys on a single
  character plus whatever byte followed it. Section 4.1 states the offsets so that
  boundary can be checked rather than assumed.
- **A blank line is a zero-length record, not a spacer.** It fails fixed-width parsing
  (`tests/fixtures/README.md` L181 to L184). One newline per record is what keeps
  `wc -l` equal to the record count and makes the arithmetic in section 4.2 a usable
  check.
- **Only a genuinely zero-byte file counts as empty** (`tests/fixtures/README.md` L144
  to L151). A file of blank lines is not empty, it is malformed. A 108-byte file is
  never treated as empty by anything, so this scenario cannot silently degrade into the
  empty case; that case is [`../empty_input`](../empty_input/README.md), whose file is
  0 bytes.
- **A rewrite to CRLF adds a byte to every record.** The carriage return would be
  absorbed into the trailing description field, pushing each row to 54 bytes and
  breaking both the width rule and the arithmetic in section 4.2.
- **Deleting row 1 leaves a syntactically valid file that documents the opposite
  outcome.** One `'D'` row on code `08` against the seeded schema takes the `+100` arm
  at L210 and reaches `9999-ABEND`, so the scenario would assert a not-found while still
  being named `delete_record`. Nothing about the file's length, encoding or field
  offsets would reveal the change - which is why section 3.2 exists.
- **Moving the code into the seeded range inverts the scenario in the other
  direction.** Row 1 on any of `01` to `07` is a duplicate key, and row 2 on the same
  code is refused by the foreign key, so a two-character edit converts a clean delete
  into two rejects.

---

## 6. Why these bytes and not others

Each item below is a decision a later author could reverse without any length, encoding
or offset check failing, which is precisely why Rule 1 L40 requires the reason to be
written beside it.

Alternatives Considered: **the file name.** `trtype-update.txt` is identical across all
six scenario directories in this domain, which is what lets the consumer resolve one
name per scenario rather than a table of names - the classpath loader in section 3.4
composes the scenario directory into the path and appends this one file name. Three
alternatives were weighed and each fails on something specific. `inpfile.txt`, after
`//INPFILE  DD  DSN=INPFILE,DISP=SHR` at `MNTTRDB2.jcl` L27 and `ASSIGN TO INPFILE` at
`COBTUPDT.cbl` L31, was rejected because `INPFILE` is a generic DD placeholder that
identifies no particular stream - the same name would be equally correct for any input
this job read. `mnttrdb2.txt` was rejected because it names the **job** rather than the
stream, so a second input to the same job would have no name left. `COBTUPDT.txt` and
`WS-INPUT-REC.txt` were rejected outright: a fixture is named for the data it stands in
for, never for a program and never for a working-storage group. The hyphenated form also
departs from the 8-character dataset-shaped names the house tree uses, and it does so
for a reason rather than by drift - **no 8-character dataset name exists for this
stream**, because the only name the baseline supplies for it is the generic `INPFILE`
above.

Alternatives Considered: **taking a description from the Db2 control cards**
`app/app-transaction-type-db2/ctl/DB2LTTYP.ctl` or `DB2LTCAT.ctl`. Superficially
attractive, since those files are the reference-data loader for the very extension tree
this flow belongs to. Rejected on two measured grounds: their values are UPPERCASE on
every row, and `DB2LTTYP.ctl` L22 carries `'REVERAL'` where the seed carries `Reversal`.
`V2__seed_reference.sql` deliberately seeds from the ASCII files instead, so a
control-card-derived value would not match the row seeded into the schema and would
violate AAP Rule T8. Where a scenario in this tree carries a code the seed holds, the
description is lifted from
[`trantype.txt`](../../../../../../../../app/data/ASCII/trantype.txt) character for
character - `01` `Purchase`, `02` `Payment`, `03` `Credit`, `04` `Authorization`, `05`
`Refund`, `06` `Reversal`, `07` `Adjustment`, mixed case included. Such a lift needs no
re-padding and cannot pick up the seed's line ending: `trantype.txt` is the 60-byte
`CVTRA03Y` record, so a seed row's description already occupies exactly 50
right-space-padded bytes at 0-based 2 to 51, against this record's 3 to 52, while the
carriage return on that file's first six rows sits at 0-based byte 60 - nine positions
past the description's last byte. The rule has a limit worth naming: **code `08` appears
nowhere in `trantype.txt`, so its description is author-composed and AAP Rule T8 does
not bind it.** That rule binds values that *do* appear in the reference data; there is no
seed value here to preserve and nothing is being paraphrased. The composed text follows
the seed's mixed-case shape for a concrete reason rather than for tidiness - the target
column holds seven mixed-case neighbours, and an uppercase spelling would put this row in
a different convention from every row beside it.

Alternatives Considered: **row 2 repeating row 1's description** rather than carrying 50
spaces. Spaces are the reasonable-looking alternative, because they match the field's own
`VALUE SPACES.` initialization at L77 and the SQL on the delete path never reads the
field anyway. Rejected on two concrete consequences: a reader could take blanks for a
meaningful empty description rather than for absence, and blanks would erase the only
visible link between the two rows, leaving which row the delete pairs with to be
reconstructed from the two-character code alone. Repeating the text makes the pairing
legible at a glance. The description's *content* is behaviourally irrelevant on row 2 for
exactly the reason section 2.2 gives - but its **50-byte width is not optional**, and the
26 trailing spaces are part of the record either way.

Assumptions: **the fixture is a plain sequence of rows with no key-ordering requirement,
and the house pre-sorting step therefore does not apply.** `COBTUPDT.cbl` L31 to L34
declare `ORGANIZATION IS SEQUENTIAL` and `ACCESS MODE IS SEQUENTIAL`, L101 is
`READ TR-RECORD NEXT RECORD INTO WS-INPUT-REC`, and the loop at L91 to L99 performs
`1003-TREAT-RECORD` on each record in turn until end-of-file. Item 5 of the house
add-a-scenario procedure at `tests/fixtures/README.md` section 9.2 - pre-sort an indexed
input so the indexed loader takes it cleanly - has no target here, and recording that
non-applicability is itself an obligation: an unexplained missing step reads as an
omission, and a reader who supplies the step anyway would sort a file whose order is
meaningful.

Assumptions: **the one ordering this scenario does have comes from its own intent and
not from any key rule.** Row 1 must precede row 2, because a delete of a row that has
not been added takes the `+100` arm rather than the success arm. That is a data
dependency between two records in one sequential stream, which is a different matter
entirely from a sorted-key requirement on the file - the distinction is drawn here
rather than left to inference, because a reader who reads "order matters" as "the file
is sorted" will look for a key that does not exist, and one who reads the absence of a
key rule as "order is free" will reverse the two rows and invert the outcome.

Trade-offs: **Option A widens this directory slightly beyond a pure single-branch
fixture**, since row 1 exercises the `'A'` arm that
[`../add_record`](../add_record/README.md) owns as its subject. The compromise is
accepted deliberately and the alternative is worth stating plainly: a `delete_record`
directory that never demonstrates a delete. Two things keep the widening bounded - the
`'A'` row is present to establish the row that row 2 removes and for no other purpose,
and the dispatch-coverage expectation for this folder is recorded as `{A, D}` in section
3.2 so the extra arm is declared rather than discovered.
