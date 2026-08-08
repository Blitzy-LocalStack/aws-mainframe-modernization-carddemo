# card-service test fixtures -- CVACT02Y card records

Authoritative register for the twelve fixed-width card-record fixtures this
module's test tree holds, and the Explainability carrier for the decisions behind
them.

Every fixture named here is a `CARD-RECORD` as declared by
`app/cpy/CVACT02Y.cpy`. That copybook is the specification; this file states the
contract each sibling `.txt` is measured against, and nothing here overrides it.
`app/**` is read as reference and is never modified by this module -- it is cited
by path and line only.

> **Note -- delivery state, measured.** All **twelve** `.txt` fixtures named in
> [section 4.1](#41-the-register) are present in this directory, carrying **40
> records** between them, and every register row is annotated **[present]**
> accordingly. The record counts, loadability classes and seed provenances
> recorded there have been re-measured against the bytes rather than restated: 40
> records at exactly 150 data bytes each, 40 distinct card numbers, zero carriage
> returns, and the seed runs named per row. The commands in
> [section 11](#11-verification) therefore have inputs, and that section reports
> what they returned.
>
> **This directory now has a consumer.**
> `services/card-service/src/test/java/com/carddemo/card/fixtures/CardFixtureContractTest.java`
> reads **all twelve** fixtures — the eleven record-bearing files through its
> `allFixtures()` source, and `card-empty-input.txt` through
> `theEmptyFixtureIsZeroBytes()` — and asserts the invariants this README declares.
> [Section 10](#10-where-the-docstring-obligation-actually-attaches) records what
> remains owed, which is the **behavioural** consumers rather than any consumer at
> all. Refactoring Rationale: an earlier revision of this paragraph said neither of
> the two test classes then present read this directory, and concluded that the
> per-fixture docstring obligation was owed by whoever wrote the first consumer. That
> was true when written; leaving it standing would now tell a reader the fixtures are
> unread, which is the one conclusion this directory cannot afford — an unread fixture
> is indistinguishable from a wrong one.
>
> **Why the annotation is here.** Assumptions:
> `tests/fixtures/README.md` section 9.3 mandates a **[present]**/**[planned]**
> marker on every referenced artifact and gives its own reason for doing so: the
> marker is what keeps a document from describing a future artifact as if it
> already existed. This register was authored ahead of the bytes it governs, and
> deliberately so, because it is what the author of those bytes worked from. The
> markers are kept now that the bytes exist, rather than deleted as spent, because
> the register still names one artifact class that does not exist -- the **behavioural**
> consumers in section 10, which are not the same thing as the contract consumer that
> now reads every file here -- and because a row that carries its availability
> explicitly is the row a reader can falsify.

Read this file before adding a fixture, before loading one into PostgreSQL, and
before asserting on one. Section 4 in particular prevents a real and confusing
failure: two of the twelve fixtures -- three records between them -- hold values
the database schema will not accept, and inserting one of those produces a
constraint error where a validation assertion was intended.

Refactoring Rationale: the sentence above counted "three of the twelve fixtures"
and now counts two files and three records. Three is the RECORD count -- one
out-of-domain status plus two out-of-range months -- and both records live in the
two `card-schema-reject-*` files. The distinction is worth the extra clause
because the reader this paragraph is addressing is about to decide which FILES
may be handed to a repository, and a file count of three would send them looking
for a third one that does not exist.

## Contents

1. [Why this file exists](#1-why-this-file-exists)
2. [Normative record layout](#2-normative-record-layout)
3. [File invariants](#3-file-invariants)
4. [Loadability classes and the fixture register](#4-loadability-classes-and-the-fixture-register)
5. [Validation domains the fixtures exercise](#5-validation-domains-the-fixtures-exercise)
6. [The expiry-day divergence](#6-the-expiry-day-divergence)
7. [There is no golden-master oracle for this context](#7-there-is-no-golden-master-oracle-for-this-context)
8. [Synthetic-provenance attestation](#8-synthetic-provenance-attestation)
9. [Decision justifications](#9-decision-justifications)
10. [Where the docstring obligation actually attaches](#10-where-the-docstring-obligation-actually-attaches)
11. [Verification](#11-verification)

## 1. Why this file exists

The project carries one user-specified rule, Rule 1 (Explainability). Its full
text is available through the `review_rules` tool and is deliberately not
reproduced here. Two of its clauses pull in opposite directions for a directory
of fixed-width data files, and the way that tension resolves is the entire
reason this Markdown file is present.

**The docstring clause does not reach the record bytes.** Rule 1 scopes its
docstring obligation to a function, a class, or a module entry point, and it
restates that scope at its closing validation gate. A 150-byte fixed-width data
record is none of those three. The formats the rule enumerates are all
programming-language docstring conventions, and it names none for a fixed-width
record. Its inline-comment clause is scoped to the code a comment sits beside,
and a data file contains no code.

There is a second and more decisive ground, and it is physical rather than
interpretive. A `#` or `--` header line would add bytes and shift every
subsequent offset, so a commented fixture would break every 150-byte read that
consumes it. The repository already settled this: of the 78 data files under
`tests/fixtures/**`, all 78 begin with a data byte and not one begins with `#`
or `-`.

**The undocumented-decision clause does reach this directory, and it binds.**
Rule 1 forbids leaving a non-obvious implementation choice undocumented
whenever a reasonable alternative existed. This fixture set is full of exactly
those choices -- a three-way loadability taxonomy, a record count picked to
cross two page boundaries, a scenario represented by deliberate absence, an
authored name value where the seed data provably cannot supply one. None of
that can live in the bytes. A Markdown carrier beside the bytes is the only
remaining vehicle, so this file is where those justifications are discharged;
they are collected in [section 9](#9-decision-justifications).

The house pattern is identical, which is why this file follows it rather than
inventing a convention. `tests/fixtures/README.md` section 9.1 mandates a
README in every scenario folder, and gives as its stated reason that static
`.txt` fixtures cannot carry docstrings; measured against the checkout, 20 of
20 house scenario directories that hold data files carry one. Section 10 of the
same file mandates a synthetic-provenance attestation wherever a fixture
carries a primary account number or identity-shaped data. These fixtures carry
both card numbers and embossed names, so that attestation is mandatory here and
appears in [section 8](#8-synthetic-provenance-attestation).

## 2. Normative record layout

The layout is declared once, here, and the register in
[section 4](#4-loadability-classes-and-the-fixture-register) refers back to it
rather than restating it. `tests/README.md:540-542` instructs that a record
layout is never duplicated and is kept single-sourced from `app/cpy/`; keeping
one copy in this directory honours that instruction.

Derived field for field from `app/cpy/CVACT02Y.cpy`, a 14-line copybook whose
`01 CARD-RECORD.` group item is at line 4.

| Field | PICTURE | Offsets (1-based) | Copybook line |
|---|---|---|---|
| `CARD-NUM` | `X(16)` | 1-16 | `app/cpy/CVACT02Y.cpy:5` |
| `CARD-ACCT-ID` | `9(11)` | 17-27 | `:6` |
| `CARD-CVV-CD` | `9(03)` | 28-30 | `:7` |
| `CARD-EMBOSSED-NAME` | `X(50)` | 31-80 | `:8` |
| `CARD-EXPIRAION-DATE` | `X(10)` | 81-90 | `:9` |
| `CARD-ACTIVE-STATUS` | `X(01)` | 91 | `:10` |
| `FILLER` | `X(59)` | 92-150 | `:11` |

`16 + 11 + 3 + 50 + 10 + 1 + 59 = 150`.

The misspelling in `CARD-EXPIRAION-DATE` is the baseline's own, and this
document reproduces it exactly whenever the field is named, because that is its
name. The target column is `expiration_date`
(`services/card-service/src/main/resources/db/migration/V1__card.sql:258`).
That rename is a target-side naming decision and one of the three such
corrections the migration plan records; it asserts nothing whatsoever about the
COBOL, which keeps its own spelling.

### 2.1 Eight independent confirmations of the 150-byte length

Record length is the one number every offset in this directory depends on, so
it is worth more than a single source. Each item below was measured against
this checkout.

1. The copybook states it itself: the banner comment at
   `app/cpy/CVACT02Y.cpy:2` reads `RECLN 150`.
2. The field widths in the table above sum to 150.
3. `app/cbl/CBACT02C.cbl:39-40` splits the same record across a file-section
   pair, `05 FD-CARD-NUM PIC X(16).` plus `05 FD-CARD-DATA PIC X(134).`, and
   `16 + 134 = 150`. The same program takes the copybook itself at
   `app/cbl/CBACT02C.cbl:45`, so the two views sit side by side in one compile
   unit.
4. `app/cbl/COCRDUPC.cbl:314-321` re-declares the identical seven-field layout
   locally as `05 CARD-UPDATE-RECORD.`, and rewrites the file from it at
   `app/cbl/COCRDUPC.cbl:1479-1480` using `LENGTH OF CARD-UPDATE-RECORD`.
5. `app/data/ASCII/carddata.txt` measures 7550 bytes across 50 records, and a
   width histogram over it yields exactly one group: 50 lines of 150.
   `151 * 50 = 7550`.
6. `app/jcl/CARDFILE.jcl:88` declares `RECORDSIZE(150,150)` on the alternate
   index. The base cluster agrees at `app/jcl/CARDFILE.jcl:54-55` with
   `KEYS(16 0)` and `RECORDSIZE(150 150)`.
7. `common-lib` registers the record at the same length in code:
   `services/common-lib/src/main/java/com/carddemo/common/codec/CopybookLayout.java:1442`
   constructs the card spec at 150 bytes with a 16-byte key at offset 0, and
   line 1449 declares `text("FILLER", 91, 59)` -- 0-based offset 91 being
   1-based position 92, for 59 bytes.
   `services/common-lib/src/main/java/com/carddemo/common/codec/FixedWidthCodec.java:43`
   tabulates the same dataset as `CARDDATA` against `CVACT02Y.cpy` at 150 bytes
   with `FILLER X(59)`.
8. The internal shape of the date field is confirmed from inside the program
   logic, not merely from its width. `app/cbl/COCRDUPC.cbl:1505-1507`
   reference-modifies `CARD-EXPIRAION-DATE(1:4)`, `(6:2)` and `(9:2)` and
   compares them against the year, month and day held separately, so the stored
   form is `YYYY-MM-DD` with literal separators at positions 5 and 8. The same
   three reference modifications are used to move the parts back out at
   `app/cbl/COCRDUPC.cbl:1514-1516`.

Confirmation 8 is why lexical comparison of this field stays equivalent to date
comparison: a zero-padded, fixed-width, most-significant-part-first date sorts
identically as text and as a date, which is what lets the field be stored as a
real `DATE` in the target without changing any ordering the baseline produced.

## 3. File invariants

These are hard, checkable properties of every `.txt` in this directory.
[Section 11](#11-verification) gives the commands.

- **Every record is exactly 150 bytes of data.** Not 149, not 151. A width
  histogram over any populated fixture must collapse to the single value 150.
- **Every file measures exactly `151 * record_count` bytes**: 150 data bytes
  plus one LF for each record, including the last. There is no missing final
  newline and no extra blank line.
- **LF only, and zero CR bytes.** Measured, `app/data/ASCII/carddata.txt`
  contains zero `\r`, and so does every house `carddata.txt` fixture.
  `tests/fixtures/README.md` section 8 records `carddata.txt` as an LF dataset,
  in contrast to `tcatbal.txt`, `trancatg.txt` and `trantype.txt`, which that
  same table records as CRLF. Getting this wrong would add a byte per record
  and break the size invariant above.
- **Printable ASCII only**, byte range 32 to 126 inclusive, plus the record LF.
  No NUL, no high-bit bytes, no byte-order mark. Measured, the seed file
  contains no byte outside that range.
- **A zero-record fixture is a 0-byte file.** It is not a file containing one
  empty line. The house precedent is exact:
  `tests/fixtures/provisioning/empty_input/carddata.txt` measures 0 bytes.
- **`FILLER` at 92-150 is 59 space (0x20) bytes, and it is load-bearing rather
  than optional.** Measured across the 50 seed records, the filler region
  yields a single distinct value and 2950 bytes of which zero are non-space.
  It has to be written out because `common-lib` treats trailing blank padding
  asymmetrically by design: `FixedWidthCodec` omits it on decode so consumers
  are not handed dozens of inert blanks, and restores it on encode so the
  physical record stays the declared length. That contract is stated at
  `services/common-lib/src/main/java/com/carddemo/common/codec/FixedWidthCodec.java:111-114`,
  which notes explicitly that omitting the padding without restoring it would
  produce a short physical record. A fixture truncated at position 91 would
  therefore fail a round trip that a full-length record passes.
- **`CARD-ACCT-ID` and `CARD-CVV-CD` are unsigned `PIC 9(n)` display fields:
  plain ASCII digits, zero-padded to the full width, with no sign overpunch
  anywhere.** This record has no signed field and no money field at all, so the
  zoned-decimal sign-overpunch handling that the account and transaction
  fixtures require simply does not apply here. Measured, all 50 seed records
  carry 16 digits at 1-16 and 3 digits at 28-30.

## 4. Loadability classes and the fixture register

A fixture in this directory can be invalid in two entirely different ways, and
conflating them causes a misleading test failure. The distinction is drawn from
the measured target schema in
`services/card-service/src/main/resources/db/migration/V1__card.sql`, which
declares `active_status CHAR(1) NOT NULL` at line 273 constrained by the named
`CONSTRAINT ck_cards_active_status CHECK (active_status IN ('Y', 'N'))` at line
342, and `expiration_date DATE NOT NULL` at line 261.

Because the status domain and the date domain are enforced by the database
itself, a record carrying status `X` or month `13` cannot reach a validator at
all -- it is rejected on insert. A record carrying year `1949`, by contrast,
inserts perfectly happily, because `1949-06-15` is a valid date; only the
application rule objects to it. Those two failures need different test setups,
so they get different labels.

- **Class A -- persistable and rule-valid.** Loads into `card.cards` without
  error and passes every `COCRDUPC` edit rule. Use these for happy-path,
  pagination, by-account and preservation assertions.
- **Class A-R -- persistable, rule-reject.** The schema accepts the bytes, so
  the row loads; a `COCRDUPC` edit rule then rejects the value, so the validator
  or the mapper must reject it. These fixtures exist precisely to prove that the
  validator rejects data the database would have stored without complaint. They
  may be inserted.
- **Class B -- schema-reject, NOT persistable.** The schema physically rejects
  the value. **A Class B record MUST NOT be inserted into `card.cards`.**
  Inserting one yields a check-constraint violation or a date-parse error
  instead of the intended validation assertion, which is a confusing and
  misleading failure that points at the wrong layer. Feed Class B bytes to the
  request validator or the mapper directly, never to the repository.

The filenames encode the class at the call site, so a reader of `CardRepositoryIT`
-- still to be written, per section 10 -- will be able to tell from the fixture
name alone whether a row may be inserted:

| Filename prefix | Class |
|---|---|
| `card-valid-*`, `card-boundary-*`, `card-*-corpus`, `card-expiry-day-preserved` | A |
| `card-rule-reject-*` | A-R |
| `card-schema-reject-*` | B |

### 4.1 The register

Twelve fixtures, 40 records in total -- **all twelve present, and every count in
this table re-measured against the bytes**, per the delivery-state note at the top
of this file. The `Avail.` column carries the marker per row, in the form
`tests/fixtures/README.md` section 9.3 mandates, so a reader who arrives at this
table directly reads the availability of each row without having to scroll for it.
"Seed provenance" names the record
positions in `app/data/ASCII/carddata.txt` that each fixture draws from; see
[section 8](#8-synthetic-provenance-attestation) for what is copied verbatim
and what is authored. Field offsets are never restated per fixture -- they come
from [section 2](#2-normative-record-layout). The Class column is a validation
taxonomy and never a presence flag.

| File | Avail. | Recs | Class | Encodes | Seed provenance | Authority |
|---|---|---|---|---|---|---|
| `card-list-page-corpus.txt` | [present] | 18 | A | 3 pages at page size 7; both interior page boundaries traversable in both directions; a `Y`/`N` status mix at positions 3, 10 and 17 | seed recs 1-18 | `COCRDLIC:177-178`, `:250` |
| `card-by-account-corpus.txt` | [present] | 4 | A | account `00000000901` holding 3 cards, `00000000902` holding 1 card, and `00000000903` holding zero -- absent by design | seed recs 19-22 | `CARDFILE.jcl:85-87` |
| `card-boundary-expiry-inclusive.txt` | [present] | 4 | A | months `01` and `12`, years `1950` and `2099`: all four inclusive bounds | seed recs 23-26 | `COCRDUPC:95`, `:99` |
| `card-expiry-day-preserved.txt` | [present] | 3 | A | days `01`, `15` and `28` at one fixed month and year | seed recs 27-29 | `COCRDUP.CPY:96` |
| `card-valid-active.txt` | [present] | 1 | A | canonical happy path with status `Y`; doubles as the mixed-case-name case | seed rec 30, entirely verbatim | `COCRDUPC:1499-1501` |
| `card-valid-inactive.txt` | [present] | 1 | A | status `N` | seed rec 31 | `COCRDUPC:89-91` |
| `card-rule-reject-name-non-alpha.txt` | [present] | 2 | A-R | an apostrophe, and an appended digit | seed rec 34 verbatim; seed rec 35 with an authored name | `COCRDUPC:822-839` |
| `card-rule-reject-name-blank.txt` | [present] | 2 | A-R | 50 spaces, and 50 ASCII `0` | seed recs 41-42, names authored | `COCRDUPC:811-819` |
| `card-rule-reject-expiry-year-out-of-range.txt` | [present] | 2 | A-R | years `1949` and `2100` | seed recs 36-37 | `COCRDUPC:99` |
| `card-schema-reject-status-out-of-domain.txt` | [present] | 1 | B | status `X` | seed rec 38 | `COCRDUPC:89-91` plus `ck_cards_active_status` |
| `card-schema-reject-expiry-month-out-of-range.txt` | [present] | 2 | B | months `00` and `13` | seed recs 39-40 | `COCRDUPC:95` plus the `DATE` column type |
| `card-empty-input.txt` | [present] | 0 | not applicable | the empty result set | none | `COCRDLIC` no-records path |

Two rows in that table deserve to be read against each other, because the
contrast is the whole reason the A-R class exists.
`card-rule-reject-expiry-year-out-of-range.txt` is Class A-R rather than Class B
**because `1949-06-15` and `2100-06-15` -- the two dates the file actually
carries, measured -- are both perfectly valid PostgreSQL dates.** The `DATE` column stores them without objection; the rule that rejects
them is `88 VALID-YEAR VALUES 1950 THRU 2099` at `app/cbl/COCRDUPC.cbl:99`,
which lives in the validator and has no schema counterpart. Meanwhile
`card-schema-reject-expiry-month-out-of-range.txt` is Class B because month `00`
and month `13` do not denote a date at all, so the value never survives
conversion. Same field, same kind of out-of-range value, two different layers
doing the rejecting.

## 5. Validation domains the fixtures exercise

Read from `app/cbl/COCRDUPC.cbl`, a 1560-line program, and
`app/cbl/COCRDLIC.cbl`. Both are reference sources: cited, never modified.

### 5.1 Active status

`app/cbl/COCRDUPC.cbl:89-91` declares `05 FLG-YES-NO-CHECK PIC X(1)` with
`VALUE 'N'` and the condition `88 FLG-YES-NO-VALID VALUES 'Y', 'N'`. The domain
is those two characters and nothing else, which is what
`ck_cards_active_status` mirrors in the schema. `card-valid-active.txt` and
`card-valid-inactive.txt` cover the two accepted values;
`card-schema-reject-status-out-of-domain.txt` carries `X`.

### 5.2 Expiry month

`app/cbl/COCRDUPC.cbl:92-95` declares `CARD-MONTH-CHECK PIC X(2)` with a
numeric `REDEFINES` as `PIC 9(2)`, carrying `88 VALID-MONTH VALUES 1 THRU 12`.
**Both bounds are inclusive**, so `01` and `12` are valid months and belong in
the boundary fixture rather than in a reject fixture.
`card-boundary-expiry-inclusive.txt` holds both;
`card-schema-reject-expiry-month-out-of-range.txt` holds `00` and `13`.

### 5.3 Expiry year

`app/cbl/COCRDUPC.cbl:96-99` declares `CARD-YEAR-CHECK PIC X(4)` with a numeric
`REDEFINES` as `PIC 9(4)`, carrying `88 VALID-YEAR VALUES 1950 THRU 2099`.
**Both bounds are inclusive**, so `1950` and `2099` are valid.
`card-boundary-expiry-inclusive.txt` holds both;
`card-rule-reject-expiry-year-out-of-range.txt` holds `1949` and `2100`.

The `X`-over-`9` `REDEFINES` pairs in 5.2 and 5.3 are also why the target
transports these two components as digit-validated strings rather than as
integers: the baseline itself holds them as characters and views them as numbers
only for the range test.

### 5.4 Embossed name -- two distinct rejection branches

`1230-EDIT-NAME` runs from `app/cbl/COCRDUPC.cbl:806` to its exit paragraph
`1230-EDIT-NAME-EXIT` at `:841`. It has **two separate rejection branches with
different flags, different messages and different rendering**. A reader who
assumes a single "bad name" outcome will write the wrong assertion, which is why
both are documented here and why two fixtures exist rather than one.

The paragraph opens at `:808` by setting the not-OK flag, so the field is
pessimistically invalid until a branch proves otherwise.

**Branch 1, blank, at `:811-819`.** The test is
`IF CCUP-NEW-CRDNAME EQUAL LOW-VALUES OR EQUAL SPACES OR EQUAL ZEROS`, spanning
lines 811 to 813. It sets the input-error flag at `:814`, sets
`FLG-CARDNAME-BLANK` at `:815` -- whose flag value is `' '`, declared at `:68` --
and at `:817` selects the message `WS-PROMPT-FOR-NAME`, whose verbatim text at
`app/cbl/COCRDUPC.cbl:181-182` is `'Card name not provided'`. It then leaves the
paragraph at `:819`.

**Branch 2, non-alphabetic, at `:822-837`.** The name is moved to a work field
at `:823`, then `INSPECT CARD-NAME-CHECK CONVERTING LIT-ALL-ALPHA-FROM TO
LIT-ALL-SPACES-TO` at `:824-826` blanks out every alphabetic character. The test
at `:828` is `IF FUNCTION LENGTH(FUNCTION TRIM(CARD-NAME-CHECK)) = 0`: if
nothing but spaces survived, the name was alphabetic and the branch continues at
`:829`. Otherwise it sets `FLG-CARDNAME-NOT-OK` at `:832` -- flag value `'0'`,
declared at `:66` -- and at `:834` selects `WS-NAME-MUST-BE-ALPHA`, whose
verbatim text at `app/cbl/COCRDUPC.cbl:183-184` is
`'Card name can only contain alphabets and spaces'`.

**Success at `:839`** sets `FLG-CARDNAME-ISVALID`, flag value `'1'`, declared at
`:67`.

The conversion alphabet is 52 characters. `app/cbl/COCRDUPC.cbl:255-257`
declares `LIT-ALL-ALPHA-FROM PIC X(52)` with the value
`'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'`, and `:258-259`
declares the matching `LIT-ALL-SPACES-TO PIC X(52) VALUE SPACES`. Note what is
**absent** from those 52 characters: there is no space and no apostrophe. The
absence of the space is exactly why a name with internal spaces passes -- the
spaces were already spaces, so the trimmed length is still zero. The absence of
the apostrophe is exactly why `Lucious O'Connell` fails: the apostrophe is not
converted, so it survives the trim and the length test is non-zero.

**A counter-intuitive ordering, worth asserting deliberately.** The blank check
runs first and treats `ZEROS` as blank. So a name of 50 ASCII `'0'` characters
takes branch 1, not branch 2: it yields `'Card name not provided'` and **not**
`'Card name can only contain alphabets and spaces'`, even though `'0'` is absent
from the 52-character alphabet and would certainly have failed branch 2 had it
reached it. `card-rule-reject-name-blank.txt` carries both the 50-space and the
50-zero pattern so that this ordering is pinned by a test rather than
rediscovered by a debugging session.

**The two branches also render differently**, per
`app/cbl/COCRDUPC.cbl:1263-1272`. The non-alphabetic case at `:1263-1266` moves
`DFHRED` into the field colour attribute only. The blank case at `:1268-1272`
moves `DFHRED` into the colour attribute **and** moves a literal `'*'` into the
field itself. That is the templated field-highlight contract of
`app/cpy/CSSETATY.cpy` implemented inline in this program, and it is the reason
the blank fixture is not redundant with the non-alphabetic one: the two produce
observably different output, so the target's per-field error rendering has two
cases to reproduce, not one.

### 5.5 Case-insensitive change detection

`9300-CHECK-CHANGE-IN-REC` begins at `app/cbl/COCRDUPC.cbl:1498` and its first
act, at `:1499-1501`, is `INSPECT CARD-EMBOSSED-NAME CONVERTING LIT-LOWER TO
LIT-UPPER`. Only then does it run the comparison chain at `:1503-1508`. A
submitted name differing from the stored name only in letter case is therefore
**not** a change, and must not be reported as one. `card-valid-active.txt`
carries the Title Case seed name that makes this assertable.

### 5.6 Page size is 7

`app/cbl/COCRDLIC.cbl:177-178` declares
`05 WS-MAX-SCREEN-LINES PIC S9(4) COMP` with `VALUE 7.`. Three further
corroborations sit in the same program: the comment at `:250` reads
`28 CHARS X 7 ROWS = 196`, `:253` declares `10 WS-ALL-ROWS PIC X(196).`, and
`:255` declares `15 WS-SCREEN-ROWS OCCURS 7 TIMES.`. An independent fifth
confirmation comes from the symbolic map, `app/cpy-bms/COCRDLI.CPY`, which
declares `ACCTNO1I` at line 84 through `ACCTNO7I` at line 264 and **no
`ACCTNO8`** anywhere.

The lookahead that decides whether a next page exists is also explicit:
`app/cbl/COCRDLIC.cbl:1284-1285` computes the read counter as
`WS-MAX-SCREEN-LINES + 1`, and `:1287` sets the next-page-exists condition. The
browse cursor it carries between turns is already a keyset rather than an
offset -- `app/cbl/COCRDLIC.cbl:230-244` holds a last-key pair, a first-key
pair, a screen number, a last-page-displayed flag and a next-page indicator.
`card-list-page-corpus.txt` is sized against page size 7 and that
read-one-extra lookahead.

### 5.7 The by-account access path

`app/jcl/CARDFILE.jcl:83` defines the alternate index, and `:85-87` declare
`KEYS(11 16)`, `NONUNIQUEKEY` and `UPGRADE`. The key is 11 bytes at 0-based
offset 16, which is 1-based position 17, which by
[section 2](#2-normative-record-layout) is `CARD-ACCT-ID`. `NONUNIQUEKEY` is the
part that matters for fixture design: one account may hold many cards.

That is the access path `idx_cards_account_id`
(`V1__card.sql:399`) replaces, and the `accountId` predicate of
`CardRepository.findForwardFromCursor` and `findBackwardFromCursor` serves -- reached
from `CardController.listCards` through `CardListService.list`.
`card-by-account-corpus.txt` is the fixture that exercises it, and it has to be
authored rather than sampled -- see the third measured proof in
[section 8](#8-synthetic-provenance-attestation).

## 6. The expiry-day divergence

This is a documented behavioural divergence between the baseline and the target.
It is recorded here in the form the migration plan requires: the baseline does
X, the Java implements Y, and the difference is documented rather than silent.
Nothing in `app/**` changes.

**What the baseline does.** The update map has a day input field:
`app/cpy-bms/COCRDUP.CPY:96` declares `02 EXPDAYI PIC X(2).`, with its length,
attribute and flag siblings at `:91-94` and its output counterpart `EXPDAYO` at
`:200`. The program accepts that field from the map at
`app/cbl/COCRDUPC.cbl:621` and assembles the stored date from three parts at
`:1467-1474`:

```
STRING  CCUP-NEW-EXPYEAR
        '-'
        CCUP-NEW-EXPMON
        '-'
        CCUP-NEW-EXPDAY
        DELIMITED BY SIZE
   INTO CARD-UPDATE-EXPIRAION-DATE
END-STRING
```

**What the baseline does not do is validate that day.** A structural sweep of
the program returns exactly six field-edit paragraphs and no seventh:
`1210-EDIT-ACCOUNT` at `:721`, `1220-EDIT-CARD` at `:762`, `1230-EDIT-NAME` at
`:806`, `1240-EDIT-CARDSTATUS` at `:845`, `1250-EDIT-EXPIRY-MON` at `:877` and
`1260-EDIT-EXPIRY-YEAR` at `:913`. **There is no `1270-EDIT-EXPIRY-DAY`.** A
second, independent indicator points the same way: the program declares
`WS-EDIT-CARDEXPMON-FLAG` at `:73` and `WS-EDIT-CARDEXPYEAR-FLAG` at `:77`, and
declares no equivalent edit flag for the day at all.

Every occurrence of the day component is a move, a display or a comparison, never
an edit. It is declared at `:300` and `:312`, accepted from the map at `:621`,
moved for display at `:675`, written to the map at `:1104`, `:1110`, `:1123` and
`:1127`, given a protected attribute at `:1285`, moved at `:1366`, assembled at
`:1471`, compared at `:1507` and moved back at `:1516`. The day is read, stored
and compared; it is never range-checked.

**What the Java implements.** `CardUpdateRequest` carries four components and
only four -- `embossedName`, `activeStatus`, `expiryMonth` and `expiryYear` --
with no `expiryDay`. An update therefore cannot alter the day-of-month, and the
stored day is preserved across an update rather than re-supplied by the client.

**Why that is the safer of the two available readings.** The alternative would
have been to accept a day component and validate it, which would introduce a
rejection the baseline never produced, and would let a client change a value the
baseline's own edit chain never examined. Preserving the stored day keeps the
observable outcome inside what the baseline can produce.
`card-expiry-day-preserved.txt` carries days `01`, `15` and `28` at one fixed
month and year so the preservation is assertable across an update; the three
days are all unambiguously valid in every month, so the fixture tests
preservation rather than accidentally testing calendar arithmetic.

## 7. There is no golden-master oracle for this context

Stated plainly, because an unstated absence reads as a claim. Do not describe
these fixtures as golden-master verified, and do not report a command as passing
that was not run.

**Why there is none.** `tests/README.md:83-85` records that the online `CO*`
CICS programs cannot be run end to end without a CICS runtime, that the runner
does not have one, and that consequently only their extractable
field-validation logic is unit-tested. None of `COCRDLIC`, `COCRDSLC` or
`COCRDUPC` is a batch program, and the house golden-master oracle covers batch
flows only. There is therefore no captured byte-exact output for a card screen
to compare against, and this directory does not pretend otherwise.

**What parity rests on instead.** For the card context, parity rests on two
things: validation logic transcribed paragraph by paragraph from the COBOL, with
each transcription citing the paragraph it came from, and the copybook record
contracts reproduced field for field. That is what
[section 5](#5-validation-domains-the-fixtures-exercise) documents and what these
fixtures feed. It is a weaker oracle than a byte comparison and is described as
such rather than dressed up as more.

**Shipping fixtures with no `golden/` directory is established house practice,
not a gap.** `tests/README.md:140-146` states in as many words that the `export`
domain drives a byte-identical round trip and therefore ships fixtures but no
`golden/` directory, and calls that internally consistent. The `prepost` domain
does the same. Measured against the checkout, `find tests -type d -name
"golden*"` returns exactly one root, `tests/golden`, and its domains are
`interest`, `posting`, `provisioning`, `reporting` and `statement` -- so both
`export` and `prepost` ship fixtures with no golden counterpart at all. This
directory follows that precedent.

**Return-code discipline.** The COBOL suite uses a graded mainframe condition-code
rubric in which a worst-case aggregate of 4 is its documented green state. That
rubric is **quarantined to `tests/**` and does not travel.** Maven, Checkstyle,
Surefire, Failsafe and JUnit gates are binary: they pass or they fail. Never
describe a Java build in this module as warn-level green, and never let the COBOL
rubric be used to excuse a Java gate.

## 8. Synthetic-provenance attestation

`tests/fixtures/README.md` section 10 mandates this attestation for every
fixture carrying a primary account number or identity-shaped data. These
fixtures carry both card numbers and embossed names, so it is mandatory here.

**No real cardholder, account or personal data appears in any fixture in this
directory.** Every `CARD-NUM`, every `CARD-CVV-CD` and every
`CARD-EMBOSSED-NAME` -- except for the two authored name cases named below -- is
copied verbatim from a named record of `app/data/ASCII/carddata.txt`, which is
published synthetic demonstration data shipped with the upstream open-source
project and describes no real person or account. **No primary account number and
no person name is freshly minted here.** The seed file is reference-only, so the
provenance chain stays reproducible: every identity-shaped value in this
directory traces back to a specific seed row by position.

The remaining three fields -- `CARD-ACCT-ID`, `CARD-EXPIRAION-DATE` and
`CARD-ACTIVE-STATUS` -- are authored **only** where the scenario demands a value
the seed provably cannot supply. That proviso needs evidence rather than
assertion, so here are the four measurements over the 50 seed records that
justify authoring anything at all:

1. **Status.** `cut -c91 app/data/ASCII/carddata.txt | sort | uniq -c` yields a
   single group: `Y`, 50 times. **There is not one `N` in the seed.** So
   `card-valid-inactive.txt` cannot be sampled; its status byte is authored.
2. **Expiry.** `cut -c81-84 | sort | uniq -c` yields exactly three years --
   `2023` (11 records), `2024` (14) and `2025` (25) -- and no others. There is
   no `1949`, `1950`, `2099` or `2100` anywhere. `cut -c86-87 | sort | uniq -c`
   yields months `01` through `10` and `12`, and never `00` or `13`. So neither
   the inclusive-bound fixture nor either out-of-range fixture can be sampled;
   their date fields are authored.
3. **Account ids.** `cut -c17-27 | sort | uniq -c` yields **every account id
   exactly once -- 50 cards spread across 50 distinct accounts, ids
   `00000000001` through `00000000050`, with zero duplicates.** No seed account
   holds two cards, so **the seed cannot exercise the by-account query at all**,
   and `card-by-account-corpus.txt` must author its account ids.
4. **Names.** All 50 seed names are alphabetic with exactly one internal space;
   a per-name internal-space histogram collapses to the single value 1. Exactly
   one seed name contains an apostrophe, record 34, `Lucious O'Connell`. **Zero
   seed names contain a digit.** So the apostrophe case is sampled verbatim,
   while the digit case has to be authored.

### 8.1 The complete list of authored values

- **Names, two cases only.** The two blank patterns in
  `card-rule-reject-name-blank.txt` (50 spaces, and 50 ASCII `0`), and
  `Britney Waters 2` in `card-rule-reject-name-non-alpha.txt`. The latter is
  derived from seed record 35, `Britney Waters`, by appending a digit -- which
  is what makes it plainly synthetic rather than identity-shaped, and is also
  the minimum edit that reaches the non-alphabetic branch. Every other embossed
  name in this directory is a verbatim seed value.
- **Account ids, three values.** `00000000901`, `00000000902` and
  `00000000903`, used only in `card-by-account-corpus.txt`. They sit
  deliberately outside the seed's `00000000001` to `00000000050` range so they
  can never be confused with a seed account or collide with one loaded from it.
- **Status bytes**, two values in four places, per measurement 1. `N` on the
  single record of `card-valid-inactive.txt`, on fixture rows 3, 10 and 17 of
  `card-list-page-corpus.txt`, and on fixture rows 2 and 4 of
  `card-boundary-expiry-inclusive.txt`; `X` on the single record of
  `card-schema-reject-status-out-of-domain.txt`. Measured directory-wide with
  `cut -c91 *.txt | sort | uniq -c`, that is **six** `N`, **one** `X` and
  **thirty-three** `Y` across the 40 records. Every status byte not named here
  is the seed's own `Y`.
- **Expiry dates, component by component.** Two fixtures author only the
  component their scenario needs and keep every other component of their seed
  date, which is what makes those authored dates checkable against the seed
  position they came from rather than merely plausible. The other two author the
  whole date, and each says below why its scenario cannot be expressed any other
  way. All four are enumerated, so a reader can check every authored date rather
  than only the ones that happen to be partial:
  - `card-boundary-expiry-inclusive.txt` authors the whole date on all four
    records -- `2024-01-15`, `2024-12-15`, `1950-06-15` and `2099-06-15` -- and
    is one of the two fixtures that does. Each record varies **exactly one**
    component away from a fixed background of year `2024`, month `06` and day
    `15`: row 1 carries month `01` and row 2 month `12`, the two inclusive month
    bounds, both at year `2024`; row 3 carries year `1950` and row 4 year
    `2099`, the two inclusive year bounds, both at month `06`. The day is `15`
    on every record and is never the varying component.
    Trade-offs: the reason the day is not sampled from the seed here is that keeping
    each seed record's own day would have let a single record carry a boundary
    year *and* a boundary month *and* a distinct day at once, so a failing
    assertion could not name which component caused it. Pinning the background
    buys unambiguous attribution at the cost of a date that no longer matches
    its seed position component for component -- an acceptable cost, because
    every identity-shaped field on these rows still traces to seed records 23-26
    verbatim, and day coverage is `card-expiry-day-preserved.txt`'s job under
    section 6. Day `15` is 28 or lower and month `06` has 30 days, so no authored
    combination can land on a date that does not exist; each of the four parses
    as a real calendar date and so is accepted by the `DATE` column.
  - `card-rule-reject-expiry-year-out-of-range.txt` authors the whole date on
    both records -- `1949-06-15` and `2100-06-15` -- and shares the fixed
    background of month `06` and day `15` with the four rows above, because it is
    the reject half of the same year-boundary pairing and has to vary the same
    single component. Its year is the one component that moves: `1949` is one
    below the inclusive lower bound and `2100` one above the inclusive upper
    bound, against the `1950` and `2099` that rows 3 and 4 of the inclusive
    fixture accept.
    Trade-offs: keeping each seed record's own month and day instead -- seed 36's
    `05-19` and seed 37's `06-04` -- would have left `1949-05-19` facing
    `1950-06-15` with three components differing at once, so an implementation
    that rejected on the month, or that read the date at the wrong offset, would
    satisfy a year assertion just as convincingly as a correct one. The accepted
    cost is a date that no longer matches its seed position component for
    component, which is the identical cost paragraph one of this section accepts
    for the inclusive fixture; every identity-shaped field on both rows still
    traces to seed records 36-37 verbatim. Day `15` is valid in every month and
    June has 30 days, so both authored dates parse as real calendar dates and are
    accepted by the `DATE` column -- which is what keeps this fixture Class A-R
    rather than Class B.
  - `card-schema-reject-expiry-month-out-of-range.txt` authors the MONTH only:
    `2025-00-12` and `2023-13-23`, keeping seed 39's year and day and seed 40's
    year and day.
  - `card-expiry-day-preserved.txt` authors the whole date on all three records
    -- `2025-06-01`, `2025-06-15` and `2025-06-28` -- and is the other of the
    two that does. Section 6 is the reason: the fixture must hold one FIXED
    month and year with three DIFFERENT days, and measurement 2 shows the seed
    offers no month and year pair with three such records to sample. The year
    and month are inside the seed's own observed ranges (2025 and 06 both occur
    in the seed), and the three days are 01, 15 and 28, each valid in every
    month, so the fixture tests day preservation rather than calendar
    arithmetic.

  Every other expiry date in this directory -- the whole of the page corpus, the
  by-account corpus, `card-valid-active.txt`, `card-valid-inactive.txt`, and both
  name-reject fixtures -- is its seed record's date verbatim.

Nothing else is authored. In particular, no card number and no card
verification value in this directory was generated.

Refactoring Rationale: the two bullets above were previously one line each,
stating only that status bytes and expiry dates are authored "where a scenario
needs" one. That was true and not checkable, and it was also incomplete: it
scoped authored dates to "a bound or an out-of-range value", which does not
cover `card-expiry-day-preserved.txt` at all -- its dates are neither. Since the
whole point of this section is that a reader can verify every authored value
against a seed position, an attestation that omits three authored dates fails at
exactly the thing it exists to do.

**No secret, credential, endpoint, ARN, cloud account identifier or other
sensitive value appears in any fixture in this directory, or in this file.** The
card verification value is present in the seed bytes because the record layout
places it there; the target stores that column encrypted and returns it from no
endpoint, so a fixture carrying it never becomes a response.

## 9. Decision justifications

Rule 1 requires each non-obvious decision to document at least one of four named
categories, and it forbids vague rationales. Each justification below therefore
names its category using the rule's own labels and cites a measured fact, a path
or a line rather than a preference. These are the choices in this directory where
a competent reader would reasonably have expected something else.

### 9.1 Flat layout, with no scenario subdirectories

Alternatives Considered: the house shape is
`tests/fixtures/<domain>/<scenario>/<record>.txt`, and measured against the
checkout there are 20 such scenario directories. The sibling account-service
fixture tree is assigned a comparable subdivision by record type. Both of those
partition **multiple** record types or multiple named scenarios that each need
several files. This directory has exactly **one** record type, `CVACT02Y`, and
one file per scenario, so a subdirectory level would partition nothing while
adding a path segment in which a rename could silently unpoint a consumer. The
scenario name is already carried by the filename, where a consumer reads it.

Assumptions: fixtures resolve by classpath name from `fixtures/**`. Neither
this module's nor the sibling's test configuration declares a fixture path, so
the filename is the binding contract between this directory and its consumers.
Renaming a fixture is therefore a breaking change and must be done together with
its consumer.

### 9.2 Why the loadability guard lives here and not only in the consumers

Alternatives Considered: documenting the taxonomy solely in the Javadoc of each
consuming test class, which is what the sibling account-service fixture tree
does. Rejected here because this directory carries a **physical** loadability
taxonomy: inserting a Class B row produces a check-constraint violation or a
date-parse error rather than the validation failure the test intended. A guard
against that has to be readable while choosing a fixture, which happens before
any consumer exists to read, and it has to be stated once for the whole
directory rather than repeated in each consumer that happens to load a row.

Trade-offs: one more file that has to be kept in step with the fixtures, and
which duplicates a little of what a consumer's Javadoc says about the row it
loads. Accepted, because the alternative leaves the Class B insertion hazard
discoverable only from inside a consumer that already made the mistake.

### 9.3 No `LOW-VALUES` fixture, although the blank branch accepts three patterns

Alternatives Considered: add a third record whose name field is 50 NUL
bytes, which would exercise the `LOW-VALUES` arm of the test at
`app/cbl/COCRDUPC.cbl:811`. Rejected because an embedded `0x00` makes
`awk '{print length($0)}'` unreliable and would compromise the byte-verification
command in [section 11](#11-verification) that every other fixture in this
directory depends on. The `SPACES` arm reaches the identical branch, the
identical flag `FLG-CARDNAME-BLANK` and the identical message, so no rule branch
and no message goes uncovered.

Trade-offs: one of the three blank byte-patterns is unrepresented, in
exchange for a verification command that works uniformly on every file here.
The uncovered pattern is a byte encoding, not a behaviour: all three arms of the
`OR` at `:811-813` converge on the same two lines, `:815` and `:817`.

### 9.4 No multi-internal-space name fixture

Assumptions: the rule at `app/cbl/COCRDUPC.cbl:822-830` converts every
alphabetic character to a space and then tests the trimmed length for zero.
Its outcome is therefore invariant to how many internal spaces a name contains
-- one space or five, the trimmed length is zero either way. Measured, all 50
seed names carry exactly one internal space. A two-space fixture would add a
filename without adding a rule branch, so it is omitted deliberately rather than
overlooked.

### 9.5 No separate mixed-case-name fixture

The case-insensitivity requirement is met by `card-valid-active.txt` rather than
by a dedicated file. Assumptions: its name is seed record 30,
`Layla Ullrich`, which is Title Case -- uppercase initials with a lowercase
remainder. That is precisely the shape that
`app/cbl/COCRDUPC.cbl:1499-1501` uppercases before comparing, so the canonical
happy-path fixture already carries the case-insensitive change-detection case.
A second fixture differing only in capitalisation would assert the same code
path twice.

### 9.6 Three loadability classes rather than two

Alternatives Considered: a simple valid/invalid split, which is what most
fixture directories use. Rejected because it conflates two materially different
failures that need different test setups. Year `1949` is a value the schema
stores without complaint -- `1949-06-15` is a valid `DATE` -- and only the
validator objects. Status `X` is a value the schema refuses outright, under the
named `ck_cards_active_status` constraint at `V1__card.sql:366`. Collapsing the
two would let a consumer insert a Class B row and receive a constraint error
where a validation assertion was intended, and the resulting failure names the
database rather than the rule that was actually under test.

Trade-offs: a third label is one more thing to learn before using this
directory. Accepted, and mitigated by encoding the class into the filename
prefix so it does not have to be looked up.

### 9.7 Eighteen records in the page corpus

Trade-offs: 15 records would give two pages plus a remainder and cross only
one interior boundary cleanly. Eighteen gives three pages at page size 7 -- 7
plus 7 plus 4 -- which yields two interior boundaries, each traversable in both
the forward and the backward direction, and exercises the read-one-extra
lookahead at `app/cbl/COCRDLIC.cbl:1284-1285` at both of them. The cost is 3
extra records to maintain. Eighteen also sits inside the 15-to-20 range this
fixture set is scoped to, so the corpus stays small enough to read by eye.

### 9.8 The zero-card account is represented by absence

Account `00000000903` appears in **no record** of `card-by-account-corpus.txt`,
and that is deliberate rather than an omission.

Assumptions: the access path this fixture exercises is declared
`NONUNIQUEKEY` at `app/jcl/CARDFILE.jcl:86`, so an account-to-card relation of
zero, one or many is all legitimate, and zero is the cardinality a query has to
handle without a row to read. An account-filtered browse
returns an empty page rather than an error for such an account, so the only way
to fixture that case is to query an id the file deliberately does not contain --
adding a record for `00000000903` would destroy the very case it was meant to
cover.

Stating this explicitly matters because the register row for this fixture reads
4 records against three named accounts, and `3 + 1 + 0 = 4`. A reader
reconciling those numbers without this note would conclude a record had been
lost. It has not: the third account's contribution is the absence itself.

### 9.9 Card numbers are disjoint across every fixture

Assumptions: `card_num` is the primary key of `card.cards`
(`CONSTRAINT pk_cards PRIMARY KEY (card_num)` at `V1__card.sql:355`), so two
fixtures loaded into the same table in the same test must not collide. Each
fixture therefore draws a distinct, contiguous run of seed record positions --
1-18, 19-22, 23-26, 27-29, 30, 31, 34-35, 36-37, 38, 39-40 and 41-42 -- and the
runs do not overlap. Across the whole directory that is 40 records carrying 40
distinct card numbers, which lets any combination of these fixtures be loaded
together without a uniqueness failure. Seed positions 32, 33 and 43-50 are
unused and are the pool to draw from when adding a fixture.

## 10. Where the docstring obligation actually attaches

Per [section 1](#1-why-this-file-exists), Rule 1's docstring obligation attaches
to functions, classes and module entry points -- so for these fixtures it
attaches to the **test class that loads them**, in
`services/card-service/src/test/java`. That is where a per-fixture docstring
belongs, and this file does not relieve whoever writes it.

There are two kinds of consumer here, and only one kind is still outstanding.

**The contract consumer exists.** `CardFixtureContractTest`, in
`services/card-service/src/test/java/com/carddemo/card/fixtures/`, reads every one of
the twelve files in this directory and asserts the invariants this README declares
rather than any business behaviour. Measured from the class, it asserts: that every
fixture divides into whole 150-byte records at the expected record count
(`everyFixtureHoldsWholeRecords`); that the empty fixture is exactly zero bytes
(`theEmptyFixtureIsZeroBytes`); that the valid pair differs only in status
(`theValidPairDiffersOnlyInStatus`); the expiry-year, month and day-preservation
boundaries (`theExpiryBoundaryFixtureBracketsTheAcceptedYears`,
`theMonthBoundaryFixtureBracketsTheRange`,
`theDayPreservedFixtureKeepsThreeDistinctDays`); that the two name-reject fixtures
carry two distinct reject kinds (`theNameRejectFixturesCarryTwoDistinctKinds`); the
by-account grouping and the page ordering
(`theByAccountCorpusGroupsCardsUnderOneAccount`,
`thePageCorpusExceedsOnePageAndIsOrdered`); that the trailing filler is dropped on
decode (`aBlankFillerIsDroppedFromEveryRecord`); that the sensitive fields decode
unmasked at this layer (`theSensitiveFieldsDecodeUnmasked`); and two negative guards —
a record at the wrong width is refused, and an absent fixture name fails loudly
(`aRecordAtTheWrongWidthIsRefused`, `anAbsentFixtureNameFailsLoudly`).

**The behavioural consumers do not exist.** Those are the four the package
documentation names -- `CardControllerTest`, `CardListServiceTest`,
`CardUpdateServiceTest` and `CardRepositoryIT` -- together with the card mapper test,
which is named here only. **None of those five exists yet.** For each fixture one of
them loads, that consumer's Javadoc should state the fixture's purpose, its provenance
(`app/cpy/CVACT02Y.cpy`, plus the seed record positions from the register in
[section 4.1](#41-the-register)) and the byte layout it relies on -- and, for a
Class B fixture, that it must not be inserted.

- Refactoring Rationale: an earlier revision of this section said none of five
  consumers existed and that the only two test classes present read nothing from this
  directory. Both halves have to be corrected together, because correcting only the
  count would leave the more misleading half standing. Assumptions: the distinction
  that matters to a reader is **contract versus behaviour**, not how many classes
  there are: the fixtures' shape is now guarded, so a malformed fixture fails a build,
  while what the fixtures *mean* is still unasserted, so a fixture that encodes the
  wrong rule still fails nothing.

The package documentation under `com/carddemo/card/service` names those four
consumers -- measured, it cites each of them -- and names this README among their
references. Whoever writes one
should leave no fixture it loads undocumented, and should treat the register in
section 4.1 as the checklist.

**Gate note, so that nobody mistakes where the enforcement comes from.**
`config/checkstyle/checkstyle.xml:185` sets
`<property name="fileExtensions" value="java"/>`, so a `.txt` or `.md` file in
this directory is already outside the Checkstyle scan entirely. There is also a
suppression covering `src/test/resources/fixtures/` at
`config/checkstyle/suppressions.xml:250`, and its own accompanying comment
describes it as belt-and-braces for exactly that reason. Treat it as
**defensive, not load-bearing**: do not rely on it, do not widen it, and do not
re-declare or relax the Javadoc gate on its account. The gate applies to the
consuming Java, and it should.

## 11. Verification

These commands verify **shape, not behaviour**. Passing them means a fixture is
well-formed against [section 3](#3-file-invariants); it says nothing about
whether the fixture encodes the business rule it claims to. That part is
verified by the consuming test.

They are written **against a fixture that exists**, and every register row now is,
so each command below has an input. Substitute a real filename for `<file>`.
Measured across all twelve fixtures on this branch, the shape checks below return:
one width group of **150** for every non-empty file, a byte count of exactly
**151 x record_count** for each, **zero** carriage returns in the directory, **40**
distinct card numbers across **40** records, and no record beginning with a comment
character. `card-empty-input.txt` is the one file the width and size checks report
nothing for, by design -- it holds zero records, which is the property it exists to
carry.

```sh
# every record is exactly 150 data bytes
awk '{print length($0)}' <file> | sort | uniq -c      # must be one group: 150

# file size is exactly 151 * record_count
wc -c <file> ; grep -c '' <file>

# LF only, zero CR
tr -cd '\r' < <file> | wc -c                          # must be 0

# no fixture begins with a comment character
head -c1 <file>                                       # must be a data byte, never # or -
```

`card-empty-input.txt` is a 0-byte file, so the `awk` check yields no groups at
all for it and `grep -c ''` yields 0. That is correct and expected, not a
failure: `151 * 0 = 0`.

To check the filler invariant on a populated fixture:

```sh
# FILLER at 92-150 must be 59 spaces on every record
cut -c92-150 <file> | tr -d ' \n' | wc -c             # must be 0
```

### 11.1 Build commands

- `mvn -f services/card-service/pom.xml validate` runs this module's Checkstyle
  gate on its own, without compiling tests. Note that `-DskipTests` skips test
  execution but does **not** skip Checkstyle, so a Javadoc violation still fails
  a build invoked that way.
- `mvn -f services/pom.xml clean verify` builds this module after `common-lib`,
  running Surefire for `*Test` classes and Failsafe for `*IT` classes.

Both commands are given so a reader can run them. Neither is reported here as
having passed, because a README is not evidence of a build result -- read the
build output for that.

---

Reference sources cited by this document, all read and never modified:
`app/cpy/CVACT02Y.cpy`, `app/cbl/COCRDUPC.cbl`, `app/cbl/COCRDLIC.cbl`,
`app/cbl/CBACT02C.cbl`, `app/cpy-bms/COCRDUP.CPY`, `app/cpy-bms/COCRDLI.CPY`,
`app/cpy/CSSETATY.cpy`, `app/jcl/CARDFILE.jcl`, `app/data/ASCII/carddata.txt`,
`tests/README.md` and `tests/fixtures/README.md`. The migration adds a path
beside the mainframe one; it removes nothing, and these sources remain the
specification.
