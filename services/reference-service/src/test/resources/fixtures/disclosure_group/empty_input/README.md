# `disclosure_group/empty_input` -- no rate rows at all

The degenerate case for the rate lookup: `discgrp.txt` is **present but genuinely
zero bytes**, so no disclosure-group row exists for **any** group. Both of the
lookup's resolution paths therefore miss -- the account's own group classifier
**and** the `DEFAULT` fallback the program substitutes when the first read comes
back not-found. This scenario *characterises the abend*. It is the honest,
auditable record of what `app/cbl/CBACT04C.cbl` does on a total lookup miss, not
an aspiration that it degrade gracefully.

In a directory whose data file holds no bytes, this README is the entirety of
what the derivation produced. There is no record to inspect, and a fixed-width
record file cannot carry a comment in any case, so everything a reader needs
about these zero bytes is here.

> **Read [`../../README.md`](../../README.md) first.** That tree-scope contract is
> authoritative for the 0-based offset base (its section 3.1), the
> `DIS-GROUP-RECORD` geometry (3.4), the exact-length rule and the sole definition
> of "empty" (5.2), the two opposite `FILLER` regimes (5.3), the zoned-decimal
> overpunch vectors (5.4), the trailing-newline rule and its one exception (5.6),
> the acceptance arithmetic (5.7), and both the `07|0001` and `01|0005` seed
> properties (6.3, 6.4). This file **references** that contract and neither
> restates nor contradicts it. What it records instead is the *why* behind this
> scenario's own non-obvious choices, which is user-specified Rule 1
> (Explainability) L27 applied where the bytes actually live.

---

## 1. Scenario intent

A total miss, proved by emptiness. The dataset the interest program opens is
valid and openable but holds zero records, so the keyed read finds nothing on the
account's own group, the program substitutes `DEFAULT` and reads again, and that
read finds nothing either. The scenario exists to pin the behaviour at the end of
that chain rather than to produce a rate.

Assumptions: the file is **committed and present at zero bytes** rather than
omitted from the directory. An absent dataset and an empty one are different
conditions to both consumers of this tree -- a class-path resolution failure
versus data -- and, in the baseline, they reach the abend from two different
paragraphs. Section 2.3 states that distinction with its line numbers.

---

## 2. The exact business rule it exercises

The `CBACT04C` rate lookup driven past **both** of its resolution paths. The
house document's own worked example of an exactly-stated business rule for this
program is *"`CBACT04C` DEFAULT-group fallback on VSAM status 23"*
[`tests/fixtures/README.md` L702-L704]; this scenario is that rule taken one step
further, to the case where the fallback itself has nothing to resolve against.

### 2.1 CRITICAL: why an empty `DISCGRP` abends the program (the central WHY)

The lookup key is the 16-byte `DIS-GROUP-KEY` -- `DIS-ACCT-GROUP-ID` plus
`DIS-TRAN-TYPE-CD` plus `DIS-TRAN-CAT-CD` -- and the file is declared
`ORGANIZATION IS INDEXED` with `ACCESS MODE IS RANDOM` at
`app/cbl/CBACT04C.cbl` L47-L51. **This is a keyed random read, not a sequential
scan**, which is the whole reason emptiness is not benign here.

| Step | Line | What happens with zero rows present |
|---|---|---|
| Key composed | L210-L212 | The group classifier, the category code and the type code are moved into the file key |
| Lookup entered | L213 | `PERFORM 1200-GET-INTEREST-RATE`; the paragraph header is at L415 |
| First keyed read | L416-L420 | `READ DISCGRP-FILE INTO DIS-GROUP-RECORD` with an `INVALID KEY` clause. With no rows at all it misses, emitting L418 `'DISCLOSURE GROUP RECORD MISSING'` and L419 `'TRY WITH DEFAULT GROUP CODE'` |
| First status test | L422 | `IF DISCGRP-STATUS = '00' OR '23'` -- not-found (`'23'`) is **tolerated**, so the program does not fail here and the L431 diagnostic is not reached |
| Fallback entered | L436-L439 | `IF DISCGRP-STATUS = '23'` moves the literal `'DEFAULT'` into the group id and performs `1200-A-GET-DEFAULT-INT-RATE` |
| Fallback read | L443-L444 | The paragraph re-reads the same file. There is **no `INVALID KEY` clause on this read**, so no further diagnostic is emitted -- and with no rows at all, **this read misses too** |
| Fallback status test | L446 | `IF DISCGRP-STATUS = '00'` -- only success is accepted. Not-found is **not** tolerated here |
| Failure reported | L455-L457 | `'ERROR READING DEFAULT DISCLOSURE GROUP'`, then the status is moved to `IO-STATUS` and rendered by `9910-DISPLAY-IO-STATUS` (L635-L648) |
| Abend | L458 | `PERFORM 9999-ABEND-PROGRAM` |
| Language Environment abend | L628-L632 | `'ABENDING PROGRAM'`, `MOVE 0 TO TIMING`, L631 `MOVE 999 TO ABCODE`, L632 `CALL 'CEE3ABD' USING ABCODE, TIMING` |

