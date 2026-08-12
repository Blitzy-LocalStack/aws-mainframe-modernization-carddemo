# `batch_reference_update/add_record` - the `'A'` dispatch branch, over two type codes the seed does not hold

Two 53-byte maintenance records, both carrying action byte `'A'`, each inserting a
transaction type that no seeded row already occupies. This is the clean-insert
control for the `COBTUPDT` batch reference-update flow.

> **Why this README exists.** `trtype-update.txt` is a fixed-width flat file, so
> every byte position is meaningful: a `#` or `//` annotation would be *data* rather
> than a comment, and the physical line carrying it would be the wrong length and
> would be rejected at load rather than ignored. None of the four documentation
> formats Rule 1 L22 names - JSDoc, Javadoc, Python docstrings, XML comments - can be
> carried by such a file, so this document is the only conforming place the
> justification for those bytes can live. Rule 1 L27 requires that justification to
> sit adjacent to what it explains, which is why it is in the scenario's own
> directory rather than aggregated upward.
>
> **The tree-wide byte contract is deliberately not reproduced here.** The offset
> base, the complete layout tables, the two `FILLER` fill regimes, the zoned-decimal
> sign-overpunch tables, the line-ending ruling, the four canonical rationale labels
> and the validation gates are all single-sourced in
> [`../../README.md`](../../README.md), whose section 3.6 tables this record and
> whose section 8.4 sets the shape of this document. What follows summarises only
> what is specific to this scenario. Copying those tables down into each scenario
> directory would create as many places for the geometry to drift as there are
> scenarios; where this document and that one ever disagree, that one governs and
> this one is wrong.

The four sections below are the house four-element scenario contract, and each
discharges one element of the docstring requirement Rule 1 L15 imposes: intent and
rule are its **Purpose** (L18), the byte table is its **Parameters** (L19), the
outcome together with its consumer is its **Return values** (L20), and the byte-level
failure modes are its **Exceptions** (L21).

---

## 1. Scenario intent - Purpose

The clean-insert case: a sequential input file in which every record selects the
`'A'` add action and every record names a transaction type that does not already
exist, so each insert can only succeed. Nothing here is a boundary, a collision or a
reject. That is the point of it - the two sibling scenarios in this domain are only
interpretable against a control in which the dispatch does exactly what it says.

The type codes are what make the scenario non-vacuous rather than merely valid, and
the reason is recorded under `Assumptions:` in section 6.

---

## 2. The exact business rule it exercises - Purpose

### 2.1 The dispatch

`1003-TREAT-RECORD` in
[`COBTUPDT.cbl`](../../../../../../../../app/app-transaction-type-db2/cbl/COBTUPDT.cbl)
spans L109 to L130. Its `EVALUATE INPUT-REC-TYPE` opens at L110 and closes at L129,
and it reads byte 0 of the record and nothing else. This scenario is its `WHEN 'A'`
arm at L111, which displays a progress line at L112 and performs `10031-INSERT-DB`
at L113.

### 2.2 The insert, and why it pins the byte mapping

`10031-INSERT-DB` occupies L132 to L164. Its embedded statement at L137 to L148
inserts into `CARDDEMO.TRANSACTION_TYPE`, taking its two column values from the host
variables `INPUT-REC-NUMBER` and `INPUT-REC-DESC`.

That statement is why the byte table in section 4 is a contract rather than a
convention: the record's 0-based bytes 1 to 2 *become* the type code column and its
0-based bytes 3 to 52 *become* the description column. A byte miscounted in the
fixture is not a formatting blemish, it is a different row inserted into a keyed
table.

The outcome arms are at L149 onward. A zero return from the database takes the
success arm at L152 to L153. A negative return takes the arm at L154 to L162, which
concatenates two literals and the edited return code held in `WS-VAR-SQLCODE`
(declared `PIC ----9` at L65) into `WS-RETURN-MSG` (`PIC X(80)`, L61) and performs
`9999-ABEND`. Neither of those arms is reached by this scenario, which exists to take
the success arm twice.

### 2.3 What the two nearest dispatch neighbours do instead

These are the arms a reader will otherwise wonder about, because they sit either side
of the one exercised here and neither is a database action:

- **`WHEN '*'` at L120** displays a line at L121 and does nothing else. It touches no
  table at all. That row is still exactly 53 bytes and still data to any
  length-checking loader - only the program reads byte 0 as a comment marker.
