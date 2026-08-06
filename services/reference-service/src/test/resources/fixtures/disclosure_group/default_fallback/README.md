# Disclosure-group fixtures - DEFAULT-group fallback (CBACT04C, VSAM status 23)

This directory is the **data-in** side of one scenario. It holds the fixed-width
record bytes a consumer reads, and it authors no expected output. It holds
exactly two files, and nothing else belongs in it:

| File | Role |
|---|---|
| `discgrp.txt` | the 50-byte disclosure-group records this scenario is built from |
| `README.md` | this file: the Explainability carrier for those bytes |

A 50-byte fixed-width record file cannot carry a comment. Every byte position is
data, so a `#` character would be a value rather than an annotation, and a
comment line would be a wrong-length record that `FixedWidthCodec` rejects
outright. This README is therefore the only place the reasoning behind those
bytes can live. **User-specified Rule 1 L15** requires a docstring on every
module entry point, and **Rule 1 L27** requires the explanation to sit adjacent
to what it explains, which is why the justification for one scenario's bytes
belongs in that scenario's own directory. Two further authorities word the same
obligation independently: the house tree at `tests/fixtures/README.md` section
9.1 (L695 to L719), stated as `MUST`, and the tree-scope charter at
[`../../README.md`](../../README.md) section 8.4, which carries it downward.

This file would not be in scope from the migration requirements alone. It exists
because Rule 1 requires it.

## How this document discharges Rule 1

The gate at **Rule 1 L43** is conjunctive: an artifact needs both the docstring
elements and a recorded reason for each non-obvious choice, and missing either
half fails review. Its triad is purpose, parameters and return values; the
exceptions element is asked for separately at **L21**, where it applies.

| Rule 1 element | Where this document discharges it |
|---|---|
| Purpose (L18) | section 1, the scenario intent, and section 4, the exact business rule |
| Parameters (L19) | section 6.1, per field: offset, `PICTURE`, fill character and copybook line |
| Return values (L20) | section 2.3, the computed value, and section 8, the consumers and what they assert |
| Exceptions (L21) | section 9, the failure modes these bytes can produce |

Rule 1 lists four justification categories at L31 to L34. **One of the four is
unavailable to this document**: L32 scopes `Refactoring Rationale` to replacing
existing code, and these record bytes replace no earlier encoding of anything.
The canon is four; the usable set here is three. Every recorded reason below is
therefore labelled `Alternatives Considered:`, `Assumptions:` or `Trade-offs:`.

---

## 1. Scenario intent, and what this README does not restate

**Intent.** These are the record vectors that exercise the **`DEFAULT`-group
interest-rate fallback** for the `reference-service` rate lookup by group, type
and category.

> **The byte contract lives one directory up.**
> [`../../README.md`](../../README.md) is the tree-scope derivation contract. It
> is the authority for the 0-based offset base (its section 3.1), the two
> opposite `FILLER` fill regimes (5.3), the overpunch tables and their worked
> vectors (5.4), the line-ending and single-trailing-newline rules (5.5 and 5.6),
> the acceptance arithmetic (5.7), the canonical justification labels (2.2), the
> per-scenario README obligation (8.4), the list of what must not be created in
> this tree (14) and the validation gates (13). **This README restates none of
> it and competes with none of it.** It records what *this* scenario does, and
> cites the charter for everything the charter already rules.

---

## 2. Worked expectation

### 2.1 The `07|0001` discriminator

The seed at `app/data/ASCII/discgrp.txt` holds **3 group ids x the same 17
(type, category) pairs = 51 rows**. The seventeen pairs are:

```text
01|0001  01|0002  01|0003  01|0004
02|0001  02|0002  02|0003
03|0001  03|0002  03|0003
04|0001  04|0002  04|0003
05|0001
06|0001  06|0002
07|0001
```

Measured across columns 11 to 50 of this fixture, **sixteen of those seventeen
pairs are byte-identical between `A000000000` and `DEFAULT   `, and exactly one
differs**:

| Pair | `A000000000` | `DEFAULT   ` | Decoded |
|---|---|---|---|
| `07\|0001` | `00150{` | `00000{` | 15.00 against 0.00 |
| the other sixteen | identical bytes | identical bytes | no difference to observe |