`ABCODE` and `TIMING` are declared `PIC S9(9) BINARY` at L138-L139, so the abend
code is a genuine numeric operand passed to the Language Environment abend
service and not a display field.

### 2.2 The asymmetry between L422 and L446 is the mechanism

**One miss is survivable and two are fatal, and the reason is a two-line
difference in what each status test accepts.**

| Read | Status test | Accepts | Consequence of not-found |
|---|---|---|---|
| First, L416 | L422 | `'00'` **or** `'23'` | Tolerated; drives the L436 fallback |
| Fallback, L444 | L446 | `'00'` only | Not tolerated; reaches L455 and L458 |

A reader who misses that asymmetry will not understand why an empty file abends
while a single missing key does not. The first read is *expected* to be able to
miss -- that is what makes a `DEFAULT` group meaningful at all -- and the second
read is the program's last resort, so it has nowhere left to fall back to.

### 2.3 Present, not absent: an empty file and a missing dataset are different failures

The file must be **present**. `0200-DISCGRP-OPEN.` at L270 does
`OPEN INPUT DISCGRP-FILE` at L272 and accepts only `'00'` at L273. An existing
empty file **opens cleanly** and then misses on read, which is the path
section 2.1 tables. A dataset that is not there fails at the `OPEN` instead and
abends from **L284** rather than L458 -- the same abend code by a different
route, reported by a different diagnostic. The house master makes the same point
in its own words at `tests/fixtures/README.md` L660-L661: an `empty_input`
scenario is authored *"so the program opens a valid (if empty) file rather than
failing on a missing dataset."*

The `DISPLAY` literal on that open path, at L281, names a different dataset than
the one the paragraph opens. That is recorded here purely as an **observed
property of the baseline**, because a reader comparing diagnostics against
paragraph names will notice it and should not have to wonder whether this
document missed it. `app/**` is reference-only per the migration plan section
0.2.2, so nothing about it is changed and no divergence is claimed on this point.

---

## 3. Expected outcome

**A Language Environment abend with `ABCODE` 999, raised by
`CALL 'CEE3ABD'` at `app/cbl/CBACT04C.cbl` L631-L632.** No interest rate is
resolved, no accrual is computed, and no statement of a rate is produced. The
expected outcome is a specific value, not a description of one: the abend code is
**999**, moved at L631.

### 3.1 The observable output sequence, in order

Reproduced character for character from the source, per the migration plan's
Rule T8 (user-visible strings are carried across verbatim):

| Order | Line | Emitted |
|---|---|---|
| 1 | L418 | `DISCLOSURE GROUP RECORD MISSING` |
| 2 | L419 | `TRY WITH DEFAULT GROUP CODE` |
| 3 | L455 | `ERROR READING DEFAULT DISCLOSURE GROUP` |
| 4 | L642 or L646 | `FILE STATUS IS: NNNN` followed by the rendered status |
| 5 | L629 | `ABENDING PROGRAM` |
| 6 | L631-L632 | `CALL 'CEE3ABD'` with `ABCODE` **999** |

Assumptions: the status in line 4 is whatever the L446 test rejected -- a keyed
read against a dataset holding no records reports not-found, which is status
`'23'` -- and `9910-DISPLAY-IO-STATUS` renders a numeric status into its
four-character form at L644-L646 rather than printing the two raw bytes. The
sequence is stated as the contract because an abend asserted only as "it fails"
is not an asserted value; the diagnostics are what make it one. The
`'ERROR READING DISCLOSURE GROUP FILE'` literal at L431 is **not** in this
sequence, because L422 tolerates not-found and that branch is never taken here.

### 3.2 Why this scenario matters: the `'DEFAULT'` seed rows are mandatory

This chain is the reason `V2__seed_reference.sql` seeds the 17 `'DEFAULT   '`
rows of `reference.disclosure_groups` and states so in its own header at L57-L58.
Omitting them would present as a crash in `batch-service` **with no local defect
of its own** -- the failing component would be reporting a data condition it did
not create. The tree-scope contract records the same consequence at its section
6.3 and derives from it the obligation that a fixture omitting the `DEFAULT`
group must say it is asserting an abend. This file says so.

### 3.3 The posting contrast: benign under a sequential read, fatal under a keyed lookup

**This single sentence is the best available explanation of why an `empty_input`
scenario exists in this domain at all.** In the sibling posting domain an empty
`DALYTRAN` drives `CBTRN02C` straight to end-of-file: the first read returns file
status `'10'`, no per-record work is ever performed, `WS-REJECT-COUNT` stays `0`,
the trailing reject test never fires, `RETURN-CODE` remains `0`, and **the
program does not abend** -- `tests/fixtures/posting/empty_input/README.md`
documents exactly that, including at its L72 and L77.