- **`WHEN OTHER` at L122** builds a message at L124 and performs `9999-ABEND` at
  L128. The paragraph name is misleading and worth reading before relying on it:
  `9999-ABEND` at L230 to L233 displays the message, moves 4 to `RETURN-CODE` and
  exits, with no `STOP RUN` on that path. It is a warn-tier soft reject that returns
  to the read loop at L93 to L96, not a termination. The sibling
  [`../invalid_type_abend`](../invalid_type_abend/README.md) is the
  scenario for that arm.

Neither arm is exercised here. Both are named so that this scenario's success path is
distinguishable from the two things it is most easily confused with.

---

## 3. Expected outcome - Return values

### 3.1 The result

Both records dispatch to the add action. Each yields one successful insert into
`reference.transaction_types`: one row keyed `08` and one row keyed `09`. The seven
rows the migration seeds, keyed `01` through `07`, are untouched, so the table holds
**nine** rows after the run. The run takes the success arm at L152 to L153 on both
records and reaches no reject and no warn tier, so its return code stays 0.

`reference.transaction_categories` is not affected, and that is a property of the
program rather than of the fixture: L54 of `COBTUPDT.cbl` includes the transaction
type table declaration and nothing else, so the program has no category declaration
to write through. A consumer asserting any change to the category table would be
asserting something this input cannot cause.

### 3.2 Consumers, with availability stated rather than implied

- **The byte-level geometry and field values are asserted.**
  `ReferenceFixtureContractTest` parameterises this file with a record length of 53
  and a record count of 2, and `ReferenceFixtureTest` decodes both rows and asserts
  that each carries `A` in `INPUT-REC-TYPE`, that the first carries `08` with the
  description `Fee Assessment`, and that the second carries `09`. Both classes live
  in `com.carddemo.reference.fixtures` and resolve this file from the test classpath
  as `fixtures/batch_reference_update/add_record/trtype-update.txt`, raising rather
  than skipping when a name is absent.
- **The behavioural consumer is declared and is absent from the tree.**
  `ReferenceBatchUpdateService` is the transcription of `1003-TREAT-RECORD`, named as
  such in the charter of `com.carddemo.reference.service`; its declared entry points
  accept the maintenance records as a stream of contiguous 53-byte records and
  dispatch one record at a time, and the add action is the branch these bytes select.
  That package holds only its `package-info.java`, so the class is not present here
  and no test of it exists. It is named as the declared consumer of these bytes, not
  as an observed caller.
- **What that consumer is not.** It is a plain service method over a record stream.
  There is no batch job, no step, no job repository and no run ledger behind this
  input, and describing one would attribute machinery to a flow whose baseline is a
  single sequential read loop at L93 to L96.

**The bytes are the contract.** A consumer's expected value is whatever these 108
bytes decode to, so a fixture that is wrong does not fail - it produces a passing
assertion that proves nothing. A disagreement between a consumer and this file is
settled by reading the bytes, not by editing them.

---

## 4. Fixture bytes and governance - Parameters

### 4.1 The record, field by field

Normative source: `01 WS-INPUT-REC.` at L71 of `COBTUPDT.cbl`. Offsets are **0-based**,
matching [`../../README.md`](../../README.md) section 3.1.

| Declared at | Field | `PICTURE` | Offset | Length | Fill |
|---|---|---|---:|---:|---|
| L72, with L73 `VALUE SPACES.` | `INPUT-REC-TYPE` | `X(1)` | 0 | 1 | single byte, nothing to pad |
| L74, with L75 `VALUE SPACES.` | `INPUT-REC-NUMBER` | `X(2)` | 1 | 2 | right-pad with spaces |
| L76, with L77 `VALUE SPACES.` | `INPUT-REC-DESC` | `X(50)` | 3 | 50 | right-pad with spaces |

`1 + 2 + 50 = 53`.

Two reading hazards in that declaration are worth naming, because a reader who
misses either will derive the wrong record:

- **`PIC` and `VALUE` sit on separate physical lines for all three fields** - L72 with
  L73, L74 with L75, L76 with L77. A layout extractor that assumes one declaration
  per line mis-reads this record.
- **The record is bytes 0 through 52 and nothing else.** `COBTUPDT.cbl` carries
  legacy sequence numbers in source columns 73 to 80, for example `00592033` on L72.
  Identification-area content is an artifact of the source file's own format and must
  never leak into a derived record.