Alternatives Considered: exercising any of the other sixteen pairs. Rejected
because on those the two groups return byte-identical rates, so an assertion that
the fallback fired passes identically whether it fired or the account's own group
resolved directly - and it would keep passing if the fallback were removed
altogether. Pair `07|0001` is the only discriminator the seed offers, which is
the ruling the charter states at its section 6.3 and calls the single most
important fixture-design fact in the tree.

The difference at that pair is not merely a different number, it is presence
against absence. `app/cbl/CBACT04C.cbl` L214 guards the accrual with
`IF DIS-INT-RATE NOT = 0`, and that guard gates L215
`PERFORM 1300-COMPUTE-INTEREST` and L216 `PERFORM 1400-COMPUTE-FEES`. So at
`07|0001` the own-group rate of 15.00 satisfies the guard and an interest
transaction follows, while a `DEFAULT` rate of 0.00 makes the guard false and
**no interest transaction is produced at all**. A consumer therefore has a binary
observable to assert rather than two numbers to compare, which is the stronger
evidence of the two.

### 2.2 Design (b), co-present groups, and the design that was rejected

Both classifiers are present here: 17 `A000000000` rows and 17 `DEFAULT   ` rows.
The group id that misses arrives from the **request** - the key the caller
composes - and not from a gap in the file.

Alternatives Considered: design (a), a `DEFAULT`-only file paired with an account
whose group id is blank. That is the house design and it is correct in its own
folder: `tests/fixtures/README.md` section 6.2 (L587 to L592) records that every
seed ACCOUNT row carries a **blank** `ACCT-GROUP-ID`, so the seed path reaches
the fallback naturally, and section 7 (L647 to L652) sanctions the arrangement
explicitly as a deliberate omission compatible with per-scenario
self-containment. It is unavailable here because its mechanism needs an account
record: the house folder carries `acctdata.txt`, `cardxref.txt` and
`tcatbal.txt` beside its `discgrp.txt`, whereas **this folder carries two files
and no account record, and must not acquire one.** `reference-service` owns the
disclosure-group side only; accounts belong to `account-service` and `CBACT04C`
to `batch-service`, so an account record here would place another service's data
in this tree. Design (a) also establishes a different claim - resolution by
fallback - rather than a rate difference, so it is not a weaker version of this
design but a separate one.

Alternatives Considered: including the third seed classifier `ZEROAPR   `.
Omitted because `ZEROAPR` reads `00000{` at `07|0001`, byte-identical to
`DEFAULT` there, so a request naming `ZEROAPR` would produce the same 0.00 that
a fallback produces and the discriminating pair would stop discriminating.
`ZEROAPR` carries `00000{` on all seventeen of its seed pairs, which makes it the
seed's zero-rate control group and the right content for a scenario that wants
one.

### 2.3 The value a consumer computes from the own-group rate

`app/cbl/CBACT04C.cbl` opens paragraph `1300-COMPUTE-INTEREST.` at **L462**, and
the statement itself spans **L464 to L465**:

```text
COMPUTE WS-MONTHLY-INT
 = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
```

The divisor 1200 is twelve months times one hundred for the percentage, applied
in fixed point. A balance of `1000.00` at the `07|0001` own-group rate of `15.00`
yields exactly **12.50**, asserted exactly and with no floating-point tolerance.
That is the worked example `tests/fixtures/README.md` section 6.2 (L570 to L580)
states, and the charter repeats it at its section 6.3.

Assumptions: AAP Rule T4 (Arithmetic order is preserved) applies literally to
those two lines. Multiply at full precision first, then divide with an explicit
scale and rounding mode. Dividing first and multiplying second changes the
intermediate precision and produces different cents on many inputs, so the order
in the source is part of the contract rather than an incidental formatting of it.

### 2.4 Why these rows come from the ASCII seed and not from the EBCDIC one

The dataset ships in two seed forms, and they do not agree at the discriminating
pair - only one of them can discriminate at all:

| Seed form | Bytes at `DEFAULT   ` type `07` category `0001` | Decoded |
|---|---|---|
| `app/data/ASCII/discgrp.txt` (51 rows, 2601 bytes, 0 CR) | `00000{` | 0.00 |
| `app/data/EBCDIC/AWS.M2.CARDDEMO.DISCGRP.PS` (51 records, 2550 bytes, no line terminators) | `00150{` | 15.00 |