Same emptiness, opposite outcome. A sequential read-to-end-of-file treats "no
records" as a complete answer; a keyed random read treats it as a failed lookup,
and this program's fallback has no second answer to give.

---

## 4. Fixture bytes and governance

### 4.1 Per-file record width, record count and line ending

| File | Copybook layout | Record width | Records | Line ending | Content |
|---|---|---:|---:|---|---|
| `discgrp.txt` | `CVTRA02Y` `DIS-GROUP-RECORD` | 50 | **0** | none -- 0 bytes | **Empty -- a genuine 0-byte file (zero records). This is the load-bearing input of the scenario** (section 2.1) |
| `README.md` | -- | -- | -- | -- | this file |

**This directory holds exactly these two entries.** No third file, no subfolder,
and in particular no `.gitkeep`, placeholder comment, single space or lone
newline standing in for the empty file -- any of those would make it non-zero and
destroy the scenario.

The declared record width is stated even though no row occupies it, because it is
what the consumer passes when it asserts that nothing decodes: `50` is the
`reclen` in the parameter set at `ReferenceFixtureTest` L407 and L426.

### 4.1.1 Derivation, per file

- **`discgrp.txt`** -- **0 bytes, 0 records, no trailing newline, no carriage
  return.** Measured: `LC_ALL=C wc -c` returns `0`, `LC_ALL=C wc -l` returns `0`,
  and `od -An -tx1` produces no output at all. Nothing is derived from
  `app/data/ASCII/discgrp.txt` into it; the derivation is the decision to emit
  nothing, and section 7 records why that decision is not the only one available.

### 4.2 The record layout no row here occupies

A reader of this directory has to know **what is absent**, so the geometry is
reproduced here rather than only pointed at. From `app/cpy/CVTRA02Y.cpy`, 13
lines, whose banner at L2 declares `RECLN = 50`, whose L4 declares
`01 DIS-GROUP-RECORD.` and whose L5 declares `05 DIS-GROUP-KEY.` with the three
key fields at level **10** inside it:

| Copybook line | Field | `PICTURE` | Offset (0-based) | Length | Fill |
|---|---|---|---:|---:|---|
| L6 | `DIS-ACCT-GROUP-ID` (level 10, in `DIS-GROUP-KEY`) | `X(10)` | 0 | 10 | right-pad space |
| L7 | `DIS-TRAN-TYPE-CD` (level 10, in `DIS-GROUP-KEY`) | `X(02)` | 10 | 2 | right-pad space |
| L8 | `DIS-TRAN-CAT-CD` (level 10, in `DIS-GROUP-KEY`) | `9(04)` | 12 | 4 | left-pad `'0'` |
| L9 | `DIS-INT-RATE` | `S9(04)V99` | 16 | 6 | left-pad `'0'`, sign in last byte |
| L10 | `FILLER` | `X(28)` | 22 | 28 | ASCII `'0'`, copied verbatim |

Byte-count check: 10 + 2 + 4 + 6 + 28 = **50**. The first three fields are the
16-byte read key.

**Offsets above are 0-based**, matching the tree-scope contract's section 3.1
declaration and `CopybookLayout`'s `signedZoned("DIS-INT-RATE", 16, 4, 2)`. The
house master tables the same record **1-based** at its section 5.7 (L437-L450):
bytes `1-10`, `11-12`, `13-16`, `17-22`, `23-50`. Both descriptions are correct
for the same fifty bytes and only the base differs, which is worth a sentence
because a reader who does not know which base is in force is off by one on
**every** field.

Assumptions: this table is a **derived index that cites a copybook line for every
field**, exactly as the tree-scope contract characterises its own tables at
section 5.8. It is not a competing declaration. Where it and `CVTRA02Y.cpy` ever
disagree the copybook wins and this file is wrong, which is what the migration
plan's Rule T1 (copybook is normative) means in practice; and a consuming test
obtains geometry from `CopybookLayout.layout("DISGROUP")` rather than from an
offset copied out of the table above.

Three independent in-repo sources agree on this geometry, which is why an offset
here can be relied on: `CVTRA02Y.cpy` L2 declares `RECLN = 50`;
`app/jcl/DISCGRP.jcl` L40 `KEYS(16 0)` and L41 `RECORDSIZE(50 50)` state the same
50 bytes and the same 16-byte key at offset 0 as a dataset definition; and both
`CopybookLayout`'s `RecordSpec("DISGROUP", 50, 16, 0, ...)` and
`tests/helpers/record_codec.py` L1118 `DISGROUP_LAYOUT` (registered in `LAYOUTS`
at L1352, with `reclen=50`, `key_length=16`, `key_offset=0`, `DIS-TRAN-CAT-CD` as
`uint` and `DIS-INT-RATE` as `zoned, 4, 2, True`) declare the same per-field
0-based offsets.