`WS-INPUT-REC` is normative because L101 reads `INTO WS-INPUT-REC` and the dispatch at
L110 inspects that copy. A byte-identical 53-byte group is also declared on the file
side, as `01 WS-INPUT-VARS.` at L40 to L46 under `FD TR-RECORD RECORDING MODE F.` at
L39; it is mentioned only because it independently confirms fixed-length 53-byte
records, and it is not the group the dispatch reads.

### 4.2 The file, and arithmetic that makes the check reproducible

| File | Record width | Records | Line ending |
|---|---|---|---|
| `trtype-update.txt` | 53 bytes | 2 | LF, exactly one trailing newline, zero carriage returns |

```text
bytes = rows x 53 + rows  =  2 x 53 + 2  =  108
```

So `wc -c` reads 108 and `wc -l` reads 2, and the two together are a complete
geometry check that needs no decoder. The rows are `A` then `08` then
`Fee Assessment` right-space-padded out to byte 52; and `A` then `09` then
`Chargeback` padded the same way.

### 4.3 The width is corroborated twice, from mutually independent artifacts

The 53 above is not asserted from one source:

1. **The `PICTURE` arithmetic** at `COBTUPDT.cbl` L71 to L77, summed in section 4.1.
2. **The JCL driver's own comment block** at
   [`MNTTRDB2.jcl`](../../../../../../../../app/app-transaction-type-db2/jcl/MNTTRDB2.jcl)
   L11 to L18, which documents column 1 as the action code with the domain
   `A`, `D`, `U`, `*`, columns 2 to 3 as the transaction type described as a numeric
   value, and columns 4 to 53 as the description. Column 53 is the last column
   documented, so the record is 53 bytes - reached without touching a `PICTURE`
   clause.

Two agreeing derivations from unrelated files is what makes the width checkable
rather than merely stated. **The JCL block is 1-based; the table in section 4.1 and
the whole of [`../../README.md`](../../README.md) are 0-based.** The bases are called
out because the baseline itself uses both, and leaving one implicit is a guaranteed
one-byte error on every field rather than a stylistic preference.

Worth recording positively: **the 53-byte regime is new to this repository.** The
house record-length enumeration at `tests/fixtures/README.md` L140 to L142 lists 350,
300, 150, 50, 500 and 80, and 53 is not among them. No existing fixture width can be
copied here, which is exactly why this scenario carries the derivation.

### 4.4 What governs the fill: this record has no `FILLER` at all

Stated positively, because a reader arriving from the two opposing `FILLER` regimes in
[`../../README.md`](../../README.md) section 5.3 will otherwise ask which one applies
here. **Neither applies, because there is no `FILLER` to fill.** `1 + 2 + 50 = 53`
accounts for the whole record, all three fields are `PIC X(n)`, and the program's own
`VALUE SPACES` on each of them at L73, L75 and L77 settles the pad character. The
generic right-pad-with-spaces rule therefore governs this record completely.
`FILLER` dropped: **none**. No zoned decimal, no sign overpunch and no packed field
arises anywhere in this domain, so none of that machinery is in play.

For contrast, and to show that the distinction is real rather than pedantic: the
60-byte seed record `CVTRA03Y` *does* declare `FILLER PIC X(08)`, and in
`app/data/ASCII/trantype.txt` that region at 0-based bytes 52 to 59 is **zero-filled**
with the eight ASCII characters `00000000`. That is a live instance of the regime that
does **not** apply to this file.

### 4.5 No synthetic-data attestation is owed here

Also stated positively, because an unexplained absence is indistinguishable from an
oversight. The house attestation obligation at `tests/fixtures/README.md` section 10.3
is scoped to scenario directories holding primary account or identity data - card
number, customer name, address, national identifier, government-issued identifier,
date of birth, telephone number or credit score. `WS-INPUT-REC` is an action byte, a
two-character reference code and a description: **it carries none of those eight
categories**, so the obligation does not attach to this directory and its absence is
deliberate. [`../../README.md`](../../README.md) section 8.6 records the same scoping
across all five domains of this tree.

---

## 5. Failure modes these bytes can produce - Exceptions

Assumptions: these are the failure modes of the *bytes*, which is the Exceptions
element of Rule 1 L21 applied to a fixture rather than to a function. Every one of
them is a way this file can be edited into something that still looks plausible.