Assumptions: these bytes are copied from the ASCII form, and that choice is what
makes the scenario possible. In the EBCDIC extract both `A000000000` and
`DEFAULT` read 15.00 at `07|0001`, so a fixture derived from it would carry no
discriminating pair whatsoever while still passing every length, line-ending and
overpunch check. This is an observed property of the two baseline datasets,
recorded here so that a later author re-deriving these rows chooses the same
form deliberately rather than losing the discrimination silently. `ReferenceFixtureTest`
pins the ASCII reading, so the loss would surface as a failing assertion rather
than as a test that quietly proves less.

---

## 3. Overpunch self-check

The charter's section 5.4 is the normative statement of this encoding. This
section decodes the literals that actually occur in *this* file, so the check can
be made without leaving the directory.

| Sign | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|---|---|---|---|---|---|---|---|---|---|---|
| positive | `{` | `A` | `B` | `C` | `D` | `E` | `F` | `G` | `H` | `I` |
| negative | `}` | `J` | `K` | `L` | `M` | `N` | `O` | `P` | `Q` | `R` |

**Position in the table is the digit value**, and the overpunch replaces the
**low-order digit only**, never the whole field. The same two strings appear in
the Python oracle at `tests/helpers/record_codec.py` L137 and L138.

`V` in `S9(04)V99` marks an **implied** decimal point that occupies **zero
bytes**. **No literal `.` is stored anywhere in this file**, and the field is six
bytes wide rather than seven.

Three raw rate literals occur here, and the counts are measured from this file
rather than inherited from the seed:

| Literal | Decodes to | Rows in this file |
|---|---|---:|
| `00000{` | 0.00 | 13 |
| `00150{` | 15.00 | 15 |
| `00250{` | 25.00 | 6 |

Walking the middle one, which is the charter's own worked vector and the one this
scenario turns on: `15.00` gives the six digits `001500`; the first five bytes
carry `00150`; the sixth digit is `0` with a positive sign, which overpunches to
`{`; the encoded field is therefore `00150{`.

Assumptions: `DIS-TRAN-CAT-CD` is unsigned `9(04)` and carries **no overpunch**.
A trailing letter is a sign byte only in a signed field, so overpunch decoding
must never be applied to a `9(n)` field - doing so reads a digit as a sign and
returns a category code that is wrong without being malformed. Both oracles
declare exactly that distinction rather than leaving it to the reader:
`record_codec.py` uses `Field("DIS-TRAN-CAT-CD", 12, 4, "uint")` beside
`Field("DIS-INT-RATE", 16, 6, "zoned", 4, 2, True)`, and `CopybookLayout` uses
`uint("DIS-TRAN-CAT-CD", 12, 4)` beside `signedZoned("DIS-INT-RATE", 16, 4, 2)`.

Assumptions: AAP Rule T3 (Money never leaves fixed point) governs the rate field.
The stored zoned form is the contract, so writing the rate as a float-looking
literal - five characters and a point where six bytes are declared - would supply
the wrong width and shift every byte after offset 16.

---

## 4. Mechanism, and the exact business rule exercised

**The rule.** The `CBACT04C` DEFAULT-group fallback on **VSAM status 23**: a rate
lookup whose key is `ACCT-GROUP-ID + TRAN-TYPE-CD + TRAN-CAT-CD` and that misses
is retried once with the group id replaced by `'DEFAULT'`, and the rate that
reaches the accrual is the `DEFAULT` group's rate.

Key trace, with every line verified against `app/cbl/CBACT04C.cbl`:

```text
L210-L212  build the read key from ACCT-GROUP-ID + TRANCAT-TYPE-CD + TRANCAT-CD
L213       PERFORM 1200-GET-INTEREST-RATE          (call site, in the main loop)
L415         1200-GET-INTEREST-RATE.               (paragraph header)
L416-L420      first READ DISCGRP-FILE ... INVALID KEY
                 -> 'DISCLOSURE GROUP RECORD MISSING' / 'TRY WITH DEFAULT GROUP CODE'
L422           IF DISCGRP-STATUS = '00' OR '23'    (status 23 is deliberately NOT an error here)
L436-L439      IF DISCGRP-STATUS = '23'
                 MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID
                 PERFORM 1200-A-GET-DEFAULT-INT-RATE
               END-IF
L443         1200-A-GET-DEFAULT-INT-RATE.
L444           second READ DISCGRP-FILE            (the re-read, now keyed on 'DEFAULT')
L455 / L458    if the DEFAULT rows are absent: 'ERROR READING DEFAULT DISCLOSURE GROUP'
                 -> PERFORM 9999-ABEND-PROGRAM
L628-L632    9999-ABEND-PROGRAM: L631 MOVE 999 TO ABCODE; L632 CALL 'CEE3ABD'
L214           IF DIS-INT-RATE NOT = 0             (gates the compute; a 0.00 rate produces nothing)
L215           PERFORM 1300-COMPUTE-INTEREST
L464-L465        COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
```