### 4.3 Acceptance arithmetic, and the newline that is absent

The tree-scope contract's section 5.7 determines a file's size completely as
`bytes = rows x RECLN + rows`. Here it degenerates:

```text
bytes = 0 x 50 + 0 = 0
```

**The file measures exactly 0 bytes and holds exactly 0 rows.** As a confidence
check on the arithmetic itself, the populated seed `app/data/ASCII/discgrp.txt`
measures 51 rows and 2601 bytes, and `51 x 50 + 51 = 2601` exactly, with zero
carriage returns across the file.

There is **no trailing newline**. The general house rule at
`tests/fixtures/README.md` L176-L185 requires *"a single trailing newline after
the last record"*, and its stated reason at L181-L184 is that `wc -l` then equals
the record count, with the explicit warning that *"an empty line is a zero-length
'record' that will fail fixed-width parsing."*

Trade-offs: the general rule is honoured by being inapplicable rather than by
being followed. **There is no last record, so there is nothing to terminate**,
and adding a single newline anyway would make the file 1 byte holding one blank
line -- one zero-length record, which is precisely the failure the rule itself
warns about and which the exact-length loaders reject outright. The rule's own
purpose survives intact: `wc -l` returns `0`, which still equals the record count
of `0`. The tree-scope contract states the same exception at its section 5.6,
where the zero-byte file is named as the sole case that carries no trailing
newline. A reader should be able to see from this paragraph that the absence is a
decision with a reason, rather than an oversight.

### 4.4 The three audits that are not applicable to a zero-record file

Three of the tree's byte-level audits have **no row to inspect** here. They are
named and marked inapplicable rather than omitted or claimed:

| Audit | Tree gate | What it would require of a row | Status here |
|---|---|---|---|
| Width | 1 | Every physical line exactly 50 bytes | **Not applicable** -- zero lines |
| Fill | 3 | Twenty-eight ASCII `'0'` (0x30) at offset 22, copied byte for byte | **Not applicable** -- no `FILLER` region exists |
| Overpunch | 4 | `DIS-INT-RATE` a valid zoned-decimal sign overpunch, and one of the seed's three raw values unless the scenario states otherwise | **Not applicable** -- no rate field exists |

Assumptions: silently omitting these three would read as an oversight, and
recording them as *passing* would be a false assertion about bytes that do not
exist. Naming them and marking them inapplicable is the only honest third option,
and it is what lets a later reader distinguish "this was considered and does not
apply" from "this was never looked at". The audits that **do** apply here are
gates 2, 7, 8 and 9, and section 9 records their outcomes.

### 4.5 The contract a future row would inherit

Recorded so that anyone adding a row to this file knows what it must satisfy
without leaving the directory. All of it is stated normatively in the tree-scope
contract and is cited here, not redefined:

- **Width.** Exactly 50 bytes per physical line (section 4.2). The loaders reject
  any other length outright.
- **Line ending.** **LF only**, one trailing newline once a record exists, and no
  blank lines. `tests/fixtures/README.md` L160-L162 names `discgrp` among the LF
  seeds and its section 8 table at L677 lists it as LF; the source seed measures
  zero carriage returns.
- **`FILLER`.** Twenty-eight ASCII `'0'` (0x30) at offset 22, **not spaces**, and
  copied byte for byte from the source row rather than synthesised from the
  padding rule. This tree carries two opposite `FILLER` regimes and **this domain
  is the ASCII-`'0'` one**, measured uniform across all 51 seed rows; the house
  master documents the same regime for the `tcatbal` seed at L432-L435. The
  tree-scope contract's section 5.3 records why applying the generic
  space-padding rule here would produce a different fifty bytes that no length
  check would catch.
- **`DIS-INT-RATE`.** Zoned decimal with sign overpunch: `{ABCDEFGHI` positive,
  `}JKLMNOPQR` negative, the **position is the digit value**, the overpunch
  replaces the **low-order digit only**, and `V` occupies no byte so **no literal
  `.` is stored**. The house master's own worked vector at L230-L234 is
  `15.00` becoming `00150{`, attributed there to the `DIS-INT-RATE` of the first
  `discgrp` row. Only three raw values occur across the 51 seed rows -- `00000{`,
  `00150{` and `00250{` -- and any other needs a stated reason in its scenario
  README.
- **`DIS-TRAN-CAT-CD`.** Unsigned `9(04)`, plain digits, **no** overpunch. A
  trailing letter is a sign byte only in a signed field, so overpunch decoding
  must never be applied to it.