- **A one-byte miscount shifts every field after the miscount.** Off-length rows are
  rejected outright - the loaders do not pad a short row, do not truncate a long one
  and do not drop a blank line - so the immediate symptom is a hard failure at
  decode. The dangerous variant is a *compensated* miscount that keeps the row at 53
  bytes while moving a boundary: that one loads cleanly and inserts a different row.
  Section 4.1 states the offsets so the boundary can be checked rather than assumed.
- **A blank line is a zero-length record, not a spacer.** It fails fixed-width
  parsing (`tests/fixtures/README.md` L181 to L184). The one-newline-per-record rule
  is what keeps `wc -l` equal to the record count.
- **Only a genuinely zero-byte file counts as empty** (`tests/fixtures/README.md`
  L144 to L151). A 108-byte file is never treated as empty by anything, so this
  scenario cannot silently degrade into the empty case; that case is
  [`../empty_input`](../empty_input/README.md), whose file is 0 bytes.
- **A rewrite to CRLF adds a byte to every record.** The carriage return would be
  absorbed into the trailing field, pushing each row to 54 bytes and breaking the
  arithmetic in section 4.2.
- **A type code moved into the seeded range turns the scenario into its opposite.**
  See the first item in section 6: the codes are the whole reason the inserts are
  clean, and nothing about the file name would reveal the change.

---

## 6. Why these bytes and not others

Each item below is a decision a later author could reverse without any assertion in
section 3.2 failing, which is precisely why Rule 1 L40 requires the reason to be
written beside it.

Assumptions: the seeded state is what makes `08` and `09` the only defensible
codes, and it is the most consequential fact in this document.
`application-test.yml` runs Flyway over both migrations against a bare PostgreSQL
container, with `schemas: reference` and `create-schemas: true`.
`V1__reference.sql` declares `reference.transaction_types.type_cd` as `CHAR(2)` under
`CONSTRAINT pk_transaction_types PRIMARY KEY (type_cd)`, with
`description VARCHAR(50) NOT NULL`. `V2__seed_reference.sql` then seeds exactly seven
rows, `01` through `07`, from `app/data/ASCII/trantype.txt`. The consequence is
direct: **an `'A'` row carrying any code in `01` through `07` is a duplicate-key case,
not a clean insert.** `08` and `09` are absent from that seed, and both are numeric,
which is what `MNTTRDB2.jcl` L16 documents columns 2 to 3 to be. If a later edit moves
these codes into the seeded range, this scenario silently becomes a duplicate-key test
while still being named `add_record`, and section 3.1's nine-row outcome becomes
unreachable.

Assumptions: the action byte is UPPERCASE `A` on both rows, and the case is load
bearing rather than stylistic. A COBOL `EVALUATE` compares the bytes of the field
against each literal, so the `WHEN 'A'` arm at L111 is satisfied only by the uppercase
byte; a lowercase `a` falls through every named arm to `WHEN OTHER` at L122. The
reasonable-looking alternative therefore does not weaken this scenario, it converts it
into the sibling reject scenario while leaving the directory name reading
`add_record`. That lowercase byte is the whole subject of
[`../invalid_type_abend`](../invalid_type_abend/README.md), which is why
these two rows and those two are one character apart in byte 0 and describe opposite
outcomes.

Assumptions: the descriptions for `08` and `09` are author-composed, and that is
correct rather than a lapse. The verbatim-sourcing rule binds values that *appear*
in the reference data; `08` and `09` appear nowhere in `trantype.txt`, so there is no
seed value to preserve and nothing is being paraphrased. Where a scenario in this tree
does carry a code the seed holds, the description is lifted from that file
character for character - `01` `Purchase`, `02` `Payment`, `03` `Credit`, `04`
`Authorization`, `05` `Refund`, `06` `Reversal`, `07` `Adjustment`, mixed case
included. Such a lift needs no re-padding and cannot pick up the seed's line ending:
`trantype.txt` is the 60-byte `CVTRA03Y` record, so a seed row's description already
occupies exactly 50 right-space-padded bytes at 0-based 2 to 51, while the carriage
return on its first six rows sits at 0-based byte 60, well past the description's last
byte. The two composed descriptions here follow that same mixed-case shape for one
concrete reason rather than for tidiness: `description` is `VARCHAR(50)`, so an
uppercase spelling would sit in the same column as seven mixed-case neighbours, and any
assertion comparing a composed row against a seeded one would then be comparing two
different conventions.