The status test at L422 is what makes this a fallback rather than an error path:
`'23'` is accepted alongside `'00'`, so a miss continues into the retry instead of
terminating.

The terminal path at L628 to L632 is a genuine Language Environment abend - L631
moves **999** into `ABCODE` and L632 issues `CALL 'CEE3ABD'`. That is why the
seventeen `'DEFAULT   '` rows in this file are mandatory rather than
conventional. The charter records at its section 6.3 that a `discgrp` fixture
which omits the `DEFAULT` group is asserting a crash, and that such a fixture's
README must say so. **This file carries those rows, so it asserts a value rather
than an abend.**

---

## 5. Paragraph roles in `CBACT04C`

| Paragraph | Line | Role |
|---|---:|---|
| main-loop call site | 213 | performs the rate lookup for the current category balance |
| `1200-GET-INTEREST-RATE` | 415 | first keyed read; treats status `00` or `23` as non-error at L422 |
| `1200-A-GET-DEFAULT-INT-RATE` | 443 | re-reads on the `'DEFAULT'` key after L437 rewrites the group id |
| `1300-COMPUTE-INTEREST` | 462 | applies the formula at L464 to L465; reached only when the L214 guard holds |
| `1400-COMPUTE-FEES` | 518 | body is the single comment `* To be implemented` at L519, so no fee is produced |
| `9999-ABEND-PROGRAM` | 628 | terminal path taken when the `DEFAULT` rows are absent |

`tests/fixtures/README.md` section 6.2 (L594 to L598) records the same property
of the fee paragraph and instructs that fee output is not to be fabricated. No
file in this directory carries a fee value, and none should acquire one.


---

## 6. Fixture files in this folder

| File | Copybook | `RECLN` | Line ending | Records | Purpose |
|---|---|---:|---|---:|---|
| `discgrp.txt` | `app/cpy/CVTRA02Y.cpy` | 50 | LF | 34 | the two co-present group rows across all 17 type/category pairs |

Governance, every figure measured from the file rather than predicted:

- **1734 bytes.** The charter's acceptance arithmetic at its section 5.7 is
  `bytes = rows x RECLN + rows`, so `34 x 50 + 34 = 1734`, and the file agrees.
- **Exactly one trailing newline**, so `wc -l` returns 34, which is the record
  count rather than a count one short or one over.
- **No blank lines** and **zero carriage returns**.
- **34 rows = 17 pairs x 2 groups.** The file subsets the 51-row seed **by group,
  never by pair**, so every pair a lookup can compose exists under both groups
  and no pair is reachable in one group but not the other.

Assumptions: **no line-ending normalisation decision arises for this dataset**,
and that is a measured fact rather than an omission. `discgrp` already ships LF
only, at 51 rows, 2601 bytes and zero CR bytes, so there is nothing to convert
and no choice to record; it is one of six LF-only seeds. Recording the absence
matters because the sibling reference domains do face that decision - the three
CRLF seeds are `tcatbal`, `trancatg` and `trantype` - so a reader arriving from
one of those folders would otherwise expect a conversion note here and read its
absence as an oversight. None of those three appears in this folder. The charter
rules line endings at its sections 5.5 and 6.1.

### 6.1 Per-field geometry, offsets 0-based

| Copybook line | Field | `PICTURE` | Offset | Length | Fill as measured here |
|---|---|---|---:|---:|---|
| L6 | `DIS-ACCT-GROUP-ID` (level 10, in `DIS-GROUP-KEY`) | `X(10)` | 0 | 10 | right-padded with spaces (`DEFAULT` plus three) |
| L7 | `DIS-TRAN-TYPE-CD` (level 10, in `DIS-GROUP-KEY`) | `X(02)` | 10 | 2 | two stored digits, occupying the full width |
| L8 | `DIS-TRAN-CAT-CD` (level 10, in `DIS-GROUP-KEY`) | `9(04)` | 12 | 4 | plain ASCII digits, no overpunch |
| L9 | `DIS-INT-RATE` | `S9(04)V99` | 16 | 6 | zoned decimal, overpunch on the low-order digit |
| L10 | `FILLER` | `X(28)` | 22 | 28 | twenty-eight ASCII `'0'` (0x30) |