- **Money stays fixed point.** The migration plan's Rule T3 forbids encoding the
  rate as a float-looking literal anywhere along its path: `NUMERIC(6,2)` in SQL,
  `BigDecimal` at scale 2 in Java, `Decimal` in Python, and a JSON **string** on
  the wire. The rate reaches the accrual through
  `(TRAN-CAT-BAL * DIS-INT-RATE) / 1200`, so an inexact rate is an inexact
  accrual.

The loader behaviour this scenario probes is the same one that would police such
a row. Per `tests/fixtures/README.md` L144-L151, `record_codec.py` and
`vsam_loader.py` reject any off-length row and do **not** pad, truncate, or
silently drop blank lines, on the stated ground at L150-L151 that *"a malformed
monetary record must never be silently coerced into a well-formed-looking one."*
The Java counterpart is fail-closed in the same way: `FixedWidthCodec` validates
record length **before reading any field** and raises `RecordLengthException`
naming the layout, the expected `reclen` and the actual length.

### 4.6 Provenance: no attestation is owed in this domain

The house obligation at `tests/fixtures/README.md` section 9.1 (L695-L719) makes
the synthetic-data attestation conditional on *"whenever the scenario carries PAN
or identity-shaped data."* **No attestation is owed here, and that is stated
positively rather than left to silence.** `DIS-ACCT-GROUP-ID` is a
disclosure-group classifier -- the seed's values are `A000000000`, `DEFAULT` and
`ZEROAPR` -- and **not an account number**. There is no primary account number,
no card verification value, no national identifier and no identity-shaped field
anywhere in `DIS-GROUP-RECORD`. With a zero-byte file the point holds doubly:
there are no bytes to attest to. The tree-scope contract reaches the same
conclusion for this domain at its section 8.6, and says so for the same reason --
silence is indistinguishable from oversight.

---

## 5. Failure modes these bytes can produce

Assumptions: the failure modes below are the ones a consumer of *these* bytes can
hit, which is the Rule 1 exceptions element (L21, "where applicable") applied to
a fixture rather than to a function. In this scenario that element is not
incidental: **the failure is the subject**, and section 2.1 is where it is
specified.

- A file rewritten by an editor that appends a final newline to an intentionally
  zero-byte fixture becomes a 1-byte file holding one zero-length record. It then
  fails fixed-width parsing instead of exercising the empty path, so the scenario
  stops testing what it is named for while still appearing to be present.
- A zero-byte resource is the one a build's resource-copy step is most likely to
  drop. Fixtures reach their consumers from the **test classpath**, so a file
  that failed to be packaged must fail a test rather than be silently read from
  source; both consumers named in section 6 raise rather than skip when a listed
  fixture cannot be resolved.
- Substituting a `.gitkeep`, a placeholder comment or a single space for the empty
  file makes it non-zero-byte and destroys the scenario, without any length check
  necessarily objecting -- a one-byte file is simply a wrong-length record.
- A row added later at any width other than 50 mis-aligns every field after the
  first. The symptom is a plausible-looking wrong value rather than an exception
  wherever length is not checked, which is why section 4.2 states the width and
  section 4.5 states the rest of the contract.
- A row added later with CRLF endings shifts every offset and every record
  boundary by one byte per line.

---

## 6. Consumer

**These fixtures are decode, round-trip and business-rule vectors. They are not
database seeds.** The distinction matters here more than anywhere else in the
tree, because a careless reading of "empty" would suggest this file empties
something.

**A zero-byte fixture does not empty the database.** The sibling
`services/reference-service/src/test/resources/application-test.yml` sets
`spring.flyway.schemas: reference` with `create-schemas: true`, so both
`V1__reference.sql` and `V2__seed_reference.sql` run against a bare
Testcontainer and **all 51 `reference.disclosure_groups` rows -- including the 17
mandatory `'DEFAULT   '` rows -- are already present** in any test that talks to
the database. This file changes none of that. It is read as bytes.

Consistency targets, read here but authored elsewhere:
`V1__reference.sql` declares `reference.disclosure_groups` with
`acct_group_id CHAR(10)`, `tran_type_cd CHAR(2)`, `tran_cat_cd CHAR(4)`,
`interest_rate NUMERIC(6,2)` and the composite primary key
`(acct_group_id, tran_type_cd, tran_cat_cd)`; `V2__seed_reference.sql` seeds all
51 rows as three group ids times the same 17 pairs.

The byte consumers, all in
`services/common-lib/src/main/java/com/carddemo/common/codec/`:

- **`CopybookLayout.java`** -- the registered `DISGROUP` layout, `reclen` 50,
  `key_length` 16, `key_offset` 0. Geometry is asked for, never hard-coded.
- **`ZonedDecimalCodec.java`** -- the `DIS-INT-RATE` overpunch decode, with
  `decodePreservingSign` and `encodePreservingSign` available where the exact
  sign byte of a zero value must survive a round trip. Unreached by this
  scenario, which has no rate field to decode.