Alternatives Considered: taking a description from the Db2 control card
`app/app-transaction-type-db2/ctl/DB2LTTYP.ctl`, or its category counterpart
`DB2LTCAT.ctl`. Superficially attractive, because those files are the reference-data
loader for the very extension tree this flow belongs to. Rejected on two concrete
grounds: their values are UPPERCASE on every row, and `DB2LTTYP.ctl` L22 carries
`'REVERAL'` where the seed carries `Reversal`. `V2__seed_reference.sql` deliberately
seeds from the ASCII files instead, so a control-card-derived value would not match
the row seeded into the schema and would violate AAP Rule T8, which requires
user-visible strings to be carried across character for character. This is the
derivation mistake most likely to look reasonable here, because a reader looking for a
description for an unseeded code has nowhere else to reach, so the rejected source is
named by path rather than left unmentioned.

Alternatives Considered: the fixture file name. House fixtures are named for the
logical dataset they stand in for - `dailytran.txt`, `acctdata.txt`, `cardxref.txt` -
never for a program and never for a record layout. `trtype-update.txt` names the
stream: transaction-type maintenance records. The rejected candidates and their
consequences:

- **`inpfile.txt`**, the obvious candidate, after `//INPFILE DD DSN=INPFILE,DISP=SHR`
  at `MNTTRDB2.jcl` L27 and `ASSIGN TO INPFILE` at `COBTUPDT.cbl` L31. Rejected
  because `INPFILE` is a generic data-definition placeholder that identifies no
  particular stream; the name would not distinguish this input from any other
  program's input.
- **`mnttrdb2.txt`**, rejected because it names the job rather than the stream.
- **`COBTUPDT.txt`** and **`WS-INPUT-REC.txt`**, rejected outright: a fixture is named
  for neither a program nor a working-storage group.

The hyphenated form departs from the eight-character dataset-shaped house names on
purpose, and the reason is that **no eight-character dataset name exists for this
stream** - the only name the baseline supplies is the generic `INPFILE` above. The
departure is recorded so it is not read as carelessness. Every scenario directory in
this domain uses this same file name, so a consumer resolves one name per scenario.

Assumptions: off-length rows fail loudly, and that stance is inherited rather than
chosen here. `tests/fixtures/README.md` L150 to L151 states the reasoning directly:
a malformed record must never be silently coerced into a well-formed-looking one. The
rejected alternative - tolerating an off-length row or treating a blank line as no
record - would accept the row and shift every field after the error, producing a
fixture that loads cleanly and asserts the wrong values. That is why section 4.2
states the arithmetic rather than leaving the width to a decoder.

Assumptions: the house pre-sort step does not transfer to this domain, and saying so
is itself an obligation. `tests/fixtures/README.md` section 9.2 item 5 requires
indexed inputs to be pre-sorted by key for its indexed loader. `COBTUPDT.cbl` L31 to
L34 declares `ORGANIZATION IS SEQUENTIAL` with `ACCESS MODE IS SEQUENTIAL` and L101 is
a sequential read, and no COBOL loader consumes these fixtures at all. A fixture here
is therefore a plain sequence of 53-byte rows with no key-ordering requirement beyond
the scenario's own intent - `08` precedes `09` for readability, not for correctness.
An unexplained absence would read as an oversight, which is why the non-transfer is
stated instead of assumed. [`../../README.md`](../../README.md) section 8.5 records
which house steps transfer and which do not.

Trade-offs: two records rather than one. A single row would select the add branch
just as well and would halve the file. Two are used because L101 sits inside the
`PERFORM UNTIL` loop at L93 to L96, and a one-row file cannot distinguish a loop that
advances from a program that reads once and stops. The compromise accepted is 54 extra
bytes - a 53-byte record plus its newline - and a second insert to account for in
section 3.1's row count.

Alternatives Considered: adding a duplicate-key scenario to this directory.
Rejected, because it is not a byte-level concern. In the target, inserting an existing
key raises PostgreSQL SQLSTATE 23505, whereas the baseline does not discriminate that
case at all: L154 of `COBTUPDT.cbl` is a single `WHEN SQLCODE < 0` arm that collapses
every negative return into the one diagnostic at L154 to L162. Discriminating it is a
behavioural divergence owned by the service that transcribes this paragraph, and it
belongs with that service's own tests, where the divergence can be documented against
the arm it departs from. The bytes needed to provoke it would be indistinguishable
from the bytes here apart from two characters, so a fixture directory is the wrong
place to carry the distinction.