Byte-count check: 10 + 2 + 4 + 6 + 28 = **50**.

Trade-offs: the table above states geometry the charter already tables at its
section 3.4, so the same five offsets now appear in two documents and could in
principle drift apart. That duplication is accepted because the charter's section
8.4 makes per-field offset, `PICTURE`, fill and copybook line the **Parameters**
element of a scenario README, which has to be discharged in the directory holding
the bytes it describes rather than by a pointer elsewhere. The drift risk is
bounded rather than merely tolerated: both tables cite a copybook line per field,
so `app/cpy/CVTRA02Y.cpy` settles any disagreement between them, and the charter's
section 5.8 rule still holds for code - executable geometry lives in
`CopybookLayout` alone, and neither table is a declaration a test may read from.

Two unrelated sources state that same geometry, which is worth recording because
an offset error here is silent rather than loud. `app/cpy/CVTRA02Y.cpy` declares
it structurally: its L2 banner reads `RECLN = 50`, L4 declares
`01 DIS-GROUP-RECORD.` and L5 declares `05 DIS-GROUP-KEY.`, which is what places
the first three fields inside one 16-byte key. `app/jcl/DISCGRP.jcl` states it
from a completely separate direction as a dataset definition: L40 `KEYS(16 0)`
and L41 `RECORDSIZE(50 50)`. The charter tables a third agreement, including the
executable layout, at its section 3.5.

Assumptions: offsets in this document are **0-based**, matching the charter's
declaration at its section 3.1 and both executable layouts - `CopybookLayout`
registers `new RecordSpec("DISGROUP", 50, 16, 0, ...)` with field offsets
0/10/12/16/22, and `record_codec.py` declares `DISGROUP_LAYOUT` at L1118 with the
same five offsets. The house document tables this same record **1-based** at its
section 5.7 (L437 to L450), as positions 1-10, 11-12, 13-16, 17-22 and 23-50.
Both describe identical bytes; the base is declared here because leaving it
implicit is a guaranteed one-byte error on every field rather than a stylistic
risk, and because the baseline itself uses both conventions.

Assumptions: `FILLER` at offset 22 carries **twenty-eight ASCII `'0'`, not
spaces** - measured uniform across all 34 rows here and all 51 in the seed. The
generic value-padding rule at `tests/fixtures/README.md` section 3.1 (L152 to
L156) right-pads text `X` fields with spaces, and applying that generic rule to
this `FILLER` would produce twenty-eight spaces: **a different 50 bytes that
still passes every length check**. The contrast lives inside a single record
here, which is why the rule cannot be applied per field class -
`DIS-ACCT-GROUP-ID` genuinely is space-padded, while `FILLER` twenty-two bytes
later is `'0'`-filled. The charter rules the two regimes at its sections 5.3 and
6.2, where `FILLER` is copied byte for byte rather than synthesised; the house
document records `'0'` fill where it applies at its section 5.6 (L432 to L435).

---

## 7. Determinism

The content of this directory is fixed and reproducible. Every byte is a literal
copied from `app/data/ASCII/discgrp.txt`, and no row is synthesised. There is no
timestamp, no generated identifier, no environment-derived value and no ordering
that depends on when the file is read, so two readers decoding it at different
times obtain identical field maps. `tests/fixtures/README.md` section 7 (L642 to
L646) states the same principle for the house tree.

Assumptions: the house step that blanks a processing timestamp and injects the
run date as a parameter has **no target in this record**. `CVTRA02Y` declares no
timestamp field at all, so there is nothing here for that step to act on, which
is the non-transfer the charter records at its section 8.5.

---

## 8. Expected outcome, and who reads these bytes

**The outcome, as specific values.** At `07|0001` the own-group rate decodes to
`BigDecimal` **15.00** and the `DEFAULT` rate to **0.00**. A balance of 1000.00 at
15.00 accrues exactly **12.50**. And because the L214 guard tests the rate
against zero, a fallback resolving to 0.00 produces **no** interest transaction
rather than a zero-valued one.