- **`FixedWidthCodec.java`** -- field composition plus the fail-closed
  record-length validation described in section 4.5.

**Oracle support, and why this domain is the reference one.**
`tests/helpers/record_codec.py` declares `DISGROUP_LAYOUT` at L1118 and registers
it in `LAYOUTS` at L1352, so a `discgrp` fixture **can** be cross-checked against
the existing Python parity oracle in addition to the Java codecs. That is **not**
true of `trantype` or `trancatg`: the Python registry carries no `TRANTYPE` key
and no `TRANCAT` key at all, which the tree-scope contract establishes at its
section 11.2 and which `CopybookLayout` records on the Java side by registering
`DISGROUP` as oracle-supported while marking `TRANTYPE` and `TRANCAT` as not.
`disclosure_group` is therefore the domain in this tree whose bytes are checkable
against two independent implementations, which is what makes it the reference
domain for the other four. The point still holds in this scenario, where the
cross-check is the degenerate one: both implementations must agree that zero bytes
yield zero records.

The executable consumers, both under
`services/reference-service/src/test/java/com/carddemo/reference/fixtures/` and
both run by Surefire, name this fixture explicitly:

- **`ReferenceFixtureContractTest`** lists
  `disclosure_group/empty_input/discgrp.txt` in its zero-byte parameter set at
  L119 and asserts the geometry the tree measures.
- **`ReferenceFixtureTest`** asserts, in
  `anEmptyFixtureIsZeroBytesAndDecodesToNoRecord`, that the file is empty
  ("section 6.5: empty means zero bytes"), that no record can be read from it,
  and that decoding a zero-length image raises
  `FixedWidthCodec.RecordLengthException` because *a zero-length image is a
  length failure, not an empty field map* (L101-L105). This fixture appears in
  its parameter sets at L407 with `reclen` 50 and record count `0`, and again at
  L426.

Availability status for the rest, stated so nothing here reads as a claim about
what exists: the `*ServiceTest`, `*ControllerTest` and `*RepositoryIT` kinds that
the tree-scope contract names as consumers at its section 9.1 are **not present
in that package**, so no unit or container-backed test asserts the abend contract
of section 3 today. They are named as the intended consumers and nothing more.
Note also that the abend of section 3 belongs to a COBOL program that this tree
does not run: the assertable contract from these bytes alone is emptiness and the
length failure, and the abend chain is the specification those assertions exist
to protect.

**The bytes are the contract**, in the direction the tree-scope contract states
at its section 9.2: a consumer's expected value is whatever this file encodes, so
a fixture that is wrong does not fail -- it produces a green test that proves
nothing. A disagreement is therefore resolved by reading the bytes, not by
editing them.

---

## 7. Why these bytes and not others

Assumptions: the choices below are properties of the **fixture** rather than of
the rule it exercises, so they are recorded here rather than in section 2. Each
one is a decision a later author could reverse without any test failing, which is
why the reason sits beside it. Under Rule 1 L40, leaving a non-obvious choice
undocumented when a reasonable alternative exists is a forbidden pattern, and for
each item below the alternative is named rather than merely implied.

- **Zero bytes, not a file containing no lines.** The house master requires the
  author to resolve this per scenario: its section 7 (L657-L661) defines an
  `empty_input` scenario as a *"present but empty (0-record) primary input file"*
  and directs that the author **document per scenario** whether that is a
  **0-byte** file or a file **containing no lines**, because *"both are 'empty'
  but the loader/codec must be told which"*.

  Alternatives Considered: the "no lines but non-zero bytes" form the house
  master itself offers. It is **not available** in this tree, and the ground is
  concrete rather than stylistic: such a file is a blank-line file, a blank line
  is a **zero-length record**, and the enforcement note at
  `tests/fixtures/README.md` L144-L151 records that the loaders do **not**
  silently drop blank lines and that *"the only input treated as 'empty' is a
  genuinely zero-byte dataset (0 bytes, no records)."* A one-byte file therefore
  fails fixed-width parsing against a 50-byte layout instead of exercising the
  empty path. Choosing 0 bytes satisfies that enforcement; documenting the choice
  in this paragraph satisfies the house master's own per-scenario obligation, and
  the tree-scope contract's section 6.5 records the same ruling at tree scope.

  Assumptions: this rests on an external contract rather than on preference --
  the exact-length behaviour recorded for `record_codec.py` and `vsam_loader.py`
  at `tests/fixtures/README.md` L144-L151, mirrored on the Java side by
  `FixedWidthCodec.RecordLengthException`, which rejects any image that is not
  exactly the declared 50 bytes. If that contract ever tolerated blank lines, the
  ruling would need to be revisited rather than merely re-stated, because a
  1-byte file would then no longer fail and the choice between the two forms
  would stop being forced.

  The measured house precedent agrees in **every** domain that carries the
  scenario: `tests/fixtures/posting/empty_input/dailytran.txt` is 0 bytes, all
  four files in `tests/fixtures/provisioning/empty_input/` are 0 bytes, and
  `tests/fixtures/statement/empty_input/trnxfile.txt` is 0 bytes. So does this
  tree, where five of the eighteen record files are genuinely zero-byte, one per
  `empty_input` scenario.

- **Only the file under test is emptied.** That is the house pattern -- the
  posting and statement scenarios each keep their other inputs fully populated
  while emptying one. Here the domain authors exactly one data file, so the whole
  scenario **is** that one emptied file, and there is no second input whose
  population could blur which read produced the outcome.

- **Present rather than omitted.** Section 2.3 gives the line numbers: an
  existing empty file opens at L272-L273 and fails on read at L446, whereas a
  missing dataset fails at the `OPEN` and abends from L284. Same abend code,
  different path, different diagnostic.

### 7.1 The `01|0005` contrast: the same abend from a fully populated file

There is a **narrower, in-data** total miss that the seed itself already
contains, and it is recorded here because it is what makes this scenario's claim
precise.

`app/data/ASCII/trancatg.txt` holds **18** `(type, category)` pairs and includes
`01|0005`, described there as `Interest Amount`, at its row 5.
`app/data/ASCII/discgrp.txt` holds only **17** pairs and contains **zero** rows
for `01|0005` in **any** of its three groups (`A000000000`, `DEFAULT   `,
`ZEROAPR   `) -- which is exactly why one file carries 18 pairs and the other 17.
Measured two ways: a set difference of the two pair lists yields exactly one
element, `010005`, and a direct search for `010005` in `discgrp.txt` returns zero
matches. A rate lookup for `01|0005` therefore misses the account's own group
**and** misses the `DEFAULT` fallback, reaching the **same L455 and L458 path
from a fully populated file**.

**The distinction, plainly: this scenario proves the total miss by EMPTINESS,
while `01|0005` proves it by the ABSENCE OF ONE PAIR WITHIN A COMPLETE FILE.**

Alternatives Considered: proving the same code path with a populated file plus
that one absent pair. It was available -- the seed supports it with no authoring
at all -- and the emptiness form was chosen because it negates the byte contract
**wholesale rather than in one cell**, so the scenario also exercises the
zero-byte definition of empty, the length-failure behaviour of the codec, and the
resource-packaging path for a zero-byte classpath entry. The two forms are
complementary rather than redundant: one is the strongest possible statement of
"no rows anywhere", the other the narrowest possible statement of "no row for
this key".

**No separate scenario directory is created for `01|0005`.** The property is
already carried at tree scope by `../../README.md` section 6.4, and it appears
here only as the contrast. That section also records it as an **observed property
of the baseline data** and states that it is not a defect -- a framing this file
keeps: the baseline data omits the pair, the migrated implementation reaches the
`'DEFAULT'` rows that `V2__seed_reference.sql` seeds, and any divergence is
documented in the migration's traceability record rather than asserted here.

---

## 8. Hazards and choices the second derivation recorded

Assumptions: these bytes were derived twice, independently, and both records are
kept because each names hazards the other does not. The section above states why
the bytes are what they are; the paragraphs below state what a later edit of them
would break. Their labels are given unemphasised, as
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md)
requires -- its L215-L226 enumerate four load-bearing properties of the one
permitted form (plural, unparenthesised, colon retained, no emphasis markup) and
record that each has actually been written wrongly at some point. Every labelled
rationale in this file is therefore written in that single form, so one literal
string search finds all of them; the wrong forms are named at that document
rather than reproduced here, so that a search for a wrong form does not return
this file.

- Assumptions: a missing `DEFAULT` group presents as an abend in the batch flow.
  A fixture that omits the group is therefore asserting a crash, and the
  tree-scope contract's section 6.3 requires the scenario README to say so. This
  one says so in sections 2.1, 3 and 3.2, with the abend code and the diagnostic
  sequence rather than as a general statement.
- Assumptions: the layout table in section 4.2 is reproduced here **because this
  directory's data file is empty**, so a reader has no row from which to infer
  the geometry and needs to know what is absent without leaving the file. It is
  framed as a derived index citing a copybook line per field, which is how the
  tree-scope contract characterises its own tables at section 5.8, and it defers
  to `CVTRA02Y.cpy` and to `CopybookLayout` on any disagreement. A scenario that
  ships a populated row does not need the reproduction and should keep citing
  section 3.4 instead, so that the geometry has as few places to drift as
  possible.