**Who reads them, verified against the branch.** Two consumers exist in
`com.carddemo.reference.fixtures`, and each resolves this file from the test
classpath as `fixtures/disclosure_group/default_fallback/discgrp.txt`:

| Consumer | What it asserts about this file |
|---|---|
| `ReferenceFixtureContractTest` | 34 records at the declared length of 50; that exactly one of the three seed classifiers is omitted and that the omitted one is `ZEROAPR   `; that 17 rows carry `DEFAULT   `; that the `DEFAULT` rate is nonzero at type `01` category `0001` |
| `ReferenceFixtureTest` | that both groups price the same seventeen pairs; that exactly one pair differs and that it is `07\|0001`; that the own-group rate there compares equal to 15.00 and the `DEFAULT` rate to 0.00; that no group prices `01\|0005` |

The three consumer kinds the charter names at its section 9.1 - `*RepositoryIT`,
`*ServiceTest` and `*ControllerTest` - are **not present in this module**, so for
those the contract stated in this README is what they must honour when they land.
The charter keeps that same distinction at its section 10, and this README follows
it rather than implying a broader consumer set than the branch carries.

**These bytes are not a database seed and must not be read as one.** The module's
test profile at `../../../application-test.yml` leaves Flyway enabled with
`locations: classpath:db/migration`, `schemas: reference` and
`create-schemas: true`, so `V1__reference.sql` and `V2__seed_reference.sql` both
run against an empty PostgreSQL container and **all 51 `disclosure_groups` rows,
including the seventeen `'DEFAULT   '` rows, are already present before any test
body executes**. This file loads nothing into that table. It is a **decode,
round-trip and business-rule vector set**.

Two consistency targets this folder matches and must never itself author:
`V1__reference.sql` gives `reference.disclosure_groups` the composite primary key
`(acct_group_id CHAR(10), tran_type_cd CHAR(2), tran_cat_cd CHAR(4))` with
`interest_rate NUMERIC(6,2)`. **The `CHAR(10)` is exactly why `'DEFAULT   '` is
space-padded to ten bytes here** - the fixture width and the column width are one
contract seen from two sides, and a seven-byte `'DEFAULT'` would match neither.
`NUMERIC(6,2)` carries the same precision and scale as the `S9(04)V99` field the
overpunch encodes.

The byte-level consumers live in
`services/common-lib/src/main/java/com/carddemo/common/codec/`:

- `CopybookLayout` declares the `DISGROUP` layout - record length 50, key length
  16, key offset 0 - and is where a test obtains geometry rather than restating an
  offset copied out of a table.
- `ZonedDecimalCodec` owns the `DIS-INT-RATE` overpunch decode, and offers
  `decodePreservingSign` with `encodePreservingSign` where the exact sign byte of
  a zero must survive a round trip.
- `FixedWidthCodec` composes the two and is **fail-closed** on length: any record
  that is not exactly `spec.reclen()` bytes raises `RecordLengthException` rather
  than decoding as far as it can.

`disclosure_group` is the one `reference-service` domain the Python oracle covers.
`tests/helpers/record_codec.py` declares `DISGROUP_LAYOUT` at L1118 and registers
it under `LAYOUTS` at L1352, so a `discgrp` fixture can be cross-checked against
that oracle; the registry carries no `TRANTYPE` key and no `TRANCAT` key, which
the charter records at its section 11.2. The oracle's own comment at L1114 to
L1117 explains the modelling these bytes are decoded with: `CVTRA02Y` groups the
first three fields under `DIS-GROUP-KEY`, and the oracle models them as three
separate leaf fields while exposing the 16-byte composite through key metadata,
keeping both the parts and the whole addressable.


---

## 9. Failure modes these bytes can produce

Rule 1 L21 asks for the errors an artifact can raise. For a fixture the answer is
what a mis-derivation does downstream, and the dangerous entries are the silent
ones - a loud failure costs a build, a silent one costs the assertion's meaning.