- Alternatives Considered: a file holding only the `ZEROAPR` group, whose 17 seed
  rows all carry a rate of `00000{`, decoding to `0.00`. Rejected on arithmetic
  as much as on intent: such a file measures `17 x 50 + 17 = 867` bytes, so it is
  emphatically **not** empty and the tree's definition of empty at section 6.5
  would not apply to it. The lookup would still miss and still fall back, and the
  fallback would still fail, but the scenario being exercised would be a
  populated single-group subset -- which is a shape the tree already carries.
  `ZEROAPR` is the natural zero-**rate** control group; it is not a substitute for
  an absent table, and conflating the two would leave the tree with no scenario
  that exercises a table holding no rows at all.
- Alternatives Considered: pairing this scenario with a golden-master file under
  a `tests/golden` mirror. Not created, on two independent grounds. The house
  mirroring rule at `tests/fixtures/README.md` section 2.1 (L92-L109) is
  conditioned on `tests/helpers/golden_compare.py` pairing scenarios by path, and
  its own availability note at L105-L109 records that the `tests/golden/**` mirror
  *"is not yet present on this branch"* and that the rule *"is not a claim that
  any golden file exists today"*; and
  `tests/fixtures/statement/empty_input/README.md` section 3 independently
  records for the analogous abend scenario that *"the correct oracle for an abend
  is not a statement golden but the observable abend contract itself."* The
  tree-scope contract's section 6.7 rules the same way for this tree, and its
  section 8.5 records golden mirroring as one of the house add-a-scenario steps
  that does **not** transfer here.

---

## 9. Validation gates for this directory

The tree-scope contract's section 13 states ten gates. Their outcomes for this
directory:

| Gate | Outcome here |
|---|---|
| 1. Length | **Not applicable** -- zero lines (section 4.4) |
| 2. Line-ending | **Pass** -- zero carriage returns; no trailing newline, which section 4.3 records as the zero-byte exception |
| 3. Fill | **Not applicable** -- no `FILLER` region exists (section 4.4) |
| 4. Overpunch | **Not applicable** -- no rate field exists (section 4.4) |
| 5. Verbatim | **Not applicable** -- no description field in this record type |
| 6. Discriminator | **Not applicable** -- this scenario claims a total miss, not that the fallback resolved a rate; the discriminator at type `07`, category `0001` belongs to `default_fallback` |
| 7. README completeness | **Pass** -- this file, carrying all four house scenario items and all four Rule 1 elements |
| 8. Prose | **Pass** -- zero non-ASCII bytes, verified with `LC_ALL=C grep -nP '[^\x00-\x7F]'`, and every rationale carries a concrete citation |
| 9. Reference-integrity | **Pass** -- nothing under `app/**`, `tests/**`, `scripts/**` or `samples/**` is modified |
| 10. Live check | Delegated to the reactor build the tree-scope contract commands |

The emptiness audit is the load-bearing one and is stated with its measurements
in section 4.1.1.

**How this file discharges user-specified Rule 1.** The tree-scope contract's
section 8.4 maps the four house scenario items onto Rule 1's four docstring
elements, and this file satisfies both readings at once:

| Rule 1 element | Where |
|---|---|
| Purpose (L18) | Sections 1 and 2, including the central WHY at 2.1 |
| Parameters (L19) | Section 4: 0 bytes, 0 records, no trailing newline, plus offset, `PICTURE`, fill character and copybook line per field at 4.2 |
| Return values (L20) | Section 3 -- `ABCODE` **999** as a specific value, with the ordered diagnostic sequence -- plus the consumers and what they assert, in section 6 |
| Exceptions (L21) | Section 5, and section 2.1, since in this scenario the failure is the subject |

Rule 1 L43 is the operative gate and it is conjunctive: *"Code missing either
fails review."* Its triad is literally purpose, parameters and return values, so
the exceptions element rests on L21's *"where applicable"* rather than on L43,
and here it is plainly applicable. L43 is also what hardens L29's *"should"* to
*"must"* for the rationale requirement, which is why the labelled justifications
above are obligations rather than courtesies. This is a review gate with no
mechanical fallback: `config/checkstyle/checkstyle.xml` scopes its `Checker` to
`fileExtensions="java"`, so no file in this directory is ever scanned, and the
`config/checkstyle/suppressions.xml` entry covering `src/test/resources/fixtures/`
permits a path without authoring content or discharging any obligation.

**Labels: four in the canon, three usable.** Rule 1 L31-L34 names four rationale
categories, and this file uses `Alternatives Considered:`, `Assumptions:` and
`Trade-offs:` -- plural, ASCII, unemphasised, with the trailing colon as part of
the label. The fourth, `Refactoring Rationale`, is **factually unavailable**
rather than merely unused: L32 scopes it by its own definition to *replacing
existing code*, and everything in this tree is newly authored beside an untouched
baseline and replaces nothing. Its precondition is never met, so it appears
nowhere above; where a choice departs from a house default the correct label is
`Alternatives Considered:`. This is the settled convention of the tree, recorded
at `../../README.md` section 2.2.