| Failure | What it actually produces |
|---|---|
| A record one byte short or long | `FixedWidthCodec` raises `RecordLengthException`. **Loud**, and the good case |
| One byte miscounted inside a record | Every field after the miscount shifts, so the rate is read from the wrong offset and decodes to a plausible wrong number. **Silent** |
| A blank line used to mean "no records" | A blank line is a zero-length record, not an absence, and it fails fixed-width parsing. Only a genuinely **zero-byte** file is empty, which the charter rules at its sections 5.2 and 6.5 |
| `FILLER` written as spaces | Correct length, correct line endings, wrong bytes. **Silent** until a round-trip or a schema comparison fails for an unrelated-looking reason |
| A carriage return surviving an edit | The record becomes 51 bytes and is rejected at load. **Loud** |
| Overpunch decoding applied to `DIS-TRAN-CAT-CD` | A digit is read as a sign and the category code is wrong. **Silent** |
| The overpunch read as the high-order rather than the low-order digit | The classic inversion of this encoding: the sign travels in the last byte, not the first |
| These rows re-derived from the EBCDIC seed form | Both groups read 15.00 at `07\|0001`, the discriminator disappears and the fallback assertion becomes vacuous while every geometry check still passes. **Silent**, which is why section 2.4 records the choice |
| A rate written with a literal `.` | Five characters and a point where six bytes are declared, so the field is the wrong width and everything after offset 16 shifts. Violates AAP Rule T3 |
| A `ZEROAPR   ` row added to this file | A request naming that group stops being distinguishable from a fallback result at the one pair that discriminates, per section 2.2 |

The charter tables the tree-scope set at its section 12. The rows above are the
subset reachable from *these* bytes.

---

## 10. Sources and scope

| Source | Role here |
|---|---|
| `app/cpy/CVTRA02Y.cpy` | the normative 50-byte layout; 13 lines, `RECLN` banner at L2 |
| `app/data/ASCII/discgrp.txt` | the byte source; 51 rows, 2601 bytes, 0 CR |
| `app/data/EBCDIC/AWS.M2.CARDDEMO.DISCGRP.PS` | the second seed form, and the reason section 2.4 names the ASCII one |
| `app/cbl/CBACT04C.cbl` | the behaviour; every line cited above was checked against it |
| `app/jcl/DISCGRP.jcl` | independent confirmation of the record and key widths at L40 and L41 |
| `tests/fixtures/README.md` | the house convention this tree inherits |
| `tests/fixtures/interest/default_fallback/README.md` | the closest structural precedent, followed for section order |
| `tests/helpers/record_codec.py` | the Python oracle that covers this layout |
| [`../../README.md`](../../README.md) | the tree-scope derivation contract, cited throughout |

**Every one of those sources is REFERENCE-ONLY: read and cited here, never
modified.** That holds for the whole of `app/**` and `tests/**`. It is also why
this document records what the baseline does rather than annotating the baseline
in place, and why every property described above is reported as an observed
property of it. Where the target implementation will differ, the divergence is
documented rather than asserted here.

**No attestation is owed by this folder, and that is a decision rather than an
oversight.** The house attestation obligation at `tests/fixtures/README.md`
section 10.3 (L818 to L831) attaches to scenario READMEs whose folder holds PAN
or identity data. `DIS-ACCT-GROUP-ID` is a **disclosure-group classifier** -
`A000000000` and `DEFAULT   ` are its only two values here - and it is **not an
account number**. The record carries no card number, no cardholder name and no
national or government identifier; its remaining fields are a two-digit type
code, a four-digit category code, an interest rate and twenty-eight bytes of
`FILLER`. This folder therefore holds no PAN and no identity-shaped data, and
there is nothing in it to attest to. The charter reaches the same conclusion by
domain at its section 8.6.

**Three sections of the structural precedent are deliberately absent from this
README.** The house document carries a second-account and final-flush narrative,
a coordination section for the shared mock at `tests/mocks/mock_discgrp.txt`, and
an `ASSIGN`-name mapping table. None has an analogue here: this folder holds no
account record and the interest program belongs to `batch-service`; no consumer
in this tree reads that mock; and the consumers here are Java and JPA, which have
no `FD` `ASSIGN` names to map. **No golden mirror is owed either, and none is to
be created.** The house mirroring requirement is conditioned on
`tests/helpers/golden_compare.py` pairing scenarios by path, a consumer this tree
does not have, which the charter rules at its section 6.7 and confirms as a
non-transfer at its section 8.5. No `.expected` file belongs in this directory.

**This README is rule-mandated.** User-specified Rule 1 requires it at L15 and
L27, the house tree words the same obligation `MUST` at its section 9.1, and the
charter carries it downward at its section 8.4. It would not be in scope from the
migration requirements alone. The directory holds exactly two files, and the
charter's section 14 is the authority for what must not be added to it.

